(ns kmet.tui.test-viewport
  "Tests for the viewport input handling: mouse wheel/button parsing, the
   structural sequence-completeness gate, and wheel/page-key scrolling of the
   primary scroll view (pi: TuiAltScreen viewport handling)."
  (:require [clojure.test :as t :refer [testing]]
            [kmet.tui.core :as core]
            [kmet.tui.keys :as keys]
            [kmet.tui.components.scroll-view :as sv]
            [kmet.tui.components.text :as text]
            [kmet.tui.components.stack :as stack]
            [clojure.string :as str]))

(defn- leaf
  "A focusable leaf component (like the editor)."
  []
  (let [focused? (atom false)]
    {:comp (reify core/IComponent
             core/IFocusable
             (render [_ _] [""])
             (handle-input [_ _] nil)
             (invalidate [_])
             (focused [_] @focused?)
             (set-focused! [_ v] (reset! focused? v)))
     :focused? focused?}))

(defn- dispatch!
  "Call the private input dispatcher (pi: TUI input routing)."
  [tui data]
  ((var kmet.tui.core/dispatch-input!) tui data))

(defn- make-scrollable-tui
  "A TUI with a tall document in a follow-end scroll view and a focusable
   leaf below (the dock). Renders once so the scroll-layout geometry and
   request-render wiring are in place."
  []
  (let [tui (core/create-tui nil)
        child (text/make-text (str/join "\n" (mapv #(str "line " %) (range 50))))
        scroll-view (sv/make-scroll-view child :follow-end true :primary true
                                         :scrollbar :auto)
        dock-leaf (leaf)]
    (core/tui-add-child tui scroll-view)
    (core/tui-add-child tui (:comp dock-leaf))
    (core/tui-set-focus tui (:comp dock-leaf))
    (stack/render-stack @(:components tui) 80 24 (fn [] nil) (:scroll-layout tui))
    {:tui tui :scroll-view scroll-view :leaf dock-leaf}))

;; ─── Mouse parsing (keys) ──────────────────────────────────────────────────

(t/deftest test-parse-wheel-event
  (testing "SGR wheel events decode button/direction/coords"
    (t/is (= {:direction -1 :x 39 :y 11}
             (keys/parse-wheel-event "\u001b[<64;40;12M")))
    (t/is (= {:direction 1 :x 39 :y 11}
             (keys/parse-wheel-event "\u001b[<65;40;12M")))
    (t/is (= {:direction -1 :x 5 :y 3}
             (keys/parse-wheel-event "\u001b[<64;6;4M")))
    (t/is (nil? (keys/parse-wheel-event "\u001b[<0;5;5M"))
          "button without the wheel flag is not a wheel")
    (t/is (nil? (keys/parse-wheel-event "\u001b[<67;5;5M"))
          "direction bits 2-3 are ignored (diagonal)")))

(t/deftest test-parse-sgr-mouse
  (testing "SGR mouse events carry button/coords/release"
    (t/is (= {:button 0 :x 4 :y 5 :release? false}
             (keys/parse-sgr-mouse "\u001b[<0;5;6M")))
    (t/is (= {:button 0 :x 4 :y 5 :release? true}
             (keys/parse-sgr-mouse "\u001b[<0;5;6m")))
    (t/is (= {:button 32 :x 39 :y 11 :release? false}
             (keys/parse-sgr-mouse "\u001b[<32;40;12M")))
    (t/is (nil? (keys/parse-sgr-mouse "a")))
    (t/is (nil? (keys/parse-sgr-mouse "\u001b[<1;2")))))

(t/deftest test-mouse-and-focus-sequence-detection
  (t/is (keys/mouse-sequence? "\u001b[<64;40;12M"))
  (t/is (keys/mouse-sequence? "\u001b[<0;5;6m"))
  (t/is (keys/mouse-sequence? "\u001b[Mabc"))
  (t/is (not (keys/mouse-sequence? "\u001b[<64;40")))
  (t/is (keys/focus-sequence? "\u001b[I"))
  (t/is (keys/focus-sequence? "\u001b[O"))
  (t/is (not (keys/focus-sequence? "\u001b[<64;40;12M"))))

;; ─── Structural completeness (pi: isCompleteSequence) ──────────────────────

(t/deftest test-complete-sequence
  (testing "ESC-prefixed sequences complete only at their final byte"
    (t/is (not (keys/complete-sequence? "\u001b")))
    (t/is (not (keys/complete-sequence? "\u001b["))
          "CSI prefix never dispatches early (no alt+[ swallow)")
    (t/is (keys/complete-sequence? "\u001b[A"))
    (t/is (keys/complete-sequence? "\u001b[5~"))
    (t/is (keys/complete-sequence? "\u001b[1;5A"))
    (t/is (not (keys/complete-sequence? "\u001bO"))
          "SS3 prefix waits for its single character")
    (t/is (keys/complete-sequence? "\u001bOA"))
    (t/is (not (keys/complete-sequence? "\u001b[<64;40;6"))
          "partial SGR mouse waits for the final byte")
    (t/is (keys/complete-sequence? "\u001b[<64;40;6M"))
    (t/is (keys/complete-sequence? "\u001b[<0;5;6m"))
    (t/is (not (keys/complete-sequence? "\u001b[M"))
          "legacy mouse needs all 6 bytes")
    (t/is (keys/complete-sequence? "\u001b[Mabc"))
    (t/is (keys/complete-sequence? "\u001b[?1;2;4c")
          "terminal responses complete like any CSI")
    (t/is (keys/complete-sequence? "\u001b[I"))
    (t/is (keys/complete-sequence? "x"))))

(defn- make-buffer-feed!
  "An input-buffer simulator sharing ONE buf atom across feeds, like the real
   reader thread (chars accumulate until dispatched)."
  [tui]
  (let [q (atom (list))
        buf (atom "")
        read-fn (fn [_] (if (seq @q) (let [c (int (first @q))] (swap! q rest) c) -2))]
    {:feed! (fn [s]
              (swap! q concat (seq s))
              (loop []
                (when (seq @q)
                  (swap! buf str (char (first @q)))
                  (swap! q rest)
                  (swap! (:input-generation tui) inc)
                  ((var kmet.tui.core/process-input-buffer!) tui read-fn buf)
                  (recur))))
     :buf buf}))

;; ─── Wheel scrolling routes to the primary scroll view ─────────────────────

(t/deftest test-wheel-scrolls-primary-view
  (let [{:keys [tui scroll-view]} (make-scrollable-tui)
        initial (sv/scroll-top scroll-view)]
    (t/is (pos? initial) "tall content overflows the viewport")
    (dispatch! tui "\u001b[<64;40;12M")
    (t/is (= (dec initial) (sv/scroll-top scroll-view))
          "wheel-up scrolls the view up one line")
    (t/is (not (sv/follows-end? scroll-view)))
    (dispatch! tui "\u001b[<65;40;12M")
    (t/is (= initial (sv/scroll-top scroll-view))
          "wheel-down scrolls back down")
    (t/is (sv/follows-end? scroll-view)
          "reaching the end re-engages following")))

(t/deftest test-wheel-outside-scroll-view-falls-back-to-primary
  ;; The pointer is below the scroll view (in the dock): the wheel still
  ;; scrolls the primary view (pi: routeWheel fallback).
  (let [{:keys [tui scroll-view]} (make-scrollable-tui)
        initial (sv/scroll-top scroll-view)]
    (dispatch! tui "\u001b[<64;40;23M")
    (t/is (= (dec initial) (sv/scroll-top scroll-view)))))

(t/deftest test-page-keys-scroll-primary-view
  (let [{:keys [tui scroll-view]} (make-scrollable-tui)
        initial (sv/scroll-top scroll-view)
        page (max 1 (- (sv/viewport-height scroll-view) 4))]
    (dispatch! tui "\u001b[5~")  ;; pageUp
    (t/is (= (- initial page) (sv/scroll-top scroll-view)))
    (dispatch! tui "\u001b[6~")  ;; pageDown
    (t/is (= initial (sv/scroll-top scroll-view)))
    (dispatch! tui "\u001b[7~")  ;; home
    (t/is (= 0 (sv/scroll-top scroll-view)))
    (dispatch! tui "\u001b[8~")  ;; end
    (t/is (= initial (sv/scroll-top scroll-view)))))

(t/deftest test-mouse-events-never-reach-focused-component
  (let [tui (core/create-tui nil)
        got (atom [])
        c (reify core/IComponent
            (render [_ _] [""])
            (handle-input [_ data] (swap! got conj data))
            (invalidate [_]))]
    (core/tui-add-child tui c)
    (core/tui-set-focus tui c)
    (dispatch! tui "\u001b[<64;40;12M")
    (dispatch! tui "\u001b[<0;5;6M")
    (dispatch! tui "\u001b[<0;5;6m")
    (dispatch! tui "\u001b[I")
    (dispatch! tui "\u001b[O")
    (t/is (empty? @got)
          "mouse and focus sequences are consumed by the viewport listener")))

(t/deftest test-mouse-sequence-dispatches-through-input-buffer
  ;; Feed the raw bytes through process-input-buffer! like the reader:
  ;; a complete SGR wheel must reach the viewport handler and scroll.
  (let [{:keys [tui scroll-view]} (make-scrollable-tui)
        initial (sv/scroll-top scroll-view)
        {:keys [feed!]} (make-buffer-feed! tui)]
    (feed! "\u001b[<64;40;12M")
    (t/is (= (dec initial) (sv/scroll-top scroll-view))
          "the structural dispatch consumes the full sequence"))
  ;; a partial sequence stays buffered (waits for the final byte)
  (let [{:keys [tui scroll-view]} (make-scrollable-tui)
        initial (sv/scroll-top scroll-view)
        {:keys [feed!]} (make-buffer-feed! tui)]
    (feed! "\u001b[<64;40;1")
    (t/is (= initial (sv/scroll-top scroll-view))
          "an incomplete SGR mouse is not dispatched")
    (feed! "2M")
    (t/is (= (dec initial) (sv/scroll-top scroll-view))
          "completing the sequence dispatches it")))

;; ─── Selection (pi: press/drag/release → highlight + copy) ─────────────────

(t/deftest test-click-drag-produces-selection-highlight
  (let [{:keys [tui]} (make-scrollable-tui)
        lines (vec (repeat 24 "the quick brown fox jumps over the lazy dog"))
        plain (count (filter #(not (str/includes? % "\u001b[7m")) lines))]
    ;; press at (10, 12), drag to (20, 13) — both inside the scroll view
    (dispatch! tui "\u001b[<0;11;13M")
    (dispatch! tui "\u001b[<32;21;14M")
    (let [out ((var kmet.tui.core/composite-selection) tui lines)]
      (t/is (some #(str/includes? % "\u001b[7m") out)
            "the drag highlights the selected row range")
      (t/is (some #(str/includes? % "\u001b[27m") out)
            "the highlight closes with reverse-off")
      (t/is (<= (count (filter #(not (str/includes? % "\u001b[7m")) out)) plain)
            "no lines are lost")))
  ;; a plain click (no drag) never highlights
  (let [{:keys [tui]} (make-scrollable-tui)
        lines (vec (repeat 24 "plain"))]
    (dispatch! tui "\u001b[<0;11;13M")
    (dispatch! tui "\u001b[<0;11;13m")
    (let [out ((var kmet.tui.core/composite-selection) tui lines)]
      (t/is (not-any? #(str/includes? % "\u001b[7m") out)
            "a click without drag leaves no selection"))))

(t/deftest test-selection-release-clears-state
  (let [{:keys [tui]} (make-scrollable-tui)]
    (dispatch! tui "\u001b[<0;11;13M")
    (t/is @(:selection-press-active? tui))
    (dispatch! tui "\u001b[<0;11;13m")
    (t/is (not @(:selection-press-active? tui)))
    (t/is (nil? @(:selection-anchor tui)))))
