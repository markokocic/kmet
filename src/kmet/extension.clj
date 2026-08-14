(ns kmet.extension
  "The kmet extension contract — the ONLY namespace an extension may depend
   on (psi: the extension-api contract; pi: the `pi` object passed to the
   extension factory).

   An extension is a Clojure namespace defining:
     (defn init [api] ...)     — required; register everything here
     (defn shutdown [api] ...) — optional; teardown on unload/reload
   api is a map of runtime-bound capabilities. The wrapper fns in this
   namespace take api as their first argument and dispatch to the runtime
   implementation, so extensions never require kmet internals.

   Layout: extensions are either single .clj files, or directories
   containing an extension.edn manifest:
     {:name \"my-ext\" :entry \"src/my_ext.clj\" :files [\"src/util.clj\"]}
   — :files load first (the extension's own source dependencies), then
   :entry. External library deps are not loadable at runtime in Babashka
   (the classpath is fixed at startup); add them to kmet's deps.edn instead.

   Lifecycle: loaded at startup (and via /reload), reloadable and unloadable
   at runtime. Unload calls shutdown (if defined), then unregisters
   everything the extension registered. Registration calls therefore return
   deregister fns where meaningful (e.g. on-event).

   All api fns are safe to call at any time — the ui/agent-control ones
   dispatch through a runtime registry and are inert before the interactive
   layout exists (and in headless/print mode).")

;; ─── API key docs ─────────────────────────────────────────────────────────
;; The api map passed to init/shutdown carries:
;;   :extension-name, :extension-path, :extension-dir   — identity/context
;;   :register-command! :unregister-command! :get-commands
;;   :register-tool! :unregister-tool! :get-all-tools
;;   :get-active-tools :set-active-tools
;;   :on-event :emit-event!                             — event bus
;;   :on-input :on-before-agent-start                   — hooks
;;   :on-tool-call :on-tool-result                      — tool transforms
;;   :register-flag! :get-flag
;;   :register-entry-renderer! :register-message-renderer!
;;   :set-model :get-thinking-level :set-thinking-level :send-user-message
;;   :exec
;;   :ui      — map of UI capabilities (dialogs, status, widgets, editor,
;;              theme, working indicator, terminal input, notify) — inert
;;              before the layout exists / in headless mode
;;   :models  — provider/model facades (get-all, find, auth status, …)
;;   :session — live session (append entry/message, labels, name)

;; ─── Wrappers (extensions call these; api is the map from init) ───────────

(defn register-command! [api cmd] ((:register-command! api) cmd))
(defn unregister-command! [api name] ((:unregister-command! api) name))
(defn get-commands [api] ((:get-commands api)))

(defn register-tool! [api tool] ((:register-tool! api) tool))
(defn unregister-tool! [api name] ((:unregister-tool! api) name))
(defn get-all-tools [api] ((:get-all-tools api)))
(defn get-active-tools [api] ((:get-active-tools api)))
(defn set-active-tools [api names] ((:set-active-tools api) names))

(defn on-event
  "Register a handler for an event type (e.g. :session-start, :agent-end,
   :message-end). Returns a deregister fn."
  [api event-type handler]
  ((:on-event api) event-type handler))
(defn emit-event! [api event] ((:emit-event! api) event))

(defn on-input [api hook] ((:on-input api) hook))
(defn on-before-agent-start [api hook] ((:on-before-agent-start api) hook))
(defn on-tool-call [api hook] ((:on-tool-call api) hook))
(defn on-tool-result [api hook] ((:on-tool-result api) hook))

(defn register-flag! [api name & [opts]] ((:register-flag! api) name opts))
(defn get-flag [api name] ((:get-flag api) name))

(defn register-entry-renderer! [api custom-type renderer]
  ((:register-entry-renderer! api) custom-type renderer))
(defn register-message-renderer! [api custom-type renderer]
  ((:register-message-renderer! api) custom-type renderer))

(defn set-model [api model] ((:set-model api) model))
(defn get-thinking-level [api] ((:get-thinking-level api)))
(defn set-thinking-level [api level] ((:set-thinking-level api) level))
(defn send-user-message [api text & [opts]] ((:send-user-message api) text opts))
(defn exec [api command args & [opts]] ((:exec api) command args opts))

(defn ui
  "The UI capability map (dialogs/status/widgets/editor/theme/...). Calls are
   inert before the layout exists and in headless mode."
  [api]
  (:ui api))

;; ─── UI wrappers (dispatch to (:ui api) capabilities) ────────────────────

(defn ui-select [api title options & [_opts]] ((:select (ui api)) title options))
(defn ui-confirm [api title message & [_opts]] ((:confirm (ui api)) title message))
(defn ui-input [api title placeholder & [_opts]] ((:input (ui api)) title placeholder))
(defn ui-notify [api message & [type]] ((:notify (ui api)) message type))
(defn ui-custom [api factory & [{:keys [overlay] :as _opts}]]
  ((:custom (ui api)) factory {:overlay overlay}))
(defn ui-set-status [api key text] ((:set-status (ui api)) key text))
(defn ui-set-widget [api key content & [{:keys [placement]}]]
  ((:set-widget (ui api)) key content {:placement placement}))
(defn ui-set-footer [api factory] ((:set-footer (ui api)) factory))
(defn ui-set-header [api factory] ((:set-header (ui api)) factory))
(defn ui-set-title [api title] ((:set-title (ui api)) title))
(defn ui-set-editor-text [api text] ((:set-editor-text (ui api)) text))
(defn ui-get-editor-text [api] ((:get-editor-text (ui api))))
(defn ui-paste-to-editor [api text] ((:paste-to-editor (ui api)) text))
(defn ui-set-working-indicator [api options] ((:set-working-indicator (ui api)) options))
(defn ui-set-working-message [api message] ((:set-working-message (ui api)) message))
(defn ui-set-working-visible [api visible?] ((:set-working-visible (ui api)) visible?))
(defn ui-set-hidden-thinking-label [api label] ((:set-hidden-thinking-label (ui api)) label))
(defn ui-set-editor-component [api factory] ((:set-editor-component (ui api)) factory))
(defn ui-add-autocomplete-provider [api factory] ((:add-autocomplete-provider (ui api)) factory))
(defn ui-get-theme [api] ((:get-theme (ui api))))
(defn ui-get-all-themes [api] ((:get-all-themes (ui api))))
(defn ui-set-theme [api theme-or-name] ((:set-theme (ui api)) theme-or-name))
(defn ui-get-tools-expanded [api] ((:get-tools-expanded (ui api))))
(defn ui-set-tools-expanded [api expanded?] ((:set-tools-expanded (ui api)) expanded?))
(defn ui-on-terminal-input [api handler] ((:on-terminal-input (ui api)) handler))

(defn models
  "Provider/model facades: :get-all :get-available :find :has-configured-auth
   :get-provider-auth-status :get-api-key-and-headers."
  [api]
  (:models api))

(defn session
  "The live session facades: :append-entry! :append-message! :get-entries
   :set-label! :get-label :set-name! :get-name."
  [api]
  (:session api))

;; ─── Nullable API (test fixture) ──────────────────────────────────────────
;; Enables narrow tests of an extension's init/shutdown without the kmet
;; runtime: load the extension against a fake api and assert what it
;; registered (psi: create-nullable-extension-api).

(defn create-nullable-api
  "A test-fixture api that captures registrations into a state atom.
   Returns {:api ... :state atom}. State shape:
     {:commands {name cmd} :tools {name tool}
      :handlers {event-type [handler ...]} :flags {name opts}
      :entry-renderers {custom-type renderer} :message-renderers {custom-type renderer}
      :tool-call-hooks [...] :tool-result-hooks [...]
      :input-hooks [...] :before-agent-start-hooks [...]
      :ui-calls [args...] :emitted [events...] :model-calls [...]}
   Deregister fns remove the corresponding registrations (unload replay)."
  []
  (let [state (atom {:commands {} :tools {} :handlers {}
                     :flags {} :entry-renderers {} :message-renderers {}
                     :tool-call-hooks [] :tool-result-hooks []
                     :input-hooks [] :before-agent-start-hooks []
                     :ui-calls [] :emitted [] :model-calls []})
        api {:extension-name "nullable" :extension-path "test" :extension-dir "test"
             :register-command! (fn [cmd]
                                  (swap! state assoc-in [:commands (:name cmd)] cmd)
                                  (fn [] (swap! state update :commands dissoc (:name cmd))))
             :unregister-command! (fn [name] (swap! state update :commands dissoc name))
             :get-commands (fn [] (vals (:commands @state)))
             :register-tool! (fn [tool]
                               (swap! state assoc-in [:tools (:name tool)] tool)
                               (fn [] (swap! state update :tools dissoc (:name tool))))
             :unregister-tool! (fn [name] (swap! state update :tools dissoc name))
             :get-all-tools (fn [] (vals (:tools @state)))
             :get-active-tools (fn [] (keys (:tools @state)))
             :set-active-tools (fn [names] (swap! state assoc :active-tools names))
             :on-event (fn [event-type handler]
                         (swap! state update-in [:handlers event-type] (fnil conj []) handler)
                         (fn [] (swap! state update-in [:handlers event-type]
                                       (fn [hs] (remove #(identical? % handler) hs)))))
             :emit-event! (fn [event] (swap! state update :emitted conj event))
             :on-input (fn [hook]
                         (swap! state update :input-hooks conj hook)
                         (fn [] (swap! state update :input-hooks
                                       (fn [hs] (remove #(identical? % hook) hs)))))
             :on-before-agent-start (fn [hook]
                                      (swap! state update :before-agent-start-hooks conj hook)
                                      (fn [] (swap! state update :before-agent-start-hooks
                                                    (fn [hs] (remove #(identical? % hook) hs)))))
             :on-tool-call (fn [hook]
                             (swap! state update :tool-call-hooks conj hook)
                             (fn [] (swap! state update :tool-call-hooks
                                           (fn [hs] (remove #(identical? % hook) hs)))))
             :on-tool-result (fn [hook]
                               (swap! state update :tool-result-hooks conj hook)
                               (fn [] (swap! state update :tool-result-hooks
                                             (fn [hs] (remove #(identical? % hook) hs)))))
             :register-flag! (fn [name opts]
                               (swap! state assoc-in [:flags name] opts)
                               (fn [] (swap! state update :flags dissoc name)))
             :get-flag (fn [name] (get-in @state [:flags name]))
             :register-entry-renderer! (fn [custom-type renderer]
                                         (swap! state assoc-in [:entry-renderers custom-type] renderer)
                                         (fn [] (swap! state update :entry-renderers dissoc custom-type)))
             :register-message-renderer! (fn [custom-type renderer]
                                           (swap! state assoc-in [:message-renderers custom-type] renderer)
                                           (fn [] (swap! state update :message-renderers dissoc custom-type)))
             :set-model (fn [model]
                          (swap! state update :model-calls conj [:set-model model])
                          (boolean model))
             :get-thinking-level (fn [] :off)
             :set-thinking-level (fn [level] (swap! state assoc :thinking level))
             :send-user-message (fn [text opts]
                                  (swap! state update :ui-calls conj [:send-user-message text opts]))
             :exec (fn [command args opts]
                     (swap! state update :ui-calls conj [:exec command args opts]))
             :ui (into {} (for [[k _] {:select 1 :confirm 1 :input 1 :notify 1 :custom 1
                                       :set-status 1 :set-widget 1 :set-footer 1 :set-header 1
                                       :set-editor-text 1 :get-editor-text 1 :paste-to-editor 1
                                       :set-theme 1 :get-theme 1 :get-all-themes 1
                                       :set-working-indicator 1 :set-working-message 1
                                       :set-working-visible 1 :on-terminal-input 1
                                       :set-tools-expanded 1 :get-tools-expanded 1}]
                            [k (fn [& args] (swap! state update :ui-calls conj (into [k] args)))]))
             :models {:get-all (fn [] (swap! state update :model-calls conj [:get-all]) [])
                      :get-available (fn [] [])
                      :find (fn [provider model-id]
                              (swap! state update :model-calls conj [:find provider model-id])
                              nil)
                      :has-configured-auth (fn [model] (boolean model))
                      :get-model-auth (fn [_model] {:configured false :source nil})
                      :get-provider-auth-status (fn [_provider] {:configured false :source nil})
                      :get-api-key-and-headers (fn [_model] {:ok false :error "nullable"})}
             :session {:append-entry! (fn [custom-type data]
                                        (swap! state update :ui-calls conj [:append-entry custom-type data]))
                       :append-message! (fn [custom-type _content display _details]
                                          (swap! state update :ui-calls conj [:append-message custom-type display]))
                       :get-entries (fn [_custom-type] [])
                       :set-label! (fn [id label] (swap! state assoc-in [:labels id] label))
                       :get-label (fn [id] (get-in @state [:labels id]))
                       :set-name! (fn [name] (swap! state assoc :session-name name))
                       :get-name (fn [] (:session-name @state))}}]
    {:api api :state state}))
