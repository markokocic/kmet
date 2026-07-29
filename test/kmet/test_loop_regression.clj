(ns kmet.test-loop-regression
  "Regression tests for fixes in kmet.agent.loop."
  (:require [clojure.test :as t]
            [kmet.agent.loop :as loop]))

;; ─── Signal atom is set on timeout path ──────────────────────────────────
;; The signal atom must be reset before each turn and set on timeout
;; so the background LLM future stops firing callbacks.

(t/deftest test-loop-run-turn-resets-signal
  (let [agent (loop/make-agent-state)]
    (reset! (:signal agent) true)
    (let [errors (atom [])]
      (loop/run-agent-turn agent
        {:message "test"
         :on-error (fn [e] (swap! errors conj e))})
      (Thread/sleep 200)
      (t/is (false? @(:signal agent)) "Signal should be reset at start of turn"))))

(t/deftest test-loop-cancel-sets-signal
  (let [agent (loop/make-agent-state)]
    (reset! (:signal agent) false)
    (loop/cancel-turn agent)
    (t/is (true? @(:signal agent)) "cancel-turn should set signal to true")))

(t/deftest test-loop-run-turn-with-api-key-missing-signal-clean
  ;; When run-agent-turn completes (even with error), signal should be false
  (let [agent (loop/make-agent-state)
        done (promise)]
    (loop/run-agent-turn agent
      {:message "hi"
       :on-text (fn [_])
       :on-done (fn [_] (deliver done true))
       :on-error (fn [_] (deliver done true))})
    (deref done 2000 :timeout)
    (t/is (false? @(:signal agent)) "Signal should be false after turn ends")))

;; ─── Status transitions ──────────────────────────────────────────────────

(t/deftest test-loop-status-after-error
  (let [agent (loop/make-agent-state)]
    (reset! (:status agent) :idle)
    (loop/run-agent-turn agent
      {:message "hi"
       :on-error (fn [_])})
    (Thread/sleep 200)
    (t/is (= :idle @(:status agent)) "Status should be idle after error turn")
    ;; But if the turn encountered an error via on-error, status might be :error
    ;; depending on timing. The main thing is signal is false.
    (t/is (false? @(:signal agent)) "Signal should be false after error turn")))

;; ─── Multiple quick cancellations ────────────────────────────────────────

(t/deftest test-loop-multiple-cancel
  (let [agent (loop/make-agent-state)]
    (loop/cancel-turn agent)
    ;; cancel-turn sets signal=true to stop any in-flight LLM call
    (t/is (true? @(:signal agent)) "cancel-turn sets signal to true")
    ;; calling cancel-turn again is idempotent
    (loop/cancel-turn agent)
    (t/is (true? @(:signal agent)) "Signal remains true after second cancel")))

(t/deftest test-loop-cancel-resets-status
  (let [agent (loop/make-agent-state)]
    (reset! (:status agent) :thinking)
    (loop/cancel-turn agent)
    (t/is (= :idle @(:status agent)) "cancel-turn resets status to idle")))
