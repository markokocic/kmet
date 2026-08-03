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
   "Fired periodically while a tool is executing (progress ping).
    Payload: :tool-call-id."

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
   :user-bash
   "Fired when the user runs a bash command (!/!!).
    Payload: :command, :exclude-from-context?, :cwd."

   ;; ─── Queue events (emitted by loop.clj) ──────────────────────────────
   :queue-update
   "Fired when steering/follow-up queues change.
    Payload: :steering, :follow-up."

   :model-select
   "Model was changed (pi: model_select).
    Payload: :model, :previous-model, :source (:set | :cycle)."

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
   Runs all callbacks in a doseq (synchronous)."
  [event]
  (let [type (:type event)
        listeners (get @event-listeners type)]
    (when listeners
      (doseq [[_ cb] listeners]
        (try
          (cb event)
          (catch Exception e
            (binding [*out* *err*]
              (println "Warning: extension event handler error:" (ex-message e)))))))))

(defn get-event-types
  "List all registered event types."
  []
  (keys @event-listeners))
