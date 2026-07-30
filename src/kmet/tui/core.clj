(ns kmet.tui.core
  "TUI - Main class for managing terminal UI with differential rendering.
   Primary entry point for kmet.tui — all public symbols are re-exported
   from this namespace for convenience."
  (:require [kmet.tui.protocols :as protocols]
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
            [kmet.tui.components.spinner :as spinner])
  ;; Protocols are re-exported into this namespace so that implementors
  ;; can do (ns ... (:require [kmet.tui.core :as tui]) ... (tui/IComponent ...))
  (:refer-clojure :exclude [render]))

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

(defrecord Overlay [component x y width height focused?])

;; ═══════════════════════════════════════════════════════════════════════════
;; Cursor marker
;; ═══════════════════════════════════════════════════════════════════════════

(def ^:const CURSOR-MARKER "\u001b_pi:c\u0007")

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
          (if-let [marker-idx (clojure.string/index-of line CURSOR-MARKER)]
            (let [before (subs line 0 marker-idx)
                  after (subs line (+ marker-idx (count CURSOR-MARKER)))
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
                running? stopped? overlays])

(defn create-tui [terminal]
  (map->TUI {:terminal terminal
             :components (atom [])
             :focused-component (atom nil)
             :input-listeners (atom [])
             :previous-lines (atom [])
             :previous-width (atom 0)
             :render-requested? (atom false)
             :running? (atom false)
             :stopped? (atom false)
             :overlays (atom [])}))

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

(declare tui-request-render tui-stop)

;; ═══════════════════════════════════════════════════════════════════════════
;; Overlays
;; ═══════════════════════════════════════════════════════════════════════════

(defn tui-show-overlay [tui component & {:keys [x y width height]}]
  (let [o (map->Overlay {:component component :x x :y y
                          :width width :height height :focused? true})]
    (swap! (:overlays tui) conj o)
    (tui-set-focus tui component)
    o))

(defn tui-hide-overlay [tui]
  (when-let [o (peek @(:overlays tui))]
    (swap! (:overlays tui) pop)
    (when (:focused? o)
      (if-let [next-o (:component (peek @(:overlays tui)))]
        (tui-set-focus tui next-o)
        (when-let [last (last @(:components tui))]
          (tui-set-focus tui last))))
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

(defn- diff-lines
  "Generate differential ANSI sequences using relative cursor movement.
   prev and next must be same-length vectors.
   cursor-content-row is the current cursor position as a content row.
   Returns [seqs last-content-row] where last-content-row is the content
   row of the last emitted change."
  [prev next cursor-content-row]
  (let [n (count prev)]
    (loop [i 0, r [], last-content-row cursor-content-row]
      (if (>= i n)
        [r last-content-row]
        (let [a (nth prev i "") b (nth next i "")]
          (if (= a b)
            (recur (inc i) r last-content-row)
            (let [delta (- i last-content-row)
                  move (cond
                         (pos? delta) (str "\u001b[" delta "B")
                         (neg? delta) (str "\u001b[" (- delta) "A")
                         :else "")]
              (recur (inc i) (conj r (str move "\r\u001b[2K" b)) i))))))))

;; ═══════════════════════════════════════════════════════════════════════════
;; Input reader
;; ═══════════════════════════════════════════════════════════════════════════

(def ^:private PASTE-START "\u001b[200~")
(def ^:private PASTE-END "\u001b[201~")

(def ^:private MAX-ESC-WAIT 30)
(def ^:private ESC-WAIT-STEP 3)

(defn- dispatch-input!
  "Dispatch a complete input sequence to listeners and focused component."
  [tui data]
  (doseq [l @(:input-listeners tui)] (l data))
  (if-let [fc @(:focused-component tui)]
    (handle-input fc data)
    (when-let [c (first @(:components tui))]
      (handle-input c data)))
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

      ;; Starts with ESC — try to complete sequence
      (= (first s) \u001b)
      (loop [waited 0]
        (let [current @buf]
          (if-let [key (keys/parse-key current)]
            (do (reset! buf "")
                (dispatch-input! tui current))
            (if (and (< waited MAX-ESC-WAIT)
                     (keys/escape-prefix? current)
                     (< (count current) 12))
              (if (.ready reader)
                (let [ch (.read reader)]
                  (when (>= ch 0)
                    (swap! buf str (char ch)))
                  (recur 0))
                (do (Thread/sleep ESC-WAIT-STEP)
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
  (let [jline (.terminal (:terminal tui))]
    (future
      (let [reader (.reader jline)
            buf (atom "")]
        (while (and @(:running? tui) (not @(:stopped? tui)))
          (try (let [ch (.read reader)]
                 (when (>= ch 0)
                   (swap! buf str (char ch))
                   (process-input-buffer! tui reader buf)))
               (catch Exception e
                 (when @(:running? tui)
                   (binding [*out* *err*] (println "input:" (.getMessage e)))))))))))

;; ═══════════════════════════════════════════════════════════════════════════
;; Start / Stop
;; ═══════════════════════════════════════════════════════════════════════════

(defn tui-request-render [tui]
  (reset! (:render-requested? tui) true))

(defn tui-stop [tui]
  (reset! (:stopped? tui) true)
  (reset! (:running? tui) false))

(defn tui-start
  "Start TUI render loop. Blocks the calling thread."
  [tui]
  (let [term (:terminal tui)
        jline (.terminal term)
        started (terminal/start! term (fn [_] nil) (fn [] (tui-request-render tui)))
        hardware-cursor-row (atom 0)
        viewport-top (atom 0)
        show-hardware-cursor? (= (System/getenv "PI_HARDWARE_CURSOR") "1")]
    (terminal/hide-cursor! started)
    ;; Pi: no clear-screen on start — preserves prior terminal output above the TUI
    (reset! (:running? tui) true)
    (reset! (:stopped? tui) false)
    (start-input-reader tui)
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
                                (vec (mapcat #(render % w) @(:components tui))))
                    cursor-result (extract-cursor-position raw-lines h)
                    cursor (:cursor cursor-result)
                    lines (:lines cursor-result)
                    lines (pad-lines-to-width lines w)
                    prev @(:previous-lines tui)
                    prev-w @(:previous-width tui)
                    prev-count (count prev)
                    new-count (count lines)
                    width-changed (and (pos? prev-w) (not= prev-w w))
                    ;; Find first index where a and b differ; returns count(a) if identical
                    first-changed (fn first-changed [a b]
                                    (let [n (count a)]
                                      (loop [i 0]
                                        (if (or (= i n) (not= (nth a i) (nth b i)))
                                          i
                                          (recur (inc i))))))
                    ;; Pi-style full redraw: clear screen, rewrite all lines from top
                    do-full-redraw (fn do-full-redraw []
                                     (when (seq prev)
                                       (terminal/write-output started "\u001b[2J\u001b[H"))
                                     (terminal/write-output started CSI-2026-H)
                                     (doseq [i (range new-count)]
                                       (when (pos? i) (terminal/write-output started "\r\n"))
                                       (terminal/write-output started (nth lines i)))
                                     (terminal/write-output started CSI-2026-L)
                                     (reset! hardware-cursor-row (dec new-count))
                                     (reset! viewport-top (max 0 (- new-count h))))]
              (cond
                ;; Full redraw: first render or width change
                (or (empty? prev) width-changed)
                (do-full-redraw)

                ;; Content grew — full redraw if common part changed above viewport,
                ;; else diff common part then append with \r\n for scrollback
                (> new-count prev-count)
                (let [common-prev (subvec prev 0 prev-count)
                      common-new (subvec lines 0 prev-count)
                      old-vt @viewport-top
                      fc (first-changed common-prev common-new)]
                  (if (< fc old-vt)
                    ;; Changes above old viewport — Pi: can't incrementally update scrollback
                    (do-full-redraw)
                    (do
                      (terminal/write-output started CSI-2026-H)
                      ;; Diff the common part (relative movement from current cursor)
                      (let [[d _] (diff-lines common-prev common-new @hardware-cursor-row)]
                        (doseq [x d] (terminal/write-output started x)))
                      ;; Append new lines with \r\n
                      (let [last-row (min prev-count h)]
                        (terminal/write-output started (str "\u001b[" last-row "H"))
                        (terminal/write-output started "\r\n")
                        (doseq [i (range prev-count new-count)]
                          (when (> i prev-count)
                            (terminal/write-output started "\r\n"))
                          (terminal/write-output started (str "\u001b[2K" (nth lines i)))))
                      (terminal/write-output started CSI-2026-L)
                      (reset! hardware-cursor-row (dec new-count))
                      (reset! viewport-top (max 0 (- new-count h))))))

                ;; Content shrunk — full redraw if viewport shifted or changes above viewport,
                ;; else diff common part then clear removed lines
                (< new-count prev-count)
                (let [new-vt (max 0 (- new-count h))]
                  (if (not= new-vt @viewport-top)
                    ;; Viewport changed — Pi: can't map old screen rows to new
                    (do-full-redraw)
                    (let [common-prev (subvec prev 0 new-count)
                          fc (first-changed common-prev lines)]
                      (if (< fc @viewport-top)
                        ;; Changes above viewport — Pi: full redraw
                        (do-full-redraw)
                        ;; Viewport stable, changes within viewport
                        (let [[d _] (diff-lines common-prev lines @hardware-cursor-row)]
                          (terminal/write-output started CSI-2026-H)
                          (doseq [x d] (terminal/write-output started x))
                          (let [extra (- prev-count new-count)
                                clear-row (inc new-count)]
                            (when (<= clear-row h)
                              (terminal/write-output started (str "\u001b[" clear-row "H"))
                              (let [lines-to-clear (min extra (- (inc h) clear-row))]
                                (dotimes [e lines-to-clear]
                                  (terminal/write-output started "\u001b[2K")
                                  (when (< e (dec lines-to-clear))
                                    (terminal/write-output started "\u001b[1B"))))))
                          (terminal/write-output started CSI-2026-L)
                          (reset! hardware-cursor-row (dec new-count)))))))

                ;; Same length — full redraw if changes above viewport,
                ;; else differential with relative cursor movement
                (not= prev lines)
                (let [fc (first-changed prev lines)]
                  (if (< fc @viewport-top)
                    ;; Changes above viewport — Pi: full redraw
                    (do-full-redraw)
                    (let [[d last-content-row] (diff-lines prev lines @hardware-cursor-row)]
                      (when (seq d)
                        (terminal/write-output started CSI-2026-H)
                        (doseq [x d] (terminal/write-output started x))
                        (terminal/write-output started CSI-2026-L)
                        (reset! hardware-cursor-row last-content-row)))))

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
                    (terminal/write-output started buf))
                  (reset! hardware-cursor-row target-row)
                  (if show-hardware-cursor?
                    (terminal/show-cursor! started)
                    (terminal/hide-cursor! started)))
                (terminal/hide-cursor! started))
              (reset! (:previous-lines tui) lines)
              (reset! (:previous-width tui) w))))
        (Thread/sleep 16)
        (recur)))
      (finally
        (reset! (:running? tui) false)
        ;; Pi: position cursor at end of content so shell prompt appears below,
        ;; and user can scroll up to review the session
        (let [prev-lines @(:previous-lines tui)]
          (when (seq prev-lines)
            (let [target-row (count prev-lines)  ;; row past last content line
                  row-delta (- target-row @hardware-cursor-row)]
              (terminal/write-output started " ")
              (when (pos? row-delta)
                (terminal/write-output started (str "\u001b[" row-delta "B")))
              (terminal/write-output started "\r\n"))))
        (terminal/show-cursor! started)
        (terminal/stop! started)))))

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
(def editor-set-on-tab! editor/editor-set-on-tab!)
(def editor-push-history! editor/editor-push-history!)
(def editor-get-history editor/editor-get-history)
(def editor-set-history! editor/editor-set-history!)
(def editor-get-paste editor/editor-get-paste)
(def editor-set-height! editor/editor-set-height!)
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

