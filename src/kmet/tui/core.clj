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
            [kmet.tui.components.editor :as editor])
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
          (tui-set-focus tui last))))))

(defn tui-has-overlay? [tui] (pos? (count @(:overlays tui))))

(declare tui-request-render tui-stop)

;; ═══════════════════════════════════════════════════════════════════════════
;; Diff
;; ═══════════════════════════════════════════════════════════════════════════

(defn- diff-lines [prev next]
  (let [n (max (count prev) (count next))]
    (loop [i 0, r []]
      (if (>= i n) r
          (let [a (get prev i "") b (get next i "")]
            (if (= a b) (recur (inc i) r)
                (recur (inc i) (conj r (str "\u001b[" (inc i) "H\u001b[2K" b)))))))))

;; ═══════════════════════════════════════════════════════════════════════════
;; Input reader
;; ═══════════════════════════════════════════════════════════════════════════

(defn- start-input-reader [tui]
  (let [jline (.terminal (:terminal tui))]
    (future
      (let [reader (.reader jline)]
        (while (and @(:running? tui) (not @(:stopped? tui)))
          (try (let [ch (.read reader)]
                 (when (>= ch 0)
                   (let [data (str (char ch))]
                     (doseq [l @(:input-listeners tui)] (l data))
                     (if-let [fc @(:focused-component tui)]
                       (handle-input fc data)
                       (when-let [c (first @(:components tui))]
                         (handle-input c data)))
                     (tui-request-render tui))))
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
        jline (.terminal term)]
    (terminal/start! term (fn [_] nil) (fn [] (tui-request-render tui)))
    (terminal/hide-cursor! term)
    (terminal/write-output term "\u001b[2J\u001b[H")
    (reset! (:running? tui) true)
    (reset! (:stopped? tui) false)
    (start-input-reader tui)
    (loop []
      (when @(:running? tui)
        (let [w (.getWidth jline)
              h (.getHeight jline)]
          (when @(:render-requested? tui)
            (reset! (:render-requested? tui) false)
            (let [lines (vec (mapcat #(render % w) @(:components tui)))
                  prev @(:previous-lines tui)]
              (when (not= prev lines)
                (let [d (diff-lines prev lines)]
                  (when (seq d)
                    (terminal/write-output term CSI-2026-H)
                    (doseq [x d] (terminal/write-output term x))
                    (terminal/write-output term CSI-2026-L)
                    (let [cr (min (count lines) (dec h))]
                      (terminal/write-output term (str "\u001b[" (max 1 (inc cr)) "H")))))
                (reset! (:previous-lines tui) lines)
                (reset! (:previous-width tui) w)))))
        (Thread/sleep 33)
        (recur)))
    (reset! (:running? tui) false)
    (terminal/show-cursor! term)
    (terminal/stop! term)))

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
