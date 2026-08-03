# kmet ↔ pi TUI Alignment (100% parity plan)

Analysis of pi's TUI architecture (pi source: `~/src/cvstree/pi/packages/tui/` +
`packages/coding-agent/src/modes/interactive/interactive-mode.ts` and
`.../theme/`) and a staged plan to bring kmet's TUI to full parity:

- **Part A — TUI-core parity**: `kmet.tui.*` vs `@earendil-works/pi-tui`
  (render loop, input pipeline, focusable/IME, overlays, theme, components).
- **Part B — app-level usage parity**: `modes/interactive.clj` +
  `app/ui/*` vs `interactive-mode.ts` (layout, dialogs, extension ctx.ui).

Status of the chat-screen layout (previously "Level 1") is DONE; the
**input pipeline (Phase 1), render loop + terminal queries (Phase 2),
overlay/focus layer (Phase 3), theme controller + validation (Phase 4),
components (Phase 5 — incl. the `IEditorComponent` protocol),
app behavior (Phase 6 — two-line footer + FooterDataProvider, ExpandableText
header, loaded resources, pending-messages display + follow-up/dequeue,
status-indicator swap model with retry countdown + cancellable compaction,
editor dynamic height), extension ctx.ui surface (Phase 7), and the
write-log + listener-chaining + Loader-indicator + DynamicBorder items
are DONE** (see the per-phase notes below).

---

## Parity matrix (pi-tui module ↔ kmet file)

| pi module (`packages/tui/src`) | kmet | Status |
|--------------------------------|------|--------|
| `tui.ts` — TUI/Container/Component/Focusable, render loop, overlays, IME cursor | `tui/core.clj`, `tui/components/container.clj` | ✅ overlays + focus-restore state machine (§A.3, §A.2); render loop: force render, height-change redraw, clearOnShrink, crash/debug logs, kitty-image diff, cell-size query done (§A.4) |
| `keys.ts` — key parsing (legacy + Kitty), `matchesKey`, event types | `tui/keys.clj` | ✅ full Kitty CSI-u + modifyOtherKeys + legacy parsing, order-insensitive matching (§A.1) |
| `terminal.ts` — ProcessTerminal, Kitty protocol negotiation, write log, progress, drainInput | `tui/terminal.clj` | ✅ negotiation, drain, write log, `set-title!`, `set-progress!` (OSC 9;4 + keepalive), `move-by!`, `clear-from-cursor!` (§A.1, §A.5) |
| `stdin-buffer.ts` — batch splitting, paste re-wrap | (inline in `core.clj` input reader) | ✅ per-sequence splitting via the ESC-wait loop (cap raised for CSI-u) |
| `native-modifiers.ts` | — | missing (Apple Terminal Shift+Enter) (§A.1) |
| `utils.ts` — visibleWidth/truncateToWidth/wrapTextWithAnsi/sliceByColumn/extractSegments | `tui/utils.clj` | ✅ (verify `extractSegments`/`normalizeTerminalOutput` for §A.3) |
| `keybindings.ts` — KeybindingsManager, keyText, conflicts | `tui/keybindings.clj` | ✅ (verify conflict reporting + `key-text`) |
| `autocomplete.ts` | `tui/autocomplete.clj` | ✅ (extension-provider delegation added with §B.9) |
| `fuzzy.ts` | `tui/fuzzy.clj` | ✅ |
| `terminal-colors.ts` — OSC 11, color scheme report | `libs/terminal.clj` | ✅ OSC 11 parse/query, `?997n` report + `?2031h/l` notifications (§A.4, §A.6) |
| `terminal-image.ts` | `libs/terminal_image.clj` | ✅ (diff-loop integration partial, §A.4) |
| `editor-component.ts` — EditorComponent interface | `tui/protocols.clj` (`IEditorComponent`) | ✅ protocol added (Phase 5) — method-based contract, implemented by Editor + wired into the custom-editor swap; properties (onSubmit/onChange/borderColor) stay duck-typed fields like pi |
| `kill-ring.ts` / `undo-stack.ts` / `word-navigation.ts` | inside `editor.clj` | ✅ for Editor; **gap**: Input lacks them (§A.7) |
| `components/text|box|spacer|truncated-text.ts` | `tui/components/*` | ✅ |
| `components/input.ts` | `tui/components/input.clj` | ✅ kill-ring/undo/word-nav/paste parity (§A.7; paste newline removal fixed Phase 5) |
| `components/editor.ts` | `tui/components/editor.clj` | ✅ (verify against §A.7 list) |
| `components/markdown.ts` | `tui/components/markdown.clj` | ✅ |
| `components/select-list.ts` | `tui/components/select_list.clj` | ✅ `truncatePrimary` + layout options (§A.7) |
| `components/settings-list.ts` | `tui/components/settings_list.clj` | ✅ |
| `components/loader.ts` + `cancellable-loader.ts` | `tui/components/spinner.clj` + `cancellable_loader.clj` | ✅ `set-indicator!` done (verbatim frames, empty-frames hide, nil restores) |
| `components/image.ts` | `tui/components/image.clj` | ✅ (cell-size query, §A.4) |
| coding-agent `theme/theme.ts` + `theme-controller.ts` | `tui/theme.clj` + `app/theme_controller.clj` | ✅ accessors, validation (sorted missing tokens), auto light/dark, watcher (poll), controller (§A.6) |

---

# Part A — TUI-core parity

## A.1 Input pipeline (biggest gap)

**pi (`terminal.ts` + `stdin-buffer.ts` + `keys.ts`):**

1. **StdinBuffer** splits batched stdin into individual sequences (10 ms
   timeout), so components receive single events and `matchesKey`/
   `isKeyRelease` work. Paste content is re-wrapped in
   `\x1b[200~…\x1b[201~` for existing editor handling.
2. **Kitty keyboard protocol negotiation**: on start writes
   `` \x1b[>7u\x1b[?u\x1b[c `` (flags: 1 = disambiguate escape codes,
   2 = report event types, 4 = report alternate keys). The response
   (`\x1b[?Nu` = kitty flags, `\x1b[?…c` = device attributes) may arrive
   split across events — a 150 ms negotiation buffer + flush timer handles
   that. Non-zero flags → enable kitty; otherwise fall back to
   modifyOtherKeys (`\x1b[>4;2m`). Disabled on stop/drain (`\x1b[<u`).
3. **keys.ts parsing**: legacy CSI/SS3 table, **Kitty `\x1b[key;mods;…u`
   forms**, `\x1b[27;mods;code~`, CSI modified f-keys, alt+key, event
   types (`:1` press, `:2` repeat, `:3` release), `Key.*` idents
   (`ctrl+shift+p`, `pageUp`, `f1`…).
4. **Apple Terminal**: `\r` while Shift held (native modifier probe) →
   `\x1b[13;2u`. Windows: native helper enables
   ENABLE_VIRTUAL_TERMINAL_INPUT after raw mode.
5. **drainInput(maxMs=1000, idleMs=50)** on exit: disable kitty/modifyOtherKeys
   first, then drain stdin so late key-release sequences don't leak to the
   parent shell over SSH.
6. **PI_TUI_WRITE_LOG**: raw ANSI stream appended to a file (auto-named
   under a directory).

**kmet today:** JLine char-by-char reader with an ESC-wait loop
(`core.clj process-input-buffer!`). `parse-key` handles legacy CSI, ctrl+letter,
plain chars only; `kitty-active?` exists but nothing negotiates or parses
Kitty sequences (they'd be dispatched char-by-char as garbage). No
batch splitting, no alt+ESC, no drain, no write log.

**Plan (files: `tui/keys.clj`, `tui/terminal.clj`, `tui/core.clj`):**

1. `tui/input_buffer.clj` (new): port `StdinBuffer` — accumulate raw
   reads, split into sequences (escape-prefix wait with 10 ms timeout;
   paste markers re-wrap). Replace the `process-input-buffer!` loop.
   (Resolved: the existing ESC-wait loop in `core.clj process-input-buffer!`
   was extended instead — equivalent per-sequence splitting; no separate
   file was created, see Phase 1 status.)
2. `keys.clj`: full Kitty parse — `\x1b[key;mods;…u` (incl. `27;mods;code~`
   and `mods;code~` variants), event-type suffix, alternate-key ids
   (`shift+tab` etc.), `alt+x` from `\x1b x` prefix, F-keys. Keep the
   legacy table.
3. `terminal.clj`: protocol negotiation — query on `start!`, buffer the
   response (150 ms flush), set `kitty-active` or enable modifyOtherKeys;
   disable both on `stop!`; add `drain-input!` and call it from
   `tui-stop` before terminal close; `set-title!` (OSC 0), `set-progress!`
   (OSC 9;4 with 1 s keepalive), `move-by!`, `clear-from-cursor!`.
4. `terminal.clj`: `PI_TUI_WRITE_LOG`-equivalent env (`KMET_TUI_WRITE_LOG`)
   appending every write.
5. Apple Terminal Shift+Enter normalization (skip on non-darwin; port
   `native-modifiers.clj` shim if feasible).
6. Windows VT input: skip (Babashka stdin path differs; note as known
   divergence).

## A.2 Focusable & IME

**pi (`tui.ts`):** `Focusable { focused: boolean }`; `isFocusable()` type
guard; `CURSOR_MARKER` = `\x1b_pi:c\x07` APC; `extractCursorPosition`
(scans only bottom `height` lines, bottom-up); `positionHardwareCursor`
(relative row delta + absolute column `\x1b[{col+1}G`, show/hide cursor per
`showHardwareCursor`); `setShowHardwareCursor()` + env
`PI_HARDWARE_CURSOR=1`; containers hosting an `Input`/`Editor` child must
implement `Focusable` and forward `focused` to the child (IME candidate
window placement).

**kmet today:** ✅ `IFocusable` (focused/set-focused!), `CURSOR-MARKER` in
`utils.clj` (`\u001b_km:c\u0007`), viewport-only bottom-up marker scan,
relative cursor positioning, `KMET_HARDWARE_CURSOR` env. Editor and Input
emit the marker and implement `IFocusable`.

**Plan (files: `tui/core.clj`, `tui/protocols.clj`):**

1. Add `set-show-hardware-cursor!` / `get-show-hardware-cursor` runtime
   API (pi has both env and method).
2. Document + enforce the propagation convention: any container-like
   component that hosts an input-bearing child must implement `IFocusable`
   and forward `set-focused!` to the child. Today no kmet component embeds
   an Input (SelectList/SettingsList handle keys directly, same as pi) —
   but the `ui-custom` dialogs (Part B) will wrap `Input`, so add the
   convention to `AGENTS.md` and follow it there.
3. Port the **overlay focus-restore state machine** from
   `setFocusInternal` (§A.3) — it is the part of focus handling that kmet
   lacks (eligible/blocked/inactive restore states, `blockedBy`,
   `resume: restore-overlay | focus-target`).
4. Input listener chaining: pi `InputListener` returns
   `{ consume?, data? }` and later listeners see transformed data; kmet
   runs listeners without chaining. Port (`tui-add-input-listener` +
   dispatch in `dispatch-input!`).

## A.3 Overlays

**pi (`tui.ts`):**

- `OverlayOptions`: `width`/`maxHeight`/`row`/`col` as `SizeValue`
  (number or `"50%"`), `minWidth`, `anchor` (9 positions), `offsetX/Y`,
  `margin` (number or per-side map), `visible(termWidth, termHeight)`,
  `nonCapturing`.
- `OverlayHandle`: `hide()`, `setHidden(bool)`, `isHidden()`,
  `focus()` (brings to front via `focusOrder`), `unfocus({target})`,
  `isFocused()`.
- Stack renders **all visible overlays** sorted by `focusOrder` (higher on
  top), composited over the padded content with `compositeLineAt`
  (single-pass `extractSegments` + `SEGMENT_RESET` + visible-width guard),
  `workingHeight = max(content, termHeight, minLinesNeeded)`,
  `viewportStart = workingHeight − termHeight`.
- Focus semantics: `preFocus` capture, `setFocusInternal` restore state
  machine (eligible/blocked/inactive), `retargetOverlayPreFocus` on
  removal, `clearOverlayFocusRestoreFor`, `getVisibleOverlayFocusRestore`,
  `isOverlayFocusAncestor`, `isComponentMounted` check before restoring
  focus to a stale component; input redirect when the focused overlay
  becomes invisible (resize/`visible()` callback).

**kmet today:** `Overlay` record with `x/y/width/height`; only the top
overlay is rendered (`peek`), fixed position, no options, no handle, no
focus-restore state machine (basic `previous-focus` restore with a
mounted check exists), no `nonCapturing`, no hidden state.

**Plan (files: `tui/core.clj`):**

1. Extend `Overlay` record: `options` (full OverlayOptions), `hidden`,
   `focus-order`, `pre-focus`. Rewrite `tui-show-overlay` to return a
   handle record with `hide/set-hidden!/is-hidden?/focus/unfocus/is-focused?`.
2. `resolve-overlay-layout`: SizeValue parsing, margins, anchors, row/col,
   offsets, clamping — port exactly.
3. `composite-overlays`: render all visible overlays sorted by focusOrder;
   port `composite-line-at` (or reuse `utils/composite-line` after adding
   `extract-segments` + width guard).
4. Port the focus-restore state machine (`set-focus-internal`,
   `clear-overlay-focus-restore`, `retarget-overlay-pre-focus`,
   `get-visible-overlay-focus-restore`, `is-overlay-focus-ancestor`).
5. Input routing: when the focused component is an overlay that became
   invisible, redirect to the topmost visible overlay or `preFocus`
   (pi `handleInput` logic).
6. `tui-hide-overlay` → port pi `hideOverlay()` (topmost only) + handle
   `hide()` (any entry) semantics; keep `tui-has-overlay?` as
   "any visible overlay" (pi `hasOverlay()`).

## A.4 Render loop & differential rendering

**pi (`tui.ts`):**

- `requestRender(force)` — force clears previous lines/width/height and
  schedules an immediate full render. ✅
- Full redraw triggers: first render; width change; **height change
  (except Termux** — keyboard show/hide would replay history);
  `clearOnShrink` when `newLines < maxLinesRendered` and no overlays
  (env `KMET_CLEAR_ON_SHRINK`, default **off** like pi's
  `PI_CLEAR_ON_SHRINK === "1"` — tui.md's earlier "default on" was wrong;
  runtime setter). ✅
- `maxLinesRendered` high-water mark; `previousViewportTop` tracking with
  `finalCursorRow`-based update; `fullRedrawCount`.
- Kitty images in the diff: `expandChangedRangeForKittyImages`,
  `deleteChangedKittyImages`, reserved-row pre-clear (`\x1b[2K` per row +
  cursor dance), full-render placement, fallback to full redraw when an
  image would scroll; `collectKittyImageIds` + delete on full render.
- Width-overflow → crash log (`pi-crash.log` in the agent dir) + throw
  with guidance; `PI_DEBUG_REDRAW=1` redraw reasons; `PI_TUI_DEBUG=1`
  render dumps to `/tmp/tui/`.
- `applyLineResets`: every non-image line gets SGR reset + OSC 8 reset
  (`\x1b[0m\x1b]8;;\x07`).
- Cell size query (`CSI 16 t`) on start when images are supported →
  `setCellDimensions` → `invalidate()` all + `requestRender()`.
- OSC 11 background query (`\x1b]11;?\x07`, timeout) and terminal color
  scheme (`CSI ? 996 n`; `CSI ? 2031 h/l` notifications) — consumed in
  `handleInput` before listeners.
- Frame pacing: `MIN_RENDER_INTERVAL_MS = 16` with `setTimeout` batching
  (kmet's fixed 16 ms sleep is equivalent).

**kmet today:** screen-row diff (scroll-aware, scrollback-preserving —
arguably stronger than pi's content diff for the unbounded chat), CSI 2026
sync, single write per frame, 16 ms loop, first-render/width-change full
redraws, viewport-top tracking, hardware cursor positioning, kitty image
rendering in `libs/terminal_image.clj` but no diff-loop integration, no
force render, no height-change redraw, no clearOnShrink, no crash/debug
logs, no cell-size query, no OSC 11 / color scheme.

**Plan (files: `tui/core.clj`, `tui/terminal.clj`, `libs/terminal_image.clj`):**

1. `tui-request-render` with `force` flag (reset previous lines/width, do
   full render next frame). ✅
2. Height-change handling: full redraw when height changes and not Termux
   (`TERMUX_VERSION` env check), matching pi — verify against kmet's
   screen-diff behavior first (kmet may already handle resize correctly;
   keep whichever matches pi's observable behavior). ✅
3. `clearOnShrink` (`KMET_CLEAR_ON_SHRINK` env, default **off** like pi,
   runtime setter) using a `max-lines-rendered` high-water mark; skip when
   overlays are active. ✅
4. Kitty image diff integration: port `expandChangedRangeForKittyImages` /
   `deleteChangedKittyImages` / reserved-row pre-clear into both diff
   paths; `collectKittyImageIds` + delete on full redraw. ✅
5. Crash log + width-overflow throw (write to `kmet-crash.log` in cwd or
   agent dir); `KMET_DEBUG_REDRAW=1` and `KMET_TUI_DEBUG=1` equivalents. ✅
6. Cell size query on start (`\x1b[16t`) when image capabilities
   detected; parse `\x1b[6;h;wt` response; `set-cell-dimensions!` +
   `invalidate` all + request render. ✅
7. OSC 11 background query + `CSI ?996n` color scheme report + `?2031h/l`
   notifications; consume responses in `dispatch-input!` before listeners
   (port `consumeOsc11BackgroundResponse`, `consumeTerminalColorSchemeReport`).

## A.5 Terminal (`tui/terminal.clj`)

✅ Covered by A.1 (negotiation, drain, write log) and A.4 (title, progress
with keepalive, `move-by!`, `clear-from-cursor!`, cell-size / OSC 11 /
color-scheme queries). Keep the JLine wrapper — no need to switch to raw
stdin.

## A.6 Theme

**pi (`coding-agent/theme/theme.ts` + `theme-controller.ts`):**

- `Theme` class: `fg(color, text)` / `bg(color, text)` (attribute-specific
  resets), `bold/italic/underline/inverse/strikethrough`,
  `getFgAnsi/getBgAnsi`, `getColorMode`, `getThinkingBorderColor(level)`,
  `getBashModeBorderColor`; `thinkingMax` falls back to `thinkingXhigh`.
- Loading: JSON schema (TypeBox) validation that reports **all missing
  required color tokens** + per-path errors; `vars` (with cycle
  detection); built-in dark/light; custom themes dir; registered themes;
  `getAvailableThemesWithPaths`.
- `ThemeController`: auto light/dark via terminal color scheme/OSC 11
  detection, `setThemeName`/`setThemeInstance`, live file watching
  (`sourcePath`), **`applyTheme` → `tui.invalidate()` (all children +
  overlays) → `requestRender()`** — this is the "rebuild on invalidate"
  contract: components that pre-bake theme colors must rebuild inside
  `invalidate()`.

**kmet today:** ✅ token sets match pi exactly (`FG-TOKENS`/`BG-TOKENS`),
Theme record, fg/bg/bold/dim/italic/underline/inverse/strikethrough with
attribute-specific resets, pi-schema EDN loading with `vars` + flat legacy,
dark fallback for missing tokens, `get-markdown-theme`/`get-select-list-theme`
/`get-settings-list-theme`/`get-editor-theme`, registry + dir loading.

**Plan (files: `tui/theme.clj`, `config.clj`, `modes/interactive.clj`):**

1. Theme API completeness: `get-fg-ansi`/`get-bg-ansi`/`get-color-mode`
   accessors; `get-thinking-border-color` and `get-bash-mode-border-color`
   helpers (kebab-cased, pi behavior); `thinkingMax` → `thinkingXhigh`
   fallback in `make-theme` (pi `??` semantics, not dark-defaults
   substitution). ✅
2. Validation parity: when a theme is missing tokens, report the sorted
   list of missing required colors (pi's `Missing required color tokens`)
   instead of the generic warning. ✅
3. **ThemeController port** (`app/theme_controller.clj` or in
   `config.clj`): auto light/dark via the A.4 color-scheme detection;
   `set-theme-name!`/`set-theme-instance!`; on change call
   `tui-invalidate-all` (children + overlays) + `tui-request-render`
   (force). File watching on `source-path` (Theme already carries it). ✅
4. Component contract: audit components that pre-bake theme strings
   (Text children created with `theme/fg` in `app/ui/*`) — they must
   rebuild in `invalidate()` (pi's "rebuild on invalidate" pattern). The
   `track!` cache handles atom-driven renders; themed-string holders do
   not — add an audit checklist to the verification section. ✅ (the
   on-changed wiring re-sets chat-history (propagating to children),
   footer, status-indicator, editor autocomplete + key-hint fns; dialogs
   are created per-call with the current theme)

## A.7 Components

| Component | pi | kmet | Work |
|-----------|----|------|------|
| Text / Box / Container / Spacer / TruncatedText | ✅ | ✅ | — |
| Input | kill-ring, undo stack, yank/yank-pop, paste buffer, word navigation | ✅ full parity (kill-ring/undo/word-nav/paste; pi-faithful paste newline removal) | — |
| Editor | full | ✅ comprehensive | verify: undo/redo, kill ring, paste, autocomplete, scroll borders, dynamic height (see B.5) |
| EditorComponent interface | `editor-component.ts` | `IEditorComponent` in `tui/protocols.clj` | ✅ protocol added (Phase 5): get-text/set-text!/get-expanded-text/add-to-history!/insert-text-at-cursor!/set-autocomplete-provider!/set-autocomplete-max-visible!/set-padding-x!/set-on-submit!/set-on-change!; implemented by Editor; core re-exports dispatch through it (custom components may implement it); properties (onSubmit/onChange/borderColor) stay duck-typed fields like pi. Placed in `kmet.tui.protocols` rather than `app/ui/editor_component.clj` — the protocol is generic TUI (pi: tui package) and `kmet.tui.components.editor` (tui layer) must implement it, which app→tui layering would forbid |
| Markdown | ✅ | ✅ | — |
| SelectList | `SelectListLayoutOptions` (min/maxPrimaryColumnWidth + `truncatePrimary` callback) + wrap-around navigation, centered viewport, `onSelectionChange` | ✅ (min/max bounds, `truncatePrimary` with pi's context + re-clamp, pi wrap up/down, centered `startIndex`, scroll-info on either-end clipping, `onSelectionChange`) | — |
| SettingsList | ✅ | ✅ | — |
| Loader | `setIndicator({frames, intervalMs})`, verbatim mode, `render()` = `["", ...text]` | ✅ `spinner-set-indicator!` (frames/interval/verbatim, empty-frames hide, nil restores) | — |
| CancellableLoader | ✅ | ✅ | — |
| Image | cell-size query integration | ✅ render | wire cell-size query (A.4.6) + invalidate on dimensions change |
| Autocomplete | ✅ | ✅ | — |
| Keybindings | conflicts reporting, `keyText` | ✅ | verify `key-text` + conflict messages match |

---

# Part B — App-level usage parity (interactive mode)

Existing layout analysis (§1 component map) is **DONE**; the per-component
behavioral plan below is updated with the full extension `ctx.ui` surface.

## B.1 Header: `ExpandableText`

Port pi's `builtInHeader` (compact/full variants, toggled by
`app.tools.expand`, startup expansion state). Move welcome/startup-help
out of the chat info-message; generate keybinding hints from the registry.
After B.6 lands, fold the current `hdr` status line into the footer.
Files: `tui/components/expandable_text.clj` (new), `modes/interactive.clj`.

## B.2 Loaded resources

`app/ui/loaded_resources.clj` (new) — dim title + lines per resource group
rendered between header and chat; rebuild on context-file changes.
Files: `app/ui/loaded_resources.clj`, `app/context.clj`,
`modes/interactive.clj`.

## B.3 Pending messages

Track steering/follow-up queue display list on `CoreState`; port
`updatePendingMessagesDisplay` (Spacer + dim `TruncatedText` per queued
message + `↳ <key> to edit all queued messages` hint); keep bash
components appended in the same container.
Files: `modes/interactive.clj`, `app/loop.clj`, `app/ui/pending_messages.clj`
(new).

## B.4 Status indicators

Swap model: `show-status-indicator!`/`clear-status-indicator!` clearing
`status-container`; kinds: working (spinner + message + elapsed time),
compaction progress, retry countdown (`CountdownTimer`), idle (stable
two-row). `setWorkingIndicator({frames, intervalMs})` with verbatim frames
and hide support; `setWorkingMessage`/`setWorkingVisible`.
Files: `app/ui/status_indicator.clj`, `tui/components/spinner.clj`,
`modes/interactive.clj`.

## B.5 Editor: dynamic height + custom editor swap

- `maxVisibleLines = max(5, floor(rows * 0.3))` recomputed per render
  (editor.clj already takes `:terminal-rows`; drop fixed `:height 8`).
- **Custom editor** (pi `setEditorComponent`): factory
  `(tui, theme, keybindings) → component`; on swap: save text, clear
  `editor-container`, wire `onSubmit`/`onChange`, copy text + padding +
  border color + autocomplete provider + action handlers (pi copies the
  default editor's actionHandlers map into the custom editor), restore on
  `undefined`. Done via the `IEditorComponent` protocol (A.7/Phase 5) when
  the custom component implements it, with pi's duck-typed field copy as
  the fallback (`transfer-editor!` in `modes/interactive.clj`).
Files: `tui/components/editor.clj`, `kmet.tui.protocols`
(`IEditorComponent`), `modes/interactive.clj`.

## B.6 Footer

Two-line pi footer: cwd (home-substituted, truncated) + git branch +
session; stats line (↑in ↓out, cache R/W, context % colored) + right-
aligned `(provider) model • thinking`. `FooterDataProvider` port (git
branch + session name + token totals). Remove the `─` separator.
Files: `app/ui/footer.clj`, `app/ui/footer_data_provider.clj` (new),
`app/session.clj`, `modes/interactive.clj`.

## B.7 Extension widgets

`register-widget!`/`unregister-widget!` keyed maps on the extension
registry; `render-widgets!` with pi's container rules (above: leading
Spacer + widgets, bare Spacer when empty; below: widgets only);
`MAX_WIDGET_LINES = 10` truncation with `… (widget truncated)` line.
Widgets get theme + `request-render`.
Files: `app/extensions.clj`, `modes/interactive.clj` (widget rendering
stays inline in the mode — like pi, which has no widgets module either;
`render-widgets!` lives in `interactive.clj`, registry access in
`app/extensions.clj`).

## B.8 Changelog (optional)

First-run `Spacer + DynamicBorder + "What's New" Markdown + Spacer +
DynamicBorder` in the chat container. Requires `DynamicBorder`
(`tui/components/dynamic_border.clj`, pi `dynamic-border.ts` — top/bottom border
lines with a color fn).

## B.9 Extension ctx.ui surface (the missing API)

Port the `ExtensionUIContext` from `interactive-mode.ts` so extensions and
custom tools can drive the UI:

- `ui.custom(factory, {overlay?, overlayOptions?, onHandle?})` — the
  centerpiece (Part A.3 handles + TUI overlay integration). Non-overlay
  mode replaces the editor container content and restores on `done()`.
- `ui.select` / `ui.confirm` / `ui.input` / `ui.editor(title, prefill)` /
  `ui.notify` dialogs (SelectList/Input hosted in the overlay machinery).
- `ui.onTerminalInput(handler)` — input listener with consume/data.
- `ui.setStatus` (✅ exists), `setWorkingIndicator` (B.4),
  `setWidget` (B.7), `setFooter(factory)` (B.6), `setHeader(factory)`,
  `setEditorComponent` (B.5), `setTitle`, `pasteToEditor`,
  `setEditorText`/`getEditorText`, `addAutocompleteProvider`.
- `ui.theme` getter, `getAllThemes`/`getTheme`/`setTheme` (A.6.3),
  `getToolsExpanded`/`setToolsExpanded`.
- Tool-side context (`createProjectTrustContext`): `select/confirm/input/
  notify` for custom tools.
- Extension lifecycle: on reload, dispose widgets/footer/header/editor,
  clear input listeners, restore defaults (`resetExtensionUI`).

---

## Order & verification

Strategy: **dependency-first, risk-first, value-last**. The riskiest rewrites
(input, rendering) go first so regressions surface before app features build
on top of them; the extension-UI capstone (B.9) goes last because it needs
everything beneath it. Each phase ends with its verification gate; run
`bb lint` + `bb format-check` + `bb test` + `bb test-ext` before each phase
boundary.

### Phase 1 — Input pipeline (A.1 + A.2.4)

**Status: DONE** — full `keys.clj` parsing (Kitty CSI-u with alternate
keys + event types, modified arrows/functional keys/home-end, xterm
modifyOtherKeys `27;mods;code~`, mode-aware legacy `\x1b\r`/`\n` →
shift+enter under Kitty, the complete legacy table incl. clear/f-key
variants, ESC-prefixed alt/ctrl+alt forms, space → `space` id) with
modifier-order-insensitive `matches-key?`; Kitty keyboard protocol
negotiation (query `\x1b[>7u\x1b[?u\x1b[c` on start, 150 ms fragment
buffer + flush timer, modifyOtherKeys `\x1b[>4;2m` fallback on zero
flags/DA sentinel, `\x1b[<u` disable on stop and on suspend);
`drain-input!` (1 s max / 50 ms idle) on quit so late key releases don't
leak to the parent shell; input-listener chaining (§A.2.4). The ESC-wait
loop already provided per-sequence splitting (pi StdinBuffer equivalent),
with the sequence-length cap raised for long CSI-u forms. Skipped:
Apple Terminal Shift+Enter probe and Windows VT input helper
(platform-specific; documented divergences).

**Contents:** `keys.clj` full parsing (Kitty `\x1b[k;m;…u`, `27;m;c~`,
alternate keys, event types, `alt+x` from ESC prefix, F-keys) →
stdin batch splitting + paste re-wrap (folded into the ESC-wait loop in
`core.clj` — the planned `tui/input_buffer.clj` was not created) →
`terminal.clj` protocol negotiation (query, 150 ms response buffer,
modifyOtherKeys fallback, disable on stop/drain) → input-listener chaining
(`{consume, data}` transforms).

**Why first:** every component's `handleInput`, every overlay, every dialog
depends on correct key parsing. It is also the highest-regression-risk item —
doing it while the surface is small means fewer things to break. Sub-order:
parse first (pure, unit-testable), then the buffer, then negotiation.

**Gate:** `bb test` key-parsing parity tests (kitty/legacy/alt/ctrl+shift+p/
F-keys against pi's `keys.ts` cases); manual: kitty vs non-kitty terminal,
paste as one wrapped event, releases filtered unless `:wants-key-release?`.

### Phase 2 — Render loop & terminal (A.4 + A.5)

**Status: DONE** — `KMET_TUI_WRITE_LOG` (write log) and `set-title!` were
already in; this pass added `requestRender(force)` (clears previous frame
state → clearing full redraw), height-change full redraw (non-Termux,
`TERMUX_VERSION` env check), `clearOnShrink` (high-water mark via
`max-lines-rendered`, skipped while overlays are active,
`KMET_CLEAR_ON_SHRINK=1` to enable + `set-clear-on-shrink!` runtime setter;
**default off — pi's `PI_CLEAR_ON_SHRINK === "1"` is off too; tui.md's
earlier "default on" was wrong**), full-redraw-count + `KMET_DEBUG_REDRAW`
(`kmet-debug-render.log` reasons) + `KMET_TUI_DEBUG` (`/tmp/tui/` render
dumps), width-overflow crash log (`kmet-crash.log` + `truncateToWidth`
guidance throw, image lines exempt), Kitty-image diff integration
(per-row old-id deletes incl. covering blocks, reserved-row `\u001b[2K`
pre-clear + C=1 placement dance that skips the padding rows, full-redraw
placement dance, previous-id delete on clear, changed-block-overflows-
screen → full redraw), cell-size query (`\u001b[16t` on start when image
capabilities detected; `\u001b[6;h;wt` consumed in the input path →
`set-cell-dimensions!` + `tui-invalidate` + render), OSC 11 background
query (`tui-query-terminal-background-color`, timeout → nil) + color
scheme report (`\u001b[?996n` query, `\u001b[?997;Nn` consumed ungated —
covers unsolicited `?2031h/l` notifications — `tui-on-terminal-color-scheme-
change`, `tui-query-terminal-color-scheme`, `tui-set-terminal-color-scheme-
notifications`), and `set-progress!` (OSC 9;4 with 1 s keepalive) /
`move-by!` / `clear-from-cursor!` on the terminal. OSC 11 settle fns now
clear the pending flag *before* resolving the promise (the old order raced
— a deref could observe a stale flag after deliver; fixed the flaky
`test-intercept-osc-11`). Responses are
intercepted in `process-input-buffer!` (like the negotiation) so they never
dispatch as keys; fragments are held with a flush timer.
Skipped: `applyLineResets` (kmet lines are self-contained via
attribute-specific resets — no SGR/OSC-8 state to clear; documented
divergence).

**Contents:** `requestRender(force)` → height-change full redraw (non-Termux)
→ `clearOnShrink` high-water mark (env + runtime setter) → Kitty-image diff
integration (`expandChangedRangeForKittyImages`, `deleteChangedKittyImages`,
reserved-row pre-clear, full-render placement) → width-overflow crash log +
`KMET_DEBUG_REDRAW`/`KMET_TUI_DEBUG` → cell-size query (CSI 16t) → OSC 11
background + color-scheme protocol (`?996n`, `?2031h/l`) → `KMET_TUI_WRITE_LOG`
→ terminal title/progress/move-by/clear-from-cursor.

**Why second:** this is the "feels right" layer — resize, shrink, images, and
the color-scheme protocol the theme controller needs. It is independent of
overlays, and stabilizing it early prevents visual regressions from
compounding with later features.

**Gate:** resize behavior side-by-side with pi (height change → full redraw
outside Termux; Termux keyboard toggle flicker-free); shrink clears rows
(default on, env-off); images survive mid-document growth without stale
pixels; `kmet-crash.log` on width overflow; `KMET_TUI_WRITE_LOG` captures
the ANSI stream.

### Phase 3 — Focus & overlays (A.2 + A.3)

**Status: DONE** — `set-show-hardware-cursor!`/`get-show-hardware-cursor`,
full `OverlayOptions` (SizeValue, anchors, margins, offsets, `visible`,
`nonCapturing`), `OverlayHandle` (hide/set-hidden!/focus/unfocus({target})/
is-focused?), multi-overlay compositing by focus order with working-height
padding, the focus-restore state machine (eligible/blocked/inactive,
retarget-overlay-pre-focus, is-overlay-focus-ancestor, mounted check),
input redirect for invisible focused overlays, input listener chaining
(`{consume, data}` transforms, §A.2.4), and the IME focus-propagation
convention (implemented by the extension dialogs, §B.9).

**Contents:** `set-show-hardware-cursor!`/`get-show-hardware-cursor` →
overlay focus-restore state machine (`set-focus-internal`, eligible/blocked/
inactive, `retarget-overlay-pre-focus`, `is-overlay-focus-ancestor`, mounted
check) → full `OverlayOptions` (SizeValue, anchors, margins, offsets,
`visible` callback, `nonCapturing`) → `OverlayHandle` (hide/setHidden/focus/
unfocus({target})/isFocused) → multi-overlay compositing (focusOrder sort,
padding to `workingHeight`, `composite-line-at` with width guard) → input
redirect when a focused overlay turns invisible.

**Why third:** the largest single piece of new machinery, and the gateway to
all dialogs + `ui.custom`. Nothing else depends on it, so it can be done
calmly after the input/rendering layers are solid.

**Gate:** `overlay-qa-tests` cases side-by-side (anchors, margins, stacking,
responsive visibility, animation); focus reclaim after nested custom UI
closes; `unfocus({target})`; hidden overlays don't capture input; IME
candidate window follows dialogs hosting an Input (focus propagation
convention).

### Phase 4 — Theme (A.6)

**Status: DONE** — API accessors (`get-fg-ansi`/`get-bg-ansi` with pi's
unknown-color throw, `get-color-mode`, `get-thinking-border-color`,
`get-bash-mode-border-color`); `thinkingMax` → `thinkingXhigh` fallback in
both resolvers (pi `??` semantics); missing-token validation at load — the
warning now lists the sorted missing required colors (pi: `Missing required
color tokens`; `:thinking-max` optional like pi's schema) + the pi name
check (no `/`); current-theme state (`get-current-theme`/`-name`,
`on-theme-change`, `init-theme!`, `set-theme!` with dark fallback,
`set-theme-instance!`, `get-theme-by-name`); custom-theme file watcher
(1 s mtime poll of `<name>.edn` in the themes dir — babashka.fs has no
watcher and java.nio is out per AGENTS.md; keeps the last good theme;
debounce-free by construction); terminal-theme detection (`get-theme-for-rgb-
color` with pi's luminance, `detect-terminal-background-from-env` via
COLORFGBG, `parse-auto-theme-setting`/`resolve-theme-setting` for
`"light/dark"` settings); the `ThemeController` in
`app/theme_controller.clj` (constructor resolves + applies the setting with
the watcher, `apply-from-settings!` — auto setting → color-scheme detection
+ auto-sync via `?2031h/l` notifications, explicit setting → apply, none →
OSC 11 detection with env fallback, `set-theme-name!`, `set-theme-instance!`,
`preview`, `apply-terminal-theme!` on scheme reports); live re-theme wiring
in `interactive.clj` (the on-changed callback re-sets chat-history/footer/
status-indicator/editor-autocomplete/key-hint theme fns + re-render), the
`/theme` command (switch + completions from the registry), and the
extension `ui.setTheme` (Theme instance → in-memory; name → controller,
disabling auto-sync).
Divergences: no settings persistence (kmet config is read-only EDN — a
high-confidence detection is applied without saving); partial themes are
rejected at load (pi parity) — the old dark-fallback fill remains for
`make-theme` callers.

**Contents:** API accessors (`get-fg-ansi`/`get-bg-ansi`/`get-color-mode`,
`get-thinking-border-color`, `get-bash-mode-border-color`) → `thinkingMax`→
`thinkingXhigh` fallback → missing-token validation errors (sorted list) →
`ThemeController` (auto light/dark via Phase 2's color-scheme detection,
`set-theme-name!`/`set-theme-instance!`, file watching on `source-path`,
apply → invalidate all children + overlays + force render) → audit themed-
string pre-baking in `app/ui/*` for rebuild-on-invalidate.

**Why fourth:** needs Phase 2's color-scheme detection; small and
self-contained; unblocks `ui.setTheme` in the capstone.

**Gate:** missing-token error lists all missing colors; `set-theme` re-themes
live (components rebuild); auto light/dark follows the terminal scheme
report; no stale colors after switching.

### Phase 5 — Components (A.7)

**Status: DONE** — Loader `set-indicator!` (verbatim frames, interval,
empty-frames hide, nil restores), `DynamicBorder`, SelectList
`truncatePrimary` (pi context map + re-clamp + scroll-info truncation) +
navigation parity (wrap-around up/down incl. ctrl+n/p, centered viewport
`startIndex = selected − ⌊height/2⌋`, scroll-info on either-end clipping,
`onSelectionChange` callback),
Input kill-ring/undo/word-nav parity (already present; paste newline
handling aligned to pi — newlines are removed, tabs → 4 spaces), and the
`IEditorComponent` protocol. The protocol lives in `kmet.tui.protocols`
(pi puts `editor-component.ts` in the tui package; `app/ui/editor_component.clj`
would invert kmet's layering since the tui-layer Editor must implement it)
and is implemented by the app `Editor`. The custom-editor swap
(`transfer-editor!` + `editor-text-get`/`editor-text-set!`/
`editor-text-get-expanded` in `interactive.clj`) dispatches through the
protocol when the custom component implements it — custom components may
implement any tui protocol — and falls back to pi's duck-typed field copy
otherwise. `core.clj` re-exports dispatch through the protocol too. The
active-editor atom now lives on `CoreState` (`:current-editor-atom`), so the
Ctrl+G external-editor flow reads/writes the *active* editor like pi's
`handleOpenExternalEditor` (`this.editor`), not just the default one.

**Contents:** Loader `setIndicator!` (frames/interval/verbatim) → SelectList
`truncatePrimary` callback → Input kill-ring/undo/word-nav/paste parity
(reuse `editor.clj` internals) → `IEditorComponent` protocol
(`kmet.tui.protocols`, implemented by Editor; swap-path dispatch).

**Why here:** all independent; small effort; `IEditorComponent` must land
before B.5.

**Gate:** `bb test` for each component (`test_select_list` truncate-primary
cases, `test_input` paste cases, `test_editor` protocol cases);
`setIndicator!` frames/interval/verbatim match pi; Input editing feels
identical (yank/yank-pop, undo).

### Phase 6 — App behavior (B.5 → B.6 → B.1 → B.3 → B.2 → B.4 → B.8)

**Status: DONE** — B.5 dynamic editor height (fixed `:height 8` dropped;
`:terminal-rows` drives `max(5, rows*0.3)` per render, default fallback 12)
+ custom-editor swap (from the earlier pass), B.6 two-line footer +
`FooterDataProvider` (cwd home-substituted + git branch on line 1; usage
stats ↑in/↓out/R/W/CH + context % colored + right-aligned
`(provider) model • thinking` on line 2; extension statuses on line 3 —
no `─` separator), B.1 `ExpandableText` header (`tui/components/expandable_text.clj`;
compact/full welcome with keybinding hints from the registry; the chat
info-message welcome was removed; toggled by `app.tools.expand` together
with tool output and the loaded-resources sections), B.3 pending-messages
display (`app/ui/pending_messages.clj`; `:queue-update` →
`update-pending-messages!`; `app.message.followUp` Alt+Enter queues a
follow-up while streaming / submits when idle; `app.message.dequeue`
Alt+Up restores queued messages to the editor), B.2 loaded resources
(`app/ui/loaded_resources.clj`; Context/Skills/Prompts/Extensions sections
between header and chat; rebuilt on `/reload`), B.4 status-indicator swap
model (`show-status-indicator!`/`clear-status-indicator!` over the
`status-container`; working/retry-countdown/compaction kinds — Retry and
Compaction indicators self-animate from elapsed time; `:auto-retry-start`/
`:auto-retry-end` and new `:compaction-start`/`:compaction-end` events wired
— the latter emitted by `compact-context!` with `:reason`
`:manual`/`:threshold`/`:overflow`). B.8 changelog skipped (marked
optional in the plan).

Also landed along the way: usage tracking end-to-end (`libs/sse.clj`
`:usage` events for OpenAI include_usage chunks + Anthropic message_start;
`llm.clj` `:on-usage`; `session.clj` `entry-usage`/`usage-totals`; assistant
messages carry `:usage` into session entries — the footer accumulates from
all entries like pi) and a latent-bug fix: `CoreState :session` is now a
`session-atom` (`/new` and `/resume` were `reset!`-ing a plain record field,
which threw at runtime).

Divergences: footer context shows `{tokens} tokens` when no
`:context-window` is configured (kmet has no model context-window data);
no session name in the footer (kmet has no `/name`); the retry countdown
is computed from elapsed time on each render (the anim timer drives
renders) instead of a JS `CountdownTimer`.

**Compaction cancellation** (pi: onEscape → `session.abortCompaction()`):
escape aborts a compaction in progress — `summarize!` now drives the
summarization with the run's cancel signal (plus a signal watch that
resolves the summarization promise immediately, so cancellation doesn't
wait for the killed stream), `compact-context!` checks the signal before
and after summarization (leaving the session untouched on cancel) and
emits `:compaction-end` with `:aborted true`, the `CompactionStatusIndicator`
shows the "(Esc to cancel)" hint, `handle-cancel` sets the signal while
`:compacting?` is set, and the manual `/compact` handler runs on a future
so the input thread stays live during the summarization (with a
`:compacting?` guard refusing concurrent compactions). An aborted
compaction reports "Compaction cancelled" / "Auto-compaction cancelled"
(pi parity, split by `:reason` :manual vs auto).

**Contents:**
- B.5 editor dynamic height (`max(5, rows*0.3)` per render) + custom editor
  swap (text/padding/border/autocomplete/action-handler transfer)
- B.6 two-line footer (cwd + branch + session / stats + model•thinking)
  + `FooterDataProvider`
- B.1 `ExpandableText` header (compact/full, `app.tools.expand` toggle;
  folds the `hdr` status line into the footer)
- B.3 pending-messages queue display (dim `TruncatedText` lines + hint)
- B.2 loaded resources (dim title + lines between header and chat)
- B.4 status-indicator swap model + kinds (working/compaction/retry
  countdown/idle) + `setWorkingIndicator`/`setWorkingMessage`/`setWorkingVisible`
- B.8 first-run changelog (optional; needs `DynamicBorder`)

**Why last among B:** these build on the now-stable `tui/` layer and touch
`interactive.clj` heavily; the internal order is dependency-driven (B.6
unblocks B.1; B.5 needs Phase 5).

**Gate:** layout checklist from the §1 component map (top: blank, header,
blank, chat; bottom: pending → status → blank → editor → footer); editor
grows to 30% of height; footer shows cwd/branch/session/tokens/context%/
model; queued messages appear with the dequeue hint; compaction/retry show
dedicated indicators; `app.tools.expand` toggles the header. Automated
coverage: `test_footer` (two-line layout, home substitution, provider
prefix, thinking level, context % + auto indicator, extension statuses,
truncation, format-tokens/cwd), `test_footer_data_provider` (usage totals,
context tokens, cache hit rate, model/provider/thinking), `test_pending_messages`,
`test_loaded_resources`, `test_expandable_text`, `test_session` usage
helpers, and the `test_loop` compaction/retry event coverage. Manual
side-by-side with pi remains for the live layout feel (the gate's manual
pass).

### Phase 7 — Capstone: extension ctx.ui (B.7 + B.9)

**Status: DONE** — extension widgets (`set-widget`, above/below editor,
`MAX_WIDGET_LINES = 10` truncation) and the full `ExtensionUIContext` port
in `kmet.app.extensions` (ui-custom/select/confirm/input/editor/notify,
on-terminal-input, set-status, set-working-indicator/message/visible,
set-hidden-thinking-label, set-widget, set-footer, set-header, set-title,
set/get-editor-text, paste-to-editor, set-editor-component,
add-autocomplete-provider, get-theme/get-all-themes,
get/set-tools-expanded, reset!); `:session-start` event (startup/reload)
emitted by the interactive mode; dialogs hosted in the editor container
with DynamicBorder framing and IME focus propagation. The extension-editor
dialog's Ctrl+G external-editor support (pi: ExtensionEditorComponent
`app.editor.external`) landed here too: the dialog wires
`app.editor.external` to a handler that runs the same suspend-edit-resume
flow as the main editor against the dialog's own editor, the hint line
shows submit/newline/cancel/external-editor (pi's `tui.select.confirm` /
`tui.input.newLine` / `tui.select.cancel` / `app.editor.external` ids —
which also fixed the pre-existing empty-hint bug from literal
"enter"/"escape" ids), and the vestigial fixed `:height 8` was dropped
(the dialog editor uses the terminal-driven dynamic height like pi's
Editor default).

**Contents:** extension widgets (`register-widget!`/`unregister-widget!`,
`render-widgets!`, `MAX_WIDGET_LINES = 10` truncation) → the full
`ExtensionUIContext` port: `ui.custom(factory, {overlay?, overlayOptions?,
onHandle?})`, `ui.select/confirm/input/editor/notify` dialogs,
`ui.onTerminalInput`, `setStatus`/`setWorkingIndicator`/`setWidget`/
`setFooter`/`setHeader`/`setEditorComponent`/`setTitle`/`pasteToEditor`/
`setEditorText`/`getEditorText`/`addAutocompleteProvider`, theme access
(`ui.theme`, `getAllThemes`, `getTheme`, `setTheme`), `getToolsExpanded`/
`setToolsExpanded`, tool-side `select/confirm/input/notify`, and the
`resetExtensionUI` lifecycle (dispose everything on reload).

**Why last:** it is the payoff — everything it exposes already exists
underneath, so it becomes wiring rather than invention.

**Gate:** extension `ui.custom` overlay + non-overlay modes resolve
`done(value)` and restore the editor; dialogs position/focus like pi;
widgets appear above (with spacer) / below (no spacer) the editor;
`setFooter`/`setHeader` swap and restore; custom editor swap preserves text;
reload leaves no stale widgets/listeners.

### Master checklist

Run `bb run` side-by-side with pi after every phase. Items marked ✅ are
covered by the automated test suites (see the phase notes); the rest need
a manual side-by-side pass.

- [ ] Kitty protocol: `kitty-active?` true under kitty/ghostty/wezterm
      (parsing + negotiation ✅ tested — `test_keys`/`test_negotiation`;
      the manual pass covers the live terminal); key releases filtered
      unless `:wants-key-release?`; `alt+x`, `ctrl+shift+p`, F-keys parse
      identically to pi
- [ ] Paste: bracketed paste content arrives as one wrapped event
- [ ] IME: CJK candidate window at the hardware cursor in the editor;
      `set-show-hardware-cursor!` toggles visibility; dialogs hosting an
      Input propagate focus (candidate window follows) — propagation
      ✅ tested (`test_extension_dialogs`)
- [x] Overlays: anchor/margin/percentage placement identical to pi
      (`overlay-qa-tests` cases); focus reclaim after nested custom UI
      closes; `unfocus({target})`; hidden overlays don't capture input
      (✅ `test_overlay`)
- [ ] Resize: height change → full redraw (non-Termux); width change →
      full redraw; no flicker under Termux keyboard toggle
      (✅ logic: `test_terminal_response` covers force-render state reset;
      manual pass needed for live resize)
- [ ] Shrink: content shrink clears rows (default **off** like pi —
      `KMET_CLEAR_ON_SHRINK=1` enables; runtime `set-clear-on-shrink!`)
- [ ] Kitty images in mid-document growth: no stale pixels, no scroll
      artifacts (✅ id-extraction + reserved-rows tests in
      `test_terminal_image`/`test_terminal_response`; live terminal pass
      needed)
- [ ] Width overflow: `kmet-crash.log` written with all lines, TUI throws
      with `truncateToWidth` guidance (✅ parse/log helpers tested)
- [ ] Theme: missing-token error lists all missing colors; `set-theme`
      via extension re-themes live (components rebuild on invalidate);
      auto light/dark follows the terminal scheme report
      (✅ logic: accessors/fallback/validation/detection/controller tests in
      `test_theme` + `test_theme_controller`; live-terminal pass needed)
- [x] `KMET_TUI_WRITE_LOG` captures the raw ANSI stream
- [x] Custom editor swap preserves text, padding, autocomplete, actions
      (✅ `IEditorComponent` protocol dispatch + duck-typed fallback, §B.5)
- [x] SelectList `truncatePrimary` callback + wrap-around navigation,
      centered viewport, `onSelectionChange`, Input paste/kill-ring/undo/
      word-nav parity (✅ `test_select_list` / `test_input` / `test_editor`)
- [x] Extension `ui.custom` overlay + non-overlay modes resolve
      `done(value)` and restore the editor (✅ `test_extensions_ui` +
      registry integration)
- [ ] Phase 6 layout: header (welcome/ExpandableText) + loaded resources +
      chat + pending (queued msgs + bash) + status + widgets + editor +
      footer side-by-side with pi; `app.tools.expand` expands header +
      loaded resources + tool output together (✅ components/logic:
      `test_expandable_text`, `test_loaded_resources`, `test_pending_messages`,
      `test_footer`; the live pass needs a terminal)
- [ ] Footer stats reflect real usage: ↑in ↓out R/W CH from streamed usage
      (✅ `test_footer_data_provider`/`test_session` usage helpers; needs a
      live LLM call to observe real numbers)
- [ ] Alt+Enter queues a follow-up while streaming; Alt+Up restores queued
      messages to the editor (✅ logic in `interactive.clj`; manual pass)
- [ ] Retry countdown counts down during backoff; compaction shows its own
      indicator and Escape cancels it (✅ `test_loop` retry/compaction +
      cancellation events — `test-loop-compaction-cancelled`,
      `test-loop-compaction-refuses-when-active`; live pass needed)

## Decision record: unbounded vs windowed chat

**Stay unbounded** (pi parity). The chat renders all lines; the terminal's
own scrollback is the history; kmet's screen-row diff already handles
mid-document growth without full redraws. The `ScrollView` port stays for
modal/embedded uses only. Revisit only if scrollback fidelity becomes a
problem on a target platform.

## Related documents

- `alignment.md` — agent-loop and editor alignment (queues, events, retry,
  autocomplete, editor actions); B.3 depends on the queue work tracked
  there.
- `AGENTS.md` — conventions (records, protocols, `track!`, no raw ANSI
  outside `tui/`, layer boundaries, Termux-specific glibc notes).
