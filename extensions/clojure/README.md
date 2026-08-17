# Clojure Extension

Clojure-aware tools for kmet, ported from [clojure-mcp](https://github.com/bhauman/clojure-mcp).

## Tools

### `clojure_edit`

Structure-aware form editing. Finds Clojure definitions by type and name, then replaces or inserts around them.

```
clojure_edit:
  file_path: "src/my_app/core.clj"
  form_type: "defn"
  form_identifier: "my-function"
  content: "(defn my-function [x y] (+ x y))"
  operation: "replace"
```

**Parameters:**
- `file_path` — path to .clj/.cljs/.cljc/.bb/.edn file
- `form_type` — `defn`, `defmethod`, `def`, `defmacro`, `deftest`, `ns`, `s/def`, etc.
- `form_identifier` — form name; for defmethod use `"method-name dispatch-value"`
- `content` — replacement Clojure source code
- `operation` — `replace` (default), `insert_before`, `insert_after`

**Features:**
- defmethod dispatch-value matching (`"shape/area :square"`)
- Delimiter auto-repair (edamame + parinfer)
- cljfmt formatting
- Similar-match suggestions when form not found

### `clojure_edit_replace_sexp`

S-expression replacement. Changes a specific expression without touching surrounding code.

```
clojure_edit_replace_sexp:
  file_path: "src/my_app/core.clj"
  match_form: "(+ x 2)"
  new_form: "(+ x 10)"
  operation: "replace"
```

**Parameters:**
- `file_path` — path to file
- `match_form` — s-expression(s) to find
- `new_form` — replacement s-expression(s)
- `replace_all` — replace all occurrences (default false)
- `operation` — `replace`, `insert_before`, `insert_after` (required)

**Features:**
- Whitespace-normalized matching (ignores formatting differences)
- Multi-expression matching (consecutive expressions)
- `replace_all` for renaming symbols across a file

## Skill

### `clojure-edit`

Editing guidelines pulled on demand when working with Clojure files. Covers:
- Why use structure-aware tools
- "Smaller edits = higher success rate"
- Parenthesis error handling
- Creating new files workflow
- defmethod dispatch value examples
- When to use which tool

## When to use which

| Use case | Tool |
|---|---|
| Replace a whole function | `clojure_edit` |
| Change one expression inside a function | `clojure_edit_replace_sexp` |
| Insert a new function before/after another | `clojure_edit` |
| Rename a symbol everywhere in a file | `clojure_edit_replace_sexp` with `replace_all` |
| Edit an ns declaration | `clojure_edit` |

## Dependencies

Declared in `deps.edn`, resolved per-extension in isolated SCI context:

- rewrite-clj 1.1.47 — zipper-based Clojure parsing
- cljfmt 0.13.1 — code formatting
- edamame 1.5.35 — delimiter error detection
- parinfer 0.4.0 — delimiter repair

## Skills

- `clojure-edit` — editing guidelines (when to use which tool, paren handling, small edits)
- Ported from clojure-mcp's `clojure_form_edit.md` system prompt
