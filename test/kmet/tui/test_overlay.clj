(ns kmet.tui.test-overlay
  (:require [clojure.string :as str]
            [clojure.test :as t :refer [testing]]
            [kmet.tui.core :as core]
            [kmet.tui.terminal :as term]))

(defn- leaf
  "A focusable leaf component with a focused?-atom (like the editor)."
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

(defn- line-comp
  "Component rendering a single fixed line."
  [line]
  (reify core/IComponent
    (render [_ _] [line])
    (handle-input [_ _] nil)
    (invalidate [_])))

;; A RECORD leaf (like the editor): hiccup splices records as foreign
;; nodes, so this is the shape a dock body yields to the tree.
(defrecord TestDockLeaf [focused?-atom]
  core/IComponent
  (render [_ _] [""])
  (handle-input [_ _] nil)
  (invalidate [_])
  core/IFocusable
  (focused [_] @focused?-atom)
  (set-focused! [_ v] (reset! focused?-atom v)))

(defn- lines-comp
  "Component rendering multiple fixed lines."
  [& lines]
  (reify core/IComponent
    (render [_ _] (vec lines))
    (handle-input [_ _] nil)
    (invalidate [_])))

(defn- fake-terminal
  "ITerminal stub with fixed dimensions for overlay :visible callbacks."
  [w h]
  (reify term/ITerminal
    (start! [_ _ _] nil)
    (stop! [_] nil)
    (write-output [_ _] nil)
    (columns [_] w)
    (rows [_] h)
    (hide-cursor! [_] nil)
    (show-cursor! [_] nil)
    (clear-line! [_] nil)
    (clear-screen! [_] nil)
    (set-title! [_ _] nil)
    (move-by! [_ _] nil)
    (clear-from-cursor! [_] nil)
    (set-progress! [_ _] nil)))

(defn- composite [tui lines w h]
  ((var kmet.tui.core/composite-overlays) tui lines w h))

(defn- line-at
  "Trim trailing padding from a composited line (composite-line pads the
   after-region to the terminal width, pi: compositeLineAt)."
  [lines i]
  (clojure.string/trimr (nth lines i)))

(defn- resolve-layout [options overlay-height w h]
  ((var kmet.tui.core/resolve-overlay-layout) options overlay-height w h))

;; ─── Layout resolution ─────────────────────────────────────────────────────

(t/deftest test-layout-defaults
  (testing "default: width min(80, available), center anchor"
    (let [{:keys [width row col]} (resolve-layout {} 5 100 40)]
      (t/is (= 80 width))
      (t/is (= 17 row) "centered vertically: (40-5)/2")
      (t/is (= 10 col) "centered horizontally: (100-80)/2"))))

(t/deftest test-layout-width-options
  (testing "percentage width, min-width, and clamping"
    (t/is (= 50 (:width (resolve-layout {:width "50%"} 5 100 40))))
    (t/is (= 10 (:width (resolve-layout {:width "10%"} 5 100 40))))
    (t/is (= 25 (:width (resolve-layout {:width 10 :min-width 25} 5 100 40)))
          "min-width raises the width")
    (t/is (= 15 (:width (resolve-layout {:width "50%" :min-width 15} 5 20 40)))
          "width clamps to available space after margins")))

(t/deftest test-layout-max-height
  (testing "max-height clamps and affects effective height for centering"
    (let [{:keys [max-height row]} (resolve-layout {:max-height 3} 5 100 40)]
      (t/is (= 3 max-height))
      (t/is (= 18 row) "centering uses the clamped height: (40-3)/2"))))

(t/deftest test-layout-anchors
  (testing "all 9 anchors position the overlay"
    (doseq [[anchor [exp-row exp-col]] {:top-left [0 0]
                                        :top-center [0 10]
                                        :top-right [0 20]
                                        :left-center [17 0]
                                        :center [17 10]
                                        :right-center [17 20]
                                        :bottom-left [35 0]
                                        :bottom-center [35 10]
                                        :bottom-right [35 20]}]
      (let [{:keys [row col]} (resolve-layout {:anchor anchor :width 80} 5 100 40)]
        (t/is (= exp-row row) (str anchor " row"))
        (t/is (= exp-col col) (str anchor " col"))))))

(t/deftest test-layout-row-col-percentage
  (testing "percentage row/col: 0% top/left, 100% bottom/right, in bounds"
    (let [{:keys [row]} (resolve-layout {:row "0%"} 5 100 40)]
      (t/is (= 0 row)))
    (let [{:keys [row]} (resolve-layout {:row "100%"} 5 100 40)]
      (t/is (= 35 row) "100% = bottom edge minus height"))
    (let [{:keys [col]} (resolve-layout {:col "50%"} 5 100 40)]
      (t/is (= 10 col) "50% of (100-80)"))
    (let [{:keys [row col]} (resolve-layout {:row 5 :col 7} 5 100 40)]
      (t/is (= 5 row) "absolute row")
      (t/is (= 7 col) "absolute col"))))

(t/deftest test-layout-offsets-and-margin
  (testing "offsets shift from anchor; margins shrink the available area"
    (let [{:keys [row col]} (resolve-layout {:anchor :center :offset-x 2 :offset-y -1}
                                            5 100 40)]
      (t/is (= 16 row) "center row 17 offset by -1")
      (t/is (= 12 col) "center col 10 offset by +2"))
    (let [{:keys [row col]} (resolve-layout {:anchor :top-left :margin 2} 5 100 40)]
      (t/is (= 2 row) "margin from top")
      (t/is (= 2 col) "margin from left"))
    (let [{:keys [width]} (resolve-layout {:anchor :top-left :margin {:left 10 :right 10}}
                                          5 100 40)]
      (t/is (= 80 width) "default width shrinks to min(80, avail) with margins")))
  (testing "clamping keeps the overlay inside margins"
    (let [{:keys [row col]} (resolve-layout {:anchor :bottom-right :offset-x 50 :offset-y 50}
                                            5 100 40)]
      (t/is (<= row 35))
      (t/is (<= col 20)))))

;; ─── Compositing ───────────────────────────────────────────────────────────

(t/deftest test-composite-no-overlays-unchanged
  (testing "no visible overlays → base lines returned as-is"
    (let [tui (core/create-tui nil)]
      (core/tui-add-child tui (line-comp "base"))
      (t/is (= ["base1" "base2"] (composite tui ["base1" "base2"] 20 10))))))

(t/deftest test-composite-top-left
  (testing "overlay composited at its resolved position over the base"
    (let [tui (core/create-tui nil)]
      (core/tui-add-child tui (line-comp "base"))
      (core/tui-show-overlay tui (line-comp "XXXX") :anchor :top-left :width 4)
      (let [lines (composite tui ["base" "base2" "base3"] 20 10)]
        (t/is (= "XXXX" (line-at lines 0)) "overlay replaces the base at its position")
        (t/is (= "base2" (second lines)))
        (t/is (= "base3" (nth lines 2)))))))

(t/deftest test-composite-centered
  (testing "default center anchor with width 4 → col 8 on a 20-wide terminal"
    (let [tui (core/create-tui nil)]
      (core/tui-add-child tui (line-comp "base"))
      (core/tui-show-overlay tui (line-comp "XXXX") :width 4)
      (let [lines (composite tui (vec (repeat 10 "--------------------")) 20 10)]
        (t/is (= "--------XXXX--------" (line-at lines 4)) "row 4 = (10-1)/2 floor")))))

(t/deftest test-composite-z-order
  (testing "overlays stack by focus order — later focus() on top"
    (let [tui (core/create-tui nil)
          _ (core/tui-add-child tui (line-comp "base"))
          h1 (core/tui-show-overlay tui (line-comp "AAAA") :anchor :top-left :width 4)
          _ (core/tui-show-overlay tui (line-comp "BB") :anchor :top-left :width 2)
          lines (composite tui ["base"] 20 10)]
      (t/is (= "BBAA" (line-at lines 0)) "later overlay wins the left columns")
      ;; Bring the first overlay to the front
      ((:focus h1))
      (t/is (= "AAAA" (line-at (composite tui ["base"] 20 10) 0))
            "focus() moves the overlay to the visual front"))))

(t/deftest test-composite-max-height-clamp
  (testing "max-height slices the rendered overlay lines"
    (let [tui (core/create-tui nil)]
      (core/tui-add-child tui (line-comp "base"))
      (core/tui-show-overlay tui (lines-comp "a" "b" "c") :anchor :top-left
                             :width 3 :max-height 2)
      (let [lines (composite tui ["ba" "b2" "b3"] 20 10)]
        (t/is (= "a" (line-at lines 0)))
        (t/is (= "b" (line-at lines 1)))
        (t/is (= "b3" (nth lines 2)) "clamped rows leave the base visible")))))

(t/deftest test-composite-hidden-skipped
  (testing "hidden overlays are not rendered (pi: setHidden)"
    (let [tui (core/create-tui nil)
          h (core/tui-show-overlay tui (line-comp "XXXX") :anchor :top-left :width 4)]
      (core/tui-add-child tui (line-comp "base"))
      ((:set-hidden! h) true)
      (t/is (true? ((:is-hidden? h))))
      (t/is (= ["base1" "base2"] (composite tui ["base1" "base2"] 20 10))))))

(t/deftest test-composite-visible-callback
  (testing ":visible callback controls rendering by terminal size"
    (let [tui (core/create-tui (fake-terminal 100 30))]
      (core/tui-show-overlay tui (line-comp "XXXX") :anchor :top-left :width 4
                             :visible (fn [w _h] (>= w 80)))
      (core/tui-add-child tui (line-comp "base"))
      (t/is (= "XXXX" (line-at (composite tui ["base"] 20 10) 0)) "wide enough")
      ;; Shrink the terminal: overlay disappears
      (reset! (:terminal tui) (fake-terminal 60 30))
      (t/is (= ["base"] (composite tui ["base"] 20 10)) "too narrow → hidden"))))

(t/deftest test-composite-overlay-line-truncated
  (testing "overlay lines wider than the resolved width are truncated"
    (let [tui (core/create-tui nil)]
      (core/tui-add-child tui (line-comp "base"))
      (core/tui-show-overlay tui (line-comp "1234567890") :anchor :top-left :width 4)
      (t/is (= "1234" (line-at (composite tui ["base"] 20 10) 0))))))

;; ─── OverlayHandle ─────────────────────────────────────────────────────────

(t/deftest test-handle-hide-removes-overlay
  (testing "hide permanently removes the overlay and restores input to home"
    (let [tui (core/create-tui nil)
          a (leaf)
          o (leaf)]
      (core/tui-add-child tui (:comp a))
      (core/tui-set-focus-home! tui (fn [] (:comp a)))
      (let [h (core/tui-show-overlay tui (:comp o))]
        (t/is (true? ((:is-focused? h))))
        ((:hide h))
        (t/is (false? (core/tui-has-overlay? tui)))
        (t/is (identical? (:comp a) @(:focused-component tui))
              "home receives focus after hide")))))

(t/deftest test-hide-falls-back-to-focus-home
  (testing "with no capturing overlay below, hide lands on the focus home"
    (let [tui (core/create-tui nil)
          gone (leaf)                 ;; never added to the tree
          home (leaf)]
      (core/tui-add-child tui (:comp home))
      (core/tui-set-focus-home! tui (fn [] (:comp home)))
      (core/tui-set-focus tui (:comp gone))
      (let [o (leaf)]
        (core/tui-show-overlay tui (:comp o))
        (t/is (identical? (:comp o) @(:focused-component tui)))
        (core/tui-hide-overlay tui)
        (t/is (identical? (:comp home) @(:focused-component tui))
              "home receives focus when nothing below captures")))))

(t/deftest test-hide-no-home-clears-focus-instead-of-last-root-child
  (testing "without a home, focus goes null — never the last root child
            (the footer-swallowed-all-input regression)"
    (let [tui (core/create-tui nil)
          gone (leaf)
          last-child (leaf)]
      ;; gone was focused but is mounted nowhere; last-child is what the
      ;; old fallback wrongly focused
      (core/tui-add-child tui (:comp last-child))
      (core/tui-set-focus tui (:comp gone))
      (let [o (leaf)]
        (core/tui-show-overlay tui (:comp o))
        (core/tui-hide-overlay tui)
        (t/is (nil? @(:focused-component tui))
              "no home: null focus, not an inert root child")))))

(t/deftest test-hide-throwing-home-is-not-fatal
  (testing "a throwing focus home degrades to null focus"
    (let [tui (core/create-tui nil)
          gone (leaf)]
      (core/tui-set-focus-home! tui (fn [] (throw (ex-info "boom" {}))))
      (core/tui-set-focus tui (:comp gone))
      (let [o (leaf)]
        (core/tui-show-overlay tui (:comp o))
        (core/tui-hide-overlay tui)
        (t/is (nil? @(:focused-component tui))
              "throwing home does not take input handling down")))))

(t/deftest test-handle-set-hidden-focus-moves
  (testing "set-hidden! releases focus to home; showing restores it"
    (let [tui (core/create-tui nil)
          a (leaf)
          o (leaf)]
      (core/tui-add-child tui (:comp a))
      (core/tui-set-focus tui (:comp a))
      (core/tui-set-focus-home! tui (fn [] (:comp a)))
      (let [h (core/tui-show-overlay tui (:comp o))]
        (t/is (identical? (:comp o) @(:focused-component tui)))
        ((:set-hidden! h) true)
        (t/is (identical? (:comp a) @(:focused-component tui))
              "focus returns home while hidden")
        ((:set-hidden! h) false)
        (t/is (identical? (:comp o) @(:focused-component tui))
              "un-hiding refocuses the overlay")
        (t/is (true? (core/tui-has-overlay? tui)) "overlay is visible again")))))

(t/deftest test-handle-unfocus-target
  (testing "unfocus with an explicit target hands focus to it"
    (let [tui (core/create-tui nil)
          a (leaf)
          b (leaf)
          o (leaf)]
      (core/tui-add-child tui (:comp a))
      (core/tui-add-child tui (:comp b))
      (core/tui-set-focus tui (:comp a))
      (let [h (core/tui-show-overlay tui (:comp o))]
        ((:unfocus h) {:target (:comp b)})
        (t/is (identical? (:comp b) @(:focused-component tui)))
        (t/is (false? ((:is-focused? h))))
        (t/is (true? (core/tui-has-overlay? tui)) "overlay stays visible")))))

(t/deftest test-handle-unfocus-no-target
  (testing "unfocus without a target releases input to home; modality
           re-snaps on the next key"
    (let [tui (core/create-tui nil)
          a (leaf)
          got-o (atom nil)
          o (reify core/IComponent
              core/IFocusable
              (render [_ _] [""])
              (handle-input [_ data] (reset! got-o data))
              (invalidate [_])
              (focused [_] false)
              (set-focused! [_ _] nil))]
      (core/tui-add-child tui (:comp a))
      (core/tui-set-focus-home! tui (fn [] (:comp a)))
      (let [h (core/tui-show-overlay tui o)]
        ((:unfocus h))
        (t/is (identical? (:comp a) @(:focused-component tui))
              "released to home")
        ((var kmet.tui.core/dispatch-input!) tui "x")
        (t/is (identical? o @(:focused-component tui))
              "modality re-snaps to the visible overlay")
        (t/is (= "x" @got-o) "the key went to the overlay, not home")))))

(t/deftest test-non-capturing-overlay
  (testing "nonCapturing overlays render without taking focus"
    (let [tui (core/create-tui nil)
          a (leaf)
          o (leaf)]
      (core/tui-add-child tui (:comp a))
      (core/tui-set-focus tui (:comp a))
      (core/tui-show-overlay tui (:comp o) :non-capturing true)
      (t/is (identical? (:comp a) @(:focused-component tui))
            "focus stays on the base component")
      (t/is (true? (core/tui-has-overlay? tui)) "overlay is visible"))))

;; ─── Modality ──────────────────────────────────────────────────────────────

(defn- dispatch! [tui data]
  ((var kmet.tui.core/dispatch-input!) tui data))

(defn- capturing-leaf
  "A focusable overlay-style component recording everything it receives."
  [got]
  (reify core/IComponent
    core/IFocusable
    (render [_ _] [""])
    (handle-input [_ data] (swap! got conj data))
    (invalidate [_])
    (focused [_] false)
    (set-focused! [_ _] nil)))

(t/deftest test-dispatch-delivers-to-visible-overlay
  (testing "while an overlay captures, input goes to it"
    (let [tui (core/create-tui nil)
          a (leaf)
          got (atom [])
          o (capturing-leaf got)]
      (core/tui-add-child tui (:comp a))
      (core/tui-set-focus-home! tui (fn [] (:comp a)))
      (core/tui-show-overlay tui o)
      (dispatch! tui "k")
      (t/is (= ["k"] @got) "delivered to the capturing overlay"))))

(t/deftest test-dispatch-reclaims-focus-from-temporary-ui
  (testing "focus stolen while an overlay captures snaps back on dispatch"
    (let [tui (core/create-tui nil)
          temp (leaf)
          got-o (atom [])
          o (capturing-leaf got-o)]
      (core/tui-show-overlay tui o)
      ;; temporary UI grabs focus away from the capturing overlay
      (core/tui-show-overlay tui (:comp temp) :non-capturing true)
      (core/tui-set-focus tui (:comp temp))
      (t/is (identical? (:comp temp) @(:focused-component tui)))
      (dispatch! tui "esc")
      (t/is (identical? o @(:focused-component tui))
            "modality re-snaps focus to the overlay")
      (t/is (= ["esc"] @got-o) "the key went to the overlay, not the thief"))))

(t/deftest test-unfocus-target-does-not-stick-against-modality
  (testing "unfocus {:target} hands input over once; the next key re-snaps"
    (let [tui (core/create-tui nil)
          b (leaf)
          got-o (atom [])
          o (capturing-leaf got-o)
          h (core/tui-show-overlay tui o)]
      ((:unfocus h) {:target (:comp b)})
      (t/is (identical? (:comp b) @(:focused-component tui)))
      (t/is (false? ((:is-focused? h))))
      (t/is (true? (core/tui-has-overlay? tui)) "overlay stays visible")
      (dispatch! tui "y")
      (t/is (identical? o @(:focused-component tui))
            "modality wins over the released target")
      (t/is (= ["y"] @got-o)))))

(t/deftest test-hidden-overlay-stops-capturing
  (testing "a hidden overlay neither receives input nor reclaims it;
           keys fall through to home until shown again"
    (let [tui (core/create-tui nil)
          got-home (atom [])
          home (capturing-leaf got-home)
          got-o (atom [])
          o (capturing-leaf got-o)]
      (core/tui-set-focus-home! tui (fn [] home))
      (let [h (core/tui-show-overlay tui o)]
        ((:set-hidden! h) true)
        (t/is (identical? home @(:focused-component tui))
              "hiding releases focus to home")
        (dispatch! tui "k")
        (t/is (= ["k"] @got-home) "home receives keys while hidden")
        (t/is (empty? @got-o) "the hidden overlay gets nothing")
        ((:set-hidden! h) false)
        (t/is (identical? o @(:focused-component tui))
              "showing refocuses the overlay")
        (dispatch! tui "j")
        (t/is (= ["j"] @got-o) "overlay captures again")))))
