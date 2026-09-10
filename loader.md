# kmet Loader — context/classloader interface (design + implementation plan)

Goal: one portable **Loader** abstraction that gives kmet's extension system
real isolation on every host — bb, JVM Clojure and Jolt — and that can be
promoted to a standalone library afterwards. It is the answer to two open
items: Jolt issue **jolt-lang/jolt#912** ("Provide Classloader lite for Jolt":
per-context classpath, resolve-fn, isolation, unload) and the
**extension-isolation** workstream in `jolt-port.md` §B3 (a hard blocker for
the port).

Status: design locked; implementation staged (see §9). Nothing here is
implemented yet.

Related docs: `jar-ext.md` (extension artifact format — the loader it touches),
`jolt-port.md` §B3, `extensions/extensions.md` (the extension contract),
`src/kmet/tui/tui.md` (house style for a package reference doc).

---

## 0. Decisions locked (do not re-litigate in implementation)

1. **Protocol name `Loader`**, namespace `kmet.libs.loader`. Methods:
   `find`, `resolve`, `load`, `parent`, `unload!`. (`close` is the *host-view*
   spelling, see §6.4.)
2. **There is no hardcoded parent.** Delegation is a slot, implemented by a
   **resolve-fn**; `:parent` is sugar for one. Delegates are loaders
   themselves (`->loader` lifts a plain fn), so policies compose.
3. **The resolution body is generic**: *already-linked → delegate → locate
   (own roots/attachments) → miss*. The resolve-fn implements the **delegate
   step** only; it never links, installs or memoizes. Those are the generic
   body's job.
4. **Links are memoized, resources are not.** "Ask once, install the mapping,
   read the table thereafter" — a resolve-fn that answers differently at two
   moments is worse than none. Resources are re-walked every call (JVM
   semantics; caching them makes unload unobservable).
5. **`:denied` ≠ miss.** A blacklisted request raises; it must not fall
   through to another tier or to the ctx's own roots.
6. **Two resolution tiers**: *linkage* is directed by the **defining ctx**
   (lexical — code carries its loader, like the JVM's constant pool through
   the defining loader); *dynamic loading* (`require`/`eval` reached
   indirectly, REPL forms, framework resource probes) is directed by the
   **ambient ctx** (thread parameter, `with-loader`, TCCL analogue).
7. **Dispatch follows the value.** Protocol/`isa?`/`instance?`/record shape
   resolve through the ctx that *created* the value, regardless of which
   ctx's code is running. (JVM: virtual dispatch through the receiver's
   loader; static linkage through the referrer's.)
8. **Requests are typed**: `:ns`, `:var`, `:class`, `:resource` — because
   real loaders use different order and policy per kind.
9. **sci is the portable baseline on all three hosts**; native backends are
   upgrades layered on the same protocol, never a prerequisite. The same sci
   backend file serves bb/JVM/Jolt with a host seam for where bytes come from.
10. **`unload!` follows the Java `close` + OSGi `stop` precedent**: no new
    loads through the loader, already-resolved definitions stay live,
    acquired host resources are released, teardown errors are *reported*
    (never thrown), call is idempotent. Usage *after* unload throws. No
    unmapping, no refcounting; reclamation is "drop the references".
11. **Never call a user resolve-fn under a lock**; per-ctx, per-request
    in-flight marks (the JVM's `getClassLoadingLock` analogue) prevent
    recursive/double loads.
12. **Vocabulary is fixed**: `loader` = the abstraction; `context` = the
    host's own object underneath (sci ctx, jolt tables), reached via an
    escape hatch; `ClassLoader` = the host's real classloader, where one
    exists, reached via `as-classloader`.
13. **Hits are pure data; opening is the loader's job.** `find` locates and
    can be cached/compared/logged; nothing is read, compiled or opened until
    `load`, and a caller holding a hit can pass it to `load` to skip
    re-resolution (§4.4). `load` is the only place that reads.
14. **`unload!` never blocks** (the Java `close` contract); it records what
    it raced instead (§5). Blocking is an opt-in drain for later, if a
    consumer asks.
15. **Diagnostics**: `unloaded?` is the one behavioral predicate;
    `(status l)` is a map documented as implementation-visible (§4.5). No
    lifecycle enum — a transient `:unloading` state is only observable from
    another thread and any test for it is flaky.

---

## 1. Vocabulary

| word | means | where it appears |
|---|---|---|
| **loader** | the portable abstraction: resolve/link/find resources/unload | `Loader`, `(loader/…)` |
| **resolve-fn** | one implementation of the *delegate* step: answers what to find and where | ctx opts |
| **context** | the host object underneath (sci ctx, jolt ctx) | `(context l)` escape hatch |
| **ClassLoader** | a *real* host classloader, where the host has one | `(as-classloader l)` host view only |

`ClassLoader` never names the protocol. That keeps "ClassLoader-shaped, not
ClassLoader-compatible" enforceable rather than aspirational: you cannot
accidentally believe you have `defineClass` when the word only appears after
you have explicitly asked for a host view.

---

## 2. The resolution algorithm

```
linked?(ctx, req)                 ; the link table — what `resolve` answers from
  → delegate(ctx, req)            ; = resolve-fn (parent, pool, allow/deny, …)
  → locate(ctx, req)              ; own roots / attached artifacts (a hit)
  → miss

;; public mapping: `find` = linked? + delegate + locate, WITHOUT installing;
;; `resolve` = linked? only; `load` = the whole algorithm + read + initialize.
```

| step | who | memoized? |
|---|---|---|
| already linked in this ctx | generic body | yes (the link table) |
| delegate | **resolve-fn** (user code) | per §0.4: the *answer* is consulted once and installed |
| locate own roots | generic body via ctx's attached sources | yes, once linked; the **hit is data** (§4.4) |
| resources | delegate → own roots, in order | **no** — re-walked every call |

The JVM's own `ClassLoader.loadClass` is exactly this shape —
`findLoadedClass` → parent → `findClass` — verified: a `proxy` overriding
`findClass` is not consulted when the parent can answer, and is consulted for
a name only it can answer. Our design is that skeleton with the delegate
generalized from "a parent" to "a resolve-fn".

### What a resolve-fn answers, per kind

Answers are **hits** — data, never opened (§4.4).

- `:ns` → the source's **location** (file path / artifact entry) or
  "already linked in another loader" (by-reference delegation, §4.3)
- `:var` → the **var cell** (shared by reference — this is how
  `kmet.extension`, `kmet.tui.*`, `kmet.libs.*` are injected today)
- `:class` → a class registration/token (jolt) or a real `Class` (JVM)
- `:resource` → the resource's **location** (a URL or an embedded entry
  name — jolt has two kinds, and both open on demand); the loader wraps it
  with `open-hit`

### Policies (combinators, all trivial)

| combinator | meaning |
|---|---|
| `(->loader f)` | lift a plain resolve-fn into a delegate loader |
| `(delegating a b)` | try a then b (needs a total resolve-fn) |
| `(allow l #{'x})` / `(deny l #{'y})` | whitelist/blacklist over a delegate → `:denied` |
| `(self-first l #{'ns.prefix})` | own roots first for those names, delegate still consulted |
| `(isolated)` | `(constantly nil)` — hermetic; the root is not visible |
| `(pool [l1 l2])` | sibling sharing |
| `(url-search roots)` | the URLClassLoader analogue |

---

## 3. The two tiers

**Linkage — defining ctx (lexical).** Code compiled for ctx1 resolves
through ctx1 for its whole life, whoever calls it and on whatever thread.
This is the load-bearing rule: a function defined in ctx1 that lazily
`require`s something minutes later does it **in ctx1**, even when the host's
event loop calls it. A callback registered by ctx1 and driven by the root
still runs in ctx1. A macro defined in ctx1 expands in ctx1.

**Dynamic loading — ambient ctx.** Requests with no lexical home (`require`
reached indirectly, `eval`, REPL forms, framework resource probes) follow a
thread parameter (`with-loader`), inherited by threads and fibers. This is
the TCCL analogue and the weaker, best-effort tier.

On the JVM the same split exists: linkage goes through the defining loader
(constant pool), `RT.load`/`require` use `baseLoader`/TCCL.

**Limit to document, not fix**: dynamic loading reached through a value
(`(apply require […])`, a var holding `require`, `requiring-resolve`) cannot
carry the lexical ctx; it falls back to the ambient one. Emitted call sites
pass the ctx explicitly where the op is statically recognizable.

---

## 4. API

### 4.1 Protocol

```clojure
(ns kmet.libs.loader
  (:refer-clojure :exclude [find resolve load]))

(defprotocol Loader
  (find    [l req])   ; ordered hits, [] on miss, throws on :denied — LOCATES only
                      ; (no read, no compile, no open, no install); hits are data
  (resolve [l req])   ; the linked definition for this ctx from the link table,
                      ; or nil — does NOT read source, does not install
  (load    [l req])   ; resolve (or accept a hit) + READ + initialize + link;
                      ; returns the handle; the only method that reads
  (parent  [l])       ; the delegate loader, or nil (bootstrap/root)
  (unload! [l]))      ; idempotent, non-blocking; returns a report map;
                      ; never throws for teardown errors
```

The arity split *is* the semantics: `find` = `findClass`/`getResource`
(probe, no side effects), `resolve` = `findLoadedClass` + the loaded-classes
record (memoized definition, no I/O), `load` = `loadClass` + `RT.load`'s "also
evaluate". Someone who knows `ClassLoader` guesses all three correctly.

### 4.2 Constructors

```clojure
(loader/root)                       ; the host: today's global world, as a loader
(loader/classpath roots)            ; (url-search …) — n roots, in order
(loader/isolated)                   ; hermetic
(loader/delegating a b)             ; composed delegates
(loader/allow host #{'kmet.tui})    ; policy over a delegate
(loader/self-first host)
(context l)                         ; host escape hatch (sci ctx, jolt ctx)
(unloaded? l)                       ; post-unload predicate (§5)
(status l)                          ; diagnostics map (§4.5)
(open-hit l hit)                    ; hit → opened handle (§4.4)
(as-classloader l)                  ; host view where the host has one
```

### 4.3 Request and answer shapes

```clojure
;; request
{:kind :ns|:var|:class|:resource   ; required
 :name "clojure.string"            ; required (string, not symbol — hash-stable)
 :load? false}                     ; :ns only: load exactly like require, or load one namespace alone

;; hits (find → vector, ordered; first-wins for the singular forms)
;; DATA ONLY — no open streams, no closures, no compiled artifacts (see below)
{:kind :ns     :file "/roots/a/b.clj" :loader <who answered>}
{:kind :var    :cell <var-cell>}
{:kind :class  :registration {:class "java.time.Instant" :statics … :ctors …} :loader <l>}
{:kind :resource :url "file:/roots/a/cfg.edn" :loader <l>}

;; denied
(ex-info "…denied by policy…" {:loader/denied true :kind :ns :name "…"})
```

`:loader` on a hit exists for provenance/debug and for the cross-ctx
identity rules (§6). A `:var` hit carries the **cell**, so sharing is by
reference and `identical?` holds across ctxs — that is what the current
sci `:namespaces` injection relies on and it must not regress.

Resource hits name a **location** (`:url` or an embedded entry); the loader
turns it into an opened handle on demand, so nothing holds a stream or a
file handle between calls. An earlier draft put the opener fn *in the hit*
(`{:open (fn [] …)}`); that is rejected (§12.4) — a closure in a hit is not
printable, comparable or serializable, and it defeats the AOT/read-note
bookkeeping the opener must run. Hits stay data; the loader owns opening.

### 4.4 `find` locates, `load` reads

`find` finds *where* the source is; it does not read, compile or open it.
`load` does that, and is the **only** place a read happens:

```clojure
(load l req)   ; resolves (or reuses) the hit, reads, initializes, links
(load l hit)   ; accepts a hit directly — skips re-resolution, closes the
                ; TOCTOU window between find and load
(open-hit l hit)  ; resources: hit → an opened handle (stream/opener), or nil
```

Consequences, all of them good: hits are printable and comparable; errors
surface at `load`, where a read actually failed, rather than at a probe;
the loader's own **construction** is where eager validation lives (an
unreadable jar or a missing root fails at `(loader/classpath …)`, not at
first load); and the AOT read-note bookkeeping (jolt) stays inside the
loader instead of leaking into data.

This is also what jolt already does internally — `find-ns-file` locates,
`ldr-read-source` reads; `resolve-resource` returns a URL that opens on
demand — so the native backend maps onto it without rework.

### 4.5 `status` (diagnostics only)

```clojure
(status l)
;=> {:loader-id "ext:foo#3" :roots ["/w/foo/src"] :parent-loaded? true
;    :loaded-namespaces 12 :in-flight 1 :unloaded? false
;    :delegate <the parent's status, one level>}
```

The map is explicitly **implementation-visible**: keys may be added freely,
and no behavior may depend on a key that is not in this document. It exists
for debugging ("which loader still holds this namespace?") and for the
conformance suite. The only *behavioral* predicate is `unloaded?`.

---

## 5. `unload!` semantics

Precedent-checked against both systems:

- **Java `URLClassLoader.close`**: "no longer be used to load *new* classes or
  resources that are defined by this loader"; parents still accessible;
  "any classes or resources that are **already loaded**, are still
  accessible"; closes files it opened; "calling close on an already closed
  loader has **no effect**"; rethrows the first `IOException` with the rest
  suppressed.
- **OSGi** splits it in two: `stop` = deactivate (activator stop, framework
  auto-unregisters services/listeners, state → RESOLVED, restartable; bounds
  the wait and throws `BundleException` on timeout); `uninstall` = terminal
  UNINSTALLED while **"capabilities provided by the old in use bundle wirings
  must remain available … until the bundle is refreshed"**. A teardown
  failure during uninstall fires an ERROR event and uninstall still
  continues.

Both say the same thing, and it is the opposite of "yank the namespace out
from under existing code":

> unload = **no new loads through this loader** + release acquired host
> resources + report teardown errors. Already-resolved definitions stay live;
> reclamation is whoever-holds-references' business.

That is also the right behaviour for extensions specifically: an in-flight
agent turn, running timer, or async callback registered by the extension
keeps working after `/reload` swapped in the new revision (OSGi's
in-use-wirings guarantee).

```clojure
;; Idempotent. Never throws for teardown failures — reports them.
;; NEVER BLOCKS: it marks closed first (so new loads fail fast) and lets an
;; in-flight load finish; "finish" means it may complete into a closed
;; loader, which is well-defined below.
{:unloaded true      ; postcondition held: no new loads, resources released
 :already  false     ; true on the second call
 :released {:namespaces 12 :resources 3 :registrations 5}  ; best-effort counts
 :in-flight 1        ; loads still running when the mark was set
 :raced    true      ; true iff any load was in flight (see below)
 :errors   []}       ; teardown errors, in order (Java's suppressed, as data)
```

- **Report, don't throw.** Symmetric with `load-extension!`'s
  `{:extension … :error …}`, which `/reload` already collects and renders.
- **Never blocks** (decision 14). Java's own contract is "if another thread
  is loading a class when close is invoked, then the result of that load is
  undefined"; OSGi's bounded wait is rejected here because on jolt a wait
  would park inside `loader.ss`'s claim protocol (§7), and because kmet's
  `/reload` already refuses to run while the agent is busy
  (`interactive.clj` "Wait for the current response to finish before
  reloading"), which serializes the extension path by construction.
- **An in-flight load completing into a closed loader is defined, not
  undefined**: the load finishes and its definitions stay usable
  (already-loaded resources remain accessible, per Java), but it does not
  re-open the loader — the next `find`/`resolve`/`load` still throws.
  `:raced` exists so a caller that cares can detect it.
- **But usage after unload throws** (OSGi's rule, better than the JVM's
  `NoClassDefFoundError`/empty enumerations): `find`/`resolve`/`load` on an
  unloaded loader → `IllegalStateException`.
- Two things deliberately **not** promised: a "live references remaining"
  count (not computable on jolt's var cells; a lie on at least one host) and
  a boolean return (`nil` loses diagnostics, `false` implies unload can
  fail — it cannot; the postcondition always holds).
- **Opt-in drain, later**: `{:drain-ms N}` if a post-promotion consumer asks.
  It needs its own analysis against jolt's non-recursive loader mutex and
  in-flight claim marks before it can be offered (it must never wait while
  holding loader state).
- Ordering, matching OSGi: **shutdown → deregister → `unload!`**. kmet's
  existing `unload-extension!` already does exactly this.

Mapping:

| OSGi | Java | here |
|---|---|---|
| `BundleActivator.stop` | — | extension `shutdown` (called before unload) |
| `Bundle.stop` (deactivate/unregister) | `URLClassLoader.close` | `unload!` |
| `Bundle.uninstall` (terminal, remove storage) | — | out of scope v1 (`remove!` later if a loader owns files) |
| `Bundle.getState` | — | `(unloaded? l)` + `(status l)` |
| in-use wirings stay live | "already loaded … still accessible" | same rule |

---

## 6. Hosts and backends

| host | sci backend | native backend | what native adds | what native cannot do |
|---|---|---|---|---|
| **bb** | ✓ (what kmet does today) | ✗ | — | `proxy` whitelist rejects `java.lang.ClassLoader`; `URLClassLoader`/`DynamicClassLoader` ctors throw `MissingReflectionRegistrationError`; `Thread.setContextClassLoader` throws `ClassCastException` (verified) |
| **JVM Clojure** | ✓ | ✓ `proxy [ClassLoader]` | real `Class` objects, `defineClass`, jar handles, `Class/forName(name,false,l)` | **no namespace isolation** — `clojure.lang.Namespace`/Var tables are per-runtime (needs a second `clojure.jar`, which breaks value identity across the boundary) |
| **Jolt** | ✓ (`vendor/sci`; `make sci` 412/424, `scifunctional` green) | ✓ native ctx | compiled (not interpreted) extensions; full ns+class+resource isolation; unload | no `defineClass` bytecode path — a class is a name + registration; boot `java.*` classes stay global |

**sci is the portability baseline, not a fallback**: every host can run it, so
every host gets the same semantics for free. The generic backend is one
implementation with a host seam for *where bytes come from*
(`babashka.classes`/resource on bb, classpath on the JVM,
`jolt.host/ns-source` on jolt) and the **share list** supplied by the app
layer (the lib must not know `kmet.extension`/`kmet.tui.*`/`kmet.libs.*`).

Verified JVM facts (probed, not assumed): `proxy [java.lang.ClassLoader]`
works and is the only viable route — `defineClass` is protected/final and
**not reachable via Clojure reflection** (`No matching method defineClass`),
so a Java shim (or `DynamicClassLoader`) is required for that one method;
overridden `getResource`/`getResources` are seen by `clojure.java.io/resource`
and `Class/forName(name, false, l)`.

### 6.1 Jolt native backend (the #912 path)

Jolt already has the *shape*: `the-classloader` is a `jhost` record with a
registered method table (`getResource`, `getResources`, `getParent` → nil,
`getResourceAsStream`), `getSystemClassLoader`, `clojure.lang.RT/baseLoader`
and `Thread.getContextClassLoader` all return it, `java.lang.ClassLoader` is
in the class hierarchy, and `resolve-resource` is the single resource funnel
walking `(get-source-roots)`. It is one global object; the work is making it
per-ctx and wiring the compiler to it.

Three-step evolution (M0–M4 in §9.4): indirection → contexts + delegation →
classes per ctx → unload → host API. Two design points fixed here:

- **Defining-ctx capture.** Var refs are already ctx-correct for free
  (`(jolt-var "ns" "name")` is hoisted per def and runs at eval time inside
  the ctx, so the cell is pinned). Closures that need the ctx capture it in
  the existing per-def const pool; class-static call sites take it as a
  hoisted argument. Screen on the IR (the way the existing const-pool
  wrapper is screened) so plainly-arithmetic closures pay nothing.
- **Boot vs application classes.** `java.*` implemented by the runtime stays
  global (shared by delegation — as on the JVM, where `java.util.Base64`
  cannot be two things). Only RFC 0014 `:jolt/provides` classes and
  `deftype`/`defrecord` types go per-ctx, including the claim tables and
  latches. Cross-ctx type/class tokens are **deliberately unequal** (a
  v1 record instance is not a v2 record instance) and must be pinned in
  `test/conformance/known-divergences.edn`.

### 6.2 Per-ctx deps resolution

**Not the loader's job.** A loader receives its roots/sources; resolving a
`deps.edn` to roots is a host seam: `borkdude.deps` on bb/JVM (today's
`closure-jars`/`jars-for`), `jolt.deps/resolve-deps` on jolt (which already
returns `{:roots … :natives … :provides … :libs …}` per graph). The loader
backend takes an injected resolve-deps fn. Version-qualified extraction dirs
already make v1/v2 coexist on disk in both systems.

### 6.3 Cross-ctx identity rules (write down, don't discover later)

- Two ctxs loading "the same" class/type ⇒ distinct tokens; `instance?`/`=`
  across them is **false** on purpose (JVM: `ClassCastException`).
- Var cells from a *shared* loader are the same object (by-reference
  injection relies on it).
- Dispatch follows the value (§0.7).

### 6.4 `as-classloader`

Where the host has a real `ClassLoader`, expose one: `loadClass` → `find` +
`load`; `getResource(s)`/`getResourceAsStream` → `find` then `open-hit`;
`getParent` → `(parent l)`'s facade; `close` → `unload!`. On the JVM this is
a `proxy [ClassLoader]`. On jolt, the per-ctx `jhost` loader with the method
table above, which also makes `(io/resource n loader)`'s currently-ignored
2-arity argument start meaning something, and keeps
`(take-while identity (iterate #(.getParent %) l))` terminating.

### 6.5 Which sci artifact (jolt)

bb has `sci.core` built in — nothing to add. Jolt needs one of: the maven
`org.borkdude/sci` (unverified that the *published jar* loads on jolt — the
`make sci` gate proves the **vendored** copy only), a git pin (kmet already
pins two jolt-lang git coordinates in `deps.edn`, so this is house style),
or vendoring sci into kmet. **Pick the git pin**, matching the revision
family the sci gate exercises, and verify early — it is the one unproven
assumption in Phase 1. `kmet.libs.loader.sci` carries the requirement in its
own namespace file, so a consumer that requires only `kmet.libs.loader` never
loads sci at runtime (Clojure namespace laziness); the dep is classpath-only
for them.

---

## 7. Concurrency and lifecycle rules

- **Never call a resolve-fn under a lock** — it may `require` into the same
  ctx (cycle). Per-ctx, per-request in-flight marks; a nested load on the
  same thread must be detected, not deadlocked. Reuse the loader's existing
  claim protocol on jolt; `getClassLoadingLock` semantics elsewhere.
- **A ctx is inherited** by threads/fibers spawned inside it (ambient tier).
  Linkage needs nothing — it is lexical.
- **Unload does not unmap** (§5). Drop references; the host reclaims.
- **State images / serialized closures**: a closure that travels through a
  state image is a name reference, not a value. In a multi-ctx world an
  image must record its home ctx, or cross-ctx restore must be refused.
  (Jolt-only concern; cheap to enforce early.)

---

## 8. Conformance suite (this list *is* the spec)

Host-agnostic, written against the protocol; must pass on every backend.

1. **v1/v2 isolation** — two ctxs, one mvn lib at v1 and v2: `ctx1/foo` ≠
   `ctx2/foo`, both correct.
2. **Hermetic** — `(isolated)`: a name the root has must **not** resolve.
3. **Shared by reference** — delegate a var: the **same cell** comes back
   (`identical?`), not a copy.
4. **Deny ≠ miss** — denying a name raises, and does not fall through to the
   ctx's own roots.
5. **Self-first** — a ctx shadows a lib its delegate also has, for the
   declared prefix only.
6. **Composed delegates** — host + pool; `(parent …)` walks the graph.
7. **Unload** — post-condition holds (no new loads), already-resolved
   definitions still work, second `unload!` is a no-op, usage after unload
   throws, teardown errors are reported not thrown.
8. **Resources** — `find`/`open-hit`/`getResource(s)`/`getResourceAsStream`
   against a ctx root; `RT/baseLoader` (or the host equivalent) inside the
   ctx resolves to the ctx's loader; the 2-arity `io/resource` honors the
   loader.
9. **Defining-ctx inheritance** — ctx1 defines `f` that lazily requires
   `lib1`; ctx2 (with a different `lib1`) *calls* `f`; assert `f` saw
   **ctx1's** `lib1`.
10. **Dispatch follows the value** — a value created in ctx2, passed to ctx1
    code, still dispatches through ctx2's type tables.
11. **Hits are data** — `find` opens nothing (a hit survives `pr-str` and `=`;
    probing a loader leaves no file handles), and `(load l hit)` on a hit
    from `find` produces the same result as `(load l req)`.
12. **Eager construction, lazy load** — a loader built on an unreadable
    root/jar fails at the constructor; a *load* that reads a file that
    disappeared after `find` fails at `load`, not silently.

Cases 1–8, 11 are expressible on bb against sci **today**, which is the
point: pin the semantics before any runtime work, the way the corpus does
for `clojure.core`.

---

## 9. Implementation plan

Order follows the user-visible payoff, not the elegance: the protocol + sci
backend deliver extension isolation on all hosts first; native backends are
upgrades that must satisfy the same suite.

### Phase 0 — protocol, policies, suite (no kmet integration yet)

- `src/kmet/libs/loader.clj` — protocol, request/answer shapes, combinators
  (`->loader`, `delegating`, `allow`, `deny`, `self-first`, `isolated`,
  `pool`, `url-search`), `with-loader`/`current-loader`, link table,
  in-flight marks, generic `find`/`resolve`/`load` body, `open-hit`,
  `unloaded?`/`status`, `unload!` report.
- `src/kmet/libs/loader/memory.clj` (ns `kmet.libs.loader.memory`) — an
  in-memory loader over a source map, used by the suite and as the demo
  loader (no sci yet).
- `test/kmet/libs/test_loader.clj` (ns `kmet.libs.test-loader`) — cases 1–8,
  10 (case 9 needs a code backend). Register in `kmet.runner/all-namespaces`.
- Gate: `bb test-changed`, `bb lint-changed`, `bb format-check-changed`.
- Constraint: `kmet.libs.test-self-contained` must stay green — no app deps.

### Phase 1 — sci backend + kmet extension wiring (all hosts, sci)

- `src/kmet/libs/loader/sci.clj` (ns `kmet.libs.loader.sci`; the file+dir
  layout already has precedent in `kmet.app.ui`) — a Loader whose generic
  body delegates
  compile/eval to sci (`:load-fn`, `:namespaces`, `:classes`), with the
  **share list injected** by the caller. Requires `sci.core` only where the
  dep exists (bb built-in / JVM maven / jolt `vendor/sci` local root);
  document the reader-conditional.
- `src/kmet/app/extensions.cljc` — replace `create-context` +
  `make-load-fn` + `jars-for` plumbing with `loader/sci` + policies
  (`allow` over the root for the shared layers; per-extension deps resolver
  injected). `load-extension!` / `unload-extension!` keep their shape
  (`{:extension … :error …}`; shutdown → deregister → `unload!`).
  The four `"Extensions not supported on Jolt"` guards collapse into backend
  selection.
- `src/kmet/extension.clj` — **re-export** the loader constructors the
  contract promises, so extensions never import `kmet.libs.loader` directly
  (this is what keeps promotion non-breaking).
- Tests: existing extension/jar-ext tests stay green; add case 1 (v1/v2)
  as a real extension fixture; case 9 on sci.
- Gate: `bb test-changed` (plus `bb test` for the extensions suites if
  touched broadly), `bb format-check-changed`.

### Phase 2 — JVM native backend + hybrid

- `kmet.libs.loader.jvm` (Clojure-only): `proxy [java.lang.ClassLoader]`
  overriding `findClass`/`getResource(s)`/`findResources`; `parent` from the
  proxy's own parent; `close`/`unload!` mapped both ways;
  `as-classloader` returns the proxy itself.
- **Java shim** for `defineClass` (or `DynamicClassLoader`) if class
  definition is needed — reflection cannot reach it (verified).
- `loader/hybrid` — native classloader for `:class`/`:resource` + attached
  sci ctx for `:ns`/`:var`; documented as the only way to get both kinds of
  isolation on the JVM.
- Suite runs on the JVM for cases 1–8, 10; case 3 (shared var) via the sci
  half.

### Phase 3 — Jolt native backend (the #912 work)

Depends on the port reaching the extension system (`jolt-port.md` §B3);
phases 0–2 already give Jolt isolation through sci.

- **M0 — indirection, zero behaviour change.** Var/ns/loader/class/type
  tables move behind a ctx record; accessors read `(current-ctx)`; default
  ctx wraps today's globals. ~100 sites, ~10 files. `make remint` (host
  sources changed). Gates: `corpus`, `unit`, `cts`, `sbperf`.
- **M1 — contexts + delegation.** `new-ctx`/`with-ctx`; ctx-aware
  `resolve-on-roots`/`loaded-ns`/data readers; ctx-aware analyze/eval/emit;
  the defining-ctx capture in the emitter (const-pool hoist + IR screening);
  resolve-fn as the delegate step. Acceptance: suite cases 1, 2, 3, 5, 6, 9,
  10.
- **M2 — classes per ctx.** Registry tables, class extensions, provider
  claims/latches, type/protocol/hierarchy tables; boot `java.*` global;
  ctx-tagged tokens; divergence entry for cross-ctx identity. Acceptance:
  cases 4, 10 (+ provider autoload inside a ctx).
- **M3 — unload + caches.** The `run-case-isolation.ss` snapshot/prune list
  as the checklist; shared-write ledger (class extensions, hierarchy
  derives); AOT cache fixes (`aot-cacheable-file`/`aot-own-key-memo` must
  resolve/key per ctx; `:jolt/features`/`:jolt/replaces` into the key);
  embedded-source keys root-qualified; `set-class-path-provider!` answers
  the current ctx. Acceptance: case 7, plus a "world returns to its
  pre-ctx content" byte-compare.
- **M4 — host API + loader objects.** Per-ctx `the-classloader`,
  `RT/baseLoader`, TCCL, `(io/resource n loader)` honoring its argument,
  `jolt.host/new-loader` etc.; `as-classloader`.
- Gates per M-stage: the corpus/unit/cts/sbperf set plus `make sci` /
  `scifunctional` (they pin the sci path the extension backend uses).

### Phase 4 — promotion to a standalone library

- Move `kmet.libs.loader*` (+ its doc and suite) out unchanged; add its own
  `deps.edn`. Because `kmet.libs.*` is already "a third-party library on the
  JVM" by project rule and extensions only ever see `kmet.extension`'s
  re-exports, promotion is a rename + a root — no API break.
- jolt's `stdlib/jolt/loader.clj` (native backend) moves/stays jolt-side;
  the JVM backend stays a separate artifact.

---

## 10. Rollout order and validation

1. Phase 0 green on bb (fast, no kmet behavior change).
2. Phase 1 on bb: `/reload`, extension fixtures, jar-ext suites unchanged.
3. Phase 1 on Jolt: `jolt -e "(require 'kmet.libs.loader)"`, the suite, then
   kmet's extension tests (once the port reaches them).
4. Phase 2 JVM, Phase 3 Jolt native — each must pass the *same* suite; a
   native backend that fails a case is a bug in the backend, not a
   permitted divergence (§6.3 excepted, recorded in the registry).
5. Changed-file gates (`bb test-changed` / `lint-changed` /
   `format-changed`) during development; full gates only on request
   (AGENTS.md).

## 11. Non-goals (v1)

- Bytecode definition on non-JVM hosts (`defineClass` is JVM-only; on jolt
  "define" = register a class token).
- Real `Class` objects / `ClassCastException` / verifier semantics outside
  the JVM.
- Namespace isolation on the JVM without sci or a second `clojure.jar`.
- Sandboxing: contexts are not a security boundary — anything shared by
  reference is fully mutable by the child (same caveat as today's sci
  injection).
- Per-context reader features / replaces policy beyond what a ctx's loader
  already carries (record the policy change when M1 lands).

## 12. Resolved questions (alternatives considered, kept for the record)

### 12.1 Where the sci backend lives / how the dep arrives — **A**

*Decision*: a direct `sci.core` require in `kmet.libs.loader.sci` (its own
namespace file); jolt gets sci via a git pin (§6.5). Protocol-only consumers
pay nothing at runtime — requiring `kmet.libs.loader` does not load
`loader.sci` — and the only cost is a classpath entry.

Note the premise: `kmet.libs` is *not* "no third-party deps". The
self-contained guard only forbids `kmet.*` requires outside `kmet.libs.*`,
and `deps.edn` already carries `babashka.http-client`, `deps.clj`,
`data.json` and two jolt-lang git deps; `kmet.libs.http` requires
`babashka.http-client` today. "Dep-light" is a promotion-quality argument,
not an enforced rule.

| alt | pros | cons | verdict |
|---|---|---|---|
| **A: direct dep, own ns** | simplest; one home; matches `libs.http`; namespace laziness isolates the cost | jolt needs a sci artifact (git pin) | **chosen** |
| B: injected backend SPI | lib truly dep-free; impls evolve separately | a second contract designed and documented before anyone needs it | rejected — premature abstraction |
| C: impl in the app layer | zero new libs deps | promotion becomes a move, not a rename; loader code in two layers now | rejected |
| D: dynamic `requiring-resolve` | no classpath change | missing dep surfaces at first use, not load; contradicts fail-early | rejected — anti-pattern |

### 12.2 `unload!` blocking — **never block** (A), report the race

*Decision*: mark closed first (new loads fail fast), let an in-flight load
finish and stay usable, record `:in-flight`/`:raced` in the report. Opt-in
`{:drain-ms N}` later, only with its own analysis against jolt's
non-recursive loader mutex and claim marks.

| alt | pros | cons | verdict |
|---|---|---|---|
| **A: best-effort / undefined (JVM)** | trivial; exactly `URLClassLoader.close`'s contract | post-promotion footgun unless the race is *reported* | **chosen + `:raced`** |
| B: bounded drain (OSGi) | predictable; catches late lazy `require`s | timeout knob + failure mode; on jolt a wait parks inside the claim protocol (deadlock risk); needs slow/timing tests | deferred (opt-in later) |
| C: refuse if busy | no waiting; matches kmet's `/reload` refusal; caller decides | racy (busy is momentary); caller would need a retry loop anyway | rejected as the contract |
| D: force + discard late results | never blocks; deterministic postcondition | partial-install rollback is genuinely complex | rejected |

Mitigating fact for A: `/reload` already refuses while the agent is busy
(`interactive.clj` "Wait for the current response to finish before
reloading"), so the extension path is serialized by construction today.

### 12.3 State exposure — **`unloaded?` + `(status l)`**

*Decision*: `unloaded?` is the only behavioral predicate; `(status l)` is a
map documented as implementation-visible and free to grow keys (§4.5). No
lifecycle enum.

| alt | pros | cons | verdict |
|---|---|---|---|
| **A: `unloaded?`** | one predicate; covers the postcondition and tests | says nothing mid-unload | **chosen** |
| B: `(state l)` enum | OSGi-comprehensible | freezes a keyword vocabulary; `:unloading` is transient and any test for it is flaky | rejected |
| C: nothing | smallest API | callers can't check | rejected |
| **D: `(status l)` map** | best debugging story; matches house style (`get-loaded-extensions`, `:initialized?`) | shape becomes API (mitigated: diagnostics-only) | **chosen, alongside A** |

### 12.4 Lazy vs eager `find` — **C: data hits + loader-owned opening**

*Decision*: hits are pure data (no streams, no closures, no compiled
artifacts); `find` locates, `load` reads, `(open-hit l hit)` opens resources,
and `load` accepts a hit from `find` to skip re-resolution (§4.4). Loader
*construction* is the one eager-validation point.

| alt | pros | cons | verdict |
|---|---|---|---|
| A: eager (read at find) | simple; read errors surface at find | every probe reads; `getResources` reads all hits for one result; repeated on every resource call (§0.4); jar churn | rejected |
| B: lazy opener closure in the hit | cheap probes | hits not printable/comparable/serializable; defeats AOT read-note bookkeeping | rejected |
| **C: data hit + `open-hit` + `load` takes hit** | cheap probes; hits printable/comparable; loader owns opening (policy, read notes); validation has a home | `load` must accept a hit (small API addition) | **chosen** |

Matches jolt internals (`find-ns-file` locates / `ldr-read-source` reads;
`resolve-resource` returns a URL that opens on demand), so the native backend
maps onto it without rework.

### 12.5 Still open

- Does the conformance suite ship with the lib at promotion (a `-test`
  artifact), or stay in kmet's `test/` as the reference implementation of
  the spec? (Phase 4 detail; the suite is the spec, so this is a packaging
  question, not a design one.)
- `remove!` (OSGi's terminal uninstall: delete owned files/storage) — v1
  leaves it out; revisit if a loader ever owns a cache directory.
