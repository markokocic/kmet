(ns kmet.modes.test-print
  "Print-mode tests (pi: print-mode.ts) — run() sends the message through
   the agent loop and returns the final text via :on-done (nil on error)."
  (:require [clojure.test :as t :refer [deftest is testing]]
            [kmet.modes.print :as print-mode]
            [kmet.app.loop :as agent]))

(defn- opts
  "Minimal run opts — :model/:provider bypass catalog resolution."
  []
  {:model "deepseek-v4-flash"
   :provider :opencode-go
   :messages ["hi"]
   :config {:provider :opencode-go :model "deepseek-v4-flash"}})

(deftest test-run-delivers-final-text
  (testing "run returns the agent's final text delivered via :on-done"
    (with-redefs [agent/run-agent-turn (fn [_ ag-opts]
                                         ((:on-done ag-opts) "hello world"))]
      (is (= "hello world" (print-mode/run (opts)))))))

(deftest test-run-error-returns-nil
  (testing "run returns nil when the agent reports an error"
    ;; print-mode surfaces the error to stderr — capture it in the test.
    (binding [*err* (java.io.StringWriter.)]
      (with-redefs [agent/run-agent-turn (fn [_ ag-opts]
                                           ((:on-error ag-opts) (ex-info "boom" {})))]
        (is (nil? (print-mode/run (opts))))))))

(deftest test-run-passes-message
  (testing "the joined user message reaches the agent"
    (let [seen (atom nil)]
      (with-redefs [agent/run-agent-turn (fn [_ ag-opts]
                                           (reset! seen (:message ag-opts))
                                           ((:on-done ag-opts) "ok"))]
        (print-mode/run (assoc (opts) :messages ["hello" "world"]))
        (is (= "hello world" @seen))))))
