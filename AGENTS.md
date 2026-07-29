# kmet — Agent Guidelines

## Project
Minimal coding agent in Babashka/Clojure with a JLine3-based TUI (differential rendering, component model, overlays).

## Conventions
- **Entry**: `bb run` — runs `kmet.demo/-main`
- **Deps**: first-party Babashka libraries (`babashka.fs`, `babashka.process`) in `deps.edn`; JLine3 bundled externally.
- **Java interop**: avoid direct Java calls. Prefer Babashka and Clojure APIs:
  - `babashka.fs` over `java.io.File` for file operations
  - `babashka.process` over `java.lang.ProcessBuilder` for subprocesses
  - `clojure.string` functions (`starts-with?`, `includes?`, `index-of`) over Java `.startsWith()`, `.contains()`, `.indexOf()`
  - Pure Clojure for string/character operations (grapheme clusters, char widths) over `java.text.BreakIterator`
  - `clojure.java.io/reader` + `line-seq` over `java.io.BufferedReader`/`InputStreamReader`
- **Type hints**: avoid `^String`, `^java.io.File`, etc. — use Babashka-compatible alternatives.
- **Naming**: kebab-case for fns/vars, `kmet.tui.*` namespace, components in `kmet.tui.components.*`
- **Records, not deftype**: use `defrecord` + `map->` constructors
- **Protocols** for extension: `IComponent` (render/handle-input/invalidate), `IFocusable` (focused/set-focused!)
- **State**: atoms for mutable state (component children, input listeners, render flags)
- **No core.async yet** — kept simple with atoms and futures

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
`bb test` (after tests are added).

## Environment
Termux on Android — glibc babashka via `ld-linux-aarch64.so.1 --library-path`.
Do not set `LD_LIBRARY_PATH` globally; use the glibc linker directly.
