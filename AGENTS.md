# kmet — Agent Guidelines

## Project
Minimal coding agent in Babashka/Clojure with a JLine3-based TUI (differential rendering, component model, overlays).

## Conventions

### Build & Run
- **Entry**: `bb run` — runs `kmet.core/-main`; `bb demo` — runs `kmet.tui-demo/-main`
- **Deps**: first-party Babashka libraries (`babashka.fs`, `babashka.process`) in `deps.edn`; JLine3 bundled externally.

### API Preferences (avoid Java interop)
- **`babashka.fs`** over `java.io.File` for all file operations:
  - `fs/file-name` over `.getName()`
  - `fs/parent` over `.getParent()`
  - `fs/directory?` over `.isDirectory()`
  - `fs/regular-file?` over `.isFile()`
  - `fs/list-dir` over `.listFiles()` / `file-seq`
  - `fs/exists?` over `.exists()`
  - `slurp` / `spit` over Java `Reader`/`Writer` constructors
- `babashka.process` over `java.lang.ProcessBuilder` for subprocesses
- `clojure.string` functions (`starts-with?`, `includes?`, `index-of`) over Java `.startsWith()`, `.contains()`, `.indexOf()`
- Pure Clojure for string/character operations (grapheme clusters, char widths) over `java.text.BreakIterator`
- `clojure.java.io/reader` + `line-seq` over `java.io.BufferedReader`/`InputStreamReader` (only when stream semantics needed; prefer `slurp` for simple reads)
- Avoid `^String`, `^java.io.File`, `^java.io.Reader` etc. type hints — stay Babashka-compatible.
- No `java.io.*` or `java.nio.file.*` imports — everything is available via `babashka.fs` and `slurp`/`spit`

### Code Style
- **Naming**: kebab-case for fns/vars, `kmet.tui.*` namespace, components in `kmet.tui.components.*`
- **Records, not deftype**: use `defrecord` + `map->` constructors
- **Protocols** for extension: `IComponent` (render/handle-input/invalidate), `IFocusable` (focused/set-focused!)
- **State**: atoms for mutable state (component children, input listeners, render flags)
- **No core.async yet** — kept simple with atoms and futures
- **Private vars**: use `defn-` / `def-` for implementation details not part of public API

## File layout
```
src/kmet/
├── tui_demo.clj      — standalone TUI editor demo
├── core.clj          — CLI entry, arg parsing
├── tui/
│   ├── core.clj      — TUI class, render loop, overlays
│   ├── terminal.clj  — JLine3 wrapper
│   ├── keys.clj      — key parsing/matching
│   ├── utils.clj     — text utilities
│   ├── index.clj     — public re-exports
│   └── components/
│       ├── text.clj
│       └── spacer.clj
```

## Testing
- **Framework**: `clojure.test`
- **Layout**: `test/kmet/` mirrors `src/kmet/`
- **Run**: `bb test`

## Environment
Termux on Android — glibc babashka via `ld-linux-aarch64.so.1 --library-path`.
Do not set `LD_LIBRARY_PATH` globally; use the glibc linker directly.

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

## Reference
- Consult `~/src/cvstree/pi/` for implementation patterns before building new features — e.g., study its TUI component model before adding new components, or its diff rendering approach before implementing a diff view.
