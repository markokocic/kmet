(ns kmet.app.extensions
  "Extension runtime: discovers, loads, reloads and unloads Clojure
   extensions. The extension contract lives in kmet.extension — extensions
   depend on that namespace plus the shared library layers kmet.tui.*
   (the generic TUI layer; the port of pi's @earendil-works/pi-tui) and
   kmet.libs.* (generic, self-contained utilities) — all shared by
   reference; this runtime wires the api capabilities to the registries
   and the interactive/loop surfaces.

   An extension is a .clj file defining (defn init [api]) in its namespace,
   or a directory containing an extension.edn manifest:
     {:name \"my-ext\" :entry \"src/my_ext.clj\"}
   The manifest lists only the initial namespace (:entry); everything else
   is required from there. Each extension evaluates in its own isolated SCI
   context: internal namespaces are served from the extension directory,
   declared libraries from its deps.edn (resolved in-process via
   borkdude.deps) — so
   different extensions can use different versions of the same library, and
   unloading an extension releases everything it pulled in. Optional
   (defn shutdown [api]) runs on unload, which also unregisters everything
   the extension registered (each registration tracks its deregister fn).

   Extensions load at startup (core.clj), are re-loaded by /reload, and can
   be unloaded/reloaded at runtime via unload-extension! /
   reload-extensions!."
  (:require [clojure.java.io :as io]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [babashka.classes]
            [babashka.fs :as fs]
            [babashka.process :as proc]
            [borkdude.deps :as bdeps]
            [sci.core :as sci]
            [kmet.ai.models :as models]
            [kmet.ai.hooks :as ai-hooks]
            [kmet.app.commands :as commands]
            [kmet.app.event-bus :as event-bus]
            [kmet.app.prompts :as prompts]
            [kmet.app.session :as session]
            [kmet.app.skills :as skills]
            [kmet.app.tools.core :as tools]
            [kmet.tui.theme :as theme]
            [kmet.extension]))

;; ─── Provider-event bridges (pi: context / before_provider_request /
;; ─── before_provider_headers / after_provider_response) ────────────────
;; The ai layer exposes injectable hooks (kmet.ai.api.shared) — it cannot
;; depend on kmet.app (the event bus). These bridges translate bus events
;; into hook results. The bus returns the LAST non-nil handler result;
;; pi chains handler results (each handler sees the previous one's
;; replacement) — kmet's approximation: handlers see the original event
;; and the last non-nil result wins. before-provider-headers handlers
;; return the replacement header map (Clojure maps are immutable — the
;; return value IS the mutation; pi mutates in place).

(defn- install-provider-event-bridges!
  "Wire the bus to the ai-layer hooks once (idempotent)."
  []
  (ai-hooks/set-context-hook!
   (fn [messages]
     (let [result (event-bus/emit-event! {:type :context :messages messages})]
       (if (and result (contains? result :messages))
         (:messages result)
         messages))))
  (ai-hooks/set-before-provider-request-hook!
   (fn [payload]
     (let [result (event-bus/emit-event! {:type :before-provider-request
                                          :payload payload})]
       (if (some? result) result payload))))
  (ai-hooks/set-before-provider-headers-hook!
   (fn [headers]
     (let [result (event-bus/emit-event! {:type :before-provider-headers
                                          :headers headers})]
       (if (map? result) result headers))))
  (ai-hooks/set-after-provider-response-hook!
   (fn [{:keys [status headers]}]
     (event-bus/emit-event! {:type :after-provider-response
                             :status status
                             :headers headers})))
  nil)

(install-provider-event-bridges!)

;; ─── Extension records ────────────────────────────────────────────────────
(defrecord Extension [name path entry-ns ctx jars api deregister-fns initialized?])

(defn- extension-dir-of
  "The extension's own directory: for a dir extension :path IS the
   directory; for a single-file extension it's the file, so the dir is its
   parent. (Was always the parent — wrong for dir extensions, which broke
   :extension-dir-relative resource discovery like skills/mcp and
   skills/clojure-edit.)"
  [ext]
  (str (if (fs/directory? (:path ext))
         (:path ext)
         (fs/parent (:path ext)))))

;; ─── Registries (the storage; api capabilities wire into these) ──────────
(defonce ^:private extensions (atom []))
(defonce ^:private input-hooks (atom []))
(defonce ^:private before-agent-start-hooks (atom []))
(defonce ^:private entry-renderers (atom {}))
(defonce ^:private message-renderers (atom {}))
(defonce ^:private tool-call-hooks (atom []))
(defonce ^:private tool-result-hooks (atom []))
(defonce ^:private markdown-transformers (atom []))
(defonce ^:private flags (atom {}))
(defonce ^:private cli-flags (atom {}))
(defonce ^:private ui-registry (atom {}))
(defonce ^:private session-atom (atom nil))
(defonce ^:private context-sink-atom (atom nil))
(defonce ^:private entry-sink-atom (atom nil))
(defonce ^:private applied-resource-paths (atom #{}))

;; ─── Input / before-agent-start hooks (pi: pi.on('input') / ──────────────
;; ─── 'before_agent_start'; applied by modes.interactive + app.loop) ──────

(defn register-input-hook!
  "Register an input hook (extension api: on-input). Fires for agent
   messages submitted from the interactive input path. Hook:
   (fn [{:keys [text source streaming-behavior images]}]) returning
   {:action :handled} to consume, {:action :transform :text ... :images ...}
   to rewrite, or nil. Returns a deregister fn."
  [hook]
  (swap! input-hooks conj hook)
  (fn [] (swap! input-hooks (fn [hs] (remove #(identical? % hook) hs)))))

(defn register-before-agent-start-hook!
  "Register a before-agent-start hook (extension api: on-before-agent-start).
   Hook: (fn [{:keys [prompt system-prompt]}]) returning a map with
   :system-prompt and/or :message, or nil. Returns a deregister fn."
  [hook]
  (swap! before-agent-start-hooks conj hook)
  (fn [] (swap! before-agent-start-hooks (fn [hs] (remove #(identical? % hook) hs)))))

(defn apply-input-hooks
  "Run all input hooks in registration order over text and images
   (pi: emitInput). Returns {:action :handled} | {:action :transform ...}
   | {:action :pass ...}."
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
  "Run all before-agent-start hooks in registration order.
   Returns {:system-prompt string-or-nil :messages [msg ...]}."
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

(defn clear-input-hooks! [] (reset! input-hooks []))
(defn clear-before-agent-start-hooks! [] (reset! before-agent-start-hooks []))

;; ─── Renderers + tool hooks (extension api) ───────────────────────────────

(defn register-entry-renderer!
  "Register a renderer for a custom entry type (extension api:
   register-entry-renderer!). RENDERER — (fn [entry]) returning a chat
   message map (or bare component) or nil. Returns a deregister fn."
  [custom-type renderer]
  (swap! entry-renderers assoc custom-type renderer)
  (fn [] (swap! entry-renderers dissoc custom-type)))

(defn get-entry-renderer [custom-type] (get @entry-renderers custom-type))

(defn register-message-renderer!
  "Register a renderer for a custom MESSAGE type (extension api:
   register-message-renderer!). Returns a deregister fn."
  [custom-type renderer]
  (swap! message-renderers assoc custom-type renderer)
  (fn [] (swap! message-renderers dissoc custom-type)))

(defn get-message-renderer [custom-type] (get @message-renderers custom-type))

(defn register-tool-call-hook!
  "Register a tool-call hook (extension api: on-tool-call): (fn [ctx]) →
   nil | {:block true :reason} | {:args transformed}. Returns a deregister fn."
  [hook]
  (swap! tool-call-hooks conj hook)
  (fn [] (swap! tool-call-hooks (fn [hs] (remove #(identical? % hook) hs)))))

(defn register-tool-result-hook!
  "Register a tool-result hook (extension api: on-tool-result): (fn [ctx])
   → nil | {:content ... :is-error ...} overrides. Returns a deregister fn."
  [hook]
  (swap! tool-result-hooks conj hook)
  (fn [] (swap! tool-result-hooks (fn [hs] (remove #(identical? % hook) hs)))))

(defn get-tool-call-hooks [] @tool-call-hooks)
(defn get-tool-result-hooks [] @tool-result-hooks)

;; ─── Shortcuts + markdown transformers (extension api) ────────────────────

(declare ui-call)

(defn register-shortcut!
  "Register a keyboard shortcut (extension api: register-shortcut! — pi:
   registerShortcut). KEY-ID is a raw key string (\"ctrl+alt+x\", \"f5\", …);
   opts: {:description str :handler (fn [ctx])}. The interactive mode
   installs it as a priority editor action — checked before every builtin
   app binding (pi: onExtensionShortcut runs first) — and registers the
   keybinding definition on the global manager (key-hints resolve, user
   overrides apply). The last registration of the same key wins (pi: last
   extension wins). Returns a deregister fn; no-op headless."
  [key-id & [{:keys [description handler]}]]
  (or (ui-call :register-shortcut! key-id {:description description :handler handler})
      ;; headless: no-op dereg (unload must never NPE on it)
      (fn [] nil)))

(defn register-markdown-transformer!
  "Register a markdown transformer (extension api:
   register-markdown-transformer! — pi: registerMarkdownTransformer):
   (fn [markdown {:keys [message-type is-streaming available-width]}])
   → string, applied to user/assistant message markdown before rendering,
   in registration order. Transformers must be idempotent (they re-run per
   render — streaming chunks re-transform the accumulated text). A
   transformer that throws is skipped (pi: keep the current markdown and
   continue). Returns a deregister fn."
  [transformer]
  (swap! markdown-transformers conj transformer)
  (fn [] (swap! markdown-transformers
                (fn [ts] (remove #(identical? % transformer) ts)))))

(defn get-markdown-transformers
  "Registered markdown transformers in registration order."
  []
  @markdown-transformers)

(defn apply-markdown-transformers
  "Apply the registered markdown transformers to MARKDOWN in registration
   order (pi: applyMarkdownTransformers); a transformer that throws is
   skipped and the chain continues with the current markdown. CTX:
   {:message-type :user|:assistant :is-streaming bool :available-width int}."
  [markdown ctx]
  (reduce (fn [acc t]
            (try
              (let [r (t acc ctx)]
                (if (string? r) r acc))
              (catch Exception _ acc)))
          markdown
          @markdown-transformers))

;; ─── CLI flags (extension api: register-flag! / get-flag) ─────────────────

(defn register-flag!
  "Register a CLI flag (extension api: register-flag!). NAME — without the
   leading --; opts: :type (:boolean|:string) :default. Returns a deregister
   fn."
  [name & [{:keys [type default]}]]
  (swap! flags assoc name {:type (or type :string) :default default})
  (fn [] (swap! flags dissoc name)))

(defn set-cli-flags! [flag-map]
  (reset! cli-flags (or flag-map {}))
  nil)

(defn get-flag
  "Value of a registered CLI flag: the argv value coerced by the registered
   :type, falling back to :default. nil for unregistered flags."
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

;; ─── UI registry (the interactive installs live implementations) ─────────

(defn set-ui-registry! [registry]
  (reset! ui-registry registry)
  nil)

(defn clear-ui-registry! [] (reset! ui-registry {}) nil)

(defn ui-call
  "Dispatch a UI capability call through the registry. No-op (nil) when the
   interactive mode has not installed the registry yet (headless/print)."
  [capability & args]
  (when-let [f (get @ui-registry capability)]
    (apply f args)))

(declare api-session)

(defn- default-extension-context
  "Static context for headless/print mode (pi: ExtensionContext): the
   interactive :build-context capability overrides these per call. Every
   key is a value or zero-arg fn so handlers always receive a callable
   map. Built per call so it can reference the session facades (defined
   below) — the fns read the live session atom at call time."
  []
  {:mode :print
   :has-ui false
   :cwd (System/getProperty "user.dir")
   :model nil
   :scoped-models []
   :thinking-level nil
   :is-idle (fn [] true)
   :has-pending-messages (fn [] false)
   :signal (fn [] nil)
   :abort (fn [] nil)
   :shutdown (fn [] (System/exit 0))
   :get-context-usage (fn [] nil)
   :compact (fn [& _] nil)
   :get-system-prompt (fn [] nil)
   :get-system-prompt-options (fn [] nil)
   :wait-for-idle (fn [] nil)
   :reload (fn [] nil)
   :new-session (fn [& _] {:cancelled true})
   :fork (fn [& _] {:cancelled true})
   :navigate-tree (fn [& _] {:cancelled true})
   :switch-session (fn [& _] {:cancelled true})
   :is-project-trusted (fn [] false)
   ;; pi: ctx.sessionManager — read facades over the live session (set by
   ;; the interactive mode / headless tests via set-session!)
   :session (api-session)})

(defn build-extension-context
  "Build the extension context (pi: ExtensionContext) for the current
   runtime: the interactive mode's live :build-context capability merged
   over the headless default. Returns a fresh map per call — the fns
   capture live state at call time. Used for command handlers (replacing
   the internal CoreState leak) and event handler ctx args.

   Session control fns (new-session/fork/navigate-tree/switch-session/
   reload) run the interactive flows synchronously — call them from
   user-initiated command handlers, never from agent-loop event handlers
   (pi restricts these to the command ctx; they can re-enter the run loop)."
  []
  (merge (default-extension-context) (ui-call :build-context)))

(defn wrap-event-handler
  "Adapt an extension event handler to the bus: handlers always receive
   (event ctx) — pi parity, ctx built fresh per event. Fixed arity-2
   contract (no legacy shim): a handler that takes fewer args fails fast
   with an ArityException, which the bus logs as a handler error."
  [handler]
  (fn [event]
    (handler event (build-extension-context))))

(defn ui-notify [message & [type]] (ui-call :notify message type))
(defn ui-custom [factory & [opts]]
  (ui-call :custom factory opts))
(defn ui-chat-info [label content] (ui-call :chat-info label content))
(defn ui-on-terminal-input [handler] (ui-call :on-terminal-input handler))
(defn ui-set-status [key text] (ui-call :set-status key text))
(defn ui-set-widget [key content & [{:keys [placement]}]] (ui-call :set-widget key content {:placement placement}))
(defn ui-set-footer [factory] (ui-call :set-footer factory))
(defn ui-set-header [factory] (ui-call :set-header factory))
(defn ui-set-title [title] (ui-call :set-title title))
(defn ui-set-editor-text [text] (ui-call :set-editor-text text))
(defn ui-get-editor-text [] (ui-call :get-editor-text))
(defn ui-paste-to-editor [text] (ui-call :paste-to-editor text))
(defn ui-set-working-indicator [options] (ui-call :set-working-indicator options))
(defn ui-set-working-message [message] (ui-call :set-working-message message))
(defn ui-set-working-visible [visible?] (ui-call :set-working-visible visible?))
(defn ui-set-hidden-thinking-label [label] (ui-call :set-hidden-thinking-label label))
(defn ui-set-editor-component [factory] (ui-call :set-editor-component factory))
(defn ui-add-autocomplete-provider [factory] (ui-call :add-autocomplete-provider factory))
(defn ui-set-theme [theme-or-name] (ui-call :set-theme theme-or-name))
(defn ui-get-tools-expanded [] (ui-call :get-tools-expanded))
(defn ui-set-tools-expanded [expanded?] (ui-call :set-tools-expanded expanded?))
(defn ui-reset! [] (ui-call :reset))

;; ─── Agent control (dispatches through the ui registry; extension api) ───

(defn set-model [model] (ui-call :set-model model))
(defn get-thinking-level [] (ui-call :get-thinking-level))
(defn set-thinking-level [level] (ui-call :set-thinking-level level) nil)
(defn send-user-message
  "Extension api: send-user-message (pi: sendUserMessage) — send text to
   the agent. OPTIONS: {:deliver-as :steer | :follow-up (queue while
   streaming), :expand-prompt-templates? bool (pi:
   expandPromptTemplates — extension commands execute immediately and
   consume the message, then skill commands and prompt templates expand;
   kmet defaults to no expansion)."
  [text & [{:keys [deliver-as expand-prompt-templates?]}]]
  (ui-call :send-user-message text {:deliver-as deliver-as
                                    :expand-prompt-templates? expand-prompt-templates?})
  nil)

(declare append-custom-message!)

(defn send-message!
  "Extension api: send-message! (pi: sendMessage). MESSAGE:
   {:custom-type :content :display :details} — appended to the session as a
   custom_message entry (persisted), injected into the agent context (sent
   to the LLM as a user message; rendered in the chat when :display), and
   optionally triggering a turn. OPTIONS: {:trigger-turn bool :deliver-as
   :steer | :follow-up | :next-turn}. Idle + trigger-turn starts the run
   (the custom message is already in context); busy queues per deliver-as
   (:steer injects into the current run immediately, :follow-up/:next-turn
   defer to the next turn). Headless (no UI registry): persists + injects
   via the sinks. Returns nil."
  [message & [opts]]
  (if (nil? (ui-call :send-message! message opts))
    ;; headless fallback: persist + inject through the sinks
    (append-custom-message! (or (:custom-type message) :custom)
                            (:content message)
                            (if (nil? (:display message)) true (:display message))
                            (:details message))
    nil)
  nil)
(defn get-active-tools [] (ui-call :get-active-tools))
(defn set-active-tools [names] (ui-call :set-active-tools names) nil)

;; ─── Model / session facades (extension api: models / session) ───────────

(defn get-all-models [] (models/get-models))
(defn get-available-models [] (models/get-available))
(defn find-model [provider-id model-id] (models/get-model provider-id model-id))
(defn has-configured-auth [model] (models/has-configured-auth model))
(defn get-provider-auth-status [provider-id] (models/get-provider-auth-status provider-id))
(defn get-api-key-and-headers [model] (models/get-api-key-and-headers model))
(defn get-registered-provider-config [provider-id] (models/get-registered-provider-config provider-id))
(defn get-registered-provider-ids [] (models/get-registered-provider-ids))
(defn register-provider! [provider-id config] (models/register-provider-config! provider-id config))
(defn unregister-provider! [provider-id] (models/unregister-provider-config! provider-id))

(defn- exec
  "Execute a shell command and return {:exit n :out str :err str}
   (extension api: exec). Options: :dir :env :timeout-ms."
  [command args & [{:keys [dir env timeout-ms]}]]
  (let [p (proc/process (concat [command] args)
                        (cond-> {:out :string :err :string}
                          dir (assoc :dir dir)
                          env (assoc :env env)
                          timeout-ms (assoc :timeout timeout-ms)))]
    {:exit (:exit @p) :out (:out @p) :err (:err @p)}))

(defn set-session! [session] (reset! session-atom session) nil)
(defn get-session [] @session-atom)
(defn set-context-sink! [f] (reset! context-sink-atom f) nil)
(defn set-entry-sink! [f] (reset! entry-sink-atom f) nil)

(defn append-custom-entry!
  "Append a custom entry (extension state, never in LLM context) to the live
   session (extension api: session :append-entry!). Returns the entry id."
  [custom-type & [data]]
  (when-let [sess @session-atom]
    (let [entry (session/append-custom-entry! sess custom-type data)]
      (when-let [sink @entry-sink-atom]
        (sink entry))
      (:id entry))))

(defn append-custom-message!
  "Append a custom message that participates in LLM context (extension api:
   session :append-message!). Returns the entry id."
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

(defn get-custom-entries [custom-type]
  (if-let [sess @session-atom]
    (session/get-custom-entries sess custom-type)
    []))

(defn set-label! [entry-id label]
  (when-let [sess @session-atom]
    (session/set-label! sess entry-id label)))

(defn get-label [entry-id]
  (when-let [sess @session-atom]
    (session/get-label sess entry-id)))

;; ─── Extension API construction ──────────────────────────────────────────

(defn- track-deregister!
  "Record a deregister fn on the extension; unload runs them all."
  [ext f]
  (swap! (:deregister-fns ext) conj f))

(defn- api-ui
  "The :ui capability map — dispatches through the runtime registry, so it
   is inert before the layout exists and in headless mode. Only host-owned
   bridges live here: mounting extension-built components (:custom), the
   flash (:notify), chat-history info messages (:chat-info), and
   integrations with host layout/editor/status state. Extensions build
   their own components with the shared kmet.tui.* layer
   (pi: ctx.ui.custom hosting extension-built pi-tui components) — the api
   carries no host-built dialogs or theme lookups (kmet.tui.theme is
   shared directly)."
  []
  {:notify ui-notify
   :custom ui-custom
   :chat-info ui-chat-info
   :on-terminal-input ui-on-terminal-input
   :set-status ui-set-status
   :set-widget ui-set-widget
   :set-footer ui-set-footer
   :set-header ui-set-header
   :set-title ui-set-title
   :set-editor-text ui-set-editor-text
   :get-editor-text ui-get-editor-text
   :paste-to-editor ui-paste-to-editor
   :set-working-indicator ui-set-working-indicator
   :set-working-message ui-set-working-message
   :set-working-visible ui-set-working-visible
   :set-hidden-thinking-label ui-set-hidden-thinking-label
   :set-editor-component ui-set-editor-component
   :add-autocomplete-provider ui-add-autocomplete-provider
   :set-theme ui-set-theme
   :get-tools-expanded ui-get-tools-expanded
   :set-tools-expanded ui-set-tools-expanded})

(defn- api-models
  "The :models capability map — ctx.models facades."
  []
  {:get-all get-all-models
   :get-available get-available-models
   :find find-model
   :has-configured-auth has-configured-auth
   :get-provider-auth-status get-provider-auth-status
   :get-api-key-and-headers get-api-key-and-headers
   :get-registered-provider-config get-registered-provider-config
   :get-registered-provider-ids get-registered-provider-ids
   :register-provider! register-provider!
   :unregister-provider! unregister-provider!})

(defn- api-session
  "The :session capability map — live session facades."
  []
  {:append-entry! append-custom-entry!
   :append-message! append-custom-message!
   :get-entries get-custom-entries
   :set-label! set-label!
   :get-label get-label
   :set-name! (fn [name]
                (when-let [sess @session-atom]
                  (session/append-session-info! sess (session/sanitize-session-name name))))
   :get-name (fn [] (when-let [sess @session-atom] (session/get-session-name sess)))})

(defn- create-extension-api
  "Build the api map for an extension. Every registration records its
   deregister fn so unload removes exactly what this extension added."
  [ext]
  (let [track (fn [f] (track-deregister! ext f) f)
        name (:name ext)]
    {:extension-name name
     :extension-path (:path ext)
     ;; the extension's own directory (dir ext = the dir itself, file ext
     ;; = the file's parent) — resource discovery resolves against it
     :extension-dir (extension-dir-of ext)
     :register-command! (fn [cmd]
                          ;; the handler is stored under :extension-handler so
                          ;; the runner can pass it the extension context
                          ;; (pi: handler(args, ctx)) while builtin commands
                          ;; keep receiving CoreState
                          (let [cmd (assoc cmd :extension-handler (:handler cmd))]
                            (commands/register-command! cmd)
                            (track (fn [] (commands/unregister-command! (:name cmd))))))
     :unregister-command! commands/unregister-command!
     ;; sanitized — other extensions must not see :handler/:extension-handler
     :get-commands #(mapv (fn [c] (select-keys c [:name :description]))
                          (commands/get-commands))
     :register-tool! (fn [tool]
                       (tools/register-tool! tool)
                       (track (fn [] (tools/unregister-tool! (:name tool)))))
     :unregister-tool! tools/unregister-tool!
     :get-all-tools #(vals (tools/get-all-tools))
     :get-active-tools get-active-tools
     :set-active-tools set-active-tools
     :on-event (fn [event-type handler]
                 (let [dereg (event-bus/on-event event-type
                                                 (wrap-event-handler handler))]
                   (track dereg)
                   dereg))
     :emit-event! event-bus/emit-event!
     :on-input (fn [hook]
                 (register-input-hook! hook)
                 (track (fn [] (swap! input-hooks
                                      (fn [hs] (remove #(identical? % hook) hs))))))
     :on-before-agent-start (fn [hook]
                              (register-before-agent-start-hook! hook)
                              (track (fn [] (swap! before-agent-start-hooks
                                                   (fn [hs] (remove #(identical? % hook) hs))))))
     :on-tool-call (fn [hook]
                     (register-tool-call-hook! hook)
                     (track (fn [] (swap! tool-call-hooks
                                          (fn [hs] (remove #(identical? % hook) hs))))))
     :on-tool-result (fn [hook]
                       (register-tool-result-hook! hook)
                       (track (fn [] (swap! tool-result-hooks
                                            (fn [hs] (remove #(identical? % hook) hs))))))
     :register-flag! (fn [flag-name & [opts]]
                       (register-flag! flag-name opts)
                       (track (fn [] (swap! flags dissoc flag-name))))
     :get-flag get-flag
     :register-shortcut! (fn [key-id & [opts]]
                           (let [dereg (register-shortcut! key-id opts)]
                             (track dereg)
                             dereg))
     :register-markdown-transformer! (fn [transformer]
                                       (let [dereg (register-markdown-transformer! transformer)]
                                         (track dereg)
                                         dereg))
     :register-entry-renderer! (fn [custom-type renderer]
                                 (register-entry-renderer! custom-type renderer)
                                 (track (fn [] (swap! entry-renderers dissoc custom-type))))
     :register-message-renderer! (fn [custom-type renderer]
                                   (register-message-renderer! custom-type renderer)
                                   (track (fn [] (swap! message-renderers dissoc custom-type))))
     :set-model set-model
     :get-thinking-level get-thinking-level
     :set-thinking-level set-thinking-level
     :send-user-message send-user-message
     :send-message! send-message!
     :exec exec
     :ui (api-ui)
     :models (api-models)
     :session (api-session)}))

;; ─── Isolated extension contexts (sci) ───────────────────────────────────
;; Each extension evaluates inside its own sci context: a private namespace
;; registry plus a per-extension loader that serves (1) the extension's own
;; files, (2) the jars its deps.edn declares (the complete transitive
;; closure, resolved in-process via borkdude.deps), and (3) anything else on
;; the classpath. Global namespaces the extension may touch — kmet.extension
;; (the contract), clojure.*, babashka.*, and the shared library layers
;; kmet.tui.* (pi: @earendil-works/pi-tui) and kmet.libs.* (generic
;; self-contained utilities) — are injected as shared references, never
;; re-evaluated, so kmet's registries and protocols are not duplicated.
;; kmet.* namespaces outside that set (app internals) are not served at
;; all: re-evaluating them in a context would create context-local
;; copies of their registries, and sharing them would break the layer
;; boundary.

(declare unload-extension!)

(def ^:private bb-imports
  "babashka's default imports (babashka.impl.classes/imports): the
   unqualified classnames lib sources may use."
  '{AbstractMethodError java.lang.AbstractMethodError
    Appendable java.lang.Appendable
    ArithmeticException java.lang.ArithmeticException
    AssertionError java.lang.AssertionError
    BigDecimal java.math.BigDecimal
    BigInteger java.math.BigInteger
    Boolean java.lang.Boolean
    Byte java.lang.Byte
    Callable java.util.concurrent.Callable
    Character java.lang.Character
    CharSequence java.lang.CharSequence
    Class java.lang.Class
    ClassCastException java.lang.ClassCastException
    ClassNotFoundException java.lang.ClassNotFoundException
    Comparable java.lang.Comparable
    Compiler clojure.lang.Compiler
    Double java.lang.Double
    Error java.lang.Error
    Exception java.lang.Exception
    ExceptionInInitializerError java.lang.ExceptionInInitializerError
    IndexOutOfBoundsException java.lang.IndexOutOfBoundsException
    IllegalArgumentException java.lang.IllegalArgumentException
    IllegalStateException java.lang.IllegalStateException
    Integer java.lang.Integer
    InterruptedException java.lang.InterruptedException
    Iterable java.lang.Iterable
    File java.io.File
    Float java.lang.Float
    Long java.lang.Long
    LinkageError java.lang.LinkageError
    Math java.lang.Math
    NullPointerException java.lang.NullPointerException
    Number java.lang.Number
    NumberFormatException java.lang.NumberFormatException
    Object java.lang.Object
    Runnable java.lang.Runnable
    Runtime java.lang.Runtime
    RuntimeException java.lang.RuntimeException
    Process java.lang.Process
    ProcessBuilder java.lang.ProcessBuilder
    SecurityException java.lang.SecurityException
    Short java.lang.Short
    StackOverflowError java.lang.StackOverflowError
    StackTraceElement java.lang.StackTraceElement
    String java.lang.String
    StringBuilder java.lang.StringBuilder
    System java.lang.System
    Thread java.lang.Thread
    ThreadLocal java.lang.ThreadLocal
    Thread$UncaughtExceptionHandler java.lang.Thread$UncaughtExceptionHandler
    Throwable java.lang.Throwable
    VirtualMachineError java.lang.VirtualMachineError
    ThreadDeath java.lang.ThreadDeath
    UnsupportedOperationException java.lang.UnsupportedOperationException})

(def ^:private bb-bundled-libs
  "Libraries babashka ships adapted (SCI implementations baked into the
   binary) whose raw Maven versions generally fail in bb. Extensions should
   omit them from deps.edn and use the bundled copy. Plain-bundled libs
   whose Maven copies run fine (tools.cli, data.json, ...) are not listed —
   they resolve to declared versions normally."
  #{"cheshire/cheshire"
    "org.clojure/core.async"
    "org.clojure/core.cache"
    "org.clojure/core.memoize"
    "org.clojure/core.rrb-vector"
    "potemkin/potemkin"
    "rewrite-clj/rewrite-clj"
    "borkdude/edamame"
    "ring/ring-core"
    "selmer/selmer"})

(def ^:private bundled-port-namespaces
  "bb-bundled namespaces that are reduced custom ports, not the Maven
   sources: bb pre-loads them and serves them under its own require, and
   the raw Maven copies fail under SCI (tools.reader 1.3+ has deftypes
   implementing the java.io.Closeable interface, which SCI's deftype
   rejects). Injected by reference like the adapted libs — a declared
   Maven version cannot win for these, because it would not evaluate."
  '#{clojure.tools.reader
     clojure.tools.reader.edn
     clojure.tools.reader.reader-types})

(def ^:private bb-shared-namespaces
  "bb pre-loads these adapted-lib namespaces at startup (rewrite-clj ports,
   edamame) and their Maven copies cannot run under SCI: they require
   clojure.tools.reader.impl.* (impl.inspect dispatches on the removed
   PersistentArrayMap$Seq class), which bb does not bundle. Injected by
   reference into extension contexts like the custom ports, so extensions
   resolving them get the bundled copy and must not declare the Maven libs
   in deps.edn."
  '#{rewrite-clj.node
     rewrite-clj.parser
     rewrite-clj.paredit
     rewrite-clj.zip
     rewrite-clj.zip.subedit
     edamame.core})

(defonce ^:private context-classes
  (into {} (map (fn [^Class c] [(symbol (.getName c)) {:class c}])
                (remove #(str/starts-with? (.getName ^Class %) "[")
                        (babashka.classes/all-classes)))))

(def ^:private tui-library-namespaces
  "The generic TUI layer and the supported app-level tool renderers shared
   with extension contexts. Required once before per-extension contexts are
   built; injected by reference so component and protocol identity is shared."
  '[kmet.tui.core
    kmet.tui.theme
    kmet.tui.keybindings
    kmet.tui.macros
    kmet.tui.fuzzy
    kmet.tui.autocomplete
    kmet.tui.components.editing
    kmet.tui.components.expandable-text
    kmet.tui.components.image
    kmet.app.ui.tool-renderers])

(def ^:private libs-library-namespaces
  "The generic kmet.libs.* layer shared with extension contexts. Every lib
   is self-contained (enforced by kmet.libs.test-self-contained), so the
   whole prefix is whitelisted. Required once here so they exist when a
   per-extension context is built — injection is by reference, never
   re-evaluated, so any protocols they define keep their identity. Keep in
   sync with src/kmet/libs/ when a lib is added or removed."
  '[kmet.libs.diff
    kmet.libs.edit-diff
    kmet.libs.dynamic-value
    kmet.libs.edn-store
    kmet.libs.hash
    kmet.libs.highlight
    kmet.libs.jsonrpc
    kmet.libs.markdown
    kmet.libs.process
    kmet.libs.sse
    kmet.libs.terminal
    kmet.libs.terminal-image
    kmet.libs.yaml
    kmet.libs.clipboard])

(defn- build-context-namespaces
  "The shared namespace map for extension contexts: kmet.extension (the
   contract), the clojure.*/babashka.* builtins (incl. slurp/spit, which
   SCI's builtin clojure.core lacks but bb's env has), and the shared
   library layers kmet.tui.* and kmet.libs.*. Rebuilt per context so
   namespaces required since the last build (the shared library layers)
   are included."
  []
  (into {'kmet.extension (ns-interns 'kmet.extension)
         ;; slurp/spit/file-seq are absent from SCI's builtin clojure.core —
         ;; inject the host fns so extensions can read/write files directly
         ;; (the mcp-adapter used to work around this with babashka.fs
         ;; read-all-lines/write-bytes; file-seq is needed by libs such as
         ;; cljfmt.io's FileEntity protocol). sci merges these into its core.
         'clojure.core {'slurp (deref #'slurp)
                        'spit (deref #'spit)
                        'file-seq (deref #'file-seq)}}
        (keep (fn [ns-obj]
                (let [n (str (ns-name ns-obj))]
                  (when (and (not (str/starts-with? n "sci."))
                             (not= n "clojure.core")
                             ;; bundled libraries whose Maven versions run under
                             ;; SCI (clojure.tools.cli, data.json, ...) are NOT
                             ;; injected — they resolve through the load-fn, so a
                             ;; declared Maven version wins over the bundled copy.
                             ;; The adapted libs (core.async, cheshire, ...),
                             ;; the custom ports (bundled-port-namespaces) and
                             ;; bb-shared-namespaces stay injected: their Maven
                             ;; copies fail under SCI, so the bundled copy is
                             ;; the only working one.
                             (not (or (str/starts-with? n "clojure.data.")
                                      (and (str/starts-with? n "clojure.tools.")
                                           (not (contains? bundled-port-namespaces
                                                           (ns-name ns-obj))))))
                             (or (str/starts-with? n "clojure.")
                                 (str/starts-with? n "babashka.")
                                 (str/starts-with? n "cheshire.")
                                 (contains? bb-shared-namespaces (ns-name ns-obj))
                                 (str/starts-with? n "kmet.tui.")
                                 (= n "kmet.app.ui.tool-renderers")
                                 (str/starts-with? n "kmet.libs.")))
                    [(ns-name ns-obj) (ns-interns ns-obj)])))
              (all-ns))))

(defn- read-ns-form
  "The (ns ...) form at the start of FILE, or nil when the file doesn't
   start with one (or can't be read)."
  [file]
  (try
    (with-open [rdr (java.io.PushbackReader. (io/reader file))]
      (let [form (read rdr)]
        (when (and (list? form) (= 'ns (first form)))
          form)))
    (catch Exception _ nil)))

(defn- read-ns-sym
  "The namespace symbol of a file's (ns ...) form, or nil."
  [file]
  (some-> (read-ns-form file) second))

(defn- scan-ns-files
  "Map namespace symbol → file for every .clj file under DIR with a
   (ns ...) form. Follows symlinks — extension dirs are commonly installed
   as symlinks into a dotfiles/repo checkout, and the glob walk skips them
   by default (leaving the entry's requires unresolvable)."
  [dir]
  (reduce (fn [acc f]
            (let [f (io/file (str f))]
              (if-let [ns-sym (read-ns-sym f)]
                (assoc acc ns-sym f)
                acc)))
          {}
          (fs/glob dir "**/*.clj" {:follow-links true})))

(defn- resolve-extension
  "Resolve PATH into {:name str :entry io.File}. A directory must contain
   extension.edn {:name :entry} — the manifest lists only the initial
   namespace; a plain file is the entry itself."
  [path]
  (let [f (io/file path)]
    (if (.isDirectory f)
      (let [manifest-file (io/file f "extension.edn")]
        (when-not (.exists manifest-file)
          (throw (ex-info (str "Extension dir " path " has no extension.edn")
                          {:path path})))
        (let [m (edn/read-string (slurp manifest-file))
              entry (io/file f (:entry m))]
          (when-not (and (:entry m) (.exists entry))
            (throw (ex-info (str "extension.edn :entry not found: " (:entry m))
                            {:path path :manifest m})))
          {:name (or (:name m) (fs/file-name f))
           :entry entry}))
      {:name (fs/file-name f)
       :entry f})))

(defn- deps-of-dir
  "The extension dir's :deps map from deps.edn, or nil."
  [dir]
  (let [f (io/file (str dir) "deps.edn")]
    (when (.exists f)
      (:deps (edn/read-string (slurp f))))))

(defn- ns-clause
  "The (:require ...) / (:use ...) / (:require-macros ...) reference form of
   an ns form, or nil (ns forms: (ns name docstring? attr-map? & refs))."
  [ns-form clause-key]
  (some #(when (and (seq? %) (= clause-key (first %))) %)
        (filter #(not (or (string? %) (map? %))) (nnext ns-form))))

(defn- require-libspec-libs
  "The library symbols of an ns :require / :require-macros / :use clause
   (each libspec is a bare symbol or a [lib ...] vector)."
  [clause]
  (keep (fn [spec]
          (cond
            (symbol? spec) spec
            (and (vector? spec) (seq spec) (symbol? (first spec))) (first spec)
            :else nil))
        clause))

(defn- validate-entry-requires!
  "Fail fast with an actionable error when the entry ns form requires a
   kmet.* namespace outside the shared set (kmet.extension + kmet.tui.* +
   kmet.libs.*). Without this the error would be silent: sci's require
   machinery NPEs on a load-fn failure and swallows the original exception."
  [ext-name ns-form tui-namespaces libs-namespaces]
  (doseq [clause-key [:require :require-macros :use]
          lib (require-libspec-libs (ns-clause ns-form clause-key))]
    (let [s (str lib)]
      (cond
        (str/starts-with? s "kmet.tui.")
        (when-not (contains? tui-namespaces lib)
          (throw (ex-info
                  (str "Extension " ext-name " requires " lib
                       " — not part of the kmet.tui.* library shared with extensions")
                  {:extension ext-name :ns lib})))

        (= s "kmet.app.ui.tool-renderers")
        (when-not (contains? tui-namespaces lib)
          (throw (ex-info
                  (str "Extension " ext-name " requires " lib
                       " — not part of the shared renderer library surface")
                  {:extension ext-name :ns lib})))

        (str/starts-with? s "kmet.libs.")
        (when-not (contains? libs-namespaces lib)
          (throw (ex-info
                  (str "Extension " ext-name " requires " lib
                       " — not part of the kmet.libs.* library shared with extensions")
                  {:extension ext-name :ns lib})))

        (and (str/starts-with? s "kmet.")
             (not= lib 'kmet.extension))
        (throw (ex-info
                (str "Extension " ext-name " requires " lib
                     " — extensions may depend only on kmet.extension, kmet.tui.* and kmet.libs.*")
                {:extension ext-name :ns lib}))))))

(defn- shared-tui-namespaces
  "The set of TUI and supported renderer namespace symbols currently loaded
   (what the context injection shares with extensions)."
  []
  (set (keep (fn [ns-obj]
               (let [n (str (ns-name ns-obj))]
                 (when (or (str/starts-with? n "kmet.tui.")
                           (= n "kmet.app.ui.tool-renderers"))
                   (ns-name ns-obj))))
             (all-ns))))

(defn- shared-libs-namespaces
  "The set of kmet.libs.* namespace symbols currently loaded (what the
   context injection shares with extensions)."
  []
  (set (keep (fn [ns-obj]
               (let [n (str (ns-name ns-obj))]
                 (when (str/starts-with? n "kmet.libs.")
                   (ns-name ns-obj))))
             (all-ns))))

(def ^:private bundled-artifacts
  "Artifacts babashka ships (clojure + spec are always bundled) — excluded
   from extension closures, matching bb's add-deps classpath-overrides."
  #{"org.clojure/clojure"
    "org.clojure/spec.alpha"
    "org.clojure/core.specs.alpha"})

(defn- bundled-artifact?
  "True when ENTRY is a jar of one of the artifacts babashka ships (clojure
   + spec are always bundled), which must not be served to extension
   contexts — the SCI-incompatible Maven copies would be evaluated instead
   of bb's bundled ports. Matches the m2 layout: only the group is
   slash-munged, the artifact name keeps its dots (org.clojure/spec.alpha
   lives at repository/org/clojure/spec.alpha/)."
  [entry]
  (some (fn [ga]
          (let [[g a] (str/split ga #"/" 2)]
            (str/includes? entry (str "repository/" (str/replace g "." "/") "/" a "/"))))
        bundled-artifacts))

(defn- closure-jars
  "The complete transitive jar set for DEPS-MAP, computed in-process via
   borkdude.deps (the tools.deps port) — no subprocess, no global classpath
   changes, nothing written outside ~/.m2. Resolution failures throw
   (borkdude.deps' default *exit-fn* would kill the process)."
  [deps-map]
  (let [cp (with-out-str
             (binding [*print-namespace-maps* false
                       bdeps/*exit-fn* (fn [{:keys [message]}]
                                         (throw (ex-info (or message "deps resolution failed")
                                                         {:deps deps-map})))]
               (bdeps/-main "-Srepro" "-Spath"
                            "-Sdeps" (pr-str {:deps deps-map
                                              :mvn/repos {"clojars" {:url "https://repo.clojars.org/"}}})
                            "-Sdeps-file" "__kmet_no_deps__.edn")))]
    (->> (str/split (str/trim cp) (re-pattern (System/getProperty "path.separator")))
         (filter #(or (str/includes? % ".m2") (str/includes? % ".gitlibs")))
         (remove bundled-artifact?)
         vec)))

(defonce ^:private jars-cache (atom {}))

(defn- jars-for
  "The jar set for DEPS-MAP, cached by the map across loads (reloads and
   extensions sharing the same deps reuse it; a deps.edn change is a new
   key and re-resolves)."
  [deps-map]
  (let [key (pr-str deps-map)]
    (or (get @jars-cache key)
        (let [jars (closure-jars deps-map)]
          (swap! jars-cache assoc key jars)
          jars))))

(defn- make-deps-resolver
  "Memoized per-extension closure resolver: resolves the extension's jar
   set on first library require (via jars-for), records it on the record's
   :jars (for introspection), reuses it after. nil when the extension has
   no deps.edn."
  [deps-map jars-atom]
  (when deps-map
    (let [resolved (volatile! nil)]
      (fn []
        (or @resolved
            (let [jars (jars-for deps-map)]
              (reset! jars-atom jars)
              (vreset! resolved jars)))))))

(defn- ns-path
  "The classpath path for NS-SYM: namespace-munged (dashes → underscores),
   dots as slashes — matching how jars and source dirs store files."
  [ns-sym]
  (str/replace (namespace-munge (str ns-sym)) "." "/"))

(defn- jar-source
  "The source of NS-SYM inside JAR-PATH, or nil."
  [jar-path ns-sym]
  (let [jar (java.util.jar.JarFile. jar-path)
        base (ns-path ns-sym)]
    (try
      (let [entry (or (.getJarEntry jar (str base ".cljc"))
                      (.getJarEntry jar (str base ".clj"))
                      (.getJarEntry jar (str base ".bb")))]
        (when entry
          (with-open [is (.getInputStream jar entry)]
            (slurp is))))
      (finally (.close jar)))))

(defn- resource-source
  "The source of NS-SYM from the classpath, or nil."
  [ns-sym]
  (let [base (ns-path ns-sym)]
    (some (fn [ext] (when-let [r (io/resource (str base ext))]
                      {:file (str r) :source (slurp r)}))
          [".cljc" ".clj" ".bb"])))

(defn- make-load-fn
  "Per-extension namespace resolver, evaluated inside the extension's
   context: own files, declared deps (closure resolved lazily on first
   library require), then bb-bundled classpath namespaces. kmet.* beyond
   the contract and undeclared non-bundled libraries are rejected with
   actionable errors — extensions must depend only on kmet.extension."
  [ext-name ns-files deps-resolver]
  (fn [{:keys [namespace]}]
    (or (when-let [f (get ns-files namespace)]
          {:file (str f) :source (slurp f)})
        (when-let [jars (and deps-resolver (deps-resolver))]
          (some (fn [j] (when-let [s (jar-source j namespace)]
                          {:file (str j) :source s}))
                jars))
        (when-not (str/starts-with? (str namespace) "kmet.")
          (resource-source namespace))
        (throw (ex-info
                (cond
                  (or (str/starts-with? (str namespace) "kmet.tui.")
                      (= (str namespace) "kmet.app.ui.tool-renderers"))
                  (str "Extension " ext-name " requires " namespace
                       " — the shared TUI/renderer library is shared by reference and was"
                       " not loaded when this context was built")

                  (str/starts-with? (str namespace) "kmet.libs.")
                  (str "Extension " ext-name " requires " namespace
                       " — the kmet.libs.* library is shared by reference and was"
                       " not loaded when this context was built")

                  (str/starts-with? (str namespace) "kmet.")
                  (str "Extension " ext-name " requires " namespace
                       " — extensions may depend only on kmet.extension, kmet.tui.* and kmet.libs.*")

                  :else
                  (str "Extension " ext-name " requires " namespace
                       " — not declared in deps.edn and not a babashka-bundled library"))
                {:extension ext-name :ns namespace})))))

(def ^:private spec-port-namespaces
  "bb-bundled clojure.spec ports (spec.alpha and, transitively, its
   spec.gen.alpha / core.specs.alpha deps). bb does not preload them at
   startup (unlike tools.reader / rewrite-clj), and the Maven copies fail
   under SCI — spec.gen.alpha's locking2 macro expands to
   monitor-enter/monitor-exit, which SCI's core lacks — so they are
   required here (bb serves its own ports) and injected by reference into
   extension contexts by the all-ns scan in build-context-namespaces.
   Extensions (e.g. cljfmt.config) get a working clojure.spec.alpha
   without deps.edn pins."
  '[clojure.spec.alpha])

(defn- create-context
  "Build the isolated sci context for one extension: full bb classes and
   imports, shared global namespaces (contract + builtins + the kmet.tui.*
   TUI library + the kmet.libs.* library layer, required first so they
   exist for the injection), and the per-extension load-fn that checks deps
   — own files, declared deps (resolved lazily on first library require),
   bb-bundled namespaces, with actionable errors for everything else."
  [ext-name ns-files deps-resolver]
  (apply require (concat tui-library-namespaces libs-library-namespaces
                         spec-port-namespaces))
  (sci/init {:classes context-classes
             :imports bb-imports
             :features #{:bb :clj}
             :namespaces (build-context-namespaces)
             :load-fn (make-load-fn ext-name ns-files deps-resolver)}))

(defn- eval-forms!
  "Evaluate every top-level form of FILE in CTX. *ns* is bound around the
   whole eval so sci's ns handling cannot leak a namespace change into kmet
   (a per-form binding would reset sci's current-ns and break alias
   resolution between forms)."
  [ctx file]
  (binding [*ns* (or (find-ns 'user) *ns*)]
    (with-open [r (java.io.PushbackReader. (io/reader file))]
      (loop [form (read r false ::eof)]
        (when-not (= ::eof form)
          (sci/eval-form ctx form)
          (recur (read r false ::eof)))))))

(defn- extension-var
  "The value of VAR-NAME in ENTRY-NS of EXT's context, or nil."
  [ext entry-ns var-name]
  (when-let [ctx @(:ctx ext)]
    (get-in @(:env ctx) [:namespaces entry-ns var-name])))

(defn load-extension!
  "Load a single extension from PATH (.clj file or dir with extension.edn).
   Each extension evaluates in its own isolated context; deps.edn jars are
   served only to that context, so different extensions may pin different
   versions of the same library. Calls the extension's init with its api.
   On failure everything is rolled back and {:extension nil :path PATH
   :error MSG} is returned (PATH names what failed — the result map has no
   extension name to report)."
  [path]
  (let [f (io/file path)
        {:keys [name entry]} (resolve-extension path)
        dir (if (fs/directory? f) f (fs/parent f))
        ext (map->Extension
             {:name name
              :path (str (fs/canonicalize f))
              :entry-ns (atom nil)
              :ctx (atom nil)
              :jars (atom [])
              :api (atom nil)
              :deregister-fns (atom [])
              :initialized? (atom false)})]
    (try
      (let [deps (when (fs/directory? f) (deps-of-dir dir))
            ns-files (when (fs/directory? f) (scan-ns-files (str f)))
            ctx (create-context name (or ns-files {}) (make-deps-resolver deps (:jars ext)))
            ;; fail fast on forbidden/misspelled kmet.* requires — sci's
            ;; require machinery swallows the load-fn error into an NPE
            _ (validate-entry-requires! name (read-ns-form entry)
                                        (shared-tui-namespaces)
                                        (shared-libs-namespaces))]
        (doseq [lib (keys deps)]
          (when (contains? bb-bundled-libs (str lib))
            (binding [*out* *err*]
              (println "Warning: extension" (:name ext) "pins" lib
                       "which babashka bundles — the Maven copy may not run;"
                       "omit it from deps.edn to use the bundled version."))))
        (reset! (:ctx ext) ctx)
        (eval-forms! ctx entry)
        (let [ns-sym (read-ns-sym entry)
              _ (when-not ns-sym
                  (throw (ex-info (str "Extension " (:name ext)
                                       " file does not start with (ns ...)")
                                  {:path path})))
              init-var (extension-var ext ns-sym 'init)
              _ (when-not init-var
                  (throw (ex-info (str "Extension " (:name ext)
                                       " does not define an init fn")
                                  {:path path})))]
          (reset! (:entry-ns ext) ns-sym)
          (let [api (create-extension-api ext)]
            (reset! (:api ext) api)
            (init-var api)
            (reset! (:initialized? ext) true))))
      (swap! extensions conj ext)
      {:extension (:name ext) :error nil}
      (catch Exception e
        (unload-extension! ext)
        {:extension nil
         :path path
         :error (or (ex-message e)
                    (str "load failed: " (.getName (class e))))}))))

(defn unload-extension!
  "Unload an extension: shutdown (if initialized), deregister everything it
   registered, then drop its isolated context — namespaces and jars become
   unreachable, nothing global is touched. Expects the Extension record
   (from the registry / reload-extensions!); nil is a no-op — the load
   result map carries only its :extension name, and (deref nil) would
   otherwise surface as a cryptic NPE."
  [ext]
  (when ext
    (when (and @(:initialized? ext) @(:entry-ns ext))
      (when-let [shutdown (extension-var ext @(:entry-ns ext) 'shutdown)]
        (try (shutdown @(:api ext))
             (catch Exception e
               (binding [*out* *err*]
                 (println "Warning: extension shutdown error:" (ex-message e)))))))
    (doseq [f @(:deregister-fns ext)]
      (try (f) (catch Exception _)))
    (reset! (:ctx ext) nil)
    (reset! (:jars ext) [])
    (swap! extensions (fn [exts] (remove #(identical? % ext) exts)))
    nil))

(defn unload-all-extensions!
  "Unload every loaded extension (reverse order)."
  []
  (doseq [ext (reverse @extensions)]
    (unload-extension! ext))
  nil)

(defn get-loaded-extensions
  "Loaded extensions as {:name str :path str :entry-ns symbol
   :extension-dir str} maps (extension-dir = the extension's own directory)."
  []
  (mapv (fn [ext] {:name (:name ext) :path (:path ext)
                   :entry-ns @(:entry-ns ext)
                   :extension-dir (extension-dir-of ext)})
        @extensions))

(defn extension-jars
  "Jar paths of the named loaded extension (its deps.edn closure), or nil."
  [name]
  (some-> (first (filter #(= name (:name %)) @extensions))
          :jars deref))

(defn discover-resources!
  "pi: extendResourcesFromExtensions — fire the :resources-discover event
   (payload {:cwd :reason}) after :session-start and apply every handler's
   contributed resource paths: :skill-paths / :prompt-paths load into the
   skills/prompts registries, :theme-paths into the theme store (pi:
   resourceLoader.extendResources → updateSkills/Prompts/ThemesFromPaths;
   paths are directories). ALL handler results are collected (pi collects
   each handler's contribution — see emit-event-collect!). Paths already
   applied since the last extension reload are skipped — discovery fires
   after every session-start (pi re-scans with mergePaths dedup; kmet's
   prompt loader would duplicate without the tracking). A throwing handler
   is skipped (its paths are lost). Returns the collected
   {:skill-paths [...] :prompt-paths [...] :theme-paths [...]}."
  [reason]
  (let [results (event-bus/emit-event-collect!
                 {:type :resources-discover
                  :cwd (str (fs/cwd))
                  :reason reason})
        paths (reduce (fn [acc r]
                        (cond-> acc
                          (seq (:skill-paths r))
                          (update :skill-paths into (:skill-paths r))
                          (seq (:prompt-paths r))
                          (update :prompt-paths into (:prompt-paths r))
                          (seq (:theme-paths r))
                          (update :theme-paths into (:theme-paths r))))
                      {:skill-paths [] :prompt-paths [] :theme-paths []}
                      results)]
    (doseq [p (:skill-paths paths)]
      (when-not (contains? @applied-resource-paths p)
        (skills/load-skills-from-dir p)
        (swap! applied-resource-paths conj p)))
    (doseq [p (:prompt-paths paths)]
      (when-not (contains? @applied-resource-paths p)
        (prompts/load-prompt-templates-from-dir p)
        (swap! applied-resource-paths conj p)))
    (doseq [p (:theme-paths paths)]
      (when-not (contains? @applied-resource-paths p)
        (theme/load-themes-from-dir p)
        (swap! applied-resource-paths conj p)))
    paths))

(defn clear-extensions!
  "Unload all extensions (used by /reload and tests)."
  []
  ;; extension-contributed resources are re-discovered on the next
  ;; session-start after a reload (the loaders are cleared too)
  (reset! applied-resource-paths #{})
  (unload-all-extensions!))

(defn load-extensions-from-dir
  "Load all extensions in DIR (a container): top-level .clj files and
   subdirectories containing extension.edn. Returns the list of per-extension
   {:extension name :error} results; failures are also printed as warnings."
  [dir]
  (let [d (io/file dir)]
    (when (fs/directory? d)
      (mapv (fn [entry]
              (let [path (str entry)
                    result (cond
                             (and (fs/regular-file? entry) (str/ends-with? path ".clj"))
                             (load-extension! path)

                             (fs/directory? entry)
                             ;; only directories with an extension.edn manifest are
                             ;; extensions — an extension's own src/ subdirs are
                             ;; loaded via the entry's requires, not here
                             (if (fs/exists? (io/file (str entry) "extension.edn"))
                               (load-extension! path)
                               nil)

                             :else nil)]
                (when (and result (:error result))
                  (binding [*out* *err*]
                    (println "Warning: Failed to load extension" path ":"
                             (:error result))))
                result))
            (sort-by str (fs/list-dir d))))))

(defn reload-extensions!
  "Unload all loaded extensions, then load from DIRS. Returns the list of
   per-extension {:extension name :error} results. Extension UI (widgets,
   custom footer/header/editor, dialogs) is reset first (pi: reload calls
   resetExtensionUI before reloading)."
  [dirs]
  (ui-call :reset)
  (unload-all-extensions!)
  (mapcat load-extensions-from-dir dirs))
