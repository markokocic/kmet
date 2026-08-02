(ns kmet.test-editing
  "Regression tests for shared editing primitives (kmet.tui.components.editing)."
  (:require [clojure.test :as t]
            [kmet.tui.components.editing :as edit]))

;; ─── Grapheme helpers ──────────────────────────────────────────────────────
;; BreakIterator works on Java char indices, not byte offsets.
;; "中文" is 2 chars (not 6 bytes) in Java's UTF-16.

(t/deftest test-grapheme-left
  (t/is (= 0 (edit/grapheme-left "" 0)))
  (t/is (= 0 (edit/grapheme-left "abc" 0)))
  (t/is (= 0 (edit/grapheme-left "abc" 1)))
  (t/is (= 1 (edit/grapheme-left "abc" 2)))
  (t/is (= 2 (edit/grapheme-left "abc" 3)))
  ;; CJK: each char is one Java char
  (t/is (= 0 (edit/grapheme-left "中文" 1)) "CJK: left from second char")
  (t/is (= 1 (edit/grapheme-left "中文" 2)) "CJK: left from past end"))

(t/deftest test-grapheme-right
  (t/is (= 0 (edit/grapheme-right "" 0)))
  (t/is (= 1 (edit/grapheme-right "abc" 0)))
  (t/is (= 3 (edit/grapheme-right "abc" 2)))
  (t/is (= 3 (edit/grapheme-right "abc" 3)))
  (t/is (= 1 (edit/grapheme-right "中文" 0)) "CJK: right from first char is 1 char ahead"))

(t/deftest test-grapheme-at
  (t/is (= "" (edit/grapheme-at "" 0)))
  (t/is (= "" (edit/grapheme-at "abc" 3)))
  (t/is (= "a" (edit/grapheme-at "abc" 0)))
  (t/is (= "c" (edit/grapheme-at "abc" 2)))
  (t/is (= "中" (edit/grapheme-at "中文" 0)) "CJK: first char")
  (t/is (= "文" (edit/grapheme-at "中文" 1)) "CJK: second char"))

(t/deftest test-grapheme-at-or-space
  (t/is (= " " (edit/grapheme-at-or-space "" 0)))
  (t/is (= " " (edit/grapheme-at-or-space "abc" 3)))
  (t/is (= "a" (edit/grapheme-at-or-space "abc" 0)))
  (t/is (= "c" (edit/grapheme-at-or-space "abc" 2))))

(t/deftest test-grapheme-segments
  (t/is (= [] (edit/grapheme-segments "")))
  (let [segs (edit/grapheme-segments "abc")]
    (t/is (= 3 (count segs)) "3 ASCII chars = 3 segments")
    (t/is (= "a" (:text (nth segs 0))))
    (t/is (= "c" (:text (nth segs 2)))))
  (let [segs (edit/grapheme-segments "ab")]
    (t/is (= 2 (count segs)))
    (t/is (= 0 (:start (first segs))))
    (t/is (= 1 (:start (second segs)))))
  (let [segs (edit/grapheme-segments "中")]
    (t/is (= 1 (count segs)) "1 CJK char = 1 segment")
    (t/is (= "中" (:text (first segs))))))

;; ─── Kill ring ─────────────────────────────────────────────────────────────

(t/deftest test-kill-ring-create
  (let [kr (edit/make-kill-ring)]
    (t/is (instance? clojure.lang.Atom (:entries kr)))
    (t/is (empty? @(:entries kr)))))

(t/deftest test-kill-ring-push-and-peek
  (let [kr (edit/make-kill-ring)]
    (edit/kill-ring-push kr "first")
    (t/is (= "first" (edit/kill-ring-peek kr)))
    (edit/kill-ring-push kr "second")
    (t/is (= "second" (edit/kill-ring-peek kr)))
    (t/is (= 2 (edit/kill-ring-length kr)))))

(t/deftest test-kill-ring-push-empty
  (let [kr (edit/make-kill-ring)]
    (edit/kill-ring-push kr "")
    (t/is (nil? (edit/kill-ring-peek kr)))
    (edit/kill-ring-push kr "text")
    (t/is (= "text" (edit/kill-ring-peek kr)))))

(t/deftest test-kill-ring-rotate
  ;; rotate moves the last entry to the front
  (let [kr (edit/make-kill-ring)]
    (edit/kill-ring-push kr "a")
    (edit/kill-ring-push kr "b")
    (edit/kill-ring-push kr "c")  ;; entries: ["a" "b" "c"]
    (edit/kill-ring-rotate kr)    ;; becomes: ["c" "a" "b"]
    (t/is (= "b" (edit/kill-ring-peek kr)) "peek returns last element (b)")
    (edit/kill-ring-rotate kr)    ;; becomes: ["b" "c" "a"]
    (t/is (= "a" (edit/kill-ring-peek kr)) "peek returns last element (a)")))

(t/deftest test-kill-ring-rotate-single
  (let [kr (edit/make-kill-ring)]
    (edit/kill-ring-push kr "only")
    (edit/kill-ring-rotate kr)
    (t/is (= "only" (edit/kill-ring-peek kr))))

  (let [kr (edit/make-kill-ring)]
    ;; edge: empty ring, rotate is a no-op
    (edit/kill-ring-rotate kr)
    (t/is (nil? (edit/kill-ring-peek kr)))))

(t/deftest test-kill-ring-accumulate
  (let [kr (edit/make-kill-ring)]
    (edit/kill-ring-push kr "abc")
    (edit/kill-ring-push kr "def" :accumulate true)
    (t/is (= "abcdef" (edit/kill-ring-peek kr)))
    (t/is (= 1 (edit/kill-ring-length kr)))))

(t/deftest test-kill-ring-accumulate-prepend
  (let [kr (edit/make-kill-ring)]
    (edit/kill-ring-push kr "abc")
    (edit/kill-ring-push kr "def" :prepend true :accumulate true)
    (t/is (= "defabc" (edit/kill-ring-peek kr)))
    (t/is (= 1 (edit/kill-ring-length kr)))))

(t/deftest test-kill-ring-length-empty
  (let [kr (edit/make-kill-ring)]
    (t/is (zero? (edit/kill-ring-length kr)))))

;; ─── Word navigation (single-line) ────────────────────────────────────────

(t/deftest test-word-boundary-left
  (t/is (= 0 (edit/word-boundary-left "" 0)))
  (t/is (= 0 (edit/word-boundary-left "hello" 0)))
  (t/is (= 0 (edit/word-boundary-left "hello" 3)) "at 'l', left goes to 0")
  (t/is (= 0 (edit/word-boundary-left "hello" 5)))
  ;; "hello world": at end, left goes to start of "world"
  (t/is (= 6 (edit/word-boundary-left "hello world" 11)))
  (t/is (= 6 (edit/word-boundary-left "hello world" 8)) "at 'o' in 'world', left to 'world' start")
  (t/is (= 0 (edit/word-boundary-left "hello world" 5)) "at space, left goes to 0"))

(t/deftest test-word-boundary-right
  (t/is (= 0 (edit/word-boundary-right "" 0)))
  ;; "hello": right from 0 moves to end of word (5)
  (t/is (= 5 (edit/word-boundary-right "hello" 0)))
  (t/is (= 5 (edit/word-boundary-right "hello" 3)))
  ;; "hello world": right from 0 moves to end of first word (5)
  (t/is (= 5 (edit/word-boundary-right "hello world" 0)))
  ;; "hello world": right from position 6 (start of "world") moves to end (11)
  (t/is (= 11 (edit/word-boundary-right "hello world" 6))))

;; ─── Shared integration: grapheme round-trip ─────────────────────────────

(t/deftest test-grapheme-segments-round-trip
  (let [inputs ["" "a" "abc" "中文" "hello world" "abc🍎def" "a\nb"]]
    (doseq [s inputs]
      (let [segs (edit/grapheme-segments s)
            reconstructed (apply str (map :text segs))]
        (t/is (= s reconstructed) (str "Round-trip preserves: " (pr-str s)))))))

(t/deftest test-grapheme-segments-start-indices
  (let [s "abcdef"
        segs (edit/grapheme-segments s)]
    (t/is (= 6 (count segs)))
    (doseq [[idx seg] (map-indexed vector segs)]
      (t/is (= idx (:start seg)) (str "Start index " idx " for char " idx)))))

;; ─── Regression: no empty trailing segment ───────────────────────────────

(t/deftest test-grapheme-segments-no-trailing-empty
  (let [segs (edit/grapheme-segments "abc")]
    (t/is (= 3 (count segs)) "No empty trailing segment")
    (doseq [s segs]
      (t/is (not (empty? (:text s))) "Each segment has non-empty text"))))

(t/deftest test-grapheme-segments-single-char
  (let [segs (edit/grapheme-segments "x")]
    (t/is (= 1 (count segs)))
    (t/is (= "x" (:text (first segs))))
    (t/is (= 0 (:start (first segs))))))

;; ─── Kill ring edge cases ────────────────────────────────────────────────

(t/deftest test-kill-ring-peek-after-empty
  (let [kr (edit/make-kill-ring)]
    (t/is (nil? (edit/kill-ring-peek kr)))
    (edit/kill-ring-push kr "x")
    (t/is (= "x" (edit/kill-ring-peek kr)))))

(t/deftest test-kill-ring-multiple-accumulate
  (let [kr (edit/make-kill-ring)]
    (edit/kill-ring-push kr "a")
    (edit/kill-ring-push kr "b" :accumulate true)
    (edit/kill-ring-push kr "c" :accumulate true)
    (t/is (= "abc" (edit/kill-ring-peek kr)))
    (t/is (= 1 (edit/kill-ring-length kr)))))

(t/deftest test-kill-ring-rotate-preserves-all
  (let [kr (edit/make-kill-ring)]
    (edit/kill-ring-push kr "a")
    (edit/kill-ring-push kr "b")
    (edit/kill-ring-push kr "c")
    (edit/kill-ring-rotate kr)
    (edit/kill-ring-rotate kr)
    (edit/kill-ring-rotate kr)
    ;; After 3 rotations, back to original order ["a" "b" "c"]
    (t/is (= "c" (edit/kill-ring-peek kr)))
    (t/is (= 3 (edit/kill-ring-length kr)))))

;; ─── Paste marker helpers ────────────────────────────────────────────────

(t/deftest test-find-paste-markers-in-line
  (t/is (= [] (edit/find-paste-markers-in-line "")))
  (t/is (= [] (edit/find-paste-markers-in-line "no markers here")))
  (let [line "abc [paste #3 +10 lines — ctrl+o to expand] xyz"
        m (first (edit/find-paste-markers-in-line line))]
    (t/is (= 3 (:id m)))
    (t/is (= 4 (:start m)))
    (t/is (= "]" (subs line (dec (:end m)) (:end m))) "end is just past the closing bracket"))
  (let [line "a[paste #1 +1 lines — ctrl+o to expand]b[paste #2 +5 lines — ctrl+o to expand]c"
        [m1 m2] (edit/find-paste-markers-in-line line)]
    (t/is (= [1 2] [(:id m1) (:id m2)]))
    (t/is (< (:end m1) (:start m2)) "markers don't overlap")
    (t/is (= "b" (subs line (:end m1) (:start m2))))))

(t/deftest test-segment-with-markers
  (let [marker "[paste #1 +10 lines — ctrl+o to expand]"]
    ;; Marker merged into a single atomic segment
    (let [segs (edit/segment-with-markers (str "ab" marker "cd") edit/grapheme-segments #{1})]
      (t/is (= 5 (count segs)))
      (t/is (= "a" (:text (nth segs 0))))
      (t/is (= "b" (:text (nth segs 1))))
      (t/is (= marker (:text (nth segs 2))) "marker is one atomic segment")
      (t/is (= "c" (:text (nth segs 3))))
      (t/is (= "d" (:text (nth segs 4)))))
    ;; Adjacent markers stay separate atomic segments
    (let [segs (edit/segment-with-markers (str marker marker) edit/grapheme-segments #{1})]
      (t/is (= 2 (count segs)))
      (t/is (= marker (:text (nth segs 0))))
      (t/is (= marker (:text (nth segs 1)))))
    ;; Unknown marker id is NOT merged
    (let [segs (edit/segment-with-markers (str "ab" marker) edit/grapheme-segments #{})]
      (t/is (> (count segs) 2)))
    ;; Marker at start
    (let [segs (edit/segment-with-markers (str marker "x") edit/grapheme-segments #{1})]
      (t/is (= 2 (count segs)))
      (t/is (= marker (:text (first segs)))))))

(t/deftest test-renumber-paste-markers-in-line
  (let [line "x[paste #3 +5 lines — ctrl+o to expand]y"
        m2 "[paste #2 +5 lines — ctrl+o to expand]"]
    (t/is (= (str "x" m2 "y") (edit/renumber-paste-markers-in-line line {3 2})))
    (t/is (= line (edit/renumber-paste-markers-in-line line {1 9})) "unknown id unchanged")
    (let [two "a[paste #2 +1 lines — ctrl+o to expand]b[paste #4 +2 lines — ctrl+o to expand]c"
          expected "a[paste #1 +1 lines — ctrl+o to expand]b[paste #2 +2 lines — ctrl+o to expand]c"]
      (t/is (= expected (edit/renumber-paste-markers-in-line two {2 1, 4 2}))))))

;; ─── Paste text processing ───────────────────────────────────────────────

(t/deftest test-decode-csi-u
  (t/is (= "\u0001" (edit/decode-csi-u "\u001b[97;5u")) "a → ctrl+a")
  (t/is (= "\u0001" (edit/decode-csi-u "\u001b[65;5u")) "A → ctrl+shift+a")
  (t/is (= "ab\u0001cd" (edit/decode-csi-u "ab\u001b[97;5ucd")))
  (t/is (= "\u001b[200;5u" (edit/decode-csi-u "\u001b[200;5u")) "non-letter cp left as-is")
  (t/is (= "plain text" (edit/decode-csi-u "plain text"))))

(t/deftest test-smart-path-spacing
  (t/is (= " /tmp/x" (edit/smart-path-spacing "/tmp/x" "o")))
  (t/is (= "/tmp/x" (edit/smart-path-spacing "/tmp/x" " ")))
  (t/is (= "/tmp/x" (edit/smart-path-spacing "/tmp/x" nil)))
  (t/is (= " ~/x" (edit/smart-path-spacing "~/x" "a")))
  (t/is (= " ./x" (edit/smart-path-spacing "./x" "b")))
  (t/is (= "word" (edit/smart-path-spacing "word" "a")) "non-path start unchanged")
  (t/is (= "x" (edit/smart-path-spacing "x" "a"))))

(t/deftest test-paste-marker-predicate
  (t/is (edit/paste-marker? "[paste #1 +3 lines — ctrl+o to expand]"))
  (t/is (not (edit/paste-marker? "hello")))
  (t/is (not (edit/paste-marker? ""))))
