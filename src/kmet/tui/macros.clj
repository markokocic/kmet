(ns kmet.tui.macros
  "Reactive cache for TUI components (track!).
   track! is a macro that rewrites @atom reads in the render body to tracked
   derefs, so any atom change invalidates the cache automatically and setters
   need no manual invalidate call. It must be a macro because deref rewriting
   happens at compile time; it expands to a plain runtime call (track-render),
   so SCI-based callers only need the function, not the macro.")

;; ═══════════════════════════════════════════════════════════════════════════
;; track! — reactive cache (Reagent-style dependency tracking)
;; ═══════════════════════════════════════════════════════════════════════════

(def ^:dynamic *tracked*
  "During track! execution, an atom holding the set of atoms deref'd so far
   in the current reactive render pass. nil outside a tracking scope."
  nil)

(defn tracked-deref
  "Deref wrapper used by track!. Records A in the active tracking set (when
   one is bound), then returns (deref A). Only IRef instances (atoms, vars)
   are tracked — volatiles and delays can't take watches and are skipped."
  [a]
  (when (and *tracked* (instance? clojure.lang.IRef a))
    (swap! *tracked* conj a))
  (deref a))

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
   tracked atom still holds the value it was computed from — watches
   invalidate on actual value changes (equal-value resets are no-ops), and
   the deref'd values are re-verified on every hit (closing the race where a
   value changes between the body's deref and its watch registration, which
   would never fire a notification).
   Requires COMPONENT to have a :cache-atom (or legacy :cache) field."
  [component width render-fn]
  (let [cache-atom (component-cache-atom component)
        cache @cache-atom]
    (if (and cache
             (= (:width cache) width)
             (every? (fn [[a v]] (= (deref a) v)) (:values cache)))
      (:result cache)
      (let [tracked (atom #{})]
        (binding [*tracked* tracked]
          (let [result (render-fn)
                tracked-set @tracked]
            (doseq [a tracked-set]
              (add-watch a (tracker-key component)
                         (fn [_ _ old new]
                           ;; Skip invalidation when the value didn't actually
                           ;; change: renders are pure functions of tracked
                           ;; values, so an equal value means the cached result
                           ;; is still valid. (Clojure fires watches even on
                           ;; equal-value reset!/swap!.)
                           (when-not (= old new)
                             (invalidate-cache component)))))
            (reset! cache-atom {:width width
                                :values (zipmap tracked-set (map deref tracked-set))
                                :result result})
            result))))))

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
