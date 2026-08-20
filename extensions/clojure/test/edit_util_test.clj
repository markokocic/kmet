(ns edit-util-test
  "Tests for the shared edit-util namespace."
  (:require [clojure.test :as t :refer [deftest testing is]]
            [clojure.string :as str]
            [babashka.fs :as fs]
            [edit-util :as util]
            [kmet.libs.edit-diff :as edit-diff]
            [rewrite-clj.node :as node]
            [rewrite-clj.parser :as p]
            [rewrite-clj.zip :as z]))

;; ═══════════════════════════════════════════════════════════════════════════════
;; File I/O
;; ═══════════════════════════════════════════════════════════════════════════════

(deftest test-slurp-utf8
  (fs/create-dirs "target/util-tests")
  (spit "target/util-tests/utf8.clj" "(def x 42)\n" :encoding "UTF-8")
  (is (= "(def x 42)\n" (util/slurp-utf8 "target/util-tests/utf8.clj"))))

(deftest test-spit-utf8
  (fs/create-dirs "target/util-tests")
  (util/spit-utf8 "target/util-tests/out.clj" "(def y 99)\n")
  (is (= "(def y 99)\n" (slurp "target/util-tests/out.clj" :encoding "UTF-8"))))

(deftest test-utf8-roundtrip
  (fs/create-dirs "target/util-tests")
  (util/spit-utf8 "target/util-tests/rt.clj" "(def greeting \"Привет\")\n")
  (is (= "(def greeting \"Привет\")\n" (util/slurp-utf8 "target/util-tests/rt.clj"))))

;; ═══════════════════════════════════════════════════════════════════════════════
;; Lint repair
;; ═══════════════════════════════════════════════════════════════════════════════

(deftest test-lint-repair
  (testing "returns [code false] — passthrough, no repair"
    (let [[code repaired?] (util/lint-repair "(+ x 1)")]
      (is (= "(+ x 1)" code))
      (is (false? repaired?)))))

;; ═══════════════════════════════════════════════════════════════════════════════
;; Diff
;; ═══════════════════════════════════════════════════════════════════════════════

(deftest test-diff-display-identical
  (is (nil? (edit-diff/generate-display-diff "hello" "hello"))))

(deftest test-diff-display-different
  (let [result (edit-diff/generate-display-diff "line1\n" "line2\n")]
    (is (string? result))
    (is (str/includes? result "-1 line1"))
    (is (str/includes? result "+1 line2"))))

;; ═══════════════════════════════════════════════════════════════════════════════
;; Formatting
;; ═══════════════════════════════════════════════════════════════════════════════

(deftest test-format-source-string
  (testing "formats malformatted Clojure"
    (let [formatted (util/format-source-string "(defn foo[x](+ x 1))")]
      (is (string? formatted))
      (is (str/includes? formatted "defn")))))

(deftest test-format-source-string-invalid
  (testing "returns original on parse error"
    (let [input "(defn foo ["]
      (is (= input (util/format-source-string input))))))

(deftest test-re-indent-to-column
  (is (= "foo\n  bar\n  baz"
         (util/re-indent-to-column "foo\nbar\nbaz" 3)))
  (testing "col 1 is no-op"
    (is (= "foo\nbar" (util/re-indent-to-column "foo\nbar" 1)))))

(deftest test-format-form-in-isolation
  (let [result (util/format-form-in-isolation "(defn foo[](let [x 1](+ x 2)))" 5)]
    (is (string? result))
    (is (str/includes? result "let"))))

(deftest test-project-fmt-opts
  (testing "finds cljfmt.edn walking up from the file's directory"
    (let [dir (fs/create-dir (fs/path "target" "fmt-config-test"))
          _   (spit (fs/file dir "cljfmt.edn")
                    "{:extra-indents {mydef [[:block 3] [:inner 1]]}}")
          clj (fs/file dir "sample.clj")]
      (spit clj "")
      (try
        (let [opts (util/project-fmt-opts (str clj))]
          (is (= [[:block 3] [:inner 1]]
                 (get (:extra-indents opts) 'mydef)))
          ;; the tool defaults still apply (cljfmt's own defaults merged)
          (is (true? (:remove-consecutive-blank-lines? opts))))
        (finally (fs/delete-tree dir)))))
  (testing "no config file — plain cljfmt defaults"
    (let [dir (fs/create-dir (fs/path "target" "fmt-config-test"))
          clj (fs/file dir "sample.clj")]
      (spit clj "")
      (try
        (let [opts (util/project-fmt-opts (str clj))]
          (is (not (contains? (:extra-indents opts) 'mydef))))
        (finally (fs/delete-tree dir)))))
  (testing "bare filename — searches from the current directory"
    (is (map? (util/project-fmt-opts "sample.clj")))))

(deftest test-format-honors-project-extra-indents
  (let [dir (fs/create-dir (fs/path "target" "fmt-config-test"))
        _   (spit (fs/file dir "cljfmt.edn")
                  "{:extra-indents {mydef [[:block 3] [:inner 1]]}}")
        clj (fs/file dir "sample.clj")]
    (spit clj "")
    (try
      (let [opts (util/project-fmt-opts (str clj))
            src  "(mydef Foo nil\n[container search-input list-container state-atom]\n(render [this w] (str a))\n(handle-input [this d] (cond\n(keys/matches-key? d \"x\") (do (foo this) nil)\n:else nil)))\n"
            out  (util/format-source-string src opts)]
        ;; [:block 3]: method forms at index >= 3 start on their own line
        ;; at block indent, NOT aligned under the first argument
        (is (str/includes? out "\n  (render [this w] (str a))"))
        (is (str/includes? out "\n  (handle-input [this d] (cond"))
        (is (not (str/includes? out "\n       (render"))))
      (finally (fs/delete-tree dir)))))

;; ═══════════════════════════════════════════════════════════════════════════════
;; Zipper navigation
;; ═══════════════════════════════════════════════════════════════════════════════

(deftest test-walk-back-to-non-comment
  (testing "walks backward past comments and whitespace to a non-comment node"
    ;; walk-back-to-non-comment uses z/prev* which traverses the full tree.
    ;; Verify it finds a non-comment node going backward.
    (let [parsed   (p/parse-string-all ";; comment\n;; another\n(+ x 1)")
          zloc     (z/of-node parsed)
          ;; Navigate into the list node: down goes to the first child (+)
          list-loc (-> zloc z/down z/right) ;; skip comment, get to list
          back     (util/walk-back-to-non-comment list-loc)]
      (is (some? back))
      (is (not (#{:whitespace :comment} (node/tag (z/node back)))))))
  (testing "returns the node itself when no preceding comment"
    (let [zloc (z/of-string "(+ x 1)")
          expr (z/down zloc)
          back (util/walk-back-to-non-comment expr)]
      (is (some? back)))))

;; ═══════════════════════════════════════════════════════════════════════════════
;; Zipper form replacement
;; ═══════════════════════════════════════════════════════════════════════════════

(deftest test-replace-form-normal
  (let [zloc  (z/of-string "(+ x 1)")
        replaced (util/replace-form zloc "(+ x 2)")]
    (is (= "(+ x 2)" (z/root-string replaced)))))

(deftest test-replace-form-with-comment
  (testing "replacing with a comment removes adjacent comments"
    (let [zloc     (z/of-string ";; old\n(+ x 1)")
          ;; Navigate to the (+ x 1) form
          expr     (-> zloc z/down z/right)
          replaced (util/replace-form expr ";; replaced")]
      (is (str/includes? (z/root-string replaced) "replaced")))))
