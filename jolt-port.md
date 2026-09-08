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
wrapper workstream (decided: curl-subprocess transport for all Jolt HTTP
— see B1)
and ~15 medium rewrites. A staged port is viable: pure layers first
(`libs` minus I/O → `ai/api` builders → `reakt`/`hiccup`/components), then
the terminal adapter, then transports, then the agent loop + tools, with the
extension system redesigned last. Roughly 60–70% of LOC is portable logic;
the rest is JVM/Babashka surface that must be reimplemented against Jolt
shims + C FFI.

Related doc: `jolt-tui.md` (TUI adapter deep-dive — FFI ground rules,
termios/kernel32 raw mode, input pipeline, key parser, concurrency mapping;
its §§4–7,9 cover the terminal adapter).

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

### B1. HTTP/SSE transport (`libs/http.cljc` 731 LOC + `libs/sse.clj` 1084 LOC)

kmet funnels ALL outbound HTTP through `kmet.libs.http` (enforced by
`test-http-boundary`): `babashka.http-client` (java.net.http) for plain
requests + raw `curl` subprocess for SOCKS/https-scheme proxies, streaming
bodies, idle-timeout readers. Every LLM call in every provider rides this.

**Decision: Jolt routes ALL HTTP through the existing curl transport.**
Implemented in `libs/http.cljc` via `#?(:jolt ...)` reader conditionals:
the java.net.http transport is JVM-only, and Jolt falls through to
`curl-request` for every request. Rationale: the curl path already handles
direct connections, proxies, streaming (`:as :stream`), cancel (`:signal`),
and idle timeouts, and it uses only `babashka.process` + `java.io.File` +
`java.lang.Process`. `jolt-lang/http-client` (evaluated 2026-09-06:
`clj-http-lite` on a hand-rolled HTTP/1.1 stack — sockets via `jolt.ffi`,
TLS/OpenSSL, libz, exposed as `java.net.URL`/`HttpURLConnection` +
`java.net.http` shims) was rejected as the Jolt transport: it covers only
the unproxied, non-streaming slice (no true streaming — `perform!`/`net-http-send`
both `recv-all` to EOF, so `:as :stream` is buffered-then-wrapped; no proxy
env support; no cancel/`signal`; per-read rather than total timeouts), and
the provider hot path (`api/*` → `:as :stream` → `sse.clj` line-by-line with
idle-timeout + `abort-fn` + `signal`) needs exactly what it lacks — so curl
would still be required alongside it, and a single transport is simpler.
Worth proposing upstream: true streaming body, per-request total deadline,
proxy env support.

`sse.clj` itself is mostly pure parsing/state-machine (port the logic);
only its body reader (`io/reader` over the response stream + idle-timeout
thread) needs the new transport. `jsonrpc.clj` (409 LOC, MCP stdio
transport) rides `babashka.process` pipes — portable *if* `jolt.process`
covers spawn + async pipe IO + `destroy-tree` (verified: `process.ss` implements `ProcessHandle` descendant tracking behind `destroy-tree`; still probe pipe-streaming + Windows behavior).

**Done (curl-only, `http.cljc` ported):** the port keeps
`kmet.libs.http`'s contract (opts, lowercased headers,
`:http-error`/`:transport-error`, `proxy-for-url`); Jolt falls through to
`curl-request` for every request, the JVM keeps java.net.http for direct
traffic. Verified: GET/POST return status=200 on Jolt (cold-run probes).

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
| M1 | `clojure.data.json` (the swap from `cheshire` → `data.json` is done — `kmet.libs.json` now aliases `clojure.data.json` directly) | **no JSON lib in stdlib** — biggest pure-logic gap. Write a `kmet.libs.json` over string ops; Jolt strings/regexes suffice. Streaming tool-call arg accumulation in `sse.clj` needs incremental parsing — keep the shape, swap the parser. **Note:** `http.cljc` is already ported (curl path via `#?(:jolt ...)`); all 27 libs now load and test green on bb/JVM. M1 is now purely a Jolt-stdlib gap | new ~500-800 LOC lib |
| M2 | `tui/terminal.clj` (JLine raw/timed-reads/size) + `core.clj` reader/timers/resize/drain | termios FFI (Unix) + kernel32 FFI (Windows); `future` reader + `locking` + gen-counters — see `jolt-tui.md` §§4–7,9. Evaluated 2026-09-06: `jolt-lang/glimmer-tui` (ncursesw via FFI, Unix-only, fullscreen `initscr` takeover) rejected — wrong architecture for the inline ANSI/scrollback model; JLine stays on bb (`jolt-tui.md` §2 decision) | rewrite ~500 LOC (Jolt only) |
| M3 | `libs/crypto.clj` (315 LOC: RSA/EC `KeyFactory`, `SHA256withRSA/ECDSA` `Signature`) + `libs/aws_sigv4.clj` (213 LOC: `MessageDigest` SHA-256, `Mac` HmacSHA256, `HexFormat`, `Normalizer`?) — grep the exact class list before the FFI design | OpenSSL FFI following `mvn_http.clj`'s libcrypto/libssl loading (note macOS boringssl SIGABRT hazard — explicit Homebrew paths only); RSA via libcrypto; `SecureRandom` via OS source. Check the `io.github.jolt-lang/crypto` git dep in `deps.edn` first (RFC 0014 shims may already cover the call sites) | rewrite ~500 LOC |
| M4 | `libs/oauth.clj` (611) + `ai/oauth.clj` (1012) + `ai/google_adc.clj` (121) — browser launch, localhost callback server, token cache | `ServerSocket` shim exists (`stdlib/jolt/socket.clj`, gated on `(require 'jolt.socket)`); browser launch via `jolt.process`; token cache via `spit`/`slurp` | adapt ~1.7k LOC |
| M5 | `libs/archive.clj` (46 LOC, `ZipFile` read) + `sse.clj:854-56` (`CRC32`) + `extensions.cljc:910,921` (`JarFile` probes) + `build.cljc:227,245,389` (`ZipOutputStream` uberjar/pack-extension). (`ai/models.clj` needs no zip work — catalogs load via `io/resource`, which answers file:/jar:/embedded URLs alike.) | `jolt.fs` explicitly EXCLUDES zip/gzip (`stdlib/jolt/fs.clj:12`: "java.util.zip not shimmed yet"). **DECIDED 2026-09-08: bb-only until the `jolt build` rewrite** — `build.cljc`/`libs/archive.clj` entry points throw `::bb-only` under Jolt, their tests carry `^:bb-only` (the runner skips them there); zip/jar work defers to extension-jar materialization via unzip (jolt's own mvn-jar model) | rewrite build; archive via FFI or subprocess. Note:
| M6 | `build.cljc` uberjar assembly (`bcp/get-classpath`, `ZipOutputStream` resource listing) + model-catalog embedding | No classpath concept; `jolt build` embeds source roots differently. Model catalogs (`ai/model_data/` + manifest) become embedded resources — `io.ss` has `register-embedded-resource!` and `io/resource` answers a `java.net.URL` from both disk and a built image | adapt ~200 LOC |
| M7 | `libs/clipboard.clj`, `libs/terminal_image.clj` (Base64 — shimmed, keep), OSC-52/kitty-graphics emit | clipboard via platform subprocesses (`pbcopy`/`xclip`/`clip`) through `jolt.process`; image protocols are pure emit logic | small |
| M8 | `config.clj` (XDG paths, EDN load/save, file watching?) | `jolt.fs` (vendored `babashka.fs`, minus zip) covers paths; `spit`/`slurp`/EDN portable; watcher → poll (same as `tui.theme`) | adapt |
| M9 | `debug.clj` (file logging) + crash/render logs | `(spit path text :append true)` (`jolt-io-writer` is 1-arg — `io.ss:1314-1323`; `spit` takes `:append` — `io.ss:1164-1195`); timestamps via the `io.github.jolt-lang/time` dep (already in `deps.edn`) or manual format. Note: Jolt's `java.io.tmpdir` honors `$TMPDIR` (`host-static-methods.ss:1002,1016`), unlike bb's hardcoded `/tmp` — keep the explicit-dir pattern anyway | small |
| M10 | `bb.edn` tasks (22: `run` + 21: uberjar/build/test/test-ext/changed/test-changed/test-ext-changed/lint-changed/format-changed/format-check-changed/nrepl/check/generate-models/generate-image-models/check-model-data/pack-extension/lint/format/format-check/help) | **DONE (test task):** `kmet.runner` is now host-aware and tolerant — every test namespace is required in a try; unloadable ones (babashka-internal requires like `babashka.classpath`/`babashka.classes`, `java.time.format.DateTimeFormatter` gaps, …) are reported and skipped, the rest run. Per-var `^:slow` split + per-var filters work on BOTH hosts (`jolt test` non-slow / `jolt test-ext` slow; bb.edn `:paths ["src" "test"]` supplies the roots under jolt). Engine: bb = per-var output capture + ref counters; jolt = `clojure.test/test-vars` with jolt's own process-wide `counters` atom read as before/after deltas (`jolt?` = `(find-var 'clojure.core/*jolt-version*)`). Remaining M10 work: `jolt build` packaging, lint/format gates, model generators | mostly done for tests |
| M11 | `clojure.spec.alpha` (SCI-context injection only), `clojure.walk` (2 requires: `libs/json.clj:16`, `ai/constrained_sampling.clj:13`), `BigDecimal` (`edn_writer` + SCI class table) | spec: absent from `stdlib/` (verified — declare `org.clojure/spec.alpha` explicitly per README's "terminal dependency" rule, or rewrite the one use); `walk`: present (`stdlib/clojure/walk.clj`, seed-embedded — keep); `BigDecimal`: PRESENT (`host/chez/java/bigdec.ss`: `M` literals + `with-precision` per README — the earlier "absent" claim was wrong; just port the call sites) | small |
| M12 | `defrecord` (27 files) + `reify` (6 files) + protocols + `deftype` (zero definitions — only comments) | README Differences confirms `deftype`/`defrecord`/`reify`/`extend-protocol`, multimethods, STM, `future`/`promise`/`agent` and `core.async` behave as on the JVM — still verify early: `satisfies?`-on-reify semantics, `defrecord` positional factories, protocol dispatch for `IComponent`/`IFocusable`. The TUI's `satisfies?` avoidance notes (AGENTS.md SCI gotcha) need re-checking on Jolt | verify early, affects everything |
| M13 | Custom `defcomponent`/`with-let` macros + clj-kondo hooks | Jolt compiles macros normally (self-hosted compiler) — should port; re-verify hygiene/&env behavior (`go`-style passes are async-only, plain macros fine). Kondo hooks keep working (source-level) | verify early |
| M14 | `java.util.concurrent` — 4 sites: `LinkedBlockingQueue`+`TimeUnit` (`libs/sse.clj:444,462`, idle-deadline reader), `ReentrantLock` (`app/session.clj:154,296`, file-mutation lock), `Callable` (`app/extensions.clj:738`, SCI class table) | `ReentrantLock` is shimmed and `ArrayBlockingQueue` is a real bounded blocking queue (both per `concurrency.ss` comments — verify exact arities on the checkout); `Callable` becomes a fn; `locking` covers the session lock. Rewrite call sites | small |
| M15 | `java.net.URI/URL/URLEncoder`, `Normalizer`, `Charset`, `HexFormat`, `Instant/DateTimeFormatter/ZoneId`, `PushbackReader`, `StringReader/Writer` | Mostly shimmed (host-interop list + `io.ss`/`io-streams.ss`); URL/URI surface exists (`jolt.socket` gating for sockets); time values via time lib. Verify each call site's exact methods | audit per site |

---

## 4. Verified `libs/` status (2026-09-08 snapshot)

Cold-ran every `kmet.libs.*` namespace under Jolt (`jolt v0.8.5`, threaded
Chez 10.x) — require + load each, then run its test suite (or probe its
public fns when no test file exists). On bb/JVM: **all 27
load and test green** — the cheshire → data.json swap unblocked the libs
that depended on M1. Jolt side: **most load and run; json/jsonrpc/sse (M1),
crypto/aws_sigv4 (M3), archive (M5) still gap the Jolt stdlib** (counts below
are the 2026-09-08 snapshot — re-run before building from them).

| lib | bb/JVM | Jolt | notes |
|-----|--------|------|-------|
| `archive` | 🟢 | 🔴 | bb-only on Jolt (2026-09-08, M5): `::bb-only` entry guard + `^:bb-only` tests; zip work deferred to extension-jar materialization (unzip) |
| `aws_sigv4` | 🟢 | 🔴 | `javax.crypto.Mac`, `java.security.MessageDigest` (M3) |
| `clipboard` | 🟢 | 🟢 | uses `babashka.process`, works |
| `concurrent` | 🟢 | 🟢 | `spawn` returns `Thread`; works |
| `context` | 🟢 | 🟢 | tests pass (11/11) |
| `crypto` | 🟢 | 🔴 | `java.security.KeyFactory`/`Signature` (M3); data.json dep now resolved on bb |
| `diff` | 🟢 | 🟢 | pure, works |
| `dynamic_value` | 🟢 | 🟢 | tests pass (53/53) |
| `edit_diff` | 🟢 | 🟢 | uses `java.text.Normalizer`, `java.util.regex.Pattern`; works |
| `edn_store` | 🟢 | 🟢 | tests pass (40/40) |
| `edn_writer` | 🟢 | 🟢 | pure, works |
| `hash` | 🟢 | 🟢 | pure, works |
| `highlight` | 🟢 | 🟢 | tests pass (139/139) |
| `hooks` | 🟢 | 🟢 | pure, works |
| `http` | 🟢 | 🟡 | **ported** — routes Jolt through curl transport via `#?(:jolt ...)` reader conditionals; JVM keeps java.net.http. Loads on bb |
| `json` | 🟢 | 🔴 | data.json resolved on bb; no JSON lib in Jolt stdlib (M1) |
| `jsonrpc` | 🟢 | 🔴 | data.json resolved on bb; no JSON lib in Jolt stdlib (M1) |
| `markdown` | 🟢 | 🟢 | tests pass (137/137) |
| `num` | 🟢 | 🟢 | portable predicates, works |
| `oauth` | 🟢 | 🔴 | `ServerSocket` shim exists (M4); data.json resolved on bb |
| `process` | 🟢 | 🟢 | uses `babashka.process`; works |
| `reakt` | 🟢 | 🟢 | tests pass (30/30) |
| `sse` | 🟢 | 🔴 | data.json resolved on bb; no JSON lib in Jolt stdlib (M1) |
| `terminal` | 🟢 | 🟢 | uses `java.time`, `java.lang.ProcessHandle`, `java.util.Base64`, `clojure.java.io`; works |
| `terminal_image` | 🟢 | 🟢 | tests pass (41/41) |
| `usage` | 🟢 | 🟢 | pure, works |
| `yaml` | 🟢 | 🟡 | bb: 20/20; Jolt: 19/20 — `test-numbers` fails (bigint vs string) |

**bb/JVM: all green (27).** Jolt: json/jsonrpc/sse (M1: no JSON lib in
Jolt stdlib) and crypto/aws_sigv4 (M3: JVM crypto classes) need work;
archive (M5) is bb-only on jolt since 2026-09-08. The cheshire →
data.json swap removed the biggest bb-side blocker; M1 is now purely a
Jolt-stdlib gap.

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
2. **Pure libs** (1–2 wks): port the §4 pure set + write `kmet.libs.json` (M1). Headless tests under Jolt's `clojure.test`.
3. **TUI core** (2–3 wks): `keys`→`utils`→`reakt`→`hiccup`→components→theme headless (`render-lines`), then `ITerminal` FFI adapter + input pipeline (`jolt-tui.md` §§5–7). Validate with pty captures.
4. **HTTP/SSE + providers** (3–5 wks, critical path): B1 transport decision + `sse` port + all 10 `api/` builders + `llm.clj` retry/cancel + auth (M4). First end-to-end: `print` mode (`modes/print.clj`, 102 LOC) answering one prompt — no TUI needed.
5. **Agent loop + tools** (2–4 wks): `app/loop.clj`, session/compaction, tools (bash/edit/write need care: process + fs + diff), `modes/interactive.clj` wiring.
6. **Packaging + tooling** (1–2 wks): `jolt build` pipeline replacing `build.cljc`, test runner `^:slow` split, lint/format gates, model generators.
7. **Extensions** (open-ended): B3 redesign decision; port shipped extensions after.

Estimate honesty: B1 transport is **decided** (curl-only on Jolt, java.net.http on JVM via `#?(:clj ...)` reader conditionals — `http.cljc` ported). Remaining B1 work is `sse.clj` (pure parsing, port the logic) + `libs.oauth`/`ai.oauth`/`ai.google_adc` (M4, needs `jolt.socket`). M1 (clojure.data.json) is resolved on bb/JVM — all 27 libs load and test green. The remaining M1 gap is Jolt's stdlib (no JSON lib) — blocks json/jsonrpc/sse from loading under Jolt only. B3 is a research spike before it is labor.

---

## 7. Risks & open questions

1. **Jolt maturity**: the Chez backend is the only production target (Gambit is demo-grade); the `--library` entry + cross-`--target` flow are young — fine for a CLI, but verify each on the checkout before relying on it.
2. **Performance**: TUI frame loop (16ms, line diffs, grapheme widths) + token-streaming rates on Chez-interpreted-vs-compiled code — `jolt build` compiles; measure early with a streaming fixture. SCI-for-extensions perf unproven.
3. **Regex engine**: irregex vs Java — `keys.clj`, response parsers, `utils.clj` wrapping all need their test suites re-run (common patterns fine, edge features differ).
4. **`vendor/` pins**: the vendored sources carry no version constants to diff against `deps.edn` — re-verify the `babashka/fs` + `babashka/process` pins by file comparison on any Jolt upgrade.
5. **Windows**: kmet supports it (Git Bash resolution, `\` zip entries, `fs` separators); Jolt's Windows FFI surface has known gaps (`process.ss`) — Windows is the last platform to light up, after Unix parity.
6. **No `/tmp` on Termux / `~` expansion / Android IME paste paths**: kmet carries Termux-specific workarounds — re-verify each on Jolt. One is already better: Jolt's `java.io.tmpdir` honors `$TMPDIR` (`host-static-methods.ss:1002,1016`), unlike bb's hardcoded `/tmp` — keep the explicit-dir pattern anyway. Burst-paste detection rides the §7 pipeline, which ports logically.
