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
            )
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
  "Find CURSOR-MARKER in rendered lines, strip it from output,
   and return {:lines cleared-lines :cursor {:row r :col c}}.
   Returns {:lines original-lines :cursor nil} when no marker found."
  [lines]
  (loop [i (dec (count lines))]
    (if (>= i 0)
      (let [line (nth lines i)]
        (if-let [marker-idx (clojure.string/index-of line CURSOR-MARKER)]
          (let [before (subs line 0 marker-idx)
                after (subs line (+ marker-idx (count CURSOR-MARKER)))
                col (utils/visible-width before)
                new-line (str before after)
                new-lines (assoc lines i new-line)]
            {:lines new-lines :cursor {:row i :col col}})
          (recur (dec i))))
      {:lines lines :cursor nil})))

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

(defn- clip-lines-to-height
  "Pad or truncate lines to exactly h lines (terminal height).
   Pads with empty lines at the top when shorter, truncates from
   the top when longer (keeping bottom h lines).
   Adjusts cursor position when truncating."
  [lines h w cursor]
  (let [n (count lines)
        empty-line (apply str (repeat w \space))]
    (cond
      (= n h) [lines cursor]
      (< n h) (let [padded (into (vec (repeat (- h n) empty-line)) lines)]
                [padded cursor])
      :else (let [offset (- n h)
                  clipped (subvec lines offset n)
                  new-cursor (when cursor
                               (let [new-row (- (:row cursor) offset)]
                                 (when (>= new-row 0)
                                   (assoc cursor :row new-row))))]
              [clipped new-cursor]))))

(defn- diff-lines [prev next]
  (let [n (count prev)] ;; prev and next are always same length (padded to height)
    (loop [i 0, r []]
      (if (>= i n) r
          (let [a (nth prev i "") b (nth next i "")]
            (if (= a b) (recur (inc i) r)
                (recur (inc i) (conj r (str "\u001b[" (inc i) "H\u001b[2K" b)))))))))

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
        max-lines-rendered (atom 0)]
    (terminal/hide-cursor! started)
    (terminal/write-output started "\u001b[2J\u001b[H")
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
                    cursor-result (extract-cursor-position raw-lines)
                    cursor (:cursor cursor-result)
                    lines (:lines cursor-result)
                    lines (pad-lines-to-width lines w)
                    ;; Normalize to exactly terminal height — avoids scroll/position bugs
                    [lines cursor] (clip-lines-to-height lines h w cursor)
                    prev @(:previous-lines tui)
                    prev-w @(:previous-width tui)
                    prev-count (count prev)
                    size-mismatch (and (pos? prev-count)
                                       (not= prev-count (count lines)))
                    width-changed (and (pos? prev-w) (not= prev-w w))]
              ;; Full redraw: first render, size mismatch (resize/scroll), or width change
              (if (or (empty? prev) size-mismatch width-changed)
                (do
                  (when (seq prev)
                    (terminal/write-output started "\u001b[2J\u001b[H"))
                  (terminal/write-output started CSI-2026-H)
                  (doseq [i (range (count lines))]
                    (when (pos? i) (terminal/write-output started "\r\n"))
                    (terminal/write-output started (nth lines i)))
                  (terminal/write-output started CSI-2026-L)
                  (reset! max-lines-rendered (count lines)))
                ;; Differential render
                (when (not= prev lines)
                  (let [d (diff-lines prev lines)]
                    (when (seq d)
                      (terminal/write-output started CSI-2026-H)
                      (doseq [x d] (terminal/write-output started x))
                      (terminal/write-output started CSI-2026-L)))))
              ;; Position hardware cursor — lines is now guaranteed ≤ h
              (if cursor
                (let [cr (min (:row cursor) (dec h))
                      cc (min (:col cursor) (dec w))]
                  (terminal/write-output started (str "\u001b[" (inc cr) "H\u001b[" (inc cc) "G"))
                  (terminal/show-cursor! started))
                (do
                  ;; Cursor past end of content: move to last content line
                  (let [last-row (min (count lines) h)]
                    (terminal/write-output started (str "\u001b[" (max 1 last-row) "H")))
                  (terminal/hide-cursor! started)))
              (reset! (:previous-lines tui) lines)
              (reset! (:previous-width tui) w)
              (when (> (count lines) @max-lines-rendered)
                (reset! max-lines-rendered (count lines))))))
        (Thread/sleep 33)
        (recur)))
      (finally
        (reset! (:running? tui) false)
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

