# kmet ↔ pi alignment: feature gap analysis

Reference: `~/src/cvstree/pi/` — `packages/coding-agent/src/**`, `packages/tui/src/**`,
`packages/ai/**`, `packages/agent/**`. Kmet side: `src/kmet/**`.

Goal: enumerate what pi exposes that kmet does not, so a "full alignment" pass has a
concrete work list. "Aligned" means the behavior exists in kmet with equivalent
semantics (EDN/Clojure adaptation); gaps are pi features with no kmet counterpart.

## Summary

kmet is a close port of pi's interactive TUI + agent core: the component/TUI
framework, agent loop, tools, provider/auth subsystem, sessions (EDNL), compaction,
skills, prompt templates, themes, and the extension input/event/UI surfaces are
functionally aligned. The remaining gaps cluster in the CLI surface, rendering
(mermaid/latex/search/images), the settings surface, and the extension API.

## Deliberately out of scope (locked decisions)

| Area | pi feature | kmet decision |
|---|---|---|
| Additional run modes | `--mode rpc` / `--mode json`, `pi server` / `pi client` / `pi rpc`, `packages/server`, `packages/client`, `packages/protocol` (CBOR), `docs/rpc.md`, `docs/sdk.md` | Not ported — kmet ships interactive + print modes only |
| Project trust | `core/project-trust.ts`, `core/trust-manager.ts`, `/trust`, `trust-selector.ts`, `--approve`/`--no-approve`, `defaultProjectTrust`, `trust.json` | Not ported — project settings/extensions load unconditionally |
| Package manager | `core/package-manager.ts`, `package-manager-cli.ts` (`pi install/remove/update/list`), `packages` setting, `npmCommand` | Not ported — resources come from local dirs only |
| Session interop / JSONL | pi JSONL session files, `/export` `.jsonl`, `--export` from JSONL | EDNL-only by design (see `session.md`) — `/export` writes standalone HTML |

## Gap analysis

### 1. CLI surface

**Subcommands** (pi has; kmet has none — only flags + `-main` dispatch):

| pi command | ref |
|---|---|
| `pi auth check` / `pi auth print-api-key` / `pi auth print-bearer-token` | `cli/auth-check.ts`, `cli/auth-command.ts`, `cli/credential-print.ts` |
| `pi config` (resource config TUI) | `cli/config-selector.ts` |
| `pi --export <session-file> [path]` | `main.ts` (`--export`) |

**Flags** — kmet covers `-p/--print`, `-c/--continue`, `-r/--resume`, `--model`,
`--provider`, `--models`, `--list-models`, `--system-prompt`, `--append-system-prompt`,
`-t/--thinking`, `-d/--debug`, `-h/--help`, `@files`, positional messages
(`src/kmet/core.clj` vs pi `cli/args.ts`). Missing:

| flag | purpose |
|---|---|
| `--api-key` | API key for a model specified via `--model`/`--models` |
| `--exclude-tools`, `--tools`, `--no-tools`, `--no-builtin-tools` | tool selection for one run (kmet has in-TUI `/tools` only) |
| `--extension` | load an extension file/dir for one run |
| `--export` | export a session file to HTML/EDNL from the CLI |
| `--fork` | start from a fork of a previous user message |
| `--session`, `--session-dir`, `--session-id` | session selection (kmet has `--resume` browsing only) |
| `--no-context-files`, `--no-extensions`, `--no-prompt-templates`, `--no-skills`, `--no-themes`, `--no-session` | disable subsystems for one run |
| `--offline` (+ `PI_OFFLINE`) | skip all startup network operations |
| `--prompt-template` | use a prompt template for one run |
| `--skill` | invoke a skill for one run |
| `--theme` | theme for one run |
| `--tui-mode` | `regular` vs experimental `fullscreen` TUI |
| `--verbose` | verbose logging |
| `--version` | print version |

### 2. Interactive features & rendering

| Feature | pi ref | kmet status |
|---|---|---|
| **Mermaid diagram rendering** | `modes/interactive/components/mermaid.ts` + `markdown.mermaid` setting (`off`/`final`/`streaming`) | Missing |
| **LaTeX rendering** (`$…$`, `$$…$$`) | `packages/tui/src/latex.ts`, wired in `tui/src/components/markdown.ts` | Missing |
| **Alt-screen search** (search overlay over the transcript) | `packages/tui/src/alt-screen-search.ts`, `tui-alt-screen.ts` | Missing |
| **Images in chat** | `terminal.showImages`, `terminal.imageWidthCells`, `images.autoResize`, `images.blockImages` settings; `show-images-selector.ts` | Partial — tool-execution images only; no settings, no inline/custom-message images |
| **Cache-miss notices** | `showCacheMissNotices` setting | Missing |
| **Skill invocation presentation** | `components/skill-invocation-message.ts` | Partial — kmet renders skills as labeled read tool calls (`app/ui/tool_execution.clj`), no dedicated message component |
| **Custom entry rendering** | `registerEntryRenderer` + `components/custom-entry.ts` | Missing — kmet persists custom entries but never renders them |

### 3. Slash commands

kmet covers: settings, model, scoped-models, export (HTML), import, share, copy, name,
session, hotkeys, fork, clone, tree, login, logout, new, compact, resume, continue,
reload, quit, help, tools, theme — full parity with pi's built-in command set.

### 4. Settings

kmet (`config.clj`) covers: provider/model/thinking/theme/session-dir/
http-idle-timeout-ms/system-prompt/append-system-prompt/retry
(enabled/max-retries/base-delay-ms)/enabled-models/hide-thinking-block/
extensions/skills/prompts/themes dirs, compaction thresholds, `.kmet/SYSTEM.md` +
`APPEND_SYSTEM.md` discovery, `KMET_PROVIDER`/`KMET_MODEL` env vars.

Missing (pi `docs/settings.md`):

| setting | purpose |
|---|---|
| `enableInstallTelemetry`, `enableAnalytics`, `trackingId` | anonymous install/analytics pings (pi.dev infrastructure) |
| `doubleEscapeAction` | double-escape behavior: `tree` / `fork` / `none` |
| `treeFilterMode` | default `/tree` filter (kmet hardcodes `:default` in `interactive.clj` `tree-filter-modes`) |
| `editorPaddingX`, `outputPad` | editor/message padding |
| `autocompleteMaxVisible` | autocomplete dropdown size (kmet constant) |
| `showHardwareCursor` | hardware cursor for IME support |
| `tuiMode`, `fullscreenExitOutput`, `fullscreenScrollbar` | fullscreen TUI mode |
| `httpProxy` | proxy URL applied as HTTP(S)_PROXY (kmet reads proxy env vars only — `ai/proxy.clj`) |
| `warnings.anthropicExtraUsage` | Anthropic subscription extra-usage warning |
| `branchSummary.reserveTokens`, `branchSummary.skipPrompt` | branch summarization config |
| `retry.provider.timeoutMs` / `maxRetries` / `maxRetryDelayMs` | provider/SDK retry tuning |
| `steeringMode`, `followUpMode` | queue drain mode (kmet hardcodes `:all` in `app/loop.clj`; pi defaults `one-at-a-time`) |
| `transport`, `websocketConnectTimeoutMs` | provider transport selection |
| `terminal.showImages`, `terminal.imageWidthCells`, `terminal.clearOnShrink` | terminal image display |
| `images.autoResize`, `images.blockImages` | image handling |
| `shellPath`, `shellCommandPrefix` | shell customization |
| `markdown.codeBlockIndent`, `markdown.mermaid` | markdown rendering |
| `enableSkillCommands` | register skills as `/skill:name` commands |
| `showCacheMissNotices` | cache-miss transcript notices |
| `thinkingBudgets` | per-level thinking token budgets |

### 5. Extension API

Aligned (`app/extensions.clj` + `app/event_bus.clj`): input + before-agent-start hooks,
provider registration (incl. OAuth), `ctx.models.*` facades, UI registry
(select/confirm/input/notify/custom/widgets/footer/header/editor/theme/status/
working-indicator/terminal-input), session append-entry/message/labels, and most
agent/session events (see Appendix).

Missing (pi `core/extensions/types.ts`):

| API | purpose |
|---|---|
| `registerTool` | extension-defined tools (kmet `app/tools/registry.clj` has `register-tool!` but no extension-facing wrapper) |
| `registerCommand`, `registerShortcut`, `registerFlag`/`getFlag` | extension commands/shortcuts/CLI flags |
| `registerMessageRenderer`, `registerMarkdownTransformer`, `registerEntryRenderer` | custom rendering |
| `sendUserMessage` (deliverAs steer/followUp) | queue user messages from extensions |
| `setModel`, `getThinkingLevel`, `setThinkingLevel` | model/thinking control |
| `exec` | shell command helper |
| `getActiveTools`/`getAllTools`/`setActiveTools`, `getCommands` | tool/command introspection |
| `registerProvider` `streamSimple`/`refreshModels` | custom wire APIs + live model refresh |
| Events: `resources_discover`, `session_info_changed`, `session_before_switch`, `session_before_fork`, `session_before_compact`, `context`, `before_provider_request`, `before_provider_headers`, `after_provider_response`, `thinking_level_select`, `tool_call`, `tool_result` | see Appendix |

### 6. Smaller gaps

- **Tree filter keybindings** — pi has standalone `app.tree.filter.*` bindings; kmet has
  the filter modes in `/tree` (`tree-filter-modes`, `cycle-filter!`) but no standalone bindings
- **`/settings` menu breadth** — kmet `/settings` covers thinking/hide-thinking/retry only;
  pi's selector also covers theme, mermaid, images, tui-mode, double-escape, tree filter
- **Auth selector/dialog components** — pi `login-dialog.ts`, `oauth-selector.ts`,
  `session-selector-search.ts`; kmet's terminal `/login` covers the flows
- **`packages/agent` (`@earendil-works/pi-agent-core`)** — general-purpose agent library
  layer; kmet's `app/loop.clj` covers the coding-agent equivalent, so no port needed

## Appendix: Event type vocabulary

pi events (`core/extensions/types.ts`) → kmet status (`app/event_bus.clj` `event-types`).

| pi event | kmet | notes |
|---|---|---|
| `session_start` | ✅ `:session-start` | reason startup/reload/new/resume/fork; kmet lacks `reload` reason flag granularity |
| `session_info_changed` | ❌ | kmet `:name`/`session` commands mutate state without an event |
| `session_before_switch` / `session_before_fork` | ❌ | kmet has `:session-before-tree` but no switch/fork counterparts |
| `session_before_compact` / `session_compact` | ~ | kmet emits `:compaction-start`/`:compaction-end` (reason manual/threshold/overflow/auto) |
| `session_before_tree` / `session_tree` | ✅ `:session-before-tree` / `:session-tree` | incl. cancel/summary/extension-summary results |
| `session_shutdown` | ~ | kmet clears extension state on `/reload` but emits no event |
| `context` | ~ | kmet has `:context-replaced` (different shape) |
| `before_agent_start` | ✅ | hook, not event |
| `agent_start` / `agent_end` / `agent_settled` | ✅ `:agent-start` / `:agent-end` / `:agent-settled` | |
| `turn_start` / `turn_end` | ✅ `:turn-start` / `:turn-end` | |
| `message_start` / `message_update` / `message_end` | ✅ | kmet `:message-update` carries `:delta` incl. tool-call |
| `tool_execution_start` / `_update` / `_end` | ✅ | |
| `tool_call` / `tool_result` | ~ | kmet emits execution events but no call/result with result-transform |
| `model_select` | ✅ `:model-select` | kmet adds `:source` (:set/:cycle) |
| `thinking_level_select` | ❌ | |
| `user_bash` | ✅ `:user-bash` | |
| `input` | ✅ | input hooks (transform/handled) |
| `before_provider_request` / `before_provider_headers` / `after_provider_response` | ❌ | no provider request/response hooks |
| `resources_discover` | ❌ | context discovery is static (`app/context.clj`) |
| `project_trust` | — | out of scope (see locked decisions) |

kmet-only additions: `:status`, `:error`, `:queue-update`, `:context-replaced`,
`:auto-retry-start`, `:auto-retry-end`, `:compaction-start`, `:compaction-end`.
