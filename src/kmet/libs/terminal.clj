(ns kmet.libs.terminal
  "Generic terminal protocol knowledge — escape sequences and the Kitty
   keyboard protocol negotiation (pi: terminal.ts / stdin-buffer.ts).
   Portable: every protocol function takes a write-fn instead of a concrete
   terminal, so this works with any output stream. Self-contained — no
   kmet.* requires (libs rule); raw ANSI escapes live here by design."
  (:require [babashka.fs :as fs]
            [clojure.java.io :as io]))

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

(def ^:const DESIRED-KITTY-FLAGS 7)
(def ^:const KITTY-KEYBOARD-PROTOCOL-QUERY "\u001b[>7u\u001b[?u\u001b[c")
(def ^:const NEGOTIATION-FLUSH-TIMEOUT-MS 150)

(defonce ^:private modify-other-keys-active (atom false))

(defn parse-negotiation-sequence
  "Parse a Kitty keyboard protocol negotiation response (pi:
   parseKeyboardProtocolNegotiationSequence): {:type :kitty-flags
   :flags n} or {:type :device-attributes}, or nil."
  [s]
  (cond
    (re-matches #"\u001b\[\?(\d+)u" s)
    {:type :kitty-flags :flags (parse-long (second (re-matches #"\u001b\[\?(\d+)u" s)))}

    (re-matches #"\u001b\[\?[\d;]*c" s)
    {:type :device-attributes}

    :else nil))

(defn negotiation-prefix?
  "True when s could still become a negotiation response (pi:
   isKeyboardProtocolNegotiationSequencePrefix). Note a bare \"\u001b[\"
   is also an arrow-key prefix — holding it for negotiation adds at most
   one char of latency."
  [s]
  (or (= s "\u001b[")
      (boolean (re-matches #"\u001b\[\?[\d;]*" s))))

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
