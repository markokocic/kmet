(ns kmet.app.ui.footer
  "FooterComponent — Pi's FooterComponent.
   The status line is built with an HStack: fixed-width left text (app name +
   status), a growing flex spacer, and a fixed-width right text (message
   count) — the flex allocation right-aligns the count (pi: h-stack.ts)."
  (:require [clojure.string :as str]
            [kmet.tui.protocols :as protocols]
            [kmet.tui.utils :as u]
            [kmet.tui.theme :as theme]
            [kmet.tui.components.text :as text]
            [kmet.tui.components.h-stack :as h-stack]
            [kmet.tui.macros :refer [track! defsetter defcomponent]]))

(defcomponent FooterComponent nil [status-text-atom n-msgs-atom theme-atom cache-atom
                                   extension-statuses-atom]
  (render [this width]
    (track! this width
      (let [th @theme-atom
            status-text @status-text-atom
            n-msgs @n-msgs-atom
            ext-statuses (vals @extension-statuses-atom)
            ext-line (when (seq ext-statuses)
                       (str (theme/dim (str/join "  " (remove nil? ext-statuses))) " "))
            sep (theme/dim (apply str (repeat width "─")))
            left (str (theme/fg th :accent "kmet") "  "
                      (when (seq status-text) (str status-text " "))
                      (or ext-line ""))
            right (theme/dim (str "msgs:" n-msgs))
            left-w (u/visible-width left)
            right-w (u/visible-width right)
            ;; Explicit basis (like pi's HStack tests): kmet Text pads to its
            ;; render width, so intrinsic measurement can't infer natural
            ;; widths — left/right keep their content width, the middle
            ;; flex spacer absorbs the remaining space to right-align right.
            row (h-stack/make-h-stack
                 [{:component (text/make-text left 0 0) :basis left-w :shrink 0}
                  {:component (text/make-text "" 0 0) :grow 1 :min-size 1}
                  {:component (text/make-text right 0 0) :basis right-w :shrink 0}])
            status-line (or (first (protocols/render row width)) "")]
        [(u/truncate-to-width sep width)
         (u/truncate-to-width status-line width)])))
  (invalidate [this]
    (reset! (:cache-atom this) nil)))

;; ─── Construction ──────────────────────────────────────────────────────────

(defn make-footer
  [& {:keys [status n-msgs theme]
      :or {status "" n-msgs 0 theme theme/dark-theme}}]
  (map->FooterComponent {:status-text-atom (atom status)
                         :n-msgs-atom (atom n-msgs)
                         :theme-atom (atom theme)
                         :cache-atom (atom nil)
                         :extension-statuses-atom (atom {})}))

;; ─── Public API ────────────────────────────────────────────────────────────

(defsetter footer-set-status! :status-text-atom comp text)
(defsetter footer-set-n-msgs! :n-msgs-atom comp n)

(defn footer-set-extension-status!
  "Set/clear a keyed extension status shown in the footer (pi:
   FooterDataProvider.setExtensionStatus — multiple extensions can set
   statuses, all rendered dim in the footer line). Pass nil/undefined
   text to clear the key."
  [comp key text]
  (swap! (:extension-statuses-atom comp)
         (fn [m] (if (nil? text) (dissoc m key) (assoc m key text))))
  nil)
