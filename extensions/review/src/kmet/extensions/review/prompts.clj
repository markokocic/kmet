(ns kmet.extensions.review.prompts
  "Review prompts — per-target prompts and the language-agnostic review
   rubric.

   Language-agnostic rubric: pi-review/review.ts adapted its rubric from
   Codex's review_prompt.md (a JS-flavored review guide). This port removes
   language-specific examples and wording so the same guidance applies to
   Clojure, Python, Rust, Go, and any other codebase the agent works in:

     - `isRecord` / `asString` examples dropped (TS idioms).
     - JSON-specific error-handling wording → language-agnostic
       parsing/decoding (covers JSON, EDN, S-expressions, protobuf, msgpack,
       custom binary formats, etc.).
     - `null` / `[]` / `false` fallback examples → generic \"silent fallback
       values\".

   Per-target prompts are still tailored (uncommitted / base branch / commit
   / folder snapshot). PR review was dropped: pi's flow needs `gh` + a clean
   work tree to `gh pr checkout`, which is environment-fragile; the
   same review can be done with `branch <remote-branch>` once a PR's
   head is fetched."
  (:require [clojure.string :as str]))

;; ─── Per-target prompts (pi: UNCOMMITTED_PROMPT / BASE_BRANCH_PROMPT_*) ───

(def uncommitted-prompt
  "Review the current code changes (staged, unstaged, and untracked files) and provide prioritized findings.")

(def base-branch-prompt-with-merge-base
  "Review the code changes against the base branch '{baseBranch}'. The merge base commit for this comparison is {mergeBaseSha}. Run `git diff {mergeBaseSha}` to inspect the changes relative to {baseBranch}. Provide prioritized, actionable findings.")

(def base-branch-prompt-fallback
  "Review the code changes against the base branch '{branch}'. Start by finding the merge diff between the current branch and {branch}'s upstream e.g. (`git merge-base HEAD \"$(git rev-parse --abbrev-ref \"{branch}@{upstream}\")\"`), then run `git diff` against that SHA to see what changes we would merge into the {branch} branch. Provide prioritized, actionable findings.")

(def commit-prompt-with-title
  "Review the code changes introduced by commit {sha} (\"{title}\"). Provide prioritized, actionable findings.")

(def commit-prompt
  "Review the code changes introduced by commit {sha}. Provide prioritized, actionable findings.")

(def folder-review-prompt
  "Review the code in the following paths: {paths}. This is a snapshot review (not a diff). Read the files directly in these paths and provide prioritized, actionable findings.")

;; ─── Token replacement (pi: buildReviewPrompt) ──────────────────────────

(defn- format-prompt
  "Apply replacements to a prompt template. REPLACEMENTS — map of
   needle (string) → replacement value (coerced to string). All
   occurrences of each needle are replaced (pi: replace(/{k}/g, ...)).
   Unknown placeholders are left as-is."
  [template replacements]
  (reduce (fn [acc [k v]]
            (str/replace acc (str k) (str v)))
          template replacements))

(defn format-uncommitted
  "The uncommitted-changes prompt — no placeholders."
  []
  uncommitted-prompt)

(defn format-base-branch
  "The base-branch prompt, choosing the merge-base form when MERGE-BASE
   is non-nil and BRANCH is present; otherwise the upstream-fallback
   form. BRANCH is the chosen base branch name; MERGE-BASE is the
   resolved SHA, or nil when git could not compute one."
  [branch merge-base]
  (if merge-base
    (format-prompt base-branch-prompt-with-merge-base
                   {"{baseBranch}" branch
                    "{mergeBaseSha}" merge-base})
    (format-prompt base-branch-prompt-fallback
                   {"{branch}" branch})))

(defn format-commit
  "The commit prompt — with TITLE when present (e.g. user typed `commit
   <sha> some title`); without when SHA is the only argument."
  [sha title]
  (if (and title (seq title))
    (format-prompt commit-prompt-with-title
                   {"{sha}" sha
                    "{title}" title})
    (format-prompt commit-prompt
                   {"{sha}" sha})))

(defn format-folder
  "The folder-snapshot prompt — paths joined by ', '."
  [paths]
  (format-prompt folder-review-prompt {"{paths}" (str/join ", " paths)}))

;; ─── Review rubric (pi: REVIEW_RUBRIC) — language-agnostic ───────────────

(def review-rubric
  "The detailed review guidelines sent before every review. Adapted from
   pi-review's REVIEW_RUBRIC (Codex review_prompt.md) and generalized to
   drop TS/JS-specific examples and JSON-specific error wording."
  "# Review Guidelines

You are acting as a code reviewer for a proposed code change made by another engineer.

Below are default guidelines for determining what to flag. These are not the final word — if you encounter more specific guidelines elsewhere (in a developer message, user message, file, or project review guidelines appended below), those override these general instructions.

## Determining what to flag

Flag issues that:
1. Meaningfully impact the accuracy, performance, security, or maintainability of the code.
2. Are discrete and actionable (not general issues or multiple combined issues).
3. Don't demand rigor inconsistent with the rest of the codebase.
4. Were introduced in the changes being reviewed (not pre-existing bugs).
5. The author would likely fix if aware of them.
6. Don't rely on unstated assumptions about the codebase or author's intent.
7. Have provable impact on other parts of the code — it is not enough to speculate that a change may disrupt another part, you must identify the parts that are provably affected.
8. Are clearly not intentional changes by the author.
9. Be particularly careful with untrusted user input and follow the specific guidelines to review.
10. Treat silent local error recovery (especially parsing/IO/network fallbacks) as high-signal review candidates unless there is explicit boundary-level justification.
11. Violate the clean-code guidelines below.
12. Introduce error handling that conflicts with the fail-fast guidelines below.

## Clean-code guidelines

1. Check whether each newly added function duplicates existing functionality elsewhere in the codebase. Flag actual duplication and identify the existing implementation.
2. Flag one-off helper functions that add indirection without improving clarity or reuse (trivial type-coercion or \"is-a-X\" wrappers that just rename an existing primitive qualify here).
3. Flag abstractions introduced without a concrete need in the reviewed change, including wrappers created only for hypothetical future use.
4. Flag defensive checks or fallback behavior that mask programming errors, especially when callers already guarantee the relevant invariants.

## Untrusted User Input

1. Be careful with open redirects, they must always be checked to only go to trusted domains (?next_page=...)
2. Always flag SQL that is not parametrized
3. In systems with user supplied URL input, http fetches always need to be protected against access to local resources (intercept DNS resolver!)
4. Escape, don't sanitize if you have the option (eg: HTML escaping)

## Comment guidelines

1. Be clear about why the issue is a problem.
2. Communicate severity appropriately - don't exaggerate.
3. Be brief - at most 1 paragraph.
4. Keep code snippets under 3 lines, wrapped in inline code or code blocks.
5. Use ```suggestion blocks ONLY for concrete replacement code (minimal lines; no commentary inside the block). Preserve the exact leading whitespace of the replaced lines.
6. Explicitly state scenarios/environments where the issue arises.
7. Use a matter-of-fact tone - helpful AI assistant, not accusatory.
8. Write for quick comprehension without close reading.
9. Avoid excessive flattery or unhelpful phrases like \"Great job...\".

## Review priorities

1. Surface critical non-blocking human callouts (migrations, dependency churn, auth/permissions, compatibility, destructive operations) at the end.
2. Prefer simple, direct solutions over wrappers or abstractions without clear value.
3. Treat back pressure handling as critical to system stability.
4. Apply system-level thinking; flag changes that increase operational risk or on-call wakeups.
5. Ensure that errors are always checked against codes or stable identifiers, never error messages.

## Fail-fast error handling (strict)

When reviewing added or modified error handling, default to fail-fast behavior.

1. Evaluate every new or changed `try/catch` (or language equivalent — `rescue`/`:try`/try-except/etc.): identify what can fail and why local handling is correct at that exact layer.
2. Prefer propagation over local recovery. If the current scope cannot fully recover while preserving correctness, rethrow (optionally with context) instead of returning fallbacks.
3. Flag catch blocks that hide failure signals (e.g. returning silent fallback values, swallowing parsing/decoding failures, logging-and-continue, or “best effort” silent recovery).
4. Structured data parsing/decoding (JSON, EDN, S-expressions, protobuf, msgpack, binary formats, …) should fail loudly by default. Quiet fallback parsing is only acceptable with an explicit compatibility requirement and clear tested behavior.
5. Boundary handlers (HTTP routes, CLI entrypoints, supervisors) may translate errors, but must not pretend success or silently degrade.
6. If a catch exists only to satisfy lint/style without real handling, treat it as a bug.
7. When uncertain, prefer crashing fast over silent degradation.

## Required human callouts (non-blocking, at the very end)

After findings/verdict, you MUST append this final section:

## Human Reviewer Callouts (Non-Blocking)

Include only applicable callouts (no yes/no lines):

- **This change adds a database migration:** <files/details>
- **This change introduces a new dependency:** <package(s)/details>
- **This change changes a dependency (or the lockfile):** <files/package(s)/details>
- **This change modifies auth/permission behavior:** <what changed and where>
- **This change introduces backwards-incompatible public schema/API/contract changes:** <what changed and where>
- **This change includes irreversible or destructive operations:** <operation and scope>
- **This change adds or removes feature flags:** <feature flags changed> (call out re-use of dormant feature flags!)
- **This change changes configuration defaults:** <config var changed>

Rules for this section:
1. These are informational callouts for the human reviewer, not fix items.
2. Do not include them in Findings unless there is an independent defect.
3. These callouts alone must not change the verdict.
4. Only include callouts that apply to the reviewed change.
5. Keep each emitted callout bold exactly as written.
6. If none apply, write \"- (none)\".

## Priority levels

Tag each finding with a priority level in the title:
- [P0] - Drop everything to fix. Blocking release/operations. Only for universal issues that do not depend on assumptions about inputs.
- [P1] - Urgent. Should be addressed in the next cycle.
- [P2] - Normal. To be fixed eventually.
- [P3] - Low. Nice to have.

## Output format

Provide your findings in a clear, structured format:
1. List each finding with its priority tag, file location, and explanation.
2. Findings must reference locations that overlap with the actual diff — don't flag pre-existing code.
3. Keep line references as short as possible (avoid ranges over 5-10 lines; pick the most suitable subrange).
4. Provide an overall verdict: \"correct\" (no blocking issues) or \"needs attention\" (has blocking issues).
5. Ignore trivial style issues unless they obscure meaning or violate documented standards.
6. Do not generate a full PR fix — only flag issues and optionally provide short suggestion blocks.
7. End with the required \"Human Reviewer Callouts (Non-Blocking)\" section and all applicable bold callouts (no yes/no).

Output all findings the author would fix if they knew about them. If there are no qualifying findings, explicitly state the code looks good. Don't stop at the first finding - list every qualifying issue. Then append the required non-blocking callouts section.")

;; ─── Branch summary / fix-findings prompts ──────────────────────────────

(def review-summary-prompt
  "We are leaving a code-review branch and returning to the main coding branch.
Create a structured handoff that can be used immediately to implement fixes.

You MUST summarize the review that happened in this branch so findings can be acted on.
Do not omit findings: include every actionable issue that was identified.

Required sections (in order):

## Review Scope
- What was reviewed (files/paths, changes, and scope)

## Verdict
- \"correct\" or \"needs attention\"

## Findings
For EACH finding, include:
- Priority tag ([P0]..[P3]) and short title
- File location (`path/to/file.ext:line`)
- Why it matters (brief)
- What should change (brief, actionable)

## Fix Queue
1. Ordered implementation checklist (highest priority first)

## Constraints & Preferences
- Any constraints or preferences mentioned during review
- Or \"(none)\"

## Human Reviewer Callouts (Non-Blocking)
Include only applicable callouts (no yes/no lines):
- **This change adds a database migration:** <files/details>
- **This change introduces a new dependency:** <package(s)/details>
- **This change changes a dependency (or the lockfile):** <files/package(s)/details>
- **This change modifies auth/permission behavior:** <what changed and where>
- **This change introduces backwards-incompatible public schema/API/contract changes:** <what changed and where>
- **This change includes irreversible or destructive operations:** <operation and scope>

If none apply, write \"- (none)\".

These are informational callouts for humans and are not fix items by themselves.

Preserve exact file paths, function names, and error messages where available.")

(def review-fix-findings-prompt
  "Use the latest review summary in this session and implement the review findings now.

Instructions:
1. Treat the summary's Findings/Fix Queue as a checklist.
2. Fix in priority order: P0, P1, then P2 (include P3 if quick and safe).
3. If a finding is invalid/already fixed/not possible right now, briefly explain why and continue.
4. Treat \"Human Reviewer Callouts (Non-Blocking)\" as informational only; do not convert them into fix tasks unless there is a separate explicit finding.
5. Follow fail-fast error handling: do not add local catch/fallback recovery unless this scope is an explicit boundary that can safely translate the failure.
6. If you add or keep a `try/catch`, explain the expected failure mode and either rethrow with context or return a boundary-safe error response.
7. Structured data parsing/decoding should fail loudly by default; avoid silent fallback parsing.
8. Run relevant tests/checks for touched code where practical.
9. End with: fixed items, deferred/skipped items (with reasons), and verification results.")

;; ─── Prompt assembly (pi: buildReviewPrompt) ────────────────────────────

(defn build-review-prompt
  "Assemble the per-target prompt body (without the rubric / custom
   instructions / project guidelines — review.clj adds those).

   TARGET — a map: one of
     {:type :uncommitted}
     {:type :base-branch :branch str :merge-base sha-or-nil}
     {:type :commit :sha str :title str-or-nil}
     {:type :folder :paths [str ...]}"
  [target]
  (case (:type target)
    :uncommitted (format-uncommitted)
    :base-branch (format-base-branch (:branch target) (:merge-base target))
    :commit (format-commit (:sha target) (:title target))
    :folder (format-folder (:paths target))
    (throw (ex-info (str "Unknown review target type: " (:type target))
                    {:target target}))))

;; ─── User-facing hints (pi: getUserFacingHint) ──────────────────────────

(def ^:private max-hint-len 40)

(defn- truncate
  "Truncate S to MAX-CHARS, appending '…' when the original exceeded the
   budget."
  [s max-chars]
  (let [s (str s)]
    (if (<= (count s) max-chars)
      s
      (str (subs s 0 (dec max-chars)) "…"))))

(defn user-facing-hint
  "A short human-readable hint for the chat flash before the review turn
   starts (pi: getUserFacingHint)."
  [target]
  (case (:type target)
    :uncommitted "current changes"
    :base-branch (str "changes against '" (:branch target) "'")
    :commit (let [short-sha (subs (:sha target) 0 (min 7 (count (:sha target))))]
              (if (:title target)
                (str "commit " short-sha ": " (:title target))
                (str "commit " short-sha)))
    :folder (let [joined (str/join ", " (:paths target))]
              (if (<= (count joined) max-hint-len)
                (str "folders: " joined)
                (str "folders: " (truncate joined max-hint-len))))
    (str "review: " (pr-str target))))
