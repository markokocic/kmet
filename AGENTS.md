# kmet — Agent Guidelines

## Project
Minimal coding agent in Babashka/Clojure with a JLine3-based TUI (differential rendering, component model, overlays).

## Conventions

### Build & Run
- **Entry**: `bb run` — runs `kmet.demo/-main`
- **Deps**: first-party Babashka libraries (`babashka.fs`, `babashka.process`) in `deps.edn`; JLine3 bundled externally.

### API Preferences (avoid Java interop)
- `babashka.fs` over `java.io.File` for file operations
- `babashka.process` over `java.lang.ProcessBuilder` for subprocesses
- `clojure.string` functions (`starts-with?`, `includes?`, `index-of`) over Java `.startsWith()`, `.contains()`, `.indexOf()`
- Pure Clojure for string/character operations (grapheme clusters, char widths) over `java.text.BreakIterator`
- `clojure.java.io/reader` + `line-seq` over `java.io.BufferedReader`/`InputStreamReader`
- Avoid `^String`, `^java.io.File` etc. type hints — stay Babashka-compatible.

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
├── demo.clj          — demo entry
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
- No logging library — keep the agent minimal and silent in normal operation
- Use `println` for diagnostics during development only (remove before committing)

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
