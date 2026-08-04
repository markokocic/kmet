(ns kmet.app.keybindings
  "App-level keybindings — extends TUI keybindings with agent-specific actions.
   Port of @earendil-works/pi-coding-agent KeybindingsManager.
   Keybinding IDs like \"app.tools.expand\" are used by key-hint in tool renderers.
   User overrides persist in ~/.kmet/agent/keybindings.edn (pi:
   ~/.pi/agent/keybindings.json) and are loaded by create-agent-keybindings-manager."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [babashka.fs :as fs]
            [kmet.config :as cfg]
            [kmet.tui.keybindings :as kb]))

;; ─── App keybinding definitions ──────────────────────────────────────────
;; Extends the TUI definitions with agent-specific actions.

(def app-keybinding-defs
  {"app.interrupt"              {:default-keys ["escape"]                 :description "Cancel or abort"}
   "app.clear"                  {:default-keys ["ctrl+c"]                 :description "Clear editor"}
   "app.exit"                   {:default-keys ["ctrl+d"]                 :description "Exit when editor is empty"}
   "app.suspend"                {:default-keys ["ctrl+z"]                 :description "Suspend to background"}
   "app.thinking.cycle"         {:default-keys ["shift+tab"]              :description "Cycle thinking level"}
   "app.model.cycleForward"     {:default-keys ["ctrl+p"]                 :description "Cycle to next model"}
   "app.model.cycleBackward"    {:default-keys ["shift+ctrl+p"]           :description "Cycle to previous model"}
   "app.model.select"           {:default-keys ["ctrl+l"]                 :description "Open model selector"}
   "app.tools.expand"           {:default-keys ["ctrl+o"]                 :description "Toggle tool output"}
   "app.thinking.toggle"        {:default-keys ["ctrl+t"]                 :description "Toggle thinking blocks"}
   "app.message.copy"           {:default-keys ["ctrl+x"]                 :description "Copy message to clipboard"}
   "app.message.followUp"       {:default-keys ["alt+enter"]              :description "Queue follow-up message"}
   "app.message.dequeue"        {:default-keys ["alt+up"]                 :description "Restore queued messages"}
   "app.editor.external"        {:default-keys ["ctrl+g"]                 :description "Open external editor"}})

;; ─── Combined definitions (TUI + App) ─────────────────────────────────────

(def all-keybinding-defs
  "Union of TUI keybinding definitions and app keybinding definitions."
  (merge kb/tui-keybinding-defs app-keybinding-defs))

;; ─── KeybindingsManager for the agent app ─────────────────────────────────

(defn make-agent-keybindings-manager
  "Create a KeybindingsManager with both TUI and app keybindings.
   Optionally accepts user overrides map."
  ([]
   (make-agent-keybindings-manager {}))
  ([user-bindings]
   (kb/make-keybindings-manager all-keybinding-defs user-bindings)))

;; ─── Persisted keybindings file (pi: KeybindingsManager.create/reload) ──
;; User overrides live in keybindings.edn next to the settings file; defaults
;; stay in code. Values are keybinding ids → key string or vector of strings.

(defn keybindings-config-path
  "Path of the user keybindings file (pi: join(agentDir, 'keybindings.json'))."
  ([]
   (keybindings-config-path (cfg/get-agent-dir)))
  ([agent-dir]
   (str (fs/path agent-dir "keybindings.edn"))))

(def ^:private legacy-keybinding-migrations
  "Legacy camelCase keybinding ids → namespaced ids (pi: KEYBINDING_NAME_MIGRATIONS)."
  {"cursorUp" "tui.editor.cursorUp"
   "cursorDown" "tui.editor.cursorDown"
   "cursorLeft" "tui.editor.cursorLeft"
   "cursorRight" "tui.editor.cursorRight"
   "cursorWordLeft" "tui.editor.cursorWordLeft"
   "cursorWordRight" "tui.editor.cursorWordRight"
   "cursorLineStart" "tui.editor.cursorLineStart"
   "cursorLineEnd" "tui.editor.cursorLineEnd"
   "jumpForward" "tui.editor.jumpForward"
   "jumpBackward" "tui.editor.jumpBackward"
   "pageUp" "tui.editor.pageUp"
   "pageDown" "tui.editor.pageDown"
   "deleteCharBackward" "tui.editor.deleteCharBackward"
   "deleteCharForward" "tui.editor.deleteCharForward"
   "deleteWordBackward" "tui.editor.deleteWordBackward"
   "deleteWordForward" "tui.editor.deleteWordForward"
   "deleteToLineStart" "tui.editor.deleteToLineStart"
   "deleteToLineEnd" "tui.editor.deleteToLineEnd"
   "yank" "tui.editor.yank"
   "yankPop" "tui.editor.yankPop"
   "undo" "tui.editor.undo"
   "newLine" "tui.input.newLine"
   "submit" "tui.input.submit"
   "tab" "tui.input.tab"
   "copy" "tui.input.copy"
   "selectUp" "tui.select.up"
   "selectDown" "tui.select.down"
   "selectPageUp" "tui.select.pageUp"
   "selectPageDown" "tui.select.pageDown"
   "selectConfirm" "tui.select.confirm"
   "selectCancel" "tui.select.cancel"
   "interrupt" "app.interrupt"
   "clear" "app.clear"
   "exit" "app.exit"
   "suspend" "app.suspend"
   "cycleThinkingLevel" "app.thinking.cycle"
   "cycleModelForward" "app.model.cycleForward"
   "cycleModelBackward" "app.model.cycleBackward"
   "selectModel" "app.model.select"
   "expandTools" "app.tools.expand"
   "toggleThinking" "app.thinking.toggle"
   "toggleSessionNamedFilter" "app.session.toggleNamedFilter"
   "externalEditor" "app.editor.external"
   "followUp" "app.message.followUp"
   "dequeue" "app.message.dequeue"
   "pasteImage" "app.clipboard.pasteImage"
   "newSession" "app.session.new"
   "tree" "app.session.tree"
   "fork" "app.session.fork"
   "resume" "app.session.resume"
   "treeFoldOrUp" "app.tree.foldOrUp"
   "treeUnfoldOrDown" "app.tree.unfoldOrDown"
   "treeEditLabel" "app.tree.editLabel"
   "treeToggleLabelTimestamp" "app.tree.toggleLabelTimestamp"
   "toggleSessionPath" "app.session.togglePath"
   "toggleSessionSort" "app.session.toggleSort"
   "renameSession" "app.session.rename"
   "deleteSession" "app.session.delete"
   "deleteSessionNoninvasive" "app.session.deleteNoninvasive"})

(defn- migrate-keybindings-config
  "pi: migrateKeybindingsConfig — returns [config migrated?]: legacy ids are
   rewritten to namespaced ones; when both the legacy and the namespaced key
   are present, the legacy one is dropped (namespaced wins)."
  [raw-config]
  (reduce-kv (fn [[acc migrated?] key value]
               (let [next-key (get legacy-keybinding-migrations key key)]
                 (if (and (not= key next-key) (contains? raw-config next-key))
                   [acc true]
                   [(assoc acc next-key value)
                    (or migrated? (not= key next-key))])))
             [{} false]
             raw-config))

(defn- to-keybindings-config
  "pi: toKeybindingsConfig — keep only string and vector-of-strings values."
  [raw-config]
  (into {}
        (keep (fn [[key value]]
                (cond
                  (string? value) [key value]
                  (and (vector? value) (every? string? value)) [key value]
                  :else nil)))
        raw-config))

(defn load-user-bindings
  "Load persisted keybinding overrides (pi: KeybindingsManager.loadFromFile).
   Reads keybindings.edn from the agent dir, migrates legacy ids, and keeps
   only string/vector-of-strings values. Returns {} when the file is missing
   or invalid (a warning is printed for malformed files)."
  ([]
   (load-user-bindings (keybindings-config-path)))
  ([path]
   (let [f (io/file (cfg/expand-path path))]
     (if-not (fs/exists? f)
       {}
       (try
         (-> (edn/read-string (slurp f))
             (migrate-keybindings-config)
             first
             to-keybindings-config)
         (catch Exception e
           (binding [*out* *err*]
             (println "Warning: Failed to load keybindings" path ":" (ex-message e)))
           {}))))))

(defn migrate-keybindings-config-file!
  "Pi: migrateKeybindingsConfigFile — rewrite legacy keybinding ids in
   keybindings.edn to namespaced ids. No-op when the file is missing or needs
   no migration. Called at startup (pi: migrations runner)."
  ([]
   (migrate-keybindings-config-file! (keybindings-config-path)))
  ([path]
   (let [f (io/file (cfg/expand-path path))]
     (when (fs/exists? f)
       (try
         (let [raw (edn/read-string (slurp f))
               [config migrated?] (migrate-keybindings-config raw)]
           (when migrated?
             (spit f (str (pr-str config) "\n"))))
         (catch Exception _ nil))))))

(defn create-agent-keybindings-manager
  "Pi: KeybindingsManager.create(agentDir) — build the agent KeybindingsManager
   with persisted user overrides from <agent-dir>/keybindings.edn."
  ([]
   (create-agent-keybindings-manager (cfg/get-agent-dir)))
  ([agent-dir]
   (make-agent-keybindings-manager (load-user-bindings (keybindings-config-path agent-dir)))))

(defn reload-agent-keybindings!
  "Pi: KeybindingsManager.reload — re-read persisted user overrides from
   <agent-dir>/keybindings.edn into the global KeybindingsManager (for /reload)."
  ([]
   (reload-agent-keybindings! (cfg/get-agent-dir)))
  ([agent-dir]
   (kb/set-user-bindings! (kb/get-global-keybindings)
                          (load-user-bindings (keybindings-config-path agent-dir)))))

;; ─── Convenience: key-hint with theme fns ───────────────────────────────

(declare key-hint)

;; This will be set by core.clj once the theme is available.
(defonce ^:private theme-fns-atom (atom nil))

(defn set-key-hint-theme-fns!
  "Set the dim/muted styling functions for key-hint rendering.
   Called during app initialization with theme/dim and theme/fg :muted."
  [dim-fn muted-fn]
  (reset! theme-fns-atom {:dim dim-fn :muted muted-fn}))

(defn key-text
  "Pi: keyText — all resolved key chords for an id joined with '/', or \"\"."
  [id]
  (or (kb/key-text (kb/get-global-keybindings) id) ""))

(defn key-hint
  "Convenience wrapper: renders a keybinding hint using the app's theme.
   kmgr — KeybindingsManager (or nil for global)
   id   — keybinding ID (e.g. \"app.tools.expand\")
   desc — description text"
  ([id desc]
   (let [{:keys [dim muted]} @theme-fns-atom]
     (if (and dim muted)
       (kb/key-hint (kb/get-global-keybindings) id desc dim muted)
       ;; Fallback: plain text
       (str (key-text id) " " desc))))
  ([kmgr id desc]
   (let [{:keys [dim muted]} @theme-fns-atom]
     (if (and dim muted)
       (kb/key-hint kmgr id desc dim muted)
       (str (str/join "/" (kb/get-keys kmgr id)) " " desc)))))
