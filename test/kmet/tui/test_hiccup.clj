(ns kmet.tui.test-hiccup
  "Headless tests for the hiccup construction layer (dsl.md §2, stages 2–3).
   Cases adapted from hiccup's own compiler tests where they transfer
   (normalization, nil/seq handling, loud head validation) plus the kmet-
   specific contracts: closed tag table with did-you-mean, leaf-tag throw,
   :primary shorthand, :key/:ref pseudo-props, record/map passthrough,
   fn components (ComponentFn), keyed reconcile/reuse, refs, and the
   memoization/idle invariant."
  (:require [clojure.string :as str]
            [clojure.test :as t]
            [kmet.tui.components.input :as input]
            [kmet.tui.components.text :as text]
            [kmet.tui.core :as core]
            [kmet.tui.hiccup :as h]
            [kmet.tui.macros :as macros :refer [with-let defcomponent]]
            [kmet.tui.protocols :as protocols]
            [kmet.tui.components.stack :as stack]
            [kmet.libs.reakt :as rag]))

(defn- joined [tree width]
  (str/join "\n" (h/render-lines tree width)))

;; ── basic compilation & rendering (hiccup: basic-element tests) ───────────

(t/deftest basic-element
  (t/is (str/includes? (joined [:text "hello"] 40) "hello"))
  ;; [:text value] shorthand == explicit props map
  (t/is (= (h/render-lines [:text "hi"] 40)
           (h/render-lines [:text {:text "hi"}] 40))))

(t/deftest props-override-defaults
  ;; text defaults to padding-x 1; an explicit 0 removes it
  (let [lines (mapv str/trimr
                    (h/render-lines [:text {:padding-x 0 :padding-y 0} "hi"]
                                    40))]
    (t/is (= ["hi"] lines))
    (let [padded (h/render-lines [:text "hi"] 40)]
      (t/is (= 3 (count padded)) "default padding-y 1 wraps both sides"))))

(t/deftest nested-containers-render-children
  (let [out (joined [:box {} [:text "a"] [:text "b"]] 20)]
    (t/is (str/includes? out "a"))
    (t/is (str/includes? out "b")))
  ;; three levels deep
  (t/is (str/includes?
         (joined [:container [:v-stack [:text "deep"]]] 30)
         "deep")))

;; ── child normalization (hiccup: nil / seq / string content) ─────────────

(t/deftest nil-children-are-skipped
  ;; this is the when/when-let/if support — free by contract
  (t/is (= ["x"] (mapv str/trimr
                       (h/render-lines [:container
                                        nil
                                        [:text {:padding-x 0 :padding-y 0} "x"]
                                        nil]
                                       10)))))

(t/deftest seqs-are-spliced
  ;; map over data → spliced children (always key them once keys matter)
  (let [tree (list
              [:text {:padding-x 0 :padding-y 0} "one"]
              [:text {:padding-x 0 :padding-y 0} "two"])
        lines (h/render-lines [:v-stack tree] 20)]
    (t/is (= ["one" "two"] (mapv str/trimr lines))))
  ;; a spliced seq at top level = multiple roots
  (t/is (= ["a" "b"] (mapv str/trimr
                           (h/render-lines
                            (list [:text {:padding-x 0 :padding-y 0} "a"]
                                  [:text {:padding-x 0 :padding-y 0} "b"])
                            10)))))

(t/deftest bare-string-children-become-text
  ;; zero-padding bare Text — distinct from the [:text] tag defaults.
  ;; containers pad lines to full width; compare trimmed.
  (t/is (= ["raw"] (mapv str/trimr (h/render-lines [:container "raw"] 10)))))

;; ── passthrough rules ────────────────────────────────────────────────────

(t/deftest records-pass-through-with-identity
  ;; dropping a live component into a tree keeps THE INSTANCE — the
  ;; adapter path that lets unmigrated widgets participate
  (let [c (text/make-text "live" 0 0)]
    (t/is (identical? c (h/compile-element c)))
    (t/is (identical? c (h/compile-tree c)))
    (let [lines (h/render-lines [:container c] 20)]
      (t/is (= ["live"] (mapv str/trimr lines))))))

(t/deftest stack-entry-maps-pass-through-inside-stacks
  ;; VStack/HStack accept entry maps alongside components — compile must
  ;; not touch them INSIDE a stack tag; entries arrive spliced (a map right
  ;; after the tag would be the props slot); anywhere outside a stack they
  ;; throw — a bare {:component c} has no render meaning there.
  (let [entry {:component (text/make-text "e" 0 0) :height 1}]
    (t/is (= ["e"] (mapv str/trimr
                         (h/render-lines [:v-stack (list entry)] 20))))
    (t/is (thrown-with-msg?
           Exception #"outside a stack tag"
           (h/render-lines [:container (list entry)] 10)))))

;; ── pseudo-props ─────────────────────────────────────────────────────────

(t/deftest key-and-ref-pseudo-props-are-stripped
  ;; ctors never see them; no unknown-key crash, no behavior change vs the
  ;; same tree without them. :ref must be a real (hiccup/ref) handle.
  (let [tree (fn [extra]
               [:box extra
                [:text (merge {:padding-x 0 :padding-y 0} extra) "hi"]])]
    (t/is (= (h/render-lines (tree {}) 20)
             (h/render-lines (tree {:key 7}) 20)))
    ;; box: padding-y 1 puts the child at line index 1; box's own blank
    ;; lines trim to empty strings
    (t/is (= ["" " hi" ""]
             (mapv str/trimr (h/render-lines (tree {:key 1}) 20))))
    ;; non-ref :ref values are rejected loudly at parse
    (t/is (thrown-with-msg? Exception #"hiccup/ref"
                            (h/render-lines [:text {:ref :fake} "x"] 10)))))

;; ── contents are concatenated / lazy & eager seqs (hiccup core_test) ────

(t/deftest multiple-string-children-concatenate
  ;; hiccup: [:body "foo" "bar"] — two bare strings become two Texts
  (t/is (= ["foo" "bar"]
           (mapv str/trimr (h/render-lines [:container "foo" "bar"] 20)))))

(t/deftest lists-of-strings-splice-inside-containers
  ;; hiccup: [:body (list "foo" "bar")]
  (t/is (= ["foo" "bar"]
           (mapv str/trimr
                 (h/render-lines [:container (list "foo" "bar")] 20)))))

(t/deftest lazy-seq-children-expand
  ;; hiccup: [:ul (for ...)] — lazy seqs from map/for work as children
  (t/is (= ["a" "b"]
           (mapv str/trimr
                 (h/render-lines
                  [:v-stack (map #(vector :text {:padding-x 0 :padding-y 0} %)
                                 ["a" "b"])]
                  20)))))

;; ── documented divergences from hiccup ──────────────────────────────────

(t/deftest keyword-children-throw-unlike-hiccup
  ;; hiccup renders [:div :foo] as "foo"; here a bare keyword child is
  ;; almost always a bug (forgotten props / wrong value) — stay loud.
  (t/is (thrown? Exception (h/render-lines [:container :foo] 10))))

(t/deftest vector-without-tag-head-as-child-throws-like-hiccup
  ;; hiccup: "vecs don't expand - error if vec doesn't have tag name"
  (t/is (thrown? Exception
                 (h/render-lines [:container [[:text {:padding-x 0
                                                      :padding-y 0} "a"]]]
                                 10))))

(t/deftest nil-and-missing-text-content-render-empty
  ;; hiccup coerces nil content to nothing; Text.render treats nil like
  ;; blank, and BLANK TEXT RENDERS ZERO LINES — an invisible placeholder
  ;; (padding included), not a stack of blanks.
  (t/is (= [] (h/render-lines [:text nil] 20)))
  (t/is (= [] (h/render-lines [:text {}] 20)))
  (t/is (= [] (h/render-lines [:text {} nil] 20))))

(t/deftest bare-map-children-throw
  ;; stack-entry maps ({:component c}) pass through as CHILDREN; a BARE
  ;; data map child is a mistake that would detonate at render time —
  ;; throw at compile instead. (A map directly after the tag is the props
  ;; slot, by design.)
  (t/is (thrown? Exception
                 (h/render-lines [:container "x" {:not-a-component true}]
                                 10)))
  (t/is (thrown? Exception (h/render-lines [:container {} {}] 10))))

(t/deftest unknown-tags-throw-loudly
  (let [e (try (h/render-lines [:tst "x"] 20) nil
               (catch Exception ex ex))]
    (t/is (some? e))
    (t/is (str/includes? (ex-message e) "unknown tag :tst"))
    (t/is (str/includes? (ex-message e) ":text") "lists known tags")))

(t/deftest invalid-heads-throw-loudly
  (t/is (thrown? Exception (h/render-lines ["not-a-tag"] 10)))
  (t/is (thrown? Exception (h/render-lines ['sym 1] 10)))
  (t/is (thrown? Exception (h/render-lines 42 10))))

(t/deftest fn-heads-are-function-components
  ;; stage 3: fn heads compile to ComponentFn wrappers — reactive bodies,
  ;; props passed through. Bare @plain-atom reads are untracked; the batched
  ;; fallback keeps them live by re-deriving every pass (never stale, just
  ;; not narrow)
  (let [state (atom "live")
        status (fn [{:keys [label]}]
                 [:text {:padding-x 0} (str label ": " @state)])
        r (h/root (fn [_] [status {:label "s"}]))]
    (t/is (str/includes? (str/join "\n" (core/render r 30)) "s: live"))
    (reset! state "changed")
    (t/is (str/includes? (str/join "\n" (core/render r 30)) "changed"))))

(t/deftest children-on-leaf-tags-throw
  (t/is (thrown? Exception (h/render-lines [:text "a" "b"] 10)))
  (t/is (thrown? Exception (h/render-lines [:spacer 2 [:text "x"]] 10))))

(t/deftest dynamic-border-leaf-renders-colored-rules
  ;; :color-fn as prop map or primary shorthand; the rule fills the width
  (let [marker (fn [s] (str "<b>" s "</b>"))
        lines (h/render-lines
               [:container {}
                [:dynamic-border {:color-fn marker}]
                [:text {:padding-x 0 :padding-y 0} "x"]
                [:dynamic-border marker]] 40)]
    (t/is (= 3 (count lines)))
    (t/is (= (first lines) (str "<b>" (apply str (repeat 40 "─")) "</b>")))
    (t/is (= (last lines) (first lines)))))

(t/deftest dynamic-border-default-color-fn-renders
  ;; no :color-fn → the component's own default (theme border color)
  (let [lines (h/render-lines [:container {} [:dynamic-border]] 40)]
    (t/is (= 1 (count lines)))
    (t/is (str/includes? (first lines) "─"))))

;; ── full tag coverage: every component has a tag (React parity) ─────────

(t/deftest truncated-text-tag
  ;; truncates to the width; :padding-x insets
  (let [wide (h/render-lines [:truncated-text {:padding-x 0} "abcdefghijklmnop"] 10)]
    (t/is (= 1 (count wide)))
    (t/is (= 10 (count (first wide))))
    (t/is (str/ends-with? (first wide) "...")))
  (let [primary (h/render-lines [:truncated-text "hi"] 12)]
    (t/is (str/includes? (first primary) "hi"))))

(t/deftest spinner-tag
  ;; inactive by default (invisible); active shows prefix + frame + text
  (t/is (empty? (h/render-lines [:spinner "Lazy"] 20)))
  (let [lines (h/render-lines [:spinner {:text "Work" :active true :prefix ""}] 20)]
    (t/is (= 2 (count lines)) "leading blank line (pi Loader shape)")
    (t/is (str/includes? (last lines) "Work"))))

(t/deftest input-tag
  ;; :value pre-fills; :on-submit is wired; live updates go through
  ;; :ref + the setter (the create-time props contract)
  (let [r (h/ref)
        submitted (atom nil)
        on-submit (fn [v] (reset! submitted v))
        r2 (h/root (fn [_]
                     [:input {:value "abc" :on-submit on-submit :ref r}]))]
    (core/render r2 20)
    (let [i (deref r)]
      (t/is (some? i) ":ref points at the Input instance")
      (t/is (= "abc" (input/input-get-value i)) ":value pre-filled")
      (input/input-set-value! i "xyz")
      (t/is (= "xyz" (input/input-get-value i)) "setter updates the instance")
      (protocols/handle-input i "\r")
      (t/is (= "xyz" @submitted) "enter fires :on-submit with the value"))))

(t/deftest expandable-text-tag
  (let [mk (fn [expanded?] [:expandable-text {:collapsed-fn (fn [] "COLLAPSED")
                                              :expanded-fn (fn [] "EXPANDED")
                                              :expanded? expanded?}])]
    (t/is (str/includes? (str/join "\n" (h/render-lines (mk false) 30)) "COLLAPSED"))
    (t/is (str/includes? (str/join "\n" (h/render-lines (mk true) 30)) "EXPANDED"))))

(t/deftest image-tag-renders-fallback
  ;; no Kitty protocol in the test terminal → the text fallback line
  (let [lines (h/render-lines [:image {:base64-data "AAA=" :mime-type "image/png"}] 40)]
    (t/is (str/includes? (str/join "\n" lines) "image/png"))))

(t/deftest select-list-tag
  ;; primary :items; callbacks wired on the instance
  (let [chosen (atom nil)
        on-select (fn [item] (reset! chosen item))
        r (h/ref)
        lines (h/render-lines
               [:select-list [{:label "one" :value 1} {:label "two" :value 2}]]
               20)
        r2 (h/root (fn [_]
                     [:select-list {:items [{:label "a" :value :a}]
                                    :on-select on-select :ref r}]))]
    (t/is (str/includes? (str/join "\n" lines) "one"))
    (core/render r2 20)
    (let [sl (deref r)]
      (t/is (some? sl))
      (protocols/handle-input sl "\r")
      (t/is (some? @chosen) "enter fires :on-select with the item"))))

(t/deftest settings-list-tag
  (let [lines (h/render-lines
               [:settings-list [{:id :a :label "A" :value "x" :values ["x" "y"]}]]
               30)]
    (t/is (str/includes? (str/join "\n" lines) "A"))))

(t/deftest editor-tag
  ;; :text pre-fills the editor
  (let [lines (h/render-lines [:editor {:text "hi there" :height 3}] 40)]
    (t/is (str/includes? (str/join "\n" lines) "hi there"))))

(t/deftest cancellable-loader-tag
  ;; default spinner is active and shows :text; a :spinner prop wins
  (let [lines (h/render-lines [:cancellable-loader {:text "Loading"}] 20)]
    (t/is (str/includes? (str/join "\n" lines) "Loading"))))

(t/deftest scroll-view-tag
  ;; single child rendered inside the viewport
  (let [lines (h/render-lines
               [:scroll-view {:scrollbar :always} [:text {:padding-x 0} "inner"]] 20)]
    (t/is (str/includes? (str/join "\n" lines) "inner")))
  ;; more than one child is a bug — throw loudly
  (t/is (thrown? Exception
                 (h/render-lines [:scroll-view {} [:text "a"] [:text "b"]] 10)))
  ;; child swap across reconcile passes keeps the scroll-view instance
  (let [state (atom "v1")
        r (h/root (fn [_] [:scroll-view {} [:text {:padding-x 0} @state]]))]
    (t/is (str/includes? (str/join "\n" (core/render r 20)) "v1"))
    (reset! state "v2")
    (protocols/invalidate r)
    (t/is (str/includes? (str/join "\n" (core/render r 20)) "v2"))))

(t/deftest stateful-leaf-identity-stable-props
  ;; a stateful host leaf keeps its instance (and state) across passes
  ;; while its props stay =-equal — a fresh closure prop would rebuild it
  (let [r (h/ref)
        r2 (h/root (fn [_] [:select-list {:items [{:label "a" :value :a}]
                                          :ref r}]))]
    (core/render r2 20)
    (let [first (deref r)]
      (t/is (some? first))
      (core/render r2 20)
      (t/is (identical? first (deref r)) "same props → same instance"))))

;; ── root mounting (dsl.md §2.6) ──────────────────────────────────────────

(t/deftest root-mounts-elements-and-fns
  ;; static element root
  (let [r (h/root [:text {:padding-x 0 :padding-y 0} "static"])]
    (t/is (str/includes? (str/join "\n" (core/render r 20)) "static")))
  ;; fn root re-derives per frame from closures over app atoms (batched)
  (let [state (atom "v1")
        r (h/root (fn [_props] [:text {:padding-x 0 :padding-y 0} @state]))]
    (t/is (str/includes? (str/join "\n" (core/render r 20)) "v1"))
    (reset! state "v2")
    (t/is (str/includes? (str/join "\n" (core/render r 20)) "v2")
          "bare fn roots re-derive on the next pass")))

(t/deftest tracked-bodies-memoize-until-deps-change
  ;; stage-3 semantics: a body reading reactive inputs through tracked-deref
  ;; runs ONCE while deps hold, re-runs exactly when one changes. The idle-UI
  ;; invariant: zero fn bodies when nothing changed.
  (let [calls (atom 0)
        s (atom "a")
        r (h/root (fn [_]
                    (swap! calls inc)
                    [:text {:padding-x 0} (rag/tracked-deref s)]))]
    (core/render r 20)
    (core/render r 20)
    (t/is (= 1 @calls) "two identical passes, one body run")
    (reset! s "b")
    (core/render r 20)
    (t/is (= 2 @calls) "dep change re-runs the body")
    (core/render r 20)
    (t/is (= 2 @calls) "still clean — no extra runs")))

(t/deftest root-of-seq-tree-renders-all-roots
  (let [r (h/root [[:text {:padding-x 0 :padding-y 0} "one"]
                   [:text {:padding-x 0 :padding-y 0} "two"]])]
    (t/is (= ["one" "two"]
             (mapv str/trimr (core/render r 10))))))
;; ═══════════════════════════════════════════════════════════════════════
;; Stage 3 — keyed reconcile, refs, disposal, scheduling (dsl.md §2.3–§2.5)
;; ═══════════════════════════════════════════════════════════════════════

;; A FOREIGN component: constructed outside the DSL (no :dsl/meta stamp),
;; so reconcile must reuse it by identity and NEVER dispose it.
(defcomponent ForeignText nil [text-atom cache disposed?]
  (render [_this _width] [(str/trimr @text-atom)])
  (dispose [_this] (reset! disposed? true)))

(t/deftest keyed-reuse-survives-prepending
  ;; the motivating case: prepending must not rebuild the other keyed
  ;; siblings — identity rides the key (dsl.md §2.3). Refs are STABLE
  ;; handles created once, outside the body.
  (let [ref0 (h/ref) ref1 (h/ref) ref2 (h/ref)
        refs {0 ref0 1 ref1 2 ref2}
        msgs (atom [{:id 1 :text "one"} {:id 2 :text "two"}])
        r (h/root (fn [_]
                    [:container
                     (map (fn [{:keys [id text]}]
                            [:text {:key id :padding-x 0 :padding-y 0
                                    :ref (refs id)} text])
                          @msgs)]))]
    (core/render r 20)
    (let [one (deref ref1)]
      (t/is (instance? kmet.tui.components.text.Text one) "ref filled on mount")
      ;; prepend id 0 — id 1 keeps its instance
      (swap! msgs (fn [m] (vec (cons {:id 0 :text "zero"} m))))
      (core/render r 20)
      (t/is (identical? one (deref ref1))
            "same key → same record across prepend")
      ;; remove id 1 entirely → its ref clears, others survive
      (swap! msgs (fn [m] (vec (remove #(= 1 (:id %)) m))))
      (core/render r 20)
      (t/is (nil? (deref ref1)) "removed element cleared its ref")
      (t/is (some? (deref ref0)) "survivor's ref still filled"))))

(t/deftest removed-keyed-children-are-disposed-root-teardown-cascades
  (let [log (atom [])
        ids (atom [1 2])
        kid (fn [i]
              (fn [_props]
                (with-let [_ (swap! log conj [:init i])]
                  [:text {:padding-x 0 :padding-y 0} (str "k" i)]
                  (finally (swap! log conj [:dispose i])))))
        r (h/root (fn [_]
                    [:container
                     (map (fn [i] [(kid i) {:key i}]) @ids)]))]
    (core/render r 20)
    (t/is (= [[:init 1] [:init 2]] @log) "inits only, no cleanups yet")
    ;; remove id 1: its cleanup fires, id 2 untouched
    (reset! ids [2])
    (core/render r 20)
    (t/is (= [[:init 1] [:init 2] [:dispose 1]] @log))
    ;; root teardown disposes the rest through the container cascade
    (protocols/dispose r)
    (t/is (contains? (set @log) [:dispose 2]))))

(t/deftest foreign-records-reused-never-disposed
  (let [disposed (atom false)
        foreign (map->ForeignText {:text-atom (atom "f")
                                   :cache (atom nil)
                                   :disposed? disposed})
        shown (atom true)
        r (h/root (fn [_]
                    [:container (when @shown foreign)]))]
    (t/is (str/includes? (str/join "\n" (core/render r 20)) "f"))
    (t/is (false? @disposed) "still mounted")
    ;; same instance reused while present
    (t/is (str/includes? (str/join "\n" (core/render r 20)) "f"))
    ;; removal takes it out of the tree but must NOT dispose it
    (reset! shown false)
    (core/render r 20)
    (t/is (false? @disposed) "foreign record left to its owner")
    ;; teardown of the root doesn't reach it either
    (protocols/dispose r)
    (t/is (false? @disposed))))

(t/deftest reified-components-pass-through-with-identity
  ;; the record splice's sibling: a hand-rolled (reify IComponent) — the
  ;; shape extension dialogs used to arrive in before CustomDialogAdapter —
  ;; splices as a foreign child instead of hitting the cannot-compile
  ;; throw. Regression: /tools crashed the render loop because
  ;; make-dock-area fed the reified wrapper through reconcile as a bare root.
  (let [disposed (atom false)
        foreign (reify protocols/IComponent
                  (render [_this _width] ["reified"])
                  (handle-input [_this _data] nil)
                  (invalidate [_this] nil)
                  (dispose [_this] (reset! disposed true)))
        shown (atom true)
        r (h/root (fn [_]
                    [:container
                     [:text "chrome"]
                     (when @shown foreign)]))]
    (t/is (identical? foreign (h/compile-element foreign))
          "compile-element preserves identity like records")
    (t/is (str/includes? (str/join "\n" (core/render r 20)) "reified"))
    ;; same instance reused across passes while present
    (t/is (str/includes? (str/join "\n" (core/render r 20)) "reified"))
    ;; removal must NOT dispose it; teardown doesn't reach it either
    (reset! shown false)
    (core/render r 20)
    (t/is (false? @disposed) "foreign reify left to its owner")
    (protocols/dispose r)
    (t/is (false? @disposed))
    ;; dispose-tree! over a bare compiled reify must not blow up on
    ;; contains? either (a reify isn't associative)
    (h/dispose-tree! foreign)
    (t/is (false? @disposed))))

(t/deftest reified-component-as-bare-root-renders
  ;; a reify as a bare ROOT renders like a record root; [foreign] is
  ;; ELEMENT syntax ([tag ...]) — a component in the tag slot is an
  ;; invalid head, same as a record there
  (let [foreign (reify protocols/IComponent
                  (render [_this _width] ["solo"])
                  (handle-input [_this _data] nil)
                  (invalidate [_this] nil)
                  (dispose [_this] nil))]
    (t/is (= ["solo"] (mapv str/trimr (h/render-lines foreign 20))))
    (t/is (thrown-with-msg? Exception #"invalid element head"
                            (h/render-lines [foreign] 20)))
    ;; non-component scalars still throw loudly (maps have their own
    ;; bare-map-child error)
    (t/is (thrown-with-msg? Exception #"cannot compile tree node"
                            (h/render-lines :a-keyword 20)))))

(t/deftest duplicate-keys-throw
  (t/is (thrown-with-msg? Exception #"duplicate :key"
                          (h/render-lines
                           [:container
                            [:text {:key :a :padding-x 0 :padding-y 0} "1"]
                            [:text {:key :a :padding-x 0 :padding-y 0} "2"]]
                           10))))

(t/deftest leaf-prop-change-rebuilds-equal-props-reuse
  ;; display leaves rebuild when props change (identity-free); equal props
  ;; keep the instance for free
  (let [txt (atom "a")
        ref (h/ref)
        r (h/root (fn [_]
                    [:container
                     [:text {:key :t :padding-x 0 :padding-y 0 :ref ref}
                      @txt]]))]
    (core/render r 10)
    (let [i1 (deref ref)]
      (reset! txt "b")
      (core/render r 10)
      (let [i2 (deref ref)]
        (t/is (not (identical? i1 i2)) "changed content → new instance")
        (core/render r 10)
        (t/is (identical? i2 (deref ref))
              "unchanged pass reuses the instance")))))

(t/deftest body-sees-width-dynamic
  (let [r (h/root (fn [_] [:text {:padding-x 0} (str "w" h/*width*)]))]
    (t/is (str/includes? (str/join "\n" (core/render r 30)) "w30"))))

(t/deftest dep-change-schedules-a-frame-through-the-hook
  ;; §3.4 pulled forward with ComponentFn: the dep-handler invokes the
  ;; :auto-run? callback ON THE MUTATOR'S THREAD the moment a dependency
  ;; changes by = (Reagent's component path), so idle UIs wake up without
  ;; any other frame source. Default no-op keeps headless use pure.
  (let [s (atom 0)
        fired (atom 0)
        r (h/root (fn [_] [:text {:padding-x 0} (str (rag/tracked-deref s))]))]
    (core/render r 20)
    (macros/set-frame-hook! #(swap! fired inc))
    (try
      (reset! s 1)
      (t/is (= 1 @fired) "dep change fires the hook immediately")
      ;; callback scheduling never enqueues — the body reruns at the next
      ;; render's deref, and no further dep changes means no more pokes
      (rag/flush!)
      (t/is (= 1 @fired) "flush adds nothing (nothing was queued)")
      (t/is (str/includes? (str/join "\n" (core/render r 20)) "1")
            "next render brought the reaction current")
      (t/is (= 1 @fired) "rendering itself does not poke the hook")
      (finally
        (macros/set-frame-hook! nil)))))

(t/deftest counters-track-bodies-and-cache-hits
  (h/reset-counters!)
  (let [s (atom "x")
        r (h/root (fn [_] [:text {:padding-x 0} (rag/tracked-deref s)]))]
    (core/render r 20)
    (t/is (= 1 (:bodies-run (h/counters))))
    (core/render r 20)
    (t/is (= 1 (:bodies-run (h/counters))) "clean pass skipped the body")
    (t/is (= 1 (:bodies-skipped (h/counters))) "counted as cache hit")))

;; ═══════════════════════════════════════════════════════════════════════
;; Stage-3 review pass — edge-case pins (dsl.md review notes)
;; ═══════════════════════════════════════════════════════════════════════

(t/deftest stack-entry-opts-update-inner-identity-preserved
  ;; entry maps are rebuilt per pass (fresh opts), the INNER component is
  ;; matched by identity and reused — grow/shrink changes land without
  ;; tearing the widget down
  (let [inner (text/make-text "row" 0 0)
        grow (atom 1)
        r (h/root (fn [_]
                    [:v-stack (list {:component inner :grow @grow})]))]
    (core/render r 20)
    (let [vs (:c (first @(:kids r)))
          e1 (first @(:entries-atom vs))]
      (t/is (= 1 (:grow e1)) "initial opts installed")
      (reset! grow 4)
      (core/render r 20)
      (let [e2 (first @(:entries-atom vs))]
        (t/is (= 4 (:grow e2)) "updated opts installed")
        (t/is (identical? (stack/entry-component e1)
                          (stack/entry-component e2))
              "inner survived the opts change")))))

(t/deftest same-fn-twice-with-keys-independent-state
  ;; two instances of one fn element, keyed apart, keep SEPARATE with-let
  ;; state slots (each wrapper has its own store). The bodies are UNTRACKED
  ;; (no reactive reads) so the valve re-runs them every pass — three
  ;; renders, three increments EACH, independently: a shared slot would
  ;; show interleaved counts instead.
  (let [r (h/root (fn [_]
                    [:container
                     [(fn [{:keys [n]}]
                        (with-let [hits (atom 0)]
                          (swap! hits inc)
                          [:text {:padding-x 0} (str n "=" @hits)]))
                      {:key :a :n "a"}]
                     [(fn [{:keys [n]}]
                        (with-let [hits (atom 0)]
                          (swap! hits inc)
                          [:text {:padding-x 0} (str n "=" @hits)]))
                      {:key :b :n "b"}]]))]
    (core/render r 20)
    (core/render r 20)
    (let [out (str/join "\n" (core/render r 20))]
      (t/is (str/includes? out "a=3") "first instance counted its own passes")
      (t/is (str/includes? out "b=3") "second instance counted its own passes"))))

(t/deftest mid-run-invalidation-convergence-survives-with-let
  ;; a dep written DURING the body makes run-sync! loop; each iteration
  ;; begins a new pass over the store — the double-use guard must not
  ;; fire for the SAME expansion site across convergence iterations
  (let [log (atom [])
        src (atom 0)
        widget (fn [_props]
                 (with-let [_ (swap! log conj :init)]
                   (rag/tracked-deref src)
                   ;; write a tracked dep while the body runs:
                   ;; forces exactly one convergence re-run
                   (when (zero? @src) (reset! src 1))
                   [:text {:padding-x 0} "x"]))
        r (h/root (fn [_] [:container [widget {}]]))]
    (core/render r 20)
    (t/is (= [:init] @log) "one init despite the convergence re-run")
    (t/is (str/includes? (str/join "\n" (core/render r 20)) "x"))))

(t/deftest container-structural-props-are-create-time-for-now
  ;; documented stage-3 contract (dsl.md §4 pending): padding/gap changes
  ;; keeps the container INSTANCE — children survive, layout stays as
  ;; constructed. The §4 props/state migration makes these live.
  (let [pad (atom 1)
        ref (h/ref)
        r2 (h/root (fn [_]
                     [:box {:key :b :padding-x @pad}
                      [:text {:padding-x 0 :padding-y 0 :ref ref} "hi"]]))]
    (core/render r2 20)
    (let [box1 (deref ref)]
      (reset! pad 3)
      (core/render r2 20)
      (t/is (identical? box1 (deref ref))
            "structural prop change keeps the container instance"))))

(t/deftest string-children-survive-second-pass
  ;; Stage 3 review find: a matched ::string item fell into the passthrough
  ;; branch of reuse-or-build, which installed a NIL child (the parsed
  ;; string has no :c) — the text silently vanished on every pass after
  ;; the first. Strings are display leaves: rebuild on change, keep when
  ;; equal.
  (let [r (h/root (fn [_] [:container [:v-stack "hello"]]))]
    (t/is (str/includes? (str/join "\n" (core/render r 40)) "hello")
          "first pass renders")
    (t/is (str/includes? (str/join "\n" (core/render r 40)) "hello")
          "second pass keeps the string child")))

(t/deftest string-children-update-and-computed-strings-rebuild
  (let [txt (atom "hello")
        r (h/root (fn [_] [:v-stack
                           "static"
                           (when-some [t @txt] [:text {:padding-x 0} t])
                           (str "count-" (count @txt))]))]
    (core/render r 40)
    (reset! txt "hi!")
    (let [lines (str/join "\n" (core/render r 40))]
      (t/is (str/includes? lines "static") "unchanged sibling kept")
      (t/is (str/includes? lines "hi!") "text element updated")
      (t/is (str/includes? lines "count-3") "computed string rebuilt")
      (t/is (not (str/includes? lines "count-5")) "stale computed string gone"))))

(t/deftest bare-ref-as-primary-value-throws
  ;; [:widget (h/ref)] missing the props map — every other child position
  ;; rejects refs; the primary slot must not swallow one either
  (t/is (thrown-with-msg? Exception #"missing|did you mean \[text"
                          (h/render-lines [:text (h/ref)] 10)))
  (t/is (thrown? Exception (h/render-lines [:container [(h/ref)]] 10))))

(t/deftest props-only-bodies-rederive-tracked-bodies-memoize
  ;; the valve contract, both sides: framework reads (props/ctree) are
  ;; EXEMPT from dependency counting, so a body whose ONLY inputs are
  ;; props has zero real deps and re-derives every pass (correctness
  ;; first — an untracked read anywhere must never poison the cache;
  ;; per-frame re-derivation is the documented batched fallback). A body
  ;; reading app state through tracked-deref/cursors/slices HAS a real
  ;; dep and memoizes until it changes.
  (h/reset-counters!)
  (let [r (h/root (fn [{:keys [label]}]
                    [:text {:padding-x 0} label]))]
    (core/render r 20)
    (core/render r 20)
    (t/is (= 2 (:bodies-run (h/counters))) "props-only: rederived")
    (t/is (zero? (:bodies-skipped (h/counters))) "nothing cached"))
  (h/reset-counters!)
  (let [s (atom "x")
        r (h/root (fn [_] [:text {:padding-x 0} (rag/tracked-deref s)]))]
    (core/render r 20)
    (core/render r 20)
    (t/is (= 1 (:bodies-run (h/counters))) "tracked: ran once")
    (t/is (= 1 (:bodies-skipped (h/counters))) "tracked: cached")))

;; ── stage-3 review fixes ──────────────────────────────────────────────────

(t/deftest keyed-element-switching-kind-remounts
  ;; same :key, different element kind: remount (React semantics), not a
  ;; crash — the keyed bucket carries the kind, so a host↔fn switch under
  ;; one key retires + constructs instead of reusing across kinds
  (let [sw (atom false)
        disposed (atom false)
        myfn (fn [_]
               (with-let [] (finally (reset! disposed true)))
               [:text {:padding-x 0} "FN"])
        r (h/root (fn [_]
                    [:container {}
                     (if @sw
                       [:box {:key "k"} [:text {:padding-x 0} "BOX"]]
                       [myfn {:key "k"}])]))]
    (t/is (str/includes? (str/join "\n" (core/render r 40)) "FN"))
    (reset! sw true)
    (let [out (str/join "\n" (core/render r 40))]
      (t/is (str/includes? out "BOX") "kind switch renders the new element")
      (t/is @disposed "the retired fn instance was disposed"))
    (reset! sw false)
    (t/is (str/includes? (str/join "\n" (core/render r 40)) "FN")
          "switching back remounts instead of crashing")))

(t/deftest entry-map-first-child-is-child-not-props
  ;; {:component c} in the first slot is a stack ENTRY, not a props map —
  ;; consuming it as props silently dropped the child it wraps
  (let [inner (text/make-text "INNER" 0 0)]
    (t/is (str/includes? (joined [:v-stack {:component inner}] 20) "INNER")
          "entry map without a props map still mounts its component")
    (t/is (thrown? Throwable
                   (joined [:box {:component (text/make-text "X" 0 0)}] 20))
          "entry map outside a stack tag still throws loudly")))

(t/deftest width-change-invalidates-idle-wrappers
  ;; *width* shapes output but is not a tracked dep — a resize must force
  ;; one re-derive of an idle reaction, which then re-caches (track!'s
  ;; per-width cache contract, one level up)
  (let [st (atom "S")
        r (h/root (fn [_]
                    [:text {:padding-x 0}
                     (str (rag/tracked-deref st) "/w" h/*width*)]))]
    (core/render r 20)
    (h/reset-counters!)
    (t/is (str/includes? (str/join "\n" (core/render r 60)) "S/w60")
          "resize alone re-derives the body at the new width")
    (t/is (= 1 (:bodies-run (h/counters))) "exactly one forced run")
    (core/render r 60)
    (t/is (= 1 (:bodies-run (h/counters))) "same width stays memoized")
    (reset! st "T")
    (t/is (str/includes? (str/join "\n" (core/render r 60)) "T/w60"))
    (t/is (= 2 (:bodies-run (h/counters))) "dep change still re-derives")))
