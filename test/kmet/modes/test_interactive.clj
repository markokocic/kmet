(ns kmet.modes.test-interactive
  "Interactive-mode tests: the agent-loop :on-event handler
   (make-agent-event-handler) must consume every event type in the loop
   vocabulary (kmet.app.event-bus/event-types) without throwing.

   Regression guard: the handler's case previously had no clause for
   :agent-start and no :default — it threw IllegalArgumentException on the
   first event of every run, the exception was swallowed by the run future
   (its catch re-emits :error through the same handler, which also threw),
   and the UI stayed on \"Working...\" forever while print mode kept working."
  (:require [clojure.test :as t :refer [deftest is testing]]
            [kmet.modes.interactive :as inter]
            [kmet.app.event-bus :as event-bus]
            [kmet.app.ui :as ui]))

(defn- make-handler
  "The real event handler over a standalone chat history and stub refs: the
   cs-dependent clauses (footer/status indicators) no-op through a nil
   cs-ref, tool-execution updates no-op through a nil pending component."
  []
  ((var inter/make-agent-event-handler)
   {:chat-history (ui/make-chat-history)
    :tui {:render-requested? (atom false)}
    :cs-ref (atom nil)
    :pending-tool-comp (atom nil)}))

(defn- minimal-event
  "Minimal but shape-valid payload for TYPE — the clause bodies run against
   real components, so payloads carry the keys the clauses read."
  [type]
  (case type
    :message-start {:type :message-start :message {:role :user :content "x"}}
    :message-update {:type :message-update
                     :message {:role :assistant :content []}
                     :delta {:type :text :content ""}}
    :message-end {:type :message-end :message {:role :assistant :content ""}}
    :tool-execution-start {:type :tool-execution-start
                           :tool-call-id "t1" :tool-name "bash" :args {}}
    :tool-execution-update {:type :tool-execution-update
                            :tool-call-id "t1" :content "x" :is-partial true}
    :tool-execution-end {:type :tool-execution-end
                         :tool-call-id "t1" :tool-name "bash"
                         :args {} :result {:content "x"}}
    :turn-end {:type :turn-end
               :message {:role :assistant :content ""} :tool-results []}
    :compaction-end {:type :compaction-end :reason :threshold :result true}
    :context-replaced {:type :context-replaced :messages []}
    :auto-retry-end {:type :auto-retry-end :success true :attempt 1}
    ;; the remaining vocabulary events carry only :type
    {:type type}))

(deftest agent-event-handler-consumes-vocabulary
  (testing "every loop event type is consumed by the UI handler without throwing"
    (let [h (make-handler)
          types (keys event-bus/loop-event-types)]
      (is (seq types) "loop-event-types must not be empty")
      (doseq [type types]
        (testing (name type)
          ;; an unhandled case clause throws — which the run future swallows,
          ;; hanging the UI; the handler call must simply not throw (clause
          ;; bodies legitimately return truthy values)
          (h (minimal-event type)))))))

(deftest agent-event-handler-default-is-safe
  (testing "unknown event types fall through the :default clause"
    (is (nil? ((make-handler) {:type :some-future-event})))))

(deftest submit-command-line-gate
  (testing "multiline submit text (e.g. pasted blocks) is never a command"
    (let [command-line? @#'inter/command-line?]
      ;; single-line slash/bang input stays a command
      (is (true? (command-line? "/model gpt-4o")))
      (is (true? (command-line? "/model")))
      (is (true? (command-line? "!ls -la")))
      (is (true? (command-line? "!!ls -la")))
      ;; multiline input whose first line starts with a command prefix is a
      ;; message, not a command (regression: pasting text starting with
      ;; "/model ..." ran the /model command)
      (is (false? (command-line? "/model gpt-4o\nsecond line")))
      (is (false? (command-line? "/model\n\nrest")))
      (is (false? (command-line? "!ls\n!echo hi"))))))
