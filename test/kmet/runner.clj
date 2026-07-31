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
            [kmet.app.ui.test-bash-execution]
            [kmet.app.ui.test-footer]))

(def fast-namespaces
  "Fast suites — run on every `bb test`. Excludes the slow timing/process
   suites (kmet.app.test-loop, kmet.app.test-tools) which run in test-ext."
  '[kmet.test-utils kmet.test-keys
    kmet.app.test-session
    kmet.app.test-llm
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
    kmet.app.ui.test-bash-execution
    kmet.app.ui.test-footer])

(def ext-namespaces
  "Full suite — slow outliers included. Run via `bb test-ext` for final
   validation before commit."
  (vec (concat fast-namespaces
               '[kmet.app.test-tools kmet.app.test-loop])))

(defn -main
  "Run the test suites. Optional mode arg: \"ext\" runs the full suite
   (including slow outliers); anything else runs the fast suites only."
  [& [mode]]
  (let [namespaces (if (= mode "ext") ext-namespaces fast-namespaces)
        results (apply t/run-tests namespaces)]
    (println "\nResults:" (:pass results) "passed," (:fail results) "failed," (:error results) "errors")
    (System/exit (if (pos? (+ (:fail results) (:error results))) 1 0))))
