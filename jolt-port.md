# Porting kmet to Jolt — full report

Scope: the whole repo (141 `src` files ≈ 52.6k LOC, 117 test files ≈ 34.9k LOC,
59 extension source `.clj` files ≈ 17k LOC (+106 `target/` test-fixture
files — counts re-verified 2026-09-08),
not just the TUI. Jolt reference is the checkout at `~/jolt/` (`533b04a3`,
2026-09-08) plus `jolt-lang.github.io/docs/{native-interop,host-interop,
differences,building-and-deps}`. Items marked "verified" were checked
against that tree (`stdlib/`, `host/chez/java/`, `vendor/`, `jolt-core/`);
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
library's `java.net.http` work. Latest main (`4744256f83e5`, 2026-09-09) runs
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
`io.github.jolt-lang/http-client` (git `4744256f83e5`); its transitive
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

Jolt **can run SCI's source** (`make sci` loads `borkdude/sci` through
joltc; `scifunctional` runs SCI functional tests) — so porting the
mechanism is plausible, not impossible (SCI is vendored at `vendor/sci`,
and `stdlib/clojure/sci/` host-layer stubs (`host_stubs.clj`, `io_stubs.clj`,
`lang_stubs.clj`) cover its host-layer modules). But:
SCI-on-Jolt performance for a whole extension ecosystem is unproven;
`borkdude/deps.clj` (JVM Maven resolution) must be replaced by Jolt's own
dep fetching (grenadine tree expansion via `vendor/grenadine`, HTTPS fetch
via `stdlib/jolt/mvn_http.clj`, `~/.m2` sharing — verify the exact
namespaces + whether they can serve arbitrary Maven closures at
extension-load time); and the bb-import/bundled-lib tables
must be rebuilt against Jolt's shim set. Alternative designs worth costing: (1) extensions as plain Jolt
namespaces, no isolation (loses version isolation); (2) extensions as
subprocesses over JSON-RPC (the MCP pattern — strong isolation, new
protocol work); (3) SCI as now. This is the last milestone either way —
the core agent must work before extensions matter.

---

## 3. Medium rewrites (bounded, one namespace at a time)

| # | kmet surface | Jolt answer (verified on checkout) | size |
|---|---|---|---|
| M1 | `clojure.data.json` (the swap from `cheshire` → `data.json` is done — `kmet.libs.json` now aliases `clojure.data.json` directly) | **RESOLVED 2026-09-09 — no JSON lib needed:** `org.clojure/data.json` is a `deps.edn` Maven dep and Jolt resolves Maven deps itself, so `kmet.libs.json` loads unchanged on Jolt. Verified green on Jolt `v0.8.5`: `test-json` (4 tests/18 assertions), `test-jsonrpc` (17/41), `test-sse` (33/109). **Note:** `http.cljc` is already ported (curl path via `#?(:jolt ...)`); all 27 libs now load and test green on bb/JVM. M1 is closed (data.json works on both hosts) | done — no new lib |
| M2 | `tui/terminal.clj` (JLine raw/timed-reads/size) + `core.clj` reader/timers/resize/drain | termios FFI (Unix) + kernel32 FFI (Windows); `future` reader + `locking` + gen-counters — see `jolt-tui.md` §§4–7,9. Evaluated 2026-09-06: `jolt-lang/glimmer-tui` (ncursesw via FFI, Unix-only, fullscreen `initscr` takeover) rejected — wrong architecture for the inline ANSI/scrollback model; JLine stays on bb (`jolt-tui.md` §2 decision) | rewrite ~500 LOC (Jolt only) |
| M3 | `libs/crypto.clj` (315 LOC: RSA/EC `KeyFactory`, `SHA256withRSA/ECDSA` `Signature`) + `libs/aws_sigv4.clj` (213 LOC: `MessageDigest` SHA-256, `Mac` HmacSHA256, `HexFormat`, `Normalizer`?) — grep the exact class list before the FFI design | OpenSSL FFI following `mvn_http.clj`'s libcrypto/libssl loading (note macOS boringssl SIGABRT hazard — explicit Homebrew paths only); RSA via libcrypto; `SecureRandom` via OS source. The `io.github.jolt-lang/crypto` git dep is in `deps.edn` (RFC 0014). **Verified 2026-09-09:** the symmetric half holds — `test-aws-sigv4` fully green on Jolt (5 tests/18 assertions), so `MessageDigest`/`Mac` are covered. The asymmetric half still gaps — `test-crypto` on Jolt: 10 tests, 2 pass, 8 fail in key-parse/sign paths: `KeyPairGenerator` has no provider (`No dependency provides java.security.KeyPairGenerator … :jolt/provides … (RFC 0014)`), `Base64/getMimeDecoder` is unshimmed (PEM/PKCS parse), and JWK hits `No matching field found: toByteArray for class java.lang.Long`. The `Base64/getMimeDecoder` half is now covered by kmet's own `jolt/` provider lib (§9). Re-verified in the 2026-09-09 full-suite run (§8): 10 tests, 2 pass, 1 failure (`test-parse-private-key-rejects-garbage` — `getMimeDecoder`) + 7 errors (4× `getMimeDecoder`, 2× JWK `toByteArray`-on-Long, 1× `KeyPairGenerator`); the same `KeyPairGenerator` gap surfaces in `ai.test-google-adc` (service-account flow) and `libs.test-oauth/test-jwt-bearer-token` | rewrite ~500 LOC |
| M4 | `libs/oauth.clj` (611) + `ai/oauth.clj` (1012) + `ai/google_adc.clj` (121) — browser launch, localhost callback server, token cache | `ServerSocket` shim exists (`stdlib/jolt/socket.clj`, gated on `(require 'jolt.socket)`); browser launch via `jolt.process`; token cache via `spit`/`slurp`. **Verified 2026-09-09:** `test-oauth` on Jolt: 26 tests, 1 failure + 1 error — `test-callback-server` times out (localhost callback; `ServerSocket` shim is gated on `(require 'jolt.socket)`) and `test-jwt-bearer-token` fails on the M3 `KeyPairGenerator` gap. **Callback server FIXED** (commit `596f439`, 2026-09-09: jolt's `readLine` keeps the trailing `\r`, so the header-block end arrived as `"\r"` — truthy — and the reader blocked one line past the headers forever; plus a socket-shim read gap); re-verified in the full-suite run (§8): 26 tests/65 assertions, **1 error only** (`test-jwt-bearer-token`, M3). `ai.oauth` still fails `test-copilot-login-invalid-domain` (JOLT-1 — jolt's single-arg `URI` ctor accepts illegal characters instead of throwing, so junk GitHub-Enterprise domains pass validation and die in curl) | adapt ~1.7k LOC |
| M5 | `libs/archive.clj` (46 LOC, `ZipFile` read) + `sse.clj` CRC-32 (pure-Clojure `libs/hash.clj/crc32` since the port — Bedrock frame tests green on Jolt, no zip work) + `extensions.cljc:910,921` (`JarFile` probes) + `build.cljc:227,245,389` (`ZipOutputStream` uberjar/pack-extension). (`ai/models.clj` needs no zip work — catalogs load via `io/resource`, which answers file:/jar:/embedded URLs alike.) | `jolt.fs` explicitly EXCLUDES zip/gzip (`stdlib/jolt/fs.clj:12`: "java.util.zip not shimmed yet"). **DECIDED 2026-09-08: bb-only until the `jolt build` rewrite** — `build.cljc`/`libs/archive.clj` entry points throw `::bb-only` under Jolt, their tests carry `^:bb-only` (the runner skips them there); zip/jar work defers to extension-jar materialization via unzip (jolt's own mvn-jar model) | rewrite build; archive via FFI or subprocess. Note:
| M6 | `build.cljc` uberjar assembly (`bcp/get-classpath`, `ZipOutputStream` resource listing) + model-catalog embedding | No classpath concept; `jolt build` embeds source roots differently. Model catalogs (`ai/model_data/` + manifest) become embedded resources — `io.ss` has `register-embedded-resource!` and `io/resource` answers a `java.net.URL` from both disk and a built image | adapt ~200 LOC |
| M7 | `libs/clipboard.clj`, `libs/terminal_image.clj` (Base64 — shimmed, keep), OSC-52/kitty-graphics emit | clipboard via platform subprocesses (`pbcopy`/`xclip`/`clip`) through `jolt.process`; image protocols are pure emit logic | small |
| M8 | `config.clj` (XDG paths, EDN load/save, file watching?) | `jolt.fs` (vendored `babashka.fs`, minus zip) covers paths; `spit`/`slurp`/EDN portable; watcher → poll (same as `tui.theme`) | adapt |
| M9 | `debug.clj` (file logging) + crash/render logs | `(spit path text :append true)` (`jolt-io-writer` is 1-arg — `io.ss:1314-1323`; `spit` takes `:append` — `io.ss:1164-1195`); timestamps via the `io.github.jolt-lang/time` dep (already in `deps.edn`) or manual format. Note: Jolt's `java.io.tmpdir` honors `$TMPDIR` (`host-static-methods.ss:1002,1016`), unlike bb's hardcoded `/tmp` — keep the explicit-dir pattern anyway | small |
| M10 | `bb.edn` tasks (22: `run` + 21: uberjar/build/test/test-ext/changed/test-changed/test-ext-changed/lint-changed/format-changed/format-check-changed/nrepl/check/generate-models/generate-image-models/check-model-data/pack-extension/lint/format/format-check/help) | **DONE (test task):** `kmet.runner` is now host-aware and tolerant — every test namespace is required in a try; unloadable ones (babashka-internal requires like `babashka.classpath`/`babashka.classes`, `java.time.format.DateTimeFormatter` gaps, …) are reported and skipped, the rest run. Per-var `^:slow` split + per-var filters work on BOTH hosts (`jolt test` non-slow / `jolt test-ext` slow; bb.edn `:paths ["src" "test"]` supplies the roots under jolt). Engine: bb = per-var output capture + ref counters; jolt = `clojure.test/test-vars` with jolt's own process-wide `counters` atom read as before/after deltas (`jolt?` = `(find-var 'clojure.core/*jolt-version*)`). **Full-suite run 2026-09-09 (`jolt v0.8.5-36-gbac15682`): all 107 namespaces load — zero unloadable** (the earlier babashka-internal/`java.time` load gaps are gone) and 1928 tests run end-to-end; deterministic result 18 failures + 18 errors, all jolt-only — **11 + 16 after the same-day kmet-side workarounds** for causes 2/4/7 (see §8). Remaining M10 work: `jolt build` packaging, lint/format gates, model generators | mostly done for tests |
| M11 | `clojure.spec.alpha` (SCI-context injection only), `clojure.walk` (2 requires: `libs/json.clj:16`, `ai/constrained_sampling.clj:13`), `BigDecimal` (`edn_writer` + SCI class table) | spec: absent from `stdlib/` (verified — declare `org.clojure/spec.alpha` explicitly per README's "terminal dependency" rule, or rewrite the one use); `walk`: present (`stdlib/clojure/walk.clj`, seed-embedded — keep); `BigDecimal`: PRESENT (`host/chez/java/bigdec.ss`: `M` literals + `with-precision` per README — the earlier "absent" claim was wrong; just port the call sites) | small |
| M12 | `defrecord` (27 files) + `reify` (6 files) + protocols + `deftype` (zero definitions — only comments) | README Differences confirms `deftype`/`defrecord`/`reify`/`extend-protocol`, multimethods, STM, `future`/`promise`/`agent` and `core.async` behave as on the JVM — still verify early: `satisfies?`-on-reify semantics, `defrecord` positional factories, protocol dispatch for `IComponent`/`IFocusable`. The TUI's `satisfies?` avoidance notes (AGENTS.md SCI gotcha) need re-checking on Jolt | verify early, affects everything |
| M13 | Custom `defcomponent`/`with-let` macros + clj-kondo hooks | Jolt compiles macros normally (self-hosted compiler) — should port; re-verify hygiene/&env behavior (`go`-style passes are async-only, plain macros fine). Kondo hooks keep working (source-level) | verify early |
| M14 | `java.util.concurrent` — 4 sites: `LinkedBlockingQueue`+`TimeUnit` (`libs/sse.clj` idle-deadline reader — now `ArrayBlockingQueue`, fixed 2026-09-09), `ReentrantLock` (`app/session.clj:154,296`, file-mutation lock), `Callable` (`app/extensions.clj:738`, SCI class table) | **Verified 2026-09-09:** `LinkedBlockingQueue` has NO ctor on Jolt (`No matching ctor found`) — `sse.clj` now uses `(ArrayBlockingQueue. 65536)`; verified `.put`, `.poll n TimeUnit`, `.offer`, `.size`, `.remainingCapacity`, and `TimeUnit/MILLISECONDS`. `ReentrantLock` still assumed shimmed (session lock not yet run on Jolt); `Callable` becomes a fn; `locking` covers the session lock | small |
| M15 | `java.net.URI/URL/URLEncoder`, `Normalizer`, `Charset`, `HexFormat`, `Instant/DateTimeFormatter/ZoneId`, `PushbackReader`, `StringReader/Writer` | Mostly shimmed (host-interop list + `io.ss`/`io-streams.ss`); URL/URI surface exists (`jolt.socket` gating for sockets); time values via time lib. **Verified 2026-09-09 (reader surface):** `io/reader` rejects `proxy` Readers (`Cannot open <reify> as a Reader` — `jolt-io-reader`, `io.ss:1291`); `BufferedReader` ctor is identity so a proxy Reader lacks `.readLine`/`.close`; `InputStreamReader` over a proxy `InputStream` constructs (reads dispatch to the override); `PipedInputStream` + `io/reader` + `.readLine` works. `sse.clj` works around all three (see B1). **Verified 2026-09-09 (URI + java.net.http surface, full-suite run §8):** the multi-arg `URI` ctors are missing — the 7-arg ctor throws `incorrect number of arguments 7 to …` (kmet's `azure_openai_responses/normalize-azure-base-url` catch-swallowed it, so Azure base URLs were never forced to `/openai/v1`; 5 failures in `test-llm-azure-url` — **fixed kmet-side 2026-09-09**: the URL is rebuilt by hand from the parsed pieces, no multi-arg ctor); the single-arg ctor is the *reverse* gap (JOLT-1 — accepts illegal characters the JDK rejects, bb-jolt.md). `java.net.http.HttpTimeoutException` exists as a class but has NO ctor (`No matching ctor found`, `test-llm-transport-error-message`). Remaining call sites still need per-site audit | audit per site |
| M16 | Jolt host string/number/format semantics (kmet's UTF-16-indexing code — §8 cause 3) | Jolt (Chez) strings index by **code point**, not UTF-16 code unit: `(count "👨‍👩‍👧‍👦")` is 7 (one char per astral code point) vs 11 surrogate units on bb/JVM, and `nth` returns the full code point. Every kmet scan that assumes surrogate pairs over-advances by one per astral char: `kmet.tui.utils` grapheme/width machinery (`codepoint-len`, `nchars` = 2 for astral) miscounts ZWJ chains and truncation, and markdown-table slicing runs off the string end — 4 failures + 1 error live (visible-width ZWJ chain, table emoji alignment ×3, robustness `StringIndexOutOfBounds`; bb-jolt.md's JOLT-3 attribution of these is wrong — they are pure index scans, no Matcher). Same family: `clojure.core/parse-long` returns a BigInt on overflow where bb/JVM returns nil (yaml plain-scalar fallback keeps the string — `libs.test-yaml/test-numbers`), and `format` has no `%g` conversion (`UnknownFormatConversionException: 'g'` — model-selector cost lines `(format "%.4g" …)`, 4 errors) | audit/fix every astral-char index site under Jolt; host-level fixes for `parse-long` and `%g` |

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
| `http` | 🟢 | 🟡 | **ported** — Jolt runs direct/http-proxy traffic through babashka.http-client over the jolt-lang/http-client shims (deps.edn: org.babashka/http-client 0.4.24 + io.github.jolt-lang/http-client), curl for SOCKS/https-scheme proxies and `:as :stream` (see B1); the `:http-transport` setting can force curl for everything. test-http 25/90 green on Jolt (every contract under both modes). Loads on bb |
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
| `yaml` | 🟢 | 🟡 | bb: 20/20; Jolt: 19/20 — `test-numbers`: `clojure.core/parse-long` returns a BigInt on overflow where bb/JVM returns nil, so the plain-scalar int fallback keeps a string on bb but yields the BigInt on Jolt (M16) |

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
3. **Regex engine**: irregex vs Java — `keys.clj`, response parsers, `utils.clj` wrapping all need their test suites re-run (common patterns fine, edge features differ). **Live divergences (2026-09-09 full-suite run, bb-jolt.md JOLT-3/JOLT-4):** `Matcher.find(int)` ignores the start index (always returns the first match — every anchored-scan idiom `(.find m i)` + `(= (.start m) i)` only matches index 0: SGR-state scans, ANSI/OSC-8 truncation), and `Matcher.region(int,int)` is missing (`test-caching-conventions` crashes). Note: the *width* failures previously blamed on the regex engine are actually the M16 string-indexing gap — see §8 cause 3.
4. **`vendor/` pins**: the vendored sources carry no version constants to diff against `deps.edn` — re-verify the `babashka/fs` + `babashka/process` pins by file comparison on any Jolt upgrade.
5. **Windows**: kmet supports it (Git Bash resolution, `\` zip entries, `fs` separators); Jolt's Windows FFI surface has known gaps (`process.ss`) — Windows is the last platform to light up, after Unix parity.
6. **No `/tmp` on Termux / `~` expansion / Android IME paste paths**: kmet carries Termux-specific workarounds — re-verify each on Jolt. One is already better: Jolt's `java.io.tmpdir` honors `$TMPDIR` (`host-static-methods.ss:1002,1016`), unlike bb's hardcoded `/tmp` — keep the explicit-dir pattern anyway. Burst-paste detection rides the §7 pipeline, which ports logically.

---

## 8. Full-suite `jolt test` status (2026-09-09)

Snapshot of the whole suite under Jolt (`jolt v0.8.5-36-gbac15682`, threaded
Chez 10.x; bb/JVM is the reference — 5 consecutive full `bb test` runs green).
The runner (M10) requires every namespace in a try/catch and reports load
failures with reasons; the full run without filters also loads the `^:slow`
and `^:bb-only` namespaces (their vars are then filtered out).

**Loads: all 107 namespaces load on Jolt — zero unloadable** (no skip report
in any of ~18 full runs). The four namespaces that never print a "Testing"
line are not load failures: `build-test` (10 vars) and `libs.test-archive` (3)
are all `^:bb-only` (M5), `modes.test-overlay-input-smoke` (2) and
`tui.test-render-loop` (6) are all `^:slow` (run under `jolt test-ext`).

**Result: 1928 tests / 12078 assertions, deterministic 18 failures + 18
errors — every one jolt-only** (bb full runs are clean). Grouped by common
cause, with the failing tests (counts = original snapshot; kmet-side
workarounds applied same day for causes 2/4/7, see below — re-run: **11
failures + 16 errors**):

| # | cause (jolt gap) | count | failing tests | status |
|---|---|---|---|---|
| 1 | JDK class/ctor surface: `Base64/getMimeDecoder` missing (4 E + 1 F), `KeyPairGenerator` without a provider (3 E — RFC 0014), BigInteger shim as Jolt `Long` — `.toByteArray` (2 E), `java.net.http.HttpTimeoutException` no ctor (1 E) | 1 F + 10 E | `libs.test-crypto` (parse-private-key F; pkcs1-rsa, pkcs8-ec, pkcs8-rsa, sign-jwt-es256, jwk-ec, jwk-rsa, sign-jwt-rs256 E), `libs.test-oauth/test-jwt-bearer-token`, `ai.test-google-adc`, `ai.test-llm/test-llm-transport-error-message` | M3/M4 rows |
| 2 | Regex `Matcher` shim: `.find(int)` ignores the start index; `.region` missing | 2 F + 1 E | `kmet.test-utils` (test-sgr-state-at, test-truncate-to-width-osc-8-close), `tui.components.test-caching-conventions` (E) | JOLT-3/JOLT-4 — the two utils F's fixed kmet-side (`match-at` slice scan); `.region` E still open |
| 3 | **String indexing: code point vs UTF-16** — Chez strings give full astral code points from `nth`/`count` (7 chars for the family emoji vs 11 surrogate units); kmet's width/grapheme scans assume UTF-16 and over-advance per astral char | 4 F + 1 E | `kmet.test-utils/test-visible-width-zwj-vs16-chain`, `tui.components.test-markdown` (test-markdown-table-emoji-alignment ×3, test-markdown-robustness-across-widths E — `StringIndexOutOfBounds`) | new — M16 |
| 4 | `java.net.URI` multi-arg ctors missing (7-arg throws) — azure base-URL normalization catch-swallows it | 5 F | `ai.test-llm/test-llm-azure-url` | M15 — FIXED kmet-side (hand-built URL, no multi-arg ctor) |
| 5 | edn reader silently drops a trailing `@` after a token — corrupt session lines parse as symbols | 4 F | `app.test-session` (test-session-load-with-multiple-corrupt-entries ×3, test-session-torn-tail-with-earlier-corruption) | JOLT-2 |
| 6 | Single-arg `URI` ctor accepts illegal characters (no throw) — junk domains pass validation, die in curl | 1 F | `ai.test-oauth/test-copilot-login-invalid-domain` | JOLT-1 |
| 7 | kwargs map destructuring throws on an odd trailing arg (Clojure ignores it) | 2 E | `tui.components.test-track/test-fresh-but-equal-collection-write-keeps-cache`, `app.ui.test-tree-selector/panning-keeps-selected-anchor-readable` | JOLT-5 — FIXED kmet-side: both sloppy call sites actually fixed (the earlier 'cleaned up' claim was wrong); the tree-selector test also needed cause 2's workaround |
| 8 | `format` has no `%g` conversion — `UnknownFormatConversionException: 'g'` | 4 E | `app.test-interactive-ui` ×2, `app.ui.test-model-selector`/`test-scoped-models-selector` (test-model-info-lines — the shared `fmt-usd-rate` cost line) | new — M16 |
| 9 | `clojure.core/parse-long` returns a BigInt on overflow (bb/JVM: nil) | 1 F | `libs.test-yaml/test-numbers` | new — M16 |

**kmet-side workarounds applied 2026-09-09 (after this snapshot):** causes
2 (utils half) + 7 (tree-selector) share the JOLT-3 anchored-scan gap —
`kmet.tui.utils` gained `match-at` (no-arg `.find` over a `subs`-slice,
bb-jolt.md's suggested workaround) behind `ansi-code-at` and truncate's
`ansi-at`, which also unblocked the tree-selector test's rendering
assertions; cause 4 fixed by hand-building the azure URL (no multi-arg
URI ctor); cause 7's call sites actually fixed (`tree_selector.clj`
positional `true` → `:strict? true`; `test_track.clj` dangling `:a` args
dropped). **Cause 1 (partial) — the `jolt/` RFC 0014 provider lib (§9):**
`jolt.kmet.providers` (requires `jolt.crypto` first) now supplies
`Base64/getMimeDecoder` (PEM decoding) and the `HttpTimeoutException` ctor
(full JDK contract incl. the `IOException` hierarchy edge) — the
parse-private-key failure and the llm transport-error error are green.
Re-run after that: 1928 tests / 12081 assertions, **10 failures + 15 errors
remain** (deterministic; cause 1's RSA/JWK `.toByteArray` gaps, causes 3,
5, 6, 8, 9 + `caching-conventions`' `.region` error); bb full suite still green.

Causes 1 and 2 match the previously documented gaps (M3/M4, JOLT-3/JOLT-4);
**causes 3, 4, 8, 9 are new findings** — cause 3 (string indexing) and 4
(URI 7-arg ctor) also correct bb-jolt.md's JOLT-3 entry, which grouped the
width failures under the Matcher bug: `visible-width` and the markdown-table
tests are pure index scans with no Matcher involvement. JOLT-6 (spawned
children inherit open fds) did not manifest — no namespace hit the runner's
15 s timeout, so no orphaned curl children pinned the fixed callback ports.

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

**Status:** after the cause-2/4/7 workarounds and the cause-1 `jolt/`
provider lib (§9), 25 deterministic issues remain (10 F + 15 E): cause 1's
RSA + JWK `.toByteArray` gaps (M3, next in `jolt/`), causes 5/6 + the
`.region` error are jolt shim gaps (bb-jolt.md JOLT-1/JOLT-2/JOLT-4 +
M4/M15); causes 3/8/9 need either jolt host fixes (`parse-long`,
`format %g`) or kmet-side portability work (M16 — make the grapheme/width
scans index-model-agnostic).

---

## 9. `jolt/` — kmet's RFC 0014 provider lib

A self-contained library in the repo root (`jolt/deps.edn` + `jolt/src/`,
README in `jolt/README.md`) that supplies JDK classes the jolt ecosystem
does not supply, declared the RFC 0014 way: kmet's root `deps.edn` pulls it
in as `jolt.kmet/providers {:local/root "jolt"}`, and `jolt/deps.edn`
carries the `:jolt/provides` claims. It is **inert on bb/JVM** — no bb
classpath namespace requires a `jolt.*` ns, clj-kondo excludes the dir.

### Why it exists (cause 1 of §8)

jolt.crypto (io.github.jolt-lang/crypto) covers symmetric crypto + **EC**
keygen/signature only — its own tests pin the RSA rejection — and jolt core
lacks `Base64/getMimeDecoder` and a `HttpTimeoutException` ctor. kmet's
production Google-ADC login (RS256) and the RS256 JWT paths cannot run on
jolt until RSA exists.

### Load order — the trap and the fix

jolt autoloads a `:jolt/provides` install namespace only while the
referenced class is **unregistered**. But jolt.crypto's `install!`
registers EC-only `Signature`/`KeyPairGenerator`/`KeyFactory` as a side
effect of ITS autoload (any `javax.crypto.Mac`/`Cipher` reference), and a
registered class never triggers a provider lookup again — so a namespace
that compiled after jolt.crypto loaded would bind the EC-only versions and
RSA would be unreachable. `jolt.kmet.providers` therefore:

1. requires `jolt.crypto` as its **first form** (its `:jolt/native`
   libcrypto load + EC/symmetric registrations always precede ours;
   `__register-class-statics!` merges into the class's shared table,
   re-registered members last-wins);
2. is claimed in `jolt/deps.edn` `:jolt/provides` for the asymmetric
   classes + `HttpTimeoutException` (deterministic first-reference
   autoload);
3. is additionally forced by **guarded requires** in the src nses that
   reference the classes directly (`kmet.libs.crypto`, `kmet.ai.google-adc`):
   `(when (find-var 'clojure.core/*jolt-version*) (require 'jolt.kmet.providers))`
   as the first form after the ns — covers the poisoned case and classes
   that cannot be claimed (`java.util.Base64`: jolt refuses claims on
   classes it implements; missing members are added at install).

This pattern is the AGENTS.md convention for any future consumer.

### Provided (verified against the bb/JVM reference)

| shim | notes |
|---|---|
| `java.util.Base64/getMimeDecoder` | pure-Clojure MIME decode (non-alphabet chars discarded, JDK rules), returns the same `[B` type core's decoder returns |
| `java.net.http.HttpTimeoutException` ctor | `jolt.host/throwable` + `register-class-supers!` edge to `java.io.IOException` — `instance?`/`catch` on Throwable/Exception/IOException match the JVM; `toString`/`ex-message`/`getCause` identical; only the `String` ctor exists, as on the JDK |

Green on jolt after this: `libs.test-crypto/test-parse-private-key-rejects-garbage`,
`ai.test-llm/test-llm-transport-error-message`; the EC tests (`pkcs8-ec`,
`sign-jwt-es256`) moved past the Base64 gap onto the `.toByteArray` blocker.

### Roadmap (next)

- **RSA** — re-register `Signature`/`KeyPairGenerator`/`KeyFactory` statics
  with an RSA+EC dispatcher (EC delegates by rebuilding jolt.crypto's
  tagged tables; RSA via libcrypto `EVP`, the same FFI seam). Unblocks the
  RS256 tests + production Google-ADC/oauth RS256 on jolt.
- **JWK `.toByteArray`** — kmet's DER builders call `.toByteArray` on
  values jolt models as `Long`/`BigInt` when small (jwk-ec/jwk-rsa and the
  EC tests): a portable bigint→two's-complement-bytes helper in
  `kmet.libs.crypto` (pure code, works on both hosts).