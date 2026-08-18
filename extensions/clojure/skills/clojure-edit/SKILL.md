---
name: clojure-edit
description: Guidelines for using clojure_edit and clojure_edit_replace_sexp tools. Use when editing Clojure files to ensure structure-aware editing, avoid paren errors, and follow best practices for Clojure code generation.
---

# Use Clojure Structure-Aware Editing Tools

ALWAYS use the specialized Clojure editing tools rather than generic text editing.
These tools understand Clojure syntax and prevent common errors.

## Why Use These Tools?
- Avoid exact whitespace matching problems
- Get early validation for parenthesis balance
- Eliminate retry loops from failed text edits
- Target forms by name rather than trying to match exact text

## Core Tools to Use
- `clojure_edit` — Replace entire top-level forms
- `clojure_edit_replace_sexp` — Modify expressions within top-level forms

## CODE SIZE DIRECTLY IMPACTS EDIT SUCCESS
- **SMALLER EDITS = HIGHER SUCCESS RATE**
- **LONG FUNCTIONS ALMOST ALWAYS FAIL** — Break into multiple small functions
- **NEVER ADD MULTIPLE FUNCTIONS AT ONCE** — Add one at a time
- Each additional line exponentially increases failure probability
- 5-10 line functions succeed, 20+ line functions usually fail
- Break large changes into multiple small edits

## COMMENTS ARE PROBLEMATIC
- Minimize comments in code generation
- Comments increase edit complexity and failure rate
- Use meaningful function and parameter names instead
- If comments are needed, add them in separate edits

## insert_after / insert_before Semantics
- Pass ONLY the new content — never repeat the anchor form inside `content`
- The inserted form lands OUTSIDE the anchor's own line:
  - a same-line trailing comment stays with the anchor form (`(defn a ...) ;; note` keeps its note)
  - a comment on its own line leads the NEXT form and stays with it
- The inserted form is blank-line separated from surrounding forms

## Alias-Qualified Forms
- `(t/deftest my-test ...)` matches with plain `form_type: "deftest"`
  (the qualified name `t/deftest` also works)

## match_form Must Be Complete
- `clojure_edit_replace_sexp` match_form/new_form need COMPLETE expressions with balanced parens
- Fragments like `...x]]` or `:else [w j])` are rejected — include the full enclosing form

## Handling Parenthesis Errors
- Break complex functions into smaller, focused ones
- Start with minimal code and add incrementally
- When facing persistent errors, verify in REPL first
- Count parentheses in the content you're adding
- For deep nesting, use threading macros (`->`, `->>`)

## Creating New Files
1. Start by writing only the namespace declaration using the `write` tool
2. Then add each function one at a time with `clojure_edit` using the "insert_after" operation
3. Test each function before adding the next

## Working with Defmethod
Remember to include dispatch values:
- Normal dispatch: `form_identifier: "area :rectangle"`
- Vector dispatch: `form_identifier: "convert-length [:feet :inches]"`
- Namespaced: `form_identifier: "tool-system/validate-inputs :clojure-eval"`

## When to Use Which Tool
| Use case | Tool |
|---|---|
| Replace a whole function | `clojure_edit` |
| Change one expression inside a function | `clojure_edit_replace_sexp` |
| Insert a new function before/after another | `clojure_edit` |
| Rename a symbol everywhere in a file | `clojure_edit_replace_sexp` with `replace_all` |
| Edit an ns declaration | `clojure_edit` |

**Rule of thumb:** prefer `insert_before`/`insert_after` for ADDING new forms
(no need to reproduce the anchor's text); use `replace` only to MODIFY an
existing form's body.
