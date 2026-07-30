(ns kmet.app.ui.status-indicator
  "StatusIndicatorComponent — standalone working indicator that sits between
   the chat history and the editor, Pi-style. Shows a spinner when the agent
   is active, renders nothing when idle.

   Pi equivalent: WorkingStatusIndicator / IdleStatus in interactive-mode's
   statusContainer — a dedicated layer between chat and editor."
  (:require [kmet.tui.protocols :as protocols]
            [kmet.tui.theme :as theme]
            [kmet.tui.components.spinner :as spinner]))

(def ^:private SPINNER-FRAMES
  ["⠋" "⠙" "⠹" "⠸" "⠼" "⠴" "⠦" "⠧" "⠇" "⠏"])

(defrecord StatusIndicator [spinner active-atom]
  protocols/IComponent
  (render [this width]
    (if @active-atom
      ;; Pi-style: one blank line above + one spinner line (with output-pad indentation)
      (let [lines (protocols/render (:spinner this) width)]
        (into [""] (mapv #(str " " %) lines)))
      []))
  (handle-input [_this _data] nil)
  (invalidate [this]
    (protocols/invalidate (:spinner this))))

;; ─── Construction ──────────────────────────────────────────────────────────

(defn make-status-indicator
  "Create a StatusIndicator.
   When active, shows a spinner with the given message.
   When inactive, renders nothing.
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
             :message-color-fn #(theme/fg theme :dim %))]
    (map->StatusIndicator {:spinner sp :active-atom (atom false)})))

;; ─── Public API ────────────────────────────────────────────────────────────

(defn status-indicator-start!
  "Activate the status indicator — shows the animated working spinner."
  [indicator]
  (spinner/spinner-start! (:spinner indicator))
  (reset! (:active-atom indicator) true)
  (protocols/invalidate indicator))

(defn status-indicator-stop!
  "Deactivate the status indicator — hides the spinner."
  [indicator]
  (spinner/spinner-stop! (:spinner indicator))
  (reset! (:active-atom indicator) false)
  (protocols/invalidate indicator))

(defn status-indicator-set-text!
  "Set the message text displayed next to the spinner."
  [indicator text]
  (spinner/spinner-set-text! (:spinner indicator) text)
  (protocols/invalidate indicator))

(defn status-indicator-set-theme!
  "Update the theme colors on the underlying spinner."
  [indicator theme]
  (spinner/spinner-set-spinner-color-fn! (:spinner indicator)
    #(theme/fg theme :accent %))
  (spinner/spinner-set-message-color-fn! (:spinner indicator)
    #(theme/fg theme :dim %))
  (protocols/invalidate indicator))
