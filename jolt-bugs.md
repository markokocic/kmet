# jolt-bugs — jolt issue inventory

Everything this repo knows about jolt-side bugs and upstream tickets.
`jolt-port.md` / `jolt-tui.md` describe port state without ticket IDs.

**Workarounds live next to their ticket below.** Each workaround block is the
removal checklist: when an upstream fix lands, delete the listed code (and the
block). Source comments describe the local *why* without ticket numbers —
grep this file to find what `file:line` belongs to which ticket.

Historical labels from the deleted `bb-jolt.md` map as: `JOLT-10`→#945,
`JOLT-11`→#946, `JOLT-12`→#947, `JOLT-13`→#944; git history has the full
field reports.

## Filed by kmet — open

### [jolt#944](https://github.com/jolt-lang/jolt/issues/944) — `jolt build` binary dies on `unbound fn jolt.time.impl/register-type!`

**Area:** build / AOT

**Impact:** a built binary starts only when the build's analysis autoloads the
`jolt.time` provider in-process; kmet's graph currently satisfies that
(load-time `DateTimeFormatter`/`ZonedDateTime`/`ZoneId` refs), but one
refactor of the entry-point closure can flip it.

**Workaround:** none in code today. Contingency: an early `(:require
[jolt.time])` in the entry namespace if a built binary dies at startup.
Nothing to remove when fixed — just re-verify `jolt build -m kmet.core` runs.

### [jolt#945](https://github.com/jolt-lang/jolt/issues/945) — `re-find` stalls ~5 s on a 50-alternative pattern with `.*` branches (never returns on aarch64)

**Area:** regex

**Impact:** first `kmet.app.loop/retryable-error?` call stalls ~5 s on x86_64
and hangs on Termux/aarch64 — on the provider retry-classification path;
cascades into the runner's per-namespace timeout there.

**Workaround:** none applied — `retryable-error-regex`
(`src/kmet/app/loop.clj:448`) still carries the full 50-alternative pattern.
If it bites again: split the two `.*` branches out (or use `str/includes?`),
or raise `kmet.runner`'s per-namespace timeout (`test/kmet/runner.clj:448`,
15 s). Nothing to remove when fixed.

### [jolt#946](https://github.com/jolt-lang/jolt/issues/946) — `CodingErrorAction` absent; `CharsetDecoder` implements only `.charset`

**Area:** charset / IO

**Impact:** the first decoded stream chunk threw — every bash tool call
failed on Jolt.

**Workaround** (`src/kmet/app/bash_executor.clj`): hand-rolled streaming
UTF-8 decode — `utf8-complete-end` (:60) plus the byte-carry stream decoder
built on `decode-chunk`/`finish-utf8` (:400–:431; a ≤3-byte `carry` atom
bridges read boundaries, malformed bytes become U+FFFD). Regression test:
`kmet.app.test-tools/test-bash-executor-streaming-utf8-decode`.
When fixed: decode with `CharsetDecoder` + `CodingErrorAction/REPLACE`
(`endOfInput? false`) and drop the carry machinery.

### [jolt#947](https://github.com/jolt-lang/jolt/issues/947) — `ProcessBuilder.redirectInput(File)` silently ignored

**Area:** process

**Impact:** children inherit jolt's stdin — `cat`-like commands steal the TTY
and block forever.

**Workaround** (`src/kmet/app/bash_executor.clj:240–:261`): always spawn with
`:in :pipe` and close the stream right after spawn (EOF for stdin readers).
Regression test: `kmet.app.test-tools/test-tool-bash-stdin-eof`.
When fixed: `:in (fs/file (if windows? "NUL" "/dev/null"))` — pi's stdio
`ignore` — and drop the close-after-spawn step (keep the `-s` transport's
command write).

### [jolt#948](https://github.com/jolt-lang/jolt/issues/948) — line reads keep a trailing `\r` (`BufferedReader.readLine`, `.lines`, `read-line`, `with-in-str`)

**Area:** IO / Reader

**Impact:** the OAuth callback server's header block never saw its blank
terminator (it arrived as `"\r"`, truthy) and blocked one line past the
headers — the request timed out. Any line-oriented protocol is affected.

**Workarounds:**
- `src/kmet/libs/oauth.clj:238–:247` — header-block emptiness check trims
  every line.
- `test/kmet/libs/test_http.clj:41–:57` — test server trims lines and values.
- `test/kmet/ai/test_llm.clj:1443, :1491, :1534` — test SSE servers trim the
  header terminator.

When fixed: drop the trims (plain `(seq line)`, raw values).

### [jolt#949](https://github.com/jolt-lang/jolt/issues/949) — multi-arg `java.net.URI` constructors missing (only the `String` ctor)

**Area:** net / URI

**Impact:** programmatic URL assembly throws `incorrect number of arguments`;
the original code's catch-swallowed failure silently produced a wrong Azure
base URL.

**Workaround** (`src/kmet/ai/api/azure_openai_responses.clj:26–:36`):
rebuild `scheme://[userinfo@]host[:port]/openai/v1` by hand from the
single-arg parse + getters.
When fixed: use `(java.net.URI. scheme userInfo host port path query
fragment)`.

### [jolt#950](https://github.com/jolt-lang/jolt/issues/950) — `java.net.http.HttpTimeoutException` has no constructor

**Area:** net

**Impact:** code that constructs the exception (mapping/raising request
timeouts) fails; the class name itself resolves.

**Workaround:** kmet's RFC 0014 provider lib registers the ctor —
`jolt/src/jolt/kmet/providers.clj:108–:121` (plus the `IOException` hierarchy
edge) and the `:jolt/provides` claim in `jolt/deps.edn:38`.
When fixed: delete the registration + claim (and the "provided" table row in
`jolt/README.md`).

### [jolt#951](https://github.com/jolt-lang/jolt/issues/951) — `LinkedBlockingQueue` has no constructor

**Area:** java.util.concurrent

**Impact:** the SSE body reader's idle-deadline queue could not be built
unbounded; a fixed-capacity queue was used instead.

**Workaround** (`src/kmet/libs/sse.clj:459–:463`): `(ArrayBlockingQueue.
65536)`.
When fixed: `(LinkedBlockingQueue.)` — and revisit whether backpressure on
the SSE daemon should stay.

### [jolt#952](https://github.com/jolt-lang/jolt/issues/952) — `io/reader` rejects reify/proxy `Reader`s; `BufferedReader` is identity over them

**Area:** IO / Reader

**Impact:** `io/reader` throws `Cannot open <reify> as a Reader` for
user-implemented Readers, and even if it did not, the identity
`BufferedReader` gives them no `.readLine`/`.close`.

**Workarounds** (`src/kmet/libs/sse.clj`):
- `body->reader` (:317–:328) returns Reader bodies as-is, bypassing
  `io/reader`.
- the `.read` char loop (:524–:529) replaces `.readLine`, with
  `strip-trailing-cr` (:491–:497) restoring CRLF handling.

When fixed: use `io/reader` + `.readLine` and drop the CR strip (the idle
path may keep the shared `.read` loop).

### [jolt#953](https://github.com/jolt-lang/jolt/issues/953) — `java.text.Normalizer` / `Normalizer$Form` unusable

**Area:** text

**Impact:** the class resolves, every member throws "no dependency provides" —
path matching and edit fuzzy-matching silently degrade (no crash).

**Workarounds:**
- `src/kmet/app/tools/read.clj:78–:84` — NFD path variant in a try/catch.
- `src/kmet/libs/edit_diff.clj:150–:158` — NFKC fuzzy-match step in a
  try/catch.

When fixed: drop both try/catches.

### [jolt#954](https://github.com/jolt-lang/jolt/issues/954) — `SocketOutputStream.write(byte[])` throws a cast error

**Area:** net / sockets

**Impact:** the 2-arg whole-array write is unusable; a callback response
would close with no page.

**Workarounds:**
- `src/kmet/libs/oauth.clj:254–:275` — response writes through the 3-arg
  overload.
- `test/kmet/libs/test_http.clj:14–:24` — `sock-write` helper (3-arg).
- `test/kmet/ai/test_llm.clj:28–:36` — `sock-write` helper (3-arg).

When fixed: plain `.write out (.getBytes ...)` / `.write out b`.

### [jolt#955](https://github.com/jolt-lang/jolt/issues/955) — `java.util.Base64/getMimeDecoder` / `getMimeEncoder` are missing, and a library cannot supply them

**Area:** Base64 / RFC 0014 provider reach

**Impact:** PEM/PKCS parsing in the crypto, JWT and Google-ADC login paths
fails at the first decode. Four of the six JDK statics work; the MIME pair
answers `No matching field or method`. No library can supply them: a
`:jolt/provides` claim on a runtime-implemented class is refused at resolve
time (`IllegalArgumentException: … claims host class Base64, which the
runtime already provides.`), and a member miss on an implemented class never
reaches the autoload path (`host-static-ref` autoloads only on a *class*
miss).

**Workaround:** kmet's provider lib registers the MIME decoder through the
internal `__register-class-statics!` API
(`jolt/src/jolt/kmet/providers.clj:99–:114`), and the *guarded requires* are
what install it before a referencing namespace is analyzed —
`src/kmet/libs/crypto.clj:28–:29`, `src/kmet/ai/google_adc.clj:25–:26`.
Regression test: `kmet.libs.test-crypto` (PEM paths).
When fixed: delete the decoder registration and the two guarded requires
(the `HttpTimeoutException` half stays until #950 is fixed).

### [jolt#956](https://github.com/jolt-lang/jolt/issues/956) — regex `.`, `^`, `$` apply the UNIX_LINES terminator set (`\n` only) unconditionally

**Area:** regex

**Impact:** the engine behaves as if Java's `(?d)` UNIX_LINES were always
on; Java's default terminator set is `\r`, `\n`, `\r\n`, NEL (`\u0085`),
LS (`\u2028`), PS (`\u2029`). CRLF text parsed with `.`/`^`/`$` silently
keeps the `\r` in captures and misses anchors at CR — the same engine as the
[jolt#945](https://github.com/jolt-lang/jolt/issues/945) stall. Found in
`parse-dump-header` (HTTP header parsing of curl's CRLF output).

**Workaround:** `src/kmet/libs/http.cljc:505–:535` (`parse-dump-header`)
trims every captured header value.
When fixed: drop the trim.
