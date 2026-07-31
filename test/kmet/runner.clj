(ns kmet.runner
  (:require [clojure.test :as t]
            [kmet.test-utils]
            [kmet.test-keys]
            [kmet.app.test-session]
            [kmet.app.test-tools]
            [kmet.app.test-llm]
            [kmet.app.test-loop]
            [kmet.test-theme]
            [kmet.test-config]
            [kmet.app.test-skills]
            [kmet.app.test-commands]
            [kmet.test-editing]
            [kmet.tui.test-fuzzy]
            [kmet.tui.test-autocomplete]
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
            [kmet.app.ui.test-chat-history]
            [kmet.app.ui.test-user-message]
            [kmet.app.ui.test-assistant-message]
            [kmet.app.ui.test-tool-execution]
            [kmet.app.ui.test-custom-message]
            [kmet.app.ui.test-footer]))

(defn -main [& _args]
  (let [namespaces '[kmet.test-utils kmet.test-keys
                     kmet.app.test-session kmet.app.test-tools
                     kmet.app.test-llm kmet.app.test-loop
                     kmet.test-theme kmet.test-config
                     kmet.app.test-skills
                     kmet.app.test-commands
                     kmet.test-editing
                     kmet.tui.test-fuzzy
                     kmet.tui.test-autocomplete
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
                     kmet.app.ui.test-chat-history
                     kmet.app.ui.test-user-message
                     kmet.app.ui.test-assistant-message
                     kmet.app.ui.test-tool-execution
                     kmet.app.ui.test-custom-message
                     kmet.app.ui.test-footer]
        results (apply t/run-tests namespaces)]
    (println "\nResults:" (:pass results) "passed," (:fail results) "failed," (:error results) "errors")
    (System/exit (if (pos? (+ (:fail results) (:error results))) 1 0))))
