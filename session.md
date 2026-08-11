# Session handling: gap analysis vs pi + alignment plan

Reference: `~/src/cvstree/pi/` (main @ 368e013de).
Kmet side: `src/kmet/app/session.clj` (+ consumers: `app/loop.clj`, `modes/interactive.clj`,
`app/ui/footer_data_provider.clj`).

## 0. Locked decisions

- **Native format: EDN only. No pi interoperability** — no JSONL import/export, no JSONL codec,
  no reading or writing pi session files. The on-disk format stays EDNL (EDN per line).
- **Alignment target: pi v3 `SessionManager` semantics**
  (`packages/coding-agent/src/core/session-manager.ts`). Kmet's `session.clj` is already a port
  of this layer; "alignment" means closing the semantic gaps below, in EDN.
- **v4 harness session (`packages/agent/src/harness/session/`) is out of scope** — no lanes,
  no shared sequence/mutation log, no records, no writer claims, no session search. Anything
  borrowed from v4 is a format-agnostic *technique* (e.g. torn-tail repair, atomic publish),
  not v4 architecture.
- **No migration machinery** — the header's `:version` is informational only; legacy
  headerless files load with `:header nil` (already handled by `load-session`). pi's
  v1→v2→v3 migrations are not ported and no `migrate!` is planned.

### v3 reference behavior (the target)

- **File layout**: per-cwd session directories; header line carries id/version/cwd/timestamp/
  parentSession; ISO-timestamped filenames.
- **Header line**: `{"type":"session","version":3,"id","timestamp","cwd","parentSession?"}`.
  `parentSession` records fork ancestry (a path in v3, an id in v4 — kmet uses a path).
- **Entry vocabulary** (`SessionEntry`): `message`, `model_change`, `thinking_level_change`,
  `active_tools_change`, `compaction`, `branch_summary`, `custom`, `custom_message`, `label`,
  `session_info`. Every entry: `{type, id, parentId, timestamp}`; ids are 8-hex chars,
  collision-checked against the index.
- **Append-only**: the file is never edited in place. `branch(id)`/`resetLeaf()` only move the
  in-memory leaf; the next append becomes a child there. Old branches remain in the file and
  are visible in the tree.
- **Lazy creation**: no file is written until the first assistant message (`_persist` guard).
  `fork`/`clone` refuse to run before that ("Wait for the first assistant response").
- **Compaction**: `appendCompaction(summary, firstKeptEntryId, tokensBefore, details?, fromHook?, usage?)`
  appends a `compaction` entry as a child of the current leaf; **summarized entries stay in the
  file**. `buildContextEntries` walks to the leaf, finds the latest compaction, and returns
  `[compaction, ...entries from firstKeptEntryId]` — old content is recoverable and re-forkable.
- **Branch summaries**: `branchWithSummary(fromId, summary, ...)` moves the leaf and appends a
  `branch_summary` entry (`fromId`, `summary`, `details`, `usage`) that becomes a context
  message on resume.
- **Labels**: `label` entries are real tree entries chained off the leaf; `getLabel` resolves
  latest-wins; `createBranchedSession` re-chains the path around labels and copies label entries
  with original timestamps.
- **Custom entries**: `custom` (extension state, never in LLM context) and `custom_message`
  (extension-injected, converted to a `custom`-role message in context, `display` flag controls
  TUI rendering).
- **Context build** (`buildSessionContext`): walk root→leaf, derive `thinkingLevel`/`model` from
  `thinking_level_change`/`model_change`/assistant-message provider, flatten entries through
  compaction + branch summaries + custom_message projections; `session_info` is excluded.
- **Listing**: `buildSessionInfo` streams each file (header scan bounded at 1 MB, line-by-line
  read) → `{id, cwd, name (latest session_info incl. explicit clears), parentSessionPath,
  created, modified (message activity time), messageCount, firstMessage, allMessagesText}`;
  concurrency 10, progress callback; `findMostRecentSession` reads only headers for discovery
  and filters by cwd.
- **Migrations**: v1→v2 (id/parentId tree), v2→v3 (hookMessage role rename), run on load and
  rewrite the file. *(reference only — kmet does not port these, see §0.)*
- **Robustness**: malformed lines skipped; torn-tail repair and atomic file publication are
  format-agnostic techniques worth adopting for EDN even though they come from v4.

## 1. Current kmet state

- `session.clj` — EDNL (EDN per line), **no header**. Entry shape
  `{:id :parent-id :role :content :timestamp}` with keyword roles
  `:user :assistant :tool :bash :system :session_info :info`. One `:leaf-id` atom (no lanes,
  no sequence). `ReentrantLock` serializes mutations (comment cites the sibling-orphan bug it
  prevents). Compacted summaries are `:system` entries.
- Files: `<hex-ms>-<hex-rand>.ednl` in a **flat** `~/.kmet/sessions` dir (configurable via
  `:session-dir`, no cwd encoding).
- `create-session` writes an empty file immediately; `load-session` slurps the whole file.
- `compact!` (count-based) and `compact-with-summary!` **physically rewrite** the file: they
  drop the summarized entries and re-parent the first kept entry onto the summary entry.
- `fork-session` exists (copies the branch into a `forks/` subdir, **re-ids** every entry) but
  is **unused** — `/fork` and `/clone` are registered as `command-not-implemented` stubs
  (interactive.clj:616-617).
- `/tree` exists but is **read-only** (view entry content); pi's `/tree` navigates branches.
- No `model_change` / `thinking_level_change` / `active_tools_change` / `branch_summary` /
  `custom` / `custom_message` / `label` entries anywhere.
- `restore-session-context!` rebuilds messages from the branch but drops `:session_info` and
  `:info`; the resumed model/thinking level comes from live config, not the session.
- `list-sessions` returns only paths (mtime-sorted); `resume-session` then **fully loads each
  file** to get name/first-message/count/age. No cwd filter, no progress, no concurrency cap.

## 2. Gap list

Legend: 🔴 high (correctness/UX parity), 🟡 medium, 🟢 low.

### A. Format & layout

| # | Gap | pi (v3) | kmet | Sev |
|---|-----|---------|------|-----|
| G1 | **No session header** | header with id/version/cwd/timestamp/parentSession | none | 🔴 |
| G2 | **Flat session dir** | cwd-encoded dirs; per-project isolation; `listAll` | one flat dir; `/resume` mixes all projects | 🔴 |
| G4 | **Lazy file creation** | file exists only after first assistant msg; fork/clone refuse before | empty file at create | 🟡 |

*Format itself (EDN vs JSONL) is resolved: EDN, no interop — see §0. No pi-file compatibility
is required, so a JSONL codec is never built.*

### B. Entry vocabulary

| # | Gap | pi | kmet | Sev |
|---|-----|----|------|-----|
| G6 | **model/thinking/tools change entries** | persisted; context settings derived from path | not persisted; resume loses them | 🔴 |
| G7 | **Compaction entry shape + append-only** | `compaction{summary,firstKeptEntryId,tokensBefore,details,usage,fromHook}`, old entries retained, context = latest compaction + tail | rewrites file, deletes summarized entries, single `:system` entry; no firstKeptEntryId/tokensBefore/usage; old content unrecoverable, /tree and /fork can't reach it | 🔴 |
| G8 | **branch_summary entries** | `branchWithSummary` persists summary, becomes context msg | none | 🔴 (blocked by G17) |
| G9 | **custom entries** (extension state, not LLM context) | `appendCustomEntry` | none; extensions have no durable state | 🟡 |
| G10 | **custom_message entries** (context-injecting, `display` flag) | converted to `custom`-role message in context | `:info` is display-only and **excluded** from context; semantic mismatch | 🟡 |
| G11 | **label entries + getLabel/setLabel** | label tree entries, latest-wins | none | 🟡 |
| G12 | **Entry id generation** | 8-hex collision-checked (v3) | `hex-ms-hex-rand(16-bit)` — same-ms collisions possible across processes; ids not time-ordered | 🟢 |

### C. Read/query & listing

| # | Gap | pi | kmet | Sev |
|---|-----|----|------|-----|
| G13 | **Loading robustness** | v3 streams 1 MB chunks + bounded 1 MB header scan; torn-tail repair is a format-agnostic technique | `slurp` whole file; torn tail skipped with a warning and left corrupt forever | 🟡 |
| G14 | **Query API** | `findEntries{type,order,limit,cursor}`, `findEntriesOnBranch{start,stopAtId,stopAtType}` | only `get-branch` (full path) and `get-tree` | 🟢 (optional; only if internal callers need it) |
| G15 | **Listing efficiency** | per-file streaming SessionInfo, concurrency 10, progress, cwd filter | full load per file in `resume-session`, no progress | 🟡 |
| G16 | **Stats parity** | `messageCount` = message entries only | `get-message-count` also counts `:bash` | 🟢 |

### D. Branching & navigation

| # | Gap | pi | kmet | Sev |
|---|-----|----|------|-----|
| G17 | **In-place branch/resetLeaf** | leaf pointer moves; next append branches; old branch intact | no leaf move API; `/tree` read-only; `/fork`/`/clone` are stubs | 🔴 |
| G18 | **fork/clone semantics** | `createBranchedSession`: same dir, new header w/ `parentSession`, **keeps entry ids**, re-chains around labels, copies labels; `forkFrom` cross-project | `fork-session`: `forks/` subdir, **re-ids every entry** (breaks label/extension references), no parent link, no label handling, unused | 🔴 |
| G19 | **Navigation command** | `/tree` switches branches (current-branch-first ordering, filter modes, branch_summary on jump) | view-only overlay | 🔴 |

### E. Concurrency & durability

| # | Gap | pi | kmet | Sev |
|---|-----|----|------|-----|
| G20 | **Atomic rewrites** | migration/rewrite paths are the only in-place writes; torn-tail repair + temp-file publication are the robustness techniques | `compact!`, `compact-with-summary!`, `replace-context!` `spit` in place — crash mid-write corrupts the file | 🔴 (shrinks once G7 is append-only; needed for what remains) |

### F. Command surface

| # | Gap | pi | kmet | Sev |
|---|-----|----|------|-----|
| G22 | `/session`, `/export`, `/share`, `/copy` | implemented (session info; HTML export; gist share; copy last message) | `command-not-implemented` stubs | 🟡 (depends on G1 for `/session`; `/export` is HTML-only, no JSONL) |
| G23 | **Continue-most-recent** | header-based discovery, cwd-scoped | mtime-based in flat dir, no cwd scoping | 🟡 (falls out of G1/G2) |

## 3. Dependencies between gaps

```
G1 header ──┬──▶ G23 cwd-scoped continue (with G2)
            └──▶ G22 /session info (with G2)
G2 cwd dirs ──▶ G15 listing cwd filter
G7 append-only compaction ──▶ G20 atomic publish (rewrite paths shrink)
G17 branch/resetLeaf ──▶ G8 branch_summary, G18 fork/clone, G19 /tree nav
G9/G10 custom entries ──▶ extensions SDK surface (kmet.app.extensions)
G11 labels ──▶ G18 label re-chaining on fork
```

*Deliberately absent: `/import` (no interop), JSONL export (no interop), and all v4 concepts
(lanes, records, sequence/log, search, writer claims).*

## 4. Proposed plan

**Phase 1 — Header & layout (G1, G2, G4, G23).**
- Add a header entry (first line) to `create-session`: `{:type :session :version 1 :id
  :created-at :cwd :parent-session}` in EDN. `load-session` reads it; `get-branch`/
  `get-tree` skip it.
- Cwd-encode session directories (`sessions/<--cwd-->/<timestamp>_<id>.ednl`); keep a
  `get-session-dir` escape hatch for custom dirs; update `ensure-session-dir`/`find-session`/
  `resume-session`/`list-sessions` to walk cwd dirs (listAll) and filter by cwd.
- Lazy file creation: defer writing until the first assistant entry; add the
  "wait for first assistant response before forking" guard.

**Phase 2 — Entry vocabulary (G6–G12).**
- Persist `model_change` / `thinking_level_change` / `active_tools_change` entries from the
  loop (on model switch, thinking change, tool-set change) and derive context settings from the
  path in `restore-session-context!` (pi `deriveSessionContextState`).
- **Rework compaction to append-only** (G7): keep `compaction{summary, first-kept-id,
  tokens-before, usage}` as a child of the leaf; keep summarized entries in the file; context
  build = `[compaction, ...from first-kept-id]` (port `buildContextEntries`). Keep the
  count-based fallback (`compact!`) but make it append a placeholder compaction entry instead
  of rewriting. Update `sync-context-after-compaction!` accordingly.
- Add `branch-summary`, `custom`, `custom-message`, `label` entry types + append/get APIs
  (G8–G11) mirroring `appendBranchSummary`/`appendCustomEntry`/`appendCustomMessageEntry`/
  `appendLabelChange`/`getLabel`; expose `custom`/`custom-message`/labels to the extensions SDK.
- Fix id generation (G12): collision-checked ids against the entry index (or a time-ordered
  scheme) instead of 16-bit rand.

**Phase 3 — Branching & navigation (G17, G18, G19).**
- Add `branch!`/`reset-leaf!` (leaf-pointer move, no file change) to `session.clj`.
- Rework `fork-session` to pi `createBranchedSession` semantics: same cwd dir, new header with
  `:parent-session`, **keep entry ids**, re-chain around labels, copy label entries; add
  `fork-from` (cross-project) and `clone` (fork at current leaf).
- Wire `/fork`, `/clone` (via the tree overlay: select entry → fork before/at), and make `/tree`
  navigate (branch to the selected entry; optional `branch-summary` when abandoning a path).
- Port pi's tree-selector ordering (current-branch-first) and filter modes as available.

**Phase 4 — Robustness (G13, G20).**
- Streaming/chunked load instead of `slurp` (1 MB chunks, line-oriented) with a bounded header
  scan; keep skip-with-warning for malformed lines but **detect and repair a torn tail**
  atomically (temp file + rename) — the v4 technique, applied to EDN.
- Make all rewrite paths (the compaction fallback, `replace-context!`) publish via temp file
  + rename; keep the in-process lock for append serialization.

**Phase 5 — Listing & stats (G15, G16).**
- `list-sessions` → per-file streaming `build-session-info` (name, firstMessage, messageCount,
  modified = message activity time, cwd, parentSessionPath) with a concurrency cap (~10) and a
  progress callback for the resume overlay; fix `get-message-count` parity (message entries
  only) or document the `:bash` inclusion.

**Phase 6 — Command surface (G22).**
- `/session` (info + stats from the header/branch), `/export` (HTML only — no JSONL), `/share`
  (gist), `/copy` (copy last agent message).
- No `/import` (no interop by decision).

## 5. Open questions

1. **Session dir**: adopt pi's cwd-encoded layout (changes where existing sessions live) vs
   keep the flat dir with a header carrying cwd (filtering still works)? cwd-encoding is what
   pi does; a header-first approach keeps the flat dir tolerable.
2. **Compaction rewrite**: dropping `compact!`'s physical rewrite changes `replace-context!`
   (loop.clj:954) and the overflow path — the in-memory context already rebuilds from the
   branch, so this should be contained; verify.
3. **Extensions SDK**: G9/G10/G11 imply new kmet extension APIs (session.setLabel,
   appendCustomEntry, custom_message injection). Confirm the kmet extension surface should grow
   to match pi's `ctx.sessionManager` read API + `session.setLabel`.

## 6. Testing

- Write EDN conformance tests in `test/kmet/app/session_test.clj` covering the pi v3 semantics
  we're aligning to: append-chain, branch walk, leaf move, compaction context
  (latest compaction + tail from first-kept-id), branch_summary projection, label latest-wins
  + fork re-chaining, torn-tail repair, fork id preservation.
- Add regression tests for the concurrency bug the lock guards (concurrent appends → no
  orphaned siblings), and for `build-session-info` parity with pi's `buildSessionInfo`.
