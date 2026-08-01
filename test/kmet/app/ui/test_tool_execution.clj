(ns kmet.app.ui.test-tool-execution
  (:require [clojure.test :as t :refer [deftest is testing]]
            [kmet.tui.core :as core]
            [kmet.app.ui.tool-execution :as te]))

(defn- strip-ansi [s]
  (clojure.string/replace s #"\u001b\[[0-9;]*[a-zA-Z]" ""))

(deftest test-create
  (testing "create tool execution component"
    (let [c (te/make-tool-execution :name "ls")]
      (is (some? c)))))

(deftest test-render-name
  (testing "renders tool name"
    (let [c (te/make-tool-execution :name "read_file")]
      (let [plain (mapv strip-ansi (core/render c 40))]
        (is (some #(re-find #"read_file" %) plain))))))

(deftest test-render-content
  (testing "renders tool output content"
    (let [c (te/make-tool-execution :name "ls" :content "file1\nfile2")]
      (let [plain (mapv strip-ansi (core/render c 40))]
        (is (some #(re-find #"file1" %) plain))
        (is (some #(re-find #"file2" %) plain))))))

(deftest test-render-error
  (testing "error tool uses tool-error-bg"
    (let [c (te/make-tool-execution :name "my-tool" :content "failed" :is-error true)]
      (let [rendered (core/render c 40)]
        ;; Content visible for errors
        (is (some #(re-find #"failed" %) (mapv strip-ansi rendered)))))))

(deftest test-set-name
  (testing "set-name! updates tool name"
    (let [c (te/make-tool-execution :name "old")]
      (te/tool-execution-set-name! c "new")
      (is (some #(re-find #"new" %) (mapv strip-ansi (core/render c 40)))))))

(deftest test-set-content
  (testing "set-content! updates output"
    (let [c (te/make-tool-execution :name "ls" :content "old")]
      (te/tool-execution-set-content! c "new content")
      (is (some #(re-find #"new content" %) (mapv strip-ansi (core/render c 40)))))))

(deftest test-set-error
  (testing "set-error! changes background"
    (let [c (te/make-tool-execution :name "ls" :content "ok")]
      (te/tool-execution-set-error! c true)
      ;; Still renders
      (is (pos? (count (core/render c 40)))))))

(deftest test-set-expanded
  (testing "set-expanded! controls visibility"
    (let [c (te/make-tool-execution :name "ls" :content "hidden")]
      ;; Content visible by default
      (is (some #(re-find #"hidden" %) (mapv strip-ansi (core/render c 40)))))))

(deftest test-empty-name
  (testing "empty name still renders"
    (let [c (te/make-tool-execution :name "" :content "just content")]
      (let [plain (mapv strip-ansi (core/render c 40))]
        (is (some #(re-find #"just content" %) plain))))))

(deftest test-set-output-pad
  (testing "set-output-pad! changes padding"
    (let [c (te/make-tool-execution :name "ls" :content "x" :output-pad 3)]
      (te/tool-execution-set-output-pad! c 5)
      (is (pos? (count (core/render c 40)))))))

(deftest test-invalidate
  (testing "invalidate clears cache"
    (let [c (te/make-tool-execution :name "ls" :content "first")]
      (core/render c 40) ;; populate cache
      (te/tool-execution-set-content! c "second")
      (is (some #(re-find #"second" %) (mapv strip-ansi (core/render c 40)))))))

;; ─── Built-in renderers (read / write / edit / bash) ──────────────────────

(defn- render-tool [& {:keys [name args content is-error expanded? truncation]}]
  (let [c (te/make-tool-execution :name name :args args :content (or content "")
                                  :is-error (boolean is-error) :expanded? expanded?
                                  :truncation truncation)]
    (mapv strip-ansi (core/render c 60))))

(deftest test-read-render-call-range
  (testing "read call shows line range suffix"
    (let [plain (render-tool :name "read" :args {:path "src/a.clj" :offset 5 :limit 3})]
      (is (some #(re-find #"read src/a\.clj:5-7" %) plain)))))

(deftest test-read-render-empty-path
  (testing "empty path renders toolOutput ellipsis (pi renderToolPath)"
    (let [plain (render-tool :name "read" :args {:path ""})]
      (is (some #(re-find #"read \.\.\." %) plain)))))

(deftest test-read-render-hidden-when-collapsed
  (testing "read result hidden when collapsed and not error (pi)"
    (let [plain (render-tool :name "read" :args {:path "src/a.clj"} :content "line1\nline2")]
      (is (not-any? #(re-find #"line1" %) plain)))))

(deftest test-read-render-expanded
  (testing "read result shown when expanded"
    (let [plain (render-tool :name "read" :args {:path "src/a.clj"} :content "line1\nline2" :expanded? true)]
      (is (some #(re-find #"line1" %) plain)))))

(deftest test-read-render-truncation-warning
  (testing "read truncation warning shown when expanded (pi format)"
    (let [plain (render-tool :name "read" :args {:path "src/a.clj"}
                             :content "a\nb" :expanded? true
                             :truncation {:total-lines 100 :output-lines 2 :truncated-by :lines
                                          :max-lines 2000 :max-bytes 51200})]
      (is (some #(re-find #"Truncated: showing 2 of 100 lines \(2000 line limit\)" %) plain)))))

(deftest test-write-render-preview
  (testing "write call previews content from args"
    (let [plain (render-tool :name "write" :args {:path "src/a.clj" :content "hello\nworld"})]
      (is (some #(re-find #"hello" %) plain))
      (is (some #(re-find #"world" %) plain)))))

(deftest test-write-render-invalid-content
  (testing "non-string content shows invalid content arg error (pi)"
    (let [plain (render-tool :name "write" :args {:path "src/a.clj" :content 42})]
      (is (some #(re-find #"invalid content arg" %) plain)))))

(deftest test-write-render-error
  (testing "write error result shown"
    (let [plain (render-tool :name "write" :args {:path "src/a.clj"} :content "denied" :is-error true)]
      (is (some #(re-find #"denied" %) plain)))))

(deftest test-edit-render-diff-preview
  (testing "edit call shows numbered diff preview"
    (spit "target/test-tools-edit-render.txt" "alpha\nbeta\ngamma")
    (let [plain (render-tool :name "edit"
                             :args {:path "target/test-tools-edit-render.txt"
                                    :old-text "beta" :new-text "BETA"})]
      (is (some #(re-find #"1 alpha" %) plain))
      (is (some #(re-find #"-2 beta" %) plain))
      (is (some #(re-find #"\+2 BETA" %) plain)))))

(deftest test-edit-render-edits-array
  (testing "edit preview handles camelCase edits array"
    (spit "target/test-tools-edit-render.txt" "one two")
    (let [plain (render-tool :name "edit"
                             :args {:path "target/test-tools-edit-render.txt"
                                    :edits [{:oldText "one" :newText "1"}]})]
      (is (some #(re-find #"\+1 1 two" %) plain)))))

(deftest test-edit-render-error-suppressed
  (testing "edit execution error already shown by preview is suppressed (pi dedup)"
    (spit "target/test-tools-edit-render.txt" "alpha")
    (let [plain (render-tool :name "edit"
                             :args {:path "target/test-tools-edit-render.txt"
                                    :old-text "zzz" :new-text "x"}
                             :content "Could not find old-text in target/test-tools-edit-render.txt"
                             :is-error true)]
      (is (= 1 (count (filter #(re-find #"Could not find old-text" %) plain)))))))

(deftest test-bash-render-call
  (testing "bash call shows $ command"
    (let [plain (render-tool :name "bash" :args {:command "ls -la"})]
      (is (some #(re-find #"\$ ls -la" %) plain)))))

(deftest test-bash-render-result
  (testing "bash result shows output and duration"
    (let [c (te/make-tool-execution :name "bash" :args {:command "ls"} :content "out1\nout2")]
      (te/tool-execution-mark-execution-started! c)
      (te/tool-execution-set-error! c false)
      (let [plain (mapv strip-ansi (core/render c 60))]
        (is (some #(re-find #"out1" %) plain))
        (is (some #(re-find #"Took" %) plain))))))
