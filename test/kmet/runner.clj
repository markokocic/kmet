(ns kmet.runner
  (:require [clojure.test :as t]
            [kmet.test-utils]
            [kmet.test-keys]
            [kmet.tui.components.test-text]
            [kmet.tui.components.test-spacer]
            [kmet.tui.components.test-container]
            [kmet.tui.components.test-box]
            [kmet.tui.components.test-input]))

(defn -main [& _args]
  (let [results (t/run-all-tests)]
    (System/exit (if (pos? (:fail results)) 1 0))))
