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
  {:provider :openai
   :model nil
   :theme "dark"
   :session-dir "~/.local/share/kmet/sessions"
   :max-session-entries 500
   :compact-threshold 400
   :system-prompt nil
   :thinking :off
   :extensions-dir "~/.config/kmet/extensions"
   :skills-dir "~/.config/kmet/skills"
   :themes-dir "~/.config/kmet/themes"
   :providers {:openai {:model "gpt-4o"}
               :anthropic {:model "claude-sonnet-4-20250514"}}})

;; ─── Path expansion ────────────────────────────────────────────────────────

(defn expand-path [path]
  (let [s (str path)]
    (if (str/starts-with? s "~")
      (str (System/getProperty "user.home") (subs s 1))
      s)))

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
            (println "Warning: Failed to load" path ":" (.getMessage e)))
          nil)))))

(defn load-config
  "Load and merge configuration from user and project directories.
   Returns merged map with defaults for any missing keys."
  [& {:keys [no-env?]}]
  (let [user-config (load-edn-file "~/.config/kmet/settings.edn")
        project-config (load-edn-file ".kmet/settings.edn")
        env-provider (when-not no-env?
                       (or (some-> (System/getenv "KMET_PROVIDER") keyword)
                           (when (System/getenv "OPENAI_API_KEY") :openai)
                           (when (System/getenv "ANTHROPIC_API_KEY") :anthropic)))
        env-model (System/getenv "KMET_MODEL")
        base (merge default-config user-config project-config)
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
