# kmet ↔ pi extension API alignment plan

Goal: close the gap between what pi gives extensions and what kmet gives
extensions, so that the extension surface (api map + `kmet.extension`
wrappers + event vocabulary + component/tool contracts) matches pi's
`ExtensionAPI` / `ExtensionContext` / `ExtensionCommandContext` /
`ExtensionUIContext` shapes as closely as the Clojure port allows —
without ever leaking `kmet.app.*` internals into extension contexts.

Reference: `packages/coding-agent/src/core/extensions/types.ts` in
`~/src/cvstree/pi` (ExtensionAPI ~line 1198, ExtensionUIContext ~199,
ExtensionContext ~340, ExtensionCommandContext ~390, ToolDefinition +
ToolRenderContext further down).

Status of this document: **P0–P12 complete** (ctx object, provider
registration, ui.editor + getTheme(name), tool_call terminate, tool
execute signal+ctx, tool renderers, shortcuts, markdown transformers,
provider events, cancellable session before-events, sendMessage
custom messages, ui.custom async factories + dispose, parity docs).

---

## 1. Current kmet extension surface (baseline)

Everything below already exists and is verified:

- Api map built by `create-extension-api` (`src/kmet/app/extensions.clj`
  ~line 380): `:register-command! :unregister-command! :get-commands
  :register-tool! :unregister-tool! :get-all-tools :get-active-tools
  :set-active-tools :on-event :emit-event! :on-input :on-before-agent-start
  :on-tool-call :on-tool-result :register-flag! :get-flag
  :register-entry-renderer! :register-message-renderer! :set-model
  :get-thinking-level :set-thinking-level :send-user-message :exec :ui
  :models :session` (+ `:extension-name/:extension-path/:extension-dir`).
- Wrappers in `src/kmet/extension.clj` mirror the map 1:1.
- `:ui` submap: select/confirm/input/notify/custom/on-terminal-input/
  set-status/set-widget/set-footer/set-header/set-title/editor-text
  get/set/paste/working-indicator/message/visible/hidden-thinking-label/
  editor-component/autocomplete-provider/get-theme/get-all-themes/
  set-theme/tools-expanded get/set.
- `:models` submap: get-all/get-available/find/has-configured-auth/
  get-provider-auth-status/get-api-key-and-headers/
  get-registered-provider-config/get-registered-provider-ids (read-only).
- `:session` submap: append-entry!/append-message!/get-entries/
  set-label!/get-label/set-name!/get-name.
- Events (`kmet.app.event-bus/event-types`): :agent-start :agent-end
  :agent-settled :turn-start :turn-end :message-start :message-update
  :message-end :tool-execution-start :tool-execution-update
  :tool-execution-end :status :session-start :session-shutdown :user-bash
  :session-before-tree :session-tree :model-select :thinking-level-select
  :session-info-changed :context-replaced :compaction-start :compaction-end.
- Tool record (`kmet.app.tools.tool/Tool`) already carries pi's fields:
  `name label description prompt-snippet prompt-guidelines parameters
  execute render-call render-result constrained-sampling render-shell
  prepare-arguments execution-mode streams?` — but only `name/description/
  parameters/execute/streams?` are *wired*; `render-call`/`render-result`
  of extension tools are **not** consulted by the transcript, and
  `constrained-sampling/render-shell/prepare-arguments/execution-mode` are
  carried but inert for extension tools.
- Tool execution (`kmet.app.tools.registry/execute-tool`): execute arities
  `(fn [args])` or `(fn [args on-update])` when the tool declares
  `:streams?`. The agent's cancel signal reaches builtin tools only through
  `kmet.app.tools.bash/*cancel-signal*` (dynamic var bound in the loop).
- Command handler contract: `(fn [cs args])` where `cs` is the internal
  CoreState — a deliberate leak we want to eliminate.
- Event handler contract: `(fn [event-map])`.
- `ui-custom` factory contract: `(fn [tui theme kb close]) → component |
  duck-typed {:render :handle-input :invalidate}`; returns nil (no dialog)
  in headless mode. Components render via `kmet.tui.protocols/render`
  (records do NOT carry `:render` as a field).
- The interactive ui registry (`kmet.modes.interactive/
  build-extension-ui-registry`) already implements **`:editor`** (dialog with
  external-editor wiring) but `api-ui` and `kmet.extension` never expose it.

---

## 2. Gap inventory

Priority: P0 = structural (do first), P1..Pn = ordered by
value/effort. Effort: S (≤ half day), M (1–2 days), L (3+ days).

| # | pi capability | kmet today | effort |
|---|---------------|------------|--------|
| G1 | Handler **context object** (ExtensionContext/CommandContext) | none; `cs` leak + `(fn [event])` | L |
| G2 | `registerProvider`/`unregisterProvider` | machinery ported but **unwired** (`kmet.ai.models/register-provider-config!`) | S | ✅ P1 |
| G3 | `ui.editor` dialog + `ui.getTheme(name)` load-without-switch | `:editor` implemented in registry, not exposed; get-theme only returns current | S | ✅ P2 |
| G4 | tool_call result `terminate: true` | block/args only | S | ✅ P3 |
| G5 | tool `execute(toolCallId, params, signal, onUpdate, ctx)` | `(fn [args])` / `(fn [args on-update])`, no signal, no ctx | M |
| G6 | tool `renderCall`/`renderResult` for extension tools | record fields exist; not wired into transcript | M |
| G7 | `registerShortcut` | nothing | M |
| G8 | `registerMarkdownTransformer` | nothing | M |
| G9 | Provider events: `context`, `before_provider_request`, `before_provider_headers`, `after_provider_response` | nothing | M |
| G10 | Cancellable `session_before_switch` / `session_before_fork` / `session_before_compact` | :session-shutdown fires pre-switch (info only); :compaction-start/end (not cancellable) | M |
| G11 | `sendMessage(triggerTurn)` / deliverAs `nextTurn` | append-message! is display-only | M |
| G12 | `ui.custom` async factories + component `dispose()` | sync only, no dispose | S |
| G13 | ctx fields `model`, `scopedModels`, `isIdle()`, `hasPendingMessages()`, `shutdown()`, `getContextUsage()`, `compact()`, `getSystemPrompt()`, session control (`waitForIdle/newSession/fork/navigateTree/switchSession/reload`) | nothing (folded into G1) | (G1) |
| G14 | `getCommands` parity, `setModel` boolean return, `getTheme(name)` | close; verify + document | S |
| — | `refreshTools` | **not needed** — kmet's tool registry is a live atom | — |
| — | `project_trust` / `resources_discover` | needs features kmet doesn't have (trust; loader contribution) | defer |
| — | `events` EventBus | parity via `on-event`/`emit-event!` (custom events work) | — |
| — | `exec`, flags, session name/labels, message/entry renderers, ui.select/confirm/input/notify/status/widget/footer/header/title/theme, autocomplete, editor-component | parity | — |

---

## 3. Design: the extension context (G1 — P0, do first)

### 3.1 Shape (mirrors pi `ExtensionContext` + `ExtensionCommandContext`)

```clojure
{:mode            :tui | :rpc | :json | :print   ; :print default headless
 :has-ui          bool
 :cwd             str
 :model           model-or-nil                    ; current model
 :scoped-models   [...]                           ; empty = no scoping
 :thinking-level  kw-or-nil
 :is-idle         (fn [] bool)
 :has-pending-messages (fn [] bool)
 :signal          (fn [] sig-or-nil)              ; current abort signal
 :abort           (fn [])
 :shutdown        (fn [])
 :get-context-usage (fn [] {:tokens n :context-window n :percent n} | nil)
 :compact         (fn [& [{:keys [custom-instructions on-complete on-error]}]])
 :get-system-prompt      (fn [] str)
 :get-system-prompt-options (fn [] map)           ; command ctx
 :wait-for-idle   (fn [] promise)                ; command ctx
 :reload          (fn [])                        ; command ctx
 :new-session     (fn [& [{:keys [parent-session setup with-session]}]])   ; command ctx
 :fork            (fn [entry-id & [{:keys [position with-session]}]])      ; command ctx
 :navigate-tree   (fn [target-id & [{:keys [summarize custom-instructions]}]]) ; command ctx
 :switch-session  (fn [session-path & [{:keys [with-session]}]])           ; command ctx
 :is-project-trusted (fn [] false)               ; constant until kmet has trust
 :session         {:<read-only facades>}}         ; reuse existing :session api
```

### 3.2 Where the state comes from

`extensions/current-context` (`src/kmet/app/extensions.clj`) = merge of a
static headless default + a **`:build-context` capability** on the ui
registry (new), installed by interactive mode, which captures its live
state at call time:

- `mode`/`has-ui`: interactive registry installs → `:tui`/true; print and
  other modes don't → default `:print`/false. (This replaces the current
  implicit "ui-custom returned nil" headless heuristic.)
- `is-idle` / `has-pending-messages` / `signal` / `abort`: from the
  interactive-held agent-state (`:status`, `(:signal agent)`,
  `(:aborted agent)` — loop.clj already has these; interactive already
  owns the agent for its own flows).
- `shutdown`: interactive quit path (graceful, same as Ctrl+C handling);
  headless fallback `(System/exit 0)`.
- `get-context-usage`: from the loop's existing usage tracking (the same
  numbers the footer cost display shows); nil when unknown.
- `compact`: `loop/compact-context!` (manual path already exists — reuse).
- `get-system-prompt` / `get-system-prompt-options`: `kmet.app.prompts` +
  the assembled options the interactive mode already builds for
  `:before-agent-start`.
- `wait-for-idle`: promise delivered by the interactive loop when the
  agent settles (`:agent-settled` event).
- Session control (`new-session/fork/navigate-tree/switch-session/reload`):
  interactive already implements these as commands/UI flows
  (`fork-from-message`, tree navigation, `/reload`); expose thin fns that
  run the same code paths and return `{:cancelled bool}`.

### 3.3 Threading (fixed arity, no legacy shim)

- **Commands**: `create-extension-api`'s `:register-command!` stores the
  extension handler under a new `:extension-handler` key in the command
  map. The interactive command runner calls
  `(handler ctx args)` when `:extension-handler` is present, else keeps
  `(handler cs args)` for builtins. No arity sniffing, no builtin churn.
- **Events**: extension `on-event` registrations are wrapped at
  registration time: the wrapper builds the ctx and calls `(h event ctx)`
  — fixed arity-2 contract, no compat shim. An arity-1 handler fails
  fast with an ArityException, which the bus logs as a handler error
  (decision: drop the legacy fallback — it masked real handler-body
  ArityExceptions behind a confusing retry).
- **Tool execute**: see G5 (opt-in arity, no breaking change).
- `extensions/tools.clj` migrates to the new contract:
  `(fn [ctx args])`, headless branch keyed off `(:mode ctx)` / `(:has-ui ctx)`
  instead of the "ui-custom returned nil" heuristic.

### 3.4 Tests

- Unit: `current-context` default shape (headless) — all fns callable,
  `:mode :print`, `:has-ui false`.
- Registry-driven: fake `:build-context` → extension command handler
  receives ctx; event handlers always receive `(event ctx)` (arity-1
  fails fast).
- Integration (interactive): `/tools` runs headless via `:mode`.
- Negative: ctx never exposes CoreState keys (no `:cs`, no atoms leak —
  assert on `(keys ctx)`).

### 3.5 P0 implementation notes (done — deviations from the plan)

- `:mode` is `:interactive` (kmet's own mode name) / `:print` — not pi's
  `:tui`; kmet has no rpc/json modes. Documented in the ctx docstring.
- Command threading via `:extension-handler` key on the registered command
  map (builtins keep `(handler cs args)`; runner in interactive.clj
  dispatches on the key's presence).
- Event threading via `extensions/wrap-event-handler`: fixed `(event ctx)`
  arity — the legacy `(event)` fallback was dropped (it masked real
  handler-body ArityExceptions behind a confusing retry); arity mistakes
  now fail fast and surface through the bus's handler-error log.
- `:cwd` comes from the footer provider's cwd; `:shutdown` = `tui-stop`
  (the /quit path); `:wait-for-idle` polls the agent status (100ms) and
  returns a promise; `:compact` runs `loop/compact-context!` in a future
  with on-complete/on-error.
- Session control wired to the existing flows: `handle-new-session`,
  `fork-at!` (entry-id), `navigate-tree!` (entry resolved by id),
  `restore-session!` (path), `handle-reload`. Missing/invalid targets
  return `{:cancelled true}`.
- `:get-system-prompt-options` reconstructs the build-system-prompt args
  (custom-prompt / append-prompt / context-files) — not the internal
  options map.
- `:get-context-usage` returns nil without an active session (pi returns
  null), not `{:tokens 0 ...}` — the footer provider has no session to
  measure until one is restored.
- `extensions/tools.clj` migrated: `(fn [ctx args])`, headless branch keyed
  off `(:has-ui ctx)`; verified end-to-end (sci load, notify path, dialog
  + toggle + persist through the real bus).

---

## 4. Work packages

### P1 — Provider registration (G2, S) — ✅ done

- `api-models` (`extensions.clj`): `:register-provider!` / `:unregister-provider!`
  backed by `kmet.ai.models/register-provider-config!` /
  `unregister-provider-config!` (validated eagerly by
  `provider-composer/validate-extension-provider`, recomposes
  builtin + models.edn + extension layers automatically;
  `unregister-provider-config!` is a safe no-op for never-registered ids).
- Wrappers in `kmet.extension`: `models-register-provider!`,
  `models-unregister-provider!`; nullable-api `:models` captures both.
- Tests: `test-extension-provider-registration` (real api via
  `create-extension-api`): model appears in `get-all`; unregister → gone;
  broken config (bad api) → throws, stored state untouched.

### P2 — Expose `ui.editor` + `getTheme(name)` (G3, S) — ✅ done

- `api-ui`: `:editor` (registry impl exists — modal dialog, promise for
  the submitted text, nil headless) and `:get-theme-by-name` (pure lookup
  over the theme store — builtins + custom themes dir).
- Wrappers: `ui-editor`, `ui-get-theme-by-name` (+ nullable-api capture).
- Theme alignment: pi's `getTheme(name)` returns the real registered
  Theme (created on demand from builtin JSON / source-path files),
  **`undefined` for unknown names**. kmet's store holds the same real
  Theme records, so the lookup returns them directly — no fallback:
  new `kmet.tui.theme/get-theme-by-name` (nil for unknown); the
  fallbacking `get-theme` stays for internal callers (config/startup).
- Tests: `test-ui-editor-and-theme-by-name-headless` (nil headless;
  dark/light resolve; unknown → nil).

### P3 — `terminate` in tool_call hooks (G4, S) — ✅ done

- pi's exact semantics (verified in `packages/agent/src/agent-loop.ts`
  `shouldTerminateToolBatch` / `prepareToolCall`): `terminate` is **only
  honored on blocked calls** (a `{:block true :terminate true}` result;
  bare `:terminate` is ignored), rides on the blocked error result, and
  the batch stops the run **only when EVERY finalized call in it carries
  the hint** (any executed call kills it). It does NOT abort the run:
  `prepareNextTurn`/`shouldStopAfterTurn` still run and the outer
  steering/follow-up queue still drains — only the tool-call continuation
  (follow-up LLM call) is skipped.
- `loop.clj`: blocked results carry `:terminate true`;
  `execute-tool-calls-{parallel,sequential}!` return
  `{:results [...] :terminate bool}`; the turn loop runs `after-turn!`
  unconditionally then settles on `(or terminate stop?)`.
- Tests: `test-loop-before-tool-call-terminate` (all blocked+terminate →
  exactly one LLM call, blocked results in transcript, status idle) and
  `test-loop-before-tool-call-terminate-mixed-batch` (one blocked+terminate
  + one executed → follow-up LLM call still happens).

### P3 — `terminate` in tool_call hooks (G4, S)

- `loop.clj` `before-tool-hook-result`: accept `:terminate true` in the
  result (alongside `:block`/`:args`). After tool results are appended,
  a terminate flag on any call aborts the run: set the run signal, return
  `:aborted` from the turn, no follow-up LLM call (pi: `terminate` aborts
  the agent loop).
- Ordering: block wins over terminate (blocked calls never execute);
  terminate short-circuits the remaining calls of that batch.
- Tests: hook returning `{:terminate true}` → turn ends, tool results
  still in transcript, no continuation; combined with `:block` → block wins.

### P4 — Tool execute: signal + ctx (+ richer on-update) (G5, M)

- New opt-in contract: when the Tool record declares `:contextual? true`
  (new field; pi always passes these — we gate to keep builtins untouched),
  `execute` receives `(fn [args on-update signal ctx])`.
- `kmet.app.tools.registry/execute-tool` gains an opts arity:
  `(execute-tool name args {:on-update f :signal s :ctx c})`; loop passes
  `(:signal agent)` and `(extensions/current-context)` (or a lighter
  per-call ctx — decide: full ctx, pi parity).
- `kmet.app.extension` tool registration helper gains
  `:contextual? true` + docstring; `extensions/tools.clj` unaffected
  (it registers no tools).
- The `:signal` is the same signal bash's `*cancel-signal*` receives —
  Escape mid-call then lets an extension tool abort its own work
  (child process kill, polling loop).
- Tests: registry unit — arity dispatch (old 1-arg, old 2-arg streams?,
  new 4-arg contextual); loop integration — Escape mid-extension-tool
  marks `:aborted` and the tool observed the signal.

### P5 — Tool renderCall/renderResult for extension tools (G6, M)

- `kmet.app.ui.chat-history` and the live tool-execution path
  (`interactive.clj` / wherever live `make-tool-execution` components are
  built): when the message's tool has a record with `:render-call` /
  `:render-result` fns, pass them as `:render-call-fn` / `:render-result-fn`
  to `make-tool-execution` (which already accepts and uses them —
  `tool_execution.clj` `custom-render-call-atom`).
- Renderer signatures stay the app's existing ones
  (`(name args theme width context)` / `(content is-error theme width
  expanded? started-at ended-at truncation context)`) — pi-compatible
  modulo the context map; document in the tool contract.
- The `ToolRenderContext` (`tool-execution-context`) already exposes
  args/tool-call-id/invalidate/last-component/state/set-state!/cwd/
  is-partial/expanded/is-error — extension renderers get exactly that map.
- Tests: register a tool with `:render-call` → transcript component uses
  it (render output contains the custom marker); fallback to default
  renderer when absent; persisted/replayed tool messages still render.

### P6 — `registerShortcut` (G7, M)

- New capability `:register-shortcut! (key-id, {:description, :handler})`
  on the api + `register-shortcut!` wrapper; deregistration on unload.
- Implementation: ui registry capability that registers into the
  interactive `KeybindingsManager` (app keybindings
  `kmet.app.keybindings` — verify the manager's register API supports
  adding bindings at runtime; if not, add it in `kmet.tui.keybindings`).
- Dispatch precedence: **extension shortcuts checked before builtin app
  bindings** (matches the "extensions never clobbered" rule in reverse —
  document the decision; pi lets the extension handler run and fall
  through to builtins via `super`-style delegation, which we approximate
  by running extension handlers first and letting them consume the key).
- Tests: register → key triggers handler (TUI-level key dispatch test with
  a fake terminal input), deregister on unload, builtin bindings still win
  when the extension handler doesn't consume (if we choose pass-through).

### P7 — Markdown transformer (G8, M)

- New registry `markdown-transformers` in `extensions.clj` (like
  entry/message renderers) + `register-markdown-transformer!` wrapper;
  transformer shape: `(fn [markdown {:keys [message-type is-streaming
  available-width]}]) → string`.
- Integration: the chat markdown render path — user/assistant message
  components (`kmet.app.ui.user-message`, `assistant-message`) or the
  shared markdown component — applies registered transformers to the
  content before rendering (streaming: applied per chunk; guard against
  transforming mid-code-block? pi applies per render — document that
  transformers must be idempotent for streaming).
- Tests: transformer registry unit; render integration — content with a
  marker is transformed in the rendered output; multiple transformers run
  in registration order; unload removes.

### P8 — Provider + context events (G9, M)

Layer-boundary note: `kmet.ai.*` may not depend on `kmet.app.*`, so the
event bus cannot be reached from `kmet.ai.llm` / `kmet.ai.api.*`
directly. Follow the existing pattern (`auth/set-config-key-source!`):
injectable hooks in `kmet.ai` with default no-ops.

- `kmet.ai.llm` (and `kmet.ai.api.shared` where headers assemble):
  - `set-before-provider-hooks! (fn [payload]) → payload-or-nil`
  - `set-before-provider-headers-hook! (fn [headers]) → headers` (mutate
    in place; nil value deletes a header — pi parity)
  - `set-after-provider-response-hook! (fn [{:keys [status headers]}])`
  - `set-context-hook! (fn [messages]) → messages-or-nil`
- `extensions.clj` installs bus bridges at startup: hook fns emit
  `:context` / `:before-provider-request` / `:before-provider-headers` /
  `:after-provider-response` events and apply the first non-nil result.
- Event payloads (pi parity): `:context {:messages [...]}`;
  `:before-provider-request {:payload ...}`; `:before-provider-headers
  {:headers {...}}` (handlers mutate the map); `:after-provider-response
  {:status n :headers {...}}`.
- Tests: unit — hook fns are called at the right points with right shapes
  (fake provider round-trip in `kmet.ai.llm`); extension bridge — event
  fires, result replaces payload; ai layer stays free of kmet.app deps
  (self-contained guard must still pass).

### P9 — Cancellable session before-events (G10, M)

- New events in `event-bus/event-types`: `:session-before-switch`
  `{:reason :new | :resume :target-session-file}`,
  `:session-before-fork` `{:entry-id :position :before | :at}`,
  `:session-before-compact` `{:preparation ... :reason :manual |
  :threshold | :overflow :will-retry bool}`.
- Fire points: interactive's switch-session and fork flows (before the
  existing `:session-shutdown`); `loop/compact-context!` and
  `maybe-compact!` (manual + threshold + overflow paths) — collect
  handler results and honor `{:cancel true}` (skip the operation; for
  compact, pi also allows `{:compaction ...}` customization — defer
  customization, land cancel first).
- `:session-before-compact` handlers receive a `:signal` in the event
  (pi passes AbortSignal) — wire the run signal.
- Tests: event fires with correct payload on each path; cancel aborts the
  switch/fork/compaction; non-cancel continues; emission order
  (before-compact → compaction-start → … → compaction-end).

### P10 — sendMessage triggerTurn (G11, M)

- `:append-message!` opts gain `{:trigger-turn? bool :deliver-as
  :steer | :follow-up | :next-turn}` (pi: sendMessage).
- Interactive wiring: when `:trigger-turn?`, after appending, route the
  custom message into the agent loop as a user message (reuse the
  submit path; `:next-turn` queues until the current run settles).
- `send-user-message` already triggers turns; add `:expand-prompt-templates?`
  opt (dispatch extension commands / expand skill commands — kmet's
  prompt-template machinery already exists in `kmet.app.prompts`).
- Tests: append without trigger stays display-only (current behavior);
  with trigger → loop runs with the custom content in context;
  `:next-turn` while streaming queues.

### P11 — ui.custom async factories + dispose (G12, S)

- Registry `:custom`: if the factory returns a `IDeref` (clojure
  `promise` / CompletableFuture), deref with a timeout (e.g. 5s) before
  treating it as the component (pi: Promise<Component>); errors/timeouts
  → same error path as today.
- On `close`, if the component has a `:dispose` fn (map or record
  field/protocol), call it before removing (pi: `dispose?()`).
- Tests: promise-returning factory renders; dispose called on close;
  timeout path returns nil dialog.

### P12 — Parity verification + docs (G14, S)

- `set-model` returns boolean (false when no API key) — verify registry
  impl and document (pi: `Promise<boolean>`).
- `get-commands` return shape parity — document.
- Update `docs/extensions.md`: the ctx contract, new capabilities, tool
  contract (contextual execute, renderers), shortcut precedence, event
  list. Update `extensions/README.md` layout rules if the contract
  changes single-file limits.
- Update `kmet.extension/create-nullable-api` fixture to capture the new
  capabilities so tests keep working.

---

## 5. Sequencing

```
P0 (ctx) ──► P1, P2, P3, P11, P12   (independent, small)
    │
    ├─► P4 (tool execute ctx)  ──► P5 (tool renderers)
    ├─► P8 (provider events)   ──► P9 (session before-events)
    └─► P6 (shortcuts), P7 (markdown), P10 (triggerTurn)
```

- P0 first: everything else either consumes the ctx (P4, P5, P9) or is
  independent.
- P8 and P9 both touch `loop.clj`; do them together to avoid double
  edits in the same regions.
- P6 needs a keybindings-manager register API check before scoping —
  if the manager can't register at runtime, P6 grows (add registration
  to `kmet.tui.keybindings` first).

## 6. Non-goals (explicitly deferred)

- `project_trust` / `isProjectTrusted` — kmet has no project-trust
  feature; ctx returns `false` constant until one exists.
- `resources_discover` — requires the skills/prompts/themes loaders to
  accept contributed paths; revisit after P0–P10.
- `refreshTools` — kmet's registry is a live atom; `get-all-tools` always
  sees new registrations. Never needed.
- `constrainedSampling`, `prepareArguments`, `executionMode` for
  extension tools — fields exist on the record but the loop/UI has no
  consumers; wire them only if a shipped extension needs them.
  (`renderShell` IS wired — P5 passes the record's `:render-shell` to the
  transcript component for extension tools too.)
- `oauth` provider registration (pi `registerProvider` oauth block) —
  kmet's OAuth flows are internal; extension providers get
  `$ENV`/`!command` api-key resolution only.

## 7. Definition of done

1. Every P-item has: api map entry + `kmet.extension` wrapper +
   deregistration on unload + test(s) in `test/kmet/app/test_extensions.clj`
   (or the interactive-ui suite where mode wiring is involved).
2. `extensions/tools.clj` runs on the ctx contract (P0) with its headless
   branch driven by `:mode`; full scratch verification still ALL OK.
3. No new `kmet.app.*`/`kmet.modes.*`/`kmet.libs.*` reachable from
   extension contexts (isolation tests extended per capability).
4. `kmet.ai` self-contained guard still passes after P8 (bridges are
   injectable hooks, not deps).
5. Docs updated (P12); `docs/extensions.md` no longer describes any
   behavior the code contradicts.
6. Full gate green: `bb lint` + `bb format-check` + `bb test` +
   `bb test-ext`; slow extension tests (`test-extension-lib-version-isolation`,
   `test-extension-bad-deps-fails-load`) re-run.

## 8. Reference index

pi sources (`~/src/cvstree/pi`):
- `packages/coding-agent/src/core/extensions/types.ts` — ExtensionAPI,
  ExtensionUIContext, ExtensionContext, ExtensionCommandContext,
  ToolDefinition, event interfaces.
- `packages/coding-agent/examples/extensions/tools.ts` — the example
  `/tools` extension (kmet port: `extensions/tools.clj`).

kmet sources:
- `src/kmet/app/extensions.clj` — api maps, isolation, load-fn,
  ui-call/registry, session facades.
- `src/kmet/extension.clj` — wrappers + `create-nullable-api` fixture.
- `src/kmet/modes/interactive.clj` — ui registry (`:editor`, `:custom`,
  set-model/status/footer…), command runner, session flows.
- `src/kmet/app/loop.clj` — tool-call hooks, tool execution (parallel/
  sequential), `compact-context!`, signal/abort handling.
- `src/kmet/app/event_bus.clj` — event vocabulary.
- `src/kmet/app/tools/registry.clj` + `tool.clj` — execute contract,
  Tool record fields.
- `src/kmet/app/ui/tool_execution.clj` + `chat_history.clj` — renderer
  wiring points for P5.
- `src/kmet/ai/models.clj` — `register-provider-config!` /
  `unregister-provider-config!` (P1).
- `src/kmet/ai/llm.clj`, `src/kmet/ai/api/shared.clj` — hook points for P8.
