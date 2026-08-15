# Built-in extensions

Extensions shipped with kmet, living in the repo so they stay in sync with
the extension contract (`kmet.extension`). They are **opt-in**: nothing here
is loaded by default.

## Enabling an extension

Extensions load from the global dir (`~/.kmet/agent/extensions/`) and the
project-local dir (`.kmet/extensions/`) at startup and on `/reload`. Enable
a shipped extension by symlinking or copying it into one of those:

```bash
# global (all projects)
mkdir -p ~/.kmet/agent/extensions
ln -s "$PWD/extensions/tools.clj" ~/.kmet/agent/extensions/tools.clj

# or project-local
mkdir -p .kmet/extensions
ln -s "$PWD/extensions/tools.clj" .kmet/extensions/tools.clj
```

A symlink keeps the shipped copy updated with the repo; a copy is a
snapshot you can edit locally. Either way, restart kmet or run `/reload`
to pick it up.

## Layout rules

- **Single-file extensions** (`*.clj` at the top level of the directory) —
  one namespace defining `(defn init [api])`. They cannot carry a
  `deps.edn`, so they may only use `kmet.extension` plus the shared
  `kmet.tui.*` / `kmet.libs.*` library layers and `clojure.*` /
  `babashka.*` builtins. They have
  **no tests** — the code is expected to
  stay small and self-contained; validate changes by loading the file
  against `kmet.extension/create-nullable-api` or the real runtime.
- **Directory-based extensions** (a subdirectory with an `extension.edn`
  manifest `{:name ... :entry "src/main.clj"}`) — separate projects: they
  may have their own source layout, a `deps.edn` for library
  dependencies, and their own tests (run them from inside the directory,
  e.g. `bb test` against the extension's own deps).

All extension files — source **and any tests they carry** — are covered by
the repo gates: `bb lint` / `bb format` / `bb format-check` lint and format
`extensions/`, and the `bb *-changed` dev loop tracks extension files
(extension namespaces join the require graph for linting, but extension
tests are never selected by the root `bb test` runner — they run from
inside the extension directory).

## Shipped extensions

| Extension | Description |
|-----------|-------------|
| `tools.clj` | Interactive `/tools` command to enable/disable tools, with selection persisted across session reloads and branch navigation (port of pi's example tools extension) |
