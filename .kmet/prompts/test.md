---
description: Run the full gate (lint + format-check + bb test + bb test-ext + jolt test + jolt test-ext) and fix failures
argument-hint: "[scope or extra instructions]"
---
Run the full validation gate for this project and fix any failures, strictly in this order:

1. `bb format-check` — if it fails, run `bb format` to fix the formatting, then re-check
2. `bb lint` — must pass with 0 errors, warnings, and info findings
3. `bb test` — the non-slow test suite on babashka
4. `bb test-ext` — the slow (^:slow) tests on babashka
5. `jolt test` — the non-slow test suite on jolt
6. `jolt test-ext` — the slow (^:slow) tests on jolt

Run all six steps sequentially, one at a time, not just the first failure. Fix any findings (lint issues, formatting drift, failing tests) and re-run the relevant step until it passes. If a later step requires fixing source code, re-run the earlier steps that could be affected before finishing. Report a summary of what was run and the results.

If extra instructions were given after /test, apply them — e.g. a narrower scope like "lint only" or a specific test namespace. The full gate is the default; this command is the explicit instruction to run it.
