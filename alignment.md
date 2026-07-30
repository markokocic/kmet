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
| **Image support** | Full kitty protocol via `Image` component, PNG conversion | `terminal-image` + `ImageComponent` with Kitty encoding, dimension parsing, fallback, async PNG conversion via python3+PIL | ✅ |

---

## 3. Remaining gaps

### Image content blocks from tools (✅ Resolved)
kmet's `tool-execution-set-images!` accepts image blocks, stores raw image
data in `image-data-atom`, and builds `ImageComponent` children at render
time from that data. The full pipeline is wired: tool → result →
event handler → `image-data-atom` → render.

The `read` tool now detects image files by extension (png, jpg, jpeg, gif,
webp, bmp) and returns both a text description and an `:images` vector
containing the base64-encoded data and MIME type. The `tool-result-message`
function in the agent loop passes `:images` through to the event, and the
`tool-execution-set-images!` handler renders them.

**Async image format conversion (✅ Resolved).** Pi calls `maybeConvertImagesForKitty()`
which asynchronously converts non-PNG images (JPEG, GIF, WebP) to PNG for
terminals that only support PNG via the Kitty protocol. kmet now does the same:
`tool-execution-set-images!` checks terminal capabilities and fires a `future`
to call `convert-to-png` for each non-PNG image. When conversion completes,
the `converted-images-atom` is updated and the component invalidates, causing
a re-render with the converted PNG data.

### Render context object (✅ Resolved)
Both kmet and Pi now pass a full `ToolRenderContext` map to `renderCall`/`renderResult`:
- `:args`
- `:tool-call-id`
- `:invalidate` (calls `protocols/invalidate` + `request-render-fn`)
- `:last-component` (previously-returned component from the same renderer)
- `:state` (value from `renderer-state-atom` — persists across renders)
- `:cwd` (from `cwd-atom`, defaults to `user.dir`)
- `:execution-started` (truthy when `started-at-atom` is set)
- `:args-complete` (from `args-complete-atom`)
- `:is-partial` (true when `ended-at-atom` is nil)
- `:expanded`
- `:show-images` (always true)
- `:is-error` (from `is-error-atom`)

The context is built via `tool-execution-context` private helper and passed
as the last argument to both `renderCall` and `renderResult`. Built-in
renderers accept and ignore it (`_context` or `& _`).

### toolCallId tracking (✅ Resolved)
Pi stores a `toolCallId` on each `ToolExecutionComponent` for correlating
tool calls across agent turns and extensions. kmet now tracks this
via `:tool-call-id-atom` on the defrecord, set from `(:id evt)` in the
`:tool-start` handler, with `tool-execution-set-tool-call-id!` and
`tool-execution-get-tool-call-id` accessors. No invalidation needed
since the ID does not affect rendering.

### `argsComplete` / `setArgsComplete()` (✅ Resolved)
Both kmet and Pi now track whether tool arguments have been fully received.
kmet stores this in `args-complete-atom` on the defrecord, with
`tool-execution-set-args-complete!` to set it. In `:tool-start` (where args
arrive as a complete map), it's set to true immediately. The render context
includes `:args-complete`. No invalidation is needed since the value doesn't
affect rendering.

### `executionStarted` timing (✅ Resolved)
kmet now calls `tool-execution-mark-execution-started!` in the `:tool-start`
event handler, which sets `started-at-atom` immediately. This activates the
pending background (`:tool-pending-bg`) and elapsed timer from tool start
rather than waiting for first `set-content!` (in `:tool-result`). The
`set-content!` function still has the guard — it won't overwrite an already-set
`started-at-atom`. Matches Pi's `markExecutionStarted()` behavior.

---

## 4. Minor cosmetic differences

| Detail | Pi | kmet | Impact |
|---|---|---|---|
| Collapsed hint truncation | `truncateToWidth(hint, width, "...")` ensures hint fits | `truncate-to-width` applied | ✅ |
| `renderShell: "self"` component bg | Edit tool's Box sets own bg (`getEditHeaderBg`) | Box with `tool-success-bg`/`tool-error-bg` | ✅ |
| Footer stripping logic | Matches `\n\n[` + `fullOutputPath` | Moot — replaced by proper metadata | ✅ |
| Error content truncation | Shows all error lines joined with `\n` | All error lines joined with `\n` | ✅ |

## Summary of work needed

| Item | Effort | Status |
|---|---|---|
| Image content blocks from tools (read tool supports images) | Low | ✅ |
| `executionStarted` timing | Low | ✅ |
| toolCallId tracking | Low | ✅ |
| Render context object | Low | ✅ |
| `argsComplete` / `setArgsComplete()` | Low | ✅ |
| Async image format conversion | Low | ✅ |
