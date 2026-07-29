(ns kmet.test-tools-regression
  "Regression tests for fixes in kmet.agent.tools."
  (:require [clojure.test :as t]
            [clojure.java.io :as io]
            [kmet.agent.tools :as tools]))

;; ─── Tool schemas: no per-property :optional ─────────────────────────────

(t/deftest test-schema-no-optional
  (doseq [tool-name ["read" "write" "edit" "bash" "grep" "find" "ls"]]
    (let [tool (tools/get-tool tool-name)
          params (:parameters tool)
          props (:properties params)]
      (doseq [[k v] props]
        (t/is (not (contains? v :optional))
          (str "Property " k " of " tool-name " should not have :optional"))))))

;; ─── Safe file traversal (file-seq replacement) ──────────────────────────

(t/deftest test-safe-file-seq-basic
  ;; find tool should work on a normal directory
  (let [result (tools/execute-tool "find" {:pattern "test" :path "src"})]
    (t/is (not (:is-error result)) "find should work on src dir")
    (t/is (string? (:content result)))))

(t/deftest test-safe-file-seq-grep
  ;; grep tool should work on a normal directory
  (let [result (tools/execute-tool "grep" {:pattern "defn" :path "src"})]
    (t/is (not (:is-error result)) "grep should work on src dir")
    (t/is (string? (:content result)))))

;; ─── Bash: large stderr output (deadlock regression) ────────────────────

(t/deftest test-bash-large-stderr
  (let [result (tools/execute-tool "bash"
                {:command "for i in $(seq 1 200); do echo err$i >&2; done; echo ok"})]
    (t/is (not (:is-error result)) "Large stderr should not deadlock")
    (t/is (.contains (:content result) "ok"))))

(t/deftest test-bash-large-stdout
  (let [result (tools/execute-tool "bash"
                {:command "for i in $(seq 1 200); do echo out$i; done; echo done"})]
    (t/is (not (:is-error result)) "Large stdout should not deadlock")
    (t/is (.contains (:content result) "done"))))

(t/deftest test-bash-stdout-and-stderr-merged
  (let [result (tools/execute-tool "bash" {:command "echo hello; echo world >&2"})]
    (t/is (not (:is-error result)))
    (t/is (.contains (:content result) "hello"))
    (t/is (.contains (:content result) "world"))))

;; ─── grep/find on small trees with unreadable files ─────────────────────

(t/deftest test-safe-file-seq-graceful
  ;; Create a small tree with a readable subdir
  (.mkdirs (io/file "target/test-tools-reg/sub"))
  (spit "target/test-tools-reg/sub/a.txt" "hello world")
  (spit "target/test-tools-reg/b.txt" "foo bar")
  (let [result (tools/execute-tool "grep" {:pattern "hello" :path "target/test-tools-reg"})]
    (t/is (not (:is-error result)))
    (t/is (.contains (:content result) "a.txt")))
  ;; Cleanup
  (.delete (io/file "target/test-tools-reg/sub/a.txt"))
  (.delete (io/file "target/test-tools-reg/sub"))
  (.delete (io/file "target/test-tools-reg/b.txt"))
  (.delete (io/file "target/test-tools-reg")))

;; ─── Bash process builder (no Runtime.exec) ─────────────────────────────

(t/deftest test-bash-basic
  (let [result (tools/execute-tool "bash" {:command "printf hello"})]
    (t/is (not (:is-error result)))
    (t/is (= "hello" (:content result)))))

(t/deftest test-bash-exit-code
  (let [result (tools/execute-tool "bash" {:command "exit 42"})]
    (t/is (:is-error result) "Non-zero exit should be error")))
