(ns kmet.app.tools.ls
  "Ls tool implementation — list directory contents."
  (:require [clojure.string :as str]
            [clojure.java.io :as io]
            [babashka.fs :as fs]))

(defn execute
  "List directory contents."
  [{:keys [path long?]}]
  (try
    (let [dir (if path (io/file path) (io/file "."))]
      (if-not (fs/directory? dir)
        {:content (str "Not a directory: " path) :is-error true}
        (let [entries (fs/list-dir dir)
              sorted (sort-by fs/file-name entries)
              result (str/join "\n"
                               (map (fn [f]
                                      (let [name (fs/file-name f)
                                            type (if (fs/directory? f) "d" "-")
                                            size (try (fs/size f) (catch Exception _ 0))]
                                        (if long?
                                          (str type " " (format "%10d" size) " " name)
                                          name)))
                                    sorted))]
          {:content (str "Contents of " (fs/canonicalize dir) ":\n" result)})))
    (catch Exception e
      {:content (str "Error listing: " (ex-message e)) :is-error true})))
