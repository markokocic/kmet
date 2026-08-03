# kmet ↔ pi TUI Layout Alignment (main chat screen)

Analysis of the main interactive chat screen layout and a staged plan to
align kmet with pi (`modes/interactive/interactive-mode.ts` +
`@earendil-works/pi-tui`).

Reference (pi source):
- Layout: `dist/modes/interactive/interactive-mode.js` — `setupUiLayout`
  (constructor `addChild` order), `renderWidgets`, `updatePendingMessagesDisplay`
- Containers: `node_modules/@earendil-works/pi-tui/dist/tui.js` — `Container`
  (pure passthrough group) and `TUI extends Container` (render = concat of
  child lines; no vertical layout — the terminal's scrollback is the history)

---

## Status

- **Level 1 — structural container hierarchy: DONE** (matches pi's addChild
  order exactly)
- **Level 2 — behavioral parity: PLANNED** (§2)
- **Full alignment — TUI-core parity: PLANNED** (§3)

---

## 1. Component map (after Level 1)

The TUI root renders children top-to-bottom. Both pi and kmet render the
chat **unbounded** — the terminal's own scrollback is the chat history
(swipe/mouse-wheel up shows earlier messages; no in-TUI scroll view).

| # | pi child | kmet child | Notes |
|---|----------|------------|-------|
| 1 | `headerContainer` `[Spacer(1), builtInHeader ExpandableText, Spacer(1)]` | `header-container` `[sp1, hdr Text, sp1]` | `hdr` is a single-line status (logo, model, session, status, cwd). Welcome/startup help lives in a chat info-message instead of the header. |
| 2 | `loadedResourcesContainer` | `loaded-resources-container` (empty) | Feature missing (see §2.2). |
| 3 | `chatContainer` | `chat-container` `[ch]` | `ch` = `ChatHistoryComponent`. Renders unbounded in both. |
| 4 | `pendingMessagesContainer` | `pending-messages-container` | kmet holds only running `!`/`!!` bash components. pi also shows steering/follow-up queue lines (§2.3). |
| 5 | `statusContainer` | `status-container` `[si]` | pi swaps whole indicator instances; kmet has one always-mounted `StatusIndicator` (§2.4). |
| 6 | `widgetContainerAbove` (default `Spacer(1)` when empty) | `widget-container-above` `[sp2]` | Extension widget API missing (§2.7). |
| 7 | `editorContainer` | `editor-container` `[ed]` | kmet fixed `:height 8`; pi dynamic 30% of rows, min 5 (§2.5). |
| 8 | `widgetContainerBelow` (empty) | `widget-container-below` (empty) | Extension widget API missing (§2.7). |
| 9 | `footer` (direct child) | `ftr` (direct child) | kmet: `─` separator + status + `msgs:N`. pi: cwd line + stats line (§2.6). |

Level 1 changes (interactive.clj only, ~30 lines):
- Replaced the single `dock` VStack with nine top-level children mirroring
  pi's `addChild` order; the spacers were re-homed to match pi:
  - `sp1` → inside `header-container` (blank line above and below header)
  - `sp2` → inside `widget-container-above` (pi's default spacer when no
    widgets are registered)
  - `sp3` → **removed** (pi has no gap between editor and footer; the
    editor's bottom border sits directly on the footer)
- Renamed the `CoreState` field `pending-bash-container` →
  `pending-messages-container`.
- No changes to `tui/` or `app/ui/` — `Container` is a layout-neutral
  passthrough in both codebases.

Rendering-model note: `stack/render-stack` has a dormant `IScrollView`
branch (viewport allocation + `render-window`). Nothing currently satisfies
`IScrollView` (the chat is a plain `ChatHistoryComponent`, `make-scroll-view`
is exported but unused), so `render-stack` degrades to plain concatenation —
the same unbounded model pi uses. See §3.1 if that ever changes.

---

## 2. Level 2 — behavioral parity (per-component plan)

### 2.1 Header: `ExpandableText` (pi: `builtInHeader`)

**pi:** `ExpandableText` with compact/expanded variants — logo +
keybinding hints + onboarding; toggled by `app.tools.expand` (expansion
state persists via `getStartupExpansionState`). Rendered inside
`headerContainer` with a spacer above/below.

**kmet:** single-line `hdr` Text (model/session/status/cwd — a status line,
not onboarding) + welcome/startup-help as a chat info-message
(`chat-history-set-info-msg!`).

**Plan:**
1. Port `ExpandableText` (compact/full render, expansion state atom) —
   either a new `tui/components/expandable_text.clj` or a thin stateful
   wrapper over `Text`.
2. Move the welcome/startup-help content (already built in `interactive.clj`
   `run` — `compact`/`full` strings with keybinding hints) out of the chat
   info-message into the header. Keep `chat-history-set-info-msg!` for
   `:info`-role extension events only.
3. Generate hints from the keybinding registry via `tui-kb/key-text`
   (kmet already has `app-kb/set-key-hint-theme-fns!`; pi's
   `keyHint(keybinding, description)` = `dim(keyText + description)`).
4. Until §2.6 lands, keep the `hdr` status line as a second header row;
   afterwards fold it into the footer (pi's header has no status line).

Files: `tui/components/expandable_text.clj` (new), `modes/interactive.clj`.

### 2.2 Loaded resources (pi: `renderLoadedResources`)

**pi:** `loadedResourcesContainer.clear()` then one section per loaded
resource group (title + content lines), rendered **before** the chat so
restored messages never precede resources.

**kmet:** `app/context.clj` already computes project context files
(`load-project-context-files`, fed to the system prompt); nothing is
rendered in `loaded-resources-container`.

**Plan:**
1. New `app/ui/loaded_resources.clj` — renders a section per resource
   group (dim title + dim lines, matching pi's styling).
2. On startup (and on context-file changes), rebuild
   `loaded-resources-container` and re-render; keep the container in the
   same addChild position (between header and chat).

Files: `app/ui/loaded_resources.clj` (new), `app/context.clj`,
`modes/interactive.clj`.

### 2.3 Pending messages: steering/follow-up queue display

**pi:** `updatePendingMessagesDisplay()` — when steering/follow-up queues
are non-empty: `Spacer(1)` + one dim `TruncatedText` per queued message
(`Steering: …` / `Follow-up: …`) + a hint line
(`↳ <key> to edit all queued messages`). Cleared when queues drain. The
same container also holds the running bash component (pi adds
`this.bashComponent` to it).

**kmet:** `pending-messages-container` holds bash components only.
`agent/steer!` exists, but queued messages are immediately added to the
chat (`chat-history-add-message!`) and no display list is kept.

**Plan:**
1. Track a display list of steered/follow-up texts on `CoreState`
   (mirror pi's `_steeringMessages`/`_followUpMessages`).
2. Port `update-pending-messages-display!` — clear + rebuild
   `pending-messages-container` with the queue lines (keep bash components
   appended after; pi removes the bash component from the same container on
   completion).
3. `app/message.dequeue` action already exists in kmet? (verify; pi shows
   the hint via `getAppKeyDisplay`). Wire the hint from the keybinding
   registry.

Files: `modes/interactive.clj`, `app/loop.clj` (queue metadata),
`app/ui/pending_messages.clj` (new, small).

### 2.4 Status indicator: swap model

**pi:** `showStatusIndicator(indicator)` / `clearStatusIndicator(kind)` —
`statusContainer.clear()` + `addChild(indicator)`. Indicator kinds:
`WorkingStatusIndicator` (spinner + message + elapsed time),
`CompactionStatusIndicator`, `RetryStatusIndicator` (countdown),
`IdleStatus` (only when `clearOnShrink`), `BranchSummaryStatusIndicator`.

**kmet:** one `StatusIndicator` mounted in `status-container`, started/
stopped with `status-indicator-start!`/`stop!`, text via
`status-indicator-set-text!`. Renders `["", ""]` when idle (same
stable-height trick as pi's `IdleStatus`).

**Plan:**
1. Add container swap helpers on `CoreState`
   (`show-status-indicator!` / `clear-status-indicator!`) that clear
   `status-container` and add the new instance.
2. Extend `StatusIndicator` with pi's kinds: compaction progress and retry
   countdown text (kmet already emits `:auto-retry-start`; compaction runs
   pre-turn). A `countdown` mode on the spinner (pi's `CountdownTimer`) is
   enough — a full kind hierarchy is optional.
3. Keep the stable two-row idle render so the editor/footer don't jump.

Files: `app/ui/status_indicator.clj`, `modes/interactive.clj`,
`tui/components/spinner.clj` (countdown support).

### 2.5 Editor: dynamic height

**pi:** `maxVisibleLines = Math.max(5, Math.floor(terminalRows * 0.3))`
recomputed on every render; scroll offset keeps the cursor visible;
scroll-border rows (`─── ↑ N more` / `─── ↓ N more`) when scrolled.

**kmet:** fixed `:height 8`; already takes `:terminal-rows` fn (used for
autocomplete max-visible) and already renders the ↑/↓ scroll borders.

**Plan:**
1. In `editor.clj` `render`, compute `max-visible` from the
   `:terminal-rows` fn (30%, min 5) instead of `height-atom`; keep
   `:height` as an explicit override.
2. Remove the now-unused `editor-set-height!` callers, if any.
3. Verify autocomplete max-visible stays consistent (pi uses the same 30%
   rule for the dropdown cap).

Files: `tui/components/editor.clj`, `modes/interactive.clj`.

### 2.6 Footer: pi's pwd + stats lines

**pi (`FooterComponent` + `FooterDataProvider`):** line 1 = cwd
(home-substituted, truncated) + git branch + session name; line 2 = stats
(`↑input ↓output Rcache Wcache`, context % with color) left, right-aligned
`(provider) model • thinking-level`. No separator line.

**kmet:** `─` separator + `kmet <status> msgs:N` (already uses the HStack
fixed-left/flex/right layout).

**Plan:**
1. Rework `app/ui/footer.clj` to two lines: pwd line + stats line
   (drop the separator — the editor's bottom border separates, as in pi).
2. Move model/session/cwd/status content out of `hdr` (§2.1.4) into the
   footer (right side = model/thinking like pi; left = status).
3. `FooterDataProvider` port: git branch + session name (via
   `babashka.fs`/`babashka.process`; watch Termux/Windows portability —
   AGENTS.md platform section), token totals from session entries, context
   % from the session token estimate (`session.clj` has entry counts; a
   token estimate may need `llm.clj`-side accounting).
4. Keep the `FooterDataProvider.onBranchChange`-style update path
   (atom + footer refresh).

Files: `app/ui/footer.clj`, `app/ui/footer_data_provider.clj` (new),
`modes/interactive.clj`, `app/session.clj`.

### 2.7 Extension widgets above/below the editor

**pi:** `extensionWidgetsAbove`/`extensionWidgetsBelow` maps; `renderWidgets()`
→ `renderWidgetContainer(container, widgets, spacerWhenEmpty, leadingSpacer)`:
above = leading `Spacer(1)` + widgets (or bare spacer when empty); below =
widgets only (no spacer when empty). Extensions register widgets via the
extension context.

**kmet:** `app/extensions.clj` has no widget API.

**Plan:**
1. Add `register-widget!`/`unregister-widget!` (keyed maps, pi-style) to
   `app/extensions.clj` or a new `app/ui/widgets.clj`.
2. Port `render-widgets!` and the two `render-widget-container` rules
   (spacer-when-empty for above; none for below).
3. Wire `widget-container-above`/`widget-container-below` to rebuild on
   widget-registry changes (event-bus event).
4. Extension widgets get theme access + `request-render` (pi passes the
   TUI for theme access).

Files: `app/extensions.clj`, `app/ui/widgets.clj` (new),
`modes/interactive.clj`.

### 2.8 chatContainer extras (optional parity)

**pi:** on first run, `chatContainer` gets `Spacer(1)` + `DynamicBorder` +
"What's New" changelog (Markdown) + `Spacer(1)` + `DynamicBorder` before
the messages.

**kmet:** no first-run changelog. Optional: render a changelog info-message
at chat start (kmet already has the info-msg mechanism — this is nearly
free once §2.1 moves the welcome out).

---

## 3. Full alignment — TUI-core parity

### 3.1 Container-aware `render-stack` (only needed if the chat becomes a ScrollView)

`stack/render-stack` finds the single `IScrollView` among **top-level**
children and allocates it `height − fixed`. With the chat now nested inside
`chat-container`, a nested scroll view would be invisible to it (and the
branch is currently dormant anyway — nothing implements `IScrollView`).

If the windowed model is ever activated (see §3.3), `render-stack` must
become container-aware:

1. `find-scroll-view`: recurse through `Container` children to locate the
   `IScrollView` (special-case the `Container` record; there is no generic
   children introspection on `IComponent`).
2. Two-pass render: first render the containing entry with the scroll view
   rendered naturally (for `fixed` measurement → `viewport` →
   `update-layout!`), then re-render substituting `render-window` lines in
   place of the natural block (`render-with-substitution`, splicing at the
   entry level; containers concatenate children in order, so the block is
   contiguous).
3. Watch out for double-rendering side effects: chat render is pure
   (message atoms read-only), but verify before relying on it.

### 3.2 Remaining pi TUI-core features

| Feature | pi | kmet | Gap |
|---------|----|------|-----|
| Frame pacing | `MIN_RENDER_INTERVAL_MS = 16` | 16ms sleep in render loop | ✅ |
| Diff rendering | differential compare + sync output (CSI 2026) | screen-row diff, single StringBuilder write + CSI 2026 | ✅ (kmet's scrollback-aware screen-row diff is equivalent) |
| `clearOnShrink` | env `PI_CLEAR_ON_SHRINK` clears rows when content shrinks | not implemented | minor |
| Terminal color scheme | OSC 11 background query (theme-aware text colors) | not implemented | minor |
| Kitty images | pre-clear + scroll handling in diff loop | `tui/terminal_image.clj` exists; diff-loop integration partial | verify |
| Hardware cursor | cursor marker + IME candidate placement | `CURSOR-MARKER` in `tui/utils.clj` | ✅ (verify parity) |
| Overlays | `overlayStack` + focus restore | `tui-show-overlay` exists | ✅ |
| Scrollbar | `ScrollView` auto scrollbar (`markScrollbarActivity`, transient) | ported in `scroll_view.clj` but dormant (no user) | see §3.3 |

### 3.3 Decision record: unbounded vs windowed chat

- **Current: unbounded** (pi parity). Chat renders all lines; the terminal
  scrolls its own buffer; kmet's screen-row diff already handles
  mid-document growth without full redraws (viewport-top tracking +
  scroll-aware cursor moves).
- **Windowed alternative:** kmet's `ScrollView` port (follow-end, scrollbar
  state machine, windowing) exists but nothing uses it. Activating it would
  bound total output to the screen (helpful on terminals with fragile
  scrollback, e.g. Termux) but diverges from pi's model and requires §3.1.
- **Recommendation:** stay unbounded (pi parity); keep the `ScrollView`
  port for modal/embedded uses. Revisit only if scrollback fidelity
  becomes a problem on a target platform.

---

## 4. Suggested order and verification

Order (quick wins first):
1. §2.5 editor dynamic height (small, self-contained)
2. §2.3 pending queue display (small, requires queue metadata)
3. §2.4 status indicator swap (medium)
4. §2.6 footer parity (medium; unblocks §2.1)
5. §2.1 ExpandableText header (medium; consumes the footer move)
6. §2.2 loaded resources (feature-level)
7. §2.7 extension widgets (feature-level)
8. §2.8 changelog (optional, trivial once §2.1 lands)

Verification checklist (run `bb run` side-by-side with pi):
- [ ] Top: blank line, header, blank line, then chat (no gap between the
      header block and chat differs from pi's spacer-only structure)
- [ ] Bottom, top→bottom: pending/queue lines → status indicator →
      blank → editor (top border) → footer (no blank between editor and
      footer)
- [ ] Editor grows to 30% of terminal height (min 5 lines) and shows
      `↑ N more` / `↓ N more` borders when scrolled
- [ ] Footer shows cwd + branch + session on line 1, tokens + context % +
      model/thinking on line 2
- [ ] Queued follow-up/steered messages appear as dim lines above the
      status indicator with the dequeue hint
- [ ] Compaction/retry phases show a progress/countdown indicator instead
      of the plain spinner
- [ ] Extension widgets appear above the editor (with leading spacer) and
      below it (no spacer)
- [ ] Loaded resources render between header and chat
- [ ] `app.tools.expand` toggles the header between compact and full
      startup help

---

## 5. Related documents

- `alignment.md` — agent-loop and editor alignment (queues, events,
  retry, autocomplete, editor actions); §2.3/§2.4 of this document depend
  on the queue/event work tracked there.
- `AGENTS.md` — conventions (records, protocols, `track!`, no raw ANSI
  outside `tui/`, layer boundaries: `app/ui` may import `tui` only).
