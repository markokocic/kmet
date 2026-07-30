(ns kmet.app.tools.bash
  "Bash tool implementation — delegates to kmet.app.bash-executor for execution.
   Pi: bash tool wraps createLocalBashOperations, same engine as ! commands."
  (:require [clojure.string :as str]
            [kmet.app.bash-executor :as bash-exec]))

(defn execute
  "Execute a bash command via the shared bash executor.
   Returns {:content str :is-error bool :truncation map?}
   matching the expected tool result format."
  [{:keys [command timeout]}]
  (try
    (let [result (bash-exec/execute-bash
                   {:command command
                    :cwd (or (System/getProperty "user.dir") ".")
                    :timeout timeout  ;; nil = no timeout (pi: optional, no default)
                    :on-chunk nil  ;; no streaming for LLM tool calls
                    :max-lines bash-exec/DEFAULT-MAX-LINES
                    :max-bytes bash-exec/DEFAULT-MAX-BYTES})
          {:keys [output exit-code cancelled truncated full-output-path]} result]
      (if cancelled
        {:content (str output "\n\nCommand cancelled.") :is-error true}
        (let [is-error (and exit-code (not= exit-code 0))
              content (if is-error
                        (str output "\n\nCommand exited with code " exit-code)
                        output)]
          (if truncated
            {:content content
             :is-error is-error
             :truncation {:total-lines (count (str/split-lines output))
                          :shown-lines bash-exec/DEFAULT-MAX-LINES
                          :full-output-path full-output-path}}
            {:content content
             :is-error is-error}))))
    (catch Exception e
      (let [msg (.getMessage e)]
        (if (str/includes? msg "timeout")
          {:content (str "Command timed out after " (or timeout "?") "s") :is-error true}
          {:content (str "Error: " msg) :is-error true})))))
