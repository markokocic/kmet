(ns kmet.app.ui.footer
  "FooterComponent — Pi's two-line footer:
     line 1: cwd (home-substituted) + git branch, dim
     line 2: usage stats (↑in ↓out R W CH%) + context % colored by usage,
             right-aligned (provider) model • thinking
     line 3 (optional): extension statuses, sorted by key
   Data comes from the FooterDataProvider (pi: FooterComponent + provider).
   No separator line — the two content lines are the footer (pi parity)."
  (:require [clojure.string :as str]
            [babashka.fs :as fs]
            [kmet.tui.protocols :as protocols]
            [kmet.tui.utils :as u]
            [kmet.tui.theme :as theme]
            [kmet.app.ui.footer-data-provider :as fdp]
            [kmet.tui.macros :refer [track! defcomponent]]))

(defn format-tokens
  "Format token counts for compact footer display (pi: formatTokens).
   999 → \"999\", 1234 → \"1.2k\", 12345 → \"12k\", 1234567 → \"1.2M\"."
  [count]
  (let [n (long count)]
    (cond
      (< n 1000) (str n)
      (< n 10000) (str (format "%.1f" (/ (double n) 1000.0)) "k")
      (< n 1000000) (str (Math/round (double (/ n 1000))) "k")
      (< n 10000000) (str (format "%.1f" (/ (double n) 1000000.0)) "M")
      :else (str (Math/round (double (/ n 1000000))) "M"))))

(defn format-cwd-for-footer
  "Replace the home prefix of cwd with ~ (pi: formatCwdForFooter). Returns
   cwd unchanged when it is not inside home."
  [cwd home]
  (if (and home (seq home))
    (let [rel (str (fs/relativize (fs/path home) (fs/path cwd)))]
      (cond
        (empty? rel) "~"
        (str/starts-with? rel (str ".." fs/file-separator)) cwd
        (= rel "..") cwd
        :else (str "~" fs/file-separator rel)))
    cwd))

(defn- sanitize-status-text
  "Sanitize extension status text for a single-line display (pi:
   sanitizeStatusText): collapse whitespace, strip newlines/tabs/CR."
  [text]
  (-> text
      (str/replace #"[\r\n\t]" " ")
      (str/replace #" +" " ")
      str/trim))

(defcomponent FooterComponent nil [provider-atom theme-atom cache-atom
                                   extension-statuses-atom auto-compact-atom]
  (render [this width]
    (track! this width
      (let [thm @theme-atom
            provider @provider-atom
            auto @auto-compact-atom
            usage (fdp/fdp-usage-totals provider)
            hit-rate (fdp/fdp-latest-cache-hit-rate provider)
            tokens (fdp/fdp-context-tokens provider)
            window (fdp/fdp-get-context-window provider)
            home (or (System/getenv "HOME") (System/getenv "USERPROFILE"))
            branch (fdp/fdp-get-git-branch provider)
            pwd (str (format-cwd-for-footer (fdp/fdp-get-cwd provider) home)
                     (when branch (str " (" branch ")")))
            pwd-line (u/truncate-to-width (theme/dim pwd) width (theme/dim "..."))
            ;; ── Stats left (pi: statsParts) ──────────────────────────────
            stats-parts (cond-> []
                          (pos? (:input usage)) (conj (str "↑" (format-tokens (:input usage))))
                          (pos? (:output usage)) (conj (str "↓" (format-tokens (:output usage))))
                          (pos? (:cache-read usage)) (conj (str "R" (format-tokens (:cache-read usage))))
                          (pos? (:cache-write usage)) (conj (str "W" (format-tokens (:cache-write usage)))))
            stats-parts (if (and (or (pos? (:cache-read usage)) (pos? (:cache-write usage)))
                                 hit-rate)
                          (conj stats-parts (str "CH" (format "%.1f" hit-rate) "%"))
                          stats-parts)
            ;; ── Context % (pi: contextPercentDisplay, colored by usage) ──
            percent (when (and window (pos? window)) (* 100.0 (/ tokens window)))
            auto-indicator (if auto " (auto)" "")
            window-display (when window (format-tokens window))
            ctx-display (if percent
                          (str (format "%.1f" percent) "%/" window-display auto-indicator)
                          (if window
                            (str "?/" window-display auto-indicator)
                            (str (format-tokens tokens) " tokens")))
            ctx-colored (cond
                          (and percent (> percent 90)) (theme/fg thm :error ctx-display)
                          (and percent (> percent 70)) (theme/fg thm :warning ctx-display)
                          :else ctx-display)
            stats-left (str/join " " (conj stats-parts ctx-colored))
            ;; ── Right side (pi: (provider) model • thinking) ─────────────
            model-name (or (fdp/fdp-get-model provider) "no-model")
            thinking-level (or (fdp/fdp-get-thinking provider) :off)
            right-side (if (= thinking-level :off)
                         model-name
                         (str model-name " • thinking " (name thinking-level)))
            provider-name (fdp/fdp-get-provider provider)
            right-side (if (and (> (fdp/fdp-get-provider-count provider) 1)
                                provider-name)
                         (str "(" (name provider-name) ") " right-side)
                         right-side)
            ;; ── Assemble line 2 (pi: statsLine right-alignment) ──────────
            stats-left-w0 (u/visible-width stats-left)
            [stats-left stats-left-w] (if (> stats-left-w0 width)
                                        (let [t (u/truncate-to-width stats-left width "...")]
                                          [t (u/visible-width t)])
                                        [stats-left stats-left-w0])
            min-padding 2
            right-w (u/visible-width right-side)
            total-needed (+ stats-left-w min-padding right-w)
            stats-line (if (<= total-needed width)
                         (str stats-left
                              (apply str (repeat (- width stats-left-w right-w) \space))
                              right-side)
                         (let [available (- width stats-left-w min-padding)]
                           (if (pos? available)
                             (let [truncated (u/truncate-to-width right-side available "")
                                   tw (u/visible-width truncated)]
                               (str stats-left
                                    (apply str (repeat (max 0 (- width stats-left-w tw)) \space))
                                    truncated))
                             stats-left)))
            ;; dim the parts independently — stats-left may contain colors
            ;; whose fg resets would clear an outer dim wrapper
            dim-left (theme/dim stats-left)
            dim-remainder (theme/dim (subs stats-line (count stats-left)))
            ;; ── Extension statuses (line 3, pi) ───────────────────────────
            ext-statuses (->> @extension-statuses-atom
                              (sort-by key)
                              (keep (fn [[_k v]] (when v (sanitize-status-text v))))
                              (remove str/blank?))
            ext-line (when (seq ext-statuses)
                       (u/truncate-to-width
                        (theme/dim (str/join " " ext-statuses))
                        width (theme/dim "...")))]
        (into [pwd-line (str dim-left dim-remainder)]
              (when ext-line [ext-line]))))))

;; ─── Construction ──────────────────────────────────────────────────────────

(defn make-footer
  "Create a FooterComponent.
   Options:
     :theme      — Theme record (default dark-theme)
     :provider   — FooterDataProvider (default a bare one with no session)
     :auto-compact — whether auto-compaction is enabled (shows \" (auto)\")"
  [& {:keys [theme provider auto-compact]
      :or {theme theme/dark-theme}}]
  (map->FooterComponent {:provider-atom (atom (or provider (fdp/make-footer-data-provider)))
                         :theme-atom (atom theme)
                         :cache-atom (atom nil)
                         :extension-statuses-atom (atom {})
                         :auto-compact-atom (atom (boolean auto-compact))}))

;; ─── Public API ────────────────────────────────────────────────────────────

(defn footer-set-theme!
  "Switch the footer's theme (live re-theme on theme changes)."
  [comp theme]
  (reset! (:theme-atom comp) theme)
  (protocols/invalidate comp))

(defn footer-set-provider!
  "Swap the FooterDataProvider (session/cwd changes, new session)."
  [comp provider]
  (reset! (:provider-atom comp) provider)
  (protocols/invalidate comp))

(defn footer-set-extension-status!
  "Set/clear a keyed extension status shown on the footer's status line
   (pi: FooterDataProvider.setExtensionStatus). Pass nil text to clear the
   key. Statuses render dim, sorted by key."
  [comp key text]
  (swap! (:extension-statuses-atom comp)
         (fn [m] (if (nil? text) (dissoc m key) (assoc m key text))))
  nil)

(defn footer-set-auto-compact!
  "Enable/disable the \" (auto)\" context indicator (pi:
   setAutoCompactEnabled)."
  [comp enabled?]
  (reset! (:auto-compact-atom comp) (boolean enabled?))
  nil)
