(ns kmet.tui.core
  "TUI - Main class for managing terminal UI with differential rendering.
   Primary entry point for kmet.tui — all public symbols are re-exported
   from this namespace for convenience."
  (:require [clojure.string :as str]
            [kmet.tui.protocols :as protocols]
            [kmet.tui.terminal :as terminal]
            [kmet.tui.keys :as keys]
            [kmet.tui.utils :as utils]
            [kmet.tui.components.text :as text]
            [kmet.tui.components.spacer :as spacer]
            [kmet.tui.components.container :as container]
            [kmet.tui.components.box :as box]
            [kmet.tui.components.input :as input]
            [kmet.tui.components.editor :as editor]
            [kmet.tui.components.select-list :as select-list]
            [kmet.tui.components.settings-list :as settings-list]
            [kmet.tui.components.markdown :as markdown]
            [kmet.tui.components.spinner :as spinner]
            [kmet.tui.components.scroll-view :as scroll-view]
            [kmet.tui.components.stack :as stack]
            ;; pi-parity components (previously stubs — now implemented)
            [kmet.tui.components.alt-screen-flash :as alt-screen-flash]
            [kmet.tui.components.cancellable-loader :as cancellable-loader]
            [kmet.tui.components.truncated-text :as truncated-text]
            [kmet.tui.components.h-stack :as h-stack]
            [kmet.tui.components.v-stack :as v-stack]))

;; ═══════════════════════════════════════════════════════════════════════════
;; Protocol re-exports
;; ═══════════════════════════════════════════════════════════════════════════

(def IComponent protocols/IComponent)
(def IFocusable protocols/IFocusable)
(def render protocols/render)
(def handle-input protocols/handle-input)
(def invalidate protocols/invalidate)
(def focused protocols/focused)
(def set-focused! protocols/set-focused!)

;; ═══════════════════════════════════════════════════════════════════════════
;; Overlay
;; ═══════════════════════════════════════════════════════════════════════════

(defrecord Overlay [component x y width height focused? previous-focus])

(defn- extract-cursor-position
  "Find CURSOR-MARKER in rendered lines (viewport only), strip it from output,
   and return {:lines cleared-lines :cursor {:row r :col c}}.
   Returns {:lines original-lines :cursor nil} when no marker found.
   Only scans the bottom `height` lines (visible viewport), matching Pi's approach."
  [lines height]
  (let [viewport-top (max 0 (- (count lines) height))]
    (loop [i (dec (count lines))]
      (if (>= i viewport-top)
        (let [line (nth lines i)]
          (if-let [marker-idx (clojure.string/index-of line utils/CURSOR-MARKER)]
            (let [before (subs line 0 marker-idx)
                  after (subs line (+ marker-idx (count utils/CURSOR-MARKER)))
                  col (utils/visible-width before)
                  new-line (str before after)
                  new-lines (assoc lines i new-line)]
              {:lines new-lines :cursor {:row i :col col}})
            (recur (dec i))))
        {:lines lines :cursor nil}))))

;; ═══════════════════════════════════════════════════════════════════════════
;; CSI 2026 sync
;; ═══════════════════════════════════════════════════════════════════════════

(def CSI-2026-H "\u001b[?2026h")
(def CSI-2026-L "\u001b[?2026l")

;; ═══════════════════════════════════════════════════════════════════════════
;; TUI
;; ═══════════════════════════════════════════════════════════════════════════

(defrecord TUI [terminal components focused-component
                input-listeners previous-lines
                previous-width render-requested?
                running? stopped? overlays
                render-loop input-reader current-reader
                flashes])

(declare tui-request-render tui-stop)

(defn create-tui [terminal]
  (let [tui (map->TUI {:terminal (atom terminal)
                       :components (atom [])
                       :focused-component (atom nil)
                       :input-listeners (atom [])
                       :previous-lines (atom [])
                       :previous-width (atom 0)
                       :render-requested? (atom false)
                       :running? (atom false)
                       :stopped? (atom false)
                       :overlays (atom [])
                       :render-loop (atom nil)
                       :input-reader (atom nil)
                       :current-reader (atom nil)
                       :flashes (atom nil)})]
    ;; AltScreenFlashContainer owned by the TUI (pi: TuiAltScreen owns its
    ;; flash container) — the render loop composites flash lines over the
    ;; screen window; tui-flash! / tui-flash-dispose! are the public API.
    (reset! (:flashes tui)
            (alt-screen-flash/make-alt-screen-flash #(tui-request-render tui)))
    tui))

(defn tui-add-child [tui c] (swap! (:components tui) conj c))
(defn tui-remove-child [tui c]
  (swap! (:components tui) (fn [v] (vec (remove #(identical? % c) v)))))
(defn tui-clear [tui] (reset! (:components tui) []))

(defn tui-set-focus [tui component]
  (when-let [prev @(:focused-component tui)]
    (when (satisfies? IFocusable prev) (set-focused! prev false)))
  (reset! (:focused-component tui) component)
  (when (satisfies? IFocusable component) (set-focused! component true))
  nil)

(defn tui-add-input-listener [tui f]
  (swap! (:input-listeners tui) conj f))
(defn tui-remove-input-listener [tui f]
  (swap! (:input-listeners tui) (fn [v] (vec (remove #(= % f) v)))))

;; ─── Flashes (pi: TuiAltScreen.flash — transient messages) ─────────────────

(defn tui-flash!
  "Show a transient inverse-video message over the bottom of the screen for
   DURATION-MS (default 1000), then remove it (pi: flash)."
  [tui message & {:keys [duration-ms]}]
  (alt-screen-flash/alt-screen-flash! @(:flashes tui) message :duration-ms duration-ms))

(defn tui-flash-dispose!
  "Clear all pending flashes immediately."
  [tui]
  (alt-screen-flash/alt-screen-flash-dispose! @(:flashes tui)))

;; ═══════════════════════════════════════════════════════════════════════════
;; Overlays
;; ═══════════════════════════════════════════════════════════════════════════

(defn tui-show-overlay [tui component & {:keys [x y width height]}]
  ;; Capture the pre-overlay focus so hiding restores it (pi: overlayFocusRestore)
  (let [o (map->Overlay {:component component :x x :y y
                         :width width :height height :focused? true
                         :previous-focus @(:focused-component tui)})]
    (swap! (:overlays tui) conj o)
    (tui-set-focus tui component)
    o))

(defn tui-hide-overlay [tui]
  (when-let [o (peek @(:overlays tui))]
    (swap! (:overlays tui) pop)
    (when (:focused? o)
      (if-let [next-o (:component (peek @(:overlays tui)))]
        ;; An overlay below exists — it owns focus now
        (tui-set-focus tui next-o)
        ;; Back at the base layout: restore the component focused before
        ;; the overlay was shown (pi: restore the saved focus target).
        ;; Only restore when it is still a live component (pi:
        ;; isComponentMounted); otherwise fall back to the last component.
        (let [prev (:previous-focus o)]
          (if (and prev (some #(identical? prev %) @(:components tui)))
            (tui-set-focus tui prev)
            (when-let [last (last @(:components tui))]
              (tui-set-focus tui last))))))
    (tui-request-render tui)))

(defn tui-has-overlay? [tui] (pos? (count @(:overlays tui))))

;; ═══════════════════════════════════════════════════════════════════════════
;; Diff
;; ═══════════════════════════════════════════════════════════════════════════

(defn- pad-lines-to-width
  "Ensure all lines are exactly width columns wide."
  [lines width]
  (mapv (fn [line]
          (let [vis (utils/visible-width line)]
            (if (>= vis width)
              line
              (str line (apply str (repeat (- width vis) \space))))))
        lines))

(defn- composite-flashes
  "Overlay transient flash lines (AltScreenFlashContainer) onto the screen
   window — the bottom HEIGHT content rows — right-aligned, one flash per
   row (pi: compositeFlashes). Takes the LAST HEIGHT flash lines so a flash
   flood stays on-screen (pi: .slice(-height)). Returns LINES unchanged
   when nothing flashes."
  [flashes lines width height]
  (let [flash-lines (protocols/render flashes width)
        flash-lines (if (> (count flash-lines) height)
                      (vec (take-last height flash-lines))
                      flash-lines)
        n (count flash-lines)
        top (max 0 (- (count lines) height))]
    (if (zero? n)
      lines
      (loop [row 0, lines lines]
        (if (>= row n)
          lines
          (let [line (nth flash-lines row)
                fw (utils/visible-width line)]
            (if (pos? fw)
              (let [idx (+ top row)
                    out (utils/composite-line (if (< idx (count lines)) (nth lines idx) "")
                                              line (- width fw) fw width)]
                (recur (inc row)
                       (if (< idx (count lines))
                         (assoc lines idx out)
                         (conj lines out))))
              (recur (inc row) lines))))))))

;; ═══════════════════════════════════════════════════════════════════════════
;; Input reader
;; ═══════════════════════════════════════════════════════════════════════════

(def ^:private PASTE-START "\u001b[200~")
(def ^:private PASTE-END "\u001b[201~")

(def ^:private MAX-ESC-WAIT 30)
(def ^:private ESC-WAIT-STEP 3)

(defn- dispatch-input!
  "Dispatch a complete input sequence to listeners and the focused component."
  [tui data]
  (doseq [l @(:input-listeners tui)] (l data))
  (when-let [fc @(:focused-component tui)]
    ;; pi: input goes only to the focused leaf; key release events are
    ;; filtered unless the component opts in via a :wants-key-release?
    ;; field (pi: Component.wantsKeyRelease)
    (when (or (not (keys/is-key-release? data))
              (:wants-key-release? fc))
      (handle-input fc data)))
  (tui-request-render tui))

(defn- process-input-buffer!
  "Process buffered input. Tries to complete ESC sequences with
   brief waits, then dispatches the first complete sequence."
  [tui reader buf]
  (let [s @buf]
    (cond
      ;; Paste markers — dispatch immediately
      (and (>= (count s) 6)
           (or (clojure.string/includes? s PASTE-START)
               (clojure.string/includes? s PASTE-END)))
      (let [marker (if (clojure.string/includes? s PASTE-START) PASTE-START PASTE-END)
            idx (clojure.string/index-of s marker)
            before (subs s 0 idx)]
        (reset! buf (or (when (seq before) before) ""))
        (dispatch-input! tui marker))

      ;; Starts with ESC — try to complete sequence. A lone ESC is NOT
      ;; dispatched yet: it may be the prefix of an arrow/CSI/SS3 sequence,
      ;; so we wait up to MAX-ESC-WAIT for the rest before treating it as
      ;; the Escape key (pi: isCompleteSequence returns "incomplete" for a
      ;; single ESC). Without this, "\u001b[A" arrived as ESC then "[A" as
      ;; raw text — arrows and ctrl+arrows never parsed.
      (= (first s) \u001b)
      (loop [waited 0]
        (let [current @buf]
          (if (and (keys/parse-key current)
                   (not= current "\u001b"))
            (do (reset! buf "")
                (dispatch-input! tui current))
            (if (and (< waited MAX-ESC-WAIT)
                     (or (keys/escape-prefix? current) (= current "\u001b"))
                     (< (count current) 12))
              ;; Timed non-blocking read: JLine's NonBlockingReader.read(ms)
              ;; returns the char, -1 on EOF, -2 on timeout. .ready() is not
              ;; reliable here (returned false while data was pending), so the
              ;; old .ready + .read combo starved the accumulation loop.
              (let [ch (.read reader ESC-WAIT-STEP)]
                (if (>= ch 0)
                  (do (swap! buf str (char ch))
                      (recur 0))
                  (recur (+ waited ESC-WAIT-STEP))))
              ;; Timeout or invalid — dispatch first char, keep rest
              (let [first-char (subs current 0 1)
                    rest (subs current 1)]
                (reset! buf rest)
                (dispatch-input! tui first-char))))))

      ;; Non-ESC — dispatch immediately (single char)
      :else
      (let [first-char (subs s 0 1)
            rest (subs s 1)]
        (reset! buf rest)
        (dispatch-input! tui first-char)))))

(defn- start-input-reader [tui]
  (let [jline (.terminal @(:terminal tui))
        reader (.reader jline)]
    ;; Track the current reader so a stale reader (from a suspended TUI
    ;; session) exits as soon as a fresh reader is installed by resume.
    (reset! (:current-reader tui) reader)
    (let [f (future
              (let [buf (atom "")]
                (while (and @(:running? tui) (not @(:stopped? tui))
                            (identical? reader @(:current-reader tui)))
                  (try (let [ch (.read reader)]
                         (when (>= ch 0)
                           (swap! buf str (char ch))
                           (process-input-buffer! tui reader buf)))
                       (catch Exception e
                         (when (and @(:running? tui)
                                    (identical? reader @(:current-reader tui)))
                           (binding [*out* *err*] (println "input:" (ex-message e)))))))))]
      (reset! (:input-reader tui) f))))

;; ═══════════════════════════════════════════════════════════════════════════
;; Start / Stop
;; ═══════════════════════════════════════════════════════════════════════════

(defn tui-request-render [tui]
  (reset! (:render-requested? tui) true))

(defn tui-stop [tui]
  (reset! (:stopped? tui) true)
  (reset! (:running? tui) false)
  ;; pi: TuiAltScreen.dispose() clears pending flashes on close
  (tui-flash-dispose! tui))

(defn- run-render-loop!
  "Start the terminal (raw mode) and run the render loop until the TUI is
   suspended (tui-suspend!) or stopped (tui-stop). Restores and closes the
   terminal on exit; tui-resume! creates a fresh one."
  [tui]
  (let [started (terminal/start! @(:terminal tui)
                                 (fn [_] nil)
                                 (fn [] (tui-request-render tui)))
        jline (.terminal started)
        hardware-cursor-row (atom 0)
        viewport-top (atom 0)
        show-hardware-cursor? (= (System/getenv "KMET_HARDWARE_CURSOR") "1")]
    (terminal/hide-cursor! started)
    ;; Pi: no clear-screen on start — preserves prior terminal output above the TUI
    (tui-request-render tui)
    (try
      (loop []
        (when @(:running? tui)
          (let [w (.getWidth jline)
                h (.getHeight jline)]
            (when @(:render-requested? tui)
              (reset! (:render-requested? tui) false)
              (let [overlays @(:overlays tui)
                    raw-lines (if (seq overlays)
                                ;; Render top overlay component
                                (let [o (peek overlays)
                                      ow (or (:width o) w)
                                      ox (or (:x o) 0)
                                      oy (or (:y o) 0)
                                      comp-lines (vec (render (:component o) ow))]
                                  (vec (concat
                                        (repeat oy "")
                                        (mapv #(str (apply str (repeat ox " ")) %) comp-lines)
                                        (repeat (max 0 (- h oy (count comp-lines))) ""))))
                                ;; Main content: the stack layout bounds the ScrollView
                                ;; (chat) to the space left by the fixed components, so
                                ;; the total never exceeds the screen and mid-document
                                ;; growth doesn't trigger full-screen redraws.
                                (stack/render-stack @(:components tui) w h
                                                    #(tui-request-render tui)))
                    cursor-result (extract-cursor-position raw-lines h)
                    cursor (:cursor cursor-result)
                    lines (:lines cursor-result)
                    lines (pad-lines-to-width lines w)
                    lines (composite-flashes @(:flashes tui) lines w h)
                    prev @(:previous-lines tui)
                    prev-w @(:previous-width tui)
                    prev-count (count prev)
                    new-count (count lines)
                    width-changed (and (pos? prev-w) (not= prev-w w))
                    ;; One write per frame: the whole frame's output (sync markers,
                    ;; cursor moves, line rewrites, cursor hide) accumulates into SB
                    ;; and is written+flushed once. Flushing per diff entry made the
                    ;; terminal paint each erase+rewrite separately — visible flicker
                    ;; at 60fps, especially on terminals without CSI 2026 sync
                    ;; support (e.g. Termux).
                    sb (StringBuilder.)
                    emit! (fn [s] (.append sb s))
                    old-vt @viewport-top
                    new-vt (max 0 (- new-count h))
                    scroll (- new-vt old-vt)
                    ;; Pi-style full redraw: clear screen, home, then clear
                    ;; scrollback ([3J) so stale lines above don't show as
                    ;; duplicates (pi: fullRender uses "\u001b[2J\u001b[H\u001b[3J").
                    do-full-redraw (fn do-full-redraw []
                                     (when (seq prev)
                                       (emit! "\u001b[2J\u001b[H\u001b[3J"))
                                     (emit! CSI-2026-H)
                                     (doseq [i (range new-count)]
                                       (when (pos? i) (emit! "\r\n"))
                                       (emit! (nth lines i)))
                                     (emit! CSI-2026-L)
                                     (reset! hardware-cursor-row (dec new-count))
                                     (reset! viewport-top new-vt))
                    ;; Screen-row diff. The terminal screen shows the last h rows of
                    ;; the buffer, which holds every content row written so far — so
                    ;; the "current" screen content is old rows [old-vt .. old-vt+h-1]
                    ;; ("" beyond prev-count). When the viewport moved down (content
                    ;; grew), the rows entering the viewport were already written in
                    ;; earlier frames, so scrolling the screen down exposes them
                    ;; without a full redraw — the terminal scrolls its own buffer.
                    ;; This keeps the screen stable while mid-document boxes (streaming
                    ;; bash/tool output) grow or shift above the viewport: only the
                    ;; screen rows that actually changed are rewritten in place.
                    ;; Content-row diffs can't do this — a one-line insert above the
                    ;; viewport shifts every content row below it, so the old code
                    ;; fell back to "\u001b[2J\u001b[H\u001b[3J" + full repaint every
                    ;; chunk, which flashes on terminals without CSI 2026 sync
                    ;; support (e.g. Termux).
                    do-screen-diff (fn do-screen-diff []
                                     (let [cursor-row (atom (- @hardware-cursor-row old-vt))]
                                       (when (pos? scroll)
                                         (let [bottom (dec h)]
                                           (when (< @cursor-row bottom)
                                             (emit! (str "\u001b[" (- bottom @cursor-row) "B")))
                                           (reset! cursor-row bottom)
                                           (emit! (apply str (repeat scroll "\r\n")))))
                                       (loop [r 0]
                                         (if (>= r h)
                                           (reset! hardware-cursor-row (+ @cursor-row new-vt))
                                           (let [idx (+ new-vt r)
                                                 cur (if (< idx prev-count) (nth prev idx) "")
                                                 exp (if (< idx new-count) (nth lines idx) "")]
                                             (if (= cur exp)
                                               (recur (inc r))
                                               (let [delta (- r @cursor-row)
                                                     move (cond
                                                            (pos? delta) (str "\u001b[" delta "B")
                                                            (neg? delta) (str "\u001b[" (- delta) "A")
                                                            :else "")
                                                     ;; Rows beyond the new content must be cleared:
                                                     ;; a bare "" would leave the old text visible.
                                                     out (if (empty? exp)
                                                           (apply str (repeat w \space))
                                                           exp)]
                                                 (emit! (str move "\r" out))
                                                 (reset! cursor-row r)
                                                 (recur (inc r)))))))))]
                (cond
                ;; Full redraw: first render, width change, viewport moved up (content
                ;; shrank), or content first overflowing the screen (crossing into
                ;; scrollback can't be done incrementally).
                  (or (empty? prev) width-changed
                      (< new-vt old-vt)
                      (and (pos? scroll) (< prev-count h)))
                  (do-full-redraw)

                ;; Everything else: scroll the screen (if the viewport moved down) and
                ;; rewrite only the screen rows that changed.
                  (or (pos? scroll) (not= prev lines))
                  (let [before (.length sb)]
                    (emit! CSI-2026-H)
                    (do-screen-diff)
                    (when (> (.length sb) before)
                      (emit! CSI-2026-L))
                    (reset! viewport-top new-vt))

                ;; No changes
                  :else nil)
              ;; Position hardware cursor (Pi-style: relative movement from tracked position)
              ;; Both hardware-cursor-row and cursor :row are content rows (0-indexed).
              ;; Since viewport is stable during cursor positioning, content-row delta
              ;; equals screen-row delta.
                (if cursor
                  (let [target-row (min (:row cursor) (dec new-count))
                        target-col (min (:col cursor) (dec w))
                        row-delta (- target-row @hardware-cursor-row)
                        buf (str (cond
                                   (pos? row-delta) (str "\u001b[" row-delta "B")
                                   (neg? row-delta) (str "\u001b[" (- row-delta) "A")
                                   :else "")
                                 "\u001b[" (inc target-col) "G")]
                    (when (seq buf)
                      (emit! buf))
                    (reset! hardware-cursor-row target-row)
                    (if show-hardware-cursor?
                      (emit! "\u001b[?25h")
                      (emit! "\u001b[?25l")))
                  (emit! "\u001b[?25l"))
              ;; Single write + flush per frame (no-op frames write nothing)
                (when (pos? (.length sb))
                  (terminal/write-output started (str sb)))
                (reset! (:previous-lines tui) lines)
                (reset! (:previous-width tui) w))))
          (Thread/sleep 16)
          (recur)))
      (finally
        (reset! (:running? tui) false)
        ;; Pi: on final stop position the cursor at end of content so the
        ;; shell prompt appears below and the user can scroll up to review
        ;; the session. On suspend the position is left untouched for the
        ;; external program that takes over the terminal.
        (when @(:stopped? tui)
          (let [prev-lines @(:previous-lines tui)]
            (when (seq prev-lines)
              (let [target-row (count prev-lines)  ;; row past last content line
                    row-delta (- target-row @hardware-cursor-row)]
                (terminal/write-output started " ")
                (when (pos? row-delta)
                  (terminal/write-output started (str "\u001b[" row-delta "B")))
                (terminal/write-output started "\r\n")))))
        (terminal/show-cursor! started)
        (terminal/stop! started)))))

;; ─── Suspend / Resume ──────────────────────────────────────────────────────

(defn tui-start
  "Start the TUI: enter raw mode, start the input reader and render loop.
   Blocks the calling thread until tui-stop is called."
  [tui]
  (reset! (:running? tui) true)
  (reset! (:stopped? tui) false)
  (start-input-reader tui)
  (reset! (:render-loop tui) (future (run-render-loop! tui)))
  (tui-request-render tui)
  ;; Block until a final stop is requested
  (loop []
    (when-not @(:stopped? tui)
      (Thread/sleep 50)
      (recur)))
  ;; Final stop: join both loops (the render loop's finally restores the
  ;; terminal; closing it unblocks the input reader so it exits too)
  (reset! (:running? tui) false)
  (when-let [f @(:render-loop tui)]
    (deref f 3000 nil))
  (reset! (:render-loop tui) nil)
  (when-let [f @(:input-reader tui)]
    (deref f 3000 nil))
  (reset! (:input-reader tui) nil)
  nil)

(defn tui-suspend!
  "Suspend the TUI: stop the render loop and input reader, restore the
   terminal to its normal (non-raw) state, and close it so a spawned
   external program (e.g. $EDITOR) has exclusive access to the terminal.
   Call tui-resume! to restart. May be called from the input reader thread
   (the thread that drives the external program afterwards)."
  [tui]
  (reset! (:running? tui) false)
  ;; The render loop's finally restores and closes the terminal, which also
  ;; unblocks any blocked input read. Wait for it to finish.
  (when-let [f @(:render-loop tui)]
    (deref f 3000 nil))
  (reset! (:render-loop tui) nil)
  nil)

(defn tui-resume!
  "Restart the TUI after tui-suspend!: create a fresh terminal, re-enter raw
   mode, and restart the render loop and input reader. Forces a full redraw."
  [tui]
  (reset! (:terminal tui) (terminal/create-terminal))
  (reset! (:running? tui) true)
  (reset! (:stopped? tui) false)
  ;; Empty previous-lines forces a full redraw on the fresh terminal
  (reset! (:previous-lines tui) [])
  (reset! (:previous-width tui) 0)
  (start-input-reader tui)
  (reset! (:render-loop tui) (future (run-render-loop! tui)))
  (tui-request-render tui)
  nil)

;; ═══════════════════════════════════════════════════════════════════════════
;; Re-exports — convenience aliases for all public symbols.
;; ═══════════════════════════════════════════════════════════════════════════

;; Terminal
(def ITerminal terminal/ITerminal)
(def create-terminal terminal/create-terminal)

;; Keys
(def matches-key? keys/matches-key?)
(def parse-key keys/parse-key)
(def is-key-release? keys/is-key-release?)
(def is-key-repeat? keys/is-key-repeat?)
(def set-kitty-active! keys/set-kitty-active!)
(def kitty-active? keys/kitty-active?)
(def KEY-UP keys/KEY-UP)
(def KEY-DOWN keys/KEY-DOWN)
(def KEY-LEFT keys/KEY-LEFT)
(def KEY-RIGHT keys/KEY-RIGHT)
(def KEY-ENTER keys/KEY-ENTER)
(def KEY-ESC keys/KEY-ESC)
(def KEY-TAB keys/KEY-TAB)
(def KEY-BACKSPACE keys/KEY-BACKSPACE)
(def KEY-DELETE keys/KEY-DELETE)
(def KEY-HOME keys/KEY-HOME)
(def KEY-END keys/KEY-END)
(def KEY-PAGE-UP keys/KEY-PAGE-UP)
(def KEY-PAGE-DOWN keys/KEY-PAGE-DOWN)
(def KEY-SPACE keys/KEY-SPACE)
(def KEY-INSERT keys/KEY-INSERT)
(def ctrl keys/ctrl)
(def shift keys/shift)
(def alt keys/alt)

;; Utils
(def visible-width utils/visible-width)
(def truncate-to-width utils/truncate-to-width)
(def wrap-text-with-ansi utils/wrap-text-with-ansi)
(def apply-background-to-line utils/apply-background-to-line)
(def strip-ansi-codes utils/strip-ansi-codes)
(def sgr utils/sgr)
(def slice-by-column utils/slice-by-column)

;; Components
(def make-text text/make-text)
(def text-set! text/text-set!)
(def make-spacer spacer/make-spacer)
(def make-container container/make-container)
(def container-add-child container/container-add-child)
(def container-remove-child container/container-remove-child)
(def container-clear container/container-clear)
(def make-box box/make-box)
(def box-add-child box/box-add-child)
(def box-remove-child box/box-remove-child)
(def box-clear box/box-clear)
(def box-set-bg-fn box/box-set-bg-fn)
(def make-input input/make-input)
(def input-set-value! input/input-set-value!)
(def input-get-value input/input-get-value)
(def input-set-on-submit! input/input-set-on-submit!)
(def input-set-on-escape! input/input-set-on-escape!)
(def make-editor editor/make-editor)
(def editor-set-text! editor/editor-set-text!)
(def editor-get-text editor/editor-get-text)
(def editor-set-on-submit! editor/editor-set-on-submit!)
(def editor-set-on-change! editor/editor-set-on-change!)
(def editor-set-on-action! editor/editor-set-on-action!)
(def editor-get-expanded-text editor/editor-get-expanded-text)
(def editor-set-on-tab! editor/editor-set-on-tab!)
(def editor-set-autocomplete-provider! editor/editor-set-autocomplete-provider!)
(def editor-set-autocomplete-max-visible! editor/editor-set-autocomplete-max-visible!)
(def editor-set-autocomplete-theme! editor/editor-set-autocomplete-theme!)
(def editor-autocomplete-active? editor/editor-autocomplete-active?)
(def editor-push-history! editor/editor-push-history!)
(def editor-get-history editor/editor-get-history)
(def editor-set-history! editor/editor-set-history!)
(def editor-get-paste editor/editor-get-paste)
(def editor-set-height! editor/editor-set-height!)
(def editor-set-terminal-rows! editor/editor-set-terminal-rows!)
(def editor-get-text-length editor/editor-get-text-length)

;; SelectList
(def make-select-list select-list/make-select-list)
(def select-list-set-items! select-list/select-list-set-items!)
(def select-list-get-selected select-list/select-list-get-selected)
(def select-list-set-theme! select-list/select-list-set-theme!)
(def default-theme select-list/default-theme)

;; SettingsList
(def make-settings-list settings-list/make-settings-list)
(def settings-list-set-on-escape! settings-list/settings-list-set-on-escape!)
(def settings-list-get-item settings-list/settings-list-get-item)
(def settings-list-set-value! settings-list/settings-list-set-value!)

;; Markdown
(def make-markdown markdown/make-markdown)
(def markdown-set-text! markdown/markdown-set-text!)
(def markdown-append! markdown/markdown-append!)
(def markdown-set-theme! markdown/markdown-set-theme!)
(def markdown-set-padding-x! markdown/markdown-set-padding-x!)
(def markdown-get-text markdown/markdown-get-text)

;; Spinner
(def make-spinner spinner/make-spinner)
(def spinner-start! spinner/spinner-start!)
(def spinner-set-start! spinner/spinner-set-start!)
(def spinner-stop! spinner/spinner-stop!)
(def spinner-active? spinner/spinner-active?)
(def spinner-set-text! spinner/spinner-set-text!)
(def spinner-set-prefix! spinner/spinner-set-prefix!)
(def spinner-set-spinner-color-fn! spinner/spinner-set-spinner-color-fn!)
(def spinner-set-message-color-fn! spinner/spinner-set-message-color-fn!)

;; ScrollView + stack layout
(def IScrollView scroll-view/IScrollView)
(def make-scroll-view scroll-view/make-scroll-view)
(def scroll-view-update-layout! scroll-view/update-layout!)
(def scroll-view-render-window scroll-view/render-window)
(def scroll-view-scroll-by! scroll-view/scroll-by!)
(def scroll-view-scroll-to! scroll-view/scroll-to!)
(def scroll-view-scroll-to-start! scroll-view/scroll-to-start!)
(def scroll-view-scroll-to-end! scroll-view/scroll-to-end!)
(def scroll-view-scroll-top scroll-view/scroll-top)
(def scroll-view-follows-end? scroll-view/follows-end?)
(def render-stack stack/render-stack)

;; AltScreenFlash — transient messages composited over the screen bottom
(def make-alt-screen-flash alt-screen-flash/make-alt-screen-flash)
(def alt-screen-flash! alt-screen-flash/alt-screen-flash!)
(def alt-screen-flash-dispose! alt-screen-flash/alt-screen-flash-dispose!)

;; CancellableLoader — Loader cancellable with Escape (pi: BorderedLoader)
(def make-cancellable-loader cancellable-loader/make-cancellable-loader)
(def cancellable-loader-aborted? cancellable-loader/cancellable-loader-aborted?)
(def cancellable-loader-signal cancellable-loader/cancellable-loader-signal)
(def cancellable-loader-set-on-abort! cancellable-loader/cancellable-loader-set-on-abort!)
(def cancellable-loader-dispose! cancellable-loader/cancellable-loader-dispose!)

;; TruncatedText — single-line truncating text
(def make-truncated-text truncated-text/make-truncated-text)
(def truncated-text-set-text! truncated-text/truncated-text-set-text!)

;; HStack / VStack — flexbox-style horizontal / vertical stacks
(def make-h-stack h-stack/make-h-stack)
(def h-stack-add-child! h-stack/h-stack-add-child!)
(def h-stack-remove-child! h-stack/h-stack-remove-child!)
(def h-stack-clear! h-stack/h-stack-clear!)
(def h-stack-set-gap! h-stack/h-stack-set-gap!)
(def h-stack-set-align! h-stack/h-stack-set-align!)
(def make-v-stack v-stack/make-v-stack)
(def v-stack-add-child! v-stack/v-stack-add-child!)
(def v-stack-remove-child! v-stack/v-stack-remove-child!)
(def v-stack-clear! v-stack/v-stack-clear!)
(def v-stack-set-gap! v-stack/v-stack-set-gap!)

;; Stack sizing (pi: allocateStackSizes) — shared by HStack/VStack
(def stack-entry? stack/stack-entry?)
(def entry-component stack/entry-component)
(def visible-stack-entries stack/visible-stack-entries)
(def allocate-stack-sizes stack/allocate-stack-sizes)

