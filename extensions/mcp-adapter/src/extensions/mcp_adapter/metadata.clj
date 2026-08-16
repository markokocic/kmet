(ns extensions.mcp-adapter.metadata
  "Persistent MCP metadata cache (§8 of the design contract — pi:
   metadata-cache.ts, adapted to EDN).

   Path: ~/.kmet/agent/mcp-cache.edn
   Shape: {:version 1
           :servers {name {:config-fingerprint str
                           :fetched-at ms
                           :tools [{:name :description :inputSchema}]}}}

   Freshness: 7 days. server-entry returns nil when stale or the config
   fingerprint mismatches — callers fall back to a live connect. A config
   change (command/args/url/disabled/direct-tools/tool-prefix or the
   relevant settings) invalidates cached metadata.

   Writes merge with the existing file and go through temp-file + rename
   (atomic-ish, single process — no lock needed)."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]))

(defn- read-text
  [path]
  (when (fs/exists? path)
    (slurp path)))

(defn- write-text
  [path text]
  (spit path (str text)))

(def ^:private cache-version 1)
(def ^:private max-age-ms (* 7 24 60 60 1000))

(defn cache-path
  "The metadata cache file (~/.kmet/agent/mcp-cache.edn)."
  []
  (str (fs/home) "/.kmet/agent/mcp-cache.edn"))

(defn- read-edn
  "Read an EDN map from PATH; nil when missing or unparsable."
  [path]
  (try
    (when (fs/exists? path)
      (let [raw (edn/read-string {:default (fn [_ _] nil)} (read-text path))]
        (when (map? raw) raw)))
    (catch Exception _ nil)))

(defn load-cache
  "Load the cache map {:version 1 :servers {...}} or nil when missing /
   unparsable / wrong version."
  []
  (let [raw (read-edn (cache-path))]
    (when (and raw (= cache-version (:version raw)) (map? (:servers raw)))
      raw)))

(defn save-cache!
  "Merge ENTRY-MAP {:servers {name entry}} into the existing cache file and
   write atomically (temp file + rename)."
  [entry-map]
  (let [path (cache-path)
        existing (load-cache)
        merged {:version cache-version
                :servers (merge (:servers existing) (:servers entry-map))}
        tmp (str path ".tmp")]
    (fs/create-dirs (fs/parent path))
    (write-text tmp (pr-str merged))
    (fs/move tmp path {:replace-existing true})
    nil))

(defn config-fingerprint
  "Fingerprint of the config bits that affect which tools a server exposes
   (§8): the server name, :command/:args/:url/:disabled/:direct-tools/
   :tool-prefix, and the relevant settings. A config change invalidates
   cached metadata."
  [name definition settings]
  (pr-str [name
           (select-keys definition [:command :args :url :disabled
                                    :direct-tools :tool-prefix])
           (select-keys settings [:direct-tools :tool-prefix
                                  :disable-proxy-tool])]))

(defn server-entry
  "The cached tools entry for a server, or nil when missing / stale (7-day
   freshness) / fingerprint mismatch — callers fall back to a live
   connect."
  [cache name definition settings]
  (when cache
    (let [entry (get-in cache [:servers name])]
      (when (and entry
                 (= (config-fingerprint name definition settings)
                    (:config-fingerprint entry))
                 (number? (:fetched-at entry))
                 (< (- (System/currentTimeMillis) (:fetched-at entry))
                    max-age-ms))
        entry))))

(defn update-entry!
  "Persist a fresh tools entry for a server (also returned)."
  [cache name definition settings tools]
  (let [entry {:config-fingerprint (config-fingerprint name definition settings)
               :fetched-at (System/currentTimeMillis)
               :tools (vec (mapv (fn [t]
                                   (select-keys t [:name :description :inputSchema]))
                                 tools))}]
    (save-cache! {:servers {name entry}})
    (assoc-in (or cache {:version cache-version :servers {}})
              [:servers name] entry)))

(defn all-tools
  "Every cached tool across servers with fresh, non-disabled entries:
   [{:server str :tool {:name ... :description ... :inputSchema ...}}]."
  [cache config settings]
  (when cache
    (for [[name definition] (:mcp-servers config)
          :let [entry (server-entry cache name definition settings)]
          :when (and entry (not (true? (:disabled definition))))
          tool (:tools entry)]
      {:server name :tool tool})))
