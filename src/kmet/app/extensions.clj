(ns kmet.app.extensions
  "Extension runtime: discovers, loads, reloads and unloads Clojure
   extensions. The extension contract lives in kmet.extension — extensions
   depend only on that namespace; this runtime wires the api capabilities to
   the registries and the interactive/loop surfaces.

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
            [kmet.app.commands :as commands]
            [kmet.app.event-bus :as event-bus]
            [kmet.app.session :as session]
            [kmet.app.tools.core :as tools]
            [kmet.extension]))

;; ─── Extension records ────────────────────────────────────────────────────
(defrecord Extension [name path entry-ns ctx jars api deregister-fns initialized?])

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

;; ─── Isolated extension contexts (sci) ───────────────────────────────────
;; Each extension evaluates inside its own sci context: a private namespace
;; registry plus a per-extension loader that serves (1) the extension's own
;; files, (2) the jars its deps.edn declares (the complete transitive
;; closure, resolved in-process via borkdude.deps), and (3) anything else on
;; the classpath. Global namespaces the extension may touch (kmet.extension,
;; clojure.*, babashka.*) are injected as shared references — never
;; re-evaluated, so kmet's registries are not duplicated. kmet.* namespaces
;; other than the contract are not served at all: re-evaluating kmet
;; internals in a context would create context-local copies of their
;; registries.

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
   whose Maven copies run fine (tools.cli, data.json, tools.reader, ...)
   are not listed — they resolve to declared versions normally."
  #{"cheshire/cheshire"
    "org.clojure/core.async"
    "org.clojure/core.cache"
    "org.clojure/core.memoize"
    "org.clojure/core.rrb-vector"
    "potemkin/potemkin"
    "ring/ring-core"
    "selmer/selmer"})

(defonce ^:private context-classes
  (into {} (map (fn [^Class c] [(symbol (.getName c)) {:class c}])
                (remove #(str/starts-with? (.getName ^Class %) "[")
                        (babashka.classes/all-classes)))))

(defonce ^:private context-namespaces
  (into {'kmet.extension (ns-interns 'kmet.extension)}
        (keep (fn [ns-obj]
                (let [n (str (ns-name ns-obj))]
                  (when (and (not (str/starts-with? n "sci."))
                             (not= n "clojure.core")
                             ;; bundled libraries (clojure.tools.cli, data.json, ...)
                             ;; are NOT injected — they resolve through the load-fn,
                             ;; so a declared Maven version wins over the bundled copy.
                             ;; (Only clojure.tools.* / clojure.data.*: the ones whose
                             ;; Maven versions actually run in bb. The adapted libs —
                             ;; core.async, cheshire, ... — stay injected: their Maven
                             ;; copies fail anyway, so the bundled copy is correct.)
                             (not (or (str/starts-with? n "clojure.tools.")
                                      (str/starts-with? n "clojure.data.")))
                             (or (str/starts-with? n "clojure.")
                                 (str/starts-with? n "babashka.")))
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
   (ns ...) form."
  [dir]
  (reduce (fn [acc f]
            (let [f (io/file (str f))]
              (if-let [ns-sym (read-ns-sym f)]
                (assoc acc ns-sym f)
                acc)))
          {}
          (fs/glob dir "**/*.clj")))

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

(def ^:private bundled-artifacts
  "Artifacts babashka ships (clojure + spec are always bundled) — excluded
   from extension closures, matching bb's add-deps classpath-overrides."
  #{"org.clojure/clojure"
    "org.clojure/spec.alpha"
    "org.clojure/core.specs.alpha"})

(defn- bundled-artifact?
  [entry]
  (some #(str/includes? entry (str "repository/" (str/replace % "." "/") "/"))
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
                            "-Sdeps" (pr-str {:deps deps-map})
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
        (when-let [jars (deps-resolver)]
          (some (fn [j] (when-let [s (jar-source j namespace)]
                          {:file (str j) :source s}))
                jars))
        (when-not (str/starts-with? (str namespace) "kmet.")
          (resource-source namespace))
        (throw (ex-info
                (if (str/starts-with? (str namespace) "kmet.")
                  (str "Extension " ext-name " requires " namespace
                       " — extensions may only depend on kmet.extension")
                  (str "Extension " ext-name " requires " namespace
                       " — not declared in deps.edn and not a babashka-bundled library"))
                {:extension ext-name :ns namespace})))))

(defn- create-context
  "Build the isolated sci context for one extension: full bb classes and
   imports, shared global namespaces, and the per-extension load-fn that
   checks deps — own files, declared deps (resolved lazily on first library
   require), bb-bundled namespaces, with actionable errors for everything
   else."
  [ext-name ns-files deps-resolver]
  (sci/init {:classes context-classes
             :imports bb-imports
             :features #{:bb :clj}
             :namespaces context-namespaces
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
   On failure everything is rolled back and {:extension nil :error msg} is
   returned."
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
            ctx (create-context name (or ns-files {}) (make-deps-resolver deps (:jars ext)))]
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
        {:extension nil :error (ex-message e)}))))

(defn unload-extension!
  "Unload an extension: shutdown (if initialized), deregister everything it
   registered, then drop its isolated context — namespaces and jars become
   unreachable, nothing global is touched."
  [ext]
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
  nil)

(defn unload-all-extensions!
  "Unload every loaded extension (reverse order)."
  []
  (doseq [ext (reverse @extensions)]
    (unload-extension! ext))
  nil)

(defn get-loaded-extensions
  "Loaded extensions as {:name str :path str :entry-ns symbol} maps."
  []
  (mapv (fn [ext] {:name (:name ext) :path (:path ext)
                   :entry-ns @(:entry-ns ext)})
        @extensions))

(defn extension-jars
  "Jar paths of the named loaded extension (its deps.edn closure), or nil."
  [name]
  (some-> (first (filter #(= name (:name %)) @extensions))
          :jars deref))

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
   per-extension {:extension name :error} results."
  [dirs]
  (unload-all-extensions!)
  (mapcat load-extensions-from-dir dirs))
