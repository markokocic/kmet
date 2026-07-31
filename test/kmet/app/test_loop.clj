(ns kmet.app.test-loop
  (:require [clojure.test :as t]
            [kmet.app.llm :as llm]
            [kmet.app.tools.core :as tools]
            [kmet.app.skills :as skills]
            [kmet.app.loop :as loop]
            [kmet.config :as cfg]))

;; ─── State construction ───────────────────────────────────────────────────

(t/deftest test-loop-make-agent-state-defaults
  (let [agent (loop/make-agent-state)]
    (t/is (= :idle @(:status agent)))
    (t/is (instance? clojure.lang.Atom (:messages agent)))
    (t/is (empty? @(:messages agent)))
    (t/is (= :openai @(:provider agent)))
    (t/is (nil? @(:model agent)))
    (t/is (string? @(:system agent)))
    (t/is (instance? clojure.lang.Atom (:signal agent)))
    (t/is (false? @(:signal agent)))
    (t/is (nil? (:on-event agent)))
    (t/is (nil? (:session agent)))
    (t/is (= :off @(:thinking agent)))))

(t/deftest test-loop-make-agent-state-with-opts
  (let [session {:dummy :session}
        events (atom [])
        agent (loop/make-agent-state
                :model "gpt-4o"
                :provider :anthropic
                :system "custom prompt"
                :session session
                :on-event (fn [e] (swap! events conj e)))]
    (t/is (= "gpt-4o" @(:model agent)))
    (t/is (= :anthropic @(:provider agent)))
    (t/is (= "custom prompt" @(:system agent)))
    (t/is (= session (:session agent)))
    (t/is (fn? (:on-event agent)))))

;; ─── State helpers ───────────────────────────────────────────────────────

(t/deftest test-loop-get-context
  (let [agent (loop/make-agent-state)]
    (t/is (empty? (loop/get-context agent)))
    (swap! (:messages agent) conj {:role :user :content "hi"})
    (t/is (= 1 (count (loop/get-context agent))))))

(t/deftest test-loop-set-system-prompt
  (let [agent (loop/make-agent-state)]
    (loop/set-system-prompt! agent "new prompt")
    (t/is (= "new prompt" @(:system agent)))))

(t/deftest test-loop-set-model
  (let [agent (loop/make-agent-state)]
    (loop/set-model! agent "gpt-4")
    (t/is (= "gpt-4" @(:model agent)))))

(t/deftest test-loop-set-provider
  (let [agent (loop/make-agent-state)]
    (loop/set-provider! agent :anthropic)
    (t/is (= :anthropic @(:provider agent)))))

;; ─── Cancellation ────────────────────────────────────────────────────────

(t/deftest test-loop-cancel-turn
  (let [agent (loop/make-agent-state)]
    (reset! (:status agent) :thinking)
    (reset! (:signal agent) false)
    (loop/cancel-turn agent)
    (t/is (= :idle @(:status agent)))
    (t/is (true? @(:signal agent)))))

(t/deftest test-loop-cancel-turn-when-idle
  (let [agent (loop/make-agent-state)]
    (loop/cancel-turn agent)
    (t/is (= :idle @(:status agent)))))

;; ─── Status transitions ──────────────────────────────────────────────────

(t/deftest test-loop-status-transitions
  (let [agent (loop/make-agent-state)]
    (t/is (= :idle @(:status agent)))
    (reset! (:status agent) :thinking)
    (t/is (= :thinking @(:status agent)))
    (reset! (:status agent) :executing)
    (t/is (= :executing @(:status agent)))
    (reset! (:status agent) :error)
    (t/is (= :error @(:status agent)))))

;; ─── Event dispatching ───────────────────────────────────────────────────

(t/deftest test-loop-on-event-fires-on-cancel
  (let [events (atom [])
        agent (loop/make-agent-state :on-event (fn [e] (swap! events conj e)))]
    (loop/cancel-turn agent)
    (t/is (pos? (count @events)))
    (t/is (= :idle (:status (last @events))))))

(t/deftest test-loop-on-event-no-listener
  (let [agent (loop/make-agent-state)]
    (t/is (nil? (:on-event agent)))
    (loop/cancel-turn agent)
    (t/is (= :idle @(:status agent)))))

(t/deftest test-loop-make-agent-state-with-thinking
  (let [agent (loop/make-agent-state :thinking :high)]
    (t/is (= :high @(:thinking agent)))))

(t/deftest test-loop-thinking-default
  (let [agent (loop/make-agent-state)]
    (t/is (= :off @(:thinking agent)))))

;; ─── run-agent-turn with no API key ──────────────────────────────────────

(t/deftest test-loop-run-agent-turn-no-api-key
  (let [agent (loop/make-agent-state)
        errors (atom [])]
    (loop/run-agent-turn agent
      {:message "hello"
       :on-error (fn [e] (swap! errors conj e))})
    (Thread/sleep 100)
    (t/is (pos? (count @errors)))
    (t/is (.contains (first @errors) "No API key"))))

;; ─── run-agent-turn with valid state ─────────────────────────────────────

(t/deftest test-loop-run-agent-turn-structure
  (let [agent (loop/make-agent-state)
        called (atom false)]
    (let [fut (loop/run-agent-turn agent
                {:message "hello"
                 :on-text (fn [_] (reset! called true))
                 :on-done (fn [_] (reset! called true))
                 :on-error (fn [_] (reset! called true))})]
      (t/is (future? fut))
      @fut
      (t/is @called))))

;; ─── Multiple turns ──────────────────────────────────────────────────────

(t/deftest test-loop-messages-accumulate
  (let [agent (loop/make-agent-state)]
    (t/is (empty? @(:messages agent)))
    (swap! (:messages agent) conj {:role :user :content [{:type :text :text "hi"}]})
    (t/is (= 1 (count @(:messages agent))))))

;; ─── Regression: run-agent-turn signals cleanup ─────────────────────────

(t/deftest test-loop-run-agent-turn-resets-signal
  (let [agent (loop/make-agent-state)]
    (reset! (:signal agent) true)
    (reset! (:status agent) :thinking)
    ;; run with no API key — should exercire error path
    (let [errors (atom [])]
      (loop/run-agent-turn agent
        {:message "test"
         :on-error (fn [e] (swap! errors conj e))})
      (Thread/sleep 200)
      ;; signal should have been reset by run-agent-turn
      (t/is (false? @(:signal agent)) "Signal reset at start of turn")
      (t/is (pos? (count @errors)) "Error callback called")
      (t/is (.contains (first @errors) "No API key") "Error message is about API key"))))

(t/deftest test-loop-signal-clean-after-turn
  (let [agent (loop/make-agent-state)
        done (promise)]
    (loop/run-agent-turn agent
      {:message "hi"
       :on-text (fn [_])
       :on-done (fn [_] (deliver done true))
       :on-error (fn [_] (deliver done true))})
    (deref done 2000 :timeout)
    (t/is (false? @(:signal agent)) "Signal should be false after turn ends")))

(t/deftest test-loop-status-after-error
  (let [agent (loop/make-agent-state)]
    (reset! (:status agent) :idle)
    (loop/run-agent-turn agent
      {:message "hi"
       :on-error (fn [_])})
    (Thread/sleep 200)
    (t/is (= :idle @(:status agent)) "Status should be idle after error turn")
    (t/is (false? @(:signal agent)) "Signal should be false after error turn")))

(t/deftest test-loop-multiple-cancel
  (let [agent (loop/make-agent-state)]
    (loop/cancel-turn agent)
    (t/is (true? @(:signal agent)) "cancel-turn sets signal to true")
    ;; calling cancel-turn again is idempotent
    (loop/cancel-turn agent)
    (t/is (true? @(:signal agent)) "Signal remains true after second cancel")))

;; ─── Event routing ───────────────────────────────────────────────────────

(t/deftest test-loop-events-routed-to-extension-system
  (let [received (atom [])
        dereg (skills/on-event :status (fn [e] (swap! received conj e)))
        agent (loop/make-agent-state :on-event (fn [_]))]
    (loop/cancel-turn agent)
    (dereg)
    ;; Events reach extension listeners even though the UI callback ignores them
    (t/is (some #(= :idle (:status %)) @received)
          "Extension listener should receive the :status event from cancel-turn")))

(t/deftest test-loop-lifecycle-events
  (let [events (atom [])
        agent (loop/make-agent-state :on-event (fn [e] (swap! events conj e)))]
    (with-redefs [cfg/get-api-key (fn [_] "test-key")
                  llm/send-message
                  (fn [opts]
                    (future
                      (when-let [on-text (:on-text opts)]
                        (on-text "hello"))
                      (when-let [on-done (:on-done opts)]
                        (on-done :stop))
                      :done))]
      ;; deref inside with-redefs keeps the rebinding until the turn completes
      @(loop/run-agent-turn agent
         {:message "hi"
          :on-done (fn [_])
          :on-error (fn [_])}))
    (let [types (mapv :type @events)]
      (doseq [t [:agent-start :turn-start :message-start
                 :message-update :message-end :turn-end
                 :agent-end :status]]
        (t/is (some #{t} types) (str "expected event " t " to be emitted")))
      (t/is (some #(= :idle (:status %))
                  (filter #(= :status (:type %)) @events))
            "final status should be :idle")
      (t/is (= :idle @(:status agent)) "Agent status should be idle after turn")
      ;; message-update carries the streaming delta
      (let [mu (first (filter #(= :message-update (:type %)) @events))]
        (t/is (= :text (:type (:delta mu))) "delta type should be :text")
        (t/is (= "hello" (:content (:delta mu))) "delta content should carry the text")))
    (t/is (false? @(:signal agent)) "Signal should be false after turn")))

(t/deftest test-loop-tool-execution-events
  (let [events (atom [])
        call-count (atom 0)
        agent (loop/make-agent-state :on-event (fn [e] (swap! events conj e)))]
    (with-redefs [cfg/get-api-key (fn [_] "test-key")
                  llm/send-message
                  ;; First call streams a tool call, second call a plain response
                  (fn [opts]
                    (future
                      (if (= 1 (swap! call-count inc))
                        (do (when-let [on-tc (:on-tool-call opts)]
                              (on-tc {:id "tc1" :name "bash" :arguments "{}" :index 0}))
                            (when-let [on-done (:on-done opts)]
                              (on-done :tool-calls)))
                        (do (when-let [on-text (:on-text opts)]
                              (on-text "done"))
                            (when-let [on-done (:on-done opts)]
                              (on-done :stop))))
                      :done))
                  tools/execute-tool
                  ;; Slow enough that the 200ms poll observes :pending at least once
                  (fn [_ _] (Thread/sleep 250) {:content "ok" :is-error false})]
      ;; deref inside with-redefs keeps the rebinding until the turn completes
      @(loop/run-agent-turn agent
         {:message "run tool"
          :on-done (fn [_])
          :on-error (fn [_])}))
    (let [types (mapv :type @events)]
      (doseq [t [:tool-execution-start :tool-execution-update
                 :tool-execution-end :turn-end]]
        (t/is (some #{t} types) (str "expected event " t " to be emitted")))
      (let [start (first (filter #(= :tool-execution-start (:type %)) @events))
            end (first (filter #(= :tool-execution-end (:type %)) @events))]
        (t/is (= "tc1" (:tool-call-id start)) "start carries the tool call id")
        (t/is (= "bash" (:tool-name start)) "start carries the tool name")
        (t/is (= "tc1" (:tool-call-id end)) "end carries the tool call id")
        (t/is (= "ok" (:content (:result end))) "end carries the result")
        (t/is (false? (:is-error end)) "end carries the error flag"))
      ;; agent-end includes the new messages from this loop
      (let [ae (first (filter #(= :agent-end (:type %)) @events))]
        (t/is (some #(and (= :user (:role %)) (= "run tool" (get-in % [:content 0 :text])))
                    (:messages ae))
              "agent-end :messages includes the user message")
        (t/is (= :idle @(:status agent)) "Agent status should be idle after tool turn")))))



;; ─── Queues (steering / follow-up) ────────────────────────────────────────

(t/deftest test-loop-steer-queues-message
  (let [events (atom [])
        agent (loop/make-agent-state :on-event (fn [e] (swap! events conj e)))]
    (loop/steer! agent "first")
    (loop/steer! agent "second")
    (t/is (= ["first" "second"] @(:steering agent)))
    (t/is (loop/has-queued-messages? agent))
    (t/is (= {:steering ["first" "second"] :follow-up []}
             (loop/queued-messages agent)))
    (t/is (= :queue-update (:type (last @events)))
          "steer! emits :queue-update")
    (loop/clear-queues! agent)
    (t/is (empty? @(:steering agent)))
    (t/is (empty? @(:follow-up agent)))
    (t/is (not (loop/has-queued-messages? agent)))
    (t/is (= :queue-update (:type (last @events)))
          "clear-queues! emits :queue-update")))

(t/deftest test-loop-followup-queues-message
  (let [events (atom [])
        agent (loop/make-agent-state :on-event (fn [e] (swap! events conj e)))]
    (loop/follow-up! agent "later")
    (t/is (= ["later"] @(:follow-up agent)))
    (t/is (loop/has-queued-messages? agent))
    (t/is (= :queue-update (:type (last @events)))
          "follow-up! emits :queue-update")))

(t/deftest test-loop-cancel-clears-queues
  (let [agent (loop/make-agent-state)]
    (loop/steer! agent "x")
    (loop/follow-up! agent "y")
    (loop/cancel-turn agent)
    (t/is (empty? @(:steering agent)) "cancel drops steering messages")
    (t/is (empty? @(:follow-up agent)) "cancel drops follow-up messages")
    (t/is (= :idle @(:status agent)))))

(t/deftest test-loop-steer-injects-mid-run
  (let [calls (atom 0)
        agent (loop/make-agent-state)]
    (with-redefs [cfg/get-api-key (fn [_] "test-key")
                  llm/send-message
                  (fn [opts]
                    (future
                      (if (= 1 (swap! calls inc))
                        (do (when-let [on-tc (:on-tool-call opts)]
                              (on-tc {:id "tc1" :name "bash" :arguments "{}" :index 0}))
                            (when-let [on-done (:on-done opts)]
                              (on-done :tool-calls)))
                        ;; second call: verify the steered message reached context
                        (do (when-let [on-text (:on-text opts)]
                              (on-text (if (some #(= "steered" (get-in % [:content 0 :text]))
                                                 (:messages opts))
                                         "ok" "MISSING")))
                            (when-let [on-done (:on-done opts)]
                              (on-done :stop))))
                      :done))
                  tools/execute-tool
                  (fn [_ _]
                    (loop/steer! agent "steered")
                    {:content "ok" :is-error false})]
      ;; deref inside with-redefs keeps the rebinding until the run completes
      @(loop/run-agent-turn agent
         {:message "first"
          :on-done (fn [_])
          :on-error (fn [_])}))
    (t/is (= 2 @calls) "exactly two LLM calls: initial + steered")
    (t/is (some #(= "steered" (get-in % [:content 0 :text])) (loop/get-context agent))
          "steered message should be in context")
    (t/is (= "ok" (get-in (last (loop/get-context agent)) [:content 0 :text]))
          "final response should see the steered message")
    (t/is (empty? @(:steering agent)) "steering queue drained")
    (t/is (= :idle @(:status agent)) "agent idle after run")))

(t/deftest test-loop-followup-continues-run
  (let [calls (atom 0)
        done-count (atom 0)
        agent (loop/make-agent-state)]
    (with-redefs [cfg/get-api-key (fn [_] "test-key")
                  llm/send-message
                  (fn [opts]
                    (future
                      (if (= 1 (swap! calls inc))
                        (do (when-let [on-tc (:on-tool-call opts)]
                              (on-tc {:id "tc1" :name "bash" :arguments "{}" :index 0}))
                            (when-let [on-done (:on-done opts)]
                              (on-done :tool-calls)))
                        (do (when-let [on-text (:on-text opts)]
                              (on-text (if (= 2 @calls) "second" "third")))
                            (when-let [on-done (:on-done opts)]
                              (on-done :stop))))
                      :done))
                  tools/execute-tool
                  (fn [_ _]
                    (loop/follow-up! agent "followup")
                    {:content "ok" :is-error false})]
      @(loop/run-agent-turn agent
         {:message "start"
          :on-done (fn [_] (swap! done-count inc))
          :on-error (fn [_])}))
    (t/is (= 3 @calls) "three LLM calls: initial, tool follow-up, queued follow-up")
    (t/is (= 1 @done-count) "on-done called once, at the very end")
    (let [ctx (loop/get-context agent)]
      (t/is (some #(= "followup" (get-in % [:content 0 :text])) ctx)
            "follow-up message should be in context")
      (t/is (some #(and (= :assistant (:role %))
                        (= "second" (get-in % [:content 0 :text]))) ctx))
      (t/is (some #(and (= :assistant (:role %))
                        (= "third" (get-in % [:content 0 :text]))) ctx)))
    (t/is (empty? @(:follow-up agent)) "follow-up queue drained")
    (t/is (= :idle @(:status agent)) "agent idle after run")))

(t/deftest test-loop-steer-one-at-a-time
  (let [calls (atom 0)
        agent (loop/make-agent-state :steering-mode :one-at-a-time)]
    (with-redefs [cfg/get-api-key (fn [_] "test-key")
                  llm/send-message
                  (fn [opts]
                    (future
                      (if (= 1 (swap! calls inc))
                        (do (when-let [on-tc (:on-tool-call opts)]
                              (on-tc {:id "tc1" :name "bash" :arguments "{}" :index 0}))
                            (when-let [on-done (:on-done opts)]
                              (on-done :tool-calls)))
                        (do (when-let [on-text (:on-text opts)]
                              (on-text "resp"))
                            (when-let [on-done (:on-done opts)]
                              (on-done :stop))))
                      :done))
                  tools/execute-tool
                  (fn [_ _]
                    (loop/steer! agent "s1")
                    (loop/steer! agent "s2")
                    {:content "ok" :is-error false})]
      @(loop/run-agent-turn agent
         {:message "start"
          :on-done (fn [_])
          :on-error (fn [_])}))
    (t/is (= 3 @calls) "three LLM calls: initial + one per steered message")
    (t/is (some #(= "s1" (get-in % [:content 0 :text])) (loop/get-context agent)))
    (t/is (some #(= "s2" (get-in % [:content 0 :text])) (loop/get-context agent)))
    (t/is (empty? @(:steering agent)) "steering queue drained")
    (t/is (= :idle @(:status agent)))))

;; ─── Queue modes (per-queue) ───────────────────────────────────────────────

(t/deftest test-loop-queue-modes-default
  (let [agent (loop/make-agent-state)]
    (t/is (= :all (:steering-mode agent)))
    (t/is (= :all (:follow-up-mode agent)))))

(t/deftest test-loop-followup-one-at-a-time
  (let [calls (atom 0)
        agent (loop/make-agent-state :follow-up-mode :one-at-a-time)]
    (with-redefs [cfg/get-api-key (fn [_] "test-key")
                  llm/send-message
                  (fn [opts]
                    (future
                      (if (= 1 (swap! calls inc))
                        (do (when-let [on-tc (:on-tool-call opts)]
                              (on-tc {:id "tc1" :name "bash" :arguments "{}" :index 0}))
                            (when-let [on-done (:on-done opts)]
                              (on-done :tool-calls)))
                        (do (when-let [on-text (:on-text opts)]
                              (on-text "resp"))
                            (when-let [on-done (:on-done opts)]
                              (on-done :stop))))
                      :done))
                  tools/execute-tool
                  (fn [_ _]
                    (loop/follow-up! agent "f1")
                    (loop/follow-up! agent "f2")
                    {:content "ok" :is-error false})]
      @(loop/run-agent-turn agent
         {:message "start"
          :on-done (fn [_])
          :on-error (fn [_])}))
    (t/is (= 4 @calls) "four LLM calls: initial + tool + one per follow-up")
    (t/is (empty? @(:follow-up agent)) "follow-up queue drained")))

;; ─── Cancellation ─────────────────────────────────────────────────────────

(t/deftest test-loop-cancel-delivers-promise
  (let [errors (atom [])
        dones (atom 0)
        agent (loop/make-agent-state)]
    (with-redefs [cfg/get-api-key (fn [_] "test-key")
                  llm/send-message
                  ;; hangs forever: on-done/on-error never fire
                  (fn [_] (future (Thread/sleep 10000) :never))]
      (let [fut (loop/run-agent-turn agent
                  {:message "hi"
                   :on-done (fn [_] (swap! dones inc))
                   :on-error (fn [e] (swap! errors conj e))})]
        ;; wait until the LLM call is in flight
        (loop []
          (when (nil? @(:active-call agent))
            (Thread/sleep 20)
            (recur)))
        (let [start (System/currentTimeMillis)]
          (loop/cancel-turn agent)
          @fut
          (t/is (< (- (System/currentTimeMillis) start) 5000)
                "run ends promptly after cancel (no 120s timeout wait)")
          (t/is (empty? @errors) "no error callback on cancel")
          (t/is (zero? @dones) "no done callback on cancel")
          (t/is (= :idle @(:status agent)) "status idle after cancel"))))))

;; ─── Error events ─────────────────────────────────────────────────────────

(t/deftest test-loop-error-event-emitted
  (let [events (atom [])
        agent (loop/make-agent-state :on-event (fn [e] (swap! events conj e)))]
    (with-redefs [cfg/get-api-key (fn [_] "test-key")
                  llm/send-message
                  (fn [opts]
                    (future
                      (when-let [on-error (:on-error opts)]
                        (on-error "boom"))
                      :done))]
      @(loop/run-agent-turn agent
         {:message "hi"
          :on-done (fn [_])
          :on-error (fn [_])}))
    (let [types (mapv :type @events)]
      (t/is (some #{:error} types) ":error event emitted on LLM error")
      (t/is (some #(and (= :agent-end (:type %)) (= "boom" (:error %))) @events)
            ":agent-end carries the error"))))

(t/deftest test-loop-cancel-during-tool-execution
  (let [errors (atom [])
        dones (atom 0)
        agent (loop/make-agent-state)]
    (with-redefs [cfg/get-api-key (fn [_] "test-key")
                  llm/send-message
                  (fn [opts]
                    (future
                      (when-let [on-tc (:on-tool-call opts)]
                        (on-tc {:id "tc1" :name "bash" :arguments "{}" :index 0}))
                      (when-let [on-done (:on-done opts)]
                        (on-done :tool-calls))
                      :done))
                  tools/execute-tool
                  (fn [_ _]
                    (Thread/sleep 2000)
                    {:content "ok" :is-error false})]
      (let [fut (loop/run-agent-turn agent
                  {:message "hi"
                   :on-done (fn [_] (swap! dones inc))
                   :on-error (fn [e] (swap! errors conj e))})]
        ;; wait until the tool is executing
        (loop []
          (when-not (= :executing @(:status agent))
            (Thread/sleep 20)
            (recur)))
        (let [start (System/currentTimeMillis)]
          (loop/cancel-turn agent)
          @fut
          (t/is (< (- (System/currentTimeMillis) start) 5000)
                "run ends promptly after cancel during tool execution")
          (t/is (empty? @errors) "no error callback on cancel")
          (t/is (zero? @dones) "no done callback on cancel")
          (t/is (= :idle @(:status agent)) "status idle after cancel"))))))
