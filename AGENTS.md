# kmet specific guidelines Agent Guidelines

## Conventions

### Build & Run
- **Entry**: `bb run` — runs `kmet.core/-main`
- **nREPL**: `bb nrepl` — starts nREPL server on port 1667 for interactive development (blocks). Connect your editor/tool to `localhost:1667`.
  To stop: evaluate `(System/exit 0)` via nREPL (or `fuser -k 1667/tcp` from another terminal).
- **Lint**: `bb lint` — clj-kondo over `src`/`test`. Custom macros (`defcomponent`/`with-let`)
  are handled via analysis hooks in `.clj-kondo/hooks/`; keep them in sync when the macro shapes change.
  The gate requires `bb lint` to pass with 0 errors, warnings, and info findings.
- **Format**: `bb format` (fix) / `bb format-check` (verify) — cljfmt over `src`/`test`.
  The generated EDN provider catalogs (`src/kmet/ai/model_data/`,
  `src/kmet/ai/image_model_data/`) are excluded: their exact bytes are
  sha256-manifested (`manifest.edn`, checked by `bb check-model-data`) and are
  owned solely by the generator (scripts/generate_models.clj, delegating to
  `kmet.ai.model-gen`; `kmet --generate-models` runs the same pipeline into
  the user-level cache ~/.kmet/agent/models-cache/, preferred over the
  bundled catalogs when strictly newer).
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
  `extensions/` is covered too: its .clj files (source **and any tests they carry**) are part
  of `bb lint`/`bb format`/`bb format-check` and the changed-file scan. Extension tests are
  separate projects — they run from inside their directory (own deps), never from the root
  runner; `bb test-changed` prints a hint instead of silently skipping them.
- **Deps**: first-party Babashka libraries (`babashka.fs`, `babashka.process`) in `deps.edn`;
  tooling deps (`cljfmt`) in `bb.edn` `:deps`; JLine **4.3.1** bundled with Babashka (see
  babashka `deps.edn`: `org.jline/jline-terminal`, `org.jline/jline-reader`).
- **Packaging** (`kmet.build`): `bb uberjar` → `target/kmet.jar` (src + resolved dep jars,
  only `borkdude/deps.clj` isn't bb-builtin); `bb build [targets|--all] [--force] [--no-smoke]`
  → self-contained executables in `dist/` (official bb release binary + appended uberjar,
  version = git tag else short hash else "dev"). On a termux host a `.sh` launcher is emitted
  next to the binary: glibc linker exec + `--jar <self>` (auto-detection breaks because
  `/proc/self/exe` resolves to `ld-linux`). Downloads cached + sha256-checked in
  `target/build-cache/`.

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
- **Agent state model** (`kmet.app.loop/AgentState`): per-field atoms ARE
  the concurrency model — independent cells (`signal`, `active-call`,
  queues) stay separate; runtime-tunable knobs live in one `:cfg` atom
  holding an immutable map (`swap!`-assoc). No single app-db atom:
  swap-retry storms at token-streaming rates.
- **Dispatch**: explicit tables/maps over multimethods — extensions register
  at runtime through calls, so compile-time `defmethod` registries would
  fork the mechanism (see the tool renderer registry)
- **Private vars**: use `defn-` / `def-` for implementation details not part of public API

## Editing
- For Clojure, Babashka and EDN files (`.clj` `.cljs` `.cljc` `.bb` `.edn`), prefer the
  structure-aware extension tools over the generic `write`/`edit` tools:
  `clojure_edit` finds a definition by `form_type` + `form_identifier` and
  `replace`/`insert_before`/`insert_after`s it; `clojure_edit_replace_sexp`
  replaces an s-expression by content match (`replace_all` to rename a symbol
  file-wide). They validate structure, reject unbalanced delimiters, and
  format with cljfmt. Keep the generic `edit`/`write` tools for plain textual
  changes (comments, docstrings, non-form text).
- For `insert_before`/`insert_after`, pass ONLY the new content — never
  re-include the anchor form. The inserted form lands outside the anchor's
  own line: a same-line trailing comment stays with the anchor, and a
  comment on its own line stays with the next form.
- Alias-qualified forms (`(t/deftest ...)`, `(s/def ...)`) match with the
  plain `form_type` keyword (`deftest`, `def`) — the qualified name also
  works.
- `clojure_edit_replace_sexp` `match_form`/`new_form` must be COMPLETE
  expressions with balanced parens; fragments like `:else [w j])` are
  rejected.
- When an `edit` call fails because of unbalanced parens, try the `clojure_paren_repair` tool first; if that doesn't help, split the change into smaller focused edits.

## File layout
```
src/kmet/
├── libs/     — Generic, self-contained code that would be a third-party library
│              on the JVM (Babashka-compatible reimplementations)
├── modes/    — Entry modes (pi: dist/modes/)
├── ai/       — Provider/auth subsystem (pi: packages/ai — a standalone library
│              the agent depends on; enforced by the
│              kmet.ai.test-self-contained guard: only kmet.libs.* deps)
│   ├── api/               — Per-wire LLM API builders (pi: packages/ai/src/api/)
│   ├── model_data/        — committed provider catalogs + manifest (bb generate-models)
│   └── image_model_data/  — committed image-model catalog (bb generate-image-models)
├── app/      — App-level business logic (pi: dist/core/)
│   ├── tools/  — Tool implementations (one file per tool)
│   └── ui/     — App-specific TUI components (Pi's coding-agent layer)
└── tui/      — Generic TUI library (Pi's @earendil-works/pi-tui)
    │           Usage docs: src/kmet/tui/tui.md — MUST be kept up to date
    │           with any behavior change they describe
    └── components/ — TUI leaf components (Container, Box, Text, ...)

extensions/ — Shipped opt-in extensions (single .clj files or manifest dirs;
              pi: examples/extensions). Extension authoring guide (the full
              kmet.extension contract): extensions/extensions.md — MUST be
              kept up to date with any behavior it describes

Root-level files: core.clj (CLI entry, arg parsing, mode dispatch), config.clj
(configuration loading), debug.clj (debug/error logging), extension.clj (the
extension contract root: namespaces extensions depend on, init/shutdown, api).
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
  `bb format-check-changed` (see Build & Run), or run individual test namespaces with filters.

### Final validation
`bb lint` and `bb format-check` are slow — don't run them during iterative
development. **Do not run the full gates unless explicitly told to**: `bb lint` +
`bb format-check` + `bb test` + `bb test-ext` (plus `bb test` inside any extension
directory that carries its own tests) are only run when the user explicitly asks
for a full gate. The default validation loop is the changed-file tasks above.
`bb lint` must pass with 0 errors, warnings, and info findings.

## Platform

- **SCI gotcha**: `(satisfies? SomeProto reify-instance)` can return false
  under Babashka even when methods are registered — dispatch through the
  protocol's multimethod instead (see tui.md §5.1).
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

Type dispatch is kind-as-data: `defcomponent` stamps KIND as the record's
first field; dispatch reads `(:kind component)` (no IComponentKind
protocol — retired in DSL stage 2, see tui.md §8).

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
- **TUI package docs**: `src/kmet/tui/tui.md` is the usage reference for
  `kmet.tui.*` (Hiccup DSL, fn components, reactivity/track!, state model,
  lifecycle, scheduling, input boundary). It must be kept up to date: a
  change to TUI behavior described there updates the doc in the same
  change.
- Consult `~/src/cvstree/pi/` for implementation patterns before building new features — e.g., study its TUI component model before adding new components, or its diff rendering approach before implementing a diff view.
