(ns kmet.config
  "Configuration loading for kmet.
   Loads settings from ~/.config/kmet/settings.edn and .kmet/settings.edn
   (project-local overrides)."
  (:require [clojure.java.io :as io]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [babashka.fs :as fs]
            [kmet.tui.theme :as theme]))

;; ─── Defaults ───────────────────────────────────────────────────────────────

(def default-config
  {:provider :opencode-go
   :model "deepseek-v4-flash"
   :theme "dark"
   :session-dir "~/.local/share/kmet/sessions"
   :max-session-entries 500
   :compact-threshold 400
   :models []
   :system-prompt nil
   :thinking :off
   :extensions-dir "~/.config/kmet/extensions"
   :skills-dir "~/.config/kmet/skills"
   :themes-dir "~/.config/kmet/themes"
   :providers {:openai {:model "gpt-4o"}
               :anthropic {:model "claude-sonnet-4-20250514"}
               :opencode-go {:model "deepseek-v4-flash"
                             :base-url "https://opencode.ai/zen/go/v1/chat/completions"
                             :api-type :openai}}})

;; ─── Path expansion ────────────────────────────────────────────────────────

(defn expand-path [path]
  (let [s (str path)]
    (if (str/starts-with? s "~")
      (str (System/getProperty "user.home") (subs s 1))
      s)))

;; ─── Deep merge & scope-relative paths ─────────────────────────────────────

(def ^:private path-keys
  "Config keys whose values are filesystem paths. Resolved relative to their
   scope dir (pi: paths in ~/.pi/agent/settings.json resolve relative to
   ~/.pi/agent; in .pi/settings.json relative to .pi)."
  #{:session-dir :extensions-dir :skills-dir :themes-dir})

(defn deep-merge
  "Recursively merge maps: nested maps merge key-by-key, non-map values from
   later maps win. Vectors/lists are replaced, not merged (pi: 'Nested
   objects are merged' — only objects merge)."
  [& maps]
  (reduce (fn [acc m]
            (if (map? m)
              (merge-with (fn [a b] (if (and (map? a) (map? b)) (deep-merge a b) b)) acc m)
              acc))
          {} maps))

(defn- resolve-path
  "Resolve a path value relative to its scope dir. ~ and absolute paths pass
   through unchanged."
  [base-dir path]
  (let [s (str path)]
    (cond
      (str/starts-with? s "~") (expand-path s)
      (fs/absolute? s) s
      :else (str (fs/path base-dir s)))))

(defn- resolve-scope-paths
  "Resolve path values in a config map relative to base-dir; other values
   pass through. nil config → {}."
  [config base-dir]
  (reduce-kv (fn [acc k v]
               (assoc acc k (if (and (contains? path-keys k) (string? v))
                              (resolve-path base-dir v)
                              v)))
             {}
             (or config {})))

;; ─── Config loading ────────────────────────────────────────────────────────

(defn- load-edn-file
  "Load an EDN file, returning nil if it doesn't exist or is invalid."
  [path]
  (let [f (io/file (expand-path path))]
    (when (fs/exists? f)
      (try
        (edn/read-string (slurp f))
        (catch Exception e
          (binding [*out* *err*]
            (println "Warning: Failed to load" path ":" (ex-message e)))
          nil)))))

;; ─── Auth ───────────────────────────────────────────────────────────────────

(def default-auth {})

(defonce ^:private auth-atom (atom default-auth))

(defn load-auth
  "Load auth from ~/.config/kmet/auth.edn.
   Returns the auth map, merging with defaults."
  []
  (let [auth (or (load-edn-file "~/.config/kmet/auth.edn") {})]
    (reset! auth-atom auth)
    auth))

(defn get-api-key
  "Look up API key for a provider.
   Checks auth.edn first, then falls back to environment variable."
  [provider]
  (or (get-in @auth-atom [provider :key])
      (case provider
        :openai (System/getenv "OPENAI_API_KEY")
        :anthropic (System/getenv "ANTHROPIC_API_KEY")
        :opencode-go (System/getenv "KMET_OPENCODE_GO_KEY")
        nil)))

;; ─── Provider config ───────────────────────────────────────────────────────

(def provider-configs
  {:openai {:base-url "https://api.openai.com/v1/chat/completions"
            :api-type :openai}
   :anthropic {:base-url "https://api.anthropic.com/v1/messages"
               :api-type :anthropic}
   :opencode-go {:base-url "https://opencode.ai/zen/go/v1/chat/completions"
                 :api-type :openai}})

(defn get-provider-config
  "Get provider configuration map (base-url, api-type).
   Checks provider-configs first, then falls back to :providers in default-config."
  [provider]
  (or (get provider-configs provider)
      (get-in default-config [:providers provider])
      {:base-url nil :api-type provider}))

;; ─── Config loading continued ──────────────────────────────────────────────

(defn load-config
  "Load and merge configuration from user and project directories.
   Path values are resolved per scope before merging (global paths relative
   to ~/.config/kmet, project paths relative to .kmet), then deep-merged:
   defaults < user < project, with nested maps merged key-by-key (pi: project
   settings override global, nested objects merge).
   Returns merged map."
  [& {:keys [no-env?]}]
  (let [user-config (load-edn-file "~/.config/kmet/settings.edn")
        project-config (load-edn-file ".kmet/settings.edn")
        _ (load-auth)
        global-dir (expand-path "~/.config/kmet")
        project-dir (str (fs/absolutize ".kmet"))
        env-provider (when-not no-env?
                       (or (some-> (System/getenv "KMET_PROVIDER") keyword)
                           (when (System/getenv "OPENAI_API_KEY") :openai)
                           (when (System/getenv "ANTHROPIC_API_KEY") :anthropic)
                           (when (get-api-key :opencode-go) :opencode-go)))
        env-model (System/getenv "KMET_MODEL")
        base (deep-merge (resolve-scope-paths default-config global-dir)
                         (resolve-scope-paths user-config global-dir)
                         (resolve-scope-paths project-config project-dir))
        with-env (cond-> base
                   env-provider (assoc :provider env-provider)
                   env-model (assoc :model env-model))]
    with-env))

;; ─── Config accessors ──────────────────────────────────────────────────────

(defn get-provider [config]
  (:provider config))

(defn get-model [config]
  (or (:model config)
      (get-in config [:providers (get-provider config) :model])))

(defn get-session-dir [config]
  (expand-path (:session-dir config)))

(defn get-theme-name [config]
  (:theme config "dark"))

(defn get-theme [config]
  (theme/get-theme (get-theme-name config)))

(defn get-provider-base-url
  "Get the API base URL for a given provider."
  [provider]
  (:base-url (get-provider-config provider)))

(defn get-provider-api-type
  "Get the API type (:openai or :anthropic) for a given provider."
  [provider]
  (:api-type (get-provider-config provider)))



;; ─── Initialization ─────────────────────────────────────────────────────────

(defn init!
  "Load config and themes. Returns the loaded config map.
   Call once at startup."
  []
  (let [config (load-config)
        themes-dir (expand-path (:themes-dir config))]
    (fs/create-dirs (get-session-dir config))
    (theme/load-themes-from-dir themes-dir)
    config))
