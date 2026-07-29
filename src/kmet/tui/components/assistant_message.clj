(ns kmet.tui.components.assistant-message
  "AssistantMessage component — Pi's AssistantMessageComponent.
   Renders assistant messages with markdown text and thinking blocks
   (italic + thinking-text color). Supports streaming via append methods."
  (:require [kmet.tui.protocols :as protocols]
            [kmet.tui.utils :as u]
            [kmet.tui.theme :as theme]
            [kmet.tui.components.markdown :as md]))



;; ─── Component ──────────────────────────────────────────────────────────────

(defrecord AssistantMessage [text-atom          ;; string (final or partial text)
                             thinking-text-atom ;; string (thinking content)
                             theme-atom
                             output-pad-atom
                             hide-thinking-atom ;; bool: true = show "Thinking..." label
                             finalized-atom     ;; bool: true = no cursor
                             cache-atom]
  protocols/IComponent

  (render [this width]
    (let [text @text-atom
          thinking @thinking-text-atom
          theme @theme-atom
          output-pad @output-pad-atom
          hide? @hide-thinking-atom
          finalized @finalized-atom
          cached @cache-atom]
      (if (and cached
               (= (:width cached) width)
               (= (:text cached) text)
               (= (:thinking cached) thinking)
               (= (:theme cached) theme)
               (= (:output-pad cached) output-pad)
               (= (:hide? cached) hide?)
               (= (:finalized cached) finalized))
        (:lines cached)
        (let [pad-x output-pad
              cw (max 1 (- width (* 2 pad-x)))
              left-pad (apply str (repeat pad-x \space))
              ;; Thinking block — render as Markdown (for bold, code, lists etc.)
              ;; then wrap each line with italic + thinking-text color (Pi-style)
              thinking-lines (when (seq thinking)
                               (if hide?
                                 ;; Pi: show "Thinking..." label
                                 [(str left-pad (theme/fg theme :thinking-text (theme/italic "Thinking...")))]
                                 ;; Pi: render thinking as Markdown, then apply italic+thinkingText to all lines
                                 (let [mc (md/make-markdown thinking :padding-x 0)
                                       md-lines (protocols/render mc cw)]
                                   (mapv (fn [line]
                                           (str left-pad (theme/fg theme :thinking-text (theme/italic line))))
                                         md-lines))))
              ;; Text content
              text-lines (when (seq text)
                           (let [wrapped (u/wrap-text-with-ansi text cw)]
                             (mapv #(str left-pad (theme/fg theme :text %)) wrapped)))
              ;; Cursor indicator (only for non-finalized streaming)
              cursor (when (and (not finalized)
                                (or (seq thinking) (seq text)))
                       (str left-pad "\u001b[1m" (theme/fg theme :muted "▍") "\u001b[0m"))
              result (vec (concat thinking-lines text-lines (when cursor [cursor])))]
          (reset! cache-atom {:width width :text text :thinking thinking
                              :theme theme :output-pad output-pad
                              :hide? hide? :finalized finalized :lines result})
          result))))

  (handle-input [_this _data] nil)

  (invalidate [this]
    (reset! (:cache-atom this) nil)))

;; ─── Construction ──────────────────────────────────────────────────────────

(defn make-assistant-message
  "Create an AssistantMessage component.
   Options:
     :text           — initial text (default \"\")
     :thinking       — initial thinking text (default \"\")
     :theme          — Theme record
     :output-pad     — horizontal padding (default 1)
     :hide-thinking? — hide thinking blocks? (default false)
     :finalized?     — is the message complete? (default false)"
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

(defn assistant-message-set-text!
  "Set the assistant message text (replaces any existing text)."
  [comp text]
  (reset! (:text-atom comp) text)
  (protocols/invalidate comp))

(defn assistant-message-append-text!
  "Append text to the assistant message (for streaming)."
  [comp text]
  (swap! (:text-atom comp) str text)
  (protocols/invalidate comp))

(defn assistant-message-set-thinking!
  "Set the thinking text (replaces any existing thinking)."
  [comp text]
  (reset! (:thinking-text-atom comp) text)
  (protocols/invalidate comp))

(defn assistant-message-append-thinking!
  "Append text to the thinking display (for streaming)."
  [comp text]
  (swap! (:thinking-text-atom comp) str text)
  (protocols/invalidate comp))

(defn assistant-message-finalize!
  "Mark the message as finalized (removes cursor indicator)."
  [comp]
  (reset! (:finalized-atom comp) true)
  (protocols/invalidate comp))

(defn assistant-message-set-hide-thinking!
  "Set whether thinking blocks are hidden (shows 'Thinking...' label)."
  [comp hide?]
  (reset! (:hide-thinking-atom comp) hide?)
  (protocols/invalidate comp))

(defn assistant-message-set-theme!
  "Set the theme."
  [comp theme]
  (reset! (:theme-atom comp) theme)
  (protocols/invalidate comp))

(defn assistant-message-set-output-pad!
  "Set horizontal padding."
  [comp n]
  (reset! (:output-pad-atom comp) n)
  (protocols/invalidate comp))

(defn assistant-message-get-text
  "Get the current message text."
  [comp]
  @(:text-atom comp))

(defn assistant-message-get-thinking
  "Get the current thinking text."
  [comp]
  @(:thinking-text-atom comp))
