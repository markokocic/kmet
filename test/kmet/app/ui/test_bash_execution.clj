(ns kmet.app.ui.test-bash-execution
  (:require [clojure.string :as str]
            [clojure.test :as t]
            [kmet.app.ui.bash-execution :as be]
            [kmet.tui.protocols :as protocols]
            [kmet.tui.theme :as theme]
            [kmet.tui.utils :as u]))

(t/deftest test-bash-execution-render-no-output
  ;; Regression: (into [top-border] (mapv ...) [bottom-border]) treated the
  ;; mapped vector as a transducer → "Key must be integer" on every render.
  (let [c (be/make-bash-execution :command "sleep 1" :exclude-from-context? false)
        lines (protocols/render c 40)]
    (t/is (seq lines))
    (t/is (= 40 (u/visible-width (first lines))) "top border spans the width")
    (t/is (= 40 (u/visible-width (last lines))) "bottom border spans the width")
    (t/is (some #(clojure.string/includes? % "$ sleep 1") lines) "command header shown")))

(t/deftest test-bash-execution-render-with-output
  ;; Collapsed preview child must be a real IComponent (was a bare map →
  ;; "No implementation of IComponent for PersistentArrayMap").
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
    (be/bash-execution-set-expanded! c true)
    (let [lines (protocols/render c 40)]
      (t/is (seq lines))
      (t/is (>= (count lines) 30) "expanded output renders all lines"))))

(t/deftest test-bash-execution-render-cancelled
  (let [c (be/make-bash-execution :command "sleep 5" :exclude-from-context? false)]
    (be/bash-execution-set-complete! c nil true)
    (let [lines (protocols/render c 40)]
      (t/is (some #(clojure.string/includes? % "cancelled") lines) "cancelled status shown"))))

(t/deftest test-bash-execution-elapsed-ticker
  (t/testing "1s ticker drives re-renders while :running; completion cancels it"
    (let [c (be/make-bash-execution :command "sleep 1")
          ticker @(:ticker-atom c)]
      (t/is (some? ticker) "ticker starts with the component")
      (t/is (future? ticker))
      (be/bash-execution-set-complete! c 0 false)
      (t/is (nil? @(:ticker-atom c)) "completion clears the ticker")
      (t/is (future-cancelled? ticker) "ticker future is cancelled"))))

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

(t/deftest test-bash-execution-set-theme
  ;; BashExecutionComponent takes a theme (was hardcoded dark-theme) and
  ;; bash-execution-set-theme! updates the render + spinner colors without
  ;; breaking subsequent renders.
  (let [c (be/make-bash-execution :command "ls" :theme theme/dark-theme)]
    (t/is (some? c))
    (t/is (seq (protocols/render c 40)) "renders with dark theme")
    (be/bash-execution-set-theme! c theme/light-theme)
    (let [lines (protocols/render c 40)]
      (t/is (some #(clojure.string/includes? % "$ ls") lines)
            "still renders after theme switch")
      (t/is (= 40 (u/visible-width (first lines))) "borders stay flush"))))
