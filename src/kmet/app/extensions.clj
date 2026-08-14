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
            [babashka.fs :as fs]
            [babashka.process :as proc]
            [kmet.ai.models :as models]
            [kmet.app.commands :as commands]
            [kmet.app.session :as session]
            [kmet.app.tools.core :as tools]))

;; ─── Extension commands, tools, exec (pi: registerCommand / registerTool /
;; ─── exec / getCommands / getAllTools) ────────────────────────────────────
;; Plain wrappers over the existing registries — extensions register slash
;; commands and tools exactly like builtins do.

(defn register-command!
  "Register (or replace by :name) a slash command (pi: registerCommand).
   CMD — {:name string :description string :argument-hint optional
   :get-argument-completions optional :handler (fn [cs args])}."
  [cmd]
  (commands/register-command! cmd)
  nil)

(defn unregister-command!
  "Remove a slash command by :name (pi: unregisterCommand)."
  [name]
  (commands/unregister-command! name)
  nil)

(defn get-commands
  "All registered slash commands (pi: getCommands)."
  []
  (commands/get-commands))

(defn register-tool!
  "Register a custom tool (pi: registerTool). Accepts a Tool record (as built
   by kmet.app.tools.core/make-tool) or the same keyword args directly:
   :name :description :params/:parameters :execute :render-call
   :render-result :prepare-arguments :streams? :constrained-sampling ..."
  [tool-or-kwargs]
  (let [tool (if (map? tool-or-kwargs)
               (apply tools/make-tool (apply concat (seq tool-or-kwargs)))
               tool-or-kwargs)]
    (tools/register-tool! tool))
  nil)

(defn unregister-tool!
  "Remove a custom tool by :name (pi: unregisterTool)."
  [name]
  (tools/unregister-tool! name)
  nil)

(defn get-all-tools
  "All configured tools with parameter schema (pi: getAllTools — returns
   an array, not the name-keyed registry map)."
  []
  (vals (tools/get-all-tools)))

(defn exec
  "Execute a shell command and return {:exit n :out str :err str}
   (pi: exec). Options: :dir (working directory), :env (extra env map),
   :timeout-ms. Raises on process launch failure (pi rejects)."
  [command args & [{:keys [dir env timeout-ms]}]]
  (let [p (proc/process (concat [command] args)
                        (cond-> {:out :string :err :string}
                          dir (assoc :dir dir)
                          env (assoc :env env)
                          timeout-ms (assoc :timeout timeout-ms)))]
    {:exit (:exit @p)
     :out (:out @p)
     :err (:err @p)}))

;; ─── Extension input / before-agent-start hooks ────────────────────────────
;; pi: extensions register via pi.on("input") and pi.on("before_agent_start");
;; AgentSession.prompt() consults them per submission. kmet: the interactive
;; input path (modes.interactive handle-submit) applies input hooks before the
;; agent runs; run-agent-turn applies before-agent-start hooks after the user
;; message is added, before the first LLM call.

(defonce ^:private input-hooks (atom []))
(defonce ^:private before-agent-start-hooks (atom []))
;; Custom-entry renderers (pi: registerEntryRenderer) + the live entry sink
;; (pi: appendEntry triggers a render). Rendered by replay-branch! and the
;; interactive entry sink; entries without a renderer stay hidden.
(defonce ^:private entry-renderers (atom {}))
(defonce ^:private entry-sink-atom (atom nil))
;; Custom-message renderers (pi: registerMessageRenderer): override the
;; default labeled info-box rendering of :custom-message entries/messages.
(defonce ^:private message-renderers (atom {}))
;; Tool hooks (pi: tool_call / tool_result with result transforms): chained
;; per call by the interactive's combined before/after-tool-call hooks.
(defonce ^:private tool-call-hooks (atom []))
(defonce ^:private tool-result-hooks (atom []))
;; Extension CLI flags (pi: registerFlag/getFlag): registrations + the
;; collected --flags from argv (set by core.clj -main after extension load).
(defonce ^:private flags (atom {}))
(defonce ^:private cli-flags (atom {}))

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

;; ─── Extension provider registry (pi: ctx.registerProvider / ctx.models) ──
;; Extensions add providers via register-provider!/unregister-provider!; the
;; read facade mirrors pi's ModelRegistry (ctx.models). Registrations persist
;; across /reload (pi: the ModelRuntime survives reload; extensions re-register
;; idempotently when the extension files re-run).

(defn register-provider!
  "Register an extension provider (pi: ctx.registerProvider): a provider-id +
   config map (kmet.ai.models/register-provider-config!) or a complete
   Provider record (kmet.ai.models/register-native-provider!). A broken
   config throws without touching previously registered state."
  ([provider-id config]
   (models/register-provider-config! provider-id config))
  ([provider]
   (models/register-native-provider! provider)))

(defn unregister-provider!
  "Remove an extension provider registration (pi: ctx.unregisterProvider):
   the provider falls back to its builtin (or disappears when it had none)."
  [provider-id]
  (models/unregister-provider-config! provider-id))

(defn get-all-models
  "All registered models across every provider (pi: ctx.models.getAll)."
  []
  (models/get-models))

(defn get-available-models
  "Models whose provider has complete auth (pi: ctx.models.getAvailable)."
  []
  (models/get-available))

(defn find-model
  "Model record for a provider + model id, or nil (pi: ctx.models.find)."
  [provider-id model-id]
  (models/get-model provider-id model-id))

(defn has-configured-auth
  "True when a model's provider has complete auth (pi:
   ctx.models.hasConfiguredAuth)."
  [model]
  (models/has-configured-auth model))

(defn get-provider-auth-status
  "Auth status map for a provider (pi: ctx.models.getProviderAuthStatus):
   {:configured bool :source kw}."
  [provider-id]
  (models/get-provider-auth-status provider-id))

(defn get-api-key-and-headers
  "Resolved request auth for a model (pi: ctx.models.getApiKeyAndHeaders):
   {:ok true :api-key str? :headers map?} or {:ok false :error str}."
  [model]
  (models/get-api-key-and-headers model))

(defn get-registered-provider-config
  "The registered extension config for a provider, or nil (pi:
   ctx.models.getRegisteredProviderConfig)."
  [provider-id]
  (models/get-registered-provider-config provider-id))

(defn get-registered-provider-ids
  "Provider ids with an extension registration (pi:
   ctx.models.getRegisteredProviderIds)."
  []
  (models/get-registered-provider-ids))

;; ─── Extension loading ─────────────────────────────────────────────────────

(defonce ^:private extensions (atom []))

(defn get-loaded-extensions
  "Vector of loaded extension maps {:name str :file str} (pi:
   resourceLoader.getExtensions)."
  []
  @extensions)

(defn clear-extensions!
  "Remove all loaded extensions and their hooks (pi: session.reload emits
   session_shutdown, then re-loads extensions). Used by /reload."
  []
  (reset! extensions [])
  (clear-input-hooks!)
  (clear-before-agent-start-hooks!)
  (reset! entry-renderers {})
  (reset! message-renderers {})
  (reset! tool-call-hooks [])
  (reset! tool-result-hooks [])
  (reset! flags {}))

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
  "Add an autocomplete provider wrapper to the editor (pi:
   ctx.ui.addAutocompleteProvider). FACTORY — (fn [base-provider]) —
   receives the current provider chain and returns a wrapped provider
   (an AutocompleteProvider, or a map with :get-suggestions and optional
   :apply-completion / :should-trigger-file-completion /
   :get-trigger-characters); returning nil keeps the chain unchanged."
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

(defn ui-set-theme
  "Switch the active theme: a Theme instance replaces it in-memory; a name
   is loaded from the registry, disabling auto light/dark sync (pi:
   ctx.ui.setTheme). Returns {:success bool :error msg?}."
  [theme-or-name]
  (ui-call :set-theme theme-or-name))

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

;; ─── Agent control (pi: ctx.setModel / getThinkingLevel / setThinkingLevel /
;; ─── sendUserMessage / getActiveTools / setActiveTools) ──────────────────
;; These dispatch through the UI registry installed by interactive mode
;; (like the ui-* fns); before the layout exists they are no-ops (matching
;; pi, where agent-control calls before startup are inert).

(defn set-model
  "Set the active model (pi: setModel). MODEL — a Model record from
   get-all-models/find-model. Returns false when the provider has no
   configured auth (pi returns false)."
  [model]
  (ui-call :set-model model))

(defn get-thinking-level
  "The current thinking level (pi: getThinkingLevel)."
  []
  (ui-call :get-thinking-level))

(defn set-thinking-level
  "Set the thinking level, clamped to the known levels
   (pi: setThinkingLevel)."
  [level]
  (ui-call :set-thinking-level level)
  nil)

(defn send-user-message
  "Send a user message to the agent (pi: sendUserMessage). Always triggers
   a turn. When the agent is streaming, DELIVER-AS controls how the message
   is queued: :steer injects it mid-run (between turns), :follow-up (the
   default) processes it after the current run settles."
  [text & [{:keys [deliver-as]}]]
  (ui-call :send-user-message text {:deliver-as deliver-as})
  nil)

(defn get-active-tools
  "Names of the currently active tools (nil = all); pi: getActiveTools."
  []
  (ui-call :get-active-tools))

(defn set-active-tools
  "Restrict the tools sent to the LLM to NAMES (a set or seq of tool
   names); nil restores all tools (pi: setActiveTools)."
  [names]
  (ui-call :set-active-tools names)
  nil)

;; ─── Extension-facing session API (pi: ctx.sessionManager / ctx.session) ──
;; kmet extensions append custom entries for durable state and custom
;; messages for LLM-context injection, and set/read labels on entries. The
;; live session and the agent context sink are registered by interactive
;; mode (set-session! / set-context-sink!).

(defonce ^:private session-atom (atom nil))
(defonce ^:private context-sink-atom (atom nil))

(defn register-entry-renderer!
  "Register a renderer for a custom entry type (pi: registerEntryRenderer).
   RENDERER — (fn [entry]) returning a chat message map (or a bare
   component, wrapped automatically) or nil. Custom entries without a
   registered renderer stay hidden (pi renders only registered custom
   types)."
  [custom-type renderer]
  (swap! entry-renderers assoc custom-type renderer)
  nil)

(defn get-entry-renderer
  "The renderer registered for CUSTOM-TYPE, or nil."
  [custom-type]
  (get @entry-renderers custom-type))

(defn register-message-renderer!
  "Register a renderer for a custom MESSAGE type (pi: registerMessageRenderer).
   RENDERER — (fn [message]) returning a chat message map (or a bare
   component, wrapped automatically), overriding the default labeled info
   box."
  [custom-type renderer]
  (swap! message-renderers assoc custom-type renderer)
  nil)

(defn get-message-renderer
  "The renderer registered for a custom message type, or nil."
  [custom-type]
  (get @message-renderers custom-type))

;; ─── Tool hooks (pi: tool_call / tool_result) ─────────────────────────────
;; Extensions chain tool-call hooks (block / rewrite args) and tool-result
;; hooks (rewrite content/is-error) — pi's tool_call/tool_result events with
;; result transforms. The interactive installs them as the agent's
;; before/after-tool-call hooks; hooks run in registration order, later
;; hooks see earlier rewrites.

(defn register-tool-call-hook!
  "Register a tool-call hook (pi: on('tool_call')).
   HOOK — (fn [{:keys [tool-name tool-call-id args assistant-message]})
   returning nil (pass), {:block true :reason str} (block execution), or
   {:args transformed} (rewrite the call's arguments)."
  [hook]
  (swap! tool-call-hooks conj hook)
  nil)

(defn register-tool-result-hook!
  "Register a tool-result hook (pi: on('tool_result')).
   HOOK — (fn [{:keys [tool-name tool-call-id args result is-error
   assistant-message]}) returning nil or a map of :content / :is-error
   overrides merged into the result."
  [hook]
  (swap! tool-result-hooks conj hook)
  nil)

(defn get-tool-call-hooks [] @tool-call-hooks)
(defn get-tool-result-hooks [] @tool-result-hooks)

(defn set-entry-sink!
  "Install the live custom-entry sink — (fn [entry]) called after a custom
   entry is appended, so the interactive mode can render it immediately
   (pi: appendEntry triggers a render). Re-installed on reload."
  [f]
  (reset! entry-sink-atom f)
  nil)

(defn set-session!
  "Register the live session for extension access (pi: ctx.sessionManager).
   Called by interactive mode whenever the live session changes (create,
   resume, fork, clone)."
  [session]
  (reset! session-atom session)
  nil)

(defn get-session
  "The currently active Session record (pi: ctx.sessionManager), or nil when
   no session is live."
  []
  @session-atom)

(defn set-context-sink!
  "Install the live-context sink — (fn [msg]) injecting a message into the
   agent's in-memory context (pi: custom messages flow through the agent
   loop). Interactive mode wires it to the agent state; re-installed on
   reload."
  [f]
  (reset! context-sink-atom f)
  nil)

(defn append-custom-entry!
  "Append a custom entry (extension state, never in LLM context) to the
   live session (pi: ctx.session.appendEntry). No-op (nil) when no session
   is live. Returns the entry id. The entry sink (set by interactive mode)
   renders it live when a renderer is registered for its custom-type."
  [custom-type & [data]]
  (when-let [sess @session-atom]
    (let [entry (session/append-custom-entry! sess custom-type data)]
      (when-let [sink @entry-sink-atom]
        (sink entry))
      (:id entry))))

(defn append-custom-message!
  "Send a custom message that participates in LLM context (pi:
   ctx.session.sendMessage — appendCustomMessageEntry + agent injection):
   the entry is persisted to the session, the message is injected into the
   agent's live context (seen by the next LLM call), and it renders in the
   TUI when DISPLAY is true. Returns the entry id, or nil when no session
   is live."
  [custom-type content display & [details]]
  (when-let [sess @session-atom]
    (let [entry (session/append-custom-message-entry! sess custom-type
                                                      content display details)]
      (when-let [sink @context-sink-atom]
        (sink {:role :custom
               :custom-type custom-type
               :content content
               :display display
               :details details}))
      (:id entry))))

(defn get-custom-entries
  "The live session's :custom entries of CUSTOM-TYPE along the active branch
   (extensions restore state on reload). Empty when no session is live."
  [custom-type]
  (if-let [sess @session-atom]
    (session/get-custom-entries sess custom-type)
    []))

(defn set-label!
  "Set or clear a label on an entry of the live session (pi:
   ctx.session.setLabel). Throws when the entry id is unknown; no-op (nil)
   when no session is live."
  [entry-id label]
  (when-let [sess @session-atom]
    (session/set-label! sess entry-id label)))

(defn get-label
  "Current label of an entry in the live session (pi:
   ctx.sessionManager.getLabel); nil when unlabeled or no session is live."
  [entry-id]
  (when-let [sess @session-atom]
    (session/get-label sess entry-id)))

;; ─── Extension CLI flags (pi: registerFlag / getFlag) ─────────────────────

(defn register-flag!
  "Register a CLI flag extensions can read with get-flag (pi: registerFlag).
   NAME — the flag name without the leading --; options: :type
   (:boolean | :string, default :string) and :default. The flag value comes
   from the argv --name [value] collected by core.clj; a bare --name is
   boolean true."
  [name & [{:keys [type default]}]]
  (swap! flags assoc name {:type (or type :string) :default default})
  nil)

(defn set-cli-flags!
  "Install the collected --flags from argv (called by core.clj -main after
   extensions load, so registered flags are visible to session_start
   handlers)."
  [flag-map]
  (reset! cli-flags (or flag-map {}))
  nil)

(defn get-flag
  "Value of a registered CLI flag (pi: getFlag): the argv value coerced by
   the registered :type, falling back to :default. nil for unregistered
   flags (pi returns undefined)."
  [name]
  (let [{:keys [type default]} (get @flags name)]
    (when (contains? @flags name)
      (let [raw (get @cli-flags name)]
        (case type
          :boolean (let [v (if (nil? raw) default raw)]
                     (if (string? v)
                       (not (contains? #{"false" "0" ""} v))
                       (boolean v)))
          :string (or (when (string? raw) raw) default)
          raw)))))

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
