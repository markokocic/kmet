(ns kmet.tui.components.editing
  "Shared editing primitives for input and editor components.
   Grapheme cluster navigation, paste marker handling, kill ring, and undo
   stack."
  (:require [clojure.string :as str]))

;; ─── Grapheme helpers ──────────────────────────────────────────────────────
;;
;; Pure Clojure grapheme cluster boundary detection (no BreakIterator).
;; Good enough for terminal TUI use — handles combining marks, ZWJ, flags,
;; and variation selectors.

(def ^:private combining?*
  "Set of code points that extend/combine with the preceding grapheme."
  (set (concat
        (range 0x0300 0x036F)    ;; Combining Diacritical Marks
        (range 0x0483 0x0489)    ;; Cyrillic combining marks
        (range 0x0591 0x05BD)    ;; Hebrew combining marks
        (range 0x0610 0x061A)    ;; Arabic combining marks
        (range 0x064B 0x065F)    ;; Arabic combining marks
        (range 0x0670 0x0670)
        (range 0x06D6 0x06DC)
        (range 0x06DF 0x06E4)
        (range 0x06E7 0x06E8)
        (range 0x06EA 0x06ED)
        (range 0x0711 0x0711)
        (range 0x0730 0x074A)
        (range 0x07A6 0x07B0)
        (range 0x07EB 0x07F3)
        (range 0x0816 0x0819)
        (range 0x081B 0x0823)
        (range 0x0825 0x0827)
        (range 0x0829 0x082D)
        (range 0x0859 0x085B)
        (range 0x0898 0x089F)
        (range 0x08CA 0x08E1)
        (range 0x08E3 0x0903)
        (range 0x093A 0x093C)
        (range 0x093E 0x094F)
        (range 0x0951 0x0957)
        (range 0x0962 0x0963)
        (range 0x0981 0x0983)
        (range 0x09BC 0x09BC)
        (range 0x09BE 0x09C4)
        (range 0x09C7 0x09C8)
        (range 0x09CB 0x09CD)
        (range 0x09D7 0x09D7)
        (range 0x09E2 0x09E3)
        (range 0x09FE 0x09FE)
        (range 0x0A01 0x0A03)
        (range 0x0A3C 0x0A3C)
        (range 0x0A3E 0x0A42)
        (range 0x0A47 0x0A48)
        (range 0x0A4B 0x0A4D)
        (range 0x0A51 0x0A51)
        (range 0x0A70 0x0A71)
        (range 0x0A75 0x0A75)
        (range 0x0A81 0x0A83)
        (range 0x0ABC 0x0ABC)
        (range 0x0ABE 0x0AC5)
        (range 0x0AC7 0x0AC9)
        (range 0x0ACB 0x0ACD)
        (range 0x0AE2 0x0AE3)
        (range 0x0AFA 0x0AFF)
        (range 0x0B01 0x0B03)
        (range 0x0B3C 0x0B3C)
        (range 0x0B3E 0x0B44)
        (range 0x0B47 0x0B48)
        (range 0x0B4B 0x0B4D)
        (range 0x0B55 0x0B57)
        (range 0x0B62 0x0B63)
        (range 0x0B82 0x0B82)
        (range 0x0BBE 0x0BC2)
        (range 0x0BC6 0x0BC8)
        (range 0x0BCA 0x0BCD)
        (range 0x0BD7 0x0BD7)
        (range 0x0C00 0x0C03)
        (range 0x0C3C 0x0C3C)
        (range 0x0C3E 0x0C44)
        (range 0x0C46 0x0C48)
        (range 0x0C4A 0x0C4D)
        (range 0x0C55 0x0C56)
        (range 0x0C62 0x0C63)
        (range 0x0C81 0x0C83)
        (range 0x0CBC 0x0CBC)
        (range 0x0CBE 0x0CC4)
        (range 0x0CC6 0x0CC8)
        (range 0x0CCA 0x0CCD)
        (range 0x0CD5 0x0CD6)
        (range 0x0CE2 0x0CE3)
        (range 0x0CF3 0x0CF3)
        (range 0x0D00 0x0D03)
        (range 0x0D3B 0x0D3C)
        (range 0x0D3E 0x0D44)
        (range 0x0D46 0x0D48)
        (range 0x0D4A 0x0D4D)
        (range 0x0D57 0x0D57)
        (range 0x0D62 0x0D63)
        (range 0x0D81 0x0D83)
        (range 0x0DCA 0x0DCA)
        (range 0x0DCF 0x0DD4)
        (range 0x0DD6 0x0DD6)
        (range 0x0DD8 0x0DDF)
        (range 0x0DF2 0x0DF3)
        (range 0x0E31 0x0E31)
        (range 0x0E33 0x0E33)
        (range 0x0E34 0x0E3A)
        (range 0x0E47 0x0E4E)
        (range 0x0EB1 0x0EB1)
        (range 0x0EB3 0x0EB3)
        (range 0x0EB4 0x0EBC)
        (range 0x0EC8 0x0ECE)
        (range 0x0F18 0x0F19)
        (range 0x0F35 0x0F35)
        (range 0x0F37 0x0F37)
        (range 0x0F39 0x0F39)
        (range 0x0F3E 0x0F3F)
        (range 0x0F71 0x0F84)
        (range 0x0F86 0x0F87)
        (range 0x0F8D 0x0F97)
        (range 0x0F99 0x0FBC)
        (range 0x0FC6 0x0FC6)
        (range 0x102B 0x103E)
        (range 0x1056 0x1059)
        (range 0x105E 0x1060)
        (range 0x1062 0x1064)
        (range 0x1067 0x106D)
        (range 0x1071 0x1074)
        (range 0x1082 0x108D)
        (range 0x108F 0x108F)
        (range 0x109A 0x109D)
        (range 0x1100 0x1159)    ;; Hangul Jamo
        (range 0x1160 0x11A2)
        (range 0x11A8 0x11F9)
        (range 0x1715 0x1715)
        (range 0x1734 0x1734)
        (range 0x17B4 0x17D3)
        (range 0x17DD 0x17DD)
        (range 0x180B 0x180D)
        (range 0x180F 0x180F)
        (range 0x1885 0x1886)
        (range 0x18A9 0x18A9)
        (range 0x1920 0x192B)
        (range 0x1930 0x193B)
        (range 0x1A17 0x1A1B)
        (range 0x1A55 0x1A5E)
        (range 0x1A60 0x1A7C)
        (range 0x1A7F 0x1A7F)
        (range 0x1AB0 0x1ACE)
        (range 0x1B00 0x1B04)
        (range 0x1B34 0x1B44)
        (range 0x1B6B 0x1B73)
        (range 0x1B80 0x1B82)
        (range 0x1BA1 0x1BAD)
        (range 0x1BE6 0x1BF3)
        (range 0x1C24 0x1C37)
        (range 0x1CD0 0x1CD2)
        (range 0x1CD4 0x1CE8)
        (range 0x1CED 0x1CED)
        (range 0x1CF4 0x1CF4)
        (range 0x1CF7 0x1CF9)
        (range 0x1DC0 0x1DFF)
        (range 0x200C 0x200D)    ;; ZWNJ, ZWJ
        (range 0x20D0 0x20F0)
        (range 0x2CEF 0x2CF1)
        (range 0x2D7F 0x2D7F)
        (range 0x2DE0 0x2DFF)
        (range 0xA66F 0xA672)
        (range 0xA674 0xA67D)
        (range 0xA69E 0xA69F)
        (range 0xA6F0 0xA6F1)
        (range 0xA802 0xA802)
        (range 0xA806 0xA806)
        (range 0xA80B 0xA80B)
        (range 0xA823 0xA827)
        (range 0xA82C 0xA82C)
        (range 0xA880 0xA881)
        (range 0xA8B4 0xA8C5)
        (range 0xA8E0 0xA8F1)
        (range 0xA8FF 0xA8FF)
        (range 0xA926 0xA92D)
        (range 0xA947 0xA953)
        (range 0xA960 0xA97C)    ;; Hangul Jamo Extended-A
        (range 0xA980 0xA983)
        (range 0xA9B3 0xA9C0)
        (range 0xA9E5 0xA9E5)
        (range 0xAA29 0xAA36)
        (range 0xAA43 0xAA43)
        (range 0xAA4C 0xAA4D)
        (range 0xAA7B 0xAA7D)
        (range 0xAAB0 0xAAB0)
        (range 0xAAB2 0xAAB4)
        (range 0xAAB7 0xAAB8)
        (range 0xAABE 0xAABF)
        (range 0xAAC1 0xAAC1)
        (range 0xAAEB 0xAAEF)
        (range 0xAAF5 0xAAF6)
        (range 0xABE3 0xABEA)
        (range 0xABEC 0xABED)
        (range 0xFB1E 0xFB1E)
        (range 0xFE00 0xFE0F)    ;; Variation selectors
        (range 0xFE20 0xFE2F)
        (range 0xFEFF 0xFEFF)    ;; BOM / ZWNBSP
        (range 0xFF9E 0xFF9F)
        (range 0xFFF9 0xFFFB)
        [0x13430 0x1343F 0x13440 0x13447 0x1344B 0x1344E 0x13455 0x13456 0x13457]
        (range 0x1D165 0x1D169)
        (range 0x1D16D 0x1D172)
        (range 0x1D17B 0x1D182)
        (range 0x1D185 0x1D18B)
        (range 0x1D1AA 0x1D1AD)
        (range 0x1D242 0x1D244)
        (range 0x1DA00 0x1DA36)
        (range 0x1DA3B 0x1DA6C)
        (range 0x1DA75 0x1DA75)
        (range 0x1DA84 0x1DA84)
        (range 0x1DA9B 0x1DA9F)
        (range 0x1DAA1 0x1DAAF)
        (range 0x1E000 0x1E006)
        (range 0x1E008 0x1E018)
        (range 0x1E01B 0x1E021)
        (range 0x1E023 0x1E024)
        (range 0x1E026 0x1E02A)
        (range 0x1E130 0x1E136)
        (range 0x1E2AE 0x1E2AE)
        (range 0x1E2EC 0x1E2EF)
        (range 0x1E4EC 0x1E4EF)
        (range 0x1E8D0 0x1E8D6)
        (range 0x1E944 0x1E94A)
        (range 0xE0020 0xE007F)  ;; Tag characters
        (range 0xE0100 0xE01EF)  ;; Variation selectors supplement
        )))

(defn- extend-char?
  "Returns true if cp is a combining/extend character that attaches to the
   preceding grapheme cluster (ZWNJ, ZWJ, combining marks, variation selectors,
   tag characters)."
  [cp]
  (contains? combining?* cp))

(defn- regional-indicator?
  "Returns true if cp is a regional indicator symbol (used in flag emoji)."
  [cp]
  (and (>= cp 0x1F1E6) (<= cp 0x1F1FF)))

(defn- grapheme-cluster-break?
  "Returns true if the boundary between cp (current code point)
   and next-cp (following code point) is a grapheme cluster break,
   i.e. next-cp should start a new grapheme."
  [cp next-cp]
  (if (nil? next-cp)
    true
    (let [ext? (extend-char? next-cp)
          ri? (and (regional-indicator? cp) (regional-indicator? next-cp))
          crlf? (and (= cp 0x0D) (= next-cp 0x0A))]
      (cond
        ;; CR+LF is one grapheme
        crlf? false
        ;; Regional indicator pairs stay together (first of pair is start)
        ri? false
        ;; Extend characters attach to preceding base
        ext? false
        ;; Everything else is a break
        :else true))))

(defn- grapheme-segments-impl
  "Return a vector of {:text str :start idx} for each grapheme cluster in s.
   Pure Clojure implementation (no BreakIterator)."
  [s]
  (if (empty? s)
    []
    (let [n (count s)
          chars (vec s)]
      (loop [i 0, segs [], seg-start 0]
        (if (>= i n)
          ;; Last segment
          (conj segs {:text (subs s seg-start) :start seg-start})
          (let [cp (int (nth chars i))
                next-cp (when (< (inc i) n) (int (nth chars (inc i))))]
            (if (grapheme-cluster-break? cp next-cp)
              ;; Break here — finalize current segment
              (let [seg-text (subs s seg-start i)]
                (recur (inc i)
                       (if (pos? (count seg-text))
                         (conj segs {:text seg-text :start seg-start})
                         segs)
                       i))
              ;; No break — carry on
              (recur (inc i) segs seg-start))))))))

(defn grapheme-left
  "Move cursor one grapheme cluster left."
  [s pos]
  (if (<= pos 0)
    0
    (let [segments (grapheme-segments-impl (subs s 0 pos))]
      (:start (last segments)))))

(defn grapheme-right
  "Move cursor one grapheme cluster right."
  [s pos]
  (let [n (count s)]
    (if (>= pos n)
      n
      (let [segments (grapheme-segments-impl (subs s pos))
            first-seg (first segments)]
        (if first-seg
          (+ pos (count (:text first-seg)))
          n)))))

(defn grapheme-at
  "Return the grapheme cluster at cursor position (or empty string if at end)."
  [s pos]
  (if (>= pos (count s))
    ""
    (let [segments (grapheme-segments-impl (subs s pos))
          first-seg (first segments)]
      (if first-seg
        (:text first-seg)
        ""))))

(defn grapheme-at-or-space
  "Return the grapheme cluster at cursor position, or space if at end."
  [s pos]
  (if (>= pos (count s))
    " "
    (let [segments (grapheme-segments-impl (subs s pos))
          first-seg (first segments)]
      (:text first-seg " "))))

(defn grapheme-segments
  "Return a vector of {:text str :start idx} for each grapheme cluster in s."
  [s]
  (grapheme-segments-impl s))

;; ─── Paste marker helpers ──────────────────────────────────────────────────
;; Paste markers ([paste #N ...]) are stored in the editor's paste store and
;; replaced with a short marker. These helpers treat a marker as a single
;; atomic unit: cursor movement, deletion, and word-wrap never break inside
;; one.

(def ^:private paste-marker-prefix
  "Literal prefix of a paste marker: [paste #"
  "[paste #")

(defn find-paste-markers-in-line
  "Find paste markers in a single line of text.
   Returns a vector of {:id int :start int :end int} in order of appearance.
   :end is the index just past the closing bracket."
  [line]
  (loop [offset 0, found []]
    (if-let [idx (clojure.string/index-of line paste-marker-prefix offset)]
      (if-let [end-idx (clojure.string/index-of line "]" (+ idx 1))]
        (let [id-str (re-find #"\d+" (subs line (+ idx (count paste-marker-prefix))))]
          (if id-str
            (recur (inc end-idx)
                   (conj found {:id (parse-long id-str) :start idx :end (inc end-idx)}))
            (recur (inc end-idx) found)))
        found)
      found)))

(defn segment-with-markers
  "Return grapheme segments of s (via base-segmenter), merging each complete
   paste marker (whose id is in valid-ids) into a single atomic segment.
   Mirrors pi's segmentWithMarkers (tui/src/components/editor.ts)."
  [s base-segmenter valid-ids]
  (let [ranges (->> (find-paste-markers-in-line s)
                    (filter #(contains? valid-ids (:id %)))
                    (mapv (juxt :start :end)))
        n (count ranges)]
    (if (zero? n)
      (base-segmenter s)
      (loop [segs (vec (base-segmenter s))
             out []
             pending nil          ;; {:text str :start int}
             pending-range -1     ;; index of the range pending belongs to
             rng-idx 0]           ;; current range cursor (monotonic)
        (if (empty? segs)
          (if pending (conj out pending) out)
          (let [pos (:start (first segs))
                ;; Advance past ranges that end at or before this segment
                rng-idx (loop [i rng-idx]
                          (if (and (< i n) (>= pos (second (nth ranges i))))
                            (recur (inc i))
                            i))
                in-marker? (and (< rng-idx n)
                                (>= pos (first (nth ranges rng-idx)))
                                (< pos (second (nth ranges rng-idx))))]
            (cond
              ;; Inside a marker, continuing the current one
              (and in-marker? pending (= pending-range rng-idx))
              (recur (subvec segs 1) out
                     (update pending :text str (:text (first segs)))
                     pending-range rng-idx)

              ;; Inside a (new) marker — flush pending, start a new one
              in-marker?
              (recur (subvec segs 1)
                     (if pending (conj out pending) out)
                     {:text (:text (first segs)) :start pos}
                     rng-idx rng-idx)

              ;; Outside markers — flush pending and emit the segment
              :else
              (recur (subvec segs 1)
                     (if pending (conj (conj out pending) (first segs)) (conj out (first segs)))
                     nil -1 rng-idx))))))))

(defn paste-marker?
  "True if s starts with a paste marker ([paste #...)."
  [s]
  (clojure.string/starts-with? s "[paste #"))

(defn renumber-paste-markers-in-line
  "Rewrite paste markers in line according to id->new (old-id → new-id).
   Markers whose id is not in the map are left unchanged."
  [line id->new]
  (let [markers (find-paste-markers-in-line line)]
    (if (empty? markers)
      line
      (loop [markers markers, out "", cursor 0]
        (if (empty? markers)
          (str out (subs line cursor))
          (let [{:keys [id start end]} (first markers)
                old-text (subs line start end)
                new-id (get id->new id)
                new-text (if new-id
                           (clojure.string/replace old-text (str "#" id) (str "#" new-id))
                           old-text)]
            (recur (rest markers) (str out (subs line cursor start) new-text) end)))))))

(defn decode-csi-u
  "Decode CSI-u encoded control bytes (ESC [ <codepoint> ; 5 u) back to
   literal control characters. Lowercase a-z → ctrl+letter, uppercase A-Z →
   ctrl+shift+letter. Mirrors pi's paste handling (editor.ts)."
  [text]
  (clojure.string/replace text #"\u001b\[(\d+);5u"
                          (fn [[match code-str]]
                            (let [cp (parse-long code-str)]
                              (cond
                                (and (>= cp 97) (<= cp 122)) (str (char (- cp 96)))
                                (and (>= cp 65) (<= cp 90)) (str (char (- cp 64)))
                                :else match)))))

(defn smart-path-spacing
  "If text starts with a path marker (/ ~ .) and prev-char is a word character,
   prepend a space so the pasted path doesn't merge with the preceding word.
   Mirrors pi's smart path spacing in paste handling."
  [text prev-char]
  (if (and (seq text)
           (re-find #"^[/~.]" text)
           prev-char
           (re-find #"\w" (str prev-char)))
    (str " " text)
    text))

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
  "Find cursor position after moving one word forward in a single line.
   Pure Clojure replacement for BreakIterator-based word boundary detection."
  [text pos]
  (let [n (count text)]
    (if (>= pos n) n
        ;; Skip whitespace after current position
        (let [after (subs text pos)
              skip-ws (count (take-while #(re-find #"^\s" (str %)) after))
              start (+ pos skip-ws)]
          (if (>= start n)
            n
            ;; Find end of next word (transition to whitespace or punctuation)
            (let [rest-text (subs text start)
                  word-len (count (take-while #(re-find #"^\w" (str %)) rest-text))]
              (if (pos? word-len)
                (+ start word-len)
                ;; If no word chars, skip one non-space character
                (let [non-ws-len (count (take-while #(not (re-find #"^\s" (str %))) rest-text))]
                  (+ start (max 1 non-ws-len))))))))))
