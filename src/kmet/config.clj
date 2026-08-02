(ns kmet.config
  "Configuration loading for kmet.
   Loads settings from ~/.kmet/agent/settings.edn and .kmet/settings.edn
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
   :session-dir "~/.kmet/sessions"
   :max-session-entries 500
   :compact-threshold 400
   :compact-token-threshold nil
   :keep-recent-tokens 20000
   :models []
   :system-prompt nil
   :append-system-prompt nil
   :thinking :off
   :extensions-dir "~/.kmet/agent/extensions"
   :skills-dir "~/.kmet/agent/skills"
   :prompts-dir "~/.kmet/agent/prompts"
   :themes-dir "~/.kmet/agent/themes"
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
  #{:session-dir :extensions-dir :skills-dir :prompts-dir :themes-dir})

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
  "Load auth from ~/.kmet/agent/auth.edn.
   Returns the auth map, merging with defaults."
  []
  (let [auth (or (load-edn-file "~/.kmet/agent/auth.edn") {})]
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
   to ~/.kmet/agent, project paths relative to .kmet), then deep-merged:
   defaults < user < project, with nested maps merged key-by-key (pi: project
   settings override global, nested objects merge).
   Returns merged map."
  [& {:keys [no-env?]}]
  (let [user-config (load-edn-file "~/.kmet/agent/settings.edn")
        project-config (load-edn-file ".kmet/settings.edn")
        _ (load-auth)
        global-dir (expand-path "~/.kmet/agent")
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

(defn get-agent-dir
  "The global agent directory (~/.kmet/agent), home-expanded."
  []
  (expand-path "~/.kmet/agent"))

;; ─── System prompt sources (pi: resolvePromptInput + discoverSystemPromptFile) ──

(defn- resolve-prompt-input
  "pi: resolvePromptInput — a value naming an existing file is read as
   content; anything else is used inline."
  [input]
  (when (seq input)
    (let [p (str input)]
      (if (fs/exists? p)
        (try (slurp p) (catch Exception _ p))
        p))))

(defn- prompt-file-candidates
  "Absolute candidate paths for a prompt file: project (.kmet) first, then
   global (~/.kmet/agent) — pi checks the project before the agent dir."
  [filename]
  [(str (fs/path (fs/cwd) ".kmet" filename))
   (str (fs/path (expand-path "~/.kmet/agent") filename))])

(defn- discover-prompt-file
  "First existing candidate for a prompt file, or nil."
  [filename]
  (some #(when (fs/exists? %) %) (prompt-file-candidates filename)))

(defn get-custom-prompt
  "Custom system prompt source (pi: systemPromptSource ??
   discoverSystemPromptFile, then resolvePromptInput): the :system-prompt
   config value (read as a file when it names an existing one), else
   .kmet/SYSTEM.md, else ~/.kmet/agent/SYSTEM.md.
   Deviations from pi: no project-trust gate."
  [config]
  (resolve-prompt-input (or (:system-prompt config)
                            (discover-prompt-file "SYSTEM.md"))))

(defn get-append-system-prompt
  "Append system prompt source (pi: appendSystemPromptSource ??
   discoverAppendSystemPromptFile): the :append-system-prompt config value
   (read as a file when it names an existing one), else .kmet/APPEND_SYSTEM.md,
   else ~/.kmet/agent/APPEND_SYSTEM.md.
   Deviations from pi: no project-trust gate."
  [config]
  (resolve-prompt-input (or (:append-system-prompt config)
                            (discover-prompt-file "APPEND_SYSTEM.md"))))

(defn apply-cli-overrides
  "Apply CLI opts onto a config map (pi: CLI flags override settings).
   Handles :model, :provider, :thinking, :system-prompt (last wins), and
   :append-system-prompt (repeatable, joined with newlines like pi)."
  [config opts]
  (cond-> config
    (:model opts) (assoc :model (:model opts))
    (:provider opts) (assoc :provider (:provider opts))
    (:thinking opts) (assoc :thinking (:thinking opts))
    (:system-prompt opts) (assoc :system-prompt (:system-prompt opts))
    (:append-system-prompt opts) (assoc :append-system-prompt
                                        (str/join "\n\n" (:append-system-prompt opts)))))

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

(defn resource-dirs
  "All directories to load for a resource type (pi: global + project +
   explicit paths load simultaneously): the global default, the project-local
   default (project-rel, resolved against cwd), and the merged config value
   (an explicit override), deduped by canonical path. Order = pi load order
   (global first), so global wins name collisions."
  [config resource-key project-rel]
  (->> [(get default-config resource-key)
        project-rel
        (get config resource-key)]
       (map expand-path)
       (map #(str (fs/canonicalize (io/file %))))
       distinct
       (mapv str)))



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
