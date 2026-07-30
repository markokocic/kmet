(ns kmet.app.tools.bash
  "Bash tool implementation — execute shell commands with timeout and truncation."
  (:require [clojure.string :as str]
            [babashka.process :as proc]
            [kmet.app.tools.protocol :as proto]))

(defn execute
  "Execute a bash command with optional timeout.
   When output exceeds MAX-BASH-OUTPUT-LINES, returns truncated content
   with a :truncation map referencing the full output in a temp file."
  [{:keys [command timeout]}]
  (try
    (let [timeout-ms (* (or timeout 30) 1000)
          p (proc/process ["sh" "-c" command]
              {:out :string :err :string})
          result (deref p timeout-ms ::timeout)]
      (if (= result ::timeout)
        (do (proc/destroy p)
            {:content (str "Command timed out after " (or timeout 30) "s")
             :is-error true})
        (let [full-output (str (:out result)
                               (when (seq (:err result))
                                 (str "\n" (:err result))))
              lines (str/split-lines full-output)
              total-lines (count lines)
              is-error (not= (:exit result) 0)]
          (if (<= total-lines proto/MAX-BASH-OUTPUT-LINES)
            {:content full-output
             :is-error is-error}
            ;; Truncate and save full output to temp file
            (let [tmp-file (java.io.File/createTempFile proto/TMP-PREFIX proto/TMP-SUFFIX)
                  tmp-path (str tmp-file)
                  shown-lines (take proto/MAX-BASH-OUTPUT-LINES lines)
                  truncated-content (str/join "\n" shown-lines)]
              (.deleteOnExit tmp-file)
              (spit tmp-path full-output)
              {:content truncated-content
               :is-error is-error
               :truncation {:total-lines total-lines
                            :shown-lines proto/MAX-BASH-OUTPUT-LINES
                            :full-output-path tmp-path}})))))
    (catch Exception e
      {:content (str "Error executing command: " (.getMessage e)) :is-error true})))
