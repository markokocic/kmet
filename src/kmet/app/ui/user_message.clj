(ns kmet.app.ui.user-message
  "UserMessageComponent component — Pi's UserMessageComponent.
   Wraps a Markdown child in a Box with user-message-bg background; text is
   tinted user-message-text via the markdown component's :default-style (pi:
   Markdown with defaultTextStyle {color: userMessageText}). Box handles
   padding/background/caching, Markdown handles parsing and word-wrap."
  (:require [kmet.tui.protocols :as protocols]
            [kmet.tui.theme :as theme]
            [kmet.tui.components.box :as box]
            [kmet.tui.components.markdown :as md]
            [kmet.tui.macros :refer [defsetter defcomponent]]))

;; ─── Record ────────────────────────────────────────────────────────────────

(defcomponent UserMessageComponent :user
              [box             ;; Box wrapping the markdown
               markdown-comp   ;; Markdown child component
               text-atom       ;; raw text (uncolored, for backward compat)
               theme-atom
               output-pad-atom]
  (render [_this width]
    (protocols/render @box width))
  (invalidate [_this]
    (protocols/invalidate @box)))

;; ─── Public API (defined before make-user-message to avoid forward ref) ───

(defsetter user-message-set-text! :text-atom comp text
  (md/markdown-set-text! @(:markdown-comp comp) text))

(defsetter user-message-set-theme! :theme-atom comp theme
  ;; Update bg-fn on existing box and re-tint the markdown child
  (box/box-set-bg-fn @(:box comp) #(theme/bg theme :user-message-bg %))
  (let [m @(:markdown-comp comp)]
    (md/markdown-set-theme! m (theme/get-markdown-theme theme))
    (md/markdown-set-default-style! m
                                    (fn [s] (theme/fg theme :user-message-text s)))))

(defsetter user-message-set-output-pad! :output-pad-atom comp n
  ;; Rebuild box with new padding, reusing the same markdown child
  (let [theme @(:theme-atom comp)
        m @(:markdown-comp comp)
        b (box/make-box n 1 #(theme/bg theme :user-message-bg %))]
    (box/box-add-child b m)
    (reset! (:box comp) b)))

;; ─── Construction ──────────────────────────────────────────────────────────

(defn make-user-message
  [& {:keys [text theme output-pad]
      :or {text "" theme theme/dark-theme output-pad 1}}]
  (let [m (md/make-markdown ""
                            :theme (theme/get-markdown-theme theme)
                            :default-style (fn [s]
                                             (theme/fg theme :user-message-text s))
                            :padding-x 0)
        b (box/make-box output-pad 1 nil)
        comp (map->UserMessageComponent {:box (atom b)
                                         :markdown-comp (atom m)
                                         :text-atom (atom text)
                                         :theme-atom (atom theme)
                                         :output-pad-atom (atom output-pad)})]
    (box/box-add-child b m)
    ;; Set initial text and theme (bg-fn)
    (user-message-set-text! comp text)
    (user-message-set-theme! comp theme)
    comp))
