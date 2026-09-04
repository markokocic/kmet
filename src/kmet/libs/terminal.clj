(ns kmet.libs.terminal
  "Generic terminal protocol knowledge — escape sequences and the Kitty
   keyboard protocol negotiation (pi: terminal.ts / stdin-buffer.ts).
   Portable: every protocol function takes a write-fn instead of a concrete
   terminal, so this works with any output stream. Self-contained — no
   kmet.* requires (libs rule); raw ANSI escapes live here by design."
  (:require [babashka.fs :as fs]
            [clojure.java.io :as io]
            [clojure.string :as str]))

;; ─── Kitty keyboard protocol state (pi: keys.ts global state) ──────────────

(defonce ^:private kitty-active (atom false))

(defn set-kitty-active! [v] (reset! kitty-active v))
(defn kitty-active? [] @kitty-active)

;; ─── Raw ANSI stream capture (pi: PI_TUI_WRITE_LOG) ────────────────────────
;; When KMET_TUI_WRITE_LOG points at a directory, a timestamped
;; tui-<ts>-<pid>.log file is created inside it; when it points at a file,
;; that file is appended to.

(def ^:private write-log-path
  (let [env (System/getenv "KMET_TUI_WRITE_LOG")]
    (when (and env (seq env))
      (if (fs/directory? env)
        (let [now (java.time.LocalDateTime/now)
              ts (format "%d-%02d-%02d_%02d-%02d-%02d"
                         (.getYear now) (.getMonthValue now) (.getDayOfMonth now)
                         (.getHour now) (.getMinute now) (.getSecond now))]
          (str env fs/file-separator "tui-" ts "-" (.pid (java.lang.ProcessHandle/current)) ".log"))
        env))))

(defn write-log!
  "Append raw output to the write-log path, ignoring errors (pi: write())."
  [s]
  (when write-log-path
    (try
      (with-open [w (io/writer write-log-path :append true)]
        (.write w s))
      (catch Exception _))))

;; ─── Shared escape sequences ────────────────────────────────────────────────

(def ^:const BRACKETED-PASTE-ON "\u001b[?2004h")
(def ^:const BRACKETED-PASTE-OFF "\u001b[?2004l")
(def ^:const PASTE-START "\u001b[200~")
(def ^:const PASTE-END "\u001b[201~")
(def ^:const CSI-2026-SYNC-ON "\u001b[?2026h")
(def ^:const CSI-2026-SYNC-OFF "\u001b[?2026l")

;; ─── Kitty keyboard protocol negotiation (pi: terminal.ts) ─────────────────
;; Requested flags: 1 = disambiguate escape codes, 2 = report event types,
;; 4 = report alternate keys. The trailing DA query is a sentinel supported
;; by terminals that do not know Kitty keyboard protocol — receiving DA
;; before a Kitty response enables the modifyOtherKeys fallback.

;; ─── Terminal queries + responses (pi: terminal.ts / terminal-colors.ts) ────

(def ^:const OSC-11-BACKGROUND-QUERY "\u001b]11;?\u0007")
(def ^:const COLOR-SCHEME-QUERY "\u001b[?996n")
(def ^:const COLOR-SCHEME-NOTIFICATIONS-ON "\u001b[?2031h")
(def ^:const COLOR-SCHEME-NOTIFICATIONS-OFF "\u001b[?2031l")
(def ^:const CELL-SIZE-QUERY "\u001b[16t")

;; OSC 9;4 terminal progress (pi: terminal.ts TERMINAL_PROGRESS_*)
(def ^:const TERMINAL-PROGRESS-ACTIVE-SEQUENCE "\u001b]9;4;3\u0007")
(def ^:const TERMINAL-PROGRESS-CLEAR-SEQUENCE "\u001b]9;4;0\u0007")
(def ^:const TERMINAL-PROGRESS-KEEPALIVE-MS 1000)

(def ^:private osc-11-response-re #"(?i)\u001b\]11;([^\u0007\u001b]*)(?:\u0007|\u001b\\)")
(def ^:private color-scheme-report-re #"\u001b\[\?997;(1|2)n")
(def ^:private cell-size-response-re #"\u001b\[6;(\d+);(\d+)t")

(defn- parse-osc-hex-channel
  "Parse a hex color channel: 2- or 4-digit hex scaled to 0-255
   (pi: parseOscHexChannel — case-insensitive)."
  [channel]
  (when (re-matches #"(?i)[0-9a-f]+" channel)
    (let [max-v (- (Math/pow 16 (count channel)) 1)]
      (when (pos? max-v)
        (int (Math/round (* (/ (Long/parseLong channel 16) max-v) 255)))))))

(defn parse-osc-11-background-response
  "Parse an OSC 11 background color response (\u001b]11;...\u0007) into
   {:r r :g g :b b} or nil. Accepts #rrggbb, #rrrrggggbbbb, and rgb:/rgba:
   channel forms (pi: parseOsc11BackgroundColor)."
  [s]
  (when-let [[_ value] (re-matches osc-11-response-re s)]
    (let [value (str/trim value)]
      (cond
        (str/starts-with? value "#")
        (let [hex (subs value 1)]
          (cond
            (re-matches #"(?i)[0-9a-f]{6}" hex)
            {:r (Long/parseLong (subs hex 0 2) 16)
             :g (Long/parseLong (subs hex 2 4) 16)
             :b (Long/parseLong (subs hex 4 6) 16)}

            (re-matches #"(?i)[0-9a-f]{12}" hex)
            (let [r (parse-osc-hex-channel (subs hex 0 4))
                  g (parse-osc-hex-channel (subs hex 4 8))
                  b (parse-osc-hex-channel (subs hex 8 12))]
              (when (and r g b) {:r r :g g :b b}))

            :else nil))

        :else
        (let [rgb (str/replace value #"(?i)^rgba?:" "")
              [r g b] (str/split rgb #"/")]
          (when (and r g b)
            (let [r (parse-osc-hex-channel r)
                  g (parse-osc-hex-channel g)
                  b (parse-osc-hex-channel b)]
              (when (and r g b) {:r r :g g :b b}))))))))

(defn parse-terminal-color-scheme-report
  "Parse a terminal color scheme report buffer into :dark / :light, or nil.
   Accepts batched buffers with several reports — the LAST report wins,
   matching pi's repeated-group capture (pi: parseTerminalColorSchemeReport,
   pattern (?: ESC [ ?997;(1|2)n )+)."
  [s]
  (when (re-matches #"(?:\u001b\[\?997;(1|2)n)+" s)
    (let [[_ v] (last (re-seq color-scheme-report-re s))]
      (if (= v "2") :light :dark))))

(defn parse-cell-size-response
  "Parse the cell size response to \u001b[16t (\u001b[6;height;widtht) into
   {:width-px w :height-px h} or nil (pi: consumeCellSizeResponse)."
  [s]
  (when-let [[_ h w] (re-matches cell-size-response-re s)]
    (let [h (Long/parseLong h) w (Long/parseLong w)]
      (when (and (pos? h) (pos? w))
        {:width-px w :height-px h}))))

(def ^:const DESIRED-KITTY-FLAGS 7)
(def ^:const KITTY-KEYBOARD-PROTOCOL-QUERY "\u001b[>7u\u001b[?u\u001b[c")
(def ^:const NEGOTIATION-FLUSH-TIMEOUT-MS 150)

(defonce ^:private modify-other-keys-active (atom false))

(defn parse-negotiation-sequence
  "Parse a Kitty keyboard protocol negotiation response (pi:
   parseKeyboardProtocolNegotiationSequence): {:type :kitty-flags
   :flags n} or {:type :device-attributes}, or nil. The flags response can
   arrive as \u001b[?N u (plain query response) or \u001b[>N u (push/pop
   protocol — Termux answers the query this way); both are recognized so
   the response is consumed instead of leaking into the input buffer, where
   an unparseable ESC sequence would swallow every subsequent key."
  [s]
  (cond
    (re-matches #"\u001b\[\?(\d+)u" s)
    {:type :kitty-flags :flags (parse-long (second (re-matches #"\u001b\[\?(\d+)u" s)))}

    (re-matches #"\u001b\[>(\d+)(?:;[\d;]*)?u" s)
    {:type :kitty-flags :flags (parse-long (second (re-matches #"\u001b\[>(\d+)(?:;[\d;]*)?u" s)))}

    (re-matches #"\u001b\[\?[\d;]*c" s)
    {:type :device-attributes}

    :else nil))

(defn negotiation-prefix?
  "True when s could still become a negotiation response (pi:
   isKeyboardProtocolNegotiationSequencePrefix). A bare \"\u001b[\" is NOT
   held: it is also an arrow-key/modifyOtherKeys prefix, and holding it
   while a stalled remainder is in flight (WSL/conpty splits sequences 50ms+
   apart) corrupts ctrl+arrow/ctrl+letter keys into escapes + literal text.
   Only the unambiguous query introducers (\"\u001b[?\" with digits,
   \"\u001b[>\") are held. The \u001b[>...u push form is a prefix too."
  [s]
  (or (boolean (re-matches #"\u001b\[\?[\d;]+" s))
      (boolean (re-matches #"\u001b\[>[\d;]*" s))))

(defn cell-size-response-prefix?
  "True when s could still become a cell size response (\u001b[6;h;wt).
   \u001b[6~ (PageDown) does not match — the required ';' disambiguates.
   A bare \"\u001b[\" or \"\u001b[6\" also prefixes arrows/keys — only
   the longer, response-shaped fragments (with ';') are held, so stalled
   key sequences (WSL/conpty splits) are never swallowed as responses."
  [s]
  (boolean (re-matches #"\u001b\[6;[\d;]*" s)))

(defn osc-11-response-prefix?
  "True when s could still become an OSC 11 response."
  [s]
  (boolean (re-matches #"(?i)\u001b\]11;[^\u0007\u001b]*" s)))

(defn color-scheme-report-prefix?
  "True when s could still become a color scheme report (\u001b[?997;Nn)."
  [s]
  (boolean (re-matches #"\u001b\[\?997(?:;[12]?)?" s)))

(defn enable-modify-other-keys!
  "Enable xterm modifyOtherKeys (\u001b[>4;2m) so Ctrl/Alt modified keys
   arrive as CSI 27;mods;code ~ (pi: enableModifyOtherKeys)."
  [write-fn]
  (when-not @modify-other-keys-active
    (write-fn "\u001b[>4;2m")
    (reset! modify-other-keys-active true)))

(defn disable-modify-other-keys!
  "Disable xterm modifyOtherKeys (pi: disableModifyOtherKeys)."
  [write-fn]
  (when @modify-other-keys-active
    (write-fn "\u001b[>4;0m")
    (reset! modify-other-keys-active false)))

(defn query-kitty-protocol!
  "Send the Kitty keyboard protocol query (pi: queryAndEnableKittyProtocol).
   Resets the modifyOtherKeys flag first so a fresh negotiation (initial
   start or resume) starts from a clean state."
  [write-fn]
  (reset! modify-other-keys-active false)
  (write-fn KITTY-KEYBOARD-PROTOCOL-QUERY))

(defn disable-kitty-protocol!
  "Disable the Kitty keyboard protocol and modifyOtherKeys (pi: drainInput /
   stop). Resets the global kitty-active flag."
  [write-fn]
  (write-fn "\u001b[<u")
  (set-kitty-active! false)
  (disable-modify-other-keys! write-fn))

(defn handle-negotiation-sequence!
  "Act on a parsed negotiation response (pi: handleKeyboardProtocolNegotiation-
   Sequence): kitty flags non-zero → enable Kitty protocol; zero flags or a
   device-attributes report (when kitty is inactive) → modifyOtherKeys
   fallback."
  [write-fn parsed]
  (case (:type parsed)
    :kitty-flags
    (if (zero? (:flags parsed))
      (enable-modify-other-keys! write-fn)
      (do (disable-modify-other-keys! write-fn)
          (when-not (kitty-active?)
            (set-kitty-active! true))))

    :device-attributes
    (when-not (kitty-active?)
      (enable-modify-other-keys! write-fn))

    nil))

;; ─── OSC 52 clipboard (pi: copyToClipboard remote-session fallback) ────────
;; The terminal copies the base64 payload to the system clipboard without
;; any platform tool. Refused when the encoded payload exceeds the common
;; terminal limit (pi caps at 100 KB).

(def ^:const max-osc52-encoded-length 100000)

(defn osc52-copy!
  "Copy TEXT via the OSC 52 escape (\u001b]52;c;<base64>\u0007) using
   WRITE-FN. Returns true when emitted, false when the encoded payload is
   too large for terminals (or TEXT is empty)."
  [write-fn text]
  (let [encoded (.encodeToString (java.util.Base64/getEncoder)
                                 (.getBytes (str text) "UTF-8"))]
    (when (and (seq encoded)
               (<= (count encoded) max-osc52-encoded-length))
      (write-fn (str "\u001b]52;c;" encoded "\u0007"))
      true)))
