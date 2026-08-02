(ns kmet.tui.components.test-h-stack
  (:require [clojure.string :as str]
            [clojure.test :as t]
            [kmet.tui.core :as core]
            [kmet.tui.utils :as u]
            [kmet.tui.components.h-stack :as hs]))

(defn- plain [lines]
  (mapv #(u/strip-ansi-codes %) lines))

(defn- trim-right [s]
  (str/replace s #"\s+$" ""))

;; Components that render at their natural width (no padding), like pi's
;; layout tests use, make intrinsic measurement meaningful.
(defn- natural-text
  "A Text-like component rendering TEXT unpadded (exact width)."
  [text]
  (reify core/IComponent
    (render [_ _width] [text])
    (handle-input [_ _] nil)
    (invalidate [_])))

(t/deftest test-create
  (let [c (hs/make-h-stack [(natural-text "a")])]
    (t/is (satisfies? core/IComponent c))
    (t/is (some? (:entries-atom c)))))

(t/deftest test-composes-children-at-allocated-widths
  ;; pi layout.test.ts: HStack with explicit basis composes side by side
  (let [c (hs/make-h-stack [{:component (natural-text "left") :basis 6 :shrink 0}
                            {:component (natural-text "right") :basis 6 :shrink 0}])
        line (trim-right (first (plain (core/render c 12))))]
    (t/is (= "left  right" line))
    (let [c (hs/make-h-stack [{:component (natural-text "left") :basis 6 :shrink 0}
                              {:component (natural-text "right") :basis 6 :shrink 0}]
                             :gap 1)
          line (trim-right (first (plain (core/render c 13))))]
      (t/is (= "left   right" line) "gap adds a column between the slots"))))

(t/deftest test-gap
  (let [c (hs/make-h-stack [{:component (natural-text "ab") :basis 2 :shrink 0}
                            {:component (natural-text "cd") :basis 2 :shrink 0}]
                           :gap 1)
        line (trim-right (first (plain (core/render c 10))))]
    (t/is (= "ab cd" line))))

(t/deftest test-right-align-with-flex-grow
  (let [c (hs/make-h-stack [{:component (natural-text "L") :basis 1 :shrink 0}
                            {:component (natural-text "") :grow 1}
                            {:component (natural-text "R") :basis 1 :shrink 0}])
        line (trim-right (first (plain (core/render c 10))))]
    (t/is (= "L        R" line) "flex spacer absorbs the remaining width")))

(t/deftest test-grow-distributes-extra-width
  ;; pi distributes the extra 6 columns round-robin within a pass (remaining
  ;; decrements per candidate) → slots [6,4], so the unpadded children sit
  ;; at x=0 and x=6
  (let [c (hs/make-h-stack [{:component (natural-text "aa") :basis 2 :grow 1}
                            {:component (natural-text "bb") :basis 2 :grow 1}])
        line (trim-right (first (plain (core/render c 10))))]
    (t/is (= "aa    bb" line)))
  ;; grow respects :max-size
  (let [c (hs/make-h-stack [{:component (natural-text "a") :basis 1 :grow 1 :max-size 2}
                            {:component (natural-text "b") :basis 1 :grow 1}])
        line (trim-right (first (plain (core/render c 10))))]
    (t/is (= "a b" line) "first child capped at 2 wide, rest goes to sibling")))

(t/deftest test-zero-width-child-not-painted
  ;; pi layout.test.ts: zero-width children are skipped
  (let [c (hs/make-h-stack [{:component (natural-text "hidden") :basis 0 :shrink 0}
                            {:component (natural-text "shown") :basis 0 :grow 1}])
        line (trim-right (first (plain (core/render c 5))))]
    (t/is (= "shown" line))))

(t/deftest test-align-center
  (let [tall (reify core/IComponent
               (render [_ _] ["top" "mid" "bot"])
               (handle-input [_ _] nil)
               (invalidate [_]))
        c (hs/make-h-stack [{:component tall :basis 3 :shrink 0}
                            {:component (natural-text "x") :basis 1 :shrink 0}]
                           :align :center)
        lines (plain (core/render c 6))]
    (t/is (= 3 (count lines)))
    (t/is (re-find #"x" (second lines)) "short child centered vertically")))

(t/deftest test-empty
  (t/is (= [] (core/render (hs/make-h-stack []) 10))))

(t/deftest test-handle-input-not-forwarded
  ;; pi: containers/stacks don't receive input — keys go to the focused leaf
  (let [got (atom nil)
        child (reify core/IComponent
                (render [_ _] [""])
                (handle-input [_ data] (reset! got data))
                (invalidate [_]))
        c (hs/make-h-stack [child])]
    (core/handle-input c "x")
    (t/is (nil? @got) "input is not forwarded to children")))
