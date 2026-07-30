(ns kmet.agent.tools.find
  "Find tool implementation — find files matching a pattern."
  (:require [clojure.string :as str]
            [clojure.java.io :as io]
            [babashka.fs :as fs]
            [kmet.agent.tools.protocol :as proto]))

(defn execute
  "Find files matching a pattern."
  [{:keys [pattern path]}]
  (try
    (let [dir (if path (io/file path) (io/file "."))
          results (volatile! [])]
      (doseq [file (proto/safe-file-seq dir)]
        (let [name (fs/file-name file)]
          (when (or (re-find (re-pattern pattern) name)
                    (re-find (re-pattern pattern) (str file)))
            (vswap! results conj (str file)))))
      (let [r @results]
        (if (empty? r)
          {:content (str "No files matching \"" pattern "\"")}
          {:content (str/join "\n" (take 200 r))
           :truncated (> (count r) 200)})))
    (catch Exception e
      {:content (str "Error finding: " (.getMessage e)) :is-error true})))
