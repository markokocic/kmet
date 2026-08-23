(ns kmet.tui.hiccup
  "Hiccup-style construction layer for the TUI DSL (dsl.md §2, stage 2).

   Trees are plain data: [:tag {props} children...]. Compilation walks the
   tree once and builds live host components through a CLOSED TAG TABLE —
   there is no registry; custom composition uses raw records spliced into
   the tree (they pass through untouched, identity preserved).

   Normalization follows hiccup's normalize-element (sources in
   ~/src/cvstree/hiccup): an optional props MAP may follow the tag;
   otherwise everything after the tag is children. kmet additions: a tag's
   :primary shorthand turns [:text \"hi\"] into props {:text \"hi\"}, nil
   children are skipped, seqs are spliced, and stack-entry maps pass
   through untouched (VStack/HStack entries).

   Validation is loud per the v1 error contract: unknown tags throw with a
   did-you-mean suggestion, children on a leaf tag throw, and function
   heads throw until ComponentFn lands (stage 3) — hiccup itself drops
   content on void tags silently, which would hide typos here.

   Mounting goes through hiccup/root — the one public constructor from a
   tree to a mounted, disposable IComponent. render-lines gives the
   headless surface: pure data in, lines out, no terminal."
  (:require
   [kmet.tui.components.box :as box]
   [kmet.tui.components.container :as container]
   [kmet.tui.components.h-stack :as h-stack]
   [kmet.tui.components.markdown :as markdown]
   [kmet.tui.components.spacer :as spacer]
   [kmet.tui.components.text :as text]
   [kmet.tui.components.v-stack :as v-stack]
   [kmet.tui.fuzzy :as fuzzy]
   [kmet.tui.macros :refer [defcomponent]]
   [kmet.tui.protocols :as protocols]))

;; ═══════════════════════════════════════════════════════════════════════════
;; Tag table — the closed set of host elements (dsl.md §2.2)
;; ═══════════════════════════════════════════════════════════════════════════

;; Spec fields:
;;   :ctor       — adapter fn from normalized PROPS to a fresh component
;;                 (keeps unmigrated host constructors usable; collapses
;;                 when components adopt the uniform props/state shape)
;;   :primary    — positional-shorthand target: [:text "hi"] compiles to
;;                 props {:text "hi"}
;;   :add-child  — present iff the tag takes children; doubles as the
;;                 container test (one answer per question)
;;
;; Adapter ctors destructure known props and ignore extras, EXCEPT the
;; pseudo-props :key/:ref which compile strips before ctors ever see them.

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
               :add-child box/box-add-child}
   :container {:ctor (fn [_props] (container/make-container []))
               :add-child container/container-add-child}
   :v-stack   {:ctor (fn [{:keys [gap]}]
                       (v-stack/make-v-stack [] :gap (or gap 0)))
               :add-child v-stack/v-stack-add-child!}
   :h-stack   {:ctor (fn [{:keys [gap align]}]
                       (h-stack/make-h-stack []
                                             :gap (or gap 0)
                                             :align (or align :stretch)))
               :add-child h-stack/h-stack-add-child!}})

(defn- known-tags [] (vec (sort (keys tags))))

(defn- nearest-tag
  "Best fuzzy match for TAG among the known tags, for did-you-mean."
  [tag]
  (let [name (subs (str tag) 1)
        scored (mapv (fn [t]
                       [t (get (fuzzy/fuzzy-match name (subs (str t) 1))
                               :score)])
                     (keys tags))
        matched (filterv (fn [[t _score]]
                           (get (fuzzy/fuzzy-match name (subs (str t) 1))
                                :matches))
                         scored)]
    (when-some [[best _] (first (sort-by second matched))]
      best)))

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

(declare compile-element)

(defn reconcile-children!
  "Compile CHILDREN and attach each to COMPONENT through SPEC's :add-child.
   Stage-2 construction fill: freshly-built containers have no previous
   children, so this is append-only — keyed reuse arrives with reconcile!
   (stage 3), which layers a diff over this same entry point. Seqs splice,
   nils skip, strings compile to bare Text (zero padding — unlike the
   [:text] tag whose defaults add breathing room), stack-entry maps and
   records pass through.

   Splice rule: LISTS/seqs splice, VECTORS are single elements (mirrors
   compile-tree — checking sequential? alone would shred every vector
   child into its own head/props/content)."
  [spec component children]
  (doseq [child children]
    (cond
      (nil? child) nil
      (vector? child) ((:add-child spec) component (compile-element child))
      (sequential? child) (reconcile-children! spec component child)
      :else (when-some [compiled (compile-element child)]
              ((:add-child spec) component compiled)))))

(defn- normalize-element
  "[TAG ...] → [spec props children]: an optional MAP right after the tag
   is the props; everything else is children. When the tag declares
   :primary, the FIRST child becomes that prop — with or without a props
   map ([:text \"hi\"] and [:text {:padding-x 0} \"hi\"] both fill :text);
   remaining children stay children. Pseudo-props :key/:ref are stripped
   here — they belong to reconciliation, never to constructors."
  [tag content]
  (let [spec (or (get tags tag) (unknown-tag! tag))
        props-map? (let [first-content (first content)]
                     ;; NB: defcomponent records ARE maps — a spliced
                     ;; component must never be mistaken for the props map.
                     (and (map? first-content) (not (record? first-content))))
        base-props (if props-map? (first content) {})
        children (if props-map? (rest content) content)
        primary? (:primary spec)
        take-primary? (boolean (and primary?
                                    (seq children)
                                    (or (not props-map?)
                                        (nil? (:add-child spec)))))
        [props children] (if take-primary?
                           [(assoc base-props primary? (first children))
                            (rest children)]
                           [base-props children])]
    [spec (dissoc props :key :ref) (vec children)]))

(defn- compile-host [tag content]
  ;; Validate BEFORE constructing: ctors are side-effect-free today, but
  ;; throwing after building would still be throwing-after-side-effect.
  (let [[spec props children] (normalize-element tag content)]
    (when (and (seq children) (nil? (:add-child spec)))
      (throw (ex-info
              (str "kmet.tui.hiccup: children given to leaf tag " tag
                   " — leaves take only props")
              {:tag tag :children children})))
    (let [component ((:ctor spec) props)]
      (when (seq children)
        (reconcile-children! spec component children))
      component)))

(defn compile-element
  "Compile one tree node into a live host component. Returns nil for nil.
   Records pass through untouched (identity preserved); stack-entry maps
   ({:component c ...}) pass through — ONLY those: a bare data map is
   almost certainly a mistake and would detonate later at render time, so
   it throws here. Strings become bare zero-padding Text."
  [el]
  (cond
    (nil? el) nil
    (string? el) (text/make-text el 0 0)
    (record? el) el
    (map? el) (if (contains? el :component)
                el
                (throw
                 (ex-info
                  (str "kmet.tui.hiccup: bare map child — maps pass through "
                       "only as stack entries {:component c}; got "
                       (pr-str (keys el)))
                  {:map el})))
    (vector? el)
    (let [tag (first el)]
      (cond
        (keyword? tag) (compile-host tag (vec (rest el)))
        (fn? tag) (throw
                   (ex-info
                    (str "kmet.tui.hiccup: function component head — fn "
                         "components arrive with ComponentFn (stage 3); "
                         "splice prebuilt records for now")
                    {:head tag}))
        :else (throw
               (ex-info
                (str "kmet.tui.hiccup: invalid element head " (pr-str tag)
                     " — expected a keyword tag from " (pr-str (known-tags)))
                {:head tag}))))
    :else (throw
           (ex-info (str "kmet.tui.hiccup: cannot compile tree node "
                         (pr-str el)
                         " — for function roots use hiccup/root")
                    {:node el}))))

(defn compile-tree
  "Compile a whole tree: one element VECTOR, or a sequence of elements
   spliced as multiple roots (a list/seq — a vector is always ONE element,
   mirroring hiccup where vectors are elements and seqs are content).
   Returns a single component, a vector of components/maps, or nil."
  [tree]
  (cond
    (nil? tree) nil
    (record? tree) tree
    (vector? tree) (compile-element tree)
    (sequential? tree) (->> tree
                            (mapv compile-element)
                            (filterv some?))
    :else (compile-element tree)))

(defn- render-compiled [compiled width]
  (cond
    (nil? compiled) []
    (sequential? compiled) (->> compiled
                                (mapv #(protocols/render % width))
                                (apply concat)
                                vec)
    :else (vec (protocols/render compiled width))))

(defn render-lines
  "Headless render: compile TREE and return the exact lines the frame loop
   would draw. No tty, no sleeps — fast-path test material. Render twice
   across a state change and compare lines to assert cache behavior held."
  [tree width]
  (render-compiled (compile-tree tree) width))

(defcomponent HiccupRoot nil [tree-fn]
  (render [_this width]
    ;; Batched mode (pre-ComponentFn): the tree fn re-runs each frame and
    ;; compilation rebuilds host elements — correctness never depends on
    ;; caches, and leaf caches absorb the cost. Reactions + reconcile bring
    ;; narrow reactivity and instance reuse in stage 3.
    (render-compiled (compile-tree (tree-fn)) width)))

(defn root
  "Mount TREE-OR-FN as an IComponent — the one way trees enter the TUI.
   An element (or sequence of elements) is compiled on every render pass;
   a vector whose first item is itself a vector counts as that sequence of
   roots; a bare FN is shorthand for [f {}] — called with empty props each
   pass, so closures over app atoms re-derive per frame (batched mode;
   narrow reactivity arrives with ComponentFn in stage 3). The owner mounts
   the returned record anywhere a component is accepted and calls dispose
   when it leaves."
  [tree-or-fn]
  (let [f (if (fn? tree-or-fn)
            tree-or-fn
            (let [tree (if (and (vector? tree-or-fn)
                                (vector? (first tree-or-fn)))
                         ;; vector of element vectors = spliced roots
                         (seq tree-or-fn)
                         tree-or-fn)]
              (constantly tree)))]
    (map->HiccupRoot {:tree-fn (fn [] (f {}))})))
