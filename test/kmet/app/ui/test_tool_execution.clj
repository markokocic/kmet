(ns kmet.app.ui.test-tool-execution
  (:require [clojure.test :as t :refer [deftest is testing]]
            [clojure.string :as str]
            [kmet.tui.core :as core]
            [kmet.tui.utils :as utils]
            [kmet.libs.terminal-image :as timg]
            [kmet.app.ui.tool-execution :as te]))

(defn- strip-ansi [s]
  (utils/strip-ansi-codes s))

(deftest test-create
  (testing "create tool execution component"
    (let [c (te/make-tool-execution :name "ls")]
      (is (some? c)))))

(deftest test-render-name
  (testing "renders tool name"
    (let [c (te/make-tool-execution :name "read_file")
          plain (mapv strip-ansi (core/render c 40))]
      (is (some #(re-find #"read_file" %) plain)))))

(deftest test-render-content
  (testing "renders tool output content"
    (let [c (te/make-tool-execution :name "ls" :content "file1\nfile2")
          plain (mapv strip-ansi (core/render c 40))]
      (is (some #(re-find #"file1" %) plain))
      (is (some #(re-find #"file2" %) plain)))))

(deftest test-render-error
  (testing "error tool uses tool-error-bg"
    (let [c (te/make-tool-execution :name "my-tool" :content "failed" :is-error true)
          rendered (core/render c 40)]
        ;; Content visible for errors
      (is (some #(re-find #"failed" %) (mapv strip-ansi rendered))))))

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
    (let [c (te/make-tool-execution :name "" :content "just content")
          plain (mapv strip-ansi (core/render c 40))]
      (is (some #(re-find #"just content" %) plain)))))

(deftest test-set-output-pad
  (testing "set-output-pad! rebuilds the box with the new horizontal padding"
    (let [c (te/make-tool-execution :name "ls" :content "x" :output-pad 1)]
      (is (= 1 (:padding-x @(:box c))))
      (te/tool-execution-set-output-pad! c 5)
      (is (= 5 (:padding-x @(:box c))) "box padding-x updated")
      (let [plain (mapv strip-ansi (core/render c 40))]
        (is (some #(re-find #"^     ls" %) plain)
            "call line indented by the new padding")))))

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
    ;; Pi: interactive mode calls setArgsComplete right after creating the
    ;; component (kmet interactive.clj tool-execution-start handler)
    (te/tool-execution-set-args-complete! c)
    (mapv strip-ansi (core/render c 60))))

(deftest test-read-render-hyperlink-path
  (testing "OSC 8 hyperlinked path stays on one line with contiguous visible text (pi linkPath)"
    (let [prev (timg/get-capabilities)]
      (timg/set-capabilities! {:images nil :true-color true :hyperlinks true})
      (try
        (let [plain (render-tool :name "read" :args {:path "src/a.clj" :offset 5 :limit 3})]
          (is (some #(re-find #"read src/a\.clj:5-7" %) plain))
          (is (not-any? #(re-find #"^ read +$" %) plain)
              "path must not wrap onto its own line"))
        (finally
          (timg/set-capabilities! prev))))))

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
                             :content "Could not find the exact text in target/test-tools-edit-render.txt. The old text must match exactly including all whitespace and newlines."
                             :is-error true)]
      (is (= 1 (count (filter #(re-find #"Could not find the exact text" %) plain)))))))

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

(deftest test-bash-elapsed-ticker
  (testing "partial bash execution starts a 1s elapsed ticker (pi: setInterval →
            context.invalidate); completion clears it and it does not restart"
    (let [c (te/make-tool-execution :name "bash" :args {:command "sleep 5"})]
      (te/tool-execution-mark-execution-started! c)
      (core/render c 60)
      (let [interval (:interval @(:renderer-state-atom c))]
        (is (some? interval) "partial execution starts the elapsed ticker")
        (is (future? interval)))
      ;; tool-execution-end always calls set-error! → ended-at set → clear
      (te/tool-execution-set-error! c false)
      (core/render c 60)
      (is (nil? (:interval @(:renderer-state-atom c)))
          "completion clears the ticker")
      ;; a later render (cache miss) must not restart it
      (te/tool-execution-set-expanded! c true)
      (core/render c 60)
      (is (nil? (:interval @(:renderer-state-atom c)))
          "no ticker after completion"))))

(deftest test-throwing-request-render-fn-contained
  (testing "a throwing request-render-fn must not propagate through setters
            (agent-loop thread) or the render pass (render-loop thread)"
    (let [c (te/make-tool-execution :name "bash" :args {:command "ls"})]
      (te/tool-execution-set-request-render-fn! c (fn [] (throw (ex-info "boom" {}))))
      (is (= :ok (try (te/tool-execution-mark-execution-started! c) :ok
                      (catch Exception _ :threw)))
          "mark-execution-started! does not throw")
      (is (= :ok (try (te/tool-execution-set-content! c "out") :ok
                      (catch Exception _ :threw)))
          "set-content! does not throw")
      (is (= :ok (try (te/tool-execution-set-error! c false) :ok
                      (catch Exception _ :threw)))
          "set-error! does not throw")
      (is (= :ok (try (core/render c 60) :ok
                      (catch Exception _ :threw)))
          "render pass does not throw")
      ;; the component still works normally afterwards
      (is (some #(re-find #"Took" %) (mapv strip-ansi (core/render c 60)))))))

;; ─── Pi parity: cached edit preview, compact read, tabs, expanded ─────────

(deftest test-edit-result-diff-corrects-preview
  (testing "edit result with a different applied diff corrects the cached preview (pi resultDiff fallback)"
    (let [f "target/test-tools-edit-resultdiff.txt"]
      (spit f "alpha\nbeta\ngamma")
      (let [c (te/make-tool-execution
               :name "edit"
               :args {:path f :old-text "beta" :new-text "BETA"})]
        (te/tool-execution-set-args-complete! c)
        ;; preview computed against the original file
        (let [plain (mapv strip-ansi (core/render c 60))]
          (is (some #(re-find #"\+2 BETA" %) plain)))
        ;; file changed between preview and apply — the tool applied a diff
        ;; that differs from the previewed one
        (spit f "alpha\nbeta\ngamma\ndelta")
        (te/tool-execution-set-content! c
                                        "Successfully replaced 1 block(s) in target/test-tools-edit-resultdiff.txt.")
        (te/tool-execution-set-error! c false)
        (te/tool-execution-set-details! c
                                        {:diff " 1 alpha\n-2 beta\n+2 BETA\n 3 gamma\n 4 delta"})
        ;; this pass still shows the stale preview; the result corrects the cache
        (let [plain (mapv strip-ansi (core/render c 60))]
          (is (some #(re-find #"\+2 BETA" %) plain)))
        ;; next render shows the actual applied diff
        (let [plain (mapv strip-ansi (core/render c 60))]
          (is (some #(re-find #" 4 delta" %) plain))
          (is (not-any? #(re-find #"Could not find old-text" %) plain)))))))

(deftest test-edit-replayed-result-diff
  (testing "replayed edit result (args incomplete, pi restore) shows the applied diff via details"
    (spit "target/test-tools-edit-replay.txt" "alpha\nBETA\ngamma")
    (let [c (te/make-tool-execution
             :name "edit"
             :args {:path "target/test-tools-edit-replay.txt"
                    :old-text "beta" :new-text "BETA"}
             :content "Successfully replaced 1 block(s) in target/test-tools-edit-replay.txt."
             :details {:diff " 1 alpha\n-2 beta\n+2 BETA\n 3 gamma"})]
      (te/tool-execution-set-error! c false)
      ;; first pass: render-result corrects the preview from details
      (core/render c 60)
      ;; second pass: the call box shows the applied diff
      (let [plain (mapv strip-ansi (core/render c 60))]
        (is (some #(re-find #"\+2 BETA" %) plain))
        (is (some #(re-find #" 1 alpha" %) plain))))))

(deftest test-edit-result-diff-matching-preview-kept
  (testing "edit result matching the preview leaves the call box unchanged (no re-preview)"
    (spit "target/test-tools-edit-match.txt" "alpha\nbeta\ngamma")
    (let [c (te/make-tool-execution
             :name "edit"
             :args {:path "target/test-tools-edit-match.txt"
                    :old-text "beta" :new-text "BETA"})]
      (te/tool-execution-set-args-complete! c)
      (let [before (mapv strip-ansi (core/render c 60))]
        (te/tool-execution-set-content! c
                                        "Successfully replaced 1 block(s) in target/test-tools-edit-match.txt.")
        (te/tool-execution-set-error! c false)
        (te/tool-execution-set-details! c
                                        {:diff " 1 alpha\n-2 beta\n+2 BETA\n 3 gamma"})
        (let [after2 (mapv strip-ansi (core/render c 60))]
          ;; identical diff: no correction, no stale-file error
          (is (= before after2))
          (is (not-any? #(re-find #"Could not find old-text" %) after2)))))))

(deftest test-edit-preview-cached-after-edit
  (testing "edit call keeps the original preview after the file was edited (pi caches per args)"
    (let [f "target/test-tools-edit-cache.txt"]
      (spit f "alpha\nbeta\ngamma")
      (let [c (te/make-tool-execution
               :name "edit"
               :args {:path f :old-text "beta" :new-text "BETA"})]
        (te/tool-execution-set-args-complete! c)
        ;; First render: preview succeeds against the original file
        (let [plain (mapv strip-ansi (core/render c 60))]
          (is (some #(re-find #"-2 beta" %) plain))
          (is (some #(re-find #"\+2 BETA" %) plain)))
        ;; Simulate the edit actually being applied
        (spit f "alpha\nBETA\ngamma")
        ;; Re-render must NOT re-derive the preview from the now-edited file
        ;; (that would fail to find old-text and flip the box to error)
        (let [plain (mapv strip-ansi (core/render c 60))]
          (is (some #(re-find #"-2 beta" %) plain))
          (is (some #(re-find #"\+2 BETA" %) plain))
          (is (not-any? #(re-find #"Could not find old-text" %) plain)))))))

(deftest test-edit-render-pending-when-args-incomplete
  (testing "edit call shows pending box (no diff) until args are complete (pi argsComplete)"
    (spit "target/test-tools-edit-pending.txt" "alpha")
    (let [c (te/make-tool-execution
             :name "edit"
             :args {:path "target/test-tools-edit-pending.txt"
                    :old-text "alpha" :new-text "ALPHA"})
      ;; args-complete is false here — no preview yet
          plain (mapv strip-ansi (core/render c 60))]
      (is (some #(re-find #"edit" %) plain))
      (is (not-any? #(re-find #"\+1 ALPHA" %) plain)))))

(deftest test-read-compact-skill
  (testing "SKILL.md reads render as [skill] label + expand hint (pi getCompactReadClassification)"
    (let [plain (render-tool :name "read"
                             :args {:path "/home/user/.pi/skills/demo-skill/SKILL.md"})]
      (is (some #(re-find #"\[skill\] demo-skill" %) plain))
      (is (some #(re-find #"to expand" %) plain)))))

(deftest test-read-compact-resource
  (testing "AGENTS.md reads render as 'read resource' label (pi)"
    (let [plain (render-tool :name "read" :args {:path "AGENTS.md"})]
      (is (some #(re-find #"read resource AGENTS\.md" %) plain)))))

(deftest test-read-compact-hidden-when-expanded
  (testing "compact read classification is skipped when expanded (pi)"
    (let [plain (render-tool :name "read" :expanded? true
                             :args {:path "/home/user/.pi/skills/demo-skill/SKILL.md"})]
      (is (not-any? #(re-find #"\[skill\]" %) plain))
      (is (some #(re-find #"read /home/user/\.pi/skills/demo-skill/SKILL\.md" %) plain)))))

(deftest test-read-render-file-path-key
  (testing "read call accepts file_path arg key (pi: str(args?.file_path ?? args?.path))"
    (let [plain (render-tool :name "read" :args {:file_path "src/a.clj"})]
      (is (some #(re-find #"read src/a\.clj" %) plain)))))

(deftest test-read-render-tabs-replaced
  (testing "read result replaces tabs with 3 spaces (pi replaceTabs)"
    (let [plain (render-tool :name "read" :args {:path "src/a.clj"}
                             :content "a\tb" :expanded? true)]
      (is (some #(re-find #"a   b" %) plain))
      (is (not-any? #(re-find #"a\tb" %) plain)))))

(deftest test-read-render-error-lines
  (testing "read error result shows lines (toolOutput; error conveyed by bg)"
    (let [plain (render-tool :name "read" :args {:path "missing.clj"}
                             :content "File not found: missing.clj" :is-error true)]
      (is (some #(re-find #"File not found: missing\.clj" %) plain)))))

(deftest test-write-render-expanded
  (testing "write call shows all lines when expanded (pi maxLines = expanded ? all : 10)"
    (let [content (str/join "\n" (map #(str "line" %) (range 1 13)))
          collapsed (render-tool :name "write"
                                 :args {:path "src/a.clj" :content content})]
      (is (some #(re-find #"line1" %) collapsed))
      (is (some #(re-find #"more lines, 12 total" %) collapsed))
      (is (not-any? #(re-find #"line12" %) collapsed))
      (let [expanded (render-tool :name "write" :expanded? true
                                  :args {:path "src/a.clj" :content content})]
        (is (some #(re-find #"line12" %) expanded))
        (is (not-any? #(re-find #"more lines" %) expanded))))))

(deftest test-read-expanded-leading-blank
  (testing "read result has a blank line between call and result (pi: result starts with a blank line)"
    (let [plain (render-tool :name "read" :expanded? true
                             :args {:path "a.clj"} :content "line1")
          trimmed (mapv str/trim plain)
          call-idx (first (keep-indexed #(when (= "read a.clj" %2) %1) trimmed))
          line-idx (first (keep-indexed #(when (= "line1" %2) %1) trimmed))]
      (is call-idx)
      (is line-idx)
      (is (= line-idx (inc (inc call-idx)))
          "exactly one blank line separates call from result"))))

(deftest test-bash-tool-result-blank-separators
  (testing "bash tool result keeps blank lines before output and before Took"
    (let [c (te/make-tool-execution :name "bash" :args {:command "echo hi"})]
      ;; set-content! marks execution started; set-error! marks it ended
      (te/tool-execution-set-content! c "hi\n")
      (te/tool-execution-set-error! c false)
      (let [plain (mapv strip-ansi (core/render c 60))
            trimmed (mapv str/trim plain)
            out-idx (first (keep-indexed #(when (= "hi" %2) %1) trimmed))
            took-idx (first (keep-indexed #(when (str/starts-with? %2 "Took") %1) trimmed))]
        (is out-idx)
        (is took-idx)
        (is (= "" (str/trim (nth plain (dec out-idx)))) "blank line before output")
        (is (= "" (str/trim (nth plain (dec took-idx)))) "blank line before Took")))))
