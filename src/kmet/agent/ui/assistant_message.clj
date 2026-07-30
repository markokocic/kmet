(ns kmet.agent.ui.assistant-message
  "AssistantMessageComponent component — Pi's AssistantMessageComponent.
   Has consistent pad-y=1 vertical spacing matching other message components.
   Optimized for streaming: text/thinking wrapping/parsing happens eagerly
   in append calls (on the LLM thread) so the render function returns
   pre-rendered lines instantly.
   Does NOT include a working spinner — the working indicator is a separate
   StatusIndicator in a dedicated layout layer between chat and editor (Pi-style)."
  (:require [kmet.tui.protocols :as protocols]
            [kmet.tui.utils :as u]
            [kmet.tui.theme :as theme]
            [kmet.tui.components.markdown :as md]
            [kmet.tui.macros :refer [with-cache]]))

;; ─── Helpers ───────────────────────────────────────────────────────────────

(defn- wrap-text-to-width
  "Wrap text to width, apply left-padding and theme-fg."
  [text cw left-pad theme]
  (when (seq text)
    (let [wrapped (u/wrap-text-with-ansi text cw)]
      (mapv #(str left-pad (theme/fg theme :text %)) wrapped))))

(defn- render-thinking-to-width
  "Render thinking text as markdown, wrap, apply italic + theme-fg."
  [text cw left-pad theme hide?]
  (if (not (seq text))
    []
    (if hide?
      [(str left-pad (theme/fg theme :thinking-text (theme/italic "Thinking...")))]
      (let [mc (md/make-markdown text :padding-x 0)
            md-lines (protocols/render mc cw)]
        (mapv (fn [line]
                (str left-pad (theme/fg theme :thinking-text (theme/italic line))))
              md-lines)))))

(defn- cursor-line
  "Return a cursor indicator line or nil."
  [left-pad theme finalized has-content]
  (when (and (not finalized) has-content)
    (str left-pad (theme/bold (theme/fg theme :muted "▍")))))

;; ─── Record ────────────────────────────────────────────────────────────────

(defrecord AssistantMessageComponent [text-atom thinking-text-atom theme-atom
                             output-pad-atom hide-thinking-atom finalized-atom
                             rendered-text-lines-atom
                             rendered-thinking-lines-atom
                             last-render-width-atom
                             cache-atom]
  protocols/IComponent
  (render [this width]
    (let [theme @theme-atom
          output-pad @output-pad-atom
          hide? @hide-thinking-atom
          finalized @finalized-atom
          pad-x output-pad
          cw (max 1 (- width (* 2 pad-x)))
          left-pad (apply str (repeat pad-x \space))
          prev-width @last-render-width-atom
          text (let [t @text-atom] (when (seq t) t))
          thinking (let [t @thinking-text-atom] (when (seq t) t))
          text-empty? (nil? text)
          thinking-empty? (nil? thinking)
          width-changed? (and prev-width (not= prev-width width))
          text-lines (if width-changed?
                       (wrap-text-to-width text cw left-pad theme)
                       @rendered-text-lines-atom)
          thinking-lines (if width-changed?
                           (render-thinking-to-width thinking cw left-pad theme hide?)
                           @rendered-thinking-lines-atom)]
      ;; Streaming with no content yet — render nothing. The working indicator
      ;; is a separate StatusIndicator in a dedicated layout layer (Pi-style).
      (if (and (not finalized) text-empty? thinking-empty?)
        []
        ;; Normal: render with cache + pad-y=1
        (with-cache this width
          {:theme theme :output-pad output-pad :hide? hide? :finalized finalized
           :text (count text) :thinking (count thinking)}
          (fn []
            (let [cursor (cursor-line left-pad theme finalized
                            (or (seq thinking) (seq text)))
                  pad-y 1
                  empty (apply str (repeat width \space))
                  content (vec (concat thinking-lines text-lines
                                       (when cursor [cursor])))]
              (vec (concat (repeat pad-y empty)
                           content
                           (repeat pad-y empty)))))))))
  (handle-input [_this _data] nil)
  (invalidate [this]
    (reset! (:cache-atom this) nil)))

;; ─── IComponentKind ─────────────────────────────────────────────────────────

(extend-type AssistantMessageComponent
  protocols/IComponentKind
  (component-kind [_] :assistant))

;; ─── Internal: reflow both text and thinking into the line atoms ──────────

(defn- reflow-all!
  "Re-wrap/render all text and thinking, storing into line atoms."
  [comp width]
  (let [theme @(:theme-atom comp)
        output-pad @(:output-pad-atom comp)
        hide? @(:hide-thinking-atom comp)
        pad-x output-pad
        cw (max 1 (- width (* 2 pad-x)))
        left-pad (apply str (repeat pad-x \space))
        text @(:text-atom comp)
        thinking @(:thinking-text-atom comp)]
    (reset! (:rendered-text-lines-atom comp)
      (wrap-text-to-width text cw left-pad theme))
    (reset! (:rendered-thinking-lines-atom comp)
      (render-thinking-to-width thinking cw left-pad theme hide?))
    (reset! (:last-render-width-atom comp) width)))

;; ─── Construction ──────────────────────────────────────────────────────────

(defn make-assistant-message
  [& {:keys [text thinking theme output-pad hide-thinking? finalized?]
      :or {text "" thinking "" theme theme/dark-theme
           output-pad 1 hide-thinking? false finalized? false}}]
  (let [comp (map->AssistantMessageComponent {:text-atom (atom text)
                          :thinking-text-atom (atom thinking)
                          :theme-atom (atom theme)
                          :output-pad-atom (atom output-pad)
                          :hide-thinking-atom (atom hide-thinking?)
                          :finalized-atom (atom finalized?)
                          :rendered-text-lines-atom (atom [])
                          :rendered-thinking-lines-atom (atom [])
                          :last-render-width-atom (atom nil)
                          :cache-atom (atom nil)})]
    ;; Do initial render so lines are ready immediately
    (reflow-all! comp 80)
    comp))

;; ─── Public API ────────────────────────────────────────────────────────────

(defn assistant-message-set-text! [comp text]
  (reset! (:text-atom comp) text)
  (reflow-all! comp (or @(:last-render-width-atom comp) 80))
  (protocols/invalidate comp))

(defn assistant-message-append-text! [comp text]
  (swap! (:text-atom comp) str text)
  (when-let [w @(:last-render-width-atom comp)]
    (reflow-all! comp w))
  (protocols/invalidate comp))

(defn assistant-message-set-thinking! [comp text]
  (reset! (:thinking-text-atom comp) text)
  (reflow-all! comp (or @(:last-render-width-atom comp) 80))
  (protocols/invalidate comp))

(defn assistant-message-append-thinking! [comp text]
  (swap! (:thinking-text-atom comp) str text)
  (when-let [w @(:last-render-width-atom comp)]
    (reflow-all! comp w))
  (protocols/invalidate comp))

(defn assistant-message-finalize! [comp]
  (reset! (:finalized-atom comp) true) (protocols/invalidate comp))

(defn assistant-message-set-hide-thinking! [comp hide?]
  (reset! (:hide-thinking-atom comp) hide?)
  (when-let [w @(:last-render-width-atom comp)]
    (reflow-all! comp w))
  (protocols/invalidate comp))

(defn assistant-message-set-theme! [comp theme]
  (reset! (:theme-atom comp) theme)
  (when-let [w @(:last-render-width-atom comp)]
    (reflow-all! comp w))
  (protocols/invalidate comp))

(defn assistant-message-set-output-pad! [comp n]
  (reset! (:output-pad-atom comp) n)
  (when-let [w @(:last-render-width-atom comp)]
    (reflow-all! comp w))
  (protocols/invalidate comp))

(defn assistant-message-get-text [comp] @(:text-atom comp))
(defn assistant-message-get-thinking [comp] @(:thinking-text-atom comp))
