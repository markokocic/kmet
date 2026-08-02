(ns kmet.tui.components.test-container
  (:require [clojure.test :as t]
            [kmet.tui.core :as core]
            [kmet.tui.components.container :as container]
            [kmet.tui.components.text :as text]))

(t/deftest test-container-create
  (let [c (container/make-container)]
    (t/is (satisfies? core/IComponent c))
    (t/is (empty? @(:children c)))))

(t/deftest test-container-render
  (let [t1 (text/make-text "a" 0 0)
        t2 (text/make-text "b" 0 0)
        c (container/make-container [t1 t2])
        lines (core/render c 5)]
    (t/is (= 2 (count lines)))
    (t/is (.contains (first lines) "a"))
    (t/is (.contains (second lines) "b"))))

(t/deftest test-container-add-remove
  (let [t1 (text/make-text "a" 0 0)
        t2 (text/make-text "b" 0 0)
        c (container/make-container [t1])]
    (t/is (= 1 (count (core/render c 5))))
    (container/container-add-child c t2)
    (t/is (= 2 (count (core/render c 5))))
    (container/container-remove-child c t1)
    (t/is (= 1 (count (core/render c 5))))))

(t/deftest test-container-clear
  (let [t1 (text/make-text "a" 0 0)
        c (container/make-container [t1])]
    (container/container-clear c)
    (let [lines (core/render c 5)]
      (t/is (empty? lines)))))
