(ns kmet.tui.components.assistant-message
  "AssistantMessage component — Pi's AssistantMessageComponent."
  (:require [kmet.tui.protocols :as protocols]
            [kmet.tui.utils :as u]
            [kmet.tui.theme :as theme]
            [kmet.tui.components.markdown :as md]
            [kmet.tui.macros :refer [with-cache]]))

(defrecord AssistantMessage [text-atom thinking-text-atom theme-atom
                             output-pad-atom hide-thinking-atom finalized-atom
                             cache-atom]
  protocols/IComponent
  (render [this width]
    (let [text @text-atom
          thinking @thinking-text-atom
          theme @theme-atom
          output-pad @output-pad-atom
          hide? @hide-thinking-atom
          finalized @finalized-atom]
      (with-cache this width
        {:text text :thinking thinking :theme theme
         :output-pad output-pad :hide? hide? :finalized finalized}
        (fn []
          (let [pad-x output-pad
                cw (max 1 (- width (* 2 pad-x)))
                left-pad (apply str (repeat pad-x \space))
                thinking-lines (when (seq thinking)
                                 (if hide?
                                   [(str left-pad (theme/fg theme :thinking-text (theme/italic "Thinking...")))]
                                   (let [mc (md/make-markdown thinking :padding-x 0)
                                         md-lines (protocols/render mc cw)]
                                     (mapv (fn [line]
                                             (str left-pad (theme/fg theme :thinking-text (theme/italic line))))
                                           md-lines))))
                text-lines (when (seq text)
                             (let [wrapped (u/wrap-text-with-ansi text cw)]
                               (mapv #(str left-pad (theme/fg theme :text %)) wrapped)))
                cursor (when (and (not finalized)
                                  (or (seq thinking) (seq text)))
                         (str left-pad "\u001b[1m" (theme/fg theme :muted "▍") "\u001b[0m"))]
            (vec (concat thinking-lines text-lines (when cursor [cursor]))))))))
  (handle-input [_this _data] nil)
  (invalidate [this]
    (reset! (:cache-atom this) nil)))

;; ─── IComponentKind ─────────────────────────────────────────────────────────

(extend-type AssistantMessage
  protocols/IComponentKind
  (component-kind [_] :assistant))

;; ─── Construction ──────────────────────────────────────────────────────────

(defn make-assistant-message
  [& {:keys [text thinking theme output-pad hide-thinking? finalized?]
      :or {text "" thinking "" theme theme/dark-theme
           output-pad 1 hide-thinking? false finalized? false}}]
  (map->AssistantMessage {:text-atom (atom text)
                          :thinking-text-atom (atom thinking)
                          :theme-atom (atom theme)
                          :output-pad-atom (atom output-pad)
                          :hide-thinking-atom (atom hide-thinking?)
                          :finalized-atom (atom finalized?)
                          :cache-atom (atom nil)}))

;; ─── Public API ────────────────────────────────────────────────────────────

(defn assistant-message-set-text! [comp text]
  (reset! (:text-atom comp) text) (protocols/invalidate comp))
(defn assistant-message-append-text! [comp text]
  (swap! (:text-atom comp) str text) (protocols/invalidate comp))
(defn assistant-message-set-thinking! [comp text]
  (reset! (:thinking-text-atom comp) text) (protocols/invalidate comp))
(defn assistant-message-append-thinking! [comp text]
  (swap! (:thinking-text-atom comp) str text) (protocols/invalidate comp))
(defn assistant-message-finalize! [comp]
  (reset! (:finalized-atom comp) true) (protocols/invalidate comp))
(defn assistant-message-set-hide-thinking! [comp hide?]
  (reset! (:hide-thinking-atom comp) hide?) (protocols/invalidate comp))
(defn assistant-message-set-theme! [comp theme]
  (reset! (:theme-atom comp) theme) (protocols/invalidate comp))
(defn assistant-message-set-output-pad! [comp n]
  (reset! (:output-pad-atom comp) n) (protocols/invalidate comp))
(defn assistant-message-get-text [comp] @(:text-atom comp))
(defn assistant-message-get-thinking [comp] @(:thinking-text-atom comp))
