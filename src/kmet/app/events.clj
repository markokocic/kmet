(ns kmet.app.events
  "Agent loop event vocabulary.

   Defines the canonical set of event types emitted by the agent loop
   (kmet.app.loop/run-agent-turn) and routed to the UI (:on-event callback)
   and the extension system (kmet.app.skills/emit-event!).

   Mirrors pi's extension event types — see alignment.md, Appendix: Event
   Type Vocabulary.")

(def event-types
  "Map of event type keyword → description. The canonical vocabulary.
   Events marked \"reserved\" are defined for future phases and not yet
   emitted by the loop."
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

   ;; ─── App-level events (emitted by core.clj, not the loop) ────────────
   :user-bash
   "Fired when the user runs a bash command (!/!!).
    Payload: :command, :exclude-from-context?, :cwd."

   ;; ─── Queue events (emitted by loop.clj) ──────────────────────────────
   :queue-update
   "Fired when steering/follow-up queues change.
    Payload: :steering, :follow-up."

   ;; ─── Reserved for future phases ───────────────────────────────────────
   :model-select
   "Model was changed. Reserved for Phase 3.
    Payload: :model, :previous-model, :source."

   :auto-retry-start
   "Fired before a retry attempt's backoff sleep when a transient LLM error
    is detected (pi: auto_retry_start).
    Payload: :attempt, :max-attempts, :delay-ms, :error-message."

   :auto-retry-end
   "Fired when retries finish — success, exhausted, or cancelled.
    Payload: :success, :attempt, :final-error (on failure)."

   :agent-settled
   "Agent is fully idle. Reserved."})

(defn known-event-type?
  "True if the keyword is part of the documented vocabulary."
  [type]
  (contains? event-types type))
