# Agent Loop Alignment: pi vs kmet

Analysis of gaps between kmet's agent loop and pi's agent loop architecture,
with implementation guidance for each gap.

## Architecture Overview

### pi (TypeScript, 3 layers)

| Layer | Package | File | Role |
|-------|---------|------|------|
| **Agent** | `@earendil-works/pi-agent-core` | `agent.js` + `agent-loop.js` | Raw loop engine: state machine, tool execution, event emission |
| **AgentSession** | `@earendil-works/pi-coding-agent` | `agent-session.js` | Session mgmt: persistence, compaction, retry, auth, extension integration |
| **Extensions** | `@earendil-works/pi-coding-agent` | `extensions/runner.js` | Lifecycle hooks at every point in the loop |

### kmet (Clojure/Babashka, flat)

| Component | File | Role |
|-----------|------|------|
| `run-agent-turn` | `loop.clj` | Single-threaded LLM→tools→LLM loop with callbacks |
| Skills/Extensions | `skills.clj` | Simple event bus + markdown skills |
| Session | `session.clj` | EDNL-based tree storage with linear compaction |
| LLM | `llm.clj` | OpenAI + Anthropic streaming client |
| Core | `core.clj` | Binds UI to agent loop |

### pi Agent Loop State Machine

```
outer: while followUpQueue has items ──────────────────────┐
  inner: while hasMoreToolCalls OR steeringQueue has items ─┐│
    prepareNextTurn (dynamic context/model/thinking update) ││
    transformContext (prune/inject messages before LLM)     ││
    streamAssistantResponse (LLM call)                      ││
    if stopReason == "error": retry → inner / abort         ││
    if stopReason == "aborted": abort                       ││
    executeToolCalls (parallel by default)                  ││
      beforeToolCall (block/modify)                         ││
      execute                                               ││
      afterToolCall (rewrite result)                        ││
    shouldStopAfterTurn? → inner / stop                     ││
    poll steeringQueue → inner (if items)                   ││
  end inner ←───────────────────────────────────────────────┘│
  poll followUpQueue → outer (if items)                     ││
end outer ←──────────────────────────────────────────────────┘
agent_end
  check retry
  check compaction
```

### kmet Agent Loop State Machine

```
run-agent-turn (one-shot, per user message)
  add user message to history
  auto-compact if threshold reached (before turn)
  loop (max 20 turns):
    call-llm
    if error → emit error, done
    if tool-calls → execute sequentially
      emit tool-start, tool-progress (200ms poll), tool-result
      add results to history
      continue loop
    if no tool-calls → emit done, done
```

---

## Gap 1: Message Queue (Steering / Follow-up)

**Severity: 🔴 Critical**

### pi Implementation (`agent.js`, `agent-loop.js`)

Two queues on the `Agent` class:

```typescript
class Agent {
  steeringQueue = new PendingMessageQueue("one-at-a-time" | "all");
  followUpQueue = new PendingMessageQueue("one-at-a-time" | "all");

  steer(message: AgentMessage): void;    // Queue for mid-turn injection
  followUp(message: AgentMessage): void; // Queue for after-agent-finishes
  clearAllQueues(): void;
}
```

The agent loop polls these via config callbacks:

```typescript
// agent-loop.js runLoop()

while (true) {  // outer: follow-up
  while (hasMoreToolCalls || pendingMessages.length > 0) {  // inner: steering
    // ... LLM call + tool execution ...
    pendingMessages = await config.getSteeringMessages?.() || [];
  }
  // Agent would stop here. Check follow-up.
  const followUps = await config.getFollowUpMessages?.() || [];
  if (followUps.length > 0) { pendingMessages = followUps; continue; }
  break;
}
```

Queue modes:
- `"one-at-a-time"` (default): drain one message per poll cycle
- `"all"`: drain all queued messages at once

`AgentSession` tracks pending messages for UI display:

```typescript
class AgentSession {
  _steeringMessages: string[];  // For UI display
  _followUpMessages: string[];
  _emitQueueUpdate(): void;     // Notifies UI
}
```

### kmet Current State

No queue. `run-agent-turn` takes a single message, processes it synchronously to completion.
Typing while agent runs has no effect. The `AgentState` record has no queue fields.

### Required Work

1. Add `steering` and `follow-up` atom queues to `AgentState` (or a single `PendingMessageQueue`)
2. Add `steer`, `followUp`, `clearQueue`, `hasQueuedMessages` helpers on AgentState
3. Restructure the inner loop in `run-agent-turn` to poll steering queue after each tool batch (before next LLM call)
4. Add an outer loop to poll follow-up queue after inner loop drains
5. Emit queue-update events so UI can display pending messages
6. Wire `core.clj` `handle-submit` to use `steer`/`followUp` instead of blocking when already running

---

## Gap 2: Extension Lifecycle Hooks

**Severity: 🔴 Critical**

### pi Implementation (`agent-session.js`, `extensions/runner.js`)

15+ typed events flowing through the extension system:

| Event Type | When | Payload |
|------------|------|---------|
| `agent_start` | Before first LLM call | — |
| `agent_end` | After all processing done | `messages` |
| `turn_start` | Before each LLM call | `turnIndex`, `timestamp` |
| `turn_end` | After tool results appended | `message`, `toolResults` |
| `message_start` | Message added to context | `message` |
| `message_update` | Streaming delta received | `message`, `assistantMessageEvent` |
| `message_end` | Message finalized | `message` |
| `tool_execution_start` | Tool execution begins | `toolCallId`, `toolName`, `args` |
| `tool_execution_update` | Partial result during exec | `partialResult` |
| `tool_execution_end` | Tool execution completes | `result`, `isError` |
| `input` | User input before processing | Returns `{action: "pass"|"handled"|"transform"}` |
| `before_agent_start` | Before agent starts, after input | Returns `{systemPrompt?, messages?}` |
| `model_select` | Model changes | `model`, `previousModel`, `source` |

Extensions register via:

```typescript
pi.on("tool_call", async (event, ctx) => { ... });
pi.registerTool({ name: "deploy", execute: ... });
pi.registerCommand("stats", { handler: ... });
```

Hook-point functions for non-event interception:

```typescript
agent.beforeToolCall = async ({ toolCall, args }) => { block?, reason? };
agent.afterToolCall = async ({ toolCall, args, result, isError }) => { content?, isError? ... };
```

### kmet Current State

`skills.clj` has a simple global event bus:

```clojure
(defonce ^:private event-listeners (atom {}))
(defn on-event [event-type callback] ...)
(defn emit-event! [event] ...)
```

Events are emitted ad-hoc from `core.clj` (`:tool-start`, `:tool-progress`, `:tool-result`, `:status`) but there's no structured lifecycle integration. Extensions get no LLM streaming updates, no turn boundaries, no message-level events.

### Required Work

1. Define a comprehensive event type vocabulary (keywords or a protocol)
2. Emit events at every lifecycle point from `run-agent-turn`
3. Emit `message_update` events during LLM streaming (text deltas, thinking deltas, tool call deltas)
4. Add `before-tool-call` and `after-tool-call` hook atoms on `AgentState`
5. Wire `skills/emit-event!` for every event type, not just tool execution
6. Support extension return values (e.g., `after-tool-call` can modify result)
7. Consider adding `input` and `before-agent-start` hooks that can transform/modify the user message before processing

---

## Gap 3: Streaming Message Updates

**Severity: 🔴 Critical`

### pi Implementation

During streaming, the partial assistant message is mutated in-place and re-emitted:

```typescript
// agent-loop.js
partialMessage = event.partial;
context.messages[context.messages.length - 1] = partialMessage;
await emit({
  type: "message_update",
  assistantMessageEvent: event,  // { type: "text_delta" | "thinking_delta" | "toolcall_start" | ... }
  message: { ...partialMessage }
});
```

The `AssistantMessageEventStream` from `@earendil-works/pi-ai` emits fine-grained events:
- `text_start`, `text_delta`, `text_end`
- `thinking_start`, `thinking_delta`, `thinking_end`
- `toolcall_start`, `toolcall_delta`, `toolcall_end`
- `done`, `error`

Extensions and UI both subscribe to these events.

### kmet Current State

`on-text` and `on-thinking` callbacks deliver raw text deltas. No structured events. The UI in `chat_history.clj` accumulates text/thinking in atoms and finalizes on completion. There's no way to hook into the stream from extensions.

### Required Work

1. Replace simple string callbacks with structured event emission
2. Events should carry the full partial message state for each delta
3. Emit `:message-start`, `:message-update`, `:message-end` for all streaming phases
4. After final message is assembled, emit `:message-end` with the complete message

---

## Gap 4: Auto-retry

**Severity: 🔴 Critical`

### pi Implementation (`agent-session.js`)

```typescript
class AgentSession {
  _retryAttempt = 0;
  _retryAbortController?: AbortController;
  _overflowRecoveryAttempted = false;

  _willRetryAfterAgentEnd(event): boolean {
    if (!settings.enabled || this._retryAttempt >= settings.maxRetries) return false;
    return this._isRetryableError(lastAssistantMessage);
  }

  _isRetryableError(msg): boolean {
    return msg.stopReason === "error" && 
           !isContextOverflow(msg) && // overflow → compact then retry
           isRetryableAssistantError(msg); // transient vs terminal
  }
}
```

Retry flow:
1. After `agent_end`, `_handlePostAgentRun()` checks retry
2. If retryable → `_prepareRetry(msg)` → fix/backup context → `agent.continue()`
3. On success → `_retryAttempt = 0`, emit `auto_retry_end`
4. On failure → increment, retry again or emit final failure
5. Context overflow errors trigger `_checkCompaction()` instead

### kmet Current State

No retry. Any error in `run-agent-turn` immediately calls `on-error` and sets status to `:error`.

### Required Work

1. Add `:max-retries` config to `AgentState`
2. After an LLM call fails (error stop reason), check if retryable
3. Before retrying, optionally compact context if overflow
4. Call `llm/send-message` again with same context (no new user message)
5. Track retry count in `AgentState`
6. Emit retry lifecycle events (`:retry-start`, `:retry-end`)

---

## Gap 5: Parallel Tool Execution

**Severity: 🔴 Critical`

### pi Implementation (`agent-loop.js`)

Two modes, configurable globally and per-tool:

```typescript
type ToolExecutionMode = "sequential" | "parallel";

// agent-loop.js
const hasSequentialToolCall = toolCalls.some(tc =>
  currentContext.tools?.find(t => t.name === tc.name)?.executionMode === "sequential"
);

if (config.toolExecution === "sequential" || hasSequentialToolCall) {
  return executeToolCallsSequential(...);
}
return executeToolCallsParallel(...);
```

Parallel execution flow:
1. Prepare all tools sequentially (validate args, run `beforeToolCall`)
2. Execute allowed tools concurrently via `Promise.all`
3. Emit `tool_execution_end` in completion order
4. Emit tool-result message artifacts later in assistant source order

Has structured tool execution events for each phase:
- `tool_execution_start` (before preparation)
- `tool_execution_update` (during execution, for streaming results)
- `tool_execution_end` (after finalization)

### kmet Current State

Sequential `doseq` over tool calls:

```clojure
(doseq [tc tool-calls]
  (emit agent {:type :tool-start :id (:id tc) ...})
  (let [f (future (tools/execute-tool (:name tc) (:arguments tc)))
        result (loop []
                 (let [v (deref f 200 :pending)]
                   (if (= :pending v)
                     (do (emit agent {:type :tool-progress})
                         (recur))
                     v)))]
    ...))
```

### Required Work

1. Add `execution-mode` field to `Tool` record (default `:parallel`)
2. In `run-agent-turn`, split tool calls into two batches: sequential ones + parallel ones
3. Execute parallel batch with `pmap` or futures + `deref` with completion order tracking
4. Emit structured `:tool-execution-start`, `:tool-execution-update`, `:tool-execution-end` events
5. Support streaming partial tool results during execution (via callback)

---

## Gap 6: Proactive Auto-compaction

**Severity: 🟡 Important**

### pi Implementation (`agent-session.js`)

```typescript
async _checkCompaction(lastAssistant: AssistantMessage, shouldContinue = true): Promise<boolean> {
  if (!this._shouldCompact(lastAssistant)) return false;

  const settings = this.settingsManager.getCompactionSettings();
  const args = await prepareCompaction({ ... });

  if (shouldCompact(args)) {
    await compact(args);              // Do the LLM-based summarization
    await this.agent.continue();      // Still have the pending user turn to process
    return true;
  }
  return false;
}
```

Compaction happens:
- **Proactively**: when approaching context window limit (after a turn, before next LLM call)
- **Reactively**: when a context overflow error is detected

Configurable threshold, summarization model, custom instructions.

### kmet Current State

```clojure
;; In run-agent-turn, before processing:
(when-let [threshold (:compact-threshold agent)]
  (let [n-entries (count @(:entries sess))]
    (when (>= n-entries threshold)
      (session/compact! sess (quot threshold 2)))))
```

Only checked **before** a turn starts, using a simple entry-count threshold.
Reactive to count, not token limit. No LLM-based summarization — just drops old entries.
No compaction after tool execution adds new results.

### Required Work

1. Check compaction **after** tool results are added but **before** next LLM call
2. Estimate token usage instead of relying on entry count
3. Store token estimate in session (or compute from messages)
4. Detect context overflow errors from LLM and trigger compaction + retry
5. Optionally use LLM to generate summaries instead of just dropping entries

---

## Gap 7: Dynamic Turn Configuration (`prepareNextTurn`)

**Severity: 🟡 Important`

### pi Implementation

```typescript
interface AgentLoopConfig {
  prepareNextTurn?: (context: PrepareNextTurnContext) =>
    AgentLoopTurnUpdate | undefined;

  prepareNextTurnWithContext?: (turn, signal) => {
    context?: AgentContext;    // replacement context
    model?: Model<any>;        // replacement model
    thinkingLevel?: ThinkingLevel;
  };
}
```

Called after each turn's `turn_end` and `toolResults`. Extensions can:

- Swap the model mid-conversation
- Change thinking level
- Inject/remove tools
- Modify system prompt for the remaining turns

`AgentSession` also sets `_systemPromptOverride` for per-turn system prompt changes
via `before_agent_start` extension hook.

### kmet Current State

No equivalent. Model, provider, thinking, system prompt are set once when `AgentState` is created and never changed during the multi-turn loop.

### Required Work

1. Add `:on-prepare-next-turn` callback to `run-agent-turn` opts
2. Call it after each turn (after tool results, before next LLM call)
3. Result can update `AgentState`'s model, system prompt, tools, thinking level
4. Support system prompt overrides per-turn

---

## Gap 8: Context Transformation

**Severity: 🟡 Important`

### pi Implementation

```typescript
interface AgentLoopConfig {
  transformContext?: (messages: AgentMessage[], signal?: AbortSignal) =>
    Promise<AgentMessage[]>;
}
```

Called before `convertToLlm` on every LLM call. Use cases:
- Prune old messages to fit context window
- Inject dynamic context (current date, file tree, etc.)
- Remove tool results that are no longer relevant
- Add third-party data before the LLM sees messages

### kmet Current State

No equivalent. Messages pass through untouched.

### Required Work

1. Add `:transform-context` callback to `run-agent-turn` and `call-llm`
2. Call it on the message vector before converting to provider format
3. Return the (possibly modified) message vector

---

## Gap 9: `shouldStopAfterTurn`

**Severity: 🟡 Important**

### pi Implementation

```typescript
interface AgentLoopConfig {
  shouldStopAfterTurn?: (context: ShouldStopAfterTurnContext) => boolean | Promise<boolean>;
}
```

Called after `turn_end` before polling steering queue. Returns `true` to stop
the agent loop gracefully. Used by extensions to implement:
- "Stop if enough information collected"
- "Stop if user asked a simple question (no more tools needed)"
- "Stop after N turns"

### kmet Current State

No equivalent. The loop only stops when:
- `max-turns` (20) is reached
- LLM returns no tool calls
- An error occurs

### Required Work

1. Add `:should-stop-after-turn` callback to `run-agent-turn`
2. Call it after each turn completes (after tool results)
3. If returns true, stop the loop and emit final response

---

## Gap 10: Tool Execution Hooks (`beforeToolCall` / `afterToolCall`)

**Severity: 🟡 Important**

### pi Implementation

```typescript
agent.beforeToolCall = async ({ toolCall, args }) => {
  // Return { block: true, reason: "Permission denied" } to block
  // Return undefined to allow
};

agent.afterToolCall = async ({ toolCall, args, result, isError }) => {
  // Return { content: [...], isError: false } to rewrite result
  // Return undefined to leave as-is
};
```

Use cases:
- Permission gating (approve/reject tool calls)
- Path protection (block reads outside project)
- Result rewriting (sanitize secrets from output)
- Usage tracking

### kmet Current State

No hooks. `execute-tool` runs directly.

### Required Work

1. Add `:before-tool-call` and `:after-tool-call` atoms to `AgentState`
2. In `run-agent-turn`'s tool execution section, call before hook before executing
3. If before hook returns `:block`, skip execution and emit error result
4. Call after hook after execution, allowing result modification
5. Support hooks returning modified data

---

## Gap 11: System Prompt Override Per-Turn

**Severity: 🟡 Important**

### pi Implementation

```typescript
class AgentSession {
  _systemPromptOverride?: string;     // Set per-turn
  _baseSystemPrompt: string;          // The canonical prompt
  _rebuildSystemPrompt(toolNames);    // Called on tool change

  // In prompt():
  if (result?.systemPrompt !== undefined) {
    this._systemPromptOverride = result.systemPrompt;
    this.agent.state.systemPrompt = result.systemPrompt;
  } else {
    this._systemPromptOverride = undefined;
    this.agent.state.systemPrompt = this._baseSystemPrompt;
  }
}
```

### kmet Current State

System prompt is set once via `make-agent-state` and never changes.

### Required Work

1. Add a `:system-prompt-override` atom to `AgentState`
2. In `call-llm`, prefer override over base system prompt
3. Reset override after each turn (or let extension control lifetime)
4. Wire into extension system so `before-agent-start` can set it

---

## Gap 12: Model / Auth Management

**Severity: 🟡 Important**

### pi Implementation

```typescript
class ModelRuntime {
  getAuth(model): Promise<{ auth: { apiKey?, headers? }, env? }>;
  checkAuth(provider): Promise<string | undefined>;  // API key or undefined
  isUsingOAuth(provider): boolean;
  hasConfiguredAuth(provider): boolean;
}

class AgentSession {
  async setModel(model): Promise<void>;    // Validates auth first
  async cycleModel(direction): Promise<Model | undefined>;  // Next/prev
  _scopedModels: Model[];  // From --models flag
}

async _emitModelSelect(nextModel, previousModel, source): Promise<void>;
```

Auth resolution is dynamic per LLM call (supports expiring OAuth tokens):

```typescript
const resolvedApiKey = config.getApiKey
  ? await config.getApiKey(config.model.provider)
  : config.apiKey;
```

### kmet Current State

```clojure
;; In run-agent-turn:
(let [api-key (cfg/get-api-key provider)]
  (if (nil? api-key) (on-error "No API key") ...))
```

Simple key resolution at turn start. No OAuth support. No model cycling.

### Required Work

1. Add `:get-api-key` callback to `llm/send-message` for dynamic key resolution
2. Support OAuth-style auth flows (may be out of scope for babashka)
3. Add `set-model!`, `cycle-model!` functions to AgentState
4. Add scoped model lists for cycling

---

## Appendix: Event Type Vocabulary

For reference, here's the full set of events kmet would need to emit for full alignment:

```clojure
;; Agent lifecycle
:agent-start                              ;; Fired once per user submission
:agent-end                                ;; Fired when agent loop finishes
  ;; payload: :messages — all new messages from this loop

;; Turn lifecycle (one per LLM call)
:turn-start                               ;; Before each LLM call
  ;; payload: :turn-index
:turn-end                                 ;; After tool results are back
  ;; payload: :message, :tool-results

;; Message lifecycle (per-message)
:message-start                            ;; Message added to context
  ;; payload: :message
:message-update                           ;; Streaming delta (assistant only)
  ;; payload: :message, :delta {:type :text :content ""}
  ;;                              {:type :thinking :content ""}
  ;;                              {:type :tool-call ...}
:message-end                              ;; Message finalized
  ;; payload: :message

;; Tool execution lifecycle (per-tool-call)
:tool-execution-start                     ;; Validation & preparation
  ;; payload: :tool-call-id, :tool-name, :args
:tool-execution-update                    ;; Partial result during execution
  ;; payload: :tool-call-id, :partial-result
:tool-execution-end                       ;; Final result available
  ;; payload: :tool-call-id, :result, :is-error

;; Queue updates
:queue-update                             ;; Steering/follow-up queues changed
  ;; payload: :steering [:msg ...], :follow-up [:msg ...]

;; Model lifecycle
:model-select                             ;; Model was changed
  ;; payload: :model, :previous-model, :source

;; Auto-retry events
:auto-retry-start                         ;; Retry about to begin
  ;; payload: :attempt
:auto-retry-end                           ;; Retry finished
  ;; payload: :success, :attempt, :final-error (on failure)

;; Agent settled (all post-processing done)
:agent-settled                            ;; Agent is fully idle
```

---

## Appendix: Comparison of State Machines

```
pi Agent:
  status: isStreaming (bool via activeRun)
  Queues: steeringQueue, followUpQueue
  State machine is implicit in runLoop()

pi AgentSession:
  State machine is implicit in prompt() → _runAgentPrompt() → _handlePostAgentRun()
  Post-run checks: retry → compaction → queue drainage → done

kmet AgentState:
  status: :idle → :thinking → :executing → :idle | :error
  No queues
  State machine is explicit but flat in run-agent-turn
```

---

## Appendix: File-Level Implementation Plan

| Gap | Primary File Changes | New Files |
|-----|---------------------|-----------|
| 1 (Queue) | `loop.clj`, `core.clj` | — |
| 2 (Events) | `loop.clj`, `skills.clj` | `app/events.clj` (event type defs) |
| 3 (Streaming) | `loop.clj`, `llm.clj` | — |
| 4 (Retry) | `loop.clj` | — |
| 5 (Parallel Tools) | `loop.clj`, `tools/core.clj`, `tools/registry.clj` | — |
| 6 (Compaction) | `loop.clj`, `session.clj` | — |
| 7 (prepareNextTurn) | `loop.clj` | — |
| 8 (transformContext) | `loop.clj`, `llm.clj` | — |
| 9 (shouldStopAfterTurn) | `loop.clj` | — |
| 10 (Tool Hooks) | `loop.clj`, `tools/core.clj` | — |
| 11 (System Prompt Override) | `loop.clj` | — |
| 12 (Auth) | `config.clj`, `loop.clj`, `llm.clj` | — |
