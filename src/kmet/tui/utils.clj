(ns kmet.tui.utils
  "Text width calculation and wrapping utilities.")

;; ─── Cursor marker ────────────────────────────────────────────────────────
;; Zero-width APC sequence emitted at cursor position for IME positioning.
;; TUI finds this marker and positions the hardware cursor there.
(def ^:const CURSOR-MARKER "\u001b_km:c\u0007")

;; ─── Width calculation ─────────────────────────────────────────────────────

(def ^:const CJK-START 0x2E80)
(def ^:const CJK-END 0x9FFF)
(def ^:const FULLWIDTH-START 0xFF00)
(def ^:const FULLWIDTH-END 0xFFEF)

(defn- cjk? [cp]
  (or (and (>= cp CJK-START) (<= cp CJK-END))
      (and (>= cp 0xAC00) (<= cp 0xD7AF))   ;; Hangul
      (and (>= cp 0x3000) (<= cp 0x303F))    ;; CJK symbols
      (and (>= cp 0xFE30) (<= cp 0xFE4F))    ;; CJK compatibility
      (and (>= cp FULLWIDTH-START) (<= cp FULLWIDTH-END))))

(defn- emoji? [cp]
  (or (and (>= cp 0x1F000) (<= cp 0x1FBFF))
      (and (>= cp 0x2600) (<= cp 0x27BF))
      (and (>= cp 0x2300) (<= cp 0x23FF))
      (and (>= cp 0xFE00) (<= cp 0xFE0F))    ;; Variation selectors
      (= cp 0x200D)                           ;; ZWJ
      (and (>= cp 0x1F1E6) (<= cp 0x1F1FF)))) ;; Regional indicators

(defn- char-width [cp]
  (cond
    (= cp 9) 4                                ;; Tab
    (< cp 32) 0                                ;; Control chars
    ;; Zero-width characters
    (or (== cp 0x200B) (== cp 0x200C) (== cp 0x200D) (== cp 0x200E) (== cp 0x200F)
        (== cp 0x2060) (== cp 0x2061) (== cp 0x2062) (== cp 0x2063) (== cp 0x2064)
        (and (>= cp 0xFE00) (<= cp 0xFE0F))  ;; Variation selectors
        (and (>= cp 0xE0100) (<= cp 0xE01EF))) ;; Variation selectors supplement
    0
    (or (cjk? cp) (emoji? cp)) 2
    :else 1))

(defn- code-point-at
  "Get the Unicode code point at index i from string s.
   Pure Clojure implementation (no .codePointAt).
   Handles surrogate pairs for characters outside the BMP."
  [s i]
  (let [c (int (nth s i))
        hi (int (first (str (char 0xd800))))]
    (if (and (>= c 0xD800) (<= c 0xDBFF) (< (inc i) (count s)))
      ;; High surrogate followed by low surrogate
      (let [low (int (nth s (inc i)))]
        (+ 0x10000 (* (- c 0xD800) 0x400) (- low 0xDC00)))
      c)))

(defn- visible-width-plain
  "Visible width of a string that has NO ANSI escape codes.
   Skips the ANSI-stripping step for efficiency."
  [s]
  (if (empty? s) 0
    (if (re-find #"[^\u0020-\u007e]" s)
      (loop [i 0, n (count s), total 0]
        (if (>= i n) total
          (let [cp (code-point-at s i)
                w (char-width cp)
                nchars (if (and (>= cp 0x10000) (<= cp 0x10FFFF)) 2 1)]
            (recur (+ i nchars) n (+ total w)))))
      (count s))))

(defn visible-width
  "Calculate the visible display width of a string in terminal columns.
   Strips ANSI escape codes before measuring.
   Fast path for plain ASCII (no CJK/emoji) — just returns count."
  [s]
  (if (empty? s) 0
      (let [clean (clojure.string/replace s #"\u001b\[[0-9;]*[a-zA-Z]" "")]
        (visible-width-plain clean))))

;; ─── Truncation ─────────────────────────────────────────────────────────────

(defn truncate-to-width
  "Truncate string to fit within max-width visible columns, appending
   ellipsis when truncated. ANSI escape codes are preserved for the kept
   prefix (pi: truncateToWidth), so styling survives truncation."
  ([s max-width] (truncate-to-width s max-width ""))
  ([s max-width ellipsis]
   (let [e-width (visible-width ellipsis)
         target (- max-width e-width)]
     (if (<= (visible-width s) target)
       s
       (if-not (clojure.string/includes? s "\u001b[")
         ;; Fast path: plain text — truncate by codepoint
         (let [sb (atom "")
               total (atom 0)
               n (count s)
               _ (loop [i 0]
                   (when (and (< i n) (< @total target))
                     (let [cp (code-point-at s i)
                           w (char-width cp)
                           nchars (if (and (>= cp 0x10000) (<= cp 0x10FFFF)) 2 1)]
                       (swap! sb str (subs s i (+ i nchars)))
                       (swap! total + w)
                       (recur (+ i nchars)))))]
           (str @sb ellipsis))
         ;; ANSI path: keep escape codes with the characters they style;
         ;; pending (unflushed) codes are dropped once truncation starts
         (let [sb (StringBuilder.)
               n (count s)
               ansi-re #"\u001b\[[0-9;]*[a-zA-Z]"
               ansi-at (fn [i]
                         (let [m (re-matcher ansi-re s)]
                           (when (and (.find m i) (= (.start m) i))
                             [(.group m) (.end m)])))]
           (loop [i 0 total 0 pending ""]
             (if (or (>= i n) (>= total target))
               (str sb ellipsis)
               (if-let [[code end] (ansi-at i)]
                 (recur end total (str pending code))
                 (let [cp (code-point-at s i)
                       w (char-width cp)
                       nchars (if (and (>= cp 0x10000) (<= cp 0x10FFFF)) 2 1)]
                   (if (<= (+ total w) target)
                     (do (.append sb pending)
                         (.append sb (subs s i (+ i nchars)))
                         (recur (+ i nchars) (+ total w) ""))
                     (str sb ellipsis))))))))))))

;; ─── Word wrapping ──────────────────────────────────────────────────────────

(defn- leading-ansi-codes
  "All ANSI escape sequences at the very start of s (pi: active codes)."
  [s]
  (let [ansi-re #"\u001b\[[0-9;]*[a-zA-Z]"]
    (loop [s s acc ""]
      (let [m (re-find ansi-re s)]
        (if (and m (clojure.string/starts-with? s m))
          (recur (subs s (count m)) (str acc m))
          acc)))))

(defn- split-long-word
  "Split WORD (longer than max-width) into pieces of at most max-width
   visible columns. Every character is preserved — long unbreakable words
   (URLs, hashes, long flags) wrap instead of being clipped (pi:
   breakLongWord). ANSI escapes are zero-width atoms kept with the piece
   that contains them; the word's leading escapes are re-prepended to
   continuation pieces so styling survives the break.
   Fast path: pure-ASCII words break with a regex (visible width = char
   count), avoiding per-character work — crucial for streaming long
   tokens under SCI where per-char interop is slow."
  [word max-width]
  (let [n (count word)
        ansi-re #"\u001b\[[0-9;]*[a-zA-Z]"
        lead (leading-ansi-codes word)]
    (if (not (re-find #"[^\u0020-\u007e]" word))
      ;; ASCII fast path: char count == visible width
      (let [body (subs word (count lead))
            pieces (if (seq body)
                     (vec (re-seq (re-pattern (str "(?s).{1," max-width "}")) body))
                     [word])]
        (if (seq lead)
          (vec (map-indexed (fn [i p] (if (zero? i) p (str lead p))) pieces))
          pieces))
      ;; Walker: wide chars (CJK/emoji) or embedded ANSI — slice by visible
      ;; width; one subs per piece, no per-character appends.
      (loop [i 0 pieces []]
        (if (>= i n)
          pieces
          (let [stop (loop [j i total 0]
                       (if (>= j n)
                         j
                         (let [m (when (= \u001b (nth word j))
                                   (re-find ansi-re (subs word j)))]
                           (if (and m (clojure.string/starts-with? (subs word j) m))
                             (recur (+ j (count m)) total)
                             (let [cp (code-point-at word j)
                                   w (char-width cp)
                                   nchars (if (and (>= cp 0x10000) (<= cp 0x10FFFF)) 2 1)]
                               ;; Stop only once the piece has visible content —
                               ;; never emit an empty piece (e.g. a styled word
                               ;; starting with a 2-wide char at max-width 1).
                               (if (and (pos? total) (> (+ total w) max-width))
                                 j
                                 (recur (+ j nchars) (+ total w))))))))]
            (recur stop
                   (conj pieces
                         (if (seq pieces)
                           (str lead (subs word i stop))
                           (subs word i stop))))))))))

(defn- wrap-single-line
  "Wrap a single line (no internal newlines) to max-width visible columns.
   Returns a vector of lines, each without trailing newlines.
   ANSI escape codes are preserved and moved with their associated words.
   Long unbreakable words are broken across lines, never clipped."
  [line max-width]
  (if (or (empty? line) (<= max-width 0))
    [""]
    (let [clean (clojure.string/replace line #"\u001b\[[0-9;]*[a-zA-Z]" "")]
      (if (<= (visible-width-plain clean) max-width)
        [line]
        (let [words (clojure.string/split line #"(?<=\s)" -1)
              result (volatile! [])
              sb (StringBuilder.)
              cur-w (volatile! 0)]
          (doseq [w words]
            (let [ww (if (re-find #"[^\u0020-\u007e]" w) (visible-width w) (count w))
                  lw @cur-w
                  sep (if (zero? lw) 0 1)]
              (if (<= (+ lw sep ww) max-width)
                (do (.append sb w)
                    (vswap! cur-w + ww))
                (do
                  (when (pos? @cur-w)
                    (vswap! result conj (str sb)))
                  (if (<= ww max-width)
                    (do (.setLength sb 0)
                        (.append sb w)
                        (vreset! cur-w ww))
                    (let [pieces (split-long-word w max-width)
                          last-p (or (last pieces) "")]
                      (doseq [p (butlast pieces)]
                        (vswap! result conj p))
                      (.setLength sb 0)
                      (.append sb last-p)
                      (vreset! cur-w (if (re-find #"[^\u0020-\u007e]" last-p)
                                       (visible-width last-p)
                                       (count last-p)))))))))
          (if (pos? @cur-w)
            (conj @result (str sb))
            @result))))))

(defn wrap-text-with-ansi
  "Word wrap preserving ANSI escape codes.
   First splits on newlines so each returned line is a proper display line
   without embedded newlines. This ensures background padding in parent
   components extends to full terminal width on every line.
   Tabs are expanded to 3 spaces (pi: replaceTabs) so wrapped output is
   display-ready regardless of the terminal's tab stop.
   Internal fast path: uses visible-width-plain on already-stripped text."
  [text max-width]
  (if (or (empty? text) (<= max-width 0))
    [""]
    (let [text (clojure.string/replace text "\t" "   ")
          input-lines (clojure.string/split text #"\r\n|\r|\n")
          result (volatile! [])]
      (doseq [input-line input-lines]
        (let [wrapped (wrap-single-line input-line max-width)]
          (doseq [wl wrapped]
            (vswap! result conj wl))))
      (if (seq @result)
        @result
        [""]))))

;; ─── ANSI helpers ───────────────────────────────────────────────────────────

(defn strip-ansi-codes [s]
  (clojure.string/replace s #"\u001b\[[0-9;]*[a-zA-Z]" ""))

(defn sgr
  ([code] (str "\u001b[" code "m"))
  ([& codes] (str "\u001b[" (clojure.string/join \; codes) "m")))

(defn apply-background-to-line [line width bg-fn]
  (let [pad (max 0 (- width (visible-width line)))
        padded (str line (apply str (repeat pad \space)))]
    (if bg-fn (bg-fn padded) padded)))

;; ─── Column-based slicing ──────────────────────────────────────────────────

(defn slice-by-column
  "Extract a substring from `text` representing the visible columns
   from start-col with the given length-in-columns.
   Handles wide (CJK/emoji) characters. Expects plain text without ANSI codes.
   When strict? is true, wide characters at the boundary are excluded."
  [text start-col length & {:keys [strict?] :or {strict? false}}]
  (let [s (if (string? text) text (str text))
        n (count s)]
    (loop [i 0 col 0 result []]
      (if (>= i n)
        (apply str result)
        (let [cp (code-point-at s i)
              w (char-width cp)
              nchars (if (and (>= cp 0x10000) (<= cp 0x10FFFF)) 2 1)
              char-str (subs s i (+ i nchars))]
          (if (>= col (+ start-col length))
            (apply str result)
            (if (>= col start-col)
              (if (and strict? (> (+ col w) (+ start-col length)))
                (apply str result)
                (recur (+ i nchars) (+ col w) (conj result char-str)))
              (recur (+ i nchars) (+ col w) result))))))))

;; ─── Visual line truncation ────────────────────────────────────────────────

(defn truncate-to-visual-lines
  "Truncate text to the last max-lines visual lines at the given width.
   Uses wrap-text-with-ansi to resolve word-wrapped lines, then takes
   the last max-lines. Returns {:visual-lines [...] :skipped-count n}
   (pi: VisualTruncateResult). Width-aware — important for long
   single-line commands or narrow terminals where a single string-line
   may wrap to multiple visual lines."
  [text max-lines width]
  (if (or (empty? text) (<= max-lines 0) (<= width 0))
    {:visual-lines [] :skipped-count 0}
    (let [visual-lines (wrap-text-with-ansi text width)
          n (count visual-lines)]
      (if (<= n max-lines)
        {:visual-lines visual-lines :skipped-count 0}
        {:visual-lines (vec (take-last max-lines visual-lines))
         :skipped-count (- n max-lines)}))))
