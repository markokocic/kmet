(ns kmet.app.ui.chat-history
  "ChatHistoryComponent — thin Container wrapper that composes per-message-type components
   (UserMessageComponent, AssistantMessageComponent, ToolExecutionComponent, CustomMessageComponent) as children.
   This is the Pi architecture: a flat Container with separate component
   classes per message role, not a monolithic renderer."
  (:require [clojure.string :as str]
            [kmet.tui.protocols :as protocols]
            [kmet.tui.utils :as u]
            [kmet.tui.theme :as theme]
            [kmet.tui.components.container :as container]
            [kmet.tui.components.spacer :as spacer]
            [kmet.tui.components.text :as text]
            [kmet.app.ui.user-message :as um]
            [kmet.app.ui.assistant-message :as am]
            [kmet.app.ui.tool-execution :as te]
            [kmet.app.ui.custom-message :as cm]
            [kmet.app.ui.bash-execution :as be]))

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

;; ─── ChatHistoryComponent record — wraps a Container ───────────────────────────────

(defrecord ChatHistoryComponent [container       ;; Container that holds message components
                        info-comp-atom  ;; atom of CustomMessageComponent or nil
                        theme-atom
                        output-pad-atom
                        streaming-atom  ;; atom of AssistantMessageComponent or nil (current streaming)
                        children-atom   ;; atom of vec (parallel to container children for iteration)
                        status-line-atom  ;; atom of StatusLine (bottom status message) or nil
                        tools-expanded-atom   ;; flag: tool output expanded (pi: toolOutputExpanded)
                        thinking-hidden-atom] ;; flag: thinking blocks hidden (pi: hideThinkingBlock)
  protocols/IComponent

  (render [this width]
    (protocols/render container width))

  (handle-input [this data]
    (protocols/handle-input container data))

  (invalidate [this]
    (protocols/invalidate container)))

;; ─── Construction ──────────────────────────────────────────────────────────

(defn make-chat-history
  "Create a ChatHistoryComponent component (Pi-style: Container of message components).
   Options:
     :theme       — Theme record (default dark-theme)
     :output-pad  — horizontal padding for boxed messages (default 1)"
  [& {:keys [theme output-pad]
      :or {theme theme/dark-theme output-pad 1}}]
  (let [c (container/make-container)]
    (map->ChatHistoryComponent {:container c
                       :info-comp-atom (atom nil)
                       :theme-atom (atom theme)
                       :output-pad-atom (atom output-pad)
                       :streaming-atom (atom nil)
                       :children-atom (atom [])
                       :status-line-atom (atom nil)
                       :tools-expanded-atom (atom false)
                       :thinking-hidden-atom (atom false)})))

;; ─── Adding messages ──────────────────────────────────────────────────────

(defn- content->display-text
  "Convert message content (string or block vector) to a display string.
   Text blocks are joined; image blocks render as [image <mime-type>]
   placeholders (pi renders image thumbnails)."
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
          (or (:text b) ""))))))

(defn- make-component-for-msg
  "Create the appropriate component for a message map.
   For tool messages, looks up render functions from the tool registry.
   Assistant messages inherit the current thinking-hidden flag and tool
   components the current expansion flag (pi: hideThinkingBlock / toolOutputExpanded)."
  [msg theme output-pad tools-expanded? thinking-hidden?]
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
                 :finalized? true)
    :tool (te/make-tool-execution
            :name (:name msg "")
            :args (:args msg {})
            :content (:content msg "")
            :is-error (:is-error msg false)
            :theme theme
            :output-pad output-pad
            :expanded? tools-expanded?)
    :bash (:component msg)  ;; Already-constructed BashExecutionComponent
    :info (cm/make-custom-message :label (:label msg)
                                  :content (:content msg "")
                                  :theme theme
                                  :output-pad output-pad)
    nil))

(defn chat-history-add-message!
  "Add a message to the chat history.
   Creates the appropriate component (UserMessageComponent, AssistantMessageComponent,
   ToolExecutionComponent, or CustomMessageComponent) and adds it to the container.
   Pi-style: adds a Spacer(1) before user messages when the container is non-empty.
   Auto-scrolls to bottom. Returns the created component (or nil)."
  [ch msg]
  (let [comp (make-component-for-msg msg @(:theme-atom ch) @(:output-pad-atom ch)
                                     @(:tools-expanded-atom ch) @(:thinking-hidden-atom ch))]
    (when comp
      ;; Pi-style: add Spacer(1) before user messages when container is non-empty
      (when (and (= :user (:role msg))
                 (seq @(:children-atom ch)))
        (let [s (spacer/make-spacer 1)]
          (container/container-add-child (:container ch) s)
          (swap! (:children-atom ch) conj s)))
      (container/container-add-child (:container ch) comp)
      (swap! (:children-atom ch) conj comp))
    comp))

(defn chat-history-add-messages!
  "Add multiple messages at once."
  [ch msgs]
  (doseq [m msgs]
    (chat-history-add-message! ch m)))

(defn chat-history-insert-before-streaming!
  "Insert a message component immediately before the current streaming
   placeholder (used for before-agent-start injected messages, which are
   input context that belongs above the assistant response). Falls back to
   appending when no streaming placeholder exists."
  [ch msg]
  (let [comp (make-component-for-msg msg @(:theme-atom ch) @(:output-pad-atom ch)
                                     @(:tools-expanded-atom ch) @(:thinking-hidden-atom ch))
        streaming @(:streaming-atom ch)]
    (when comp
      (let [children @(:children-atom ch)
            idx (if streaming
                  (or (first (keep-indexed (fn [i c] (when (identical? c streaming) i)) children))
                      (count children))
                  (count children))
            new-children (vec (concat (subvec children 0 idx) [comp] (subvec children idx)))]
        (container/container-set-children! (:container ch) new-children)
        (reset! (:children-atom ch) new-children)))
    comp))

(defn chat-history-remove-last!
  "Remove the last message from history.
   A trailing status line is not a message, so it is removed first.
   Also removes any preceding Spacer(1) added for user messages."
  [ch]
  (let [children @(:children-atom ch)]
    (when (seq children)
      ;; Remove a trailing status line so the real last message is popped
      (when (identical? (last children) @(:status-line-atom ch))
        (container/container-remove-child (:container ch) @(:status-line-atom ch))
        (swap! (:children-atom ch) pop)
        (reset! (:status-line-atom ch) nil))
      (let [children @(:children-atom ch)]
        (when (seq children)
          (let [last-child (last children)]
            (container/container-remove-child (:container ch) last-child)
            (swap! (:children-atom ch) pop)
            ;; If the new last child doesn't satisfy IComponentKind, it's a
            ;; Spacer(1) added before a user message — remove it too.
            (let [remaining @(:children-atom ch)]
              (when (and (seq remaining)
                         (not (satisfies? protocols/IComponentKind (last remaining))))
                (let [spacer (last remaining)]
                  (container/container-remove-child (:container ch) spacer)
                  (swap! (:children-atom ch) pop))))))))))

;; ─── Streaming ────────────────────────────────────────────────────────────

(defn chat-history-start-streaming!
  "Start a new streaming assistant message.
   Creates the component, adds it to the container, and returns it
   so the caller can call append-text!/append-thinking!/finalize! on it.
   Inherits the current thinking-hidden flag (pi: hideThinkingBlock)."
  [ch]
  (let [comp (am/make-assistant-message
               :text "" :thinking ""
               :theme @(:theme-atom ch)
               :output-pad @(:output-pad-atom ch)
               :hide-thinking? @(:thinking-hidden-atom ch)
               :finalized? false)]
    (container/container-add-child (:container ch) comp)
    (swap! (:children-atom ch) conj comp)
    (reset! (:streaming-atom ch) comp)
    comp))

(defn chat-history-append-streaming-text!
  "Append text to the current streaming response.
   If there's no streaming component, creates one."
  [ch text]
  (let [comp (or @(:streaming-atom ch)
                 (chat-history-start-streaming! ch))]
    (am/assistant-message-append-text! comp text)))

(defn chat-history-append-thinking-text!
  "Append text to the current thinking display.
   If there's no streaming component, creates one."
  [ch text]
  (let [comp (or @(:streaming-atom ch)
                 (chat-history-start-streaming! ch))]
    (am/assistant-message-append-thinking! comp text)))

(defn chat-history-finalize-streaming!
  "Finalize the current streaming message.
   Captures any thinking text into the message and removes cursor.
   Returns the component (or nil if no streaming)."
  [ch]
  (when-let [comp @(:streaming-atom ch)]
    (am/assistant-message-finalize! comp)
    (reset! (:streaming-atom ch) nil)
    comp))

(defn chat-history-finalize-thinking!
  "Clear the thinking buffer on the streaming component (no-op with new architecture
   since thinking is stored in the message component itself)."
  [ch]
  ;; Pi doesn't separately clear thinking — it's captured in the component.
  ;; If there's a streaming component, the thinking is part of it.
  nil)

(defn chat-history-get-streaming-text
  "Get the current streaming text."
  [ch]
  (if-let [comp @(:streaming-atom ch)]
    (am/assistant-message-get-text comp)
    ""))

(defn chat-history-clear-streaming!
  "Clear text and thinking from the current streaming component.
   Used on auto-retry so a retried stream starts from a blank slate."
  [ch]
  (when-let [comp @(:streaming-atom ch)]
    (am/assistant-message-set-text! comp "")
    (am/assistant-message-set-thinking! comp "")))

;; ─── Info message ─────────────────────────────────────────────────────────

(defn- container-prepend-child
  "Add a child to the front of a container's children list."
  [c child]
  (swap! (:children c) (fn [v] (into [child] v))))

(defn chat-history-set-info-msg!
  "Set or clear the info message at the top.
   Pass {:label \"...\" :content \"...\"} or nil to clear.
   The info message is a CustomMessageComponent component at index 0.
   Keeps children-atom parallel with the container children so kind-based
   dispatch (theme, toggles, persistence) sees the banner."
  [ch msg]
  (when-let [old @(:info-comp-atom ch)]
    (container/container-remove-child (:container ch) old)
    (swap! (:children-atom ch) (fn [v] (vec (remove #(identical? % old) v)))))
  (if msg
    (let [comp (make-info-msg msg @(:theme-atom ch) @(:output-pad-atom ch))]
      (when comp
        (container-prepend-child (:container ch) comp)
        (swap! (:children-atom ch) (fn [v] (into [comp] v)))
        (reset! (:info-comp-atom ch) comp)))
    (reset! (:info-comp-atom ch) nil)))

(defn chat-history-clear-info-msg!
  "Clear the top info message."
  [ch]
  (chat-history-set-info-msg! ch nil))

;; ─── Toggles ─────────────────────────────────────────────────────────────

(defn- kind-of
  "Get the component kind via the IComponentKind protocol.
   Every chat child implements it (user/assistant/tool/bash/custom/status)."
  [child]
  (when (satisfies? protocols/IComponentKind child)
    (protocols/component-kind child)))

(defn- set-hide-thinking!
  "Set the thinking-hidden state of an assistant component."
  [child hidden?]
  (am/assistant-message-set-hide-thinking! child hidden?))

(defn chat-history-toggle-tool-expanded!
  "Toggle tool output expansion on all ToolExecutionComponent children.
   Tracks a single expansion flag (pi: toolOutputExpanded) applied to tools,
   bash executions, and the collapsible info/help banner; new tool components
   inherit the flag. Returns the new expansion state."
  [ch]
  (let [expanded? (not @(:tools-expanded-atom ch))]
    (reset! (:tools-expanded-atom ch) expanded?)
    (doseq [child @(:children-atom ch)]
      (case (kind-of child)
        :tool (te/tool-execution-set-expanded! child expanded?)
        :bash (be/bash-execution-set-expanded! child expanded?)
        nil))
    ;; pi: startup info banner is expandable with ctrl+o
    (when-let [info @(:info-comp-atom ch)]
      (when (cm/custom-message-collapsible? info)
        (cm/custom-message-set-expanded! info expanded?)))
    expanded?))

(defn chat-history-get-tool-expanded
  "Check if tool output is expanded (the tracked expansion flag)."
  [ch]
  @(:tools-expanded-atom ch))

(defn chat-history-toggle-thinking-hidden!
  "Toggle thinking block visibility on all AssistantMessageComponent children.
   Tracks a single flag (pi: hideThinkingBlock) applied to existing messages;
   new assistant messages inherit it. Returns the new hidden state."
  [ch]
  (let [hidden? (not @(:thinking-hidden-atom ch))]
    (reset! (:thinking-hidden-atom ch) hidden?)
    (doseq [child @(:children-atom ch)]
      (when (= (kind-of child) :assistant)
        (set-hide-thinking! child hidden?)))
    hidden?))

(defn chat-history-get-thinking-hidden
  "Check if thinking blocks are hidden (the tracked hidden flag)."
  [ch]
  @(:thinking-hidden-atom ch))

;; ─── Status message (pi: showStatus) ────────────────────────────────────────

;; StatusLine — bottom-of-chat status line: a dim text under a Spacer(1).
;; Implements IComponentKind with kind nil so kind-based dispatch (toggles,
;; message persistence, theme application) skips it.
(defrecord StatusLine [spacer-atom text-atom]
  protocols/IComponent
  (render [this width]
    (into [] (concat (protocols/render @spacer-atom width)
                     (protocols/render @text-atom width))))
  (handle-input [_this _data] nil)
  (invalidate [this]
    (protocols/invalidate @spacer-atom)
    (protocols/invalidate @text-atom)))

(extend-type StatusLine
  protocols/IComponentKind
  (component-kind [_] nil))

(defn chat-history-show-status!
  "Show a dim status message at the bottom of the chat (pi: showStatus).
   When the last child is already the previous status line, its text is
   updated in place instead of appending, so repeated toggles don't
   accumulate status lines."
  [ch message]
  (let [children @(:children-atom ch)
        last-child (when (seq children) (peek children))]
    (if (identical? last-child @(:status-line-atom ch))
      (text/text-set! @(:text-atom @(:status-line-atom ch)) (theme/dim message))
      (let [line (map->StatusLine {:spacer-atom (atom (spacer/make-spacer 1))
                                   :text-atom (atom (text/make-text (theme/dim message) 1 0))})]
        (container/container-add-child (:container ch) line)
        (swap! (:children-atom ch) conj line)
        (reset! (:status-line-atom ch) line))))
  nil)

;; ─── Misc ─────────────────────────────────────────────────────────────────

(defn chat-history-clear!
  "Clear all messages, streaming state, and info."
  [ch]
  (container/container-clear (:container ch))
  (reset! (:children-atom ch) [])
  (reset! (:info-comp-atom ch) nil)
  (reset! (:streaming-atom ch) nil)
  (reset! (:status-line-atom ch) nil))

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

(defn- child->msg
  "Convert a component child back to a message map."
  [child]
  (case (kind-of child)
    :user {:role :user :content @(:text-atom child)}
    :assistant (let [m {:role :assistant :content @(:text-atom child)}]
                 (if-let [t @(:thinking-text-atom child)]
                   (assoc m :thinking t)
                   m))
    :tool {:role :tool :name @(:name-atom child)
           :content @(:content-atom child)
           :is-error @(:is-error-atom child)}
    :custom {:role :info :label @(:label-atom child)
             :content @(:content-atom child)}
    nil))

(defn chat-history-get-messages
  "Get all stored messages (converts children back to message maps for backward compat)."
  [ch]
  (vec (keep child->msg @(:children-atom ch))))

(defn chat-history-set-max-lines!
  "No-op: Pi architecture doesn't use max-lines (terminal handles viewport)."
  [ch _n] nil)

(defn- apply-to-kind!
  "Apply a function to all children of a given kind."
  [ch kind f]
  (doseq [child @(:children-atom ch)]
    (when (= (kind-of child) kind)
      (f child))))

(defn- set-theme-on!
  "Set theme on a child based on its kind."
  [child t]
  (case (kind-of child)
    :user (um/user-message-set-theme! child t)
    :assistant (am/assistant-message-set-theme! child t)
    :tool (te/tool-execution-set-theme! child t)
    :custom (cm/custom-message-set-theme! child t)
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
  "Set the theme on all children."
  [ch t]
  (reset! (:theme-atom ch) t)
  (apply-to-kind! ch :user #(um/user-message-set-theme! % t))
  (apply-to-kind! ch :assistant #(am/assistant-message-set-theme! % t))
  (apply-to-kind! ch :tool #(te/tool-execution-set-theme! % t))
  (apply-to-kind! ch :custom #(cm/custom-message-set-theme! % t)))

(defn chat-history-set-output-pad!
  "Set horizontal padding on all children."
  [ch n]
  (reset! (:output-pad-atom ch) n)
  (apply-to-kind! ch :user #(um/user-message-set-output-pad! % n))
  (apply-to-kind! ch :assistant #(am/assistant-message-set-output-pad! % n))
  (apply-to-kind! ch :tool #(te/tool-execution-set-output-pad! % n))
  (apply-to-kind! ch :custom #(cm/custom-message-set-output-pad! % n)))

;; ─── IFocusable ─────────────────────────────────────────────────────────────

(extend-type ChatHistoryComponent
  protocols/IFocusable
  (focused [this] false)
  (set-focused! [this val]))
