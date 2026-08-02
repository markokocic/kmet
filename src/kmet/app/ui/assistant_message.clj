(ns kmet.app.ui.assistant-message
  "AssistantMessageComponent component — Pi's AssistantMessageComponent.
   Top pad-y=1 only (Pi-style Spacer(1) at top when content present).
   No bottom padding — next component provides its own top spacing.
   Spacer between thinking and text blocks when text follows thinking.
   Optimized for streaming: text/thinking wrapping/parsing happens eagerly
   in append calls (on the LLM thread) so the render function returns
   pre-rendered lines instantly.
   Does NOT include a working spinner — the working indicator is a separate
   StatusIndicator in a dedicated layout layer between chat and editor (Pi-style)."
  (:require [clojure.string :as str]
            [kmet.tui.protocols :as protocols]
            [kmet.tui.theme :as theme]
            [kmet.tui.components.markdown :as md]
            [kmet.tui.macros :refer [track! defsetter defgetter defcomponent]]))

;; ─── Helpers ───────────────────────────────────────────────────────────────

(defn- render-text-to-width
  "Render assistant text as markdown (pi: Markdown + getMarkdownTheme, which
   includes syntax-highlighting the code fences). Plain text is left unstyled
   — terminal default, exactly like pi's assistant messages."
  [text cw left-pad theme]
  (when (seq text)
    (let [mc (md/make-markdown text
                               :theme (theme/get-markdown-theme theme)
                               :padding-x 0)
          md-lines (protocols/render mc cw)]
      (mapv #(str left-pad %) md-lines))))

(defn- render-thinking-to-width
  "Render thinking text as markdown tinted thinkingText + italic (pi:
   Markdown with defaultTextStyle {color: thinkingText, italic: true}). Code
   fences inside thinking highlight, like pi."
  [text cw left-pad theme hide?]
  (if (not (seq text))
    []
    (if hide?
      [(str left-pad (theme/fg theme :thinking-text (theme/italic "Thinking...")))]
      (let [mc (md/make-markdown text
                                 :theme (theme/get-markdown-theme theme)
                                 :default-style (fn [s]
                                                  (theme/fg theme :thinking-text
                                                            (theme/italic s)))
                                 :padding-x 0)
            md-lines (protocols/render mc cw)]
        (mapv #(str left-pad %) md-lines)))))

(declare reflow-all!)

;; ─── Record ────────────────────────────────────────────────────────────────

(defcomponent AssistantMessageComponent :assistant
              [text-atom thinking-text-atom theme-atom
               output-pad-atom hide-thinking-atom
               rendered-text-lines-atom
               rendered-thinking-lines-atom
               rendered-text-atom        ;; text source of the cached lines (stale check)
               rendered-thinking-atom    ;; thinking source of the cached lines (stale check)
               last-render-width-atom
               cache-atom]
  (render [this width]
    (track! this width
      (let [prev-width @last-render-width-atom
            ;; Pi trims each content block (content.text.trim()); whitespace-only
            ;; blocks render nothing (and get no Spacer(1)).
            text (let [t (str/trim (or @text-atom ""))] (when (seq t) t))
            thinking (let [t (str/trim (or @thinking-text-atom ""))] (when (seq t) t))
            text-empty? (nil? text)
            thinking-empty? (nil? thinking)
            ;; Reflow lazily on the render thread: appends only swap the text
            ;; atom (never blocking the LLM stream); a render re-wraps when the
            ;; width changed or new text arrived since the cached lines.
            stale? (or (and prev-width (not= prev-width width))
                       (not= text @rendered-text-atom)
                       (not= thinking @rendered-thinking-atom))
            _ (when stale? (reflow-all! this width))
            text-lines @rendered-text-lines-atom
            thinking-lines @rendered-thinking-lines-atom]
        ;; Pi-style: no visible content (streaming or finalized empty) → render nothing.
        ;; The working indicator is a separate StatusIndicator between chat and editor.
        (if (and text-empty? thinking-empty?)
          []
          ;; Normal: render with reactive cache + top pad-y=1 only (Pi-style Spacer(1) equivalent)
          (let [pad-y 1
                empty (apply str (repeat width \space))
                ;; Pi-style: spacer between thinking and text blocks when text follows
                thinking-text-spacer? (and (seq thinking) (seq text))
                content (vec (concat thinking-lines
                                     (when thinking-text-spacer? [empty])
                                     text-lines))]
            ;; Pi-style: top padding only (Spacer(1) equivalent).
            ;; No bottom padding — next component provides its own top spacing.
            (vec (concat (repeat pad-y empty)
                         content)))))))
  (invalidate [this]
    (reset! (:cache-atom this) nil)))

;; ─── Internal: reflow both text and thinking into the line atoms ──────────

(defn- reflow-all!
  "Re-wrap/render all text and thinking, storing into line atoms plus the
   source text they were wrapped from (the render's stale check)."
  [comp width]
  (let [theme @(:theme-atom comp)
        output-pad @(:output-pad-atom comp)
        hide? @(:hide-thinking-atom comp)
        pad-x output-pad
        cw (max 1 (- width (* 2 pad-x)))
        left-pad (apply str (repeat pad-x \space))
        text (str/trim (or @(:text-atom comp) ""))
        thinking (str/trim (or @(:thinking-text-atom comp) ""))]
    (reset! (:rendered-text-lines-atom comp)
            (render-text-to-width text cw left-pad theme))
    (reset! (:rendered-thinking-lines-atom comp)
            (render-thinking-to-width thinking cw left-pad theme hide?))
    (reset! (:rendered-text-atom comp) text)
    (reset! (:rendered-thinking-atom comp) thinking)
    (reset! (:last-render-width-atom comp) width)))

;; ─── Construction ──────────────────────────────────────────────────────────

(defn make-assistant-message
  [& {:keys [text thinking theme output-pad hide-thinking?]
      :or {text "" thinking "" theme theme/dark-theme
           output-pad 1 hide-thinking? false}}]
  (let [comp (map->AssistantMessageComponent {:text-atom (atom text)
                                              :thinking-text-atom (atom thinking)
                                              :theme-atom (atom theme)
                                              :output-pad-atom (atom output-pad)
                                              :hide-thinking-atom (atom hide-thinking?)
                                              :rendered-text-lines-atom (atom [])
                                              :rendered-thinking-lines-atom (atom [])
                                              :rendered-text-atom (atom nil)
                                              :rendered-thinking-atom (atom nil)
                                              :last-render-width-atom (atom nil)
                                              :cache-atom (atom nil)})]
    ;; Do initial render so lines are ready immediately
    (reflow-all! comp 80)
    comp))

;; ─── Public API ────────────────────────────────────────────────────────────

(defsetter assistant-message-set-text! :text-atom comp text)

(defn assistant-message-append-text! [comp text]
  ;; Appends only swap the text atom — reflow happens lazily in render, so
  ;; streaming deltas never block the LLM thread (pi rebuilds content on
  ;; every message_update; kmet defers the wrap to the render thread).
  (swap! (:text-atom comp) str text))

(defsetter assistant-message-set-thinking! :thinking-text-atom comp text)

(defn assistant-message-append-thinking! [comp text]
  (swap! (:thinking-text-atom comp) str text))

(defsetter assistant-message-set-hide-thinking! :hide-thinking-atom comp hide?
  (when-let [w @(:last-render-width-atom comp)]
    (reflow-all! comp w)))

(defsetter assistant-message-set-theme! :theme-atom comp theme
  (when-let [w @(:last-render-width-atom comp)]
    (reflow-all! comp w)))

(defsetter assistant-message-set-output-pad! :output-pad-atom comp n
  (when-let [w @(:last-render-width-atom comp)]
    (reflow-all! comp w)))

(defgetter assistant-message-get-text :text-atom comp)
(defgetter assistant-message-get-thinking :thinking-text-atom comp)
