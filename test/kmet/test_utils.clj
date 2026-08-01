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

(t/deftest test-wrap-long-unbreakable-word
  ;; A single word longer than the width wraps across lines (pi: breakLongWord)
  ;; instead of being clipped — every character is preserved.
  (let [lines (u/wrap-text-with-ansi (apply str (repeat 60 "x")) 22)]
    (t/is (= [22 22 16] (mapv u/visible-width lines)))
    (t/is (= 60 (apply + (map u/visible-width lines))) "no characters lost")))

(t/deftest test-wrap-no-spurious-blank
  ;; Regression: a too-long word at line start used to emit an empty line.
  (let [lines (u/wrap-text-with-ansi (str (apply str (repeat 60 "x")) " more") 22)]
    (t/is (not-any? empty? lines) "no blank lines from word wrapping")
    (t/is (= 65 (apply + (map u/visible-width lines))) "all characters kept")
    (t/is (clojure.string/includes? (last lines) "more")
      "a following word joins the long word's last piece")))

(t/deftest test-wrap-ansi-survives-break
  ;; The fg escape on a styled long word is re-applied to continuation lines.
  (let [styled (str "\u001b[38;2;212;212;212m" (apply str (repeat 60 "x")) "\u001b[39m")
        lines (u/wrap-text-with-ansi styled 22)]
    (t/is (= 60 (apply + (map u/visible-width lines))))
    (t/is (clojure.string/includes? (first lines) "\u001b[38")
      "first piece keeps the fg code")
    (t/is (clojure.string/includes? (second lines) "\u001b[38")
      "continuation piece is re-styled")))

(t/deftest test-wrap-cjk-width
  (let [lines (u/wrap-text-with-ansi (clojure.string/join "" (repeat 15 "汉字")) 22)]
    (t/is (= [22 22 16] (mapv u/visible-width lines))
      "wide characters counted as 2 columns")
    (t/is (= 60 (apply + (map u/visible-width lines))))))
