# jolt-tui: porting kmet's TUI to Jolt

How to build a cross-platform TUI for Jolt by porting `kmet.tui`
(a Clojure/Babashka port of `@earendil-works/pi-tui`), following the same
architecture pi-tui proves out: **portable ANSI rendering + platform-aware
raw input**, with the platform half swapped from JLine to C FFI.

> Status of the previous draft of this file: direction right, code samples
> wrong. `tty-mode-set!` is Gambit, not Chez/Jolt. `get-proc`,
> `make-uint32-ref`, `os-type`, `bitwise-ior`/`bitwise-and`/`bitwise-not`,
> `(import (jolt.ffi))` do not exist in Jolt. The real API is
> `jolt.ffi/defcfn` + `with-out`/`read`/`write` (see §4). The 5-case
> `read-key` loop also ignores ~2000 lines of input machinery in
> `src/kmet/tui/core.clj` that exists for observed corruption bugs (§7).
> This rewrite fixes both.

Source of truth: `src/kmet/tui/tui.md` (package docs),
`src/kmet/tui/terminal.clj` (the only file that must be rewritten),
`src/kmet/libs/terminal.clj` + `src/kmet/tui/keys.clj` (port verbatim),
`src/kmet/tui/core.clj` (input/render loop to reimplement against).
Jolt API refs: `jolt-lang.github.io/docs/native-interop.html`,
`docs/host-interop.html`.
Companion: `jolt-port.md` (whole-repo port report; this file is its TUI
deep-dive).

---

## 1. Architecture overview

pi-tui / kmet separate three concerns. Only the third changes per platform:

| concern | pi-tui (Node) | kmet (bb/JVM) | jolt-tui (Jolt/Chez) |
|---|---|---|---|
| rendering | ANSI escapes to stdout | same — pure Clojure | same — port as-is |
| key parsing | `parseKey`, Kitty + legacy tables | `tui/keys.clj`, 0 Java interop | port verbatim |
| protocol knowledge | `terminal.ts` constants + negotiation | `libs/terminal.clj`, pure | port verbatim |
| raw mode + I/O | `stdin.setRawMode` + libuv | **JLine** (`tui/terminal.clj`) | **termios FFI** (Unix) + **kernel32 FFI** (Windows) |
| Windows VT input | `win32-console-mode.node` (`GetConsoleMode`/`SetConsoleMode` + `ENABLE_VIRTUAL_TERMINAL_INPUT`) | free via JLine | `defcfn` to `kernel32.dll` — same three calls |

kmet already enforces this split: raw `\u001b` is banned outside `src/kmet/tui/`
and `src/kmet/libs/terminal.clj`, and the `ITerminal` protocol
(`terminal.clj:13`) is the sole seam between the portable core and the OS.
A port rewrites one ~240-line adapter and keeps ~12k lines of
`reakt` + `hiccup` + components + `utils` + `keys` + `libs.terminal`.

---

## 2. Why kmet uses JLine, and what "replace with raw mode" means

In kmet, JLine **is** raw mode plus portable I/O around it.
`terminal.clj:60` is literally `(.enterRawMode t)`. Nothing else from JLine
is used — no `LineReader`, no completion; the editor is custom
(`tui/components/editor.clj`). The full used surface:

- **raw on/off + handle acquire**: `.enterRawMode`, `.reader`/`.writer`,
  `.close` (`terminal.clj:55-75`).
- **timed reads**: `NonBlockingReader.read(timeout)` — a bounded `100ms`
  read plus a `1ms` drain batch (`core.clj:1367,1384`), `.ready`/`.read` in
  `drain-input!` (`terminal.clj:180`). A blocking read deadlocks close on
  aarch64 Linux (comment at `core.clj:1363`, jline3 #1909).
- **size**: live `.getWidth`/`.getHeight` polled every 16ms (`core.clj:1570`)
  because WINCH signal handlers don't register under bb's GraalVM image
  (`core.clj:1579-85`) — resize arrives via poll, not via callback.
- **portability**: `TerminalBuilder/terminal` opens `/dev/tty`, detects the
  terminal type, and handles the Windows console. Babashka bundles JLine
  4.3.1, so this costs zero extra deps.

Half the work is already manual: `run-stty`/`capture-stty-snapshot`
(`terminal.clj:29-50`) saves `stty -g` before JLine construction because
JLine's FFM termios mapping writes baud `0` on construction and its own
restore leaves speed `0`.

Replacing JLine therefore means reimplementing, per platform:

1. raw on/off with save/restore on every exit path (normal, exception,
   shutdown hook — JLine's `.close` currently owns this);
2. timed/batched reads (else the close deadlock returns);
3. live size queries (subprocess `stty size` every 16ms is too heavy; want
   `ioctl(TIOCGWINSZ)` or a slower poll);
4. the Windows console (`GetConsoleMode`/`SetConsoleMode` + VT-input flag) —
   the hard part JLine currently gives you for free.

On bb/JVM there is no reason to do this (re-solve solved bugs to save a
bundled dep). On Jolt there is no JVM, so it is mandatory — §§4–5 below.

---

## 3. Rendering: portable, port as-is

All drawing is ANSI/VT sequences; Windows Terminal, iTerm2, GNOME Terminal
all accept them. kmet's render loop diffs lines and emits only changes
(`core.clj` frame loop); implement the same virtual-buffer diff or port the
function. Nothing here touches the OS:

```clojure
(print "\u001b[2J\u001b[H") (flush)   ; clear + home
(printf "\u001b[%d;%dH" r c) (flush)  ; move cursor
(print "\u001b[?25l") (flush)         ; hide cursor
(print "\u001b[?25h") (flush)         ; show cursor
```

Styling goes through `tui/theme.clj` (attribute-specific resets, never bare
`\u001b[0m`, so nested styles compose). Keep that rule.

---

## 4. FFI ground rules (read before writing any binding)

Jolt has no JVM and no `java.*`. The outside world is reached through
`jolt.ffi`: declare the library, bind each C function with arg/return type
keywords, marshal memory by hand. No GC of foreign memory; every `alloc`
needs a `free` on every path (prefer the `with-*` scoped macros).

```clojure
(ns my.tui.term
  (:require [jolt.ffi :as ffi]
            [clojure.string :as str]))

;; Outside a deps.edn project, load before first call:
;; (ffi/load-library)          ; process symbols (libc/POSIX)
;; (ffi/load-library "libsqlite3.dylib") ; one file
;; Inside a project, prefer deps.edn (next block).
```

`deps.edn` — shape per Native Interop guide + `deps.clj`/`main.clj`:
per-OS candidate vectors tried in order (`:darwin`/`:linux`/`:windows`,
`:mac` aliases `:darwin`); `:optional` skips when missing (probe with
`ffi/loaded?`); `:process` uses process symbols (libc/POSIX — no file);
`:static` bakes the archive into `jolt build`.
libc/POSIX needs no declaration at all — call `(ffi/load-library)` (or
`nil`) for the boot's global handle (`ffi.ss`: re-loading the global
handle would re-promote it above `:jolt/native` handles — the boot
loads it once, and the boringssl lesson in the same file is why):

```clojure
{:jolt/native [{:name "kernel32" :windows ["kernel32.dll"]}]}
;; without deps.edn: (ffi/load-library {:darwin "…" :linux "…" :windows "…"})
;; (per-OS map; vectors = ordered candidates per `ffi-candidate-list`), or
;; (ffi/load-library) / (ffi/load-library nil) for process symbols only.
```

Keep the candidates for `run`/`repl`; for `jolt build`, add `:static
{:archive "/path/to/libfoo.a"}` to bake the archive into the binary
(build needs only `cc` on `PATH`; running needs nothing). Details:
Native Interop guide, "Static vs dynamic linking".

Binding shape — both are MACROS (Chez needs types at compile time):
`defcfn` defs the binding (docstring/attr-map supported, plus a wrapper
form for out-params — `stdlib/jolt/ffi.clj:1350`), `foreign-fn`/`cfn` (same
thing) expand inline. Signatures must be literal; trailing option is
`:blocking` or a literal `{:blocking … :capture-native-error …}` map
(`ffi.clj:1306`):

```clojure
(ffi/defcfn c-strlen "strlen" [:string] :size_t)
;; :blocking emits __collect_safe so a parked thread does NOT pin the GC for
;; every thread. Mark anything that can wait (read/recv/sleep/lock).
(ffi/defcfn c-read "read" [:int :pointer :size_t] :ssize_t :blocking)
;; capture variant: [result errno] atomically (non-void scalar results only):
(ffi/defcfn c-read-cap "read" [:int :pointer :size_t] :ssize_t {:blocking true :capture-native-error true})
```

Type keywords (`ffi.clj:19-29`; arena-based — zeroed alloc; caller-owned
`(alloc n)` + `free`, arena-owned `(alloc arena n)` / `confined-arena`
(one thread) / `shared-arena` (any thread) closed by `close-arena` or
`with-arena`): `:int :uint :long :ulong :int64 :uint64 :size_t
:ssize_t :iptr :uptr :double :float :pointer :string :bool :void :uint8
(`:u8`/`:byte`) `:char`, plus exact widths `:int8`/`:i8 :int16`/`:short
:uint16`/`:ushort :int32 :uint32`. `:bool` is one-byte `_Bool` (jolt
truthiness on the way out). `:string` carries nil↔NULL in both directions
(`""` still allocates); false in a `:string` position is rejected. **A jolt
pointer is a raw address integer** — no bounds checks; `size` answers only
what jolt was told. Memory is ZEROED on alloc. Out-params use a scoped
cell:

```clojure
;; sqlite3_open(path, &db) pattern — the shape of every GetConsoleMode call:
(defn open-db [path]
  (ffi/with-out [pp :pointer]
    (let [rc (sqlite3-open path pp)]
      (when-not (zero? rc)
        (throw (ex-info (str "open failed: " path) {:rc rc})))
      (ffi/read pp :pointer))))
```

Memory (`ffi.clj:678-1260`): caller-owned `(alloc n)` + `free`, or
arena-owned `(alloc arena n)` / `confined-arena` (one thread) /
`shared-arena` (any thread) closed by `with-open`-style `close-arena`.
Scopes returning the body value: `with-alloc`, `with-out`, `with-layout`,
`with-c-string`, `with-c-string-array`. `sizeof` / `alignof` take a keyword
or compiled layout. `read`/`write`: **value BEFORE offset** (`(write p t v)`
/ `(write p t v off)` — babashka.ffi order). No `ffi/copy` confusion:
`read-array`/`write-array` move scalar arrays element-wise (one-byte
widths as one block copy); `read-bytes`/`write-bytes` encode/decode UTF-8;
`read-into!` fills an existing buffer (streaming reads); `byte-buffer`
shares memory zero-copy. `layout` takes a LITERAL descriptor
(macros need it at compile time — `(layout d)` on a runtime value has
nothing to compile); unions `[:union …]` read as a pointer to the bytes.
`string->ptr`/`ptr->string` round-trip nil. `null`/`null?` (host-provided),
`loaded?`, `defining-libraries` (duplicate-symbol probe — RTLD_LOCAL keeps
natives apart; `ffi.ss` scoped-loader section). errno: `(ffi/errno)` immediately after the
failing call (allocation/park/FFI in between may overwrite),
`(ffi/errno-message e?)`.

OS detection uses the shimmed `System/getProperty` / `System/getenv`
(`host-static-methods.ss`: `os.name` answers `"Mac OS X"` / `"Windows"` /
`"Linux"` from Chez's machine-type; `getenv` with no args returns the whole
map, one arg the value or nil; `java.io.tmpdir` answers `$TMPDIR` else `/tmp`
— still pass an explicit dir, kmet's `libs.http/temp-dir` pattern):

```clojure
(defn windows? []
  (str/includes? (str/lower-case (str (System/getProperty "os.name"))) "win"))
```

Notes and traps:

- A `defcfn` resolves against declared natives first, process-global
  fallback second — a system lib can't shadow your binding.
- Variadic marker is `:&` (`:varargs` is the older spelling, same thing —
  `ffi.clj:239`): `(ffi/defcfn c-fcntl "fcntl" [:int :int :& :int] :int)`,
  bare `(ffi/defcfn c-open "open" [:string :int :&] :int)` infers the tail
  per call (first call of a new shape compiles, ~0.8ms, then cached).
  Load-bearing on Apple arm64 (variadics travel on the stack — fixed-arity
  bindings hand garbage). Neither form combines with `:blocking`
  (`ffi.clj:272`). C promotions apply past the marker: pass sub-int values
  as `:int`, float as `:double` (incl. `:bool`).
- Callbacks into Jolt (a `qsort` comparator, a signal handler) use
  `ffi/foreign-callable` (macro) + `ffi/free-callable`, or arena-owned
  `ffi/callback` (no explicit release). `:collect-safe` goes on the
  CALLABLE when C invokes it from a thread jolt never started OR from a
  jolt thread parked in a `:blocking` call (e.g. GUI main loop); omit it
  for same-thread callbacks (qsort comparator) — it costs an activation
  per call. Without it on a foreign/parked thread the process dies with a
  nonrecoverable memory fault. `export!` publishes entry points for `jolt
  build --library` (resolved via `jolt_lookup` after `jolt_library_init`;
  single thread, `jolt_library_shutdown` to tear down).
- Windows caveat: `process.ss` notes the FFI surface is missing on some
  Windows machine types (Chez `open-process-ports` fallback there), and
  cross `--target` builds retarget only step 4 under the target pack's Chez
  (`build.ss`). Test a `kernel32` `defcfn` on real Windows early; declare
  the dll under `:windows` (or `load-library` the `{:windows …}` map). Fallback is a tiny C helper in `native/` baked
  via `:static {:archive …}` — the same role as pi-tui's vendored
  `win32-console-mode.node` (3 KB, one function). Natives load RTLD_LOCAL
  and resolve per-handle (never shadowed by system libs — the boringssl
  lesson, `ffi.ss:33-46`); `defining-libraries` names duplicates.

---

## 5. Raw mode: Unix via termios FFI

Chez (hence Jolt) has no `tty-mode-set!` — that procedure is Gambit (Marc
Feeley's `tty-mode-set!` mails describe the Gambit API). On Chez you drive
`termios` yourself, exactly like the Chez raw-input gist does with
`foreign-procedure`: `tcgetattr` → `cfmakeraw` → `tcsetattr`, restore on
exit. In Jolt the `foreign-procedure` step is `defcfn` against process
symbols:

```clojure
(ns my.tui.unix
  (:require [jolt.ffi :as ffi]))

(ffi/defcfn c-tcgetattr "tcgetattr" [:int :pointer] :int)
(ffi/defcfn c-tcsetattr "tcsetattr" [:int :int :pointer] :int)
(ffi/defcfn c-cfmakeraw "cfmakeraw" [:pointer] :void)

(def STDIN-FD 0)
(def TCSANOW 0)
(def TCSAFLUSH 2)
;; Opaque termios buffer: big enough for Linux (60B) and macOS (~72B).
(def TERMIOS-SIZE 256)

(defonce saved-termios (atom nil))

(defn enable-raw! []
  ;; Save cooked state once; build raw from a byte copy so restore is exact.
  ;; NOTE: write takes VALUE before offset: (ffi/write p t v) / (write p t v off).
  (let [saved (ffi/alloc TERMIOS-SIZE)
        raw   (ffi/alloc TERMIOS-SIZE)]
    (if (zero? (c-tcgetattr STDIN-FD saved))
      (do (ffi/copy saved raw TERMIOS-SIZE)
          (c-cfmakeraw raw)
          (if (zero? (c-tcsetattr STDIN-FD TCSAFLUSH raw))
            (do (ffi/free raw)
                (reset! saved-termios saved)
                true)
            (do (ffi/free saved) (ffi/free raw)
                (throw (ex-info "tcsetattr (raw) failed"
                                {:errno (ffi/errno)})))))
      (do (ffi/free saved) (ffi/free raw)
          (throw (ex-info "tcgetattr failed" {:errno (ffi/errno)}))))))

(defn restore-cooked! []
  (when-let [saved @saved-termios]
    (c-tcsetattr STDIN-FD TCSAFLUSH saved)
    (ffi/free saved)
    (reset! saved-termios nil)))

(defn with-raw-mode [f]
  ;; dynamic-wind equivalent: raw in, cooked out on every path.
  (enable-raw!)
  (try (f)
       (finally (restore-cooked!))))
```

`ffi/errno` reads the calling thread's slot (`__errno_location` /
`__error` / `_errno` per OS — `ffi.clj:1494`), correct under threads and
fibers; read it IMMEDIATELY (an alloc/park/FFI call in between may
overwrite). `ffi/errno-message` renders via `strerror`. `try`/`finally`
and `ex-info` are portable. (Also: `tcsetattr`'s fd param is the same
`:int`; `cfmakeraw`'s arg is `:pointer` to the struct bytes — opaque buffer
is fine, no `layout` needed.)

Reads: raw mode makes bytes available immediately, but `read-char`-style
port reads still buffer. Read fd 0 directly on a dedicated reader thread
(Jolt's Chez backend runs `future` bodies on real shared-heap OS threads,
so a captured atom is shared). Mark the call `:blocking`:

```clojure
(ffi/defcfn c-read "read" [:int :pointer :size_t] :ssize_t :blocking)

(defn start-reader! [on-bytes]
  ;; on-bytes: (fn [byte-array n]) — hand batches to the input buffer (§7).
  ;; read-into! fills an EXISTING buffer (no per-chunk array); byte-array elts
  ;; are signed — mask with (bit-and b 0xFF) when reassembling UTF-8.
  (future
    (ffi/with-alloc [buf 65536]
      (let [frame (byte-array 65536)]
        (loop []
          (let [n (c-read STDIN-FD buf 65536)]
            (when (pos? n)
              (ffi/read-into! buf frame 0 n)
              (on-bytes frame n)
              (recur))))))))
```

Match kmet's batching: feed one burst per pass (kmet drains everything
already queued behind the first char — `core.clj` reader loop) so a
multi-byte sequence never straddles a scheduling stall byte-by-byte (that
stall is what flushed phantom Escapes and leaked `[200~` as text). A close
path must unblock the parked `read` (kmet uses timed reads for exactly this
— `core.clj:1363` deadlock note); with a blocking `read`, close the fd or
send a signal/wakeup byte from `stop!` instead (`future-cancel` cannot
substitute here: a `:blocking` FFI call only notices the interrupt when it
returns to Scheme — §9).

Size: cache and poll; `stty size` as a subprocess per 16ms frame is too
heavy. Preferred is `ioctl(TIOCGWINSZ)` via FFI (bare-`:&` form, since the
third arg is an out-pointer — `ffi.clj:239-260`), falling back to `stty
size` at a slow cadence:

```clojure
;; winsize = {ws_row, ws_col, ...} unsigned shorts; read back fields by offset.
;; os.name answers "Linux" / "Mac OS X" / "Windows" — match strings, not keywords.
(def TIOCGWINSZ
  (let [os (str (System/getProperty "os.name"))]
    (cond (str/includes? os "Mac") 0x40087468
          :else 0x5413)))
```

---

## 6. Raw mode + VT input: Windows via kernel32 FFI

Windows has no `termios`. Call the console API directly — these are the same
three calls as pi-tui's `win32-console-mode.c`
(`GetStdHandle`/`GetConsoleMode`/`SetConsoleMode`):

```clojure
(ns my.tui.win
  (:require [jolt.ffi :as ffi]))

(ffi/defcfn Win-GetStdHandle   "GetStdHandle"   [:int] :pointer)
(ffi/defcfn Win-GetConsoleMode "GetConsoleMode" [:pointer :pointer] :int)
(ffi/defcfn Win-SetConsoleMode "SetConsoleMode" [:pointer :uint] :int)

(def STD-INPUT-HANDLE -10)
(def ENABLE-VIRTUAL-TERMINAL-INPUT 0x0200)
(def ENABLE-LINE-INPUT 0x0002)
(def ENABLE-ECHO-INPUT 0x0004)

(defonce saved-mode (atom nil))

(defn get-mode [handle]
  ;; with-out allocates ONE scalar and returns the BODY's value (`ffi.clj:1223).
  (ffi/with-out [m :uint]
    (when (pos? (Win-GetConsoleMode handle m))
      (ffi/read m :uint))))

(defn enable-vt-input! []
  ;; Makes the console emit VT sequences (e.g. \u001b[Z for Shift+Tab).
  ;; pi runs this AFTER setRawMode(true): raw mode resets console flags.
  (let [h (Win-GetStdHandle STD-INPUT-HANDLE)]
    (when-let [mode (get-mode h)]
      (Win-SetConsoleMode h (bit-or mode ENABLE-VIRTUAL-TERMINAL-INPUT)))))

(defn enable-raw! []
  (let [h (Win-GetStdHandle STD-INPUT-HANDLE)]
    (when-let [mode (get-mode h)]
      (reset! saved-mode mode)
      ;; clear LINE + ECHO; OR in VT-INPUT in the same step (ordering above)
      (Win-SetConsoleMode h (bit-or (bit-and mode
                                             (bit-not (bit-or ENABLE-LINE-INPUT
                                                              ENABLE-ECHO-INPUT)))
                                    ENABLE-VIRTUAL-TERMINAL-INPUT)))))

(defn restore-cooked! []
  (when-let [mode @saved-mode]
    (Win-SetConsoleMode (Win-GetStdHandle STD-INPUT-HANDLE) mode)
    (reset! saved-mode nil)))

(defn set-raw-mode! [on?]
  (if on? (enable-raw!) (restore-cooked!)))
```

Combined entry (replaces the old draft's `os-type`/`tty-mode-set!` version):

```clojure
(defn set-raw-mode! [on?]
  (if (windows?) (win/set-raw-mode! on?)
      (if on? (unix/enable-raw!) (unix/restore-cooked!))))
```

Restore must run on every exit path (normal stop, exception, shutdown
hook) — a crashed TUI that leaves the console raw/echo-off is the classic
failure. pi-tui's helper is deliberately non-fatal when missing; keep that:
warn and continue with degraded keys, never crash startup.

After raw + VT-input are on, the byte stream is uniform across platforms
and one shared parser handles it (§8).

---

## 7. The input subsystem the minimal loop misses

`read-char` + a 5-case `case` (up/down/left/right/shift-tab) will appear to
work and then corrupt keys in production. `core.clj`'s machinery exists for
observed bugs; port the behavior, not necessarily line-for-line:

1. **Kitty negotiation + fallback** (`libs/terminal.clj`, `core.clj`
   interceptors). Send `\u001b[>7u\u001b[?u\u001b[c`; consume the reply
   (`\u001b[?Nu` / `\u001b[>Nu` / DA `\u001b[?..c`) in the input path so it
   never dispatches as keys. Non-zero flags → Kitty; zero/DA → xterm
   `modifyOtherKeys` (`\u001b[>4;2m`). Flush held fragments after
   `NEGOTIATION-FLUSH-TIMEOUT-MS` (150ms) so a stalled reply is never
   swallowed forever. Prefix-hold rule matters: a bare `\u001b[` is NOT
   held (it's also an arrow-key prefix; holding it across a 50ms+ conpty
   split corrupts ctrl+arrows into Escape + text) — only unambiguous heads
   (`\u001b[?`+digits, `\u001b[>`) are.
2. **Terminal-response interception**. Cell-size (`\u001b[6;h;wt`, ungated),
   OSC-11 background (gated on an outstanding query), color-scheme report
   (`\u001b[?997;Nn`) are consumed before key parsing, with the same
   prefix-hold discipline (cell-size needs the `;` — bare `\u001b[6` also
   prefixes keys).
3. **Structural completeness gate** (`keys/complete-sequence?`). Dispatch an
   ESC sequence only when structurally finished (CSI/OSC/DCS/APC/SS3/mouse
   rules) AND recognized. A partial CSI prefix that happens to parse as
   `alt+[` must never dispatch early and swallow the rest.
4. **Leading-sequence scan + garbage drop**. The buffer can hold several
   sequences; dispatch the leading complete one, re-process the remainder.
   A complete-but-unrecognized leading sequence is dropped (Termux DA
   `\u001b[?64;..c`, unparsed Kitty push forms) — otherwise the buffer grows
   and swallows all later input ("frozen, keys dead, no crash log").
5. **Generation-guarded flush timers**. Lone ESC waits `ESCAPE-FLUSH-MS`
   (100ms — pi's 10ms orphans tails on WSL/conpty 50ms+ splits, leaking
   `[27;5;97~` as text); partial CSI waits `SEQUENCE-FLUSH-MS` (50ms).
   Fire only after true idleness (input-generation counter); re-check under
   the dispatch lock — a stale timer must neither dispatch a split
   sequence's head as Escape nor clear a fresh arm. Timers stay
   `future`+generation-counter first (`future-cancel` also interrupts a
   sleeping timer thread, §9 — use it as backup, never as the only guard).
6. **Pastes**. Bracketed-paste markers (`\u001b[200~`/`\u001b[201~`, mode
   2004) dispatch immediately with surrounding text kept in order.
   Unbracketed bursts (IME injection, tmux `send-keys`) arrive as raw
   bytes: a CR ending a paste-like burst (≥4 chars incl. the CR within
   100ms, at least one non-CR) is rewritten to `\n` so pasted `/cmd` text
   can't submit; the LF half of a rewritten CRLF is swallowed within 50ms.
   Bulk-insert runs (everything up to the first control/ESC) to keep big
   pastes O(n).
7. **Mouse/focus filtering**. Complete SGR/X10 mouse and `\u001b[I`/`O`
   focus sequences are recognized so partial fragments never leak as editor
   text (main-screen model: not enabled, still filtered).
8. **Listener chain + modality**. Listeners run first, each may `:consume`
   or return transformed `:data` (empty string drops the event). While a
   visible capturing overlay exists, input snaps to it before delivery;
   hidden/removed overlays never keep keys. Focus restore resolves from
   live state (topmost visible overlay → app-registered focus-home thunk →
   null drops at the guard) — no "previously focused" snapshot, plus a
   watch on the overlay stack as the unbypassable restore chokepoint
   (`::ghost-guard`). Key-release events are filtered unless the component
   opts in (`:wants-key-release?`).
9. **Drain on exit**. Disable Kitty + `modifyOtherKeys`, then drain pending
   input (≤1000ms, stop after 50ms idle) so late release sequences don't
   leak into the parent shell.

---

## 8. Key parsing: port verbatim

`src/kmet/tui/keys.clj` (499 lines, **0 Java interop**) ports unchanged in
logic; only check the regex engine (Jolt uses irregex — common patterns
work, some Java-specific features differ). Order matters (mirrors pi's
`parseKey`): Kitty CSI-u/arrows/functional → `modifyOtherKeys`
(`CSI 27;mods;code ~`) → Kitty-active mode remaps (`\u001b\r`, `\n` →
`shift+enter`) → legacy table (arrows, SS3 application-cursor mode,
shift/ctrl/alt-cursor, Emacs `alt+b/f/p/n`, `alt+enter/space/backspace`,
`shift+tab` = `\u001b[Z`, F1–F12 in all pi legacy forms) → `ESC`+ctrl →
`ctrl+alt+letter` / `alt+key` → ctrl singles → DEL/BS → `space` →
printable. `matches-key?` treats modifier order as insignificant.
`is-key-release?` / `is-key-repeat?` decode Kitty event types 3/2 and must
exclude bracketed paste content (MAC addresses contain `:3F`).

---

## 9. Concurrency + host-shim mapping

Verified against the checkout at `~/jolt` (`023285d2`, 2026-09-05;
`host/chez/java/concurrency.ss`, `host-static-methods.ss`, `locks.ss`,
`jolt-core/clojure/core/30-macros.clj:117`). Carriers differ, so pick by
blocking shape: `future` = real OS thread, shared heap (blocking FFI,
`read(2)`, sleeps go here); fiber (`go`/`io-thread`/`jolt.fibers/spawn`) =
multiplexed carrier (channel ops and `deref` park, but a blocking FFI call
or `Thread/sleep` pins the carrier — `fibers.clj` header pins exactly
this). Mapping for `core.clj`'s idioms:

| kmet (bb/JVM) | Jolt |
|---|---|
| `future` body on thread pool | `future` on a real OS thread, shared heap — reader + timers translate directly |
| `(Thread/sleep ms)` | interruptible sleep on OS threads (same door as `TimeUnit.sleep`); on a fiber it pins the carrier — `fibers.clj` header (“park-capable waits — channel ops, deref, `jolt.socket`/`jolt.process` IO — are the ones to use inside a body”). Sleeps belong in `future`s, never in `go`/fiber bodies |
| `future-cancel` | real `cancel(true)` (`concurrency.ss:182-208`): marks cancelled+done (derefs throw `CancellationException`) **and interrupts the worker** — a thread parked in an interruptible wait (`Thread/sleep`, future/promise deref, `CountDownLatch`, blocking-queue ops) is thrown out promptly; running compute sees it via `Thread/interrupted` / `.isInterrupted`. Caveat: a thread parked in a `:blocking` FFI call only notices when the call returns to Scheme (same file, interrupt section) — like the JVM not killing native code. So cancel *does* stop sleeping flush timers; it does *not* unblock a parked `read(2)` — `stop!` still needs a wakeup byte / fd close (§5) |
| `locking` | **present** (`clojure.core/locking` → `jolt.host/with-monitor`, per-object reentrant monitor). NOT fiber-aware: `locks.ss` exists precisely because an OS mutex across a fiber switch loses exclusion either way (unwind releases mid-section; no-unwind lets a carrier-mate walk in). `with-monitor` is that same monitor with a dynamic-wind release plus park-rewind handling — usable for `dispatch-lock`, but keep the body short, non-sleeping, and park-free; generation counters (§7.5) stay the primary stale-timer defense, cancel the backup |
| `System/getenv` | shimmed — keep (`KMET_*` flags translate to env reads) |
| `ProcessBuilder` + `stty` snapshot/restore | replace with §5 FFI (no subprocess on the hot path) |
| `StringBuilder` + `.append/.charAt/.length` | shimmed (`append/toString/length/charAt/setLength`) — keep, verify arities |
| `java.util.Base64` (OSC-52) | shimmed — keep |
| `Pattern/compile/quote/split`, `MULTILINE` | shimmed — keep, but re-run key/response regex tests under irregex |
| `LocalDateTime/now` log timestamps, `ProcessHandle/.pid` log names | shape differs: time values live behind the time lib (`Instant`/`LocalDateTime` autoloaded core types per host-interop); pid via the `ProcessHandle` shim (`process.ss:1009` `.pid`) — verify call shapes on the checkout before porting log paths |
| `(io/writer path :append true)` + `with-open` + `.write` | reshaped, not absent: `jolt-io-writer` takes ONE arg (no opts) and `spit` takes `:append` (both verified in `io.ss`; non-append `spit` is temp-file+rename, append writes in place). Crash/write logs become `(spit path text :append true)`; `with-open` exists (`30-macros.clj:186`, closes via `__close`); `FileWriter`/`file-writer` persists on flush/close |
| `babashka.fs` (`directory?`, `cwd`, `file-separator`, …) | present via install roots + `jolt.bb.fs` supplement (`loader.ss`: supplement loads after `babashka.fs`; install copy always wins over a project copy); vendor/ pins verified — `fs` v0.5.34 + `process` v0.6.25 = exactly kmet's `deps.edn` |
| `String.getBytes` (OSC-52 `libs/terminal.clj:257`) | present and charset-aware (`natives-str.ss:381`) — keep, or use `ffi/write-bytes` |
| `clojure.java.io` (`reader`/`writer`/`file`/`input-stream`/…) | present as vars (`io.ss`/`io-streams.ss` `def-var! "clojure.java.io" …` — note: `writer`/`input-stream`/`output-stream` are defined in BOTH files, streams file wins at load; `resource` takes an optional ignored loader arg) — keep call sites, verify arities |

STM (`ref`/`dosync`/`alter`) is present; agents are real async (per-agent
serialized dispatch on worker threads, `await`/`await-for`,
`shutdown-agents` gate; `send-via` behaves as `send`). Neither is the timer
mechanism — timers stay `future` + generation counters.

---

## 10. What ports unchanged, what gets rewritten

| namespace | verdict |
|---|---|
| `libs.terminal` (Kitty/OSC constants, negotiation + response parsing) | port logic verbatim; `Base64` shimmed (keep or use `ffi/write-bytes`); log-file names need no `LocalDateTime` (format manually or drop the timestamp) |
| `tui.keys`, `tui.keybindings`, `tui.utils` (width/wrap/truncate), `libs.reakt`, `tui.hiccup`, `tui.macros`, `tui.protocols`, all `tui.components.*` | portable — port, checking `add-watch`-on-atom and regex spots |
| `tui.theme` | portable minus file-watching (`fs` watcher → poll, `java.nio` stays out per AGENTS.md rule analogue) |
| `tui.terminal` (240 lines) | **rewrite** per §§4–6 behind the same `ITerminal` protocol |
| `tui.core` input half + start/stop/resize/drain | **reimplement** per §§5–7,9 (reader thread, poll-based resize, generation-guarded timers, restores) |
| `tui.core` render half (diff, overlays, flashes, Kitty-image ranges, crash/debug logs) | port logic; retarget logging to portable I/O |

Suggested order: `keys` → `utils` → `reakt` → `hiccup` → components →
theme, all headless-testable through `hiccup/render-lines` (no tty, no
sleeps — pin the idle-UI invariant: no state change ⇒ zero fn bodies /
reaction re-runs). Then the `ITerminal` adapter (§§5–6). Then the input
transport (§7). Validate interactively with the tmux/pty capture scripts
(`scripts/tmux_capture.sh`, `pty_capture.py`, `term_dump.py` equivalents)
before widgets.

---

## 11. Packaging

- Unix/macOS: no extra runtime deps — libc/`termios` are process symbols.
- Windows: no extra DLLs — `kernel32.dll` is always present; bind directly.
- Optional tiny C helper (pi-tui's `win32-console-mode.node` pattern): only
  if the Windows FFI surface proves missing (§4 caveat); ship via
  `:jolt/native` `:static {:archive …}` so `jolt build` cc-links it in while
  `run`/`repl` still load dynamically (a `:static`-only spec is skipped at
  `run` with a warning).
- `jolt build` gives the single self-contained executable (runtime + app +
  static natives); linking needs Chez's kernel dev files (`libkernel.a`,
  `scheme.h`) + `cc` — both ship with the prebuilt jolt binary, NOT with
  distro `chezscheme` packages (per README).

---

## 12. Minimal end-to-end sketch (corrected API)

```clojure
(ns my.tui.main
  (:require [jolt.ffi :as ffi]
            [clojure.string :as str]
            [my.tui.term :as term]     ; set-raw-mode! from §§5–6
            [my.tui.keys :as keys]))   ; ported §8 parser

(defn clear-screen [] (print "\u001b[2J\u001b[H") (flush))
(defn hide-cursor [] (print "\u001b[?25l") (flush))
(defn show-cursor [] (print "\u001b[?25h") (flush))

(defn run-tui []
  (clear-screen)
  (hide-cursor)
  (print "Press 'q' to quit") (flush)
  (term/set-raw-mode! true)
  (try
    (loop []
      ;; real port: this read is the §5 reader future feeding the §7 buffer;
      ;; shown inline here only to keep the sketch small.
      (let [k (keys/read-key-blocking!)]
        (cond (= k "q") :quit
              (= k "escape") (recur)
              :else (do (println (str "\r\nkey: " k)) (recur)))))
    (finally
      (term/set-raw-mode! false)
      (clear-screen)
      (show-cursor)
      (println "Goodbye!"))))
```

`read-key-blocking!` above is a placeholder for the §7 pipeline
(reader bytes → negotiation/response interceptors → structural buffer →
`parse-key`), not a bare `read-char`.

---

## 13. Next steps

1. Prove the FFI slice first: `tcgetattr`/`cfmakeraw`/`tcsetattr` round-trip
   + blocking `read` on Unix; `GetStdHandle`/`GetConsoleMode`/
   `SetConsoleMode` + VT-input on Windows (real Windows host).
2. Port `keys` + `libs.terminal` with their tests under irregex.
3. Port `reakt` → `hiccup` → components headless (`render-lines`).
4. Build the `ITerminal` adapter (§§5–6), then the §7 input pipeline.
5. Differential-render loop, overlays, focus/modality, drain-on-exit.
6. Widget library (input/editor/select/settings lists) on the ported core;
   mouse tracking (`libs.terminal` constants already cover the protocol).
