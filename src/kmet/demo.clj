(ns kmet.demo
  "Minimal demo to test the TUI stack."
  (:require [kmet.tui.core :as tui]
            [kmet.tui.terminal :as term]
            [kmet.tui.keys :as keys]
            [kmet.tui.components.text :as text]
            [kmet.tui.components.spacer :as spacer]
            [kmet.tui.components.input :as input]
            [kmet.tui.components.box :as box]))

(defn -main
  "Run the TUI demo. Press q to quit."
  [& _args]
  (println "Starting kmet demo...")
  (let [terminal (term/create-terminal)
        t (tui/create-tui terminal)
        greeting (text/make-text "Welcome to kmet!" 1 1)
        info (text/make-text "Type in the input field below, then press Enter" 1 1)
        spacer1 (spacer/make-spacer 1)
        inp (input/make-input)
        last-msg (atom "")
        spacer2 (spacer/make-spacer 1)
        output (text/make-text "" 1 1)]

    ;; Handle submit from input
    (input/input-set-on-submit! inp
      (fn [val]
        (reset! last-msg (str "Submitted: " val))
        (text/text-set! output @last-msg)
        (input/input-set-value! inp "")
        (tui/tui-request-render t)))

    ;; Handle escape from input
    (input/input-set-on-escape! inp
      (fn []
        (reset! last-msg "Cancelled")
        (text/text-set! output @last-msg)
        (tui/tui-request-render t)))

    (tui/tui-add-child t greeting)
    (tui/tui-add-child t spacer1)
    (tui/tui-add-child t info)
    (tui/tui-add-child t inp)
    (tui/tui-add-child t spacer2)
    (tui/tui-add-child t output)

    ;; Focus the input component
    (tui/tui-set-focus t inp)

    ;; Global quit handler
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
