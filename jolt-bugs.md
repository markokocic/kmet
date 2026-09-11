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

### [jolt#947](https://github.com/jolt-lang/jolt/issues/947) — `ProcessBuilder.redirectInput(File)` silently ignored

**Area:** process

**Impact:** children inherit jolt's stdin — `cat`-like commands steal the TTY
and block forever.

**Workaround** (`src/kmet/app/bash_executor.clj:228`): always spawn with
`:in :pipe` and close the stream right after spawn (EOF for stdin readers).
Regression test: `kmet.app.test-tools/test-tool-bash-stdin-eof`.
When fixed: `:in (fs/file (if windows? "NUL" "/dev/null"))` — pi's stdio
`ignore` — and drop the close-after-spawn step (keep the `-s` transport's
command write).

### [jolt#949](https://github.com/jolt-lang/jolt/issues/949) — multi-arg `java.net.URI` constructors missing (only the `String` ctor)

**Area:** net / URI

**Impact:** programmatic URL assembly throws `incorrect number of arguments`;
the original code's catch-swallowed failure silently produced a wrong Azure
base URL.

**Workaround** (`src/kmet/ai/api/azure_openai_responses.clj:18–:36`):
rebuild `scheme://[userinfo@]host[:port]/openai/v1` by hand from the
single-arg parse + getters.
When fixed: use `(java.net.URI. scheme userInfo host port path query
fragment)`.

### [jolt#950](https://github.com/jolt-lang/jolt/issues/950) — `java.net.http.HttpTimeoutException` has no constructor

**Area:** net

**Impact:** code that constructs the exception (mapping/raising request
timeouts) fails; the class name itself resolves.

**Workaround:** kmet's RFC 0014 provider lib registers the ctor —
`jolt/src/jolt/kmet/providers.clj:36–:43` (plus the `IOException` hierarchy
edge) and the `:jolt/provides` claim in `jolt/deps.edn:32`.
When fixed: delete the registration + claim (and the "provided" table row in
`jolt/README.md`).

### [jolt#951](https://github.com/jolt-lang/jolt/issues/951) — `LinkedBlockingQueue` has no constructor

**Area:** java.util.concurrent

**Impact:** the SSE body reader's idle-deadline queue could not be built
unbounded; a fixed-capacity queue was used instead.

**Workaround** (`src/kmet/libs/sse.clj:456`): `(ArrayBlockingQueue. 65536)`.
When fixed: `(LinkedBlockingQueue.)` — and revisit whether backpressure on
the SSE daemon should stay.

### [jolt#954](https://github.com/jolt-lang/jolt/issues/954) — `SocketOutputStream.write(byte[])` throws a cast error

**Area:** net / sockets

**Impact:** the 2-arg whole-array write is unusable; a callback response
would close with no page.

**Workarounds:**
- `src/kmet/libs/oauth.clj:249–:267` — response writes through the 3-arg
  overload.
- `test/kmet/libs/test_http.clj:14–:22` — `sock-write` helper (3-arg).
- `test/kmet/ai/test_llm.clj:28–:35` — `sock-write` helper (3-arg).

When fixed: plain `.write out (.getBytes ...)` / `.write out b`.
