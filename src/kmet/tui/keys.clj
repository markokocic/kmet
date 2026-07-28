(ns kmet.tui.keys
  "Keyboard input handling.
   Supports both legacy terminal sequences and Kitty keyboard protocol.
   Port of @earendil-works/pi-tui keys.ts")

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

;; ─── Legacy key sequence map ────────────────────────────────────────────────

(defonce ^:private legacy-map
  (delay
    (into {}
      [[ "\u001b[A"    KEY-UP]
       [ "\u001b[B"    KEY-DOWN]
       [ "\u001b[C"    KEY-RIGHT]
       [ "\u001b[D"    KEY-LEFT]
       [ "\u001b[H"    KEY-HOME]
       [ "\u001b[F"    KEY-END]
       [ "\u001b[1~"   KEY-HOME]
       [ "\u001b[4~"   KEY-END]
       [ "\u001b[2~"   KEY-INSERT]
       [ "\u001b[3~"   KEY-DELETE]
       [ "\u001b[5~"   KEY-PAGE-UP]
       [ "\u001b[6~"   KEY-PAGE-DOWN]
       [ "\u001b[7~"   KEY-HOME]
       [ "\u001b[8~"   KEY-END]
       ;; Shift + cursor
       [ "\u001b[a"    (shift KEY-UP)]
       [ "\u001b[b"    (shift KEY-DOWN)]
       [ "\u001b[c"    (shift KEY-RIGHT)]
       [ "\u001b[d"    (shift KEY-LEFT)]
       ;; Ctrl + cursor
       [ "\u001bOa"    (ctrl KEY-UP)]
       [ "\u001bOb"    (ctrl KEY-DOWN)]
       [ "\u001bOc"    (ctrl KEY-RIGHT)]
       [ "\u001bOd"    (ctrl KEY-LEFT)]
       ;; Alt + arrows (ESC + legacy)
       [ "\u001b\u001b[A"  (alt KEY-UP)]
       [ "\u001b\u001b[B"  (alt KEY-DOWN)]
       [ "\u001b\u001b[C"  (alt KEY-RIGHT)]
       [ "\u001b\u001b[D"  (alt KEY-LEFT)]
       ;; Emacs-style alt bindings (ESC + letter)
       [ "\u001bb"    (alt KEY-LEFT)]
       [ "\u001bf"    (alt KEY-RIGHT)]
       [ "\u001bp"    (alt KEY-UP)]
       [ "\u001bn"    (alt KEY-DOWN)]
       ;; Function keys
       [ "\u001bOP"    "f1"]
       [ "\u001bOQ"    "f2"]
       [ "\u001bOR"    "f3"]
       [ "\u001bOS"    "f4"]
       [ "\u001b[15~"  "f5"]
       [ "\u001b[17~"  "f6"]
       [ "\u001b[18~"  "f7"]
       [ "\u001b[19~"  "f8"]
       [ "\u001b[20~"  "f9"]
       [ "\u001b[21~"  "f10"]
       [ "\u001b[23~"  "f11"]
       [ "\u001b[24~"  "f12"]])))

;; ─── Kitty protocol state ───────────────────────────────────────────────────

(defonce ^:private kitty-active (atom false))

(defn set-kitty-active! [v] (reset! kitty-active v))
(defn kitty-active? [] @kitty-active)

;; ─── Key matching ───────────────────────────────────────────────────────────

(declare parse-key)

(defn matches-key?
  "Check if raw input data matches a key identifier (e.g. \"ctrl+c\", \"up\")"
  [data key-id]
  (let [parsed (parse-key data)]
    (when parsed
      ;; For now just check identity
      (= parsed key-id))))

(defn parse-key
  "Parse raw terminal input into a key identifier string.
   Returns the key-id or nil if unrecognized."
  [^String data]
  (or (get @legacy-map data)
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
                     ;;          0x1c-0x1f -> ctrl+\\, ctrl+], ctrl+^, ctrl+_
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
      ;; Regular character
      (when (= (count data) 1)
        (let [c (first data)]
          (when (and (>= (int c) 32) (<= (int c) 126))
            (str c))))
      nil))

;; ─── Sequence helpers ───────────────────────────────────────────────────────

(defn is-key-release?
  "Check if the data looks like a key release event (Kitty protocol)"
  [data]
  (when kitty-active
    (when (and (.contains data ":3")
               (or (.contains data "u") (.contains data "~")
                   (.contains data "A") (.contains data "B")
                   (.contains data "C") (.contains data "D")))
      true)))

(defn is-key-repeat?
  "Check if the data looks like a key repeat event (Kitty protocol)"
  [data]
  (when kitty-active
    (when (and (.contains data ":2")
               (or (.contains data "u") (.contains data "~")
                   (.contains data "A") (.contains data "B")
                   (.contains data "C") (.contains data "D")))
      true)))
