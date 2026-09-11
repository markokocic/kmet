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
its §§4–7,9 cover the terminal adapter); `bb-jolt.md` (JOLT-1…6 — field bug
reports of Clojure-semantics divergences found by running kmet's suite under
Jolt, each with a minimal repro; §8 summarizes what is live today).

---

## 1. Inventory — what kmet is made of

| area | files | LOC | character |
|---|---|---|---|
| `libs/` (generic utils) | 27 | ~8.3k | half pure (diff, yaml, markdown, highlight, reakt, num), half JVM-bound (http, process, crypto, archive, oauth, jsonrpc, sse) |
| `ai/` (providers/auth) | 13 + 10 `api/` | ~8.8k | request builders (pure) + streaming over `libs.http` + auth/token caches on disk |
| `tui/` (generic TUI) | 11 + 21 components | ~11.7k | ~95% pure; only `terminal.clj` (JLine) + `core.clj` reader/timers touch the host |
| `app/` (agent/tools/ui) | 14 + 11 tools + 27 ui | ~17.4k | business logic + tools (bash/edit/grep/ls/read/write/find) + `extensions.cljc` (SCI) |
| `modes/` (entry) | 2 | ~4.5k | `interactive.clj` (4.4k, TUI wiring) + `print.clj` (headless) |
| root (`core/config/debug/extension/build`) | 5 | ~1.6k | CLI dispatch, config loading, extension contract, bb-based build |
| `extensions/` (shipped, non-`target/` source) | 59 | ~17k | opt-in extensions incl. `mcp-adapter`, `clojure`, `lsp-adapter`, `tools.clj` (+106 `target/` test-fixture files excluded from the count) |
| `test/` | 117 | ~34.9k | `clojure.test`, `kmet.runner` custom runner with `^:slow` split |

External deps (`deps.edn` + `bb.edn`): `babashka/fs`, `babashka/process`
(first-party bb libs), `borkdude/deps.clj` (Maven resolution),
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
now dispatches `:jolt` exactly like `:clj` plus that stream carve-out.
The transport is also a user setting (`settings.edn` `:http-transport`,
`/settings` HTTP transport row): `:platform` (default, as above) or
`:curl` — every request through curl on both hosts; `test-http` covers
every request contract under both modes on both hosts (Jolt: 25/90).
Verified: deps.edn carries `org.babashka/http-client` 0.4.24 +
`io.github.jolt-lang/http-client` — **pinned to `markokocic/http-client`
`fix/bionic-addrinfo` (`4958c9d`, one commit on upstream main
`4744256f83e5`) until its upstream PR merges**, because upstream's `ai_addr`
offset is glibc's and every platform-transport request fails EFAULT on
bionic/Android (bb-jolt.md JOLT-9); its transitive
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
uses for Jolt's own spawning — reusable patterns).

### B3. Extension isolation (`app/extensions.cljc` — SCI, 1668 LOC)

Each extension evaluates in its own **SCI context** (`sci/init`,
`sci/eval-form`): private ns registry + loader serving own files, declared
Maven jars (resolved in-process via `borkdude/deps.clj`), and host
classpath; shared layers (`kmet.extension`, `clojure.*`, `babashka.*`,
`kmet.tui.*`, `kmet.libs.*`) injected by reference. Plus bb-import tables,
bundled-lib redirection (rewrite-clj, edamame, …), per-extension
deps.edn, load-fn error handling, classpath-overrides matching bb.

**SCI substrate VERIFIED on Jolt 2026-09-11 (`v0.8.6-32-gc4ebc570`, then
re-checked on the rebase base `69a6f592` / `main`) — no longer the
blocker.** `make sci` (pure-Chez source-load gate) exits 0 at 412/424
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

**Interop inside interpreted code — measured, and fixed on a jolt branch
(2026-09-11).** Extension sources — and every deps.edn library they
load — run interpreted, and SCI's interpreter routes *both* instance and
static method calls through `sci.impl.reflector`: `get-methods` delegates to
`clojure.lang.Reflector/getMethods` and returns real
`java.lang.reflect.Method` objects that the transliterated
`invoke-matching-method` matches on (`getParameterTypes`, `getModifiers`,
`.invoke`, `Compiler/subsumes`). jolt registers only
`Reflector/invokeConstructor` / `invokeStaticMethod` / `invokeInstanceMethod`
(all three work when called directly), so stock SCI answers ctors and `str`
and nothing else: against a `:classes` + `:imports` table,
`System/currentTimeMillis`, `System/getenv`, `System/getProperty`,
`Math/round`, `Integer/parseInt`, `Long/parseLong`,
`Character/isWhitespace`, `Thread/sleep`, `.indexOf`, `.toUpperCase`,
`.getBytes`, `.getName`, `.getScheme`, `.size` and `.toString` all fail with
one error (`Reflector/getMethods` called in a shape jolt does not register:
"incorrect number of arguments 5"). That is not a corner: **16 of 41 `src`
extension files use interop** (all 16 via `System/` statics —
`currentTimeMillis` ×31, `getenv` ×20, `getProperty` ×20 — plus instance
methods: `.indexOf`, `.getBytes`, java.time chains), and loaded libraries do
too (cljfmt 0.16.5: `java.io.File` in 3 of its 12 sources). No `.-field`
access anywhere in the corpus (0 hits).

**MERGED upstream — jolt PR #933 (commit `847d9499`, merge `0f7d1a11`,
`v0.8.6-72`+, unreleased after v0.8.6; the PR carries the changelog entry
`937c8c1f`).**
The clean fix needed no
SCI patch and no shadow: jolt registers the lookup SCI actually calls,
`clojure.lang.Reflector/getMethods`, plus the two companions the same path
needs — `Class.cast` (jolt reports every parameter as `Object`, where the cast
is the identity; without an arm the lookup fell through to resolving the class
by name, which raised for `java.lang.Object`) and `Util/sneakyThrow` (the
rethrow SCI's invoke ends every call with — a throwing method otherwise
reported "No matching field or method: clojure.lang.Util/sneakyThrow" instead
of its own exception). `getMethods` answers from the registries
`Class.getMethods` already reads; for a class whose methods are a `cond` over
the receiver (String, the collections) it answers with a member carrying the
dispatch rule — parameter count pinned in a slot, instance calls routed
through `record-method-dispatch`, statics through `host-static-call`,
`canAccess` → yes — under the same "jolt reports what its registries know"
model as `reflect-member-model`. `Method.invoke` also reads a lone nil
argument array as the empty one (how a reflective caller spells a
zero-parameter call). **Verified:** the whole interop matrix above is green on
*stock* SCI 0.13.53 (`System/currentTimeMillis`/`getenv`/`getProperty`,
`Math/round`, `Integer/parseInt`, `Character/isWhitespace`, `Thread/sleep`,
`.indexOf`/`.toUpperCase`/`.getBytes`/`.getName`/`.getScheme`/`.size`/
`.toString`, constructors, `(Thread. (fn [] …))`) and so is kmet's loader shape
end-to-end
(`$TMPDIR/kmet-loader-smoke.clj`: `:load-fn` + `:namespaces` injection +
interop inside an interpreted extension's own source). `make sci` 412/424
(floor) unchanged; `make scifunctional` and `unit.edn`'s
`reflect-member-model` (26 rows) gained the cases; `make unit` shows the same
6 `/tmp`-based failures as `main` (Termux has no `/tmp`). **Rebased onto
`69a6f592` (2026-09-11) and re-verified there** — byte-identical (`188
insertions, 7 deletions`), and the gates hold on the new base: `make sci`
412/424, `scifunctional` OK, `reflect-member-model` 26/26, interop matrix +
loader smoke green. `make corpus` reports the same 9 crashes (3×
`ISO-2022-JP`, 2× `Shift_JIS`, 2× `windows-1252` from upstream's `Charset`
object change, 2× `/tmp/jolt-spit` from the environment) at the same
5494/5513 on `origin/main` without the patch, so none are attributable to
it. `make smoke`/`make loaderconf` need a built binary, and `make testbin`
cannot link on this Termux toolchain (system-Chez iconv), unrelated to
source. Upstream merged the branch (PR #933), so the fallback stays
unwritten; it had been a ~30-line
jolt-side shadow of `sci.impl.reflector` on a jolt-only root (verified
too: `sci/impl/reflector.clj` with `get-methods` → one-element `ArrayList`
sentinel carrying the class, `maybe-fi-method` → nil, `box-arg` →
identity) — but note it must never
sit under `src/`, where bb's classpath would gain a namespace shadowing its
built-in SCI.

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
| M2 | `tui/terminal.clj` (JLine raw/timed-reads/size) + `core.clj` reader/timers/resize/drain | termios FFI (Unix) + kernel32 FFI (Windows); `future` reader + `locking` + gen-counters — see `jolt-tui.md` §§4–7,9. Evaluated 2026-09-06: `jolt-lang/glimmer-tui` (ncursesw via FFI, Unix-only, fullscreen `initscr` takeover) rejected — wrong architecture for the inline ANSI/scrollback model; JLine stays on bb (`jolt-tui.md` §2 decision) | rewrite ~500 LOC (Jolt only) |
| M3 | `libs/crypto.clj` (315 LOC: RSA/EC `KeyFactory`, `SHA256withRSA/ECDSA` `Signature`) + `libs/aws_sigv4.clj` (213 LOC: `MessageDigest` SHA-256, `Mac` HmacSHA256, `HexFormat`, `Normalizer`?) — grep the exact class list before the FFI design | OpenSSL FFI following `mvn_http.clj`'s libcrypto/libssl loading (note macOS boringssl SIGABRT hazard — explicit Homebrew paths only); RSA via libcrypto; `SecureRandom` via OS source. The `io.github.jolt-lang/crypto` git dep is in `deps.edn` (RFC 0014). **Verified 2026-09-09:** the symmetric half holds — `test-aws-sigv4` fully green on Jolt (5 tests/18 assertions), so `MessageDigest`/`Mac` are covered. The asymmetric half still gaps — `test-crypto` on Jolt: 10 tests, 2 pass, 8 fail in key-parse/sign paths: `KeyPairGenerator` has no provider (`No dependency provides java.security.KeyPairGenerator … :jolt/provides … (RFC 0014)`), `Base64/getMimeDecoder` is unshimmed (PEM/PKCS parse), and JWK hits `No matching field found: toByteArray for class java.lang.Long`. The `Base64/getMimeDecoder` half is now covered by kmet's own `jolt/` provider lib (§9). Re-verified in the 2026-09-09 full-suite run (§8): 10 tests, 2 pass, 1 failure (`test-parse-private-key-rejects-garbage` — `getMimeDecoder`) + 7 errors (4× `getMimeDecoder`, 2× JWK `toByteArray`-on-Long, 1× `KeyPairGenerator`); the same `KeyPairGenerator` gap surfaces in `ai.test-google-adc` (service-account flow) and `libs.test-oauth/test-jwt-bearer-token`. **CLOSED 2026-09-10:** RSA landed in jolt.crypto (merged upstream as jolt-lang/crypto#8, merge `79ecb3d` — keygen + `SHA*withRSA` + `KeyFactory`), the JWK `.toByteArray` gap landed in `kmet.libs.crypto/bigint->bytes`, and `test-crypto` is fully green on Jolt (10 tests/21 assertions) | done (jolt.crypto + kmet.libs.crypto) |
| M4 | `libs/oauth.clj` (611) + `ai/oauth.clj` (1012) + `ai/google_adc.clj` (121) — browser launch, localhost callback server, token cache | `ServerSocket` shim exists (`stdlib/jolt/socket.clj`, gated on `(require 'jolt.socket)`); browser launch via `jolt.process`; token cache via `spit`/`slurp`. **Verified 2026-09-09:** `test-oauth` on Jolt: 26 tests, 1 failure + 1 error — `test-callback-server` times out (localhost callback; `ServerSocket` shim is gated on `(require 'jolt.socket)`) and `test-jwt-bearer-token` fails on the M3 `KeyPairGenerator` gap. **Callback server FIXED** (commit `596f439`, 2026-09-09: jolt's `readLine` kept the trailing `\r`, so the header-block end arrived as `"\r"` — truthy — and the reader blocked one line past the headers forever; plus a socket-shim read gap); re-verified in the full-suite run (§8): 26 tests/65 assertions, **1 error only** (`test-jwt-bearer-token`, M3). **Upstream v0.8.6 + main (re-verified 2026-09-11):** JOLT-1 is fixed and `ai.test-oauth` is 53 tests/223 assertions fully green on jolt, but the `readLine` claim was too broad: v0.8.6's fix covers `System/in`'s `read-line`, not the `BufferedReader`/`InputStreamReader` path the callback server uses — on `v0.8.6-72-g0f7d1a11` a socket line `"x\r\n"` still reads `"x\r"` (probed 2026-09-11; same for the ByteArrayInputStream construction), so kmet's `str/trim` emptiness check is LOAD-BEARING and stays. The 3-arg socket `write` stands too (portable, identical on both hosts — the 2-arg `write(byte[])` still throws on jolt's SocketOutputStream, verified 2026-09-10). | adapt ~1.7k LOC |
| M5 | `libs/archive.clj` (46 LOC, `ZipFile` read) + `sse.clj` CRC-32 (pure-Clojure `libs/hash.clj/crc32` since the port — Bedrock frame tests green on Jolt, no zip work) + `extensions.cljc:910,921` (`JarFile` probes) + `build.cljc:227,245,389` (`ZipOutputStream` uberjar/pack-extension). (`ai/models.clj` needs no zip work — catalogs load via `io/resource`, which answers file:/jar:/embedded URLs alike.) | `jolt.fs` explicitly EXCLUDES zip/gzip (`stdlib/jolt/fs.clj:12`: "java.util.zip not shimmed yet"). **DECIDED 2026-09-08: bb-only until the `jolt build` rewrite** — `build.cljc`/`libs/archive.clj` entry points throw `::bb-only` under Jolt, their tests carry `^:bb-only` (the runner skips them there); zip/jar work defers to extension-jar materialization via unzip (jolt's own mvn-jar model) | rewrite build; archive via FFI or subprocess. Note:
| M6 | `build.cljc` uberjar assembly (`bcp/get-classpath`, `ZipOutputStream` resource listing) + model-catalog embedding | No classpath concept; `jolt build` embeds source roots differently. Model catalogs (`ai/model_data/` + manifest) become embedded resources — `io.ss` has `register-embedded-resource!` and `io/resource` answers a `java.net.URL` from both disk and a built image | adapt ~200 LOC |
| M7 | `libs/clipboard.clj`, `libs/terminal_image.clj` (Base64 — shimmed, keep), OSC-52/kitty-graphics emit | clipboard via platform subprocesses (`pbcopy`/`xclip`/`clip`) through `jolt.process`; image protocols are pure emit logic | small |
| M8 | `config.clj` (XDG paths, EDN load/save, file watching?) | `jolt.fs` (vendored `babashka.fs`, minus zip) covers paths; `spit`/`slurp`/EDN portable; watcher → poll (same as `tui.theme`) | adapt |
| M9 | `debug.clj` (file logging) + crash/render logs | `(spit path text :append true)` (`jolt-io-writer` is 1-arg — `io.ss:1314-1323`; `spit` takes `:append` — `io.ss:1164-1195`); timestamps via the `io.github.jolt-lang/time` dep (already in `deps.edn`) or manual format. Note: Jolt's `java.io.tmpdir` honors `$TMPDIR` (`host-static-methods.ss:1002,1016`), unlike bb's hardcoded `/tmp` — keep the explicit-dir pattern anyway | small |
| M10 | `bb.edn` tasks (22: `run` + 21: uberjar/build/test/test-ext/changed/test-changed/test-ext-changed/lint-changed/format-changed/format-check-changed/nrepl/check/generate-models/generate-image-models/check-model-data/pack-extension/lint/format/format-check/help) | **DONE (test task):** `kmet.runner` is now host-aware and tolerant — every test namespace is required in a try; unloadable ones (babashka-internal requires like `babashka.classpath`/`babashka.classes`, `java.time.format.DateTimeFormatter` gaps, …) are reported and skipped, the rest run. Per-var `^:slow` split + per-var filters work on BOTH hosts (`jolt test` non-slow / `jolt test-ext` slow; bb.edn `:paths ["src" "test"]` supplies the roots under jolt). Engine: bb = per-var output capture + ref counters; jolt = `clojure.test/test-vars` with jolt's own process-wide `counters` atom read as before/after deltas (`jolt?` = `(find-var 'clojure.core/*jolt-version*)`). **Full-suite run 2026-09-09 (`jolt v0.8.5-36-gbac15682`): all 107 namespaces load — zero unloadable** (the earlier babashka-internal/`java.time` load gaps are gone) and 1928 tests run end-to-end; deterministic result 18 failures + 18 errors, all jolt-only — **11 + 16 after the same-day kmet-side workarounds** for causes 2/4/7 (see §8). The `^:slow` set is a separate story — **not green on jolt** (§8): 19 F + 7 E, of which 8 reds are TUI-attributable. Remaining M10 work: `jolt build` packaging, lint/format gates, model generators | mostly done for tests; slow-set status in §8 |
| M11 | `clojure.spec.alpha` (SCI-context injection only), `clojure.walk` (2 requires: `libs/json.clj:16`, `ai/constrained_sampling.clj:13`), `BigDecimal` (`edn_writer` + SCI class table) | spec: absent from `stdlib/` (verified — declare `org.clojure/spec.alpha` explicitly per README's "terminal dependency" rule, or rewrite the one use); `walk`: present (`stdlib/clojure/walk.clj`, seed-embedded — keep); `BigDecimal`: PRESENT (`host/chez/java/bigdec.ss`: `M` literals + `with-precision` per README — the earlier "absent" claim was wrong; just port the call sites) | small |
| M12 | `defrecord` (27 files) + `reify` (6 files) + protocols + `deftype` (zero definitions — only comments) | README Differences confirms `deftype`/`defrecord`/`reify`/`extend-protocol`, multimethods, STM, `future`/`promise`/`agent` and `core.async` behave as on the JVM — still verify early: `satisfies?`-on-reify semantics, `defrecord` positional factories, protocol dispatch for `IComponent`/`IFocusable`. The TUI's `satisfies?` avoidance notes (AGENTS.md SCI gotcha) need re-checking on Jolt | verify early, affects everything |
| M13 | Custom `defcomponent`/`with-let` macros + clj-kondo hooks | Jolt compiles macros normally (self-hosted compiler) — should port; re-verify hygiene/&env behavior (`go`-style passes are async-only, plain macros fine). Kondo hooks keep working (source-level) | verify early |
| M14 | `java.util.concurrent` — 4 sites: `LinkedBlockingQueue`+`TimeUnit` (`libs/sse.clj` idle-deadline reader — now `ArrayBlockingQueue`, fixed 2026-09-09), `ReentrantLock` (`app/session.clj:154,296`, file-mutation lock), `Callable` (`app/extensions.clj:738`, SCI class table) | **Verified 2026-09-09:** `LinkedBlockingQueue` has NO ctor on Jolt (`No matching ctor found`) — `sse.clj` now uses `(ArrayBlockingQueue. 65536)`; verified `.put`, `.poll n TimeUnit`, `.offer`, `.size`, `.remainingCapacity`, and `TimeUnit/MILLISECONDS`. `ReentrantLock` still assumed shimmed (session lock not yet run on Jolt); `Callable` becomes a fn; `locking` covers the session lock | small |
| M15 | `java.net.URI/URL/URLEncoder`, `Normalizer`, `Charset`, `HexFormat`, `Instant/DateTimeFormatter/ZoneId`, `PushbackReader`, `StringReader/Writer` | Mostly shimmed (host-interop list + `io.ss`/`io-streams.ss`); URL/URI surface exists (`jolt.socket` gating for sockets); time values via time lib. **Verified 2026-09-09 (reader surface):** `io/reader` rejects `proxy` Readers (`Cannot open <reify> as a Reader` — `jolt-io-reader`, `io.ss:1291`); `BufferedReader` ctor is identity so a proxy Reader lacks `.readLine`/`.close`; `InputStreamReader` over a proxy `InputStream` constructs (reads dispatch to the override); `PipedInputStream` + `io/reader` + `.readLine` works. `sse.clj` works around all three (see B1). **Verified 2026-09-09 (URI + java.net.http surface, full-suite run §8):** the multi-arg `URI` ctors are missing — the 7-arg ctor throws `incorrect number of arguments 7 to …` (kmet's `azure_openai_responses/normalize-azure-base-url` catch-swallowed it, so Azure base URLs were never forced to `/openai/v1`; 5 failures in `test-llm-azure-url` — **fixed kmet-side 2026-09-09**: the URL is rebuilt by hand from the parsed pieces, no multi-arg ctor); the single-arg ctor gap (JOLT-1 — accepted illegal characters the JDK rejects, bb-jolt.md) is **FIXED upstream in v0.8.6** (verified 2026-09-10: junk URIs throw). The multi-arg ctors are still missing (verified 2026-09-10: both the 7-arg and 4-arg ctors throw `incorrect number of arguments`), so the hand-built azure URL stays. `java.net.http.HttpTimeoutException` exists as a class but has NO ctor (`No matching ctor found`, `test-llm-transport-error-message`). Remaining call sites still need per-site audit | audit per site |
| M16 | Jolt host string/number/format semantics (kmet's UTF-16-indexing code — §8 cause 3) | Jolt (Chez) strings index by **code point**, not UTF-16 code unit: `(count "👨‍👩‍👧‍👦")` is 7 (one char per astral code point) vs 11 surrogate units on bb/JVM, and `nth` returns the full code point. Every kmet scan that assumes surrogate pairs over-advances by one per astral char: `kmet.tui.utils` grapheme/width machinery (`codepoint-len`, `nchars` = 2 for astral) miscounts ZWJ chains and truncation, and markdown-table slicing runs off the string end — 4 failures + 1 error live (visible-width ZWJ chain, table emoji alignment ×3, robustness `StringIndexOutOfBounds`; bb-jolt.md's JOLT-3 attribution of these is wrong — they are pure index scans, no Matcher). **FIXED kmet-side 2026-09-09** — `codepoint-len` (utils.clj) derives the element span from the string itself, 2 only when index I is a high surrogate followed by a low surrogate — the same pairing test `code-point-at` uses — never from cp magnitude, and the four inline `nchars` sites (truncate-to-width ×2, split-long-word, slice-by-column) now route through it: the walkers step in the host's own element model (a semantic no-op on bb/JVM; 1 per astral cp on Jolt), so the 4 F + 1 E are green on both hosts (§8). Same family: `clojure.core/parse-long` returned a BigInt on overflow where bb/JVM returns nil (yaml plain-scalar fallback keeps the string — `libs.test-yaml/test-numbers`) — **FIXED upstream in jolt#927** (PR #932, merge `684f6ea0`, `v0.8.6-54`+), the `num/parse-long` wrapper was removed 2026-09-10; `format`'s missing `%g` conversion (`UnknownFormatConversionException: 'g'` — model-selector cost lines `(format "%.4g" …)`, 4 errors) is **FIXED upstream in v0.8.6** (verified 2026-09-10 — green with no kmet change) | width scans: done (kmet-side, above); `format %g`: fixed upstream in v0.8.6; `parse-long` overflow: fixed upstream (jolt#927, PR #932) |

---

## 4. Verified `libs/` status (2026-09-08 snapshot; json/jsonrpc/sse/crypto/aws_sigv4/oauth re-verified 2026-09-09)

Cold-ran every `kmet.libs.*` namespace under Jolt (`jolt v0.8.5`, threaded
Chez 10.x) — require + load each, then run its test suite (or probe its
public fns when no test file exists). On bb/JVM: **all 27
load and test green** — the cheshire → data.json swap unblocked the libs
that depended on M1. Jolt side (re-verified 2026-09-09, `jolt v0.8.5`): **json/jsonrpc/sse/aws_sigv4 fully green; crypto partially (M3 asymmetric gaps); oauth partially (M4); archive bb-only (M5)** (counts below — re-verified rows carry a 2026-09-09 note; the rest is still the 2026-09-08 snapshot).

| lib | bb/JVM | Jolt | notes |
|-----|--------|------|-------|
| `archive` | 🟢 | 🔴 | bb-only on Jolt (2026-09-08, M5): `::bb-only` entry guard + `^:bb-only` tests; zip work deferred to extension-jar materialization (unzip) |
| `aws_sigv4` | 🟢 | 🟢 | Jolt 2026-09-09: 5 tests/18 assertions green — `MessageDigest`/`Mac` via the crypto dep hold (M3 symmetric half done) |
| `clipboard` | 🟢 | 🟢 | uses `babashka.process`, works |
| `concurrent` | 🟢 | 🟢 | `spawn` returns `Thread`; works |
| `context` | 🟢 | 🟢 | tests pass (11/11) |
| `crypto` | 🟢 | 🟡 | Jolt 2026-09-09 full-suite re-run: 10 tests/13 assertions, 2 pass — still gaps (M3): `KeyPairGenerator` without provider (RFC 0014 `:jolt/provides`), `Base64/getMimeDecoder` (1 failure + 4 errors), `.toByteArray` on Jolt Long (JWK, 2 errors) |
| `diff` | 🟢 | 🟢 | pure, works |
| `dynamic_value` | 🟢 | 🟢 | tests pass (53/53) |
| `edit_diff` | 🟢 | 🟢 | uses `java.text.Normalizer`, `java.util.regex.Pattern`; works |
| `edn_store` | 🟢 | 🟢 | tests pass (40/40) |
| `edn_writer` | 🟢 | 🟢 | pure, works |
| `hash` | 🟢 | 🟢 | pure, works |
| `highlight` | 🟢 | 🟢 | tests pass (139/139) |
| `hooks` | 🟢 | 🟢 | pure, works |
| `http` | 🟢 | 🟡 | **ported** — Jolt runs direct/http-proxy traffic through babashka.http-client over the jolt-lang/http-client shims (deps.edn: org.babashka/http-client 0.4.24 + io.github.jolt-lang/http-client), curl for SOCKS/https-scheme proxies and `:as :stream` (see B1); the `:http-transport` setting can force curl for everything. test-http 25/90 green on Jolt (every contract under both modes) — **the platform transport needs both bionic fixes**: the `ai_addr` offset (bb-jolt.md JOLT-9), which comes from the `markokocic/http-client` fork pin in deps.edn until its PR merges, and the `errno` accessor (JOLT-8), fixed upstream in jolt PR #939 (`v0.8.6-67`+) — any stock main/release build carries it, the local `patchset` build is no longer needed (re-verified 2026-09-11 on `v0.8.6-72-g0f7d1a11`). Both pre-exist the #926/#927 rebase. Loads on bb |
| `json` | 🟢 | 🟢 | Jolt 2026-09-09: 4 tests/18 assertions green — data.json resolves via deps.edn (M1 closed) |
| `jsonrpc` | 🟢 | 🟢 | Jolt 2026-09-09: 17 tests/41 assertions green (M1 closed) |
| `markdown` | 🟢 | 🟢 | tests pass (137/137) |
| `num` | 🟢 | 🟢 | portable predicates, works |
| `oauth` | 🟢 | 🟡 | Jolt 2026-09-09 full-suite re-run: 26 tests/65 assertions, **1 error only** — `test-jwt-bearer-token` (JWT signing, M3 `KeyPairGenerator`); the callback-server timeout is FIXED (commit `596f439` — jolt `readLine` keeps the trailing `\r`, breaking the header-block end test) |
| `process` | 🟢 | 🟢 | uses `babashka.process`; works |
| `reakt` | 🟢 | 🟢 | tests pass (30/30) |
| `sse` | 🟢 | 🟢 | Jolt 2026-09-09: 33 tests/109 assertions green — reader ported (`ArrayBlockingQueue`, `.read` loop, `body->reader`; M1 closed) |
| `terminal` | 🟢 | 🟢 | uses `java.time`, `java.lang.ProcessHandle`, `java.util.Base64`, `clojure.java.io`; works |
| `terminal_image` | 🟢 | 🟢 | tests pass (41/41) |
| `usage` | 🟢 | 🟢 | pure, works |
| `yaml` | 🟢 | 🟢 | bb: 20/20; Jolt: 20/20 (core `parse-long` — overflow is nil on both hosts since jolt#927, PR #932; the `num/parse-long` wrapper was removed 2026-09-10) |

**bb/JVM: all green (27).** Jolt (2026-09-09): json/jsonrpc/sse/aws_sigv4 green;
crypto partially green (M3 asymmetric gaps: `KeyPairGenerator`, `Base64/getMimeDecoder`,
JWK `.toByteArray`); oauth partial — only the M3 JWT-signing error left (callback server
fixed, commit `596f439`); archive bb-only (M5); yaml 19/20 (M16 `parse-long`).
M1 is closed — data.json resolves on both hosts. Whole-suite re-verification
2026-09-09 (`jolt v0.8.5-36-gbac15682`): `jolt test` loads all 107 namespaces and
runs 1928 tests end-to-end — every lib row above ran in-suite with these counts;
see §8 for the whole-suite status incl. the app/ai/tui namespaces beyond `libs`.

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

Estimate honesty: B1 transport is **decided** (babashka.http-client on both hosts — native on bb/JVM, over the jolt-lang/http-client shims on Jolt — with curl for SOCKS/https-scheme proxies, Jolt live streams, and the user's `:curl` mode; `http.cljc` ported). Remaining B1 work is `libs.oauth`/`ai.oauth`/`ai.google_adc` (M4, needs `jolt.socket`) — the `sse.clj` reader is ported (2026-09-09, 33/109 green on Jolt). M1 is closed (data.json on both hosts) — all 27 libs load and test green on bb/JVM, and json/jsonrpc/sse/aws_sigv4 are green on Jolt too. B3 is a research spike before it is labor.

---

## 7. Risks & open questions

1. **Jolt maturity**: the Chez backend is the only production target (Gambit is demo-grade); the `--library` entry + cross-`--target` flow are young — fine for a CLI, but verify each on the checkout before relying on it.
2. **Performance**: TUI frame loop (16ms, line diffs, grapheme widths) + token-streaming rates on Chez-interpreted-vs-compiled code — `jolt build` compiles; measure early with a streaming fixture. SCI-for-extensions perf unproven.
3. **Regex engine**: irregex vs Java — `keys.clj`, response parsers, `utils.clj` wrapping all need their test suites re-run (common patterns fine, edge features differ). **Regex `Matcher` divergences (bb-jolt.md JOLT-3/JOLT-4; the 2026-09-09 live ones) FIXED upstream** in jolt PR #922 (merge `1e5036a5`, `v0.8.6-31-g1e5036a5`+): `.find(int)` scans from the index (with the JVM's `IndexOutOfBoundsException` outside `[0, length]`), and `.region` / `.regionStart` / `.regionEnd` / the no-arg `.reset` exist. kmet's `subs`-slice `match-at` and the `.region`-free ignore-window were removed with the fix (verified 2026-09-10, both hosts). Note: the *width* failures previously blamed on the regex engine are actually the M16 string-indexing gap — see §8 cause 3.
4. **`vendor/` pins**: the vendored sources carry no version constants to diff against `deps.edn` — re-verify the `babashka/fs` + `babashka/process` pins by file comparison on any Jolt upgrade.
5. **Windows**: kmet supports it (Git Bash resolution, `\` zip entries, `fs` separators); Jolt's Windows FFI surface has known gaps (`process.ss`) — Windows is the last platform to light up, after Unix parity.
6. **No `/tmp` on Termux / `~` expansion / Android IME paste paths**: kmet carries Termux-specific workarounds — re-verify each on Jolt. One is already better: Jolt's `java.io.tmpdir` honors `$TMPDIR` (`host-static-methods.ss:1002,1016`), unlike bb's hardcoded `/tmp` — keep the explicit-dir pattern anyway. Burst-paste detection rides the §7 pipeline, which ports logically.

---

## 8. Full-suite `jolt test` status (2026-09-10 — jolt v0.8.6-18-g64bdeff4)

Snapshot of the whole suite under Jolt (`jolt v0.8.6-18-g64bdeff4`, threaded
Chez 10.x; bb/JVM is the reference — full `bb test` runs green). Prior
snapshot 2026-09-09 (`jolt v0.8.5-36-gbac15682`): 1928 tests / 12078
assertions, deterministic 18 failures + 18 errors, every one jolt-only.
The runner (M10) requires every namespace in a try/catch and reports load
failures with reasons; the full run without filters also loads the `^:slow`
and `^:bb-only` namespaces (their vars are then filtered out).

**Loads: all 107 namespaces load on Jolt — zero unloadable** (no skip report
in any of ~18 full runs). The four namespaces that never print a "Testing"
line are not load failures: `build-test` (10 vars) and `libs.test-archive` (3)
are all `^:bb-only` (M5), `modes.test-overlay-input-smoke` (2) and
`tui.test-render-loop` (5 of its 6 vars are `^:slow`) run under
`jolt test-ext` — where they **error** on jolt (`Unknown class
TerminalBuilder`; the suite builds a virtual JLine terminal), not pass;
see the test-ext paragraph below.

**Slow set (`jolt test-ext`) is NOT green — 19 F + 7 E (2026-09-10, same
host).** The non-slow run above says nothing about it: the slow tests are
the subprocess-, pty- and network-driven ones. Breakdown:

| namespace | reds | cause |
|---|---|---|
| `kmet.app.test-tools` | 17 F | jolt-only; the bash/pipe tests (`stdout`+`stderr` merge) — B2 (subprocess pipe semantics), not TUI. Green on bb (10 tests / 22 assertions) |
| `kmet.tui.test-render-loop` | 5 E | `Unknown class TerminalBuilder` — JLine; needs the jolt-tui ITerminal adapter (`jolt-tui.md` §0) |
| `kmet.modes.test-overlay-input-smoke` | 2 F + 1 E | pty-driven app smoke test; the 15 s ns timeout interrupts it (`InterruptedException: future deref`). Needs the adapter + input pipeline |
| `kmet.ai.test-llm` | 1 E | `test-llm-codex-responses-end-to-end` — network e2e, interrupted by the ns timeout |

**TUI-attributable: 8 of the 26 reds** (render-loop + overlay-input-smoke);
the same two namespaces are covered in `jolt-tui.md` §0.

**Result of the non-slow run (2026-09-10): 1981 tests / 13277 assertions, deterministic 1 failure + 10
errors — every one jolt-only** (bb is green on the affected namespaces:
60 tests / 338 assertions). Grouped by common cause:

| # | cause (jolt gap) | count | failing tests | status |
|---|---|---|---|---|
| 1 | JDK class/ctor surface (remainder after the `jolt/` lib + upstream fixes): `KeyPairGenerator`/`Signature` without an RSA provider (4 E — RFC 0014), BigInteger shim as Jolt `Long`/`BigInt` — `.toByteArray` (5 E) | 9 E | `libs.test-crypto` (sign-jwt-rs256 E — KPG RSA; pkcs8-rsa E — Sig SHA256withRSA; jwk-ec, pkcs1-rsa, jwk-rsa E — `.toByteArray` on Long; pkcs8-ec, sign-jwt-es256 E — `.toByteArray` on BigInt), `libs.test-oauth/test-jwt-bearer-token` (KPG RSA), `ai.test-google-adc/test-adc-service-account-flow` (KPG RSA) | M3 — the `getMimeDecoder` + `HttpTimeoutException` halves are covered by the `jolt/` lib (§9); RSA + `.toByteArray` are the roadmap items |
| 2 | Regex `Matcher` shim: `.find(int)` ignored the start index; `.region` missing | 0 (was 1 E) | ~~`tui.components.test-caching-conventions` (E)~~ — FIXED upstream (jolt PR #922, `1e5036a5`, v0.8.6-31); kmet workarounds removed (`match-at` back to the plain anchored scan, scanner back to `.region`) | JOLT-3/JOLT-4 — closed |
| 3 | **String indexing: code point vs UTF-16** — Chez strings give full astral code points from `nth`/`count` (7 chars for the family emoji vs 11 surrogate units); kmet's width/grapheme scans assume UTF-16 and over-advance per astral char | 4 F + 1 E | `kmet.test-utils/test-visible-width-zwj-vs16-chain`, `tui.components.test-markdown` (test-markdown-table-emoji-alignment ×3, test-markdown-robustness-across-widths E — `StringIndexOutOfBounds`) | M16 — FIXED kmet-side 2026-09-09 (see below): `codepoint-len` derives the span from the string, not cp magnitude; the 4 F + 1 E are green on both hosts |
| 4 | `java.net.URI` multi-arg ctors missing (7-arg throws) — azure base-URL normalization catch-swallows it | 5 F | `ai.test-llm/test-llm-azure-url` | M15 — FIXED kmet-side (hand-built URL, no multi-arg ctor) |
| 5 | ~~edn reader silently drops a trailing `@` after a token — corrupt session lines parse as symbols~~ | 0 (was 4 F) | ~~`app.test-session` (×3 + torn-tail)~~ — FIXED upstream in v0.8.6, green with no kmet change | JOLT-2 — closed |
| 6 | ~~Single-arg `URI` ctor accepts illegal characters (no throw) — junk domains pass validation, die in curl~~ | 0 (was 1 F) | ~~`ai.test-oauth/test-copilot-login-invalid-domain`~~ — FIXED upstream in v0.8.6, green with no kmet change | JOLT-1 — closed |
| 7 | ~~kwargs map destructuring throws on an odd trailing arg (Clojure ignores it)~~ | 0 (was 2 E) | ~~test-track + tree-selector~~ — FIXED upstream in v0.8.6; kmet's 2026-09-09 call-site fixes stand (real bugs, correct on both hosts) | JOLT-5 — closed |
| 8 | ~~`format` has no `%g` conversion — `UnknownFormatConversionException: 'g'`~~ | 0 (was 4 E) | ~~interactive-ui + model-selector cost lines~~ — FIXED upstream in v0.8.6, green with no kmet change | new — M16, closed |
| 9 | ~~`clojure.core/parse-long` returns a BigInt on overflow (bb/JVM: nil)~~ | 0 (was 1 F) | ~~`libs.test-yaml/test-numbers`~~ — FIXED upstream in PR #932 (`684f6ea0`, `v0.8.6-54`+); kmet workaround `num/parse-long` removed 2026-09-10 | JOLT-7 — closed (jolt#927) |

**kmet-side workarounds applied 2026-09-09 (after this snapshot):** causes
2 (utils half) + 7 (tree-selector) share the JOLT-3 anchored-scan gap —
`kmet.tui.utils` gained `match-at` (no-arg `.find` over a `subs`-slice,
bb-jolt.md's suggested workaround) behind `ansi-code-at` and truncate's
`ansi-at`, which also unblocked the tree-selector test's rendering
assertions (both JOLT-3/JOLT-4 workarounds REMOVED 2026-09-10, jolt PR
#922 — `match-at` is back to `(.find m i)`, the scanner back to
`.region`); cause 4 fixed by hand-building the azure URL (no multi-arg
URI ctor); cause 7's call sites actually fixed (`tree_selector.clj`
positional `true` → `:strict? true`; `test_track.clj` dangling `:a` args
dropped). **Cause 1 (partial) — the `jolt/` RFC 0014 provider lib (§9):**
`jolt.kmet.providers` now supplies `Base64/getMimeDecoder` (PEM decoding)
and the `HttpTimeoutException` ctor (full JDK contract incl. the
`IOException` hierarchy edge) — the parse-private-key failure and the llm
transport-error error are green. (It required `jolt.crypto` when this was
written; that require was dropped 2026-09-10 — see §9.)
**Cause 3 (string indexing, M16) — fixed kmet-side:** `codepoint-len`
(utils.clj) now derives the element span of the cp at index I from the
string itself — 2 only when element I is a high surrogate followed by a
low surrogate (the same pairing test `code-point-at` uses), never from cp
magnitude — and the four inline `nchars` sites (`truncate-to-width` ×2,
`split-long-word`, `slice-by-column`) route through it. Every width/grapheme
walker therefore steps in the host's own element model: a semantic no-op on
bb/JVM (the pairing test fires exactly when the old magnitude test did),
1 element per astral cp on Jolt, where `subs`-overrun corruptions and the
width miscounts disappear. Re-verified: the 4 F + 1 E above green on both hosts
(`bb test` + `jolt test` on utils/markdown/text/truncated-text/editor/
select-list/input/alt-screen-flash/bash-execution/session-selector/
tree-selector — 345 tests/5812 assertions clean on jolt).
Re-run after that: 1928 tests / 12081 assertions, **6 failures + 14 errors
remain** (deterministic; cause 1's RSA/JWK `.toByteArray` gaps, causes 5,
6, 8, 9 + `caching-conventions`' `.region` error); bb full suite still green.

Causes 1 and 2 match the previously documented gaps (M3/M4, JOLT-3/JOLT-4);
**causes 3, 4, 8, 9 were new findings on 2026-09-09** — cause 3 (string indexing) and 4
(URI 7-arg ctor) also correct bb-jolt.md's JOLT-3 entry, which grouped the
width failures under the Matcher bug: `visible-width` and the markdown-table
tests are pure index scans with no Matcher involvement. JOLT-6 (spawned
children inherit open fds) did not manifest — no namespace hit the runner's
15 s timeout, so no orphaned curl children pinned the fixed callback ports
(same on the 2026-09-10 re-run under v0.8.6).

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

**Status (2026-09-10, final): `jolt test` is GREEN — 1998 tests / 13462
assertions, 0 failures, 0 errors**; re-verified the same day after the
borders/timers/key-labels work and the resource-resolution port, now
**2032 tests / 13803 assertions, 0 failures, 0 errors** (the delta is newly
added tests, not fixed ones). Re-verified once more on the jolt#914 fix build
(`v0.8.6-29-gf85adb51`, the PR #924 merge): the affected namespaces are green
(`jolt test kmet.libs.test-crypto kmet.ai.test-google-adc kmet.ai.test-llm` —
99 tests / 523 assertions). The last two causes closed the same day:

- cause 1's JWK `.toByteArray` (5 E) — `kmet.libs.crypto/bigint->bytes`
  (`8eff434`), a portable quot/rem two's-complement encoder verified
  byte-identical to `.toByteArray` on bb/JVM;
- cause 1's RSA (4 E) — landed in jolt.crypto (merged upstream as jolt-lang/crypto#8, merge `79ecb3d`): RSA keygen +
  `SHA*withRSA` + RSA `KeyFactory`, claims moved into crypto's own
  `:jolt/provides`;
- cause 2's `.region` (1 E) and cause 9's `parse-long` overflow (1 F) —
  kmet-side workarounds (`4f900ed`): a portable ignore-window in
  `top-level-forms`, and `num/parse-long` (nil on overflow on both hosts).
  The ignore-window was **removed 2026-09-10** — jolt PR #922 (`1e5036a5`,
  v0.8.6-31) added `.find(int)` index scanning and `.region`, so the
  scanner and `kmet.tui.utils/match-at` are back to their plain forms
  (re-verified on both hosts); the `parse-long` workaround was removed the
  same day — jolt PR #932 (`684f6ea0`, `v0.8.6-54`+) range-checks the
  value, so `kmet.libs.yaml` is back on plain core `parse-long`
  (bb-jolt.md JOLT-7).

Causes 5/6/7/8 had already closed upstream in the v0.8.6 release
(bb-jolt.md JOLT-1/JOLT-2/JOLT-5 + M16 `format %g` — all re-verified fixed
on v0.8.6), and cause 3 (string indexing) is closed kmet-side — the
width/grapheme walkers are index-model-agnostic now (see the workaround
paragraph above). History: the 2026-09-09 snapshot was 18 F + 18 E;
same-day kmet-side workarounds for causes 2/4/7 brought it to 11 F + 16 E;
the v0.8.6 release closed causes 5–8 with no kmet change; the final two
workarounds + the crypto RSA port closed the remaining 11.

**Re-verification on upstream main (2026-09-11, `v0.8.6-72-g0f7d1a11`,
built locally):** the suite is not green on main for an unrelated reason —
`kmet.app.loop/retryable-error?` hangs jolt's regex engine on its 50-alt
`retryable-error-regex` (bb-jolt.md JOLT-10), so `kmet.ai.test-llm` hits
the runner's 15 s per-namespace timeout and its cancellation cascades into
spurious errors in later namespaces. Isolated re-runs of every other red
namespace are green: `libs.test-http` 25/90 plus the slow
platform-transport test, `ai.test-oauth` 53/223,
`app.ui.test-session-selector` 31/130, `libs.test-edn-store` 17/40. The
upstream fixes merged in this window (bionic-errno PR #939, spawn-fd PR
#936, sci-reflector PR #933) are verified; JOLT-6/JOLT-8 are closed,
JOLT-9 still needs the http-client fork pin (PR #19 open).

---

## 9. `jolt/` — kmet's RFC 0014 provider lib

A self-contained library in the repo root (`jolt/deps.edn` + `jolt/src/`,
README in `jolt/README.md`) that supplies JDK classes the jolt ecosystem
does not supply, declared the RFC 0014 way: kmet's root `deps.edn` pulls it
in as `jolt.kmet/providers {:local/root "jolt"}`, and `jolt/deps.edn`
carries the `:jolt/provides` claims. It is **inert on bb/JVM** — no bb
classpath namespace requires a `jolt.*` ns, clj-kondo excludes the dir.

### Why it exists (cause 1 of §8)

jolt.crypto (io.github.jolt-lang/crypto) covered symmetric crypto + **EC**
keygen/signature only when this lib was born (its own tests pinned the RSA
rejection; RSA landed upstream 2026-09-10 — roadmap below) and jolt core
lacks `Base64/getMimeDecoder` and a `HttpTimeoutException` ctor. kmet's
production Google-ADC login (RS256) and the RS256 JWT paths could not run on
jolt until RSA existed.

### Load order — the trap, fixed upstream (jolt#914)

jolt used to autoload a `:jolt/provides` install namespace only while the
referenced class was **unregistered**. But jolt.crypto's `install!`
registers classes as a side effect of ITS autoload (any
`javax.crypto.Mac`/`Cipher` reference), and a registered class never
triggered a provider lookup again — so a namespace that compiled after
jolt.crypto loaded bound crypto's then-EC-only
`Signature`/`KeyPairGenerator`/`KeyFactory` and RSA was unreachable. Same
deps.edn, two outcomes decided by compile order — kmet itself showed both
(`ai.test-google-adc` errored `No dependency provides
java.security.KeyPairGenerator`, a later `libs.test-crypto` got the EC-only
shim).

That is fixed upstream: **jolt#914** closed by **PR #924** (merge
`f85adb51`, in the `v0.8.6-29`+ builds). A declared provider resolves its
class whatever loaded first: a registration for a claimed class from a
non-claimer is **held** until the claimer loads, then replayed through the
same guard — what the provider implements wins, members it does not answer
still land — and once the provider has registered a member, a registration
of that member from anywhere else is **dropped** (with a warning).
Resolution is a property of the dependency graph now, not of incidental
load state. `java.util.Base64` is explicitly out of scope: a claim on a
class the runtime implements is still refused, and a member-miss autoload
for implemented classes is a separate upstream item.

What that means for kmet's three load-order payloads:

1. the `jolt.crypto` **first-form require** in `jolt.kmet.providers` is
   **dropped** (2026-09-10): it existed only while kmet's provider
   re-registered crypto's asymmetric classes last-wins, and it made jolt's
   `JOLT_DEBUG` diagnostics misattribute crypto's registrations to kmet —
   a nested load of another provider's install namespace keeps the OUTER
   provider's `lib-loading-provider` mark, so crypto's `MessageDigest` /
   `Signature` / … showed up as "`jolt.kmet.providers` registers … without
   declaring it" (the general case, **jolt#926** — fixed upstream in PR
   #930, merge `899a2204`, `v0.8.6-42`+: a provider reached from another
   provider's install namespace is attributed to itself; the require stays
   gone because it buys nothing after jolt#914). With the require
   gone, crypto loads on its own first class reference and attributes
   correctly; the provider now needs only `jolt.host`;
2. the `jolt/deps.edn` `:jolt/provides` claim (now only
   `HttpTimeoutException`) autoloads on the **first reference**, whatever
   registered the class earlier — verified: a bare
   `(java.net.http.HttpTimeoutException. "x")` loads the provider with no
   guard at all;
3. the **guarded requires** in `kmet.libs.crypto` / `kmet.ai.google-adc`
   remain, but now only for `java.util.Base64`: the claim on it is refused,
   so the guard is the only thing that installs `getMimeDecoder` before a
   referencing namespace is analyzed. Verified: without the guard,
   `No matching field or method: java.util.Base64/getMimeDecoder`. Their
   crypto half is obsolete — `Signature`/`KeyPairGenerator`/`KeyFactory`
   are declared by jolt.crypto and autoload it deterministically (verified
   with no requires at all).

This pattern is the AGENTS.md convention for any future consumer. The
`JOLT_DEBUG` note kmet's Base64 registration used to draw was **jolt#926**
(both #914 follow-ups: the nested-provider mark and this note), fixed
upstream in PR #930 (merge `899a2204`, `v0.8.6-42`+): a class the runtime
IMPLEMENTS no longer gets a "registers … without declaring it" note whose
advice (a `:jolt/provides` claim) jolt refuses, and the general
nested-provider attribution is correct. The guard itself is unchanged — a
claim on `java.util.Base64` is still refused.

### Provided (verified against the bb/JVM reference)

| shim | notes |
|---|---|
| `java.util.Base64/getMimeDecoder` | pure-Clojure MIME decode (non-alphabet chars discarded, JDK rules), returns the same `[B` type core's decoder returns |
| `java.net.http.HttpTimeoutException` ctor | `jolt.host/throwable` + `register-class-supers!` edge to `java.io.IOException` — `instance?`/`catch` on Throwable/Exception/IOException match the JVM; `toString`/`ex-message`/`getCause` identical; only the `String` ctor exists, as on the JDK |

Green on jolt after this: `libs.test-crypto/test-parse-private-key-rejects-garbage`,
`ai.test-llm/test-llm-transport-error-message`; the EC tests (`pkcs8-ec`,
`sign-jwt-es256`) moved past the Base64 gap onto the `.toByteArray` blocker
(since closed — see the roadmap below).

**RSA moved out of this lib (2026-09-10):** jolt.crypto provides it now, so
the provider here is Base64 MIME + `HttpTimeoutException` only, and its
`jolt.crypto` require is gone with the last-wins re-registration that
justified it (see the load-order section above).

**Re-verified 2026-09-11 on upstream main (`v0.8.6-72-g0f7d1a11`):** both
shims are still unshimmed upstream — a bare
`(java.util.Base64/getMimeDecoder)` answers
`No matching field or method: java.util.Base64/getMimeDecoder` and a bare
`(java.net.http.HttpTimeoutException. "x")` answers
`No matching ctor found for class java.net.http.HttpTimeoutException`,
while the kmet suite loads them through this provider. Nothing to remove.

### Roadmap — CLOSED 2026-09-10 (full jolt suite green)

- **Load-order dependence (jolt#914)** — fixed upstream in **PR #924**
  (merge `f85adb51`, `v0.8.6-29`+): a declared provider resolves its class
  whatever loaded first (hold-then-replay for a non-claimer's registration,
  drop once the provider owns the member). kmet's guarded requires remain,
  now solely for `java.util.Base64` — claims on runtime-implemented classes
  are still refused (separate upstream item) — and the now-vestigial
  `jolt.crypto` require is dropped (see above).
- **RSA** — landed upstream in **jolt.crypto** (jolt-lang/crypto#8, merge `79ecb3d`): RSA keygen
  (`RSA_new` / `RSA_generate_key_ex` + `EVP_PKEY_set1_RSA`), `SHA*withRSA` in
  `Signature`, RSA in `KeyFactory`, reusing the EVP seam the EC code already
  had; `:jolt/provides` now claims `Signature` / `KeyPairGenerator` /
  `KeyFactory` and the spec classes, so a dependent autoloads crypto on first
  reference. kmet's `jolt/deps.edn` dropped those claims (jolt allows a class
  one provider) and keeps only `HttpTimeoutException`.
- **JWK `.toByteArray`** — closed in `kmet.libs.crypto` (`8eff434`): the DER
  writers use the portable `bigint->bytes` (quot/rem digit loop; no host
  `.toByteArray`), so bigints jolt models as `Long`/`BigInt` work on both
  hosts.

With those two closed, plus the earlier `num/parse-long` workaround
(`4f900ed`; its upstream fix landed in jolt PR #932, merge `684f6ea0`,
`v0.8.6-54`+ — the wrapper was removed 2026-09-10, bb-jolt.md JOLT-7),
**`jolt test` is fully green: 1998 tests / 13462
assertions, 0 failures, 0 errors** (was 1 F + 10 E). The companion
`.region` workaround was removed on 2026-09-10: jolt PR #922 (`1e5036a5`,
v0.8.6-31) supplied `.find(int)` index scanning and `.region`, and the
workaround-free kmet passes on both hosts (`kmet.test-utils` +
`kmet.tui.components.test-caching-conventions` +
`kmet.app.ui.test-tree-selector` + `kmet.tui.components.test-markdown` /
`test-truncated-text` + `kmet.tui.test-core`: 155 tests / 5465 assertions
green under jolt and bb). Line counts for the
crypto change: ~90 lines of implementation + ~120 lines of tests, verified
against OpenSSL-generated known-answer vectors.

**Status on upstream main (2026-09-11):** the green result above is on the
v0.8.6-series builds (`patchset` tip and earlier). On the freshly built
`v0.8.6-72-g0f7d1a11` the suite is red for one reason only — the JOLT-10
regex hang in `kmet.ai.test-llm` and the timeout-cancel cascade it causes
(§8's re-verification note has the numbers; every affected namespace is
green when re-run alone).