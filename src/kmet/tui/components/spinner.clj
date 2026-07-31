(ns kmet.tui.components.spinner
  "Animated spinner component — shows a cycling frame animation with a text message.
   Renders nothing when inactive, one animated line when active.
   Equivalent to Pi's Loader component.
   Supports theme-aware color functions for spinner frame and message text."
  (:require [kmet.tui.protocols :as protocols]
            [kmet.tui.utils :as u]))

(def ^:private DEFAULT-FRAMES
  ["⠋" "⠙" "⠹" "⠸" "⠼" "⠴" "⠦" "⠧" "⠇" "⠏"])

(def ^:private CYN "\u001b[36m")
(def ^:private RST "\u001b[0m")

(defrecord Spinner [active-atom text-atom start-atom frames-atom interval-ms-atom
                    prefix-atom spinner-color-fn-atom message-color-fn-atom]
  protocols/IComponent
  (render [this width]
    (if-not @active-atom
      []  ;; invisible when inactive
      (let [elapsed (- (System/nanoTime) @start-atom)
            interval-ms @interval-ms-atom
            frame-idx (long (/ elapsed (* interval-ms 1000000)))
            frame (nth @frames-atom (mod frame-idx (count @frames-atom)))
            spinner-fn (or @spinner-color-fn-atom (fn [s] (str CYN s RST)))
            msg-fn (or @message-color-fn-atom identity)
            prefix @prefix-atom
            line (str prefix (spinner-fn frame) " " (msg-fn @text-atom))]
        [(u/truncate-to-width line width)])))
  (handle-input [_this _data] nil)
  (invalidate [_this] nil))

;; ─── Construction ──────────────────────────────────────────────────────────

(defn make-spinner
  "Create a new Spinner component (Pi's Loader equivalent).
   Options key-value pairs:
     :text            — message text (e.g. \"Working...\")
     :active          — whether to show the spinner initially
     :prefix          — string prepended before the spinner (default \"  \")
     :frames          — vector of frame strings (default braille spinner)
     :interval-ms     — animation interval in ms (default 100)
     :spinner-color-fn — (fn [frame-str] colored-frame-str) (default cyan)
     :message-color-fn — (fn [message-str] colored-message-str) (default identity)"
  [& {:keys [text active prefix frames interval-ms spinner-color-fn message-color-fn]
      :or {text "" active false prefix "  " frames DEFAULT-FRAMES interval-ms 100}}]
  (map->Spinner {:active-atom (atom active)
                 :text-atom (atom text)
                 :start-atom (atom (System/nanoTime))
                 :frames-atom (atom frames)
                 :interval-ms-atom (atom interval-ms)
                 :prefix-atom (atom prefix)
                 :spinner-color-fn-atom (atom spinner-color-fn)
                 :message-color-fn-atom (atom message-color-fn)}))

;; ─── Public API ────────────────────────────────────────────────────────────

(defn spinner-start!
  "Activate the spinner and reset its animation timer."
  [spinner]
  (reset! (:start-atom spinner) (System/nanoTime))
  (reset! (:active-atom spinner) true))

(defn spinner-set-start!
  "Reset the animation timer."
  [spinner]
  (reset! (:start-atom spinner) (System/nanoTime)))

(defn spinner-stop!
  "Deactivate the spinner."
  [spinner]
  (reset! (:active-atom spinner) false))

(defn spinner-active?
  "Returns true if the spinner is currently active."
  [spinner]
  @(:active-atom spinner))

(defn spinner-set-text!
  "Set the message text displayed next to the spinner."
  [spinner text]
  (reset! (:text-atom spinner) text))

(defn spinner-set-prefix!
  "Set the prefix before the spinner."
  [spinner prefix]
  (reset! (:prefix-atom spinner) prefix))

(defn spinner-set-spinner-color-fn!
  "Set the color function for the spinner frame (fn frame → colored-frame)."
  [spinner f]
  (reset! (:spinner-color-fn-atom spinner) f))

(defn spinner-set-message-color-fn!
  "Set the color function for the message text (fn msg → colored-msg)."
  [spinner f]
  (reset! (:message-color-fn-atom spinner) f))
