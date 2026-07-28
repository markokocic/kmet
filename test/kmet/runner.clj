(ns kmet.runner
  (:require [clojure.test :as t]
            [kmet.test-utils]
            [kmet.test-keys]
            [kmet.test-session]
            [kmet.test-tools]
            [kmet.test-llm]
            [kmet.test-loop]
            [kmet.tui.components.test-text]
            [kmet.tui.components.test-spacer]
            [kmet.tui.components.test-container]
            [kmet.tui.components.test-box]
            [kmet.tui.components.test-input]
            [kmet.tui.components.test-editor]
            [kmet.tui.components.test-select-list]
            [kmet.tui.components.test-settings-list]
            [kmet.tui.components.test-markdown]))

(defn -main [& _args]
  (let [namespaces '[kmet.test-utils kmet.test-keys
                     kmet.test-session kmet.test-tools
                     kmet.test-llm kmet.test-loop
                     kmet.tui.components.test-text
                     kmet.tui.components.test-spacer
                     kmet.tui.components.test-container
                     kmet.tui.components.test-box
                     kmet.tui.components.test-input
                     kmet.tui.components.test-editor
                     kmet.tui.components.test-select-list
                     kmet.tui.components.test-settings-list
                     kmet.tui.components.test-markdown]
        results (apply t/run-tests namespaces)]
    (println "\\nResults:" (:pass results) "passed," (:fail results) "failed," (:error results) "errors")
    (System/exit (if (pos? (+ (:fail results) (:error results))) 1 0))))
