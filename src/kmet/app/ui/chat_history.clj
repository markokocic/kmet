(ns kmet.app.ui.chat-history
  "ChatHistoryComponent — data-driven chat history.
   Holds messages as plain maps in a single messages-atom (the single source
   of truth); each message map carries its :component. Render derives the
   component tree from the data on each pass, delegating to the per-message
   component caches. There is no parallel children bookkeeping and no
   child→message reverse-engineering — persistence reads the atom directly."
  (:require [clojure.string :as str]
            [kmet.tui.protocols :as protocols]
            [kmet.tui.theme :as theme]
            [kmet.tui.components.spacer :as spacer]
            [kmet.tui.components.text :as text]
            [kmet.tui.components.truncated-text :as truncated-text]
            [kmet.tui.components.markdown :as md]
            [kmet.tui.components.container :as container]
            [kmet.app.ui.user-message :as um]
            [kmet.app.ui.assistant-message :as am]
            [kmet.app.ui.tool-execution :as te]
            [kmet.app.ui.custom-message :as cm]
            [kmet.app.ui.bash-execution :as be]
            [kmet.tui.macros :refer [track! track-deps defcomponent]]))

;; ─── Info component at top ─────────────────────────────────────────────────

(defn- make-info-msg
  "Create a CustomMessageComponent for the top info banner.
   Supports :collapsed-content / :expanded-content variants (pi: ExpandableText)
   and an :expanded? flag to restore a previously expanded banner."
  [msg theme output-pad]
  (when msg
    (let [comp (cm/make-custom-message :label (:label msg)
                                       :content (:content msg "")
                                       :theme theme
                                       :output-pad output-pad)]
      (when (and (some? (:collapsed-content msg))
                 (some? (:expanded-content msg)))
        (cm/custom-message-set-collapsible-content! comp
                                                    (:collapsed-content msg) (:expanded-content msg))
        (when (:expanded? msg)
          (cm/custom-message-set-expanded! comp true)))
      comp)))

;; ─── Render helpers (defined before the record) ────────────────────────────

(defn- render-messages
  "Render message components. A user message that follows any earlier
   content gets a leading blank line (the Spacer(1) the old container
   model stored explicitly). The info banner counts as earlier content,
   so the first user message after the banner gets the same separator as
   subsequent ones — the banner's box padding alone doesn't read as a
   visible gap between two boxed messages."
  [msgs width banner-present?]
  (loop [msgs msgs, seen-any? banner-present?, acc []]
    (if-let [m (first msgs)]
      (let [lines (protocols/render (:component m) width)
            sep? (and (= :user (:role m)) seen-any?)]
        (recur (rest msgs) true
               (into acc (concat (when sep? [""]) lines))))
      acc)))

;; ─── ChatHistoryComponent record ───────────────────────────────────────────

(defcomponent ChatHistoryComponent nil
              [messages-atom  ;; atom of vec of message maps, each with :component
               info-comp-atom  ;; atom of CustomMessageComponent or nil
               theme-atom
               output-pad-atom
               streaming-atom  ;; atom of streaming message map or nil
               tools-expanded-atom   ;; flag: tool output expanded (pi: toolOutputExpanded)
               thinking-hidden-atom  ;; flag: thinking blocks hidden (pi: hideThinkingBlock)
               hidden-label-atom]    ;; label shown in place of hidden thinking (pi: hiddenThinkingLabel)

  (render [_this width]
    (let [msgs @messages-atom
          info-lines (when-let [i @info-comp-atom] (protocols/render i width))
          msg-lines (render-messages msgs width (some? @info-comp-atom))]
      (into [] (concat info-lines msg-lines))))

  (invalidate [_this]
    (when-let [i @info-comp-atom] (protocols/invalidate i))
    (doseq [m @messages-atom] (protocols/invalidate (:component m)))))

;; ─── Construction ──────────────────────────────────────────────────────────

(defn make-chat-history
  "Create a ChatHistoryComponent.
   Options:
     :theme            — Theme record (default dark-theme)
     :output-pad       — horizontal padding for boxed messages (default 1)
     :thinking-hidden  — initial thinking-blocks hidden flag (default false;
                         pi: hideThinkingBlock loaded from settings at startup)"
  [& {:keys [theme output-pad thinking-hidden]
      :or {theme theme/dark-theme output-pad 1 thinking-hidden false}}]
  (map->ChatHistoryComponent {:messages-atom (atom [])
                              :info-comp-atom (atom nil)
                              :theme-atom (atom theme)
                              :output-pad-atom (atom output-pad)
                              :streaming-atom (atom nil)
                              :tools-expanded-atom (atom false)
                              :thinking-hidden-atom (atom (boolean thinking-hidden))
                              :hidden-label-atom (atom "Thinking...")}))

;; ─── Adding messages ──────────────────────────────────────────────────────

(defn- content->display-text
  "Convert message content (string or block vector) to a display string.
   Handles :text blocks, :tool_result blocks (:content) and image blocks
   (pi renders image thumbnails; here a placeholder)."
  [content]
  (cond
    (string? content) content
    (nil? content) ""
    :else
    (str/join "\n"
              (for [b content]
                (cond
                  (or (= (:type b) :image) (= (:type b) "image"))
                  (str "[image " (or (:mime-type b) "?") "]")
                  :else
                  (or (:content b) (:text b) ""))))))

(defn- make-plain-msg
  "Create a Spacer(1) + plain Text pair — pi's showError/showWarning: a
   dim/error/warning line with no background box."
  [text]
  (let [c (container/make-container)]
    (container/container-add-child c (spacer/make-spacer 1))
    (container/container-add-child c (text/make-text text 1 0))
    c))

(defn- make-plain-md-msg
  "Spacer(1) + Markdown tinted with DEFAULT-STYLE-FN — pi's compaction
   summaries and unknown-role content render as Markdown."
  [text theme default-style-fn]
  (let [c (container/make-container)]
    (container/container-add-child c (spacer/make-spacer 1))
    (container/container-add-child c
                                   (md/make-markdown text
                                                     :theme (theme/get-markdown-theme theme)
                                                     :default-style default-style-fn
                                                     :padding-x 0))
    c))

;; ─── Status line (pi: showStatus) ──────────────────────────────────────────

;; StatusLine — a dim single-line status entry appended to the chat (pi:
;; showStatus appends Spacer(1) + Text to the chat container). Uses
;; TruncatedText so long statuses truncate with an ellipsis instead of
;; wrapping. No component kind — kind-based dispatch (toggles, theme
;; application) returns nil for it.
(defcomponent StatusLine nil [spacer-atom text-atom cache-atom]
  (render [this width]
    (track! this width
      (let [sp @spacer-atom
            tt @text-atom]
        ;; The inner TruncatedText's text atom changes on status updates
        ;; (truncated-text-set-text!) — track it so the cache invalidates.
        (track-deps @(:text-atom tt))
        (into [] (concat (protocols/render sp width)
                         (protocols/render tt width))))))
  (invalidate [_this]
    (protocols/invalidate @spacer-atom)
    (protocols/invalidate @text-atom)))

(defn- make-status-line
  "Create a StatusLine for a status message (pi: showStatus — a Spacer(1)
   plus a dim line)."
  [message]
  (map->StatusLine {:spacer-atom (atom (spacer/make-spacer 1))
                    :text-atom (atom (truncated-text/make-truncated-text
                                      (theme/dim message)
                                      :padding-x 1 :padding-y 0))
                    :cache-atom (atom nil)}))

(defn- make-component-for-msg
  "Create the appropriate component for a message map.
   For tool messages, looks up render functions from the tool registry.
   Assistant messages inherit the current thinking-hidden flag/label and
   tool components the current expansion flag (pi: hideThinkingBlock /
   toolOutputExpanded / hiddenThinkingLabel). A message carrying a pre-built
   :component (extension renderers returning a component directly, and
   replayed :bash executions) uses it as-is."
  [msg theme output-pad tools-expanded? thinking-hidden? hidden-label]
  (cond
    ;; Pre-built component — extension entry/message renderers may return a
    ;; bare component (pi: renderers produce components); the interactive
    ;; wraps those as {:component comp}. :bash messages carry theirs too.
    (:component msg) (:component msg)

    :else
    (case (:role msg)
      :user (um/make-user-message
             :text (content->display-text (:content msg ""))
             :theme theme :output-pad output-pad)
      :assistant (am/make-assistant-message
                  :text (content->display-text (:content msg ""))
                  :thinking (:thinking msg "")
                  :theme theme
                  :output-pad output-pad
                  :hide-thinking? thinking-hidden?
                  :hidden-label hidden-label)
      :tool (let [comp (te/make-tool-execution
                        :name (:name msg "")
                        :args (:args msg {})
                        :content (content->display-text (:content msg ""))
                        :is-error (:is-error msg false)
                        :truncation (:truncation msg)
                        :details (:details msg)
                        :theme theme
                        :output-pad output-pad
                        :expanded? tools-expanded?)]
            ;; Pi: replayed/persisted tool results are final — mark ended so
            ;; they render with success/error bg, footer strip, and Took.
            ;; Live pending messages (content "" + is-error false) are skipped.
              (when (or (seq (:content msg)) (:is-error msg))
                (te/tool-execution-set-error! comp (:is-error msg false)))
              (when-let [images (:images msg)]
                (te/tool-execution-set-images! comp images))
              comp)
      :bash (:component msg)  ;; Already-constructed BashExecutionComponent
      :info (cm/make-custom-message :label (:label msg)
                                    :content (:content msg "")
                                    :theme theme
                                    :output-pad output-pad)
      :error (make-plain-msg (theme/fg theme :error (str "Error: " (:content msg ""))))
      :warning (make-plain-msg (theme/fg theme :warning (str "Warning: " (:content msg ""))))
      :status (make-status-line (:content msg ""))
    ;; Fallback for roles with no dedicated component (e.g. :system compaction
    ;; summaries, unknown roles from session data): render content as markdown
    ;; (pi renders compaction summaries via Markdown) rather than dropping it.
      (make-plain-md-msg (content->display-text (:content msg "")) theme
                         (fn [s] (theme/fg theme :text s))))))

(defn chat-history-add-message!
  "Add a message to the chat history.
   Creates the appropriate component and appends the message map (with its
   :component) to messages-atom. Returns the created component (or nil).
   Auto-scrolls to bottom."
  [ch msg]
  (let [comp (make-component-for-msg msg @(:theme-atom ch) @(:output-pad-atom ch)
                                     @(:tools-expanded-atom ch) @(:thinking-hidden-atom ch)
                                     @(:hidden-label-atom ch))]
    (when comp
      (swap! (:messages-atom ch) conj (assoc msg :component comp)))
    comp))

(defn chat-history-add-messages!
  "Add multiple messages at once."
  [ch msgs]
  (doseq [m msgs]
    (chat-history-add-message! ch m)))

(defn chat-history-insert-before-streaming!
  "Insert a message immediately before the current streaming message.
   Used for before-agent-start injected messages, which are input context
   that belongs above the assistant response. Falls back to appending when
   no streaming message exists. Returns the created component (or nil)."
  [ch msg]
  (let [comp (make-component-for-msg msg @(:theme-atom ch) @(:output-pad-atom ch)
                                     @(:tools-expanded-atom ch) @(:thinking-hidden-atom ch)
                                     @(:hidden-label-atom ch))
        streaming @(:streaming-atom ch)]
    (when comp
      (let [entry (assoc msg :component comp)]
        (swap! (:messages-atom ch)
               (fn [msgs]
                 (let [idx (if streaming (max 0 (dec (count msgs))) (count msgs))]
                   (vec (concat (subvec msgs 0 idx) [entry] (subvec msgs idx))))))))
    comp))

(defn- drop-trailing-statuses
  "Pop trailing :status entries (UI-only status lines) off the end of MSGS."
  [msgs]
  (loop [msgs msgs]
    (if (= :status (:role (peek msgs)))
      (recur (pop msgs))
      msgs)))

(defn chat-history-remove-streaming-placeholder!
  "Remove the current streaming placeholder from the chat if still present.
   The placeholder is matched by IDENTITY, never by position: a tool
   execution or a consumed steering/follow-up user message can be appended
   after it, and popping the last entry would delete that message instead
   (pi: agent_end removes the streamingComponent by reference). Returns
   true when the placeholder was removed (and the streaming state cleared)."
  [ch]
  (let [streaming @(:streaming-atom ch)]
    (when streaming
      (let [removed? (volatile! false)]
        (swap! (:messages-atom ch)
               (fn [msgs]
                 (let [msgs' (drop-trailing-statuses msgs)]
                   (cond
                     (identical? (peek msgs') streaming)
                     (do (vreset! removed? true) (pop msgs'))

                     (some #(identical? % streaming) msgs')
                     (do (vreset! removed? true)
                         (vec (remove #(identical? % streaming) msgs')))

                     :else msgs))))
        (when @removed?
          (reset! (:streaming-atom ch) nil)
          true)))))

;; ─── Streaming ────────────────────────────────────────────────────────────

(defn chat-history-start-streaming!
  "Start a new streaming assistant message.
   Creates the component, appends the message map to messages-atom, and
   returns the message map (callers can use chat-history-append-* to feed it).
   Inherits the current thinking-hidden flag (pi: hideThinkingBlock)."
  [ch]
  (let [comp (am/make-assistant-message
              :text "" :thinking ""
              :theme @(:theme-atom ch)
              :output-pad @(:output-pad-atom ch)
              :hide-thinking? @(:thinking-hidden-atom ch)
              :hidden-label @(:hidden-label-atom ch))
        msg {:role :assistant :content "" :component comp :streaming? true}]
    (swap! (:messages-atom ch) conj msg)
    (reset! (:streaming-atom ch) msg)
    msg))

(defn chat-history-append-streaming-text!
  "Append text to the current streaming response.
   If there's no streaming message, creates one."
  [ch text]
  (let [msg (or @(:streaming-atom ch)
                (chat-history-start-streaming! ch))]
    (am/assistant-message-append-text! (:component msg) text)))

(defn chat-history-append-thinking-text!
  "Append text to the current thinking display.
   If there's no streaming message, creates one."
  [ch text]
  (let [msg (or @(:streaming-atom ch)
                (chat-history-start-streaming! ch))]
    (am/assistant-message-append-thinking! (:component msg) text)))

(defn chat-history-finalize-streaming!
  "Finalize the current streaming message.
   Captures the final text/thinking from the component into the message map
   and marks it non-streaming. Returns the component (or nil if no streaming)."
  [ch]
  (when-let [msg @(:streaming-atom ch)]
    (let [comp (:component msg)
          text (am/assistant-message-get-text comp)
          thinking (am/assistant-message-get-thinking comp)]
      (swap! (:messages-atom ch)
             (fn [msgs]
               (mapv (fn [m]
                       (if (identical? m msg)
                         (cond-> (assoc m :content text :streaming? false)
                           (seq thinking) (assoc :thinking thinking))
                         m))
                     msgs)))
      (reset! (:streaming-atom ch) nil)
      comp)))

(defn chat-history-finalize-thinking!
  "Clear the thinking buffer on the streaming component (no-op with new architecture
   since thinking is stored in the message component itself)."
  [_ch]
  ;; Pi doesn't separately clear thinking — it's captured in the component.
  ;; If there's a streaming component, the thinking is part of it.
  nil)

(defn chat-history-get-streaming-text
  "Get the current streaming text."
  [ch]
  (if-let [msg @(:streaming-atom ch)]
    (am/assistant-message-get-text (:component msg))
    ""))

(defn chat-history-clear-streaming!
  "Clear text and thinking from the current streaming component.
   Used on auto-retry so a retried stream starts from a blank slate."
  [ch]
  (when-let [msg @(:streaming-atom ch)]
    (am/assistant-message-set-text! (:component msg) "")
    (am/assistant-message-set-thinking! (:component msg) "")))

;; ─── Info message ─────────────────────────────────────────────────────────

(defn chat-history-set-info-msg!
  "Set or clear the info message at the top.
   Pass {:label \"...\" :content \"...\"} or nil to clear."
  [ch msg]
  (if msg
    (when-let [comp (make-info-msg msg @(:theme-atom ch) @(:output-pad-atom ch))]
      (reset! (:info-comp-atom ch) comp))
    (reset! (:info-comp-atom ch) nil)))

(defn chat-history-clear-info-msg!
  "Clear the top info message."
  [ch]
  (chat-history-set-info-msg! ch nil))

;; ─── Toggles ─────────────────────────────────────────────────────────────

(defn- kind-of
  "Get the component kind via the IComponentKind protocol.
   Every message component implements it (user/assistant/tool/bash/custom)."
  [child]
  (when (satisfies? protocols/IComponentKind child)
    (protocols/component-kind child)))

(defn chat-history-toggle-tool-expanded!
  "Toggle tool output expansion on all ToolExecutionComponent children.
   Tracks a single expansion flag (pi: toolOutputExpanded) applied to tools,
   bash executions, and the collapsible info/help banner; new tool components
   inherit the flag. Returns the new expansion state."
  [ch]
  (let [expanded? (not @(:tools-expanded-atom ch))]
    (reset! (:tools-expanded-atom ch) expanded?)
    (doseq [m @(:messages-atom ch)]
      (let [child (:component m)]
        (case (kind-of child)
          :tool (te/tool-execution-set-expanded! child expanded?)
          :bash (be/bash-execution-set-expanded! child expanded?)
          nil)))
    ;; pi: startup info banner is expandable with ctrl+o
    (when-let [info @(:info-comp-atom ch)]
      (when (cm/custom-message-collapsible? info)
        (cm/custom-message-set-expanded! info expanded?)))
    expanded?))

(defn chat-history-get-tool-expanded
  "Check if tool output is expanded (the tracked expansion flag)."
  [ch]
  @(:tools-expanded-atom ch))

(defn chat-history-set-thinking-hidden!
  "Set thinking block visibility on all assistant messages (pi:
   setHideThinkingBlock — applied to existing children; new assistant
   messages inherit the flag). Returns the value set."
  [ch hidden?]
  (let [hidden? (boolean hidden?)]
    (reset! (:thinking-hidden-atom ch) hidden?)
    (doseq [m @(:messages-atom ch)]
      (when (= (kind-of (:component m)) :assistant)
        (am/assistant-message-set-hide-thinking! (:component m) hidden?)))
    hidden?))

(defn chat-history-toggle-thinking-hidden!
  "Toggle thinking block visibility on all AssistantMessageComponent children.
   Tracks a single flag (pi: hideThinkingBlock) applied to existing messages;
   new assistant messages inherit it. Returns the new hidden state."
  [ch]
  (chat-history-set-thinking-hidden! ch (not @(:thinking-hidden-atom ch))))

(defn chat-history-get-thinking-hidden
  "Check if thinking blocks are hidden (the tracked hidden flag)."
  [ch]
  @(:thinking-hidden-atom ch))

(defn chat-history-set-hidden-thinking-label!
  "Set the label shown in place of hidden thinking blocks (pi:
   setHiddenThinkingLabel). Applies to all existing assistant messages;
   new messages inherit it. Pass nil to restore the default."
  [ch label]
  (let [label (or label "Thinking...")]
    (reset! (:hidden-label-atom ch) label)
    (doseq [m @(:messages-atom ch)]
      (when (= (kind-of (:component m)) :assistant)
        (am/assistant-message-set-hidden-label! (:component m) label))))
  nil)

;; ─── Status message (pi: showStatus) ────────────────────────────────────────

(defn chat-history-show-status!
  "Show a dim status message at the end of the chat (pi: showStatus — appends
   a Spacer(1) + dim Text to the chat container). The status is a regular
   trailing entry, not a pinned bottom line: subsequent messages append after
   it and it scrolls away with the transcript. When the trailing entry is
   already a status, its text is updated in place so repeated toggles don't
   accumulate. Status entries are UI-only — chat-history-get-messages excludes
   them, so they are never persisted."
  [ch message]
  (let [last-msg (peek @(:messages-atom ch))]
    (if (and last-msg (= :status (:role last-msg)))
      (truncated-text/truncated-text-set-text! @(:text-atom (:component last-msg))
                                               (theme/dim message))
      (chat-history-add-message! ch {:role :status :content message})))
  nil)

;; ─── Misc ─────────────────────────────────────────────────────────────────

(defn chat-history-clear!
  "Clear all messages, streaming state, and info."
  [ch]
  (reset! (:messages-atom ch) [])
  (reset! (:info-comp-atom ch) nil)
  (reset! (:streaming-atom ch) nil))

(defn chat-history-rebuild!
  "Rebuild the chat history from a new message vector (context replacement).
   Clears existing messages and streaming state, preserves the top info banner."
  [ch msgs]
  (let [info @(:info-comp-atom ch)
        info-msg (when info
                   (cond-> {:label @(:label-atom info)
                            :content @(:content-atom info)}
                     (cm/custom-message-collapsible? info)
                     (assoc :collapsed-content @(:collapsed-content-atom info)
                            :expanded-content @(:expanded-content-atom info)
                            :expanded? (cm/custom-message-get-expanded info))))]
    (chat-history-clear! ch)
    (doseq [m msgs]
      (chat-history-add-message! ch m))
    (when info-msg
      (chat-history-set-info-msg! ch info-msg))))

(defn chat-history-get-messages
  "Get all stored messages as plain maps — the data source of the chat,
   read directly from messages-atom (no component reverse-engineering).
   Includes the info banner first; excludes bash executions (!! / !) and
   status lines, which are UI-only, and strips the :component/:streaming?
   keys."
  [ch]
  (->> (concat
        (when-let [info @(:info-comp-atom ch)]
          [{:role :info
            :label @(:label-atom info)
            :content @(:content-atom info)}])
        @(:messages-atom ch))
       (remove #(#{:bash :status} (:role %)))
       (mapv #(dissoc % :component :streaming?))))

(defn chat-history-set-max-lines!
  "No-op: Pi architecture doesn't use max-lines (terminal handles viewport)."
  [_ch _n] nil)

(defn- all-message-comps
  "All message components plus the info banner component."
  [ch]
  (concat (map :component @(:messages-atom ch))
          (when-let [info @(:info-comp-atom ch)] [info])))

(defn- set-theme-on!
  "Set theme on a child based on its kind."
  [child t]
  (case (kind-of child)
    :user (um/user-message-set-theme! child t)
    :assistant (am/assistant-message-set-theme! child t)
    :tool (te/tool-execution-set-theme! child t)
    :custom (cm/custom-message-set-theme! child t)
    :bash (be/bash-execution-set-theme! child t)
    nil))

(defn- set-pad-on!
  "Set output padding on a child based on its kind."
  [child n]
  (case (kind-of child)
    :user (um/user-message-set-output-pad! child n)
    :assistant (am/assistant-message-set-output-pad! child n)
    :tool (te/tool-execution-set-output-pad! child n)
    :custom (cm/custom-message-set-output-pad! child n)
    nil))

(defn chat-history-set-theme!
  "Set the theme on all messages and the info banner."
  [ch t]
  (reset! (:theme-atom ch) t)
  (doseq [child (all-message-comps ch)]
    (set-theme-on! child t)))

(defn chat-history-set-output-pad!
  "Set horizontal padding on all messages and the info banner."
  [ch n]
  (reset! (:output-pad-atom ch) n)
  (doseq [child (all-message-comps ch)]
    (set-pad-on! child n)))

;; ─── IFocusable ─────────────────────────────────────────────────────────────

(extend-type ChatHistoryComponent
  protocols/IFocusable
  (focused [_this] false)
  (set-focused! [_this _val]))
