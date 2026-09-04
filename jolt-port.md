# Porting kmet to Jolt — full report

Scope: the whole repo (138 `src` files ≈ 52k LOC, 114 test files ≈ 34k LOC,
222 extension `.clj` files ≈ 17k LOC), not just the TUI. Jolt reference is
the full checkout at `~/src/cvstree/jolt` (`b3455297`) plus
`jolt-lang.net/docs/{native-interop,api/ffi,host-interop}`.

**Bottom line**: a full port is a multi-month project with 3 hard blockers
(HTTP/SSE transport, subprocess/process management, extension isolation)
and ~15 medium rewrites. A staged port is viable: pure layers first
(`libs` minus I/O → `ai/api` builders → `reakt`/`hiccup`/components), then
the terminal adapter, then transports, then the agent loop + tools, with the
extension system redesigned last. Roughly 60–70% of LOC is portable logic;
the rest is JVM/Babashka surface that must be reimplemented against Jolt
shims + C FFI.

Related doc: `jolt-tui.md` §§4–10 (TUI adapter deep-dive — FFI ground rules,
termios/kernel32 raw mode, input pipeline, key parser, concurrency mapping).

---

## 1. Inventory — what kmet is made of

| area | files | LOC | character |
|---|---|---|---|
| `libs/` (generic utils) | 25 | ~8.3k | half pure (diff, yaml, markdown, highlight, reakt), half JVM-bound (http, process, crypto, archive, oauth, jsonrpc, sse) |
| `ai/` (providers/auth) | 13 + 10 `api/` | ~8.8k | request builders (pure) + streaming over `libs.http` + auth/token caches on disk |
| `tui/` (generic TUI) | 11 + 21 components | ~11.7k | ~95% pure; only `terminal.clj` (JLine) + `core.clj` reader/timers touch the host |
| `app/` (agent/tools/ui) | 14 + 11 tools + 26 ui | ~16.9k | business logic + tools (bash/edit/grep/ls/read/write/find) + `extensions.clj` (SCI) |
| `modes/` (entry) | 2 | ~4.5k | `interactive.clj` (4.4k, TUI wiring) + `print.clj` (headless) |
| root (`core/config/debug/extension/build`) | 5 | ~1.6k | CLI dispatch, config loading, extension contract, bb-based build |
| `extensions/` (shipped) | 222 | ~17k | opt-in extensions incl. `mcp-adapter`, `clojure`, `lsp-adapter`, `tools.clj` |
| `test/` | 114 | ~34k | `clojure.test`, `kmet.runner` custom runner with `^:slow` split |

External deps (`deps.edn` + `bb.edn`): `babashka/fs`, `babashka/process`
(first-party bb libs), `borkdude/deps.clj` (Maven resolution),
`dev.weavejester/cljfmt` (tooling only), JLine 4.3.1 (bb-bundled),
`cheshire` (bb-bundled). `bb.edn` also defines ~22 tasks (build/test/lint/format/nrepl/generate-models/…).

---

## 2. Hard blockers (need design + substantial new code)

### B1. HTTP/SSE transport (`libs/http.clj` 725 LOC + `libs/sse.clj` 1084 LOC)

kmet funnels ALL outbound HTTP through `kmet.libs.http` (enforced by
`test-http-boundary`): `babashka.http-client` (java.net.http) for plain
requests + raw `curl` subprocess for SOCKS/https-scheme proxies, streaming
bodies, idle-timeout readers. Every LLM call in every provider rides this.

Jolt has **no HTTP client library**: `stdlib/` has `jolt/mvn_http.clj`
(TLS-via-OpenSSL FFI, Maven-fetch shaped — not a general client),
`jolt/socket.clj` (blocking IPv4 TCP over POSIX sockets via FFI, 800 LOC),
`jolt/process.clj` (thin re-export of vendored `babashka.process`).
So the port must build a client on one of:

- (a) `jolt.socket` + OpenSSL FFI (mirroring `mvn_http`'s TLS) — full
  control, large work: TLS, chunked/SSE framing, redirects, timeouts,
  proxy (incl. SOCKS), connection reuse;
- (b) `curl` subprocess via `jolt.process` — much smaller, inherits proxy
  parity for free, but streaming + cancel + timeout semantics must be
  rebuilt over pipes, and Windows needs a bundled curl;
- (c) hybrid: (b) first for all transports, (a) later for the hot path.

`sse.clj` itself is mostly pure parsing/state-machine (port the logic);
only its body reader (`io/reader` over the response stream + idle-timeout
thread) needs the new transport. `jsonrpc.clj` (409 LOC, MCP stdio
transport) rides `babashka.process` pipes — portable *if* `jolt.process`
covers spawn + async pipe IO + `destroy-tree` (verify; `process.ss` has
`destroy-tree` + descendants tracking, promising).

### B2. Subprocess/process management (`libs/process.clj`, bash tool, MCP stdio)

kmet leans on `babashka.process` hard: `proc/process` (19 uses), `shell`,
`destroy-tree`, pid tracking, `setsid`, timeout-kill, pipe streaming.
`jolt.process` re-exports vendored `babashka.process` over Jolt's
`ProcessBuilder`/Process shims (`process.ss`) — the highest-leverage
compat to verify early. Gaps to probe: `destroy-tree` on all OSes,
pipe-streaming without deadlock, timeout semantics (bb's `:timeout`
reports exit 0 on kill — kmet works around it; check Jolt matches),
`setsid`/process-group kill, `exec` (explicitly NOT re-exported — check
nothing needs it), stdin/stdout as async streams for MCP stdio servers.
The bash tool + `bash-executor` + session export + git operations all sit
on this; if `jolt.process` falls short, the fallback is direct
`fork`/`exec`/`waitpid`/`kill` FFI (all in `process.ss` already for Jolt's
own use — reusable patterns).

### B3. Extension isolation (`app/extensions.clj` — SCI, ~1500 LOC)

Each extension evaluates in its own **SCI context** (`sci/init`,
`sci/eval-form`): private ns registry + loader serving own files, declared
Maven jars (resolved in-process via `borkdude/deps.clj`), and host
classpath; shared layers (`kmet.extension`, `clojure.*`, `babashka.*`,
`kmet.tui.*`, `kmet.libs.*`) injected by reference. Plus bb-import tables,
bundled-lib redirection (cheshire, rewrite-clj, edamame, …), per-extension
deps.edn, load-fn error handling, classpath-overrides matching bb.

Jolt **can run SCI's source** (`make sci` loads `borkdude/sci` through
joltc; `scifunctional` runs SCI functional tests) — so porting the
mechanism is plausible, not impossible. But: SCI-on-Jolt performance for a
whole extension ecosystem is unproven; `borkdude/deps.clj` (JVM Maven
resolution) must be replaced by Jolt's own dep fetching (`jolt.deps`
uses `slurp` + `mvn_http` — check it can serve arbitrary Maven closures);
and the bb-import/bundled-lib tables must be rebuilt against Jolt's shim
set. Alternative designs worth costing: (1) extensions as plain Jolt
namespaces, no isolation (loses version isolation); (2) extensions as
subprocesses over JSON-RPC (the MCP pattern — strong isolation, new
protocol work); (3) SCI as now. This is the last milestone either way —
the core agent must work before extensions matter.

---

## 3. Medium rewrites (bounded, one namespace at a time)

| # | kmet surface | Jolt answer (verified on checkout) | size |
|---|---|---|---|
| M1 | `cheshire` (`json/parse-string` 28×, `generate-string` 26×, across `ai/`, `libs/`, `app/`) | **no JSON lib in stdlib** — biggest pure-logic gap. Write a `kmet.libs.json` (or vendor data.json) over string ops; Jolt strings/regexes suffice. Streaming tool-call arg accumulation in `sse.clj` needs incremental parsing — keep the shape, swap the parser | new ~500-800 LOC lib |
| M2 | `tui/terminal.clj` (JLine raw/timed-reads/size) + `core.clj` reader/timers/resize/drain | termios FFI (Unix) + kernel32 FFI (Windows); `future` reader + `locking` + gen-counters — see `jolt-tui.md` §§4–7,9 | rewrite ~500 LOC |
| M3 | `libs/crypto.clj` + `libs/aws_sigv4.clj` (`MessageDigest`, `Mac/HmacSHA256`, `Signature`, `KeyFactory`, `SecureRandom`, `HexFormat`) | OpenSSL FFI following `mvn_http.clj`'s libcrypto/libssl loading (note macOS boringssl SIGABRT hazard — explicit Homebrew paths only); RSA via libcrypto; `SecureRandom` via OS source | rewrite ~500 LOC |
| M4 | `libs/oauth.clj` + `ai/oauth.clj` + `ai/google_adc.clj` (browser launch, localhost callback server, token cache) | `ServerSocket` shim exists (host-interop lists it, gated on `(require 'jolt.socket)`); browser launch via `jolt.process`; token cache via `spit`/`slurp` | adapt ~1k LOC |
| M5 | `libs/archive.clj` (`ZipFile`/`ZipOutputStream`/`JarFile`/`CRC32`) + `build.clj` (uberjar/catted-binary packaging) | `jolt.fs` explicitly EXCLUDES zip/gzip ("java.util.zip not shimmed yet"). Options: miniz FFI, `unzip`/`zip` subprocess, or drop archive support from build/pack-extension. `jolt build` replaces the bb-binary+catted-uberjar packaging entirely — `build.clj` (460 LOC) is rewritten anyway | rewrite build; archive via FFI or subprocess |
| M6 | `ai/models.clj` + `build.clj` classpath scanning (`bcp/get-classpath`, uberjar resource listing) | No classpath concept; `jolt build` embeds source roots differently. Model catalogs (`ai/model_data/` + manifest) become embedded resources — check Jolt's resource story (`io/resource` exists? `embedded-res?` in `io.ss` suggests yes — verify) | adapt ~200 LOC |
| M7 | `libs/clipboard.clj`, `libs/terminal_image.clj` (Base64 — shimmed, keep), OSC-52/kitty-graphics emit | clipboard via platform subprocesses (`pbcopy`/`xclip`/`clip`) through `jolt.process`; image protocols are pure emit logic | small |
| M8 | `config.clj` (XDG paths, EDN load/save, file watching?) | `jolt.fs` (vendored `babashka.fs`, minus zip) covers paths; `spit`/`slurp`/EDN portable; watcher → poll (same as `tui.theme`) | adapt |
| M9 | `debug.clj` (file logging) + crash/render logs | `(spit path text :append true)` (`jolt-io-writer` is 1-arg, `spit` takes `:append` — `io.ss:1045`); timestamps without `LocalDateTime/now` (time lib or manual format) | small |
| M10 | `bb.edn` tasks (22: build/test/lint/format/nrepl/generate-…/pack-extension/…) | No bb. Test runner → `clojure.test` on Jolt (+ port `kmet.runner`'s `^:slow` split); build → `jolt build`; lint/format → clj-kondo/cljfmt still work on source (they're host-independent); nREPL → Jolt's socket REPL? (check); model generators are plain HTTP scripts (ride B1) | new tooling layer |
| M11 | `clojure.spec.alpha` (1 use), `clojure.walk`, `BigDecimal` (`edn_writer` + extensions) | spec: check stdlib (not seen — likely absent; rewrite the one use or vendor spec); `walk`: trivial to inline; `BigDecimal`: absent per host-interop (`decimal?` always false — verify money paths don't need it) | small |
| M12 | `defrecord` (27 files) + `reify` (4) + protocols + `deftype` (few) | Jolt supports records/protocols/reify (hierarchy ss registers record supers). Verify: `satisfies?`-on-reify semantics, `defrecord` positional factories, protocol method dispatch for `IComponent`/`IFocusable`. The TUI's `satisfies?` avoidance notes (AGENTS.md SCI gotcha) need re-checking on Jolt | verify early, affects everything |
| M13 | Custom `defcomponent`/`with-let` macros + clj-kondo hooks | Jolt compiles macros normally (self-hosted compiler) — should port; re-verify hygiene/&env behavior (`go`-style passes are async-only, plain macros fine). Kondo hooks keep working (source-level) | verify early |
| M14 | `java.util.concurrent` (`LinkedBlockingQueue`, `ReentrantLock`, `TimeUnit`, `Callable`) — few uses | `future`/`promise`/`locking`/agents/`core.async` (real threads + fibers + channels, `async.clj` ~superset) cover these; rewrite call sites | small |
| M15 | `java.net.URI/URL/URLEncoder`, `Normalizer`, `Charset`, `HexFormat`, `Instant/DateTimeFormatter/ZoneId`, `PushbackReader`, `StringReader/Writer` | Mostly shimmed (host-interop list + `io.ss`/`io-streams.ss`); URL/URI surface exists (`jolt.socket` gating for sockets); time values via time lib. Verify each call site's exact methods | audit per site |

---

## 4. What ports mostly as-is (the good news)

- **Pure logic** (~60–70% of LOC): `libs/{diff,edit_diff,yaml,markdown,highlight,usage,hash,context,edn_writer,dynamic_value,hooks,concurrent}`, `ai/api/*` request builders (all 10 provider wire formats — pure data transformation), `ai/{models,model_config,constrained_sampling,attribution,hooks}`, `libs/reakt`, `tui/{hiccup,macros,protocols,keys,keybindings,utils,theme}` + all 21 components, most of `app/{session,compaction,skills,prompts,commands,event_bus,keybindings,model_resolver}`, tools `{find,grep,ls,read}` (fs ops via `jolt.fs`).
- **`babashka.fs` → `jolt.fs`**: vendored + supplemented (`jolt.bb.fs`), same API minus zip. The 39 `fs/` call sites (`exists?`, `path`, `cwd`, `canonicalize`, `glob`, `create-dirs`, …) transfer almost mechanically.
- **`babashka.process` → `jolt.process`**: same re-export shape — IF the shims hold (B2 verification).
- **`clojure.string`/`clojure.edn`/`clojure.test`/`clojure.walk`-ish**: present in stdlib. `spit`/`slurp`/`with-open` present (archive caveat M5 for streams).
- **`System/getenv/getProperty`, `currentTimeMillis`/`nanoTime`, `Thread/sleep`, `StringBuilder`, `Base64`, `Pattern`**: all shimmed (see `jolt-tui.md` §9 for file-level refs).
- **`future`/`promise`/`locking`/agents/STM/`core.async`**: real and fiber-aware — the agent loop's concurrency (`AgentState` per-field atoms, futures for LLM calls, event bus) has a home; see §9 carrier rules.

---

## 5. Port order (staged, each stage testable)

1. **Prove the substrate** (days): `defrecord`/`reify`/`satisfies?` semantics (M12), macro hygiene (M13), `jolt.fs` fn coverage, `jolt.process` spawn/pipe/kill/timeout matrix (B2), `jolt.socket` reachability, SCI-load smoke (B3 feasibility), `io/resource` + embedded resources (M6).
2. **Pure libs** (1–2 wks): port the §4 pure set + write `kmet.libs.json` (M1). Headless tests under Jolt's `clojure.test`.
3. **TUI core** (2–3 wks): `keys`→`utils`→`reakt`→`hiccup`→components→theme headless (`render-lines`), then `ITerminal` FFI adapter + input pipeline (`jolt-tui.md` §§5–7). Validate with pty captures.
4. **HTTP/SSE + providers** (3–5 wks, critical path): B1 transport decision + `sse` port + all 10 `api/` builders + `llm.clj` retry/cancel + auth (M4). First end-to-end: `print` mode (`modes/print.clj`, 102 LOC) answering one prompt — no TUI needed.
5. **Agent loop + tools** (2–4 wks): `app/loop.clj`, session/compaction, tools (bash/edit/write need care: process + fs + diff), `modes/interactive.clj` wiring.
6. **Packaging + tooling** (1–2 wks): `jolt build` pipeline replacing `build.clj`, test runner `^:slow` split, lint/format gates, model generators.
7. **Extensions** (open-ended): B3 redesign decision; port shipped extensions after.

Estimate honesty: stages 1–3 are predictable port labor; B1's transport choice dominates the schedule (curl-subprocess weeks vs OpenSSL-native months for parity); B3 is a research spike before it is labor.

---

## 6. Risks & open questions

1. **Jolt maturity**: single-threaded library entry (`jolt_library_init` on one thread), no cross-`--target` natives, Gambit backend demo-grade — the Chez backend is the only target; fine for a CLI.
2. **Performance**: TUI frame loop (16ms, line diffs, grapheme widths) + token-streaming rates on Chez-interpreted-vs-compiled code — `jolt build` compiles; measure early with a streaming fixture. SCI-for-extensions perf unproven.
3. **Regex engine**: irregex vs Java — `keys.clj`, response parsers, `utils.clj` wrapping all need their test suites re-run (common patterns fine, edge features differ).
4. **`vendor/` submodules**: `babashka.fs`/`process` come from git submodules — pin versions matching kmet's `deps.edn` (`fs 0.5.34`, `process 0.6.25`) or adapt to drift.
5. **Windows**: kmet supports it (Git Bash resolution, `\` zip entries, `fs` separators); Jolt's Windows FFI surface has known gaps (`process.ss`) — Windows is the last platform to light up, after Unix parity.
6. **No `/tmp` on Termux / `~` expansion / Android IME paste paths**: kmet carries Termux-specific workarounds — re-verify each on Jolt (`TMPDIR`-explicit dirs, burst-paste detection rides §7 which ports logically).
