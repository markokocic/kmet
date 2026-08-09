(ns kmet.test-utils
  (:require [clojure.string :as str]
            [clojure.test :as t]
            [kmet.tui.utils :as u]))

(t/deftest test-visible-width
  (t/is (= 0 (u/visible-width "")))
  (t/is (= 5 (u/visible-width "hello")))
  (t/is (= 4 (u/visible-width "中文")))
  (t/is (= 2 (u/visible-width "🍎")))
  (t/is (= 6 (u/visible-width "中文🍎")))   ;; 2+2+2=6
  (t/is (= 6 (u/visible-width "ab\tc"))))   ;; tab expands to 3 spaces (pi)

(t/deftest test-truncate-to-width
  (t/is (= "hello" (u/truncate-to-width "hello" 10)))
  (t/is (= "" (u/truncate-to-width "" 5)))
  (t/is (<= (u/visible-width (u/truncate-to-width "hello world" 5)) 5)))

(t/deftest test-truncate-to-width-pi-parity
  ;; pi: truncateToWidth guards on max-width — fitting text is never
  ;; truncated even when an ellipsis is requested
  (t/is (= "abcdef" (u/truncate-to-width "abcdef" 6 "...")))
  (t/is (= "a very ..." (u/truncate-to-width "a very long line here" 10 "..."))
        "kept prefix + ellipsis = max-width")
  (t/is (= "hello..." (u/truncate-to-width "hello world foo" 8 "...")))
  ;; pi: truncateFragmentToWidth — an ellipsis wider than max-width is clipped
  (t/is (= ".." (u/truncate-to-width "hello world" 2 "...")))
  (t/is (= "" (u/truncate-to-width "hello world" 0 "..."))))

(t/deftest test-truncate-to-width-osc-8-close
  ;; pi: getActiveOsc8Close — cutting through a hyperlink label must close
  ;; the link before the ellipsis so following text isn't swallowed by it
  (t/testing "BEL-terminated link is closed with BEL"
    (let [link "\u001b]8;;https://example.com\u0007"
          result (u/truncate-to-width (str link "hello world") 9 "…")]
      (t/is (= (str link "hello wo\u001b]8;;\u0007…") result))
      (t/is (= 9 (u/visible-width result)))))
  (t/testing "ST-terminated link is closed with ST"
    (let [link "\u001b]8;;https://example.com\u001b\\"
          result (u/truncate-to-width (str link "hello world") 5 "…")]
      (t/is (= (str link "hell\u001b]8;;\u001b\\…") result))
      (t/is (= 5 (u/visible-width result)))))
  (t/testing "an already-closed link is not closed again"
    (let [text "\u001b]8;;https://example.com\u0007ok\u001b]8;;\u0007hello world"
          result (u/truncate-to-width text 6 "…")]
      (t/is (str/ends-with? result "hel…"))
      (t/is (not (str/includes? result "\u001b]8;;\u0007\u001b]8;;\u0007")) "no doubled close")
      (t/is (= 6 (u/visible-width result))))))

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

(t/deftest test-wrap-ansi-multiword-styled-line
  ;; A styled multi-word line (edit diff / bash output: one fg code at the
  ;; start, reset at the end) must re-open the style on every wrapped
  ;; continuation line — previously only the first line kept it.
  (let [styled (str "\u001b[38;5;245m" (clojure.string/join " " (repeat 8 "word123456")) "\u001b[39m")
        lines (u/wrap-text-with-ansi styled 20)]
    (t/is (>= (count lines) 2) "line wraps")
    (doseq [l lines]
      (t/is (clojure.string/includes? l "\u001b[38")
            (str "every wrapped line is re-styled: " (pr-str l))))
    (t/is (= 87 (apply + (map u/visible-width lines))) "all characters kept")))

(t/deftest test-wrap-ansi-fast-path-first-piece-styled
  ;; A styled word that must be broken across lines (ASCII fast path) — the
  ;; FIRST piece starts a fresh line and must re-open the style, not just
  ;; the continuations (pi: breakLongWord starts with getActiveCodes).
  (let [styled (str "\u001b[31mone two three\u001b[0m")
        lines (u/wrap-text-with-ansi styled 3)]
    (doseq [l lines]
      (t/is (clojure.string/includes? l "\u001b[31m")
            (str "every piece is re-styled: " (pr-str l))))
    (t/is (= 13 (apply + (map u/visible-width lines))) "all characters kept")))

(t/deftest test-wrap-ansi-styles-survive-literal-newline
  ;; A style left open on one line is re-emitted on the next input line (pi:
  ;; wrapTextWithAnsi carries the tracker across input lines).
  (let [styled "\u001b[31mline one words here\nline two words here\u001b[0m"
        lines (u/wrap-text-with-ansi styled 12)]
    (t/is (>= (count lines) 2))
    (doseq [l lines]
      (t/is (clojure.string/includes? l "\u001b[31m")
            (str "each line is re-styled: " (pr-str l))))    (t/is (= 38 (apply + (map u/visible-width lines))))))

(t/deftest test-wrap-cjk-width
  (let [lines (u/wrap-text-with-ansi (clojure.string/join "" (repeat 15 "汉字")) 22)]
    (t/is (= [22 22 16] (mapv u/visible-width lines))
          "wide characters counted as 2 columns")
    (t/is (= 60 (apply + (map u/visible-width lines))))))

;; ─── ANSI-aware slicing + compositing (HStack support) ─────────────────────

(t/deftest test-slice-with-width-plain
  (t/is (= {:text "hello" :width 5} (u/slice-with-width "hello world" 0 5 :strict? true)))
  (t/is (= {:text "world" :width 5} (u/slice-with-width "hello world" 6 5 :strict? true)))
  (t/is (= {:text "" :width 0} (u/slice-with-width "hello" 0 0 :strict? true))))

(t/deftest test-slice-with-width-ansi
  ;; ANSI codes before the window are prepended to the first kept character
  (let [styled (str "\u001b[31m" "red" "\u001b[39m" "tail")
        s (u/slice-with-width styled 0 3 :strict? true)]
    (t/is (= 3 (:width s)))
    (t/is (str/includes? (:text s) "red"))))

(t/deftest test-slice-with-width-strict
  ;; strict? excludes a wide char crossing the end boundary
  (let [s (u/slice-with-width "中文x" 0 3 :strict? true)]
    (t/is (= 2 (:width s)) "second 2-wide char would cross col 3 → excluded")))

(t/deftest test-composite-line-plain
  (t/is (= "...X......" (u/composite-line ".........." "X" 3 1 10)))
  (t/is (= "ab cd" (str/trim (u/composite-line "ab        " "cd" 3 2 10)))))

(t/deftest test-composite-line-ansi-overlay
  (let [out (u/composite-line ".........." (str "\u001b[31m" "XX" "\u001b[39m") 3 2 10)]
    (t/is (= 10 (u/visible-width out)))
    (t/is (str/includes? out "\u001b[31m") "overlay styling kept")))

(t/deftest test-composite-line-inherits-base-style
  ;; The after segment keeps the base's background across the overlay region
  (let [bg (str "\u001b[41m" "abcdefghij" "\u001b[49m")
        out (u/composite-line bg "XX" 3 2 10)]
    (t/is (= 10 (u/visible-width out)))
    (t/is (str/includes? out "abc"))
    (t/is (str/includes? out "fghij"))
    (t/is (str/includes? out "\u001b[41m") "after content restyled with the base bg")))

(t/deftest test-sgr-state-at
  (t/is (= "" (u/sgr-state-at "plain" 0)))
  (let [styled (str "\u001b[31m" "ab" "\u001b[39m" "cd")]
    (t/is (= "\u001b[31m" (u/sgr-state-at styled 0)))
    (t/is (= "\u001b[31m" (u/sgr-state-at styled 1)))
    (t/is (= "" (u/sgr-state-at styled 2)) "reset applied at col 2")))

(t/deftest test-composite-line-image-passthrough
  ;; pi: compositeTuiLine returns kitty image lines untouched
  (let [img-line (str "\u001b_Gf=100,m=0;" "aGVsbG8=" "\u001b\\" "rest")]
    (t/is (= img-line (u/composite-line img-line "XX" 3 2 10)))))

(t/deftest test-slice-with-width-graphemes-agree-with-visible-width
  ;; skin-tone cluster and flag pair measure 2 columns in both
  (let [s "👍🏽"]
    (t/is (= 2 (:width (u/slice-with-width s 0 10 :strict? true))))
    (t/is (= (u/visible-width s) (:width (u/slice-with-width s 0 10 :strict? true)))))
  (t/is (= 2 (:width (u/slice-with-width "🇺🇸" 0 10 :strict? true)))))
