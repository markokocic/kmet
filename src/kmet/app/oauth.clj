(ns kmet.app.oauth
  "OAuth subscription auth (pi: packages/ai/src/auth/types.ts +
   auth/oauth/*.ts, adapted to Babashka).

   kmet's only OAuth-capable catalog provider is github-copilot (device-code,
   RFC 8628); the generic OAuthAuth record + device-code poll flow + PKCE
   machinery are built so anthropic (Pro/Max), openai-codex, openrouter, and
   future subscription providers plug in.

   Credential model (pi): OAuth credentials are plain maps
   {:type :oauth :access str :refresh str :expires ms
    :available-model-ids [str] :enterprise-url str?} stored in auth.edn
   (expires = provider expiry × 1000 − 5 min skew, pi). The refresh/to-auth
   split lets the credential store own the locked refresh pattern: refresh
   produces a credential, to-auth derives request auth (api-key + per-
   credential base-url, e.g. Copilot's proxy-ep endpoint).

   Interaction (pi AuthInteraction): a map {:signal cancel-atom :prompt
   (fn [prompt-map] → string) :notify (fn [event] nil)} provided by the UI
   (kmet.modes.interactive) — this namespace never touches the TUI."
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [kmet.app.proxy :as proxy]))

;; ─── Records (pi: types.ts OAuthAuth) ──────────────────────────────────────

(defrecord OAuthAuth [name            ;; display name, e.g. "GitHub Copilot"
                      is-subscription? ;; bool: access is a provider subscription
                      login-label     ;; selector label for the oauth login option
                      login           ;; (fn [interaction] → oauth credential)
                      refresh         ;; (fn [credential signal] → oauth credential)
                      to-auth         ;; (fn [credential] → {:api-key str :base-url str?})
                      ])

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

(defn- abortable-sleep
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

   Returns the completed value; throws ex-info on failure/timeout/cancel with
   pi's distinct messages (slow_down timeouts get the clock-drift hint)."
  [{:keys [interval-seconds expires-in-seconds wait-before-first-poll poll signal now]}]
  (let [now (or now System/currentTimeMillis)
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
          (abortable-sleep (min @interval-ms remaining) signal))))
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
                (abortable-sleep (min @interval-ms remaining) signal)))
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

;; ─── GitHub Copilot device-code flow (pi: auth/oauth/github-copilot.ts) ───

(def ^:private copilot-client-id
  "The GitHub OAuth client id used by the Copilot Chat integrations
   (pi decodes the same literal)."
  "Iv1.b507a08c87ecfe98")

(def ^:private copilot-headers
  {"User-Agent" "GitHubCopilotChat/0.35.0"
   "Editor-Version" "vscode/1.107.0"
   "Editor-Plugin-Version" "copilot-chat/0.35.0"
   "Copilot-Integration-Id" "vscode-chat"})

(def ^:private copilot-api-version "2026-06-01")
(def ^:private copilot-policy-concurrency 4)

(defn- url-encode
  "Form-urlencode a string (pi URLSearchParams)."
  [s]
  (java.net.URLEncoder/encode s "UTF-8"))

(defn- normalize-domain
  "Trim a GitHub Enterprise URL/domain to its hostname; nil when blank or
   unparsable (pi normalizeDomain)."
  [input]
  (let [trimmed (str/trim (or input ""))]
    (when (seq trimmed)
      (try
        (let [uri (java.net.URI. (if (str/includes? trimmed "://")
                                   trimmed
                                   (str "https://" trimmed)))
              host (.getHost uri)]
          (when (seq host) host))
        (catch Exception _ nil)))))

(defn- get-urls
  "Endpoints for a GitHub domain (pi getUrls)."
  [domain]
  {:device-code-url (str "https://" domain "/login/device/code")
   :access-token-url (str "https://" domain "/login/oauth/access_token")
   :copilot-token-url (str "https://api." domain "/copilot_internal/v2/token")})

(defn- get-base-url-from-token
  "Parse the proxy-ep claim from a Copilot token and convert it to the API
   base URL (pi getBaseUrlFromToken): 'proxy.xxx' → 'https://api.xxx'."
  [token]
  (when-let [proxy-host (second (re-find #"proxy-ep=([^;]+)" token))]
    (str "https://" (str/replace proxy-host #"^proxy\." "api."))))

(defn get-github-copilot-base-url
  "API base URL for a Copilot token/enterprise (pi getGitHubCopilotBaseUrl):
   the token's proxy-ep claim when present, else the enterprise
   copilot-api endpoint, else the individual default."
  ([token] (get-github-copilot-base-url token nil))
  ([token enterprise-domain]
   (or (when token (get-base-url-from-token token))
       (when enterprise-domain (str "https://copilot-api." enterprise-domain))
       "https://api.individual.githubcopilot.com")))

(defn- fetch-json
  "HTTP request expecting JSON (pi fetchJson) via proxy/request-json, which
   throws ex-info on non-2xx and parses the JSON body."
  [url opts]
  (proxy/request-json url opts nil))

(defn- start-device-flow
  "Start a device-code flow for a GitHub domain (pi startDeviceFlow):
   POST /login/device/code with the client id. Validates the response fields
   and that the verification URI is http(s)."
  [domain]
  (let [{:keys [device-code-url]} (get-urls domain)
        data (fetch-json device-code-url
                         {:method :post
                          :headers {"Accept" "application/json"
                                    "Content-Type" "application/x-www-form-urlencoded"
                                    "User-Agent" "GitHubCopilotChat/0.35.0"}
                          :body (str "client_id=" copilot-client-id "&scope=read%3Auser")
                          :timeout 15000})
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
    ;; The verification URI is opened in the user's browser — force a real
    ;; http(s) URL so `open` can never run an executable or similar (pi).
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

(defn- poll-for-github-access-token
  "Poll /login/oauth/access_token until the user authorizes (pi
   pollForGitHubAccessToken). Returns the GitHub access token."
  [domain device signal]
  (poll-oauth-device-code-flow
   {:interval-seconds (:interval device)
    :expires-in-seconds (:expires-in device)
    :wait-before-first-poll true
    :signal signal
    :poll
    (fn []
      (let [{:keys [access-token-url]} (get-urls domain)
            raw (fetch-json access-token-url
                            {:method :post
                             :headers {"Accept" "application/json"
                                       "Content-Type" "application/x-www-form-urlencoded"
                                       "User-Agent" "GitHubCopilotChat/0.35.0"}
                             :body (str "client_id=" copilot-client-id
                                        "&device_code=" (url-encode (:device-code device))
                                        "&grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Adevice_code")
                             :timeout 15000})]
        (cond
          (string? (:access_token raw))
          {:status :complete :value (:access_token raw)}

          (string? (:error raw))
          (case (:error raw)
            "authorization_pending" {:status :pending}
            "slow_down" {:status :slow_down :interval-seconds (:interval raw)}
            {:status :failed
             :message (str "Device flow failed: " (:error raw)
                           (when (:error_description raw)
                             (str ": " (:error_description raw))))})

          :else {:status :failed :message "Invalid device token response"})))}))

(defn- refresh-github-copilot-access-token
  "Exchange a GitHub access/refresh token for a Copilot token (pi
   refreshGitHubCopilotAccessToken): POST /copilot_internal/v2/token with
   Authorization: Bearer. Returns the oauth credential map with the 5-min
   expiry skew applied."
  [refresh-token enterprise-domain]
  (let [domain (or enterprise-domain "github.com")
        {:keys [copilot-token-url]} (get-urls domain)
        raw (fetch-json copilot-token-url
                        {:method :post
                         :headers (assoc copilot-headers
                                         "Accept" "application/json"
                                         "Authorization" (str "Bearer " refresh-token))
                         :timeout 15000})
        token (:token raw)
        expires-at (:expires_at raw)]
    (when-not (and (string? token) (number? expires-at))
      (throw (ex-info "Invalid Copilot token response fields"
                      {:type :oauth-invalid-response})))
    {:type :oauth
     :refresh refresh-token
     :access token
     :expires (- (* expires-at 1000) (* 5 60 1000))
     :enterprise-url enterprise-domain}))

(defn- parse-available-copilot-model-ids
  "Parse the /models response into the model ids the account can use (pi
   parseAvailableCopilotModelIds): picker-enabled ids take precedence,
   falling back to policy-enabled ids for individual accounts (which report
   false picker flags despite explicit enabled policies)."
  [raw allow-policy-fallback]
  (let [data (:data raw)]
    (when-not (vector? data)
      (throw (ex-info "Invalid Copilot models response"
                      {:type :oauth-invalid-response})))
    (let [picker-ids (atom [])
          policy-ids (atom [])]
      (doseq [item data]
        (when (map? item)
          (let [id (:id item)]
            (when (string? id)
              (let [supports (get-in item [:capabilities :supports])
                    policy (:policy item)]
                (when-not (false? (:tool_calls supports))
                  (when (and (true? (:model_picker_enabled item))
                             (not= "disabled" (:state policy)))
                    (swap! picker-ids conj id))
                  (when (= "enabled" (:state policy))
                    (swap! policy-ids conj id))))))))
      (if (or (seq @picker-ids) (not allow-policy-fallback))
        @picker-ids
        @policy-ids))))

(defn- fetch-available-github-copilot-model-ids
  "GET the Copilot /models endpoint and parse the usable ids (pi
   fetchAvailableGitHubCopilotModelIds; 5s timeout like pi's abort race)."
  [copilot-token enterprise-domain]
  (let [base-url (get-github-copilot-base-url copilot-token enterprise-domain)
        ;; Individual accounts return false for every picker flag despite
        ;; explicit enabled policies — limit the fallback to that endpoint.
        allow-policy-fallback (= base-url "https://api.individual.githubcopilot.com")
        raw (fetch-json (str base-url "/models")
                        {:method :get
                         :headers (assoc copilot-headers
                                         "Accept" "application/json"
                                         "Authorization" (str "Bearer " copilot-token)
                                         "X-GitHub-Api-Version" copilot-api-version)
                         :timeout 10000})]
    (parse-available-copilot-model-ids raw allow-policy-fallback)))

(defn- enable-github-copilot-model
  "POST a policy-enable for one Copilot model (pi enableGitHubCopilotModel);
   some models (Claude, Grok) need it before they can be used. Non-2xx or
   transport failure → false (pi swallows the error)."
  [token model-id enterprise-domain]
  (let [base-url (get-github-copilot-base-url token enterprise-domain)
        url (str base-url "/models/" model-id "/policy")]
    (try
      (let [response (proxy/request-json
                      url
                      {:method :post
                       :headers (assoc copilot-headers
                                       "Content-Type" "application/json"
                                       "Authorization" (str "Bearer " token)
                                       "openai-intent" "chat-policy"
                                       "x-interaction-type" "chat-policy")
                       :body (json/generate-string {:state "enabled"})
                       :timeout 15000}
                      nil)]
        (<= 200 (:status response) 299))
      (catch Exception _ false))))

(defn- enable-all-github-copilot-models
  "Enable all catalog Copilot models in batches of 4 (pi
   enableAllGitHubCopilotModels — COPILOT_POLICY_CONCURRENCY)."
  [token enterprise-domain model-ids]
  (doseq [batch (partition-all copilot-policy-concurrency model-ids)]
    (doseq [model-id batch]
      (enable-github-copilot-model token model-id enterprise-domain))))

(defn- login-github-copilot
  "Full device-code login (pi loginGitHubCopilot): prompt for the enterprise
   domain, start the flow, notify the device code, poll, exchange for the
   Copilot token, enable models, fetch the account's available model ids."
  [interaction model-ids]
  (let [input ((:prompt interaction)
               {:type :text
                :message "GitHub Enterprise URL/domain (blank for github.com)"
                :placeholder "company.ghe.com"})
        _ (when @(:signal interaction)
            (throw (ex-info cancel-message {:type :login-cancelled})))
        trimmed (str/trim (or input ""))
        enterprise-domain (normalize-domain input)]
    (when (and (seq trimmed) (nil? enterprise-domain))
      (throw (ex-info "Invalid GitHub Enterprise URL/domain"
                      {:type :oauth-invalid-domain})))
    (let [domain (or enterprise-domain "github.com")
          device (start-device-flow domain)]
      ((:notify interaction)
       {:type :device-code
        :user-code (:user-code device)
        :verification-uri (:verification-uri device)
        :interval-seconds (:interval device)
        :expires-in-seconds (:expires-in device)})
      (let [github-access-token (poll-for-github-access-token
                                 domain device (:signal interaction))
            credentials (refresh-github-copilot-access-token
                         github-access-token enterprise-domain)]
        ((:notify interaction) {:type :progress :message "Enabling models..."})
        (enable-all-github-copilot-models (:access credentials)
                                          enterprise-domain
                                          model-ids)
        (assoc credentials
               :available-model-ids (fetch-available-github-copilot-model-ids
                                     (:access credentials)
                                     enterprise-domain))))))

(defn- refresh-github-copilot-token
  "Refresh a Copilot credential (pi refreshGitHubCopilotToken): new access
   token + a refreshed available-model-ids list."
  [credential]
  (let [credentials (refresh-github-copilot-access-token
                     (:refresh credential) (:enterprise-url credential))]
    (assoc credentials
           :available-model-ids (fetch-available-github-copilot-model-ids
                                 (:access credentials)
                                 (:enterprise-url credentials)))))

(defn- copilot-enterprise-domain
  "Normalized enterprise domain from a credential (pi
   copilotEnterpriseDomain), nil when absent."
  [credential]
  (when (and (string? (:enterprise-url credential))
             (seq (:enterprise-url credential)))
    (normalize-domain (:enterprise-url credential))))

(defn make-github-copilot-oauth
  "The github-copilot OAuthAuth (pi githubCopilotOAuth). MODEL-IDS — a thunk
   returning the catalog model ids used for the enable-all policy step
   (kmet reads the committed github-copilot.edn catalog; pi imports the
   static GITHUB_COPILOT_MODELS list)."
  [model-ids]
  (map->OAuthAuth
   {:name "GitHub Copilot"
    :is-subscription? true
    :login (fn [interaction] (login-github-copilot interaction (model-ids)))
    :refresh (fn [credential _signal]
               (refresh-github-copilot-token credential))
    :to-auth (fn [credential]
               {:api-key (:access credential)
                :base-url (get-github-copilot-base-url
                           (:access credential)
                           (copilot-enterprise-domain credential))})}))
