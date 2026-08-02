(ns kmet.app.test-loop
  (:require [clojure.test :as t]
            [clojure.string :as str]
            [babashka.fs :as fs]
            [kmet.app.llm :as llm]
            [kmet.app.tools.core :as tools]
            [kmet.app.extensions :as extensions]
            [kmet.app.event-bus :as event-bus]
            [kmet.app.session :as session]
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
        errors (atom [])
        done (promise)]
    (loop/run-agent-turn agent
                         {:message "hello"
                          :on-error (fn [e] (swap! errors conj e) (deliver done true))})
    (t/is (true? (deref done 2000 :timeout)) "error callback fires")
    (t/is (pos? (count @errors)))
    (t/is (.contains (first @errors) "No API key"))))

;; ─── run-agent-turn with valid state ─────────────────────────────────────

(t/deftest test-loop-run-agent-turn-structure
  (let [agent (loop/make-agent-state)
        called (atom false)
        fut (loop/run-agent-turn agent
                                 {:message "hello"
                                  :on-text (fn [_] (reset! called true))
                                  :on-done (fn [_] (reset! called true))
                                  :on-error (fn [_] (reset! called true))})]
    (t/is (future? fut))
    @fut
    (t/is @called)))

;; ─── Multiple turns ──────────────────────────────────────────────────────

(t/deftest test-loop-messages-accumulate
  (let [agent (loop/make-agent-state)]
    (t/is (empty? @(:messages agent)))
    (swap! (:messages agent) conj {:role :user :content [{:type :text :text "hi"}]})
    (t/is (= 1 (count @(:messages agent))))))

;; ─── Regression: run-agent-turn signals cleanup ─────────────────────────

(t/deftest ^:slow test-loop-run-agent-turn-resets-signal
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

(t/deftest ^:slow test-loop-status-after-error
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
        dereg (event-bus/on-event :status (fn [e] (swap! received conj e)))
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

(t/deftest ^:slow test-loop-tool-execution-events
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
                  (fn [_ _ _] (Thread/sleep 250) {:content "ok" :is-error false})]
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

;; ─── before-agent-start hooks ─────────────────────────────────────────────

(t/deftest test-loop-before-agent-start-hooks
  (let [events (atom [])
        sent (atom nil)
        agent (loop/make-agent-state :on-event (fn [e] (swap! events conj e)))]
    (extensions/clear-before-agent-start-hooks!)
    (extensions/register-before-agent-start-hook!
     (fn [_]
       {:system-prompt "EXTRA SYSTEM PROMPT"
        :message {:role :info :label "ext" :content "injected note"}}))
    (with-redefs [cfg/get-api-key (fn [_] "test-key")
                  llm/send-message
                  (fn [opts]
                    (reset! sent opts)
                    (future
                      (when-let [on-done (:on-done opts)]
                        (on-done :stop))
                      :done))]
      ;; deref inside with-redefs keeps the rebinding until the turn completes
      @(loop/run-agent-turn agent
                            {:message "hi"
                             :on-done (fn [_])
                             :on-error (fn [_])}))
    (extensions/clear-before-agent-start-hooks!)
    (t/is (str/includes? (get-in @sent [:messages 0 :content 0 :text])
                         "EXTRA SYSTEM PROMPT")
          "before-agent-start system prompt override reaches the LLM call")
    (t/is (some #(and (= :message-start (:type %))
                      (= :info (:role (:message %))))
                @events)
          ":message-start is emitted for the injected message")
    (t/is (= 1 (count (filter #(= :info (:role %)) @(:messages agent))))
          "injected :info message stays in context")
    (t/is (= [{:type :text :text "injected note"}]
             (:content (first (filter #(= :info (:role %)) @(:messages agent)))))
          "string content is normalized to text blocks")
    (t/is (not-any? #(= :info (:role %)) (:messages @sent))
          "display-only :info messages are excluded from the LLM context")))

;; ─── User message images ──────────────────────────────────────────────────

(t/deftest test-loop-user-message-images
  (let [sent (atom nil)
        agent (loop/make-agent-state)]
    (with-redefs [cfg/get-api-key (fn [_] "test-key")
                  llm/send-message
                  (fn [opts]
                    (reset! sent opts)
                    (future
                      (when-let [on-done (:on-done opts)]
                        (on-done :stop))
                      :done))]
      @(loop/run-agent-turn agent
                            {:message "look at this"
                             :images [{:type :image :data "AA" :mime-type "image/png"}]
                             :on-done (fn [_])
                             :on-error (fn [_])}))
    (let [user-msg (first (filter #(= :user (:role %)) @(:messages agent)))]
      (t/is (= [{:type :text :text "look at this"}
                {:type :image :data "AA" :mime-type "image/png"}]
               (:content user-msg))
            "user message carries text + image blocks"))
    (let [llm-user (first (filter #(= :user (:role %)) (:messages @sent)))]
      (t/is (= [{:type :text :text "look at this"}
                {:type :image :data "AA" :mime-type "image/png"}]
               (:content llm-user))
            "image blocks reach the LLM call in kmet message format"))))

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
                  (fn [_ _ _]
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
                  (fn [_ _ _]
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
                  (fn [_ _ _]
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
                  (fn [_ _ _]
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

(t/deftest ^:slow test-loop-cancel-during-tool-execution
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
                  (fn [_ _ _]
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

;; ─── Retry classification ─────────────────────────────────────────────────

(t/deftest test-loop-retryable-error?
  (t/is (loop/retryable-error? "rate limit exceeded"))
  (t/is (loop/retryable-error? "429 Too Many Requests"))
  (t/is (loop/retryable-error? "503 service unavailable"))
  (t/is (loop/retryable-error? "Internal Server Error"))
  (t/is (loop/retryable-error? "connection refused"))
  (t/is (loop/retryable-error? "ECONNRESET: socket hang up"))
  (t/is (loop/retryable-error? "Request timed out"))
  (t/is (loop/retryable-error? "Provider returned error: upstream connect"))
  (t/is (not (loop/retryable-error? "insufficient_quota")))
  (t/is (not (loop/retryable-error? "Monthly usage limit reached")))
  (t/is (not (loop/retryable-error? "GoUsageLimitError")))
  (t/is (not (loop/retryable-error? "Invalid API key provided")))
  (t/is (not (loop/retryable-error? nil)))
  (t/is (not (loop/retryable-error? ""))))

(t/deftest test-loop-context-overflow?
  (t/is (loop/context-overflow? "prompt is too long: 213462 tokens > 200000 maximum"))
  (t/is (loop/context-overflow? "This model's maximum context length is 128000 tokens"))
  (t/is (loop/context-overflow? "Your input exceeds the context window of this model"))
  (t/is (loop/context-overflow? "context_length_exceeded"))
  (t/is (loop/context-overflow? "exceeded model token limit: 100000 (requested: 200000)"))
  (t/is (not (loop/context-overflow? "rate limit exceeded")))
  (t/is (not (loop/context-overflow? "Throttling error: Too many tokens, please wait")))
  (t/is (not (loop/context-overflow? nil))))

;; ─── Tool hooks (before/after tool-call) ─────────────────────────────────

(defn- stub-llm-tool-then-text
  "send-message stub: first call streams one tool call, subsequent calls a plain text reply."
  [call-count]
  (fn [opts]
    (future
      (if (= 1 (swap! call-count inc))
        (do (when-let [on-tc (:on-tool-call opts)]
              (on-tc {:id "tc1" :name "bash" :arguments "{}" :index 0}))
            (when-let [on-done (:on-done opts)]
              (on-done :tool-calls)))
        (do (when-let [on-text (:on-text opts)]
              (on-text "ok"))
            (when-let [on-done (:on-done opts)]
              (on-done :stop))))
      :done)))

(t/deftest test-loop-before-tool-call-blocks
  (let [events (atom [])
        executed (atom false)
        agent (loop/make-agent-state :on-event (fn [e] (swap! events conj e)))]
    (loop/set-before-tool-call! agent
                                (fn [_] {:block true :reason "Permission denied"}))
    (with-redefs [cfg/get-api-key (fn [_] "test-key")
                  llm/send-message (stub-llm-tool-then-text (atom 0))
                  tools/execute-tool (fn [_ _ _] (reset! executed true)
                                       {:content "should not run" :is-error false})]
      @(loop/run-agent-turn agent {:message "run" :on-error (fn [_])}))
    (t/is (false? @executed) "blocked tool must not execute")
    (let [end (first (filter #(= :tool-execution-end (:type %)) @events))]
      (t/is (= "tc1" (:tool-call-id end)))
      (t/is (true? (:is-error end)) "blocked tool result is an error")
      (t/is (= "Permission denied" (:content (:result end)))))))

(t/deftest test-loop-before-tool-call-hook-throws
  (let [events (atom [])
        agent (loop/make-agent-state :on-event (fn [e] (swap! events conj e)))]
    (loop/set-before-tool-call! agent
                                (fn [_] (throw (ex-info "hook boom" {}))))
    (with-redefs [cfg/get-api-key (fn [_] "test-key")
                  llm/send-message (stub-llm-tool-then-text (atom 0))
                  tools/execute-tool (fn [_ _ _] {:content "ok" :is-error false})]
      @(loop/run-agent-turn agent {:message "run" :on-error (fn [_])}))
    (let [end (first (filter #(= :tool-execution-end (:type %)) @events))]
      (t/is (true? (:is-error end)))
      (t/is (.contains (:content (:result end)) "before-tool-call hook error")))))

(t/deftest test-loop-after-tool-call-rewrites
  (let [events (atom [])
        agent (loop/make-agent-state :on-event (fn [e] (swap! events conj e)))]
    (loop/set-after-tool-call! agent
                               (fn [{:keys [result]}]
                                 {:content (str (:content result) " [sanitized]")}))
    (with-redefs [cfg/get-api-key (fn [_] "test-key")
                  llm/send-message (stub-llm-tool-then-text (atom 0))
                  tools/execute-tool (fn [_ _ _] {:content "secret-key=abc" :is-error false})]
      @(loop/run-agent-turn agent {:message "run" :on-error (fn [_])}))
    (let [end (first (filter #(= :tool-execution-end (:type %)) @events))]
      (t/is (= "secret-key=abc [sanitized]" (:content (:result end))))
      (t/is (false? (:is-error end)) "rewrite preserves is-error unless overridden"))))

(t/deftest test-loop-after-tool-call-sets-error
  (let [events (atom [])
        agent (loop/make-agent-state :on-event (fn [e] (swap! events conj e)))]
    (loop/set-after-tool-call! agent
                               (fn [{:keys [result]}]
                                 {:content (:content result) :is-error true}))
    (with-redefs [cfg/get-api-key (fn [_] "test-key")
                  llm/send-message (stub-llm-tool-then-text (atom 0))
                  tools/execute-tool (fn [_ _ _] {:content "ok" :is-error false})]
      @(loop/run-agent-turn agent {:message "run" :on-error (fn [_])}))
    (let [end (first (filter #(= :tool-execution-end (:type %)) @events))]
      (t/is (true? (:is-error end)) "after hook can mark a result as error"))))

(t/deftest test-loop-after-tool-call-hook-throws
  (let [events (atom [])
        agent (loop/make-agent-state :on-event (fn [e] (swap! events conj e)))]
    (loop/set-after-tool-call! agent
                               (fn [_] (throw (ex-info "hook boom" {}))))
    (with-redefs [cfg/get-api-key (fn [_] "test-key")
                  llm/send-message (stub-llm-tool-then-text (atom 0))
                  tools/execute-tool (fn [_ _ _] {:content "ok" :is-error false})]
      @(loop/run-agent-turn agent {:message "run" :on-error (fn [_])}))
    (let [end (first (filter #(= :tool-execution-end (:type %)) @events))]
      (t/is (true? (:is-error end)))
      (t/is (.contains (:content (:result end)) "after-tool-call hook error")))))

;; ─── Auto-retry ───────────────────────────────────────────────────────────

(t/deftest test-loop-auto-retry-succeeds
  (let [events (atom [])
        call-count (atom 0)
        agent (loop/make-agent-state
               :on-event (fn [e] (swap! events conj e))
               :max-retries 2
               :base-delay-ms 1)]
    (with-redefs [cfg/get-api-key (fn [_] "test-key")
                  llm/send-message
                  (fn [opts]
                    (future
                      (case (swap! call-count inc)
                        1 (when-let [on-error (:on-error opts)]
                            (on-error "upstream connect error"))
                        2 (when-let [on-error (:on-error opts)]
                            (on-error "502 Bad Gateway"))
                        (do (when-let [on-text (:on-text opts)]
                              (on-text "recovered"))
                            (when-let [on-done (:on-done opts)]
                              (on-done :stop))))
                      :done))]
      @(loop/run-agent-turn agent {:message "hi" :on-done (fn [_]) :on-error (fn [_])}))
    (let [starts (filter #(= :auto-retry-start (:type %)) @events)
          ends (filter #(= :auto-retry-end (:type %)) @events)]
      (t/is (= 2 (count starts)) "one retry start per transient failure")
      (t/is (= 1 (:attempt (first starts))))
      (t/is (= 2 (:attempt (second starts))))
      (t/is (= 2 (:max-attempts (first starts))))
      (t/is (pos? (:delay-ms (first starts))) "backoff delay reported")
      (t/is (= 1 (count ends)))
      (t/is (true? (:success (first ends))))
      (t/is (= 2 (:attempt (first ends)))))
    (t/is (= :idle (loop/get-status agent)))
    ;; The retried stream's text is the final assistant message
    (t/is (some #(and (= :message-end (:type %))
                      (= "recovered" (get-in % [:message :content 0 :text])))
                @events))))

(t/deftest test-loop-auto-retry-exhausted
  (let [events (atom [])
        errors (atom [])
        agent (loop/make-agent-state
               :on-event (fn [e] (swap! events conj e))
               :max-retries 1
               :base-delay-ms 1)]
    (with-redefs [cfg/get-api-key (fn [_] "test-key")
                  llm/send-message
                  (fn [opts]
                    (future
                      (when-let [on-error (:on-error opts)]
                        (on-error "503 service unavailable"))
                      :done))]
      @(loop/run-agent-turn agent {:message "hi" :on-error (fn [e] (swap! errors conj e))}))
    (t/is (= 1 (count @errors)) "only the terminal error surfaces via on-error")
    (let [ends (filter #(= :auto-retry-end (:type %)) @events)]
      (t/is (= 1 (count ends)))
      (t/is (false? (:success (first ends))))
      (t/is (= 1 (:attempt (first ends))))
      (t/is (= "503 service unavailable" (:final-error (first ends)))))
    (t/is (= :error (loop/get-status agent)))
    (t/is (some #(= :error (:type %)) @events) "terminal :error event emitted")))

(t/deftest test-loop-no-retry-on-quota-error
  (let [events (atom [])
        errors (atom [])
        agent (loop/make-agent-state
               :on-event (fn [e] (swap! events conj e))
               :max-retries 3
               :base-delay-ms 1)]
    (with-redefs [cfg/get-api-key (fn [_] "test-key")
                  llm/send-message
                  (fn [opts]
                    (future
                      (when-let [on-error (:on-error opts)]
                        (on-error "insufficient_quota"))
                      :done))]
      @(loop/run-agent-turn agent {:message "hi" :on-error (fn [e] (swap! errors conj e))}))
    (t/is (= 1 (count @errors)) "quota errors are never retried")
    (t/is (not-any? #(= :auto-retry-start (:type %)) @events))))

(t/deftest test-loop-no-retry-on-context-overflow
  (let [events (atom [])
        agent (loop/make-agent-state
               :on-event (fn [e] (swap! events conj e))
               :max-retries 3
               :base-delay-ms 1)]
    (with-redefs [cfg/get-api-key (fn [_] "test-key")
                  llm/send-message
                  (fn [opts]
                    (future
                      (when-let [on-error (:on-error opts)]
                        (on-error "prompt is too long: 213462 tokens > 200000 maximum"))
                      :done))]
      @(loop/run-agent-turn agent {:message "hi" :on-error (fn [_])}))
    (t/is (not-any? #(= :auto-retry-start (:type %)) @events)
          "overflow is not retried (compaction handles it)")))

(t/deftest ^:slow test-loop-retry-cancel-during-backoff
  (let [events (atom [])
        agent (loop/make-agent-state
               :on-event (fn [e] (swap! events conj e))
               :max-retries 3
               :base-delay-ms 5000)]
    (with-redefs [cfg/get-api-key (fn [_] "test-key")
                  llm/send-message
                  (fn [opts]
                    (future
                      (when-let [on-error (:on-error opts)]
                        (on-error "connection lost"))
                      :done))]
      (let [fut (loop/run-agent-turn agent {:message "hi" :on-error (fn [_])})]
        (Thread/sleep 200)
        (loop/cancel-turn agent)
        @fut))
    (let [ends (filter #(= :auto-retry-end (:type %)) @events)]
      (t/is (= 1 (count ends)))
      (t/is (false? (:success (first ends))))
      (t/is (= "Retry cancelled" (:final-error (first ends)))))
    (t/is (= :idle (loop/get-status agent)) "run settles idle after cancel during backoff")))

(t/deftest ^:slow test-loop-retry-resets-count-on-success-then-error
  (let [events (atom [])
        call-count (atom 0)
        agent (loop/make-agent-state
               :on-event (fn [e] (swap! events conj e))
               :max-retries 1
               :base-delay-ms 500)]
    (with-redefs [cfg/get-api-key (fn [_] "test-key")
                  llm/send-message
                  (fn [opts]
                    (future
                      (case (swap! call-count inc)
                        1 (when-let [on-error (:on-error opts)]
                            (on-error "timeout"))
                        2 (do (when-let [on-text (:on-text opts)]
                                (on-text "first success"))
                              (when-let [on-done (:on-done opts)]
                                (on-done :stop)))
                        3 (when-let [on-error (:on-error opts)]
                            (on-error "503 service unavailable"))
                        (when-let [on-error (:on-error opts)]
                          (on-error "503 service unavailable")))
                      :done))]
      ;; Turn 1 fails once, retries after 500ms backoff, succeeds.
      ;; Queue a follow-up during the backoff so the run continues into turn 2.
      (let [fut (loop/run-agent-turn agent {:message "hi" :on-error (fn [_])})]
        (Thread/sleep 100)
        (loop/follow-up! agent "second")
        @fut))
    (let [ends (filter #(= :auto-retry-end (:type %)) @events)]
      ;; First turn: retry succeeded (1 end, success). Second turn: 503 is
      ;; retryable but budget is fresh — one more retry → exhausted.
      (t/is (= 2 (count ends)))
      (t/is (true? (:success (first ends))))
      (t/is (false? (:success (second ends)))))))

;; ─── Phase 3: model management ───────────────────────────────────────────

(t/deftest test-loop-make-agent-state-phase3-opts
  (let [agent (loop/make-agent-state)]
    (t/is (instance? clojure.lang.Atom (:system-prompt-override agent)))
    (t/is (instance? clojure.lang.Atom (:transform-context agent)))
    (t/is (instance? clojure.lang.Atom (:prepare-next-turn agent)))
    (t/is (instance? clojure.lang.Atom (:should-stop-after-turn agent)))
    (t/is (instance? clojure.lang.Atom (:get-api-key agent)))
    (t/is (= [] @(:models agent)))
    (t/is (false? @(:overflow-recovered agent)))
    (t/is (nil? (:compact-token-threshold agent)))
    (t/is (= 3 (:max-retries agent)) "pi default maxRetries")))

(t/deftest test-loop-cycle-model
  (let [events (atom [])
        agent (loop/make-agent-state
               :model "a" :models ["a" "b" "c"]
               :on-event (fn [e] (swap! events conj e)))]
    (t/is (= "b" (loop/cycle-model! agent 1)))
    (t/is (= "c" (loop/cycle-model! agent 1)))
    (t/is (= "a" (loop/cycle-model! agent 1)) "wraps around at the end")
    (t/is (= "c" (loop/cycle-model! agent -1)) "cycles backward")
    (t/is (= "b" (loop/cycle-model! agent -1)))
    (let [ev (last @events)]
      (t/is (= :model-select (:type ev)) "cycle emits :model-select")
      (t/is (= :cycle (:source ev)))
      (t/is (= "b" (:model ev)))
      (t/is (= "c" (:previous-model ev))))))

(t/deftest test-loop-cycle-model-no-models
  (let [agent (loop/make-agent-state :model "a")]
    (t/is (nil? (loop/cycle-model! agent 1)) "no scoped models → nil")))

(t/deftest test-loop-set-model-emits-model-select
  (let [events (atom [])
        agent (loop/make-agent-state :model "a" :on-event (fn [e] (swap! events conj e)))]
    (loop/set-model! agent "b")
    (let [ev (last @events)]
      (t/is (= :model-select (:type ev)))
      (t/is (= "b" (:model ev)))
      (t/is (= "a" (:previous-model ev)))
      (t/is (= :set (:source ev))))
    ;; setting the same model is a no-op (no event)
    (loop/set-model! agent "b")
    (t/is (= 1 (count (filter #(= :model-select (:type %)) @events))))))

;; ─── Phase 3: transform-context ──────────────────────────────────────────

(t/deftest test-loop-transform-context
  (let [seen (atom nil)
        agent (loop/make-agent-state)]
    (loop/set-transform-context! agent
                                 (fn [messages]
                                   (reset! seen messages)
                                   (conj messages {:role :user :content [{:type :text :text "injected"}]})))
    (with-redefs [cfg/get-api-key (fn [_] "test-key")
                  llm/send-message
                  (fn [opts]
                    (future
                      (let [msgs (:messages opts)]
                        (t/is (= :system (:role (first msgs))) "system prompt prepended after transform")
                        (t/is (some #(= "injected" (get-in % [:content 0 :text])) (rest msgs))
                              "transformed message visible to the LLM"))
                      (when-let [on-text (:on-text opts)] (on-text "ok"))
                      (when-let [on-done (:on-done opts)] (on-done :stop))
                      :done))]
      @(loop/run-agent-turn agent {:message "hi" :on-error (fn [_])}))
    (t/is (some #(= "hi" (get-in % [:content 0 :text])) @seen)
          "hook receives the original conversation (no system prompt)")
    (t/is (not-any? #(= :system (:role %)) @seen)
          "transform-context sees only conversation messages")))

;; ─── Phase 3: system prompt override ─────────────────────────────────────

(t/deftest test-loop-system-prompt-override
  (let [override-msgs (atom [])
        holder (atom nil)
        ;; The extension sets the override on :agent-start (pi: before_agent_start)
        agent (loop/make-agent-state
               :system "base prompt"
               :on-event (fn [evt]
                           (when (= :agent-start (:type evt))
                             (loop/set-system-prompt-override! @holder "override prompt"))))]
    (reset! holder agent)
    (with-redefs [cfg/get-api-key (fn [_] "test-key")
                  llm/send-message
                  (fn [opts]
                    (future
                      (swap! override-msgs conj (get-in opts [:messages 0 :content 0 :text]))
                      (when-let [on-text (:on-text opts)] (on-text "ok"))
                      (when-let [on-done (:on-done opts)] (on-done :stop))
                      :done))]
      @(loop/run-agent-turn agent {:message "hi" :on-error (fn [_])}))
    (t/is (= ["override prompt"] @override-msgs)
          "override preferred over base system prompt")
    (t/is (= "override prompt" @(:system-prompt-override agent))
          "override persists for the duration of the run")
    ;; Next run resets the override at start (pi: prompt() resets per run)
    (loop/set-system-prompt-override! agent "override prompt")
    (loop/set-system-prompt-override! agent nil)
    (t/is (nil? @(:system-prompt-override agent)))))

;; ─── Phase 3: prepareNextTurn / shouldStopAfterTurn ──────────────────────

(t/deftest test-loop-prepare-next-turn-updates-state
  (let [agent (loop/make-agent-state :model "model-a" :thinking :off)]
    (loop/set-prepare-next-turn! agent
                                 (fn [_] {:model "model-b" :thinking :high :system-prompt-override "custom"}))
    (with-redefs [cfg/get-api-key (fn [_] "test-key")
                  llm/send-message (stub-llm-tool-then-text (atom 0))
                  tools/execute-tool (fn [_ _ _] {:content "ok" :is-error false})]
      @(loop/run-agent-turn agent {:message "run" :on-error (fn [_])}))
    (t/is (= "model-b" @(:model agent)) "prepare-next-turn swaps the model")
    (t/is (= :high @(:thinking agent)) "prepare-next-turn updates thinking level")
    (t/is (= "custom" @(:system-prompt-override agent))
          "prepare-next-turn sets the system prompt override for later turns")))

(t/deftest ^:slow test-loop-should-stop-after-turn
  (let [calls (atom 0)
        agent (loop/make-agent-state)]
    (loop/set-should-stop-after-turn! agent (fn [_] true))
    (with-redefs [cfg/get-api-key (fn [_] "test-key")
                  llm/send-message
                  (fn [opts]
                    (future
                      (swap! calls inc)
                      (when-let [on-tc (:on-tool-call opts)]
                        (on-tc {:id "tc1" :name "bash" :arguments "{}" :index 0}))
                      (when-let [on-done (:on-done opts)]
                        (on-done :tool-calls))
                      :done))]
      @(loop/run-agent-turn agent {:message "run" :on-error (fn [_])}))
    (t/is (= 1 @calls) "loop stops after the first turn")
    (t/is (= :idle (loop/get-status agent)))))

;; ─── Phase 3: parallel tool execution ────────────────────────────────────

(defn- stub-llm-two-tool-calls-then-text
  "send-message stub: first call streams two tool calls, subsequent calls a plain reply."
  [call-count]
  (fn [opts]
    (future
      (if (= 1 (swap! call-count inc))
        (do (when-let [on-tc (:on-tool-call opts)]
              (on-tc {:id "tc1" :name "bash" :arguments "{}" :index 0})
              (on-tc {:id "tc2" :name "bash" :arguments "{}" :index 1}))
            (when-let [on-done (:on-done opts)]
              (on-done :tool-calls)))
        (do (when-let [on-text (:on-text opts)]
              (on-text "ok"))
            (when-let [on-done (:on-done opts)]
              (on-done :stop))))
      :done)))

(t/deftest ^:slow test-loop-parallel-tool-execution
  (let [events (atom [])
        agent (loop/make-agent-state :on-event (fn [e] (swap! events conj e)))]
    (with-redefs [cfg/get-api-key (fn [_] "test-key")
                  llm/send-message (stub-llm-two-tool-calls-then-text (atom 0))
                  tools/execute-tool
                  (fn [_ _ _]
                    (Thread/sleep 400)
                    {:content "ok" :is-error false})]
      (let [start (System/currentTimeMillis)]
        @(loop/run-agent-turn agent {:message "run" :on-error (fn [_])})
        (t/is (< (- (System/currentTimeMillis) start) 700)
              "two 400ms tools run concurrently in well under 700ms")))
    (t/is (= 2 (count (filter #(= :tool-execution-start (:type %)) @events))))
    (t/is (= 2 (count (filter #(= :tool-execution-end (:type %)) @events))))
    (let [te (first (filter #(= :turn-end (:type %)) @events))]
      (t/is (= 2 (count (:tool-results te))) "turn-end carries both results"))
    (t/is (= 2 (count (filter #(= :tool (:role %)) (loop/get-context agent))))
          "both tool results appended to context")))

(t/deftest ^:slow test-loop-sequential-tool-execution
  (let [seq-calls (atom 0)
        agent (loop/make-agent-state)]
    (tools/register-tool!
     (tools/make-tool :name "seq-tool" :label "Seq"
                      :description "sequential test tool"
                      :parameters {:x (tools/param :x :string "x")}
                      :execute (fn [_] {:content "seq" :is-error false})
                      :execution-mode :sequential))
    (try
      (with-redefs [cfg/get-api-key (fn [_] "test-key")
                    llm/send-message
                    (fn [opts]
                      (future
                        (if (= 1 (swap! seq-calls inc))
                          (do (when-let [on-tc (:on-tool-call opts)]
                                (on-tc {:id "t1" :name "seq-tool" :arguments "{}" :index 0})
                                (on-tc {:id "t2" :name "seq-tool" :arguments "{}" :index 1}))
                              (when-let [on-done (:on-done opts)]
                                (on-done :tool-calls)))
                          (do (when-let [on-text (:on-text opts)]
                                (on-text "ok"))
                              (when-let [on-done (:on-done opts)]
                                (on-done :stop))))
                        :done))
                    tools/execute-tool
                    (fn [_ _ _]
                      (Thread/sleep 400)
                      {:content "seq" :is-error false})]
        (let [start (System/currentTimeMillis)]
          @(loop/run-agent-turn agent {:message "run" :on-error (fn [_])})
          (t/is (>= (- (System/currentTimeMillis) start) 700)
                "a :sequential tool forces the whole batch sequential (~800ms for 2×400ms)")))
      (finally
        (tools/unregister-tool! "seq-tool")))))

;; ─── Phase 3: compaction ─────────────────────────────────────────────────

(t/deftest test-loop-overflow-compact-retry
  (let [events (atom [])
        main-call-count (atom 0)
        dir (fs/create-temp-dir {:dir (System/getProperty "user.home")})
        sess (session/create-session (str dir))
        agent (loop/make-agent-state
               :on-event (fn [e] (swap! events conj e))
               :session sess
                ;; high entry threshold so the proactive check never fires;
                ;; only the overflow path compacts
               :compact-threshold 100
               :keep-recent-tokens 5)]
    (try
      (dotimes [i 12]
        (session/append-entry sess {:role :user :content [{:type :text :text (str "msg " i)}]}))
      (with-redefs [cfg/get-api-key (fn [_] "test-key")
                    llm/send-message
                    (fn [opts]
                      (future
                        (if (seq (:tools opts))
                          ;; main agent call
                          (case (swap! main-call-count inc)
                            1 (when-let [on-error (:on-error opts)]
                                (on-error "prompt is too long: 500000 tokens > 200000 maximum"))
                            (do (when-let [on-text (:on-text opts)]
                                  (on-text "recovered"))
                                (when-let [on-done (:on-done opts)]
                                  (on-done :stop))))
                          ;; summarization call (no tools)
                          (do (when-let [on-text (:on-text opts)]
                                (on-text "summary of the old conversation"))
                              (when-let [on-done (:on-done opts)]
                                (on-done :stop))))
                        :done))]
        (binding [*err* (java.io.StringWriter.)]
          @(loop/run-agent-turn agent {:message "hi" :on-error (fn [_])})))
      (t/is (= 2 @main-call-count) "overflow triggers one compaction then a retry")
      (t/is (< (count @(:entries sess)) 12) "session was compacted")
      (t/is (some #(and (= :message-end (:type %))
                        (= "recovered" (get-in % [:message :content 0 :text])))
                  @events)
            "retried call succeeds after compaction")
      (finally
        (fs/delete-tree dir)))))

;; ─── Phase 3: dynamic API key ────────────────────────────────────────────

(t/deftest test-loop-get-api-key-hook
  (let [agent (loop/make-agent-state)]
    (loop/set-get-api-key! agent (fn [_] "hook-key"))
    (with-redefs [cfg/get-api-key (fn [_] "cfg-key")
                  llm/send-message
                  (fn [opts]
                    (future
                      (t/is (= "hook-key" (:api-key opts))
                            "dynamic get-api-key hook preferred over cfg")
                      (when-let [on-text (:on-text opts)] (on-text "ok"))
                      (when-let [on-done (:on-done opts)] (on-done :stop))
                      :done))]
      @(loop/run-agent-turn agent {:message "hi" :on-error (fn [_])}))))

(t/deftest test-loop-token-threshold-compaction
  (let [dir (fs/create-temp-dir {:dir (System/getProperty "user.home")})
        sess (session/create-session (str dir))
        agent (loop/make-agent-state
               :session sess
               :compact-token-threshold 10
               :compact-threshold 1000
               :keep-recent-tokens 40)]
    (try
      (doseq [i (range 10)]
        (let [m {:role :user
                 :content [{:type :text :text
                            (str "This is message body number " i
                                 " with plenty of words so the estimated token count "
                                 "easily exceeds the small test threshold.")}]}]
          (swap! (:messages agent) conj m)
          (session/append-entry sess m)))
      (t/is (true? (binding [*err* (java.io.StringWriter.)]
                     (loop/maybe-compact! agent)))
            "token estimate above threshold triggers compaction")
      (t/is (< (count @(:entries sess)) 10) "session entries reduced")
      (t/is (< (count @(:messages agent)) 10) "in-memory context aligned with session")
      (t/is (= (count @(:messages agent)) (count @(:entries sess)))
            "messages and session entry counts match after compaction")
      (finally
        (fs/delete-tree dir)))))

(t/deftest test-loop-compact-no-op-reports-false
  (let [dir (fs/create-temp-dir {:dir (System/getProperty "user.home")})
        sess (session/create-session (str dir))
        agent (loop/make-agent-state :session sess :compact-threshold 1000)]
    (try
      (doseq [i (range 3)]
        (let [m {:role :user :content [{:type :text :text (str "msg " i)}]}]
          (swap! (:messages agent) conj m)
          (session/append-entry sess m)))
      (t/is (false? (loop/maybe-compact! agent))
            "below threshold → no compaction")
      (t/is (= 3 (count @(:entries sess))))
      (t/is (= 3 (count @(:messages agent))) "context untouched when no compaction")
      (finally
        (fs/delete-tree dir)))))

(t/deftest test-loop-get-api-key-resolved-per-call
  (let [keys-seen (atom [])
        llm-keys (atom [])
        calls (atom 0)
        agent (loop/make-agent-state)]
    (loop/set-get-api-key! agent
                           (fn [_] (swap! keys-seen conj (str "key-" (count @keys-seen)))))
    (with-redefs [cfg/get-api-key (fn [_] "cfg-key")
                  llm/send-message
                  (fn [opts]
                    (future
                      (swap! llm-keys conj (:api-key opts))
                      (if (= 1 (swap! calls inc))
                        (do (when-let [on-tc (:on-tool-call opts)]
                              (on-tc {:id "tc1" :name "bash" :arguments "{}" :index 0}))
                            (when-let [on-done (:on-done opts)]
                              (on-done :tool-calls)))
                        (do (when-let [on-text (:on-text opts)]
                              (on-text "ok"))
                            (when-let [on-done (:on-done opts)]
                              (on-done :stop))))
                      :done))
                  tools/execute-tool (fn [_ _ _] {:content "ok" :is-error false})]
      @(loop/run-agent-turn agent {:message "run" :on-error (fn [_])}))
    (t/is (= 2 (count @llm-keys)) "two LLM calls in the run")
    (t/is (apply not= @llm-keys)
          "each LLM call gets a freshly resolved key")
    (t/is (>= (count @keys-seen) 3)
          "hook also resolves at run start for the nil check")))

(t/deftest test-loop-prepare-next-turn-context-replaces-session
  (let [dir (fs/create-temp-dir {:dir (System/getProperty "user.home")})
        sess (session/create-session (str dir))
        agent (loop/make-agent-state :session sess)]
    (try
      (loop/set-prepare-next-turn! agent
                                   (fn [_] {:context [{:role :user :content [{:type :text :text "replacement"}]}]}))
      (with-redefs [cfg/get-api-key (fn [_] "test-key")
                    llm/send-message (stub-llm-tool-then-text (atom 0))
                    tools/execute-tool (fn [_ _ _] {:content "ok" :is-error false})]
        @(loop/run-agent-turn agent {:message "run" :on-error (fn [_])}))
      (t/is (= [{:role :user :content [{:type :text :text "replacement"}]}]
               (loop/get-context agent))
            "prepare-next-turn :context replaces the conversation")
      (t/is (= 1 (count @(:entries sess)))
            "session rebuilt to match the replaced context")
      (t/is (= "replacement" (get-in (first @(:entries sess)) [:content 0 :text]))
            "session entry mirrors the replacement message")
      (finally
        (fs/delete-tree dir)))))

(t/deftest test-loop-context-replaced-event
  (let [events (atom [])
        agent (loop/make-agent-state :on-event (fn [e] (swap! events conj e)))]
    (loop/set-prepare-next-turn! agent
                                 (fn [_] {:context [{:role :user :content [{:type :text :text "replacement"}]}]}))
    (with-redefs [cfg/get-api-key (fn [_] "test-key")
                  llm/send-message (stub-llm-tool-then-text (atom 0))
                  tools/execute-tool (fn [_ _ _] {:content "ok" :is-error false})]
      @(loop/run-agent-turn agent {:message "run" :on-error (fn [_])}))
    (let [evs (filter #(= :context-replaced (:type %)) @events)]
      (t/is (seq evs) ":context-replaced emitted on context replacement")
      (t/is (= "replacement" (get-in (last evs) [:messages 0 :content 0 :text]))
            "event carries the new conversation"))))
