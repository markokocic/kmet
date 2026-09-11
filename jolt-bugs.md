# jolt-bugs — open upstream tickets

Every **open** jolt-side ticket whose fix requires a change in kmet. Closed and
unfiled findings are not tracked here. `jolt-port.md` / `jolt-tui.md` describe
port state without ticket IDs.

**Workarounds live next to their ticket below.** Each workaround block is the
removal checklist: when an upstream fix lands, delete the listed code (and the
block). Source comments describe the local *why* without ticket numbers —
grep this file to find what `file:line` belongs to which ticket.

Historical labels from the deleted `bb-jolt.md` map as: `JOLT-12`→#947,
`JOLT-13`→#944; git history has the full field reports.

## Open

None.

## Closed — workarounds removed

Re-verified 2026-09-11 on the locally built **`v0.8.6-98-g23296732`** (the
fixes are `2bd77e53` / PR #959, beyond the `v0.8.6-86` build the previous
re-verification used). Every kmet workaround is gone; the ledger below is what
was removed and where the code stands now.

### [jolt#944](https://github.com/jolt-lang/jolt/issues/944) — `jolt build` binary dies on `unbound fn jolt.time.impl/register-type!`

**Area:** build / AOT

The class-scan and data-reader namespaces now load under the loader's order
hook, so a provider split across the app's deps and jolt's embedded stdlib
emits callee-before-caller consistently. No kmet workaround existed; nothing to
delete. Re-verify with a `jolt build -m kmet.core` smoke run.

### [jolt#947](https://github.com/jolt-lang/jolt/issues/947) — `ProcessBuilder.redirectInput(File)` silently ignored

**Area:** process

`redirectInput(File)` is `redirectInput(Redirect.from(file))` now (as are the
`redirectOutput`/`redirectError` `File`/`Path` overloads), so the redirect
reaches fd 0.

**Removed** (`src/kmet/app/bash_executor.clj`): the spawn's `:in :pipe` +
close-after-spawn workaround is gone for the non-`-s` transport. Stdin is
`(fs/file (if process/windows-os? "NUL" "/dev/null"))` — pi's stdio
`ignore` — and only the WSL `bash -s` transport keeps a real pipe (it writes
the command, then closes it). Regression test:
`kmet.app.test-tools/test-tool-bash-stdin-eof`.

### [jolt#949](https://github.com/jolt-lang/jolt/issues/949) — multi-arg `java.net.URI` constructors missing (only the `String` ctor)

**Area:** net / URI

The JDK's other four ctors exist (composing + quoting, like the JDK's).

**Removed** (`src/kmet/ai/api/azure_openai_responses.clj`): the hand-rebuilt
`scheme://[userinfo@]host[:port]/openai/v1` string. `normalize-azure-base-url`
now derives it through the 7-arg `(java.net.URI. scheme userInfo host port path
query fragment)` ctor.

### [jolt#950](https://github.com/jolt-lang/jolt/issues/950) — `java.net.http.HttpTimeoutException` has no constructor

**Area:** net

The class (and its `HttpConnectTimeoutException` subclass) has a ctor now,
both plain `IOException` subclasses as on the JDK.

**Removed:** the RFC 0014 registration in `jolt/` — the ctor shim + the
`IOException` hierarchy edge are gone, and the `:jolt/provides` claim is empty.
The runtime *implements* the class now, so a claim would be refused at startup
anyway (`jolt.kmet.providers claims host classes … which the runtime already
provides`). The provider lib stays as empty scaffolding
(`jolt/src/jolt/kmet/providers.clj`, `jolt/deps.edn`).

### [jolt#951](https://github.com/jolt-lang/jolt/issues/951) — `LinkedBlockingQueue` has no constructor

**Area:** java.util.concurrent

**Removed** (`src/kmet/libs/sse.clj`): the fixed-capacity
`(ArrayBlockingQueue. 65536)` in the SSE body reader's idle-deadline queue is
`(LinkedBlockingQueue.)` again — unbounded, so the daemon no longer
backpressures. `stop` interrupts the daemon, which still releases a blocked
`put`/`poll`.

### [jolt#954](https://github.com/jolt-lang/jolt/issues/954) — `SocketOutputStream.write(byte[])` throws a cast error

**Area:** net / sockets

The 1-arg write dispatches on its argument, so the whole-array overload works.

**Removed:** the 3-arg `(.write out b 0 (alength b))` workarounds —
`src/kmet/libs/oauth.clj` now writes `head-bytes`/`body-bytes` whole, and the
`sock-write` helpers in `test/kmet/libs/test_http.clj` and
`test/kmet/ai/test_llm.clj` are deleted (the test servers write directly); a
`test/kmet/libs/test_oauth.clj` comment and the `out-write` helper went with
them.
