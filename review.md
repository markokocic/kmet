# Plan: Port pi-review to kmet

Port [`~/src/cvstree/pi-review`](https://github.com/earendil-works/pi-review)
(a standalone `/review` + `/end-review` extension for the pi coding agent) to
kmet as a shipped opt-in extension, with the review rubric made
**language-agnostic** (the pi original is JS-flavored).

Status: **investigation complete — implementation pending**.

## What we're porting

`pi-review/review.ts` (~1600 lines) provides:

- `/review` with modes: **uncommitted**, **base branch**, **commit**, **pull
  request** (checks out locally via `gh`), **folder(s)** (snapshot, not diff)
- Interactive preset selector with smart default (uncommitted → feature
  branch → commit), branch/commit pickers with fuzzy filter, custom shared
  review instructions (add/remove, persisted per session)
- `/review pr 123` / `pr https://github.com/o/r/pull/123`, `branch main`,
  `commit abc123`, `folder src docs`, `--extra "..."` (any mode)
- Fresh-session review: branches the session tree at the first user message,
  labels it "code-review", shows a "review active" widget, returns via
  `/end-review` with three actions: **Return only** / **Return and fix
  findings** / **Return and summarize** (structured handoff: scope, verdict,
  findings, fix queue, constraints, human callouts)
- `REVIEW_GUIDELINES.md` project instructions (found beside the project
  marker dir, `.pi` → kmet `.kmet`), appended to every review prompt
- A detailed review rubric (from Codex's review_prompt.md): what to flag,
  clean-code guidelines, untrusted-user-input, comment guidelines, review
  priorities, fail-fast error handling, human reviewer callouts, priority
  levels, output format

## Deliverables

### 1. New dir extension `extensions/review/`

Mirrors the `extensions/clojure/` manifest-dir layout (own `bb.edn` + tests):

| File | Contents |
|---|---|
| `extension.edn` | `{:name "review" :entry "src/kmet/extensions/review/review.clj"}` |
| `src/kmet/extensions/review/review.clj` | `/review` + `/end-review` commands, session state (custom entries), widget, restore on `:session-start`/`:session-tree`, PR checkout flow, fresh-session orchestration |
| `src/kmet/extensions/review/prompts.clj` | REVIEW_RUBRIC (generalized), REVIEW_SUMMARY_PROMPT, REVIEW_FIX_FINDINGS_PROMPT, per-target prompts |
| `src/kmet/extensions/review/git.clj` | `ext/exec` git/gh helpers: merge-base, local branches, recent commits, pending-changes, PR info/checkout, default branch |
| `src/kmet/extensions/review/dialogs.clj` | Selector / input dialogs built on `kmet.tui.*` + `ui-custom` (kmet has no host `ctx.ui.select`/`editor`; pi does — we compose the equivalents) |
| `test/…` + `bb.edn` | Unit tests: arg tokenize/parse, prompt assembly, git output parsing, rubric content assertions |
| `README.md` | Port of pi-review's README |

### 2. Host changes (pi-parity gaps the port needs)

Three small changes to kmet core (with tests):

1. **`kmet.app.extensions` `api-session` facades** — add `:get-branch`,
   `:get-leaf-id`, `:get-entry` (pi `SessionManager.getBranch/getLeafId/
   getEntry`). The port needs the branch entries (first user message,
   message count) and the origin leaf id.
2. **`kmet.modes.interactive` `:navigate-tree` — accept `:label`** — pi's
   `navigateTree(..., {label})` tags the review branch "code-review"; kmet's
   tree-navigation prep already carries label, the extension-context fn just
   drops it.
3. **Thread `:replace-instructions`** — pi's summary navigation replaces the
   default summary prompt with custom instructions (`replaceInstructions:
   true`); kmet always appends "Additional focus:". Thread the flag through
   `navigate-tree!` → `branch-summarize-and-apply!` →
   `generate-branch-summary` → `compaction/branch-summary-messages`. Without
   it the structured REVIEW_SUMMARY_PROMPT would be mashed into the builtin
   summary format.

## Key design decisions (kmet differences, pi-equivalent behavior)

- **Waiting on summarize navigation**: kmet's `:navigate-tree` with
  `summarize: true` is fire-and-forget (summarization runs on a future).
  `/end-review` "return + summarize" therefore waits on a one-shot
  `:session-tree` listener (delivered on `:summary-entry` or the target
  leaf) with a timeout, showing the working indicator meanwhile (pi's
  BorderedLoader).
- **`ctx.ui.editor` / `ctx.ui.select`** don't exist in kmet → SelectList /
  Input / Editor dialogs composed via `ui-custom`. kmet's SelectList already
  has builtin fuzzy filtering, so the pi branch/commit filter UIs simplify.
- **REVIEW_GUIDELINES.md** resolution: walk up from `:cwd` until a `.kmet`
  dir is found, read the file beside it (pi: `.pi`).
- **Headless**: `(:mode ctx)` check → `ui-notify` error (pi: `hasUI` guard).
- **Review state persistence**: custom entries (`review-session` /
  `review-settings` / `review-anchor`) along the active branch, restored on
  `:session-start` / `:session-tree` (pi: sessionManager entries).
- **Rubric generalization** (the explicitly requested part): remove
  JS-specific examples (`isRecord`/`asString`), JSON-specific error-handling
  wording → language-agnostic parsing/decoding, `null`/`[]`/`false` fallback
  examples → generic "silent fallback values".

## Execution order

1. Host changes (facades + navigate-tree opts + replace-instructions
   threading) with tests → `bb test-changed` / `bb lint-changed` /
   `bb format-changed`
2. `prompts.clj` (the generalized rubric)
3. `git.clj` → `dialogs.clj` → `review.clj` wiring
4. Extension tests (`bb test` inside `extensions/review/`), README,
   `extensions.md` doc updates
5. Final: `bb test-changed` + `bb lint-changed` + `bb format-changed`; full
   gates only on explicit request
