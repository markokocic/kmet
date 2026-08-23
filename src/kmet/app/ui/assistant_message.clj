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
            [kmet.tui.macros :refer [track! defsetter defcomponent]]
            [kmet.app.extensions :as extensions]))

;; ─── Helpers ───────────────────────────────────────────────────────────────

(defn- make-assistant-transform
  "Extension markdown transform for assistant messages (pi:
   createMarkdownTransform(\"assistant\", this.isStreaming)): transformers
   apply in registration order at reflow time (every streaming chunk
   re-runs them), throwing transformers are skipped; the transformer list is
   read at apply time so late registrations take effect on the next reflow.
   STREAMING-ATOM — the component's streaming flag (true while the response
   streams, false once finalized)."
  [streaming-atom]
  (fn [text {:keys [available-width]}]
    (extensions/apply-markdown-transformers
     text {:message-type :assistant
           :is-streaming (boolean @streaming-atom)
           :available-width available-width})))

(defn- render-text-to-width
  "Render assistant text as markdown (pi: Markdown + getMarkdownTheme, which
   includes syntax-highlighting the code fences). Plain text is left unstyled
   — terminal default, exactly like pi's assistant messages."
  [text cw left-pad theme transform]
  (when (seq text)
    (let [mc (md/make-markdown text
                               :theme (theme/get-markdown-theme theme)
                               :transform transform
                               :padding-x 0)
          md-lines (protocols/render mc cw)]
      (mapv #(str left-pad %) md-lines))))

(defn- render-thinking-to-width
  "Render thinking text as markdown tinted thinkingText + italic (pi:
   Markdown with defaultTextStyle {color: thinkingText, italic: true}). Code
   fences inside thinking highlight, like pi. When hidden, renders the
   hidden-thinking label instead (pi: hiddenThinkingLabel)."
  [text cw left-pad theme hide? hidden-label transform]
  (if (not (seq text))
    []
    (if hide?
      [(str left-pad (theme/italic (theme/fg theme :thinking-text hidden-label)))]
      (let [mc (md/make-markdown text
                                 :theme (theme/get-markdown-theme theme)
                                 :default-style (fn [s]
                                                  (theme/italic (theme/fg theme :thinking-text s)))
                                 :transform transform
                                 :padding-x 0)
            md-lines (protocols/render mc cw)]
        (mapv #(str left-pad %) md-lines)))))

(declare reflow-all!)

;; ─── Record ────────────────────────────────────────────────────────────────

(defcomponent AssistantMessageComponent :assistant
              [text-atom thinking-text-atom theme-atom
               output-pad-atom hide-thinking-atom hidden-label-atom
               streaming-atom
               rendered-text-lines-atom
               rendered-thinking-lines-atom
               rendered-text-atom        ;; text source of the cached lines (stale check)
               rendered-thinking-atom    ;; thinking source of the cached lines (stale check)
               rendered-streaming-atom   ;; streaming flag of the cached lines (stale check)
               rendered-hide?-atom       ;; hide flag the cached lines were built with
               rendered-hidden-label-atom ;; label the cached lines were built with
               last-render-width-atom
               cache-atom]
  (render [this width]
    (track! this width
      (let [prev-width @last-render-width-atom
            ;; Pi trims each content block (content.text.trim()); whitespace-only
            ;; blocks render nothing (and get no Spacer(1)).
            text (let [t (str/trim (or @text-atom ""))] (when (seq t) t))
            thinking (let [t (str/trim (or @thinking-text-atom ""))] (when (seq t) t))
            streaming? (boolean @streaming-atom)
            ;; Read in the track! body so a flip of the (possibly SHARED)
            ;; flag/label atoms invalidates this cache like any other input.
            hide? (boolean @hide-thinking-atom)
            hidden-label @hidden-label-atom
            text-empty? (nil? text)
            thinking-empty? (nil? thinking)
            ;; Reflow lazily on the render thread: appends only swap the text
            ;; atom (never blocking the LLM stream); a render re-wraps when the
            ;; width changed, new text arrived, or the streaming flag flipped
            ;; (finalize must re-run transformers with is-streaming false)
            ;; since the cached lines.
            stale? (or (and prev-width (not= prev-width width))
                       (not= text @rendered-text-atom)
                       (not= thinking @rendered-thinking-atom)
                       (not= streaming? @rendered-streaming-atom)
                       (not= hide? @rendered-hide?-atom)
                       (not= hidden-label @rendered-hidden-label-atom))
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
                         content))))))))

;; ─── Internal: reflow both text and thinking into the line atoms ──────────

(defn- reflow-all!
  "Re-wrap/render all text and thinking, storing into line atoms plus the
   source text they were wrapped from (the render's stale check)."
  [comp width]
  (let [theme @(:theme-atom comp)
        output-pad @(:output-pad-atom comp)
        hide? @(:hide-thinking-atom comp)
        hidden-label @(:hidden-label-atom comp)
        pad-x output-pad
        cw (max 1 (- width (* 2 pad-x)))
        left-pad (apply str (repeat pad-x \space))
        text (str/trim (or @(:text-atom comp) ""))
        thinking (str/trim (or @(:thinking-text-atom comp) ""))
        streaming? (boolean @(:streaming-atom comp))
        transform (make-assistant-transform (:streaming-atom comp))]
    (reset! (:rendered-text-lines-atom comp)
            (render-text-to-width text cw left-pad theme transform))
    (reset! (:rendered-thinking-lines-atom comp)
            (render-thinking-to-width thinking cw left-pad theme hide? hidden-label transform))
    (reset! (:rendered-text-atom comp) text)
    (reset! (:rendered-thinking-atom comp) thinking)
    (reset! (:rendered-streaming-atom comp) streaming?)
    (reset! (:rendered-hide?-atom comp) hide?)
    (reset! (:rendered-hidden-label-atom comp) hidden-label)
    (reset! (:last-render-width-atom comp) width)))

;; ─── Construction ──────────────────────────────────────────────────────────

(defn make-assistant-message
  [& {:keys [text thinking text-atom thinking-atom theme output-pad
             hide-thinking? hidden-label thinking-hidden-atom hidden-label-atom]
      :or {text "" thinking "" theme theme/dark-theme
           output-pad 1 hide-thinking? false}}]
  ;; Optional SHARED flag atoms: when the chat history passes its own
  ;; thinking-hidden/hidden-label atoms, every message built from it reads
  ;; the same state — a toggle is a single reset! that track! watches on all
  ;; messages invalidate at once (pi: hideThinkingBlock). Callers passing a
  ;; nil label fall back to the default.
  ;; TEXT-ATOM/THINKING-ATOM are the data-layer content homes (Stage 5,
  ;; dsl.md §3.2): the chat history owns them on the message map and appends
  ;; swap them directly — no component-facing mutation API. When absent the
  ;; component creates its own (standalone constructors).
  (let [hidden-label (or hidden-label "Thinking...")
        comp (map->AssistantMessageComponent {:kind :assistant
                                              :text-atom (or text-atom (atom text))
                                              :thinking-text-atom (or thinking-atom (atom thinking))
                                              :theme-atom (atom theme)
                                              :output-pad-atom (atom output-pad)
                                              :hide-thinking-atom (or thinking-hidden-atom
                                                                      (atom hide-thinking?))
                                              :hidden-label-atom (or hidden-label-atom
                                                                     (atom hidden-label))
                                              :streaming-atom (atom false)
                                              :rendered-text-lines-atom (atom [])
                                              :rendered-thinking-lines-atom (atom [])
                                              :rendered-text-atom (atom nil)
                                              :rendered-thinking-atom (atom nil)
                                              :rendered-streaming-atom (atom nil)
                                              :rendered-hide?-atom (atom nil)
                                              :rendered-hidden-label-atom (atom nil)
                                              :last-render-width-atom (atom nil)
                                              :cache-atom (atom nil)})]
    ;; Do initial render so lines are ready immediately
    (reflow-all! comp 80)
    comp))

;; ─── Public API ────────────────────────────────────────────────────────
;; Content lives in the data-layer atoms (the message map's :text-atom/
;; :thinking-atom, shared into this record) — there is deliberately NO
;; component-facing text/thinking setter, appender or getter: the app swaps
;; and reads the atoms directly; track!'s watches invalidate the cache and
;; schedule the frame on every real change (dsl.md §3.2 Stage 5).

(defn assistant-message-set-streaming!
  "Set the streaming flag (pi: this.isStreaming) — true while the response
   streams, false once finalized. Flips the markdown transformers'
   :is-streaming context and forces a reflow on the next render."
  [comp streaming?]
  (reset! (:streaming-atom comp) (boolean streaming?)))

(defsetter assistant-message-set-hide-thinking! :hide-thinking-atom comp hide?
  (when-let [w @(:last-render-width-atom comp)]
    (reflow-all! comp w)))

(defsetter assistant-message-set-hidden-label! :hidden-label-atom comp label
  (when-let [w @(:last-render-width-atom comp)]
    (reflow-all! comp w)))

(defsetter assistant-message-set-theme! :theme-atom comp theme
  (when-let [w @(:last-render-width-atom comp)]
    (reflow-all! comp w)))

(defsetter assistant-message-set-output-pad! :output-pad-atom comp n
  (when-let [w @(:last-render-width-atom comp)]
    (reflow-all! comp w)))
