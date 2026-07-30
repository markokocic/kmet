# kmet ↔ Pi Alignment

This document tracks known gaps between kmet's ToolExecutionComponent and Pi's
(the original TypeScript coding agent). The goal is full behavioral parity.

Legend:
- ✅ Aligned
- ⚠️ Partial — minor cosmetic difference
- ❌ Missing — requires infrastructure not yet in kmet

---

## 1. Tool rendering

### read

| Aspect | Pi | kmet | Status |
|---|---|---|---|
| **renderCall** | `read <path>` + `:start-end` (warning) | `read <path>` + `:start-end` (warning) | ✅ |
| **renderResult collapsed, no error** | `""` (empty text, nothing shown) | `nil` (no result section) | ✅ |
| **renderResult expanded** | Content lines in `toolOutput`, all lines, leading `\n`, no `N lines` header | Content lines in `tool-output`, all lines, leading blank line, no header | ✅ |
| **renderResult collapsed, error** | Up to 10 lines of content + `... (N more lines...)` | Up to 10 lines + `... N more lines` (error color on content) | ✅ |

### write

| Aspect | Pi | kmet | Status |
|---|---|---|---|
| **renderCall** | `write <path>` + content preview (10 lines, syntax highlighted) | `write <path>` + content preview (10 lines in muted Box) | ✅ |
| **renderResult success** | Empty container (nothing) | `nil` | ✅ |
| **renderResult error** | All error text lines joined with `\n`, in `error` color | All error lines joined with `\n`, in `error` color | ✅ |

### edit

| Aspect | Pi | kmet | Status |
|---|---|---|---|
| **renderCall** | `edit <path>` + diff preview inline via `renderShell: "self"` (Box with preview-dependent bg) | `edit <path>` + diff preview inline via `:render-shell :self` (Box with `tool-success-bg`/`tool-error-bg`) | ✅ |
| **renderResult** | Only when error or result diff differs from preview | Only when error (preview covers success case) | ✅ |

### bash

| Aspect | Pi | kmet | Status |
|---|---|---|---|
| **renderCall** | `$ <command>` in toolTitle bold + `(timeout Ns)` in muted | `$ <command>` in toolTitle bold + `(timeout Ns)` in muted | ✅ |
| **renderResult status** | No `done`/`exit N` text (background color indicates status) | No `done`/`exit N` text | ✅ |
| **renderResult expanded** | `\n${styledOutput}` — all lines in a single Text with leading newline | Leading blank line + each line as separate Text child | ✅ (visually equivalent) |
| **renderResult collapsed** | Last 5 **visual** lines (width-aware via `truncateToVisualLines`), leading blank line, truncated hint | Last 5 **visual** lines via `truncate-to-visual-lines`, leading blank line, hint truncated via `truncate-to-width` | ✅ |
| **renderResult duration** | `\n${muted("Elapsed X.Xs"/"Took X.Xs")}` — in-text newline | Blank line + muted text child | ✅ |
| **renderResult truncation warnings** | `[Truncated: ...]` and `[Full output: ...]` when output was truncated server-side | Same via `:truncation` metadata | ✅ |
| **renderResult keybinding hints** | `keyHint("app.tools.expand", "to expand")` renders clickable keybinding badge | `key-hint` via keybindings manager with dim/muted styling | ✅ |
| **Footer stripping** | Strips `[Showing...Full output:...]` by matching `\n\n[` + `fullOutputPath` | Moot — replaced by proper truncation metadata | ✅ |

---

## 2. Component architecture

| Aspect | Pi | kmet | Status |
|---|---|---|---|
| **Timing ownership** | Component via `executionStarted`/`isPartial` | Component via `started-at-atom`/`ended-at-atom` | ✅ |
| **Timing from chat/UI** | None — tool definitions own timing | None — auto-set in `set-content!`/`set-error!` | ✅ |
| **Background running** | `toolPendingBg` when `isPartial` | `:tool-pending-bg` when started-at set, ended-at nil | ✅ |
| **Background error** | `toolErrorBg` | `:tool-error-bg` | ✅ |
| **Background success** | `toolSuccessBg` | `:tool-success-bg` | ✅ |
| **Interval timer** | `setInterval(() => c.invalidate(), 1000)` in bash renderResult | `future(Thread/sleep 1000; invalidate)` with `timer-active-atom` guard in render fn | ✅ |
| **`invalidate()`** | Calls `this.invalidate()` + `this.ui.requestRender()` — both clears cache and triggers TUI re-render | Calls `protocols/invalidate @box` + `request-render-fn` callback | ✅ |
| **`renderShell: "self"`** | edit tool uses it; call/render components draw their own Box with preview-dependent background | edit tool uses it; preview Box with `tool-success-bg`/`tool-error-bg` background | ✅ |
| **Hide empty components** | `hideComponent = true` when no content and no images | Returns `[]` when both call-comp and result-comp are nil | ✅ |
| **Image support** | Full kitty protocol via `Image` component, PNG conversion | `terminal-image` + `ImageComponent` with Kitty encoding, dimension parsing, fallback, conversion via python3+PIL | ✅ |

---

## 3. Remaining gaps

### Image content blocks not parsed from LLM responses (⚠️ Low)
kmet's `tool-execution-set-images!` accepts image blocks and renders them,
but the LLM content pipeline doesn't parse image blocks from tool results yet.
Currently images must be injected via `:images` in the tool result map.

### Render context object (⚠️ Architectural)
Pi passes a `ToolRenderContext` to `renderCall`/`renderResult` with `state`,
`invalidate`, `executionStarted`, `expanded`, `showImages`, `cwd`, etc.
kmet uses positional args `(name args theme width)`. Functionally equivalent
for built-in tools but limits extension potential.

### toolCallId not tracked (⚠️ Low)
Pi stores a `toolCallId` on each `ToolExecutionComponent` for correlating
tool calls across agent turns and extensions. kmet doesn't track this.
Not needed for current rendering functionality.

---

## 4. Minor cosmetic differences

| Detail | Pi | kmet | Impact |
|---|---|---|---|
| Collapsed hint truncation | `truncateToWidth(hint, width, "...")` ensures hint fits | `truncate-to-width` applied | ✅ |
| `renderShell: "self"` component bg | Edit tool's Box sets own bg (`getEditHeaderBg`) | Box with `tool-success-bg`/`tool-error-bg` | ✅ |
| Footer stripping logic | Matches `\n\n[` + `fullOutputPath` | Moot — replaced by proper metadata | ✅ |
| Error content truncation | Shows all error lines joined with `\n` | All error lines joined with `\n` | ✅ |

---

## Summary of work needed

| Item | Effort | Status |
|---|---|---|
| Image content blocks from LLM results | Low | ⚠️ |
| Render context object | Low | ⚠️ |
| toolCallId tracking | Low | ⚠️ |
