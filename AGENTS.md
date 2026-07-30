# kmet specific guidelines Agent Guidelines

## Conventions

### Build & Run
- **Entry**: `bb run` — runs `kmet.core/-main`
- **nREPL**: `bb nrepl` — starts nREPL server on port 1667 for interactive development (blocks). Connect your editor/tool to `localhost:1667`.
  To stop: evaluate `(System/exit 0)` via nREPL (or `fuser -k 1667/tcp` from another terminal).
- **Deps**: first-party Babashka libraries (`babashka.fs`, `babashka.process`) in `deps.edn`; JLine **4.3.1** bundled with Babashka (see babashka `deps.edn`: `org.jline/jline-terminal`, `org.jline/jline-reader`).

### API Preferences (avoid Java interop)
- **`babashka.fs`** over `java.io.File` for all file operations
- `babashka.process` over `java.lang.ProcessBuilder` for subprocesses
- `clojure.string` over Java `.startsWith()`, `.contains()`, `.indexOf()`
- `clojure.java.io`  over `java.io.*`
- Avoid `^String`, `^java.io.File`, `^java.io.Reader` etc. type hints — stay Babashka-compatible.
- No `java.io.*` or `java.nio.file.*` imports — everything is available via `babashka.fs` and `slurp`/`spit`

### Code Style
- **Records, not deftype**: use `defrecord` + `map->` constructors
- **Protocols** for extension: `IComponent` (render/handle-input/invalidate), `IFocusable` (focused/set-focused!)
- **State**: atoms for mutable state (component children, input listeners, render flags)
- **Private vars**: use `defn-` / `def-` for implementation details not part of public API

## File layout
```
src/kmet/
├── core.clj            — CLI entry, arg parsing, main layout
├── config.clj          — Configuration loading
├── debug.clj           — Debug/error logging
├── app/                — App-level business logic
│   ├── bash_executor.clj — Bash command execution (raw byte streaming, truncation, temp file)
│   ├── llm.clj         — LLM API calls
│   ├── loop.clj        — Agent conversation loop
│   ├── session.clj     — Session persistence
│   ├── skills.clj      — Skills & extensions system
│   ├── keybindings.clj — App keybindings
│   ├── tools.clj       — Tool public API (re-exports from tools/)
│   ├── tools/          — Tool implementations (one file per tool)
│   │   ├── protocol.clj   — Tool record, param helpers, constants
│   │   ├── read.clj       — read tool (+ image detection)
│   │   ├── write.clj      — write tool
│   │   ├── edit.clj       — edit tool
│   │   ├── bash.clj       — bash tool
│   │   ├── grep.clj       — grep tool (disabled)
│   │   ├── find.clj       — find tool (disabled)
│   │   ├── ls.clj         — ls tool (disabled)
│   │   └── registry.clj   — tool map, schema conversion, registration, execution
│   ├── ui.clj          — Re-exports for app UI components
│   └── ui/             — App-specific TUI components (Pi's coding-agent layer)
│       ├── bash_execution.clj  — BashExecutionComponent (!/!! TUI display)
│       ├── chat_history.clj
│       ├── user_message.clj
│       ├── assistant_message.clj
│       ├── tool_execution.clj
│       ├── custom_message.clj
│       ├── status_indicator.clj
│       └── footer.clj
├── tui/                — Generic TUI library (Pi's @earendil-works/pi-tui)
│   ├── core.clj        — TUI class, render loop, overlays
│   ├── terminal.clj    — JLine 4.x wrapper
│   ├── keys.clj        — key parsing/matching
│   ├── protocols.clj   — IComponent, IFocusable, IComponentKind
│   ├── utils.clj       — text width, wrapping, ANSI helpers
│   ├── theme.clj       — Theme system (fg/bg colors)
│   ├── macros.clj      — with-cache helper
│   └── components/
│       ├── container.clj
│       ├── box.clj
│       ├── text.clj
│       ├── spacer.clj
│       ├── markdown.clj
│       ├── input.clj
│       ├── editor.clj
│       ├── select_list.clj
│       └── settings_list.clj
```

### Layer boundaries
- **`kmet.tui.*`** — generic. No dependency on app, LLM, or session concepts.
- **`kmet.app.ui.*`** — app-specific. Builds on `kmet.tui.*`; imports `with-cache` from `kmet.tui.macros`.
- **`kmet.app.*`** (non-ui) — business logic. Never imports `kmet.tui.*` or `kmet.app.ui.*`.

### ANSI escape codes
- **Never use raw ANSI escape codes (`\u001b[...`) outside `src/kmet/tui/`.**
  All terminal styling goes through `kmet.tui.theme` functions (`theme/fg`, `theme/bg`,
  `theme/bold`, `theme/dim`, `theme/italic`, etc.) which use attribute-specific resets
  (`\u001b[22m` for bold/dim, `\u001b[23m` for italic, `\u001b[39m` for fg, `\u001b[49m` for bg)
  instead of catch-all `\u001b[0m`, so nested styles (e.g. bold inside a `theme/fg` wrapper)
  compose correctly without losing attributes.

## Testing
- **Framework**: `clojure.test`
- **Layout**: `test/kmet/` mirrors `src/kmet/`
- **Run**: `bb test` to validate

## Platform
- **Target**: cross-platform (any system with Babashka and a terminal)
- **Primary dev environment**: Termux on Android — glibc babashka via `ld-linux-aarch64.so.1 --library-path`.
  Do not set `LD_LIBRARY_PATH` globally; use the glibc linker directly when on Termux.
- **Shell resolution** (`kmet.app.bash-executor`): `/bin/bash` → `which bash` → `sh`.
  Works on any Unix-like system. Windows support requires Git Bash or WSL (not tested).

## Error handling
- Use `ex-info` with a `:cause` or `:type` key for structured errors
- Let errors propagate up to the top-level handler rather than swallowing silently

## Logging
- **Module**: `kmet.debug` — minimal file logging, no external library
- **Debug log** (`debug.log`, cwd): opt-in via `--debug` flag. Logs lifecycle events (submit, cancel, agent turns, commands) and handled exceptions with full stack traces. Uses `kmet.debug/log`.
- **Error log** (`kmet.error.log`, cwd): written unconditionally on unhandled exceptions in the `-main` catch block. Uses `kmet.debug/log-error`.
- Both `log` and `log-error` accept Exception objects and expand them to class name, message, and full stack trace.
- Log format: `[ISO_TIMESTAMP] [ERROR: ]message\n`

## Docstrings
- Required on all public vars, protocol methods, and `defrecord` types
- Optional on private vars — use when the intent isn't obvious from the name

## Instruction hierarchy
When guidelines conflict, priority is (highest first):
1. Explicit user instructions in the current conversation
2. This `AGENTS.md` file
3. The pi coding agent harness defaults
4. General best practices

If the user asks for something that contradicts AGENTS.md, explain the conflict and ask for confirmation.

### Component architecture
Each message type has its own `defrecord` implementing `IComponent`:
- `UserMessage` — user text in a `Box` with `user-message-bg`
- `AssistantMessage` — assistant text + thinking (italic + `thinking-text` color)
- `ToolExecution` — tool call/result in `Box` with status background
- `CustomMessage` — info/custom messages in `Box` with `custom-message-bg`
- `ChatHistory` — thin `Container` wrapper that composes per-role components

Type dispatch uses `IComponentKind` protocol (`component-kind` returning `:user`,
`:assistant`, `:tool`, `:custom`). Render cache managed by `kmet.tui.macros/with-cache`.

## Reference
- Consult `~/src/cvstree/pi/` for implementation patterns before building new features — e.g., study its TUI component model before adding new components, or its diff rendering approach before implementing a diff view.
