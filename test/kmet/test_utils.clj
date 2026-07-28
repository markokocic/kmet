(ns kmet.test-utils
  (:require [clojure.test :as t]
            [kmet.tui.utils :as u]))

(t/deftest test-visible-width
  (t/is (= 0 (u/visible-width "")))
  (t/is (= 5 (u/visible-width "hello")))
  (t/is (= 4 (u/visible-width "中文")))
  (t/is (= 2 (u/visible-width "🍎")))
  (t/is (= 6 (u/visible-width "中文🍎")))   ;; 2+2+2=6
  (t/is (= 7 (u/visible-width "ab\tc"))))   ;; 1+1+4+1=7

(t/deftest test-truncate-to-width
  (t/is (= "hello" (u/truncate-to-width "hello" 10)))
  (t/is (= "" (u/truncate-to-width "" 5)))
  (t/is (<= (u/visible-width (u/truncate-to-width "hello world" 5)) 5)))
