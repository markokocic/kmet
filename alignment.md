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
| **Image support** | Full kitty protocol via `Image` component, PNG conversion | Not supported | ❌ kmet TUI has no image protocol support |

---

## 3. Infrastructure gaps

These require new libraries or significant refactoring to implement:

### Keybinding hints
Implemented via `kmet.tui.keybindings` (KeybindingsManager) and `kmet.agent.keybindings`
(app-level extensions with `app.tools.expand` etc.). The `app-kb/key-hint` function formats
styled keybinding hints using the resolved keybinding from the manager. Input handling in
`core.clj` now uses keybinding IDs instead of hardcoded key checks.

### Image rendering
Pi's `Image` component supports kitty terminal image protocol with:
- `allocateImageId` / `renderImage` for kitty sequences
- PNG conversion for non-PNG images
- `imageFallback` for terminals without image support
- Width/height calculation from cell dimensions

This would require adding kitty protocol support to kmet's JLine3-based terminal layer.

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

### Remaining
| Item | Effort | Status |
|---|---|---|
| Kitty protocol image support | Large | ❌ |
| Keybinding hint system | Large | ✅ |
