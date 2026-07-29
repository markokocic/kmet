(ns kmet.tui.components.chat-history
  "Chat history component for displaying user/assistant/tool messages.
   Supports scrolling and streaming text rendering."
  (:require [kmet.tui.protocols :as protocols]
            [kmet.tui.keys :as keys]
            [kmet.tui.utils :as u]
            [kmet.tui.theme :as theme]))

;; ─── ANSI constants ────────────────────────────────────────────────────────

(def ^:private bold "\u001b[1m")
(def ^:private reset "\u001b[0m")
(def ^:private dim "\u001b[2m")

;; ─── Message rendering ─────────────────────────────────────────────────────

(defn- render-user-msg
  "Render a user message into a vector of ANSI-styled lines."
  [content width theme]
  (let [cw (max 1 (- width 4))
        header (str bold (theme/fg theme :accent "─── You ") reset
                    (apply str (repeat (max 0 (- width 8)) "─")))
        wrapped (u/wrap-text-with-ansi content cw)
        body (mapv #(str "  " (theme/fg theme :text %) reset) wrapped)]
    (into [header] body)))

(defn- render-assistant-msg
  "Render an assistant message into a vector of ANSI-styled lines."
  [content width theme]
  (let [cw (max 1 (- width 4))
        header (str bold (theme/fg theme :accent "─── Assistant ") reset
                    (apply str (repeat (max 0 (- width 13)) "─")))
        wrapped (u/wrap-text-with-ansi content cw)
        body (mapv #(str "  " (theme/fg theme :text %) reset) wrapped)]
    (into [header] body)))

(defn- render-tool-msg
  "Render a tool call/result message into a vector of ANSI-styled lines."
  [{:keys [name content is-error]} width theme]
  (let [cw (max 1 (- width 4))
        title-color (if is-error :error :tool-title)
        name-part (str bold (theme/fg theme title-color (str "─── " name " ")) reset)
        sep-len (max 0 (- width (+ 5 (count name))))
        header (str name-part (apply str (repeat sep-len "─")))
        content-color (if is-error :error :tool-output)
        display (theme/fg theme content-color content)
        wrapped (u/wrap-text-with-ansi display cw)
        body (mapv #(str "  " %) wrapped)]
    (into [header] body)))

(defn- render-streaming-msg
  "Render the in-progress streaming assistant response.
   Shows thinking text dimmed above the response."
  [msg-text thinking-text width theme]
  (let [cw (max 1 (- width 4))
        dim-color dim
        header (str bold (theme/fg theme :accent "─── Assistant ") reset
                    (apply str (repeat (max 0 (- width 13)) "─")))
        thinking-lines (when (seq thinking-text)
                         (let [wrapped (u/wrap-text-with-ansi thinking-text cw)
                               lines (mapv #(str "  " dim-color % reset) wrapped)]
                           (into [(str "  " dim "(thinking)" reset)] lines)))
        body-lines (if (empty? msg-text)
                     []
                     (let [wrapped (u/wrap-text-with-ansi msg-text cw)]
                       (mapv #(str "  " (theme/fg theme :text %) reset) wrapped)))
        cursor (when (or (seq msg-text) (seq thinking-text))
                 (str "  " bold (theme/fg theme :muted "▍") reset))
        all-lines (let [v (vec (concat (when (seq thinking-lines) thinking-lines)
                                       (when (seq body-lines) body-lines)))
                        v (if cursor (conj v cursor) v)]
                    v)]
    (if (or (seq msg-text) (seq thinking-text))
      (into [header] all-lines)
      [])))

;; ─── Build full lines from messages ────────────────────────────────────────

(defn- build-all-lines
  "Given messages vector and streaming text, produce all rendered lines."
  [messages streaming-text thinking-text width theme]
  (let [msg-lines (mapcat (fn [m]
                            (case (:role m)
                              :user (render-user-msg (:content m "") width theme)
                              :assistant (render-assistant-msg (:content m "") width theme)
                              :tool (render-tool-msg m width theme)
                              []))
                          messages)
        stream-lines (render-streaming-msg streaming-text thinking-text width theme)]
    (vec (concat msg-lines stream-lines))))

;; ─── ChatHistory record ─────────────────────────────────────────────────────

(defrecord ChatHistory [messages-atom       ;; atom of [{:role :user/:assistant/:tool :content ...}]
                        streaming-text-atom  ;; atom of string (current streaming text)
                        thinking-text-atom   ;; atom of string (current thinking text)
                        scroll-offset-atom   ;; atom of int (starting line index)
                        max-lines-atom       ;; atom of int (visible line count)
                        focused?
                        theme-atom           ;; atom of Theme record
                        cache-atom           ;; atom of {:lines [...] :width N :msgs [...]}
                        last-width-atom]     ;; atom of width value
  protocols/IComponent

  (render [this width]
    (let [messages @messages-atom
          streaming-text @streaming-text-atom
          thinking-text @thinking-text-atom
          max-lines @max-lines-atom
          theme @theme-atom
          cached @cache-atom]
      (if (and cached
               (= (:width cached) width)
               (= (:msgs cached) messages)
               (= (:stream cached) streaming-text)
               (= (:thinking cached) thinking-text)
               (= (:theme cached) theme))
        (:lines cached)
        (let [all-lines (build-all-lines messages streaming-text thinking-text width theme)
              total (count all-lines)
              _ (reset! last-width-atom width)
              scroll-offset @scroll-offset-atom
              ;; clamp scroll-offset to valid range
              max-offset (max 0 (- total max-lines))
              offset (if (<= total max-lines)
                       0
                       (min scroll-offset max-offset))
              visible (if (pos? max-lines)
                        (subvec all-lines offset (min (+ offset max-lines) total))
                        all-lines)]
          ;; Update scroll offset if it was clamped
          (when (not= offset scroll-offset)
            (reset! scroll-offset-atom offset))
          ;; Cache
          (reset! cache-atom {:width width :msgs messages
                              :stream streaming-text :thinking thinking-text
                              :theme theme :lines visible})
          visible))))

  (handle-input [this data]
    (let [total (count (build-all-lines @messages-atom @streaming-text-atom
                                         @thinking-text-atom
                                         @last-width-atom @theme-atom))
          max-lines @max-lines-atom
          offset @scroll-offset-atom
          max-offset (max 0 (- total max-lines))]
      (cond
        (or (keys/matches-key? data "up")
            (keys/matches-key? data (keys/ctrl "p")))
        (when (pos? offset)
          (swap! scroll-offset-atom #(max 0 (dec %)))
          (reset! cache-atom nil)
          nil)

        (or (keys/matches-key? data "down")
            (keys/matches-key? data (keys/ctrl "n")))
        (when (< offset max-offset)
          (swap! scroll-offset-atom #(min max-offset (inc %)))
          (reset! cache-atom nil)
          nil)

        (keys/matches-key? data "pageUp")
        (do (swap! scroll-offset-atom #(max 0 (- % (max 1 (dec max-lines)))))
            (reset! cache-atom nil)
            nil)

        (keys/matches-key? data "pageDown")
        (do (swap! scroll-offset-atom #(min max-offset (+ % (max 1 (dec max-lines)))))
            (reset! cache-atom nil)
            nil)

        (keys/matches-key? data "home")
        (do (reset! scroll-offset-atom 0)
            (reset! cache-atom nil)
            nil)

        (keys/matches-key? data "end")
        (do (reset! scroll-offset-atom max-offset)
            (reset! cache-atom nil)
            nil)

        :else nil)))

  (invalidate [this]
    (reset! (:cache-atom this) nil)))

;; ─── Construction ──────────────────────────────────────────────────────────

(defn make-chat-history
  "Create a ChatHistory component.
   Options:
     :max-lines — number of visible lines (default 20)
     :theme     — Theme record (default dark-theme)"
  [& {:keys [max-lines theme] :or {max-lines 20
                                   theme theme/dark-theme}}]
  (map->ChatHistory {:messages-atom (atom [])
                     :streaming-text-atom (atom "")
                     :thinking-text-atom (atom "")
                     :scroll-offset-atom (atom 0)
                     :max-lines-atom (atom max-lines)
                     :focused? (atom false)
                     :theme-atom (atom theme)
                     :cache-atom (atom nil)
                     :last-width-atom (atom 80)}))

;; ─── Public API ────────────────────────────────────────────────────────────

(defn chat-history-add-message!
  "Add a message to the chat history.
   msg: {:role :user/:assistant/:tool :content \"...\" ...}
   When adding a non-streaming message, auto-scroll to bottom."
  [ch msg]
  (swap! (:messages-atom ch) conj msg)
  ;; Auto-scroll to bottom — set large so render clamps to max-offset
  (reset! (:scroll-offset-atom ch) Integer/MAX_VALUE)
  (protocols/invalidate ch))

(defn chat-history-add-messages!
  "Add multiple messages at once."
  [ch msgs]
  (doseq [m msgs]
    (swap! (:messages-atom ch) conj m))
  (reset! (:scroll-offset-atom ch) Integer/MAX_VALUE)
  (protocols/invalidate ch))

(defn chat-history-set-streaming-text!
  "Set the streaming assistant response text.
   Setting to empty string hides the streaming indicator."
  [ch text]
  (reset! (:streaming-text-atom ch) text)
  (protocols/invalidate ch))

(defn chat-history-append-streaming-text!
  "Append text to the current streaming response."
  [ch text]
  (swap! (:streaming-text-atom ch) str text)
  (protocols/invalidate ch))

(defn chat-history-finalize-streaming!
  "Convert the current streaming text into a finalized assistant message.
   Returns the message that was added, or nil if streaming text was empty."
  [ch]
  (let [text @(:streaming-text-atom ch)]
    (reset! (:streaming-text-atom ch) "")
    (when (seq text)
      (let [msg {:role :assistant :content text}]
        (chat-history-add-message! ch msg)
        msg))))

(defn chat-history-append-thinking-text!
  "Append text to the current thinking/reasoning display."
  [ch text]
  (swap! (:thinking-text-atom ch) str text)
  (protocols/invalidate ch))

(defn chat-history-finalize-thinking!
  "Clear the thinking text (called when assistant starts responding)."
  [ch]
  (reset! (:thinking-text-atom ch) "")
  (protocols/invalidate ch))

(defn chat-history-clear!
  "Clear all messages, streaming text, and thinking text."
  [ch]
  (reset! (:messages-atom ch) [])
  (reset! (:streaming-text-atom ch) "")
  (reset! (:thinking-text-atom ch) "")
  (reset! (:scroll-offset-atom ch) 0)
  (reset! (:cache-atom ch) nil))

(defn chat-history-get-messages
  "Get all stored messages."
  [ch]
  @(:messages-atom ch))

(defn chat-history-set-max-lines!
  "Set the number of visible lines."
  [ch n]
  (reset! (:max-lines-atom ch) n)
  (protocols/invalidate ch))

(defn chat-history-get-streaming-text
  "Get the current streaming text."
  [ch]
  @(:streaming-text-atom ch))

(defn chat-history-set-theme!
  "Set the theme used for rendering."
  [ch theme]
  (reset! (:theme-atom ch) theme)
  (protocols/invalidate ch))

;; ─── IFocusable ─────────────────────────────────────────────────────────────

(extend-type ChatHistory
  protocols/IFocusable
  (focused [this] @(:focused? this))
  (set-focused! [this val] (reset! (:focused? this) val)))
