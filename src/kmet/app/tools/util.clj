(ns kmet.app.tools.util
  "Shared utilities for tool implementations — safe file traversal, etc."
  (:require [babashka.fs :as fs]))

(def ^:private max-traverse-files 10000)

(defn safe-file-seq
  "Like file-seq but with symlink cycle protection and a max-files limit."
  [dir-path]
  (let [visited (atom #{})]
    (take max-traverse-files
      (filter fs/regular-file?
        (tree-seq
          (fn [f]
            (and (fs/directory? f)
                 (let [cp (fs/canonicalize f)]
                   (when-not (contains? @visited cp)
                     (swap! visited conj cp)
                     true))))
          (fn [d] (fs/list-dir d))
          (fs/file dir-path))))))
