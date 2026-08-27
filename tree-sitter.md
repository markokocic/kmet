# tree-sitter extension — implementation plan

Companion to [SPEC.md](SPEC.md) (validated design facts). This file is the
ordered build plan. Each phase ends runnable and testable; do not start a
phase before the previous one's *done-when* holds.

## Project shape

Manifest-dir extension mirroring `extensions/clojure/`:

```
extensions/tree-sitter/
├── SPEC.md                   # design facts (done)
├── tree-sitter.md            # this plan
├── extension.edn             # {:name "tree-sitter" :entry "src/kmet/extensions/tree_sitter/core.clj"}
├── README.md                 # end-user doc (phase 4)
├── deps.edn                  # own deps; org.babashka/http-client only extra
├── bb.edn                    # test task (runs from inside this dir — own project, like clojure/)
├── resources/kmet/extensions/tree_sitter/queries/
│   └── {clojure,python,typescript,tsx}.edn   ; tree-extraction rule sets
└── src/kmet/extensions/tree_sitter/
    ├── core.clj              # init/shutdown: wire everything, nothing else
    ├── paths.clj             # ~/.kmet/agent/tree-sitter/{bin,libs,grammars} + config.json
    ├── fetch.clj             # downloads: binary + zed tarballs, sha256 verify
    ├── cli.clj               # locate binary (Termux launcher), spawn w/ timeout, output parse
    ├── grammars.clj          # lang map (ext → lang → zed-id), scaffold dirs, load-check
    ├── validate.clj          # ERROR/MISSING collection → report strings; delimiter fallback
    ├── dispatch.clj          # file-extension → validator routing incl. clojure deference
    ├── hooks.clj             # write pre-block / edit post-warn (paren_repair semantics)
    ├── symbols.clj           # query running + capture → symbol maps (5 tool backends)
    ├── tools.clj             # register-tool! ×5, execute → {:content … :details …}
    └── renderers.clj         # call/result components per SPEC renderer tables
```

Conventions enforced repo-wide (AGENTS.md): `babashka.fs` over java.io, no
type hints, `ex-info` with `:cause`/`:type`, `defn-` for internals,
docstrings only where non-obvious, cljfmt clean (`bb format`),
clj-kondo 0-findings. Tests live in `test/…` mirroring src; **run them from
inside `extensions/tree-sitter/`** (own project) — never via root runner.
Network-dependent tests get `^:slow`.

## Phase 1 — cache layout, fetch, CLI runner

Files: `paths.clj`, `fetch.clj`, `cli.clj`, plus their tests.

1. `paths.clj`: derive `~/.kmet/agent/tree-sitter/…` via
   `fs/home` + fixed relative path (no env override in v1). Provide
   `bin-path`, `libs-dir`, `grammars-dir`, `config-path`; `ensure-dirs!`.
2. `fetch.clj`: `download+verify!` (url, dest, expected-sha256) using
   `babashka.http-client`; temp-file then atomic `fs/move`. Tarball extract
   via spawned `tar xzf` (no pure-clj tar dep; tar exists on all target
   platforms incl. Termux). Binary manifest embedded as resource EDN
   `{version {target {url sha256}}}` covering linux-x64/arm64, macos-x64/arm64,
   windows-x64/arm64; grammar manifest EDN `{lang {:zed-id … :version …
   :sha256 … :file-types […]}}`.
3. `cli.clj`: resolve invocation — plain binary, except Termux where a
   `.sh` launcher (glibc `ld-linux-aarch64.so.1 --library-path`) is emitted
   next to the binary on install (same pattern as `kmet.build`). Spawn
   helper `exec!` (generic `process!`) with arg vector, env (`TREE_SITTER_LIBDIR`,
   `TREE_SITTER_DIR` if needed), timeout → destroy-tree on expiry, returns
   `{:exit n :out s :err s}` or `{:error :timeout/:spawn-failure}` — never
   throws for infra reasons.
4. Install orchestration `ensure-binary!`: cached+verified → reuse;
   missing/corrupt → download once, emit launcher, smoke-run `--version`.

**Done when:** from a clean `$HOME` cache, calling `ensure-binary!` then
`(cli/exec! ["--version"])` prints `tree-sitter 0.26.x` on this box; corrupt
sha256 triggers re-download (validated via a one-off manual smoke — tests
never touch the network: sha-mismatch and timeout paths are unit-tested with
local fixtures).

## Phase 2 — grammars: scaffold, fetch-on-demand, load-check

Files: `grammars.clj` + tests.

1. Language table v1 (shipped as `libs_manifest.edn`): clojure (zed
   registry), python / typescript / tsx (bare wasms from the official
   tree-sitter org repos — see SPEC fact 5 caveat; tsx is its own grammar
   row, not an alias), each with source id/url, pinned version, expected
   wasm sha256, file-types list, probe snippet for the load-check.
   NOTE: this differs from the original "all from zed" assumption — the
   registry has no python/typescript entries.
2. `scaffold!` writes exactly the proven layout per lang:
   `grammars/tree-sitter-<lang>/{tree-sitter.json, src/grammar.json,
   src/parser.c(stub, mtime set old)}`, and regenerates `config.json`
   (`{"parser-directories": [<grammars-dir>]}`).
3. `ensure-grammar!`: provisions the CLI binary, then — wasm cached &
   sha-ok → ensure scaffold → done; else fetch per source (zed tarball →
   extract `grammars/<lang>.wasm`, or direct download) → verify blob sha →
   scaffold → **load-check**: parse a known-clean probe snippet through the
   CLI; delete blob+scaffold on any failure (mirror pi-tree-sitter
   corruption policy).
4. `resolve-lang` maps file extension → language (via each entry's
   file-types) or nil.

**Done when:** `(grammars/ensure-grammar! :clojure)` then a manual
`parse --wasm` of a `.clj` file prints a source tree (the exact flow
validated in ~/ts-test); unknown lang → nil; tampered wasm re-fetches.

## Phase 3 — the five tools

Files: `symbols.clj`, `tools.clj`, query resources + tests.

**Deviation from the original plan (validated):** the CLI's `query`
subcommand cannot load WASM grammars (native-dlopen only — SPEC fact 8),
so instead of `.scm` queries we ship per-language EDN rule sets
(`queries/<lang>.edn`) and walk the CLI's **default s-expr output**
(`parse --wasm`, not `-x`) in Clojure with a hand-rolled parser
(`sexp.clj`). The walker records every call's nearest enclosing def,
which is what makes find_callers/find_callees work without query-engine
support. Names are recovered from source via the trees' BYTE ranges
(positions are byte offsets, ASCII fast path). Per-process tree+result
caches (keyed by file size+mtime) make repeat project-wide queries
near-instant.

1. Rule sets for clojure / python / typescript / tsx: defs matched by node
   type (+ name-field, or leading head-symbol for clojure), calls by call
   node with field-descend for attribute/member invocations (`obj.meth` →
   `meth`).
2. `symbols.clj`: batched `parse --wasm` over files; capture walk →
   unified symbol maps `{:name :kind :line :end-line :signature}` + calls
   `{:name :line :enclosing}`; project-wide variant walks tracked files
   (git ls-files inside a repo, skip-list walk otherwise).
3. `tools.clj`: register with descriptions/guidelines steering agents from
   grep to these (rab wording spirit); each execute returns text content
   for the model **plus details map** per SPEC renderer tables
   (`{:count :label :name :file-count}` / `{:name :line-count :path :body}`).
   Errors (symbol not found, no grammar for lang) → normal error results,
   never exceptions.

**Done when:** against a fixture project, list_symbols/find_definition/
get_symbol_body/find_callers/find_callees return correct structured data
for all three languages; `bb test` green inside the dir.

## Phase 4 — renderers + docs + enablement

Files: `render.clj`, README.md, catalog row in `extensions/README.md`.
DONE.

1. Renderers implemented aligned with the lsp-adapter renderer's
   conventions (container/text/spacer building, shared path helpers,
   key-hint expand footer) rather than rab's exact token set — style
   tokens follow lsp usage (`:muted`/`:tool-output`/`:success`;
   `:dim` folded into `:muted`); expanded get_symbol_body bodies go
   through the theme markdown-theme's `:highlight-code` with the
   language derived from the file extension. Headless ANSI-stripped
   tests cover collapsed/expanded/fallback.
2. README.md written as specified.
3. Catalog row added to `extensions/README.md`.

**Remaining from done-when:** a live-session visual pass (collapsed/
expanded states on screen) — headless tests cover structure only.

## Phase 5 — validation + hooks

Files: `validate.clj`, `dispatch.clj`, `hooks.clj` + tests. DONE.

Implementation notes: dispatch routes clojure-family files to defer/
delimiter and grammar langs to tree-sitter; unknown extensions are never
validated. validate's delimiter backend is a clojure-flavored scanner; the
tree backend walks `parse --wasm` trees for ERROR nodes and zero-width
named children (missing), plus the trailing per-file stats line whose
(MISSING …) record is the ONLY signal for some recoverable errors (e.g.
python `def f(:` parses to a clean tree). Capped, with snippet + expected
token. Hooks mirror paren_repair's write-block / edit-warn shapes and
never throw — infra failures pass through. Known limitation: tolerant
recovery can still mask some mistakes entirely.

1. `validate.clj`:
   - `parse-errors` — run `parse --wasm` (sexp output), collect ERROR +
     zero-width nodes from the tree and the (MISSING …) record from the
     stats line, capped at 10 → report lines with line/col/snippet;
     MISSING includes expected-token text. Contract stays
     `{:problems [{:kind :error/:missing :line :col :snippet :expected}]}`.
   - `delimiter-balance` — comment/string-aware scanner port
     (pi-tree-sitter `delimiter.ts` logic) for languages without grammar.
2. `dispatch.clj`: extension → route:
   - clojure family ∧ clojure-extension enabled → `nil` route (never touch);
     detection: `(some #(#{…clojure tools…} (:name %)) ((:get-all-tools api)))`
     evaluated lazily per hook call.
   - clojure family otherwise → delimiter-balance only (edamame stays out
     of scope; no bundled parser).
   - known lang → tree-sitter validate; else → delimiter-balance.
3. `hooks.clj` (semantics copied from `extensions/clojure/src/paren_repair.clj`):
   - `on-tool-call`: only `write`; accept `:path`/`:file_path`;
     route via dispatch; block reason `<report>\nWrite blocked — <hint>`
     (hint names the language fix; delimiter case suggests checking
     brackets).
   - `on-tool-result`: only successful `edit`; re-read resulting file;
     append `\n\n⚠️ <report>` + hint when broken.
   - **Never-throw:** wrap everything; infra errors → log + `nil`.
     Only observed syntax problems may produce blocks/warnings.
4. Register both in `core.clj` init; unregister automatic on shutdown.

**Done when:** tests show: python write with syntax error → blocked with
line/col; python edit leaving broken file → ⚠️ appended, not blocked;
valid write passes; `.clj` write with clojure tools registered → hook
inert; simulated spawn-failure → nil pass-through. Manual smoke in a live
session on a scratch project, then repo-wide gates stay green (`bb lint` /
`bb format-check`; extension sources are covered by root gates too)
including this dir's own tests.

## Risks / decisions deferred

- ERROR-node extraction route (JSON vs sexp output): settle in phase 5
  with a spike; contract already fixed above.
- Zed registry availability: pinned versions + sha256 make failures loud;
  vendored-release fallback URLs can slot into the same manifests later.
- Query authoring quality is the long-tail cost — ship only tested
  languages; grow the table incrementally.
- Windows arm64 CLI untested locally; treat as best-effort until CI covers.
