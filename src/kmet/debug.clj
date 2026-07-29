(ns kmet.debug
  "Minimal debug logging to debug.log and error logging to kmet.error.log.
   Enable debug.log with --debug flag at startup.
   kmet.error.log is always written on unhandled errors.")

(defonce ^:private log-path "debug.log")
(defonce ^:private error-path "kmet.error.log")
(defonce ^:private enabled? (atom false))

(defn- timestamp []
  (str (java.time.LocalDateTime/now)))

(defn- write-line [path line]
  (try
    (spit path line :append true)
    (catch Exception _
      ;; Silently ignore — don't disrupt the main program
      nil)))

(defn- exception-str
  "Format an exception with class, message, and full stack trace."
  [e]
  (let [sw (java.io.StringWriter.)
        pw (java.io.PrintWriter. sw)]
    (.printStackTrace e pw)
    (.flush pw)
    (str (.getName (class e)) ": " (.getMessage e) "\n" (.toString sw))))

(defn enable!
  "Enable debug logging."
  []
  (reset! enabled? true)
  (log "─── debug logging enabled ───"))

(defn disable!
  "Disable debug logging."
  []
  (log "─── debug logging disabled ───")
  (reset! enabled? false))

(defn log
  "Write a timestamped line to debug.log if logging is enabled.
   If the last argument is an Exception, formats it with full stack trace."
  [& parts]
  (when @enabled?
    (let [parts-str (apply str
                     (map (fn [p] (if (instance? Exception p) (exception-str p) (str p)))
                       parts))]
      (write-line log-path (str "[" (timestamp) "] " parts-str "\n")))))

(defn enabled?
  "Returns true if debug logging is currently enabled."
  []
  @enabled?)

(defn log-error
  "Write a timestamped error to kmet.error.log. Always writes, regardless of debug mode.
   If the last argument is an Exception, formats it with full stack trace."
  [& parts]
  (let [parts-str (apply str
                   (map (fn [p] (if (instance? Exception p) (exception-str p) (str p)))
                     parts))
        line (str "[" (timestamp) "] ERROR: " parts-str "\n")]
    (write-line error-path line)
    ;; Also log to debug.log if enabled
    (when @enabled?
      (write-line log-path line))))
