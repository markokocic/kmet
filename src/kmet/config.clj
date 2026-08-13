(ns kmet.config
  "Configuration loading for kmet.
   Loads settings from ~/.kmet/agent/settings.edn and .kmet/settings.edn
   (project-local overrides)."
  (:require [clojure.java.io :as io]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [babashka.fs :as fs]
            [kmet.tui.theme :as theme]
            [kmet.app.auth :as auth]
            [kmet.libs.file-lock :as file-lock]))

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
   :retry {:enabled true :max-retries 3 :base-delay-ms 2000}
   :models []
   :http-idle-timeout-ms 300000
   :system-prompt nil
   :append-system-prompt nil
   :thinking :off
   :extensions-dir "~/.kmet/agent/extensions"
   :skills-dir "~/.kmet/agent/skills"
   :prompts-dir "~/.kmet/agent/prompts"
   :themes-dir "~/.kmet/agent/themes"})

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

;; ─── Auth (Phase 3: kmet.app.auth owns auth.edn + the env-var table) ──────

(defn get-api-key
  "API key for a provider (pi: getApiKey) — auth.edn credential first, then
   the provider's env vars in pi order. Delegates to kmet.app.auth."
  [provider]
  (auth/resolve-api-key provider))

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
        _ (auth/load-auth!)
        global-dir (expand-path "~/.kmet/agent")
        project-dir (str (fs/absolutize ".kmet"))
        env-provider (when-not no-env?
                       (or (some-> (System/getenv "KMET_PROVIDER") keyword)
                           (when (auth/resolve-api-key :opencode-go) :opencode-go)))
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
  (:model config))

(defn get-session-dir [config]
  (expand-path (:session-dir config)))

(defn get-agent-dir
  "The global agent directory (~/.kmet/agent), home-expanded."
  []
  (expand-path "~/.kmet/agent"))

;; ─── Settings persistence (pi: SettingsManager setters) ───────────────────
;; The config module reads settings.edn at load; these fns persist individual
;; fields back to the global file (pi: setHideThinkingBlock etc. write to
;; ~/.pi/agent/settings.json via SettingsManager.save).

(defn global-settings-path
  "Path of the global settings file (~/.kmet/agent/settings.edn)."
  []
  (expand-path "~/.kmet/agent/settings.edn"))

(defn- pretty-edn
  "Format a settings map as EDN with each top-level entry on its own line and
   the closing brace on its own line (pi: JSON.stringify(settings, null, 2))."
  [m]
  (str "{" (str/join "\n " (for [[k v] m] (str (pr-str k) " " (pr-str v)))) "\n}\n"))

(defn- safe-parse-settings
  "Parse settings text as a map, nil when malformed or not a map."
  [text]
  (when-let [parsed (try (edn/read-string text) (catch Exception _ nil))]
    (when (map? parsed) parsed)))

(defn- with-settings-lock
  "Run F with the settings lock held (pi: SettingsStorage.withLock)."
  [f]
  (file-lock/with-file-lock (str (global-settings-path) ".lock") f))

(defn- setting-line
  "EDN text for one pretty settings entry, e.g. \" :hide-thinking-block true\"."
  [key value]
  (str " " (pr-str key) " " (pr-str value)))

(defn- top-level-key-line?
  "True when LINE is a top-level KEY entry of an EDN settings map (key at
   line start, possibly indented)."
  [key line]
  (boolean (re-matches (re-pattern (str "^\\s*" (java.util.regex.Pattern/quote (pr-str key)) "\\s+.*"))
                       line)))

(defn- update-setting-text
  "Return settings text with the top-level KEY entry set to VALUE, preserving
   unrelated lines (hand-written comments): the key's line is replaced when
   present, otherwise a new line is inserted with the closing brace on its own
   line (canonical pretty format), so later updates stay in-place."
  [text key value]
  (let [line (setting-line key value)
        lines (str/split-lines text)
        idx (first (keep-indexed (fn [i l] (when (top-level-key-line? key l) i)) lines))
        trailing-nl? (str/ends-with? text "\n")]
    (if idx
      (str (str/join "\n" (assoc (vec lines) idx line))
           (when trailing-nl? "\n"))
      (when-let [i (str/last-index-of text "}")]
        (if (= \newline (get text (dec i)))
          (str (subs text 0 (dec i)) "\n" line "\n}" (subs text (inc i)))
          (str (subs text 0 i) "\n" line "\n}" (subs text (inc i))))))))

(defn save-setting!
  "Persist a setting to the global settings.edn (pi: SettingsManager.save —
   only the given field is merged into the current file content, nested keys
   merged leaf-wise). PATH is a key path (e.g. [:hide-thinking-block]); only
   that leaf is merged, so unrelated keys survive. Hand-written comments are
   preserved via line surgery; the file is rewritten in a canonical pretty
   format when surgery is unsafe (malformed or one-line files, nested paths)."
  [path value]
  (let [path (if (vector? path) path [path])
        file (io/file (global-settings-path))]
    (fs/create-dirs (fs/parent (global-settings-path)))
    (with-settings-lock
      (fn []
        (if-not (fs/exists? file)
          (spit (global-settings-path) (pretty-edn (assoc-in {} path value)))
          (let [text (slurp (global-settings-path))
                base (or (safe-parse-settings text) {})
                edited (when (= 1 (count path))
                         (update-setting-text text (peek path) value))
                edited-ok? (and edited
                                (let [parsed (safe-parse-settings edited)]
                                  (and parsed
                                       (= parsed (assoc-in base path value)))))]
            (if edited-ok?
              (spit (global-settings-path) edited)
              (spit (global-settings-path)
                    (pretty-edn (assoc-in base path value))))))))))

(defn get-hide-thinking-block
  "Pi: getHideThinkingBlock — whether thinking blocks are hidden by default."
  [config]
  (boolean (:hide-thinking-block config)))

(defn set-hide-thinking-block!
  "Pi: setHideThinkingBlock — persist the thinking-block hidden flag to the
   global settings file (applied by Ctrl+T / app.thinking.toggle)."
  [hidden?]
  (save-setting! [:hide-thinking-block] (boolean hidden?)))

(defn get-enabled-models
  "Enabled model patterns for Ctrl+P cycling (pi: settingsManager
   enabledModels — same format as the --models flag). nil = all enabled."
  [config]
  (:enabled-models config))

(defn- read-global-settings
  "The parsed global settings map, or nil when the file is missing,
   unreadable, or not a map."
  []
  (let [file (io/file (global-settings-path))]
    (when (fs/exists? file)
      (try (let [parsed (edn/read-string (slurp file))]
             (when (map? parsed) parsed))
           (catch Exception _ nil)))))

(defn get-enabled-models-live
  "Live :enabled-models patterns from the global settings file (pi: the
   SettingsManager holds mutable settings — kmet's in-memory config is a
   startup snapshot, so /scoped-models re-reads after a Ctrl+S persist).
   Falls back to the CONFIG value when the file is missing or unreadable."
  [config]
  (if-let [settings (read-global-settings)]
    (:enabled-models settings)
    (:enabled-models config)))

(defn set-enabled-models!
  "Persist the enabled-model patterns to the global settings file (pi:
   settingsManager.setEnabledModels). nil removes the filter — all models
   enabled."
  [patterns]
  (save-setting! [:enabled-models] patterns))

(defn get-retry-settings
  "Retry settings (pi: settings-manager retry block — enabled, maxRetries,
   baseDelayMs). Returns {:enabled bool :max-retries n :base-delay-ms n};
   the deep-merged config may carry a partial :retry map."
  [config]
  (let [retry (:retry config)]
    {:enabled (if (contains? retry :enabled) (:enabled retry) true)
     :max-retries (or (:max-retries retry) 3)
     :base-delay-ms (or (:base-delay-ms retry) 2000)}))

(defn get-retry-settings-live
  "Live :retry settings from the global settings file (the in-memory config
   is a startup snapshot — /settings re-reads after a same-session change,
   like get-enabled-models-live). Falls back to the CONFIG value when the
   file is missing or unreadable."
  [config]
  (get-retry-settings (if-let [settings (read-global-settings)]
                        (assoc config :retry (:retry settings))
                        config)))

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
   Handles :model, :provider, :thinking, :models (scoped list), :system-prompt
   (last wins), and :append-system-prompt (repeatable, joined with newlines
   like pi)."
  [config opts]
  (cond-> config
    (:model opts) (assoc :model (:model opts))
    (:provider opts) (assoc :provider (:provider opts))
    (:thinking opts) (assoc :thinking (:thinking opts))
    (:models opts) (assoc :models (:models opts))
    (:system-prompt opts) (assoc :system-prompt (:system-prompt opts))
    (:append-system-prompt opts) (assoc :append-system-prompt
                                        (str/join "\n\n" (:append-system-prompt opts)))))

(defn get-theme-name [config]
  (:theme config "dark"))

(defn get-theme [config]
  (theme/get-theme (get-theme-name config)))

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
