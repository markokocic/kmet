# kmet TUI — package documentation

`kmet.tui.*` is a generic terminal-UI library (a Clojure/Babashka port of
`@earendil-works/pi-tui`, extended with a Reagent-style component layer).
It knows nothing about chat, LLMs or sessions — the app layer (`kmet.app.ui.*`)
builds on it, and extensions consume the same namespaces.

This document is the authoritative usage reference for the package. Keep it
up to date whenever the described behavior changes.

## Contents

1. [Architecture overview](#1-architecture-overview)
2. [The Hiccup DSL](#2-the-hiccup-dsl)
3. [Reactivity](#3-reactivity)
4. [State handling](#4-state-handling--global--local--props)
5. [Lifecycle](#5-lifecycle)
6. [Frame scheduling](#6-frame-scheduling)
7. [Input](#7-input--imperative-by-design)
8. [Protocols](#8-protocols)
9. [Theming](#9-theming)
10. [Component catalog](#10-component-catalog)
11. [Debugging rendering](#11-debugging-rendering)
12. [Testing & performance invariants](#12-testing--performance-invariants)
13. [Layer boundaries](#13-layer-boundaries)

---

## 1. Architecture overview

Two layers, one rule: **records are the terminal's "DOM elements"; function
components sit above them**, exactly like React components above `[:div]`.

- **Host elements** (records built with `defcomponent`): Text, Box,
  Container, VStack, HStack, Markdown, Editor, Input, SelectList, … — the
  terminal's primitives.
- **Components** (plain fns): `(fn [props] tree)` — the composition layer.
- **Reconciler** (`kmet.tui.hiccup`): compiles trees to records and diffs
  them by key.
- **Root** (`hiccup/root`): the one constructor from a tree to a mounted,
  disposable record.
- **Reactivity** (`kmet.libs.reakt` + `track!`): dependency-discovering
  reactions over plain atoms; invalidation schedules the next frame.
- **Output**: every record caches its rendered lines per width; the frame
  loop emits only the diff against the previous frame.

End-to-end flow:

```
atom change → reaction dirty → queued → frame flush runs it →
   = -gated notify → invalidate + schedule frame → fn bodies re-run →
   tree → compile+reconcile (keyed, per level) → record tree →
   lines (each record caches) → line diff → terminal bytes
```

### Namespaces

| namespace | role |
|---|---|
| `kmet.tui.core` | TUI instance: create/start/stop, child list, focus, overlays, input listeners, flash, render loop with line diffing |
| `kmet.tui.protocols` | `IComponent`, `IFocusable`, `IEditorComponent` |
| `kmet.tui.macros` | `defcomponent`, `track!`, `with-let`, `invalidate-cache`, deref-capture runtime |
| `kmet.libs.reakt` | reactions/track/cursor/batching over plain atoms |
| `kmet.tui.hiccup` | tag table, compile/reconcile, `root`, `ref`, `compute`, `render-lines` |
| `kmet.tui.components.*` | host elements (see §10) |
| `kmet.tui.theme` | color/styling API, active theme atom, theme files |
| `kmet.tui.keys` / `keybindings` | key names, Kitty protocol decoding, keybinding manager |
| `kmet.tui.autocomplete` / `fuzzy` | editor autocomplete dropdown, fuzzy matching |
| `kmet.tui.utils` | text wrapping, visible width, truncation helpers |

---

## 2. The Hiccup DSL

### 2.1 Syntax — Hiccup vectors

Trees are plain data plus whatever code you want, evaluated once per
re-render:

```clojure
[:box {:padding-x 1}
 [:text "hi"]
 (when-let [s @status] [:status-line s])   ;; nil → skipped
 existing-component                        ;; records pass through
 ;; seqs get spliced — always key spliced children, or prepending an
 ;; item rebuilds every unkeyed sibling after it:
 (map #(vector :text {:key (:id %) :text (:content %)}) msgs)]
```

A tree becomes live only through `hiccup/root` (§2.6) — there is no other
public entry point.

Children rules:

| child | result |
|---|---|
| `nil` | skipped — this is the `when`/`when-let`/`if` support, free |
| string | compiled to a Text (the tag's `:primary` shorthand, §2.2) |
| record | passed through as-is (identity preserved; never disposed by reconcile — ownership stays with whoever created it) |
| seq | spliced (each element treated as a child) |
| stack-entry map (VStack/HStack) | passed through as-is |

Tags are keywords from the closed tag table (§2.2) or function heads:
`[status-area {:mode :normal}]` is valid Reagent-style usage. Two more
normalizer rules keep call sites terse:

- **Props map optional** — `[:v-stack child…]` compiles with `{}`; the map
  slot is needed only when props exist (`:key`, `:ref`, options).
- **A fn component may return a seq of roots** — spliced where the element
  sits (the fragment equivalent), for wrappers that must not introduce a
  Box/VStack node into layout.

Validation fails loudly: unknown tags throw with a did-you-mean suggestion
(`:tst` → did-you-mean `:text`), children on a leaf tag throw, duplicate
`:key`s throw, stack-entry maps outside a stack tag throw, keyword children
throw.

### 2.2 Host elements — the tag table

Host elements are a **closed set** — `hiccup.clj` hardcodes the tag → ctor
table (no registry). Custom composition uses fn heads `[my-fn props]`;
extensions never add host elements. Tags and props:

| tag | props | children |
|---|---|---|
| `:text` | `:text` (primary), `:padding-x` `:padding-y` (default 1), `:bg-fn` | none (leaf) |
| `:markdown` | `:text` (primary), `:theme`, `:padding-x`, `:default-style`, `:transform` | none (leaf) |
| `:spacer` | `:lines` (default 1) | none (leaf) |
| `:box` | `:padding-x` `:padding-y` (default 1), `:bg-fn` | yes |
| `:container` | — | yes |
| `:v-stack` | `:gap` | yes (entry maps allowed: `{:component c}`) |
| `:h-stack` | `:gap`, `:align` (`:stretch` default) | yes (entry maps allowed) |

`:primary` names the positional shorthand: `[:text "hi"]` compiles to props
`{:text "hi"}` merged over defaults.

Fn heads are fn **values**, never symbols — trees are built at runtime and
symbol resolution would couple the DSL to caller namespaces.

### 2.3 Compile + reconcile — the keyed diff

The reconciler is React's render pass, recursive and keyed — **one
reconciler per component, not global**:

```clojure
;; per component: diff desired tree against previous children
(hiccup/reconcile! children-atom tree)
```

Matching: the `:key` prop wins; fallback is match-kind (tag / fn value /
record payload / string). Reorder by key = reuse (like React), so stateful
subtrees (editors, `with-let` state, caches) survive reorders. Matched
children get their props re-applied wholesale — `(reset! (:props c) props)`
— so **every prop is live**: equal values no-op (memoized children for
free), changed values re-apply. Unmatched previous children are **disposed**
(children-first contract, §5.1).

Ownership rides the `:dsl/meta` stamp: everything the DSL constructs carries
it; foreign records spliced into trees never do and are never disposed.
Display leaves (Text/Markdown/Spacer/string) with changed props are rebuilt
rather than mutated — identity-free, their caches absorb rendering;
containers and fn components keep identity across passes. One mechanism
fills everything: containers are constructed empty and filled by the same
keyed diff through per-tag children lenses.

**Duplicate `:key`s throw at reconcile** — two spliced siblings sharing a
key makes reuse undefined; throwing beats a silently vanishing subtree.

### 2.4 Refs — the imperative escape hatch

Focus, editor text access and scrolling are imperative calls on concrete
records. A tree that declares such an element reaches its instantiated
record through a ref — a second pseudo-prop beside `:key`:

```clojure
(def editor-ref (hiccup/ref))

[:editor-container {:ref editor-ref}]

;; elsewhere — an event handler or effect, never a render body:
(tui/tui-set-focus t @editor-ref)
```

Rules:

- refs are created with `(hiccup/ref)`; reconcile fills them on construct
  and clears on dispose — treat as read-only;
- deref only outside render bodies (handlers, effects): nil until first
  reconcile constructs the element;
- one ref per element instance — sharing across two elements means
  last-mount wins.

### 2.5 Fn components — ComponentFn

A plain fn used as a tag head is wrapped in a ComponentFn record whose body
runs inside a reaction: dependencies auto-discovered at deref time, re-runs
queued to the frame flush, notification only when the output changes by `=`.
An idle UI runs zero bodies.

- **Props re-applied on reuse** via reset!; equal-value resets no-op.
- **Reconciliation is bounded**: per component's direct children — there is
  no whole-app vdom pass.
- **Dispose order is contractual**: children first, then own cleanups
  (§5.1).
- **Dynamic `hiccup/*width*`**: bound around bodies for truncation at the
  real width; a resize forces one re-derive of affected idle bodies, then
  they re-cache. `hiccup/*comp*` is the running ComponentFn itself.
- **Error contract**: a throwing component fn crashes the render loop
  (Throwable → render-crash.log → tui-stop) — loud beats silently wrong.

**The three forms** (Reagent's taxonomy):

| Reagent | here |
|---|---|
| Form-1: pure fn returning a tree | `(defn status-area [props] …tree)` — reaction-backed automatically |
| Form-2: local state | `with-let` bindings (§5.2) |
| Form-3: class components | `defcomponent` records (§3.2, §8) |
| — | raw records spliced into trees — the fourth form, for live instances owned elsewhere |

Bodies collecting **no tracked dependency** (only untracked reads or static
trees) re-run on every pass — batched semantics, uncached, never stale.
Mixed bodies must read reactive inputs through component-body derefs,
`tracked-deref`, computes or cursors (the coverage contract, §3.1).

**Granularity guidance** — don't build one giant screen component reading
all app state: any change then re-runs the entire body, tracked reads of
large collections pay a structural equality walk per change, and mapping a
big message list into elements inside one body resurrects a full-tree
rebuild at frame rate. Many small components reacting narrowly win; see §4
for where state lives.

Footguns (all documented in §5.2): keep fn bodies pure per pass, create
subscriptions once, hoist inline callbacks to named fns or stable values —
a props map rebuilt each frame containing fresh fn literals defeats
prop-equality memoization.

### 2.6 Mounting — hiccup/root

Trees enter the TUI through one constructor:

```clojure
(hiccup/root dock-component)     ; bare fn: shorthand for [fn {}]
(hiccup/root [:box {:padding 1} …])
(hiccup/root [[:text "a"] [:text "b"]])   ; seq of roots
```

Returns an IComponent: first render compiles/reconciles the tree, later
renders re-reconcile like any ComponentFn. Mount it anywhere a record is
accepted today (`tui-add-child`, `container-add-child`, `tui-show-overlay`,
stack entry maps) and call `dispose` when it leaves (overlay close,
shutdown). Ownership follows the container it was handed to; nothing else
retains it.

### 2.7 Headless rendering — trees are data, tests stay plain

Compilation is pure, so components are unit-testable without a terminal:

```clojure
(hiccup/render-lines [:box {:padding-x 0} [:text "hi"]] 40)
;; => ["hi"] — the exact lines the frame loop would draw
```

Assert on returned lines directly, or call twice across a state change and
diff — identical lines prove keyed reuse and cache hits held. No tty, no
sleeps: fast-path `bb test` material, never `^:slow`.

---

## 3. Reactivity

### 3.1 `kmet.libs.reakt` — reactions over plain atoms

A Babashka port of `reagent.ratom`'s semantics. There is **no custom atom
type**: Babashka seals `IWatchable`/`IReset` away from pure-source
implementations, so dependency capture rides the existing deref funnel —
`kmet.libs.reakt/tracked-deref`, through which every component render body
routes its reads via `track!`. Plain `clojure.lang.Atom`s ARE the tracked
inputs — plain atoms need no wrapper, so there is no `ratom` sugar.

API: `make-reaction` / `reaction` (macro) / `derive` (derived ref over
explicit deps) / `cursor`, `watch-ref` / `unwatch-ref` (reactions aren't
IRefs — core `add-watch` cannot take them), `add-on-dispose!`, `flush!`
(drain the batch queue), `force-run!`, `invalidate!`, `dispose!`,
`tracked-deref`, `changed?`.

Scheduling: a reaction whose deps change (by `=`) is marked dirty and
**enqueued**; `flush!` runs each dirty reaction once per pass — drained
from the render loop's ~16ms tick. Deref outside any reaction settles the
queue first and always answers the CURRENT value. Watchers fire only on
real output changes; sticky errors rethrow without re-execution until the
next dep change clears them.

Coverage contract — tracked reads are exactly:
(a) component render bodies via `track!` (automatic),
(b) explicit `macros/tracked-deref` calls in hand-written bodies,
(c) nested reaction/cursor derefs (automatic).
A bare `@plain-atom` inside a hand-written reaction body is UNTRACKED —
correct under the batched fallback (§2.5), just not narrow.

### 3.2 `track!` — the reactive render cache

Record components wrap their render body:

```clojure
(defcomponent Text nil [text-atom padding-x padding-y bg-fn cache]
  (render [this width]
    (track! this width ...)))
```

Every `@atom` read in the body is recorded; when any changes, the cache
invalidates automatically — setters become plain `reset!`/`swap!`, no manual
`(protocols/invalidate c)` calls. While all tracked values are unchanged the
cached result returns untouched. `defcomponent` generates the
cache-clearing `invalidate` method when the render uses `track!`; write an
`invalidate` method only for extra side effects (delegating to children,
requesting renders).

- **`track-deps`** declares dependencies inside a track! body whose *values*
  don't appear there but must still invalidate: `(track-deps @a @b)`.
- Atoms the render body itself mutates should be read through non-tracking
  helpers so they don't self-invalidate.
- **Do NOT use track!** for: transparent parents (Container, Box, VStack,
  HStack, ScrollView, ChatHistory — children change independently and the
  parent can't track that), time-animated output (spinners, status flashes —
  must render fresh every pass), and focused input widgets (editor/input).
  A child's internal state affecting output means the parent must deref it
  too.

### 3.3 Derived state — `hiccup/compute`

Derived refs over the atoms the app already owns — sugar over a reaction
whose body reads each listed dep tracked and applies F to their current
values:

```clojure
(defn compute
  "Re-derives when any dep changes by =, applying F to the deps' current
   values; equal results notify nobody." [deps f] ...)
```

Anything F reads through tracked channels joins the discovered set
automatically. Equal-value recomputation notifies nobody → fine-grained
invalidation for free, and invalidation schedules the next frame (§6) —
subscribing is enough to stay live, no manual request-render.

Two usage patterns over the one primitive:

```clojure
;; Per-instance — compute under with-let: created once, disposed with
;; the instance (automatic when created during a render pass):
(defn message [props]
  (with-let [content (hiccup/compute [(:messages-atom props)]
                          #(get-in % [(:idx props) :content]))]
    [:text {:text @content}]))

;; Shared — def'd top-level computes; (def ...) IS the registry:
(def agent-status-sub (hiccup/compute [agent-state] :status))
(defn status-line [_props] [:text {:text (str @agent-status-sub)}])
```

There is deliberately no `reg-sub`/`subscribe`. Create computes ONCE per
instance — one built bare inside a render body leaks a reaction per pass
(visible as `:computes` climbing in hiccup's `--debug` counters).

---

## 4. State handling — global / local / props

Three homes for state, decided by one question: *how many components read it?*

| State | Home | examples |
|---|---|---|
| Read by ≥2 components | **Global**: app-owned domain atoms + computes/subscriptions | session state, config, theme, messages |
| Read by 1 component (+ children) | **Local**: `with-let` bindings / record state fields | filter text, expansion, selection, draft |
| Passed to a child as configuration | **Props**: re-applied by reconcile every pass | labels, callbacks, indices, layout params |

The payoff: app code stays pure data — a `swap!` on a domain atom watched
by a compute replaces all find-component-and-poke-its-setter plumbing:

```clojure
;; app layer: pure update, no component knowledge
(swap! messages-atom assoc-in [idx :content] new-text)

;; view: the message component subscribes to its slice (§3.3 pattern)
```

**Hot-path carve-out**: the transcript is NOT a fn component re-deriving
from a message seq — that would be O(transcript) rebuild per token. It
stays records with instance storage; screens reference it as a splice/tag.

---

## 5. Lifecycle

### 5.1 dispose

`IComponent` has a `dispose` method (default no-op, synthesized by
`defcomponent`). Callers invoke it unconditionally when a component leaves —
overlay close, reconcile removal, shutdown; implementations must be
**idempotent**.

- **Order is contractual**: containers dispose children first, then run
  their own cleanups — a child cleanup may still read intact parent state.
- Hand-rolled implementors (reify/defrecord outside `defcomponent`) MUST
  include `dispose` — there is no universal default under SCI.
- `defcomponent` prepends track-watch teardown to every dispose: watches
  must never outlive the component ("zombie watchers").
- Timers/intervals belong in `dispose` — a dropped component must not keep
  a ticker invalidating forever.

### 5.2 Local state — with-let

Transient state for fn components (Reagent Form-2's good half):

```clojure
(defn timer [props]
  (with-let [start (system-time)]          ;; init: once per instance
    (str "elapsed: " (- (system-time) start) "s")
    (finally (stop-timer!))))              ;; cleanup: once at dispose
```

Bindings initialize exactly once per instance; the body re-runs every pass
with the same values. A top-level `(finally …)` is stolen as cleanup (runs
LIFO across nested with-lets).

Footguns:

- Fn bodies must be **pure per pass** — creation-time side effects go in
  `with-let` init, cleanup in `finally`.
- Subscriptions/computes are created **once** (under `with-let` or shared
  `def`s) — never bare in the body.
- `with-let` works only inside a component body (the wrapper binds the
  store); calling it from an async callback throws loudly.
- A TOP-LEVEL `(try … (finally …))` in the body is captured by the cleanup
  extractor (same footgun as Reagent's) — nest the try inside a let when
  you need both.
- Using one expansion site twice in one render pass throws — each instance
  needs its own element.

---

## 6. Frame scheduling

**A dependency change schedules the render** — the other half of the
reactive loop:

- All invalidation funnels through `kmet.tui.macros/invalidate-cache`;
  track!'s watches, generated invalidate methods and subscription teardown
  all reach it, and it fires the frame hook (installed by `tui.core` on
  start, cleared on stop; a no-op default keeps headless tests pure).
- Coalescing is free: `tui-request-render` sets an idempotent flag polled by
  the ~16ms loop; N invalidations between frames collapse into one.
- Equal-value no-ops stay no-ops end to end: a compute recomputing to the
  same value requests no frame.
- Manual `tui-request-render` stays valid forever (idempotent); keep it next
  to ordering-sensitive mutations (focus changes, scroll-to-end, overlay
  show/hide) and before mutations nothing else tracks yet.
- Time-animated components keep their own tick loops; the hook runs inside
  a watch on the mutating thread and must not throw.

---

## 7. Input — imperative by design

Input goes to the **focused leaf only** (`tui/tui-set-focus`; pi parity —
Kitty release events, IME and focus routing are machinery the tree never
sees). Consequences:

- **No declarative input props, ever**: a `:on-key` prop in a tree is a
  design error, not a missing feature. Interactivity = focus + widget
  records (Editor/Input/SelectList/SettingsList) + `:ref` + keybindings.
- Containers do not receive input; a Box exists for padding/background.
- Key names come from `kmet.tui.keys` (`keys/KEY-UP`, `(keys/ctrl "p")`,
  …); `kmet.tui.keybindings` maps binding IDs to resolved chords with user
  overrides and conflict detection.
- Widgets implement `handle-input`; dialogs trap keys manually around their
  focused editor.

---

## 8. Protocols

Exactly three, by design:

```clojure
(defprotocol IComponent            ; implemented for you by defcomponent
  (render [this width])            ; -> lines (seq of strings); required
  (handle-input [this data])       ; default no-op
  (invalidate [this])              ; default: cache clear (+ your extras)
  (dispose [this]))                ; default: watch teardown (+ your extras)

(defprotocol IFocusable            ; focus routing (input/editor/select/settings lists)
  (focused [this]) (set-focused! [this val]))

(defprotocol IEditorComponent      ; extension seam for alternative editors
  (editor-get-text [this]) (editor-set-text! [this text]) …)
```

Notes:

- Components needing extra protocols (e.g. IFocusable) use a separate
  `extend-type` form after the `defcomponent`.
- Message-style components carry their kind as DATA: `defcomponent Name
  kind [fields…]` stamps KIND as the record's first field; dispatch reads
  `(:kind component)`. There is no kind protocol.
- `render` always returns lines, never a tree — the tree level belongs
  above the protocol, in the DSL.

---

## 9. Theming

All styling goes through `kmet.tui.theme`; raw ANSI escapes are banned
outside `src/kmet/tui/` and `kmet.libs.terminal`.

```clojure
(theme/fg theme :primary text)     ; wraps, resets fg only (\u001b[39m)
(theme/bg theme :user-message-bg text)
(theme/bold text) (theme/dim t) (theme/italic t) …   ; attribute-specific resets
(theme/get-fg-ansi theme :accent)  ; raw escape for a known color (throws on unknown)
```

Attribute-specific resets (not catch-all `\u001b[0m`) make nested styles
compose correctly.

The active theme is a reactive input: `theme/theme-atom` (a plain atom).
Components subscribe through a shared compute — e.g. the app defines
`kmet.app.ui.subs/theme-sub` = `(hiccup/compute [theme/theme-atom]
identity)` — instead of receiving theme as a constructor argument; a palette
switch invalidates exactly the subscribed subtrees. Construction-time
snapshot reads (`get-current-theme`) remain valid.

Theme definitions are EDN files (`examples/themes/` for format);
truecolor/256-color modes are handled inside the theme module.

---

## 10. Component catalog

All under `src/kmet/tui/components/`, constructed via `make-*` fns (or the
DSL tags of §2.2):

| component | purpose |
|---|---|
| `text` | multi-line word-wrapped text, optional padding/bg |
| `truncated_text` | single-line truncated text |
| `markdown` | markdown renderer with syntax highlighting |
| `box` | padding + background wrapper (no input) |
| `container` | transparent child list |
| `stack` / `v_stack` / `h_stack` | vertical/horizontal layout, gaps, entry maps |
| `scroll_view` | viewport scrolling around a child |
| `dynamic_border` | border drawn around current content dimensions |
| `input` | single-line input widget (focusable) |
| `editor` | multi-line editor: wrapping, undo/redo, kill-ring, history, paste markers, autocomplete hooks (focusable) |
| `editing` | grapheme/cursor editing primitives behind the editor |
| `select_list` / `settings_list` | interactive lists (focusable) |
| `spinner` | animated indicator (time-animated — never cached) |
| `cancellable_loader` | loader with abort signal |
| `expandable_text` | collapsed/expanded long text (deref-aware caching) |
| `image` | inline image protocol rendering (kitty/iTerm style) |
| `alt_screen_flash` | alternate-screen takeover + restore |

Message-like app components (chat history, tool executions) live in
`kmet.app.ui.*`, not here — this layer stays generic.

---

## 11. Debugging rendering

### Print what a component renders

The fastest way to see what a component produces: render it headless and
print the lines —

```clojure
(require '[kmet.tui.hiccup :as hiccup])

;; any tree data …
(doseq [l (hiccup/render-lines [:box {:padding-x 1} [:text "hi"]] 40)]
  (println l))

;; … or a live record instance (records pass through compile untouched)
(doseq [l (hiccup/render-lines my-component 80)]
  (println l))
```

Notes:

- `render-lines` accepts one element vector, a seq of roots, or a single
  record; it returns exactly the lines the frame loop would draw.
- It is a ONE-SHOT inspection tool: DSL-owned roots are disposed after the
  call — don't reuse it as a second render path for mounted components.
- Lines carry ANSI styling; pipe through `cat -v` (or strip escapes) when
  eyeballing. For width math use `kmet.tui.utils/visible-width`, never
  `.length` — escape bytes count otherwise.
- Invalidation debugging: render before and after a state change and diff
  the line seqs — identical output proves keyed reuse and caches held (§2.7).
- Never `println` from inside a live TUI's render bodies or input handlers:
  stdout writes land mid-frame and corrupt the display. Use the logs below.

### Per-frame counters

Under `--debug`, hiccup exposes process-wide counters:

```clojure
(hiccup/counters)
;; {:bodies-run 2 :bodies-skipped 37 :constructs 0 :reuses 5
;;  :disposals 0 :computes 4}
(hiccup/reset-counters!)   ;; back to zero (tests)
```

Reading them: `bodies-run` climbing on frames where nothing the body derefs
changed means either an inline-callback trap (fresh fn literals in props,
§2.5) or broken equality; `computes` climbing frame over frame means a
compute created bare inside a render body instead of under `with-let`
(§3.3).

### Frame dumps & full-redraw reasons (env flags)

- `KMET_TUI_DEBUG=1 bb run` — every frame dumps `newLines` vs
  `previousLines`, viewportTop, hardwareCursorRow and size into
  `/tmp/tui/render-*.log` (pi: PI_TUI_DEBUG).
- `KMET_DEBUG_REDRAW=1 bb run` — appends one line per FULL redraw with its
  trigger reason to `kmet-debug-render.log` (cwd); a steady stream during
  normal streaming points at shrink/full-redraw churn.

### Crash + error logs

| file | written when |
|---|---|
| `kmet-crash.log` | a rendered line exceeds the terminal width — dumps all rendered lines with visible widths + the offending index; the frame truncates the line and keeps running |
| `render-crash.log` | a render body threw — full stack trace, then the TUI stops (loud-crash contract, §2.5) |
| `debug.log` | opt-in via `--debug`: lifecycle events (submit, cancel, agent turns) |
| `kmet.error.log` | unhandled top-level exceptions |

### When bytes look wrong but headless render looks right

Scroll-region/diff bugs are invisible at the lines level. Capture the
session's raw output — `scripts/tmux_capture.sh` or
`scripts/pty_capture.py` — and replay it through the minimal ANSI emulator:
`python3 scripts/term_dump.py out.raw` prints the frames (with colors) at
sync boundaries. See AGENTS.md ("Debugging scripts") for the exact
invocations.

## 12. Testing & performance invariants

- **Headless first**: `hiccup/render-lines` covers construction, keyed
  reuse, caching and invalidation without a terminal (§2.7). Real-terminal
  behavior (raw mode, query timeouts, subprocess spawns) belongs in
  `^:slow` tests.
- **Idle-UI invariant**: an idle UI runs zero fn bodies and zero reaction
  re-runs — render a tree twice with no state change between passes;
  invocation counters (§11) must stay flat. This pins the memoization
  contract (reactions + caches + equality no-ops).
- New test namespaces register in `kmet.runner/all-namespaces`.

---

## 13. Layer boundaries

```
kmet.app        owns atoms, pure data updates (no component knowledge)
kmet.app.ui     fn components (shared def'd computes, with-let local state)
                + hiccup/root mount points
kmet.tui        reagent, hiccup, macros, protocols, components — generic;
                no app/chat/session concepts; may depend on kmet.libs.*
kmet.libs.*     self-contained (terminal protocol lives here too)
```

`kmet.tui.*` must never require `kmet.app.*`, `kmet.modes.*` or
`kmet.ai.*`; app-specific components belong in `kmet.app.ui.*`.
