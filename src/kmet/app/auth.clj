(ns kmet.app.auth
  "Provider credential resolution (pi: packages/ai credential-store +
   env-api-keys.ts, adapted to EDN).

   auth.edn shape: {provider {:key \"...\"}} — kmet-internal (pi's auth.json
   shape not required for parity). Loaded at startup (config/load-config);
   /login and /logout write it under a file lock.

   Resolution order (pi composeApiKeyAuth.resolve): auth.edn credential →
   models.edn/extension :api-key config value (registered via
   set-config-key-source! by models/load-models-config!) → env vars in pi
   order."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [babashka.fs :as fs]
            [kmet.app.config-value :as config-value]
            [kmet.libs.file-lock :as file-lock]))

;; ─── Env var table (pi env-api-keys.ts) ────────────────────────────────────

(def ^:private getenv
  "Env lookup — indirected so tests can redef it without touching the real
   environment (babashka cannot set env vars)."
  (fn [k] (System/getenv k)))

(def env-vars-by-provider
  "Provider → env var names, in pi's order (pi: env-api-keys.ts).
   :anthropic participates in all three (pi findEnvKeys): AUTH_TOKEN is the
   Authorization: Bearer path (skipped by resolve-api-key, pi getEnvApiKey),
   OAUTH_TOKEN / API_KEY are the x-api-key paths. :google is reserved for a
   later provider set."
  {:opencode-go ["OPENCODE_API_KEY"]
   :opencode ["OPENCODE_API_KEY"]
   :deepseek ["DEEPSEEK_API_KEY"]
   :github-copilot ["COPILOT_GITHUB_TOKEN"]
   :openai ["OPENAI_API_KEY"]
   :xai ["XAI_API_KEY"]
   :openai-codex []                          ;; OAuth only (no env var)
   :azure-openai-responses ["AZURE_OPENAI_API_KEY"]
   :anthropic ["ANTHROPIC_AUTH_TOKEN" "ANTHROPIC_OAUTH_TOKEN" "ANTHROPIC_API_KEY"]
   :google ["GEMINI_API_KEY"]})

(defn provider-env-vars
  "Env var names for a provider, in pi's order (empty vector when unknown)."
  [provider]
  (get env-vars-by-provider provider []))

(defn anthropic-auth-token
  "The ANTHROPIC_AUTH_TOKEN value for the :anthropic provider (nil otherwise)
   — the Authorization: Bearer credential path (pi anthropic provider
   resolve: auth-token → auth.headers). AUTH_TOKEN is skipped by
   resolve-api-key, so requests that use it must branch on this instead."
  [provider]
  (when (= :anthropic provider)
    (getenv "ANTHROPIC_AUTH_TOKEN")))

(defn- api-key-env-vars
  "Env vars that provide an x-api-key: for :anthropic, the trio minus
   ANTHROPIC_AUTH_TOKEN (pi getEnvApiKey: first env key ≠ AUTH_TOKEN — the
   token travels as Authorization: Bearer instead)."
  [provider]
  (if (= :anthropic provider)
    (remove #(= "ANTHROPIC_AUTH_TOKEN" %) (provider-env-vars provider))
    (provider-env-vars provider)))

;; ─── auth.edn state ────────────────────────────────────────────────────────

(def ^:private default-auth {})

(defonce ^:private auth-atom (atom default-auth))

(defn auth-file-path
  "Global auth file (~/.kmet/agent/auth.edn), home-expanded."
  []
  (str (fs/path (System/getProperty "user.home") ".kmet" "agent" "auth.edn")))

(defn valid-credential?
  "Validate an auth.edn entry (pi auth-storage parse: non-object entries
   throw): the api-key shape {:key string?} (key optional — pi
   ApiKeyCredential.key is optional) or the oauth shape {:type :oauth with
   string :access/:refresh and a finite number :expires}. Non-map entries
   are invalid — a stored entry is always a map."
  [credential]
  (and (map? credential)
       (if (= :oauth (:type credential))
         (and (string? (:access credential))
              (string? (:refresh credential))
              (number? (:expires credential))
              (Double/isFinite (:expires credential)))
         (or (nil? (:key credential)) (string? (:key credential))))))

(defn- read-auth-file
  "Parse auth.edn as a map, nil when missing or malformed. Entries failing
   valid-credential? are dropped (pi auth-storage parse throws — kmet keeps
   startup lenient, the invalid entry just never resolves)."
  []
  (let [f (fs/file (auth-file-path))]
    (when (fs/exists? f)
      (try (let [parsed (edn/read-string (slurp f))]
             (when (map? parsed)
               (into {} (filter (fn [[_ v]] (valid-credential? v))) parsed)))
           (catch Exception _ nil)))))

(defn- pretty-auth
  "Canonical pretty EDN for auth.edn: one entry per line, closing brace on
   its own line (same format as config's settings files)."
  [m]
  (str "{" (str/join "\n " (for [[k v] m] (str (pr-str k) " " (pr-str v)))) "\n}\n"))

(defn load-auth!
  "Load auth.edn into the auth atom; returns the auth map. Called at startup
   (config/load-config); /login and /logout refresh the atom directly."
  []
  (let [auth (or (read-auth-file) {})]
    (reset! auth-atom auth)
    auth))

(defn get-credentials
  "The current auth map (auth.edn content, as loaded)."
  []
  @auth-atom)

(defn- update-auth!
  "Apply F to the current auth.edn content and persist the result under the
   file lock — read-modify-write is serialized, so concurrent writers can't
   lose each other's updates (pi: SettingsStorage.withLock)."
  [f]
  (let [path (auth-file-path)]
    (fs/create-dirs (fs/parent path))
    (file-lock/with-file-lock (str path ".lock")
      (fn []
        (let [updated (f (or (read-auth-file) {}))]
          (spit path (pretty-auth updated))
          updated)))))

(defn set-credential!
  "Store an API key for PROVIDER in auth.edn and refresh the auth atom.
   Returns the new auth map."
  [provider key]
  (let [auth (update-auth! #(assoc % provider {:key key}))]
    (reset! auth-atom auth)
    auth))

(defn stored-credential
  "The raw auth.edn entry for a provider — the api-key shape {:key ...} or
   the oauth shape {:type :oauth ...} — or nil."
  [provider]
  (get @auth-atom provider))

(defn stored-oauth-credential
  "The stored oauth credential map for a provider, or nil."
  [provider]
  (let [c (stored-credential provider)]
    (when (and (map? c) (= :oauth (:type c))) c)))

(defn set-oauth-credential!
  "Store an OAuth credential (map with :type :oauth, string :access/:refresh,
   finite number :expires — pi OAuthCredential) for PROVIDER in auth.edn and
   refresh the auth atom. Throws on an invalid shape. Returns the new auth
   map."
  [provider credential]
  (when-not (valid-credential? credential)
    (throw (ex-info (str "Invalid OAuth credential for " (name provider))
                    {:type :oauth-credential-invalid})))
  (let [auth (update-auth! #(assoc % provider credential))]
    (reset! auth-atom auth)
    auth))

(defn remove-credential!
  "Remove PROVIDER's auth.edn entry (no-op when absent) and refresh the auth
   atom. Returns the new auth map."
  [provider]
  (let [auth (update-auth! #(dissoc % provider))]
    (reset! auth-atom auth)
    auth))

;; ─── Resolution (pi: credential-store resolveApiKey) ──────────────────────

(defonce ^:private config-key-source (atom nil))

(defn set-config-key-source!
  "Register the provider → raw models.edn/extension :api-key config value
   source (set by models/load-catalogs!; nil returns no configured
   key). Kept behind a hook so auth stays dependency-free of the registry
   (no require cycle)."
  [f]
  (reset! config-key-source f))

(defn config-key-source-installed?
  "True when a config-key source is registered (models/load-catalogs! or
   extension registration)."
  []
  (some? @config-key-source))

(defn- configured-api-key
  "Raw models.edn/extension :api-key config value for a provider (nil when
   none configured)."
  [provider]
  (when-let [f @config-key-source]
    (f provider)))

;; ─── OAuth (Phase 10) ─────────────────────────────────────────────────────
;; The OAuthAuth record for a provider lives on the Provider record; auth
;; reads it through a hook (like config-key-source) to stay dependency-free
;; of the registry.

(defonce ^:private oauth-source (atom nil))

(defn set-oauth-source!
  "Register the provider → OAuthAuth record source (set by
   models/load-catalogs!; nil returns no oauth). Kept behind a hook so auth
   stays dependency-free of the registry."
  [f]
  (reset! oauth-source f))

(defn- provider-oauth
  "The OAuthAuth record for a provider, or nil."
  [provider]
  (when-let [f @oauth-source]
    (f provider)))

(def ^:private oauth-min-validity-ms
  "Refresh when the token has less than this much validity left (pi
   DEFAULT_OAUTH_MINIMUM_VALIDITY_MS = 5 min)."
  (* 5 60 1000))

(defn- oauth-fresh?
  "True when an oauth credential needs no refresh (pi expiresSoon: now +
   5 min < expires)."
  [credential]
  (and (number? (:expires credential))
       (< (+ (System/currentTimeMillis) oauth-min-validity-ms)
          (:expires credential))))

(defonce ^:private credential-op-tails (atom {}))

(defn run-credential-op!
  "Serialize credential ops per provider (pi ModelRuntime
   enqueueCredentialOperation): TASK runs after previously queued ops for
   PROVIDER-ID settle. Returns a future delivering TASK's result — deref
   rethrows on failure; the stored tail swallows errors so the chain
   survives a failed op. Used by the oauth refresh path (double-checked
   locking) and available to extensions for credential mutations."
  [provider-id task]
  (let [prev (get @credential-op-tails provider-id)
        op (future
             (when prev @prev)
             (task))]
    (swap! credential-op-tails assoc provider-id
           (future (try @op (catch Exception _ nil))))
    op))

(defn- resolve-oauth-auth
  "Resolved request auth from a stored oauth credential (pi
   resolveStoredOAuth): {:api-key access :base-url str?} without a refresh
   when the token is fresh; otherwise refresh under the per-provider lock
   (double-checked: another request may have rotated it meanwhile), persist
   the rotated credential, and derive auth from it. nil when the provider
   has no stored oauth credential or no registered OAuthAuth. A refresh
   failure resolves nil (the request reports the standard no-auth error; pi
   surfaces 'OAuth refresh failed' as a stream error)."
  [provider-id]
  (when-let [cred (stored-oauth-credential provider-id)]
    (when-let [oauth (provider-oauth provider-id)]
      (if (oauth-fresh? cred)
        ((:to-auth oauth) cred)
        (let [op (run-credential-op!
                  provider-id
                  (fn []
                    (let [current (stored-oauth-credential provider-id)]
                      (if (oauth-fresh? current)
                        ((:to-auth oauth) current)
                        (let [rotated ((:refresh oauth) current (atom false))]
                          (set-oauth-credential! provider-id rotated)
                          ((:to-auth oauth) rotated))))))]
          (try @op
               (catch Exception _ nil)))))))

(defn resolve-provider-auth
  "Resolved request auth for a provider (pi composeApiKeyAuth.resolve +
   resolveStoredOAuth + the anthropic provider resolve): {:api-key str} for
   the x-api-key paths, {:bearer str} when ANTHROPIC_AUTH_TOKEN wins
   (anthropic), or {:api-key str :base-url str} for an oauth credential (the
   base-url is the per-credential endpoint, e.g. Copilot's proxy-ep). Exact
   pi order: stored oauth credential (refreshed when expiring; a stored
   oauth credential without a registered OAuthAuth resolves nil and blocks
   the env fallback) → auth.edn api-key → models.edn/extension configured
   key (a configured key present but unresolvable blocks the rest, pi
   resolveConfigValueOrThrow) → ANTHROPIC_AUTH_TOKEN → oauth/api env vars
   (api-key-env-vars, AUTH_TOKEN skipped). nil when nothing is configured."
  [provider]
  (or (resolve-oauth-auth provider)
      (when-let [k (get-in @auth-atom [provider :key])]
        {:api-key k})
      ;; pi: a stored credential owns the provider — no env fallback when the
      ;; stored oauth has no registered auth handler
      (when-not (stored-oauth-credential provider)
        (let [raw (configured-api-key provider)]
          (if raw
            (when-let [k (config-value/resolve-config-value raw)]
              {:api-key k})
            (or (when-let [t (anthropic-auth-token provider)]
                  {:bearer t})
                (when-let [k (some getenv (api-key-env-vars provider))]
                  {:api-key k})))))))

(defn resolve-api-key
  "API key for a provider — the api-key view of resolve-provider-auth
   (nil when the anthropic AUTH_TOKEN bearer provides auth instead)."
  [provider]
  (:api-key (resolve-provider-auth provider)))

(defn env-key-present?
  "True when any of the provider's env vars is present in the process env
   (pi findEnvKeys — feeds get-provider-auth-status)."
  [provider]
  (boolean (some getenv (provider-env-vars provider))))

(defn configured?
  "True when the provider has a credential: auth.edn entry — an api-key
   always, an oauth credential only when the provider has a registered
   OAuthAuth (pi checkAuth: a stored oauth credential without auth.oauth is
   not configured; the stored credential blocks env discovery) — a
   configured models.edn/extension api-key (literal or !command always;
   $ENV needs the var present), or any env var present (feeds
   models/get-available). Never refreshes or executes commands."
  [provider]
  (let [stored (stored-credential provider)]
    (boolean
     (cond
       (stored-oauth-credential provider) (some? (provider-oauth provider))
       stored true
       :else (or (when-let [raw (configured-api-key provider)]
                   (config-value/is-config-value-configured? raw))
                 (env-key-present? provider))))))
