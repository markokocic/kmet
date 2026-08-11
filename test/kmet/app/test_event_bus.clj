(ns kmet.app.test-event-bus
  (:require [clojure.test :as t]
            [clojure.string :as str]
            [kmet.app.event-bus :as event-bus]))

;; ─── Event vocabulary ─────────────────────────────────────────────────────

(t/deftest test-events-vocabulary
  (t/is (map? event-bus/event-types))
  (t/is (pos? (count event-bus/event-types)))
  (t/is (every? keyword? (keys event-bus/event-types)))
  (t/is (every? string? (vals event-bus/event-types))))

(t/deftest test-events-core-types-known
  (doseq [t [:agent-start :agent-end :agent-settled :turn-start :turn-end
             :message-start :message-update :message-end
             :tool-execution-start :tool-execution-update :tool-execution-end
             :status :error :user-bash]]
    (t/is (event-bus/known-event-type? t) (str t " should be a known event type"))))

(t/deftest test-events-unknown-types
  (t/is (not (event-bus/known-event-type? :bogus)))
  (t/is (not (event-bus/known-event-type? nil)))
  (t/is (not (event-bus/known-event-type? "agent-start"))))

;; ─── Event bus ────────────────────────────────────────────────────────────

(t/deftest test-event-register-and-emit
  (let [log (atom [])]
    (event-bus/clear-event-listeners!)
    (event-bus/on-event :test-event (fn [e] (swap! log conj e)))
    (event-bus/emit-event! {:type :test-event :data "hello"})
    (t/is (= 1 (count @log)))
    (t/is (= :test-event (:type (first @log))))
    (t/is (= "hello" (:data (first @log))))))

(t/deftest test-event-multiple-listeners
  (let [log (atom [])]
    (event-bus/clear-event-listeners!)
    (event-bus/on-event :multi (fn [e] (swap! log conj (str "a:" (:val e)))))
    (event-bus/on-event :multi (fn [e] (swap! log conj (str "b:" (:val e)))))
    (event-bus/emit-event! {:type :multi :val 42})
    (t/is (= 2 (count @log)))
    (t/is (str/includes? (first @log) "42"))))

(t/deftest test-event-no-listeners
  (t/testing "Emitting with no listeners should not throw"
    (event-bus/clear-event-listeners!)
    (event-bus/emit-event! {:type :unregistered :data "test"})))

(t/deftest test-event-deregister
  (let [log (atom [])]
    (event-bus/clear-event-listeners!)
    (let [dereg (event-bus/on-event :dereg-test (fn [e] (swap! log conj e)))]
      (event-bus/emit-event! {:type :dereg-test :data "first"})
      (dereg)
      (event-bus/emit-event! {:type :dereg-test :data "second"})
      (t/is (= 1 (count @log)))
      (t/is (= "first" (:data (first @log)))))))

(t/deftest test-event-types-distinct
  (event-bus/clear-event-listeners!)
  (event-bus/on-event :type-a identity)
  (event-bus/on-event :type-b identity)
  (let [types (event-bus/get-event-types)]
    (t/is (contains? (set types) :type-a))
    (t/is (contains? (set types) :type-b))))

(t/deftest test-event-clear
  (event-bus/clear-event-listeners!)
  (event-bus/on-event :clear-test identity)
  (event-bus/clear-event-listeners!)
  (t/is (empty? (event-bus/get-event-types))))

(t/deftest test-event-listener-error-handling
  (let [log (atom [])]
    (event-bus/clear-event-listeners!)
    (event-bus/on-event :err-test (fn [_] (throw (Exception. "boom"))))
    (event-bus/on-event :err-test (fn [e] (swap! log conj (:data e))))
    ;; The throwing listener prints a warning to stderr — suppress it.
    (binding [*err* (java.io.StringWriter.)]
      (event-bus/emit-event! {:type :err-test :data "ok"}))
    (t/is (= 1 (count @log)))
    (t/is (= "ok" (first @log)))))

(t/deftest test-event-returns-last-non-nil-result
  (event-bus/clear-event-listeners!)
  (event-bus/on-event :tree-prep (fn [_] {:summary "ext summary"}))
  (event-bus/on-event :tree-prep (fn [_] nil))
  (t/is (= {:summary "ext summary"}
           (event-bus/emit-event! {:type :tree-prep :x 1}))
        "emit returns the last non-nil handler result (pi: runner.emit)")
  (t/is (nil? (event-bus/emit-event! {:type :unregistered :x 1}))
        "no listeners → nil"))

(t/deftest test-event-handler-error-swallowed
  (event-bus/clear-event-listeners!)
  (event-bus/on-event :bad (fn [_] (throw (ex-info "boom" {}))))
  (event-bus/on-event :bad (fn [_] {:ok true}))
  (t/is (= {:ok true} (event-bus/emit-event! {:type :bad}))
        "an errored handler doesn't prevent later handlers or throw"))
