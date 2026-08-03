(ns kmet.tui.terminal
  "JLine 4.x terminal wrapper (Babashka bundles JLine 4.3.1).
   Port of @earendil-works/pi-tui ProcessTerminal — the adapter half only:
   the ITerminal abstraction over JLine. The portable protocol knowledge
   (Kitty keyboard negotiation, escape sequences, write log) lives in
   kmet.libs.terminal; the fns below are thin record-taking wrappers over
   it (write-fn based), plus the JLine-reader drain loop."
  (:require [kmet.libs.terminal :as lib]))

(import '(org.jline.terminal TerminalBuilder Terminal))

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
  (set-title! [this title] "Set the terminal window title (OSC 0)")
  (move-by! [this lines] "Move cursor up (negative) or down (positive) by N lines")
  (clear-from-cursor! [this] "Clear from cursor to end of screen")
  (set-progress! [this active] "Show/hide the terminal progress indicator (OSC 9;4)"))

(defrecord JLineTerminal [^Terminal terminal ^java.io.Reader reader ^java.io.Writer writer
                          input-handler resize-handler running? progress-interval-atom]

  ITerminal
  (start! [this on-input on-resize]
    (let [t (:terminal this)
          r (.reader t)
          w (.writer t)]
      (.enterRawMode t)
      (.write w lib/BRACKETED-PASTE-ON)
      (.flush w)
      (assoc this :reader r :writer w
             :input-handler on-input
             :resize-handler on-resize
             :running? true)))

  (stop! [this]
    (when (:running? this)
      (try
        (when-let [w (:writer this)]
          (.write w lib/BRACKETED-PASTE-OFF)
          (.flush w))
        (finally
          (.close (:terminal this))))
      (assoc this :running? false)))

  (write-output [this s]
    (when-let [w (:writer this)]
      (.write w s)
      (.flush w)
      (lib/write-log! s)))

  (columns [this] (.getWidth (:terminal this)))
  (rows [this] (.getHeight (:terminal this)))
  (hide-cursor! [this] (write-output this "\u001b[?25l"))
  (show-cursor! [this] (write-output this "\u001b[?25h"))
  (clear-line! [this] (write-output this "\u001b[2K"))
  (clear-screen! [this] (write-output this "\u001b[2J\u001b[H"))
  (set-title! [this title] (write-output this (str "\u001b]0;" title "\u0007")))
  (move-by! [this lines]
    (cond
      (pos? lines) (write-output this (str "\u001b[" lines "B"))
      (neg? lines) (write-output this (str "\u001b[" (- lines) "A"))
      :else nil))
  (clear-from-cursor! [this] (write-output this "\u001b[J"))
  (set-progress! [this active]
    (if active
      (do (write-output this lib/TERMINAL-PROGRESS-ACTIVE-SEQUENCE)
          (when (nil? @(:progress-interval-atom this))
            ;; Keepalive: some terminals drop the progress indicator without
            ;; periodic re-assertion (pi: setInterval keepalive)
            (reset! (:progress-interval-atom this)
                    (future
                      (try
                        (loop []
                          (Thread/sleep lib/TERMINAL-PROGRESS-KEEPALIVE-MS)
                          (when @(:progress-interval-atom this)
                            (write-output this lib/TERMINAL-PROGRESS-ACTIVE-SEQUENCE)
                            (recur)))
                        (catch InterruptedException _))))))
      (do (when-let [f @(:progress-interval-atom this)]
            (future-cancel f)
            (reset! (:progress-interval-atom this) nil))
          (write-output this lib/TERMINAL-PROGRESS-CLEAR-SEQUENCE)))))

(defn create-terminal []
  (let [t (TerminalBuilder/terminal)]
    (map->JLineTerminal {:terminal t :progress-interval-atom (atom nil)})))

(defn create-dumb-terminal []
  (let [t (TerminalBuilder/terminal
           (into-array Object ["dumb" true "system" false]))]
    (map->JLineTerminal {:terminal t :progress-interval-atom (atom nil)})))

;; ─── Kitty protocol wrappers (lib fns bound to this terminal's writer) ─────

(defn write-fn
  "A write-fn bound to the terminal's write-output (for kmet.libs.terminal)."
  [terminal]
  #(write-output terminal %))

(defn query-kitty-protocol!
  "Send the Kitty keyboard protocol query (pi: queryAndEnableKittyProtocol)."
  [terminal]
  (lib/query-kitty-protocol! (write-fn terminal)))

(defn disable-kitty-protocol!
  "Disable the Kitty keyboard protocol and modifyOtherKeys (pi: drainInput /
   stop). Resets the global kitty-active flag."
  [terminal]
  (lib/disable-kitty-protocol! (write-fn terminal)))

(defn handle-negotiation-sequence!
  "Act on a parsed negotiation response (pi: handleKeyboardProtocolNegotiation-
   Sequence): kitty flags non-zero → enable Kitty protocol; zero flags or a
   device-attributes report (when kitty is inactive) → modifyOtherKeys
   fallback."
  [terminal parsed]
  (lib/handle-negotiation-sequence! (write-fn terminal) parsed))

(defn drain-input!
  "Disable the keyboard protocols and drain pending input so late key
   release sequences do not leak to the parent shell (pi: drainInput —
   max 1000ms, exits after 50ms of input idle). The drain loop is
   JLine-reader specific; the protocol disable is the lib's."
  [terminal]
  (lib/disable-kitty-protocol! (write-fn terminal))
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

;; ─── Terminal queries (pi: terminal.ts / tui.ts) ───────────────────────────

(defn query-cell-size!
  "Query the terminal's cell size in pixels (CSI 16 t). The response
   (\u001b[6;h;wt) is consumed by the TUI input path."
  [terminal]
  (write-output terminal lib/CELL-SIZE-QUERY))

(defn query-osc-11-background!
  "Query the terminal's default background color (OSC 11;?)."
  [terminal]
  (write-output terminal lib/OSC-11-BACKGROUND-QUERY))

(defn query-color-scheme!
  "Query the terminal's color scheme preference (CSI ? 996 n); the reply
   is \u001b[?997;1n (dark) / ;2n (light)."
  [terminal]
  (write-output terminal lib/COLOR-SCHEME-QUERY))

(defn set-color-scheme-notifications!
  "Enable/disable unsolicited color scheme reports (CSI ? 2031 h/l)."
  [terminal enabled?]
  (write-output terminal (if enabled?
                           lib/COLOR-SCHEME-NOTIFICATIONS-ON
                           lib/COLOR-SCHEME-NOTIFICATIONS-OFF)))

;; Re-exported protocol constants (for kmet.tui.core and other tui namespaces)
(def BRACKETED-PASTE-ON lib/BRACKETED-PASTE-ON)
(def BRACKETED-PASTE-OFF lib/BRACKETED-PASTE-OFF)
(def PASTE-START lib/PASTE-START)
(def PASTE-END lib/PASTE-END)
(def CSI-2026-SYNC-ON lib/CSI-2026-SYNC-ON)
(def CSI-2026-SYNC-OFF lib/CSI-2026-SYNC-OFF)
(def DESIRED-KITTY-FLAGS lib/DESIRED-KITTY-FLAGS)
(def KITTY-KEYBOARD-PROTOCOL-QUERY lib/KITTY-KEYBOARD-PROTOCOL-QUERY)
(def NEGOTIATION-FLUSH-TIMEOUT-MS lib/NEGOTIATION-FLUSH-TIMEOUT-MS)
(def OSC-11-BACKGROUND-QUERY lib/OSC-11-BACKGROUND-QUERY)
(def COLOR-SCHEME-QUERY lib/COLOR-SCHEME-QUERY)
(def COLOR-SCHEME-NOTIFICATIONS-ON lib/COLOR-SCHEME-NOTIFICATIONS-ON)
(def COLOR-SCHEME-NOTIFICATIONS-OFF lib/COLOR-SCHEME-NOTIFICATIONS-OFF)
(def CELL-SIZE-QUERY lib/CELL-SIZE-QUERY)
(def TERMINAL-PROGRESS-ACTIVE-SEQUENCE lib/TERMINAL-PROGRESS-ACTIVE-SEQUENCE)
(def TERMINAL-PROGRESS-CLEAR-SEQUENCE lib/TERMINAL-PROGRESS-CLEAR-SEQUENCE)
(def TERMINAL-PROGRESS-KEEPALIVE-MS lib/TERMINAL-PROGRESS-KEEPALIVE-MS)
(def parse-negotiation-sequence lib/parse-negotiation-sequence)
(def negotiation-prefix? lib/negotiation-prefix?)
(def cell-size-response-prefix? lib/cell-size-response-prefix?)
(def osc-11-response-prefix? lib/osc-11-response-prefix?)
(def color-scheme-report-prefix? lib/color-scheme-report-prefix?)
(def parse-osc-11-background-response lib/parse-osc-11-background-response)
(def parse-terminal-color-scheme-report lib/parse-terminal-color-scheme-report)
(def parse-cell-size-response lib/parse-cell-size-response)
(def enable-modify-other-keys! lib/enable-modify-other-keys!)
(def disable-modify-other-keys! lib/disable-modify-other-keys!)
