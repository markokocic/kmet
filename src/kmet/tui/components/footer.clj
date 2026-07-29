(ns kmet.tui.components.footer
  "Footer component — Pi's FooterComponent.
   Renders a two-line footer with app name, status, and message count."
  (:require [kmet.tui.protocols :as protocols]
            [kmet.tui.utils :as u]))

(def ^:private DIM "\u001b[2m")
(def ^:private RST "\u001b[0m")
(def ^:private CYN "\u001b[36m")

(defrecord Footer [status-text-atom   ;; string: left status (e.g. "idle", "● thinking")
                   n-msgs-atom        ;; int
                   cache-atom]
  protocols/IComponent

  (render [this width]
    (let [status @status-text-atom
          n-msgs @n-msgs-atom
          cached @cache-atom]
      (if (and cached (= (:width cached) width)
               (= (:status cached) status)
               (= (:n-msgs cached) n-msgs))
        (:lines cached)
        (let [;; Separator line
              sep (str DIM (apply str (repeat width "─")) RST)
              ;; Status line: "kmet" + status on left, "msgs:N" on right
              left (str CYN "kmet" RST "  " (when (seq status) (str status " ")))
              right (str DIM "msgs:" n-msgs RST)
              left-w (u/visible-width left)
              right-w (u/visible-width right)
              pad (max 1 (- width left-w right-w))
              status-line (str left (apply str (repeat pad \space)) right)
              result [sep (u/truncate-to-width status-line width)]]
          (reset! cache-atom {:width width :status status :n-msgs n-msgs :lines result})
          result))))

  (handle-input [_this _data] nil)

  (invalidate [this]
    (reset! (:cache-atom this) nil)))

;; ─── Construction ──────────────────────────────────────────────────────────

(defn make-footer
  "Create a Footer component.
   Options:
     :status — initial status text (default \"\")
     :n-msgs  — initial message count (default 0)"
  [& {:keys [status n-msgs] :or {status "" n-msgs 0}}]
  (map->Footer {:status-text-atom (atom status)
                :n-msgs-atom (atom n-msgs)
                :cache-atom (atom nil)}))

;; ─── Public API ────────────────────────────────────────────────────────────

(defn footer-set-status!
  "Set the left-side status text."
  [comp text]
  (reset! (:status-text-atom comp) text)
  (protocols/invalidate comp))

(defn footer-set-n-msgs!
  "Set the message count."
  [comp n]
  (reset! (:n-msgs-atom comp) n)
  (protocols/invalidate comp))
