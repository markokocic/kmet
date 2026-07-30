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
| **renderCall** | `write <path>` + content preview (10 lines, syntax highlighted) | `write <path> (<N> lines)` | ⚠️ Pi shows preview, kmet shows line count |
| **renderResult success** | Empty container (nothing) | `nil` | ✅ |
| **renderResult error** | All error text lines joined with `\n`, in `error` color | First error line in `error` color | ⚠️ kmet truncates to first line only |

### edit

| Aspect | Pi | kmet | Status |
|---|---|---|---|
| **renderCall** | `edit <path>` + diff preview inline via `renderShell: "self"` (Box with preview-dependent bg) | `edit <path>` via `:render-shell :self` (no Box, no preview bg) | ⚠️ kmet lacks diff preview mechanism |
| **renderResult** | Only when error or result diff differs from preview | Always shows `+A / -R` stats or `Applied` | ⚠️ kmet has no preview, so always shows result |

### bash

| Aspect | Pi | kmet | Status |
|---|---|---|---|
| **renderCall** | `$ <command>` in toolTitle bold + `(timeout Ns)` in muted | `$ <command>` in toolTitle bold + `(timeout Ns)` in muted | ✅ |
| **renderResult status** | No `done`/`exit N` text (background color indicates status) | No `done`/`exit N` text | ✅ |
| **renderResult expanded** | `\n${styledOutput}` — all lines in a single Text with leading newline | Leading blank line + each line as separate Text child | ✅ (visually equivalent) |
| **renderResult collapsed** | Last 5 **visual** lines (width-aware via `truncateToVisualLines`), leading `""` blank line, truncated hint | Last 5 **string** lines, leading blank line, plain hint | ⚠️ kmet uses string-level truncation, not width-aware |
| **renderResult duration** | `\n${muted("Elapsed X.Xs"/"Took X.Xs")}` — in-text newline | Blank line + muted text child | ✅ |
| **renderResult truncation warnings** | `[Truncated: ...]` and `[Full output: ...]` when output was truncated server-side | Not shown | ❌ kmet doesn't capture truncation metadata |
| **renderResult keybinding hints** | `keyHint("app.tools.expand", "to expand")` renders clickable keybinding badge | Plain text `"to expand"` | ❌ kmet has no keybinding hint system |
| **Footer stripping** | Strips `[Showing...Full output:...]` by matching `\n\n[` + `fullOutputPath` | Strips by regex on last line | ⚠️ Different approaches, same result |

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
| **`invalidate()`** | Calls `this.invalidate()` + `this.ui.requestRender()` — both clears cache and triggers TUI re-render | Calls `protocols/invalidate @box` — only clears Box cache | ⚠️ Relies on animation timer for `tui-request-render` |
| **`renderShell: "self"`** | edit tool uses it; call/render components draw their own Box with preview-dependent background | edit tool uses it; no Box at all (plain container) | ⚠️ kmet doesn't set background on self-shell components |
| **Hide empty components** | `hideComponent = true` when no content and no images | Always renders (no hide) | ⚠️ Empty tool components still take up space |
| **Image support** | Full kitty protocol via `Image` component, PNG conversion | Not supported | ❌ kmet TUI has no image protocol support |

---

## 3. Infrastructure gaps

These require new libraries or significant refactoring to implement:

### Visual line truncation (`bash` collapsed)
Pi's `truncateToVisualLines` creates a temporary `Text` component, renders it at the given
width to get visual lines (accounting for word wrap), then takes the last `BASH_PREVIEW_LINES`.
This ensures truncation is width-aware — important for long single-line commands or narrow
terminals.

kmet would need a similar utility:
```clojure
(defn truncate-to-visual-lines [text max-visual-lines width]
  ;; Render text at width, get visual lines, take last N
  )
```

### Truncation metadata
Pi's bash tool details include `TruncationResult` (output lines, total lines, truncated-by type,
max bytes). The `renderResult` shows warnings like:
```
[Truncated: showing 500 of 5000 lines (500 line limit)]
[Full output: /tmp/pi-bash-xxxxx]
```

kmet would need to pass this metadata through `execute-tool` → event → component.

### Keybinding hints
Pi has `keyHint("app.tools.expand", "to expand")` which renders a styled keybinding badge
(e.g., `[ctrl+e] to expand`) in the truncation hint. kmet has no equivalent UI system.

### Image rendering
Pi's `Image` component supports kitty terminal image protocol with:
- `allocateImageId` / `renderImage` for kitty sequences
- PNG conversion for non-PNG images
- `imageFallback` for terminals without image support
- Width/height calculation from cell dimensions

This would require adding kitty protocol support to kmet's JLine3-based terminal layer.

### Diff preview (edit tool)
Pi's edit tool computes a diff preview *before* executing the edit (by reading the file and
applying edits in memory). The preview determines the background color of the call component:
- Green if preview succeeds
- Red if preview fails (error)
- Pending yellow while computing

kmet doesn't compute previews, so the edit component always uses default background and has
no preview body.

---

## 4. Minor cosmetic differences

| Detail | Pi | kmet | Impact |
|---|---|---|---|
| Collapsed hint truncation | `truncateToWidth(hint, width, "...")` ensures hint fits | Raw hint string | Low — hint is usually short enough |
| `renderShell: "self"` component bg | Edit tool's Box sets own bg (`getEditHeaderBg`) | No background | Low — edit shows colored text instead |
| Footer stripping logic | Matches `\n\n[` + `fullOutputPath` | Regex `^\[Showing.*Full output:.*\]$` on last line | Low — same result in practice |
| Error content truncation | Shows all error lines joined with `\n` | Shows first error line | Low — first line usually contains the message |

---

## Summary of work needed

### Quick wins (cosmetic, low effort)
- Add `:tool-pending-bg` to theme if missing (already in use)
- Show all error lines for write tool (not just first)

### Medium effort (new utilities)
- Add `truncate-to-visual-lines` utility to kmet's TUI utils
- Pass truncation metadata through tool execution pipeline
- Compute edit diffs preview before execution

### Large effort (new infrastructure)
- Kitty protocol image support in JLine3 terminal layer
- Keybinding hint system in TUI library
