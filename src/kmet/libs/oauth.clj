(ns kmet.libs.oauth
  "Generic OAuth machinery for Babashka, extracted from kmet.ai.oauth so the
   mcp-adapter extension (which cannot require kmet.ai.*) shares one
   implementation with the provider subscription flows.

   Contents:
   - RFC 8628 device-code polling (poll-oauth-device-code-flow, incl.
     slow_down/interval handling and cancel),
   - PKCE (generate-pkce) and the one-shot loopback callback server
     (start-callback-server — pi: node http.createServer in the loopback
     flows, port 0 binds an OS-assigned port),
   - the callback/manual-paste race (wait-for-callback-or-manual),
   - RFC 8414 authorization-server metadata discovery,
   - RFC 7591 dynamic client registration,
   - token endpoint exchange/refresh + RFC 8628 device-authorization start.

   Transport-agnostic: plain babashka.http-client (no kmet.ai.proxy — the
   caller routes through its own transport). The caller supplies token
   storage, browser opening and interaction fns; this namespace never
   touches the TUI or credential stores."
  (:require [babashka.http-client :as http]
            [cheshire.core :as json]
            [clojure.string :as str]))

;; ─── Device-code polling (pi: auth/oauth/device-code.ts) ──────────────────

(def ^:private cancel-message "Login cancelled")
(def ^:private timeout-message "Device flow timed out")
(def ^:private slow-down-timeout-message
  "Device flow timed out after one or more slow_down responses. This is often caused by clock drift in WSL or VM environments. Please sync or restart the VM clock and try again.")
(def ^:private minimum-interval-ms 1000)
;; RFC 8628 section 3.2: if the authorization server omits `interval`, the client must use 5 seconds.
(def ^:private default-poll-interval-seconds 5)
;; RFC 8628 section 3.5: `slow_down` means the polling interval must increase by 5 seconds.
(def ^:private slow-down-interval-increment-ms 5000)

(defn abortable-sleep
  "Sleep MS ms, aborting early when SIGNAL (an atom) turns truthy; then throw
   \"Login cancelled\" (pi abortableSleep). Checks in 100ms slices so cancel
   is never blocked by a long sleep."
  [ms signal]
  (let [end (+ (System/currentTimeMillis) ms)]
    (loop []
      (cond
        @signal (throw (ex-info cancel-message {:type :login-cancelled}))
        (< (System/currentTimeMillis) end)
        (do (Thread/sleep (min 100 (- end (System/currentTimeMillis))))
            (recur))))))

(defn poll-oauth-device-code-flow
  "RFC 8628 device-code polling (pi pollOAuthDeviceCodeFlow). Options:

     :interval-seconds      — initial poll interval (default 5)
     :expires-in-seconds    — overall deadline; nil = never
     :wait-before-first-poll — sleep one interval before the first poll
     :poll                  — (fn [] → {:status :complete :value v}
                                | {:status :pending}
                                | {:status :slow_down :interval-seconds n?}
                                | {:status :failed :message str})
     :signal                — cancel atom (truthy aborts with
                               \"Login cancelled\")
     :sleep                 — (fn [ms signal]) sleep implementation;
                               defaults to abortable-sleep. Callers that need
                               with-redefs-compatible sleeping (kmet.ai.oauth)
                               pass their own var here.

   Returns the completed value; throws ex-info on failure/timeout/cancel with
   pi's distinct messages (slow_down timeouts get the clock-drift hint)."
  [{:keys [interval-seconds expires-in-seconds wait-before-first-poll poll signal now sleep]}]
  (let [sleep (or sleep abortable-sleep)
        now (or now System/currentTimeMillis)
        deadline (if (number? expires-in-seconds)
                   (+ (now) (* expires-in-seconds 1000))
                   Double/POSITIVE_INFINITY)
        interval-ms (atom (max minimum-interval-ms
                               (long (Math/floor (* (or interval-seconds
                                                        default-poll-interval-seconds)
                                                    1000)))))
        slow-downs (atom 0)]
    (when wait-before-first-poll
      (let [remaining (- deadline (now))]
        (when (pos? remaining)
          (sleep (min @interval-ms remaining) signal))))
    (loop []
      (when @signal
        (throw (ex-info cancel-message {:type :login-cancelled})))
      (when (<= deadline (now))
        (throw (ex-info (if (pos? @slow-downs)
                          slow-down-timeout-message
                          timeout-message)
                        {:type :device-flow-timeout})))
      (let [result (poll)]
        (case (:status result)
          :failed (throw (ex-info (:message result) {:type :device-flow-failed}))
          :slow_down
          (do (swap! slow-downs inc)
              ;; Use the server-provided interval when given (GitHub reports
              ;; the new required minimum in `interval`); trusting only a
              ;; client-tracked value risks polling early forever under
              ;; WSL/VM clock drift. Otherwise apply RFC 8628 3.5: +5s.
              (let [server-interval (:interval-seconds result)]
                (reset! interval-ms
                        (if (and (number? server-interval) (pos? server-interval))
                          (max minimum-interval-ms
                               (long (Math/floor (* server-interval 1000))))
                          (max minimum-interval-ms
                               (+ @interval-ms slow-down-interval-increment-ms))))))
          nil)
        (if (= :complete (:status result))
          (:value result)
          (do
            (let [remaining (- deadline (now))]
              (when (pos? remaining)
                (sleep (min @interval-ms remaining) signal)))
            (recur)))))))

;; ─── PKCE (pi: auth/oauth/pkce.ts) ────────────────────────────────────────

(defn- random-bytes
  "N cryptographically random bytes (java.security.SecureRandom — no Web
   Crypto in Babashka)."
  [n]
  (let [bytes (byte-array n)]
    (.nextBytes (java.security.SecureRandom.) bytes)
    bytes))

(defn- base64url
  "Base64url without padding (pi: base64urlEncode)."
  [bytes]
  (.encodeToString (.withoutPadding (java.util.Base64/getUrlEncoder)) bytes))

(defn generate-pkce
  "PKCE verifier + challenge (pi pkce.ts): verifier = 32 random bytes
   base64url; challenge = base64url(SHA-256(verifier))."
  []
  (let [verifier (base64url (random-bytes 32))
        digest (.digest (java.security.MessageDigest/getInstance "SHA-256")
                        (.getBytes verifier "UTF-8"))]
    {:verifier verifier :challenge (base64url digest)}))

(defn random-hex
  "N random bytes as lowercase hex (pi createState — 16 bytes for the codex
   state, 16 for the openrouter callback path)."
  [n]
  (apply str (map #(format "%02x" %) (random-bytes n))))

(defn url-encode
  "Form-urlencode a string (pi URLSearchParams)."
  [s]
  (java.net.URLEncoder/encode s "UTF-8"))

;; ─── OAuth callback server + login pages (pi: node http.createServer in
;;    auth/oauth/{anthropic,openai-codex,openrouter}.ts + oauth-page.ts) ────
;; Babashka has no bundled HTTP server, so the one-shot loopback callback is
;; a plain java.net.ServerSocket: read one HTTP request, answer it, close.

(defn- escape-html
  "Escape & < > \" for HTML text (pi escapeHtml)."
  [s]
  (-> (str s)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")
      (str/replace "\"" "&quot;")))

(defn- oauth-page
  "Port of pi's oauth-page.ts renderPage: a dark browser page shown after
   the OAuth callback. The pi logo is dropped (kmet-brandless)."
  [heading message details]
  (str "<!doctype html>\n<html lang=\"en\">\n<head>\n"
       "  <meta charset=\"utf-8\" />\n"
       "  <meta name=\"viewport\" content=\"width=device-width, initial-scale=1\" />\n"
       "  <title>" (escape-html heading) "</title>\n"
       "  <style>"
       ":root{--text:#fafafa;--text-dim:#a1a1aa;--page-bg:#09090b;"
       "--font-sans:ui-sans-serif,system-ui,-apple-system,BlinkMacSystemFont,\"Segoe UI\",Roboto,\"Helvetica Neue\",Arial,\"Noto Sans\",sans-serif,\"Apple Color Emoji\",\"Segoe UI Emoji\",\"Segoe UI Symbol\",\"Noto Color Emoji\";"
       "--font-mono:ui-monospace,SFMono-Regular,Menlo,Monaco,Consolas,\"Liberation Mono\",\"Courier New\",monospace;}"
       "*{box-sizing:border-box}html{color-scheme:dark}body{margin:0;min-height:100vh;display:flex;align-items:center;justify-content:center;padding:24px;background:var(--page-bg);color:var(--text);font-family:var(--font-sans);text-align:center}"
       "main{width:100%;max-width:560px;display:flex;flex-direction:column;align-items:center;justify-content:center}"
       "h1{margin:0 0 10px;font-size:28px;line-height:1.15;font-weight:650;color:var(--text)}"
       "p{margin:0;line-height:1.7;color:var(--text-dim);font-size:15px}"
       ".details{margin-top:16px;font-family:var(--font-mono);font-size:13px;color:var(--text-dim);white-space:pre-wrap;word-break:break-word}"
       "</style>\n</head>\n<body>\n  <main>\n"
       "    <h1>" (escape-html heading) "</h1>\n"
       "    <p>" (escape-html message) "</p>\n"
       (when details (str "    <div class=\"details\">" (escape-html details) "</div>\n"))
       "  </main>\n</body>\n</html>"))

(defn oauth-success-html
  "The success page shown after a completed callback (pi oauthSuccessHtml)."
  [message]
  (oauth-page "Authentication successful" message nil))

(defn oauth-error-html
  "The failure page shown for a bad/denied callback (pi oauthErrorHtml)."
  [message & [details]]
  (oauth-page "Authentication failed" message details))

(defn callback-host
  "Loopback bind host for the callback servers (pi getProviderEnvValue
   PI_OAUTH_CALLBACK_HOST): default 127.0.0.1."
  []
  (or (System/getenv "PI_OAUTH_CALLBACK_HOST") "127.0.0.1"))

(defn parse-query-string
  "Parse an HTTP query string into {decoded-keyword decoded-value}
   (+ = space, like URLSearchParams)."
  [query-string]
  (when (seq query-string)
    (into {} (for [pair (str/split query-string #"&")
                   :let [[k v] (str/split pair #"=" 2)]
                   :when (seq k)]
               [(keyword (java.net.URLDecoder/decode k "UTF-8"))
                (java.net.URLDecoder/decode (or v "") "UTF-8")]))))

(defn- read-http-request
  "Read one HTTP/1.1 request from IN: the request line + headers (discarded;
   the OAuth callbacks are GET requests with no body). Returns {:method ..
   :path .. :query-params {..}} or nil on EOF."
  [in]
  (let [reader (java.io.BufferedReader. (java.io.InputStreamReader. in "UTF-8"))
        request-line (try (.readLine reader) (catch Exception _ nil))]
    (when (seq request-line)
      (loop []
        (let [line (try (.readLine reader) (catch Exception _ nil))]
          (when (and line (seq line)) (recur))))
      (let [[method target] (str/split request-line #"\s+" 3)
            [path query-string] (str/split (or target "") #"\?" 2)]
        {:method (or method "")
         :path (or path "")
         :query-params (parse-query-string query-string)}))))

(defn- write-http-response
  "Write an HTTP/1.1 response (status + text/html body, Connection: close)
   to OUT."
  [out {:keys [status body]}]
  (let [status (or status 200)
        reason ({200 "OK" 400 "Bad Request" 404 "Not Found"
                 409 "Conflict" 500 "Internal Server Error" 502 "Bad Gateway"}
                status)
        body-bytes (.getBytes (str (or body "")) "UTF-8")
        head (str "HTTP/1.1 " status " " reason "\r\n"
                  "Content-Type: text/html; charset=utf-8\r\n"
                  "Cache-Control: no-store\r\n"
                  "Content-Length: " (alength body-bytes) "\r\n"
                  "Connection: close\r\n\r\n")]
    (.write out (.getBytes head "UTF-8"))
    (.write out body-bytes)
    (.flush out)))

(defn start-callback-server
  "One-shot HTTP callback server (pi: node http.createServer in the loopback
   OAuth flows). PORT 0 binds an ephemeral port (:port in the result).
   HANDLER receives {:method .. :path .. :query-params {..}} and returns
   {:status n :body html} (nil → no response). The accept loop runs on a
   future and every connection is answered on its own future, one request,
   then closed; :close stops listening and releases the port."
  [port handler]
  (let [server (java.net.ServerSocket. port 10
                                       (java.net.InetAddress/getByName (callback-host)))
        closed (atom false)
        close! (fn []
                 (when-not @closed
                   (reset! closed true)
                   (try (.close server) (catch Exception _ nil))))]
    (future
      (while (not @closed)
        (try
          (let [socket (.accept server)]
            (future
              (try
                (with-open [s socket
                            in (.getInputStream s)
                            out (.getOutputStream s)]
                  (when-let [req (read-http-request in)]
                    (when-let [resp (handler req)]
                      (write-http-response out resp))))
                (catch Exception _ nil))))
          (catch Exception _ nil))))
    {:port (.getLocalPort server) :close close!}))

(defn parse-authorization-input
  "pi parseAuthorizationInput (anthropic/codex): extract {:code .. :state ..}
   from a full URL, 'code#state', a 'code=...' query, or a bare code."
  [input]
  (let [value (str/trim (or input ""))]
    (when (seq value)
      (cond
        (str/includes? value "://")
        (let [uri (try (java.net.URI. value) (catch Exception _ nil))]
          (if uri
            (parse-query-string (.getQuery uri))
            {}))
        (str/includes? value "#")
        (let [[code state] (str/split value #"#" 2)]
          {:code code :state state})
        (str/includes? value "code=")
        (parse-query-string value)
        :else {:code value}))))

(defn wait-for-callback-or-manual
  "Race the callback server's CODE-P promise against a manual-paste prompt
   (pi: interaction.prompt(...) raced with server.waitForCode()). The manual
   prompt runs on a future — kmet's UI prompt blocks its caller — and the
   flow's :abort-prompt! (an optional UI-provided interaction hook) closes
   the pending dialog when the callback wins. Returns {:source :callback
   :value v} | {:source :manual :value input} | {:source :error :error ex}
   | {:source :cancelled} | {:source :timeout}."
  [interaction code-p prompt-map timeout-ms]
  (let [result-p (promise)
        deadline (+ (System/currentTimeMillis) (or timeout-ms 600000))
        _ (future
            (try
              (deliver result-p {:source :manual
                                 :value ((:prompt interaction) prompt-map)})
              (catch Exception e
                (deliver result-p {:source :error :error e}))))
        _ (future (deliver result-p {:source :callback :value (deref code-p)}))]
    ;; The finally settles CODE-P so the callback future above never stays
    ;; blocked on a login that ended via manual/timeout/cancel (a no-op when
    ;; the callback already won — deliver is one-shot).
    (try
      (loop []
        (let [result (deref result-p 200 :pending)]
          (cond
            (not= :pending result) result
            @(:signal interaction) {:source :cancelled}
            (< deadline (System/currentTimeMillis)) {:source :timeout}
            :else (recur))))
      (finally (deliver code-p nil)))))

;; ─── HTTP (RFC 8414 discovery / RFC 7591 DCR / token endpoints) ───────────
;; Plain babashka.http-client — transport-agnostic (no kmet.ai.proxy). The
;; caller supplies proxy routing if needed.

(defn fetch-json
  "HTTP request expecting JSON via plain babashka.http-client. OPTS:
   :method (default :post), :headers, :body — a string, or a map that is
   JSON-encoded; :timeout ms. Returns {:status n :body parsed-map-or-nil}:
   non-2xx responses throw ex-info '<status>: <body>' with the raw response
   body in the message."
  [url opts]
  (let [opts (cond-> (assoc opts :url url :throw false)
               (map? (:body opts)) (update :body json/generate-string))
        response (http/request opts)
        status (:status response)
        body (:body response)]
    (when-not (<= 200 status 299)
      (throw (ex-info (str status ": " body)
                      {:type :oauth-http :status status})))
    {:status status
     :body (when (seq body) (json/parse-string body true))}))

(defn discover-authorization-server
  "RFC 8414 authorization-server metadata discovery for an MCP server URL:
   GET <url>/.well-known/oauth-authorization-server, falling back to
   <url>/.well-known/oauth-protected-resource. Returns the metadata map
   (keys as received) or nil when neither endpoint answers (non-2xx or
   transport error — pi's probe returns {} on failure). OPTS: :headers
   (static headers to send), :timeout-ms (default 5000)."
  [url & [opts]]
  (let [base (str/replace url #"/+$" "")
        candidates [(str base "/.well-known/oauth-authorization-server")
                    (str base "/.well-known/oauth-protected-resource")]
        headers (merge {"Accept" "application/json"} (:headers opts))]
    (loop [[candidate & more] candidates]
      (when candidate
        (let [result (try
                       (fetch-json candidate
                                   {:method :get
                                    :headers headers
                                    :timeout (or (:timeout-ms opts) 5000)})
                       (catch Exception _ nil))]
          (if (and result (map? (:body result)))
            (:body result)
            (recur more)))))))

(defn register-client
  "RFC 7591 dynamic client registration. POSTS to REGISTRATION-ENDPOINT and
   returns the registered client metadata map (with :client_id, and
   :client_secret when the server issues one). OPTS:
     :redirect-uris                — vector of redirect URIs (required)
     :client-name                  — display name (default \"kmet\")
     :client-uri                   — client homepage
     :token-endpoint-auth-method   — default \"none\"
     :grant-types                  — default [\"authorization_code\"
                                      \"refresh_token\"]
     :response-types               — default [\"code\"]
     :scope                        — optional scope hint
     :timeout-ms                   — default 15000"
  [registration-endpoint {:keys [redirect-uris client-name client-uri
                                 token-endpoint-auth-method grant-types
                                 response-types scope timeout-ms]
                          :or {client-name "kmet"
                               token-endpoint-auth-method "none"
                               grant-types ["authorization_code" "refresh_token"]
                               response-types ["code"]}}]
  (when-not (seq redirect-uris)
    (throw (ex-info "OAuth dynamic client registration requires redirect URIs"
                    {:type :oauth-invalid-config})))
  (:body (fetch-json registration-endpoint
                     {:method :post
                      :headers {"Content-Type" "application/json"
                                "Accept" "application/json"}
                      :body (cond-> {:redirect_uris redirect-uris
                                     :client_name client-name
                                     :token_endpoint_auth_method token-endpoint-auth-method
                                     :grant_types grant-types
                                     :response_types response-types}
                              client-uri (assoc :client_uri client-uri)
                              scope (assoc :scope scope))
                      :timeout (or timeout-ms 15000)})))

(defn- token-response
  "Parse a token endpoint response into the normalized
   {:access str :refresh str? :expires-in n :scope str?} trio; throws when
   the access token or expiry is missing (pi readTokenResponse)."
  [op data]
  (let [access (:access_token data)
        refresh (:refresh_token data)
        expires-in (:expires_in data)]
    (when-not (and (string? access) (number? expires-in))
      (throw (ex-info (str "OAuth token " op " response missing fields: "
                           (pr-str data))
                      {:type :oauth-invalid-response})))
    (cond-> {:access access :expires-in expires-in}
      (string? refresh) (assoc :refresh refresh)
      (:scope data) (assoc :scope (:scope data)))))

(defn exchange-authorization-code
  "Exchange an authorization code at TOKEN-ENDPOINT (grant_type
   authorization_code + PKCE verifier). OPTS: :client-id (required unless
   the endpoint authenticates the client another way), :code, :code-verifier,
   :redirect-uri, :scope, :timeout-ms. Returns the normalized token map
   {:access :refresh :expires-in :scope}."
  [token-endpoint {:keys [client-id code code-verifier redirect-uri scope]
                   :as opts}]
  (let [body (cond-> {"grant_type" "authorization_code"
                      "client_id" client-id
                      "code" code}
               code-verifier (assoc "code_verifier" code-verifier)
               redirect-uri (assoc "redirect_uri" redirect-uri)
               scope (assoc "scope" scope))
        data (:body (fetch-json token-endpoint
                                {:method :post
                                 :headers {"Content-Type"
                                           "application/x-www-form-urlencoded"
                                           "Accept" "application/json"}
                                 :body (str/join "&" (map (fn [[k v]]
                                                            (str (url-encode k) "=" (url-encode (str v))))
                                                          body))
                                 :timeout (or (:timeout-ms opts) 15000)}))]
    (token-response "exchange" data)))

(defn refresh-access-token
  "Refresh tokens at TOKEN-ENDPOINT (grant_type refresh_token). OPTS:
   :client-id, :refresh-token (required), :scope, :timeout-ms. Returns the
   normalized token map {:access :refresh :expires-in :scope}."
  [token-endpoint {:keys [client-id refresh-token scope timeout-ms]}]
  (when-not (seq refresh-token)
    (throw (ex-info "OAuth refresh requires a refresh token"
                    {:type :oauth-invalid-config})))
  (let [body (cond-> {"grant_type" "refresh_token"
                      "refresh_token" refresh-token}
               client-id (assoc "client_id" client-id)
               scope (assoc "scope" scope))
        data (:body (fetch-json token-endpoint
                                {:method :post
                                 :headers {"Content-Type"
                                           "application/x-www-form-urlencoded"
                                           "Accept" "application/json"}
                                 :body (str/join "&" (map (fn [[k v]]
                                                            (str (url-encode k) "=" (url-encode (str v))))
                                                          body))
                                 :timeout (or timeout-ms 15000)}))]
    (token-response "refresh" data)))

(defn start-device-authorization
  "Start an RFC 8628 device flow at DEVICE-ENDPOINT. OPTS: :client-id
   (required), :scope, :timeout-ms. Validates the response fields and that
   the verification URI is http(s) — it is opened in the user's browser, so
   `open` must never run an executable or similar (pi). Returns
   {:device-code :user-code :verification-uri :interval :expires-in}."
  [device-endpoint {:keys [client-id scope timeout-ms]}]
  (when-not (seq client-id)
    (throw (ex-info "OAuth device flow requires a client id"
                    {:type :oauth-invalid-config})))
  (let [body (cond-> {"client_id" client-id}
               scope (assoc "scope" scope))
        data (:body (fetch-json device-endpoint
                                {:method :post
                                 :headers {"Accept" "application/json"
                                           "Content-Type"
                                           "application/x-www-form-urlencoded"}
                                 :body (str/join "&" (map (fn [[k v]]
                                                            (str (url-encode k) "=" (url-encode (str v))))
                                                          body))
                                 :timeout (or timeout-ms 15000)}))
        device-code (:device_code data)
        user-code (:user_code data)
        verification-uri (:verification_uri data)
        interval (:interval data)
        expires-in (:expires_in data)]
    (when-not (and (string? device-code)
                   (string? user-code)
                   (string? verification-uri)
                   (or (nil? interval) (number? interval))
                   (number? expires-in))
      (throw (ex-info "Invalid device code response fields"
                      {:type :oauth-invalid-response})))
    (let [uri (try (java.net.URI. verification-uri)
                   (catch Exception _ nil))
          scheme (some-> uri .getScheme str/lower-case)]
      (when-not (and uri (contains? #{"https" "http"} scheme))
        (throw (ex-info "Untrusted verification_uri in device code response"
                        {:type :oauth-untrusted-uri})))
      {:device-code device-code
       :user-code user-code
       :verification-uri (.toString uri)
       :interval interval
       :expires-in expires-in})))
