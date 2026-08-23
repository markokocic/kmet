(ns kmet.tui.components.test-scroll-view
  "Tests for the ScrollView: follow-end windowing, scroll clamping, the
   following-end re-engagement rule, and render gating (no-op scrolls don't
   request a render) — matching pi's scroll-view.ts semantics."
  (:require [clojure.test :as t]
            [kmet.tui.protocols :as protocols]
            [kmet.tui.components.scroll-view :as sv]))

(defn- fake-child
  "A component whose render returns LINES at width."
  [lines]
  (reify
    protocols/IComponent
    (render [_this _width] lines)
    (handle-input [_this _data] nil)
    (invalidate [_this] nil)
    (dispose [_this] nil)))

(defn- make-sv
  [lines & {:keys [follow-end viewport]}]
  (let [sv (sv/make-scroll-view (fake-child lines) :follow-end follow-end)]
    (sv/update-layout! sv (count lines) viewport (fn [] nil))
    sv))

(t/deftest test-window-follows-end
  ;; Content taller than the viewport shows the LAST viewport lines.
  (let [lines (mapv #(str "line" %) (range 10))
        sv (make-sv lines :follow-end true :viewport 4)]
    (t/is (= ["line6" "line7" "line8" "line9"] (sv/render-window sv lines)))))

(t/deftest test-window-pads-when-content-fits
  ;; Content shorter than the viewport is top-aligned and padded so the
  ;; layout below stays pinned to the bottom.
  (let [lines ["a" "b"]
        sv (make-sv lines :follow-end true :viewport 4)]
    (t/is (= ["a" "b" "" ""] (sv/render-window sv lines)))))

(t/deftest test-scroll-by-clamps-and-renders
  (let [lines (mapv #(str "line" %) (range 10))
        renders (atom 0)
        sv (sv/make-scroll-view (fake-child lines) :follow-end true)
        _ (sv/update-layout! sv 10 4 #(swap! renders inc))]
    ;; scroll up from the end (maxScrollTop 6 -> 3)
    (t/is (= 0 (sv/scroll-by! sv -3)))
    (t/is (= 3 (sv/scroll-top sv)))
    (t/is (not (sv/follows-end? sv)))
    (t/is (= 1 @renders))
    ;; no-op scroll at the top does not render
    (sv/scroll-to-start! sv)
    (t/is (= 2 @renders))
    (t/is (= -1 (sv/scroll-by! sv -1)))
    (t/is (= 2 @renders))
    ;; scroll back to the end re-engages following
    (sv/scroll-by! sv 100)
    (t/is (sv/follows-end? sv))
    (t/is (= 6 (sv/scroll-top sv)))))

(t/deftest test-scroll-returns-remainder
  ;; Pi: scrollBy returns the unscrolled remainder (overscroll chaining).
  (let [lines (mapv #(str "line" %) (range 10))
        sv (make-sv lines :follow-end true :viewport 4)]
    ;; only 6 lines scrollable above the end
    (t/is (= -4 (sv/scroll-by! sv -10)))
    (t/is (= 0 (sv/scroll-by! sv 3)))))

(t/deftest test-scroll-to-end-clamps-and-re-engages
  (let [lines (mapv #(str "line" %) (range 10))
        sv (make-sv lines :follow-end true :viewport 4)]
    (sv/scroll-to-start! sv)
    (t/is (= 0 (sv/scroll-top sv)))
    (t/is (not (sv/follows-end? sv)))
    (sv/scroll-to-end! sv)
    (t/is (= 6 (sv/scroll-top sv)))
    (t/is (sv/follows-end? sv))))

(t/deftest test-update-layout-re-engages-following
  ;; Pi: when content grows and the viewport was already at the bottom,
  ;; following re-engages so new content stays pinned.
  (let [sv (sv/make-scroll-view (fake-child ["x"]) :follow-end true)]
    (sv/update-layout! sv 10 4 (fn [] nil))
    (sv/scroll-by! sv -2)
    (t/is (not (sv/follows-end? sv)))
    (sv/scroll-by! sv 2)
    (t/is (sv/follows-end? sv))
    ;; content grows while at the bottom — stays following
    (sv/update-layout! sv 20 4 (fn [] nil))
    (t/is (sv/follows-end? sv))
    (t/is (= 16 (sv/scroll-top sv)))))

(t/deftest test-no-follow-end-keeps-position
  ;; Without follow-end, scroll position is retained on layout updates.
  (let [sv (sv/make-scroll-view (fake-child ["x"]) :follow-end false)]
    (sv/update-layout! sv 10 4 (fn [] nil))
    (sv/scroll-by! sv 3)
    (t/is (= 3 (sv/scroll-top sv)))
    (sv/update-layout! sv 20 4 (fn [] nil))
    (t/is (= 3 (sv/scroll-top sv)))))

(t/deftest test-zero-viewport-renders-empty
  (let [lines (mapv #(str "line" %) (range 10))
        sv (make-sv lines :follow-end true :viewport 0)]
    (t/is (= [] (sv/render-window sv lines)))))

(t/deftest test-get-content-width-reserves-column-for-always-scrollbar
  (let [sv (sv/make-scroll-view (fake-child ["x"]) :scrollbar :always)]
    (t/is (= 79 (sv/get-content-width sv 80)))
    (sv/set-scrollbar! sv :hidden)
    (t/is (= 80 (sv/get-content-width sv 80)))))

(t/deftest ^:slow test-auto-scrollbar-transient-visible
  (let [lines (mapv #(str "line" %) (range 10))
        sv (sv/make-scroll-view (fake-child lines) :scrollbar :auto
                                :scrollbar-hide-delay-ms 50)]
    (sv/update-layout! sv 10 4 (fn [] nil))
    (t/is (not (sv/is-scrollbar-visible? sv)))
    (sv/scroll-by! sv -2)
    (t/is (sv/is-scrollbar-visible? sv))
    ;; the transient scrollbar hides again after the delay
    (Thread/sleep 120)
    (t/is (not (sv/is-scrollbar-visible? sv)))))

(t/deftest test-scrollbar-geometry
  ;; The thumb tracks the scroll position (pi: getScrollbarGeometry).
  (let [lines (mapv #(str "line" %) (range 20))
        sv (sv/make-scroll-view (fake-child lines) :scrollbar :auto)]
    (sv/update-layout! sv 20 5 (fn [] nil))
    (t/is (nil? (sv/scrollbar-geometry sv 80))
          "no geometry while the transient scrollbar is hidden")
    (sv/scroll-by! sv -5)
    (let [g (sv/scrollbar-geometry sv 80)]
      (t/is (= 79 (:column g)) "thumb sits in the last column")
      (t/is (= 5 (:track-height g)))
      (t/is (= 15 (:max-scroll-top g)))
      (t/is (pos? (:thumb-top g)) "scrolled up → thumb moved down"))))
