(ns kmet.test-loop
  (:require [clojure.test :as t]
            [kmet.agent.loop :as loop]))

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
    (t/is (nil? (:session agent)))))

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

(t/deftest test-loop-on-event
  (let [events (atom [])
        agent (loop/make-agent-state :on-event (fn [e] (swap! events conj e)))]
    (reset! (:status agent) :thinking)
    (t/is (= :thinking @(:status agent)))))

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
