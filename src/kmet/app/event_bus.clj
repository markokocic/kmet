(ns kmet.app.event-bus
  "Agent loop event vocabulary + extension event bus.

   Defines the canonical set of event types emitted by the agent loop
   (kmet.app.loop/run-agent-turn) and routed to the UI (:on-event callback)
   and the extension system (emit-event!).

   Mirrors pi's extension event types — see alignment.md, Appendix: Event
   Type Vocabulary — and pi's core/event-bus.js.")

(def event-types
  "Map of event type keyword → description. The canonical vocabulary.
   Every listed event is emitted by the loop, the interactive mode, or the
   app — see each entry's description for the emitting path."
  {:agent-start
   "Fired once per user submission, before the first LLM call."

   :agent-end
   "Fired when the agent loop finishes (success or error).
    Payload: :messages (messages added during this loop), :error (optional)."

   :turn-start
   "Fired before each LLM call.
    Payload: :turn-index."

   :turn-end
   "Fired after a turn completes (LLM call plus any tool execution).
    Payload: :message, :tool-results."

   :message-start
   "Fired when a message is added to the context, or when assistant
    streaming begins.
    Payload: :message."

   :message-update
   "Fired for each streaming delta (assistant only).
    Payload: :message (partial), :delta
    {:type :text | :thinking | :tool-call ...}."

   :message-end
   "Fired when an assistant message is finalized and added to context.
    Payload: :message."

   :tool-execution-start
   "Fired when a tool execution begins.
    Payload: :tool-call-id, :tool-name, :args."

   :tool-execution-update
   "Fired when a streaming tool emits partial output (e.g. bash live output).
    Payload: :tool-call-id, :content, :is-partial."

   :tool-execution-end
   "Fired when a tool execution completes.
    Payload: :tool-call-id, :tool-name, :args, :result, :is-error."

   :status
   "UI status change.
    Payload: :status (:idle :thinking :executing :error)."

   :error
   "Unhandled error inside the agent loop.
    Payload: :message."

   ;; ─── App-level events (emitted by the interactive mode, not the loop) ──
   :session-start
   "Fired once after the interactive TUI is built and the extension UI
    registry is live, before the render loop starts (pi: session_start).
    Payload: :reason (:new | :resume | :continue), :previous-session-file
    (optional). Extensions use this to set up widgets, statuses, footers,
    and custom editors."

   :user-bash
   "Fired when the user runs a bash command (!/!!).
    Payload: :command, :exclude-from-context?, :cwd."

   :session-before-tree
   "Fired before a session-tree navigation branches (pi: session_before_tree).
    Payload: :preparation {:target-id :old-leaf-id :common-ancestor-id
    :entries-to-summarize :user-wants-summary :custom-instructions
    :replace-instructions :label}, :signal (abort atom). Handlers may return
    a map: {:cancel true} aborts the navigation, {:summary str :details …}
    (when :user-wants-summary) supplies the branch summary, and
    :custom-instructions/:label override the inputs."

   :session-tree
   "Fired after a session-tree navigation branched (pi: session_tree).
    Payload: :new-leaf-id, :old-leaf-id, :summary-entry (optional),
    :from-extension? (when the summary came from an extension)."

   ;; ─── Queue events (emitted by loop.clj) ──────────────────────────────
   :queue-update
   "Fired when steering/follow-up queues change.
    Payload: :steering, :follow-up."

   :model-select
   "Model was changed (pi: model_select).
    Payload: :model, :previous-model, :source (:set | :cycle)."

   :thinking-level-select
   "Thinking level changed (pi: thinking_level_select, emitted by
    loop/set-thinking-level! on an actual change).
    Payload: :level."

   :session-info-changed
   "Session display name changed (pi: session_info_changed, emitted by the
    /name command).
    Payload: :session-file, :name."

   :context-replaced
   "Fired when prepareNextTurn replaces the conversation context.
    Payload: :messages — the new conversation messages."

   :auto-retry-start
   "Fired before a retry attempt's backoff sleep when a transient LLM error
    is detected (pi: auto_retry_start).
    Payload: :attempt, :max-attempts, :delay-ms, :error-message."

   :auto-retry-end
   "Fired when retries finish — success, exhausted, or cancelled.
    Payload: :success, :attempt, :final-error (on failure)."

   :compaction-start
   "Fired before session compaction begins (pi: compaction_start).
    Payload: :reason (:manual | :threshold | :overflow | :auto)."

   :compaction-end
   "Fired when compaction finishes (pi: compaction_end).
    Payload: :reason, :result (true when compaction happened), :aborted
    (true when the user cancelled mid-compaction — session untouched)."

   :agent-settled
   "Fired when the agent run is fully settled — immediately after :agent-end
    on every run exit (success, error, timeout, or cancel). The agent is idle
    and no further events for this run will be emitted (pi: agent_settled,
    emitted from a finally block after the run)."})

(defn known-event-type?
  "True if the keyword is part of the documented vocabulary."
  [type]
  (contains? event-types type))

;; ─── Extension event bus ──────────────────────────────────────────────────
;; Global listener registry. Extensions register callbacks with on-event;
;; the agent loop and app routes events through emit-event!.

(defonce ^:private event-listeners (atom {}))

(defn on-event
  "Register a callback for an event type.
   event-type — keyword from kmet.app.event-bus/event-types
                (e.g. :agent-start, :turn-start, :message-update,
                 :tool-execution-start, :user-bash, :status)
   callback   — (fn [event-map])
   Returns a deregister function."
  [event-type callback]
  (let [id (random-uuid)]
    (swap! event-listeners update event-type assoc id callback)
    (fn [] (swap! event-listeners update event-type dissoc id))))

(defn clear-event-listeners!
  "Remove all event listeners (for testing)."
  []
  (reset! event-listeners {}))

(defn emit-event!
  "Emit an event to all registered listeners.
   event — map with :type keyword and any additional data.
   Returns the last non-nil handler result (pi: runner.emit — the tree
   navigation reads :session-before-tree results: :cancel, :summary, ...)."
  [event]
  (let [type (:type event)
        listeners (get @event-listeners type)]
    (when listeners
      (reduce (fn [acc [_ cb]]
                (let [result (try
                               (cb event)
                               (catch Exception e
                                 (binding [*out* *err*]
                                   (println "Warning: extension event handler error:" (ex-message e)))
                                 nil))]
                  (if (some? result) result acc)))
              nil
              listeners))))

(defn get-event-types
  "List all registered event types."
  []
  (keys @event-listeners))
