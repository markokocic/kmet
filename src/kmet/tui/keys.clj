(ns kmet.tui.keys
  "Keyboard input handling.
   Supports both legacy terminal sequences and Kitty keyboard protocol.
   Port of @earendil-works/pi-tui keys.ts — parseKey covers Kitty CSI-u
   (with alternate keys + event types), modifyOtherKeys, mode-aware
   legacy sequences, and the full legacy table."
  (:require [clojure.string :as str]))

;; ─── Key constants ──────────────────────────────────────────────────────────

(def ^:const KEY-UP     "up")
(def ^:const KEY-DOWN   "down")
(def ^:const KEY-LEFT   "left")
(def ^:const KEY-RIGHT  "right")
(def ^:const KEY-ENTER  "enter")
(def ^:const KEY-ESC    "escape")
(def ^:const KEY-TAB    "tab")
(def ^:const KEY-BACKSPACE "backspace")
(def ^:const KEY-DELETE "delete")
(def ^:const KEY-HOME   "home")
(def ^:const KEY-END    "end")
(def ^:const KEY-PAGE-UP   "pageUp")
(def ^:const KEY-PAGE-DOWN "pageDown")
(def ^:const KEY-INSERT "insert")
(def ^:const KEY-SPACE  "space")

(defn ctrl [k] (str "ctrl+" k))
(defn shift [k] (str "shift+" k))
(defn alt [k] (str "alt+" k))
(defn ctrl-shift [k] (str "ctrl+shift+" k))

;; ─── Kitty protocol state ───────────────────────────────────────────────────

(defonce ^:private kitty-active (atom false))

(defn set-kitty-active! [v] (reset! kitty-active v))
(defn kitty-active? [] @kitty-active)

;; ─── Kitty key decoding (pi: keys.ts formatParsedKey) ──────────────────────

(def ^:private MODIFIER-SHIFT 1)
(def ^:private MODIFIER-ALT 2)
(def ^:private MODIFIER-CTRL 4)
(def ^:private MODIFIER-SUPER 8)
(def ^:private LOCK-MASK 192)  ;; Caps Lock + Num Lock

(def ^:private SYMBOL-KEYS
  #{"`" "-" "=" "[" "]" "\\" ";" "'" "," "." "/" "!" "@" "#" "$" "%" "^"
    "&" "*" "(" ")" "_" "+" "|" "~" "{" "}" ":" "<" ">" "?"})

;; Negative values are pi's FUNCTIONAL/ARROW_CODEPOINTS (up -1 … end -15).
(def ^:private KITTY-FUNCTIONAL-EQUIVALENTS
  {57399 48, 57400 49, 57401 50, 57402 51, 57403 52, 57404 53, 57405 54,
   57406 55, 57407 56, 57408 57, 57409 46, 57410 47, 57411 42, 57412 45,
   57413 43, 57415 61, 57416 44,
   57417 -4, 57418 -3, 57419 -1, 57420 -2,
   57421 -12, 57422 -13, 57423 -14, 57424 -15, 57425 -11, 57426 -10})

(defn- kitty-functional-equiv [cp]
  (get KITTY-FUNCTIONAL-EQUIVALENTS cp cp))

(defn- normalize-shifted-letter-identity
  "Shift + uppercase letter reports the lowercase identity codepoint
   (pi: normalizeShiftedLetterIdentityCodepoint)."
  [cp modifier]
  (if (and (pos? (bit-and modifier MODIFIER-SHIFT))
           (<= 65 cp 90))
    (+ cp 32)
    cp))

(defn- format-key-name-with-modifiers
  "Prefix KEY-NAME with the modifier names, pi order (shift/ctrl/alt/super).
   Rejects unknown modifier bits; returns nil then."
  [key-name modifier]
  (let [effective (bit-and modifier (bit-not LOCK-MASK))
        supported (bit-or MODIFIER-SHIFT MODIFIER-CTRL MODIFIER-ALT MODIFIER-SUPER)]
    (when (zero? (bit-and effective (bit-not supported)))
      (let [mods (cond-> []
                   (pos? (bit-and effective MODIFIER-SHIFT)) (conj "shift")
                   (pos? (bit-and effective MODIFIER-CTRL)) (conj "ctrl")
                   (pos? (bit-and effective MODIFIER-ALT)) (conj "alt")
                   (pos? (bit-and effective MODIFIER-SUPER)) (conj "super"))]
        (if (seq mods)
          (str (str/join "+" mods) "+" key-name)
          key-name)))))

(defn- safe-char
  "Char string for a valid printable codepoint (nil otherwise)."
  [cp]
  (when (and (>= cp 32) (<= cp 0x10ffff))
    (str (char cp))))

(defn- format-parsed-key
  "Format a decoded key event into a key id (pi: formatParsedKey). Uses the
   base layout key only when the codepoint is not a recognized Latin
   letter/digit/symbol (remapped-layout protection)."
  [codepoint modifier & [base-layout-key]]
  (let [normalized (kitty-functional-equiv codepoint)
        identity (normalize-shifted-letter-identity normalized modifier)
        is-latin-letter? (<= 97 identity 122)
        is-digit? (<= 48 identity 57)
        is-known-symbol? (contains? SYMBOL-KEYS (safe-char identity))
        effective (if (or is-latin-letter? is-digit? is-known-symbol?)
                    identity
                    (or base-layout-key identity))
        key-name (cond
                   (= effective 27) "escape"
                   (= effective 9) "tab"
                   (or (= effective 13) (= effective 57414)) "enter"
                   (= effective 32) "space"
                   (= effective 127) "backspace"
                   (= effective -10) "delete"
                   (= effective -11) "insert"
                   (= effective -14) "home"
                   (= effective -15) "end"
                   (= effective -12) "pageUp"
                   (= effective -13) "pageDown"
                   (= effective -1) "up"
                   (= effective -2) "down"
                   (= effective -3) "right"
                   (= effective -4) "left"
                   (safe-char effective) (safe-char effective)
                   :else nil)]
    (when key-name
      (format-key-name-with-modifiers key-name modifier))))

(defn- parse-kitty-sequence
  "Decode a Kitty protocol sequence (pi: parseKittySequence):
   - CSI u with alternate keys (flag 4) and event types (flag 2):
     \\u001b[<cp>[:<shifted>[:<base>]];[<mod>[:<event>]]u
   - Arrows with modifier: \\u001b[1;<mod>[:<event>]A/B/C/D
   - Functional keys: \\u001b[<num>[;<mod>[:<event>]]~
   - Home/End with modifier: \\u001b[1;<mod>[:<event>]H/F
   Returns {:codepoint :modifier :base-layout-key} or nil. Modifiers are
   normalized from 1-indexed to the bitmask (pi: modValue - 1)."
  [data]
  (or
   ;; shifted-key group is decoded but unused for key ids (pi uses it only
   ;; for printable decoding, which kmet does not need)
   (when-let [[_ cp _shifted base mod _evt]
              (re-matches #"\u001b\[(\d+)(?::(\d*))?(?::(\d+))?(?:;(\d+))?(?::(\d+))?u" data)]
     {:codepoint (parse-long cp)
      :base-layout-key (when (seq base) (parse-long base))
      :modifier (dec (parse-long (or mod "1")))})
   (when-let [[_ mod _evt arrow]
              (re-matches #"\u001b\[1;(\d+)(?::(\d+))?([ABCD])" data)]
     {:codepoint (case arrow "A" -1 "B" -2 "C" -3 "D" -4)
      :modifier (dec (parse-long mod))})
   (when-let [[_ num mod _evt]
              (re-matches #"\u001b\[(\d+)(?:;(\d+))?(?::(\d+))?~" data)]
     (when-let [cp (case (parse-long num)
                     2 -11  ;; insert
                     3 -10  ;; delete
                     5 -12  ;; pageUp
                     6 -13  ;; pageDown
                     7 -14  ;; home
                     8 -15  ;; end
                     nil)]
       {:codepoint cp :modifier (dec (parse-long (or mod "1")))}))
   (when-let [[_ mod _evt hf]
              (re-matches #"\u001b\[1;(\d+)(?::(\d+))?([HF])" data)]
     {:codepoint (if (= hf "H") -14 -15)
      :modifier (dec (parse-long mod))})))

(defn- parse-modify-other-keys
  "Decode xterm modifyOtherKeys format CSI 27;mods;code ~
   (pi: parseModifyOtherKeysSequence)."
  [data]
  (when-let [[_ mod code] (re-matches #"\u001b\[27;(\d+);(\d+)~" data)]
    {:codepoint (parse-long code)
     :modifier (dec (parse-long mod))}))

;; ─── Legacy key sequence map (pi: LEGACY_SEQUENCE_KEY_IDS) ─────────────────

(defonce ^:private legacy-map
  (delay
    (into {}
          [["\u001b[A"    KEY-UP]
           ["\u001b[B"    KEY-DOWN]
           ["\u001b[C"    KEY-RIGHT]
           ["\u001b[D"    KEY-LEFT]
           ["\u001b[H"    KEY-HOME]
           ["\u001b[F"    KEY-END]
           ["\u001b[1~"   KEY-HOME]
           ["\u001b[4~"   KEY-END]
           ["\u001b[2~"   KEY-INSERT]
           ["\u001b[3~"   KEY-DELETE]
           ["\u001b[5~"   KEY-PAGE-UP]
           ["\u001b[6~"   KEY-PAGE-DOWN]
           ["\u001b[7~"   KEY-HOME]
           ["\u001b[8~"   KEY-END]
           ["\u001b[[5~"  KEY-PAGE-UP]
           ["\u001b[[6~"  KEY-PAGE-DOWN]
           ["\u001b[E"    "clear"]
           ["\u001bOE"    "clear"]
       ;; Shift + cursor / functional
           ["\u001b[a"    (shift KEY-UP)]
           ["\u001b[b"    (shift KEY-DOWN)]
           ["\u001b[c"    (shift KEY-RIGHT)]
           ["\u001b[d"    (shift KEY-LEFT)]
           ["\u001b[e"    (shift "clear")]
           ["\u001b[2$"   (shift KEY-INSERT)]
           ["\u001b[3$"   (shift KEY-DELETE)]
           ["\u001b[5$"   (shift KEY-PAGE-UP)]
           ["\u001b[6$"   (shift KEY-PAGE-DOWN)]
           ["\u001b[7$"   (shift KEY-HOME)]
           ["\u001b[8$"   (shift KEY-END)]
       ;; Ctrl + cursor / functional
           ["\u001bOa"    (ctrl KEY-UP)]
           ["\u001bOb"    (ctrl KEY-DOWN)]
           ["\u001bOc"    (ctrl KEY-RIGHT)]
           ["\u001bOd"    (ctrl KEY-LEFT)]
           ["\u001bOe"    (ctrl "clear")]
           ["\u001b[2^"   (ctrl KEY-INSERT)]
           ["\u001b[3^"   (ctrl KEY-DELETE)]
           ["\u001b[5^"   (ctrl KEY-PAGE-UP)]
           ["\u001b[6^"   (ctrl KEY-PAGE-DOWN)]
           ["\u001b[7^"   (ctrl KEY-HOME)]
           ["\u001b[8^"   (ctrl KEY-END)]
       ;; Ctrl + cursor (xterm CSI-with-modifier form — also parsed by the
       ;; Kitty arrow parser, kept here for non-Kitty terminals)
           ["\u001b[1;5A"  (ctrl KEY-UP)]
           ["\u001b[1;5B"  (ctrl KEY-DOWN)]
           ["\u001b[1;5C"  (ctrl KEY-RIGHT)]
           ["\u001b[1;5D"  (ctrl KEY-LEFT)]
       ;; Alt + arrows (ESC + legacy)
           ["\u001b\u001b[A"  (alt KEY-UP)]
           ["\u001b\u001b[B"  (alt KEY-DOWN)]
           ["\u001b\u001b[C"  (alt KEY-RIGHT)]
           ["\u001b\u001b[D"  (alt KEY-LEFT)]
       ;; Emacs-style alt bindings (ESC + letter, pi legacy map)
           ["\u001bb"    (alt KEY-LEFT)]
           ["\u001bf"    (alt KEY-RIGHT)]
           ["\u001bp"    (alt KEY-UP)]
           ["\u001bn"    (alt KEY-DOWN)]
       ;; Alt + Enter / space / backspace
           ["\u001b\r"   (alt "enter")]
           ["\u001b\n"   (alt "enter")]
           ["\u001b "    (alt "space")]
           ["\u001b\u007f" (alt "backspace")]
           ["\u001b\b"   (alt "backspace")]
       ;; Alt + left/right (pi maps ESC+B / ESC+F before the generic alt rule)
           ["\u001bB"    (alt KEY-LEFT)]
           ["\u001bF"    (alt KEY-RIGHT)]
       ;; Shift + Tab
           ["\u001b[Z"    (shift "tab")]
       ;; SS3 arrows (application cursor mode)
           ["\u001bOA"    KEY-UP]
           ["\u001bOB"    KEY-DOWN]
           ["\u001bOC"    KEY-RIGHT]
           ["\u001bOD"    KEY-LEFT]
       ;; Function keys (all pi legacy forms)
           ["\u001bOP"    "f1"]
           ["\u001bOQ"    "f2"]
           ["\u001bOR"    "f3"]
           ["\u001bOS"    "f4"]
           ["\u001b[11~"  "f1"]
           ["\u001b[12~"  "f2"]
           ["\u001b[13~"  "f3"]
           ["\u001b[14~"  "f4"]
           ["\u001b[[A"   "f1"]
           ["\u001b[[B"   "f2"]
           ["\u001b[[C"   "f3"]
           ["\u001b[[D"   "f4"]
           ["\u001b[[E"   "f5"]
           ["\u001b[15~"  "f5"]
           ["\u001b[17~"  "f6"]
           ["\u001b[18~"  "f7"]
           ["\u001b[19~"  "f8"]
           ["\u001b[20~"  "f9"]
           ["\u001b[21~"  "f10"]
           ["\u001b[23~"  "f11"]
           ["\u001b[24~"  "f12"]])))

;; ─── Key matching ───────────────────────────────────────────────────────────

(declare parse-key)

(defn matches-key?
  "Check if raw input data matches a key identifier (e.g. \"ctrl+c\", \"up\").
   Modifier order is insignificant (pi: parseKeyId splits on '+'), so
   \"shift+ctrl+p\" and \"ctrl+shift+p\" match the same key."
  [data key-id]
  (let [parsed (parse-key data)
        normalize (fn [id]
                    (let [parts (str/split id #"\+")]
                      (when (seq parts)
                        {:key (last parts)
                         :mods (set (butlast parts))})))]
    (when parsed
      (let [a (normalize parsed)
            b (normalize key-id)]
        (and a b (= a b))))))

(defn parse-key
  "Parse raw terminal input into a key identifier string.
   Returns the key-id or nil if unrecognized. Mirrors pi's parseKey order:
   Kitty CSI-u / arrows / functional keys, modifyOtherKeys, mode-aware
   legacy sequences, the legacy table, then single characters."
  [data]
  (or
   ;; Kitty protocol sequences (any modifier combination)
   (when-let [k (parse-kitty-sequence data)]
     (format-parsed-key (:codepoint k) (:modifier k) (:base-layout-key k)))

   ;; xterm modifyOtherKeys fallback: CSI 27;mods;code ~
   (when-let [m (parse-modify-other-keys data)]
     (format-parsed-key (:codepoint m) (:modifier m)))

   ;; Mode-aware legacy sequences (pi): with Kitty active, \u001b\r and \n
   ;; are shift+enter (custom terminal mappings), not alt+enter/ctrl+j.
   (when @kitty-active
     (case data
       "\u001b\r" "shift+enter"
       "\n" "shift+enter"
       nil))

   (get @legacy-map data)

   ;; Standalone control sequences (pi parseKey singles)
   (case data
     "\u001b\u001b" "ctrl+alt+["
     "\u001b\u001c" "ctrl+alt+\\"
     "\u001b\u001d" "ctrl+alt+]"
     "\u001b\u001f" "ctrl+alt+-"
     "\u001bOM" "enter"
     "\u0000" "ctrl+space"
     "\u001b " "alt+space"
     nil)

   ;; ESC + ctrl char → ctrl+alt+letter; ESC + printable → alt+key
   ;; (pi legacy alt/modifier handling, skipped when Kitty is active)
   (when (and (not @kitty-active)
              (= (count data) 2)
              (= (first data) \u001b))
     (let [code (int (nth data 1))]
       (cond
         (and (>= code 1) (<= code 26))
         (str "ctrl+alt+" (char (+ (dec code) (int \a))))

         (or (and (>= code 97) (<= code 122))
             (and (>= code 48) (<= code 57))
             (contains? SYMBOL-KEYS (str (char code))))
         (str "alt+" (char code))

         :else nil)))

   ;; Ctrl+letter or Ctrl+symbol
   (when (and (= (count data) 1)
              (let [c (int (first data))]
                (< c 32)))
     (let [base (case (int (first data))
                  8 "backspace"
                  9 "tab"
                  13 "enter"
                  27 "escape"
                  127 "backspace"
                  ;; default: 0x01-0x1a -> ctrl+a..ctrl+z
                  ;;          0x1c-0x1f -> ctrl+\\, ctrl+], ctrl+^, ctrl+-
                  (let [n (int (first data))]
                    (cond
                      (and (>= n 1) (<= n 26))
                      (str "ctrl+" (char (+ (dec n) (int \a))))
                      (== n 28) "ctrl+\\"
                      (== n 29) "ctrl+]"
                      (== n 30) "ctrl+^"
                      (== n 31) "ctrl+-"
                      :else nil)))]
       base))

   ;; Backspace (DEL = 0x7f, BS = 0x08)
   (when (and (= (count data) 1)
              (let [c (int (first data))]
                (or (== c 0x7f) (== c 0x08))))
     "backspace")

   ;; Space is its own key id (pi); other printable chars parse to themselves
   (when (= data " ")
     "space")

   ;; Regular character
   (when (= (count data) 1)
     (let [c (first data)]
       (when (and (>= (int c) 32) (<= (int c) 126))
         (str c))))
   nil))

;; ─── Sequence helpers ───────────────────────────────────────────────────────

(defn is-key-release?
  "Check if the data looks like a key release event (Kitty protocol, event
   type 3). Bracketed paste content is never a release event (pi: bluetooth
   MAC addresses like \"90:62:3F:A5\" contain \":3F\")."
  [data]
  (when-not (str/includes? data "\u001b[200~")
    (when @kitty-active
      (when (and (str/includes? data ":3")
                 (or (str/includes? data "u") (str/includes? data "~")
                     (str/includes? data "A") (str/includes? data "B")
                     (str/includes? data "C") (str/includes? data "D")))
        true))))

(defn is-key-repeat?
  "Check if the data looks like a key repeat event (Kitty protocol, event
   type 2). Bracketed paste content is never a repeat event."
  [data]
  (when-not (str/includes? data "\u001b[200~")
    (when @kitty-active
      (when (and (str/includes? data ":2")
                 (or (str/includes? data "u") (str/includes? data "~")
                     (str/includes? data "A") (str/includes? data "B")
                     (str/includes? data "C") (str/includes? data "D")))
        true))))

;; ─── Escape sequence prefix detection ──────────────────────────────────────

(defn- csi-prefix?
  "True if s is a valid prefix of a CSI sequence (ESC [ ... final byte)."
  [s]
  (and (str/starts-with? s "\u001b[")
       (let [payload (subs s 2)]
         (or (empty? payload)
             (and (not (re-find #"[\u0040-\u007e]$" payload))
                  (not (re-find #"[\u0000-\u001f]" payload)))))))

(defn- ss3-prefix?
  "True if s is a valid prefix of an SS3 sequence (ESC O + 1 char)."
  [s]
  (and (str/starts-with? s "\u001bO")
       (< (count s) 3)))

(defn escape-prefix?
  "Check if string s could be the prefix of a known escape sequence.
   Returns true if more characters might complete a valid sequence."
  [s]
  (or (= s "\u001b")
      (= s "\u001b\u001b")
      (csi-prefix? s)
      (ss3-prefix? s)
      ;; ESC + single char that could be alt/modifier prefix
      (and (str/starts-with? s "\u001b")
           (= (count s) 2)
           (let [c (int (nth s 1))]
             (or (and (>= c 32) (<= c 126))
                 (= c 27))))))
