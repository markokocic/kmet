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
  "Truncate string to fit within max-width visible columns."
  ([s max-width] (truncate-to-width s max-width ""))
  ([s max-width ellipsis]
   (let [clean (clojure.string/replace s #"\u001b\[[0-9;]*[a-zA-Z]" "")
         e-width (visible-width ellipsis)
         target (- max-width e-width)]
     (if (<= (visible-width clean) target) s
         (let [sb (atom "")
               total (atom 0)
               _ (loop [i 0, n (count clean)]
                   (when (and (< i n) (< @total target))
                     (let [cp (code-point-at clean i)
                           w (char-width cp)
                           nchars (if (and (>= cp 0x10000) (<= cp 0x10FFFF)) 2 1)]
                       (swap! sb str (subs clean i (+ i nchars)))
                       (swap! total + w)
                       (recur (+ i nchars) n))))]
           (str @sb ellipsis))))))

;; NOTE: truncate-to-width currently doesn't handle ANSI codes in the
;; output correctly when truncating. The atom-based approach is a
;; placeholder — for proper ANSI preservation, the original string
;; with codes should be used, not the stripped version.

;; ─── Word wrapping ──────────────────────────────────────────────────────────

(defn- wrap-single-line
  "Wrap a single line (no internal newlines) to max-width visible columns.
   Returns a vector of lines, each without trailing newlines.
   ANSI escape codes are preserved and moved with their associated words."
  [line max-width]
  (if (or (empty? line) (<= max-width 0))
    [""]
    (let [clean (clojure.string/replace line #"\u001b\[[0-9;]*[a-zA-Z]" "")]
      (if (<= (visible-width-plain clean) max-width)
        [line]
        (let [words (clojure.string/split line #"(?<=\s)" -1)
              result (volatile! [])
              current (volatile! "")]
          (doseq [w words]
            (let [ww (visible-width w)
                  lw (visible-width @current)
                  sep (if (zero? lw) 0 1)]
              (if (<= (+ lw sep ww) max-width)
                (vswap! current str w)
                (do (vswap! result conj @current)
                    (if (<= ww max-width)
                      (vreset! current w)
                      (let [clipped (truncate-to-width w max-width)]
                        (vswap! result conj clipped)
                        (vreset! current "")))))))
          (let [last-line @current]
            (if (seq last-line)
              (conj @result last-line)
              @result)))))))

(defn wrap-text-with-ansi
  "Word wrap preserving ANSI escape codes.
   First splits on newlines so each returned line is a proper display line
   without embedded newlines. This ensures background padding in parent
   components extends to full terminal width on every line.
   Internal fast path: uses visible-width-plain on already-stripped text."
  [text max-width]
  (if (or (empty? text) (<= max-width 0))
    [""]
    (let [input-lines (clojure.string/split text #"\r\n|\r|\n")
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
   the last max-lines. Lines are returned as a vector of strings.
   This is width-aware — important for long single-line commands or
   narrow terminals where a single string-line may wrap to multiple
   visual lines."
  [text max-lines width]
  (if (or (empty? text) (<= max-lines 0) (<= width 0))
    []
    (let [visual-lines (wrap-text-with-ansi text width)
          n (count visual-lines)]
      (if (<= n max-lines)
        visual-lines
        (vec (take-last max-lines visual-lines))))))
