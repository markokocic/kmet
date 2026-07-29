(ns kmet.agent.ui.tool-execution
  "ToolExecutionComponent component — Pi's ToolExecutionComponent."
  (:require [kmet.tui.protocols :as protocols]
            [kmet.tui.utils :as u]
            [kmet.tui.theme :as theme]
            [kmet.tui.macros :refer [with-cache]]))

(def ^:private BLD "\u001b[1m")
(def ^:private RST "\u001b[0m")

(defrecord ToolExecutionComponent [name-atom content-atom is-error-atom theme-atom
                          output-pad-atom expanded-atom cache-atom]
  protocols/IComponent
  (render [this width]
    (let [name @name-atom
          content @content-atom
          is-error @is-error-atom
          theme @theme-atom
          output-pad @output-pad-atom]
      (with-cache this width
        {:name name :content content :is-error is-error
         :theme theme :output-pad output-pad}
        (fn []
          (let [pad-x output-pad pad-y 1
                cw (max 1 (- width (* 2 pad-x)))
                left-pad (apply str (repeat pad-x \space))
                bg-key (if is-error :tool-error-bg :tool-success-bg)
                bg (fn [line] (theme/bg theme bg-key
                                (str line (apply str (repeat (max 0 (- width (u/visible-width line))) \space)))))
                empty (apply str (repeat width \space))
                name-str (str BLD (theme/fg theme :tool-title name) RST)
                name-wrapped (u/wrap-text-with-ansi name-str cw)
                name-indented (mapv #(str left-pad %) name-wrapped)
                content-indented (when (seq content)
                                   (let [wrapped (u/wrap-text-with-ansi content cw)
                                         colored (mapv #(theme/fg theme :tool-output %) wrapped)]
                                     (mapv #(str left-pad %) colored)))
                top-pad (repeat pad-y (bg empty))
                bottom-pad (repeat pad-y (bg empty))]
            (vec (concat top-pad (map bg name-indented)
                         (when content-indented (map bg content-indented))
                         bottom-pad)))))))
  (handle-input [_this _data] nil)
  (invalidate [this]
    (reset! (:cache-atom this) nil)))

;; ─── IComponentKind ─────────────────────────────────────────────────────────

(extend-type ToolExecutionComponent
  protocols/IComponentKind
  (component-kind [_] :tool))

;; ─── Construction ──────────────────────────────────────────────────────────

(defn make-tool-execution
  [& {:keys [name content is-error theme output-pad expanded?]
      :or {name "" content "" is-error false theme theme/dark-theme
           output-pad 1 expanded? false}}]
  (map->ToolExecutionComponent {:name-atom (atom name)
                       :content-atom (atom content)
                       :is-error-atom (atom is-error)
                       :theme-atom (atom theme)
                       :output-pad-atom (atom output-pad)
                       :expanded-atom (atom expanded?)
                       :cache-atom (atom nil)}))

;; ─── Public API ────────────────────────────────────────────────────────────

(defn tool-execution-set-name! [comp name]
  (reset! (:name-atom comp) name) (protocols/invalidate comp))
(defn tool-execution-set-content! [comp content]
  (reset! (:content-atom comp) content) (protocols/invalidate comp))
(defn tool-execution-set-error! [comp is-error]
  (reset! (:is-error-atom comp) is-error) (protocols/invalidate comp))
(defn tool-execution-set-expanded! [comp expanded?]
  (reset! (:expanded-atom comp) expanded?) (protocols/invalidate comp))
(defn tool-execution-set-theme! [comp theme]
  (reset! (:theme-atom comp) theme) (protocols/invalidate comp))
(defn tool-execution-set-output-pad! [comp n]
  (reset! (:output-pad-atom comp) n) (protocols/invalidate comp))
