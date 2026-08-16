(ns extensions.mcp-adapter.config
  "EDN MCP config loading/merging for the mcp-adapter extension (§6 of the
   design contract, extensions/mcp-adapter/mcp-adapter.md — pi:
   config.ts loadMcpConfig / writeProjectServerDisabledOverride, adapted to
   kmet: EDN only, exactly two sources, no imports/host discovery).

   Sources & precedence:
     - global  ~/.kmet/agent/mcp.edn   (lower)
     - project <cwd>/.kmet/mcp.edn     (higher — the only file this
                                        extension writes)

   Project wins with a per-field server merge + per-key settings merge.
   Credential-bearing fields are bound to the url that supplied them: when
   a higher-precedence source repoints a server at a different url, the
   lower entry's :headers/:bearer-token/:bearer-token-env/:oauth are
   dropped before merging (pi mergeServerMaps SECURITY note).

   Keys are read in camel or kebab form (see normalize-key) so a file
   copied from a pi-style JSON config works with light edits; unknown keys
   pass through unmodified. :lifecycle / :tool-prefix values accept string
   or keyword."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]))

(defn- read-text
  "The text of PATH, or nil when the file does not exist."
  [path]
  (when (fs/exists? path)
    (slurp path)))

(defn- write-text
  "Write TEXT to PATH (UTF-8), replacing any existing content."
  [path text]
  (spit path (str text)))

(def global-config-path
  "Global config file (~/.kmet/agent/mcp.edn)."
  (delay (str (fs/home) "/.kmet/agent/mcp.edn")))

(defn project-config-path
  "Project config file (<cwd>/.kmet/mcp.edn) — the only file the extension
   writes."
  [& [cwd]]
  (str (or cwd (System/getProperty "user.dir")) "/.kmet/mcp.edn"))

;; ─── Key normalization (§6.3) ─────────────────────────────────────────────

(def ^:private key-aliases
  "Camel → kebab aliases for known keys. Unknown keys pass through
   unmodified."
  {:mcpServers :mcp-servers
   :directTools :direct-tools
   :toolPrefix :tool-prefix
   :requestTimeoutMs :request-timeout-ms
   :bearerToken :bearer-token
   :bearerTokenEnv :bearer-token-env
   :httpTransport :http-transport
   :disableProxyTool :disable-proxy-tool
   :clientId :client-id
   :clientSecret :client-secret
   :redirectUri :redirect-uri
   :authorizationServerUrl :authorization-server-url
   :skipIssuerMetadataValidation :skip-issuer-metadata-validation
   :grantType :grant
   :privateKeyFile :private-key-file
   :privateKeyJwk :private-key-jwk
   :tokenEndpoint :token-endpoint
   :tokenEndpointAuthMethod :token-endpoint-auth-method})

(defn- normalize-key
  "CamelCase known keys → kebab; everything else passes through (a kebab
   key that is also a known alias stays kebab)."
  [k]
  (if (keyword? k)
    (or (key-aliases k) k)
    k))

(defn- normalize-value
  "Keyword/string keyword-valued options (:lifecycle, :tool-prefix,
   :http-transport, :auth, :flow) → keyword."
  [v]
  (cond
    (keyword? v) v
    (string? v) (keyword v)
    :else v))

(def ^:private keyword-valued-keys
  #{:lifecycle :tool-prefix :http-transport :auth :flow :grant :algorithm
    :token-endpoint-auth-method})

(defn- normalize-oauth
  "Normalize an :oauth map (nil → nil, false → false)."
  [oauth]
  (cond
    (nil? oauth) nil
    (false? oauth) false
    (map? oauth)
    (into {}
          (keep (fn [[k v]]
                  (let [k (normalize-key k)]
                    (when (some? v)
                      [k (if (keyword-valued-keys k)
                           (normalize-value v)
                           v)]))))
          oauth)
    :else oauth))

(defn- normalize-server
  "Normalize one server entry: known camel keys → kebab, keyword-valued
   options → keywords, :oauth map normalization. Unknown keys pass through."
  [entry]
  (if (map? entry)
    (into {}
          (keep (fn [[k v]]
                  (let [k (normalize-key k)]
                    (when (some? v)
                      (if (= k :oauth)
                        [k (normalize-oauth v)]
                        (if (keyword-valued-keys k)
                          [k (normalize-value v)]
                          [k v]))))))
          entry)
    entry))

(defn- normalize-settings
  [settings]
  (when (map? settings)
    (into {} (map (fn [[k v]]
                    (let [k (normalize-key k)]
                      [k (if (keyword-valued-keys k) (normalize-value v) v)])))
          settings)))

(defn- normalize-config
  "Normalize a raw config map: keys at the top level (:mcp-servers /
   :settings), server entries, and the :oauth sub-map."
  [raw]
  (when (map? raw)
    (let [raw (into {} (map (fn [[k v]] [(normalize-key k) v])) raw)
          settings (normalize-settings (:settings raw))]
      (cond-> {}
        (:mcp-servers raw) (assoc :mcp-servers
                                  (into {} (map (fn [[name entry]]
                                                  [name (normalize-server entry)]))
                                        (:mcp-servers raw)))
        settings (assoc :settings settings)))))

;; ─── Reading ──────────────────────────────────────────────────────────────

(defn read-config-file
  "Read + normalize one EDN config file. Returns nil when the file does
   not exist; throws ex-info (with the path) on a parse error or a
   non-map root."
  [path]
  (when (fs/exists? path)
    (let [raw (try
                (edn/read-string {:default (fn [_ _] nil)} (read-text path))
                (catch Exception e
                  (throw (ex-info (str "Failed to read MCP config at " path
                                       ": " (ex-message e))
                                  {:type :mcp-config-parse-error :path path}
                                  e))))]
      (when-not (map? raw)
        (throw (ex-info (str "Failed to read MCP config at " path
                             ": root value must be a map")
                        {:type :mcp-config-parse-error :path path})))
      (normalize-config raw))))

;; URL-bound auth fields: when a higher-precedence source repoints a server
;; at a different url, these MUST NOT be inherited from the lower entry —
;; otherwise the original endpoint's credentials would ship to the new url.
(def ^:private url-bound-auth-fields
  [:headers :bearer-token :bearer-token-env :oauth])

(defn- merge-server-maps
  "Per-field server merge (pi mergeServerMaps): later sources win per field.
   When the higher entry supplies a different :url, the lower entry's
   url-bound auth material is dropped first."
  [base next]
  (reduce (fn [merged [name definition]]
            (let [existing (get merged name)
                  base-entry (cond
                               (and existing (string? (:url definition))
                                    (not= (:url definition) (:url existing)))
                               (apply dissoc existing url-bound-auth-fields)

                               :else existing)]
              (assoc merged name
                     (merge base-entry definition))))
          base
          next))

(defn- merge-settings
  "Per-key settings merge (later wins)."
  [base next]
  (if (or base next) (merge base next) nil))

(defn- merge-configs
  "Merge two normalized configs (pi mergeConfigs)."
  [base next]
  (cond-> {}
    (or (:mcp-servers base) (:mcp-servers next))
    (assoc :mcp-servers (merge-server-maps (:mcp-servers base) (:mcp-servers next)))
    (or (:settings base) (:settings next))
    (assoc :settings (merge-settings (:settings base) (:settings next)))))

(defn load-config
  "Load + merge the global and project EDN configs (§6.1). Returns
   {:mcp-servers {name entry} :settings {...}} — empty maps when neither
   file exists. A parse error in either file throws (with the path)."
  []
  (let [global (read-config-file @global-config-path)
        project (read-config-file (project-config-path))
        base (or global {:mcp-servers {}})]
    (merge-configs base project)))

;; ─── Template (§6.4) ──────────────────────────────────────────────────────

(def ^:private template-edn
  "Schema-commented starter config written to the global file on first
   init."
  (str "{:settings {:direct-tools false           ;; global default for direct tools\n"
       "            :tool-prefix :server          ;; :server | :none | :short | :mcp\n"
       "            :disable-proxy-tool false}\n"
       " :mcp-servers\n"
       " {\"filesystem\" {:command \"npx\"\n"
       "                 :args [\"-y\" \"@modelcontextprotocol/server-filesystem\" \"/tmp\"]\n"
       "                 :lifecycle :lazy}        ;; :lazy | :eager | :keep-alive\n"
       "  ;; \"remote\" {:url \"https://mcp.example.com/mcp\"\n"
       "  ;;            :auth :bearer               ;; :bearer | :oauth | false\n"
       "  ;;            :bearer-token-env \"MY_TOKEN\" ;; or :bearer-token / :headers\n"
       "  ;;            :http-transport :streamable-http ;; :streamable-http | :sse\n"
       "  ;;            :lifecycle :lazy}\n"
       "  ;; \"notion\" {:url \"https://mcp.notion.com/mcp\"\n"
       "  ;;            :auth :oauth\n"
       "  ;;            :oauth {:flow :auto        ;; :auto | :pkce | :device\n"
       "  ;;                    :scopes [\"read\"]}}\n"
       "  ;; \"service\" {:url \"https://mcp.example.com/mcp\"\n"
       "  ;;            :auth :oauth\n"
       "  ;;            :oauth {:grant :client-credentials ;; machine grant, no browser\n"
       "  ;;                    :client-id \"svc\"          ;; auth: :client-secret-basic\n"
       "  ;;                    :client-secret \"...\"}}   ;; | :client-secret-post | :none\n"
       "  ;; \"svc-jwt\" {:url \"https://mcp.example.com/mcp\"\n"
       "  ;;            :auth :oauth\n"
       "  ;;            :oauth {:grant :jwt-bearer        ;; RFC 7523 signed assertion\n"
       "  ;;                    :private-key-file \"svc.pem\" ;; PKCS#8/PKCS#1 PEM, or\n"
       "  ;;                    :issuer \"kmet\"            ;; :private-key-jwk {..}\n"
       "  ;;                    :audience \"https://as.example/token\"}}\n"
       " }}\n"))

(defn ensure-global-template!
  "Write the schema-commented starter template to the global mcp.edn when
   it does not exist yet (§6.4). Returns the path when written, nil when
   the file already exists."
  []
  (let [path @global-config-path]
    (when-not (fs/exists? path)
      (fs/create-dirs (fs/parent path))
      (write-text path template-edn)
      path)))

;; ─── enable/disable write (§6.5) ──────────────────────────────────────────

(defn- read-project-raw
  "The raw (un-normalized) project config map; {} when missing. Throws with
   the path on a parse error (pi writeProjectServerDisabledOverride)."
  []
  (let [path (project-config-path)]
    (if-not (fs/exists? path)
      {}
      (let [raw (try
                  (edn/read-string {:default (fn [_ _] nil)} (read-text path))
                  (catch Exception e
                    (throw (ex-info (str "Failed to read project MCP override at "
                                         path ": " (ex-message e))
                                    {:type :mcp-config-parse-error :path path}
                                    e))))]
        (when-not (map? raw)
          (throw (ex-info (str "Failed to read project MCP override at " path
                               ": root value must be a map")
                          {:type :mcp-config-parse-error :path path})))
        raw))))

(defn- lower-config-for
  "The merged config from every source EXCEPT the project file (pi: the
   lower-precedence sources)."
  []
  (or (read-config-file @global-config-path) {:mcp-servers {}}))

(defn set-server-disabled!
  "Port of pi's writeProjectServerDisabledOverride, onto EDN (§6.5):

     Disable: set :disabled true on the server entry (create entry if
     absent).
     Enable:  remove the :disabled key; if the merged *lower* config
     (global file) has the server disabled, write :disabled false instead;
     if the entry is now empty, remove the server key.

   Writes pretty EDN (pr-str, trailing newline). Returns
   {:path str :changed bool} — :changed false when the write would be a
   no-op (entry already in the target state). The command tells the user to
   run /reload to apply."
  [server-name disabled]
  (let [path (project-config-path)
        raw (read-project-raw)
        servers-key (if (contains? raw :mcp-servers)
                      :mcp-servers
                      (if (contains? raw :mcpServers) :mcpServers :mcp-servers))
        servers (get raw servers-key {})
        existing (get servers server-name)
        _ (when (and (some? existing) (not (map? existing)))
            (throw (ex-info (str "Failed to update project MCP override at " path
                                 ": server \"" server-name "\" must be a map")
                            {:type :mcp-config-parse-error :path path})))
        next (if disabled
               (assoc (or existing {}) :disabled true)
               (let [cleaned (dissoc (or existing {}) :disabled)]
                 (if (:disabled (get-in (lower-config-for) [:mcp-servers server-name]))
                   (assoc cleaned :disabled false)
                   cleaned)))
        changed? (not= (or existing {}) next)]
    (when changed?
      ;; the project .kmet/ dir may not exist yet — create it before writing
      (fs/create-dirs (fs/parent path)))
    (if (and changed? (not (seq next)))
      (do (write-text path (str (pr-str (update raw servers-key dissoc server-name)) "\n"))
          {:path path :changed true})
      (if changed?
        (do (write-text path (str (pr-str (assoc-in raw [servers-key server-name] next)) "\n"))
            {:path path :changed true})
        {:path path :changed false}))))
