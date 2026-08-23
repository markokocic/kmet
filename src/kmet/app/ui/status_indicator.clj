(ns kmet.app.ui.status-indicator
  "Status indicators — the layer between the chat history and the editor
   (pi: status-indicator.ts + interactive-mode statusContainer).
   The default working indicator (StatusIndicator) shows a spinner while the
   agent is active and renders the idle two rows otherwise; Retry and
   Compaction indicators are transient swaps shown via show-status-indicator!
   / clear-status-indicator! (pi: showStatusIndicator/clearStatusIndicator).
   All indicators render the same two-row shape (leading blank + content) so
   the editor and footer below never jump."
  (:require [kmet.tui.protocols :as protocols]
            [kmet.tui.reagent :as r]
            [kmet.tui.theme :as theme]
            [kmet.tui.utils :as u]
            [kmet.tui.components.spinner :as spinner]
            [kmet.tui.macros :refer [defcomponent]]))

(def ^:private SPINNER-FRAMES
  ["⠋" "⠙" "⠹" "⠸" "⠼" "⠴" "⠦" "⠧" "⠇" "⠏"])

(defn- frame-at
  "Braille frame for the given elapsed millis — self-animating on re-render
   (no timer needed; the interactive mode's anim timer drives renders)."
  [elapsed]
  (nth SPINNER-FRAMES (mod (quot (max 0 elapsed) 100) (count SPINNER-FRAMES))))

(defcomponent StatusIndicator nil [spinner active-atom]
  (render [this width]
    (if @active-atom
      ;; The Spinner renders the pi Loader shape itself (leading blank +
      ;; animated line); indent only the content line for chat alignment.
      (let [lines (protocols/render (:spinner this) width)]
        (into [(first lines)] (mapv #(str " " %) (rest lines))))
      ;; Pi: IdleStatus — always occupy the same two rows so the editor and
      ;; footer below don't jump when the indicator appears/disappears.
      ["" ""]))
  (invalidate [this]
    (protocols/invalidate (:spinner this))))

;; ─── Construction ──────────────────────────────────────────────────────────

(defn make-status-indicator
  "Create the default working StatusIndicator.
   When active, shows a spinner with the given message.
   When inactive, renders nothing (the idle two rows).
   Options key-value pairs:
     :text  — message text (default \"Working...\")
     :theme — theme map (default dark-theme)"
  [& {:keys [text theme]
      :or {text "Working..." theme theme/dark-theme}}]
  (let [sp (spinner/make-spinner
            :text text
            :active false
            :prefix ""
            :frames SPINNER-FRAMES
            :interval-ms 100
            :spinner-color-fn #(theme/fg theme :accent %)
            :message-color-fn #(theme/fg theme :muted %))]
    (map->StatusIndicator {:spinner sp :active-atom (atom false)})))

;; ─── Public API ────────────────────────────────────────────────────────────

(defn status-indicator-start!
  "Activate the status indicator — shows the animated working spinner."
  [indicator]
  (spinner/spinner-start! (:spinner indicator))
  (reset! (:active-atom indicator) true))

(defn status-indicator-stop!
  "Deactivate the status indicator — hides the spinner (idle two rows)."
  [indicator]
  (spinner/spinner-stop! (:spinner indicator))
  (reset! (:active-atom indicator) false))

(defn status-indicator-set-text!
  "Set the message text displayed next to the spinner."
  [indicator text]
  (spinner/spinner-set-text! (:spinner indicator) text))

(defn status-indicator-set-theme!
  "Update the theme colors on the underlying spinner."
  [indicator th]
  (spinner/spinner-set-spinner-color-fn! (:spinner indicator)
                                         #(theme/fg th :accent %))
  (spinner/spinner-set-message-color-fn! (:spinner indicator)
                                         #(theme/fg th :muted %)))

(defn make-status-area
  "The status layer as a fn component (dsl.md stage 4, pi: statusContainer):
   renders the transient indicator recorded in CURRENT-ATOM ({:kind k
   :indicator c} or nil), else the default WORKING-INDICATOR record.
   The current atom is read through a tracked deref inside the mounted
   ComponentFn's reaction, so a swap re-derives the tree exactly once and
   an idle UI re-derives nothing; indicator records are spliced foreign —
   reconcile swaps identity on change and never disposes them (their
   lifecycle stays with the swapper, dsl.md §5)."
  [current-atom working-indicator]
  (fn [_props]
    (if-let [{:keys [indicator]} (r/tracked-deref current-atom)]
      indicator
      working-indicator)))

;; ─── Transient indicators (pi: Retry/CompactionStatusIndicator) ───────────
;; Self-animating from elapsed time; colors read from the current theme at
;; render time (the interactive mode re-renders continuously while active).

(defcomponent RetryStatusIndicator nil [start-atom attempt-atom max-attempts-atom
                                        delay-ms-atom cancel-hint-atom cache-atom]
  (render [_this width]
    (let [elapsed (- (System/currentTimeMillis) @start-atom)
          remaining (max 0 (long (Math/ceil (/ (- @delay-ms-atom elapsed) 1000.0))))
          th (theme/get-current-theme)
          frame (frame-at elapsed)
          message (str "Retrying (" @attempt-atom "/" @max-attempts-atom ") in "
                       remaining "s... (" @cancel-hint-atom " to cancel)")
          line (str (theme/fg th :warning frame) " " (theme/fg th :muted message))]
      ["" (u/truncate-to-width line width)]))
  (invalidate [this] (reset! (:cache-atom this) nil)))

(defn make-retry-status-indicator
  "Retry countdown indicator (pi: RetryStatusIndicator + CountdownTimer —
   the countdown is computed from elapsed time on each render instead of a
   timer). cancel-hint — key display text (e.g. \"Escape\")."
  [attempt max-attempts delay-ms & {:keys [cancel-hint]
                                    :or {cancel-hint "Escape"}}]
  (map->RetryStatusIndicator {:start-atom (atom (System/currentTimeMillis))
                              :attempt-atom (atom attempt)
                              :max-attempts-atom (atom max-attempts)
                              :delay-ms-atom (atom delay-ms)
                              :cancel-hint-atom (atom cancel-hint)
                              :cache-atom (atom nil)}))

(defcomponent CompactionStatusIndicator nil [start-atom message-atom cache-atom]
  (render [_this width]
    (let [elapsed (- (System/currentTimeMillis) @start-atom)
          th (theme/get-current-theme)
          frame (frame-at elapsed)
          line (str (theme/fg th :accent frame) " " (theme/fg th :muted @message-atom))]
      ["" (u/truncate-to-width line width)]))
  (invalidate [this] (reset! (:cache-atom this) nil)))

(defn make-compaction-status-indicator
  "Compaction progress indicator (pi: CompactionStatusIndicator). kmet's
   compaction is not cancellable, so no cancel hint is shown."
  [& {:keys [message] :or {message "Compacting context..."}}]
  (map->CompactionStatusIndicator {:start-atom (atom (System/currentTimeMillis))
                                   :message-atom (atom message)
                                   :cache-atom (atom nil)}))

(defcomponent BranchSummaryStatusIndicator nil [start-atom message-atom
                                                cancel-hint-atom cache-atom]
  (render [_this width]
    (let [elapsed (- (System/currentTimeMillis) @start-atom)
          th (theme/get-current-theme)
          frame (frame-at elapsed)
          message (str @message-atom " (" @cancel-hint-atom " to cancel)")
          line (str (theme/fg th :accent frame) " " (theme/fg th :muted message))]
      ["" (u/truncate-to-width line width)]))
  (invalidate [this] (reset! (:cache-atom this) nil)))

(defn make-branch-summary-status-indicator
  "Branch summarization progress indicator (pi: BranchSummaryStatusIndicator)
   with a cancel hint — escape aborts the summarization via the editor's
   interrupt action."
  [& {:keys [message cancel-hint]
      :or {message "Summarizing branch..." cancel-hint "Escape"}}]
  (map->BranchSummaryStatusIndicator {:start-atom (atom (System/currentTimeMillis))
                                      :message-atom (atom message)
                                      :cancel-hint-atom (atom cancel-hint)
                                      :cache-atom (atom nil)}))
