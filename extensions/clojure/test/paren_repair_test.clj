(ns paren-repair-test
  "Tests for the clojure_paren_repair tool (paren-repair namespace).
   Each test writes a temp file, calls paren-repair/execute, and asserts
   on the result map and the written file content."
  (:require [clojure.test :as t :refer [deftest is]]
            [clojure.string :as str]
            [babashka.fs :as fs]
            [paren-repair]))

;; ─── Helpers ───────────────────────────────────────────────────────────────

(def ^:private test-dir "target/paren-repair-tests")

(defn- ensure-test-dir! []
  (fs/create-dirs test-dir))

(defn- write-test-file!
  "Write BODY to a temp file under test-dir and return its path."
  [name body]
  (ensure-test-dir!)
  (let [path (str test-dir "/" name)]
    (spit path body)
    path))

(defn- read-test-file [path]
  (slurp path :encoding "UTF-8"))

;; ═══════════════════════════════════════════════════════════════════════════════
;; Parameter validation
;; ═══════════════════════════════════════════════════════════════════════════════

(deftest test-missing-file-path
  (let [result (paren-repair/execute {})]
    (is (:is-error result))
    (is (str/includes? (:content result) "file_path"))))

(deftest test-nonexistent-file
  (let [result (paren-repair/execute {:file_path "target/paren-repair-tests/does-not-exist.clj"})]
    (is (:is-error result))
    (is (str/includes? (:content result) "does not exist"))))

(deftest test-non-clojure-file
  (let [path (write-test-file! "notes.txt" "hello (unclosed")
        result (paren-repair/execute {:file_path path})]
    (is (:is-error result))
    (is (str/includes? (:content result) "Not a Clojure file"))))

;; ═══════════════════════════════════════════════════════════════════════════════
;; Repair behavior
;; ═══════════════════════════════════════════════════════════════════════════════

(deftest test-repairs-missing-paren
  (let [path (write-test-file! "missing.clj" "(defn foo [x]\n  (+ x 1\n")
        result (paren-repair/execute {:file_path path})]
    (is (not (:is-error result)))
    (is (str/includes? (:content result) "delimiter-fixed"))
    (let [content (read-test-file path)]
      (is (str/includes? content "(defn foo [x]"))
      (is (str/includes? content "(+ x 1)")))))

(deftest test-repairs-extra-paren
  (let [path (write-test-file! "extra.clj" "(defn foo [x] (+ x 1)))\n")
        result (paren-repair/execute {:file_path path})]
    (is (not (:is-error result)))
    (is (str/includes? (:content result) "delimiter-fixed"))
    (let [content (read-test-file path)]
      ;; the extra close is dropped
      (is (= "(defn foo [x] (+ x 1))" (str/trim content))))))

(deftest test-repairs-brackets-and-braces
  (let [path (write-test-file! "mixed.clj" "(let [x 1]\n  {:a x\n")
        result (paren-repair/execute {:file_path path})]
    (is (not (:is-error result)))
    (let [content (read-test-file path)]
      (is (str/includes? content "{:a x}")))))

(deftest test-no-changes-needed
  (let [path (write-test-file! "balanced.clj" "(def x 1)\n")
        result (paren-repair/execute {:file_path path})]
    (is (not (:is-error result)))
    (is (str/includes? (:content result) "No changes needed"))
    (is (= "(def x 1)\n" (read-test-file path)))))

(deftest test-format-false-keeps-formatting
  (let [path (write-test-file! "noformat.clj" "(defn foo[x](+ x 1\n")
        result (paren-repair/execute {:file_path path :format false})]
    (is (not (:is-error result)))
    ;; delimiters fixed but cljfmt not applied — no space added after foo
    (let [content (read-test-file path)]
      (is (str/includes? content "(defn foo[x](+ x 1))")))))

(deftest test-format-true-applies-cljfmt
  (let [path (write-test-file! "fmt.clj" "(defn foo[x](+ x 1\n")
        result (paren-repair/execute {:file_path path})]
    (is (not (:is-error result)))
    (let [content (read-test-file path)]
      (is (str/includes? content "(defn foo [x]")))))

(deftest test-returns-diff
  (let [path (write-test-file! "diff.clj" "(defn foo [x] (+ x 1\n")
        result (paren-repair/execute {:file_path path})]
    (is (not (:is-error result)))
    (is (some? (:details result)))
    (is (str/includes? (get-in result [:details :diff]) "+"))))

(deftest test-edn-files-supported
  (let [path (write-test-file! "config.edn" "{:a 1\n")
        result (paren-repair/execute {:file_path path})]
    (is (not (:is-error result)))
    (is (str/includes? (read-test-file path) "{:a 1}"))))

(deftest test-multiline-balanced-file
  (let [path (write-test-file! "multiline.clj"
                               "(defn process [items]\n  (map (fn [x]\n         (* x 2))\n       items))\n")
        result (paren-repair/execute {:file_path path})]
    (is (not (:is-error result)))
    (let [content (read-test-file path)]
      ;; file was balanced already — no changes
      (is (str/includes? (:content result) "No changes needed"))
      (is (= "(defn process [items]\n  (map (fn [x]\n         (* x 2))\n       items))\n" content)))))

;; ═══════════════════════════════════════════════════════════════════════════════
;; Hooks: write (pre, reject) + edit (post, warn)
;; ═══════════════════════════════════════════════════════════════════════════════

(deftest test-write-hook-blocks-unbalanced
  (let [result (paren-repair/on-tool-call
                {:tool-name "write"
                 :args {:path "src/foo.clj"
                        :content "(defn foo [x]"}})]
    (is (:block result))
    (is (str/includes? (:reason result) "Unbalanced delimiters in src/foo.clj"))
    (is (str/includes? (:reason result) "Write blocked"))))

(deftest test-write-hook-allows-balanced
  (is (nil? (paren-repair/on-tool-call
             {:tool-name "write"
              :args {:path "src/foo.clj"
                     :content "(defn foo [x] (+ x 1))"}}))))

(deftest test-write-hook-ignores-non-clojure
  (is (nil? (paren-repair/on-tool-call
             {:tool-name "write"
              :args {:path "src/foo.txt"
                     :content "(unbalanced"}}))))

(deftest test-write-hook-ignores-other-tools
  (is (nil? (paren-repair/on-tool-call
             {:tool-name "edit"
              :args {:path "src/foo.clj"
                     :content "(unbalanced"}}))))

(deftest test-write-hook-reports-expected-vs-opened
  (let [result (paren-repair/on-tool-call
                {:tool-name "write"
                 :args {:path "a.clj"
                        :content "(defn foo [x]"}})]
    (is (:block result))
    ;; precise: expected ')' to close '(' with opened-at location
    (is (str/includes? (:reason result) "expected ')' to close '('"))
    (is (str/includes? (:reason result) "opened at line"))))

(deftest test-edit-hook-warns-on-unbalanced-result
  (let [path (write-test-file! "edit-warn.clj" "(defn foo [x]")
        result (paren-repair/on-tool-result
                {:tool-name "edit"
                 :args {:path path}
                 :result {:content "edited"}
                 :is-error false})]
    (is (some? result))
    (is (str/includes? (:content result) "⚠️"))
    (is (str/includes? (:content result) "clojure_paren_repair"))
    (is (str/includes? (:content result) "edited"))))

(deftest test-edit-hook-silent-on-balanced-result
  (let [path (write-test-file! "edit-ok.clj" "(defn foo [x] (+ x 1))")
        result (paren-repair/on-tool-result
                {:tool-name "edit"
                 :args {:path path}
                 :result {:content "edited"}
                 :is-error false})]
    (is (nil? result))))

(deftest test-edit-hook-ignores-errors
  (let [path (write-test-file! "edit-err.clj" "(defn foo [x]")
        result (paren-repair/on-tool-result
                {:tool-name "edit"
                 :args {:path path}
                 :result {:content "failed"}
                 :is-error true})]
    (is (nil? result))))

(deftest test-edit-hook-ignores-non-clojure
  (let [path (write-test-file! "notes.txt" "(unbalanced")
        result (paren-repair/on-tool-result
                {:tool-name "edit"
                 :args {:path path}
                 :result {:content "edited"}
                 :is-error false})]
    (is (nil? result))))
