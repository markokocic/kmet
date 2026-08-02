(ns kmet.tui.components.test-v-stack
  (:require [clojure.test :as t]
            [kmet.tui.core :as core]
            [kmet.tui.utils :as u]
            [kmet.tui.components.text :as text]
            [kmet.tui.components.v-stack :as vs]))

(defn- plain [lines]
  (mapv #(u/strip-ansi-codes %) lines))

(t/deftest test-create
  (let [c (vs/make-v-stack [(text/make-text "a" 0 0)])]
    (t/is (satisfies? core/IComponent c))
    (t/is (some? (:entries-atom c)))))

(t/deftest test-renders-children-top-to-bottom
  (let [lines (plain (core/render (vs/make-v-stack [(text/make-text "a" 0 0)
                                                    (text/make-text "b" 0 0)])
                                  5))]
    (t/is (= 2 (count lines)))
    (t/is (re-find #"a" (first lines)))
    (t/is (re-find #"b" (second lines)))))

(t/deftest test-gap
  (let [lines (plain (core/render (vs/make-v-stack [(text/make-text "a" 0 0)
                                                    (text/make-text "b" 0 0)]
                                                   :gap 2)
                                  5))]
    (t/is (= 4 (count lines)) "2 children + 2 gap lines between")
    (t/is (= "" (second lines)))
    (t/is (= "" (nth lines 2)))))

(t/deftest test-entry-maps
  (let [hidden (text/make-text "x" 0 0)
        shown (text/make-text "y" 0 0)
        c (vs/make-v-stack [{:component hidden :visible (constantly false)}
                            shown])
        lines (plain (core/render c 5))]
    (t/is (= 1 (count lines)))
    (t/is (re-find #"y" (first lines)))))

(t/deftest test-empty
  (t/is (= [] (core/render (vs/make-v-stack []) 10))))

(t/deftest test-add-remove-clear
  (let [a (text/make-text "a" 0 0)
        b (text/make-text "b" 0 0)
        c (vs/make-v-stack [a])]
    (vs/v-stack-add-child! c b)
    (t/is (= 2 (count (plain (core/render c 5)))))
    (vs/v-stack-remove-child! c a)
    (t/is (= 1 (count (plain (core/render c 5)))))
    (vs/v-stack-clear! c)
    (t/is (= [] (core/render c 5)))))

(t/deftest test-handle-input-not-forwarded
  ;; pi: containers/stacks don't receive input — keys go to the focused leaf
  (let [got (atom nil)
        child (reify core/IComponent
                (render [_ _] [""])
                (handle-input [_ data] (reset! got data))
                (invalidate [_]))
        c (vs/make-v-stack [child])]
    (core/handle-input c "x")
    (t/is (nil? @got) "input is not forwarded to children")))

(t/deftest test-invalidate-forwards
  (let [invalidated (atom 0)
        child (reify core/IComponent
                (render [_ _] [""])
                (handle-input [_ _] nil)
                (invalidate [_] (swap! invalidated inc)))
        c (vs/make-v-stack [child])]
    (core/invalidate c)
    (t/is (= 1 @invalidated))))
