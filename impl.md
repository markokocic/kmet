# kmet — Implementation Plan

kmet is a minimal coding agent using Babashka, heavily inspired by [pi](https://pi.dev).
The TUI is a Clojure port of [@earendil-works/pi-tui](https://github.com/badlogic/pi-mono/tree/main/packages/tui).

---

## Table of Contents

1. [Philosophy & Design Decisions](#1-philosophy--design-decisions)
2. [Project Layout](#2-project-layout)
3. [Phase 1 — TUI Foundation (DONE)](#3-phase-1--tui-foundation-done)
4. [Phase 2 — Interactive Components](#4-phase-2--interactive-components)
5. [Phase 3 — Agent Core](#5-phase-3--agent-core)
6. [Phase 4 — Integration](#6-phase-4--integration)
7. [Phase 5 — Polish](#7-phase-5--polish)
8. [Appendix: Terminal Capabilities](#8-appendix-terminal-capabilities)

---

## 1. Philosophy & Design Decisions

### Inspired by pi

- **Minimal core** — provide the essential building blocks, let the user (or future extensions) add features
- **Differential rendering** — only write changed lines to the terminal (CSI 2026 synchronized output)
- **Component-based** — every visual element implements `IComponent` (render / handle-input / invalidate)
- **EDNL sessions** — session files are line-delimited EDN with parent-child IDs for tree branching
- **Multi-provider LLM** — start with Anthropic and OpenAI, add more via a registry

### Babashka-specific choices

- **JLine3** (bundled with bb 1.12.215+) for terminal I/O — raw mode, input reading, dimensions
- **No external TUI libraries** — build from scratch to match pi-tui's API exactly
- **core.async** for communication between TUI thread and agent thread
- **babashka.http-client** for LLM API calls
- **clojure.edn** (bundled) for EDN read/write
- **EDN for config** (`~/.config/kmet/settings.edn`, `.kmet/settings.edn`)
- **bb task runner** for development (`bb run`, `bb test`)

---

## 2. Project Layout

```
kmet/
├── kmet                      # Entry script (bb shebang)
├── bb.edn                   # Babashka tasks
├── deps.edn                 # Dependencies (currently empty, all built-in)
├── impl.md                  # This file
├── src/kmet/
│   ├── core.clj             # CLI entry point, argument parsing
│   ├── tui/
│   │   ├── core.clj         # TUI class: render loop, focus, overlays, input routing
│   │   ├── terminal.clj     # JLine3 wrapper: raw mode, write, dimensions
│   │   ├── keys.clj         # Key parsing and matching (matches-key?, parse-key)
│   │   ├── utils.clj        # visible-width, truncate-to-width, wrap-text, ANSI helpers
│   │   ├── index.clj        # Re-exports for convenient (require 'kmet.tui)
│   │   └── components/
│   │       ├── container.clj  # IComponent that groups children (TODO)
│   │       ├── text.clj       # Multi-line word-wrapped text
│   │       ├── spacer.clj     # N empty lines
│   │       ├── box.clj        # Box with padding + background (TODO)
│   │       ├── input.clj      # Single-line input (TODO)
│   │       ├── editor.clj     # Multi-line editor (TODO)
│   │       ├── select_list.clj # Selection list (TODO)
│   │       ├── settings_list.clj # Settings toggle list (TODO)
│   │       └── markdown.clj   # Markdown to ANSI renderer (TODO)
│   └── agent/
│       ├── llm.clj           # LLM API client — Anthropic + OpenAI (TODO)
│       ├── tools.clj         # Tool definitions and execution (TODO)
│       ├── loop.clj          # Agent turn loop (TODO)
│       └── session.clj       # EDNL session storage (TODO)
```

---

## 3. Phase 1 — TUI Foundation (DONE)

### 3.1 `kmet.tui.keys` — Key Handling

**Purpose:** Parse raw terminal input sequences into semantic key identifiers.

**Current implementation:**
- `matches-key?` — check if raw data matches a key-ID string
- `parse-key` — convert raw bytes to key-ID (handles legacy escape sequences)
- Key constants: `KEY-UP`, `KEY-DOWN`, `KEY-ENTER`, etc.
- Modifier helpers: `ctrl`, `shift`, `alt`
- Kitty protocol support: `set-kitty-active!`, `kitty-active?`, `is-key-release?`, `is-key-repeat?`

**Legacy sequences mapped:** arrows, home/end, page up/down, insert/delete, function keys F1-F12, shift+arrows, ctrl+arrows, alt+arrows.

**Next:** Add more CSI-u (Kitty protocol) parsing and `parse-key` improvements for complex sequences.

### 3.2 `kmet.tui.terminal` — JLine3 Wrapper

**Purpose:** Wrap JLine3's `Terminal` class in a Clojure-friendly record.

**Protocol `ITerminal`:**
- `start!` — enter raw mode, enable bracketed paste
- `stop!` — restore terminal
- `write-output` — write string to terminal
- `columns` / `rows` — terminal dimensions
- `hide-cursor!` / `show-cursor!`
- `clear-line!` / `clear-screen!`

**Usage:**
```clojure
(def term (create-terminal))
(t/start! term on-input-fn on-resize-fn)
(t/write-output term "hello")
(t/columns term)  ;; => 80
```

### 3.3 `kmet.tui.utils` — Text Utilities

**Functions:**
- `visible-width` — display width of a string (strips ANSI, handles CJK/emoji as width-2)
- `truncate-to-width` — truncate to fit max columns, optional ellipsis
- `wrap-text-with-ansi` — word-wrap preserving ANSI codes
- `strip-ansi-codes` — remove ANSI sequences
- `sgr` — build ANSI SGR escape codes
- `apply-background-to-line` — pad + apply background fn

**Width calculation:** ASCII = 1, CJK (U+2E80..U+9FFF, Hangul, fullwidth) = 2, emoji ranges = 2, tabs = 4, controls = 0.

### 3.4 `kmet.tui.core` — TUI Class

**Protocols:**

```clojure
(defprotocol IComponent
  (render [this width] "→ seq of strings, each ≤ width")
  (handle-input [this data] "→ keyboard input")
  (invalidate [this] "→ clear cached state"))

(defprotocol IFocusable
  (focused [this])
  (set-focused! [this val]))
```

**Container:** groups children, delegates render/input/invalidate.

**TUI record:**
- `terminal` — the JLineTerminal
- `components` — atom of IComponent children
- `focused-component` — atom, receives input
- `input-listeners` — atom of global input hooks
- `overlays` — atom of Overlay records
- `previous-lines` — atom, holds last rendered frame for diffing

**Render loop (`tui-start`):**
1. Enter raw mode, clear screen
2. Start background input reader (future)
3. Main thread loops at ~30fps:
   - If render requested or resize, diff `previous-lines` with new render
   - Write only changed lines using CSI 2026 synchronized output
   - Track cursor position at end of content

**Input reader:**
- Reads from JLine's reader in a `future`
- Dispatches to input-listeners first, then focused component
- Calls `tui-request-render` after each input event

**Overlays:**
- `tui-show-overlay` — push component onto overlay stack, focus it
- `tui-hide-overlay` — pop overlay, restore focus to previous

### 3.5 `kmet.tui.components.text` — Text Component

- Multi-line with word-wrap
- Configurable padding (left/right via `padding-x`, top/bottom via `padding-y`)
- Optional background function
- Render cache: invalidate on text change or resize

### 3.6 `kmet.tui.components.spacer` — Spacer

- Renders N empty lines
- Minimal, no caching needed

---

## 4. Phase 2 — Interactive Components

### 4.1 `kmet.tui.components.container` — Container (DONE)

**Purpose:** Group child components vertically.

**Implementation:**
- Extracted from `core.clj` into `src/kmet/tui/components/container.clj`
- `Container` record + `make-container`, `container-add-child`, `container-remove-child`, `container-clear`
- Renders children sequentially, passes `handle-input` to each (stops at first handler)

### 4.2 `kmet.tui.components.box` — Box (DONE)

**Purpose:** Container with padding and background color.

**Implementation:**
- `padding-x`, `padding-y`, `bg-fn`, `cache` fields
- Renders children into content area (width - 2*padding-x), pads each line with left padding
- Applies `bg-fn` to each line, pads to full width
- Render cache (invalidates on width/bg-fn/child-lines change)
- API: `make-box`, `box-add-child`, `box-remove-child`, `box-clear`, `box-set-bg-fn`

### 4.3 `kmet.tui.components.input` — Input (DONE)

**Purpose:** Single-line text input with horizontal scrolling and cursor.

**Port of:** pi-tui `Input` component.

**Features implemented:**
- Single line of text with editable cursor
- Horizontal scrolling when text exceeds available width
- Cursor rendered as inverse video block with `CURSOR_MARKER` for IME
- Grapheme-aware cursor movement (Java `BreakIterator`)
- All keybindings: enter/submit, escape/cancel, left/right, home/end, ctrl+a/e, backspace/delete, ctrl+w/alt+backspace, ctrl+d, ctrl+u, ctrl+k, alt+d/alt+delete
- Word navigation: alt+left/right, ctrl+left/right, alt+b/f
- Kill ring: ctrl+y (yank), alt+y (yank-pop) with accumulate
- Undo stack with coalescing (consecutive word chars = one undo unit)
- Bracketed paste mode support
- IFocusable protocol for hardware cursor positioning

**Key implementation details:**
- Uses Java `BreakIterator` for grapheme and word segmentation
- `render-line` emits `CURSOR_MARKER` when focused for IME positioning
- Horizontal scrolling centers cursor in viewport
- Undo coalescing: whitespace chars trigger snapshot, word chars coalesce

### 4.4 `kmet.tui.components.editor` — Editor

**Purpose:** Multi-line text editor with word-wrap, vertical scrolling, undo/redo, kill-ring, paste markers.

**Port of:** pi-tui `Editor` component — the most complex TUI component (~1950 lines JS).

**Sub-phases:**
- **2b.1** — Core Editor: multi-line editing, word-wrap layout, cursor movement (arrows, home/end, page up/down), basic editing (typing, backspace, delete, enter newline, submit), vertical scrolling with scroll indicators, border
- **2b.2** — Editor Features: undo/redo stack, kill-ring/yank/yank-pop, line editing (ctrl+u/k/w, ctrl+w/alt+d), paste markers, character jump mode
- **2b.3** — History & Autocomplete: up/down history navigation, slash/filename autocomplete (SelectList overlay), trigger/debounce logic

**Overall features:**
- Multi-line editing with word-wrap
- Slash command autocomplete (type `/`)
- File path autocomplete (type `@`, press Tab)
- Large paste handling (>10 lines → `[paste #1 +50 lines]` markers)
- Vertical scrolling when content exceeds terminal height
- Undo/redo stack
- Kill-ring for delete/yank operations
- Cursor movement by character, word, line
- Character jump mode (`ctrl+]`)

**Keybindings:**
| Key | Action |
|-----|--------|
| `enter` | Submit (or `disableSubmit` to prevent) |
| `shift+enter` / `ctrl+enter` / `alt+enter` | New line |
| `tab` | Autocomplete |
| `up` / `down` | Cursor up/down (visual lines with word-wrap) |
| `left` / `right` | Cursor left/right |
| `ctrl+a` / `ctrl+e` | Line start/end |
| `ctrl+u` | Delete to line start |
| `ctrl+k` | Delete to line end |
| `ctrl+w` / `alt+backspace` | Delete word backward |
| `alt+d` / `alt+delete` | Delete word forward |
| `ctrl+]` | Jump forward to character |
| `ctrl+alt+]` | Jump backward to character |
| `escape` | Abort/cancel |

**Implementation approach:**
- Internal state: `{:lines [] :cursor-line N :cursor-col N}`
- Word-wrap: `wordWrapLine` splits a logical line into visual chunks at word boundaries
- Visual line map: maps visual line indices back to logical lines + offsets
- Cursor tracking: maintain preferred visual column for up/down movement (sticky column)
- Undo stack: record state before each modification
- Kill-ring: circular buffer of deleted text blocks
- Autocomplete: async provider with debounce, renders SelectList overlay
- Paste markers: replace >10 line pastes with `[paste #N +M lines]` markers

### 4.5 `kmet.tui.components.select_list` — SelectList

**Purpose:** Interactive selection list with keyboard navigation and fuzzy filtering.

**Port of:** pi-tui `SelectList` component.

**Features:**
- Items with value, label, optional description
- Keyboard navigation: up/down, page up/down, home/end
- Fuzzy filtering: type to filter items
- Scroll indicator for long lists
- Themeable: selected prefix, selected text, description, scroll info

**Theme interface:**
```clojure
(defrecord SelectListTheme [selected-prefix selected-text description scroll-info no-match])
```

### 4.6 `kmet.tui.components.settings_list` — SettingsList

**Purpose:** Toggle/cycle through setting values.

**Port of:** pi-tui `SettingsList` component.

**Features:**
- Items with id, label, current value, possible values
- Left/right to cycle values
- Fuzzy search by label

### 4.7 `kmet.tui.components.markdown` — Markdown

**Purpose:** Render CommonMark with ANSI styling.

**Port of:** pi-tui `Markdown` component.

**Features:**
- Headings, bold, italic, code blocks, lists, links, blockquotes, horizontal rules
- Syntax highlighting (optional, language-agnostic)
- HTML tags rendered as plain text
- Padding support
- Render caching

**Implementation approach:**
- Use a Clojure markdown parser (or write a minimal one)
- Convert parsed AST to ANSI-styled terminal lines
- Theme interface for colors:

```clojure
(defrecord MarkdownTheme [heading link link-url code code-block
                          code-block-border quote quote-border hr
                          list-bullet bold italic])
```

---

## 5. Phase 3 — Agent Core

### 5.1 `kmet.tui.components.chat_history` — Chat History

**Purpose:** Display user/assistant/tool messages in the TUI.

**Features:**
- Message bubbles with role prefixes
- Collapsible tool call/result blocks
- Streaming assistant response display
- Vertical scrolling with auto-scroll to bottom
- Thinking/reasoning block display

### 5.2 `kmet.agent.llm` — LLM API Client

**Purpose:** Unified interface to multiple LLM providers.

**Supported providers (in priority order):**
1. **Anthropic** — Messages API, streaming, tool use
2. **OpenAI** — Chat Completions API, streaming, tool use
3. **Google Gemini** — via extension

**API:**

```clojure
(defn send-message
  [{:keys [provider model api-key messages tools stream? signal]
    :as opts}]
  ;; Returns channel of events: :text, :tool-call, :done, :error
  )
```

**Streaming:** Use `babashka.http-client` with `:as :stream` and parse SSE events.

**Tool use format:**
- Anthropic: `{type: "tool_use", id, name, input}` and `{type: "tool_result", tool_use_id, content}`
- OpenAI: `{function: {name, arguments}}` and `{role: "tool", tool_call_id, content}`

**Implementation plan:**

```clojure
;; kmet.agent.llm

(defn- anthropic-request [{:keys [model messages system tools stream?] :as opts}]
  ;; Build Anthropic Messages API payload
  ;; POST to https://api.anthropic.com/v1/messages
  ;; Parse SSE stream: event: message_start, content_block_delta, etc.
  )

(defn- openai-request [{:keys [model messages tools stream?] :as opts}]
  ;; Build OpenAI Chat Completions payload
  ;; POST to https://api.openai.com/v1/chat/completions
  ;; Parse SSE stream
  )

(defn send-message [{:keys [provider] :as opts}]
  (case provider
    :anthropic (anthropic-request opts)
    :openai    (openai-request opts)
    (throw (ex-info "Unknown provider" {:provider provider}))))
```

### 5.3 `kmet.agent.tools` — Tool System

**Purpose:** Define and execute tools that the LLM can call.

**Tool definition record:**
```clojure
(defrecord Tool [name label description parameters execute])
```

**Built-in tools (matching pi):**

| Tool | Description |
|------|-------------|
| `read` | Read file contents (with offset/limit) |
| `write` | Write content to file (create or overwrite) |
| `edit` | Precise text replacement in file |
| `bash` | Execute bash command (with timeout) |
| `grep` | Search file contents |
| `find` | Find files by pattern |
| `ls` | List directory contents |

**Tool parameter schema:** Use Clojure maps with type hints:
```clojure
{:path {:type :string, :description "File path"}
 :limit {:type :number, :optional true, :description "Max lines"}}
```

**Execution flow:**
1. LLM requests tool call with parameters
2. Validate parameters against schema
3. Execute tool function
4. Return result (content + error flag)

### 5.4 `kmet.agent.loop` — Agent Loop

**Purpose:** Orchestrate the conversation between user and LLM.

**State machine:**
```
IDLE → (user prompt) → THINKING → (tool calls) → TOOL RESULT → THINKING → ...
                                                                    ↓
                                                            (final text) → IDLE
```

**Implementation:**

```clojure
(defn run-agent-turn
  "Send messages to LLM, handle tool calls, return final response."
  [{:keys [llm-opts tools messages signal on-event]}]
  ;; 1. Send messages to LLM
  ;; 2. Receive streaming events via channel
  ;; 3. If text delta → emit :text event
  ;; 4. If tool call → execute tool, emit :tool-call / :tool-result
  ;; 5. Send tool results back to LLM
  ;; 6. Repeat steps 2-5 until final text response
  ;; 7. Emit :done event
  )
```

**Concurrency:** Agent loop runs in a `core.async` go block. The TUI reads events from a channel and updates the display.

### 5.5 `kmet.agent.session` — Session Storage

**Purpose:** Persist conversation history as EDNL files.

**File format (.ednl, one entry per line):**
```ednl
{:id "msg1", :parent-id nil, :role :user, :content [{:type :text, :text "Hello"}]}
{:id "msg2", :parent-id "msg1", :role :assistant, :content [{:type :text, :text "Hi!"}]}
```

**Session record:**
```clojure
(defrecord Session [file id entries leaf-id])
```

**API:**
- `create-session [dir]` — create new session file
- `load-session [path]` — load existing session
- `append-entry [session entry]` — append entry
- `get-branch [session]` — get active branch entries
- `compact! [session]` — summarize older entries
- `fork-session [session entry-id]` — fork at entry

---

## 6. Phase 4 — Integration

### 6.1 Main TUI Layout

```
┌─────────────────────────────────────┐
│  Header (model name, shortcuts)     │
├─────────────────────────────────────┤
│                                     │
│  Chat History                       │
│  - User messages                    │
│  - Assistant messages (Markdown)    │
│  - Tool call/result blocks          │
│  - Thinking blocks                  │
│                                     │
├─────────────────────────────────────┤
│  Editor (multi-line input)          │
├─────────────────────────────────────┤
│  Footer (cwd, tokens, cost, model)  │
└─────────────────────────────────────┘
```

### 6.2 Event Flow

```
User types in Editor → Enter pressed
  → Message added to session
  → Agent loop starts (background)
  → LLM streams response
  → TUI updates Chat History with streaming text
  → If tool call:
      → TUI shows tool call block
      → Tool executes
      → TUI shows tool result
      → Sent back to LLM
  → When LLM finishes:
      → Agent loop ends
      → Session saved
      → Editor re-enabled
```

### 6.3 Commands

| Command | Description |
|---------|-------------|
| `/quit` | Exit kmet |
| `/model` | Switch model |
| `/new` | Start new session |
| `/resume` | Browse/resume past sessions |
| `/help` | Show commands |

### 6.4 Keyboard Shortcuts

| Key | Action |
|-----|--------|
| `enter` | Submit message |
| `escape` | Cancel/abort |
| `ctrl+c` | Clear editor / quit |
| `ctrl+l` | Clear screen |
| `ctrl+p` | Cycle models |
| `up` / `down` | Scroll chat / recall history |

### 6.5 CLI Arguments

```
kmet [options] [@files...] [messages...]

Options:
  -p, --print           Print response and exit (non-interactive)
  -c, --continue        Continue most recent session
  -r, --resume          Browse sessions
  --model <id>          Model to use
  --provider <name>     Provider (anthropic, openai)
  --thinking <level>    Thinking level (off, low, medium, high)
  --no-session          Ephemeral mode (don't save)
  -h, --help            Show help
```

---

## 7. Phase 5 — Polish

### 7.1 Themes

```clojure
(defrecord Theme [name
                  ;; Foreground colors
                  text accent muted dim
                  success error warning
                  border border-accent border-muted
                  user-message-bg custom-message-bg
                  tool-title tool-output
                  md-heading md-link md-code md-block
                  ;; Background colors
                  selected-bg
                  thinking-levels])
```

Themes defined as EDN in `~/.config/kmet/themes/`.

### 7.2 Configuration

```clojure
;; ~/.config/kmet/settings.edn
{:provider :anthropic
 :model "claude-sonnet-4-20250514"
 :thinking :medium
 :theme :dark
 :session-dir "~/.local/share/kmet/sessions"}
```

Project-local overrides in `.kmet/settings.edn`.

### 7.3 Skills & Extensions

Minimal extension system:
- Skills: markdown files in `~/.config/kmet/skills/` or `.kmet/skills/`
- Extensions: Clojure files in `~/.config/kmet/extensions/` or `.kmet/extensions/`
- Events: `session-start`, `tool-call`, `tool-result`, `message-start` (inspired by pi)

### 7.4 Compaction

Long sessions compacted by summarizing older messages. Configurable threshold (default: 80% of context window).

### 7.5 Session Tree

Navigation via `/tree` to browse/restore any point in the session history.

---

## 8. Appendix: Terminal Capabilities

### CSI 2026 Synchronized Output

Wraps render updates in `\x1b[?2026h` / `\x1b[?2026l` to prevent partial frame rendering.

### Kitty Keyboard Protocol

Detected at startup via `\x1b[>flags u` query. When active, provides:
- Disambiguated escape codes (no ambiguity between Tab and Ctrl+I)
- Event types: press/repeat/release
- Alternate keys (shifted/base layout variants)

### Bracketed Paste Mode

`\x1b[?2004h` / `\x1b[?2004l`. Pasted text is wrapped in `\x1b[200~` / `\x1b[201~` markers, allowing the editor to handle large pastes correctly.

### CURSOR_MARKER

Zero-width APC sequence `\x1b_pi:c\x07` emitted by `IFocusable` components at the cursor position. TUI scans for it, strips it, and positions the hardware cursor there for IME support.

### Color Support

- 16 standard ANSI colors
- 256-color palette (38;5;N / 48;5;N)
- True color (38;2;R;G;B / 48;2;R;G;B)

---

## Implementation Order

1. ~~**Phase 1** — TUI Foundation~~ ✅ DONE
2. ~~**Phase 2a** — `input.clj`, `container.clj`, `box.clj` (basic interactive components)~~ ✅ DONE
3. **Phase 2b** — `editor.clj` (the big one — core of user input) ← CURRENT
4. **Phase 2c** — `select_list.clj`, `settings_list.clj`, `markdown.clj`
5. **Phase 3a** — `llm.clj` (Anthropic first, then OpenAI)
6. **Phase 3b** — `tools.clj` (read, write, edit, bash)
7. **Phase 3c** — `loop.clj` + `session.clj` (agent orchestration)
8. **Phase 4** — Chat history integration, main TUI layout, commands
9. **Phase 5** — Themes, config, polish
