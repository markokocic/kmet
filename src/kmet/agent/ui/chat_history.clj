(ns kmet.agent.ui.chat-history
  "ChatHistoryComponent — thin Container wrapper that composes per-message-type components
   (UserMessageComponent, AssistantMessageComponent, ToolExecutionComponent, CustomMessageComponent) as children.
   This is the Pi architecture: a flat Container with separate component
   classes per message role, not a monolithic renderer."
  (:require [kmet.tui.protocols :as protocols]
            [kmet.tui.utils :as u]
            [kmet.tui.theme :as theme]
            [kmet.tui.components.container :as container]
            [kmet.agent.ui.user-message :as um]
            [kmet.agent.ui.assistant-message :as am]
            [kmet.agent.ui.tool-execution :as te]
            [kmet.agent.ui.custom-message :as cm]))

;; ─── Info component at top ─────────────────────────────────────────────────

(defn- make-info-msg
  "Create a CustomMessageComponent for the top info banner."
  [msg theme output-pad]
  (when msg
    (cm/make-custom-message :label (:label msg)
                            :content (:content msg "")
                            :theme theme
                            :output-pad output-pad)))

;; ─── ChatHistoryComponent record — wraps a Container ───────────────────────────────

(defrecord ChatHistoryComponent [container       ;; Container that holds message components
                        info-comp-atom  ;; atom of CustomMessageComponent or nil
                        theme-atom
                        output-pad-atom
                        streaming-atom  ;; atom of AssistantMessageComponent or nil (current streaming)
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
   Creates the appropriate component (UserMessageComponent, AssistantMessageComponent,
   ToolExecutionComponent, or CustomMessageComponent) and adds it to the container.
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
   The info message is a CustomMessageComponent component at index 0."
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

(defn- kind-of
  "Get the component kind via IComponentKind protocol, falling back to key-based
   heuristics for components that don't implement the protocol."
  [child]
  (if (satisfies? protocols/IComponentKind child)
    (protocols/component-kind child)
    ;; Fallback for components that don't implement the protocol yet
    (cond
      (and (map? child) (contains? child :name-atom) (contains? child :expanded-atom)) :tool
      (and (map? child) (contains? child :thinking-text-atom) (contains? child :hide-thinking-atom)) :assistant
      (and (map? child) (contains? child :text-atom) (not (contains? child :thinking-text-atom))) :user
      (and (map? child) (contains? child :label-atom) (contains? child :content-atom)
           (not (contains? child :name-atom))) :custom
      :else nil)))

(defn- toggle-expanded!
  "Toggle the expanded state of a tool component."
  [child]
  (let [current @(:expanded-atom child)]
    (te/tool-execution-set-expanded! child (not current))))

(defn- toggle-hide-thinking!
  "Toggle the thinking-hidden state of an assistant component."
  [child]
  (let [current @(:hide-thinking-atom child)]
    (am/assistant-message-set-hide-thinking! child (not current))))

(defn chat-history-toggle-tool-expanded!
  "Toggle tool output expansion on all ToolExecutionComponent children."
  [ch]
  (doseq [child @(:children-atom ch)]
    (when (= (kind-of child) :tool)
      (toggle-expanded! child))))

(defn chat-history-get-tool-expanded
  "Check if tools are expanded. Returns false if no ToolExecutionComponent children."
  [ch]
  (boolean
    (some (fn [child]
            (when (= (kind-of child) :tool)
              @(:expanded-atom child)))
          @(:children-atom ch))))

(defn chat-history-toggle-thinking-hidden!
  "Toggle thinking block visibility on all AssistantMessageComponent children."
  [ch]
  (doseq [child @(:children-atom ch)]
    (when (= (kind-of child) :assistant)
      (toggle-hide-thinking! child))))

(defn chat-history-get-thinking-hidden
  "Check if thinking is hidden. Returns false if no AssistantMessageComponent children."
  [ch]
  (boolean
    (some (fn [child]
            (when (= (kind-of child) :assistant)
              @(:hide-thinking-atom child)))
          @(:children-atom ch))))

;; ─── Misc ─────────────────────────────────────────────────────────────────

(defn chat-history-clear!
  "Clear all messages, streaming state, and info."
  [ch]
  (container/container-clear (:container ch))
  (reset! (:children-atom ch) [])
  (reset! (:info-comp-atom ch) nil)
  (reset! (:streaming-atom ch) nil))

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
