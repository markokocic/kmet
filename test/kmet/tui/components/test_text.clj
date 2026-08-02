(ns kmet.tui.components.test-text
  (:require [clojure.test :as t]
            [kmet.tui.core :as core]
            [kmet.tui.utils :as u]
            [kmet.tui.components.text :as text]))

(t/deftest test-text-create
  (let [t (text/make-text "hello" 1 1)]
    (t/is (satisfies? core/IComponent t))
    (t/is (some? (:text-atom t)))))

(t/deftest test-text-render-simple
  (let [t (text/make-text "hi" 0 0)
        lines (core/render t 10)]
    (t/is (= 1 (count lines)))
    (t/is (.contains (first lines) "hi"))))

(t/deftest test-text-render-padding
  (let [t (text/make-text "hi" 1 1)
        lines (core/render t 10)]
    ;; With padding-y=1: 1 empty top + 1 content + 1 empty bottom = 3 lines
    (t/is (= 3 (count lines)))
    (t/is (= 10 (count (first lines))))
    (t/is (= 10 (count (second lines))))
    (t/is (= 10 (count (nth lines 2))))))

(t/deftest test-text-set!
  (let [t (text/make-text "a" 0 0)]
    (text/text-set! t "b")
    (let [lines (core/render t 5)]
      (t/is (.contains (first lines) "b")))))

(t/deftest test-text-word-wrap
  (let [t (text/make-text "hello world foo" 0 0)
        lines (core/render t 6)]
    (t/is (> (count lines) 1))
    (doseq [l lines]
      (t/is (<= (u/visible-width l) 6)))))

(t/deftest test-text-empty
  (let [t (text/make-text "" 0 0)
        lines (core/render t 10)]
    (t/is (vector? lines))))
