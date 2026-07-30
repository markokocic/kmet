(ns kmet.test-skills
  (:require [clojure.test :as t]
            [clojure.java.io :as io]
            [kmet.skills :as skills]))

;; ─── Event system ─────────────────────────────────────────────────────────
;; Test before skill registration to avoid state leakage from skill tests.

(t/deftest test-event-register-and-emit
  (let [log (atom [])]
    (skills/clear-event-listeners!)
    (skills/on-event :test-event (fn [e] (swap! log conj e)))
    (skills/emit-event! {:type :test-event :data "hello"})
    (t/is (= 1 (count @log)))
    (t/is (= :test-event (:type (first @log))))
    (t/is (= "hello" (:data (first @log))))))

(t/deftest test-event-multiple-listeners
  (let [log (atom [])]
    (skills/clear-event-listeners!)
    (skills/on-event :multi (fn [e] (swap! log conj (str "a:" (:val e)))))
    (skills/on-event :multi (fn [e] (swap! log conj (str "b:" (:val e)))))
    (skills/emit-event! {:type :multi :val 42})
    (t/is (= 2 (count @log)))
    (t/is (.contains (first @log) "42"))))

(t/deftest test-event-no-listeners
  (t/testing "Emitting with no listeners should not throw"
    (skills/clear-event-listeners!)
    (skills/emit-event! {:type :unregistered :data "test"})))

(t/deftest test-event-deregister
  (let [log (atom [])]
    (skills/clear-event-listeners!)
    (let [dereg (skills/on-event :dereg-test (fn [e] (swap! log conj e)))]
      (skills/emit-event! {:type :dereg-test :data "first"})
      (dereg)
      (skills/emit-event! {:type :dereg-test :data "second"})
      (t/is (= 1 (count @log)))
      (t/is (= "first" (:data (first @log)))))))

(t/deftest test-event-types-distinct
  (skills/clear-event-listeners!)
  (skills/on-event :type-a identity)
  (skills/on-event :type-b identity)
  (let [types (skills/get-event-types)]
    (t/is (contains? (set types) :type-a))
    (t/is (contains? (set types) :type-b))))

(t/deftest test-event-clear
  (skills/clear-event-listeners!)
  (skills/on-event :clear-test identity)
  (skills/clear-event-listeners!)
  (t/is (empty? (skills/get-event-types))))

(t/deftest test-event-listener-error-handling
  (let [log (atom [])]
    (skills/clear-event-listeners!)
    (skills/on-event :err-test (fn [_] (throw (Exception. "boom"))))
    (skills/on-event :err-test (fn [e] (swap! log conj (:data e))))
    (skills/emit-event! {:type :err-test :data "ok"})
    (t/is (= 1 (count @log)))
    (t/is (= "ok" (first @log)))))

;; ─── Skills ────────────────────────────────────────────────────────────────

(t/deftest test-register-skill
  (let [name "test-skill"
        content "# Test Skill\nDo something useful."]
    (skills/register-skill! name content)
    (let [loaded (skills/get-skill name)]
      (t/is (some? loaded))
      (t/is (= name (:name loaded)))
      (t/is (= content (:content loaded))))))

(t/deftest test-get-skill-not-found
  (t/is (nil? (skills/get-skill "nonexistent"))))

(t/deftest test-build-system-prompt
  (t/testing "build-system-prompt starts with base prompt and appends skills"
    (let [result (skills/build-system-prompt "Base prompt")]
      (t/is (.startsWith result "Base prompt")))))

(t/deftest test-get-skills-returns-list
  (let [name "test-gs"
        content "# GS"]
    (skills/register-skill! name content)
    (let [all (skills/get-skills)]
      (t/is (sequential? all))
      (t/is (some #(= name (:name %)) all)))))

(t/deftest test-load-skills-from-dir-non-existent
  (t/testing "Loading from non-existent dir should not throw"
    (t/is (nil? (skills/load-skills-from-dir "/nonexistent/skills")))))

(t/deftest test-load-skills-from-dir
  (let [tmp-dir (str "target/test-skills-" (System/currentTimeMillis))
        f (str tmp-dir "/test.md")]
    (io/make-parents f)
    (spit f "# Test Skill\nDo the thing.")
    (spit (str tmp-dir "/note.txt") "not a skill")
    (skills/load-skills-from-dir tmp-dir)
    (let [loaded (skills/get-skill "test")]
      (t/is (some? loaded))
      (t/is (= "test" (:name loaded)))
      (t/is (.contains (:content loaded) "Test Skill")))))

;; ─── Extensions ───────────────────────────────────────────────────────────

(t/deftest test-load-extensions-from-dir-non-existent
  (t/testing "Loading from non-existent dir should not throw"
    (t/is (nil? (skills/load-extensions-from-dir "/nonexistent/extensions")))))

(t/deftest test-load-extensions-from-dir
  (let [tmp-dir (str "target/test-ext-" (System/currentTimeMillis))
        f (str tmp-dir "/ext.clj")]
    (io/make-parents f)
    (spit f "(println \"extension loaded\")")
    (t/testing "Loading should not throw"
      (t/is (nil? (skills/load-extensions-from-dir tmp-dir))))))
