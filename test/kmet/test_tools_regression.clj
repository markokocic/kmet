(ns kmet.test-tools-regression
  "Regression tests for fixes in kmet.agent.tools."
  (:require [clojure.test :as t]
            [clojure.java.io :as io]
            [kmet.agent.tools :as tools]))

;; ─── Tool schemas: no per-property :optional ─────────────────────────────

(t/deftest test-schema-no-optional
  (doseq [tool-name ["read" "write" "edit" "bash"]]
    (let [tool (tools/get-tool tool-name)
          params (:parameters tool)
          props (:properties params)]
      (doseq [[k v] props]
        (t/is (not (contains? v :optional))
          (str "Property " k " of " tool-name " should not have :optional"))))))


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



;; ─── Bash process builder (no Runtime.exec) ─────────────────────────────

(t/deftest test-bash-basic
  (let [result (tools/execute-tool "bash" {:command "printf hello"})]
    (t/is (not (:is-error result)))
    (t/is (= "hello" (:content result)))))

(t/deftest test-bash-exit-code
  (let [result (tools/execute-tool "bash" {:command "exit 42"})]
    (t/is (:is-error result) "Non-zero exit should be error")))
