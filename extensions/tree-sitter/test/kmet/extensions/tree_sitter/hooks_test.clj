(ns kmet.extensions.tree-sitter.hooks-test
  "Hook behavior with stubbed validators (offline) plus one guarded
   integration through the real CLI."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [kmet.extensions.tree-sitter.dispatch :as dispatch]
            [kmet.extensions.tree-sitter.hooks :as hooks]
            [kmet.extensions.tree-sitter.test-util :as tu]
            [kmet.extensions.tree-sitter.validate :as validate]))

(def ^:private py-problems
  [{:kind :error :line 2 :col 3 :expected nil :snippet "x = (1"}])

(defmacro with-stub
  "Redef parse-problems! for the body: PROBLEMS vector or nil."
  [problems & body]
  `(with-redefs [validate/parse-problems!
                 (fn [& _#] (when-some [p# ~problems] {:problems p#}))]
     ~@body))

(defn- call-write [args]
  (#'hooks/on-tool-call {:tool-name "write" :args args}))

(defn- call-edit-result [f result]
  (#'hooks/on-tool-result {:tool-name "edit" :is-error false
                           :args {:path f}
                           :result result}))

(deftest write-block-format-test
  (with-stub py-problems
    (let [r (call-write {:path "/tmp/x.py" :content "x = (1 +"})]
      (is (some? r))
      (is (:block r))
      (is (str/includes? (:reason r) "line 2, col 3"))
      (is (str/includes? (:reason r) "Write blocked — ")))))

(deftest write-clean-passes-test
  (with-stub nil
    (is (nil? (call-write {:path "/tmp/ok.py"
                           :content "def f():\n    return 1\n"})))))

(defn- call-write-with-tool [tool]
  (#'hooks/on-tool-call {:tool-name tool
                         :args {:path "/tmp/x.py" :content "x = (1 +"}}))

(deftest only-write-intercepted-test
  (testing "edit is handled by the post-hook, not the pre-hook"
    (with-stub py-problems
      (is (nil? (call-write-with-tool "edit"))))))

(deftest unknown-ext-passes-test
  (is (nil? (call-write {:path "/tmp/notes.md" :content "# hello )"}))))

(deftest clojure-deferred-to-paren-repair-test
  ;; clojure extension present -> tree-sitter never gates clojure files,
  ;; even when the delimiter scanner would find problems
  (dispatch/set-api! {:get-all-tools (fn [] [{:name "clojure_edit"}])})
  (with-stub [{:kind :unclosed :line 1 :col 1 :expected ")" :snippet "("}]
    (is (nil? (call-write {:path "/tmp/broken.clj" :content "(defn f [x]"}))))
  (testing "without the clojure extension the delimiter fallback blocks"
    (dispatch/set-api! {:get-all-tools (fn [])})
    (with-stub [{:kind :unclosed :line 1 :col 1 :expected ")" :snippet "("}]
      (let [r (call-write {:path "/tmp/broken.clj" :content "(defn f [x]"})]
        (is (:block r))
        (is (str/includes? (:reason r) "never closed")))))
  (testing "balanced clojure content passes the delimiter fallback"
    (dispatch/set-api! {:get-all-tools (fn [])})
    (is (nil? (call-write {:path "/tmp/ok.clj"
                           :content "(defn f [x]\n  (inc x))\n"})))))

(deftest never-throw-test
  (with-redefs [validate/parse-problems!
                (fn [& _] (throw (ex-info "boom" {})))]
    (is (nil? (call-write {:path "/tmp/x.py" :content "whatever"})))))

;; ─── edit post-hook ───────────────────────────────────────────────────────

(defn- broken-file-fixture! []
  (let [dir (tu/temp-dir! "ts-hooks")
        f (str (fs/path dir "svc.py"))]
    (spit f "def load(uid):\n    return db_fetch(uid)\n")
    [dir f]))

(deftest edit-warn-appends-test
  (let [[dir f] (broken-file-fixture!)]
    (try
      (with-stub [{:kind :error :line 2 :col 5
                   :expected nil :snippet "oops"}]
        (let [r (call-edit-result f {:content "Edited successfully."})]
          (is (some? r))
          (is (str/includes? (:content r) "⚠️"))
          (is (str/includes? (:content r) "Edited successfully."))))
      (finally (fs/delete-tree dir)))))

(deftest edit-clean-no-warning-test
  (let [[dir f] (broken-file-fixture!)]
    (try
      (with-stub nil
        (is (nil? (call-edit-result f {:content "ok"}))))
      (finally (fs/delete-tree dir)))))

(deftest edit-error-result-not-inspected-test
  (is (nil? (#'hooks/on-tool-result
             {:tool-name "edit" :is-error true
              :args {:path "/tmp/x.py"} :result {:content ""}}))))
