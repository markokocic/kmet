(ns kmet.app.tools.edit
  "Edit tool implementation — precise text replacement in files."
  (:require [clojure.string :as str]
            [clojure.java.io :as io]
            [babashka.fs :as fs]))

(defn execute
  "Precise text replacement in a file."
  [{:keys [path old-text new-text]}]
  (try
    (let [f (io/file path)]
      (if-not (fs/exists? f)
        {:content (str "File not found: " path) :is-error true}
        (let [content (slurp f)
              idx (str/index-of content old-text)]
          (if (nil? idx)
            {:content (str "Could not find old-text in " path) :is-error true}
            (let [result (str/replace-first content old-text new-text)
                  replaced (count old-text)
                  new-len (count new-text)]
              (spit f result)
              {:content (str "Replaced " replaced " chars with " new-len " chars in " path)})))))
    (catch Exception e
      {:content (str "Error editing " path ": " (ex-message e)) :is-error true})))
