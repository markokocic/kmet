# Login-input freeze analysis

**Status:** open — root cause not yet confirmed.

**KEY FACT: pi DOES work under the same Termux 0.119.0-beta.3 terminal while
kmet freezes.** This rules out "the beta renderer is fundamentally broken"
and means there is a concrete byte-level (or cursor-state) difference in
what kmet emits vs pi that stalls the terminal. The terminal is not the
root cause by itself.

**Revision note (latest):** the paste-theory WIP fixes (input/editor/editing
control-char stripping, `\n` submit fallback, paste-timeout, api-key login
control stripping + their tests) were **reverted** — they chased a "frozen
after paste" narrative that the hardware evidence disproved (paste arrives
clean, submits fine, app healthy, byte-identical to typed). Remaining WIP:
only genuinely-correct independent fixes (see below).

## Remaining WIP (kept — real, defensible fixes)
- `libs/edn_store.clj` — bounded lock-acquire retry (old `recur attempt`
  never advanced the counter → infinite spin/cpu-burn on a failed
delete-tree; matches observed CPU-spinning orphans).
- `libs/terminal.clj` — kitty **push** response parsing (`\e[>N u`) + push
  prefix; consumed instead of leaking into the input buffer.
- `tui/core.clj` — drop complete-but-unrecognized ESC sequences (backstop
  against input swallowing); truncate-oversized-line instead of killing the
  app. (The stuck-watchdog diagnostic was stripped per request.)

## Reverted WIP (chasing the phantom freeze)
- `input.clj` / `editor.clj` / `editing.clj` / `interactive.clj` paste
  control-char stripping, raw-`\n` submit fallback, paste-timeout,
  api-key control stripping — all predicated on "frozen after paste = input
  bug", which the evidence disproved.

## Symptom
- kmet `/login opencode` → API-key dialog → **paste** the key → press **Enter** →
  freeze: dialog stays on screen, new keys stop painting.
- **Typing** the key instead of pasting → works (closes, saves, paints).
- **pi works under the same Termux 0.119.0-beta.3 terminal; kmet freezes**
  (confirmed — the terminal itself is not the problem, the divergence is).
- Minimal blind `/quit` + Enter afterward **exits** and the terminal looks
  normal again.

## What is provably TRUE (hardware evidence)
1. The app is **healthy**: threads parked on futexes, `rchar` moves on input,
   `wchar` grows on render-resize kicks. No stuck thread.
2. The credential **saves correctly**: `debug.log` shows
   `api-key login submit ... value-len=67` → `overlay hidden` →
   `credential saved`. `auth.edn` updated.
3. The stuck-watchdog (`kmet-diag.log`) **never fires**, even though rendering
   "stops" — the app is not actually blocked.
4. After the submit frames kmet **keeps rendering** — a typed `/q` + Enter
   produced subsequent frames in `input-trace.log`.
5. The paste arrives **bracketed** (`\e[200~ … \e[201~`), value is clean
   (67 chars, no control bytes). Clipboard checked: plain ASCII.
6. Captured `input-trace.log` showed paste and typed flows are **byte-identical**.

## Conclusion from hardware evidence
The freeze is a **visual-only stall** of the Termux 0.119.0-beta.3 renderer on
the specific submit/overlay-removal frame. kmet's app logic, the value, and
the writahead are all correct.

**Since pi works on the same terminal, the stall is triggered by a concrete
kmet-vs-pi difference in an emitted frame (line content or cursor
positioning), NOT by the terminal itself.** Next step: byte-for-byte diff of
kmet's dialog-submit/overlay-hide frame against pi's equivalent.

## kmet vs pi comparison (eliminated hypotheses)
Both emit byte-near-identical streams:
- **DEC 2026 sync markers** `\e[?2026h … \e[?2026l` — pi uses them too.
- **`\r\r\n` line separators** — pi's captured frames ALSO show `\r\r\n`
  (Node's `setRawMode` on this box leaves ONLCR on; both otput run through
  the same driver). So the earlier OPOST/ONLCR theory was wrong — reverted.
- **Terminal init/negotiation**: identical query `\e[>7u\e[?u\e[c`, same
  modifyOtherKeys `\e[>4;2m`, same kitty disable `\e[<u`.
- **`2J`/`3J` full-redraw** clear — pi uses the same `\e[2J\e[H\e[3J`.
- **OSC-8** `\e]8;;\x07` SGR/OSC reset per line — same in both.

## Leading (unproven) hypotheses
1. **Overlay-removal cursor desync** — the submit frame moves up
   (`\e[4A`/`\e[13A`) and rewrites; a cursor-positioning difference vs pi in
   the overlay-hide frame could leave the beta renderer's internal cursor
   state out of sync, stalling subsequent output. Reporter hints "issue with
   overlay?" — the dialog show/hide path is the only kmet flow with no direct
   pi equivalent at the same byte shape.
2. **A specific escape/content byte only kmet emits** on the submit frame
   that the beta parser balks on (not yet isolated; needs a byte-for-byte
   diff of kmet's dialog-submit frame vs pi's overlay-close frame).

## Why typed≠pasted if bytes are identical
Not fully reconciled. Strongest guess: the freeze is terminal-side and
probabilistic — the paste path happens to hit the stall-triggering frame
where typed doesn't (or the user's "typed works" observation was a different
run/session). The TRACE clearly shows pasted == typed structurally.

## Next steps
- **Byte-for-byte diff** kmet's dialog-submit/overlay-hide frame against
  pi's overlay-open/close frame — find the first divergent escape/cursor
  sequence (the two were not yet compared frame-by-frame; only init/glue/
  separators were compared and found identical).
- Verify on **stable Termux 0.118.x** — if no freeze there, double-check the
  beta; but since pi works on the beta, prioritize the kmet-vs-pi diff over
  blaming the terminal.
- Consider matching kmet's overlay-hide cursor placement and rendered-line
  content to pi's exactly.
