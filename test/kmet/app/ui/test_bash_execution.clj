(ns kmet.app.ui.test-bash-execution
  (:require [clojure.string :as str]
            [clojure.test :as t]
            [kmet.app.ui.bash-execution :as be]
            [kmet.tui.core :as core]
            [kmet.tui.macros :as macros]
            [kmet.tui.protocols :as protocols]
            [kmet.tui.theme :as theme]
            [kmet.tui.utils :as u]))

(t/deftest test-bash-execution-render-no-output
  (let [c (be/make-bash-execution :command "sleep 1" :exclude-from-context? false)
        lines (protocols/render c 40)]
    (t/is (seq lines))
    (t/is (= 40 (u/visible-width (first lines))) "top border spans the width")
    (t/is (= 40 (u/visible-width (last lines))) "bottom border spans the width")
    (t/is (some #(clojure.string/includes? % "$ sleep 1") lines) "command header shown")))

(t/deftest test-bash-execution-render-with-output
  ;; Collapsed preview renders the last lines plus the expand hint.
  (let [c (be/make-bash-execution :command "ls" :exclude-from-context? false)
        big (clojure.string/join "\n" (repeat 30 "line"))]
    (be/bash-execution-append-output! c big)
    (be/bash-execution-set-complete! c 0 false)
    (let [lines (protocols/render c 40)]
      (t/is (seq lines))
      (t/is (some #(clojure.string/includes? % "line") lines) "preview lines shown")
      (t/is (some #(clojure.string/includes? % "more lines") lines) "expand hint shown")
      (t/is (some #(clojure.string/includes? % "Took") lines) "duration shown"))))

(t/deftest test-bash-execution-render-expanded
  (let [c (be/make-bash-execution :command "ls" :exclude-from-context? false)
        big (clojure.string/join "\n" (repeat 30 "line"))]
    (be/bash-execution-append-output! c big)
    (reset! (:expanded-atom c) true)
    (let [lines (protocols/render c 40)]
      (t/is (seq lines))
      (t/is (>= (count lines) 30) "expanded output renders all lines"))))

(t/deftest test-bash-execution-render-cancelled
  (let [c (be/make-bash-execution :command "sleep 5" :exclude-from-context? false)]
    (be/bash-execution-set-complete! c nil true)
    (let [lines (protocols/render c 40)]
      (t/is (some #(clojure.string/includes? % "cancelled") lines) "cancelled status shown"))))

(t/deftest test-bash-execution-frame-driver
  (t/testing "80ms frame driver (pi Loader setInterval parity) runs while :running; completion cancels it"
    (let [c (be/make-bash-execution :command "sleep 1")
          driver @(:ticker-atom c)]
      (t/is (some? driver) "driver starts with the component")
      (t/is (future? driver))
      (be/bash-execution-set-complete! c 0 false)
      (t/is (nil? @(:ticker-atom c)) "completion clears the driver")
      (t/is (future-cancelled? driver) "driver future is cancelled"))))

(t/deftest test-bash-execution-borders-flush
  ;; Every content line — preview output, blank separator, status — must be
  ;; padded to the content width so both border columns stay flush. A broken
  ;; right border shows as a │ not at the last column.
  (let [c (be/make-bash-execution :command "ls" :exclude-from-context? false)]
    (be/bash-execution-append-output! c (clojure.string/join "\n" (repeat 30 "line")))
    (be/bash-execution-set-complete! c 0 false)
    (let [lines (protocols/render c 40)]
      (t/is (= 40 (u/visible-width (first lines))))
      (t/is (= 40 (u/visible-width (last lines))))
      (doseq [line (rest (butlast lines))]
        (t/is (= 40 (u/visible-width line))
              (str "content line spans full width: " (pr-str line))))
      ;; No stray spacer line between status and bottom border
      (t/is (some #(clojure.string/includes? % "Took") lines))
      (let [took-idx (first (keep-indexed #(when (clojure.string/includes? %2 "Took") %1) lines))]
        (t/is (= took-idx (- (count lines) 2))
              "bottom border directly follows the status line")))))

(t/deftest test-theme-sub-retheme
  ;; Stage 5: the component subscribes to ui.subs/theme-sub — swapping the
  ;; shared atom re-themes borders/spinner on the next render, without any
  ;; per-component setter.
  (let [c (be/make-bash-execution :command "ls")]
    (t/is (some? c))
    (let [before (protocols/render c 40)]
      (t/is (seq before) "renders with the current theme")
      (reset! theme/theme-atom (theme/get-theme "light"))
      (try
        (let [lines (protocols/render c 40)]
          (t/is (some #(clojure.string/includes? % "$ ls") lines)
                "still renders after theme switch")
          (t/is (= 40 (u/visible-width (first lines))) "borders stay flush")
          (t/is (not= before lines) "styling changed with the theme"))
        (finally
          (reset! theme/theme-atom (theme/get-theme "dark")))))))

(t/deftest ^:slow test-dispose-stops-frame-driver
  ;; A component dropped from the chat (e.g. /new while a run is in
  ;; flight) must not keep firing schedule-frame! into the frame hook.
  ;; Settles 150ms (>80ms period) after dispose, so an in-flight tick past
  ;; the done check lands inside the settle window, not the assertion window.
  (let [c (be/make-bash-execution :command "sleep 10")
        fired (atom 0)]
    (core/render c 40)
    (macros/set-frame-hook! #(swap! fired inc))
    (try
      (t/is (pos? (do (Thread/sleep 250) @fired)) "driver fires while running")
      (protocols/dispose c)
      (t/is (nil? @(:ticker-atom c)) "dispose cleared the driver future")
      ;; Settle: a tick past the done check before dispose can still fire
      ;; once — wait it out (>80ms period) before capturing the baseline.
      (Thread/sleep 150)
      (let [n @fired]
        (Thread/sleep 250)
        (t/is (= n @fired) "no frames scheduled after dispose"))
      (finally
        (macros/set-frame-hook! nil)))))

(t/deftest test-append-output-schedules-frame
  ;; The on-chunk path retired its manual request-render — the state swap
  ;; must dirty the root's reaction, which schedules the frame (edge-triggered:
  ;; the first swap schedules; further swaps coalesce until the frame flush
  ;; re-arms, exactly like chat-history's streaming appends). Requires a
  ;; rendered-once component so the reaction's watches are installed. The
  ;; 80ms driver is cancelled first so its ticks can't race the counts.
  (let [c (be/make-bash-execution :command "sleep 10")
        fired (atom 0)]
    (core/render c 40)
    (when-let [driver @(:ticker-atom c)]
      (future-cancel driver)
      (reset! (:ticker-atom c) nil))
    (macros/set-frame-hook! #(swap! fired inc))
    (try
      (be/bash-execution-append-output! c "hello\n")
      (t/is (= 1 @fired) "first chunk scheduled the frame")
      (be/bash-execution-append-output! c "world\n")
      (t/is (= 1 @fired) "coalesced chunk scheduled no extra frame")
      (let [lines (core/render c 40)]
        (t/is (some #(clojure.string/includes? % "hello") lines))
        (t/is (some #(clojure.string/includes? % "world") lines)))
      (finally
        (macros/set-frame-hook! nil)
        (be/bash-execution-set-complete! c 0 false)
        (protocols/dispose c)))))
