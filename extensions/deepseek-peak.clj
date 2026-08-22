(ns kmet.extensions.deepseek-peak
  "/deepseek-peak — DeepSeek API peak/off-peak hours in your local time zone.

   Shows when DeepSeek's API bills at the full (peak) rate vs the discounted
   off-peak rate, converted to the machine's local time zone, plus whether
   it is currently peak or off-peak and how long until the next switch.
   Hours per DeepSeek's official pricing page (api-docs.deepseek.com):
   peak is 01:00–04:00 and 06:00–10:00 UTC daily, everything else is
   off-peak at half the peak rate.

   The window table lives in `peak-windows-utc`; update it there if
   DeepSeek ever changes its schedule.

   In TUI mode the command opens a small overlay dialog (built from
   kmet.tui.*, mounted via ui-custom like extensions/tools.clj); any key
   dismisses it. Headless/print mode falls back to a one-line flash.

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
            [kmet.tui.components.container :as container]
            [kmet.tui.components.text :as text]
            [kmet.tui.keys :as keys]
            [kmet.tui.protocols :as protocols]
            [kmet.tui.theme :as theme]))

;; ─── The schedule (api-docs.deepseek.com/quick_start/pricing) ──────────────

(def ^:private peak-windows-utc
  "Daily peak windows as {:start [hour minute] :end [hour minute]} pairs in
   UTC. Everything outside these windows is off-peak (half the peak rate)."
  [{:start [1 0], :end [4 0]}
   {:start [6 0], :end [10 0]}])

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
   :next-change is the instant the current phase ends."
  [now]
  (let [wins (windows-around now)
        current (some (fn [{:keys [start end] :as w}]
                        (when (and (not (.isBefore now start))
                                   (.isBefore now end))
                          w))
                      wins)
        next-change (if current
                      (:end current)
                      (->> wins
                           (map :start)
                           (filter #(.isBefore now %))
                           (apply min-key #(.toEpochSecond %))))]
    {:phase (if current :peak :off-peak)
     :next-change next-change}))

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

(defn- fmt-windows-compact
  "All WINDOWS on one line: \"01:00–04:00 · 06:00–10:00\", times in ZONE."
  [wins zone]
  (->> wins
       (map (fn [{:keys [start end]}]
              (format "%s–%s" (fmt-local start zone) (fmt-local end zone))))
       (str/join " · ")))

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

;; ─── The dialog (pi: the ctx.ui.custom factory) ────────────────────────────

(defn- peak-dialog
  "Build the static info panel. TUI/TH/KB/CLOSE are what ui-custom passes
   to factories (pi: (tui, theme, kb, done)); any key dismisses the overlay,
   mouse/focus sequences are ignored so stray terminal events don't close it."
  [_tui th _kb close]
  (let [now (now-local)
        zone (.getZone now)
        {:keys [phase next-change]} (peak-status now)
        remaining (format-duration (java.time.Duration/between now next-change))
        peak? (= :peak phase)
        status-color (if peak? :error :success)
        window-rows (windows-on-utc-date now 0)
        lines [(theme/fg th :accent (theme/bold "DeepSeek API Peak Hours"))
               ;; the schedule block is static reference — all dim
               (theme/dim "Peak (full rate):")
               (theme/dim (str (fmt-windows-compact window-rows utc) " UTC"))
               (theme/dim (str (fmt-windows-compact window-rows zone) " " zone))
               (theme/dim "Off-peak: half rate")
               ""
               (str (theme/fg th status-color
                              (theme/bold (if peak? "● PEAK" "● OFF-PEAK")))
                    (format " — %s at %s (in %s)"
                            (if peak? "off-peak" "peak")
                            (fmt-local next-change zone true)
                            remaining))]
        c (container/make-container
           (mapv #(text/make-text % 0 0) lines))]
    ;; duck-typed component like extensions/tools.clj's wrapper
    {:render (fn [width] (protocols/render c width))
     :handle-input (fn [data]
                     (when-not (or (keys/mouse-sequence? data)
                                   (keys/focus-sequence? data))
                       (close nil)))
     :invalidate (fn [] (protocols/invalidate c))}))

(defn- show-peak-info!
  "Open the dialog in TUI mode; flash a one-line summary otherwise
   (headless/print has no UI registry — pi: ctx.mode !== 'tui')."
  [api ctx]
  (when (or (not= :interactive (:mode ctx))
            (not (ext/ui-custom api peak-dialog
                                {:overlay true
                                 :overlay-options {:anchor :center :width 72}})))
    (ext/ui-notify api (summary-line (now-local)))))

(defn init
  "Register the /deepseek-peak command."
  [api]
  (ext/register-command!
   api {:name "deepseek-peak"
        :description "DeepSeek API peak/off-peak hours in your local time zone"
        :handler (fn [ctx _args] (show-peak-info! api ctx))}))
