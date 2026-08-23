(ns kmet.tui.macros
  "Macros for TUI components.

   track! — reactive render cache: rewrites @atom reads in the render body to
   tracked derefs, so any atom change invalidates the cache automatically and
   setters need no manual invalidate call. It must be a macro because deref
   rewriting happens at compile time; it expands to a plain runtime call
   (track-render), so SCI-based callers only need the function, not the macro.

   defsetter / defgetter — component state accessor boilerplate: generate the
   (defn name [comp value] (reset! (:field comp) value)) skeleton that every
   component setter/getter repeats.")

;; ═══════════════════════════════════════════════════════════════════════════
;; track! — reactive cache (Reagent-style dependency tracking)
;; ═══════════════════════════════════════════════════════════════════════════

(def ^:dynamic *tracked*
  "During track! execution, a map of the atoms deref'd so far in the
   current reactive render pass, keyed by atom → value as read. nil outside
   a tracking scope."
  nil)

(def ^:dynamic *ratom-context*
  "The running reaction's capture frame ({:pending (atom #{}) :self ref}),
   bound by kmet.tui.reagent around reaction bodies. nil outside one.
   Lives here (not in reagent.clj) so tracked-deref can feed it without a
   require cycle — reagent sits above macros."
  nil)

(defn capture-deref!
  "Record REF in the active reaction's pending-dependency set, if a reaction
   is running. Called from tracked-deref; reactions and cursors additionally
   record themselves from their own deref implementations."
  [ref]
  (when-some [{:keys [pending self]} *ratom-context*]
    (when-not (identical? self ref)
      (swap! pending conj ref)))
  nil)

(defn tracked-deref
  "Deref wrapper used by track!. Records A in the active tracking map (when
   one is bound) with the value just read, then returns it. Only IRef
   instances (atoms, vars) are tracked — volatiles and delays can't take
   watches and are skipped. Also records A in the running reaction (when
   one is bound), so component render bodies are reactive under the DSL."
  [a]
  (let [v (deref a)]
    (when (and *tracked* (instance? clojure.lang.IRef a))
      (swap! *tracked* assoc a v))
    (capture-deref! a)
    v))

(defn- deref-form? [f]
  ;; Only the 1-arg (deref x) form — @x reads as (clojure.core/deref x).
  ;; Multi-arg deref (timeout variants) is left untouched.
  (and (seq? f)
       (symbol? (first f))
       (or (= 'clojure.core/deref (first f))
           (= 'deref (first f)))
       (= 2 (count f))))

(defn- rewrite-derefs
  "Replace every @atom / (deref atom) form in FORM with a tracked-deref call,
   so derefs during a track! render are recorded. Quoted forms are skipped
   (quoted deref symbols are data, not reads)."
  [form]
  (cond
    (or (symbol? form) (not (coll? form))) form
    (and (seq? form) (= 'quote (first form))) form
    (deref-form? form) (list 'kmet.tui.macros/tracked-deref (rewrite-derefs (second form)))
    (seq? form) (apply list (map rewrite-derefs form))
    (vector? form) (mapv rewrite-derefs form)
    (map? form) (into (empty form) (map (fn [[k v]] [(rewrite-derefs k) (rewrite-derefs v)]) form))
    (set? form) (into (empty form) (map rewrite-derefs form))
    :else form))

(defn- tracker-key
  "Stable per-component watch key. add-watch replaces an existing watch with
   the same key, so re-registration after re-renders is idempotent."
  [component]
  (keyword (str "track!" (System/identityHashCode component))))

(declare invalidate-cache)

(defn- component-cache-atom
  "The render cache field: :cache-atom (current) or legacy :cache (Text/Box)."
  [component]
  (or (:cache-atom component) (:cache component)))

(defn track-render
  "Runtime implementation of track!. Runs RENDER-FN within a tracking scope
   and caches the result under WIDTH. Returns the cached result while every
   tracked atom still holds the value it was read with — watches invalidate
   on actual value changes (equal-value resets are no-ops), and the cache
   records the values AS READ (not a post-body snapshot), so an atom that
   changed mid-render makes the stored values disagree with the current
   ones on the next hit check and forces a re-render.
   Requires COMPONENT to have a :cache-atom (or legacy :cache) field.
   A body that invalidates itself mid-run (a render fn calling :invalidate)
   is not cached — the next render re-runs it with the fresh state."
  [component width render-fn]
  (let [cache-atom (component-cache-atom component)
        cache @cache-atom]
    (if (and cache
             (= (:width cache) width)
             (every? (fn [[a v]]
                       (let [cur (deref a)]
                         ;; identical? first: O(1) positive filter — the
                         ;; common case is an atom untouched since caching,
                         ;; whose current value IS the recorded object.
                         ;; Structural = only runs for atoms actually
                         ;; written in between (persistent collections give
                         ;; fresh roots on every update).
                         (or (identical? cur v)
                             (= cur v))))
                     (:values cache)))
      (:result cache)
      (let [tracked (atom {})]
        (binding [*tracked* tracked]
          (let [cache-watch-key (keyword (str "track!cache" (System/identityHashCode component)))
                invalidated? (atom false)
                ;; Watch the cache atom itself: an invalidate mid-body (a
                ;; render fn calling :invalidate, or a tracked atom changing
                ;; while the body runs) resets it — a signal that this result
                ;; was built from stale state, so it must not be cached; the
                ;; next render re-runs the body with the fresh state.
                _ (add-watch cache-atom cache-watch-key
                             (fn [_ _ _ _] (reset! invalidated? true)))]
            (try
              (let [result (render-fn)
                    tracked-map @tracked]
                (doseq [a (keys tracked-map)]
                  (add-watch a (tracker-key component)
                             (fn [_ _ old new]
                               ;; Skip invalidation when the value didn't actually
                               ;; change: renders are pure functions of tracked
                               ;; values, so an equal value means the cached result
                               ;; is still valid. (Clojure fires watches even on
                               ;; equal-value reset!/swap!.) identical? is the
                               ;; O(1) fast path; structural = catches persistent
                               ;; copies (fresh object, equal content).
                               (when-not (or (identical? old new)
                                             (= old new))
                                 (invalidate-cache component)))))
                (when-not @invalidated?
                  (reset! cache-atom {:width width
                                      :values tracked-map
                                      :result result}))
                result)
              (finally
                (remove-watch cache-atom cache-watch-key)))))))))

(defmacro track!
  "Reactive cache wrapper for IComponent render methods.

   Usage:
     (render [this width]
       (track! this width
         ;; render body — every @atom read is tracked automatically
         ...))

   Runs BODY, recording every atom deref'd. When any recorded atom changes,
   the component's cache is invalidated automatically, so setters become
   plain (reset! atom val) / (swap! atom f) with no manual
   (protocols/invalidate comp) call. While all tracked values are unchanged,
   the cached result is returned without re-running BODY."
  [component width & body]
  `(track-render ~component ~width (fn [] ~@(rewrite-derefs body))))

(defn invalidate-cache
  "Invalidate a component's cache. Call from your invalidate method.
   Equivalent to (reset! (:cache-atom component) nil)."
  [component]
  (when-let [cache (component-cache-atom component)]
    (reset! cache nil)))

;; ═══════════════════════════════════════════════════════════════════════════
;; defsetter / defgetter — component state accessor boilerplate
;; ═══════════════════════════════════════════════════════════════════════════

(defmacro defsetter
  "Define a one-arg setter for a component atom field.

     (defsetter name field comp value body...)
       → (defn name [comp value] (reset! (field comp) value) body...)

   FIELD is the keyword name of the atom field. COMP and VALUE are the
   parameter names of the generated function — BODY refers to them as
   needed. BODY is optional and spliced after the reset, for side effects
   that must follow the state change ((protocols/invalidate comp), reflow,
   additional resets)."
  [name field comp value & body]
  `(defn ~name [~comp ~value]
     (reset! (~field ~comp) ~value)
     ~@body))

(defmacro defgetter
  "Define a one-arg getter for a component atom field.

     (defgetter name field comp)
       → (defn name [comp] @(field comp))"
  [name field comp]
  `(defn ~name [~comp]
     @(~field ~comp)))

;; ═══════════════════════════════════════════════════════════════════════════
;; ═════════════════════════════════════════════════════════════════════
;; with-let store — generation-keyed locals + LIFO cleanups (Stage-1
;; primitive; the with-let macro lands here over this in Stage 3)
;; ═════════════════════════════════════════════════════════════════════

(def ^:dynamic *store*
  "The per-instance with-let store bound around a component body. Nil
   outside one (fetch-local/register-cleanup! throw)."
  nil)

(defn new-store
  "A fresh with-let store: holds once-initialized locals and LIFO cleanups."
  []
  (atom {:locals {} :cleanups () :cleanup-sites #{}}))

(defn fetch-local
  "Value for SITE in the bound store, initialized by INIT-FN exactly once
   per store (per component instance — the body re-runs every pass, the
   init does not)."
  [site init-fn]
  (when (nil? *store*)
    (throw (ex-info "kmet.tui.macros/fetch-local called outside a with-let store" {:site site})))
  (if (contains? (:locals @*store*) site)
    (get (:locals @*store*) site)
    (let [v (init-fn)]
      (swap! *store* assoc-in [:locals site] v)
      v)))

(defn register-cleanup!
  "Register F to run when the bound store is destroyed. Registration is
   once-per-SITE: the body re-runs every render pass, and a bare conj would
   grow the cleanup list per frame. Cleanups run LIFO at destroy-store!."
  [site f]
  (when (nil? *store*)
    (throw (ex-info "kmet.tui.macros/register-cleanup! called outside a with-let store" {:site site})))
  (when-not (contains? (:cleanup-sites @*store*) site)
    (swap! *store* (fn [s]
                     (-> s
                         (update :cleanups conj f)
                         (update :cleanup-sites conj site)))))
  nil)

(defn destroy-store!
  "Run STORE's cleanups in reverse registration order (each isolated — a
   throwing cleanup logs to stderr and lets the rest run), then empty it."
  [store]
  (doseq [f (:cleanups @store)]
    (try
      (f)
      (catch Throwable e
        (binding [*out* *err*]
          (println "kmet.tui.macros with-let cleanup error:" (ex-message e))))))
  (swap! store assoc :cleanups () :cleanup-sites #{} :locals {})
  nil)

(defmacro with-store
  "Bind STORE as the ambient with-let store for BODY (dynamic *store*)."
  [store & body]
  `(binding [*store* ~store]
     ~@body))

;; defcomponent — record + IComponent + IComponentKind boilerplate
;; ═══════════════════════════════════════════════════════════════════════════

(defn track-deps
  "Declare tracked dependencies inside a track! body: deref atoms whose
   changes must invalidate the render cache even though their values don't
   otherwise appear in the body (wrapper components whose output comes from
   cached children, e.g. a message component re-rendering its Box). Call
   with @atom forms — the track! deref rewrite records them like any other
   read. Returns nil."
  [& _]
  nil)

(defn- track!-form?
  "True when FORM is a (track! ...) call (referred or fully qualified)."
  [form]
  (and (seq? form)
       (or (= 'track! (first form))
           (= 'kmet.tui.macros/track! (first form)))))

(defn- render-uses-track?
  "True when the render method calls track! lexically (the body must contain
   a (track! ...) form for the deref rewrite to apply)."
  [render-method]
  (boolean (some track!-form?
                 (tree-seq coll? seq render-method))))

(defn- cache-clearing-invalidate
  "An invalidate method that clears the component's track! cache (the
   reactive watches make it redundant, but explicit invalidation must stay
   correct)."
  []
  '(invalidate [this] (kmet.tui.macros/invalidate-cache this)))

(defn- prepend-cache-clear
  "Prepend the track! cache clear to a custom invalidate method body, using
   the method's own component binding (authors may name it _this when the
   body doesn't use it)."
  [invalidate-form]
  (let [[_ args & body] invalidate-form
        this-arg (first args)]
    (list* 'invalidate args
           (list 'kmet.tui.macros/invalidate-cache this-arg)
           body)))

(defn- body-method
  "First body form whose head symbol is SYM, or nil."
  [body sym]
  (first (filter #(and (seq? %) (= sym (first %))) body)))

(defmacro defcomponent
  "Define a TUI component: a defrecord implementing protocols/IComponent.

     (defcomponent Name kind [field...]
       (render [this width] ...)          ; required
       (handle-input [this data] ...)     ; optional — defaults to no-op
       (invalidate [this] ...))           ; optional

   When the render body calls track!, an invalidate method is generated
   (or the cache clear prepended to a custom one) so explicit invalidation
   always clears the render cache — components only write an invalidate
   method for additional side effects (delegating to children, firing
   request-render).

   KIND is stamped as the record's FIRST FIELD (kind-as-data, dsl.md §5):
   a keyword like :user / :assistant / :tool / :custom for message
   components, or nil for plain tui components (Text, Spacer, footer...).
   Dispatch reads (:kind component) — there is no IComponentKind
   protocol. Construct via map-> (kind defaults to nil when absent from
   the map). Components that need additional protocols (IFocusable) keep
   a separate extend-type form after the call. The expansion requires
   kmet.tui.protocols first, so component namespaces load standalone
   (their own ns need not require it — clj-kondo would flag that as
   unused)."
  [name _kind fields & body]
  (let [render (body-method body 'render)]
    (when-not render
      (throw (ex-info "defcomponent requires a render method" {:name name})))
    (let [track? (render-uses-track? render)
          handle-input (or (body-method body 'handle-input)
                           '(handle-input [_this _data] nil))
          custom-invalidate (body-method body 'invalidate)
          invalidate (cond
                       custom-invalidate (if track?
                                           (prepend-cache-clear custom-invalidate)
                                           custom-invalidate)
                       track? (cache-clearing-invalidate)
                       :else '(invalidate [this] nil))]
      `(do
         (clojure.core/require 'kmet.tui.protocols)
         (defrecord ~name ~(vec (cons 'kind fields))
           kmet.tui.protocols/IComponent
           ~render
           ~handle-input
           ~invalidate)))))
