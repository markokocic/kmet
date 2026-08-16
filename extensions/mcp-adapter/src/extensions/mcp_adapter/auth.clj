(ns extensions.mcp-adapter.auth
  "OAuth adapter for HTTP MCP servers (§7.8 of the design contract — pi:
   mcp-auth.ts / mcp-oauth-provider.ts / oauth-handler.ts /
   mcp-auth-flow.ts / mcp-callback-server.ts, adapted onto the generic
   machinery in kmet.libs.oauth).

   Thin adapter: server config → lib calls, token-store file wiring
   (~/.kmet/agent/mcp-oauth.edn, plaintext with 0600 perms — bb has no OS
   keyring; documented tradeoff; pi uses the keyring), browser open,
   status text. The extension cannot require kmet.ai.*, so the generic
   machinery lives in kmet.libs.oauth (RFC 8414 discovery, RFC 7591 DCR,
   PKCE loopback + RFC 8628 device flows, token exchange/refresh).

   Flow (per server, §7.8):
     1. token lookup — expired → refresh; missing/refresh-failed → flow
     2. discovery (RFC 8414; :authorization-server-url skips it)
     3. client registration (RFC 7591) unless :oauth {:client-id ...}
     4. authorization — PKCE loopback on an OS-assigned port (default), or
        the RFC 8628 device flow (forced via :flow :device, or auto when
        the metadata exposes a device endpoint and the host is headless)
     5. tokens stored; requests attach Authorization: Bearer; 401 with a
        stored refresh token → refresh once + retry once"
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [kmet.libs.oauth :as oauth-lib]))

;; slurp/spit are not available in the extension sci context (see config.clj).
(defn- read-text
  [path]
  (when (fs/exists? path)
    (str/join "\n" (fs/read-all-lines path))))

(defn- write-text
  [path text]
  (fs/write-bytes path (.getBytes (str text) "UTF-8")))

;; ─── Token store (~/.kmet/agent/mcp-oauth.edn) ────────────────────────────
;; {:servers {name {:tokens {:access .. :refresh .. :expires ms :scope ..}
;;                  :client-info {:client-id .. :redirect-uris [..]}}}}
;; Plaintext, 0600 perms (fixed path; no MCP_OAUTH_DIR override in Phase 1).

(defn store-path
  "The OAuth token store file."
  []
  (str (fs/home) "/.kmet/agent/mcp-oauth.edn"))

(defn- read-edn
  [path]
  (try
    (when (fs/exists? path)
      (let [raw (edn/read-string {:default (fn [_ _] nil)} (read-text path))]
        (when (map? raw) raw)))
    (catch Exception _ nil)))

(defn- write-file-0600
  "Atomic write (temp + rename) with 0600 perms (best-effort — Windows has
   no posix perms)."
  [path content]
  (let [tmp (str path ".tmp")]
    (fs/create-dirs (fs/parent path))
    (write-text tmp content)
    (try (fs/set-posix-file-permissions tmp "rw-------") (catch Exception _ nil))
    (fs/move tmp path {:replace-existing true})
    nil))

(defn- read-store
  []
  (or (read-edn (store-path)) {:servers {}}))

(defn- write-store!
  [store]
  (write-file-0600 (store-path) (pr-str store)))

(defn- server-entry
  "The stored {:tokens .. :client-info ..} entry for a server, or nil."
  [name]
  (get-in (read-store) [:servers name]))

(defn- store-server!
  [name entry]
  (write-store! (assoc-in (read-store) [:servers name] entry)))

(defn- token-expired?
  "True when the stored tokens are expired (60s skew, pi's 5-min window
   reduced for MCP's shorter-lived tokens)."
  [entry]
  (let [expires (get-in entry [:tokens :expires])]
    (and (number? expires)
         (<= expires (+ (System/currentTimeMillis) 60000)))))

;; in-memory cache for machine-grant tokens (client-credentials /
;; jwt-bearer — §7.8.6); defined before logout! below
(defonce ^:private machine-token-cache (atom {}))

(defn logout!
  "Clear the stored OAuth tokens + client info for a server (§10.6), and
   any cached machine-grant token."
  [name]
  (swap! machine-token-cache dissoc name)
  (let [store (read-store)]
    (when (contains? (:servers store) name)
      (write-store! (update store :servers dissoc name)))))

;; ─── Discovery (RFC 8414) ─────────────────────────────────────────────────

(defn- origin-of
  "scheme://host of a URL."
  [url]
  (try
    (let [uri (java.net.URI. url)]
      (str (.getScheme uri) "://" (.getAuthority uri)))
    (catch Exception _ url)))

(defn- issuer-matches?
  "Lenient issuer check: the metadata issuer must share the server URL's
   origin (or be the full server URL) — catches gross mismatches while
   allowing path variations real servers use."
  [url issuer]
  (let [origin (origin-of url)
        base (str/replace url #"/+$" "")]
    (or (= issuer origin)
        (= issuer base)
        (= origin (origin-of issuer)))))

(defn- discover-meta
  "Authorization-server metadata for a server (§7.8.2): config
   :authorization-server-url fetches the metadata from that URL directly
   (skips well-known discovery); otherwise RFC 8414 discovery against the
   server URL. Throws when nothing answers. The issuer check (RFC 8414) is
   skipped when :skip-issuer-metadata-validation is set."
  [definition]
  (let [cfg (:oauth definition)
        url (:url definition)
        headers (:headers definition)
        metadata (cond
                   ;; an explicit token endpoint needs no discovery at all
                   (:token-endpoint cfg) {:token_endpoint (:token-endpoint cfg)}

                   (:authorization-server-url cfg)
                   (:body (oauth-lib/fetch-json (:authorization-server-url cfg)
                                                {:method :get
                                                 :headers headers
                                                 :timeout 5000}))

                   :else
                   (oauth-lib/discover-authorization-server url {:headers headers}))]
    (when-not (map? metadata)
      (throw (ex-info (str "MCP auth failed: no OAuth authorization server metadata "
                           "discovered at " url)
                      {:type :oauth-no-metadata})))
    (when-not (or (:token-endpoint cfg)
                  (true? (:skip-issuer-metadata-validation cfg)))
      (when (and (seq (:issuer metadata))
                 (not (issuer-matches? url (:issuer metadata))))
        (throw (ex-info (str "MCP auth failed: authorization server issuer mismatch ("
                             (:issuer metadata) " vs " url "). Set "
                             ":skip-issuer-metadata-validation true to override.")
                        {:type :oauth-issuer-mismatch}))))
    metadata))

(defn- required-endpoint
  "One metadata endpoint or a clear error."
  [metadata key name]
  (or (get metadata key)
      (throw (ex-info (str "MCP auth failed: authorization server metadata for " name
                           " has no " key)
                      {:type :oauth-no-endpoint :endpoint key}))))

;; ─── Callback server (process-wide, OS-assigned port) ─────────────────────

(defonce ^:private callback-state (atom nil))
(defonce ^:private current-flow (atom nil))

(defn- redirect-uri-for
  "The loopback redirect URI for the callback server (config
   :redirect-uri wins; must be an http loopback URI with an explicit
   port, pi parseOAuthRedirectUri). Returns [uri bind-port] — the bind
   port (the configured URI's port, or 0 for OS-assigned) feeds
   ensure-callback-server! so the browser callback reaches the server."
  [cfg default-port default-path]
  (if-let [configured (:redirect-uri cfg)]
    (let [uri (try (java.net.URI. configured) (catch Exception _ nil))
          host (some-> uri .getHost str/lower-case)
          port (some-> uri .getPort)]
      (when-not (and uri (= "http" (.getScheme uri))
                     (contains? #{"localhost" "127.0.0.1" "::1"} host)
                     (pos? port))
        (throw (ex-info (str "MCP auth failed: :redirect-uri must be an http:// "
                             "loopback URI with an explicit port")
                        {:type :oauth-invalid-config})))
      [configured port])
    [(str "http://" (oauth-lib/callback-host) ":" default-port default-path)
     default-port]))

(defn- ensure-callback-server!
  "Start the process-wide loopback callback server once. PORT is the bind
   port: a configured :redirect-uri supplies its explicit port (the
   browser callback must reach the server); 0 → OS assigns. PATH is the
   callback path (default /callback; a configured :redirect-uri's path is
   served instead). Every later flow reuses the bound port, so a DCR'd
   client's redirect URI stays valid. Returns {:port n :path str}."
  [& [port path]]
  (or @callback-state
      (locking callback-state
        (or @callback-state
            (let [callback-path (or path "/callback")
                  server (oauth-lib/start-callback-server
                          (or port 0)
                          (fn [{:keys [path query-params]}]
                            (let [flow @current-flow]
                              (cond
                                (not= path callback-path)
                                {:status 404
                                 :body (oauth-lib/oauth-error-html
                                        "Callback route not found.")}

                                (nil? flow)
                                {:status 400
                                 :body (oauth-lib/oauth-error-html
                                        "No OAuth flow is in progress.")}

                                (not= (:state query-params) (:state flow))
                                {:status 400
                                 :body (oauth-lib/oauth-error-html "State mismatch.")}

                                (nil? (:code query-params))
                                {:status 400
                                 :body (oauth-lib/oauth-error-html
                                        "Missing authorization code.")}

                                :else
                                (do (deliver (:code-p flow)
                                             {:code (:code query-params)})
                                    {:status 200
                                     :body (oauth-lib/oauth-success-html
                                            "MCP authentication completed. You can close this window.")})))))]
              (reset! callback-state {:server server
                                      :port (:port server)
                                      :path callback-path})
              @callback-state)))))

(defn- ensure-callback-redirect!
  "Ensure the process-wide callback server and return the redirect URI to
   use for this flow. The server binds ONCE: the first flow's
   :redirect-uri config (explicit port + path) wins; later flows reuse the
   bound server and derive the URI from it, so the authorize URL always
   matches the server the browser hits (a DCR'd client's registered URI
   stays valid)."
  [cfg]
  (if-let [configured (:redirect-uri cfg)]
    (let [[uri bind-port] (redirect-uri-for cfg 0 "/callback")
          uri-obj (try (java.net.URI. configured) (catch Exception _ nil))]
      (ensure-callback-server! bind-port (some-> uri-obj .getPath))
      uri)
    (let [{:keys [port path]} (ensure-callback-server!)]
      (str "http://" (oauth-lib/callback-host) ":" port path))))

(defn shutdown!
  "Close the callback server and drop machine-token caches (extension
   unload). Idempotent."
  []
  (reset! machine-token-cache {})
  (when-let [{:keys [server]} @callback-state]
    (try ((:close server)) (catch Exception _ nil))
    (reset! callback-state nil)
    (reset! current-flow nil)))

;; ─── Client info (config pre-registered or RFC 7591 DCR) ──────────────────

(defn- scopes-string
  "Config :scopes — a string or a vector, joined with spaces."
  [cfg]
  (let [scopes (:scopes cfg)]
    (cond
      (nil? scopes) nil
      (string? scopes) scopes
      (sequential? scopes) (str/join " " scopes)
      :else (str scopes))))

(defn- stored-client-id
  [name]
  (get-in (server-entry name) [:client-info :client-id]))

(defn- resolve-client-id!
  "The client id for a server: config :oauth {:client-id ...} wins; else
   the stored (DCR'd) client; else RFC 7591 dynamic registration against
   the metadata's registration_endpoint (registered once, persisted with
   the entry)."
  [name definition metadata]
  (let [cfg (:oauth definition)]
    (or (:client-id cfg)
        (stored-client-id name)
        (let [registration-endpoint (get metadata :registration_endpoint)]
          (when-not registration-endpoint
            (throw (ex-info (str "MCP auth failed: " name " has no OAuth client id and the "
                                 "authorization server does not support dynamic client "
                                 "registration. Set :oauth {:client-id ...} in mcp.edn.")
                            {:type :oauth-no-registration})))
          (let [redirect-uri (ensure-callback-redirect! cfg)
                client (oauth-lib/register-client
                        registration-endpoint
                        {:redirect-uris [redirect-uri]
                         :client-name "kmet"
                         :scope (scopes-string cfg)})]
            (store-server! name (assoc (or (server-entry name) {})
                                       :client-info {:client-id (:client_id client)
                                                     :redirect-uris [redirect-uri]}))
            (:client_id client))))))

;; ─── Token storage ────────────────────────────────────────────────────────

(defn- tokens->store
  "Normalize a lib token map {:access :refresh :expires-in :scope} into the
   store shape {:access .. :refresh .. :expires ms :scope ..}."
  [tokens]
  (cond-> {:access (:access tokens)
           :expires (+ (System/currentTimeMillis) (* 1000 (:expires-in tokens)))}
    (:refresh tokens) (assoc :refresh (:refresh tokens))
    (:scope tokens) (assoc :scope (:scope tokens))))

(defn- store-tokens!
  [name tokens]
  (store-server! name (assoc (or (server-entry name) {}) :tokens tokens)))

(defn- bearer-token
  "The static bearer token from config (:bearer-token or
   :bearer-token-env)."
  [definition]
  (or (:bearer-token definition)
      (when-let [env-name (:bearer-token-env definition)]
        (System/getenv env-name))))

(defn- auth-required-error
  [name]
  (ex-info (str "MCP auth failed: " name " is not authenticated. Run /mcp auth "
                name " to log in.")
           {:type :mcp-auth-required}))

(defn- oauth-bearer-header
  [tokens]
  {"Authorization" (str "Bearer " (:access tokens))})

;; ─── Machine grants (client-credentials / jwt-bearer, §7.8.6) ────────────
;; Non-interactive grants: a token is fetched on demand from the token
;; endpoint (discovery, :authorization-server-url, or an explicit
;; :token-endpoint), cached in memory with its expiry, and re-fetched on
;; expiry or 401 — the re-fetch IS the refresh (no refresh token is
;; expected). Nothing is persisted: the token store stays for the
;; interactive grants.

(defn- grant-of
  "The configured grant: :authorization-code (default) |
   :client-credentials | :jwt-bearer."
  [definition]
  (or (get-in definition [:oauth :grant]) :authorization-code))

(defn- machine-grant?
  [definition]
  (contains? #{:client-credentials :jwt-bearer} (grant-of definition)))

(defn- fetch-machine-token!
  "Fetch a fresh token for a machine-grant server and cache it. Throws
   MCP auth failed on any error (§7.7)."
  [name definition]
  (let [cfg (:oauth definition)
        token-endpoint (required-endpoint (discover-meta definition)
                                          :token_endpoint name)
        tokens (case (grant-of definition)
                 :client-credentials
                 (oauth-lib/client-credentials-token
                  token-endpoint
                  {:client-id (:client-id cfg)
                   :client-secret (:client-secret cfg)
                   :token-endpoint-auth-method (:token-endpoint-auth-method cfg)
                   :scope (scopes-string cfg)})

                 :jwt-bearer
                 (let [key-file (:private-key-file cfg)
                       jwk (:private-key-jwk cfg)]
                   (when-not (or key-file jwk)
                     (throw (ex-info (str "MCP auth failed: " name " jwt-bearer grant "
                                          "requires :oauth {:private-key-file ...} or "
                                          ":private-key-jwk ...")
                                     {:type :oauth-invalid-config})))
                   (when (and key-file (not (fs/exists? key-file)))
                     (throw (ex-info (str "MCP auth failed: private key file not found: "
                                          key-file)
                                     {:type :oauth-invalid-config})))
                   (oauth-lib/jwt-bearer-token
                    token-endpoint
                    {:private-key (if (and key-file (string? key-file))
                                    (read-text key-file)
                                    jwk)
                     :algorithm (:algorithm cfg)
                     :issuer (:issuer cfg)
                     :subject (:subject cfg)
                     :audience (:audience cfg)
                     :client-id (:client-id cfg)
                     :scope (scopes-string cfg)})))
        stored (tokens->store tokens)]
    (swap! machine-token-cache assoc name stored)
    stored))

(defn- machine-token-header
  "Authorization header for a machine-grant server: the cached token, or
   a fresh fetch when missing/expired. FORCE skips the cache — the 401
   retry path must not resend a rejected token."
  [name definition force]
  (let [entry (get @machine-token-cache name)
        expired? (and entry (<= (:expires entry)
                                (+ (System/currentTimeMillis) 60000)))]
    (if (and entry (not force) (not expired?))
      (oauth-bearer-header entry)
      (oauth-bearer-header (fetch-machine-token! name definition)))))

;; ─── Request auth (§7.8.5) ────────────────────────────────────────────────

(declare refresh-tokens!)

(defn- oauth-header
  "Authorization header from the stored tokens; refreshes silently when
   expired, throws when not authenticated (§7.8.5 pre-emptive refresh)."
  [name definition]
  (let [entry (server-entry name)]
    (cond
      (nil? entry) (throw (auth-required-error name))
      (token-expired? entry)
      (if-let [refreshed (refresh-tokens! name definition)]
        (oauth-bearer-header refreshed)
        (throw (auth-required-error name)))
      :else (oauth-bearer-header (:tokens entry)))))

(defn- refresh-tokens!
  "Refresh the stored tokens; returns the fresh tokens map or nil when no
   refresh token is stored / the refresh failed (error recorded in the
   store state only on success)."
  [name definition]
  (let [entry (server-entry name)
        refresh (get-in entry [:tokens :refresh])]
    (when (and (seq refresh)
               (seq (:url definition)))
      (try
        (let [metadata (discover-meta definition)
              token-endpoint (required-endpoint metadata :token_endpoint name)
              client-id (or (get-in definition [:oauth :client-id])
                            (stored-client-id name))
              tokens (when client-id
                       (oauth-lib/refresh-access-token
                        token-endpoint
                        {:client-id client-id
                         :refresh-token refresh
                         :scope (get-in entry [:tokens :scope])}))]
          (when tokens
            (store-tokens! name (tokens->store tokens))
            (tokens->store tokens)))
        (catch Exception _ nil)))))

(defn- oauth-header-after-401
  "401 retry path: refresh the stored tokens (a stored refresh token is
   required — the 401 already proved the access token invalid), then
   return fresh headers. Throws the auth-required message otherwise."
  [name definition]
  (if-let [refreshed (refresh-tokens! name definition)]
    (oauth-bearer-header refreshed)
    (throw (auth-required-error name))))

(defn make-auth-fns
  "Auth wiring for a server's HTTP conn (§7.8.5): :auth-headers — called
   per request (pre-emptive refresh on expiry); :on-401 — refresh + fresh
   headers, retried once. Static config :headers are merged in for every
   HTTP server. Returns {} when the server has no auth configured."
  [name definition]
  (let [config-headers (:headers definition)
        merge-headers (fn [auth]
                        (if (seq config-headers)
                          (merge config-headers auth)
                          auth))]
    (cond
      (= :oauth (:auth definition))
      (if (machine-grant? definition)
        {:auth-headers (fn [] (merge-headers (machine-token-header name definition false)))
         :on-401 (fn [] (merge-headers (machine-token-header name definition true)))}
        {:auth-headers (fn [] (merge-headers (oauth-header name definition)))
         :on-401 (fn [] (merge-headers (oauth-header-after-401 name definition)))})

      (= :bearer (:auth definition))
      {:auth-headers (fn [] (merge-headers (oauth-bearer-header
                                            {:access (or (bearer-token definition) "")})))}

      :else
      (when (seq config-headers)
        {:auth-headers (fn [] config-headers)}))))

;; ─── Full flow (/mcp auth, §7.8.4) ────────────────────────────────────────

(defn- resolve-flow
  "Flow selection: config :flow (:pkce | :device | :auto default). :auto →
   PKCE loopback; the device flow is auto-selected when the metadata
   exposes a device endpoint and the host is headless (no UI to paste a
   redirect URL)."
  [cfg metadata interaction]
  (let [forced (:flow cfg)
        device-endpoint? (contains? metadata :device_authorization_endpoint)]
    (case forced
      :pkce :pkce
      :device :device
      :auto (if (and device-endpoint? (not (:has-ui interaction))) :device :pkce)
      nil (if (and device-endpoint? (not (:has-ui interaction))) :device :pkce)
      (throw (ex-info (str "MCP auth failed: unknown OAuth flow " forced
                           " (expected :auto, :pkce or :device)")
                      {:type :oauth-invalid-config})))))

(defn- authorize-url
  "The authorization endpoint URL with the PKCE challenge, state, scope
   and redirect URI."
  [authorize-endpoint client-id redirect-uri challenge state scope]
  (str authorize-endpoint
       "?response_type=code"
       "&client_id=" (oauth-lib/url-encode client-id)
       "&redirect_uri=" (oauth-lib/url-encode redirect-uri)
       "&code_challenge=" (oauth-lib/url-encode challenge)
       "&code_challenge_method=S256"
       "&state=" (oauth-lib/url-encode state)
       (when (seq scope)
         (str "&scope=" (oauth-lib/url-encode scope)))))

(defn- run-pkce-flow
  [name definition metadata interaction]
  (let [cfg (:oauth definition)
        {:keys [verifier challenge]} (oauth-lib/generate-pkce)
        state (oauth-lib/random-hex 16)
        redirect-uri (ensure-callback-redirect! cfg)
        client-id (resolve-client-id! name definition metadata)
        code-p (promise)
        authorize-endpoint (required-endpoint metadata :authorization_endpoint name)]
    (reset! current-flow {:state state :code-p code-p})
    (try
      (let [url (authorize-url authorize-endpoint client-id redirect-uri
                               challenge state (scopes-string cfg))]
        ((:notify interaction)
         {:type :auth-url :url url
          :instructions "Open the URL in your browser to authorize MCP access."})
        (try ((:open-url interaction) url) (catch Exception _ nil))
        (let [result (oauth-lib/wait-for-callback-or-manual
                      interaction code-p
                      {:type :manual-code
                       :message (str "Complete login in your browser, or paste the "
                                     "authorization code / redirect URL here:")}
                      600000)
              code (case (:source result)
                     :callback (:code (:value result))
                     :manual (let [parsed (oauth-lib/parse-authorization-input
                                           (:value result))]
                               (when (and (:state parsed)
                                          (not= (:state parsed) state))
                                 (throw (ex-info "OAuth state mismatch"
                                                 {:type :oauth-state-mismatch})))
                               (:code parsed))
                     :cancelled (throw (ex-info "Login cancelled"
                                                {:type :login-cancelled}))
                     :timeout (throw (ex-info "OAuth login timed out"
                                              {:type :oauth-timeout}))
                     :error (throw (:error result)))]
          (when-not code
            (throw (ex-info "Missing authorization code"
                            {:type :oauth-missing-code})))
          (let [tokens (oauth-lib/exchange-authorization-code
                        (required-endpoint metadata :token_endpoint name)
                        {:client-id client-id
                         :code code
                         :code-verifier verifier
                         :redirect-uri redirect-uri
                         :scope (scopes-string cfg)})]
            (store-tokens! name (tokens->store tokens))
            (store-server! name (assoc (server-entry name)
                                       :client-info {:client-id client-id
                                                     :redirect-uris [redirect-uri]}))
            :logged-in)))
      (finally
        ;; pi: manualAbort.abort — dismiss the pending manual-paste dialog
        ;; and unblock its prompt when the callback won (no-op when the
        ;; user already dismissed it)
        (when-let [abort-prompt! (:abort-prompt! interaction)]
          (abort-prompt!))
        (reset! current-flow nil)))))

(defn- run-device-flow
  [name definition metadata interaction]
  (let [cfg (:oauth definition)
        client-id (resolve-client-id! name definition metadata)
        device (oauth-lib/start-device-authorization
                (required-endpoint metadata :device_authorization_endpoint name)
                {:client-id client-id :scope (scopes-string cfg)})
        token-endpoint (required-endpoint metadata :token_endpoint name)]
    ((:notify interaction)
     {:type :device-code
      :user-code (:user-code device)
      :verification-uri (:verification-uri device)
      :expires-in-seconds (:expires-in device)})
    (try ((:open-url interaction) (:verification-uri device))
         (catch Exception _ nil))
    (let [tokens (oauth-lib/poll-oauth-device-code-flow
                  {:interval-seconds (:interval device)
                   :expires-in-seconds (:expires-in device)
                   :wait-before-first-poll true
                   :signal (:signal interaction)
                   :poll
                   (fn []
                     (let [raw (:body (oauth-lib/fetch-json
                                       token-endpoint
                                       {:method :post
                                        :headers {"Content-Type"
                                                  "application/x-www-form-urlencoded"
                                                  "Accept" "application/json"}
                                        :body (str "grant_type=urn:ietf:params:oauth:"
                                                   "grant-type:device_code"
                                                   "&device_code="
                                                   (oauth-lib/url-encode (:device-code device))
                                                   "&client_id="
                                                   (oauth-lib/url-encode client-id))
                                        :timeout 15000}))]
                       (cond
                         (string? (:access_token raw))
                         {:status :complete :value raw}

                         (string? (:error raw))
                         (case (:error raw)
                           "authorization_pending" {:status :pending}
                           "slow_down" {:status :slow_down
                                        :interval-seconds (:interval raw)}
                           {:status :failed
                            :message (str "Device flow failed: " (:error raw)
                                          (when (:error_description raw)
                                            (str ": " (:error_description raw))))})

                         :else
                         {:status :failed
                          :message "Invalid device token response"})))})]
      (store-tokens! name (tokens->store {:access (:access_token tokens)
                                          :expires-in (:expires_in tokens)
                                          :refresh (:refresh_token tokens)
                                          :scope (:scope tokens)}))
      (store-server! name (assoc (server-entry name)
                                 :client-info {:client-id client-id}))
      :logged-in)))

(defn run-flow!
  "Run the auth flow for a server (§7.8) — fresh login, replaces stored
   tokens. Machine grants (client-credentials / jwt-bearer) just fetch +
   cache a token, validating the config. INTERACTION: {:signal
   cancel-atom :has-ui bool :notify (fn [event-map]) :prompt (fn
   [prompt-map] → string) :open-url (fn [url])}. Returns :logged-in;
   throws on failure/cancel."
  [name definition interaction]
  (if (machine-grant? definition)
    (do (fetch-machine-token! name definition) :logged-in)
    (let [metadata (discover-meta definition)
          flow (resolve-flow (:oauth definition) metadata interaction)]
      (case flow
        :pkce (run-pkce-flow name definition metadata interaction)
        :device (run-device-flow name definition metadata interaction)))))

;; ─── Status (§9.5) ────────────────────────────────────────────────────────

(defn auth-status
  "Auth state for a server: nil (not configured) | :bearer | :logged-in |
   :expired | :none (oauth configured, no tokens) | :client-credentials |
   :jwt-bearer (machine grants — always available, tokens fetched on
   demand)."
  [name definition]
  (when (and (:url definition) (:auth definition))
    (case (:auth definition)
      :bearer (if (seq (bearer-token definition)) :bearer :none)
      :oauth (cond
               (machine-grant? definition) (grant-of definition)
               (nil? (server-entry name)) :none
               (token-expired? (server-entry name)) :expired
               :else :logged-in)
      nil)))
