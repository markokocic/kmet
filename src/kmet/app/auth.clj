(ns kmet.app.auth
  "Provider credential resolution (pi: packages/ai credential-store +
   env-api-keys.ts, adapted to EDN).

   auth.edn shape: {provider {:key \"...\"}} — kmet-internal (pi's auth.json
   shape not required for parity). Loaded at startup (config/load-config);
   /login and /logout write it under a file lock."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [babashka.fs :as fs]
            [kmet.libs.file-lock :as file-lock]))

;; ─── Env var table (pi env-api-keys.ts) ────────────────────────────────────

(def ^:private getenv
  "Env lookup — indirected so tests can redef it without touching the real
   environment (babashka cannot set env vars)."
  (fn [k] (System/getenv k)))

(def env-vars-by-provider
  "Provider → env var names, in pi's order (pi: env-api-keys.ts).
   :anthropic keeps only ANTHROPIC_API_KEY — the auth-token variants are
   deferred (later phases); :google is reserved for a later provider set."
  {:opencode-go ["OPENCODE_API_KEY"]
   :opencode ["OPENCODE_API_KEY"]
   :deepseek ["DEEPSEEK_API_KEY"]
   :github-copilot ["COPILOT_GITHUB_TOKEN"]
   :openai ["OPENAI_API_KEY"]
   :anthropic ["ANTHROPIC_API_KEY"]
   :google ["GEMINI_API_KEY"]})

(defn provider-env-vars
  "Env var names for a provider, in pi's order (empty vector when unknown)."
  [provider]
  (get env-vars-by-provider provider []))

;; ─── auth.edn state ────────────────────────────────────────────────────────

(def ^:private default-auth {})

(defonce ^:private auth-atom (atom default-auth))

(defn auth-file-path
  "Global auth file (~/.kmet/agent/auth.edn), home-expanded."
  []
  (str (fs/path (System/getProperty "user.home") ".kmet" "agent" "auth.edn")))

(defn- read-auth-file
  "Parse auth.edn as a map, nil when missing, malformed, or not a map
   (non-map content is replaced on the next write — pi: SettingsStorage
   parses to a map or starts fresh)."
  []
  (let [f (fs/file (auth-file-path))]
    (when (fs/exists? f)
      (try (let [parsed (edn/read-string (slurp f))]
             (when (map? parsed) parsed))
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

(defn remove-credential!
  "Remove PROVIDER's auth.edn entry (no-op when absent) and refresh the auth
   atom. Returns the new auth map."
  [provider]
  (let [auth (update-auth! #(dissoc % provider))]
    (reset! auth-atom auth)
    auth))

;; ─── Resolution (pi: credential-store resolveApiKey) ──────────────────────

(defn resolve-api-key
  "API key for a provider: auth.edn credential first, then env vars in pi
   order (pi: auth.json before env; the x-api-key path skips
   ANTHROPIC_AUTH_TOKEN — deferred here since kmet only knows
   ANTHROPIC_API_KEY)."
  [provider]
  (or (get-in @auth-atom [provider :key])
      (some getenv (provider-env-vars provider))))

(defn configured?
  "True when the provider has a credential: auth.edn entry or any env var
   present (feeds models/get-available)."
  [provider]
  (boolean (or (get-in @auth-atom [provider :key])
               (some getenv (provider-env-vars provider)))))
