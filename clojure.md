# Clojure Extension — Implementation Plan

## Overview

A kmet extension providing Clojure-aware tools, ported from [clojure-mcp](https://github.com/bhauman/clojure-mcp) and [clojure-mcp-light](https://github.com/bhauman/clojure-mcp-light).

## Architecture

```
extensions/clojure/
├── deps.edn              — rewrite-clj, cljfmt, edamame, parinfer
├── extension.edn         — {:name "clojure" :entry "src/kmet/extensions/clojure/core.clj"}
├── README.md             — usage info with examples
├── skills/
│   └── clojure-edit/
│       └── SKILL.md      — editing guidelines (port of clojure-mcp clojure_form_edit.md)
└── src/
    ├── edit_tool.clj     — ns edit-tool (= tool name clojure_edit)
    ├── sexp_tool.clj     — ns sexp-tool (= tool name clojure_edit_replace_sexp)
    └── kmet/extensions/clojure/
        └── core.clj      — ns kmet.extensions.clojure.core (entry)
```

Each extension runs in an isolated SCI context. `deps.edn` declares per-extension dependencies resolved by `borkdude.deps` in-process. The shared library layers `kmet.tui.*` and `kmet.libs.*` are injected by reference.

Tool namespaces use the tool name (e.g. `clojure_edit` → ns `edit-tool`). The entry point `kmet.extensions.clojure.core` requires tool namespaces and calls their `register!` functions.

## Completed

### clojure_edit (edit-tool.clj)

Structure-aware Clojure form editing. Full port of clojure-mcp `form_edit/{core,pipeline,tool}.clj`.

**Tool metadata** — matches clojure-mcp exactly:
- Name: `clojure_edit`
- Description with examples (replace, insert_before, insert_after, ns, defmethod)
- prompt-snippet, prompt-guidelines aligned
- JSON schema with string keys, all 5 params required
- Enum on operation: `replace`, `insert_before`, `insert_after`

**Features ported:**
- Form finding by type (defn, defmethod, def, defmacro, deftest, ns, ...) + name via BFS zipper traversal (max-depth 3)
- defmethod dispatch-value matching (`"area :rectangle"`, `"shape/area :square"`)
- defmethod dispatch extraction from replacement content when not in form_identifier
- replace / insert_before / insert_after operations
- Comment-leading replacement (special `;` handling — absorbs preceding comments)
- Delimiter repair (edamame detect + parinfer fix) on replacement content before editing
- Partial formatting (format replacement in isolation via cljfmt, re-indent to column)
- Full-file cljfmt formatting after edit
- Similar-match suggestions when form not found (namespace-qualified alternatives)
- Unified diff output via `/usr/bin/diff -u`

**Pipeline (matches clojure-mcp):**
1. Lint-repair replacement content → `[repaired, fixed?]`
2. Enhance defmethod name from replacement content
3. Load source file (UTF-8)
4. Parse source into rewrite-clj zipper
5. Find form by type + name (BFS with max-depth 3)
6. Capture form column position
7. Format replacement in isolation, re-indent to column
8. Re-edit with formatted content
9. Format whole file via cljfmt
10. Write file
11. Generate unified diff

**Not ported (kmet-specific):**
- File timestamp tracking (`file-timestamps`) — clojure-mcp uses this for multi-edit safety; kmet's edit tool doesn't have it
- Config-based cljfmt toggle (`:partial`, `true`, `false`) — kmet always uses full formatting
- Write-file-guard — not applicable without nREPL integration

### clojure_edit_replace_sexp (sexp-tool.clj)

S-expression level replacement. Full port of clojure-mcp `form_edit/{core,pipeline,tool}.clj` (sexp variant).

**Tool metadata** — matches clojure-mcp exactly:
- Name: `clojure_edit_replace_sexp`
- Description with examples (replace, rename, remove, multi-expression)
- prompt-snippet, prompt-guidelines aligned
- JSON schema with string keys, 4 params required: `file_path`, `match_form`, `new_form`, `operation`
- Enum on operation: `replace`, `insert_before`, `insert_after`

**Features ported:**
- Find s-expressions by normalized content match (ignores whitespace differences)
- `*match-clean*` fallback: when normal match fails, strips comments too
- replace / insert_before / insert_after operations
- `replace_all` — replace all occurrences in the file
- Multi-expression matching: match consecutive expressions as a sequence
- Truncation strategy: for multi-match, truncate to first expr then replace
- Delimiter repair on both match_form and new_form before editing
- Full-file cljfmt formatting after edit
- Unified diff output
- `dry_run` parameter (`"diff"` / `"new-source"`)

**Validation (matches clojure-mcp):**
- `match_form` must be parseable and contain at least one S-expression (not just comments/whitespace)
- `new_form` must be parseable Clojure code
- `replace_all` forced to false for insert_before/insert_after operations

**Not ported:**
- `whitespace_sensitive` parameter — always uses normalized matching (the common case; original doesn't use it in the sexp pipeline either)
- File timestamp tracking — kmet-specific omission
- Partial formatting — sexp pipeline doesn't use it

### clojure-edit skill (skills/clojure-edit/SKILL.md)

Editing guidelines for the Clojure tools. Port of clojure-mcp `clojure_form_edit.md` prompt.

**Content:**
- "ALWAYS use specialized Clojure editing tools"
- "SMALLER EDITS = HIGHER SUCCESS RATE"
- "NEVER ADD MULTIPLE FUNCTIONS AT ONCE"
- "COMMENTS ARE PROBLEMATIC"
- Parenthesis error handling strategies
- Creating new files workflow
- defmethod dispatch value examples
- "When to Use Which Tool" decision table

**Adapted for kmet:**
- Replaced `file_edit`/`file_write` with kmet `edit`/`write`
- Removed MCP prefix instructions
- Added tool comparison table

## Planned

### clojure_read_file

Smart Clojure file reader with collapsed view and pattern-based expansion. Port of clojure-mcp `unified_read_file/pattern_core.clj`.

**Features:**
- Collapsed view: show only form signatures (e.g., `(defn foo [x] ...)`) for large files
- Pattern matching: `name_pattern` regex to expand matching forms, `content_pattern` for content search
- Full view: when no patterns provided, return full file content
- defmethod support: match on combined `"method-name dispatch-value"` strings
- Reader conditional support: `#?(:clj ...)` forms extracted and displayed with platform tags
- Form name collection: list all top-level form names for reference

**Tool parameters:**
- `path` — file path
- `collapsed` — boolean, force collapsed view
- `name_pattern` — regex to match form names
- `content_pattern` — regex to match form content

### clj-nrepl-eval (from clojure-mcp-light)

nREPL evaluation tool. Port of clojure-mcp-light `nrepl_eval.clj`.

**Features:**
- Connect to running nREPL server
- Auto-discover ports via `.nrepl-port` file and `lsof`
- Persistent sessions per host:port
- Auto-repair delimiters before evaluation
- Environment type detection (clj, bb, shadow-cljs, basilisp)
- Timeout handling

**Tool parameters:**
- `port` — nREPL port (auto-discover if omitted)
- `code` — Clojure code to evaluate
- `timeout` — milliseconds

### clj-paren-repair (from clojure-mcp-light)

Standalone delimiter repair. Port of clojure-mcp-light `paren_repair.clj`.

**Features:**
- Fix unbalanced parentheses/brackets/braces
- Auto-format with cljfmt
- Works on files or stdin/stdout
- Statistics tracking

### Additional skills (from clojure-mcp prompts)

- `clojure_repl_form_edit.md` — REPL-driven development workflow (EXPLORE → DEVELOP → CRITIQUE → BUILD → EDIT → VERIFY)
- `create_project_summary.md` — project summary generation prompt

## Dependencies

| Library | Version | Purpose |
|---|---|---|
| rewrite-clj | 1.1.47 | Zipper-based Clojure code parsing and transformation |
| cljfmt | 0.13.1 | Clojure code formatting |
| edamame | 1.5.35 | Delimiter error detection (parser) |
| parinfer | 0.4.0 | Delimiter repair (indent-mode) |

## Alignment with clojure-mcp

### What aligns exactly
- Tool names, descriptions, prompt-snippet, prompt-guidelines
- JSON schema format (string keys, required arrays, enum)
- Core algorithms: BFS form finding, multi-sexp matching, comment handling
- Delimiter repair pipeline (edamame → parinfer)
- Formatting pipeline (cljfmt with community indent style)
- Validation logic (parseable forms, non-empty sexprs)

### Intentional differences (kmet-specific)
- No file timestamp tracking — kmet doesn't track modification timestamps
- No write-file-guard — not applicable without nREPL integration
- No config-based cljfmt toggle — always uses full formatting
- No `whitespace_sensitive` parameter — always normalized matching
- Diff via `babashka.process` shell instead of `com.github.difflib` Java lib
- File I/O via `babashka.fs` instead of `java.io.File`
- Extension isolation via SCI context instead of MCP server
