(ns kmet.tui.hiccup
  "Hiccup-style construction + reconciliation layer for the TUI DSL
   (dsl.md §2, stages 2–3).

   Trees are plain data: [:tag {props} children...] or [my-fn {props}]
   function heads. Compilation walks the tree through a CLOSED TAG TABLE —
   there is no registry; custom composition uses fn components (wrapped in
   ComponentFn, their bodies running inside reactions with auto-discovered
   deps) or raw records spliced into the tree (identity preserved, never
   disposed — they are owned elsewhere).

   Reconciliation (dsl.md §2.3): ONE keyed diff drives everything — the
   ComponentFn wrapper's child list AND the fill of every host container
   (through per-tag children lenses). Matching is explicit :key first,
   fallback match-kind (tag / fn value / record payload / string);
   unmatched previous children are disposed (children-first contract),
   matched ones are reused — identity survives reorders by key, so stateful
   subtrees (editors, fn components with with-let state) live across
   passes. Ownership rides the :dsl/meta stamp: everything the DSL
   constructs carries it; foreign records never do and are never disposed.

   Props: display leaves (text/markdown/spacer/string) are rebuilt when
   their props change — identity-free, their caches absorb rendering;
   equal props short-circuit to the same instance. Containers keep their
   instance across passes; their structural props (padding/gap) are
   create-time until the §4 props/state migration makes them live.

   Validation is loud per the v1 error contract: unknown tags throw with a
   did-you-mean suggestion, children on a leaf tag throw, duplicate :keys
   throw, stack-entry maps outside a stack tag throw.

   Mounting goes through hiccup/root — the one public constructor from a
   tree to a mounted, disposable IComponent (a ComponentFn). render-lines
   gives the headless surface: pure data in, lines out, no terminal."
  (:refer-clojure :exclude [ref])
  (:require
   [kmet.tui.components.box :as box]
   [kmet.tui.components.container :as container]
   [kmet.tui.components.h-stack :as h-stack]
   [kmet.tui.components.markdown :as markdown]
   [kmet.tui.components.spacer :as spacer]
   [kmet.tui.components.stack :as stack]
   [kmet.tui.components.text :as text]
   [kmet.tui.components.v-stack :as v-stack]
   [kmet.tui.fuzzy :as fuzzy]
   [kmet.tui.macros :as macros :refer [defcomponent]]
   [kmet.tui.protocols :as protocols]
   [kmet.tui.reagent :as r]))

;; ═══════════════════════════════════════════════════════════════════════════
;; Refs — the imperative escape hatch (dsl.md §2.3)
;; ═══════════════════════════════════════════════════════════════════════════

(defprotocol DslRef
  "Internal surface of hiccup refs. Not for external use — reconcile
   fills and clears refs; consumers only deref."
  (-fill-ref! [this c] "Point the ref at C (nil clears it)."))

(defn ref
  "Create a ref handle: deref it (outside render bodies — handlers,
   effects) to reach the mounted record of the tree element that declares
   it as a :ref prop. Nil until reconcile first constructs that element,
   cleared when the element is disposed. Filled by reconciliation only —
   treat as read-only (a core reset! on this reify would silently no-op
   under babashka, so there is deliberately nothing to intercept: the
   type simply isn't an IAtom). One ref per element instance."
  []
  (let [cell (atom nil)]
    (reify
      DslRef
      (-fill-ref! [_ c] (reset! cell c) nil)
      clojure.lang.IDeref
      (deref [_] @cell))))

;; ═══════════════════════════════════════════════════════════════════════════
;; Dynamics + observability (dsl.md §2.4)
;; ═══════════════════════════════════════════════════════════════════════════

(def ^:dynamic *width*
  "The current render-pass width, bound around ComponentFn bodies — for
  status bars/dialog headers that must truncate at the real width. Read
  only; nil outside a component render.")

(def ^:dynamic *comp*
  "The ComponentFn whose body is running. nil outside one.")

;; Behind --debug these surface the inline-callback trap (§2.5): a body
;; invoked on frames where nothing it derefs changed shows up as
;; bodies-run climbing while bodies-skipped stays flat.
(def ^:private zero-counters
  {:bodies-run 0 :bodies-skipped 0 :constructs 0 :reuses 0 :disposals 0
   :computes 0})

(defonce ^:private counters-atom (atom zero-counters))

(defn counters
  "Per-frame fn-invocation/reconcile counters ({:bodies-run :bodies-skipped
   :constructs :reuses :disposals :computes}). Process-wide;
   reset-counters! in tests."
  [] @counters-atom)

(defn reset-counters!
  "Back to all zeros (the immutable zero map is shared with the initial
   value — reset! swaps whole values, so no aliasing concerns)."
  []
  (reset! counters-atom zero-counters)
  nil)

(defn- bump! [k] (swap! counters-atom update k (fnil inc 0)))

;; ═══════════════════════════════════════════════════════════════════════════
;; Tag table — the closed set of host elements (dsl.md §2.2)
;; ═══════════════════════════════════════════════════════════════════════════

;; Spec fields:
;;   :ctor       — adapter fn from normalized PROPS to a fresh component
;;                 (keeps unmigrated host constructors usable; collapses
;;                 when components adopt the uniform props/state shape)
;;   :primary    — positional-shorthand target: [:text "hi"] compiles to
;;                 props {:text "hi"}
;;   :lens       — present iff the tag takes children; get/put fns over the
;;                 component's child STORAGE. get returns the raw stored
;;                 children (components; entry maps for stacks) —
;;                 itemization happens once, in reconcile-into. put installs
;;                 the reconciled items back (:item wins — stacks store
;;                 entry maps, other containers components).
;;
;; Adapter ctors destructure known props and ignore extras, EXCEPT the
;; pseudo-props :key/:ref which parse strips before ctors ever see them.

(def ^:private tags
  {:text      {:ctor (fn [{:keys [text padding-x padding-y bg-fn]}]
                       (text/make-text text
                                       (or padding-x 1)
                                       (or padding-y 1)
                                       bg-fn))
               :primary :text}
   :markdown  {:ctor (fn [{:keys [text theme padding-x default-style transform]}]
                       (markdown/make-markdown text
                                               :theme theme
                                               :padding-x (or padding-x 1)
                                               :default-style default-style
                                               :transform transform))
               :primary :text}
   :spacer    {:ctor (fn [{:keys [lines]}] (spacer/make-spacer (or lines 1)))}
   :box       {:ctor (fn [{:keys [padding-x padding-y bg-fn]}]
                       (box/make-box
                        (or padding-x 1) (or padding-y 1) bg-fn))
               :lens {:get (fn [c] (vec @(:children c)))
                      :put (fn [c items] (reset! (:children c) (mapv :c items)))}}
   :container {:ctor (fn [_props] (container/make-container []))
               :lens {:get (fn [c] (vec @(:children c)))
                      :put (fn [c items] (reset! (:children c) (mapv :c items)))}}
   :v-stack   {:ctor (fn [{:keys [gap]}]
                       (v-stack/make-v-stack [] :gap (or gap 0)))
               ;; :entries? — children of this tag may be stack entry maps
               :entries? true
               :lens {:get (fn [c] (vec @(:entries-atom c)))
                      :put (fn [c items]
                             (reset! (:entries-atom c)
                                     (mapv #(or (:item %) (:c %)) items)))}}
   :h-stack   {:ctor (fn [{:keys [gap align]}]
                       (h-stack/make-h-stack []
                                             :gap (or gap 0)
                                             :align (or align :stretch)))
               :entries? true
               :lens {:get (fn [c] (vec @(:entries-atom c)))
                      :put (fn [c items]
                             (reset! (:entries-atom c)
                                     (mapv #(or (:item %) (:c %)) items)))}}})

(defn- known-tags [] (vec (sort (keys tags))))

(defn- nearest-tag
  "Best fuzzy match for TAG among the known tags, for did-you-mean
   (lower score is a better match)."
  [tag]
  (let [name (subs (str tag) 1)]
    (->> (keys tags)
         (keep (fn [t]
                 (let [{:keys [matches score]}
                       (fuzzy/fuzzy-match name (subs (str t) 1))]
                   (when matches [score t]))))
         (sort-by first)
         first
         second)))

(defn- unknown-tag!
  "Throw the unknown-tag error: names the tag, lists the closed set, and
   suggests the nearest known tag (did-you-mean via the fuzzy matcher)."
  [tag]
  (let [hint (when-some [near (nearest-tag tag)]
               (str " Did you mean :" (name near) "?"))]
    (throw (ex-info (str "kmet.tui.hiccup: unknown tag " tag
                         ". Known tags: " (pr-str (known-tags)) "."
                         hint)
                    {:tag tag :known-tags (known-tags)}))))

;; ═══════════════════════════════════════════════════════════════════════════
;; Parsing — tree nodes → desired items (validation happens HERE, loudly)
;; ═══════════════════════════════════════════════════════════════════════════

;; Desired/previous items share one shape:
;;   :kind   ::host | ::fncomp | ::record | ::entry | ::string
;;   :mkey   match-kind — what groups this item with its previous self
;;           (explicit key wins; else tag / fn value / payload identity /
;;           ::string constant)
;;   :key    explicit :key pseudo-prop (nil mostly)
;;   :ref    explicit :ref pseudo-prop (nil mostly)
;;   :c      the component (records: the payload; entries: the inner)
;;   :item   the raw object placed back into a container (:item for stack
;;           entry maps, :c for everything else)
;;   :owned  true iff the DSL constructed it — only owned items are disposed
;; plus construction inputs on desired items:
;;   ::host    :tag :spec :props :nodes
;;   ::fncomp  :f :props :ctree

(declare check-ref!)

(defn- normalize-element
  "[TAG ...] → [spec props children]: an optional MAP right after the tag
   is the props; everything else is children. When the tag declares
   :primary, the FIRST child becomes that prop — with or without a props
   map ([:text \"hi\"] and [:text {:padding-x 0} \"hi\"] both fill :text);
   remaining children stay children. Pseudo-props :key/:ref are extracted
   separately — they belong to reconciliation, never to constructors.
   NB: defcomponent records ARE maps — a spliced component must never be
   mistaken for the props map; neither may a stack-entry map ({:component
   c} is never a legal prop shape)."
  [tag content]
  (let [spec (or (get tags tag) (unknown-tag! tag))
        props-map? (let [first-content (first content)]
                     (and (map? first-content)
                          (not (record? first-content))
                          ;; {:component c} is the stack-entry shape —
                          ;; always a CHILD; consuming it as props would
                          ;; silently drop the element it wraps
                          (not (contains? first-content :component))))
        base-props (if props-map? (first content) {})
        children (if props-map? (rest content) content)
        primary? (:primary spec)
        take-primary? (boolean (and primary?
                                    (seq children)
                                    (or (not props-map?)
                                        (nil? (:lens spec)))))
        ;; a ref handle in the PRIMARY slot is almost certainly
        ;; [:widget (h/ref)] missing the props map — every other child
        ;; position rejects refs loudly, so must this one too (checked
        ;; against PRE-split children: take-primary consumes the head)
        _ (when (and take-primary? (satisfies? DslRef (first children)))
            (throw (ex-info
                    (str "kmet.tui.hiccup: bare (hiccup/ref) as the value "
                         "child of " tag " — did you mean [" (name tag)
                         " {:ref ...} ...]?")
                    {:tag tag})))
        [props children] (if take-primary?
                           [(assoc base-props primary? (first children))
                            (rest children)]
                           [base-props children])]
    [spec (dissoc props :key :ref) (vec children)
     (select-keys base-props [:key :ref])]))

(declare make-component-fn)

(defn- parse-host
  "[:tag ...] → desired item. Validates before any construction: unknown
   tags and children-on-leaf throw here."
  [tag content]
  (let [[spec props children meta] (normalize-element tag content)]
    (when (and (seq children) (nil? (:lens spec)))
      (throw (ex-info
              (str "kmet.tui.hiccup: children given to leaf tag " tag
                   " — leaves take only props")
              {:tag tag :children children})))
    (check-ref! tag (:ref meta))
    {:kind ::host :mkey (if-some [k (:key meta)] {::user-key k ::kind ::host} tag)
     :key (:key meta) :ref (:ref meta)
     :tag tag :spec spec :props props :nodes children}))

(defn- check-ref!
  ":ref must be a handle from hiccup/ref — anything else (a keyword, a
   string) is a typo that would otherwise detonate later at fill time."
  [where r]
  (when (and (some? r) (not (satisfies? DslRef r)))
    (throw (ex-info
            (str "kmet.tui.hiccup: :ref must be a (hiccup/ref) handle, got "
                 (pr-str r) (when (keyword? where) (str " on tag " where)))
            {:ref r}))))

(defn- parse-node
  "One tree node → desired item, or nil to skip (nil nodes — the
   when/when-let support). Seqs never reach here (flattened earlier)."
  [node stack?]
  (cond
    (nil? node) nil
    (string? node)
    {:kind ::string :mkey ::string :props {:text node}}
    (vector? node)
    (let [tag (first node)]
      (cond
        (keyword? tag) (parse-host tag (vec (rest node)))
        (fn? tag)
        (let [content (rest node)
              props-map? (and (map? (first content))
                              (not (record? (first content))))
              base-props (if props-map? (first content) {})
              children (vec (if props-map? (rest content) content))
              {:keys [key ref]} base-props]
          (check-ref! tag ref)
          {:kind ::fncomp :mkey (if-some [k key] {::user-key k ::kind ::fncomp} tag) :key key :ref ref
           :f tag :props (dissoc base-props :key :ref)
           :ctree (when (seq children) children)})
        :else (throw
               (ex-info
                (str "kmet.tui.hiccup: invalid element head " (pr-str tag)
                     " — expected a keyword tag or a function component")
                {:head tag}))))
    (record? node)
    {:kind ::record :mkey node :c node :item node :owned false}
    (map? node)
    (if (contains? node :component)
      (do
        (when-not stack?
          (throw
           (ex-info
            (str "kmet.tui.hiccup: stack entry map {:component c} outside "
                 "a stack tag (:v-stack/:h-stack) — entries are stack "
                 "children only")
            {:entry node})))
        {:kind ::entry :mkey (stack/entry-component node)
         :c (stack/entry-component node) :item node :owned false})
      (throw
       (ex-info
        (str "kmet.tui.hiccup: bare map child — maps pass through "
             "only as stack entries {:component c}; got "
             (pr-str (keys node)))
        {:map node})))
    :else (throw
           (ex-info (str "kmet.tui.hiccup: cannot compile tree node "
                         (pr-str node)
                         " — for function roots use hiccup/root")
                    {:node node}))))

(defn- flatten-nodes
  "Splice seqs into a flat node list — VECTORS are single elements and
   must be checked FIRST (they are sequential too; checking sequential?
   alone would shred every element into its own head/props/content).
   Nils survive flattening; parse drops them."
  [nodes acc]
  (reduce (fn [acc n]
            (cond
              (vector? n) (conj acc n)
              (sequential? n) (flatten-nodes n acc)
              :else (conj acc n)))
          acc nodes))

(defn- parse-nodes
  "Tree nodes → desired items, seqs spliced, nils dropped, duplicate
   explicit keys throwing (two spliced siblings sharing a key makes reuse
   undefined — throwing beats silent subtree loss, dsl.md §2.3). STACK?
   licenses entry maps (true only for stack-tag children)."
  [nodes stack?]
  (loop [flat (flatten-nodes nodes [])
         parsed []
         seen #{}]
    ;; NB: seq-destructure, NOT if-some on the head — a nil CHILD must be
    ;; skipped, not end the parse
    (if-let [[n & more] (seq flat)]
      (if-some [d (parse-node n stack?)]
        (do
          (when (and (:key d) (contains? seen (:key d)))
            (throw (ex-info
                    (str "kmet.tui.hiccup: duplicate :key " (pr-str (:key d))
                         " among siblings — keys must uniquely identify a "
                         "child for reuse")
                    {:key (:key d)})))
          (recur more (conj parsed d) (cond-> seen (:key d) (conj (:key d)))))
        (recur more parsed seen))
      parsed)))

(declare reconcile!)

;; ═══════════════════════════════════════════════════════════════════════════
;; ComponentFn — the fn-component wrapper (dsl.md §2.4)
;; ═══════════════════════════════════════════════════════════════════════════

(defcomponent ComponentFn nil [f props kids store rx ctree last-width]
  (render [_this width]
    (binding [*width* width *comp* _this]
      ;; Uncached by design (transparent-parent allowlist): the memoization
      ;; boundary is the REACTION — deps auto-discovered at deref time, and
      ;; an idle reaction hands back its cached tree without running the
      ;; body (zero fn invocations on frames where nothing changed).
      (let [rx (or @rx
                   (let [nr (r/make-reaction
                             (fn []
                               (macros/with-store store
                                 (macros/begin-pass! store)
                                 (bump! :bodies-run)
                                 (let [p (macros/tracked-deref props)
                                       ;; tracked: a parent adding/removing the
                                       ;; element's children changes CTREE alone,
                                       ;; with equal props — must still re-derive
                                       ct (macros/tracked-deref ctree)
                                       tree (if ct (f p ct) (f p))]
                                   (reconcile! kids tree))))
                             {:auto-run? (fn [_] (macros/schedule-frame!))
                              ;; Bodies reading only untracked values (bare
                              ;; @app-atom closures, static trees) must never
                              ;; cache stale output — they re-derive per pass,
                              ;; exactly like the pre-stage-3 batched root.
                              ;; Consequence, pinned by test: bodies whose ONLY
                              ;; inputs are props/ctree also re-derive each pass
                              ;; (correct — the props watch still dirties on real
                              ;; changes; equal-prop frames just cost a body run
                              ;; + diff). Reactive inputs beyond these go through
                              ;; cursors, slices, or tracked-deref and get narrow
                              ;; memoization for free.
                              :rerun-without-deps? true
                              :implicit-deps [props ctree]})]
                     (reset! rx nr)
                     nr))]
        ;; Width participates in memoization: it shapes body output (*width*
        ;; is read inside bodies) but is a dynamic var, not a tracked dep —
        ;; so a width change forces one re-derive of an idle reaction, which
        ;; then re-caches: track!'s per-width cache contract, one level up.
        (when (and (not= width @last-width)
                   (= :idle (:state (r/reaction-state rx))))
          (r/invalidate! rx))
        (reset! last-width width)
        (when (= :idle (:state (r/reaction-state rx)))
          (bump! :bodies-skipped))
        ;; Bring current: unrun/dirty → the body reconciles synchronously;
        ;; idle → the cached tree's items render without re-running it.
        (deref rx)
        (let [items @kids]
          (if (seq items)
            (->> items
                 (mapv #(protocols/render (:c %) width))
                 (apply concat)
                 vec)
            [])))))
  (dispose [_this]
    ;; Contractual order (dsl.md §2.4): children first — their cleanups
    ;; still see intact parent state; then the reaction (unwatches deps),
    ;; then the store's cleanups LIFO, leaving nothing registered.
    (doseq [it @kids :when (:owned it)]
      (protocols/dispose (:c it)))
    (when-some [r @rx]
      (r/dispose! r))
    (macros/destroy-store! store)))

(defn make-component-fn
  "Wrap F in a ComponentFn. CTREE is the element's raw child tree — when
   present, F is called as (f props children) instead of (f props)."
  ([f] (make-component-fn f nil))
  ([f ctree]
   (map->ComponentFn {:f f
                      :props (atom {})
                      :kids (atom [])
                      :store (macros/new-store)
                      :rx (atom nil)
                      :ctree (atom ctree)
                      :last-width (atom nil)})))

;; ═══════════════════════════════════════════════════════════════════════════
(declare stamped-meta remember-ref! reconcile-into)

;; ═══════════════════════════════════════════════════════════════════════════
;; The diff — one keyed reconcile for wrapper children AND container fill
;; ═══════════════════════════════════════════════════════════════════════════

(defn- itemize-prev
  "Raw stored child (from a container's lens or a wrapper's kid list) →
   previous item. Kind/ownership inferred: our stamps mark DSL-built
   hosts, ComponentFn instances are always ours, foreign records/entries
   are never owned (and so never disposed by reconcile)."
  [raw]
  (letfn [(mk [fallback kind key]
            (if-some [k key] {::user-key k ::kind kind} fallback))]
    (cond
      (and (map? raw) (contains? raw :component))
      {:kind ::entry :mkey (stack/entry-component raw)
       :c (stack/entry-component raw) :item raw :owned false}
      ;; our stamps come FIRST: an explicit :key must override the
      ;; tag/fn fallback on the previous side too, or keyed matching dies
      ;; at the second pass (::fncomponent marks fn wrappers)
      (and (record? raw) (some? (:dsl/meta raw)))
      (let [{:keys [tag key ref]} (stamped-meta raw)]
        (if (= tag ::fncomponent)
          {:kind ::fncomp :mkey (mk (:f raw) ::fncomp key) :key key :ref ref
           :c raw :item raw :owned true}
          {:kind ::host :mkey (mk tag ::host key) :key key :ref ref
           :c raw :item raw :owned true}))
      (instance? ComponentFn raw)
      {:kind ::fncomp :mkey (:f raw) :c raw :item raw :owned true}
      (record? raw)
      {:kind ::record :mkey raw :c raw :item raw :owned false}
      :else
      (throw (ex-info
              (str "kmet.tui.hiccup: unexpected child in container storage: "
                   (pr-str raw))
              {:child raw})))))

(defn- retire-item!
  "Dispose an owned previous item (clearing its ref first — dispose owns
   the ref lifecycle, on the handle AND in the stamp). Foreign items are
   left alone: they are owned elsewhere and merely leave the child list."
  [{:keys [c ref owned]}]
  (when owned
    (bump! :disposals)
    (when ref
      (-fill-ref! ref nil)
      (when-some [m (:dsl/meta c)]
        (swap! m assoc :ref nil)))
    (protocols/dispose c)))

(defn- stamp!
  "Mark a freshly-constructed component as DSL-owned. The stamp is an ATOM
   (record identity must never change, yet reconciliation must remember
   per-instance facts across passes): :tag kinds the component, :props is
   the leaf reuse fast-path comparison, :key the explicit key (match-kind
   must survive into the next pass), :ref the last ref handle pointed at
   this instance — cleared when the instance leaves its tree. Records
   tolerate the extra key."
  ([c tag props] (stamp! c tag props nil))
  ([c tag props key]
   (assoc c :dsl/meta (atom {:tag tag :props props :key key :ref nil}))))

(defn- stamped-meta
  "The deref'd DSL stamp of C (callers guarantee presence)."
  [c]
  @(:dsl/meta c))

(defn- remember-ref!
  "Record R as C's live ref handle (so removal can clear it later)."
  [c r]
  (when-some [m (:dsl/meta c)]
    (swap! m assoc :ref r)))

(defn- construct-item
  "Build a fresh component for a desired item (construction inputs on the
   item; containers recursively reconcile their children here — licensed
   for entry maps by the TAG's own :entries?, never the parent context)."
  [{:keys [kind tag spec props nodes f ctree c item] :as d}]
  (case kind
    ::host
    (let [comp (stamp! ((:ctor spec) props) tag props (:key d))]
      (bump! :constructs)
      (when (and (:lens spec) (seq nodes))
        (reconcile-into (:lens spec) comp nodes (boolean (:entries? spec))))
      (when-some [r (:ref d)]
        (-fill-ref! r comp)
        (remember-ref! comp r))
      {:kind kind :mkey (:mkey d) :key (:key d) :ref (:ref d)
       :c comp :item comp :owned true})
    ::fncomp
    (let [wrapper (assoc (make-component-fn f ctree)
                         :dsl/meta (atom {:tag ::fncomponent
                                          :key (:key d) :ref nil}))]
      (reset! (:props wrapper) props)
      (bump! :constructs)
      (when-some [r (:ref d)]
        (-fill-ref! r wrapper)
        (remember-ref! wrapper r))
      {:kind kind :mkey (:mkey d) :key (:key d) :ref (:ref d)
       :c wrapper :item wrapper :owned true})
    ::string
    (let [comp (stamp! (text/make-text (:text props) 0 0)
                       ::string props (:key d))]
      (bump! :constructs)
      {:kind kind :mkey ::string :c comp :item comp :owned true})
    ;; ::record / ::entry pass through — never constructed, never owned
    {:kind kind :mkey (:mkey d) :key (:key d) :ref (:ref d)
     :c c :item item :owned false}))

(defn- reuse-or-build
  "Matched pair → [kept-item retired-prev?]. Display leaves rebuild when
   their props changed (identity-free; the equal-props fast path keeps
   unchanged frames free); everything else keeps the previous instance."
  [d prev]
  (letfn [(keep [c item]
            {:item {:kind (:kind d) :mkey (:mkey d) :key (:key d)
                    :ref (:ref d) :c c :item item :owned true}
             :retire nil})]
    (case (:kind d)
      ::host
      (let [container? (some? (:lens (:spec d)))
            prev-props (:props (stamped-meta (:c prev)))]
        (if (or container? (= (:props d) prev-props))
          (do (bump! :reuses)
              (when container?
                (reconcile-into (:lens (:spec d)) (:c prev) (:nodes d)
                                (boolean (:entries? (:spec d)))))
              (when-some [r (:ref d)]
                (-fill-ref! r (:c prev))
                (remember-ref! (:c prev) r))
              (keep (:c prev) (:c prev)))
          ;; leaf with different props: rebuild. Retire FIRST — the old
          ;; instance's stamp holds the same ref handle the fresh construct
          ;; is about to fill; retiring afterwards would wipe it. Returns
          ;; the full new item map (no retire left for the caller).
          (do (retire-item! prev)
              {:item (construct-item d) :retire nil})))
      ::fncomp
      (let [w (:c prev)]
        (bump! :reuses)
        (reset! (:props w) (:props d))
        (reset! (:ctree w) (:ctree d))
        (when-some [r (:ref d)]
          (-fill-ref! r w)
          (remember-ref! w r))
        (keep w w))
      ;; ::record / ::entry — passthrough, take the DESIRED payload (the
      ;; bucket matched structically; the tree's own object is canonical)
      {:item {:kind (:kind d) :mkey (:mkey d) :key (:key d) :ref (:ref d)
              :c (:c d) :item (:item d) :owned false}
       :retire nil})))

(defn- split-bucket
  "Pop the oldest previous item off MKEY's bucket (order-stable matching
   within a match-kind: i-th desired ↔ i-th previous)."
  [buckets mkey]
  (if-some [bucket (get buckets mkey)]
    [(first bucket) (assoc buckets mkey (vec (rest bucket)))]
    [nil buckets]))

(defn- diff-items
  "The keyed diff (dsl.md §2.3): walk desired items in order, reusing the
   matching previous item per match-kind bucket; leftovers are retired
   (disposed iff owned, ref cleared). Returns the kept items in desired
   order."
  [prev-items desired]
  (let [buckets (volatile!
                 (reduce (fn [m it] (update m (:mkey it) (fnil conj []) it))
                         {} prev-items))
        out (volatile! [])]
    (doseq [d desired]
      (let [[prev buckets'] (split-bucket @buckets (:mkey d))]
        (vreset! buckets buckets')
        (if prev
          (let [{:keys [item retire]} (reuse-or-build d prev)]
            (when retire (retire-item! retire))
            (vswap! out conj item))
          (vswap! out conj (construct-item d)))))
    (doseq [bucket (vals @buckets), prev bucket]
      (retire-item! prev))
    (vec @out)))

(defn- reconcile-into
  "Reconcile NODES into CONTAINER through SPEC's lens: read the current
   raw children, diff, install the result back. The one children
   mechanism — used by host containers at every nesting level (dsl.md §5)."
  [lens container nodes stack?]
  (let [prev (mapv itemize-prev ((:get lens) container))
        kept (diff-items prev (parse-nodes nodes stack?))]
    ((:put lens) container kept)))

(defn- as-roots
  "TREE → sequence of root nodes. An element VECTOR is ONE root; only a
  seq splices as multiple roots (vectors ARE sequential — check vector?
  first; this exact trap produced three bugs during stage 3)."
  [tree]
  (cond
    (nil? tree) []
    (vector? tree) [tree]
    (sequential? tree) tree
    :else [tree]))

(defn reconcile!
  "Diff TREE against KIDS-ATOM's current items and install the result —
   the ComponentFn render pass (dsl.md §2.3). TREE may be one element, a
   sequence of roots, or nil. Returns the kept items."
  [kids-atom tree]
  (let [kept (diff-items @kids-atom (parse-nodes (as-roots tree) false))]
    (reset! kids-atom kept)
    kept))

;; ═══════════════════════════════════════════════════════════════════════════
;; Public compile + render surfaces
;; ═══════════════════════════════════════════════════════════════════════════

(defn compile-element
  "Compile one tree node into a live component (fresh construction —
   reconcile against an empty previous pool). Returns nil for nil.
   Records pass through untouched (identity preserved); stack-entry maps
   ({:component c ...}) are rejected outside stack tags; strings become
   bare zero-padding Text. A SEQ input throws — splicing multiple roots
   is compile-tree's business."
  [el]
  (when (and (sequential? el) (not (vector? el)))
    (throw (ex-info
            (str "kmet.tui.hiccup: seq passed to compile-element — a seq "
                 "splices as CHILDREN of an element or ROOTS via compile-tree")
            {:node el})))
  (let [parsed (parse-nodes [el] false)]
    (when (seq parsed)
      (:c (first (diff-items [] parsed))))))

(defn compile-tree
  "Compile a whole tree: one element VECTOR, or a sequence of elements
   spliced as multiple roots (a list/seq — a vector is always ONE element).
   Returns a single component, a vector of components, or nil."
  [tree]
  ;; nil → as-roots [] → zero items → nil: the empty case falls through
  (let [items (diff-items [] (parse-nodes (as-roots tree) false))]
    (case (count items)
      0 nil
      1 (:c (first items))
      (mapv :c items))))

(defn- render-compiled [compiled width]
  (cond
    (nil? compiled) []
    (sequential? compiled) (->> compiled
                                (mapv #(protocols/render % width))
                                (apply concat)
                                vec)
    :else (vec (protocols/render compiled width))))

(defn- owned? [c]
  (or (instance? ComponentFn c) (contains? c :dsl/meta)))

(defn render-lines
  "Headless render: compile TREE and return the exact lines the frame loop
   would draw. No tty, no sleeps — fast-path test material. DSL-built
   components are disposed afterwards (their reactions unwound); foreign
   spliced records are left alone."
  [tree width]
  (let [compiled (compile-tree tree)]
    (try
      (render-compiled compiled width)
      (finally
        (doseq [c (cond
                    (nil? compiled) []
                    (sequential? compiled) compiled
                    :else [compiled])
                :when (owned? c)]
          (protocols/dispose c))))))

(defn compute
  "A derived reactive ref over DEPS (dsl.md §3.1, Stage 5): sugar over
   kmet.tui.reaction whose body reads every dep through a tracked deref
   and applies F to their current values. The reaction re-derives when any
   dependency changes by =
   — the explicit DEPS list seeds tracking, and everything F itself reads
   through tracked channels (tracked-deref, other computes/reactions,
   cursors) joins the discovered set automatically. A recomputation to an
   equal value notifies nobody: watchers of the ref fire only on real
   output changes, component caches stay valid, no frame is requested
   (fine-grained invalidation for free).

   F is applied to the CURRENT VALUES of the deps as arguments:
   (compute [theme-atom] identity) and (compute [a b] +) work directly;
   with an empty dep list F takes no arguments and may close over its own
   slices.

   Returns a reaction: derefable everywhere (@c), watchable via
   kmet.tui.reagent/watch-ref, never reset!. Derefs outside a reaction
   settle the batch queue first and always answer the CURRENT value;
   inside a running reaction the deref records this ref as a dependency —
   the component-body pattern. First deref runs F lazily; dep changes
   re-run queued at the frame flush.

   When created during a component render pass (macros/*store* bound —
   under with-let), the reaction is disposed with the component: per-instance
   computes need no manual cleanup. Top-level shared computes (no enclosing
   store) live forever, which is the point. Create computes ONCE per
   instance — one built bare inside a component body leaks a reaction per
   pass, visible as :computes climbing in hiccup's --debug counters."
  [deps f]
  ;; Count creations: computes are created ONCE per instance (top-level def
  ;; or with-let init). A compute built bare inside a component body leaks a
  ;; reaction per render pass — the counter climbing frame over frame makes
  ;; that visible (dsl.md §3.1).
  (let [_ (bump! :computes)
        rx (r/make-reaction
            (fn []
              ;; Read the listed deps TRACKED and hand their values to F:
              ;; they are watched even where F ignores them. Anything else
              ;; F reads through tracked channels is discovered too.
              (apply f (mapv macros/tracked-deref deps))))]
    (when macros/*store*
      (macros/register-cleanup!
       (gensym "compute-")
       #(r/dispose! rx)))
    rx))

;; root — the one mount path (dsl.md §2.6)
;; ═══════════════════════════════════════════════════════════════════════════

(defn root
  "Mount TREE-OR-FN as an IComponent — the one way trees enter the TUI.
   An element (or sequence of elements) is compiled/reconciled on render
   passes; a vector whose first item is itself a vector counts as that
   sequence of roots; a bare FN is shorthand for [f {}] — called with the
   props map each time its reaction re-runs, so closures over app atoms
   are tracked at deref time and the body only re-runs when they actually
   change (memoized; the idle UI runs zero bodies). The owner mounts the
   returned record anywhere a component is accepted and calls dispose
   when it leaves."
  [tree-or-fn]
  (if (fn? tree-or-fn)
    ;; bare fn: shorthand for [f {}] — mounted directly, no extra wrapper
    (make-component-fn tree-or-fn)
    (let [tree (if (and (vector? tree-or-fn)
                        (vector? (first tree-or-fn)))
                 ;; vector of element vectors = spliced roots
                 (seq tree-or-fn)
                 tree-or-fn)]
      (make-component-fn (constantly tree)))))
