# kmet TUI DSL — Reagent-style component layer (proposal)

Status: design + implementation record. The migration plan (§7) is being
worked top-down; each section describes the target design, with deviations
that shipped noted inline. Implemented so far:

- **Stage 1 — `kmet.tui.reagent`**: done. Full ratom port (`make-reaction`,
  `track`, `cursor`, batch queue drained from the render-loop tick,
  `watch-ref`/`unwatch-ref`, sticky errors, callback scheduling), plain
  atoms as tracked inputs via the `tracked-deref` capture point.
- **Stage 2 — construction layer + protocol cleanup**: done. Tag table,
  compile/validation, `hiccup/root` (batched mode), headless
  `render-lines`, `dispose` on IComponent, `IComponentKind` retired,
  redundant no-op bodies deleted.
- **Stage 3 — reconcile + ComponentFn**: done. Keyed `reconcile!` diff
  (one mechanism for wrapper children AND host-container fill — the
  append-only stage-2 path is gone), `ComponentFn` wrapper running bodies
  inside reactions (auto-discovered deps, memoized: unchanged deps skip
  the body entirely), fn heads valid everywhere, `:ref` pseudo-prop with
  the `(hiccup/ref)` wrapper type, `with-let` macro over the store
  primitives, and the §3.4 scheduler hook installed beside the flush
  (default no-op; call-site retirement stays Stage 5).
- **Stage 4–5**: item 10 (status container) done — first app mount;
  dock/widget areas and dialogs pending; `compute` and mirror-plumbing
  removal are pending.

---

## 1. The reframe

kmet already has the reactive core of Reagent:

- `track!` is a hand-rolled reaction system: render bodies deref atoms
  through `tracked-deref`, watches invalidate the cache on change, equal-value
  resets are no-ops, mid-render invalidation is detected and not cached.
- The frame loop diffs `previous-lines` against new renders — the diff step,
  done at the right level (final output lines, which is what a byte-stream
  terminal consumes).

What's missing is the *composition* layer. The key insight:

> **kmet's records are the terminal's "DOM elements". Function components
> sit *above* them, exactly like React components sit above `[:div ...]`.**

So the architecture is:

- **Host elements** (records): Text, Box, VStack, Container, Spacer,
  ScrollView, SelectList, Editor, message components — unchanged, they are
  the terminal's primitives.
- **Components** (functions): `(fn [props] tree)` — the composition layer,
  new.
- **Reconciler** (hiccup ns): compiles trees to records, reconciles by key,
  re-applies props.
- **Root** (`hiccup/root`): the one constructor from a tree to a mounted,
  disposable record — how trees enter the TUI (§2.6).
- **Reactivity**: `track!` on host elements (already exists); fn
  components run inside reactions once `kmet.tui.reagent` lands (§2.8,
  Stage 1 of the plan) — Reagent's own model: deps auto-discovered at
  deref time, batched at the frame flush. Until then fn components are
  frame-batched (§3.4).
- **Lifecycle**: `dispose` — new method on IComponent (no new protocol;
  §5). `IComponentKind` retires — kind becomes a stamped record field.
- **Scheduling**: invalidation schedules the next frame (§3.4) — new;
  without it, pure data updates never reach the screen.

The flow, end to end (post-Stage 1):

```
ratom/atom change → reaction dirty → queued → frame flush runs it →
          = -changed? notify → invalidate + schedule frame → component
          bodies re-run inside reactions → tree → compile+reconcile
          (keyed, per level) → record tree → lines (each record caches)
          → frame diff → terminal
```

---

## 2. Core primitives

### 2.1 DSL syntax — Hiccup vectors

Trees are plain data plus whatever code you want, evaluated once per
re-render:

```clojure
[:box {:padding 1}
 [:text "hi"]
 (when-let [s @status] [:status-line s])   ;; nil → skipped
 existing-component                        ;; records pass through
 ;; seqs get spliced — always key spliced children, or prepending a
 ;; message rebuilds every unkeyed sibling after it:
 (map #(vector :text {:key (:id %) :text (:content %)}) msgs)]
```

A tree becomes live only through `hiccup/root` (§2.6) — there is no other
public entry point.

Children rules:

| child | result |
|---|---|
| `nil` | skipped — this is the `when`/`when-let`/`if` support, free |
| string | compiled to a Text (the tag's `:primary` shorthand, §2.2) |
| record | passed through as-is (identity preserved) |
| seq | spliced (each element treated as a child) |
| stack-entry map (VStack) | passed through as-is |

Tags are keyword (tag table, §2.2) or a fn head: `[status-area
{:mode :normal}]` is valid Reagent-style usage. Two more normalizer
rules keep call sites terse:

- **Props map optional** — `[:v-stack child…]` compiles with `{}`;
  the map slot is needed only when props exist (`:key`, `:ref`, options).
- **A fn component may return a seq of roots** — spliced where the
  element sits (the fragment/`:<>` equivalent), for wrappers that must
  not introduce a Box/VStack node into layout.

### 2.2 Tag table — no registry

Host elements are a **closed set** — `hiccup.clj` hardcodes the tag → ctor
table (no `register!`, no registry atom). Custom composition uses fn
heads `[my-fn props]`; extensions never add host elements (they go
through the ui API). With the props/state split, the ctor is the only
per-component bit:

```clojure
(def tags
  {:text    {:ctor map->Text :primary :text :container? false
             :defaults {:padding-x 1 :padding-y 1}}
   :box     {:ctor map->Box :container? true
             :defaults {:padding-x 1 :padding-y 1}}
   :v-stack {:ctor map->VStack :container? true}})
```

`:primary` names the positional-shorthand target: `[:text "hi"]` compiles
to props `{:text "hi"}` merged over `:defaults` — the bare string child
becomes that one prop.

Fn heads call `(f props)` — or `(f props children)` when the element
carries children: the raw child tree, embedded by the fn wherever it
returns them (Reagent's `props.children`). Either way the result is
wrapped in the ComponentFn record (§2.4).

Validation fails loudly — under the v1 error contract a silent fallback
would hide typos until they misrender. An unknown tag throws naming the
offending element, listing the known tags, and suggesting the nearest
match (`:tst` → did-you-mean `:text`). Fn heads are fn **values**, never
symbols: extensions build trees at runtime (SCI), and symbol resolution
would couple the DSL to caller namespaces. Children on a leaf tag throw
the same way.

Compile is generic from the spec: merge props + defaults, wrap into
`:props`/`:state`/`:cache` atoms, apply `:ctor`. **One children
mechanism**: containers are constructed empty; filling them is
`reconcile-children!`'s job (`hiccup.clj`'s one generic fn, §5) — compile never
duplicates that via a `:children?`/`:children-key` spec. No field
mapping, no `:structural`.

**Every prop re-applies, and every prop is live**: reconcile resets the
component's `:props` atom wholesale — `(reset! (:props c) props)` — there
is no reactive/structural distinction and no seed-once carve-out (§4).
A parent passing different padding next frame changes the padding;
equality no-ops keep unchanged props free.

### 2.3 Compile + reconcile — `reconcile!`

The wrapper's `reconcile!` is React's render pass, recursive and keyed —
**one reconciler per component, not global**:

```clojure
(defn reconcile! [children-atom tree]
  (let [prev @children-atom
        desired (mapv compile-element (normalize tree))]
    ;; for each desired element:
    ;;   :key match   → reuse record, reset! its :props atom,
    ;;                  fill its :ref atom
    ;;   no match     → construct (fn tags → ComponentFn wrapper),
    ;;                  fill :ref; drop unmatched prev → dispose + clear :ref
    ;;   (:key/:ref pseudo-props stripped before compile)
    (reset! children-atom new-children)))
```

Matching: `:key` prop (pseudo-prop, stripped before compile) wins;
fallback is tag + position. Reorder by key = reuse (like React).

**Duplicate `:key`s throw at reconcile.** Two spliced siblings sharing a
key makes reuse undefined — which one wins depends on diff order, and
the loser's subtree silently vanishes or the wrong record gets reused.
Throwing beats both.

**`:ref` — the imperative escape hatch.** Focus, editor text access, and
scrolling are imperative calls on concrete records today
(`tui-set-focus`, editor getters, `IScrollView` methods, custom-editor
swapping). A tree that declares such an element must still reach its
instantiated record, so the DSL copies React's answer as a second
pseudo-prop beside `:key`:

```clojure
(def editor-ref (hiccup/ref))

[:editor-container {:ref editor-ref}]

;; elsewhere — an event handler or effect, never a render body:
(tui/tui-set-focus t @editor-ref)
```

Reconcile owns the ref: construct fills it with the record, dispose
clears it. Like `:key`, `:ref` is stripped before compile — host
elements never see it. Rules:

- refs are created with `(hiccup/ref)`, which returns a tagged wrapper
  type (derefable like an atom) that only reconcile can write — a user
  `reset!` throws;
- deref it only outside render bodies (handlers, `with-let` init,
  effects): it is nil until the first reconcile constructs the element,
  and a render-body deref would race construction while adding a
  meaningless dependency;
- one ref atom per element instance — sharing one across two elements
  means last-mount wins.

This is what unblocks Stage 4's dialog conversion: `hide-dialog` keeps its imperative
focus call, but the dialog tree declares the editor with a ref instead
of the app pre-building and stashing the record.

### 2.4 The ComponentFn wrapper

```clojure
(defcomponent ComponentFn nil [f props state children cleanups cache-atom]
  (render [this width]
    (binding [*comp* this *width* width]
      ;; body runs inside a reagent/reaction (§2.8 Stage 1): deps
      ;; auto-discovered at deref time, re-runs queued to the frame
      ;; flush, = -gated notification. Batched fallback for opt-outs:
      ;; skip the reaction, re-derive every pass.
      (r/run-in-reaction*
        #(do (reconcile! children (f @props))
             (render-children @children width))
        this)))
  (dispose [this]
    ;; children first — their cleanups still see intact parent state;
    ;; then own cleanups, then drop state
    (doseq [c @children] (dispose c))
    (doseq [f @cleanups] (f))
    (reset! state {})))
```

Properties:

- **One path, Reagent semantics**: the wrapper runs the body inside a
  reaction — deps auto-discovered at deref time, re-runs queued to the
  frame flush, notification only on `=` change. Before Stage 1 lands
  (or via opt-out), the same body runs batched: re-derived every pass,
  reconcile dedupes children, leaf caches absorb rendering. Both paths
  share one correctness story because the fallback is *uncached*, so
  untracked reads can't go stale.
- **No deref-rewriting macro**: reactions capture at deref time, so
  tracking doesn't depend on compile-time rewriting of the body. Plain
  atoms participate through the shared capture point (`tracked-deref`,
  §2.8-bb): component render bodies already route their `@reads`
  through it, so they are reactive with zero migration; hand-written
  reaction bodies read atoms via `tracked-deref` explicitly.
- **Props re-applied on reuse** via `reset!`; equal-value resets no-op →
  memoized children for free (the `@props` deref inside the reaction
  scope makes prop changes invalidate correctly).
- **Reconciliation is bounded**: per component's direct children, not the
  whole app tree. The transcript stays a record (never a fn component),
  so the rejected per-frame whole-tree rebuild never happens.
- **Error contract (v1)**: a throwing component fn crashes the render
  loop — the existing policy (Throwable → render-crash.log → tui-stop).
  Fn bodies run arbitrary app code every frame, so this is likelier than
  with records alone; accepted for v1. If it bites: isolate per-child
  render+reconcile in a catch and render a placeholder
  (error-boundary-lite) — deferred until a real case exists.
- **Cached means allowlisted correctly**: batched ComponentFn joins the
  uncached-allowlist in test-caching-conventions (transparent-parent
  category) — that test scans src/kmet/tui/components + src/kmet/app/ui
  and fails otherwise.
- **Dynamic `*width*`**: fn bodies receive only props, but status bars
  and dialog headers genuinely need the current pass width (truncation).
  The wrapper binds `*width*` beside `*comp*` — read-only, per-pass,
  same dynamic-scope cost as `*comp*`. Width participates in
  memoization: since it shapes output without being a tracked dep, a
  pass at a NEW width forces one body re-derive of an idle reaction
  (`reagent/invalidate!`, then re-cache) — track!'s per-width cache
  contract, one level up. Without this, resize leaves cached trees
  stale in any body that both derefs real state and reads `*width*`.
- **Dispose order is contractual**: children first, then own cleanups —
  a child cleanup may still read intact parent state (React effect
  semantics). Not incidental; tests rely on it.
- **Observability**: behind `--debug` the wrapper counts per-frame fn
  invocations, reconciles, and cache hits. A fn invoked on frames where
  nothing it derefs changed is the inline-callback trap (§2.5's
  inline-callbacks bullet) or a broken equality — invisible in output
  bytes, obvious in counters.
- **`defc` dropped from the plan** (superseded by Stage 1): it existed
  to deliver dependency tracking through compile-time deref rewriting —
  a workaround for not owning the atom type. The `kmet.tui.reagent`
  port removes that premise twice over: reactions discover deps at
  deref time, and the §2.8-bb pivot makes existing plain atoms the
  tracked inputs through the shared capture point — no constructor
  swap ever. Fn components are Form-1-reactive with no macro; reads
  outside the tracking contract are covered explicitly via
  `compute`/`cursor` slices. If a hot spot ever needs input-side
  caching without reactions, that's a different, smaller tool.

**The three forms — Reagent's taxonomy, mapped.** Reagent has exactly
three ways to author a component; this design covers all three with the
same surface, minus Form-2's footguns:

| Reagent | Here | Notes |
|---|---|---|
| **Form-1**: pure fn `(defn c [] [:div …])` | `(defn status-area [props] tree)` | Identical ergonomics; batched scheduling |
| **Form-2** (reactivity): reaction-wrapped Form-1 | every ComponentFn body runs inside a `reagent/reaction` (Stage 1 port, §2.8) | Same semantics as Reagent: deps discovered at deref time. No inner-fn idiom → the accidental-inner-fn bug can't exist; `with-let` covers its state half |
| **Form-3**: `create-class {…}` | `defcomponent` record | Equivalent method map: `render` ↔ `render`, `dispose` ↔ `componentWillUnmount`, `handle-input` is terminal-only (no DOM analog); `did-mount` deferred until a second reconcile-created side effect appears (§5) |
| — | raw records spliced into trees (`existing-component`, §2.1) | A fourth form Reagent lacks — dropping a live instance into Hiccup; the migration/adapter path, load-bearing for Stage 2 |

Other Reagent mechanisms land as follows: local state → closure/`with-let`
in Form-2 becomes `with-let`/`let-state`; lifecycle methods →
defcomponent methods; `shouldComponentUpdate` → no user-facing knob,
absorbed by equality no-ops + leaf caches; render props / children
composition → `(f props children)` (§2.2); HOCs → not needed.

**The honest divergence — scheduling granularity (plain fns).** Reagent
is per-component reactive: every Form-1 is wrapped in its own reaction,
so it re-runs only when atoms *it* derefs change, independent of
everything else. Here, *plain* fn heads are frame-batched: their derefs
untracked — any invalidation anywhere schedules a frame (§3.4), and
each frame re-derives the tree from the roots downward; reconcile
dedupes children and leaf caches + line diff absorb the cost. After
Stage 1 the gap closes entirely: bodies run inside reactions with
auto-discovered deps, so a body whose deps didn't change isn't even
queued. The idle-UI invariant (zero fn bodies when nothing changed)
holds in both eras. Pre-Stage-1 rule for plain fns: don't reach for
derefs to "optimize" re-render scope; subscriptions are the tool
(§3.1).

**Why not port reactions from the start / why Stages at all?** The
port (§2.8) is the destination; the staging exists so the app never
breaks: pure additions first, conversions leaf-first and one-commit
revertible, scheduler gated behind a working subscription story. The
old blocker paragraph ("extensions can't use ratoms", "macro needed")
dissolved on inspection: extensions are internal, the macro was only
ever a delivery mechanism for tracking, and the §2.8-bb capture point
makes existing plain atoms first-class reactive inputs with no
migration at all.

**Granularity guidance (the monolith trap).** A tempting shortcut:
"make the whole main screen one big component reading all app state" —
covered and correct, since any change re-derives it. Three reasons not
to:

1. **Coarsest legal granularity**: one reaction means *any* change
   re-runs the *entire* body — during streaming, that's a full-screen
   re-derivation per frame. Same CPU profile as the batched default,
   none of the reactivity benefit. The win comes from many small
   components
   reacting narrowly (screen shell → region panels → leaves).
2. **Tracked reads of large collections cost an equality check per
   frame** — the cache hit-check compares each tracked value; untouched
   atoms short-circuit on `identical?` (O(1) — the derefed object *is*
   the recorded one), so only atoms actually written in between pay a
   structural `=` walk. Derefing `messages-atom` in a body means that
   walk goes deep during streaming (the tail changed). Narrow reads or
   computes sliced near the consumer avoid it. Reagent pays an analogous
   cost on the *output* side — its reaction re-runs the body first, then
   `=`-compares whole hiccup trees; input-side comparison skips the body
   entirely and also makes equal-value writes free at the watch.
3. **The transcript stays out regardless** (§3.3): mapping messages
   into elements inside a screen body is the rejected per-token whole-
   tree rebuild, now at frame rate. ChatHistory remains records;
   screens reference it as a splice/tag.

Stage 4's shape follows: a root shell + region-level components
(dock/status/dialogs) + transcript as records.

### 2.5 Lifecycle — `dispose`, not `with-let`-as-macro

`IComponent` gains a `dispose` method, default no-op (synthesized by
`defcomponent`; no new protocol — §5). Containers, overlays, and the
TUI call it when a child is removed. Component-local state is already
record fields; `dispose` is the missing cleanup half.

For fn components that need transient state, primitives (SCI-friendly,
plain fns — living in `kmet.tui.macros` beside `track-render`/
`invalidate-cache`, so macro and runtime companions share one home,
same pattern as `track!`). Shipped as the generation-keyed STORE rather
than `*comp*` readers — the ComponentFn binds one store per instance
around the body; the raw fns are `fetch-local`/`register-cleanup!`
(throw loudly outside a store binding), and `with-let` is sugar over
them:

```clojure
(defmacro with-let [bindings & body]
  ;; each binding becomes (fetch-local '<per-site gensym> (fn [] init));
  ;; a top-level (finally ...) becomes a once-per-site register-cleanup!
  ;; (the guard stops per-frame re-registration). Cleanups run LIFO in
  ;; destroy-store!, which ComponentFn.dispose calls after children.
  ...)
```

The guard matters: the body re-runs only when deps change now (Stage 3
reactions), but registration must still be once-per-instance — wrapping
it in the site-keyed guard keeps that true regardless of scheduling.

Footguns (documented):

- **Fn bodies must be pure per pass** — they re-run every render pass
  (the wrapper is uncached). Creation-time side effects (timers) go in
  `with-let` init (runs once), cleanup in `on-dispose!`.
- **Subscriptions are created once** (shared registry, or `compute`
  under `with-let`), never bare in the render body — or the derived
  atom leaks per re-render.
- **let-state/on-dispose! are render-pass-only** — they read the dynamic
  store bound by the wrapper (`*store*`, via fetch-local/register-cleanup!).
  Calling them from an async callback throws loudly (no store bound) — the
  flip side of having no hooks rules: the constraint exists but is one
  line of doc, not a lint regime.
- **State keys are per-component** — raw `let-state` takes explicit keys;
  don't reuse a key within one component. `with-let` is immune: its keys
  are per-expansion-site gensyms, so sibling bindings can't collide.
- **A top-level `(try … (finally …))` in the body is stolen** by the
  cleanup extractor — same footgun as Reagent's `with-let`. Nest the
  `try` inside a `let` when you need both.
- **Inline callbacks defeat props memoization** — a props map rebuilt
  each frame containing `(fn […] …)` literals is never `=` across
  passes, so every tracked deref of `@props` invalidates the leaf's
  cache every frame (a Markdown re-parsing per frame). Hoist handlers
  to named `defn`s or pass atoms/stable values. Line diffing hides this
  in the output bytes but not in CPU — the `--debug` per-frame counters
  (§2.4) surface it.

---

### 2.6 Mounting — `hiccup/root`

Trees enter the TUI through one constructor; there is no second path:

```clojure
(hiccup/root f-or-element)
```

Returns a record implementing IComponent: first render
compiles/reconciles the tree, later renders re-reconcile like any
ComponentFn. A bare fn is shorthand for `[f {}]`; a vector compiles as
an element. The owner mounts it anywhere a record is expected today —
`tui-add-child`, `container-add-child`, `tui-show-overlay` — and calls
`dispose` when it leaves (overlay close, suspend/shutdown). Ownership
follows the container it was handed to; nothing else retains it.

This is the missing endpoint for Stage 4: interactive.clj keeps every
existing mount point and swaps pre-built records for roots —
`(tui-add-child t (hiccup/root dock-component))` instead of constructing
the widget stack by hand.

### 2.7 Headless rendering — trees are data, tests stay plain

Compilation is pure, so fn components are unit-testable without a
terminal:

```clojure
(hiccup/render-lines [:box {:padding 1} [:text "hi"]] 40)
;; the exact lines the frame loop would draw
```

A plain function: no tty, no sleeps — fast-path `bb test` material,
not `^:slow`. Assert on returned lines directly, or call it twice
across a state change and diff — identical lines prove keyed reuse and
cache hits held (the invalidation regression net). Most of Phases B/C
lands against this one function before anything touches a real
terminal.

### 2.8 `kmet.tui.reagent` — the reactive core (Reagent-compatible)

An alternative track, independent of Phases A–D: a **faithful JVM port
of `reagent.ratom`** — the ~700 LOC of Reagent that is pure Clojure
semantics with no DOM and no React. Motivated by the React-renderers
tutorial ("nothing in Reagent's implementation is tied to the DOM") and
by re-examining what React actually provides: its reconciler exists to
serve imperative DOM mutation — kmet already has both halves it would
replace (per-level keyed reconcile = the renderer contract; lines+diff =
the commit). What React has that kmet lacks is only **L0–L2**: the atom,
the auto-discovering reaction, the batching queue. Port those; keep our
L3–L6.

Scope (engine in one namespace, `kmet.tui.reagent`; capture/store
primitives live beside their consumers in `kmet.tui.macros`):

- `*ratom-context*` / `in-context?` / `tracked-deref` — capture-aware
  deref; plain `clojure.lang.Atom`s ARE the tracked inputs (no custom
  atom type ships — see §2.8-bb)
- `make-reaction` / `reaction` / `track` / `cursor` — **auto-dependency
  discovery**: capture derefs during run, `_update-watching` set-diff to
  add/remove watches, dirty flag, queued asynchronous re-run, notify
  watchers only when the new result differs by `=` (Reagent ≥0.6
  semantics, verified against ratom.cljs source)
- batching queue drained at the frame flush — the existing 16ms render
  loop *is* requestAnimationFrame
- `with-let` generation-keyed value store matching Reagent's

**§2.8-bb — Babashka reality check: no RAtom; capture at
`tracked-deref`** (pivoted during implementation). The port as first
proposed assumed a drop-in `RAtom` implementing IDeref/IWatchable/
IReset/ISwap like `clojure.lang.Atom`. Babashka forbids that in pure
source — verified against the running binary:

- `defrecord`/`deftype` support protocol implementations only;
  implementing any Java interface throws ("only support protocol
  implementations").
- `reify` resolves only IDeref/IAtom/IAtom2 of the interfaces needed;
  `IWatchable`/`IReset`/`IRef`/`IPending`/`IMeta`/`IObj` don't resolve,
  and importing them is blocked by bb's class allowlist.
- Delegating through raw interop (`.add-watch`/`.reset`/`.swap` onto an
  inner real Atom) is reflection-blocked in the native image.
- Even with methods present, `clojure.core/add-watch` casts its target
  to `IRef` (ClassCastException), and core `reset!` on an unknown ref
  type returns nil silently — a silent no-op setter, worse than throwing.
- `proxy`, `definterface`, base classes (`ARef`/`AFn`), `gen-class`: all
  unavailable. hashCode/equals on SCI-generated classes are sealed too,
  so reactions must never sit in hash collections (dep vectors scanned
  by identity; watch keys on plain atoms go through record wrappers —
  transcript-scale per-message subscriptions would otherwise hit the
  array-map→hash-map transition and detonate).

So there is no drop-in RAtom, and none is needed. The pivot: kmet
already owns a deref-time capture point — `macros/tracked-deref`, the
runtime under `track!`, through which every component render body
routes its `@reads` today. That fn now also records into
`*ratom-context*`, so reactions discover dependencies from component
bodies with zero constructor migration. Concretely:

- **Plain atoms are the reactive inputs**, watched with ordinary
  `add-watch` (fully supported) — "plain atoms stay first-class" holds
  literally, not as an interim concession.
- **Reactions and cursors are `reify`'d `IDeref` refs** carrying their
  own watcher registries; nested reaction/cursor derefs record
  themselves into the enclosing context from inside their own `deref`.
  They are watched/disposed via `watch-ref`/`unwatch-ref` (core
  `add-watch` can't take them — they aren't IRefs).
- **Coverage contract**: tracked reads are (a) component render bodies,
  automatic via the `track!` rewrite; (b) explicit `tracked-deref`
  calls in hand-written reaction/compute bodies; (c) nested
  reaction/cursor derefs, automatic. A bare `@plain-atom` inside a
  hand-written body is an UNTRACKED read — correct under the batched
  fallback (uncached, never stale), just not narrow. This replaces
  Reagent's "everything derefable is trackable" with an explicit
  contract: the price of bb's sealed interfaces, paid once, here.
- **`(r/atom x)` returns a plain atom**, so re-frame-shaped call sites
  port mechanically; there is simply nothing to swap out underneath.

Consequences downstream: the Stage-1 "RAtom interchangeability" test
becomes the stronger plain-atom interop story (existing setters work on
tracked inputs because tracked inputs ARE existing atoms), and Stage 6
(constructor migration) dissolves entirely — see §7.

**Post-source-review refinements** (after diffing the port against
reagent.ratom 2.0.1 and rum's derived-atom/cursor, sources cloned to
`~/src/cvstree/`): adopted sticky caught errors (a failed body's
exception rethrows from derefs without re-execution; `run!` retries;
the next dep change clears it), callback scheduling (`:auto-run? fn`
— the ComponentFn hook, Reagent's `run-in-reaction` shape), manual-track
value caching, last-watcher auto-dispose for manual reactions,
`add-on-dispose!`, and `run!` = flush-then-force. One deliberate
deviation from current Reagent: plain reactions re-run QUEUED at the
frame tick rather than synchronously in the watch handler — coalescing
matters at streaming write rates, and Reagent's own component path is
callback-based anyway.

What this flips:

- **Auto-tracking becomes the primitive** — decision #5's "deferred,
  unlikely" tracking extraction is superseded: reactions discover deps
  at deref time, no per-body macro rewriting needed. The port is now
  **Stage 1** of the migration plan (§7) rather than a tail phase.
- **Every fn component can be Form-1-reactive** like Reagent — the
  ComponentFn body runs inside a reaction instead of relying on batched
  re-derivation. Batched remains as the opt-out fast path; no
  deref-rewriting macro ever ships.
- **`compute` becomes sugar** over `(r/reaction …)` + frame flush.

Unchanged: §3.3 transcript carve-out (records stay records), keyed
reconcile, line diffing, protocols, layer boundaries. This track is
additive — it is now **Stage 1** of the plan (§7): the port
lands first because nothing consumes it yet.

Rejected alternatives (see §6): vendoring reagent's `.clj` macro files
(they expand into CLJS/JS runtime internals — load but detonate on the
JVM); embedding react-reconciler via GraalJS (~23-item host contract +
custom JVM scheduler to serve an easier commit than ours); rum as base
(its gem, reactive.cljs's deferred-scheduler, is exactly the ~200 LOC
this port absorbs — rewritten without js/Promise/js/Map).

---


## 3. State handling — the combined model

Three homes for state, decided by one question: *how many components read it?*

| State | Home | kmet examples |
|---|---|---|
| Read by ≥2 components | **Global**: app-owned domain atoms + subscriptions | agent-state, session-atom, config, theme, active-status-kind, messages |
| Read by 1 component (+ its children) | **Local**: `:state` map on the record / `with-let` | filter text, expansion, selection, draft, tick |
| Passed to a child as configuration | **Props**: re-applied by reconcile | labels, callbacks, indices, layout params |

### 3.1 Derived state — `hiccup/compute` (explicit-dep, B-lite)

A derived-state mechanism over the atoms the app already owns. No
mega-store, no app rewrite — `compute` lives in the tree layer's
namespace. Before Stage 1 it is self-contained (keyed watches over
listed deps); after Stage 1 it is sugar over `(r/reaction …)` with the
dep list as a hint. Same call shape either way:

```clojure
;; kmet.tui.hiccup — generic, knows nothing about kmet.app
(defn compute
  "Derived atom over DEPS: watches each, recomputes (F) on change,
   skips the write when the result is equal. Returns a plain atom."
  [deps f] ...)
```

Deps are listed explicitly — no dep *discovery*, so nothing has to be
extracted from `track-render` first. (The auto-discovering upgrade is
the `kmet.tui.reagent` track, §2.8: real reactions make `compute` sugar
over `(r/reaction …)` + frame flush.) The returned atom uses the same
watch machinery as `track-render`: keyed watches, equal-value no-op. Derefs are normal
tracked derefs, and when a source changes but the derived value
doesn't, the equality check no-ops → **fine-grained invalidation for
free** — and invalidation schedules the next frame automatically
(§3.4), so subscribing is enough to keep a component live; no manual
`tui-request-render`.

Two usage patterns over the one primitive:

- **Per-instance** — `compute` under `with-let`: created once, disposed
  with the instance. For component-specific slices:

  ```clojure
  (defn message [props]
    (with-let [content (hiccup/compute [(:messages-atom props)]
                             #(get-in @(:messages-atom props)
                                      [(:idx props) :content]))]
      [:text {:text @content}]))
  ```

  Disposal is automatic: when `compute` runs during a render pass
  (`*comp*` bound), it registers its unwinder (remove-watch on each
  dep) with the component via `on-dispose!` — mirrors Reagent's
  dispose-on-unmount, no manual cleanup. Top-level shared computes have
  no enclosing instance and live forever, which is the point. Behind
  `--debug`, `compute` counts instances per component per frame and
  logs loudly on growth — a leak (bare compute in a render body) is
  visible in counters, not just doc.

- **Shared** — a def'd compute: one atom, defined once, N components
  derefing it. No registry, no keys, no lazy creation — `(def …)` *is*
  the registry:

  ```clojure
  ;; kmet.app.ui — shared subs are plain top-level computes
  (def theme-sub (hiccup/compute [theme-atom] identity))
  (def agent-status-sub (hiccup/compute [agent-state] #(:status @agent-state)))

  (defn status-line [props]
    [:text {:text (str @agent-status-sub)}])
  ```

There is deliberately no `reg-sub`/`subscribe`: keyed registries pay off
only with dynamic sub registration, which decision #4 defers
indefinitely — if it ever appears, the registry returns as sugar over
the same compute.

### 3.2 The payoff: kill the mirror plumbing

Today the app layer *finds components and pokes their atoms*
(`assistant-message-append-text!`, pending-bash juggling). With
subscriptions, the app owns pure data and components subscribe to their
slice:

```clojure
;; app layer: pure data update, no component knowledge
(swap! messages-atom update-in [idx :content] str text)

;; message component: computes its slice — under with-let so it is
;; disposed with the instance (see §3.1)
(defn message [props]
  (with-let [content (hiccup/compute [(:messages-atom props)]
                         #(get-in @(:messages-atom props)
                                  [(:idx props) :content]))]
    [:text {:text @content}]))
```

No component lookup, no setter, no sync step. **App = pure state + pure
update fns; view = components subscribing to slices + view-local `:state`.**
This is the re-frame separation of concerns, and the strongest argument
for the global half.

Note the shape: a pure `swap!` on a domain atom, watched by `compute`,
never touches any component API. `kmet.tui.hiccup` is fully usable
headless (plain atoms in → plain atom out) — another §2.7 testing win.

**Theme rides this too.** Today `theme-atom` is threaded through
constructors; under the DSL it's the textbook shared compute —
`(def theme-sub (hiccup/compute [theme-atom] identity))` — and
components deref it instead of receiving theme as a constructor
arg/prop. A palette switch invalidates exactly the subscribed subtrees;
the recurring constructor argument retires gradually, one component at
a time.

### 3.3 Hot path carve-out

The transcript stays **records with instance storage** (ChatHistory
pattern) — never a fn component re-deriving from the message seq, or every
token append is O(transcript) tree rebuild + reconcile. What changes is
*how* a message learns its content: subscription instead of setter-poking.

Per-message subscriptions mean O(n) watch fan-out per append (999
recomputes to equal values, one invalidates) — fine at kmet's scale. If it
ever bites: per-message atoms, or keep the mirror atoms on that one path.
The design doesn't force it.

### 3.4 Frame scheduling — closing the reactive loop

The render loop renders only when `tui-request-render` has set
`:render-requested?` (polled at the loop's ~16ms cadence); today every
mutation site requests its own frame (~90 call sites in interactive.clj
alone). `invalidate-cache` only clears a component's cache — it does not
ask for a frame. That works while state changes flow through setters
that pair `reset!` with an explicit request-render, and it breaks
exactly when Stage 5 lands: replace setter-poking with pure `swap!`s
and **nothing schedules a frame — the UI freezes** until an unrelated
input event happens to force one. Uncached fn components don't fix
this; they only guarantee the fn re-derives on whatever frame arrives.
An atom change must *cause* the frame.

Target model — the other half of Reagent's loop: **a dependency change
schedules a render.**

- All invalidation funnels through `invalidate-cache` (macros.clj):
  `track!`'s watches call it on every real value change, the
  `defcomponent`-generated `invalidate` calls it, and subscription
  invalidation will too (§3.1). One choke point.
- `kmet.tui.macros` grows a scheduler hook: a var holding `(fn [])`,
  default no-op (headless tests, library use). `kmet.tui.core` installs
  `(fn [] (tui-request-render tui))` on start and clears it on stop.
  Dependency direction stays clean — macros never require core; core
  pushes the callback down.
- Coalescing is free: `tui-request-render` sets an idempotent flag the
  loop polls; N invalidations between frames collapse into one frame.
  No debounce machinery.
- Equal-value no-ops stay no-ops end to end: a subscription recomputing
  to the same value fires no invalidation and requests no frame.
  Fine-grained invalidation and frame suppression are one mechanism.

Carve-outs and rules:

- Manual `tui-request-render` stays valid forever (idempotent); Stage 5
  retires call sites gradually and mixed mode is safe.
- Ordering-sensitive mutations keep their explicit request-render next
  to them (focus changes, scroll-to-end, overlay show/hide).
- Time-animated components (spinner/status intervals, flash container)
  keep their own tick loops — unchanged.
- The hook runs inside a watch on the mutating thread; it must not
  throw (wrap + log, mirroring the render loop's crash policy).

With this the §3.2 payoff is genuinely complete: `swap!` → watches
invalidate subscribers → invalidation schedules the frame → the frame
re-derives affected fn components → reconcile dedupes children → line
diff emits the delta. App code stays pure data; the loop closes itself.

---

## 4. Component state — the props/state split

Current: N atom fields per record (`text-atom`, `children`, `entries-atom`,
`gap-atom`, `cache`…) with ~30 bespoke accessors (`text-set!`,
`box-add-child`, `v-stack-set-gap!`…) — `defsetter`/`defgetter` exist to
paper over this.

Target: every component — host element or fn component — has the same
three-field shape, the React props/state model:

```clojure
(defcomponent Text nil [props state cache]
  (render [this width]
    (track! this width
      (let [{:keys [text padding-x padding-y bg-fn]} @props] ...))))
```

Rules:

- **`:props`** — values the parent's tree determines. Replaced wholesale by
  reconcile: `(reset! (:props c) new-props)`. The only writer is reconcile.
  Read in render (tracked).
- **`:state`** — values the component owns and mutates:
  `(state/get c :k)` / `(state/set! c :k v)`. Read in render (tracked).
- **`:cache`** — render output, never reactive state.
- **All props are live** (no seed-once): structural values (padding,
  sizes) stay in `:props` and re-apply like everything else — equality
  no-ops when unchanged, and a parent passing a different value changes
  it. No third category, no copy-to-`:state` step: a value is a prop
  iff the parent's tree determines it, state iff the component mutates
  it. Components that derive structures from props do it in render
  (cache-absorbed), never at construction.
- **Scratch stays out**: `tool_execution`'s last-component atoms, Image's
  id allocation — non-tracked scratch fields, never in `:props`/`:state`.

Why this is low-risk *now* but wasn't before: `track!` invalidates
automatically from derefs, so moving fields into maps requires no
hand-wired invalidation anywhere.

Payoffs:

- reconcile is one line for every component kind: `(reset! (:props c) props)`
- fn components have the same shape — ComponentFn is
  `[f props state children cleanups]` (no `:cache` — the wrapper is
  uncached/allowlisted); `with-let` writes `:state`,
  reconcile writes `:props` — one local-state concept across styles
- ~30 bespoke setters + `defsetter`/`defgetter` collapse into
  `state/get`/`state/set!`
- both maps are snapshotable for tests
- **migration never blocks DSL adoption**: until a component migrates,
  its tag entry uses an adapter ctor mapping the uniform props shape
  onto today's fields — the DSL works either way, and each migration
  stays a small local diff (Stage 2, opportunistic)

Costs (honest): migration covers props *and* internal state (still gradual,
per component, tied to its DSL registration — no big-bang); coarser
invalidation than per-field atoms (absorbed by per-width caches + frame
diff); state keys are looser than fields (destructure + document);
judgment calls at the props/state line follow one question — does the
parent's tree determine it (prop) or does the component mutate it
(state)?

---

## 5. Protocol rework — zero new, one retired

The current protocols: `IComponent` (render/handle-input/invalidate),
`IFocusable`, `IComponentKind`, `IEditorComponent`.

Usage audit (src/, test/, extensions/): `IComponent` — all ~79
components, dispatch in stack/containers/history + the render loop, plus
the extension boundary (`normalize-custom-component` reifies duck-typed
extension maps into it). `IFocusable` — 4 implementors
(input/editor/select-list/settings-list), 2 call sites in focus
routing. `IEditorComponent` — 1 implementor (Editor), 15 `satisfies?`
checks (10 near-identical wrapper fns in core.clj, 2 in interactive, 3
in external_editor). `IComponentKind` — 5 implementors, **one
consumer** (chat_history's private `kind-of`, 5 `case` sites).

Assessment: the composability limits (global focus routing, natural-height
layout) are architecture choices, not protocol gaps. The DSL adds
**no new protocols** — both candidates dissolved into mechanisms the
design already has — and retires **one**: end state is 3 protocols
(`IComponent` + `dispose`, `IFocusable`, `IEditorComponent`), net −1
while gaining a component layer.

**`dispose` goes on `IComponent` — not a separate `ILifecycle`.**
Precedent is in the system already: `handle-input` sits on `IComponent`
with a synthesized no-op so callers never check `satisfies?` — `dispose`
is the same shape (meaningful for a few components, no-op for most).
`defcomponent` synthesizes the no-op (one line in the macro, beside the
`handle-input` default); reconcile calls it unconditionally on removed
children. A separate protocol would buy only an `ILifecycle` check that
the no-op default makes pointless.

Reconcile-created components need cleanup on removal. Today that's
manual (`status-indicator-stop!` ordered around swaps). Containers
implement `dispose` as delegation — dispose each child — or removing a
Box/VStack/Container leaks its subtree's cleanups (the synthesized
no-op doesn't recurse). The need is visible today: tool_execution
hand-cancels its elapsed-ticker interval on completion "so a component
dropped from the chat doesn't keep a zombie interval invalidating
forever" — dispose generalizes exactly that. The same audit applies to
track! itself: its render watches used to outlive disposed components
(zombie watchers firing invalidate-cache forever); `defcomponent`
now prepends `remove-track-watches!` to every generated/custom dispose,
and track-render drops watches for atoms a branch switch stopped reading.

`mount` is deferred: the only reconcile-created timer today (status
spinner) already has explicit start/stop. Add a hook only when a second
reconcile-created side effect appears.

**Children: the closed tag table answers, not an `IChildrenContainer`.**
The protocol existed because reconcile couldn't know who takes children.
It can: the tag table is a hardcoded closed set — container tags carry
`:container?` (storage is uniform after the props/state convergence, so
no `:children-key` either). Reconcile consults the table and calls **one
generic `reconcile-children!` fn in `hiccup.clj`** — a plain function, not
a protocol. Records spliced into trees from outside are opaque at that
level by definition; nobody dispatches on them. Validation ("children on
a leaf") comes free from the same table lookup — another loud throw.

**Cleanup only: delete redundant no-op bodies.** Eight components
hand-write `(handle-input [_this _data] nil)` — box, container,
v_stack, plus alt_screen_flash, dynamic_border, h_stack, scroll_view,
spinner — but `defcomponent` already synthesizes that exact no-op when
omitted. Pure noise; removing them is free, zero-risk cleanup.

**Retire `IComponentKind` — kind-as-data.** Usage audit: implemented by
the five message components, consumed by exactly one namespace —
chat_history's private `kind-of`, feeding five `case` sites. It fails
on three counts:

1. **The data exists one level up.** The messages-atom maps already
   carry `:role`; `kind-of` re-derives type information the data layer
   owns (a parallel copy).
2. **One consumer.** A protocol + extend-type machinery + a
   `satisfies?` guard for a single namespace's internal dispatch.
3. **Kind-as-data is strictly simpler and equally reliable.**
   `defcomponent` already takes the kind argument — stamp it as a plain
   record field instead of an `extend-type`: `(case (:kind child) :tool
   … :bash … nil)`. No `satisfies?` (nil for records without it = same
   semantics as today's guard); works on extension duck-typed adapter
   maps; serializable, so persistence gets message kinds free. The
   field name is owned by the macro in one place — a rename breaks the
   macro call site loudly, not silently, which is all the original
   "safer than duck typing" argument ever bought.

**The survivors, and why:** `IComponent` is essential (see audit).
`IFocusable` is genuine per-type dispatch that extension-provided
editors need without joining any tag table; the field-based alternative
(`(:focused? c)`) is the duck typing this code already got burned by.
`IEditorComponent` has one implementor but is *the* extension seam for
alternative editors (vim/emacs factory mechanism, pi parity) — its
smell isn't the protocol but the ten near-identical `satisfies?`
wrapper fns around it in core.clj, which is adapter noise to
consolidate, not a design flaw. Honest caveat: both survivors rest on
the extension argument, not internal need — if extensions ever stop
replacing editors/focusing widgets, they collapse into fields too.
Today pi parity answers that the other way.

**Explicitly NOT doing** (see also §6):

- `render` returning a tree in the protocol — forces every render through
  compile+reconcile (the hot-path cost); the tree level belongs *above*
  the protocol, in the DSL. `render → lines` stays; fn trees compile
  down to it.
- Input propagation through ancestors (containers intercepting keys
  before the focused leaf) — breaks pi parity, complicates the input path
  (Kitty release events, IME); dialogs already trap keys manually.
- Fine capability split (`IRenderable`/`IInputHandler` as separate
  protocols) — moves no-op checks to call sites (`satisfies?` in the
  TUI's focus dispatch); `defcomponent` already hides the no-ops. Churn
  without real gain.
- Declarative input props (`:on-key`/`:on-click` in trees) — see below.

**The input boundary — stated, because it will be asked.** Hiccup for
the web invites `[:button {:on-click f}]` thinking; that cannot work
here. Input goes to the **focused leaf only** (pi parity; Kitty release
events, IME, focus routing are all real machinery the tree never sees).
So: **no declarative input props, ever; interactivity stays imperative**
— focus + widget records (Editor/Input/SelectList) + `:ref` +
keybindings. The DSL owns composition and presentation; input ownership
is the one axis it deliberately does not touch. A `:on-key` prop in a
tree is a design error, not a missing feature — this paragraph is the
answer to give whoever reaches for it.

---

## 6. Explicitly rejected

| Idea | Why |
|---|---|
| Global vdom reconciliation | Per-component reconcilers only; terminal output already diffs at the line level underneath. |
| Growing reactivity via compile-time deref rewriting (`defc`) | The macro was only ever a delivery mechanism for tracking while atom types weren't owned. Deref-time capture (Stage 1) plus the §2.8-bb capture point remove that premise: reactions track existing plain atoms directly, no constructors migrate. Two tracking mechanisms would be one too many (§2.4, §2.8). |
| `:children?`/`:children-key` tag-spec fields | Second children mechanism beside the tag table's `:container?` — one answer per question: the table says who takes children, the one generic `reconcile-children!` fills them (§2.2, §5). |
| Converting primitives to fn components | They're the host elements — that would be reimplementing `[:div]` as a React component. |
| Full re-frame store (global app-state atom + cursors) | App-layer rewrite; crosses the `kmet.app`/`kmet.tui` boundary; kmet's state graph isn't complex enough. B-lite (domain atoms + subscriptions) gets the value without the rewrite. |
| Stateless components + top-level connect | Prop-drilling is verbose; the connect still needs per-component re-derivation; Reagent's idiom is "ratoms anywhere". |
| `dsl/update!` (imperative child swapping) | Subsumed: a `when-let` in a tree + track!-driven re-derivation + reconcile handles it. |
| Re-invoking *expensive* work per frame with no caching | Pre-Stage-1 fn bodies are batched-re-invoked by design — cheap by construction. What's rejected is uncached expensive work at frame rate: the transcript stays records (§3.3); after Stage 1, reactions skip unchanged bodies entirely. |
| `render` → tree in the protocol | Forces every render through compile+reconcile — the hot-path cost. The tree level belongs above the protocol, in the DSL. |
| Input propagation through ancestors | Would make dialogs trap keys declaratively, but breaks pi parity, complicates the input path (Kitty release events, IME); dialogs already trap manually. |
| Fine capability split (`IRenderable`/`IInputHandler`) | Moves no-op checks to call sites; `defcomponent` already hides the no-ops. Churn without gain. |
| Monolithic screen component (one reaction reading all app state) | Covered and correct — any change re-derives it — but coarsest legal granularity: full-screen body re-run per frame during streaming, structural `=` walks on large tracked collections, and mapping messages into elements inside it resurrects the rejected transcript rebuild at frame rate. Shape: small region-level components + records for the transcript (§2.4, §3.3). |
| `register!` registry API | Host elements are a closed set — hardcoded tag table in `hiccup.clj`; fn heads cover custom components. |
| Seed-once / `:structural` spec category | Three-way props/state/structural split has no consistent semantic (who re-writes the seeded value when the parent changes it?); all-props-live + equality no-ops covers it with two categories and one rule. |
| Per-child render isolation (error boundaries) at v1 | No real throwing-component case yet; the loop's crash policy (log + stop) is honest enough until one exists. Revisit when a component fn can plausibly throw per-frame. |
| Auto-tracking subscriptions (`kmet.tui.tracking`) on the critical path | Explicit-dep `compute` needs no dep discovery — same equality no-op, none of the riskiest-refactor exposure. Deferred upgrade, only if branchy subs appear (§3.1). |
| Two public entry points (`dsl/component` + `dsl/root`) | `root` is component-plus-mounting; two names for one concept invites a tree that's compiled but never mounted. One entry point. |
| New lifecycle/children protocols (`ILifecycle`, `IChildrenContainer`) | `dispose` is `handle-input`-shaped: few real implementations, no-op for most — it belongs on `IComponent` with a synthesized default. Children dispatch on the closed tag table (`:container?`) through one generic fn. Zero new protocols (§5). |
| Keeping `IComponentKind` as a protocol | One consumer (chat_history's `kind-of`), and the messages-atom already carries `:role` — a protocol re-derives data the data layer owns. Kind-as-data stamped by `defcomponent` is simpler, serializable, and fails loudly on rename (§5). |
| Declarative input props (`:on-key`/`:on-click`) in trees | Input goes to the focused leaf only (pi parity, Kitty/IME); an `:on-key` prop would be a dead handler. Interactivity stays imperative: focus + widget records + `:ref` + keybindings (§5 input boundary). |
| Vendoring reagent's `.clj` macro files (`core.clj`, `ratom.clj`) | They are macro shadows expanding into CLJS/JS internals (`cljs.core/js-obj`, `unchecked-aset`, `reagent.impl.component/functional-render`) — they load on the JVM and detonate at runtime. The portable content is the API surface, absorbed by `kmet.tui.reagent` (§2.8). |
| Embedding React (react-reconciler + GraalJS) as the renderer | ~23-item host contract (13 host-config fns, custom JVM scheduler — no MessageChannel, hook runtime, fiber trees) to serve imperative DOM mutation our architecture never does: keyed reconcile is the renderer contract, lines+diff is the commit. GraalJS is a heavy new dep on Termux. Port the ratom layer instead (§2.8). |
| Rum as the base | Its portable gem is `reactive.cljs`'s deferred-scheduler (~200 LOC: capture derefs, watch set-diff, queued re-runs) — sitting on `js/Promise`/`js/Map` + React mixins. Keeping the idea means rewriting those lines anyway; that rewrite *is* `kmet.tui.reagent` (§2.8). |

---

## 7. Migration plan

Ordered so the app keeps working at every step: **pure additions land
before anything consumes them**, conversions are one-commit revertible,
and the transcript is never touched. Each stage ends with the full gate
`bb lint` + `bb format-check` + `bb test` + `bb test-ext`.

### Stage 1 — `kmet.tui.reagent`: the full port, first — DONE

Pure addition — a new namespace nobody imports yet, so breakage risk is
zero by construction.

1. **Port `reagent.ratom`'s engine** — per §2.8-bb: no RAtom type;
   capture feeds `*ratom-context*` from `tracked-deref` (macros.clj);
   reactions/cursors are reify'd IDeref refs with internal watcher
   registries behind `watch-ref`/`unwatch-ref`; `make-reaction` with
   auto-dep discovery (capture → `_update-watching` set-diff → dirty
   flag → queued run → notify on `=` change), `track`, `cursor`,
   batching queue, generation-keyed `with-let` store.
2. **Headless tests first**: dep discovery across branches, watch
   set-diff on branch change, `=`-no-op notification, queue draining,
   plain-atom interop (deps are existing atoms — existing setter fns
   work unchanged), component-body capture (a Text render inside a
   reaction tracks `text-set!`), plus ports of reagent's own test
   cases: branch-switch disposal, reset-in-reaction convergence,
   indirect exception recovery, sticky caught errors. Register the
   test ns in `kmet.runner/all-namespaces`.
3. **Frame flush install** — batching drained from the render loop's
   existing 16ms tick (one hook install in `tui.core`; default no-op
   when no queue exists). Gate: a queued reaction runs exactly once per
   frame.

Nothing else changes. The app doesn't know this namespace exists.

### Stage 2 — `hiccup.clj` construction layer + protocol cleanup — DONE

Still pure addition (plus the A0/A1 cleanups, which are behavior-
preserving):

4. **Protocol cleanup** — delete redundant `handle-input` no-op bodies;
   retire `IComponentKind` (kind stamped as field by `defcomponent`;
   chat_history dispatches on `(:kind …)`).
5. **Tag table + `compile-element` + `hiccup/root`** — construction
   only; loud validation (unknown-tag throw with did-you-mean, fn heads
   as values, children on a leaf tag); adapter ctors keep unmigrated
   host elements usable. `render-lines` headless surface.
6. **Dispose plumbing** — `dispose` on `IComponent` (synthesized no-op);
   tag table `:container?`; generic `reconcile-children!`.

Still nothing mounted — the app renders exactly as before.

### Stage 3 — reconcile + ComponentFn (additive machinery) — DONE

7. **`reconcile!`** — keyed child diff, duplicate keys throw,
   `:key`/`:ref` pseudo-props stripped, refs filled/cleared.
8. **ComponentFn** — wrapper record; binds `*comp*`/`*width*`; body
   runs inside a `reagent/reaction` (auto-discovered deps — the §2.8
   story from day one; there is no defc, see below); `dispose` runs
   children first, then cleanups. Batched fallback path retained for
   opt-outs and non-ratom reads. `--debug` counters.
9. **`with-let`** lands in `macros.clj` over the Stage-1 store.

Shipped deviations: one diff mechanism replaces both the stage-2
append-only fill and the wrapper-level reconcile (host containers get
their children through the same keyed diff via per-tag children lenses);
reused host LEAVES with changed props are rebuilt rather than mutated
(identity-free display records; content setters unnecessary), while
containers and fn components keep identity across passes. Ownership and
keyed-match recovery ride a `:dsl/meta` stamp — an ATOM on every DSL-
constructed record (record identity never changes, yet reconcile must
remember per-instance facts across passes: the applied props for the
leaf fast-path, the explicit :key so keyed matching survives into the
next pass, and the last ref handle so removal clears it). Foreign
spliced records never carry the stamp and are never disposed.

The frame scheduler hook (`kmet.tui.macros/schedule-frame!`, no-op
default) landed with ComponentFn's `:auto-run?` callback — which fires
synchronously in the dep-handler on the MUTATOR's thread (Reagent's own
component path), not at flush time; install/uninstall lives beside
flush! in tui.core.

One correctness gap surfaced during review and closed there:
reaction-cached bodies reading only UNTRACKED values (bare `@plain-atom`
closures, static trees) would cache once and go STALE forever, where the
pre-stage-3 batched fallback re-derived every pass. ComponentFn
reactions therefore run with `:rerun-without-deps? true` (+ an
`:implicit-deps` exemption for the framework's own props/ctree reads): a
body collecting no real dependencies re-runs on every deref — batched
semantics restored — while bodies with ≥1 tracked dep stay narrow.
Mixed bodies must read their reactive inputs through tracked-deref,
cursors, or reaction slices (the §2.8-bb coverage contract). One
corollary, pinned by test: PROPS-ONLY bodies (no other reactive input)
also re-derive each frame — correct (the props watch still dirties on
real changes) and identical to the batched cost model; the §4 migration
shrinks that class by moving pure display into host leaves.

`with-let` shipped over the store primitives (`fetch-local`
/`register-cleanup!`) with both reagent behaviors verified by test: a
throwing init retries next pass per binding and holds the body out
until it succeeds (reagent issue #525), and using one expansion site
twice in one render pass throws loudly (reagent's generation warning,
promoted to a throw per the v1 contract — give each instance its own
element/component).

Stage-3 review fixes (all pinned by test):

- **Keyed buckets carry the kind**: `{::user-key k ::kind kind}` on
  both the desired and previous sides — an element switching host↔fn
  under one stable key remounts (React semantics) instead of crashing
  on a cross-kind reuse (the old shared bucket NPE'd one direction and
  accidentally worked in the other).
- **Entry maps are never props**: `[:v-stack {:component c}]` mounts
  the entry as a child; consuming it as the props map silently dropped
  it. Outside stack tags it still throws loudly.
- **Width invalidation**: see the §2.4 `*width*` bullet — resize alone
  re-derives affected bodies exactly once, then re-caches.
- **track! watch lifecycle**: watches no longer outlive components
  (see §5); branch-switched atoms lose their watches at re-render.

Second review round (cross-checked line-by-line against
reagent.ratom.cljs and hiccup's compiler):

- **Non-reactive deref settles then answers CURRENT** — Reagent's
  `-deref` flushes the queue before every read outside a reaction and
  inline-runs dirty bodies; the port had enqueued fresh reactions and
  returned nil until the next frame (a stale-read trap for Stage 5
  computes read from handlers). Aligned: flush first, always current.
  The deliberate deviation stands UNCHANGED for dep-change re-runs:
  those stay queued at the frame tick instead of running in the watch
  handler.
- **Self-dispose scoped to manual tracks** — only `:auto-run? false`
  reactions die with their last watcher. Callback-driven ComponentFn
  reactions must not (kmet dispose is terminal; Reagent survives the
  equivalent because plain reactions resurrect on deref).
- Hiccup parity notes confirmed: attrs-map-in-first-position matches;
  kmet's record/entry-map guards close holes real hiccup has (records
  are maps there too); keyword children throw instead of rendering an
  empty element (deliberate, tested).
- Accepted divergences (documented, not bugs): cursors are read-only
  (no reset!/swap-through); input-side dep gating is stricter than
  Reagent's identical?-only fast path; flush! throws on non-settling
  cycles where Reagent would spin.

Still zero app usage.

### Stage 4 — first mounts, leaf-first (the only stage touching live UI)

Each conversion is one commit: convert, eyeball in a real session, full
gate. Rollback = revert that commit. Order chosen bottom-up — smallest
blast radius first:

10. **Status container — DONE** — the clear/add/stop dance became a
    `when-let` tree mounted via `hiccup/root`. The layer is
    `ui/make-status-area` (`kmet.app.ui.status-indicator`): a fn component
    derefing a `:status-current` atom ({`:kind k :indicator c}` or nil,
    tracked read → narrow reaction) over the default working indicator;
    swaps are pure `reset!`s and reconcile diffs the single child.
    `CoreState` lost `:status-container`/`:active-status-kind` to
    `:status-root` + `:status-current`; spinner start/stop stays imperative
    at the swap sites (dsl.md §5), manual request-render kept per stage
    rules. Transient indicators splice as foreign records — never disposed
    by reconcile; their lifecycle stays with the swapper.
11. **Dock / widget areas** — static-ish composition, exercises keyed
    reconcile against real input handlers.
12. **Dialogs** — exercises `:ref` (focus) and imperative interop; do
    these after the simple trees have soaked.

Manual `tui-request-render` stays everywhere during this stage — the
scheduler comes later, so nothing can freeze.

### Stage 5 — subscriptions + scheduling

13. **`compute` = sugar** over Stage-1 reactions (explicit deps remain
    available; auto-discovery is the default under it).
14. **Scheduler gate** — invalidate-cache triggers the hook (§3.4).
    Gate: bare `swap!`/`reset!` on a subscribed source produces exactly
    one frame. Only after the gate passes, retire manual request-render
    call sites gradually (they stay valid forever).
15. **Mirror-plumbing removal** — per message kind, one commit each:
    components compute their slices; `assistant-message-append-text!`
    et al. retire. Theme becomes a shared def'd reaction.

### Stage 6 — state typing + cleanup (optional tail)

16. ~~**Migrate app-owned atom constructors to `RAtom`**~~ — dissolved
    by the §2.8-bb pivot: there is no RAtom type, and none is needed.
    Plain atoms already ARE the tracked inputs — component bodies get
    that for free via `tracked-deref`; hand-written reaction bodies use
    `compute`/`cursor` slices or explicit `tracked-deref`. The two-
    worlds split Stage 6 existed to close ("is this read tracked?") is
    closed by the shared capture point instead of by owning the atom
    type: every `@read` in a component render body is tracked today,
    before any migration. What remains of this stage is item 17's
    cleanup only. (On a non-bb target where interfaces are
    implementable again, a tracked atom type could return — as sugar,
    not as a premise.)

17. **Retire leftovers** — `assistant-message-append-text!` remnants,
    unused adapter ctors, any remaining manual request-render next to
    converted trees.

### The transcript, explicitly

Never a fn tree, at no stage (§3.3). ChatHistory remains records with
instance storage; screens reference it as a splice/tag. Its internals
can adopt `compute` slices later, opportunistically.

### Do we need `defc`? No — dropped from the plan

`defc` existed to deliver dependency tracking through compile-time deref
rewriting — a workaround for *not owning the atom type*. Stage 1 removes
that premise twice over: reactions discover deps at deref time (no
rewriting needed for tracked reads), and the §2.8-bb pivot makes the
app's EXISTING plain atoms the tracked inputs through the shared capture
point — no constructor swap ever. Every fn component is Form-1-reactive
like Reagent, no macro. Reads outside the contract (bare `@plain-atom`
in a hand-written body) are covered explicitly via `compute`/`cursor`
slices or `tracked-deref`. The macro is never written; if a hot spot
ever needs input-side caching without reactions, that's a different,
smaller tool.

Guardrails: tests in `test/kmet/tui/`, new namespaces registered in
`kmet.runner/all-namespaces`; clj-kondo hooks for `track!`/`with-let`;
cljfmt `:extra-indents`; ComponentFn on the uncached-allowlist for its
batched path (AGENTS.md reactive-cache section documents the
reaction-backed path); plain-atom interop stays green forever (existing
setter fns keep working on tracked inputs — they are the same objects;
the rollback guarantee for every conversion stage); full gate at each
stage boundary.

**The perf invariant, as a test**: an idle UI runs zero fn bodies and
zero reaction re-runs. Headless (§2.7): render a tree twice with no
state change between — invocation counts stay 0. This single assertion
cements the memoization contract (reactions + caches + equality
no-ops); failures land in `bb test`, not someone's scrollback.

## 8. Layer boundaries (unchanged)

```
kmet.app        : owns atoms, pure data updates (no component knowledge)
kmet.app.ui     : fn components (shared def'd computes, :state local)
                  + hiccup/root mount points
kmet.tui        : reagent (reactions/track/cursor/batching over plain
                  atoms — no RAtom type, §2.8-bb),
                  hiccup (tags/compile/reconcile, root, ref,
                  render-lines, compute-as-sugar), macros (track!,
                  with-let, let-state, on-dispose!, invalidate-cache),
                  ComponentFn (reaction-backed / batched opt-out), protocols
                  (IComponent + dispose, IFocusable,
                  IEditorComponent — IComponentKind retired: kind-as-data)
kmet.libs.*     : self-contained (unchanged)
```

`hiccup/compute` is generic — no app concepts. Shared computes are
def'd in `kmet.app.ui`. `kmet.app` (non-ui) never imports `kmet.tui.*`.

---

## 9. Decisions

One line each — the sections carry the reasoning.

1. **Props/state split** — `:props` + `:state` + `:cache`; all props
   live; adapter ctors decouple migration (§4).
2. **No `defc`** — reactions (Stage 1 port) deliver tracking at deref
   time; no deref-rewriting macro ever ships. Reads outside the
   tracking contract covered by compute/cursor slices or explicit
   `tracked-deref` (§2.4, §2.8-bb).
3. **`with-let` day one** — sugar over `let-state`/`on-dispose!`; all
   three live in `kmet.tui.macros` (§2.5).
4. **Shared computes are def'd, per-instance under `with-let`;**
   auto-disposal when created in a render pass; registry only if
   dynamic registration ever appears (§3.1).
5. **Explicit-dep `compute` first, auto-tracking as Stage 1** —
   compute ships without dep discovery; `kmet.tui.reagent` (§2.8) is
   the upgrade path that makes compute sugar over real reactions.
6. **Zero new protocols, one retired** — dispose joins `IComponent`;
   children via tag table fn; `IComponentKind` → kind-as-data field;
   end state 3 protocols, net −1 (§5).
7. **Hardcoded tag table** — closed set in `hiccup.clj`; fn heads cover
   custom composition (§2.2).
8. **Mirror-plumbing removal is committed payoff**, not optional
   (§3.2).
9. **Invalidation schedules the frame** — scheduler-hook var, coalesced
   by the existing ~16ms poll; manual request-render stays valid
   (§3.4).
10. **`:ref` pseudo-prop** — reconcile-owned wrapper type, imperative
    escape hatch: focus/text/scroll only (§2.3).
11. **Error contract v1: crash loud** + loud validation everywhere;
    error boundaries deferred (§2.4).
12. **One mount path** — `hiccup/root`; owner disposes (§2.6).
13. **Headless-first testing** — `render-lines`; idle-UI invariant:
    zero fn bodies when nothing changed (§2.7).
14. **Dispose order contractual** — children first, then own cleanups
    (§2.4).
15. **Dynamic `*width*`** — bound beside `*comp*` during fn render
    (§2.4).
16. **Input stays imperative** — no declarative input props, ever
    (§5).
17. **Reagent's three forms, faithfully** — plain fn = Form-1
    (reaction-backed after Stage 1), `with-let` = Form-2's state half,
    `defcomponent` = Form-3; raw-record splicing as fourth adapter form
    (§2.4, §2.8).
18. **No `defc`, ever** — the deref-rewriting macro was a workaround
    for not owning atom types; deref-time capture (Stage 1) removes the
    premise outright, and the §2.8-bb capture point makes existing
    atoms first-class inputs with no migration. Batched path remains as
    opt-out (§2.4).
19. **Port the ratom, not React** — `kmet.tui.reagent` is a faithful
    port of `reagent.ratom`'s semantics: auto-dep reactions, batching
    at the frame flush, Reagent-compatible API. React/rum rejected as
    bases — their value is L0–L2, which the port covers; their
    reconcilers serve a DOM contract kmet doesn't have (§2.8).
20. **No RAtom type — capture at `tracked-deref`** (amendment forced
    by Babashka): bb seals IWatchable/IReset/IRef away from pure-source
    implementations (verified against the binary), so a drop-in atom
    type cannot exist. Capture rides the `tracked-deref` funnel every
    component body already uses; plain atoms are the tracked inputs;
    reactions/cursors are reify'd IDeref refs behind
    `watch-ref`/`unwatch-ref`; Stage 6 dissolves (§2.8-bb).
