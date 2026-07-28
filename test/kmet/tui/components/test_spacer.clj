(ns kmet.tui.components.test-spacer
  (:require [clojure.test :as t]
            [kmet.tui.core :as core]
            [kmet.tui.components.spacer :as spacer]))

(t/deftest test-spacer-default
  (let [s (spacer/make-spacer)]
    (let [lines (core/render s 10)]
      (t/is (= 1 (count lines)))
      (t/is (= "" (first lines))))))

(t/deftest test-spacer-n
  (let [s (spacer/make-spacer 3)]
    (let [lines (core/render s 10)]
      (t/is (= 3 (count lines)))
      (doseq [l lines]
        (t/is (= "" l))))))

(t/deftest test-spacer-zero
  (let [s (spacer/make-spacer 0)]
    (let [lines (core/render s 10)]
      (t/is (empty? lines)))))
