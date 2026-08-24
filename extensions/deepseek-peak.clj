(ns kmet.extensions.deepseek-peak
  "/deepseek-peak — DeepSeek API peak/off-peak hours in your local time zone.

   Shows when DeepSeek's API bills at the full (peak) rate vs the discounted
   off-peak rate, converted to the machine's local time zone, plus whether
   it is currently peak or off-peak and how long until the next switch.
   Hours per DeepSeek's official pricing page (api-docs.deepseek.com):
   peak is 01:00–04:00 and 06:00–10:00 UTC on weekdays (Monday through
   Friday, Beijing time), everything else is off-peak at half the peak
   rate — since 2026-08-23 the whole weekend is off-peak.

   The window table lives in `peak-windows-utc`; update it there if
   DeepSeek ever changes its schedule.

   Display follows /session: the panel is appended to the chat history as
   an :info message via kmet.extension/ui-chat-info — part of the live
   transcript, no overlay and nothing to dismiss, never sent to the LLM,
   not persisted across restarts. Headless/print mode falls back to a
   one-line flash — as does a host whose running instance predates the
   bridge (/reload refreshes extension files, not host code).

   The local time zone is detected explicitly: on Termux/Android the JVM
   falls back to GMT (no /etc/localtime) while Android keeps the real zone
   in a system property — so TZ, getprop, /etc/timezone and the
   /etc/localtime symlink are consulted before the JVM default.

   Usage: symlink or copy this file into ~/.kmet/agent/extensions/ (global)
   or .kmet/extensions/ (project-local), then restart kmet or run /reload."
  (:require [babashka.fs :as fs]
            [babashka.process :as proc]
            [clojure.string :as str]
            [kmet.extension :as ext]
            [kmet.tui.theme :as theme]))

;; ─── The schedule (api-docs.deepseek.com/quick_start/pricing) ──────────────

(def ^:private peak-windows-utc
  "Weekday peak windows as {:start [hour minute] :end [hour minute]} pairs in
   UTC — peak applies Monday through Friday (in Beijing time; see
   weekend-off-peak?). Everything outside these windows is off-peak (half the
   peak rate)."
  [{:start [1 0], :end [4 0]}
   {:start [6 0], :end [10 0]}])

(defn- weekend-off-peak?
  "True when NOW falls on a Saturday or Sunday in Beijing time — off-peak
   applies throughout the day (rule since 2026-08-23).

   The weekend is read off the +08:00 clock, not UTC: Beijing's weekend runs
   16:00 Friday UTC → 16:00 Sunday UTC. Reading the day-of-week off the
   unshifted instant would bill Friday 16:00–24:00 UTC (Beijing Saturday)
   and Sunday 00:00–16:00 UTC (Beijing Sunday) at peak."
  [now]
  (let [beijing (.withZoneSameInstant now (java.time.ZoneId/of "Asia/Shanghai"))
        dow (.getDayOfWeek beijing)]
    (or (= dow java.time.DayOfWeek/SATURDAY)
        (= dow java.time.DayOfWeek/SUNDAY))))

;; ─── Time helpers (java.time — comparisons are instant-based, so DST and
;;     fractional-offset zones come out right automatically) ────────────────

(def ^:private utc java.time.ZoneOffset/UTC)

(def ^:private hh-mm (java.time.format.DateTimeFormatter/ofPattern "HH:mm"))
(def ^:private dow-hh-mm (java.time.format.DateTimeFormatter/ofPattern "EEE HH:mm"))

(defn- parse-zone
  "ZONE-STRING as a ZoneId, or nil when blank/unparseable."
  [zone-string]
  (when (and zone-string (not (str/blank? zone-string)))
    (try
      (java.time.ZoneId/of (str/trim zone-string))
      (catch Exception _ nil))))

(defn- zone-from-android-prop
  "The Android system timezone (Termux: the JVM default is GMT because
   there is no /etc/localtime — Android keeps the user's zone in a system
   property instead)."
  []
  (try
    (parse-zone (:out (proc/sh "getprop" "persist.sys.timezone")))
    (catch Exception _ nil)))

(defn- zone-from-localtime-link
  "The zone encoded in /etc/localtime's symlink target (Debian-style)."
  []
  (try
    (->> (str (fs/read-link "/etc/localtime"))
         (re-find #"/zoneinfo/(.+)$")
         (second)
         (parse-zone))
    (catch Exception _ nil)))

(def ^:private local-zone
  "The machine's real local zone. Detection order: TZ env var → Android
   system property → /etc/timezone → /etc/localtime symlink → JVM default.
   Detected once per extension load (/reload re-runs it)."
  (delay
    (or (parse-zone (System/getenv "TZ"))
        (zone-from-android-prop)
        (try (parse-zone (slurp "/etc/timezone")) (catch Exception _ nil))
        (zone-from-localtime-link)
        (java.time.ZoneId/systemDefault))))

(defn- now-local
  "NOW as a ZonedDateTime in the detected local zone."
  []
  (java.time.ZonedDateTime/now @local-zone))

(defn- windows-on-utc-date
  "Every peak window whose START falls on NOW's UTC date shifted by
   DAY-OFFSET days."
  [now day-offset]
  (let [date (-> now (.withZoneSameInstant utc) (.toLocalDate) (.plusDays day-offset))]
    (for [{[sh sm] :start, [eh em] :end} peak-windows-utc]
      {:start (java.time.ZonedDateTime/of (.atTime date sh sm) utc)
       :end (java.time.ZonedDateTime/of (.atTime date eh em) utc)})))

(defn- windows-around
  "Windows starting from two days before to one day after NOW's UTC date —
   enough to cover both the current window and the next one regardless of
   the local offset."
  [now]
  (mapcat #(windows-on-utc-date now %) [-2 -1 0 1]))

(defn- peak-status
  "Where NOW sits relative to the schedule:
     {:phase :peak | :off-peak, :next-change ZonedDateTime}
   :next-change is the instant the current phase ends. Peak windows apply
   only Monday through Friday in Beijing time; the whole weekend (Beijing
   time) is off-peak. The next change is the earliest transition after NOW:
   the end of the current window when inside one, otherwise the next window
   start — a weekend is one contiguous off-peak block, so no transition
   occurs at Saturday/Sunday 00:00 Beijing."
  [now]
  (let [in-window? (fn [zdt]
                     (and (not (weekend-off-peak? zdt))
                          (some (fn [w]
                                  (and (not (.isBefore zdt (:start w)))
                                       (.isBefore zdt (:end w))))
                                (windows-around zdt))))
        ;; every candidate phase boundary after NOW, built in UTC (the window
        ;; hours are UTC); a boundary is only real when its own Beijing day is
        ;; a weekday — Saturday/Sunday add no transitions (whole weekend is
        ;; one off-peak block)
        candidates (for [d (range -2 5)
                         :let [date (.plusDays (.toLocalDate (.withZoneSameInstant now utc)) d)]
                         [h m] (concat (map :start peak-windows-utc)
                                       (map :end peak-windows-utc))
                         :let [zdt (java.time.ZonedDateTime/of (.atTime date h m) utc)]
                         :when (not (weekend-off-peak? zdt))
                         :when (.isAfter zdt now)]
                     zdt)
        next-change (apply min-key #(.toEpochSecond %) candidates)
        phase (if (in-window? now) :peak :off-peak)]
    {:phase phase :next-change next-change}))

(defn- format-duration
  "DURATION as a compact human string: \"<1m\", \"42m\", \"1h\", \"1h 37m\"."
  [duration]
  (let [mins (.toMinutes duration)]
    (cond
      (< mins 1) "<1m"
      (< mins 60) (str mins "m")
      :else (let [h (quot mins 60), m (rem mins 60)]
              (if (zero? m) (str h "h") (format "%dh %02dm" h m))))))

(defn- fmt-local
  "Format ZDT shifted into ZONE, HH:mm (with weekday when WITH-DOW?)."
  ([zdt zone] (fmt-local zdt zone false))
  ([zdt zone with-dow?]
   (.format (if with-dow? dow-hh-mm hh-mm)
            (.withZoneSameInstant zdt zone))))

(defn- summary-line
  "One-line status for headless mode (the flash)."
  [now]
  (let [zone (.getZone now)
        {:keys [phase next-change]} (peak-status now)
        remaining (format-duration (java.time.Duration/between now next-change))]
    (if (= :peak phase)
      (format "DeepSeek: PEAK (full rate) — off-peak at %s (in %s)"
              (fmt-local next-change zone true) remaining)
      (format "DeepSeek: OFF-PEAK (half rate) — peak at %s (in %s)"
              (fmt-local next-change zone true) remaining))))

;; ─── The panel (/session-style chat info message) ─────────────────────────

(defn- fmt-window-line
  "One window as \"HH:mm–HH:mm\" in ZONE (local view) with its UTC span on
   the following line: e.g. \"04:00–07:00 Europe/Athens\n  (01:00–04:00 UTC)\"."
  [w zone]
  (let [local (format "%s–%s" (fmt-local (:start w) zone) (fmt-local (:end w) zone))
        utc-span (format "%s–%s" (fmt-local (:start w) utc) (fmt-local (:end w) utc))]
    (str local " " zone "\n"
         "  (" utc-span " UTC)")))

(defn- peak-panel-text
  "The panel as styled plain text for the chat :info message (dim labels,
   bracketed [DeepSeek Peak Hours] label above — the /session look).
   Rendered through the chat's markdown view, which passes ANSI through
   with ANSI-aware wrapping, so no theme instance is needed: only the
   global theme/dim. Every line is kept short (~33 chars max) so the
   panel never wraps inside the message box, even on narrow terminals."
  []
  (let [now (now-local)
        zone (.getZone now)
        {:keys [phase next-change]} (peak-status now)
        remaining (format-duration (java.time.Duration/between now next-change))
        peak? (= :peak phase)
        window-rows (windows-on-utc-date now 0)]
    (str (theme/dim "Peak (full rate):") " Mon–Fri Beijing\n"
         (str/join "\n" (map #(fmt-window-line % zone) window-rows))
         "\n\n"
         (theme/dim "Off-peak: half rate") "\n"
         "  " (theme/dim "all other hours") "\n"
         "  " (theme/dim "all day Sat/Sun") "\n\n"
         (if peak?
           (str (theme/dim "● PEAK — full rate now")
                "\n  " (theme/dim (format "off-peak at %s (in %s)"
                                          (fmt-local next-change zone true)
                                          remaining)))
           (str (theme/dim "● OFF-PEAK — half rate now")
                "\n  " (theme/dim (format "peak at %s (in %s)"
                                          (fmt-local next-change zone true)
                                          remaining)))))))

(defn- show-peak-info!
  "Append the panel as an :info chat message (the /session display style —
   stays in the transcript, nothing to dismiss); flash a one-line summary
   otherwise. Feature-detects the :chat-info capability: a kmet instance
   started before the bridge existed (host code only changes on restart,
   not on /reload) has no such key, and referencing the missing var would
   fail the whole extension load — degrade to the flash instead."
  [api ctx]
  (if-let [chat-info (and (= :interactive (:mode ctx))
                          (get-in api [:ui :chat-info]))]
    (chat-info "DeepSeek Peak Hours" (peak-panel-text))
    (ext/ui-notify api (summary-line (now-local)))))

(defn init
  "Register the /deepseek-peak command."
  [api]
  (ext/register-command!
   api {:name "deepseek-peak"
        :description "DeepSeek API peak/off-peak hours in your local time zone"
        :handler (fn [ctx _args] (show-peak-info! api ctx))}))
