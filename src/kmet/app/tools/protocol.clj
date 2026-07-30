(ns kmet.app.tools.protocol
  "Shared types and utilities for the tool system.
   Tool record definition, parameter helpers, file traversal, constants."
  (:require [clojure.string :as str]
            [babashka.fs :as fs]))

;; ─── Constants ────────────────────────────────────────────────────────────

;; Max output lines before bash output is truncated server-side.
(def MAX-BASH-OUTPUT-LINES 500)
(def TMP-PREFIX "kmet-bash-")
(def TMP-SUFFIX ".txt")

;; ─── Safe file traversal ──────────────────────────────────────────────────

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

;; ─── Tool record ────────────────────────────────────────────────────────────

(defrecord Tool [name label description prompt-snippet prompt-guidelines parameters execute render-call render-result])

;; ─── Parameter helpers ──────────────────────────────────────────────────────

(defn param
  "Define a tool parameter for JSON schema generation."
  [name type description & {:keys [optional?]}]
  (merge {:type type :description description}
         (when optional? {:optional true})))

(defn ->json-schema
  "Convert a map of param definitions to a JSON schema map."
  [params]
  {:type "object"
   :properties (reduce-kv (fn [m k v]
                            (assoc m (name k)
                              {:type (name (:type v))
                               :description (:description v)}))
                          {} params)
   :required (vec (->> params (remove #(:optional (val %))) (map key) (map name)))})
