(ns extensions.mcp-adapter.proxy
  "The mcp proxy tool executor (§9 of the design contract — pi:
   proxy-modes.ts, Phase-1 subset: status/search/describe/call/connect/
   disconnect/list; no instructions/ui-messages/auth actions).

   Dispatch precedence (§9.2): search → describe → tool → connect →
   disconnect → list → server (list that server's tools) → status.
   Search/describe read the metadata cache only (no spawn); call/connect
   ensure a live connection. Every mode returns the kmet tool result shape
   {:content str :is-error bool}."
  (:require [clojure.string :as str]
            [extensions.mcp-adapter.auth :as auth]
            [extensions.mcp-adapter.client :as client]
            [extensions.mcp-adapter.metadata :as metadata]))

(def ^:private desc-truncate-length 50)
(def ^:private max-regex-query-length 256)
(def ^:private failure-backoff-ms 60000)

;; ─── Tool/state helpers ───────────────────────────────────────────────────

(defn- server-definition
  "The effective definition for a server (per-server merge over settings)."
  [state name]
  (get-in state [:config :mcp-servers name]))

(defn- settings
  [state]
  (:settings (:config state)))

(defn- server-state
  [state name]
  (get-in state [:servers name]))

(defn- failure-age-seconds
  "Seconds since the server's last recorded connect failure, or nil
   outside the 60s backoff window (pi getFailureAgeSeconds — a failed
   lazy use does not retry inside the window)."
  [state name]
  (when-let [failed-at @(get-in state [:servers name :failed-at])]
    (let [age (- (System/currentTimeMillis) failed-at)]
      (when (< age failure-backoff-ms)
        (quot age 1000)))))

(defn- disabled?
  [state name]
  (true? (:disabled (server-definition state name))))

(defn- misconfigured?
  "A server with neither :command nor :url (§6.2)."
  [definition]
  (and (not (:command definition)) (not (:url definition))))

(defn- truncate-at-word
  "Truncate S to LENGTH chars at a word boundary (pi truncateAtWord)."
  [s length]
  (let [s (or s "")]
    (if (<= (count s) length)
      s
      (let [cut (subs s 0 length)
            space (str/last-index-of cut " ")]
        (str (if space (subs cut 0 space) cut) "…")))))

(defn- cached-tools
  "The cached tools for a server (fresh + fingerprint-valid), or nil."
  [state name]
  (when-let [entry (metadata/server-entry (:cache state)
                                          name
                                          (server-definition state name)
                                          (settings state))]
    (:tools entry)))

;; ─── Tool naming (§10.5 pi getServerPrefix/formatToolName) ────────────────
;; The cache stores RAW MCP tool names; the user-facing surface (search/
;; describe/list output and the mcp({ tool }) parameter) uses the prefixed
;; names registered for direct tools, so both spellings resolve.

(defn- sanitize-tool-name
  "Lowercase; [^a-z0-9_] → _ (§10.5)."
  [s]
  (-> (str/lower-case (str s))
      (str/replace #"[^a-z0-9_]" "_")))

(defn- tool-prefix-mode
  "The effective prefix mode for a server (per-server over settings)."
  [state server-name]
  (or (get-in state [:config :mcp-servers server-name :tool-prefix])
      (get-in state [:config :settings :tool-prefix])
      :server))

(defn- format-tool-name
  "The prefixed display name for a raw tool name (§10.5)."
  [state server-name tool-name]
  (let [mode (tool-prefix-mode state server-name)
        prefix (case mode
                 :none ""
                 :short (let [short (sanitize-tool-name
                                     (str/replace server-name #"-?mcp$" ""))]
                          (if (seq short) short "mcp"))
                 :mcp "mcp"
                 (sanitize-tool-name server-name))
        sanitized (sanitize-tool-name tool-name)]
    (if (seq prefix) (str prefix "_" sanitized) sanitized)))

;; ─── Status (§9.5) ────────────────────────────────────────────────────────

(defn- lifecycle-label
  [definition]
  (name (or (:lifecycle definition) :lazy)))

(defn- state-label
  "Per-server runtime state: idle/connecting/connected/failed/disabled/
   misconfigured/unsupported-transport."
  [state name definition]
  (cond
    (true? (:disabled definition)) :disabled
    (misconfigured? definition) :misconfigured
    :else
    (let [{:keys [conn]} (server-state state name)]
      (cond
        (and @conn (client/alive? @conn)) :connected
        (failure-age-seconds state name) :failed
        :else :idle))))

(defn- auth-state-label
  [name definition]
  (let [status (auth/auth-status name definition)]
    (case status
      :bearer "bearer"
      :logged-in "oauth logged-in"
      :expired "oauth expired"
      :none "oauth none"
      nil)))

(defn status-text
  "§9.5 status text: per server — name, lifecycle, state, auth state when
   configured, tool count (from cache or live), error tail when failed,
   cache age. Plus the global settings line and cache file age."
  [state]
  (let [config (:config state)
        lines (atom [])]
    (doseq [[server-name definition] (sort-by key (:mcp-servers config))]
      (let [slabel (state-label state server-name definition)
            failed-ago (failure-age-seconds state server-name)
            error (:error (server-state state server-name))
            tool-count (count (or (cached-tools state server-name) []))
            auth-label (auth-state-label server-name definition)
            age (when-let [entry (get-in (:cache state) [:servers server-name])]
                  (quot (- (System/currentTimeMillis) (:fetched-at entry)) 60000))
            state-part (if failed-ago
                         (str "failed " failed-ago "s ago"
                              (when (seq @error)
                                (str " — " (truncate-at-word @error 120))))
                         (name slabel))]
        (swap! lines conj
               (str server-name " (" (lifecycle-label definition) ", " state-part
                    (when (and tool-count (not= :connected slabel)) (str ", " tool-count " tools"))
                    (when auth-label (str ", " auth-label))
                    (when age (str ", cache " age "m old"))
                    ")"))))
    (let [s (settings state)]
      (swap! lines conj
             (str "settings: direct-tools=" (if (:direct-tools s) "on" "off")
                  " tool-prefix=" (or (:tool-prefix s) :server)
                  " proxy-tool=" (if (:disable-proxy-tool s) "disabled" "enabled"))))
    (if (seq (:mcp-servers config))
      (str (str/join "\n" @lines)
           "\n\nmcp({ server: \"name\" }) to list tools, mcp({ search: \"...\" }) to search")
      "No MCP servers configured.")))

;; ─── Search (§9.3) ────────────────────────────────────────────────────────

(defn- schema-param-lines
  "Compact param lines for one tool schema (indented, §9.3): one line per
   property with type, required/optional, and default when present."
  [input-schema]
  (let [schema (or input-schema {})
        properties (:properties schema)
        required (set (:required schema))]
    (mapv (fn [[name spec]]
            (let [spec (or spec {})
                  type (or (:type spec) "any")
                  default (when (contains? spec :default)
                            (str "default: " (pr-str (:default spec))))
                  parts (str name " (" type
                             ", " (if (required name) "required" "optional")
                             (when default (str ", " default)) ")")
                  description (str/trim (or (:description spec) ""))]
              (str "  " parts (when (seq description)
                                (str " — " (truncate-at-word description 80))))))
          (sort-by key properties))))

(defn- return-error
  [message]
  {:content message :is-error true})

(defn- search-tools
  "Search cached tools (§9.3): substring (case-insensitive) or regex
   (COMPILED, or nil); name matches rank above description matches; both
   sorted by name; server-then-name for ties. Returns {:total :items}
   honoring :limit/:offset."
  [state query compiled server limit offset]
  (let [matches (atom [])]
    (doseq [[name _definition] (:mcp-servers (:config state))]
      (when (and (not (disabled? state name))
                 (or (nil? server) (= server name)))
        (doseq [tool (or (cached-tools state name) [])]
          (let [name-match? (if compiled
                              (boolean (re-find compiled (:name tool)))
                              (str/includes? (str/lower-case (or (:name tool) ""))
                                             (str/lower-case query)))
                desc-match? (if compiled
                              (boolean (re-find compiled (or (:description tool) "")))
                              (str/includes? (str/lower-case (or (:description tool) ""))
                                             (str/lower-case query)))]
            (when (or name-match? desc-match?)
              (swap! matches conj {:server name
                                   :tool tool
                                   :score (if name-match? 1 0)}))))))
    (let [all (sort-by (juxt (comp - :score) :server (comp :name :tool)) @matches)
          total (count all)
          page (vec (take limit (drop offset all)))]
      {:total total :items page})))

(defn search-text
  "§9.3 output: one block per hit — name line, one-line description,
   indented param lines (omitted when include-schemas? is false). Invalid
   regex → error message."
  [state query regex? server include-schemas? limit offset]
  (let [compiled (when regex?
                   (try
                     (when (> (count query) max-regex-query-length)
                       (throw (ex-info "too long" {})))
                     (re-pattern (str "(?i)" query))
                     (catch Exception _ ::invalid)))
        {:keys [total items]} (if (= ::invalid compiled)
                                {:total 0 :items []}
                                (search-tools state query compiled server
                                              (or limit 12) (or offset 0)))]
    (cond
      (= ::invalid compiled)
      (return-error (str "Invalid regex: " query))

      (and (str/blank? query) (nil? server))
      (return-error "Search query cannot be empty")

      (zero? total)
      {:content (str "No tools matching \"" query "\""
                     (when server (str " in \"" server "\"")))
       :is-error false}

      :else
      (let [out (atom [(str "Found " total " tool" (when (not= 1 total) "s")
                            " matching \"" query "\":\n")])]
        (doseq [{:keys [server tool]} items]
          (swap! out conj (str server ": "
                               (format-tool-name state server (:name tool))
                               " — " (or (:description tool) "(no description)")))
          (when (not= false include-schemas?)
            (doseq [line (schema-param-lines (:inputSchema tool))]
              (swap! out conj line))))
        (when (< (+ (or offset 0) (count items)) total)
          (swap! out conj (str "\n" (count items) " of " total
                               " — offset: " (+ (or offset 0) (count items)) " for more")))
        {:content (str/join "\n" @out) :is-error false}))))

;; ─── Describe (§9.4) ──────────────────────────────────────────────────────

(defn- find-tool
  "Find a tool by (prefixed) name across servers. Returns {:server :tool}
   or :ambiguous when the name matches multiple enabled servers."
  [state tool-name]
  (let [matches (for [[name _] (:mcp-servers (:config state))
                      :when (not (disabled? state name))
                      tool (or (cached-tools state name) [])
                      :when (or (= tool-name (:name tool))
                                (= tool-name (format-tool-name state name (:name tool))))]
                  {:server name :tool tool})]
    (cond
      (> (count matches) 1) :ambiguous
      (seq matches) (first matches)
      :else nil)))

(defn describe-text
  "§9.4 full listing: server, tool name, description, each param with
   type, required/optional, description, enum/default when present.
   Ambiguous (same tool name on multiple servers) → instruct to add
   server."
  [state tool-name]
  (let [match (find-tool state tool-name)]
    (cond
      (= :ambiguous match)
      (return-error (str "Tool \"" tool-name "\" matches multiple servers. "
                         "Specify a server with mcp({ tool: ..., server: \"...\" })."))

      (nil? match)
      (return-error (str "Tool \"" tool-name "\" not found. Use mcp({ search: \"...\" }) to search."))

      :else
      (let [{:keys [server tool]} match
            schema (or (:inputSchema tool) {})
            properties (:properties schema)
            required (set (:required schema))
            out (atom [(str (format-tool-name state server (:name tool))
                            "\nServer: " server "\n\n"
                            (or (:description tool) "(no description)"))])]
        (if (seq properties)
          (do
            (swap! out conj "\nParameters:")
            (doseq [[pname spec] (sort-by key properties)]
              (let [spec (or spec {})
                    req? (required pname)
                    type (or (:type spec) "any")
                    enum (when (seq (:enum spec)) (str "enum: " (pr-str (:enum spec))))
                    default (when (contains? spec :default)
                              (str "default: " (pr-str (:default spec))))
                    parts (str/join ", " (remove nil? [(str "type: " type)
                                                       (if req? "required" "optional")
                                                       enum default]))]
                (swap! out conj (str "  " pname " (" parts ")")
                       (when (seq (:description spec))
                         (str "      " (:description spec)))))))
          (swap! out conj "\nNo parameters defined."))
        {:content (str/join "\n" @out) :is-error false}))))

;; ─── List / connect / disconnect ──────────────────────────────────────────

(defn list-text
  "List a server's tools (cache; §9.2 `server` mode)."
  [state server]
  (let [definition (server-definition state server)]
    (cond
      (nil? definition)
      (return-error (str "Server \"" server "\" not found. Use mcp({}) to see available servers."))

      (disabled? state server)
      (return-error (str "Server \"" server "\" is disabled. Run /mcp enable " server
                         " and /reload to enable it."))

      :else
      (let [tools (cached-tools state server)]
        (if (seq tools)
          (let [out (atom [(str server " (" (count tools) " tools"
                                (when-not (= :connected (state-label state server definition))
                                  ", not connected, cached")
                                "):\n")])]
            (doseq [tool (sort-by :name tools)]
              (swap! out conj (str "- " (format-tool-name state server (:name tool))
                                   (when (seq (:description tool))
                                     (str " - " (truncate-at-word (:description tool)
                                                                  desc-truncate-length))))))
            {:content (str/join "\n" @out) :is-error false})
          (if (= :connected (state-label state server definition))
            {:content (str "Server \"" server "\" has no tools.") :is-error false}
            {:content (str "Server \"" server "\" is configured but not connected. "
                           "Use mcp({ connect: \"" server "\" }) to retry.")
             :is-error false}))))))

(defn list-all-text
  "All servers with their tool counts (§10.6 list)."
  [state]
  (let [config (:config state)
        out (atom ["MCP servers:"])]
    (doseq [[name definition] (sort-by key (:mcp-servers config))]
      (let [tools (or (cached-tools state name) [])
            extra (cond
                    (true? (:disabled definition)) " (disabled)"
                    (misconfigured? definition) " (misconfigured)"
                    :else (str " (" (count tools) " tools)"))]
        (swap! out conj (str "  " name extra))))
    {:content (str/join "\n" @out) :is-error false}))

;; ─── Call (§9.2 tool mode) ────────────────────────────────────────────────

(defn call-mcp-tool
  "Call one MCP tool on a server (direct-tool executor + proxy tool mode).
   Ensures the connection first (reconnect-on-use). Returns the kmet tool
   result shape."
  [state server tool-name args]
  (let [definition (server-definition state server)]
    (cond
      (nil? definition)
      (return-error (str "Server \"" server "\" not found. Use mcp({}) to see available servers."))

      (disabled? state server)
      (return-error (str "Server \"" server "\" is disabled. Run /mcp enable " server
                         " and /reload to enable it."))

      :else
      (if-let [failed-ago (failure-age-seconds state server)]
        ;; pi executeCall: inside the 60s backoff window a lazy use does
        ;; not retry — explicit mcp({connect}) bypasses this
        (return-error (str "Server \"" server "\" not available (last failed "
                           failed-ago "s ago)"))
        (try
          (let [conn ((:ensure-connected-fn state) server)]
            (if conn
              (let [timeout-ms (or (:request-timeout-ms definition) 120000)
                    result (client/request! conn "tools/call"
                                            {:name tool-name :arguments (or args {})}
                                            {:timeout-ms timeout-ms})
                    formatted (client/format-result result)]
                (if (:is-error formatted)
                  (return-error (:text formatted))
                  {:content (:text formatted) :is-error false}))
              (return-error (str "Server \"" server "\" not connected"))))
          (catch Exception e
            (return-error (str "MCP call failed: " (ex-message e)))))))))

;; ─── Dispatch (§9.2) ──────────────────────────────────────────────────────

(defn execute
  "Proxy tool dispatch over STATE-ATOM (the §10.1 state atom) and PARAMS
   (§9.1). The atom is deref'd per dispatch so connect (which refreshes the
   cache and tools) is followed by a fresh view."
  [state-atom params]
  (let [params (or params {})
        state @state-atom]
    (cond
      (some? (:search params))
      (search-text state (:search params) (:regex params) (:server params)
                   (:includeSchemas params) (:limit params) (:offset params))

      (some? (:describe params))
      (describe-text state (:describe params))

      (some? (:tool params))
      (let [match (find-tool state (:tool params))
            server (or (:server params)
                       (when (and match (not= :ambiguous match))
                         (:server match)))]
        (if (nil? server)
          (if (= :ambiguous match)
            (return-error (str "Tool \"" (:tool params) "\" matches multiple servers. "
                               "Specify a server with mcp({ tool: ..., server: \"...\" })."))
            (return-error (str "Tool \"" (:tool params) "\" not found. "
                               "Use mcp({ search: \"...\" }) to search.")))
          ;; the wire call uses the RAW tool name — prefixed spellings
          ;; (plan §10.4: tool: "server_tool") resolve through the cache
          (call-mcp-tool state server
                         (if (and match (not= :ambiguous match))
                           (:name (:tool match))
                           (:tool params))
                         (:args params))))

      (some? (:connect params))
      (try
        (let [conn ((:ensure-connected-fn state) (:connect params))
              ;; fresh view after the connect refreshed the cache
              fresh @state-atom]
          (if conn
            (list-text fresh (:connect params))
            (return-error (str "Failed to connect to \"" (:connect params) "\""))))
        (catch Exception e
          (return-error (str "Failed to connect to \"" (:connect params) "\": "
                             (ex-message e)))))

      (some? (:disconnect params))
      (do ((:disconnect-fn state) (:disconnect params))
          {:content (str "Disconnected \"" (:disconnect params) "\".") :is-error false})

      (some? (:list params))
      (list-text state (:list params))

      (some? (:server params))
      (list-text state (:server params))

      :else
      {:content (status-text state) :is-error false})))
