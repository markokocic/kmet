(ns kmet.app.tools.write
  "Write tool implementation — create or overwrite files."
  (:require [clojure.java.io :as io]
            [babashka.fs :as fs]))

(defn execute
  "Write content to a file (create or overwrite)."
  [{:keys [path content]}]
  (try
    (let [f (io/file path)]
      (fs/create-dirs (fs/parent f))
      (spit f content)
      {:content (str "Written " (count content) " bytes to " path)})
    (catch Exception e
      {:content (str "Error writing to " path ": " (ex-message e)) :is-error true})))
