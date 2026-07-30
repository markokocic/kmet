(ns kmet.runner
  (:require [clojure.test :as t]
            [kmet.test-utils]
            [kmet.test-keys]
            [kmet.test-session]
            [kmet.test-tools]
            [kmet.test-tools-regression]
            [kmet.test-llm]
            [kmet.test-loop]
            [kmet.test-loop-regression]
            [kmet.test-theme]
            [kmet.test-config]
            [kmet.test-config-regression]
            [kmet.test-skills]
            [kmet.test-editing]
            [kmet.tui.components.test-text]
            [kmet.tui.components.test-spacer]
            [kmet.tui.components.test-container]
            [kmet.tui.components.test-box]
            [kmet.tui.components.test-input]
            [kmet.tui.components.test-editor]
            [kmet.tui.components.test-select-list]
            [kmet.tui.components.test-settings-list]
            [kmet.tui.components.test-markdown]
            [kmet.tui.test-terminal-image]
            [kmet.agent.ui.test-chat-history]
            [kmet.agent.ui.test-user-message]
            [kmet.agent.ui.test-assistant-message]
            [kmet.agent.ui.test-tool-execution]
            [kmet.agent.ui.test-custom-message]
            [kmet.agent.ui.test-footer]))

(defn -main [& _args]
  (let [namespaces '[kmet.test-utils kmet.test-keys
                     kmet.test-session kmet.test-tools
                     kmet.test-tools-regression
                     kmet.test-llm kmet.test-loop
                     kmet.test-loop-regression
                     kmet.test-theme kmet.test-config
                     kmet.test-config-regression
                     kmet.test-skills
                     kmet.test-editing
                     kmet.tui.components.test-text
                     kmet.tui.components.test-spacer
                     kmet.tui.components.test-container
                     kmet.tui.components.test-box
                     kmet.tui.components.test-input
                     kmet.tui.components.test-editor
                     kmet.tui.components.test-select-list
                     kmet.tui.components.test-settings-list
                     kmet.tui.components.test-markdown
                     kmet.tui.test-terminal-image
                     kmet.agent.ui.test-chat-history
                     kmet.agent.ui.test-user-message
                     kmet.agent.ui.test-assistant-message
                     kmet.agent.ui.test-tool-execution
                     kmet.agent.ui.test-custom-message
                     kmet.agent.ui.test-footer]
        results (apply t/run-tests namespaces)]
    (println "\\nResults:" (:pass results) "passed," (:fail results) "failed," (:error results) "errors")
    (System/exit (if (pos? (+ (:fail results) (:error results))) 1 0))))
