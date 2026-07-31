(ns kmet.app.ui.custom-message
  "CustomMessageComponent component — Pi's CustomMessageComponent.
   Wraps label and content Text children in a Box with custom-message-bg.
   Matching Pi architecture: Box handles padding/background/caching."
  (:require [kmet.tui.protocols :as protocols]
            [kmet.tui.utils :as u]
            [kmet.tui.theme :as theme]
            [kmet.tui.components.box :as box]
            [kmet.tui.components.text :as text]
            [kmet.tui.components.container :as container]
            [kmet.tui.components.spacer :as spacer]))

;; ─── Record ────────────────────────────────────────────────────────────────

(defrecord CustomMessageComponent [spacer          ;; Spacer(1) for top vertical gap (Pi-style)
                                   box             ;; Box wrapping the content
                                   inner-container  ;; Container holding label + content Text children
                                   label-atom
                                   content-atom
                                   theme-atom
                                   output-pad-atom
                                   expanded-atom   ;; current expanded state (collapsible messages)
                                   collapsed-content-atom  ;; content when collapsed (nil = not collapsible)
                                   expanded-content-atom]  ;; content when expanded (nil = not collapsible)
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

(defn- current-content
  "Pick the content to display: collapsed/expanded variant when collapsible,
   otherwise the plain content."
  [comp]
  (let [collapsed @(:collapsed-content-atom comp)
        expanded-content @(:expanded-content-atom comp)
        expanded @(:expanded-atom comp)]
    (cond
      (and (some? collapsed) (some? expanded-content)) (if expanded expanded-content collapsed)
      :else @(:content-atom comp))))

(defn- rebuild-content!
  "Rebuild the Text children inside the inner container with current content/theme."
  [comp]
  (let [theme @(:theme-atom comp)
        label @(:label-atom comp)
        content (current-content comp)
        container @(:inner-container comp)]
    (container/container-clear container)
    (when (seq label)
      (let [label-str (theme/fg theme :custom-message-label (theme/bold (str "[" label "]")))]
        (container/container-add-child container
          (text/make-text label-str 0 0))))
    (when (seq content)
      (let [colored (theme/fg theme :custom-message-text content)]
        (container/container-add-child container
          (text/make-text colored 0 0))))))

;; ─── Public API (defined before make- to avoid forward ref) ──────────────

(defn custom-message-set-label! [comp label]
  "Set the label text shown in brackets."
  (reset! (:label-atom comp) label)
  (rebuild-content! comp))

(defn custom-message-set-content! [comp content]
  (reset! (:content-atom comp) content)
  ;; Setting plain content clears any collapsible variants
  (reset! (:collapsed-content-atom comp) nil)
  (reset! (:expanded-content-atom comp) nil)
  (reset! (:expanded-atom comp) false)
  (rebuild-content! comp))

(defn custom-message-set-collapsible-content!
  "Set collapsed/expanded content variants (pi: ExpandableText).
   Pass nil content to clear collapsible mode and fall back to plain content."
  [comp collapsed expanded]
  (reset! (:collapsed-content-atom comp) collapsed)
  (reset! (:expanded-content-atom comp) expanded)
  (reset! (:expanded-atom comp) false)
  (rebuild-content! comp))

(defn custom-message-collapsible?
  "True if the message has collapsed/expanded content variants."
  [comp]
  (and (some? @(:collapsed-content-atom comp))
       (some? @(:expanded-content-atom comp))))

(defn custom-message-set-expanded!
  "Set the expanded state for a collapsible message."
  [comp expanded?]
  (reset! (:expanded-atom comp) expanded?)
  (rebuild-content! comp))

(defn custom-message-get-expanded
  "Get the current expanded state of a collapsible message."
  [comp]
  @(:expanded-atom comp))

(defn custom-message-set-theme! [comp theme]
  "Update the theme colors on the label, content, and box background."
  (reset! (:theme-atom comp) theme)
  (box/box-set-bg-fn @(:box comp) #(theme/bg theme :custom-message-bg %))
  (rebuild-content! comp))

(defn custom-message-set-output-pad! [comp n]
  "Set the horizontal output padding; rebuilds the box with the new padding."
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
  "Create a CustomMessageComponent.
   Options:
     :label       — bracketed label line (default nil)
     :content     — message text (default \"\")
     :theme       — theme map (default dark-theme)
     :output-pad  — horizontal padding (default 1)"
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
                                              :output-pad-atom (atom output-pad)
                                              :expanded-atom (atom false)
                                              :collapsed-content-atom (atom nil)
                                              :expanded-content-atom (atom nil)})]
      ;; Set initial content
      (rebuild-content! comp)
      (box/box-set-bg-fn b #(theme/bg theme :custom-message-bg %))
      comp)))
