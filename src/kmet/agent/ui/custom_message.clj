(ns kmet.agent.ui.custom-message
  "CustomMessage component — Pi's CustomMessageComponent."
  (:require [kmet.tui.protocols :as protocols]
            [kmet.tui.utils :as u]
            [kmet.tui.theme :as theme]
            [kmet.tui.macros :refer [with-cache]]))

(def ^:private BLD "\u001b[1m")
(def ^:private RST "\u001b[0m")

(defrecord CustomMessage [label-atom content-atom theme-atom output-pad-atom cache-atom]
  protocols/IComponent
  (render [this width]
    (let [label @label-atom
          content @content-atom
          theme @theme-atom
          output-pad @output-pad-atom]
      (with-cache this width {:label label :content content :theme theme :output-pad output-pad}
        (fn []
          (let [pad-x output-pad pad-y 1
                cw (max 1 (- width (* 2 pad-x)))
                left-pad (apply str (repeat pad-x \space))
                bg (fn [line] (theme/bg theme :custom-message-bg
                                (str line (apply str (repeat (max 0 (- width (u/visible-width line))) \space)))))
                empty (apply str (repeat width \space))
                label-lines (when (seq label)
                              (let [label-str (str BLD (theme/fg theme :custom-message-label (str "[" label "]")) RST)
                                    wrapped (u/wrap-text-with-ansi label-str cw)]
                                (mapv #(str left-pad %) wrapped)))
                content-lines (when (seq content)
                                (let [wrapped (u/wrap-text-with-ansi content cw)
                                      colored (mapv #(theme/fg theme :custom-message-text %) wrapped)]
                                  (mapv #(str left-pad %) colored)))
                top-pad (repeat pad-y (bg empty))
                bottom-pad (repeat pad-y (bg empty))]
            (vec (concat top-pad
                         (when label-lines (map bg label-lines))
                         (when content-lines (map bg content-lines))
                         bottom-pad)))))))
  (handle-input [_this _data] nil)
  (invalidate [this]
    (reset! (:cache-atom this) nil)))

;; ─── IComponentKind ─────────────────────────────────────────────────────────

(extend-type CustomMessage
  protocols/IComponentKind
  (component-kind [_] :custom))

;; ─── Construction ──────────────────────────────────────────────────────────

(defn make-custom-message
  [& {:keys [label content theme output-pad]
      :or {content "" theme theme/dark-theme output-pad 1}}]
  (map->CustomMessage {:label-atom (atom label)
                       :content-atom (atom content)
                       :theme-atom (atom theme)
                       :output-pad-atom (atom output-pad)
                       :cache-atom (atom nil)}))

;; ─── Public API ────────────────────────────────────────────────────────────

(defn custom-message-set-label! [comp label]
  (reset! (:label-atom comp) label) (protocols/invalidate comp))
(defn custom-message-set-content! [comp content]
  (reset! (:content-atom comp) content) (protocols/invalidate comp))
(defn custom-message-set-theme! [comp theme]
  (reset! (:theme-atom comp) theme) (protocols/invalidate comp))
(defn custom-message-set-output-pad! [comp n]
  (reset! (:output-pad-atom comp) n) (protocols/invalidate comp))
