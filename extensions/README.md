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

## Building UI

Extensions build their own UI with the shared `kmet.tui.*` layer and mount
it with `ui-custom` (pi: `ctx.ui.custom`) — the api carries no host-built
dialogs. The factory receives `(tui theme keybindings close)` and returns a
component (a `defcomponent`, or a duck-typed map `{:render :handle-input
:invalidate}`); the host mounts it (overlay or editor dock), feeds it
input, and `close` dismisses it. Pattern (extensions/tools.clj,
extensions/mcp-adapter/src/extensions/mcp_adapter/panel.clj):

```clojure
(ext/ui-custom api
              (fn [tui th kb close]
                (my-component ... close))
              {:overlay true
               :overlay-options {:anchor :center :width 82}})
```

Only host-owned bridges remain api capabilities: `:custom`, `:notify`
(the flash — the TUI instance is host-owned), `:chat-info` (append an
`:info` message to the chat history — the `/session` display style: part
of the live transcript, nothing to dismiss, never sent to the LLM and
not persisted across restarts), and integrations with the
host layout/editor/status/theme-controller state. Theme lookups come from
`kmet.tui.theme` directly (`get-theme` / `get-all-themes` /
`get-theme-by-name` / `get-current-theme`). `ui-custom` forwards its opts
map untouched — `{:overlay ... :overlay-options {:anchor :center :width
82}}` matches pi's overlay placement.

## Shipped extensions

The extension authoring guide — the full `kmet.extension` API contract —
lives in [`extensions.md`](extensions.md); keep it up to date whenever the
contract changes.

| Extension | Description |
|-----------|-------------|
| `tools.clj` | Interactive `/tools` command to enable/disable tools, with selection persisted across session reloads and branch navigation (port of pi's example tools extension) |
| `deepseek-peak.clj` | `/deepseek-peak` — DeepSeek API peak/off-peak hours in your local time zone, shown as a `/session`-style chat info panel (flash fallback in headless mode) |
| `clojure/` | Clojure-aware editing tools ported from clojure-mcp: `clojure_edit`, `clojure_edit_replace_sexp`, `clojure_paren_repair`, plus the `clojure-edit` skill — see `clojure/README.md` |
| `mcp-adapter/` | MCP server access via one lazy `mcp` proxy tool: stdio + streamable-HTTP/SSE transports, EDN config (`~/.kmet/agent/mcp.edn` + `.kmet/mcp.edn`), OAuth (PKCE loopback + device flow), direct tools, `/mcp` command (port of pi-mcp-adapter — see `mcp-adapter/README.md`) |
