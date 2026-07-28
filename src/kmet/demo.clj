(ns kmet.demo
  "Minimal demo to test the TUI stack."
  (:require [kmet.tui.core :as tui]
            [kmet.tui.terminal :as term]
            [kmet.tui.keys :as keys]
            [kmet.tui.components.text :as text]
            [kmet.tui.components.spacer :as spacer]))

(defn -main
  "Run the TUI demo. Press q to quit."
  [& _args]
  (println "Starting kmet demo...")
  (let [terminal (term/create-terminal)
        t (tui/create-tui terminal)
        greeting (text/make-text "Welcome to kmet!" 1 1)
        info (text/make-text "Press q to quit" 1 1)
        spacer1 (spacer/make-spacer 2)]
    (tui/tui-add-child t greeting)
    (tui/tui-add-child t spacer1)
    (tui/tui-add-child t info)
    (tui/tui-add-input-listener t
      (fn [data]
        (when (keys/matches-key? data "q")
          (tui/tui-stop t))))
    (println "Starting TUI...")
    (tui/tui-start t)
    (println "Demo ended.")))

(defn quick-test
  "Quick smoke test - start terminal, print something, stop."
  []
  (println "Quick test...")
  (let [terminal (term/create-terminal)
        jline (.terminal terminal)]
    (println "terminal created, acquiring terminal...")
    (.enterRawMode jline)
    (let [w (.writer jline)]
      (.write w "Hello from kmet!\r\n")
      (.flush w))
    (.close jline)
    (println "Quick test done.")))
