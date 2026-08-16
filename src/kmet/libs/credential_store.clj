(ns kmet.libs.credential-store
  "Generic EDN credential store under file lock. Provides read/write primitives
   for EDN map files (auth.edn, mcp-oauth.edn, etc.) with optional entry
   validation and file-lock serialization.

   The caller owns the atom and the validate fn — this lib is purely about
   the file I/O and lock mechanics."
  (:require [clojure.edn :as edn]
            [babashka.fs :as fs]
            [kmet.libs.edn-settings :as eds]
            [kmet.libs.file-lock :as file-lock]))

(defn read-edn-map
  "Parse an EDN file as a map, nil when missing or malformed. When VALIDATE
   is provided, only entries passing it are kept (invalid entries are
   silently dropped — startup leniency)."
  ([path] (read-edn-map path nil))
  ([path validate]
   (let [f (fs/file path)]
     (when (fs/exists? f)
       (try (let [parsed (edn/read-string (slurp f))]
              (when (map? parsed)
                (if validate
                  (into {} (filter (fn [[_ v]] (validate v))) parsed)
                  parsed)))
            (catch Exception _ nil))))))

(defn write-edn-map!
  "Write a map to PATH as pretty EDN, under a file lock. Creates parent
   directories as needed."
  [path m]
  (fs/create-dirs (fs/parent path))
  (file-lock/with-file-lock (str path ".lock")
    (fn [] (spit path (eds/pretty-edn m)))))

(defn update-edn-map!
  "Read-modify-write: apply F to the current map on disk, persist the result
   under the file lock, return the new map. The read and write are both
   inside the lock so concurrent callers can't lose each other's updates.
   When VALIDATE is provided, the read pass filters entries through it."
  ([path f] (update-edn-map! path f nil))
  ([path f validate]
   (fs/create-dirs (fs/parent path))
   (file-lock/with-file-lock (str path ".lock")
     (fn []
       (let [current (or (read-edn-map path validate) {})
             updated (f current)]
         (spit path (eds/pretty-edn updated))
         updated)))))
