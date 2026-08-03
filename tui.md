# kmet ↔ pi TUI Alignment (100% parity plan)

Analysis of pi's TUI architecture (pi source: `~/src/cvstree/pi/packages/tui/` +
`packages/coding-agent/src/modes/interactive/interactive-mode.ts` and
`.../theme/`) and a staged plan to bring kmet's TUI to full parity:

- **Part A — TUI-core parity**: `kmet.tui.*` vs `@earendil-works/pi-tui`
  (render loop, input pipeline, focusable/IME, overlays, theme, components).
- **Part B — app-level usage parity**: `modes/interactive.clj` +
  `app/ui/*` vs `interactive-mode.ts` (layout, dialogs, extension ctx.ui).

Status of the chat-screen layout (previously "Level 1") is DONE; the
**overlay/focus layer (Phase 3), the extension ctx.ui surface (Phase 7),
and the write-log + listener-chaining + Loader-indicator + DynamicBorder
items are DONE** (see the per-phase notes below); the remaining phases are
planned below.

---

## Parity matrix (pi-tui module ↔ kmet file)

| pi module (`packages/tui/src`) | kmet | Status |
|--------------------------------|------|--------|
| `tui.ts` — TUI/Container/Component/Focusable, render loop, overlays, IME cursor | `tui/core.clj`, `tui/components/container.clj` | ✅ overlays + focus-restore state machine done (§A.3, §A.2); render-loop items open (§A.4) |
| `keys.ts` — key parsing (legacy + Kitty), `matchesKey`, event types | `tui/keys.clj` | **gap**: no Kitty parsing, no alt+ESC (§A.1) |
| `terminal.ts` — ProcessTerminal, Kitty protocol negotiation, write log, progress, drainInput | `tui/terminal.clj` | **gap**: negotiation, drain, title/progress; write log + `set-title!` done (§A.1, §A.5) |
| `stdin-buffer.ts` — batch splitting, paste re-wrap | (inline in `core.clj` input reader) | **gap**: no batch splitting (§A.1) |
| `native-modifiers.ts` | — | missing (Apple Terminal Shift+Enter) (§A.1) |
| `utils.ts` — visibleWidth/truncateToWidth/wrapTextWithAnsi/sliceByColumn/extractSegments | `tui/utils.clj` | ✅ (verify `extractSegments`/`normalizeTerminalOutput` for §A.3) |
| `keybindings.ts` — KeybindingsManager, keyText, conflicts | `tui/keybindings.clj` | ✅ (verify conflict reporting + `key-text`) |
| `autocomplete.ts` | `tui/autocomplete.clj` | ✅ (extension-provider delegation added with §B.9) |
| `fuzzy.ts` | `tui/fuzzy.clj` | ✅ |
| `terminal-colors.ts` — OSC 11, color scheme report | — | missing (§A.4, §A.6) |
| `terminal-image.ts` | `libs/terminal_image.clj` | ✅ (diff-loop integration partial, §A.4) |
| `editor-component.ts` — EditorComponent interface | — | missing (§A.7); custom-editor swap uses duck-typing like pi (§B.5 done) |
| `kill-ring.ts` / `undo-stack.ts` / `word-navigation.ts` | inside `editor.clj` | ✅ for Editor; **gap**: Input lacks them (§A.7) |
| `components/text|box|spacer|truncated-text.ts` | `tui/components/*` | ✅ |
| `components/input.ts` | `tui/components/input.clj` | gap: kill-ring/undo/paste parity (§A.7) |
| `components/editor.ts` | `tui/components/editor.clj` | ✅ (verify against §A.7 list) |
| `components/markdown.ts` | `tui/components/markdown.clj` | ✅ |
| `components/select-list.ts` | `tui/components/select_list.clj` | gap: layout options (§A.7) |
| `components/settings-list.ts` | `tui/components/settings_list.clj` | ✅ |
| `components/loader.ts` + `cancellable-loader.ts` | `tui/components/spinner.clj` + `cancellable_loader.clj` | ✅ `set-indicator!` done (verbatim frames, empty-frames hide, nil restores) |
| `components/image.ts` | `tui/components/image.clj` | ✅ (cell-size query, §A.4) |
| coding-agent `theme/theme.ts` + `theme-controller.ts` | `tui/theme.clj` + `config.clj` | gap: controller, validation, auto light/dark (§A.6) |

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
  schedules an immediate full render.
- Full redraw triggers: first render; width change; **height change
  (except Termux** — keyboard show/hide would replay history);
  `clearOnShrink` when `newLines < maxLinesRendered` and no overlays
  (env `PI_CLEAR_ON_SHRINK`, default **on**, plus runtime setter).
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
   full render next frame).
2. Height-change handling: full redraw when height changes and not Termux
   (`TERMUX_VERSION` env check), matching pi — verify against kmet's
   screen-diff behavior first (kmet may already handle resize correctly;
   keep whichever matches pi's observable behavior).
3. `clearOnShrink` (`KMET_CLEAR_ON_SHRINK` env, default on, runtime
   setter) using a `max-lines-rendered` high-water mark; skip when
   overlays are active.
4. Kitty image diff integration: port `expandChangedRangeForKittyImages` /
   `deleteChangedKittyImages` / reserved-row pre-clear into both diff
   paths; `collectKittyImageIds` + delete on full redraw.
5. Crash log + width-overflow throw (write to `kmet-crash.log` in cwd or
   agent dir); `KMET_DEBUG_REDRAW=1` and `KMET_TUI_DEBUG=1` equivalents.
6. Cell size query on start (`\x1b[16t`) when image capabilities
   detected; parse `\x1b[6;h;wt` response; `set-cell-dimensions!` +
   `invalidate` all + request render.
7. OSC 11 background query + `CSI ?996n` color scheme report + `?2031h/l`
   notifications; consume responses in `dispatch-input!` before listeners
   (port `consumeOsc11BackgroundResponse`, `consumeTerminalColorSchemeReport`).

## A.5 Terminal (`tui/terminal.clj`)

Covered by A.1 (negotiation, drain, write log) and A.4 (title, progress).
Remaining small items: `clear-from-cursor!`, `move-by!`, `clear-screen!`
exists. Keep the JLine wrapper — no need to switch to raw stdin.

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
   substitution).
2. Validation parity: when a theme is missing tokens, report the sorted
   list of missing required colors (pi's `Missing required color tokens`)
   instead of the generic warning.
3. **ThemeController port** (`app/theme_controller.clj` or in
   `config.clj`): auto light/dark via the A.4 color-scheme detection;
   `set-theme-name!`/`set-theme-instance!`; on change call
   `tui-invalidate-all` (children + overlays) + `tui-request-render`
   (force). File watching on `source-path` (Theme already carries it).
4. Component contract: audit components that pre-bake theme strings
   (Text children created with `theme/fg` in `app/ui/*`) — they must
   rebuild in `invalidate()` (pi's "rebuild on invalidate" pattern). The
   `track!` cache handles atom-driven renders; themed-string holders do
   not — add an audit checklist to the verification section.

## A.7 Components

| Component | pi | kmet | Work |
|-----------|----|------|------|
| Text / Box / Container / Spacer / TruncatedText | ✅ | ✅ | — |
| Input | kill-ring, undo stack, yank/yank-pop, paste buffer, word navigation | minimal (value/cursor/submit/escape) | port kill-ring/undo/word-nav/paste into `input.clj` (reuse editor.clj internals) |
| Editor | full | ✅ comprehensive | verify: undo/redo, kill ring, paste, autocomplete, scroll borders, dynamic height (see B.5) |
| EditorComponent interface | `editor-component.ts` | — | add `IEditorComponent` protocol (set-text/get-text/get-expanded-text, on-submit/on-change, action-handlers map, on-escape/on-ctrl-d/on-paste-image, border-color, set-padding-x, set-autocomplete-provider) — needed by B.9 `set-editor-component` |
| Markdown | ✅ | ✅ | — |
| SelectList | `SelectListLayoutOptions` (min/maxPrimaryColumnWidth + `truncatePrimary` callback) | min/max column bounds ✅ | add `truncatePrimary` callback (context: text/maxWidth/columnWidth/item/isSelected) |
| SettingsList | ✅ | ✅ | — |
| Loader | `setIndicator({frames, intervalMs})`, verbatim mode, `render()` = `["", ...text]` | spinner (no set-indicator!) | add `spinner-set-indicator!` (frames/interval/verbatim) matching pi Loader semantics |
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
  `undefined`. Requires the `IEditorComponent` protocol (A.7).
Files: `tui/components/editor.clj`, `app/ui/editor_component.clj` (new),
`modes/interactive.clj`.

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
Files: `app/extensions.clj`, `app/ui/widgets.clj` (new),
`modes/interactive.clj`.

## B.8 Changelog (optional)

First-run `Spacer + DynamicBorder + "What's New" Markdown + Spacer +
DynamicBorder` in the chat container. Requires `DynamicBorder`
(`app/ui/dynamic_border.clj`, pi `dynamic-border.ts` — top/bottom border
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

**Status: PARTIAL** — the input-listener chaining item (`{consume, data}`
transforms, §A.2.4) is DONE (ported into `dispatch-input!`); the key
parsing, `input_buffer.clj`, and protocol negotiation are open.

**Contents:** `keys.clj` full parsing (Kitty `\x1b[k;m;…u`, `27;m;c~`,
alternate keys, event types, `alt+x` from ESC prefix, F-keys) →
`tui/input_buffer.clj` (new; stdin batch splitting + paste re-wrap) →
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

**Status: PARTIAL** — `KMET_TUI_WRITE_LOG` (write log) and `set-title!`
are DONE; the rest is open.

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

**Status: PARTIAL** — Loader `set-indicator!` (verbatim frames, interval,
empty-frames hide, nil restores) and `DynamicBorder` are DONE; SelectList
`truncatePrimary`, Input kill-ring/undo/word-nav parity, and the
`IEditorComponent` protocol remain (the custom-editor swap uses pi's
duck-typing instead — see §B.5).

**Contents:** Loader `setIndicator!` (frames/interval/verbatim) → SelectList
`truncatePrimary` callback → Input kill-ring/undo/word-nav/paste parity
(reuse `editor.clj` internals) → `IEditorComponent` protocol
(`app/ui/editor_component.clj`, new).

**Why here:** all independent; small effort; `IEditorComponent` must land
before B.5.

**Gate:** `bb test` for each component; `setIndicator!` frames/interval/
verbatim match pi; Input editing feels identical (yank/yank-pop, undo).

### Phase 6 — App behavior (B.5 → B.6 → B.1 → B.3 → B.2 → B.4 → B.8)

**Status: PARTIAL** — B.5 custom-editor swap (duck-typed, preserving text/
padding/border/autocomplete/actions), B.4 `setWorkingIndicator`/
`setWorkingMessage`/`setWorkingVisible`, B.6 footer extension statuses
(keyed `setStatus`), and B.8's `DynamicBorder` are DONE; the two-line
footer, `ExpandableText` header, pending-messages display, loaded-resources
layer, and the full status-indicator swap model are open.

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
dedicated indicators; `app.tools.expand` toggles the header.

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
with DynamicBorder framing and IME focus propagation. Remaining: `set-theme`
(deferred to Phase 4's ThemeController) and the extension-editor dialog's
Ctrl+G external-editor support (kmet's dialog omits it).

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

- [ ] Kitty protocol: `kitty-active?` true under kitty/ghostty/wezterm;
      key releases filtered unless `:wants-key-release?`; `alt+x`,
      `ctrl+shift+p`, F-keys parse identically to pi
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
- [ ] Shrink: content shrink clears rows (default on), disabled via env
- [ ] Kitty images in mid-document growth: no stale pixels, no scroll
      artifacts (image pre-clear path)
- [ ] Width overflow: `kmet-crash.log` written with all lines, TUI throws
      with `truncateToWidth` guidance
- [ ] Theme: missing-token error lists all missing colors; `set-theme`
      via extension re-themes live (components rebuild on invalidate);
      auto light/dark follows the terminal scheme report
- [x] `KMET_TUI_WRITE_LOG` captures the raw ANSI stream
- [x] Custom editor swap preserves text, padding, autocomplete, actions
      (✅ duck-typed transfer, §B.5)
- [x] Extension `ui.custom` overlay + non-overlay modes resolve
      `done(value)` and restore the editor (✅ `test_extensions_ui` +
      registry integration)

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
