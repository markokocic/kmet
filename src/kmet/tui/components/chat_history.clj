(ns kmet.tui.components.chat-history
  "ChatHistory — thin Container wrapper that composes per-message-type components
   (UserMessage, AssistantMessage, ToolExecution, CustomMessage) as children.
   This is the Pi architecture: a flat Container with separate component
   classes per message role, not a monolithic renderer."
  (:require [kmet.tui.protocols :as protocols]
            [kmet.tui.utils :as u]
            [kmet.tui.theme :as theme]
            [kmet.tui.components.container :as container]
            [kmet.tui.components.user-message :as um]
            [kmet.tui.components.assistant-message :as am]
            [kmet.tui.components.tool-execution :as te]
            [kmet.tui.components.custom-message :as cm]))

;; ─── Info component at top ─────────────────────────────────────────────────

(defn- make-info-msg
  "Create a CustomMessage for the top info banner."
  [msg theme output-pad]
  (when msg
    (cm/make-custom-message :label (:label msg)
                            :content (:content msg "")
                            :theme theme
                            :output-pad output-pad)))

;; ─── ChatHistory record — wraps a Container ───────────────────────────────

(defrecord ChatHistory [container       ;; Container that holds message components
                        info-comp-atom  ;; atom of CustomMessage or nil
                        theme-atom
                        output-pad-atom
                        streaming-atom  ;; atom of AssistantMessage or nil (current streaming)
                        children-atom]  ;; atom of vec (parallel to container children for iteration)
  protocols/IComponent

  (render [this width]
    (protocols/render container width))

  (handle-input [this data]
    (protocols/handle-input container data))

  (invalidate [this]
    (protocols/invalidate container)))

;; ─── Construction ──────────────────────────────────────────────────────────

(defn make-chat-history
  "Create a ChatHistory component (Pi-style: Container of message components).
   Options:
     :theme       — Theme record (default dark-theme)
     :output-pad  — horizontal padding for boxed messages (default 1)"
  [& {:keys [theme output-pad]
      :or {theme theme/dark-theme output-pad 1}}]
  (let [c (container/make-container)]
    (map->ChatHistory {:container c
                       :info-comp-atom (atom nil)
                       :theme-atom (atom theme)
                       :output-pad-atom (atom output-pad)
                       :streaming-atom (atom nil)
                       :children-atom (atom [])})))

;; ─── Adding messages ──────────────────────────────────────────────────────

(defn- make-component-for-msg
  "Create the appropriate component for a message map."
  [msg theme output-pad]
  (case (:role msg)
    :user (um/make-user-message :text (:content msg "") :theme theme :output-pad output-pad)
    :assistant (am/make-assistant-message
                 :text (:content msg "")
                 :thinking (:thinking msg "")
                 :theme theme
                 :output-pad output-pad
                 :hide-thinking? false
                 :finalized? true)
    :tool (te/make-tool-execution
            :name (:name msg "")
            :content (:content msg "")
            :is-error (:is-error msg false)
            :theme theme
            :output-pad output-pad
            :expanded? false)
    :info (cm/make-custom-message :label (:label msg)
                                  :content (:content msg "")
                                  :theme theme
                                  :output-pad output-pad)
    nil))

(defn chat-history-add-message!
  "Add a message to the chat history.
   Creates the appropriate component (UserMessage, AssistantMessage,
   ToolExecution, or CustomMessage) and adds it to the container.
   Auto-scrolls to bottom."
  [ch msg]
  (let [comp (make-component-for-msg msg @(:theme-atom ch) @(:output-pad-atom ch))]
    (when comp
      (container/container-add-child (:container ch) comp)
      (swap! (:children-atom ch) conj comp))))

(defn chat-history-add-messages!
  "Add multiple messages at once."
  [ch msgs]
  (doseq [m msgs]
    (chat-history-add-message! ch m)))

(defn chat-history-remove-last!
  "Remove the last message from history."
  [ch]
  (let [children @(:children-atom ch)]
    (when (seq children)
      (let [last-child (last children)]
        (container/container-remove-child (:container ch) last-child)
        (swap! (:children-atom ch) pop)))))

;; ─── Streaming ────────────────────────────────────────────────────────────

(defn chat-history-start-streaming!
  "Start a new streaming assistant message.
   Creates the component, adds it to the container, and returns it
   so the caller can call append-text!/append-thinking!/finalize! on it."
  [ch]
  (let [comp (am/make-assistant-message
               :text "" :thinking ""
               :theme @(:theme-atom ch)
               :output-pad @(:output-pad-atom ch)
               :hide-thinking? false
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

;; ─── Info message ─────────────────────────────────────────────────────────

(defn- container-prepend-child
  "Add a child to the front of a container's children list."
  [c child]
  (swap! (:children c) (fn [v] (into [child] v))))

(defn chat-history-set-info-msg!
  "Set or clear the info message at the top.
   Pass {:label \"...\" :content \"...\"} or nil to clear.
   The info message is a CustomMessage component at index 0."
  [ch msg]
  (when-let [old @(:info-comp-atom ch)]
    (container/container-remove-child (:container ch) old))
  (if msg
    (let [comp (make-info-msg msg @(:theme-atom ch) @(:output-pad-atom ch))]
      (when comp
        (container-prepend-child (:container ch) comp)
        (reset! (:info-comp-atom ch) comp)))
    (reset! (:info-comp-atom ch) nil)))

(defn chat-history-clear-info-msg!
  "Clear the top info message."
  [ch]
  (chat-history-set-info-msg! ch nil))

;; ─── Toggles ─────────────────────────────────────────────────────────────

(defn- tool-execution?
  "Check if a component is a ToolExecution (has :name-atom and :expanded-atom)."
  [child]
  (and (map? child) (contains? child :name-atom) (contains? child :expanded-atom)))

(defn- assistant-message?
  "Check if a component is an AssistantMessage (has :thinking-text-atom and :hide-thinking-atom)."
  [child]
  (and (map? child) (contains? child :thinking-text-atom) (contains? child :hide-thinking-atom)))

(defn- user-message?
  "Check if a component is a UserMessage (has :text-atom and :output-pad-atom but not :thinking-text-atom)."
  [child]
  (and (map? child) (contains? child :text-atom) (not (contains? child :thinking-text-atom))))

(defn- custom-message?
  "Check if a component is a CustomMessage (has :label-atom and :content-atom but not :name-atom)."
  [child]
  (and (map? child) (contains? child :label-atom) (contains? child :content-atom)
       (not (contains? child :name-atom))))

(defn chat-history-toggle-tool-expanded!
  "Toggle tool output expansion on all ToolExecution children."
  [ch]
  (doseq [child @(:children-atom ch)]
    (when (tool-execution? child)
      (let [current @(:expanded-atom child)]
        (te/tool-execution-set-expanded! child (not current))))))

(defn chat-history-get-tool-expanded
  "Check if tools are expanded. Returns false if no ToolExecution children."
  [ch]
  (let [val (some (fn [child]
                    (when (tool-execution? child)
                      @(:expanded-atom child)))
                  @(:children-atom ch))]
    (boolean val)))

(defn chat-history-toggle-thinking-hidden!
  "Toggle thinking block visibility on all AssistantMessage children."
  [ch]
  (doseq [child @(:children-atom ch)]
    (when (assistant-message? child)
      (let [current @(:hide-thinking-atom child)]
        (am/assistant-message-set-hide-thinking! child (not current))))))

(defn chat-history-get-thinking-hidden
  "Check if thinking is hidden. Returns false if no AssistantMessage children."
  [ch]
  (let [val (some (fn [child]
                    (when (assistant-message? child)
                      @(:hide-thinking-atom child)))
                  @(:children-atom ch))]
    (boolean val)))

;; ─── Misc ─────────────────────────────────────────────────────────────────

(defn chat-history-clear!
  "Clear all messages, streaming state, and info."
  [ch]
  (container/container-clear (:container ch))
  (reset! (:children-atom ch) [])
  (reset! (:info-comp-atom ch) nil)
  (reset! (:streaming-atom ch) nil))

(defn chat-history-get-messages
  "Get all stored messages (converts children back to message maps for backward compat)."
  [ch]
  (vec (keep (fn [child]
               (cond
                 (user-message? child)
                 {:role :user :content @(:text-atom child)}
                 (assistant-message? child)
                 (let [m {:role :assistant :content @(:text-atom child)}]
                   (if-let [t @(:thinking-text-atom child)]
                     (assoc m :thinking t)
                     m))
                 (tool-execution? child)
                 {:role :tool :name @(:name-atom child)
                  :content @(:content-atom child)
                  :is-error @(:is-error-atom child)}
                 (custom-message? child)
                 {:role :info :label @(:label-atom child)
                  :content @(:content-atom child)}
                 :else nil))
             @(:children-atom ch))))

(defn chat-history-set-max-lines!
  "No-op: Pi architecture doesn't use max-lines (terminal handles viewport)."
  [ch _n] nil)

(defn chat-history-set-theme!
  "Set the theme on all children."
  [ch t]
  (reset! (:theme-atom ch) t)
  (doseq [child @(:children-atom ch)]
    (cond
      (user-message? child) (um/user-message-set-theme! child t)
      (assistant-message? child) (am/assistant-message-set-theme! child t)
      (tool-execution? child) (te/tool-execution-set-theme! child t)
      (custom-message? child) (cm/custom-message-set-theme! child t))))

(defn chat-history-set-output-pad!
  "Set horizontal padding on all children."
  [ch n]
  (reset! (:output-pad-atom ch) n)
  (doseq [child @(:children-atom ch)]
    (cond
      (user-message? child) (um/user-message-set-output-pad! child n)
      (assistant-message? child) (am/assistant-message-set-output-pad! child n)
      (tool-execution? child) (te/tool-execution-set-output-pad! child n)
      (custom-message? child) (cm/custom-message-set-output-pad! child n))))

;; ─── IFocusable ─────────────────────────────────────────────────────────────

(extend-type ChatHistory
  protocols/IFocusable
  (focused [this] false)
  (set-focused! [this val]))
