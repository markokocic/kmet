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
- **Reconciler** (dsl): compiles trees to records, reconciles by key,
  re-applies props.
- **Reactivity**: `track!` — already exists; a component fn is re-invoked
  when the atoms it derefs change.
- **Lifecycle**: `dispose` — new IComponent method.

The flow, end to end:

```
app atoms → defc fn (tracked) → tree → compile+reconcile (keyed, per level)
          → record tree → lines (each record caches) → frame diff → terminal
```

---

## 2. Core primitives

### 2.1 DSL syntax — Hiccup vectors

Trees are plain data plus whatever code you want, evaluated once per
re-render:

```clojure
(dsl/component
  [:box {:padding 1}
   [:text "hi"]
   (when-let [s @status] [:status-line s])   ;; nil → skipped
   existing-component                        ;; records pass through
   (map #(vector :text (:content %)) msgs)]) ;; seqs get spliced
```

Children rules:

| child | result |
|---|---|
| `nil` | skipped — this is the `when`/`when-let`/`if` support, free |
| string | compiled to a Text |
| record | passed through as-is (identity preserved) |
| seq | spliced (each element treated as a child) |
| stack-entry map (VStack) | passed through as-is |

Tags are keyword (registry) or a fn head: `[status-area {:mode :normal}]`
is valid Reagent-style usage.

### 2.2 Registry — `dsl/register!`

Each host element registers a small spec. Props are a flat set — there is
no reactive/structural distinction; reconcile re-applies everything via
the `:props` atom, and one-shot values follow the seed-once rule (see §4):

```clojure
(dsl/register! :text
  {:primary :text                          ;; [:text "hi"] → {:text "hi"}
   :props #{:text :padding-x :padding-y :bg-fn}
   :defaults {:padding-x 1 :padding-y 1}})
```

Fn components register as `{:component f}` — or skip registration entirely
and use fn heads.

**Every prop re-applies**: reconcile resets the component's `:props` atom
wholesale — `(reset! (:props c) props)` — there is no reactive/structural
distinction in the spec. One-shot/structural values (padding, sizes) are
copied into `:state` at construction (seed-once rule, see §4).

### 2.3 Compile + reconcile — `reconcile!`

The wrapper's `reconcile!` is React's render pass, recursive and keyed —
**one reconciler per component, not global**:

```clojure
(defn reconcile! [children-atom tree]
  (let [prev @children-atom
        desired (mapv compile-element (normalize tree))]
    ;; for each desired element:
    ;;   :key match   → reuse record, reset! its :props atom
    ;;   no match     → construct (fn tags → ComponentFn wrapper)
    ;; drop unmatched prev → dispose
    (reset! children-atom new-children)))
```

Matching: `:key` prop (pseudo-prop, stripped before compile) wins;
fallback is tag + position. Reorder by key = reuse (like React).

### 2.4 The ComponentFn wrapper

```clojure
(defcomponent ComponentFn nil [f props state children cache cleanups]
  (render [this width]
    (track! this width
      (binding [*comp* this]
        (let [tree (f @props)]              ;; re-invoked only when derefs change
          (reconcile! children tree)
          (render-children @children width)))
  (dispose [this]
    (doseq [f @cleanups] (f))
    (reset! state {})
    (doseq [c @children] (dispose c))))
```

Properties:

- **Per-component tracking**: the fn runs inside *its own wrapper's*
  `track!` scope, so derefs record against that wrapper — no bubbling up to
  ancestors.
- **Props re-applied on reuse** via `reset!`; equal-value resets no-op →
  memoized children for free.
- **Reconciliation is bounded**: per re-rendered component's direct
  children, not the whole app tree.

### 2.5 `defc` — the one required macro

`track!`'s deref rewriting is lexical, and a plain `defn` body compiles
separately. So fn components need a definition form that rewrites derefs:

```clojure
(defmacro defc [name [props] & body]
  `(defn ~name [~props] ~@(macros/rewrite-derefs body)))
```

`rewrite-derefs` stays private in `kmet.tui.macros` — `defc` is defined
in `macros.clj` itself, keeping all deref-rewriting machinery together
(decision #2). `defc` fns may deref app atoms anywhere (idiomatic
Reagent); plain `defn` fns are allowed but only read props (untracked).

### 2.6 Lifecycle — `dispose`, not `with-let`-as-macro

`IComponent` gains a `dispose` method, default no-op. Containers,
overlays, and the TUI call it when a child is removed. Component-local
state is already record fields; `dispose` is the missing cleanup half.

For fn components that need transient state, primitives (SCI-friendly,
plain fns):

```clojure
(defn let-state
  "Per-instance value for KEY, initialized once. Derefs inside this fn are
   NOT rewritten by track! — local state reads don't self-invalidate."
  [key init]
  (let [ls (:local-state *comp*)]
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
        body' (remove #(identical? cleanup %) body)]
    `(let [~@(mapcat (fn [[s i]] [s `(let-state '~s (fn [] ~i))]) bindings)]
       ~@(when cleanup [`(on-dispose! (fn [] ~@(rest cleanup)))])
       ~@body')))
```

Footguns (documented, matching existing track! conventions):

- **Render must never *write* local state** — writes inside render
  self-invalidate. Animation ticks come from timers, exactly like today
  (`status-indicator-start!` etc.).
- **Subscriptions are created once** (global registry or `with-let`),
  never in the render body — or a reaction leaks per re-render.

---

## 3. State handling — the combined model

Three homes for state, decided by one question: *how many components read it?*

| State | Home | kmet examples |
|---|---|---|
| Read by ≥2 components | **Global**: app-owned domain atoms + subscriptions | agent-state, session-atom, config, theme, active-status-kind, messages |
| Read by 1 component (+ its children) | **Local**: `:state` map on the record / `with-let` | filter text, expansion, selection, draft, tick |
| Passed to a child as configuration | **Props**: re-applied by reconcile | labels, callbacks, indices, layout params |

### 3.1 Subscriptions — `kmet.tui.reactions` (B-lite, not re-frame)

A generic subscription mechanism in `kmet.tui` over the atoms the app
already owns. No mega-store, no app rewrite:

```clojure
;; kmet.tui.reactions — generic, knows nothing about kmet.app
(defn reg-sub   "Register a named subscription" [k f] ...)
(defn subscribe "Shared reaction value-atom for k, created lazily" [k] ...)
```

`subscribe` returns a **plain atom** holding the derived value, updated by
watches (the machinery `track-render` already uses: keyed watches,
equal-value no-op). So derefs are normal tracked derefs, and when the
source changes but the derived value doesn't, the equality check no-ops →
**fine-grained invalidation for free**.

```clojure
;; kmet.app.ui — the view layer wires subs to app atoms
(reactions/reg-sub :agent-status (fn [] (:status @agent-state)))

(defc status-line [props]
  (let [status (reactions/subscribe :agent-status)]
    [:text {:text (str @status)}]))
```

Two subscription patterns:

- **Global registry** (`reg-sub` + `subscribe`): one shared reaction per
  key, app-lifetime, N components deref the same value atom. For shared
  state.
- **Per-instance** (via `with-let`): created once, disposed with the
  instance. For component-specific slices (e.g. one message's content).

### 3.2 The payoff: kill the mirror plumbing

Today the app layer *finds components and pokes their atoms*
(`assistant-message-append-text!`, pending-bash juggling). With
subscriptions, the app owns pure data and components subscribe to their
slice:

```clojure
;; app layer: pure data update, no component knowledge
(swap! messages-atom update-in [idx :content] str text)

;; message component: subscribes to its own slice
(defc message [props]
  (with-let [content (subscribe #(get-in @(:messages-atom props) [(:idx props) :content]))]
    [:text {:text @content}]))
```

No component lookup, no setter, no sync step. **App = pure state + pure
update fns; view = components subscribing to slices + view-local `:state`.**
This is the re-frame separation of concerns, and the strongest argument
for the global half.

### 3.3 Hot path carve-out

The transcript stays **records with instance storage** (ChatHistory
pattern) — never a fn component re-deriving from the message seq, or every
token append is O(transcript) tree rebuild + reconcile. What changes is
*how* a message learns its content: subscription instead of setter-poking.

Per-message subscriptions mean O(n) watch fan-out per append (999
recomputes to equal values, one invalidates) — fine at kmet's scale. If it
ever bites: per-message atoms, or keep the mirror atoms on that one path.
The design doesn't force it.

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
- **Seed-once rule**: one-shot/structural values (padding, sizes) are copied
  from `:props` into `:state` at construction. There is no `:structural`
  spec set — everything in `:props` re-applies uniformly, equality no-ops
  when unchanged.
- **Scratch stays out**: `tool_execution`'s last-component atoms, Image's
  id allocation — non-tracked scratch fields, never in `:props`/`:state`.

Why this is low-risk *now* but wasn't before: `track!` invalidates
automatically from derefs, so moving fields into maps requires no
hand-wired invalidation anywhere.

Payoffs:

- reconcile is one line for every component kind: `(reset! (:props c) props)`
- fn components have the same shape — ComponentFn is
  `[f props state children cache cleanups]`; `with-let` writes `:state`,
  reconcile writes `:props` — one local-state concept across styles
- ~30 bespoke setters + `defsetter`/`defgetter` collapse into
  `state/get`/`state/set!`
- both maps are snapshotable for tests

Costs (honest): migration covers props *and* internal state (still gradual,
per component, tied to its DSL registration — no big-bang); coarser
invalidation than per-field atoms (absorbed by per-width caches + frame
diff); state keys are looser than fields (destructure + document);
judgment calls at the props/state line are resolved by the seed-once rule.

---

## 5. Protocol rework

The current protocols: `IComponent` (render/handle-input/invalidate),
`IFocusable`, `IComponentKind`, `IEditorComponent`.

Assessment: the composability limits (global focus routing, natural-height
layout) are architecture choices, not protocol gaps. But the DSL exposes
three real protocol gaps, addressed with a **targeted rework** — no
render-contract revolution.

**1. `IChildrenContainer` — reconcile needs a contract, not duck typing.**

The reconciler must ask "can this component accept children, and how?"
Today that's field-poking (`:children` on Box/Container, `:entries-atom`
on VStack) — the duck typing `IComponentKind` was created to avoid
("Safer than key-based duck typing … which silently breaks when record
fields are renamed"):

```clojure
(defprotocol IChildrenContainer
  (reconcile-children! [this tree]
    "Compile TREE and diff against current children by :key"))
```

VStack/Box/Container implement it; leaves don't. Reconcile dispatches on
the protocol, never field names.

**2. `ILifecycle` — mount/dispose.** Reconcile-created components (a
spinner appearing in a tree) need a start hook; removed ones need
cleanup. Today that's manual (`status-indicator-start!`/`stop!` ordered
around swaps):

```clojure
(defprotocol ILifecycle
  (mount [this]   "Called after creation/reconciliation")
  (dispose [this] "Called before removal"))
```

`defcomponent` defaults both to no-ops, so nothing breaks.

**3. Delete redundant no-op bodies.** Box/Container/VStack hand-write
`(handle-input [_this _data] nil)` — but `defcomponent` already
synthesizes that exact no-op when omitted
(`(or (body-method body 'handle-input) '(handle-input [_this _data] nil))`).
Those lines are pure noise; removing them is free, zero-risk cleanup.

**Explicitly NOT doing** (see also §6):

- `render` returning a tree in the protocol — forces every render through
  compile+reconcile (the hot-path cost); the tree level belongs *above*
  the protocol, in the DSL. `render → lines` stays; `defc` trees compile
  down to it.
- Input propagation through ancestors (containers intercepting keys
  before the focused leaf) — breaks pi parity, complicates the input path
  (Kitty release events, IME); dialogs already trap keys manually.
- Fine capability split (`IRenderable`/`IInputHandler` as separate
  protocols) — moves no-op checks to call sites (`satisfies?` in the
  TUI's focus dispatch); `defcomponent` already hides the no-ops. Churn
  without real gain.

---

## 6. Explicitly rejected

| Idea | Why |
|---|---|
| Global vdom reconciliation | Per-component reconcilers only; terminal output already diffs at the line level underneath. |
| Converting primitives to fn components | They're the host elements — that would be reimplementing `[:div]` as a React component. |
| Full re-frame store (global app-state atom + cursors) | App-layer rewrite; crosses the `kmet.app`/`kmet.tui` boundary; kmet's state graph isn't complex enough. B-lite (domain atoms + subscriptions) gets the value without the rewrite. |
| Stateless components + top-level connect | Prop-drilling is verbose; the connect still needs `defc` tracking; Reagent's idiom is "ratoms anywhere". |
| `dsl/update!` (imperative child swapping) | Subsumed: a `when-let` in a tree + track!-driven re-derivation + reconcile handles it. |
| Render-fn Hiccup re-invoked per frame | That's per-frame whole-tree rebuilds — the expensive part in SCI. `track!` already bounds re-invocation to atom changes. |
| `render` → tree in the protocol | Forces every render through compile+reconcile — the hot-path cost. The tree level belongs above the protocol, in the DSL. |
| Input propagation through ancestors | Would make dialogs trap keys declaratively, but breaks pi parity, complicates the input path (Kitty release events, IME); dialogs already trap manually. |
| Fine capability split (`IRenderable`/`IInputHandler`) | Moves no-op checks to call sites; `defcomponent` already hides the no-ops. Churn without gain. |

---

## 7. Migration plan

0. **Protocol cleanup (trivial)** — delete redundant `(handle-input
   [_this _data] nil)` bodies in `box.clj`/`container.clj`/`v_stack.clj`;
   `defcomponent` already defaults them.
1. **`kmet.tui.reactions`** — extract the track+watch core from
   `track-render` into a shared `kmet.tui.tracking` (used by `track!` and
   `subscribe`); add `reg-sub`/`subscribe`. Unit-testable in isolation
   (equal-value no-op, dep re-recording on branch).
2. **`dsl.clj`** — `register!`, `compile-element`, `dsl/component`
   (construction-only; `reconcile!` comes with step 3/5). Spec shape
   (decided): `{:primary k :props #{...} :defaults {...}}` — no
   `:structural`, no field mapping; children via `:children?`.
   Registry specs for Text, Box, VStack, Container.
3. **Protocols for reconcile** — `IChildrenContainer`
   (`reconcile-children!`) on VStack/Box/Container; `ILifecycle`
   (mount/dispose, defaulted no-ops) — reconcile dispatches on the
   protocols, never field names.
4. **`defc` + `with-let`** in `macros.clj` (rewrite-derefs stays private);
   `with-let` expands to `let-state`/`on-dispose!` runtime fns in
   `kmet.tui.dsl`.
5. **ComponentFn** wrapper record in `kmet.tui.components`; wrapper
   implements `ILifecycle` (cleanups, local-state clear, child dispose).
6. **Convert composition sites** in `interactive.clj` — dock, status
   container, dialogs — to `defc` trees. The status-container swap
   (clear/add/stop dance) becomes a `when-let` in a tree.
7. **Props/state split**: migrate host elements to `:props` + `:state`
   atoms *one component at a time, only when its call sites are being
   converted to the DSL anyway* — the two changes merge into one coherent
   diff per component. No big-bang.
8. **Mirror-plumbing removal (B)**: move app updates to pure data;
   components subscribe to their slices. `assistant-message-append-text!`
   etc. retire.

Guardrails: new macros need clj-kondo hooks
(`.clj-kondo/hooks/kmet_macros.clj`) and cljfmt `:extra-indents`; tests in
`test/kmet/tui/`; full gate `bb lint` + `bb format-check` + `bb test` +
`bb test-ext` at the end.

---

## 8. Layer boundaries (unchanged)

```
kmet.app        : owns atoms, pure data updates (no component knowledge)
kmet.app.ui     : reg-subs + defc components (subscribe shared, :state local)
kmet.tui        : reactions (reg-sub/subscribe), tracking, dsl
                  (compile/reconcile), track!, ComponentFn, protocols
                  (IChildrenContainer, ILifecycle, IFocusable, IComponentKind)
kmet.libs.*     : self-contained (unchanged)
```

`kmet.tui.reactions` is generic — no app concepts. `reg-sub` wiring lives
in `kmet.app.ui`. `kmet.app` (non-ui) never imports `kmet.tui.*`.

---

## 9. Decisions (all resolved)

1. **Props/state split** — every component holds `:props` (tree-driven,
   reconcile is the only writer) + `:state` (self-driven,
   `state/get`/`state/set!`) + `:cache`. Seed-once rule for one-shot
   values; scratch stays out. Migration gradual, per component.
2. **`defc` lives in `kmet.tui.macros`** — all deref-rewriting machinery
   stays together; `rewrite-derefs` stays private; `dsl.clj` remains a
   runtime-fn namespace (SCI-friendly). `with-let` expands to runtime fns
   in `kmet.tui.dsl` (`let-state`/`on-dispose!`, bound via `*comp*`).
3. **Primitives + `with-let`** — `let-state` + `on-dispose!` are the
   runtime API; `with-let` is implemented as the ergonomic macro form on
   top of them (expands to a plain runtime call, same philosophy as
   `track!`).
4. **Global `reg-sub` reactions are app-lifetime** — accepted at kmet's
   scale; per-instance subscriptions (via `with-let`) for slices. Revisit
   only if dynamic sub registration appears.
5. **Extract `kmet.tui.tracking`** — `track-render`'s track+watch core
   (dep recording, keyed watches, equality-skip, mid-run invalidation
   detection) shared by `track!` and `subscribe`; no duplication.
   Riskiest refactor — needs dedicated tests before anything else uses it.
6. **Protocol scope confirmed** — `IChildrenContainer` + `ILifecycle`
   required; redundant no-op removal trivial; fine capability split and
   input propagation stay rejected.
