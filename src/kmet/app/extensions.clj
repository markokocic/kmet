(ns kmet.app.extensions
  "Extensions system for kmet.
   Clojure files loaded from extension directories.

   Extension hooks (pi: pi.on('input') and pi.on('before_agent_start')):
   - input hooks intercept/rewrite user input before the agent runs
     (applied at the interactive input path, core.clj/modes.interactive
     handle-submit)
   - before-agent-start hooks override the system prompt / inject context
     messages for a run (applied by kmet.app.loop/run-agent-turn)

   Extension events flow through kmet.app.event-bus (pi: pi.on('<event>')).
   pi: core/extensions/runner.js."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [babashka.fs :as fs]))

;; ─── Extension input / before-agent-start hooks ────────────────────────────
;; pi: extensions register via pi.on("input") and pi.on("before_agent_start");
;; AgentSession.prompt() consults them per submission. kmet: the interactive
;; input path (modes.interactive handle-submit) applies input hooks before the
;; agent runs; run-agent-turn applies before-agent-start hooks after the user
;; message is added, before the first LLM call.

(defonce ^:private input-hooks (atom []))
(defonce ^:private before-agent-start-hooks (atom []))

(defn register-input-hook!
  "Register an input hook (pi: pi.on('input')).
   Fires for agent messages submitted from the interactive input path
   (modes.interactive handle-submit) — slash and bash commands are native UI
   features with their own extension hooks and bypass input hooks.
   Hook: (fn [{:keys [text source streaming-behavior images]}])
   Return {:action :handled} to consume the input (no agent run),
   {:action :transform :text new-text :images new-images} to rewrite it
   (later hooks see the rewritten text and images), or nil to leave it
   unchanged."
  [hook]
  (swap! input-hooks conj hook)
  nil)

(defn register-before-agent-start-hook!
  "Register a before-agent-start hook (pi: pi.on('before_agent_start')).
   Hook: (fn [{:keys [prompt system-prompt]}])
   Return a map with :system-prompt (per-run override; later hooks see the
   overridden prompt) and/or :message (a message map injected into the
   context), or nil."
  [hook]
  (swap! before-agent-start-hooks conj hook)
  nil)

(defn apply-input-hooks
  "Run all input hooks in registration order over text and images
   (pi: emitInput).
   Hook ctx: {:text t :source s :streaming-behavior b :images [...]} —
   streaming-behavior is :steer when the agent is already running (input will
   be queued), nil when idle (input starts a fresh run); :images is the
   vector of attached image content blocks (possibly empty).
   Returns:
     {:action :handled}                  — a hook consumed the input
     {:action :transform :text t :images i}  — hooks rewrote the input (later
                                     hooks saw the rewritten text and images)
     {:action :pass :text t :images i}   — no hook changed anything"
  [text source & [{:keys [streaming-behavior images]}]]
  (let [initial-images (or images [])]
    (loop [hooks @input-hooks
           current text
           current-images initial-images]
      (if-let [hook (first hooks)]
        (let [result (try
                       (hook {:text current :source source
                              :streaming-behavior streaming-behavior
                              :images current-images})
                       (catch Exception e
                         (binding [*out* *err*]
                           (println "Warning: input hook error:" (ex-message e)))
                         nil))]
          (cond
            (= :handled (:action result)) {:action :handled}
            (= :transform (:action result))
            (recur (next hooks) (:text result current)
                   (if (contains? result :images) (:images result) current-images))
            :else (recur (next hooks) current current-images)))
        (if (and (= current text) (= current-images initial-images))
          {:action :pass :text text :images current-images}
          {:action :transform :text current :images current-images})))))

(defn apply-before-agent-start-hooks
  "Run all before-agent-start hooks in registration order (pi: emitBeforeAgentStart).
   Later hooks see the system prompt as modified by earlier hooks.
   Returns {:system-prompt string-or-nil :messages [msg ...]} —
   :system-prompt is the overridden prompt or nil when unchanged; :messages
   are the custom messages returned by the hooks in order."
  [prompt system-prompt]
  (loop [hooks @before-agent-start-hooks
         current-prompt system-prompt
         messages []]
    (if-let [hook (first hooks)]
      (let [result (try
                     (hook {:prompt prompt :system-prompt current-prompt})
                     (catch Exception e
                       (binding [*out* *err*]
                         (println "Warning: before-agent-start hook error:" (ex-message e)))
                       nil))]
        (recur (next hooks)
               (if (and result (contains? result :system-prompt))
                 (:system-prompt result)
                 current-prompt)
               (if (and result (:message result))
                 (conj messages (:message result))
                 messages)))
      {:system-prompt (when (not= current-prompt system-prompt) current-prompt)
       :messages messages})))

(defn clear-input-hooks!
  "Remove all input hooks (for testing)."
  []
  (reset! input-hooks []))

(defn clear-before-agent-start-hooks!
  "Remove all before-agent-start hooks (for testing)."
  []
  (reset! before-agent-start-hooks []))

;; ─── Extension loading ─────────────────────────────────────────────────────

(defonce ^:private extensions (atom []))

(defn clear-extensions!
  "Remove all loaded extensions and their hooks (pi: session.reload emits
   session_shutdown, then re-loads extensions). Used by /reload."
  []
  (reset! extensions [])
  (clear-input-hooks!)
  (clear-before-agent-start-hooks!))

;; ─── Extension UI registry (pi: ExtensionUIContext) ────────────────────────
;; The interactive mode installs the live UI implementations after building
;; the layout; extensions call the ui-* fns below, which dispatch through
;; the registry. Before the layout exists the registry is empty and every
;; call is a no-op returning nil (matching pi, where ui methods called
;; before startup are inert). The registry is cleared on reload; extensions
;; re-register via session_start handlers.

(defonce ^:private ui-registry (atom {}))

(defn set-ui-registry!
  "Install the interactive mode's UI implementations: a map of capability
   keyword → (fn [& args]). Called by build-layout; replaced on reload."
  [registry]
  (reset! ui-registry registry)
  nil)

(defn clear-ui-registry!
  "Drop the UI registry (no-op ui calls afterwards)."
  []
  (reset! ui-registry {})
  nil)

(defn ui-call
  "Dispatch a UI capability call through the registry. No-op (nil) when the
   interactive mode has not installed the registry yet (pi: ui methods are
   inert before startup)."
  [capability & args]
  (when-let [f (get @ui-registry capability)]
    (apply f args)))

;; ─── Extension-facing UI API (pi: ctx.ui.*) ────────────────────────────────
;; Each fn mirrors one ExtensionUIContext method. Dialogs return a Clojure
;; promise (pi: Promise) — deref it on a worker/agent thread to get the
;; result (nil on cancel). NOTE: derefing from the TUI input thread
;; deadlocks input (same constraint as blocking pi's single-threaded loop);
;; event handlers and command handlers that run off the input thread are
;; safe.

(defn ui-custom
  "Show a custom extension component (pi: ctx.ui.custom).
   FACTORY — (fn [tui theme keybindings done]) returning a component (or
   render map). DONE is (fn [result]) — call it to close the UI and resolve
   the returned promise with RESULT.
   Options: :overlay true to show as an overlay (pi overlay mode;
   default false replaces the editor area); :overlay-options — map or
   (fn [] map) passed to tui-show-overlay; :on-handle — (fn [handle])
   receiving the OverlayHandle map."
  [factory & [{:keys [overlay overlay-options on-handle]}]]
  (ui-call :custom factory {:overlay overlay
                            :overlay-options overlay-options
                            :on-handle on-handle}))

(defn ui-select
  "Show a list selector dialog (pi: ctx.ui.select). Returns a promise of
   the chosen string, or nil when cancelled."
  [title options & [_opts]]
  (ui-call :select title options))

(defn ui-confirm
  "Show a Yes/No confirmation (pi: ctx.ui.confirm). Returns a promise of
   a boolean."
  [title message & [_opts]]
  (ui-call :confirm title message))

(defn ui-input
  "Show a one-line input dialog (pi: ctx.ui.input). Returns a promise of
   the entered string, or nil when cancelled."
  [title placeholder & [_opts]]
  (ui-call :input title placeholder))

(defn ui-notify
  "Show a transient notification (pi: ctx.ui.notify). Type: :info |
   :warning | :error (all rendered as a bottom flash in kmet)."
  [message & [type]]
  (ui-call :notify message type))

(defn ui-on-terminal-input
  "Register a raw terminal input listener (pi: ctx.ui.onTerminalInput).
   HANDLER — (fn [data]) returning nil, or a map with :consume true to
   stop dispatch and/or :data to transform the data for later listeners.
   Returns a deregister function."
  [handler]
  (ui-call :on-terminal-input handler))

(defn ui-set-status
  "Set/clear a keyed status shown in the footer (pi: ctx.ui.setStatus).
   Pass nil text to clear the key."
  [key text]
  (ui-call :set-status key text))

(defn ui-set-widget
  "Set/clear a persistent widget above (default) or below the editor
   (pi: ctx.ui.setWidget). CONTENT — vector of lines, or a factory
   (fn [tui theme]) returning a component; nil clears the widget.
   Options: :placement :above-editor | :below-editor."
  [key content & [{:keys [placement]}]]
  (ui-call :set-widget key content {:placement placement}))

(defn ui-set-footer
  "Replace the footer with a custom component (pi: ctx.ui.setFooter).
   FACTORY — (fn [tui theme footer-data]) returning a component; nil
   restores the built-in footer. FOOTER-DATA — map with :get-git-branch,
   :get-extension-statuses, :on-branch-change."
  [factory]
  (ui-call :set-footer factory))

(defn ui-set-header
  "Replace the header with a custom component (pi: ctx.ui.setHeader).
   FACTORY — (fn [tui theme]) returning a component; nil restores the
   built-in header."
  [factory]
  (ui-call :set-header factory))

(defn ui-set-title
  "Set the terminal window title (pi: ctx.ui.setTitle)."
  [title]
  (ui-call :set-title title))

(defn ui-set-editor-text
  "Replace the editor content (pi: ctx.ui.setEditorText)."
  [text]
  (ui-call :set-editor-text text))

(defn ui-get-editor-text
  "Current editor content (pi: ctx.ui.getEditorText)."
  []
  (ui-call :get-editor-text))

(defn ui-paste-to-editor
  "Paste text into the editor as if bracketed-pasted (pi:
   ctx.ui.pasteToEditor)."
  [text]
  (ui-call :paste-to-editor text))

(defn ui-set-working-indicator
  "Customize the streaming working indicator (pi: ctx.ui.setWorkingIndicator).
   Options map with :frames (vector of frame strings, rendered verbatim)
   and/or :interval-ms; empty frames hide the indicator; nil restores the
   default spinner."
  [options]
  (ui-call :set-working-indicator options))

(defn ui-set-working-message
  "Set the working indicator message (pi: ctx.ui.setWorkingMessage)."
  [message]
  (ui-call :set-working-message message))

(defn ui-set-working-visible
  "Show/hide the working indicator (pi: ctx.ui.setWorkingVisible)."
  [visible?]
  (ui-call :set-working-visible visible?))

(defn ui-set-hidden-thinking-label
  "Set the label shown in place of hidden thinking blocks (pi:
   ctx.ui.setHiddenThinkingLabel)."
  [label]
  (ui-call :set-hidden-thinking-label label))

(defn ui-set-editor-component
  "Replace the editor with a custom component (pi: ctx.ui.setEditorComponent).
   FACTORY — (fn [tui theme keybindings]) returning a component; nil
   restores the default editor, preserving text."
  [factory]
  (ui-call :set-editor-component factory))

(defn ui-add-autocomplete-provider
  "Add an autocomplete provider to the editor (pi:
   ctx.ui.addAutocompleteProvider)."
  [factory]
  (ui-call :add-autocomplete-provider factory))

(defn ui-get-theme
  "The active theme map (pi: ctx.ui.theme)."
  []
  (ui-call :get-theme))

(defn ui-get-all-themes
  "All registered themes as a name → theme map (pi: ctx.ui.getAllThemes)."
  []
  (ui-call :get-all-themes))

(defn ui-get-tools-expanded
  "Whether tool output is expanded (pi: ctx.ui.getToolsExpanded)."
  []
  (ui-call :get-tools-expanded))

(defn ui-set-tools-expanded
  "Expand/collapse all tool output (pi: ctx.ui.setToolsExpanded)."
  [expanded?]
  (ui-call :set-tools-expanded expanded?))

(defn ui-reset!
  "Reset all extension UI back to defaults (pi: resetExtensionUI). Called
   on reload: disposes widgets, restores footer/header/editor, clears
   statuses and working-indicator customization, drops terminal input
   listeners."
  []
  (ui-call :reset))

(defn load-extensions-from-dir
  "Load all .clj extension files from a directory.
   Each file is loaded with load-string for side effects."
  [dir]
  (let [d (io/file dir)]
    (when (fs/directory? d)
      (doseq [f (fs/list-dir d)]
        (when (str/ends-with? (fs/file-name f) ".clj")
          (try
            (let [code (slurp (str f))]
              (load-string code)
              (swap! extensions conj
                     {:name (fs/file-name f) :file (str (fs/canonicalize f))}))
            (catch Exception e
              (binding [*out* *err*]
                (println "Warning: Failed to load extension" (fs/file-name f) ":" (ex-message e))))))))))
