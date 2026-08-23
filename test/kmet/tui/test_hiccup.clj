(ns kmet.tui.test-hiccup
  "Headless tests for the hiccup construction layer (dsl.md §2, stage 2).
   Cases adapted from hiccup's own compiler tests where they transfer
   (normalization, nil/seq handling, loud head validation) plus the kmet-
   specific contracts: closed tag table with did-you-mean, leaf-tag throw,
   :primary shorthand, :key/:ref stripping, record/map passthrough, and
   root re-derivation."
  (:require [clojure.string :as str]
            [clojure.test :as t]
            [kmet.tui.components.text :as text]
            [kmet.tui.core :as core]
            [kmet.tui.hiccup :as h]))

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

(t/deftest stack-entry-maps-pass-through
  ;; VStack/HStack accept entry maps alongside components — compile must
  ;; not touch them
  (let [entry {:component (text/make-text "e" 0 0) :height 1}]
    (t/is (identical? entry (h/compile-element entry)))))

;; ── pseudo-props ─────────────────────────────────────────────────────────

(t/deftest key-and-ref-pseudo-props-are-stripped
  ;; ctors never see them; no unknown-key crash, no behavior change vs the
  ;; same tree without them
  (let [tree (fn [extra]
               [:box extra
                [:text (merge {:padding-x 0 :padding-y 0} extra) "hi"]])]
    (t/is (= (h/render-lines (tree {}) 20)
             (h/render-lines (tree {:key 7 :ref :fake}) 20)))
    ;; box: padding-y 1 puts the child at line index 1; box's own blank
    ;; lines trim to empty strings
    (t/is (= ["" " hi" ""]
             (mapv str/trimr (h/render-lines (tree {:key 1}) 20))))))

;; ── loud validation (hiccup drops void-tag content silently; we throw) ──

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

(t/deftest fn-heads-throw-until-component-fn
  (t/is (thrown? Exception (h/render-lines [(fn [_] [:text "x"]) {}] 10))))

(t/deftest children-on-leaf-tags-throw
  (t/is (thrown? Exception (h/render-lines [:text "a" "b"] 10)))
  (t/is (thrown? Exception (h/render-lines [:spacer 2 [:text "x"]] 10))))

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

(t/deftest root-of-seq-tree-renders-all-roots
  (let [r (h/root [[:text {:padding-x 0 :padding-y 0} "one"]
                   [:text {:padding-x 0 :padding-y 0} "two"]])]
    (t/is (= ["one" "two"]
             (mapv str/trimr (core/render r 10))))))
