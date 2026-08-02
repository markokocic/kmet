(ns kmet.tui.components.test-stack
  (:require [clojure.test :as t]
            [kmet.tui.components.stack :as s]))

(t/deftest test-stack-entry?
  (t/is (s/stack-entry? {:component :c}))
  (t/is (not (s/stack-entry? nil)))
  (t/is (not (s/stack-entry? [:c]))))

(t/deftest test-entry-component
  (t/is (= :c (s/entry-component {:component :c})))
  (t/is (= :plain (s/entry-component :plain))))

(t/deftest test-visible-stack-entries
  (let [entries [{:component :a :visible (constantly true)}
                 {:component :b :visible (constantly false)}
                 {:component :c}]]
    (t/is (= [:a :c] (mapv :component (s/visible-stack-entries entries {:width 10 :height 10}))))))

(t/deftest test-allocate-grow
  ;; pi distributes the leftover round-robin within a pass (remaining
  ;; decrements per candidate), so unequal splits are not perfectly even
  (t/is (= [10 3 7]
           (s/allocate-stack-sizes [{:grow 1} {} {:grow 1}] [5 3 4] 20 0)))
  (t/is (= [5 5]
           (s/allocate-stack-sizes [{:grow 1} {:grow 1}] [4 4] 10 0)))
  ;; grow weights 2:1 over 6 extra columns → [5,1] (pi: floor-based round-robin)
  (t/is (= [5 1]
           (s/allocate-stack-sizes [{:grow 2} {:grow 1}] [0 0] 6 0))))

(t/deftest test-allocate-shrink
  (t/is (= [3 2 3]
           (s/allocate-stack-sizes [{} {} {}] [5 3 4] 8 0)))
  (t/is (= [6 2]
           (s/allocate-stack-sizes [{:min-size 6} {}] [5 3] 8 0))))

(t/deftest test-allocate-basis
  (t/is (= [4 3]
           (s/allocate-stack-sizes [{:basis 4} {}] [5 3] 12 0))))

(t/deftest test-allocate-normalizes-options
  ;; pi normalizes grow/shrink/min/max at addChild (floored, ≥ 0)
  (t/is (= [1 1] (s/allocate-stack-sizes [{:grow 0.5} {:grow 0.5}] [1 1] 6 0))
        "fractional grow 0.5 → 0 → no growth")
  (t/is (= [5 3] (s/allocate-stack-sizes [{:shrink 0.5} {:shrink 1}] [5 5] 8 0))
        "fractional shrink 0.5 → 0 → only the sibling shrinks")
  (t/is (= [2 8] (s/allocate-stack-sizes [{:grow 1 :max-size 2.5} {:grow 1}] [1 1] 10 0))
        "fractional max-size 2.5 → 2 → integer capacity"))

(t/deftest test-allocate-gap
  ;; gap reduces the content width available to children
  (t/is (= [5 3] (s/allocate-stack-sizes [{} {}] [5 3] 10 1))
        "content 9 ≥ total 8 → sizes unchanged"))
