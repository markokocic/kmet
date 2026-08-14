(ns kmet.app.extensions
  "Extension runtime: discovers, loads, reloads and unloads Clojure
   extensions. The extension contract lives in kmet.extension — extensions
   depend only on that namespace; this runtime wires the api capabilities to
   the registries and the interactive/loop surfaces.

   An extension is a .clj file defining (defn init [api]) in its namespace,
   or a directory containing an extension.edn manifest:
     {:name \"my-ext\" :entry \"src/my_ext.clj\" :files [\"src/util.clj\"]}
   :files load first (the extension's source deps), then :entry. Optional
   (defn shutdown [api]) runs on unload, which also unregisters everything
   the extension registered (each registration tracks its deregister fn).

   Extensions load at startup (core.clj), are re-loaded by /reload, and can
   be unloaded/reloaded at runtime via unload-extension! /
   reload-extensions!."
  (:require [clojure.java.io :as io]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [babashka.fs :as fs]
            [babashka.process :as proc]
            [kmet.ai.models :as models]
            [kmet.app.commands :as commands]
            [kmet.app.event-bus :as event-bus]
            [kmet.app.session :as session]
            [kmet.app.tools.core :as tools]))

;; ─── Extension records ────────────────────────────────────────────────────
(defrecord Extension [name path entry-ns loaded-ns api deregister-fns initialized?])

;; ─── Registries (the storage; api capabilities wire into these) ──────────
(defonce ^:private extensions (atom []))
(defonce ^:private input-hooks (atom []))
(defonce ^:private before-agent-start-hooks (atom []))
(defonce ^:private entry-renderers (atom {}))
(defonce ^:private message-renderers (atom {}))
(defonce ^:private tool-call-hooks (atom []))
(defonce ^:private tool-result-hooks (atom []))
(defonce ^:private flags (atom {}))
(defonce ^:private cli-flags (atom {}))
(defonce ^:private ui-registry (atom {}))
(defonce ^:private session-atom (atom nil))
(defonce ^:private context-sink-atom (atom nil))
(defonce ^:private entry-sink-atom (atom nil))

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

(defn ui-select [title options & [_opts]] (ui-call :select title options))
(defn ui-confirm [title message & [_opts]] (ui-call :confirm title message))
(defn ui-input [title placeholder & [_opts]] (ui-call :input title placeholder))
(defn ui-notify [message & [type]] (ui-call :notify message type))
(defn ui-custom [factory & [{:keys [overlay overlay-options on-handle]}]]
  (ui-call :custom factory {:overlay overlay :overlay-options overlay-options :on-handle on-handle}))
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
(defn ui-get-theme [] (ui-call :get-theme))
(defn ui-get-all-themes [] (ui-call :get-all-themes))
(defn ui-set-theme [theme-or-name] (ui-call :set-theme theme-or-name))
(defn ui-get-tools-expanded [] (ui-call :get-tools-expanded))
(defn ui-set-tools-expanded [expanded?] (ui-call :set-tools-expanded expanded?))
(defn ui-reset! [] (ui-call :reset))

;; ─── Agent control (dispatches through the ui registry; extension api) ───

(defn set-model [model] (ui-call :set-model model))
(defn get-thinking-level [] (ui-call :get-thinking-level))
(defn set-thinking-level [level] (ui-call :set-thinking-level level) nil)
(defn send-user-message [text & [{:keys [deliver-as]}]] (ui-call :send-user-message text {:deliver-as deliver-as}) nil)
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
   is inert before the layout exists and in headless mode."
  []
  {:select ui-select
   :confirm ui-confirm
   :input ui-input
   :notify ui-notify
   :custom ui-custom
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
   :get-theme ui-get-theme
   :get-all-themes ui-get-all-themes
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
   :get-registered-provider-ids get-registered-provider-ids})

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
     :extension-dir (str (fs/parent (:path ext)))
     :register-command! (fn [cmd]
                          (commands/register-command! cmd)
                          (track (fn [] (commands/unregister-command! (:name cmd)))))
     :unregister-command! commands/unregister-command!
     :get-commands #(commands/get-commands)
     :register-tool! (fn [tool]
                       (tools/register-tool! tool)
                       (track (fn [] (tools/unregister-tool! (:name tool)))))
     :unregister-tool! tools/unregister-tool!
     :get-all-tools #(vals (tools/get-all-tools))
     :get-active-tools get-active-tools
     :set-active-tools set-active-tools
     :on-event (fn [event-type handler]
                 (let [dereg (event-bus/on-event event-type handler)]
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
     :exec exec
     :ui (api-ui)
     :models (api-models)
     :session (api-session)}))

;; ─── Discovery / loading / unloading ──────────────────────────────────────

(declare unload-extension!)

(defn- read-ns-sym
  "The namespace symbol of a file's (ns ...) form, or nil."
  [file]
  (with-open [rdr (java.io.PushbackReader. (io/reader file))]
    (let [form (read rdr)]
      (when (and (list? form) (= 'ns (first form)))
        (second form)))))

(defn- resolve-extension
  "Resolve PATH into {:name str :files [io.File ...]}. A directory must
   contain extension.edn {:name :entry :files}; a plain file is the entry
   itself."
  [path]
  (let [f (io/file path)]
    (if (.isDirectory f)
      (let [manifest-file (io/file f "extension.edn")]
        (when-not (.exists manifest-file)
          (throw (ex-info (str "Extension dir " path " has no extension.edn")
                          {:path path})))
        (let [m (edn/read-string (slurp manifest-file))
              entry (io/file f (:entry m))
              extra (mapv #(io/file f %) (:files m []))]
          (when-not (and (:entry m) (.exists entry))
            (throw (ex-info (str "extension.edn :entry not found: " (:entry m))
                            {:path path :manifest m})))
          (doseq [ef extra]
            (when-not (.exists ef)
              (throw (ex-info (str "extension.edn :files entry not found: " ef)
                              {:path path :manifest m}))))
          {:name (or (:name m) (fs/file-name f))
           :files (concat extra [entry])}))
      {:name (fs/file-name f)
       :files [f]})))

(defn- load-extension-files!
  "load-file each file (proper namespace loading) and record its ns."
  [ext files]
  (doseq [f files]
    (load-file (str f))
    (when-let [ns-sym (read-ns-sym f)]
      (swap! (:loaded-ns ext) conj ns-sym))))

(defn- unload-namespaces!
  "remove-ns every namespace the extension loaded (fresh reload)."
  [ext]
  (doseq [ns-sym @(:loaded-ns ext)]
    (when (find-ns ns-sym)
      (remove-ns ns-sym))))

(defn load-extension!
  "Load a single extension from PATH (.clj file or dir with extension.edn).
   Calls the extension's init with its api. On failure, everything is rolled
   back (unloaded) and {:extension nil :error msg} is returned."
  [path]
  (let [ext (map->Extension
             {:name (fs/file-name path)
              :path (str (fs/canonicalize (io/file path)))
              :entry-ns (atom nil)
              :loaded-ns (atom [])
              :api (atom nil)
              :deregister-fns (atom [])
              :initialized? (atom false)})]
    (try
      (let [{:keys [files]} (resolve-extension path)]
        (load-extension-files! ext files)
        (let [ns-sym (last @(:loaded-ns ext))
              _ (when-not ns-sym
                  (throw (ex-info (str "Extension " (:name ext)
                                       " file does not start with (ns ...)")
                                  {:path path})))
              init-var (ns-resolve (find-ns ns-sym) 'init)
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
        {:extension nil :error (ex-message e)}))))

(defn unload-extension!
  "Unload an extension: shutdown (if initialized), deregister everything it
   registered, remove its namespaces."
  [ext]
  (when @(:initialized? ext)
    (when-let [ns-sym @(:entry-ns ext)]
      (when-let [shutdown (ns-resolve (find-ns ns-sym) 'shutdown)]
        (try (shutdown @(:api ext))
             (catch Exception e
               (binding [*out* *err*]
                 (println "Warning: extension shutdown error:" (ex-message e))))))))
  (doseq [f @(:deregister-fns ext)]
    (try (f) (catch Exception _)))
  (unload-namespaces! ext)
  (swap! extensions (fn [exts] (remove #(identical? % ext) exts)))
  nil)

(defn unload-all-extensions!
  "Unload every loaded extension (reverse order)."
  []
  (doseq [ext (reverse @extensions)]
    (unload-extension! ext))
  nil)

(defn get-loaded-extensions
  "Loaded extensions as {:name str :path str} maps."
  []
  (mapv (fn [ext] {:name (:name ext) :path (:path ext)}) @extensions))

(defn clear-extensions!
  "Unload all extensions (used by /reload and tests)."
  []
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
                             ;; loaded via its manifest :files, not here
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
   per-extension {:extension name :error} results."
  [dirs]
  (unload-all-extensions!)
  (mapcat load-extensions-from-dir dirs))
