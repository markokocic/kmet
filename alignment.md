# kmet ↔ pi Alignment

Analysis of gaps between kmet and pi's agent loop and editor architectures,
with implementation guidance for each gap.

## Implementation Roadmap

Work proceeds in the following order:

1. **Phase 0 — Event foundation** (Agent Gaps 2, 3): event vocabulary
   (`kmet.app.events`), structured lifecycle events from `run-agent-turn`,
   extension routing via `skills/emit-event!`. ✅ done
2. **Phase 1 — State machine** (Agent Gap 1): steering/follow-up queues,
   inner/outer loop restructure. ✅ done
3. **Phase 2 — Resilience** (Agent Gaps 4, 10): auto-retry, before/after
   tool-call hooks. ✅ done
4. **Phase 3 — Polish** (Agent Gaps 5–9, 11, 12): parallel tools, context
   transforms, per-turn config, compaction, system prompt override. ✅ done
5. **Phase 4 — Editor quick wins** (Editor Gaps 5–9): history drafts,
   dynamic height, paste-marker atomic segments, CSI-u decode + renumbering.
6. **Phase 5 — Autocomplete** (Editor Gaps 1, 2, 10): provider protocol,
   slash command registry, fuzzy matching, file path completion, SelectList
   integration, auto-trigger + debounce.
7. **Phase 6 — Actions & external editor** (Editor Gaps 3, 4): editor
   action system, `$EDITOR` flow.

---

# Agent Loop Alignment: pi vs kmet

Analysis of gaps between kmet's agent loop and pi's agent loop architecture,
with implementation guidance for each gap.

## Architecture Overview

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

---

# Editor Alignment: pi vs kmet

Analysis of gaps between kmet's multi-line text editor (`kmet.tui.components.editor`)
and pi's Editor (`@earendil-works/pi-tui` `components/editor.ts`), covering
autocomplete, navigation, editing, layout, and extensibility.

## Architecture Overview

### pi (TypeScript, layered)

| Layer | File | Role |
|-------|------|------|
| **Editor** | `packages/tui/src/components/editor.ts` (2352 lines) | Core multi-line editor: word-wrap, cursor, scrolling, undo, kill-ring, paste markers, autocomplete integration |
| **Autocomplete** | `packages/tui/src/autocomplete.ts` (500+ lines) | `CombinedAutocompleteProvider` — slash commands, file paths (`@`), fuzzy filtering, `fd` integration, argument completion |
| **Fuzzy** | `packages/tui/src/fuzzy.ts` | Scoring: word-boundary bonuses, consecutive match rewards, camelCase/alpha-numeric swapping |
| **SelectList** | `packages/tui/src/components/select-list.ts` | Dropdown menu with primary+secondary columns, scroll indicators, keyboard navigation |
| **EditorComponent** | `packages/tui/src/editor-component.ts` | Interface: `getText`, `setText`, `handleInput`, `setAutocompleteProvider`, `onSubmit`, `onChange`, `addToHistory`, `insertTextAtCursor`, `getExpandedText` |
| **CustomEditor** | `packages/coding-agent/src/modes/interactive/components/custom-editor.ts` | Extends Editor with `onAction()` system: app-level actions (model select, external editor, clipboard paste, session mgmt) |
| **Slash commands** | `packages/coding-agent/src/core/slash-commands.ts` | 20 builtin commands with descriptions and argument hints |
| **Word navigation** | `packages/tui/src/word-navigation.ts` | Pure functions — `findWordBackward`, `findWordForward` with paste-marker awareness |
| **Kill ring** | `packages/tui/src/kill-ring.ts` | Ring buffer with accumulate (prepend/append), rotate, peek |
| **Undo stack** | `packages/tui/src/undo-stack.ts` | Generic stack with `structuredClone` |

### kmet (Clojure/Babashka, flat)

| Component | File | Role |
|-----------|------|------|
| **Editor** | `src/kmet/tui/components/editor.clj` (~960 lines) | Multi-line editor: word-wrap, cursor, scrolling, undo+redo, kill-ring, basic paste markers, autocomplete provider slot |
| **Input** | `src/kmet/tui/components/input.clj` | Single-line input: horizontal scrolling, kill-ring, undo |
| **Editing primitives** | `src/kmet/tui/components/editing.clj` | Grapheme clusters (pure Clojure), kill-ring, word boundaries |
| **Utils** | `src/kmet/tui/utils.clj` | visible-width, CURSOR-MARKER, ANSI helpers |
| **SelectList** | `src/kmet/tui/components/select_list.clj` | Exists separately (used for session picker) but **not integrated with editor** |
| **Core** | `src/kmet/core.clj` | Wires editor submit handler, provides tab fn via `editor-set-on-tab!` |

---

## Gap 1: Autocomplete System

**Severity: 🔴 Critical**

### pi Implementation

Editor integrates with `CombinedAutocompleteProvider` via a clean interface:

```typescript
interface AutocompleteProvider {
  triggerCharacters?: string[];
  getSuggestions(lines, cursorLine, cursorCol, { signal, force }): Promise<AutocompleteSuggestions | null>;
  applyCompletion(lines, cursorLine, cursorCol, item, prefix): { lines, cursorLine, cursorCol };
  shouldTriggerFileCompletion?(lines, cursorLine, cursorCol): boolean;
}
```

The provider handles three completion domains:

**1. Slash commands** — builtins (20 commands from `slash-commands.ts`), skills, extensions, prompt templates. Fuzzy-matched via `fuzzyFilter`. Rendered in a `SelectList` with primary column (name) and secondary column (description/argument hint).

**2. File path completion** (`@` prefix or Tab trigger) — Two approaches:
   - **`fd`-based fuzzy search**: async, respects `.gitignore`, full-path matching, recusive, returns top-20 scored results. Used when typing `@` before a path.
   - **Directory listing**: synchronous `readdirSync` for Tab completion at explicit directory boundaries (`./`, `../`, `~/`, `/`). Directories sorted first, alphabetical within.
   - Supports **quoted paths** (`@"path with spaces"`).
   - **Home directory expansion** (`~/` → expanded path).

**3. Argument completion** — after a slash command name and space, `getArgumentCompletions()` on the command object returns context-specific items (e.g., `/model <Tab>` lists available models).

**Auto-trigger logic** in `insertCharacter()`:
- `/` at start of message → trigger
- `@`, `#` or other trigger characters at token boundary → trigger with debounce
- Letters within slash command context → trigger
- Letters matching trigger pattern → trigger with debounce (20ms for `@` paths)

**Debounce and cancellation**:
```typescript
ATTACHMENT_AUTOCOMPLETE_DEBOUNCE_MS = 20;
// ^ only for @-prefix matching (expensive fd calls)
// Tab/force triggers skip debounce
```
- `AbortController` cancels in-flight requests
- Request serialization via `autocompleteRequestTask` chain
- Stale snapshot detection: if text/cursor changed since request, ignore result
- Cancel on: escape, cursor movement, text change, blur

**Keyboard navigation** within autocomplete dropdown:
- Up/Down arrows move selection (wrapping)
- Enter/Tab confirms selected item
- Escape cancels
- Tab with single result auto-completes immediately without showing dropdown

### kmet Current State

```clojure
;; editor.clj line 593
(defn- handle-tab [editor]
  (if-let [ap @(:autocomplete-provider editor)]
    (let [state @(:state-atom editor)
          lines (:lines state)
          cl (:cursor-line state) cc (:cursor-col state)
          line (or (nth lines cl) "")
          before-cursor (subs line 0 cc)
          word-start (or (last (keep-indexed #(when (re-find #"[\s/]" (str %2)) %1) before-cursor))
                         -1)
          partial (subs before-cursor (inc word-start))
          result (ap partial (editor-get-text editor))]
      (when result
        (insert-character editor result)))
    ;; Default tab: insert 4 spaces
    (insert-character editor "    ")))
```

Key limitations:

1. **No autocomplete dropdown UI** — `SelectList` exists but is never used by the editor. The provider returns a string that is immediately inserted at cursor — no selection, no preview, no cancellation.

2. **No `CombinedAutocompleteProvider`** — The provider atom expects a function `(fn prefix current-text → string-or-nil)`. No protocol, no `getSuggestions`/`applyCompletion` separation, no structured items with descriptions.

3. **No slash command autocomplete** — Even if a provider is wired, there's no builtin command registry, no skill commands, no extension commands. The `core.clj` `handle-command` dispatches manually on `/quit`, `/help`, `/model`, `/new`, `/resume`, `/tree`, `/theme` — no way to discover these from the editor.

4. **No file path completion** — No `@` prefix handler, no `fd` integration, no `readdirSync` equivalent. Tab in a non-slash context just inserts 4 spaces.

5. **No argument completion** — After typing `/model `, Tab has no way to propose provider:model values.

6. **No fuzzy matching** — The provider is called with the exact partial prefix; no scoring or ranking.

7. **No abort/cancellation** — No debounce, no abort controller, no stale-snapshot check. Every keystroke invokes the provider synchronously.

8. **No auto-trigger** — Only fires on explicit Tab press. No automatic popup on `/`, `@`, or letters within a command.

### Required Work

1. **Define `AutocompleteProvider` protocol** with `get-suggestions` and `apply-completion` methods, matching pi's interface
2. **Port `CombinedAutocompleteProvider`** with slash command registry, file path completion, argument completion
3. **Port `fuzzyFilter`** — fuzzy matching with word-boundary bonuses, consecutive match rewards, alpha-numeric swapping
4. **Build slash command registry** — builtins from `core.clj`, extendable by skills and extensions
5. **Build file path completion** — `babashka.fs` for directory listing, optional `fd` integration for recursive search, `@` prefix for file attachment, `~` expansion, quoted path support
6. **Integrate `SelectList` into editor render** — when autocomplete state is active, render SelectList below the editor in `render()`
7. **Wire keyboard navigation** — Up/Down/Enter/Escape when autocomplete is active
8. **Add auto-trigger logic** — `/` at line start, `@` at token boundary, letters in slash command, with optional debounce for file-system operations
9. **Add abort/debounce** — atom-based request tracking, cancellation on cursor movement/text change
10. **Wire in `core.clj`** — on startup, call `editor-set-autocomplete-provider!` with a `CombinedAutocompleteProvider` that has builtin commands and current-working-directory file completion

---

## Gap 2: Autocomplete UI (SelectList in Editor)

**Severity: 🔴 Critical**

### pi Implementation

The Editor's `render()` method appends the autocomplete SelectList after the editor content:

```typescript
// editor.ts render()
if (this.autocompleteState && this.autocompleteList) {
  const autocompleteResult = this.autocompleteList.render(contentWidth);
  for (const line of autocompleteResult) {
    const lineWidth = visibleWidth(line);
    const linePadding = " ".repeat(Math.max(0, contentWidth - lineWidth));
    result.push(`${leftPadding}${line}${linePadding}${rightPadding}`);
  }
}
```

The SelectList renders:
- A primary column with the item label (truncated to `maxPrimaryColumnWidth`, default 32)
- A secondary column with description (truncated to remaining width, minimum 10 chars)
- Selection indicator: `→` for selected, `  ` for unselected
- Selected items are styled via `selectedText` theme function
- Description uses `description` theme function
- Scroll indicator: `(N/M)` at bottom when items exceed visible count
- Items are rendered starting from `selectedIndex - floor(maxVisible/2)` for centered scrolling

### kmet Current State

The `SelectList` component exists at `kmet.tui.components.select-list` and has feature parity with pi's version (primary/secondary columns, scroll indicators, wrapping navigation). However, it is **never used by the Editor**. The Editor has no autocomplete rendering at all.

### Required Work

1. Add `autocomplete-list`, `autocomplete-state`, `autocomplete-prefix` atoms to Editor record
2. In Editor's `render()`, after rendering the bottom border, check if autocomplete is active and call `protocols/render autocomplete-list content-width`
3. Append the rendered lines with proper padding
4. In `handle-input`, when autocomplete is active, intercept Up/Down/Enter/Tab/Escape and delegate to SelectList or apply completion
5. Style autocomplete lines using theme (selected prefix, selected text, description colors)
6. Layer autocomplete **below** the editor border (not overlaying content) — pi places it after the bottom border

---

## Gap 3: Editor Action / Extension System

**Severity: 🔴 Critical**

### pi Implementation

`CustomEditor` extends `Editor` and adds an action system:

```typescript
class CustomEditor extends Editor {
  actionHandlers: Map<AppKeybinding, () => void> = new Map();
  onEscape?: () => void;
  onCtrlD?: () => void;
  onPasteImage?: () => void;
  onExtensionShortcut?: (data: string) => boolean;

  onAction(action: AppKeybinding, handler: () => void): void { ... }

  handleInput(data: string): void {
    if (this.onExtensionShortcut?.(data)) return;
    if (this.keybindings.matches(data, "app.clipboard.pasteImage")) { ... }
    if (this.keybindings.matches(data, "app.interrupt")) { if (!this.isShowingAutocomplete()) { ... } }
    for (const [action, handler] of this.actionHandlers) {
      if (this.keybindings.matches(data, action)) { handler(); return; }
    }
    super.handleInput(data);
  }
}
```

Registered actions include:
- `app.interrupt` — escape/cancel
- `app.exit` — Ctrl+D quit
- `app.model.select` — show model selector
- `app.model.cycleForward` / `app.model.cycleBackward` — cycle models
- `app.tools.expand` — toggle tool output
- `app.thinking.toggle` — toggle thinking blocks
- `app.editor.external` — open external editor
- `app.message.copy` — copy last message
- `app.message.followUp` — dequeue/steer
- `app.session.new`, `app.session.tree`, `app.session.fork`, `app.session.resume` — session management

Additionally, an `EditorComponent` interface provides a clean abstraction so extensions can provide custom editor implementations:

```typescript
interface EditorComponent extends Component {
  getText(): string;
  setText(text: string): void;
  handleInput(data: string): void;
  onSubmit?: (text: string) => void;
  onChange?: (text: string) => void;
  addToHistory?(text: string): void;
  insertTextAtCursor?(text: string): void;
  getExpandedText?(): string;
  setAutocompleteProvider?(provider: AutocompleteProvider): void;
  borderColor?: (str: string) => string;
  setPaddingX?(padding: number): void;
  setAutocompleteMaxVisible?(maxVisible: number): void;
}
```

### kmet Current State

Editor is a plain `defrecord` implementing `IComponent`. All app-level keybindings are handled externally in `core.clj`'s global input listener, not through the editor itself. There is no action registration mechanism. The editor's `autocomplete-provider` is a simple function atom with no protocol.

### Required Work

1. Add `:on-action` callback mechanism to Editor (or an `actions` dispatch map)
2. Wire app actions from `core.clj`'s keybinding handler through the editor's action system
3. Add `:on-escape`, `:on-external-editor` hooks to Editor
4. Consider adding `EditorComponent` protocol for extension-provided editors
5. Move app-level keybinding dispatch (Ctrl+C, Ctrl+L, app.interrupt, app.clear) into the editor's `handle-input` via action handlers, keeping only truly global keys at the TUI level

---

## Gap 4: External Editor Integration

**Severity: 🟡 Important**

### pi Implementation

`interactive-mode.ts` has a full external editor flow:

```typescript
async handleOpenExternalEditor(): Promise<void> {
  const content = this.editor.getExpandedText();  // paste markers expanded
  const tmpFile = path.join(tmpdir(), `pi-editor-${randomUUID()}.md`);
  fs.writeFileSync(tmpFile, content);
  const editor = process.env.EDITOR || "vi";
  spawnSync(editor, [tmpFile], { stdio: "inherit" });
  const newContent = fs.readFileSync(tmpFile, "utf-8");
  this.editor.setText(newContent);
  this.ui.requestRender();
}
```

Key details:
- Uses `getExpandedText()` to expand paste markers before editing
- Writes to temp file in system tmpdir
- Spawns `$EDITOR` (or `vi`) with `inherit` stdio
- On return, reads file and calls `setText()`
- Cleans up temp file
- Requires TUI to stop rendering while external editor is active (terminal mode switch)

### kmet Current State

No external editor integration. No `get-expanded-text` function (paste markers can't be expanded). No temp file creation / `$EDITOR` spawning.

### Required Work

1. Add `editor-get-expanded-text` function that replaces paste markers with stored content
2. In `core.clj`, bind a keybinding (e.g., Ctrl+E) to external editor flow:
   - Suspend TUI rendering
   - Write expanded content to temp file
   - Spawn `$EDITOR` via `babashka.process`
   - Read file back
   - Call `editor-set-text!`
   - Resume TUI rendering
3. Handle cleanup of temp files

---

## Gap 5: Paste Markers — Atomic Segments

**Severity: 🟡 Important**

### pi Implementation

Pi uses `segmentWithMarkers()` to wrap `Intl.Segmenter` and merge paste markers into single atomic segments:

```typescript
function segmentWithMarkers(text, baseSegmenter, validIds): Iterable<Intl.SegmentData> {
  // Find all [paste #N ...] markers with valid IDs
  // Merge all graphemes within a marker into one segment
  // Return merged segments
}
```

This ensures:
- Cursor never lands in the middle of a `[paste #1 +123 lines]` marker
- Backspace deletes the entire marker at once
- Word-wrap doesn't break inside a marker
- Word movement treats the marker as one unit

Used in `segment()` method which is the single entry point for all grapheme segmentation:

```typescript
private segment(text, mode): Iterable<Intl.SegmentData> {
  return segmentWithMarkers(text, mode === "word" ? wordSegmenter : graphemeSegmenter, this.validPasteIds());
}
```

### kmet Current State

Paste markers are stored (`paste-store`) but segmentation is handled by `edit/grapheme-segments` which has no knowledge of markers. Cursor can land inside a marker, backspace deletes one character at a time within it, and word-wrap can break across marker boundaries.

### Required Work

1. Add `segment-with-markers` function in `editing.clj` that takes text, base-segmenter function, and valid paste IDs
2. It should find all `[paste #N ...]` markers with valid IDs and merge their grapheme segments into single atomic units
3. Replace direct calls to `grapheme-segments` in Editor with a `segment` helper that wraps markers
4. Apply to: cursor movement (left/right), backspace, forward-delete, and word-wrap

---

## Gap 6: Undo Coalescing (Fish-Style)

**Severity: 🟡 Important**

### pi Implementation

Pi uses fish-style undo coalescing where consecutive word characters merge into one undo unit, while spaces create boundaries:

```typescript
private insertCharacter(char: string, skipUndoCoalescing?: boolean): void {
  if (!skipUndoCoalescing) {
    if (isWhitespaceChar(char) || this.lastAction !== "type-word") {
      this.pushUndoSnapshot();
    }
    this.lastAction = "type-word";
  }
  // ... insert char
}
```

This means:
- Typing "hello" pushes one undo snapshot for all 5 chars
- A space creates a boundary — typing space pushes a snapshot before space
- Then typing "world" pushes no additional snapshot until space or non-word char
- Undo removes the whole word, then each space separately

### kmet Current State

```clojure
(defn- insert-character [editor char]
  (when (or (re-find #"^\s" char) (not= @(:last-action editor) :type-word))
    (push-undo-state editor))
  (reset! (:last-action editor) :type-word))
```

kmet has the same coalescing logic, so this is **already implemented**. However, pi's handler also supports `skipUndoCoalescing` for atomic operations like paste — kmet's `insert-character` always coalesces, which means large pastes that go through `insert-text-at-cursor-internal` may produce inefficient undo granularity.

### Required Work

1. Add `skip-undo-coalescing` parameter to kmet's `insert-character`
2. Call with `skip-undo-coalescing = true` from `handle-paste` and `add-new-line`

---

## Gap 7: Extended Paste Features

**Severity: 🟡 Important**

### pi Implementation

Pi's paste handling includes several refinements beyond basic bracketed paste:

**CSI-u control byte decoding** — Some terminals (e.g., tmux popups with CSI-u mode) encode control bytes inside bracketed paste as `ESC [ <codepoint> ; 5 u` sequences. Pi decodes these back to literal control bytes before processing:

```typescript
const decodedText = pastedText.replace(/\x1b\[(\d+);5u/g, (match, code) => {
  const cp = Number(code);
  if (cp >= 97 && cp <= 122) return String.fromCharCode(cp - 96);
  if (cp >= 65 && cp <= 90) return String.fromCharCode(cp - 64);
  return match;
});
```

**Smart path spacing** — If pasting a file path (starting with `/`, `~`, or `.`) and the char before cursor is a word character, prepend a space:

```typescript
if (/^[/~.]/.test(filteredText)) {
  const charBeforeCursor = ...;
  if (charBeforeCursor && /\w/.test(charBeforeCursor)) {
    filteredText = ` ${filteredText}`;
  }
}
```

**Paste markers** — Large pastes (>10 lines or >1000 chars) are stored and replaced with `[paste #N +N lines]` or `[paste #N NNNN chars]` markers. On delete of a marker, the paste registry is cleaned up and remaining IDs are renumbered.

### kmet Current State

Basic paste markers exist (store content, insert marker). Missing: CSI-u decode, smart path spacing, paste marker renumbering on delete.

### Required Work

1. Add CSI-u control byte decoding for bracketed paste
2. Add smart path spacing heuristic
3. Add paste marker renumbering when a marker is deleted (shift higher IDs down)

---

## Gap 8: History Draft Preservation

**Severity: 🟡 Important**

### pi Implementation

When entering history browsing mode (up arrow), pi captures the current editor state as a draft:

```typescript
private navigateHistory(direction: 1 | -1): void {
  if (this.historyIndex === -1 && newIndex >= 0) {
    this.pushUndoSnapshot();
    this.historyDraft = structuredClone(this.state);
  }
  // ...
  if (this.historyIndex === -1) {
    // Restore draft
    const draft = this.historyDraft;
    this.historyDraft = null;
    if (draft) {
      this.state = draft;
      // ...
    } else {
      this.setTextInternal("");
    }
  }
}
```

This ensures that pressing Up then Down returns to exactly the typed state, even if there were multiple lines and cursor positioning. The draft is also pushed onto the undo stack so `Ctrl+Z` can restore it.

### kmet Current State

```clojure
(defn- history-backward [editor]
  (let [h @(:history editor) idx @(:history-idx editor)]
    (if (or (neg? idx) (empty? h)) nil
      (let [next-idx (max -1 (dec idx))
            entry (nth h idx)]
        (reset! (:history-idx editor) next-idx)
        (history-set-state! editor entry) ...))))
```

kmet does not capture the current editor state before entering history mode. If you type something, press Up (history), then Down to return — the typed text is **lost** and replaced with empty editor.

### Required Work

1. Before switching to a history entry, save current editor state in a `history-draft` atom
2. When returning to `history-idx = -1`, restore the draft instead of setting empty text
3. Push draft onto undo stack so it can be recovered

---

## Gap 9: Dynamic Editor Height

**Severity: 🟢 Minor**

### pi Implementation

```typescript
const terminalRows = this.tui.terminal.rows;
const maxVisibleLines = Math.max(5, Math.floor(terminalRows * 0.3));
```

Editor height is **dynamic**: 30% of terminal height, min 5 lines. Adapts to terminal resize automatically since `render()` recalculates on every call.

### kmet Current State

```clojure
(defn- get-editor-height [editor]
  (or @(:height-atom editor) 12))
```

Fixed default of 12 lines. Configurable via `:height` option, but never dynamically recalculated from terminal size.

### Required Work

1. In `render()`, compute height from `(tui/terminal-rows)` * 0.3, clamped to [5, ...]
2. The editor needs access to the terminal (currently `make-editor` doesn't receive a TUI reference — add one, or add a `terminal-rows-atom` that the TUI updates on resize)
3. Remove `height-atom` or keep it as a fallback

---

## Gap 10: Slash Command Registry

**Severity: 🔴 Critical**

### pi Implementation

20 builtin slash commands defined in `slash-commands.ts` with names, descriptions, and argument hints:

```typescript
export const BUILTIN_SLASH_COMMANDS: ReadonlyArray<BuiltinSlashCommand> = [
  { name: "settings", description: "Open settings menu" },
  { name: "model", description: "Select model", argumentHint: "<provider/model>" },
  { name: "export", description: "Export session" },
  { name: "import", description: "Import session" },
  // ... 16 more
];
```

Commands are registered into the `CombinedAutocompleteProvider` alongside skill commands and extension commands. Discovery is automatic — typing `/` shows all available commands.

### kmet Current State

Commands handled in a manual `case` dispatch in `handle-command`:

```clojure
(case cmd
  "quit" ...
  "help" ...
  "model" ...
  "new" ...)
```

There is no command registry. Skills can't register commands. Extensions can't register commands. No way to discover available commands from the UI.

### Required Work

1. Define a slash command registry (vector of `{:name :description :argument-hint :handler}` maps)
2. Register builtin commands at startup
3. Allow skills and extensions to register commands via `register-command!`
4. Wire registry into the `CombinedAutocompleteProvider`
5. Keep `handle-command` dispatch but derive it from registry

---

## Summary: Feature Comparison Table

| Feature | pi | kmet | Priority |
|---------|----|------|----------|
| **Slash command autocomplete (dropdown)** | ✅ CombinedAutocompleteProvider + SelectList | ❌ No dropdown | 🔴 Critical |
| **File path completion (@)** | ✅ fd + readdirSync, fuzzy, quotes | ❌ Not implemented | 🔴 Critical |
| **Fuzzy matching** | ✅ fuzzyFilter with scoring | ❌ Not implemented | 🔴 Critical |
| **Argument completion** | ✅ getArgumentCompletions | ❌ Not implemented | 🔴 Critical |
| **Auto-trigger autocomplete** | ✅ On /, @, letters in command | ❌ Tab-only | 🔴 Critical |
| **Autocomplete abort/debounce** | ✅ AbortController, 20ms debounce | ❌ Synchronous, no cancel | 🔴 Critical |
| **Autocomplete UI (SelectList)** | ✅ Rendered below editor border | ❌ Not used in editor | 🔴 Critical |
| **Editor action system** | ✅ CustomEditor.onAction() | ❌ No action system | 🔴 Critical |
| **EditorComponent interface** | ✅ Clean protocol for extensions | ❌ No protocol | 🔴 Critical |
| **External editor** | ✅ $EDITOR with paste expansion | ❌ Not implemented | 🟡 Important |
| **Paste-marker atomic segments** | ✅ segmentWithMarkers | ❌ Not implemented | 🟡 Important |
| **Paste marker renumbering** | ✅ On delete, shift IDs | ❌ Not implemented | 🟡 Important |
| **CSI-u paste decode** | ✅ Decode control bytes in paste | ❌ Not implemented | 🟡 Important |
| **History draft preservation** | ✅ Draft saved on history browse | ❌ Lost on return | 🟡 Important |
| **Dynamic editor height** | ✅ 30% of terminal, min 5 | ❌ Fixed 12 lines | 🟢 Minor |
| **Slash command registry** | ✅ 20 builtins + extensions | ❌ Manual case dispatch | 🔴 Critical |
| **Undo coalescing (fish-style)** | ✅ Word chars merge, spaces boundary | ✅ Same logic | ✅ Already done |
| **Redo** | ❌ Not implemented | ✅ Ctrl+Z redo | ✅ kmet ahead |
| **Kill line** | ❌ Not implemented | ✅ Ctrl+W kill line | ✅ kmet ahead |
| **Grapheme-aware movement** | ✅ Intl.Segmenter | ✅ Pure Clojure | ✅ Already done |
| **Word boundaries** | ✅ Intl.Segmenter word mode | ✅ Regex-based | ✅ Already done |
| **Sticky column (vertical)** | ✅ Decision table | ✅ Basic | ✅ Already done |
| **Character jump (f/t)** | ✅ Multi-line search | ✅ Basic | ✅ Already done |
| **Page up/down** | ✅ Cursor moves by page | ✅ Same | ✅ Already done |
| **Kill ring** | ✅ Accumulate, rotate, yank-pop | ✅ Same | ✅ Already done |
| **Bracketed paste** | ✅ Full support | ✅ Basic | ✅ Already done |
| **Scroll indicators** | ✅ ↑ N more / ↓ N more | ✅ Same | ✅ Already done |
| **Cursor marker for IME** | ✅ CURSOR_MARKER | ✅ Same | ✅ Already done |
| **Padding** | ✅ Configurable paddingX | ✅ Same | ✅ Already done |

