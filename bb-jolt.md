# Jolt bug reports (bb-jolt divergences)

Field reports of Clojure-semantics bugs in Jolt (first observed on
`jolt v0.8.5-36-gbac15682`, threaded Chez 10.x, 2026-09; re-verified on
`jolt v0.8.6-72-g0f7d1a11` — upstream main, locally built, 2026-09-11:
JOLT-1..JOLT-8 are fixed upstream; JOLT-9 is open (fork pin, PR
jolt-lang/http-client#19) and Android-only; JOLT-10 is new), each with a minimal
repro, the expected
Clojure/babashka behavior, and the kmet test it broke.
All were discovered by running kmet's test suite under Jolt (`jolt test`);
bb/JVM is the reference implementation (real Clojure semantics).

Repro convention: run the same expression under `bb -e` (expected) and
`jolt -e` (actual). Report format per bug: summary → repro → expected vs
actual → root area → kmet impact → workaround/status.

---

## JOLT-1 — `java.net.URI` accepts illegal characters the JDK rejects (FIXED upstream in v0.8.6)

**Area:** `java.net.URI` shim (lenient parsing).

**Repro:**

```clojure
;; bb (JDK):  throws IllegalArgumentException
;;             "Illegal character in authority at index 11: https://not a url"
;; jolt ≤ v0.8.5: returns a URI whose .getHost is "not a url"
;; jolt v0.8.6+:  throws like the JDK (fixed — verified 2026-09-10)
(let [u (java.net.URI. "https://not a url")]
  (.getHost u))
```

**Expected vs actual (jolt ≤ v0.8.5; fixed in v0.8.6):** the JDK's single-arg `URI` ctor validates the
authority and throws on illegal characters (spaces, `%`, …). Jolt's ctor
accepts them and reports the garbage as the host — so downstream validation
that relies on the ctor throwing (or on `getHost` being sane) silently
passes malformed input through.

**kmet impact:** `kmet.ai.oauth/normalize-domain` (GitHub Enterprise login)
validates user input as `https://<input>` via the URI ctor, expecting a
throw for junk → returns the host. On Jolt `"not a url"` parses, the login
flow proceeds, and the first real request dies in curl with `URL rejected:
Malformed input to a URL function`. Failing test:
`kmet.ai.test-oauth/test-copilot-login-invalid-domain`.

**Status:** FIXED upstream in v0.8.6 (verified 2026-09-10: the ctor throws
`Illegal character in authority at index 11: https://not a url`, and
`kmet.ai.test-oauth/test-copilot-login-invalid-domain` is green on jolt
with no kmet change). No kmet workaround was ever applied —
`normalize-domain` is unchanged. (Note: the multi-arg `URI` ctors are still
missing on jolt — the separate M15 gap.)

---

## JOLT-2 — `clojure.edn/read-string` silently drops a trailing `@` after a token (FIXED upstream in v0.8.6)

**Area:** edn reader (token termination / deref macro handling).

**Repro:**

```clojure
(require '[clojure.edn :as edn])

;;                        bb (JVM)                          jolt ≤ v0.8.5 (fixed in v0.8.6 — throws like bb)
(edn/read-string "garbage!@")   ;; throws "Invalid constituent character: @"  → returns garbage! (the "@" is dropped)
(edn/read-string "1@")          ;; throws "Invalid number: 1@"                → returns 1
(edn/read-string "a@b")         ;; throws "Invalid constituent character: @"  → returns a (everything from @ on is dropped)
```

Both hosts agree on trailing junk that does not start with `@` (e.g.
`"1 2"`, `"garbage! junk"`, `"{:a 1} junk"` all return the first form), and
on `@` after a *delimited* form (`"{:a 1}@"` → `{:a 1}` on both). The
divergence is specifically `@` immediately following a bare token, where
the JVM errors and Jolt truncates the input at the token and discards the
rest.

**Expected vs actual:** the JVM reader treats `@` as a macro char; after a
token ends at `@`, the deref read hits EOF/invalid input and throws. Jolt
ends the token at `@` and silently ignores the remainder of the string.

**kmet impact:** session-file corruption detection treats "a line that
`edn/read-string` rejects" as corrupt and skips it. On Jolt, corrupt lines
like `garbage!@` (the test fixture for "invalid characters") parse as a
plain symbol and load as a bogus session entry. Failing tests:
`kmet.app.test-session/test-session-load-with-multiple-corrupt-entries`,
`test-session-torn-tail-with-earlier-corruption`.

**Status:** FIXED upstream in v0.8.6 (verified 2026-09-10:
`(edn/read-string "garbage!@")` throws `Invalid constituent character: @`,
and all four `kmet.app.test-session` corruption tests are green on jolt
with no kmet change). No kmet workaround was ever applied.

---

## JOLT-3 — `java.util.regex.Matcher.find(int)` ignores the start index

**Area:** regex `Matcher` shim.

**Repro:**

```clojure
(let [m (re-matcher #"ab" "xxabab")]
  [(.find m 3) (.start m) (.end m)])

;; bb:   [true 4 6]   ; finds the "ab" AT 4, i.e. at-or-after index 3
;; jolt: [true 2 4]   ; always returns the FIRST match in the whole string
```

`.find m 7` behaves identically to `.find m 0` — the argument is ignored.
Every anchored-scan idiom `(.find m i)` + `(= (.start m) i)` (find an ANSI
code *starting at* index i of a line) therefore only ever matches index 0:
the first code in the string is returned for every i, or nothing when the
string's first code is not at i.

**kmet impact:** all ANSI-aware scanning in `kmet.tui.utils` that walks a
styled line code-by-code is wrong past the first code — SGR resets at
column ≥ 1 are never applied (`sgr-state-at`) and OSC-8 truncation keeps
stray code fragments (`truncate-to-width`) or cuts styled slices short
(e.g. the tree-selector panning view, whose closing `\u001b[22m` past index 0
was walked as literal text). Failing tests: `kmet.test-utils`
(`test-sgr-state-at`, `test-truncate-to-width-osc-8-close`).

**Note (2026-09-09):** the markdown table alignment/robustness and
`visible-width` failures previously attributed to this bug are NOT
Matcher-based — `visible-width` is a pure codepoint loop. They are the
string code-point-indexing gap (jolt-port.md §8 cause 3 / M16): jolt
strings index by code point (Chez), kmet's scans assume UTF-16 surrogate
pairs and over-advance per astral char.

**Status:** FIXED upstream — jolt PR #922 (merge `1e5036a5`,
`v0.8.6-31-g1e5036a5`+), closing #906. Verified 2026-09-10: `.find(3)`
returns `[true 4 6]`, and an out-of-range start throws the JVM's
`IndexOutOfBoundsException` (`Illegal start index`), like bb. kmet-side
workaround REMOVED with the fix: `match-at` (`kmet.tui.utils`) is back to
the plain anchored-scan idiom `(.find m i)` + `(= i (.start m))` — no
`subs`-slice per scan. `test-sgr-state-at` and
`test-truncate-to-width-osc-8-close` stay green on jolt.

---

## JOLT-4 — `java.util.regex.Matcher.region(int,int)` is missing

**Area:** regex `Matcher` shim (companion to JOLT-3).

**Repro:**

```clojure
(.region (re-matcher #"a" "ba") 1 2)
;; bb:   works (restricts the match region to [1,2))
;; jolt: IllegalArgumentException
;;       "No matching method region found taking 2 args for class :object"
```

**kmet impact:** the caching-conventions convention test scans source with
a `Matcher` and narrows with `.region` to skip escaped chars — crashes on
Jolt. Failing test: `kmet.tui.components.test-caching-conventions`
(ERROR). (That test is source-scanning tooling, so the impact is
test-infrastructure only, but the API gap is general.)

**Status:** FIXED upstream — same jolt PR #922 (merge `1e5036a5`,
`v0.8.6-31-g1e5036a5`+), closing #907: `.region`, `.regionStart`,
`.regionEnd` and the no-arg `.reset` exist, with `^`/`$` anchored at the
region's edges. Verified 2026-09-10 (repro returns `[true 1 2]`). kmet-side
workaround REMOVED with the fix: `test-caching-conventions`'
`top-level-forms` is back to `.region` narrowing to skip escaped chars.

---

## JOLT-5 — map destructuring of `& {:keys ...}` kwargs throws on an odd trailing argument; Clojure ignores it (FIXED upstream in v0.8.6)

**Area:** destructuring / arglist semantics.

**Repro:**

```clojure
(defn f [x & {:keys [a]}] [x a])

(f 1 :a)   ;; bb: [1 nil]  — the odd trailing keyword is dropped, like Clojure
           ;; jolt ≤ v0.8.5: IllegalArgumentException "Don't know how to create ISeq from: clojure.lang.Keyword"
           ;; jolt v0.8.6+: [1 nil] like bb (fixed — verified 2026-09-10)

(f 1 :a 2) ;; both: [1 2]  — even counts work identically
```

**Expected vs actual:** Clojure's map destructuring over a seq of kwargs
takes the pairs and ignores a trailing unpaired element (the same as
`(apply hash-map ...)` tolerating an odd tail — empirically `[1 nil]` on
real Clojure). Jolt tries to iterate the leftover keyword as a seq and
throws. Any call site that relies on the lenient behavior (a stray
positional arg after the options) crashes on Jolt instead of silently
ignoring the arg.

**kmet impact:** two call sites pass a stray positional argument to
kwargs-taking fns (`make-select-list` with a dangling `:a` keyword in
`test/kmet/tui/components/test_track.clj`, `slice-with-width` with a
positional `true` meant as `:strict?` in
`src/kmet/app/ui/tree_selector.clj:639`) — dead arguments that bb
tolerates and Jolt rejects. Failing tests:
`kmet.tui.components.test-track/test-fresh-but-equal-collection-write-keeps-cache`,
`kmet.app.ui.test-tree-selector/panning-keeps-selected-anchor-readable`.
(Note: the crash is the *symptom*; the underlying bug is the sloppy call
sites. A conforming Clojure would still ignore the trailing arg, so Jolt's
throw is the divergence — but the earlier claim that kmet's call sites
were already cleaned up was wrong: both sites above still existed.)

**Status:** FIXED upstream in v0.8.6 (verified 2026-09-10: `(f 1 :a)` →
`[1 nil]` like bb). kmet-side: call sites actually fixed 2026-09-09 — the
dangling `:a` args were dropped and the positional `true` became
`:strict? true` (the original intent) — and those fixes STAND (they were
real sloppy-call bugs, now correct on both hosts). Both tests are green
on jolt again (the tree-selector test additionally needed the JOLT-3
`ansi-code-at` workaround then — removed once #922 fixed `.find(int)`).

---

## JOLT-6 — spawned subprocesses inherit every open fd (no CLOEXEC); orphaned children keep listeners alive

**Area:** process spawn fd hygiene (`jolt.process` / `posix_spawn`).

**Repro (controlled probe):**

```clojure
(require '[babashka.process :as p])
(let [ss (java.net.ServerSocket. 0)]   ; listening socket
  (p/process ["sleep" "25"] {:out :string})
  (.close ss))                          ; parent closes the server…
;; …the sleep child STILL holds the socket fd:
;;   ls -l /proc/<child>/fd → 1 socket (the inherited listener)
```

**Expected vs actual:** on the JVM (bb), `ProcessBuilder` closes all
parent fds except the three stdio streams in the child, so a child never
inherits a listening socket and a dead parent releases its ports. On Jolt
children inherit every open fd, so:

1. a jolt process whose request never completes leaves its curl children
   running (they outlive the parent when it is killed, e.g. by the test
   runner's ns timeout), and each child pins the parent's listening
   sockets open — the port stays bound (`bind failed on port …`) until
   every orphan is killed;
2. hung curl children accumulate indefinitely (observed: 8 orphans aged
   16 min – 3.7 h after one interrupted test run).

**kmet impact:** jolt test runs that hit the runner's 15 s ns timeout
leave orphaned curl processes holding fixed callback ports; subsequent
runs fail with `java.io.IOException: bind failed on port 54603` etc.
until manually cleaned (`pkill -f "curl -sS -N"`). Affected:
`kmet.ai.test-oauth` fixed-port tests under repeated/consecutive runs.

**Status:** FIXED upstream — jolt PR #936 (commit `803d8743`, merge
`bebe765f`, `v0.8.6-55`+, unreleased after v0.8.6). The spawn now closes
every descriptor above the stdio pair in the child
(`posix_spawn_file_actions_addclosefrom_np` where it resolves, an
enumerated `/proc/self/fd` close-action list elsewhere). Verified
2026-09-11 on the built `v0.8.6-72-g0f7d1a11`: bind a listener, spawn a
child (`sh -c 'sleep 3'`), close the listener — the port can be rebound
immediately, while the child is still alive. An orphaned child no longer
pins a callback port, so kmet's suggested side of the fix (the runner
killing an interrupted ns's process group) was never applied and is not
needed.

---

## JOLT-7 — `clojure.core/parse-long` returns a BigInt on overflow; the JVM returns nil

**Area:** numeric parsing (`host/chez/natives-num.ss` — `jolt-parse-long`).

**Repro:**

```clojure
(parse-long "9223372036854775807")    ;; both: 9223372036854775807 (max long — in range)
(parse-long "9223372036854775808")    ;; bb:   nil
                                      ;; jolt: 9223372036854775808N
(parse-long "-9223372036854775809")   ;; bb:   nil
                                      ;; jolt: -9223372036854775809N
(class (parse-long "9223372036854775808"))  ;; bb: nil   jolt: clojure.lang.BigInt
```

**Expected vs actual:** Clojure's `parse-long` is `Long/parseLong` with
the `NumberFormatException` caught to nil: an out-of-range decimal is not
a long, the result is nil, and every non-nil result is a Long. Jolt's
`jolt-parse-long` shape-checks the string (sign + digits, fully anchored)
then calls Chez `string->number`, which promotes an out-of-range value to
a bignum instead of failing — the caller gets a `clojure.lang.BigInt`
where the JVM gets nil. The in-range boundaries (`9223372036854775807`,
`-9223372036854775808`) parse exactly on both hosts; only overflow
diverges.

**kmet impact:** `kmet.libs.yaml`'s plain-scalar fallback branches on
`parse-long`'s nil — an integer-looking scalar that overflows stays a
string on bb but loads as a BigInt on jolt. Failing test:
`kmet.libs.test-yaml/test-numbers`. Any `(if-let [n (parse-long s)] …)`
call site silently changes type category instead of taking the nil branch.

**Status:** FIXED upstream — **[jolt#927](https://github.com/jolt-lang/jolt/issues/927)**,
PR #932 (merge `684f6ea0`, `v0.8.6-54`+, verified 2026-09-10): the value is
range-checked against the long bounds, so `parse-long` answers nil past them
as the JVM does. The same PR put every `java.lang` integer parser on one
Java grammar (a `1e3`/`#xff`/`" 5"` no longer parse, an out-of-range value
fails instead of widening, `Long/decode` and siblings exist). kmet-side
workaround APPLIED 2026-09-09 (`4f900ed`) — `kmet.libs.num/parse-long`
wrapped core's and returned the value only when it is a `Long` — and
**REMOVED** 2026-09-10 after rebasing onto the fix: `kmet.libs.yaml` is back
on plain core `parse-long` (`kmet.libs.num` keeps only `finite?`). The
repro above, re-run on `v0.8.6-55-ga2a51bde`: the two overflow lines read
`nil` and `(class …)` reads `nil`.

---

## JOLT-8 — `jolt.ffi/errno` reads glibc's `__errno_location`; bionic exports `__errno`

**Area:** `stdlib/jolt/ffi.clj` — the `errno` accessor `jolt.io-poller` and
jolt-lang/http-client read after a failing `recv`/`send`/`poll`.

**Repro (Termux/Android):**

```clojure
(require '[jolt.io-poller :as p])
(p/errno)
;; bb:   (an int — 0 or whatever the last syscall left)
;; jolt: Unhandled exception (RuntimeException):
;;       foreign-procedure: no entry for "__errno_location"
```

`readelf -sW /system/lib64/libc.so | grep -w __errno` shows bionic exports
`__errno` (and `__errno@@LIBC`) only — no `__errno_location`.

**Expected vs actual:** `errno` picks its accessor by `os.name`:
`"Mac OS X"` → `__error`, `"Windows"` → `_errno`, else glibc's
`__errno_location`. Android reports `os.name` **"Linux"** while its libc is
bionic, so the Linux branch resolves a symbol that is not there and every
errno read throws. On glibc/macOS nothing changes; the bug is Android-only.

**kmet impact:** surfaced by `kmet.libs.test-http`'s
`test-follow-redirects-default` (the stale-pooled-connection retry reads
errno through `jolt.http.net/recv-bytes`) once the JOLT-9 connect fix let
the HTTP tests reach a real recv path: 1 error, and a live transport
failure on any recv/send error.

**Fix (verified 2026-09-11):** resolve the accessor on first use — try
glibc's spelling, fall back to `__errno` when the foreign-procedure has no
entry — and cache it (a delay; `errno` runs on hot paths). The same commit
adds `__errno` to `host/chez/java/process.ss`'s `proc-errno-loc` fallback
chain (on bionic it was `#f`, so `proc-errno` read 0 and the EINTR retries
around `waitpid`/read/write never fired).

**Status:** FIXED upstream — jolt PR #939 (merge `3f7fc672`: `a4261e4d`
"Bionic's errno accessor is `__errno`, not glibc's `__errno_location`",
`96c278a2` "Cache the errno accessor, not the pointer it returns",
changelog `ae4a7478`, `v0.8.6-67`+, unreleased after v0.8.6). Verified
2026-09-11 on the built `v0.8.6-72-g0f7d1a11`: `(p/errno)` answers `2` on
Termux. **The local `patchset` branch (`b36ef75f`) is redundant** — its
content is upstream with different shas — and a stock main build (or any
build ≥ `v0.8.6-67`) now serves every kmet jolt run; no kmet-side
workaround existed (kmet reads errno only through
jolt-lang/http-client's recv path). Pre-existing note: the same probe at
`v0.8.6-32-gc4ebc570` (source mode, `bin/jolt`) throws the same
`no entry for "__errno_location"`, so this was not a regression from the
#926/#927 rebase.

---

## JOLT-9 — jolt-lang/http-client's `getaddrinfo` walk reads glibc's `ai_addr` offset; bionic's `struct addrinfo` is BSD-ordered

**Area:** jolt-lang/http-client `src/jolt/http/net.clj` (`O-ai-addr`) — the
BSD-socket layer under `jolt.http.platform` / `jolt.http.tls`, i.e. every
`java.net.http` request babashka.http-client makes on Jolt.

**Repro (Termux/Android).** The layout is a libc fact, so ask the host's
headers for it:

```c
/* cc aiprobe.c && ./aiprobe */
#include <netdb.h>
#include <stdio.h>
#include <stddef.h>
int main(void) {
  printf("ai_addrlen=%zu ai_canonname=%zu ai_addr=%zu ai_next=%zu\n",
         offsetof(struct addrinfo, ai_addrlen), offsetof(struct addrinfo, ai_canonname),
         offsetof(struct addrinfo, ai_addr), offsetof(struct addrinfo, ai_next));
}
;; bionic:  ai_addrlen=16 ai_canonname=24 ai_addr=32 ai_next=40   (BSD order)
;; glibc:   ai_addrlen=16 ai_addr=24 ai_canonname=32 ai_next=40
```

The shim hardcodes `(def O-ai-addr (if macos? 32 24))`: `os.name` is
"Linux" on Android, so it reads offset 24 — `ai_canonname`, NULL because
the shim requests no canonical name — and hands `connect(2)` a NULL
sockaddr:

```clojure
(require '[jolt.http.net :as net])
(net/connect "example.com" 443)
;; jolt/bionic: connection refused: example.com:443   (connect returned -1, errno 14 EFAULT)
;; expected:    an open fd
```

Every address getaddrinfo returned fails (EFAULT), the walk reports
"connection refused", and so **every** platform-transport request fails on
Android — `kmet.libs.test-http` was 4F + 10E, `kmet.ai.test-oauth`
5F + 12E. The timed-connect path is not merely slow here: a failed connect
leaves the socket unconnected, `poll(POLLOUT)` reports it writable, and
`SO_ERROR` reads 0, so the timeout path can report a phantom success.

**Fix (verified 2026-09-11):** probe the entry instead of trusting
`os.name` — offset 24 (glibc) is used only when it holds a non-null pointer
whose first two bytes are `AF_INET` (2) or `AF_INET6` (10); the BSD order
(32) otherwise, cached after the first probe.

**Status:** open — the PR now exists: **[jolt-lang/http-client#19](https://github.com/jolt-lang/http-client/pull/19)**
"Resolve ai_addr's offset from the libc, not from os.name" (branch
`fix/bionic-addrinfo`, commit `4958c9d`, based on http-client main
`4744256`; created 2026-09-11, **not merged** as of 2026-09-11 —
upstream main is still `4744256`). **kmet's deps.edn depends on the
`markokocic/http-client` fork** until it merges (the
`io.github.jolt-lang/http-client` entry under `org.babashka/http-client`;
revert those two lines to `jolt-lang/http-client` + upstream sha once
merged). The hand patch that used to sit in the gitlibs checkout of upstream
`4744256...` was **removed** when the fork pin landed (that checkout is
pristine again — the fork is the single source of the fix). With it:
`kmet.libs.test-http` 25/90 green, the slow platform-transport test green,
`kmet.ai.test-oauth` 53/223 and `kmet.app.ui.test-session-selector` 31/130
green (all re-run on `v0.8.6-72-g0f7d1a11`, 2026-09-11). Pre-existing:
the same connect at `v0.8.6-32-gc4ebc570` (source mode, `bin/jolt`) fails
with errno 14 too, so this is not a regression from the #926/#927 rebase.

---

## JOLT-10 — `re-find` stops returning on a large alternation carrying two `.*` branches

**Area:** `host/chez/regex.ss` — the irregex search behind `re-find` /
`java.util.regex.Pattern` (a whole alternation is handed to irregex as one
pattern).

**Repro (built `v0.8.6-72-g0f7d1a11`, Termux):**

```clojure
(require '[kmet.app.loop :as loop])
(loop/retryable-error? "HTTP 500: ext_proc failed: no more response messages")
;; never returns — killed at 12 s / 30 s; repeated 8/8

;; the same pattern alone; the input is irrelevant (the no-match "zzz" hangs too)
(re-find @#'loop/retryable-error-regex "HTTP 500")
```

The pattern is kmet's `retryable-error-regex` (`kmet.app.loop`): 50
alternatives, 589 chars, `(?i)`, two of them unbounded —
`upstream.*unavailable` and `stream error: .*closed`. Bisecting the union:
a 35-alternative prefix answers `:hit 500` in milliseconds; the full 50
never return, and 39–40-alternative prefixes hung on re-runs (the exact
boundary is not stable across probes). Deleting **either** `.*`
alternative fixes it; replacing one `.*` with a literal fixes it. A
same-size union of plain literals, or a small union with the same two
`.*` branches, is fast — the blowup needs a large union *and* multiple
`.*` branches, and it is not about `(?i)` (the pattern without it hangs
too).

**Expected vs actual:** the JVM's `re-find` answers instantly (`500`).
On jolt, `v0.8.6-18-g64bdeff4` in source mode (`bin/jolt`) answered
`:hit 500` in under a second (2026-09-11 probe), while the built
`v0.8.6-54-g684f6ea0` and `v0.8.6-72-g0f7d1a11` never return — so the
pathology appeared between `-18` and `-54`, not with the
sci-reflector/errno patches (a surgical revert of #922's new
`irregex-search` end-argument did not fix it). A source-mode bisect
across the range gave non-monotonic answers (source mode reuses its
compile cache across checkouts, mixing stale `.so` files), so the next
step is a built-binary bisect between `64bdeff4` and `684f6ea0`.

**kmet impact:** `retryable-error?` is on the retry-classification path.
`ai.test-llm/test-llm-transport-error-message` calls it and the namespace
hits the runner's 15 s per-namespace limit (13 tests / 52 assertions
done); the runner's `future-cancel` then interrupted an in-flight
namespace load, which cascaded — `ai.test-model-data` also timed out and
`libs.test-http` / `ai.test-oauth` reported `Invalid leading character: @`
while loading http-client's `net.clj` (only after a cancel; those
namespaces are green when re-run individually, e.g. `test-http` 25/90 and
the slow transport test, `test-oauth` 53/223). Full non-slow run on main,
2026-09-11: 2124 tests / 14128 assertions, **10 F + 23 E**, every red
traceable to this timeout or to the cascade (two were load flakes that
passed isolated).

**Status:** open, not filed upstream yet — the repro above is
self-contained. Workaround candidates if the engine is not fixed: split
the alternation so the two `.*` branches are matched separately (or
replaced by `str/includes?`), or raise the runner's per-namespace timeout
(the cancellation itself is what turns the timeout into spurious errors).
kmet has applied no side change so far.
