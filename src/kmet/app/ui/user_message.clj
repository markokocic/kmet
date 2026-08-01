(ns kmet.app.ui.user-message
  "UserMessageComponent component — Pi's UserMessageComponent.
   Wraps a Text child in a Box with user-message-bg background
   and user-message-text foreground color. Matches Pi's architecture:
   Box handles padding/background/caching, Text handles word-wrap."
  (:require [kmet.tui.protocols :as protocols]
            [kmet.tui.theme :as theme]
            [kmet.tui.components.box :as box]
            [kmet.tui.components.text :as text]
            [kmet.tui.macros :refer [defsetter defcomponent]]))

;; ─── Record ────────────────────────────────────────────────────────────────

(defcomponent UserMessageComponent :user
  [box           ;; Box wrapping the text
   text-comp     ;; Text child component
   text-atom     ;; raw text (uncolored, for backward compat)
   theme-atom
   output-pad-atom]
  (render [this width]
    (protocols/render @box width))
  (invalidate [this]
    (protocols/invalidate @box)))

;; ─── Public API (defined before make-user-message to avoid forward ref) ───

(defsetter user-message-set-text! :text-atom comp text
  (let [theme @(:theme-atom comp)]
    (text/text-set! @(:text-comp comp) (theme/fg theme :user-message-text text))))

(defsetter user-message-set-theme! :theme-atom comp theme
  ;; Update bg-fn on existing box
  (box/box-set-bg-fn @(:box comp) #(theme/bg theme :user-message-bg %))
  ;; Re-color existing text
  (let [raw @(:text-atom comp)]
    (text/text-set! @(:text-comp comp) (theme/fg theme :user-message-text raw))))

(defsetter user-message-set-output-pad! :output-pad-atom comp n
  ;; Rebuild box with new padding
  (let [theme @(:theme-atom comp)
        raw @(:text-atom comp)
        t (text/make-text (theme/fg theme :user-message-text raw) 0 0)
        b (box/make-box n 1 #(theme/bg theme :user-message-bg %))]
    (box/box-add-child b t)
    (reset! (:box comp) b)
    (reset! (:text-comp comp) t)))

;; ─── Construction ──────────────────────────────────────────────────────────

(defn make-user-message
  [& {:keys [text theme output-pad]
      :or {text "" theme theme/dark-theme output-pad 1}}]
  (let [raw-text-atom (atom text)
        t (text/make-text "" 0 0)
        b (box/make-box output-pad 1 nil)]
    ;; Add text as child of box
    (box/box-add-child b t)
    (let [comp (map->UserMessageComponent {:box (atom b)
                                           :text-comp (atom t)
                                           :text-atom raw-text-atom
                                           :theme-atom (atom theme)
                                           :output-pad-atom (atom output-pad)})]
      ;; Set initial text and theme
      (user-message-set-text! comp text)
      (user-message-set-theme! comp theme)
      comp)))
