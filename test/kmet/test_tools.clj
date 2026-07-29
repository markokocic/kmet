(ns kmet.test-tools
  (:require [clojure.test :as t]
            [clojure.java.io :as io]
            [kmet.agent.tools :as tools]))

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
  (let [result (tools/execute-tool "read" {:path "target/test-tools-read.txt" :limit 2})]
    (let [content (:content result)]
      (t/is (.contains content "a"))
      (t/is (.contains content "b"))
      (t/is (not (.contains content "c"))))))

(t/deftest test-tool-read-nonexistent
  (let [result (tools/execute-tool "read" {:path "nonexistent-file"})]
    (t/is (:is-error result))
    (t/is (.contains (:content result) "not found"))))

;; ─── Tool write ───────────────────────────────────────────────────────────

(t/deftest test-tool-write
  (let [result (tools/execute-tool "write" {:path "target/test-tools-write.txt" :content "hello world"})]
    (t/is (not (:is-error result)))
    (t/is (.contains (:content result) "Written"))
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

;; ─── Tool bash ────────────────────────────────────────────────────────────

(t/deftest test-tool-bash
  (let [result (tools/execute-tool "bash" {:command "echo hello bash"})]
    (t/is (not (:is-error result)))
    (t/is (.contains (:content result) "hello bash"))))

(t/deftest test-tool-bash-error
  (let [result (tools/execute-tool "bash" {:command "ls /nonexistent-path-xyz"})]
    (t/is (:is-error result))))




;; ─── Unknown tool ─────────────────────────────────────────────────────────

(t/deftest test-tool-unknown
  (let [result (tools/execute-tool "unknown-tool" {})]
    (t/is (:is-error result))
    (t/is (.contains (:content result) "Unknown tool"))))

;; ─── Tool schemas ─────────────────────────────────────────────────────────

(t/deftest test-tool-anthropic-schema
  (let [tool (tools/get-tool "read")
        schema (tools/tool->anthropic-schema tool)]
    (t/is (= "read" (:name schema)))
    (t/is (string? (:description schema)))
    (t/is (map? (:input_schema schema)))))

(t/deftest test-tool-openai-schema
  (let [tool (tools/get-tool "read")
        schema (tools/tool->openai-schema tool)]
    (t/is (= "function" (:type schema)))
    (t/is (= "read" (get-in schema [:function :name])))))

;; ─── Custom tool registration ─────────────────────────────────────────────

(t/deftest test-tools-register-custom
  (let [custom (tools/map->Tool
                 {:name "custom"
                  :label "Custom"
                  :description "A custom tool"
                  :parameters {:type "object" :properties {} :required []}
                  :execute (fn [_] {:content "done"})})]
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

;; ─── Regression: bash default timeout ────────────────────────────────────

(t/deftest test-tool-bash-default-timeout
  (let [result (tools/execute-tool "bash" {:command "echo default-timeout-test"})]
    (t/is (not (:is-error result)))
    (t/is (.contains (:content result) "default-timeout-test"))))

