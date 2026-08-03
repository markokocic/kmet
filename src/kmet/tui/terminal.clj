(ns kmet.tui.terminal
  "JLine 4.x terminal wrapper (Babashka bundles JLine 4.3.1).
   Port of @earendil-works/pi-tui ProcessTerminal.
   API is backward-compatible with JLine 3; implementation uses FFM/JNI."
  (:require [babashka.fs :as fs]
            [clojure.java.io :as io]))

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

(defprotocol ITerminal
  (start! [this on-input on-resize] "Enter raw mode, start reading input")
  (stop! [this] "Restore terminal, clean up")
  (write-output [this s] "Write text to terminal")
  (columns [this] "Terminal width in columns")
  (rows [this] "Terminal height in rows")
  (hide-cursor! [this])
  (show-cursor! [this])
  (clear-line! [this])
  (clear-screen! [this]))

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
  (clear-screen! [this] (write-output this "\u001b[2J\u001b[H")))

(defn create-terminal []
  (let [t (TerminalBuilder/terminal)]
    (map->JLineTerminal {:terminal t})))

(defn create-dumb-terminal []
  (let [t (TerminalBuilder/terminal
           (into-array Object ["dumb" true "system" false]))]
    (map->JLineTerminal {:terminal t})))
