(ns kmet.tui.components.test-dynamic-border
  (:require [clojure.string :as str]
            [clojure.test :as t :refer [testing]]
            [kmet.tui.components.dynamic-border :as db]
            [kmet.tui.core :as core]
            [kmet.tui.hiccup :as h]
            [kmet.tui.utils :as utils]))

(defn- plain
  "Strip ANSI codes from a rendered line (default color fn themes the border)."
  [line]
  (utils/strip-ansi-codes line))

(t/deftest test-default-renders-full-width-border
  (testing "default color fn renders a full-width ─ line"
    (let [c (db/make-dynamic-border)]
      (t/is (= "──────────" (plain (first (core/render c 10)))))
      (t/is (= "─" (plain (first (core/render c 1)))))))
  (testing "default uses the :border theme color"
    (let [c (db/make-dynamic-border)]
      (t/is (str/includes? (first (core/render c 3)) "\u001b[")
            "themed output carries ANSI color codes"))))

(t/deftest test-width-0-clamps-to-1
  (testing "zero width renders a single border char (pi: Math.max(1, width))"
    (let [c (db/make-dynamic-border)]
      (t/is (= "─" (plain (first (core/render c 0))))))))

(t/deftest test-custom-color-fn
  (testing "custom color fn is applied to the border string"
    (let [c (db/make-dynamic-border (fn [s] (str "<" s ">")))]
      (t/is (= ["<────>"] (core/render c 4))))))

(t/deftest test-border-styles
  (testing ":ascii draws the fallback rule"
    (let [c (db/make-dynamic-border nil :ascii)]
      (t/is (= "----" (plain (first (core/render c 4)))))))
  (testing ":rounded differs from :normal only in frame corners, not rules"
    (let [c (db/make-dynamic-border nil :rounded)]
      (t/is (= "────" (plain (first (core/render c 4)))))))
  (testing ":hidden keeps the footprint with no ink"
    (let [c (db/make-dynamic-border nil :hidden)]
      (t/is (= "    " (plain (first (core/render c 4)))))))
  (testing ":none renders no line at all"
    (let [c (db/make-dynamic-border nil :none)]
      (t/is (= [] (core/render c 4)))))
  (testing "a partial map merges over :normal"
    (let [c (db/make-dynamic-border nil {:top "="})]
      (t/is (= "====" (plain (first (core/render c 4)))))))
  (testing "an unknown style fails loudly at construction"
    (t/is (thrown-with-msg? Exception #"unknown border style"
                            (db/make-dynamic-border nil :rounnded)))))

(t/deftest test-border-prop-through-the-dsl
  ;; the :dynamic-border tag forwards :border to the constructor
  (t/is (= ["===="]
           (mapv plain (h/render-lines [:dynamic-border {:color-fn identity :border {:top "="}}] 4))))
  (t/is (= []
           (h/render-lines [:dynamic-border {:color-fn identity :border :none}] 4)))
  (t/is (= ["────"]
           (mapv plain (h/render-lines [:dynamic-border {:color-fn identity}] 4)))
        "the default is unchanged"))

(t/deftest test-invalidate-noop
  (testing "invalidate is a no-op (no cached state, pi parity)"
    (let [c (db/make-dynamic-border)]
      (core/invalidate c)
      (t/is (= "───" (plain (first (core/render c 3))))))))
