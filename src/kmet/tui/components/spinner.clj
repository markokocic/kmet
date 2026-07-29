(ns kmet.tui.components.spinner
  "Animated spinner component — shows a cycling frame animation with a text message.
   Renders nothing when inactive, one animated line when active.
   Designed for Working/Thinking/Executing indicators."
  (:require [kmet.tui.protocols :as protocols]
            [kmet.tui.utils :as u]))

(def ^:private DEFAULT-FRAMES
  ["⠋" "⠙" "⠹" "⠸" "⠼" "⠴" "⠦" "⠧" "⠇" "⠏"])

(def ^:private CYN "\u001b[36m")
(def ^:private RST "\u001b[0m")

(defrecord Spinner [active-atom text-atom start-atom frames-atom interval-ms-atom prefix-atom cache-atom]
  protocols/IComponent
  (render [this width]
    (if-not @active-atom
      []  ;; invisible when inactive
      (let [elapsed (- (System/nanoTime) @start-atom)
            interval-ms @interval-ms-atom
            frame-idx (long (/ elapsed (* interval-ms 1000000)))
            frame (nth @frames-atom (mod frame-idx (count @frames-atom)))
            prefix @prefix-atom
            line (str prefix CYN frame RST " " @text-atom)]
        [(u/truncate-to-width line width)])))
  (handle-input [_this _data] nil)
  (invalidate [this]
    (reset! (:cache-atom this) nil)))

;; ─── Construction ──────────────────────────────────────────────────────────

(defn make-spinner
  "Create a new Spinner component.
   Options key-value pairs:
     :text       — message text (e.g. \"Working...\")
     :active     — whether to show the spinner initially
     :prefix     — string prepended before the spinner (default \"  \")
     :frames     — vector of frame strings (default braille spinner)
     :interval-ms — animation interval in ms (default 100)"
  [& {:keys [text active prefix frames interval-ms]
      :or {text "" active false prefix "  " frames DEFAULT-FRAMES interval-ms 100}}]
  (map->Spinner {:active-atom (atom active)
                 :text-atom (atom text)
                 :start-atom (atom (System/nanoTime))
                 :frames-atom (atom frames)
                 :interval-ms-atom (atom interval-ms)
                 :prefix-atom (atom prefix)
                 :cache-atom (atom nil)}))

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
