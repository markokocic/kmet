(ns kmet.demo
  "Minimal demo to test the TUI stack with the multi-line editor."
  (:require [kmet.tui.core :as tui]
            [kmet.tui.terminal :as term]
            [kmet.tui.keys :as keys]
            [kmet.tui.components.text :as text]
            [kmet.tui.components.spacer :as spacer]
            [kmet.tui.components.input :as input]
            [kmet.tui.components.box :as box]
            [kmet.tui.theme :as theme]
            [kmet.config :as cfg]
            [kmet.skills :as skills]))

(defonce ^:private global-config (atom nil))

(defn -main
  "Run the TUI demo with the multi-line editor.
   Press Enter to submit, Escape to cancel, Ctrl+Z to quit."
  [& _args]
  (println "Starting kmet demo...")
  (let [terminal (term/create-terminal)
        t (tui/create-tui terminal)
        greeting (text/make-text "kmet — minimal coding agent" 1 1)
        info (text/make-text "Multi-line editor demo. Enter to submit, Shift+Enter for newline, Ctrl+Z to quit." 1 1)
        spacer1 (spacer/make-spacer 1)
        editor (tui/make-editor :height 10 :padding-x 2)
        last-msg (atom "")
        spacer2 (spacer/make-spacer 1)
        output (text/make-text "" 1 1)
        history (atom [])]

    ;; Handle submit from editor
    (tui/editor-set-on-submit! editor
      (fn [val]
        (if val
          (do (reset! last-msg (str "Submitted (" (count val) " chars):\n" val))
              (text/text-set! output @last-msg)
              ;; Push to history
              (swap! history conj val)
              (tui/editor-set-history! editor @history)
              (tui/editor-set-text! editor "")
              (tui/tui-request-render t))
          (do (reset! last-msg "Cancelled (Escape)")
              (text/text-set! output @last-msg)
              (tui/tui-request-render t)))))

    ;; Handle change (real-time preview)
    (tui/editor-set-on-change! editor
      (fn [text]
        (text/text-set! output (str "Editing (" (count text) " chars)..."))
        (tui/tui-request-render t)))

    ;; Tab autocomplete demo
    (tui/editor-set-on-tab! editor
      (fn [partial full-text]
        (let [suggestions ["/help" "/quit" "/model" "/new" "/resume"
                           "defn " "defrecord " "defprotocol "
                           "let " "if " "when " "cond " "loop "
                           "println " "pr-str " "str " "vec "]]
          (some #(when (clojure.string/starts-with? % partial) (subs % (count partial)))
                suggestions))))

    (tui/tui-add-child t greeting)
    (tui/tui-add-child t spacer1)
    (tui/tui-add-child t info)
    (tui/tui-add-child t editor)
    (tui/tui-add-child t spacer2)
    (tui/tui-add-child t output)

    ;; Focus the editor
    (tui/tui-set-focus t editor)

    ;; Global quit handler
    (tui/tui-add-input-listener t
      (fn [data]
        (when (or (keys/matches-key? data "q")
                  (keys/matches-key? data (keys/ctrl "z")))
          (tui/tui-stop t))))

    (println "Starting TUI...")
    (tui/tui-start t)
    (println "Demo ended.")))
