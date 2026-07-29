(ns kmet.agent.ui.footer
  "FooterComponent — Pi's FooterComponent."
  (:require [kmet.tui.protocols :as protocols]
            [kmet.tui.utils :as u]
            [kmet.tui.macros :refer [with-cache]]))

(def ^:private DIM "\u001b[2m")
(def ^:private RST "\u001b[0m")
(def ^:private CYN "\u001b[36m")

(defrecord FooterComponent [status-text-atom n-msgs-atom cache-atom]
  protocols/IComponent
  (render [this width]
    (let [status-text @status-text-atom
          n-msgs @n-msgs-atom]
      (with-cache this width {:status-text status-text :n-msgs n-msgs}
        (fn []
          (let [sep (str DIM (apply str (repeat width "─")) RST)
                left (str CYN "kmet" RST "  " (when (seq status-text) (str status-text " ")))
                right (str DIM "msgs:" n-msgs RST)
                left-w (u/visible-width left)
                right-w (u/visible-width right)
                pad (max 1 (- width left-w right-w))
                status-line (str left (apply str (repeat pad \space)) right)]
            [(u/truncate-to-width sep width)
             (u/truncate-to-width status-line width)])))))
  (handle-input [_this _data] nil)
  (invalidate [this]
    (reset! (:cache-atom this) nil)))

;; ─── Construction ──────────────────────────────────────────────────────────

(defn make-footer
  [& {:keys [status n-msgs] :or {status "" n-msgs 0}}]
  (map->FooterComponent {:status-text-atom (atom status)
                :n-msgs-atom (atom n-msgs)
                :cache-atom (atom nil)}))

;; ─── Public API ────────────────────────────────────────────────────────────

(defn footer-set-status! [comp text]
  (reset! (:status-text-atom comp) text) (protocols/invalidate comp))
(defn footer-set-n-msgs! [comp n]
  (reset! (:n-msgs-atom comp) n) (protocols/invalidate comp))
