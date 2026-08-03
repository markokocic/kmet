(ns kmet.tui.terminal
  "JLine 4.x terminal wrapper (Babashka bundles JLine 4.3.1).
   Port of @earendil-works/pi-tui ProcessTerminal.
   API is backward-compatible with JLine 3; implementation uses FFM/JNI."
  (:require [babashka.fs :as fs]
            [clojure.java.io :as io]
            [kmet.tui.keys :as keys]))

(import '(org.jline.terminal TerminalBuilder Terminal))

;; Raw ANSI stream capture (pi: PI_TUI_WRITE_LOG). When KMET_TUI_WRITE_LOG
;; points at a directory, a timestamped tui-<ts>-<pid>.log file is created
;; inside it; when it points at a file, that file is appended to.
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

(defn- write-log!
  "Append raw output to the write-log path, ignoring errors (pi: write())."
  [s]
  (when write-log-path
    (try
      (with-open [w (io/writer write-log-path :append true)]
        (.write w s))
      (catch Exception _))))

;; ─── Kitty keyboard protocol negotiation (pi: terminal.ts) ──────────────────
;; Requested flags: 1 = disambiguate escape codes, 2 = report event types,
;; 4 = report alternate keys. The trailing DA query is a sentinel supported
;; by terminals that do not know Kitty keyboard protocol — receiving DA
;; before a Kitty response enables the modifyOtherKeys fallback.

(declare write-output)
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
  [terminal]
  (when-not @modify-other-keys-active
    (write-output terminal "\u001b[>4;2m")
    (reset! modify-other-keys-active true)))

(defn disable-modify-other-keys!
  "Disable xterm modifyOtherKeys (pi: disableModifyOtherKeys)."
  [terminal]
  (when @modify-other-keys-active
    (write-output terminal "\u001b[>4;0m")
    (reset! modify-other-keys-active false)))

(defn query-kitty-protocol!
  "Send the Kitty keyboard protocol query (pi: queryAndEnableKittyProtocol).
   Resets the modifyOtherKeys flag first so a fresh negotiation (initial
   start or resume) starts from a clean state."
  [terminal]
  (reset! modify-other-keys-active false)
  (write-output terminal KITTY-KEYBOARD-PROTOCOL-QUERY))

(defn disable-kitty-protocol!
  "Disable the Kitty keyboard protocol and modifyOtherKeys (pi: drainInput /
   stop). Resets the global kitty-active flag."
  [terminal]
  (write-output terminal "\u001b[<u")
  (keys/set-kitty-active! false)
  (disable-modify-other-keys! terminal))

(defn handle-negotiation-sequence!
  "Act on a parsed negotiation response (pi: handleKeyboardProtocolNegotiation-
   Sequence): kitty flags non-zero → enable Kitty protocol; zero flags or a
   device-attributes report (when kitty is inactive) → modifyOtherKeys
   fallback."
  [terminal parsed]
  (case (:type parsed)
    :kitty-flags
    (if (zero? (:flags parsed))
      (enable-modify-other-keys! terminal)
      (do (disable-modify-other-keys! terminal)
          (when-not (keys/kitty-active?)
            (keys/set-kitty-active! true))))

    :device-attributes
    (when-not (keys/kitty-active?)
      (enable-modify-other-keys! terminal))

    nil))

(defn drain-input!
  "Disable the keyboard protocols and drain pending input so late key
   release sequences do not leak to the parent shell (pi: drainInput —
   max 1000ms, exits after 50ms of input idle)."
  [terminal]
  (disable-kitty-protocol! terminal)
  (let [reader (:reader terminal)
        max-ms 1000
        idle-ms 50]
    (loop [last-read (System/nanoTime)
           waited 0]
      (when (and (< waited max-ms)
                 (< (- (System/nanoTime) last-read) (* idle-ms 1000000)))
        (if (and reader (.ready reader))
          (do (.read reader)
              (recur (System/nanoTime) 0))
          (do (Thread/sleep 10)
              (recur last-read (+ waited 10)))))))
  nil)

(defprotocol ITerminal
  (start! [this on-input on-resize] "Enter raw mode, start reading input")
  (stop! [this] "Restore terminal, clean up")
  (write-output [this s] "Write text to terminal")
  (columns [this] "Terminal width in columns")
  (rows [this] "Terminal height in rows")
  (hide-cursor! [this])
  (show-cursor! [this])
  (clear-line! [this])
  (clear-screen! [this])
  (set-title! [this title] "Set the terminal window title (OSC 0)"))

(defrecord JLineTerminal [^Terminal terminal ^java.io.Reader reader ^java.io.Writer writer
                          input-handler resize-handler running?]

  ITerminal
  (start! [this on-input on-resize]
    (let [t (:terminal this)
          r (.reader t)
          w (.writer t)]
      (.enterRawMode t)
      (.write w "\u001b[?2004h")         ;; bracketed paste
      (.flush w)
      (assoc this :reader r :writer w
             :input-handler on-input
             :resize-handler on-resize
             :running? true)))

  (stop! [this]
    (when (:running? this)
      (try
        (when-let [w (:writer this)]
          (.write w "\u001b[?2004l")
          (.flush w))
        (finally
          (.close (:terminal this))))
      (assoc this :running? false)))

  (write-output [this s]
    (when-let [w (:writer this)]
      (.write w s)
      (.flush w)
      (write-log! s)))

  (columns [this] (.getWidth (:terminal this)))
  (rows [this] (.getHeight (:terminal this)))
  (hide-cursor! [this] (write-output this "\u001b[?25l"))
  (show-cursor! [this] (write-output this "\u001b[?25h"))
  (clear-line! [this] (write-output this "\u001b[2K"))
  (clear-screen! [this] (write-output this "\u001b[2J\u001b[H"))
  (set-title! [this title] (write-output this (str "\u001b]0;" title "\u0007"))))

(defn create-terminal []
  (let [t (TerminalBuilder/terminal)]
    (map->JLineTerminal {:terminal t})))

(defn create-dumb-terminal []
  (let [t (TerminalBuilder/terminal
           (into-array Object ["dumb" true "system" false]))]
    (map->JLineTerminal {:terminal t})))
