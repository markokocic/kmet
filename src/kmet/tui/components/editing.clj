(ns kmet.tui.components.editing
  "Shared editing primitives for input and editor components.
   Grapheme cluster navigation, kill ring, and undo stack."
  (:import [java.text BreakIterator]))

;; ─── Grapheme helpers ──────────────────────────────────────────────────────

(defn grapheme-left
  "Move cursor one grapheme cluster left."
  [s pos]
  (if (<= pos 0) 0
      (let [bi (BreakIterator/getCharacterInstance)]
        (.setText bi s)
        (let [prev (.preceding bi pos)]
          (if (== prev BreakIterator/DONE) 0 prev)))))

(defn grapheme-right
  "Move cursor one grapheme cluster right."
  [s pos]
  (if (>= pos (count s)) (count s)
      (let [bi (BreakIterator/getCharacterInstance)]
        (.setText bi s)
        (let [nxt (.following bi pos)]
          (if (== nxt BreakIterator/DONE) (count s) nxt)))))

(defn grapheme-at
  "Return the grapheme cluster at cursor position (or empty string if at end)."
  [s pos]
  (if (>= pos (count s)) ""
      (let [bi (BreakIterator/getCharacterInstance)]
        (.setText bi s)
        (let [nxt (.following bi pos)]
          (subs s pos (if (== nxt BreakIterator/DONE) (count s) nxt))))))

(defn grapheme-at-or-space
  "Return the grapheme cluster at cursor position, or space if at end."
  [s pos]
  (if (>= pos (count s)) " "
      (let [bi (BreakIterator/getCharacterInstance)]
        (.setText bi s)
        (let [nxt (.following bi pos)]
          (subs s pos (if (== nxt BreakIterator/DONE) (count s) nxt))))))

(defn grapheme-segments
  "Return a vector of {:text str :start idx} for each grapheme cluster in s."
  [s]
  (if (empty? s) []
      (let [bi (BreakIterator/getCharacterInstance)]
        (.setText bi s)
        (loop [seg [] pos (.first bi)]
          (let [nxt (.next bi)]
            (if (== nxt BreakIterator/DONE)
              seg
              (recur (conj seg {:text (subs s pos nxt) :start pos}) nxt)))))))

;; ─── Kill ring ─────────────────────────────────────────────────────────────

(defrecord KillRing [entries])

(defn make-kill-ring []
  (map->KillRing {:entries (atom [])}))

(defn kill-ring-push
  "Push text onto the kill ring.
   Options:
     :prepend    — prepend text to last entry (instead of append)
     :accumulate — merge with the last entry instead of creating a new one"
  [kr text & {:keys [prepend accumulate]}]
  (when (seq text)
    (swap! (:entries kr)
      (fn [es]
        (if (and accumulate (seq es))
          (let [last (peek es)]
            (conj (vec (butlast es))
                  (if prepend (str text last) (str last text))))
          (conj (vec es) text))))))

(defn kill-ring-peek
  "Return the most recent kill ring entry."
  [kr]
  (peek @(:entries kr)))

(defn kill-ring-rotate
  "Rotate the kill ring (put last entry at front)."
  [kr]
  (swap! (:entries kr)
    (fn [es]
      (if (> (count es) 1)
        (into [(peek es)] (vec (butlast es)))
        es))))

(defn kill-ring-length
  "Number of entries in the kill ring."
  [kr]
  (count @(:entries kr)))

;; ─── Word navigation (single-line) ─────────────────────────────────────────

(defn word-boundary-left
  "Find cursor position after moving one word backward in a single line."
  [text pos]
  (let [n (count text)
        pos (min pos n)]
    (if (<= pos 0) 0
        (let [before (subs text 0 pos)
              no-trail (clojure.string/replace before #"\s+$" "")
              trimmed (count no-trail)]
          (if (zero? trimmed) 0
              (let [last-char (subs no-trail (dec trimmed))
                    word-char? (boolean (re-find #"^\w" last-char))]
                (loop [i (dec trimmed)]
                  (if (<= i 0) 0
                      (let [c (subs text i (inc i))
                            is-word (re-find #"^\w" c)
                            is-space (re-find #"^\s" c)]
                        (cond
                          is-space (if word-char?
                                     (inc i)
                                     (recur (dec i)))
                          word-char? (if is-word (recur (dec i)) (inc i))
                          :else (if is-word (inc i) (recur (dec i)))))))))))))

(defn word-boundary-right
  "Find cursor position after moving one word forward in a single line."
  [text pos]
  (let [n (count text)]
    (if (>= pos n) n
        (let [bi (BreakIterator/getWordInstance)]
          (.setText bi text)
          (loop [p pos]
            (let [nxt (.following bi p)]
              (if (== nxt BreakIterator/DONE) n
                  (let [c (subs text p (min (inc p) n))]
                    (if (re-find #"^\s" c)
                      (recur nxt)
                      nxt)))))))))
