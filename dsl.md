# kmet TUI DSL — Reagent-style component layer (proposal)

Status: design proposal. Nothing here is implemented yet. This document
captures the agreed architecture for adding a Reagent/Hiccup-like
composition layer to the existing kmet TUI, and how state is held.

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
  components are frame-batched — dependency changes schedule frames
  (§3.4), each frame re-derives fn trees from the roots, leaf caches +
  line diff absorb the cost. Not Reagent's per-component reactions: a
  bare `@atom` in a plain fn schedules nothing — only subscription/
  track! invalidation brings frames; `defc` opts a fn into cached,
  reactive reads (§2.4, §3.4).
- **Lifecycle**: `dispose` — new method on IComponent (no new protocol;
  §5). `IComponentKind` retires — kind becomes a stamped record field.
- **Scheduling**: invalidation schedules the next frame (§3.4) — new;
  without it, pure data updates never reach the screen.

The flow, end to end:

```
app atoms → (change) → invalidate + schedule frame → fn (re-derives per
          pass) → tree → compile+reconcile (keyed, per level) → record
          tree → lines (each record caches) → frame diff → terminal
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

This is what unblocks Phase B step 7: `hide-dialog` keeps its imperative
focus call, but the dialog tree declares the editor with a ref instead
of the app pre-building and stashing the record.

### 2.4 The ComponentFn wrapper

```clojure
(defcomponent ComponentFn nil [f props state children cleanups cache-atom]
  (render [this width]
    (binding [*comp* this *width* width]
      (if (:reactive? this)
        ;; defc path — host-element semantics: cached, tracked derefs
        (track! this width
          (reconcile! children (f @props))
          (render-children @children width))
        ;; plain path — batched: re-derives EVERY render pass
        (reconcile! children (f @props))
        (render-children @children width))))
  (dispose [this]
    ;; children first — their cleanups still see intact parent state;
    ;; then own cleanups, then drop state
    (doseq [c @children] (dispose c))
    (doseq [f @cleanups] (f))
    (reset! state {})))
```

Properties:

- **Two paths by authoring choice**: fn heads written as plain `defn`s
  are batched (the wrapper is uncached for them, like the
  transparent-parent allowlist) — trees are small and `reconcile!`
  dedupes children, so unchanged subtrees cost nothing. `defc` fns get
  the track! path: same machinery host elements use (`rewrite-derefs` +
  `track-render`, both already in production), so the body re-runs only
  when `@props` or any deref inside it changed value. No reaction
  scheduler, no atom ownership, no tracking extraction — the frame
  still fires per §3.4; the cache just makes the fine-grained part of
  it free.
- **The reactive door is the macro** — a correctness rule, not style.
  A plain fn's internal derefs are never rewritten to `tracked-deref`,
  so caching its output would serve stale lines when only an untracked
  atom changed. Plain = batched, `defc` = reactive; there is no third
  way and no implicit upgrade.
- **Props re-applied on reuse** via `reset!`; equal-value resets no-op →
  memoized children for free (and on the defc path, the `@props` deref
  inside the tracked scope makes prop changes invalidate correctly).
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
  same dynamic-scope cost as `*comp*`, no machinery.
- **Dispose order is contractual**: children first, then own cleanups —
  a child cleanup may still read intact parent state (React effect
  semantics). Not incidental; tests rely on it.
- **Observability**: behind `--debug` the wrapper counts per-frame fn
  invocations, reconciles, and cache hits. A fn invoked on frames where
  nothing it derefs changed is the inline-callback trap (§2.5's
  inline-callbacks bullet) or a broken equality — invisible in output
  bytes, obvious in counters.
- **`defc` — the reactive fn form, opt-in**. Plain `defn` heads stay
  batched and are the default:

  ```clojure
  (defn status-area [props]      ; plain: batched, re-runs every frame
    [:v-stack {:gap 0}
     (when-let [kind @(:active-status-kind app)]
       [:status-indicator {:key kind}])])
  ```

  `defc` opts a component into the track! path:

  ```clojure
  (defc hot-widget [props] ...)  ; macro: defn + rewrite-derefs on body
                                 ; + :reactive? flag on the wrapper instance
  ```

  The macro is ~10 lines (expand to `defn`, wrap body in the existing
  deref rewriting, mark the var) — no new runtime machinery: it reuses
  `track-render` verbatim, which is what host elements already run.
  Deps are automatic (branch-conditional reads handled like Reagent);
  this supersedes the previously sketched `{:memo [deps]}` hatch — one
  mechanism, automatic, more faithful. Phase D, gated on the `--debug`
  counters showing real body-churn waste; until then everything ships
  batched and the invariant tests pass either way.

  Lexical boundary (same as Reagent's): reads that don't appear as
  `@x`/`(deref x)` forms at compile time — dynamically built derefs,
  eval'd code — aren't tracked. Documented, not detected.

  SCI note: expands to plain calls (`tracked-deref`), and
  `kmet.tui.macros` is already injected into extension contexts —
  extensions can use defc without host changes. This is also what
  dissolves the atom-ownership problem for extensions: `tracked-deref`
  records *any* IDeref, so a defc body reacts to plain app atoms — no
  ratom, no migration (the macro moves tracking from the atom's type
  to the read site).

**The three forms — Reagent's taxonomy, mapped.** Reagent has exactly
three ways to author a component; this design covers all three with the
same surface, minus Form-2's footguns:

| Reagent | Here | Notes |
|---|---|---|
| **Form-1**: pure fn `(defn c [] [:div …])` | `(defn status-area [props] tree)` | Identical ergonomics; batched scheduling |
| **Form-2** (reactivity): reaction-wrapped Form-1 | `(defc hot-widget [props] …)` — opt-in macro: defn + deref rewriting + track! cache (§2.4) | Same role, different mechanism: Reagent wraps every fn in a reaction; here the wrapper caches on tracked values. No inner-fn idiom → the accidental-inner-fn bug can't exist; `with-let` covers its state half |
| **Form-3**: `create-class {…}` | `defcomponent` record | Equivalent method map: `render` ↔ `render`, `dispose` ↔ `componentWillUnmount`, `handle-input` is terminal-only (no DOM analog); `did-mount` deferred until a second reconcile-created side effect appears (§5) |
| — | raw records spliced into trees (`existing-component`, §2.1) | A fourth form Reagent lacks — dropping a live instance into Hiccup; the migration/adapter path, load-bearing for Phase A |

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
dedupes children and leaf caches + line diff absorb the cost. `defc`
fns close most of that gap (above) without a reaction scheduler: same
frame fires, but the track! cache skips bodies whose deps didn't change.
The idle-UI invariant (zero fn bodies when nothing changed) holds for
both paths. For plain fns the old rule stands: don't reach for derefs
to "optimize" re-render scope; subscriptions are the tool (§3.1).

**Why not full per-component reactions for everything?** The remaining
gap to Reagent's runtime needs deref tracking inside *every* fn body.
Tracking must exist **at the read site**, which needs one of:

1. **Reagent-owned atoms** — users write `@(r/atom …)`, a custom IDeref
   whose deref records into the enclosing reaction. Mechanically kmet
   *could* ship such a type (bb/sci support custom IDeref; namespaces
   are injected into extension contexts by reference), but it fails on
   coverage and mixing:
   - **Coverage**: tracking lives in the atom type, and the atoms worth
     reading are `kmet.app`'s — plain `clojure.lang.Atom`s created long
     before the DSL. On the JVM you cannot retro-fit deref behavior
     onto existing instances, so reactive-over-app-data requires
     migrating every app-owned atom. A ratom only ever makes an
     extension reactive to its own local state.
   - **Mixing**: partial adoption produces silent freezes, not errors —
     a component reading its own ratom plus `messages-atom` re-renders
     on the former and never on the latter. Reagent has this exact two-
     worlds split (`r/atom` vs plain cljs atom) and handles it socially
     (greenfield app, one team, "always use r/atom"); a plugin API
     can't rely on convention for correctness, and nothing stops an
     author writing `(def s (atom …))` anyway.
2. **Compile-time rewriting everywhere** — making `defc` mandatory. All
   kmet extensions are internal (first-party, updated in lockstep), so
   the classic objection — taxing third-party plugin authors — doesn't
   apply here. What remains: mandatory defc forfeits plain-`defn`
   Form-1 authoring for the 90% of widgets whose per-frame
   re-derivation is already free, and buys nothing over opt-in (the
   tracking machinery is identical; only the default differs). Opt-in
   keeps the low-friction path and stays evidence-gated.

And the payoff wouldn't justify either. The waste batching leaves on
the table = live fn bodies × small-vector-building per frame —
microseconds even at 100 components, against leaves whose real costs
(markdown parse, wrapping) are already cache-absorbed. A cached fn
component couldn't prune the child walk anyway: transparent parents sit
on the uncached allowlist precisely because children change via their
own deps — so caching gates body+reconcile only, never rendering below.
Value-based memoization would also make inline-callback props a
correctness-grade trap instead of a CPU footnote, deterministic headless
tests get harder with async flush windows, and pi's explicit-
requestRender model — mirrored everywhere else in the codebase — drifts
further away. The `--debug` counters + idle-UI invariant test exist
exactly to detect if this trade ever stops being free.

The escape hatch is `defc` (above), not a separate memo mechanism: an
earlier draft sketched `{:memo [deps]}` / `(hiccup/memo deps f)` — explicit
deps over discovery — but defc supersedes it: automatic deps, no listing,
branch-conditional reads handled, same no-scheduler/no-atom-ownership
properties, and it reuses machinery that already exists. One reactive
story instead of two.

**Granularity guidance (the monolith trap).** A tempting shortcut:
"make the whole main screen one big `defc` that reads all app state" —
covered and correct, since any change re-derives it. Three reasons not
to:

1. **Coarsest legal granularity**: one reaction means *any* change
   re-runs the *entire* body — during streaming, that's a full-screen
   re-derivation per frame. Same CPU profile as the batched default,
   none of defc's benefit. The win comes from many small `defc`s
   reacting narrowly (screen shell → region panels → leaves).
2. **Tracked reads of large collections cost an equality walk per
   frame** — derefing `messages-atom` in a body means a structural `=`
   against last frame's value; during streaming the tail changed, so
   the walk goes deep before failing. Narrow reads or computes sliced
   near the consumer avoid it.
3. **The transcript stays out regardless** (§3.3): mapping messages
   into elements inside a screen body is the rejected per-token whole-
   tree rebuild, now at frame rate. ChatHistory remains records;
   screens reference it as a splice/tag.

Phase B step 8's shape follows: `defc` root shell + region-level
`defc`s (dock/status/dialogs) + transcript as records.

### 2.5 Lifecycle — `dispose`, not `with-let`-as-macro

`IComponent` gains a `dispose` method, default no-op (synthesized by
`defcomponent`; no new protocol — §5). Containers, overlays, and the
TUI call it when a child is removed. Component-local state is already
record fields; `dispose` is the missing cleanup half.

For fn components that need transient state, primitives (SCI-friendly,
plain fns — living in `kmet.tui.macros` beside `track-render`/
`invalidate-cache`, so macro and runtime companions share one home,
same pattern as `track!`):

```clojure
(defn let-state
  "Per-instance value for KEY, initialized once."
  [key init]
  (let [ls (:state *comp*)]
    (if (contains? @ls key) (get @ls key)
        (let [v (init)] (swap! ls assoc key v) v))))

(defn on-dispose! [f] (swap! (:cleanups *comp*) conj f))
```

`with-let` is sugar over these two — **implemented**, not deferred
(decision #3) — expanding to a plain runtime call (same philosophy as
`track!`):

```clojure
(defmacro with-let [bindings & body]
  (let [cleanup (first (filter #(and (seq? %) (= 'finally (first %))) body))
        body' (remove #(identical? cleanup %) body)
        ;; gensym'd once per expansion site → stable across render passes,
        ;; unique per with-let — the let-state guard makes it once-only.
        ckpt (gensym "with-let-cleanup")]
    `(let [~@(mapcat (fn [[s i]]
                       ;; key is the expansion-site gensym, not the symbol:
                       ;; two sibling with-lets binding the same name cannot
                       ;; collide — each gets its own state slot by construction
                       [s `(let-state '~(gensym (str s "-")) (fn [] ~i))])
                     bindings)]
       ~@(when cleanup
           [`(let-state '~ckpt (fn [] (on-dispose! (fn [] ~@(rest cleanup)))))])
       ~@body')))
```

The guard matters: the body re-runs every render pass, so a bare
`(on-dispose! …)` would conj a fresh closure per frame — cleanups grow
without bound and all fire on dispose. Wrapping the registration itself
in `let-state` runs it once per instance, exactly like the bindings.

Footguns (documented):

- **Fn bodies must be pure per pass** — they re-run every render pass
  (the wrapper is uncached). Creation-time side effects (timers) go in
  `with-let` init (runs once), cleanup in `on-dispose!`.
- **Subscriptions are created once** (shared registry, or `compute`
  under `with-let`), never bare in the render body — or the derived
  atom leaks per re-render.
- **`let-state`/`on-dispose!` are render-pass-only** — they read the
  dynamic `*comp*`, bound only while the wrapper renders. Calling them
  from an async callback gets the wrong instance or none. This is the
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

This is the missing endpoint for Phase B: interactive.clj keeps every
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

---

## 3. State handling — the combined model

Three homes for state, decided by one question: *how many components read it?*

| State | Home | kmet examples |
|---|---|---|
| Read by ≥2 components | **Global**: app-owned domain atoms + subscriptions | agent-state, session-atom, config, theme, active-status-kind, messages |
| Read by 1 component (+ its children) | **Local**: `:state` map on the record / `with-let` | filter text, expansion, selection, draft, tick |
| Passed to a child as configuration | **Props**: re-applied by reconcile | labels, callbacks, indices, layout params |

### 3.1 Derived state — `hiccup/compute` (B-lite, not re-frame)

A derived-state mechanism over the atoms the app already owns. No
mega-store, no app rewrite, no separate namespace — `compute` lives in
the tree layer's namespace because one function doesn't earn its own:

```clojure
;; kmet.tui.hiccup — generic, knows nothing about kmet.app
(defn compute
  "Derived atom over DEPS: watches each, recomputes (F) on change,
   skips the write when the result is equal. Returns a plain atom."
  [deps f] ...)
```

Deps are listed explicitly — no dep *discovery*, so nothing has to be
extracted from `track-render` first (the auto-tracking upgrade is
deferred, see below). The returned atom uses the same watch machinery
as `track-render`: keyed watches, equal-value no-op. Derefs are normal
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
exactly when Phase C lands: replace setter-poking with pure `swap!`s
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

- Manual `tui-request-render` stays valid forever (idempotent); Phase C
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
  stays a small local diff (Phase A step 2)

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
forever" — dispose generalizes exactly that.

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
| Per-component reactions (full Reagent runtime) for *every* fn component | Requires Reagent-owned atoms (coverage/mixing — §2.4) or making the macro mandatory. All extensions are internal, so enforcement isn't the objection — the objection is zero benefit: fn bodies build small vectors (real costs cache-absorbed in leaves), caching can't prune the child walk (transparent-parent rule), and opt-in `defc` already delivers reactivity where wanted. `{:memo [deps]}` rejected in its favor. |
| `:children?`/`:children-key` tag-spec fields | Second children mechanism beside the tag table's `:container?` — one answer per question: the table says who takes children, the one generic `reconcile-children!` fills them (§2.2, §5). |
| Converting primitives to fn components | They're the host elements — that would be reimplementing `[:div]` as a React component. |
| Full re-frame store (global app-state atom + cursors) | App-layer rewrite; crosses the `kmet.app`/`kmet.tui` boundary; kmet's state graph isn't complex enough. B-lite (domain atoms + subscriptions) gets the value without the rewrite. |
| Stateless components + top-level connect | Prop-drilling is verbose; the connect still needs per-component re-derivation; Reagent's idiom is "ratoms anywhere". |
| `dsl/update!` (imperative child swapping) | Subsumed: a `when-let` in a tree + track!-driven re-derivation + reconcile handles it. |
| Re-invoking *expensive* work per frame with no caching | Plain fn bodies ARE batched-re-invoked by design — cheap by construction. What's rejected is uncached expensive work at frame rate: the transcript stays records (§3.3), defc caches hot bodies (§2.4). |
| `render` → tree in the protocol | Forces every render through compile+reconcile — the hot-path cost. The tree level belongs above the protocol, in the DSL. |
| Input propagation through ancestors | Would make dialogs trap keys declaratively, but breaks pi parity, complicates the input path (Kitty release events, IME); dialogs already trap manually. |
| Fine capability split (`IRenderable`/`IInputHandler`) | Moves no-op checks to call sites; `defcomponent` already hides the no-ops. Churn without gain. |
| `defc` as the default/only fn form | The original blanket rejection over-estimated cost (assumed a reaction scheduler; `track-render` already provides the semantics). With internal-only extensions the enforcement objection is gone too — what remains: mandatory defc forfeits plain-`defn` Form-1 for widgets whose per-frame re-derivation is free, and buys nothing over opt-in (same machinery, different default). Resolution: opt-in, Phase D, gated on `--debug` counters (§2.4). |
| Monolithic screen component (`defc` reading all app state) | Covered and correct — any change re-derives it — but coarsest legal granularity: full-screen body re-run per frame during streaming, structural `=` walks on large tracked collections, and mapping messages into elements inside it resurrects the rejected transcript rebuild at frame rate. Shape: small region-level `defc`s + records for the transcript (§2.4, §3.3). |
| `register!` registry API | Host elements are a closed set — hardcoded tag table in `hiccup.clj`; fn heads cover custom components. |
| Seed-once / `:structural` spec category | Three-way props/state/structural split has no consistent semantic (who re-writes the seeded value when the parent changes it?); all-props-live + equality no-ops covers it with two categories and one rule. |
| Per-child render isolation (error boundaries) at v1 | No real throwing-component case yet; the loop's crash policy (log + stop) is honest enough until one exists. Revisit when a component fn can plausibly throw per-frame. |
| Explicit-dep `{:memo [deps]}` / `(hiccup/memo deps f)` | Superseded by `defc`: automatic deps, branch-conditional reads handled, no listing burden, same no-scheduler/no-atom-ownership properties, reuses existing machinery. One reactive story instead of two (§2.4). |
| Auto-tracking subscriptions (`kmet.tui.tracking`) on the critical path | Explicit-dep `compute` needs no dep discovery — same equality no-op, none of the riskiest-refactor exposure. Deferred upgrade, only if branchy subs appear (§3.1). |
| Two public entry points (`dsl/component` + `dsl/root`) | `root` is component-plus-mounting; two names for one concept invites a tree that's compiled but never mounted. One entry point. |
| New lifecycle/children protocols (`ILifecycle`, `IChildrenContainer`) | `dispose` is `handle-input`-shaped: few real implementations, no-op for most — it belongs on `IComponent` with a synthesized default. Children dispatch on the closed tag table (`:container?`) through one generic fn. Zero new protocols (§5). |
| Keeping `IComponentKind` as a protocol | One consumer (chat_history's `kind-of`), and the messages-atom already carries `:role` — a protocol re-derives data the data layer owns. Kind-as-data stamped by `defcomponent` is simpler, serializable, and fails loudly on rename (§5). |
| Declarative input props (`:on-key`/`:on-click`) in trees | Input goes to the focused leaf only (pi parity, Kitty/IME); an `:on-key` prop would be a dead handler. Interactivity stays imperative: focus + widget records + `:ref` + keybindings (§5 input boundary). |

---

## 7. Migration plan

Four phases (A–C required, D gated); each ends with the full gate `bb
lint` + `bb format-check` + `bb test` + `bb test-ext`.

### Phase A — Boilerplate (the first goal)

0. **Protocol cleanup (trivial)** — delete redundant `(handle-input
   [_this _data] nil)` bodies in box/container/v_stack plus
   alt_screen_flash/dynamic_border/h_stack/scroll_view/spinner;
   `defcomponent` already defaults them. Retire `IComponentKind`:
   `defcomponent` stamps `:kind` as a record field instead of the
   `extend-type`; chat_history's `kind-of` becomes `(case (:kind child)
   …)`; protocol + `satisfies?` guard deleted (§5).
1. **`hiccup.clj`** — tag table (hardcoded, no registry): `{:ctor :primary
   :defaults}` per host element; `compile-element` + `hiccup/root`
   (construction-only; `reconcile!` comes with Phase B); loud validation
   (unknown-tag throw with did-you-mean, fn heads as values, children on
   a leaf tag). Tags for Text, Box, VStack, Container, Spacer.
2. **Props/state split — no deadline.** Migrate host elements to
   `:props` + `:state` atoms *one component at a time, only when its
   call sites are being converted to the DSL anyway* — the two changes
   merge into one coherent diff per component. No big-bang. Unmigrated
   components don't block adoption: their tag entry uses an **adapter
   ctor** that maps the uniform props shape onto today's fields —
   `(fn [props] (map->Text {:text-atom (atom (:text props)) …}))` —
   and is deleted when the component migrates. DSL-first never forces
   the split; this step is opportunistic forever, nothing downstream
   waits on it.

### Phase B — Reagent model (declarative presence)

3. **Dispose + children plumbing** — `dispose` added to `IComponent`
   (no-op default synthesized by `defcomponent`; containers delegate to
   children); tag table gains `:container?`; `reconcile-children!` as
   one generic fn in `hiccup.clj` (no new protocols); reconcile dispatches
   on the tag table, never field names.
4. **ComponentFn** — wrapper record in `kmet.tui.components`; two paths:
   plain fn heads batched (re-derive every pass), `defc` heads cached
   via track! (§2.4); binds `*comp*` and `*width*` for
   `let-state`/`on-dispose!`; `reconcile!`s children; `dispose` runs
   children first, then cleanups, clears `:state`; optional `--debug`
   per-frame counters.
5. **`reconcile!`** — keyed child diff, per component; duplicate keys
   throw; `:key`/`:ref` pseudo-props stripped before compile, refs
   filled on construct and cleared on dispose.
6. **Headless test surface** — `hiccup/render-lines` with unit tests
   for keyed reuse, dispose order, and the footgun list; most of B/C
   validates here before touching a terminal.
7. **`with-let`** in `macros.clj`, expanding to the `let-state`/
   `on-dispose!` runtime fns in the same namespace.
8. **Convert composition sites** in `interactive.clj` — dock, status
   container, dialogs — to fn trees mounted via `hiccup/root`. The
   status-container swap (clear/add/stop dance) becomes a `when-let` in
   a tree.

### Phase C — Subscriptions (kept)

9. **`compute`** in `kmet.tui.hiccup` — derived atom over explicit
   deps: keyed watches, equality no-op, auto-disposal under `with-let`.
   Unit-testable without the tracking extraction: no dep discovery, so
   `kmet.tui.tracking` is **off the critical path** (step 12).
10. **Frame scheduling** — scheduler hook var in `kmet.tui.macros`
    (no-op default), installed by `kmet.tui.core` on start / cleared on
    stop; `invalidate-cache` triggers it (§3.4). Gate: a bare `swap!` on
    a subscribed atom produces exactly one frame. Only then retire
    manual `tui-request-render` call sites, gradually (they stay valid).
11. **Mirror-plumbing removal** — move app updates to pure data;
    components compute their slices. `assistant-message-append-text!`
    etc. retire. Theme becomes a shared def'd compute
    (`(def theme-sub (hiccup/compute [theme-atom] identity))`); the
    constructor arg retires one component at a time (§3.2).
12. **Auto-tracking upgrade (deferred)** — only if branchy subs appear
    (deps that differ per branch, which explicit lists can't express):
    extract `kmet.tui.tracking` from `track-render` and give `compute`
    a zero-dep auto-recording form. Needs dedicated tests first. At
    kmet's scale (`get-in` slices), unlikely to trigger.
### Phase D — `defc`, the reactive fn form (gated)

13. **`defc`** — opt-in reactive fn form; macro in
    `macros.clj` expanding to defn + deref rewriting + `:reactive?`
    flag; ComponentFn's track! path reuses `track-render` verbatim.
    **Gate**: Phase C's `--debug` counters must show real body-churn
    waste first (live fn bodies re-running with unchanged deps at
    streaming cadence). Supersedes the `{:memo [deps]}` sketch (never
    built). Everything ships batched-by-default regardless — defc is
    an upgrade for hot components, never a migration.

Guardrails: tests in `test/kmet/tui/`, new namespaces registered in
`kmet.runner/all-namespaces`; clj-kondo hooks for `track!`, `with-let`,
and `defc`; cljfmt `:extra-indents`; ComponentFn on the
uncached-allowlist in test-caching-conventions for its plain path (and
the AGENTS.md reactive-cache section documenting both wrapper paths);
full gate at each phase boundary.

**The perf invariant, as a test**: an idle UI runs zero fn bodies.
Headless (§2.7): render a tree twice with no state change between — fn
invocation count must stay 0. This single assertion cements the whole
memoization contract (batched wrappers + defc caches + cached records +
equality no-ops); if it ever fails, something is invalidating spuriously and the
failure is caught in `bb test`, not in someone's scrollback.

---

## 8. Layer boundaries (unchanged)

```
kmet.app        : owns atoms, pure data updates (no component knowledge)
kmet.app.ui     : fn components (shared def'd computes, :state local)
                  + hiccup/root mount points
kmet.tui        : hiccup (tags/compile/reconcile, root, ref,
                  render-lines, compute), macros (track!, with-let,
                  let-state, on-dispose!, invalidate-cache),
                  ComponentFn (plain batched / defc reactive), protocols
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
2. **Batched default, `defc` opt-in** — macro is the only door to
   reactive reads; Phase D, gated on counters (§2.4).
3. **`with-let` day one** — sugar over `let-state`/`on-dispose!`; all
   three live in `kmet.tui.macros` (§2.5).
4. **Shared computes are def'd, per-instance under `with-let`;**
   auto-disposal when created in a render pass; registry only if
   dynamic registration ever appears (§3.1).
5. **Explicit-dep `compute`, in `kmet.tui.hiccup`** — no dep
   discovery; tracking extraction off the critical path (§3.1).
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
17. **Reagent's forms with a twist on Form-2** — plain fn = Form-1,
    `defc` = Form-2's reactivity, `defcomponent` = Form-3; raw-record
    splicing as fourth adapter form (§2.4).
18. **Frame-batching default; `defc` escape hatch** — full reactions
    blocked on coverage/mixing (owned atoms) or a mandatory macro;
    supersedes the memo sketch. Internal-only extensions remove the
    enforcement objection, not the zero-benefit one (§2.4).
