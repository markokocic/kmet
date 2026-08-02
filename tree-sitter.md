# Bug ticket: edit tool silently prefix-matches `oldText`, then the tree-sitter guard rejects with a misleading error

## Summary

The `edit` tool's `oldText` matching is too lenient: when `oldText` is a **prefix** of the actual file content (e.g. missing trailing closing parens), the tool silently applies the partial match instead of failing with "Could not find the exact text". The leftover characters corrupt the file, and the pre-write tree-sitter guard then rejects the edit with a confusing **"stray `)` / unclosed `(`"** message that points at the *result* — making it look like the `newText` is wrong when the real bug is an inexact `oldText` match.

Observed during a cljfmt/lint cleanup session: ~30 valid-looking edits were rejected this way; each rejection required byte-verifying `oldText` externally and re-submitting (or applying via a script). The guard's error display shows the corrupted proposed line, which is actively misleading.

## Environment

- pi coding agent harness (Termux/Android), `edit` tool pre-write guard
- Files: Clojure (`.clj`), but likely any tree-sitter-supported language
- Tree-sitter grammar: clojure

## Reproduction (minimal, confirmed)

File `repro.clj`:

```clojure
(ns guard-repro
  (:require [clojure.test :as t]))

(deftest test-tool-read-with-limit
  (let [all (f)]
    (t/is (map? all))
    (t/is (contains? all "read"))
    (t/is (contains? all "bash"))))
```

Submit an edit whose `oldText` ends with **fewer** closing parens than the file actually has — e.g. `oldText` ends with `(t/is (contains? all "bash"))` (2 trailing parens) while the file line has `(t/is (contains? all "bash"))))` (4 trailing parens: `contains?`, `t/is`, `let`, `deftest`):

```clojure
oldText: "...\n    (t/is (contains? all \"bash\"))"
newText: "...\n    (t/is (contains? all \"bash\"))"   ; same text, some other change
```

**Actual result** — the tool does *not* say "could not find"; it applies the prefix match, leaving the two unmatched `))`, and rejects:

```
Syntax check failed for repro.clj: 1 error(s) detected by tree-sitter.
Delimiter balance also reports issues:
  repro.clj: 1 unclosed `(` — the one at line 4 is never closed; add 1 matching `)`
Fix and re-submit. (This is a pre-write guard — the file was NOT modified.)
  Unexpected `(ns guard-repro` at line 1:1
```

The same edit with a **byte-exact** `oldText` (all 4 trailing parens) is accepted.

A second, even more misleading variant (from the same session): the guard displays the corrupted line with *more* trailing parens than the submitted `newText` contained, e.g. `"stray ) at line 65 — (t/is (.contains content "more lines in file"))))))` where `newText` ended `))))` (4) but the display shows 6 — because the leftover prefix chars are appended after the replacement.

## Expected behavior

1. `oldText` must match the file **exactly** (the tool's own error message says: *"The old text must match exactly including all whitespace and newlines"*). If it doesn't, fail with "Could not find the exact text" — never apply a partial match.
2. If the tool intentionally supports prefix/substring matching, the guard error should make the real cause clear ("oldText matched only a prefix; N characters left unmatched"), not report a stray paren in the result.

## Root cause hypothesis

`oldText` is located via substring/prefix search rather than exact match. The "Could not find" error only fires when even a prefix match fails. Whitespace/paren-count mistakes in `oldText` therefore silently corrupt the file instead of failing loudly, and the tree-sitter guard's delimiter-balance diagnostic reports the *symptom* (unbalanced result) rather than the *cause* (inexact match).

## Impact

- Valid edits blocked with misleading errors; time lost re-verifying `oldText` byte-for-byte (in one session: 30+ rejections across ~10 files).
- The "This is a pre-write guard — the file was NOT modified" message is true, but the accompanying diagnostics push the user toward fixing the wrong thing (the `newText`).
- In a cleanup/refactor workflow, paren-count-sensitive `oldText` (nested-let merges, indentation fixes) is exactly where this bites.

## Suggested fix

- Match `oldText` exactly; if no exact match exists, report the first differing byte position instead of falling back to a prefix match.
- If prefix matching is kept for convenience, detect leftover unmatched content after the replacement and fail with a message naming it, e.g. *"oldText matched only a prefix — N trailing characters (')))') were left unmatched"*.
- Optionally, have the guard diff the submitted `newText` against the actual proposed content and surface the discrepancy.

## Workarounds

- Verify `oldText` is present verbatim in the file before submitting (e.g. `python3 -c "print(old in open(f).read())"` / `grep -F`).
- For paren-count-sensitive edits, apply via the `write` tool or an external script, then validate with the language's own parser (`bb -e '(read-string (slurp "f.clj"))'` for Clojure).
