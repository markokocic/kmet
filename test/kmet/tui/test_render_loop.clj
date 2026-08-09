(ns kmet.tui.test-render-loop
  "Render-loop tests over a virtual terminal (a forced-dumb JLine terminal,
   80x24, every write recorded). Covers the full-redraw clearing contract:

   - a full redraw re-emits the whole transcript, so it must clear the
     scrollback too (\\u001b[3J) or the re-emit appends after the old
     history and duplicates it (pi issue #6050);
   - the first render writes without clearing, preserving prior terminal
     output above the TUI;
   - forced renders — what tui-resume! (external editor) and Ctrl+L now
     do — take the clearing full-redraw path;
   - the mid-diff fallback (a line changing above the viewport) clears as
     well.

   The render loop is driven headlessly through the private
   run-render-loop!, mirroring pi's VirtualTerminal-based render tests."
  (:require [clojure.string :as str]
            [clojure.test :as t :refer [deftest testing]]
            [kmet.tui.core :as core]
            [kmet.tui.terminal :as term])
  (:import (org.jline.terminal TerminalBuilder Size)))

(def ^:private clear-seq
  "The clear sequence a clearing full redraw must emit: erase screen, home,
   erase scrollback."
  "\u001b[2J\u001b[H\u001b[3J")

(def ^:private sync-on
  "Begin-synchronized-output marker that opens every frame write."
  "\u001b[?2026h")

(defn- count-occurrences
  "Count non-overlapping occurrences of NEEDLE in S."
  [s needle]
  (loop [i 0 n 0]
    (if-let [j (str/index-of s needle i)]
      (recur (+ j (count needle)) (inc n))
      n)))

;; ─── Virtual terminal ──────────────────────────────────────────────────────

(defrecord VirtualTerminal [terminal writes]
  term/ITerminal
  (start! [this _ _] this)
  (stop! [_] nil)
  (write-output [_ s] (swap! writes conj s))
  (columns [this] (.getWidth (:terminal this)))
  (rows [this] (.getHeight (:terminal this)))
  (hide-cursor! [this] (term/write-output this "\u001b[?25l"))
  (show-cursor! [this] (term/write-output this "\u001b[?25h"))
  (clear-line! [_] nil)
  (clear-screen! [_] nil)
  (set-title! [_ _] nil)
  (move-by! [_ _] nil)
  (clear-from-cursor! [_] nil)
  (set-progress! [_ _] nil))

(defn- make-virtual-terminal
  "A virtual terminal: a forced-dumb JLine terminal (80x24) with every
   write recorded in an atom. Dumb is forced so the test never touches the
   real controlling terminal, even when the suite runs interactively."
  []
  (let [jline (-> (TerminalBuilder/builder) (.dumb true) (.build))]
    (.setSize jline (Size. 80 24))
    (let [writes (atom [])]
      {:terminal (map->VirtualTerminal {:terminal jline :writes writes})
       :writes writes})))

(defn- test-component
  "IComponent rendering the strings in LINES (an atom)."
  [lines]
  (reify core/IComponent
    (render [_ _] @lines)
    (handle-input [_ _] nil)
    (invalidate [_] nil)))

;; ─── Render-loop driver ────────────────────────────────────────────────────

(defn- start-loop
  "Run the private render loop in a background thread."
  [tui]
  (reset! (:running? tui) true)
  (reset! (:stopped? tui) false)
  (reset! (:render-loop tui) (future ((var core/run-render-loop!) tui)))
  tui)

(defn- stop-loop
  "Stop the render loop and join its thread (must run even on failure)."
  [tui]
  (when @(:running? tui)
    (core/tui-stop tui))
  (when-let [f @(:render-loop tui)]
    (try
      (deref f 3000 nil)
      (catch Throwable t
        (println "render loop ended with error:" t)))
    (reset! (:render-loop tui) nil)))

(defn- frame-writes
  "The per-frame write strings (every frame opens with the sync marker)."
  [writes]
  (filterv #(str/includes? % sync-on) @writes))

(defn- wait-for-frames
  "Block until N frames are written or TIMEOUT-MS elapses."
  [writes n timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (when (and (< (System/currentTimeMillis) deadline)
                 (< (count (frame-writes writes)) n))
        (Thread/sleep 5)
        (recur)))
    (t/is (<= n (count (frame-writes writes)))
          (str "expected " n " rendered frame(s), saw " (count (frame-writes writes))))))

;; ─── Tests ─────────────────────────────────────────────────────────────────

(deftest first-render-preserves-terminal-output
  (testing "the first frame writes without clearing (prior terminal output above the TUI survives)"
    (let [vt (make-virtual-terminal)
          tui (core/create-tui (:terminal vt))]
      (try
        (core/tui-add-child tui (test-component (atom ["alpha" "beta"])))
        (start-loop tui)
        (wait-for-frames (:writes vt) 1 2000)
        (let [frame (first (frame-writes (:writes vt)))]
          (t/is (some? frame) "a frame was rendered")
          (t/is (not (str/includes? frame "\u001b[2J")) "no screen clear on first render")
          (t/is (not (str/includes? frame "\u001b[3J")) "no scrollback clear on first render")
          (t/is (str/includes? frame "alpha") "transcript written")
          (t/is (str/includes? frame "beta") "transcript written"))
        (finally
          (stop-loop tui))))))

(deftest forced-full-redraw-clears-scrollback
  (testing "a forced render (tui-resume! / Ctrl+L) clears screen AND scrollback before re-emitting"
    (let [vt (make-virtual-terminal)
          tui (core/create-tui (:terminal vt))]
      (try
        (core/tui-add-child tui (test-component (atom ["alpha" "beta" "gamma"])))
        (start-loop tui)
        (wait-for-frames (:writes vt) 1 2000)
        (core/tui-request-render tui true)
        (wait-for-frames (:writes vt) 2 2000)
        (let [redraw (second (frame-writes (:writes vt)))
              clear-idx (str/index-of redraw clear-seq)
              alpha-idx (str/index-of redraw "alpha")]
          (t/is (some? clear-idx) "forced full redraw emits 2J H 3J (screen + scrollback)")
          (when clear-idx
            (t/is (and alpha-idx (> alpha-idx clear-idx))
                  "transcript re-emitted after the clear — no duplication"))
          (t/is (= 1 (count-occurrences (apply str @(:writes vt)) clear-seq))
                "the clear sequence appears exactly once (only in the forced frame)")
          (t/is (= 80 @(:previous-width tui)) "diff state consistent after the forced redraw"))
        (finally
          (stop-loop tui))))))

(deftest change-above-viewport-uses-clearing-redraw
  (testing "a change above the viewport falls back to a clearing full redraw (firstChanged < viewportTop)"
    (let [lines (atom (vec (map #(str "line " %) (range 30))))
          vt (make-virtual-terminal)
          tui (core/create-tui (:terminal vt))]
      (try
        (core/tui-add-child tui (test-component lines))
        (start-loop tui)
        (wait-for-frames (:writes vt) 1 2000)
        ;; 30 lines on a 24-row screen → viewport top at row 6; change row 2
        (swap! lines assoc 2 "line 2 CHANGED")
        (core/tui-request-render tui)
        (wait-for-frames (:writes vt) 2 2000)
        (let [redraw (second (frame-writes (:writes vt)))]
          (t/is (str/includes? redraw clear-seq)
                "mid-diff fallback full redraw clears screen and scrollback")
          (t/is (str/includes? redraw "line 2 CHANGED") "new content re-emitted")
          (t/is (str/includes? redraw "line 29") "full transcript re-emitted"))
        (finally
          (stop-loop tui))))))

(deftest ordinary-diff-does-not-clear
  (testing "an in-viewport change takes the diff path — no clear, no scrollback wipe"
    (let [lines (atom ["alpha" "beta"])
          vt (make-virtual-terminal)
          tui (core/create-tui (:terminal vt))]
      (try
        (core/tui-add-child tui (test-component lines))
        (start-loop tui)
        (wait-for-frames (:writes vt) 1 2000)
        (swap! lines conj "gamma") ;; append — below the viewport
        (core/tui-request-render tui)
        (wait-for-frames (:writes vt) 2 2000)
        (let [diff (second (frame-writes (:writes vt)))]
          (t/is (not (str/includes? diff "\u001b[2J")) "no screen clear on an ordinary diff")
          (t/is (not (str/includes? diff "\u001b[3J")) "no scrollback clear on an ordinary diff")
          (t/is (str/includes? diff "gamma") "new line written"))
        (finally
          (stop-loop tui))))))

(deftest terminal-resize-triggers-full-redraw
  (testing "a terminal resize re-renders without any input event: JLine's native WINCH
            handler is dead under the GraalVM native image, so the loop must detect
            the size change itself and reflow (pi: terminal.on('resize') → requestRender).
            Without this the editor keeps wrapping at the pre-resize width."
    (let [lines (atom ["alpha" "beta"])
          vt (make-virtual-terminal)
          tui (core/create-tui (:terminal vt))
          jline (:terminal (:terminal vt))]
      (try
        (core/tui-add-child tui (test-component lines))
        (start-loop tui)
        (wait-for-frames (:writes vt) 1 2000)
        (t/is (= 80 @(:previous-width tui)) "rendered at the initial 80 cols")
        ;; Resize the terminal WITHOUT requesting a render — the loop's own
        ;; size poll must notice and reflow.
        (.setSize jline (Size. 60 24))
        (wait-for-frames (:writes vt) 2 2000)
        (let [frame (second (frame-writes (:writes vt)))]
          (t/is (some? frame) "a new frame was rendered after the resize")
          (t/is (str/includes? frame clear-seq)
                "width change takes the clearing full-redraw path")
          (t/is (str/includes? frame "alpha") "transcript re-emitted at the new width"))
        (t/is (= 60 @(:previous-width tui)) "diff state updated to the new width")
        ;; Height-only change must also re-render (editor dynamic height depends
        ;; on rows): the loop detects it and renders. Whether a frame is written
        ;; depends on the diff (Termux: height changes take the diff path, so
        ;; unchanged content emits nothing) — the render itself is observable
        ;; via the diff-state update.
        (.setSize jline (Size. 60 30))
        (let [deadline (+ (System/currentTimeMillis) 2000)]
          (loop []
            (when (and (< (System/currentTimeMillis) deadline)
                       (not= 30 @(:previous-height tui)))
              (Thread/sleep 5)
              (recur))))
        (t/is (= 30 @(:previous-height tui)) "height change detected and rendered")
        (finally
          (stop-loop tui))))))
