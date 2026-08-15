# kmet specific guidelines Agent Guidelines

## Conventions

### Build & Run
- **Entry**: `bb run` — runs `kmet.core/-main`
- **nREPL**: `bb nrepl` — starts nREPL server on port 1667 for interactive development (blocks). Connect your editor/tool to `localhost:1667`.
  To stop: evaluate `(System/exit 0)` via nREPL (or `fuser -k 1667/tcp` from another terminal).
- **Lint**: `bb lint` — clj-kondo over `src`/`test`. Custom macros (`defcomponent`/`defsetter`/`defgetter`)
  are handled via analysis hooks in `.clj-kondo/hooks/`; keep them in sync when the macro shapes change.
  The gate requires `bb lint` to pass with 0 errors, warnings, and info findings.
- **Format**: `bb format` (fix) / `bb format-check` (verify) — cljfmt over `src`/`test`.
  `cljfmt.edn` carries `:extra-indents` for the custom macros; default arg alignment is
  align-to-first-argument (modern cljfmt). Run `bb format` after structural edits (e.g. let merges).
- **Changed-file dev loop** (fast validation of only the current changes): the `bb *-changed`
  tasks are backed by `kmet.changed` (`test/kmet/changed.clj`) — a require-graph scan over
  src/test with reverse-transitive closure, so a source change also re-runs the tests that
  transitively require it. `bb changed` lists files changed since the last commit
  (git diff vs HEAD + untracked; mtime-since-baseline fallback when the project has no git).
  `bb test-changed` runs the non-slow tests of affected namespaces and
  `bb test-ext-changed` the slow (^:slow) ones (full gates: `bb test` / `bb test-ext`);
  `bb lint-changed` lints changed files plus affected
  dependents (a changed signature is only flagged at the call site), falling back to a full
  lint when `.clj-kondo/` config/hooks changed; `bb format-check-changed` / `bb format-changed`
  cover just the changed files. Full gates stay `bb test`/`bb test-ext`/`bb lint`/`bb format-check`.
  Caveats: "changed" is since-last-commit, so a full gate without committing re-runs those
  files next time (over-inclusive, never under); green full `bb test`/`bb test-ext` runs
  (no filters) update the mtime baseline via `kmet.runner` → `kmet.changed/mark-validated!`,
  which is a no-op with git.
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
│   ├── terminal.clj    — Terminal protocol knowledge: Kitty keyboard
│   │                     negotiation, escape sequences, raw-ANSI write log
│   │                     (pi terminal.ts; writer-fn based)
│   ├── yaml_lite.clj   — Minimal YAML subset parser (frontmatter; babashka-compatible)
│   ├── frontmatter.clj — YAML frontmatter extraction (--- delimited block +
│   │                     yaml-lite parse; pi utils/frontmatter.js)
│   ├── clipboard.clj   — Clipboard copy via platform tools (Termux/Wayland/
│   │                     X11/macOS/Windows; OSC52 fallback lives in libs.terminal)
│   ├── terminal_image.clj — Kitty terminal image protocol + image dimension parsing
│   │                     (native PNG/JPEG/GIF via f= codes — no conversion)
│   ├── file_lock.clj   — Cross-process file locking (settings.edn/auth.edn writes)
│   ├── hash.clj        — pi shortHash port (32-bit imul/ushr, byte-exact)
│   ├── highlight.clj   — Syntax highlighting (pi highlight.ts)
│   └── markdown.clj    — Markdown rendering for the chat view
├── modes/              — Entry modes (pi: dist/modes/)
│   ├── interactive.clj — Interactive TUI: layout, CoreState, submit/cancel,
│   │                     bash commands, external editor, session browsing
│   └── print.clj       — Print mode: send message, print response, exit
├── ai/                 — Provider/auth subsystem (pi: packages/ai — a standalone
│   │                     library the agent depends on; enforced by the
│   │                     kmet.ai.test-self-contained guard: only kmet.libs.* deps)
│   ├── models.clj      — Model/Provider records + registry atom, catalog
│   │                     loading + manifest, cost (pi: models.ts; Phases 0-5)
│   ├── model_data/     — committed provider catalogs + manifest (bb generate-models)
│   ├── model_config.clj   — models.edn loading + validation (pi: model-config.ts)
│   ├── provider_composer.clj — builtin+models.edn+extension composition
│   │                     (pi: provider-composer.ts)
│   ├── config_value.clj    — $ENV/!command config-value resolution (pi: resolve-config-value.ts)
│   ├── auth.clj        — env-var table, auth.edn, credential resolution
│   │                     (pi: env-api-keys.ts + auth-storage.ts)
│   ├── oauth.clj       — OAuthAuth record, device-code + PKCE loopback
│   │                     flows + the callback server (pi: auth/oauth/*.ts)
│   ├── attribution.clj — provider attribution headers (pi: provider-attribution.ts)
│   ├── aws_sigv4.clj   — AWS SigV4 request signing for bedrock (no AWS SDK in bb)
│   ├── google_adc.clj  — Google ADC token fetch for google-vertex
│   ├── image_models.clj — image-generation registry + :openrouter-images
│   │                     wire (pi: images*.ts; Deferred B)
│   ├── image_model_data/ — committed image-model catalog (bb generate-image-models)
│   ├── usage.clj       — usage-map normalization (pi normalizeUsage; shared by
│   │                     the wire layer, session store and footer cost display)
│   ├── llm.clj         — LLM API dispatcher (pi: send-message — auth/model
│   │                     resolution, effort clamp, dispatch to kmet.ai.api.*)
│   ├── api/            — Per-wire LLM API builders (pi: packages/ai/src/api/)
│   │   ├── shared.clj  — URL construction, headers, thinking levels, message
│   │   │                transformers, tool schemas, cost/event handling
│   │   │                (pi transform-messages.ts + openai-prompt-cache.ts)
│   │   ├── openai_completions.clj — OpenAI Completions (pi openai-completions.ts)
│   │   ├── openai_responses.clj — OpenAI Responses (pi openai-responses.ts)
│   │   ├── openai_codex_responses.clj — Codex responses (pi openai-codex-responses.ts)
│   │   ├── azure_openai_responses.clj — Azure responses (pi azure-openai-responses.ts)
│   │   ├── anthropic_messages.clj — Anthropic Messages (pi anthropic-messages.ts)
│   │   ├── google_generative_ai.clj — Gemini (pi google-generative-ai.ts)
│   │   ├── mistral_conversations.clj — Mistral (pi mistral-conversations.ts)
│   │   ├── google_vertex.clj — Vertex (pi google-vertex.ts)
│   │   └── bedrock_converse_stream.clj — Bedrock Converse (pi bedrock-converse-stream.ts)
│   └── proxy.clj      — Proxy env vars (HTTPS_PROXY/ALL_PROXY/NO_PROXY) + transport;
│                       SOCKS & https-scheme proxies via curl (java.net.http is HTTP-proxy-only)
├── app/                — App-level business logic (pi: dist/core/)
│   ├── model_resolver.clj — model pattern/CLI resolution (pi: model-resolver.ts;
│   │                     resolves against kmet.ai.models)
│   ├── bash_executor.clj — Bash command execution (raw byte streaming, truncation, temp file)
│   ├── loop.clj        — Agent conversation loop
│   ├── compaction.clj  — Conversation compaction (pi: compaction.ts)
│   ├── context.clj     — Context file discovery (AGENTS.md/CLAUDE.md)
│   ├── session.clj     — Session persistence
│   ├── session_export.clj — HTML export for /export and /share (standalone
│   │                     dark page; JSONL deliberately not built)
│   ├── skills.clj      — Skills loading + system prompt
│   ├── prompts.clj     — Prompt template loading + /name expansion (pi: core/prompt-templates.js)
│   ├── extension.clj   — THE extension contract (root): the namespaces
│   │                     extensions depend on — kmet.extension plus the
│   │                     shared kmet.tui.* / kmet.libs.* library layers;
│   │                     init/shutdown contract, api
│   │                     wrappers, create-nullable-api test fixture
│   ├── extensions.clj  — Extension runtime: discover/load/reload/unload
│   │                     (single .clj or extension.edn manifest dirs),
│   │                     per-extension deregistration, api construction,
│   │                     UI registry + dispatchers (pi: ExtensionUIContext)
│   ├── event_bus.clj   — Event vocabulary + extension event bus
│   ├── commands.clj    — Slash command registry (builtins, skills, extensions)
│   ├── keybindings.clj — App keybindings
│   ├── theme_controller.clj — Theme switching / controller
│   ├── tools/          — Tool implementations (one file per tool)
│   │   ├── core.clj    — Tool public API (re-exports from tool.clj/registry.clj)
│   │   ├── tool.clj    — Tool record, param helpers, schema conversion
│   │   ├── read.clj    — read tool (+ image detection)
│   │   ├── write.clj   — write tool
│   │   ├── edit.clj    — edit tool
│   │   ├── edit_diff.clj — edit diff application (pi edit-diff)
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
│       ├── extension_dialogs.clj  — ui.select/input/editor dialogs (DynamicBorder
│       │                  framing + IME focus propagation)
│       ├── external_editor.clj — editor-text-* accessors (duck-typed IEditorComponent)
│       │                  + handle-external-editor ($EDITOR on a temp file; pi external-editor.ts)
│       ├── fork_selector.clj — fork-from-message picker, on-select callback
│       │                  (pi UserMessageSelectorComponent; the fork stays in the mode)
│       ├── model_selector.clj — /model + /scoped-models selectors and model-switch
│       │                  helpers (pi model-selector.ts + model-search.ts; shared with
│       │                  Ctrl+P cycling and the footer sync)
│       ├── session_selector.clj — session browsing overlay with streaming
│       │                  session-info population (pi SessionSelectorComponent;
│       │                  the restore stays in the mode)
│       ├── settings_selector.clj — /settings overlay: thinking level, hide-thinking,
│       │                  retry block + theme row (pi showSettingsSelector, simplified)
│       ├── tree_selector.clj — /tree overlay: filter modes (ctrl+d/t/u/l/a/o),
│       │                  label editing, current-leaf marker (pi TreeSelectorComponent;
│       │                  navigation stays in the mode via on-navigate)
│       ├── status_indicator.clj
│       ├── footer.clj
│       ├── footer_data_provider.clj — footer state (model/thinking/cost/CH%)
│       ├── loaded_resources.clj — loaded-context display
│       ├── pending_messages.clj — queued-message indicator
│       └── scoped_models_selector.clj — /scoped-models selector (pi ScopedModelsSelectorComponent)
├── tui/                — Generic TUI library (Pi's @earendil-works/pi-tui)
│   ├── core.clj        — TUI class, render loop, overlays
│   ├── terminal.clj    — JLine 4.x wrapper: ITerminal protocol + the
│   │                     record-taking wrappers over kmet.libs.terminal
│   │                     (negotiation, drain); portable protocol code
│   │                     lives in the lib
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
│       ├── expandable_text.clj
│       ├── select_list.clj
│       ├── settings_list.clj
│       ├── spinner.clj
│       ├── image.clj
│       ├── scroll_view.clj — bounded viewport over one child (pi ScrollView:
│       │                     follow-end, scroll API, scrollbar state machine);
│       │                     standalone component, not used by the interactive
│       │                     layout — the main screen scrolls natively
│       ├── stack.clj    — stack sizing (allocate-stack-sizes) + the render-loop
│       │                  vertical layout: every component renders at its
│       │                  natural height, a single flat document (pi Container;
│       │                  overflow scrolls into the native terminal scrollback)
│       ├── h_stack.clj  — horizontal flex stack (grow/shrink allocation,
│       │                  ANSI-aware line compositing; pi HStack)
│       ├── v_stack.clj  — vertical stack component (children top-to-bottom
│       │                  with gap; pi VStack — used for the interactive dock)
│       ├── alt_screen_flash.clj — transient inverse-video messages owned by
│       │                  the TUI and composited over the screen bottom
│       │                  (pi AltScreenFlashContainer; tui-flash!)
│       ├── cancellable_loader.clj — spinner cancellable with Escape + abort
│       │                  signal (pi CancellableLoader)
│       ├── dynamic_border.clj — theme-colored border line spanning the
│       │                  render width (pi DynamicBorder)
│       └── truncated_text.clj — single-line truncating text (pi TruncatedText;
│                              used for the chat status line)
```

### Layer boundaries
- **`kmet.libs.*`** — generic, self-contained. **Must not require any kmet.*
  namespace outside `kmet.libs.*`** (no app, tui, modes, ai, or sibling-lib
  deps beyond the libs tree itself). Each lib is a portable
  unit: only stdlib + third-party deps, and any bundled assets (scripts) live in
  the lib directory. Enforced by `kmet.libs.test-self-contained`.
- **`kmet.ai.*`** — provider/auth subsystem (pi: `packages/ai`). **Must not require
  any other kmet.* namespace beyond `kmet.libs.*`** — a standalone library the
  agent depends on. Enforced by `kmet.ai.test-self-contained`.
- **`kmet.tui.*`** — generic. No dependency on app, LLM, or session concepts.
  May depend on `kmet.libs.*`.
- **`kmet.modes.*`** — entry modes. Depends on `kmet.app.*`, `kmet.tui.*`, `kmet.config`.
- **`kmet.app.ui.*`** — app-specific. Builds on `kmet.tui.*`; imports `track!` from `kmet.tui.macros`.
- **`kmet.app.*`** (non-ui) — business logic. Never imports `kmet.tui.*` or `kmet.app.ui.*`.
  May depend on `kmet.libs.*` and `kmet.ai.*`.
- **`kmet.core`** — entry only: args + dispatch. Never contains app logic.

### ANSI escape codes
- **Never use raw ANSI escape codes (`\u001b[...`) outside `src/kmet/tui/` and
  `src/kmet/libs/terminal.clj`** (the protocol library, where they belong by
  design).
  All terminal styling goes through `kmet.tui.theme` functions (`theme/fg`, `theme/bg`,
  `theme/bold`, `theme/dim`, `theme/italic`, etc.) which use attribute-specific resets
  (`\u001b[22m` for bold/dim, `\u001b[23m` for italic, `\u001b[39m` for fg, `\u001b[49m` for bg)
  instead of catch-all `\u001b[0m`, so nested styles (e.g. bold inside a `theme/fg` wrapper)
  compose correctly without losing attributes.

## Testing
- **Framework**: `clojure.test`
- **Layout**: `test/kmet/` mirrors `src/kmet/`
- **Run**: `bb test` — all tests except those marked `^:slow`.
  Use **`bb test-ext`** to run only the `^:slow` tests (tests that wait
  real wall-clock time: sleeps, terminal-query timeouts; real network
  calls; and subprocess spawns — bash tool, shell commands, git). Mark
  slow tests with `^:slow` on the deftest; selection happens per test var
  in `kmet.runner`.
- New test namespaces must be registered in `kmet.runner/all-namespaces` (the full run loads
  exactly that list).
- During development, validate only what changed with `bb test-changed` / `bb lint-changed` /
  `bb format-check-changed` (see Build & Run); the full suite stays the pre-wrap-up gate.

### Final validation
`bb lint` and `bb format-check` are slow — don't run them during iterative
development. Run the full gate once before wrapping up:
`bb lint` + `bb format-check` + `bb test` + `bb test-ext`.
`bb lint` must pass with 0 errors, warnings, and info findings.

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

## Debugging scripts (`scripts/`)
For debugging terminal rendering issues — modify them (sizes, timing, input)
to reproduce the issue at hand. The kmet scripts hardcode the Termux path.

- Capture a session's raw output: `tmux_capture.sh <session> <send-after> <text> <timeout> <outfile> <cmd...>`,
  `tmux_repro.sh <name> <outfile> <cmd...>` (fixed sequence incl. resize),
  `pty_capture.py` (tmux-free pty; `--cols/--rows/--text/--timeout/--out`).
- Analyze: `term_dump.py <raw-capture>` replays the bytes through a minimal
  ANSI emulator and dumps frames (at 2026 sync boundaries) with colors.
- kmet scenarios: `kmet_sanity.sh <outfile>` (startup + wheel scroll + exit),
  `kmet_verify.sh <outfile>` (flicker metric while streaming + scrolled up).

Workflow: run a capture script → `python3 scripts/term_dump.py out.raw`.

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
- **All UI components are defined with `defcomponent`** (never a bare
  `defrecord` implementing IComponent — extra protocols like IFocusable go
  in separate `extend-type` forms after the call, e.g. select-list.clj).
  Enforced by `test-caching-conventions`.
- Each message type has its own `defrecord` implementing `IComponent`:
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
- **Default**: wrap a component's render body with `(track! this width ...)`
  and give the record a `:cache-atom` field. Every `@atom` read is recorded;
  when any of them changes, the cache invalidates automatically — setters
  become plain `reset!`/`swap!` with no manual `(protocols/invalidate comp)`
  call. Enforcement: `test-caching-conventions` requires every component with a
  `render` method to either use track! or be on the documented uncached
  allowlist.
- **`defcomponent` generates the cache-clearing `invalidate`**: when the
  render calls track!, the macro adds `(invalidate [this]
  (invalidate-cache this))` unless a custom method is given — in which case
  the cache clear is prepended automatically. Components only write an
  `invalidate` method for extra side effects (delegating to children,
  firing `request-render-fn`).
- **`track-deps`** declares dependencies inside a track! body: atoms whose
  changes must invalidate the cache even though their values don't appear in
  the body — `(track-deps @theme-atom @content-atom)` (wrapper components
  re-rendering cached children).
- **Sound only when the output is a function of atoms the render derefs**:
  leaves (Text, Spacer, Markdown, Image, ...) and self-contained composites
  whose mutation paths touch their own atoms (messages, tool executions,
  footer, status line, expandable header, ...). When a child's internal state
  affects the output, the render must deref it too (e.g. StatusLine and
  ExpandableText deref the inner component's text atom). Atoms that the
  render body itself mutates (e.g. tool_execution's last-component atoms,
  Image's id allocation) are read through non-tracking helpers so they don't
  self-invalidate the cache.
- **Do NOT use track!** for: transparent parents (Container, Box, HStack,
  VStack, ChatHistory, ScrollView — children change independently and the
  parent cannot track that; they stay cheap by relying on children's caches;
  Box additionally memoizes its padding/bg composition), time-animated
  output (Spinner, status indicators, flashes — must render fresh every
  pass), and focused input widgets (input, editor). Time-animated content
  must live at the document bottom (spinner/status) or be cached so it only
  ticks with real updates (the tool-execution elapsed counter ticks via its
  own 1s invalidate interval while a tool is partial — pi: setInterval →
  context.invalidate — so a silent long-running tool still updates steadily).
- A render body that invalidates itself mid-run (a render fn calling
  `:invalidate`, or `set-state!` on tracked state) does not cache its stale
  result: track-render watches the cache atom, so the next render re-runs
  the body with the fresh state.
- **Full redraws emit `\u001b[3J` (erase scrollback)**: the full redraw
  re-emits the whole transcript, so the scrollback must be cleared or the
  history duplicates (pi issue #6050). Windows Terminal scrolls to the top
  on 3J — a known WT bug (microsoft/terminal#20370) accepted over duplicated
  output (see `do-full-redraw` in `kmet.tui.core`).

## Reference
- Consult `~/src/cvstree/pi/` for implementation patterns before building new features — e.g., study its TUI component model before adding new components, or its diff rendering approach before implementing a diff view.
