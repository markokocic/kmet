(ns kmet.tui.components.test-dynamic-border
  (:require [clojure.string :as str]
            [clojure.test :as t :refer [testing]]
            [kmet.tui.components.dynamic-border :as db]
            [kmet.tui.core :as core]
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

(t/deftest test-invalidate-noop
  (testing "invalidate is a no-op (no cached state, pi parity)"
    (let [c (db/make-dynamic-border)]
      (core/invalidate c)
      (t/is (= "───" (plain (first (core/render c 3))))))))
