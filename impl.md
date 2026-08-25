# TUI + App Layer Improvement Plan

Findings from a review of `kmet.tui.*` (macros, reagent, hiccup) and
`kmet.app.*` (loop, chat_history, tool_execution, tool_renderers, session,
event_bus, subs). Framing: the reactive *substrate* of re-frame already
exists here — auto-tracking reactions, cursors, batched flush, `compute`
(= `subscribe`), `with-let`, reconciliation, track! caches. What's missing
is not machinery but adoption of conventions.

## 1. Reactivity: the subs pattern exists but is ~5% adopted

`src/kmet/app/ui/subs.clj` contains exactly one subscription (`theme-sub`).
Everything else flows through constructor args + imperative setter calls.
`AgentState` (`loop.clj`) is a record of ~35 fields (~30 individual atoms);
the UI learns about changes via `on-event` callbacks that mutate components
by hand.

Move: shared state becomes *derived, not pushed* —

```clojure
(def status-sub (h/compute [(:status agent)] identity))
(def model-sub  (h/compute [(:model agent) (:provider agent)] vector))
```

Components deref these inside `track!` bodies; setters become bare
`reset!`s with zero notification code — each conversion deletes both sides
of the push wiring (emitter call AND setter). Candidates by fan-out:
`:status`, `:model`/`:provider`, thinking-hidden / tools-expanded flags.

## 2. `AgentState`: group, don't unify

A single app-db atom is wrong here: the agent loop mutates from multiple
futures at token-streaming rates — one atom means swap! retry storms.
Per-field atoms ARE the concurrency model. Middle ground:

- Config-ish fields (`max-retries`, `base-delay-ms`, `http-idle-timeout-ms`,
  `steering-mode`, `follow-up-mode`, `compact-*`, `keep-recent-tokens`)
  rarely change and never concurrently → one immutable map replaced
  wholesale. ~10 fields → 1.
- Genuinely independent cells (`signal`, `active-call`, `steering`,
  `pending-bash`, `compacting?`) stay atoms.

## 3. Accessor boilerplate: delete it ✅ DONE

`loop.clj` lines ~1698–1950 hold ~15 pure pass-through pairs
(`set-max-retries!`, `get-scoped-models`, …). Records are their own
accessors — call sites read `@(:model agent)` / write
`(reset! (:model agent) m)` directly. Keep functions only where logic
exists (`cycle-model!`, `switch-thinking-level`, `set-model!` with
resolution). Note: `defsetter`/`defgetter` live in `kmet.tui.macros`,
which `kmet.app.*` may not import (layer boundary) — direct keyword access
is the idiomatic cure anyway.

Also fixed: `make-agent-state` docstring listed `:should-stop-after-turn`
twice.

## 4. Dispatch: data tables beat multimethods here ✅ DONE

Zero `defmulti` in src. Three dispatch sites:

| Site | Was | Now |
|---|---|---|
| `tool_execution.clj` builtin renderers | hardcoded `case name` | one registry map `{name {:call f :result g :shell s}}`; custom/extension overrides win |
| `chat_history.clj` expand-toggle fan-out | `case (kind-of child)` | method table — later DELETED (see item 3): children read the shared toggle atom directly, no per-child push remains; `pad-fns` table survives for the output-pad walk |
| `event_bus.clj` | listeners map keyed by event type | unchanged — already an open dispatch |

Why not `defmethod`: extensions register at *runtime* through atoms/calls;
`defmethod` is a compile-time global registry — it would fork the extension
story into two mechanisms. A plain map keeps explicit-data style, stays
greppable, and unifies builtin + custom + extension renderers into one path.

Adjacent cleanup decision: the registry keeps **string keys** matching the
tool-name domain as-is — keywordizing at lookup adds a transformation per
construction with no downstream consumer of the keyword form. Revisit if
another dispatch site ever shares the keyword.

## 5. Declarative construction: use the Hiccup DSL ✅ DONE (75ea264)

`tool_renderers.clj` builds trees imperatively (`make-box` → repeated
`container-add-child`). `compile-element` passes foreign records through,
so hybrid trees work today: static scaffolding as element vectors, dynamic
leaves as records. Renderers become data testable headlessly via
`render-lines`. Biggest line-count reducer in `app/ui`.

## 6. `run-agent-turn`: decompose into phases ✅ DONE (c0da194)

~290 lines: nested future → try → inline closures. Extract phases
returning values — `prepare-run`, `llm-turn`, `execute-tools`,
`drain-queues?` — so the outer loop reduces over turn state. Each phase
unit-testable without a live LLM.

## 7. Deliberately NOT doing

- **Full re-frame port**: event bus + reactions cover the useful 20%;
  importing vocabulary without need just renames things.
- **One big app-db atom** (see §2).
- **Protocols for kind-dispatch**: `case` on `:kind` / method tables give
  the same openness with less ceremony than a namespace-wide protocol.
- **Splitting `tui/core.clj` (2203 lines)** for its own sake.

## Order (value ÷ risk)

1. ✅ Delete passthrough accessors; docstring dup fix — 18 pure pass-through
   defs removed from `loop.clj` (getters + setters whose body was a bare
   `reset!`/deref); coercion-carrying setters kept (`set-scoped-models!`,
   `set-auto-compact!`, `set-http-idle-timeout-ms!`, `set-active-tools!`);
   all call sites across `interactive.clj`, ui selectors, and tests now read
  /write fields directly. *(trivial)*
2. ✅ Renderer registry map replacing the `case`s; kind method tables —
   `builtin-renderers` table in `tool_execution.clj` (:call/:result/:shell),
   `expand-fns` + `pad-fns` tables in `chat_history.clj`. *(small)*
   (expand-fns subsequently deleted by item 3's shared-toggle conversion;
   pad-fns remains.)
3. ✅ Extend subs to shared state; push-setters → bare resets *(medium)*

   **Status-slice finding (commit d7d0c89):** `:status` turned out to have
   ZERO render-input consumers — all ten readers are event-handler guards
   ("may I start a run?"), which stay direct field reads. The real push-web
   was around the FOOTER: fdp atoms were deref'd inside getter fns,
   invisible to the track! lexical rewrite, so `update-footer!` (on every
   :status event) and `sync-footer-model!` invalidated by hand. Fixed with
   track-deps over the fdp atoms + the live session :entries vector (which
   Session mutates in place); both manual invalidations deleted, regression
   test added.

   **Flags slice (commit 931af52):** tools-expanded converted — tool/bash
   components OR the shared chat toggle atom into their render (lexical
   deref inside track!); toggle is one swap!, inheritance automatic,
   `expand-fns` push table deleted. thinking-hidden already used the
   shared-atom pattern. Item 3 complete; model/provider computes deferred
   until a second consumer appears.
4. ✅ Config-field grouping in `AgentState` *(commit ed567fa)* — seven
   runtime knobs (`max-retries`, `base-delay-ms`, `http-idle-timeout-ms`,
   `steering-mode`, `follow-up-mode`, `auto-compact`, `context-window`)
   collapsed into one `:cfg` atom holding an immutable map; reads
  `(:k @(:cfg ag))`, writers swap!-assoc. Concurrent cells + plain ctor
   ints untouched; make-agent-state opts API unchanged.
5. ✅ Hiccup renderer subtrees *(commit 75ea264)* — read/write/default
   call+result + build-edit-box compile element trees (tool-text leaf,
   :container/:box/:spacer) through hiccup/compile-tree; −70 lines, new
   headless test-tool-renderers. Bash pair + render-edit-result stay
   imperative (per-component state/intervals). Leak-safety argument:
   converted leaves are precomputed-string-only, no reactive derefs.
6. ✅ `run-agent-turn` phase extraction *(commit c0da194)* — normalize-llm-result
   + retry-decision extracted as PURE fns (unit-tested headlessly),
   prepare-run!/timeout-abort!/terminal-error!/tools-phase!/final-phase!
   as named side-effecting phases; emission-site multiset proven identical,
   after-turn unconditional-hook invariant preserved (self-review caught a
   short-circuit that would have skipped hooks on terminate).
7. ✅ Extract reactive core to **`kmet.libs.reakt`** *(commit 4d9c355)* —
   reactions/cursors/batching/tracked-deref now standalone (tui.reagent
   deleted, generic half of tui.macros merged in); self-contained guard
   enforces purity. New `reakt/derive` = pure core of hiccup/compute
   (which keeps store-disposal + counters as a thin wrapper). with-let
   stays in tui.macros: component-model machinery, zero reaction coupling,
   no second consumer yet — promote when one appears.
8. ✅ Delete defsetter/defgetter *(same commit)* — defgetter had 1 caller;
   defsetter generated the pass-through accessors removed in item 1 and
   couldn't carry docstrings (set-content!'s timing contract moved to
   mark-execution-started!, the only legitimate stamper). Pure pass-
   throughs inlined; logic-bearing setters became plain documented defns.

## Validation (items 1–2)

- `bb lint-changed` — 0 errors, 0 warnings
- `bb test-changed` — 402 tests, 1625 assertions, all passing
- `bb format-check-changed` — clean
- Net: −95 lines across 11 files

## Final verification at HEAD (post item 6)

All four tiers exercised after the last structural change:

- Full fast suite — **1772 tests / 11,682 assertions passing**
- Extension project lens (`extensions/clojure`, consumes ../../src) —
  144 tests / 396 assertions
- Slow real-timing suite (`bb test-ext`: subprocesses, git, terminal
  queries) — 60 tests / 291 assertions
- `bb lint-changed` full-lint escalation (hooks/config change) — 0/0

Review ledger across the session: five defects caught post-implementation
by adversarial passes (double-parens from scripted edits, a zombie twin
atom, mangled paren-repair on legacy formatting, an or-short-circuit that
would have skipped unconditional after-turn hooks, an emit payload re-read
instead of decision snapshot) — none escaped into a settled commit.
