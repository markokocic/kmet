(ns kmet.tui.components.test-container
  (:require [clojure.test :as t]
            [kmet.tui.core :as core]
            [kmet.tui.components.container :as container]
            [kmet.tui.components.text :as text]
            [kmet.tui.macros :as macros]))

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

(defn- tracked?
  "True when COMP is registered in the track! watch registry (its render ran
   with watches)."
  [comp]
  (contains? @(deref #'macros/watch-registry)
             (keyword (str "track!" (System/identityHashCode comp)))))

(t/deftest test-container-replace-children-disposes-dropped
  (let [kept (text/make-text "keep" 0 0)
        dropped (text/make-text "drop" 0 0)
        c (container/make-container [kept dropped])]
    (core/render c 10)
    (t/is (tracked? kept))
    (t/is (tracked? dropped))
    ;; replace: KEPT is re-added (same instance), a new child takes DROPPED's
    ;; place — the dropped child is disposed, its watches torn down
    (let [fresh (text/make-text "fresh" 0 0)]
      (container/container-replace-children! c [kept fresh])
      (t/is (= 2 (count @(:children c))))
      (t/is (not (tracked? dropped)) "dropped child disposed (no zombie watches)")
      (t/is (tracked? kept) "kept child untouched")
      (core/render c 10)
      (t/is (tracked? fresh)))))

(t/deftest test-container-replace-children-empty
  (let [t1 (text/make-text "a" 0 0)
        c (container/make-container [t1])]
    (core/render c 5)
    (container/container-replace-children! c [])
    (t/is (empty? @(:children c)))
    (t/is (not (tracked? t1)) "clearing disposes every child")
    (t/is (empty? (core/render c 5)))))
