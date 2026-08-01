(ns kmet.app.tools.write
  "Write tool implementation — create or overwrite files.
   Pi: write.ts — success message matches pi's wording."
  (:require [clojure.java.io :as io]
            [babashka.fs :as fs]))

(defn execute
  "Write content to a file (create or overwrite)."
  [{:keys [path content]}]
  (try
    (let [f (io/file path)]
      ;; Pi: mkdir(dir, {recursive: true}) — skip when the path has no parent
      (when-let [parent (fs/parent f)]
        (fs/create-dirs parent))
      (spit f content)
      {:content (str "Successfully wrote " (count content) " bytes to " path)})
    (catch Exception e
      {:content (str "Error writing to " path ": " (ex-message e)) :is-error true})))
