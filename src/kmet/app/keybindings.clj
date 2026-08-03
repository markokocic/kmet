(ns kmet.app.keybindings
  "App-level keybindings — extends TUI keybindings with agent-specific actions.
   Port of @earendil-works/pi-coding-agent KeybindingsManager.
   Keybinding IDs like \"app.tools.expand\" are used by key-hint in tool renderers."
  (:require [clojure.string :as str]
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
