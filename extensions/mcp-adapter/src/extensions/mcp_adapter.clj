(ns extensions.mcp-adapter
  "mcp-adapter entry (pi: index.ts installMcpAdapter + init.ts, adapted to
   kmet's extension contract and babashka — sync, no SDK).

   Init/shutdown (§10.2), state (§10.1), proxy + direct tool registration
   (§10.4/§10.5), connection lifecycle (§10.3), the /mcp command (§10.6),
   events (§10.7: :session-start eager/keep-alive connects,
   :session-shutdown disconnect-all, :resources-discover skill)."
  (:require [babashka.fs :as fs]
            [babashka.process :as proc]
            [clojure.string :as str]
            [extensions.mcp-adapter.auth :as auth]
            [extensions.mcp-adapter.client :as client]
            [extensions.mcp-adapter.config :as config]
            [extensions.mcp-adapter.metadata :as metadata]
            [extensions.mcp-adapter.proxy :as proxy]
            [kmet.extension :as ext]))

(def ^:private state-atom (atom nil))

(defn- spawn
  "Start a daemon thread running F (future is not available in the
   extension sci context). Exceptions in F are dropped."
  [f]
  (let [t (Thread. (fn [] (try (f) (catch Throwable _ nil))))]
    (.setDaemon t true)
    (.start t)
    t))

(declare ensure-connected! disconnect-server! sync-direct-tools! register-proxy-tool!)

(def ^:private builtin-tool-names
  #{"read" "bash" "edit" "write" "grep" "find" "ls" "mcp"})

;; ─── State (§10.1) ────────────────────────────────────────────────────────

(defn- build-servers
  "Build the :servers map from config: {name {:definition .. :conn atom
   :error atom :lock Object}}."
  [config]
  (into {} (map (fn [[name definition]]
                  [name {:definition definition
                         :conn (atom nil)
                         :error (atom nil)
                         :lock (Object.)}]))
        (:mcp-servers config)))

(defn- rebuild-servers
  "Rebuild :servers from a fresh config, preserving conn/error/lock for
   servers that persist (used by /mcp refresh)."
  [state config]
  (let [old (:servers @state)]
    (into {} (map (fn [[name definition]]
                    [name (assoc (or (get old name)
                                     {:conn (atom nil)
                                      :error (atom nil)
                                      :lock (Object.)})
                                 :definition definition)]))
          (:mcp-servers config))))

(defn- init-state
  "Fresh §10.1 state map over API + CONFIG."
  [api config]
  (let [state (atom nil)]
    (reset! state {:api api
                   :config config
                   :cache (metadata/load-cache)
                   :servers (build-servers config)
                   :registered-direct (atom {})})
    (swap! state assoc
           :ensure-connected-fn (fn [name] (ensure-connected! state name))
           :disconnect-fn (fn [name] (disconnect-server! state name)))
    state))

;; ─── Connection lifecycle (§10.3) ─────────────────────────────────────────

(defn- refresh-after-connect!
  "On successful connect: refresh the metadata cache + save, resync direct
   tools, rebuild the proxy description (§10.3)."
  [state name tools]
  (when-let [definition (get-in @state [:config :mcp-servers name])]
    (swap! state (fn [st]
                   (assoc st :cache
                          (metadata/update-entry! (:cache st) name definition
                                                  (:settings (:config st))
                                                  tools))))
    (sync-direct-tools! state)
    (register-proxy-tool! state)))

(defn- connect-with-auth
  "Connect a server, wiring the HTTP auth fns (§7.8.5) when configured."
  [state name]
  (let [definition (get-in @state [:config :mcp-servers name])]
    (client/connect! definition (auth/make-auth-fns name definition))))

(defn- ensure-connected!
  "Locked, idempotent connect (§10.3): connected + alive? → return; dead →
   clear, retry; else connect (stdio/http by definition), handshake,
   tools/list. On success: record :conn, clear :error, refresh cache + save,
   resync direct tools, rebuild the proxy description. On failure: record
   :error, throw."
  [state name]
  (let [{:keys [conn error lock]} (get-in @state [:servers name])]
    (when (nil? lock)
      (throw (ex-info (str "MCP server \"" name "\" not found")
                      {:type :mcp-error})))
    (locking lock
      (let [current @conn]
        (cond
          (and current (client/alive? current)) current

          :else
          (do
            (when current (try (client/close! current) (catch Exception _ nil)))
            (reset! conn nil)
            (try
              ;; the connect result also carries :conn — bind it under a
              ;; different name so the outer conn atom is not shadowed
              (let [result (connect-with-auth state name)
                    new-conn (:conn result)
                    tools (:tools result)]
                (reset! conn new-conn)
                (reset! error nil)
                (refresh-after-connect! state name tools)
                new-conn)
              (catch Exception e
                (reset! error (ex-message e))
                (throw e)))))))))

(defn- disconnect-server!
  "Close + kill a server connection (§10.3)."
  [state name]
  (when-let [{:keys [conn]} (get-in @state [:servers name])]
    (when-let [c @conn]
      (try (client/close! c) (catch Exception _ nil)))
    (reset! conn nil))
  nil)

(defn- disconnect-all!
  "Close every connection (session shutdown / extension shutdown)."
  [state]
  (doseq [name (keys (:servers @state))]
    (disconnect-server! state name)))

;; ─── Direct tools (§10.5) ─────────────────────────────────────────────────

(defn- sanitize-tool-name
  "Lowercase; [^a-z0-9_] → _ (§10.5)."
  [s]
  (-> (str/lower-case (str s))
      (str/replace #"[^a-z0-9_]" "_")))

(defn- server-prefix
  "Pi getServerPrefix (§10.5): :server default → sanitized server name;
   :mcp → \"mcp\"; :none/:short → bare (collision fallback by the caller)."
  [server-name mode]
  (case mode
    :none ""
    :short (let [short (sanitize-tool-name (str/replace server-name #"-?mcp$" ""))]
             (if (seq short) short "mcp"))
    :mcp "mcp"
    (sanitize-tool-name server-name)))

(defn- prefixed-tool-name
  "Pi formatToolName (§10.5): prefix + '_' + sanitized tool name; bare
   names with a collision (another server's tool or a builtin) fall back to
   the server prefix."
  [server-name tool-name mode seen]
  (let [sanitized (sanitize-tool-name tool-name)
        prefix (server-prefix server-name mode)
        name (if (seq prefix) (str prefix "_" sanitized) sanitized)]
    (if (or (contains? seen name)
            (and (empty? prefix) (contains? builtin-tool-names name)))
      (str (sanitize-tool-name server-name) "_" sanitized)
      name)))

(defn- direct-tools-specs
  "Resolve direct-tool specs from the metadata cache only (never spawns;
   §10.5.1): MCP_DIRECT_TOOLS env → only listed servers (config ignored),
   __none__ → none; else server :direct-tools (bool | name list), else
   settings :direct-tools, else false. Skips disabled and misconfigured
   servers. Naming per §10.5 with collision fallback. STATE is the §10.1
   atom."
  [state]
  (let [config (:config @state)
        settings (:settings config)
        env-raw (System/getenv "MCP_DIRECT_TOOLS")
        env-servers (when (and env-raw (not= "__none__" env-raw))
                      (set (map str/trim (str/split env-raw #","))))
        env-none? (= "__none__" env-raw)
        prefix-mode (or (:tool-prefix settings) :server)
        specs (atom [])
        seen (atom #{})]
    (doseq [[name definition] (sort-by key (:mcp-servers config))]
      (when-not (or (true? (:disabled definition))
                    (and (not (:command definition)) (not (:url definition))))
        (let [filter (cond
                       env-none? false
                       env-servers (contains? env-servers name)
                       :else (if (contains? definition :direct-tools)
                               (:direct-tools definition)
                               (if (:direct-tools settings) true false)))]
          (when filter
            (let [entry (metadata/server-entry (:cache @state) name definition settings)
                  mode (or (:tool-prefix definition) prefix-mode)]
              (doseq [tool (:tools entry)]
                (when (or (true? filter) (some #{(:name tool)} filter))
                  (let [prefixed (prefixed-tool-name name (:name tool) mode @seen)]
                    (swap! seen conj prefixed)
                    (swap! specs conj
                           {:server name
                            :original (:name tool)
                            :prefixed prefixed
                            :description (or (:description tool) "")
                            :input-schema (:inputSchema tool)})))))))))
    @specs))

(defn- truncate
  "Truncate S to N chars with an ellipsis."
  [s n]
  (let [s (or s "")]
    (if (<= (count s) n) s (str (subs s 0 n) "…"))))

(defn- normalize-direct-schema
  "Ensure {:type \"object\" :properties {}} base; pass through
   properties/required as-is (§10.5)."
  [input-schema]
  (let [schema (or input-schema {})]
    {:type "object"
     :properties (or (:properties schema) {})
     :required (or (:required schema) [])}))

(defn- sync-direct-tools!
  "Diff-based resync (§10.5): register new/updated (by fingerprint),
   unregister removed. Runs at init and after every connect/refresh."
  [state]
  (let [specs (direct-tools-specs state)
        next-names (set (map :prefixed specs))
        registered @(:registered-direct @state)
        fingerprint (fn [spec]
                      (pr-str (select-keys spec
                                           [:server :original :prefixed
                                            :description :input-schema])))]
    (doseq [spec specs]
      (let [fp (fingerprint spec)]
        (when (not= fp (get registered (:prefixed spec)))
          (ext/register-tool! (:api @state)
                              {:name (:prefixed spec)
                               :label (str "MCP: " (:original spec))
                               :description (or (:description spec) "(no description)")
                               :prompt-snippet (truncate (:description spec) 100)
                               :parameters (normalize-direct-schema (:input-schema spec))
                               :execute (fn [args]
                                          (proxy/call-mcp-tool @state
                                                               (:server spec)
                                                               (:original spec)
                                                               args))})
          (swap! (:registered-direct @state) assoc (:prefixed spec) fp))))
    (doseq [name (remove next-names (keys registered))]
      (ext/unregister-tool! (:api @state) name)
      (swap! (:registered-direct @state) dissoc name))))

;; ─── Proxy tool (§10.4) ───────────────────────────────────────────────────

(defn- build-proxy-description
  "Dynamic proxy description from config + cache (§10.4): server tool
   counts, lazy-connect note, usage examples."
  [state]
  (let [config (:config @state)
        settings (:settings config)
        summaries (for [[name definition] (sort-by key (:mcp-servers config))
                        :let [entry (metadata/server-entry (:cache @state) name
                                                           definition settings)
                              count (count (:tools entry))]
                        :when (and (not (true? (:disabled definition)))
                                   (pos? count))]
                    (str name " (" count " tools)"))
        prefix (or (:tool-prefix settings) :server)]
    (str "MCP gateway: status, search, describe, call. "
         (if (seq summaries)
           (str "Servers: " (str/join ", " summaries) ". ")
           "No servers with cached tools. ")
         "Servers connect lazily on first use. "
         "search: \"screenshot\" · tool: \"" (name prefix) "_tool\" args: {...}.")))

(defn- register-proxy-tool!
  "Register the mcp proxy tool (replaces by name — re-registered after
   every connect/refresh so the system prompt sees current availability)."
  [state]
  (ext/register-tool! (:api @state)
                      {:name "mcp"
                       :label "MCP"
                       :description (build-proxy-description state)
                       :prompt-snippet "MCP gateway — status, search, describe, and single MCP tool calls"
                       :parameters {:type "object"
                                    :properties
                                    {"tool" {:type "string"
                                             :description "MCP tool name to call (e.g. 'server_toolname')"}
                                     "args" {:type "object"
                                             :description "Arguments as a JSON object (a JSON string is also accepted)"}
                                     "server" {:type "string"
                                               :description "Scope to / disambiguate a server"}
                                     "search" {:type "string"
                                               :description "Search tools by name/description"}
                                     "regex" {:type "boolean"
                                              :description "Treat search as regex (default false)"}
                                     "includeSchemas" {:type "boolean"
                                                       :description "Schemas in search output (default true)"}
                                     "describe" {:type "string"
                                                 :description "Show a tool's parameters"}
                                     "connect" {:type "string"
                                                :description "Connect a server now (+ refresh)"}
                                     "disconnect" {:type "string"
                                                   :description "Disconnect a server"}
                                     "list" {:type "string"
                                             :description "List a server's tools"}
                                     "limit" {:type "number"
                                              :description "Search limit (default 12)"}
                                     "offset" {:type "number"
                                               :description "Search offset (default 0)"}}
                                    :required []}
                       :execute (fn [params] (proxy/execute state params))}))

;; ─── /mcp command (§10.6) ─────────────────────────────────────────────────

(defn- notify-or-print
  "ui-notify in the TUI, println headless."
  [state ctx message]
  (if (:has-ui ctx)
    (ext/ui-notify (:api @state) message "info")
    (println message)))

(defn- handle-connect
  [state server-name ctx]
  (if (seq server-name)
    (try
      (let [conn ((:ensure-connected-fn @state) server-name)]
        (if conn
          (notify-or-print state ctx (:content (proxy/list-text @state server-name)))
          (notify-or-print state ctx (str "Failed to connect to \"" server-name "\""))))
      (catch Exception e
        (notify-or-print state ctx (str "Failed to connect to \"" server-name "\": "
                                        (ex-message e)))))
    (notify-or-print state ctx "Usage: /mcp connect <server>")))

(defn- handle-enable-disable
  [state server-name disabled ctx]
  (if (seq server-name)
    (let [{:keys [path changed]} (config/set-server-disabled! server-name disabled)]
      (notify-or-print state ctx
                       (if changed
                         (str (if disabled "Disabled" "Enabled") " server \"" server-name
                              "\" in " path " — run /reload to apply")
                         (str "Server \"" server-name "\" is already "
                              (if disabled "disabled" "enabled")))))
    (notify-or-print state ctx (str "Usage: /mcp " (if disabled "disable" "enable") " <server>"))))

(defn- handle-refresh
  "Reload the EDN config: add/remove servers (disconnecting dropped ones),
   resync tools, rebuild the description (§10.6)."
  [state ctx]
  (let [config (config/load-config)
        current-names (set (keys (:mcp-servers (:config @state))))]
    (doseq [name (remove (set (keys (:mcp-servers config))) current-names)]
      ((:disconnect-fn @state) name))
    (swap! state assoc :config config :servers (rebuild-servers state config))
    (sync-direct-tools! state)
    (register-proxy-tool! state)
    (notify-or-print state ctx "MCP config reloaded.")))

(defn- open-browser
  "Open a URL with the platform helper (xdg-open / open /
   termux-open-url)."
  [url]
  (let [cmd (cond
              (System/getenv "TERMUX_VERSION") ["termux-open-url" url]
              :else (if (fs/which "xdg-open") ["xdg-open" url] ["open" url]))]
    (try
      @(proc/process cmd {:out :discard :err :discard})
      (catch Exception _ nil))))

(defn- build-interaction
  "The §7.8 interaction map for the OAuth flow, from the extension ctx."
  [state ctx]
  (let [has-ui (:has-ui ctx)
        signal (when-let [s (:signal ctx)]
                 (try (s) (catch Exception _ nil)))]
    {:signal (or signal (atom false))
     :has-ui has-ui
     :open-url open-browser
     :notify (fn [event]
               (case (:type event)
                 :auth-url
                 (notify-or-print state ctx
                                  (str "Open this URL to authenticate:\n\n"
                                       (:url event)
                                       "\n\nComplete login in your browser."))
                 :device-code
                 (notify-or-print state ctx
                                  (str "Open " (:verification-uri event)
                                       " and enter the code: " (:user-code event)))
                 (notify-or-print state ctx (str "MCP auth: " (:message event)))))
     :prompt (fn [prompt-map]
               (if has-ui
                 (deref (ext/ui-input (:api @state) (:message prompt-map) ""))
                 ;; headless: wait for the browser callback without a prompt
                 (do (Thread/sleep 600000) nil)))}))

(defn- handle-auth
  "Run the OAuth flow for a server on a background future (§10.6 auth) —
   the flow never blocks the command handler."
  [state server-name ctx]
  (if (seq server-name)
    (let [definition (get-in @state [:config :mcp-servers server-name])]
      (cond
        (nil? definition)
        (notify-or-print state ctx (str "Server \"" server-name "\" not found in config"))

        (not (:url definition))
        (notify-or-print state ctx (str "Server \"" server-name
                                        "\" has no URL — OAuth requires HTTP transport"))

        (not= :oauth (:auth definition))
        (notify-or-print state ctx (str "Server \"" server-name
                                        "\" is not configured for OAuth "
                                        "(set :auth :oauth in mcp.edn)"))

        :else
        (do
          (notify-or-print state ctx (str "Starting OAuth flow for \"" server-name
                                          "\" — complete login in your browser."))
          (spawn
           (fn []
             (try
               (auth/run-flow! server-name definition
                               (build-interaction state ctx))
               (notify-or-print state ctx (str "OAuth authentication successful for \""
                                               server-name "\"."
                                               (when (:has-ui ctx) " The connection will use the new token on next use.")))
               (catch Exception e
                 (notify-or-print state ctx (str "Failed to authenticate \"" server-name
                                                 "\": " (ex-message e))))))))))
    (notify-or-print state ctx "Usage: /mcp auth <server>")))

(defn- handle-mcp-command
  [state args ctx]
  (let [trimmed (str/trim (or args ""))
        space (str/index-of trimmed " ")
        sub (if (nil? space) trimmed (subs trimmed 0 space))
        rest-args (if (nil? space) "" (str/trim (subs trimmed (inc space))))]
    (case sub
      ("status" "")
      (notify-or-print state ctx (proxy/status-text @state))

      "search"
      (let [[q regex] (str/split rest-args #"\s+" 2)]
        (notify-or-print state ctx (:content (proxy/search-text @state (or q "") (boolean regex)
                                                                nil true 12 0))))

      "list"
      (notify-or-print state ctx (:content (if (seq rest-args)
                                             (proxy/list-text @state rest-args)
                                             (proxy/list-all-text @state))))

      "connect"
      (handle-connect state rest-args ctx)

      "disconnect"
      (if (seq rest-args)
        (do ((:disconnect-fn @state) rest-args)
            (notify-or-print state ctx (str "Disconnected \"" rest-args "\".")))
        (notify-or-print state ctx "Usage: /mcp disconnect <server>"))

      "enable" (handle-enable-disable state rest-args false ctx)
      "disable" (handle-enable-disable state rest-args true ctx)
      "refresh" (handle-refresh state ctx)
      "auth" (handle-auth state rest-args ctx)

      "logout"
      (if (seq rest-args)
        (do (auth/logout! rest-args)
            (notify-or-print state ctx (str "OAuth credentials cleared for \"" rest-args "\".")))
        (notify-or-print state ctx "Usage: /mcp logout <server>"))

      (notify-or-print state ctx (str "Unknown /mcp subcommand: " sub)))))

(def ^:private mcp-subcommands
  ["status" "search" "list" "connect" "disconnect"
   "enable" "disable" "refresh" "auth" "logout"])

(defn- mcp-completions
  "Argument completions: subcommands, then server names for the
   server-taking subcommands."
  [state arg-prefix]
  (let [prefix (str/trim (or arg-prefix ""))
        space (str/index-of prefix " ")]
    (if (nil? space)
      (let [subs (filter #(str/starts-with? % prefix) mcp-subcommands)]
        (mapv (fn [s] {:value s :label s}) subs))
      (let [sub (subs prefix 0 space)
            server-prefix (str/trim (subs prefix (inc space)))]
        (when (contains? #{"connect" "disconnect" "enable" "disable" "auth" "logout"} sub)
          (let [servers (filter #(str/starts-with? % server-prefix)
                                (keys (:mcp-servers (:config @state))))]
            (mapv (fn [s] {:value (str sub " " s) :label s}) servers)))))))

;; ─── Events (§10.7) ───────────────────────────────────────────────────────

(defn- on-session-start
  "Background future connects of :eager/:keep-alive servers — never blocks
   session start (§10.3)."
  [state _event _ctx]
  (spawn
   (fn []
     (doseq [[name definition] (:mcp-servers (:config @state))]
       (when (and (not (true? (:disabled definition)))
                  (contains? #{:eager :keep-alive} (:lifecycle definition)))
         (try
           ((:ensure-connected-fn @state) name)
           (catch Exception _ nil)))))))

(defn- on-session-shutdown
  [state _event _ctx]
  (disconnect-all! state))

;; ─── Init / shutdown (§10.2) ──────────────────────────────────────────────

(defn init
  "Extension init (required by the loader)."
  [api]
  (let [config (config/load-config)
        _ (config/ensure-global-template!)
        state (init-state api config)]
    (reset! state-atom state)
    ;; 2. proxy tool (§10.4)
    (register-proxy-tool! state)
    ;; 3. direct tools from cache (§10.5)
    (sync-direct-tools! state)
    ;; 4. /mcp command + completions (§10.6)
    (ext/register-command! api
                           {:name "mcp"
                            :description "MCP server status, search, connect, auth"
                            :get-argument-completions (fn [arg-prefix]
                                                        (mcp-completions state arg-prefix))
                            :handler (fn [args ctx]
                                       (handle-mcp-command state args ctx))})
    ;; 5. events (§10.7)
    (ext/on-event api :session-start (fn [event ctx]
                                       (on-session-start state event ctx)))
    (ext/on-event api :session-shutdown (fn [event ctx]
                                          (on-session-shutdown state event ctx)))
    (ext/on-event api :resources-discover
                  (fn [_event _ctx]
                    {:skill-paths [(str (:extension-dir api) "/skills/mcp")]}))))

(defn shutdown
  "Extension shutdown (optional): close all connections, kill process
   trees, close the OAuth callback server. Idempotent."
  [_api]
  (when-let [state @state-atom]
    (disconnect-all! state)
    (auth/shutdown!)
    (reset! state-atom nil)))
