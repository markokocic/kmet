(ns kmet.agent.ui.custom-message
  "CustomMessageComponent component — Pi's CustomMessageComponent.
   Wraps label and content Text children in a Box with custom-message-bg.
   Matching Pi architecture: Box handles padding/background/caching."
  (:require [kmet.tui.protocols :as protocols]
            [kmet.tui.utils :as u]
            [kmet.tui.theme :as theme]
            [kmet.tui.components.box :as box]
            [kmet.tui.components.text :as text]
            [kmet.tui.components.container :as container]
            [kmet.tui.components.spacer :as spacer]
            [kmet.tui.macros :refer [with-cache]]))

;; ─── Record ────────────────────────────────────────────────────────────────

(defrecord CustomMessageComponent [spacer          ;; Spacer(1) for top vertical gap (Pi-style)
                                   box             ;; Box wrapping the content
                                   inner-container  ;; Container holding label + content Text children
                                   label-atom
                                   content-atom
                                   theme-atom
                                   output-pad-atom]
  protocols/IComponent
  (render [this width]
    (let [spacer-lines (protocols/render @spacer width)
          box-lines (protocols/render @box width)]
      (into [] (concat spacer-lines box-lines))))
  (handle-input [_this _data] nil)
  (invalidate [this]
    (protocols/invalidate @spacer)
    (protocols/invalidate @box)))

;; ─── IComponentKind ─────────────────────────────────────────────────────────

(extend-type CustomMessageComponent
  protocols/IComponentKind
  (component-kind [_] :custom))

;; ─── Internal: rebuild the content children ────────────────────────────────

(defn- rebuild-content!
  "Rebuild the Text children inside the inner container with current content/theme."
  [comp]
  (let [theme @(:theme-atom comp)
        label @(:label-atom comp)
        content @(:content-atom comp)
        container @(:inner-container comp)]
    (container/container-clear container)
    (when (seq label)
      (let [label-str (theme/fg theme :custom-message-label (theme/bold (str "[" label "]")))]
        (container/container-add-child container
          (text/make-text label-str 0 0))))
    (when (seq content)
      (let [colored (theme/fg theme :custom-message-text content)]
        (container/container-add-child container
          (text/make-text colored 0 0))))
    (protocols/invalidate @(:box comp))))

;; ─── Public API (defined before make- to avoid forward ref) ──────────────

(defn custom-message-set-label! [comp label]
  (reset! (:label-atom comp) label)
  (rebuild-content! comp))

(defn custom-message-set-content! [comp content]
  (reset! (:content-atom comp) content)
  (rebuild-content! comp))

(defn custom-message-set-theme! [comp theme]
  (reset! (:theme-atom comp) theme)
  (box/box-set-bg-fn @(:box comp) #(theme/bg theme :custom-message-bg %))
  (rebuild-content! comp))

(defn custom-message-set-output-pad! [comp n]
  (reset! (:output-pad-atom comp) n)
  ;; Rebuild box with new padding, keep spacer
  (let [theme @(:theme-atom comp)
        inner-container (container/make-container)
        b (box/make-box n 1 #(theme/bg theme :custom-message-bg %))]
    (box/box-add-child b inner-container)
    (reset! (:box comp) b)
    (reset! (:inner-container comp) inner-container)
    (rebuild-content! comp)))

;; ─── Construction ──────────────────────────────────────────────────────────

(defn make-custom-message
  [& {:keys [label content theme output-pad]
      :or {content "" theme theme/dark-theme output-pad 1}}]
  (let [inner-container (container/make-container)
        s (spacer/make-spacer 1)
        b (box/make-box output-pad 1 nil)]
    (box/box-add-child b inner-container)
    (let [comp (map->CustomMessageComponent {:spacer (atom s)
                                              :box (atom b)
                                              :inner-container (atom inner-container)
                                              :label-atom (atom label)
                                              :content-atom (atom content)
                                              :theme-atom (atom theme)
                                              :output-pad-atom (atom output-pad)})]
      ;; Set initial content
      (rebuild-content! comp)
      (box/box-set-bg-fn b #(theme/bg theme :custom-message-bg %))
      comp)))
