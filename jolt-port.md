# Porting kmet to Jolt — full report

Scope: the whole repo (141 `src` files ≈ 52.6k LOC, 117 test files ≈ 34.9k LOC,
59 extension source `.clj` files ≈ 17k LOC (+106 `target/` test-fixture
files — counts re-verified 2026-09-08),
not just the TUI. Jolt reference is the checkout at `~/jolt/` (`533b04a3`,
2026-09-08; re-checked at `c4ebc570` / `v0.8.6-32` and, for B3, at
`69a6f592`, 2026-09-11) plus `jolt-lang.github.io/docs/{native-interop,
host-interop,differences,building-and-deps}`. Items marked "verified" were
checked against that tree (`stdlib/`, `host/chez/java/`, `vendor/`, `jolt-core/`);
the rest is code reasoning, not a running port.

**Bottom line**: a full port is a multi-month project with 2 hard blockers
(subprocess/process management, extension isolation) plus the HTTP/SSE
wrapper workstream (B1: babashka.http-client over the jolt-lang shims,
curl for SOCKS proxies and live streams — sse reader ported 2026-09-09)
and ~15 medium rewrites. A staged port is viable: pure layers first
(`libs` minus I/O → `ai/api` builders → `reakt`/`hiccup`/components), then
the terminal adapter, then transports, then the agent loop + tools, with the
extension system redesigned last. Roughly 60–70% of LOC is portable logic;
the rest is JVM/Babashka surface that must be reimplemented against Jolt
shims + C FFI.

Related docs: `jolt-tui.md` (TUI adapter deep-dive — FFI ground rules,
termios/kernel32 raw mode, input pipeline, key parser, concurrency mapping;
its §§4–7,9 cover the terminal adapter). `jolt-bugs.md` is the inventory of
jolt-side issues this repo has filed or tracks; §8 summarizes what is live
today.

---

## 1. Inventory — what kmet is made of

| area | files | LOC | character |
|---|---|---|---|
| `libs/` (generic utils) | 27 | ~8.3k | half pure (diff, yaml, markdown, highlight, reakt, num), half JVM-bound (http, process, crypto, archive, oauth, jsonrpc, sse) |
| `ai/` (providers/auth) | 13 + 10 `api/` | ~8.8k | request builders (pure) + streaming over `libs.http` + auth/token caches on disk |
| `tui/` (generic TUI) | 11 + 21 components | ~11.7k | ~95% pure; only the terminal backends (`terminal.clj` protocol + `terminal_jline.clj`/`terminal_native.cljc`) + `core.clj` reader/timers touch the host |
| `app/` (agent/tools/ui) | 14 + 11 tools + 27 ui | ~17.4k | business logic + tools (bash/edit/grep/ls/read/write/find) + `extensions.cljc` (SCI) |
| `modes/` (entry) | 2 | ~4.5k | `interactive.clj` (4.4k, TUI wiring) + `print.clj` (headless) |
| root (`core/config/debug/extension/build`) | 5 | ~1.6k | CLI dispatch, config loading, extension contract, bb-based build |
| `extensions/` (shipped, non-`target/` source) | 59 | ~17k | opt-in extensions incl. `mcp-adapter`, `clojure`, `lsp-adapter`, `tools.clj` (+106 `target/` test-fixture files excluded from the count) |
| `test/` | 117 | ~34.9k | `clojure.test`, `kmet.runner` custom runner with `^:slow` split |

External deps (`deps.edn` + `bb.edn`): `babashka.fs` / `babashka.process` are
**not** deps — babashka bundles them and jolt vendors the same namespaces
(built-in on both hosts; see §4). `borkdude/deps.clj` (Maven resolution),
`org.clojure/data.json` (JSON engine behind `kmet.libs.json`),
`io.github.jolt-lang/time` + `io.github.jolt-lang/crypto` (Jolt-only shims,
inert on bb/JVM), `dev.weavejester/cljfmt` (tooling only), JLine 4.3.1 (bb-bundled). `bb.edn` also defines 22 tasks (`run` + 21: build/test/lint/format/nrepl/generate-models/…).

---

## 2. Hard blockers (need design + substantial new code)

### B1. HTTP/SSE transport (`libs/http.cljc` 754 LOC + `libs/sse.clj` 1114 LOC)

kmet funnels ALL outbound HTTP through `kmet.libs.http` (enforced by
`test-http-boundary`): `babashka.http-client` (java.net.http) for plain
requests + raw `curl` subprocess for SOCKS/https-scheme proxies, streaming
bodies, idle-timeout readers. Every LLM call in every provider rides this.

**Decision (revised 2026-09-09): native `babashka.http-client` on Jolt over the
`jolt-lang/http-client` shims; curl only for SOCKS/https-scheme proxies and
live `:as :stream` feeds.** The 2026-09-06 rejection below predated the
library's `java.net.http` work. Its main (`4744256f83e5`, 2026-09-09) runs
`org.babashka/http-client` 0.4.24 unmodified from Maven over `jolt.http.jdk`
(RFC 0014 `:jolt/provides`): real `:proxy` routing (absolute-form http,
CONNECT-tunnelled https), `:follow-redirects` `:never`/`:normal`/`:always`
with https→http downgrade refusal, `:ssl-context` incl. `{:insecure true}` +
PKCS#12 stores, `:authenticator`, `:cookie-handler`, `:connect-timeout` +
per-request total `:timeout`, pooled connections with stale-peer retry, and
`CompletableFuture` async on jolt's own pool (no caller `:executor`, no
HTTP/2 on the wire — `:version :http2` degrades to 1.1). What still keeps
curl on Jolt: **live streams** — the shim reads a body in full before the
response returns, so an endless SSE feed (`api/*` hot path) never returns
and `:as :stream` requests stay on `curl-request` — and SOCKS/https-scheme
proxies (same `curl-proxy?` split the JVM side already has). `http.cljc`
is one dispatch body shared by both hosts — the stream carve-out is the only
host-specific part (`#?(:jolt (= :stream (:as opts)) :default false)`).
The transport is also a user setting (`settings.edn` `:http-transport`,
`/settings` HTTP transport row): `:platform` (default, as above) or
`:curl` — every request through curl on both hosts; `test-http` covers
every request contract under both modes on both hosts (Jolt: 25/90).
Verified: deps.edn carries `org.babashka/http-client` 0.4.24 +
`io.github.jolt-lang/http-client` (upstream `b98833b8`); its transitive
`jolt-lang/jolt-crypto` pin (`44da69` — same repo as the direct
`io.github.jolt-lang/crypto` dep at `5effcc89`) lands both shas on the jolt
classpath with no load conflict observed. test-http is green on both hosts
with every request contract running under both transports (dual-mode suite:
Jolt 25/90, bb 26/91); test-sse re-verified green (streams untouched).

`sse.clj` reader is ported (2026-09-09): the parsing/state-machine needed no changes; the body reader needed three Jolt workarounds, all inside `sse.clj` — `(ArrayBlockingQueue. 65536)` for the idle-deadline queue (`LinkedBlockingQueue` has no ctor on Jolt), a shared `.read` char loop instead of `.readLine` (Jolt's `BufferedReader` ctor is identity, so a passed-through `proxy` Reader has no `readLine` method), and `body->reader` (Jolt's `jolt-io-reader` rejects `proxy` Readers with `Cannot open <reify> as a Reader`, so Reader bodies bypass `io/reader`; `(.close rdr)` is failure-tolerant for the same reason). `test-sse` is fully green on Jolt (33 tests/109 assertions, `jolt v0.8.5`). `jsonrpc.clj` (409 LOC, MCP stdio
transport) rides `babashka.process` pipes — portable *if* `jolt.process`
covers spawn + async pipe IO + `destroy-tree` (verified: `process.ss` implements `ProcessHandle` descendant tracking behind `destroy-tree`; still probe pipe-streaming + Windows behavior).

**Done (transport split, `http.cljc`):** the port keeps
`kmet.libs.http`'s contract (opts, lowercased headers,
`:http-error`/`:transport-error`, `proxy-for-url`). Jolt runs direct and
http-proxy traffic through babashka.http-client over the jolt shims (see
the revised decision above); curl survives only for SOCKS/https-scheme
proxies and `:as :stream`. Verified: full test-http green on both hosts.

**Done (sse reader, 2026-09-09):** see above — `test-sse` 33/109 green on Jolt; no `http.cljc` changes needed (production `:body` values are real streams).

### B2. Subprocess/process management (`libs/process.clj`, bash tool, MCP stdio)

kmet leans on `babashka.process` hard: `proc/process` (19 uses), `shell`,
`destroy-tree`, pid tracking, `setsid`, timeout-kill, pipe streaming.
`jolt.process` re-exports vendored `babashka.process` over Jolt's
`ProcessBuilder`/Process shims (`host/chez/java/process.ss`: `posix_spawn`
+ `waitpid`/`kill` FFI, WNOHANG poll loop, per-process reap mutex) — the
highest-leverage compat to verify early. In-tree facts: `process.ss`
implements `ProcessHandle` descendant tracking behind `destroy-tree`
(`process.ss:1046-1122`); `exec` is explicitly NOT re-exported (`stdlib/jolt/process.clj:15`
— and nothing in `src/` needs it: the only `:execute` hits are tool-registry
keys). Still to verify on the checkout: whether the vendored
`babashka.process` matches kmet's `deps.edn` pin (the vendored sources
carry no version constants — compare by file, not by number). Gaps to probe:
`destroy-tree` on all OSes (Windows falls back to Chez
`open-process-ports`, where `^C` cannot interrupt the child),
pipe-streaming without deadlock, timeout semantics (bb's `:timeout`
reports exit 0 on kill — kmet works around it; check Jolt matches),
`setsid`/process-group kill, stdin/stdout as async streams for MCP stdio servers.
The bash tool + `bash-executor` + session export + git operations all sit
on this; if `jolt.process` falls short, the fallback is direct
`posix_spawn`/`waitpid`/`kill` FFI (the exact calls `process.ss` already
uses for Jolt's own spawning — reusable patterns). **The bash-tool path is
green on `jolt test-ext` as of 2026-09-11**: `bash-executor` works around
two jolt gaps — the streaming decoder avoids the JVM `CharsetDecoder`
(absent on jolt), and stdin is an always-pipe closed right after spawn
because jolt's `ProcessBuilder.redirectInput(File)` is a no-op (open
upstream — see `jolt-bugs.md`).

### B3. Extension isolation (`app/extensions.cljc` — SCI, 1668 LOC)

Each extension evaluates in its own **SCI context** (`sci/init`,
`sci/eval-form`): private ns registry + loader serving own files, declared
Maven jars (resolved in-process via `borkdude/deps.clj`), and host
classpath; shared layers (`kmet.extension`, `clojure.*`, `babashka.*`,
`kmet.tui.*`, `kmet.libs.*`) injected by reference. Plus bb-import tables,
bundled-lib redirection (rewrite-clj, edamame, …), per-extension
deps.edn, load-fn error handling, classpath-overrides matching bb.

**SCI substrate VERIFIED on Jolt 2026-09-11 (`v0.8.6-32`+).** `make sci`
(pure-Chez source-load gate) exits 0 at 412/424
(floor 412; its 12 not-ok forms are the gate's curated load-order over a
curated subset, not host gaps — see the gate's header),
and `scifunctional` prints `SCI-FUNCTIONAL-TEST OK` (SCI 0.13.53 through the
ordinary dependency path: `sci/init`, `eval-string*`, persistent/isolated
contexts). A loader-shaped smoke of kmet's exact surface — host `read` +
`sci/eval-form` per form, `:load-fn` serving a second namespace,
`:namespaces` host-var injection, init-var fetch from `@(:env ctx)` — passed
with **one required change**: vanilla SCI's `eval-form` contract wants
`sci/binding [sci/ns …]` around the eval loop for `(ns …)` forms; bb
tolerates the unwrapped host-`*ns*`-only shape `eval-source!` uses today
(Jolt throws `Can't change/establish root binding of #'clojure.core/*ns*
with set` from `sci.lang/throw-root-binding`). Timings for a 2-file
extension: ctx init 45 ms, eval 23 ms, init call 5 ms. Per-extension deps:
`jolt.deps/resolve-deps` (public, AOT'd into the binary) returns the
extracted source roots of an arbitrary deps map at runtime and
`jolt.deps/add-deps` is the `babashka.deps/add-deps` twin — the
`borkdude.deps/-main -Spath` replacement, verified from the built binary
(roots: `sci` + `edamame` + `sci.impl.types` + `graal.locking` +
`tools.reader`). **Version pin: 0.13.53** — the jolt-gated SCI; the latest
release (0.15.58) does not load on jolt yet (`No such var: clojure.core/Inst`
loading `sci.impl.core-protocols` — jolt's `clojure.core` lacks the 1.12
protocol). (The `stdlib/clojure/sci/*_stubs.clj` files are only for
the pure-Chez `run-sci.ss` harness — the binary loads real SCI source.)

**Interop inside interpreted code — verified on stock SCI (2026-09-11).**
Extension sources — and every deps.edn library they
load — run interpreted, and SCI's interpreter routes *both* instance and
static method calls through `sci.impl.reflector`: `get-methods` delegates to
`clojure.lang.Reflector/getMethods` and returns real
`java.lang.reflect.Method` objects that the transliterated
`invoke-matching-method` matches on (`getParameterTypes`, `getModifiers`,
`.invoke`, `Compiler/subsumes`). jolt registers that lookup
(`clojure.lang.Reflector/getMethods`) plus the two companions the same path
needs — `Class.cast` (jolt reports every parameter as `Object`, where the cast
is the identity) and `Util/sneakyThrow` (the
rethrow SCI's invoke ends every call with). `getMethods` answers from the registries
`Class.getMethods` already reads; for a class whose methods are a `cond` over
the receiver (String, the collections) it answers with a member carrying the
dispatch rule — parameter count pinned in a slot, instance calls routed
through `record-method-dispatch`, statics through `host-static-call`,
`canAccess` → yes — under the same "jolt reports what its registries know"
model as `reflect-member-model`. `Method.invoke` also reads a lone nil
argument array as the empty one (how a reflective caller spells a
zero-parameter call). **Verified:** the interop matrix
(`System/currentTimeMillis`/`getenv`/`getProperty`,
`Math/round`, `Integer/parseInt`, `Character/isWhitespace`, `Thread/sleep`,
`.indexOf`/`.toUpperCase`/`.getBytes`/`.getName`/`.getScheme`/`.size`/
`.toString`, constructors, `(Thread. (fn [] …))`) is green on
*stock* SCI 0.13.53, and so is kmet's loader shape
end-to-end
(`$TMPDIR/kmet-loader-smoke.clj`: `:load-fn` + `:namespaces` injection +
interop inside an interpreted extension's own source). `make sci` 412/424
(floor); `make scifunctional` and `unit.edn`'s
`reflect-member-model` (26 rows) cover the cases; `make unit` shows the same
6 `/tmp`-based failures as `main` (Termux has no `/tmp`). `make corpus`
reports 9 crashes (3×
`ISO-2022-JP`, 2× `Shift_JIS`, 2× `windows-1252` from upstream's `Charset`
object change, 2× `/tmp/jolt-spit` from the environment) at 5494/5513 on
`origin/main`. `make smoke`/`make loaderconf` need a built binary, and `make testbin`
cannot link on this Termux toolchain (system-Chez iconv), unrelated to
source.

That coverage matters: **16 of 41 `src`
extension files use interop** (all 16 via `System/` statics —
`currentTimeMillis` ×31, `getenv` ×20, `getProperty` ×20 — plus instance
methods: `.indexOf`, `.getBytes`, java.time chains), and loaded libraries do
too (cljfmt 0.16.5: `java.io.File` in 3 of its 12 sources). No `.-field`
access anywhere in the corpus (0 hits).

Remaining port items: (a) **the `sci/binding` wrapper** — vanilla SCI wants
`sci/binding [sci/ns …]` around the eval loop for an `(ns …)` form; bb
tolerates the unwrapped host-`*ns*`-only shape `eval-source!` uses today, Jolt
throws `Can't change/establish root binding of #'clojure.core/*ns* with set`
(`sci.lang/throw-root-binding`). kmet-side, and `#?(:jolt …)`-conditional:
bb's own `sci/binding` is broken (`Unable to resolve symbol:
sci.impl.vars/push-thread-bindings`); (b) the class/import tables
(`context-classes` is bb's `babashka.classes/all-classes` today; jolt exposes
no class enumeration — an upstream listing API or a curated table — the
reflector path needs the table populated to reach any class); (c) the
bb-bundled-lib redirection tables (`bundled-port-namespaces`,
`bb-shared-namespaces`, rewrite-clj/edamame, `bb-imports`); (d) jar/zip
extension artifacts (M5); (e) SCI-perf beyond one small extension. Alternative
designs worth costing: (1) extensions as plain Jolt namespaces, no isolation
(loses version isolation); (2) extensions as subprocesses over JSON-RPC (the
MCP pattern — strong isolation, new protocol work); (3) SCI as now. This is
the last milestone either way — the core agent must work before extensions
matter.

---

## 3. Medium rewrites (bounded, one namespace at a time)

| # | kmet surface | Jolt answer (verified on checkout) | size |
|---|---|---|---|
| M1 | `clojure.data.json` (the swap from `cheshire` → `data.json` is done — `kmet.libs.json` now aliases `clojure.data.json` directly) | **RESOLVED 2026-09-09 — no JSON lib needed:** `org.clojure/data.json` is a `deps.edn` Maven dep and Jolt resolves Maven deps itself, so `kmet.libs.json` loads unchanged on Jolt. Verified green on Jolt `v0.8.5`: `test-json` (4 tests/18 assertions), `test-jsonrpc` (17/41), `test-sse` (33/109). **Note:** `http.cljc` is already ported (curl path via `#?(:jolt ...)`); all 27 libs now load and test green on bb/JVM. M1 is closed (data.json works on both hosts) | done — no new lib |
| M2 | `tui/terminal.clj` (JLine raw/timed-reads/size) + `core.clj` reader/timers/resize/drain | termios FFI (Unix) + kernel32 FFI (Windows); `future` reader + `locking` + gen-counters — see `jolt-tui.md` §§4–7,9. Evaluated 2026-09-06: `jolt-lang/glimmer-tui` (ncursesw via FFI, Unix-only, fullscreen `initscr` takeover) rejected — wrong architecture for the inline ANSI/scrollback model; JLine stays on bb (`jolt-tui.md` §2 decision). **LANDED 2026-09-11 (Unix)**: `kmet.tui.terminal` is now a lean total `ITerminal` protocol + shared verbs; `terminal_jline.clj` (bb) and `terminal_native.cljc` (Jolt: termios + poll/read + ioctl winsize + shutdown-hook restore) are two backends behind it, resolved at runtime. Verified on `jolt v0.8.6-72`: FFI pty round-trip, new native tests green, `jolt test-ext kmet.tui.test-render-loop` green, and the real kmet TUI runs on Jolt (`jolt run -m kmet.core` in a pty, clean exit 0). Windows stays open (`jolt-tui.md` §0/§6) | Unix done; Windows open |
| M3 | `libs/crypto.clj` (315 LOC: RSA/EC `KeyFactory`, `SHA256withRSA/ECDSA` `Signature`) + `libs/aws_sigv4.clj` (213 LOC: `MessageDigest` SHA-256, `Mac` HmacSHA256, `HexFormat`, `Normalizer`?) — grep the exact class list before the FFI design | `io.github.jolt-lang/crypto` (OpenSSL via `jolt.ffi`, RFC 0014) provides `MessageDigest`/`Mac`/`Cipher` and EC + RSA `Signature`/`KeyPairGenerator`/`KeyFactory`; `jolt.kmet.providers` covers the Base64 MIME decode (§9); the DER/JWK writers use the portable `kmet.libs.crypto/bigint->bytes`, so bigints jolt models as `Long`/`BigInt` work on both hosts. Jolt: `test-aws-sigv4` green (5 tests/18 assertions), `test-crypto` green (10 tests/21 assertions); the Google-ADC / oauth RS256 paths run green | done (jolt.crypto + kmet.libs.crypto) |
| M4 | `libs/oauth.clj` (611) + `ai/oauth.clj` (1012) + `ai/google_adc.clj` (121) — browser launch, localhost callback server, token cache | `ServerSocket` shim exists (`stdlib/jolt/socket.clj`, gated on `(require 'jolt.socket)`); browser launch via `jolt.process`; token cache via `spit`/`slurp`. Jolt: `test-oauth` green (26 tests/73 assertions) and `ai.test-oauth` 53 tests/223 assertions green (2026-09-11). The callback path keeps two workarounds for open jolt behaviors: header lines are trimmed (a socket line `"x\r\n"` reads `"x\r"`) and responses write through the 3-arg socket `write` (the 2-arg `write(byte[])` throws) — see `jolt-bugs.md` | adapt ~1.7k LOC |
| M5 | `libs/archive.clj` (46 LOC, `ZipFile` read) + `sse.clj` CRC-32 (pure-Clojure `libs/hash.clj/crc32` since the port — Bedrock frame tests green on Jolt, no zip work) + `extensions.cljc:910,921` (`JarFile` probes) + `build.cljc:227,245,389` (`ZipOutputStream` uberjar/pack-extension). (`ai/models.clj` needs no zip work — catalogs load via `io/resource`, which answers file:/jar:/embedded URLs alike.) | `jolt.fs` explicitly EXCLUDES zip/gzip (`stdlib/jolt/fs.clj:12`: "java.util.zip not shimmed yet"). **DECIDED 2026-09-08: bb-only until the `jolt build` rewrite** — `build.cljc`/`libs/archive.clj` entry points throw `::bb-only` under Jolt, their tests carry `^:bb-only` (the runner skips them there); zip/jar work defers to extension-jar materialization via unzip (jolt's own mvn-jar model) | rewrite build; archive via FFI or subprocess. Note:
| M6 | `build.cljc` uberjar assembly (`bcp/get-classpath`, `ZipOutputStream` resource listing) + model-catalog embedding | No classpath concept; `jolt build` embeds source roots differently. Model catalogs (`ai/model_data/` + manifest) become embedded resources — `io.ss` has `register-embedded-resource!` and `io/resource` answers a `java.net.URL` from both disk and a built image. **VERIFIED 2026-09-11 (Unix): `jolt build -m kmet.core -o kmet-self` (release mode) produces a working self-contained binary** — the full TUI runs in a pty (renders, native FFI terminal raw mode + reads, bracketed paste, Kitty query, `/quit` exits 0 with cursor/paste restore) and the model catalogs resolve from the embedded resources (the status line shows the resolved model). Packaging caveat: a built binary dies at startup unless the `io.github.jolt-lang/time` provider was autoloaded in-process during the build; kmet's graph currently satisfies that (load-time `DateTimeFormatter`/`ZonedDateTime`/`ZoneId` refs in `aws_sigv4`/`tree_selector`), with an explicit early `(:require [jolt.time])` as the workaround if a future entry point stops doing so. `build.cljc`'s own entry points stay `::bb-only` until the jolt pipeline (extension packing, model generation) is ported | TUI binary done; remaining build.cljc surface open |
| M7 | `libs/clipboard.clj`, `libs/terminal_image.clj` (Base64 — shimmed, keep), OSC-52/kitty-graphics emit | clipboard via platform subprocesses (`pbcopy`/`xclip`/`clip`) through `jolt.process`; image protocols are pure emit logic | small |
| M8 | `config.clj` (XDG paths, EDN load/save, file watching?) | `jolt.fs` (vendored `babashka.fs`, minus zip) covers paths; `spit`/`slurp`/EDN portable; watcher → poll (same as `tui.theme`) | adapt |
| M9 | `debug.clj` (file logging) + crash/render logs | `(spit path text :append true)` (`jolt-io-writer` is 1-arg — `io.ss:1314-1323`; `spit` takes `:append` — `io.ss:1164-1195`); timestamps via the `io.github.jolt-lang/time` dep (already in `deps.edn`) or manual format. Note: Jolt's `java.io.tmpdir` honors `$TMPDIR` (`host-static-methods.ss:1002,1016`), unlike bb's hardcoded `/tmp` — keep the explicit-dir pattern anyway | small |
| M10 | `bb.edn` tasks (22: `run` + 21: uberjar/build/test/test-ext/changed/test-changed/test-ext-changed/lint-changed/format-changed/format-check-changed/nrepl/check/generate-models/generate-image-models/check-model-data/pack-extension/lint/format/format-check/help) | **DONE (test task):** `kmet.runner` is now host-aware and tolerant — every test namespace is required in a try; unloadable ones (babashka-internal requires like `babashka.classpath`/`babashka.classes`, `java.time.format.DateTimeFormatter` gaps, …) are reported and skipped, the rest run. Per-var `^:slow` split + per-var filters work on BOTH hosts (`jolt test` non-slow / `jolt test-ext` slow; bb.edn `:paths ["src" "test"]` supplies the roots under jolt). Engine: bb = per-var output capture + ref counters; jolt = `clojure.test/test-vars` with jolt's own process-wide `counters` atom read as before/after deltas (`jolt?` = `(find-var 'clojure.core/*jolt-version*)`). **Full-suite status: §8.** Remaining M10 work: `jolt build` packaging, the format gates, model generators. **Lint gate DONE 2026-09-11**: `kmet.lint` runs clj-kondo over BOTH reader views in either gate, merging and deduping the findings — the babashka view (the tree, with each file carrying a `:bb` branch projected into `target/bb-lint/`) and the jolt view (the files carrying `:jolt`, projected into `target/jolt-lint/`, plus `jolt/`; a file carrying only `:bb` is read raw, which is jolt's reading of it), each projection re-spelling its feature to `:clj` — the one feature clj-kondo resolves — under the `.clj-kondo-jolt` overlay for the jolt runtime seams. `bb lint` and `jolt lint` are therefore equivalent in coverage, and `lint`/`lint-changed` are host-independent (`jolt lint-changed` included). The branch features are `:bb`/`:jolt` (babashka-only / jolt-only, disjoint, so no branch order can let one host run the other's code); `:clj` is not used in kmet source — it matches both hosts, so `#?(:clj A :jolt B)` gives both hosts A | mostly done for tests; slow-set status in §8 |
| M11 | `clojure.spec.alpha` (SCI-context injection only), `clojure.walk` (2 requires: `libs/json.clj:16`, `ai/constrained_sampling.clj:13`), `BigDecimal` (`edn_writer` + SCI class table) | spec: absent from `stdlib/` (verified — declare `org.clojure/spec.alpha` explicitly per README's "terminal dependency" rule, or rewrite the one use); `walk`: present (`stdlib/clojure/walk.clj`, seed-embedded — keep); `BigDecimal`: PRESENT (`host/chez/java/bigdec.ss`: `M` literals + `with-precision` per README — the earlier "absent" claim was wrong; just port the call sites) | small |
| M12 | `defrecord` (27 files) + `reify` (6 files) + protocols + `deftype` (zero definitions — only comments) | README Differences confirms `deftype`/`defrecord`/`reify`/`extend-protocol`, multimethods, STM, `future`/`promise`/`agent` and `core.async` behave as on the JVM — still verify early: `satisfies?`-on-reify semantics, `defrecord` positional factories, protocol dispatch for `IComponent`/`IFocusable`. The TUI's `satisfies?` avoidance notes (AGENTS.md SCI gotcha) need re-checking on Jolt | verify early, affects everything |
| M13 | Custom `defcomponent`/`with-let` macros + clj-kondo hooks | Jolt compiles macros normally (self-hosted compiler) — should port; re-verify hygiene/&env behavior (`go`-style passes are async-only, plain macros fine). Kondo hooks keep working (source-level) | verify early |
| M14 | `java.util.concurrent` — 4 sites: `LinkedBlockingQueue`+`TimeUnit` (`libs/sse.clj` idle-deadline reader — now `ArrayBlockingQueue`; the ctor gap is open upstream, `jolt-bugs.md`), `ReentrantLock` (`app/session.clj:154,296`, file-mutation lock), `Callable` (`app/extensions.clj:738`, SCI class table) | **Verified 2026-09-09:** `LinkedBlockingQueue` has NO ctor on Jolt (`No matching ctor found`) — `sse.clj` now uses `(ArrayBlockingQueue. 65536)`; verified `.put`, `.poll n TimeUnit`, `.offer`, `.size`, `.remainingCapacity`, and `TimeUnit/MILLISECONDS`. `ReentrantLock` still assumed shimmed (session lock not yet run on Jolt); `Callable` becomes a fn; `locking` covers the session lock | small |
| M15 | `java.net.URI/URL/URLEncoder`, `Normalizer`, `Charset`, `HexFormat`, `Instant/DateTimeFormatter/ZoneId`, `PushbackReader`, `StringReader/Writer` | Mostly shimmed (host-interop list + `io.ss`/`io-streams.ss`); URL/URI surface exists (`jolt.socket` gating for sockets); time values via time lib. **Verified 2026-09-09 (reader surface):** `io/reader` rejects `proxy` Readers (`Cannot open <reify> as a Reader` — `jolt-io-reader`, `io.ss:1291`); `BufferedReader` ctor is identity so a proxy Reader lacks `.readLine`/`.close`; `InputStreamReader` over a proxy `InputStream` constructs (reads dispatch to the override); `PipedInputStream` + `io/reader` + `.readLine` works. `sse.clj` works around all three (see B1). **Verified 2026-09-09 (URI + java.net.http surface):** the multi-arg `URI` ctors are missing — both the 7-arg and the 4-arg throw `incorrect number of arguments` (verified 2026-09-10), so `azure_openai_responses/normalize-azure-base-url` rebuilds the URL by hand from the parsed pieces. `java.net.http.HttpTimeoutException` exists as a class but has NO ctor — kmet's `jolt/` provider lib supplies it (§9; open upstream gap). `java.text.Normalizer`/`Normalizer$Form` resolve as classes but every member throws "no dependency provides" — kmet's two call sites (`edit_diff`, `read`) treat it as best-effort. Remaining call sites still need per-site audit | audit per site |
| M16 | Jolt host string semantics (kmet's UTF-16-indexing code) | Jolt (Chez) strings index by **code point**, not UTF-16 code unit: `(count "👨‍👩‍👧‍👦")` is 7 (one char per astral code point) vs 11 surrogate units on bb/JVM, and `nth` returns the full code point. kmet's grapheme/width scans are index-model-agnostic: `codepoint-len` (utils.clj) derives the element span from the string itself — 2 only when the element at index I is a high surrogate followed by a low surrogate (the same pairing test `code-point-at` uses), never from cp magnitude — and the four inline `nchars` sites (truncate-to-width ×2, split-long-word, slice-by-column) route through it: the walkers step in the host's own element model (a semantic no-op on bb/JVM; 1 per astral cp on Jolt) | done |

---

## 4. Verified `libs/` status (2026-09-11)

Cold-ran every `kmet.libs.*` namespace under Jolt (`jolt v0.8.6`, threaded
Chez 10.x) — require + load each, then run its test suite (or probe its
public fns when no test file exists). On bb/JVM: **all 27
load and test green**. Jolt: **all green except `archive` (bb-only, M5)** —
crypto/oauth/http re-verified 2026-09-11 (counts below).

| lib | bb/JVM | Jolt | notes |
|-----|--------|------|-------|
| `archive` | 🟢 | 🔴 | bb-only on Jolt (2026-09-08, M5): `::bb-only` entry guard + `^:bb-only` tests; zip work deferred to extension-jar materialization (unzip) |
| `aws_sigv4` | 🟢 | 🟢 | Jolt 2026-09-09: 5 tests/18 assertions green — `MessageDigest`/`Mac` via the crypto dep hold (M3 symmetric half done) |
| `clipboard` | 🟢 | 🟢 | uses `babashka.process`, works |
| `concurrent` | 🟢 | 🟢 | `spawn` returns `Thread`; works |
| `context` | 🟢 | 🟢 | tests pass (11/11) |
| `crypto` | 🟢 | 🟢 | Jolt: 10 tests/21 assertions green — RSA/EC from `io.github.jolt-lang/crypto`, Base64 MIME from the `jolt/` provider lib, DER bytes from `kmet.libs.crypto/bigint->bytes` |
| `diff` | 🟢 | 🟢 | pure, works |
| `dynamic_value` | 🟢 | 🟢 | tests pass (53/53) |
| `edit_diff` | 🟢 | 🟢 | uses `java.text.Normalizer` (unshimmed, the NFKC step is best-effort — see `jolt-bugs.md`), `java.util.regex.Pattern`; works |
| `edn_store` | 🟢 | 🟢 | tests pass (40/40) |
| `edn_writer` | 🟢 | 🟢 | pure, works |
| `hash` | 🟢 | 🟢 | pure, works |
| `highlight` | 🟢 | 🟢 | tests pass (139/139) |
| `hooks` | 🟢 | 🟢 | pure, works |
| `http` | 🟢 | 🟢 | **ported** — Jolt runs direct/http-proxy traffic through babashka.http-client over the jolt-lang/http-client shims (deps.edn: org.babashka/http-client 0.4.24 + io.github.jolt-lang/http-client, upstream `b98833b8`), curl for SOCKS/https-scheme proxies and `:as :stream` (see B1); the `:http-transport` setting can force curl for everything. test-http 25/90 green on Jolt (every contract under both modes, Termux/bionic included). Loads on bb |
| `json` | 🟢 | 🟢 | Jolt 2026-09-09: 4 tests/18 assertions green — data.json resolves via deps.edn (M1 closed) |
| `jsonrpc` | 🟢 | 🟢 | Jolt 2026-09-09: 17 tests/41 assertions green (M1 closed) |
| `markdown` | 🟢 | 🟢 | tests pass (137/137) |
| `num` | 🟢 | 🟢 | portable predicates, works |
| `oauth` | 🟢 | 🟢 | Jolt: 26 tests/73 assertions green; the callback path's line trims stay for jolt's trailing-`\r` line reads (`jolt-bugs.md`) |
| `process` | 🟢 | 🟢 | uses `babashka.process`; works |
| `reakt` | 🟢 | 🟢 | tests pass (30/30) |
| `sse` | 🟢 | 🟢 | Jolt 2026-09-09: 33 tests/109 assertions green — reader ported (`ArrayBlockingQueue`, `.read` loop, `body->reader`; M1 closed) |
| `terminal` | 🟢 | 🟢 | uses `java.time`, `java.lang.ProcessHandle`, `java.util.Base64`, `clojure.java.io`; works |
| `terminal_image` | 🟢 | 🟢 | tests pass (41/41) |
| `usage` | 🟢 | 🟢 | pure, works |
| `yaml` | 🟢 | 🟢 | bb: 20/20; Jolt: 20/20 |

**bb/JVM: all green (27).** Jolt: all green except archive (bb-only, M5).
M1 is closed — data.json resolves on both hosts. See §8 for the whole-suite
status (the app/ai/tui namespaces beyond `libs`).

---

## 5. What ports mostly as-is (the good news)

- **Pure logic** (~60–70% of LOC): `libs/{diff,edit_diff,yaml,markdown,highlight,usage,hash,context,edn_writer,dynamic_value,hooks,concurrent}`, `ai/api/*` request builders (all 10 provider wire formats — pure data transformation), `ai/{models,model_config,constrained_sampling,attribution,hooks}`, `libs/reakt`, `tui/{hiccup,macros,protocols,keys,keybindings,utils,theme}` + all 21 components, most of `app/{session,compaction,skills,prompts,commands,event_bus,keybindings,model_resolver}`, tools `{find,grep,ls,read}` (fs ops via `jolt.fs`).
- **`babashka.fs` → `jolt.fs`**: vendored + supplemented (`jolt.bb.fs`), same API minus zip (`stdlib/jolt/fs.clj:12`). ~370 `fs/` occurrences / 35 distinct fns (`exists?` 51, `path` 43, `file-name` 40, `parent` 27, …) transfer almost mechanically.
- **`babashka.process` → `jolt.process`**: same re-export shape — IF the shims hold (B2 verification).
- **`clojure.string`/`clojure.edn`/`clojure.test`/`clojure.walk`-ish**: present in stdlib. `spit`/`slurp`/`with-open` present (archive caveat M5 for streams).
- **`System/getenv/getProperty`, `currentTimeMillis`/`nanoTime`, `Thread/sleep`, `StringBuilder`, `Base64`, `Pattern`**: all shimmed (see `jolt-tui.md` §9 for file-level refs).
- **`future`/`promise`/`locking`/agents/STM/`core.async`**: real and fiber-aware — the agent loop's concurrency (`AgentState` per-field atoms, futures for LLM calls, event bus) has a home; see §9 carrier rules.

---

## 6. Port order (staged, each stage testable)

1. **Prove the substrate** (days): `defrecord`/`reify`/`satisfies?` semantics (M12), macro hygiene (M13), `jolt.fs` fn coverage, `jolt.process` spawn/pipe/kill/timeout matrix (B2), `jolt.socket` reachability, SCI-load smoke (B3 feasibility), `io/resource` + embedded resources (M6).
2. **Pure libs** (1–2 wks): port the §4 pure set (`kmet.libs.json` needs no rewrite — data.json resolves on Jolt, M1 closed). Headless tests under Jolt's `clojure.test`.
3. **TUI core** (2–3 wks): `keys`→`utils`→`reakt`→`hiccup`→components→theme headless (`render-lines`), then `ITerminal` FFI adapter + input pipeline (`jolt-tui.md` §§5–7). Validate with pty captures.
4. **HTTP/SSE + providers** (3–5 wks, critical path): B1 transport decision + `sse` port + all 10 `api/` builders + `llm.clj` retry/cancel + auth (M4). First end-to-end: `print` mode (`modes/print.clj`, 102 LOC) answering one prompt — no TUI needed.
5. **Agent loop + tools** (2–4 wks): `app/loop.clj`, session/compaction, tools (bash/edit/write need care: process + fs + diff), `modes/interactive.clj` wiring.
6. **Packaging + tooling** (1–2 wks): `jolt build` pipeline replacing `build.cljc`, test runner `^:slow` split, lint/format gates, model generators.
7. **Extensions** (open-ended): B3 redesign decision; port shipped extensions after.

Estimate honesty: B1 transport is **decided** (babashka.http-client on both hosts — native on bb/JVM, over the jolt-lang/http-client shims on Jolt — with curl for SOCKS/https-scheme proxies, Jolt live streams, and the user's `:curl` mode; `http.cljc` ported), and its consumers are green on Jolt — `sse.clj` (33/109) and `libs.oauth`/`ai.oauth`/`ai.google_adc` (M4). M1 is closed (data.json on both hosts) — all 27 libs load and test green on bb/JVM, and json/jsonrpc/sse/aws_sigv4 are green on Jolt too. B3 is a research spike before it is labor.

---

## 7. Risks & open questions

1. **Jolt maturity**: the Chez backend is the only production target (Gambit is demo-grade); the `--library` entry + cross-`--target` flow are young — fine for a CLI, but verify each on the checkout before relying on it.
2. **Performance**: TUI frame loop (16ms, line diffs, grapheme widths) + token-streaming rates on Chez-interpreted-vs-compiled code — `jolt build` compiles; measure early with a streaming fixture. SCI-for-extensions perf unproven.
3. **Regex engine**: irregex vs Java — `keys.clj`, response parsers, `utils.clj` wrapping all need their test suites re-run (common patterns fine, edge features differ). Two open engine gaps sit on kmet paths: a 50-alternative pattern with `.*` branches stalls `re-find` — ~5 s on x86_64, never returns on Termux/aarch64 (`kmet.app.loop/retryable-error?`; see `jolt-bugs.md`) — and `.`/`^`/`$` apply the UNIX_LINES terminator set (`jolt-bugs.md`, worked around in `parse-dump-header`).
4. **Vendored libs, not pins**: `babashka.fs` / `babashka.process` come from the host — bb bundles them, jolt vendors the same namespaces (built-in; jolt's public surfaces are `jolt.fs` / `jolt.process`, the latter excluding zip/gzip). deps.edn carries no version to diff, so the pins are gone by design: re-verify the *surface kmet uses* on any Jolt upgrade (a `jolt test` run covers it; the vendored sources carry no version constants).
5. **Windows**: kmet supports it (Git Bash resolution, `\` zip entries, `fs` separators); Jolt's Windows FFI surface has known gaps (`process.ss`) — Windows is the last platform to light up, after Unix parity.
6. **No `/tmp` on Termux / `~` expansion / Android IME paste paths**: kmet carries Termux-specific workarounds — re-verify each on Jolt. One is already better: Jolt's `java.io.tmpdir` honors `$TMPDIR` (`host-static-methods.ss:1002,1016`), unlike bb's hardcoded `/tmp` — keep the explicit-dir pattern anyway. Burst-paste detection rides the §7 pipeline, which ports logically.

---

## 8. Full-suite `jolt test` status (2026-09-11)

The runner (M10) requires every namespace in a try/catch and reports load
failures with reasons; the full run without filters also loads the `^:slow`
and `^:bb-only` namespaces (their vars are then filtered out). bb/JVM is the
reference — full `bb test` runs green.

**Loads: all 107 namespaces load on Jolt — zero unloadable.** The
namespaces that never print a "Testing" line are not load failures:
`build-test` (10 vars) and `libs.test-archive` (3) are all `^:bb-only` (M5),
and `modes.test-overlay-input-smoke` (2) runs only under `jolt test-ext`.

**Non-slow suite:** green on the v0.8.6-series builds — 2032 tests / 13803
assertions, 0 failures, 0 errors (`v0.8.6-18-g64bdeff4`; re-verified on the
provider-load-order build `v0.8.6-29-gf85adb51`). On upstream main
(`v0.8.6-72-g0f7d1a11`, built locally) it is red for one reason only:
`kmet.app.loop/retryable-error?` stalls jolt's regex engine on its
50-alternative `retryable-error-regex` (first match ~5 s here, never returns
on Termux/aarch64), so `kmet.ai.test-llm` hits the runner's 15 s
per-namespace timeout and the cancellation cascades into later namespaces.
Every affected namespace is green when re-run alone: `libs.test-http` 25/90
plus the slow platform-transport test, `ai.test-oauth` 53/223,
`app.ui.test-session-selector` 31/130, `libs.test-edn-store` 17/40. Open
issue: `jolt-bugs.md`.

**Slow set (`jolt test-ext`):** `kmet.app.test-tools` green 2026-09-11
(`bash-executor`'s workarounds — §B2) and `kmet.tui.test-render-loop` green
via the M2 protocol-stub adapter; TUI-attributable reds: 0 (Unix). The
remaining reds are runner/network artifacts, not terminal gaps:

| namespace | reds | cause |
|---|---|---|
| `kmet.modes.test-overlay-input-smoke` | 2 F + 1 E | the test spawns a hardcoded `bb run` through a pty, so on Jolt it exercises bb's TUI; its stages need ~20 s while the Jolt runner kills each namespace after 15 s (`kmet.runner`: `deref f 15000`) |
| `kmet.ai.test-llm` | 1 E | `test-llm-codex-responses-end-to-end` — network e2e, interrupted by the ns timeout |

**Flaky (not in the deterministic set):** the frame-hook counting tests
`tui.test-compute/compute-change-invalidates-subscriber-and-schedules-frame`
and `tui.test-hiccup/dep-change-schedules-a-frame-through-the-hook` each
failed once in ~18 full runs with "rendering itself does not poke the hook"
(fired = 2 — one extra `schedule-frame!` during the post-flush render).
Jolt-only; never reproduces standalone, in partial-suite prefixes, or under
poke-site/drain instrumentation (5 instrumented full runs clean); bb is
stable over 5 full runs. Mechanism unpinned — rare timing/state interaction
(~10% per full run), possibly a leaked reaction from an earlier namespace
firing `schedule-frame!` into the test's hook.

---

## 9. `jolt/` — kmet's RFC 0014 provider lib

A self-contained library in the repo root (`jolt/deps.edn` + `jolt/src/`,
README in `jolt/README.md`) that supplies JDK classes the jolt ecosystem
does not supply, declared the RFC 0014 way: kmet's root `deps.edn` pulls it
in as `jolt.kmet/providers {:local/root "jolt"}`, and `jolt/deps.edn`
carries the `:jolt/provides` claims. It is **inert on bb/JVM** — no bb
classpath namespace requires a `jolt.*` ns and the babashka view excludes the
dir; the jolt view lints it (kmet.lint's mirror + the `.clj-kondo-jolt`
overlay), and both `bb lint` and `jolt lint` run both views.

### Why it exists

jolt core lacks `java.util.Base64/getMimeDecoder` and a
`HttpTimeoutException` ctor, and jolt.crypto (io.github.jolt-lang/crypto)
covers symmetric crypto plus EC/RSA keygen/signature but not those two.
kmet's production Google-ADC login (RS256) and the RS256 JWT paths ride
jolt.crypto; the MIME decoder is the first thing every PEM/PKCS parse
touches.

### Load-order semantics

RFC 0014's rules decide which provider answers a class, whatever loaded
first:

- a declared provider's class resolves through the claimer's install
  namespace, registered on the first reference;
- a registration for a claimed class from a non-claimer is **held** until
  the claimer loads, then replayed through the same guard — what the
  provider implements wins, members it does not answer still land — and
  once the provider has registered a member, a registration of that member
  from anywhere else is **dropped** (with a warning);
- `java.util.Base64` is out of scope for that machinery: a claim on a class
  the runtime implements is refused, and a member-miss autoload for
  implemented classes is a separate upstream item.

What that means for kmet's three load-order payloads:

1. `jolt.kmet.providers` requires **nothing but `jolt.host`**: crypto's
   classes load on their own first class reference and are attributed to
   crypto, so no require is needed to pin an order;
2. the `jolt/deps.edn` `:jolt/provides` claim (now only
   `HttpTimeoutException`) autoloads on the **first reference**, whatever
   registered the class earlier — verified: a bare
   `(java.net.http.HttpTimeoutException. "x")` loads the provider with no
   guard at all;
3. the **guarded requires** in `kmet.libs.crypto` / `kmet.ai.google-adc`
   remain, for `java.util.Base64` only: the claim on it is refused, so the
   guard is the only thing that installs `getMimeDecoder` before a
   referencing namespace is analyzed. Verified: without the guard,
   `No matching field or method: java.util.Base64/getMimeDecoder`.

This pattern is the AGENTS.md convention for any future consumer. The guard
itself is unchanged — a claim on `java.util.Base64` is still refused.

### Provided (verified against the bb/JVM reference)

| shim | notes |
|---|---|
| `java.util.Base64/getMimeDecoder` | pure-Clojure MIME decode (non-alphabet chars discarded, JDK rules), returns the same `[B` type core's decoder returns |
| `java.net.http.HttpTimeoutException` ctor | `jolt.host/throwable` + `register-class-supers!` edge to `java.io.IOException` — `instance?`/`catch` on Throwable/Exception/IOException match the JVM; `toString`/`ex-message`/`getCause` identical; only the `String` ctor exists, as on the JDK |

Both are exercised by kmet's suite: `libs.test-crypto` (PEM paths,
10 tests/21 assertions green), `ai.test-llm`'s transport-error test, and the
EC/RSA sign paths (`pkcs8-ec`, `sign-jwt-es256`, `sign-jwt-rs256`) over
jolt.crypto.

**Re-verified 2026-09-11 on upstream main (`v0.8.6-72-g0f7d1a11`):** both
shims are still unshimmed upstream — a bare
`(java.util.Base64/getMimeDecoder)` answers
`No matching field or method: java.util.Base64/getMimeDecoder` and a bare
`(java.net.http.HttpTimeoutException. "x")` answers
`No matching ctor found for class java.net.http.HttpTimeoutException`,
while the kmet suite loads them through this provider — the shims stay as
is (open upstream gaps: `jolt-bugs.md`).

### Status

On the v0.8.6-series builds `jolt test` is fully green — 2032 tests / 13803
assertions, 0 failures, 0 errors. On upstream main
(`v0.8.6-72-g0f7d1a11`) the suite is red for one reason only: the regex
stall in `kmet.ai.test-llm` and the timeout-cancel cascade it causes (§8);
every affected namespace is green when re-run alone.