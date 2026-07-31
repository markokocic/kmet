(ns kmet.app.ui.test-bash-execution
  (:require [clojure.test :as t]
            [kmet.app.ui.bash-execution :as be]
            [kmet.tui.protocols :as protocols]
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
