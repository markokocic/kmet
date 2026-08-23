(ns kmet.app.ui.user-message
  "UserMessageComponent component — Pi's UserMessageComponent.
   Wraps a Markdown child in a Box with user-message-bg background; text is
   tinted user-message-text via the markdown component's :default-style (pi:
   Markdown with defaultTextStyle {color: userMessageText}). Box handles
   padding/background/caching, Markdown handles parsing and word-wrap.
   Extension markdown transformers apply to the text per render (pi:
   createMarkdownTransform(\"user\", false))."
  (:require [kmet.tui.protocols :as protocols]
            [kmet.tui.theme :as theme]
            [kmet.tui.components.box :as box]
            [kmet.tui.components.markdown :as md]
            [kmet.tui.macros :refer [track! track-deps defsetter defcomponent]]
            [kmet.app.extensions :as extensions]))

;; ─── Record ────────────────────────────────────────────────────────────────

(defcomponent UserMessageComponent :user
              [box             ;; Box wrapping the markdown
               markdown-comp   ;; Markdown child component
               text-atom       ;; raw text (uncolored, for backward compat)
               theme-atom
               output-pad-atom
               cache-atom]
  (render [this width]
    (track! this width
      (let [b @box]
        (track-deps @text-atom @theme-atom @output-pad-atom)
        (protocols/render b width))))
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

(defn- make-user-transform
  "Extension markdown transform for user messages (pi:
   createMarkdownTransform(\"user\", false)): transformers apply in
   registration order at render time (each text/width change re-runs them),
   throwing transformers are skipped; the transformer list is read at apply
   time so late registrations take effect on the next render."
  []
  (fn [text {:keys [available-width]}]
    (extensions/apply-markdown-transformers
     text {:message-type :user
           :is-streaming false
           :available-width available-width})))

(defn make-user-message
  [& {:keys [text theme output-pad]
      :or {text "" theme theme/dark-theme output-pad 1}}]
  (let [m (md/make-markdown ""
                            :theme (theme/get-markdown-theme theme)
                            :default-style (fn [s]
                                             (theme/fg theme :user-message-text s))
                            :transform (make-user-transform)
                            :padding-x 0)
        b (box/make-box output-pad 1 nil)
        comp (map->UserMessageComponent {:kind :user
                                         :box (atom b)
                                         :markdown-comp (atom m)
                                         :text-atom (atom text)
                                         :theme-atom (atom theme)
                                         :output-pad-atom (atom output-pad)
                                         :cache-atom (atom nil)})]
    (box/box-add-child b m)
    ;; Set initial text and theme (bg-fn)
    (user-message-set-text! comp text)
    (user-message-set-theme! comp theme)
    comp))
