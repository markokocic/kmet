# kmet specific guidelines Agent Guidelines

## Conventions

### Build & Run
- **Entry**: `bb run` — runs `kmet.core/-main`
- **nREPL**: `bb nrepl` — starts nREPL server on port 1667 for interactive development (blocks). Connect your editor/tool to `localhost:1667`.
  To stop: evaluate `(System/exit 0)` via nREPL (or `fuser -k 1667/tcp` from another terminal).
- **Lint**: `bb lint` — clj-kondo over `src`/`test`. Custom macros (`defcomponent`/`defsetter`/`defgetter`)
  are handled via analysis hooks in `.clj-kondo/hooks/`; keep them in sync when the macro shapes change.
- **Format**: `bb format` (fix) / `bb format-check` (verify) — cljfmt over `src`/`test`.
  `cljfmt.edn` carries `:extra-indents` for the custom macros; default arg alignment is
  align-to-first-argument (modern cljfmt). Run `bb format` after structural edits (e.g. let merges).
- **Deps**: first-party Babashka libraries (`babashka.fs`, `babashka.process`) in `deps.edn`;
  tooling deps (`cljfmt`) in `bb.edn` `:deps`; JLine **4.3.1** bundled with Babashka (see
  babashka `deps.edn`: `org.jline/jline-terminal`, `org.jline/jline-reader`).

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

## Editing
- When an `edit` call fails because of unbalanced parens, try the `clojure_paren_repair` tool first; if that doesn't help, split the change into smaller focused edits.

## File layout
```
src/kmet/
├── core.clj            — CLI entry, arg parsing, mode dispatch (pi: cli.js)
├── config.clj          — Configuration loading
├── debug.clj           — Debug/error logging
├── libs/               — Generic, self-contained code that would be a third-party
│   │                     library on the JVM (Babashka-compatible reimplementations)
│   ├── diff.clj        — Myers O(ND) line diff
│   ├── process.clj     — Process tree management (descendant collection, tree kill, pid registry)
│   ├── sse.clj         — SSE line parsing + LLM stream processing
│   ├── yaml_lite.clj   — Minimal YAML subset parser (frontmatter; babashka-compatible)
│   └── terminal_image.clj — Kitty terminal image protocol + image dimension parsing
│                           (native PNG/JPEG/GIF via f= codes — no conversion)
├── modes/              — Entry modes (pi: dist/modes/)
│   ├── interactive.clj — Interactive TUI: layout, CoreState, submit/cancel,
│   │                     bash commands, external editor, session browsing
│   └── print.clj       — Print mode: send message, print response, exit
├── app/                — App-level business logic (pi: dist/core/)
│   ├── bash_executor.clj — Bash command execution (raw byte streaming, truncation, temp file)
│   ├── llm.clj         — LLM API calls
│   ├── proxy.clj      — Proxy env vars (HTTPS_PROXY/ALL_PROXY/NO_PROXY) + transport;
│   │                     SOCKS & https-scheme proxies via curl (java.net.http is HTTP-proxy-only)
│   ├── loop.clj        — Agent conversation loop
│   ├── session.clj     — Session persistence
│   ├── skills.clj      — Skills loading + system prompt
│   ├── prompts.clj     — Prompt template loading + /name expansion (pi: core/prompt-templates.js)
│   ├── frontmatter.clj — YAML frontmatter parsing shared by skills/prompts (pi: utils/frontmatter.js)
│   ├── extensions.clj  — Extension loading, input/before-agent-start hooks
│   ├── event_bus.clj   — Event vocabulary + extension event bus
│   ├── commands.clj    — Slash command registry (builtins, skills, extensions)
│   ├── keybindings.clj — App keybindings
│   ├── tools/          — Tool implementations (one file per tool)
│   │   ├── core.clj    — Tool public API (re-exports from tool.clj/registry.clj)
│   │   ├── tool.clj    — Tool record, param helpers, schema conversion
│   │   ├── read.clj    — read tool (+ image detection)
│   │   ├── write.clj   — write tool
│   │   ├── edit.clj    — edit tool
│   │   ├── bash.clj    — bash tool
│   │   ├── grep.clj    — grep tool (disabled)
│   │   ├── find.clj    — find tool (disabled)
│   │   ├── ls.clj      — ls tool (disabled)
│   │   ├── util.clj    — Shared tool utilities (safe file traversal)
│   │   └── registry.clj — tool registry, registration, execution
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
│   ├── keybindings.clj — TUI keybindings
│   ├── fuzzy.clj       — fuzzy matching (select-list filter)
│   ├── autocomplete.clj — editor autocomplete
│   ├── protocols.clj   — IComponent, IFocusable, IComponentKind
│   ├── utils.clj       — text width, wrapping, ANSI helpers
│   ├── theme.clj       — Theme system (fg/bg colors)
│   ├── terminal_image.clj — Kitty protocol image rendering
│   ├── macros.clj      — track! reactive cache (deref tracking)
│   └── components/
│       ├── container.clj
│       ├── box.clj
│       ├── text.clj
│       ├── spacer.clj
│       ├── markdown.clj
│       ├── input.clj
│       ├── editor.clj
│       ├── editing.clj
│       ├── select_list.clj
│       ├── settings_list.clj
│       ├── spinner.clj
│       ├── image.clj
│       ├── scroll_view.clj — bounded viewport over one child (pi ScrollView:
│       │                     follow-end, scroll API, scrollbar state machine)
│       ├── stack.clj    — stack sizing (allocate-stack-sizes) + the render-loop
│       │                  vertical layout (the single IScrollView entry grows
│       │                  to fill remaining height)
│       ├── h_stack.clj  — horizontal flex stack (grow/shrink allocation,
│       │                  ANSI-aware line compositing; pi HStack)
│       ├── v_stack.clj  — vertical stack component (children top-to-bottom
│       │                  with gap; pi VStack — used for the interactive dock)
│       ├── alt_screen_flash.clj — transient inverse-video messages owned by
│       │                  the TUI and composited over the screen bottom
│       │                  (pi AltScreenFlashContainer; tui-flash!)
│       ├── cancellable_loader.clj — spinner cancellable with Escape + abort
│       │                  signal (pi CancellableLoader)
│       └── truncated_text.clj — single-line truncating text (pi TruncatedText;
│                              used for the chat status line)
```

### Layer boundaries
- **`kmet.libs.*`** — generic, self-contained. **Must not require any other kmet.*
  namespace** (no app, tui, modes, or sibling-lib deps). Each lib is a portable
  unit: only stdlib + third-party deps, and any bundled assets (scripts) live in
  the lib directory. Enforced by `kmet.libs.test-self-contained`.
- **`kmet.tui.*`** — generic. No dependency on app, LLM, or session concepts.
  May depend on `kmet.libs.*`.
- **`kmet.modes.*`** — entry modes. Depends on `kmet.app.*`, `kmet.tui.*`, `kmet.config`.
- **`kmet.app.ui.*`** — app-specific. Builds on `kmet.tui.*`; imports `track!` from `kmet.tui.macros`.
- **`kmet.app.*`** (non-ui) — business logic. Never imports `kmet.tui.*` or `kmet.app.ui.*`.
  May depend on `kmet.libs.*`.
- **`kmet.core`** — entry only: args + dispatch. Never contains app logic.

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
- **Run**: `bb test` — all tests except those marked `^:slow`.
  Use **`bb test-ext`** to run only the `^:slow` tests (timing/process
  suites: real backoff sleeps, parallel tool timing, bash tool process
  spawns). Mark slow tests with `^:slow` on the deftest; selection happens
  per test var in `kmet.runner`.

### Final validation
`bb lint` and `bb format-check` are slow — don't run them during iterative
development. Run the full gate once before wrapping up:
`bb lint` + `bb format-check` + `bb test` + `bb test-ext`.

## Platform
- **Fully supported**: Linux, WSL, Windows, Termux (Android)
- **macOS**: supported too, but no way to test there — expect untested rough edges
- **Primary dev environment**: Termux on Android — glibc babashka via `ld-linux-aarch64.so.1 --library-path`.
  Do not set `LD_LIBRARY_PATH` globally; use the glibc linker directly when on Termux.
- **Shell resolution** (`kmet.app.bash-executor`): `/bin/bash` → `which bash` → `sh`.
  On Windows this resolves through Git Bash; under WSL the WSL shell is used.

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
- No trivial docstrings — a docstring must add information beyond the name (intent, contract, args/return, side effects, exceptions). Skip it when the name is self-explanatory.
- Where behavior isn't obvious, document: public vars, protocol methods, and `defrecord` types
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
- `ChatHistory` — data-driven: holds plain message maps in one `messages-atom`
  (each carrying its `:component`); render derives the tree, persistence reads
  the atom directly (no component reverse-engineering)

Type dispatch uses `IComponentKind` protocol (`component-kind` returning `:user`,
`:assistant`, `:tool`, `:custom`).

### Reactive render cache (track!)
- Wrap a component's render body with `(track! this width ...)`. Every `@atom`
  read is recorded; when any of them changes, the cache invalidates
  automatically — setters become plain `reset!`/`swap!` with no manual
  `(protocols/invalidate comp)` call. Requires a `:cache-atom` (or legacy
  `:cache`) field on the record.
- Do NOT use track! when the cache depends on computed child output (Box),
  when rendering is time-animated (spinner), or for deliberately uncached
  input widgets (input, editor). `tool_execution` keeps manual invalidate —
  its invalidate is a re-render signal (fires `request-render-fn`), not cache
  boilerplate.

## Reference
- Consult `~/src/cvstree/pi/` for implementation patterns before building new features — e.g., study its TUI component model before adding new components, or its diff rendering approach before implementing a diff view.
