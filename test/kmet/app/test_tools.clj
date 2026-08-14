(ns kmet.app.test-tools
  (:require [clojure.string :as str]
            [clojure.test :as t]
            [kmet.app.tools.core :as tools]
            [kmet.ai.api.shared :as schema-shared]
            [kmet.app.tools.bash :as bash-tool]
            [kmet.app.bash-executor :as bash-exec]))

;; ─── Tool registry ─────────────────────────────────────────────────────────

(t/deftest test-tools-get-all
  (let [all (tools/get-all-tools)]
    (t/is (map? all))
    (t/is (contains? all "read"))
    (t/is (contains? all "write"))
    (t/is (contains? all "edit"))
    (t/is (contains? all "bash"))))

(t/deftest test-tools-get
  (let [t (tools/get-tool "read")]
    (t/is (some? t))
    (t/is (= "read" (:name t)))
    (t/is (fn? (:execute t)))))

(t/deftest test-tools-get-unknown
  (t/is (nil? (tools/get-tool "nonexistent"))))

;; ─── Tool record ──────────────────────────────────────────────────────────

(t/deftest test-tool-record
  (let [t (tools/get-tool "read")]
    (t/is (map? t))
    (t/is (string? (:name t)))
    (t/is (string? (:label t)))
    (t/is (string? (:description t)))
    (t/is (map? (:parameters t)))
    (t/is (fn? (:execute t)))))

(t/deftest test-tool-record-fields
  (let [t (tools/get-tool "read")]
    (t/is (= "read" (:name t)))
    (t/is (= "Read file" (:label t)))
    (t/is (= 3 (count (keys (:parameters t)))))))

;; ─── Tool read ────────────────────────────────────────────────────────────

(t/deftest test-tool-read-file
  (spit "target/test-tools-read.txt" "hello\nworld\nfoo\nbar")
  (let [result (tools/execute-tool "read" {:path "target/test-tools-read.txt"})]
    (t/is (string? (:content result)))
    (t/is (.contains (:content result) "hello"))
    (t/is (not (:is-error result)))))

(t/deftest test-tool-read-with-offset
  (spit "target/test-tools-read.txt" "line1\nline2\nline3\nline4")
  (let [result (tools/execute-tool "read" {:path "target/test-tools-read.txt" :offset 2})]
    (t/is (not (.contains (:content result) "line1")))
    (t/is (.contains (:content result) "line3"))))

(t/deftest test-tool-read-with-limit
  (spit "target/test-tools-read.txt" "a\nb\nc\nd\ne")
  (let [result (tools/execute-tool "read" {:path "target/test-tools-read.txt" :limit 2})
        content (:content result)]
    (t/is (.contains content "a"))
    (t/is (.contains content "b"))
    ;; Pi: a user limit stops early with a continuation footer
    (t/is (not (re-find #"(?m)^c$" content)))
    (t/is (.contains content "more lines in file"))))

(t/deftest test-tool-read-nonexistent
  (let [result (tools/execute-tool "read" {:path "nonexistent-file"})]
    (t/is (:is-error result))
    (t/is (.contains (:content result) "not found"))))

;; ─── Tool write ───────────────────────────────────────────────────────────

(t/deftest test-tool-write
  (let [result (tools/execute-tool "write" {:path "target/test-tools-write.txt" :content "hello world"})]
    (t/is (not (:is-error result)))
    (t/is (.contains (:content result) "Successfully wrote"))
    (t/is (= "hello world" (slurp "target/test-tools-write.txt")))))

;; ─── Tool edit ────────────────────────────────────────────────────────────

(t/deftest test-tool-edit
  (spit "target/test-tools-edit.txt" "hello world")
  (let [result (tools/execute-tool "edit" {:path "target/test-tools-edit.txt"
                                           :old-text "hello"
                                           :new-text "bye"})]
    (t/is (not (:is-error result)))
    (t/is (= "bye world" (slurp "target/test-tools-edit.txt")))))

(t/deftest test-tool-edit-pattern-not-found
  (spit "target/test-tools-edit.txt" "hello")
  (let [result (tools/execute-tool "edit" {:path "target/test-tools-edit.txt"
                                           :old-text "nonexistent"
                                           :new-text "x"})]
    (t/is (:is-error result))))

(t/deftest test-tool-edit-nonexistent-file
  (let [result (tools/execute-tool "edit" {:path "target/nonexistent-edit.txt"
                                           :old-text "x" :new-text "y"})]
    (t/is (:is-error result))))

(t/deftest test-tool-edit-crlf-preserved
  (t/testing "CRLF files match with LF old-text, keep CRLF, diff has no \r (pi normalizeToLF + restoreLineEndings)"
    (spit "target/test-tools-edit-crlf.txt" "alpha\r\nbeta\r\ngamma")
    (let [result (tools/execute-tool "edit" {:path "target/test-tools-edit-crlf.txt"
                                             :edits [{:old-text "beta" :new-text "BETA"}]})]
      (t/is (not (:is-error result)))
      (t/is (= "alpha\r\nBETA\r\ngamma" (slurp "target/test-tools-edit-crlf.txt")))
      (t/is (not (clojure.string/includes? (get-in result [:details :diff]) "\r"))))))

(t/deftest test-tool-edit-bom-preserved
  (t/testing "UTF-8 BOM is stripped for matching and restored on write (pi stripBom)"
    (spit "target/test-tools-edit-bom.txt" "\uFEFFline1\nline2")
    (let [result (tools/execute-tool "edit" {:path "target/test-tools-edit-bom.txt"
                                             :edits [{:old-text "line2" :new-text "LINE2"}]})]
      (t/is (not (:is-error result)))
      (t/is (= "\uFEFFline1\nLINE2" (slurp "target/test-tools-edit-bom.txt"))))))

(t/deftest test-tool-edit-fuzzy-match
  (t/testing "trailing whitespace differences match fuzzily (pi normalizeForFuzzyMatch)"
    (spit "target/test-tools-edit-fuzzy.txt" "alpha\nbeta  \ngamma")
    (let [result (tools/execute-tool "edit" {:path "target/test-tools-edit-fuzzy.txt"
                                             :edits [{:old-text "beta \t" :new-text "BETA"}]})]
      (t/is (not (:is-error result)))
      ;; Pi: the changed line is rewritten from the normalized base (trailing
      ;; whitespace stripped); unchanged lines keep their original bytes
      (t/is (= "alpha\nBETA\ngamma" (slurp "target/test-tools-edit-fuzzy.txt"))))))

(t/deftest test-tool-edit-duplicate-error
  (t/testing "ambiguous old-text reports occurrence count (pi getDuplicateError)"
    (spit "target/test-tools-edit-dup.txt" "x\nx")
    (let [result (tools/execute-tool "edit" {:path "target/test-tools-edit-dup.txt"
                                             :edits [{:old-text "x" :new-text "X"}]})]
      (t/is (:is-error result))
      (t/is (clojure.string/includes? (:content result) "Found 2 occurrences")))))

(t/deftest test-tool-edit-no-change-error
  (t/testing "identical replacement reports no-change (pi getNoChangeError)"
    (spit "target/test-tools-edit-nc.txt" "alpha")
    (let [result (tools/execute-tool "edit" {:path "target/test-tools-edit-nc.txt"
                                             :edits [{:old-text "alpha" :new-text "alpha"}]})]
      (t/is (:is-error result))
      (t/is (clojure.string/includes? (:content result) "No changes made")))))

(t/deftest test-tool-edit-overlap-error
  (t/testing "overlapping edits are rejected (pi overlap check)"
    (spit "target/test-tools-edit-ov.txt" "abcdef")
    (let [result (tools/execute-tool "edit" {:path "target/test-tools-edit-ov.txt"
                                             :edits [{:old-text "abcd" :new-text "X"}
                                                     {:old-text "cdef" :new-text "Y"}]})]
      (t/is (:is-error result))
      (t/is (clojure.string/includes? (:content result) "overlap")))))

(t/deftest test-tool-edit-empty-oldtext-error
  (t/testing "empty old-text is rejected (pi getEmptyOldTextError)"
    (spit "target/test-tools-edit-empty.txt" "alpha")
    (let [result (tools/execute-tool "edit" {:path "target/test-tools-edit-empty.txt"
                                             :edits [{:old-text "" :new-text "x"}]})]
      (t/is (:is-error result))
      (t/is (clojure.string/includes? (:content result) "oldText must not be empty")))))

(t/deftest ^:slow test-tool-bash-streams
  (t/testing "bash tool streams partial output via on-update (pi onUpdate)"
    (let [updates (atom [])
          result (tools/execute-tool "bash" {:command "echo first; sleep 0.3; echo second"}
                                     (fn [partial]
                                       (swap! updates conj (:content partial))))]
      (t/is (not (:is-error result)))
      (t/is (seq @updates))
      (t/is (some #(clojure.string/includes? % "first") @updates))
      (t/is (clojure.string/includes? (:content result) "second")))))

;; ─── Tool bash ────────────────────────────────────────────────────────────

(t/deftest ^:slow test-tool-bash
  (let [result (tools/execute-tool "bash" {:command "echo hello bash"})]
    (t/is (not (:is-error result)))
    (t/is (.contains (:content result) "hello bash"))))

(t/deftest ^:slow test-tool-bash-error
  (let [result (tools/execute-tool "bash" {:command "ls /nonexistent-path-xyz"})]
    (t/is (:is-error result))))

(t/deftest ^:slow test-tool-bash-exit-code
  (let [result (tools/execute-tool "bash" {:command "exit 42"})]
    (t/is (:is-error result) "Non-zero exit should be error")))

(t/deftest ^:slow test-tool-bash-large-stderr
  (let [result (tools/execute-tool "bash"
                                   {:command "for i in $(seq 1 200); do echo err$i >&2; done; echo ok"})]
    (t/is (not (:is-error result)) "Large stderr should not deadlock")
    (t/is (.contains (:content result) "ok"))))

(t/deftest ^:slow test-tool-bash-large-stdout
  (let [result (tools/execute-tool "bash"
                                   {:command "for i in $(seq 1 200); do echo out$i; done; echo done"})]
    (t/is (not (:is-error result)) "Large stdout should not deadlock")
    (t/is (.contains (:content result) "done"))))

(t/deftest ^:slow test-tool-bash-stdout-and-stderr-merged
  (let [result (tools/execute-tool "bash" {:command "echo hello; echo world >&2"})]
    (t/is (not (:is-error result)))
    (t/is (.contains (:content result) "hello"))
    (t/is (.contains (:content result) "world"))))

(t/deftest ^:slow test-tool-bash-stdin-eof
  (t/testing "commands that read stdin get EOF, not the TTY (pi stdio ignore)"
    ;; Regression: `cat` with no args inherited the TTY stdin and deadlocked
    ;; the TUI (kmet spawns with stdin redirected from /dev/null instead).
    (let [result (tools/execute-tool "bash" {:command "cat; echo FINISHED"
                                             :timeout 15})]
      (t/is (not (:is-error result)) "stdin must be EOF, not the TTY")
      (t/is (.contains (:content result) "FINISHED")))))

(t/deftest ^:slow test-tool-bash-background-pipe-closed
  (t/testing "detached child holding the pipe open doesn't stall the tool (pi waitForChildProcess)"
    ;; The descendant outlives the tool's bounded drain: a broken
    ;; implementation that waits for pipe EOF takes ~10s (the sleep's
    ;; lifetime); the fixed one returns at the ~2s grace + spawn overhead.
    ;; The 8s window separates the two with margin on both sides, and is
    ;; generous enough for a loaded host.
    (let [start (System/currentTimeMillis)
          result (tools/execute-tool "bash" {:command "sleep 10 &"})
          elapsed (- (System/currentTimeMillis) start)]
      (t/is (not (:is-error result)))
      (t/is (< elapsed 8000)
            (str "tool should return at bash exit + the bounded drain, took " elapsed "ms")))))

(t/deftest ^:slow test-tool-bash-cancel-signal
  (t/testing "setting the bound cancel signal kills the process (pi AbortSignal)"
    (let [sig (atom false)
          f (binding [bash-tool/*cancel-signal* sig]
              (future (tools/execute-tool "bash" {:command "sleep 30"})))]
      (Thread/sleep 500)
      (reset! sig true)
      (let [result (deref f 5000 ::timeout)]
        (t/is (not= ::timeout result) "cancelled bash must return promptly")
        (t/is (:is-error result))
        (t/is (str/includes? (:content result) "aborted"))))))

;; ─── Unknown tool ─────────────────────────────────────────────────────────

(t/deftest test-truncate-tail-surrogate-boundary
  (t/testing "byte-cut landing mid-surrogate keeps the whole char (no lone surrogate)"
    (let [s (str (apply str (repeat 9 "x")) "😀" (apply str (repeat 9 "y")))
          ;; count = 9 + 2 + 9 = 20; max-bytes 10 → start lands on the low
          ;; surrogate of the emoji
          result (bash-exec/truncate-tail s :max-bytes 10)
          content (:content result)]
      (t/is (:truncated result))
      (t/is (not (re-find #"[\udc00-\udfff]" content))
            "no lone low surrogate in truncated output")
      (t/is (str/includes? content "😀") "the full emoji survives"))))

(t/deftest test-tool-unknown
  (let [result (tools/execute-tool "unknown-tool" {})]
    (t/is (:is-error result))
    (t/is (.contains (:content result) "Unknown tool"))))

;; ─── Tool schemas ─────────────────────────────────────────────────────────

(t/deftest test-tool-anthropic-schema
  (let [tool (tools/get-tool "read")
        schema (schema-shared/tool->anthropic-schema tool)]
    (t/is (= "read" (:name schema)))
    (t/is (string? (:description schema)))
    (t/is (map? (:input_schema schema)))))

(t/deftest test-tool-openai-schema
  (let [tool (tools/get-tool "read")
        schema (schema-shared/tool->openai-schema tool)]
    (t/is (= "function" (:type schema)))
    (t/is (= "read" (get-in schema [:function :name])))))

;; ─── Custom tool registration ─────────────────────────────────────────────

(t/deftest test-tools-register-custom
  (let [custom (tools/make-tool
                :name "custom"
                :label "Custom"
                :description "A custom tool"
                :parameters {:type "object" :properties {} :required []}
                :execute (fn [_] {:content "done"}))]
    (tools/register-tool! custom)
    (t/is (contains? (tools/get-all-tools) "custom"))
    (let [result (tools/execute-tool "custom" {})]
      (t/is (= "done" (:content result))))
    (tools/unregister-tool! "custom")
    (t/is (not (contains? (tools/get-all-tools) "custom")))))

;; ─── Edge cases ──────────────────────────────────────────────────────────

(t/deftest test-tool-read-binary
  (spit "target/test-tools-binary.bin" (byte-array [0 1 2 3]))
  (let [result (tools/execute-tool "read" {:path "target/test-tools-binary.bin"})]
    (t/is (string? (:content result)))))

;; ─── Tool schema: no internal :optional leak ────────────────────────────

(t/deftest test-tool-schema-no-optional
  (doseq [tool-name ["read" "write" "edit" "bash"]]
    (let [tool (tools/get-tool tool-name)
          params (:parameters tool)
          props (:properties params)]
      (doseq [[k v] props]
        (t/is (not (contains? v :optional))
              (str "Property " k " of " tool-name " should not have :optional"))))))

(t/deftest test-tool-edit-edits-array
  (spit "target/test-tools-edit.txt" "one two three")
  (let [result (tools/execute-tool "edit" {:path "target/test-tools-edit.txt"
                                           :edits [{:old-text "one" :new-text "1"}
                                                   {:old-text "three" :new-text "3"}]})]
    (t/is (not (:is-error result)))
    (t/is (= "1 two 3" (slurp "target/test-tools-edit.txt")))))

(t/deftest test-tool-edit-camelcase-keys
  (spit "target/test-tools-edit.txt" "hello world")
  (let [result (tools/execute-tool "edit" {:path "target/test-tools-edit.txt"
                                           :edits [{:oldText "hello" :newText "bye"}]})]
    (t/is (not (:is-error result)))
    (t/is (= "bye world" (slurp "target/test-tools-edit.txt")))))

(t/deftest test-tool-edit-json-string-edits
  (spit "target/test-tools-edit.txt" "alpha beta")
  (let [result (tools/execute-tool "edit" {:path "target/test-tools-edit.txt"
                                           :edits "[{\"oldText\":\"beta\",\"newText\":\"BETA\"}]"})]
    (t/is (not (:is-error result)))
    (t/is (= "alpha BETA" (slurp "target/test-tools-edit.txt")))))

(t/deftest test-tool-string-args-json
  ;; String args only reach execute-tool when the LLM emitted malformed
  ;; tool-call arguments (pi: parseStreamingJson) — valid JSON strings must
  ;; parse to a map and work normally.
  (spit "target/test-tools-read.txt" "string args")
  (let [result (tools/execute-tool "read" "{\"path\": \"target/test-tools-read.txt\"}")]
    (t/is (not (:is-error result)))
    (t/is (.contains (:content result) "string args"))))

(t/deftest test-tool-string-args-malformed
  ;; Malformed arguments JSON used to crash edit with "java.lang.String
  ;; cannot be cast to clojure.lang.Associative" (assoc on a string in
  ;; normalize-edits). It must degrade to {} and fail validation instead.
  (let [result (tools/execute-tool "edit" "{malformed-json")]
    (t/is (:is-error result))
    (t/is (not (re-find #"ClassCastException" (:content result))))
    (t/is (re-find #"at least one replacement" (:content result)))))

(t/deftest test-tool-edit-empty-edits
  (let [result (tools/execute-tool "edit" {:path "target/test-tools-edit.txt"})]
    (t/is (:is-error result))
    (t/is (.contains (:content result) "at least one replacement"))))

(t/deftest test-tool-read-offset-1-indexed
  (spit "target/test-tools-read.txt" "line1\nline2\nline3\nline4")
  (let [result (tools/execute-tool "read" {:path "target/test-tools-read.txt" :offset 2 :limit 2})]
    (t/is (.contains (:content result) "line2"))
    (t/is (.contains (:content result) "line3"))
    (t/is (not (.contains (:content result) "line1")))))

(t/deftest test-tool-read-truncation-metadata
  (spit "target/test-tools-read.txt" (clojure.string/join "\n" (range 1 3001)))
  (let [result (tools/execute-tool "read" {:path "target/test-tools-read.txt"})]
    (t/is (= 3000 (:total-lines (:truncation result))))
    (t/is (= 2000 (:output-lines (:truncation result))))
    (t/is (= :lines (:truncated-by (:truncation result))))))

(t/deftest test-tool-write-parentless-path
  ;; Regression: (fs/create-dirs (fs/parent f)) with a parentless path → nil → crash
  (let [result (tools/execute-tool "write" {:path "target/parentless-write.txt" :content "x"})]
    (t/is (not (:is-error result)) "parentless path should succeed")
    (t/is (.contains (:content result) "Successfully wrote"))))

(t/deftest test-tool-read-beyond-eof
  (spit "target/test-tools-read.txt" "a\nb\nc")
  (let [result (tools/execute-tool "read" {:path "target/test-tools-read.txt" :offset 99})]
    (t/is (:is-error result))
    (t/is (.contains (:content result) "beyond end of file"))))

(t/deftest test-tool-read-image
  (spit "target/test-tools-read.png" "fake-png-bytes")
  (let [result (tools/execute-tool "read" {:path "target/test-tools-read.png"})]
    (t/is (some? (:images result)))
    (t/is (.contains (:content result) "Read image file"))))
