# Jolt bug reports (bb-jolt divergences)

Field reports of Clojure-semantics bugs in Jolt (first observed on
`jolt v0.8.5-36-gbac15682`, threaded Chez 10.x, 2026-09; re-verified on
`jolt v0.8.6-31-g1e5036a5`, 2026-09-10 — JOLT-1..JOLT-5 are fixed there,
JOLT-6 and JOLT-7 still open), each with a minimal repro, the expected
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

**Status:** open. kmet-side: the runner could kill the process group of
an interrupted ns (the curl children are setsid'd group leaders — see
`kmet.libs.http`), but the fd inheritance itself needs a Jolt fix
(CLOEXEC on spawn). Re-run 2026-09-10 (`v0.8.6-18-g64bdeff4`): still no
manifestation — the full suite completed with no ns timeout, so no orphan
cascade was observed.

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

**Status:** open — filed upstream as **[jolt#927](https://github.com/jolt-lang/jolt/issues/927)**
(re-verified 2026-09-10 on `v0.8.6-31-g1e5036a5`; not covered by jolt's own
tests either: `test/chez/corpus.edn` has no overflow row for the parse fns
and `known-divergences.edn` has no entry, so `make certify` cannot see it).
kmet-side workaround APPLIED 2026-09-09 (`4f900ed`):
`kmet.libs.num/parse-long` wraps core's and returns the value only when
it is a `Long`, nil otherwise — both hosts then get JVM semantics. The
wrapper stays until a jolt fix lands.
