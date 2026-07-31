(ns kmet.app.ui.footer
  "FooterComponent — Pi's FooterComponent."
  (:require [kmet.tui.protocols :as protocols]
            [kmet.tui.utils :as u]
            [kmet.tui.theme :as theme]
            [kmet.tui.macros :refer [track!]]))

(defrecord FooterComponent [status-text-atom n-msgs-atom theme-atom cache-atom]
  protocols/IComponent
  (render [this width]
    (track! this width
      (let [th @theme-atom
            status-text @status-text-atom
            n-msgs @n-msgs-atom
            sep (theme/dim (apply str (repeat width "─")))
            left (str (theme/fg th :accent "kmet") "  " (when (seq status-text) (str status-text " ")))
            right (theme/dim (str "msgs:" n-msgs))
            left-w (u/visible-width left)
            right-w (u/visible-width right)
            pad (max 1 (- width left-w right-w))
            status-line (str left (apply str (repeat pad \space)) right)]
        [(u/truncate-to-width sep width)
         (u/truncate-to-width status-line width)])))
  (handle-input [_this _data] nil)
  (invalidate [this]
    (reset! (:cache-atom this) nil)))

;; ─── Construction ──────────────────────────────────────────────────────────

(defn make-footer
  [& {:keys [status n-msgs theme]
      :or {status "" n-msgs 0 theme theme/dark-theme}}]
  (map->FooterComponent {:status-text-atom (atom status)
                :n-msgs-atom (atom n-msgs)
                :theme-atom (atom theme)
                :cache-atom (atom nil)}))

;; ─── Public API ────────────────────────────────────────────────────────────

(defn footer-set-status! [comp text]
  (reset! (:status-text-atom comp) text))
(defn footer-set-n-msgs! [comp n]
  (reset! (:n-msgs-atom comp) n))
