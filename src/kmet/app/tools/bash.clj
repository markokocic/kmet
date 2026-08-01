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
          {:keys [output exit-code cancelled truncated truncation full-output-path timed-out]} result]
      ;; Pi: [Showing lines X-Y of Z. Full output: path] footer for the LLM
      ;; (stripped from the TUI display by the bash render-result, which
      ;; shows the truncation warning instead)
      (let [footer (when (and truncation full-output-path)
                     (let [total-lines (:total-lines truncation)
                           output-lines (:output-lines truncation)
                           start-line (+ (- total-lines output-lines) 1)
                           end-line total-lines
                           limit-str (when (= (:truncated-by truncation) :bytes)
                                       (str " (" (bash-exec/format-size
                                                   (or (:max-bytes truncation)
                                                       bash-exec/DEFAULT-MAX-BYTES))
                                            " limit)"))]
                       (str "\n\n[Showing lines " start-line "-" end-line " of " total-lines
                            limit-str ". Full output: " full-output-path "]")))]
        (if cancelled
          ;; Pi: appendStatus — only adds \n\n separator when there is output
          {:content (str (when (seq (str output footer)) (str output footer "\n\n")) "Command aborted")
           :is-error true}
          (let [is-error (or timed-out (and exit-code (not= exit-code 0)))
                ;; Pi: formatOutput — empty successful output shows "(no output)"
                base (if (and (not is-error) (empty? output)) "(no output)" output)
                ;; Pi: appendStatus(outputText, status) — status follows the output
                status (cond
                         timed-out (str "Command timed out after " (or timeout "?") " seconds")
                         is-error (str "Command exited with code " exit-code)
                         :else nil)
                content (if status
                          (str (when (seq (str base footer))
                                 (str base footer "\n\n"))
                               status)
                          (str base footer))]
            (if truncated
              ;; Pi: details are lost when the tool throws, so errors carry no
              ;; truncation metadata (the footer stays in the content instead)
              (cond-> {:content content
                       :is-error is-error}
                (not is-error)
                (assoc :truncation {:total-lines (:total-lines truncation)
                                    :total-bytes (:total-bytes truncation)
                                    :shown-lines (:output-lines truncation)
                                    :truncated-by (:truncated-by truncation)
                                    :max-bytes (:max-bytes truncation)
                                    :max-lines (:max-lines truncation)
                                    :full-output-path full-output-path}))
              {:content content
               :is-error is-error})))))
    (catch Exception e
      (let [msg (ex-message e)]
        (if (str/includes? msg "timeout")
          {:content (str "Command timed out after " (or timeout "?") " seconds") :is-error true}
          {:content (str "Error: " msg) :is-error true})))))
