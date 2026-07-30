# kmet ↔ Pi Alignment

This document tracks known gaps between kmet's ToolExecutionComponent and Pi's
(the original TypeScript coding agent). The goal is full behavioral parity.

Legend:
- ✅ Aligned

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

## 4. User bash commands (`!` / `!!`)

| Aspect | Pi | kmet | Status |
|---|---|---|---|
| **Input detection** | `text.startsWith("!")` in handleSubmit, `!!` → excludeFromContext | `str/starts-with? trimmed "!"` in handle-submit, `!!` → exclude-from-context? | ✅ |
| **Component** | `BashExecutionComponent` extends `Container` — bordered box, `$ command` header, rolling output, loader, status | `BashExecutionComponent` defrecord implementing `IComponent` — same bordered layout | ✅ |
| **Output display** | Lines accumulated in array, collapsed by default (20 visual lines), expand/collapse | Lines accumulated in atom vector, collapsed by default (20 lines), expand/collapse | ✅ |
| **Incomplete line continuation** | `appendOutput` appends first chunk of new data to last line (incomplete line continuation) | `bash-execution-append-output!` — same logic: appends to last line then adds rest | ✅ |
| **Visual line truncation** | `truncateToVisualLines(styledInput, PREVIEW_LINES, width)` — width-aware word wrapping | `u/truncate-to-visual-lines` — same | ✅ |
| **Context truncation before display** | Applies `truncateTail(fullOutput, 2000/50KB)` before rendering — display only shows context-relevant tail | `bash-exec/truncate-tail` applied to output before building display lines | ✅ |
| **Loader animation** | Pi's `Loader` component — animated braille spinner with `Running... (Esc to cancel)` text | `Spinner` component — braille spinner with `Running... (key to cancel)` text, starts active | ✅ |
| **Duration display** | Shows `Elapsed X.Xs` during execution, `Took X.Xs` after completion | Same: `Elapsed X.Xs` / `Took X.Xs` in muted color in status line | ✅ |
| **Bordered frame** | `DynamicBorder` component (capped corners, theme-aware) | Manually drawn `┌─┐`/`└─┘` lines | ✅ (visual equivalent) |
| **Border color key** | `bashMode` for `!`, `dim` for `!!` | `:bash-mode` for `!`, `:dim` for `!!` | ✅ |
| **Status info** | `(cancelled)` in warning, `(exit N)` in error | Same | ✅ |
| **Expand keybinding** | `keyHint("app.tools.expand", "to expand")` → `Ctrl+O` badge | `app-kb/key-hint` → same keybinding text | ✅ |
| **Output accumulator detail** | `OutputAccumulator` class: tracks `totalRawBytes` + `totalDecodedBytes` separately, `completedLines` vs `hasOpenLine` for accurate line counting, `tailStartsAtLineBoundary` for clean snapshots | Raw byte chunks read from `InputStream`, copied on storage. `tail-buf` tracks decoded text for display. `tail-starts-at-line-boundary` flag updated on trim. | ✅ |
| **Temp file content** | Raw `Buffer` bytes written to `createWriteStream` (raw `fs.WriteStream`) | `byte-array` chunks read from `InputStream`, written to `FileOutputStream` before any decoding. Exact original binary preserved. | ✅ |
| **Temp file naming** | `randomBytes(8).toString("hex")` → `/tmp/pi-bash-{id}.log` | `File/createTempFile` → `/tmp/kmet-bash-{random}{suffix}.log` | ✅ (equivalent) |
| **Truncation result detail** | Returns `truncatedBy: "lines" | "bytes" | null` and `lastLinePartial: bool` | Returns `truncated-by: :lines | :bytes | nil` — matching pi | ✅ |
| **Session env injection** | Injects `PI_SESSION_ID`, `PI_PROVIDER`, `PI_MODEL`, `PI_REASONING_LEVEL` into bash env | Same: built from current session + agent state, passed as `:env` to executor | ✅ |
| **Spawn hook** | `BashSpawnHook` — extensions can rewrite command/cwd/env before execution | `:spawn-hook` parameter on `execute-bash`, wired from core.clj's handler | ✅ |
| **Detached child tracking** | Global `Set` of PIDs, cleaned up on process shutdown via `killTrackedDetachedChildren()` | `tracked-pids` atom with `track-pid!`/`untrack-pid!`, `kill-tracked-children!` called on shutdown | ✅ |
| **`waitForChildProcess`** | Event-driven: `exit` listener + `data` event re-arms 100ms grace timer | Polling after `deref p`: checks `future-done?` on the raw-byte reader + re-arms 100ms grace on each new decoded byte | ✅ (same 100ms grace semantics) |
| **`killProcessTree`** | `process.kill(-pid, "SIGKILL")` on Unix, `taskkill /T` on Windows — kills full process group | `kill -9 -<pid>` on Unix, `taskkill /F /T /PID` on Windows | ✅ |
| **Error display** | `showError()` — adds spacer + raw `Text` to chat container | `ui/show-error!` — same: spacer + `Text` with `:error` color | ✅ |
| **Warning display** | `showWarning()` — adds spacer + raw `Text` to chat container | `ui/show-warning!` — same: spacer + `Text` with `:warning` color | ✅ |
| **Concurrent bash guard** | Check in `handleSubmit`, shows warning in UI | Same: check in `handle-bash-command`, calls `ui/show-warning!` | ✅ |
| **Pending area during streaming** | Dedicated `pendingMessagesContainer` between chat and footer | `pending-bash-container` Container placed between chat-history and status-indicator | ✅ |
| **Extension hooks** | `user_bash` event → extensions can intercept | `skills/emit-event! {:type :user-bash ...}` + `:spawn-hook` parameter | ✅ |
| **Shell resolution** | Cross-platform: WSL (`-s` stdin), Git Bash, `where bash.exe`, `which bash`, fallback `sh` | Cross-platform: WSL detection (`windows\system32\bash.exe` → `-s` stdin), Git Bash paths, `where`/`command -v`, `cmd.exe`/`sh`. Matches pi's full resolution order + WSL. | ✅ |
| **Output sanitization** | `sanitizeBinaryOutput()` — strips control chars + Unicode format (U+FFF9-U+FFFB) | `sanitize-output` — same: strips control chars + format characters via updated regex range | ✅ |
| **UTF-8 streaming decoder** | `TextDecoder({stream: true})` — correct multi-byte decoding across chunk boundaries | `CharsetDecoder` via `java.nio` — same streaming behavior with `endOfInput` flag. Flushed in `finalize`. | ✅ |
| **Error propagation** | Non-signal, non-timeout errors are re-thrown (`throw err`) | All errors caught and returned as `{:is-error true}` result | ⚠️ (kmet is more defensive — never throws) |
| **`formatSize` utility** | `formatSize(bytes)` → human-readable size (e.g., "12.5KB") for truncation warnings | `bash-exec/format-size` — same output format (B/KB/MB). Used in tool_execution.clj's truncation warnings. | ✅ |
| **`BASH_UPDATE_THROTTLE_MS`** | Bash tool throttles `onUpdate` to 100ms during streaming | kmet's bash tool doesn't stream via `onUpdate` — returns full result synchronously | ⚠️ (not needed — `execute-bash` is synchronous) |

## 5. Minor cosmetic differences

| Detail | Pi | kmet | Impact |
|---|---|---|---|
| Spinner color setup | Set once in Loader constructor | Set once in `make-bash-execution` via `:spinner-color-fn`/`:message-color-fn` | ✅ |
| Unused variable `content-width` | N/A | Removed in cleanup | ✅ |
| Collapsed hint truncation | `truncateToWidth(hint, width, "...")` ensures hint fits | `truncate-to-width` applied | ✅ |
| `renderShell: "self"` component bg | Edit tool's Box sets own bg (`getEditHeaderBg`) | Box with `tool-success-bg`/`tool-error-bg` | ✅ |
| Footer stripping logic | Matches `\n\n[` + `fullOutputPath` | Moot — replaced by proper metadata | ✅ |
| Error content truncation | Shows all error lines joined with `\n` | All error lines joined with `\n` | ✅ |




## 6. Code quality notes

| Issue | Detail | Status |
|---|---|---|
| **`tail-starts-at-line-boundary` flag now used** | Tracks whether rolling tail starts at a line boundary after trimming. `finalize` reads it: if false, skips the partial first line by finding the first `\n` and slicing from there. | ✅ |
| **Stderr grace period** | Both stdout and stderr futures are tracked. Grace polling loop waits for both to complete (or grace to expire), with 100ms re-armed on new data. | ✅ |
| **Spinner text set in constructor** | `spinner-set-text!` called once in `make-bash-execution` with the cancel keybinding resolved at construction time. No longer set on every render. | ✅ |

## 7. Missing API surface (not in pi)

| Feature | pi | kmet | Status |
|---|---|---|---|
| **`BashOperations` pluggable backend** | Interface for replacing execution (SSH, containers, etc.) | `:operations` parameter on `execute-bash`. `create-default-ops` factory mirrors `createLocalBashOperations`. Custom ops receive `{:keys [command cwd on-data signal timeout env]}` and call `on-data` with raw byte arrays. | ✅ |
| **`commandPrefix`** | Prepended to every command (e.g., `source ~/.profile`) | `:command-prefix` parameter on `execute-bash`. Prepended with `\n` separator. | ✅ |
| **`shellPath`** | Explicit shell path from user settings | `:shell-path` parameter on `execute-bash` and `create-default-ops`. Overrides automatic resolution. | ✅ |
| **`exposeSessionEnvironment` flag** | Controls whether `PI_*` env vars are injected (default: true) | `!` commands always inject (matching pi's default). Tool caller controls via `:env` parameter — no separate flag needed. | ✅ (equivalent) |
| **`truncateHead`** | `truncateHead(content)` — keeps first N lines/bytes for file reads | `bash-exec/truncate-head` — same interface, includes `:first-line-exceeds-limit` for edge cases. | ✅ |
