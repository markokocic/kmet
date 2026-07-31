(ns kmet.app.test-skills
  (:require [clojure.test :as t]
            [clojure.java.io :as io]
            [kmet.app.skills :as skills]))

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
    ;; The throwing listener prints a warning to stderr — suppress it.
    (binding [*err* (java.io.StringWriter.)]
      (skills/emit-event! {:type :err-test :data "ok"}))
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
      ;; The fixture extension prints "extension loaded" to stdout.
      (t/is (nil? (binding [*out* (java.io.StringWriter.)]
                    (skills/load-extensions-from-dir tmp-dir)))))))

;; ─── Extension input / before-agent-start hooks ───────────────────────────

(t/deftest test-input-hook-pass
  (skills/clear-input-hooks!)
  (let [r (skills/apply-input-hooks "hello" :interactive)]
    (t/is (= :pass (:action r)))
    (t/is (= "hello" (:text r)))))

(t/deftest test-input-hook-transform
  (skills/clear-input-hooks!)
  (skills/register-input-hook!
    (fn [{:keys [text]}] {:action :transform :text (str ">> " text)}))
  (let [r (skills/apply-input-hooks "hello" :interactive)]
    (t/is (= :transform (:action r)))
    (t/is (= ">> hello" (:text r)))))

(t/deftest test-input-hook-transform-chains
  (skills/clear-input-hooks!)
  (skills/register-input-hook!
    (fn [{:keys [text]}] {:action :transform :text (str text "!")}))
  (skills/register-input-hook!
    (fn [{:keys [text]}] {:action :transform :text (str "[" text "]")}))
  (let [r (skills/apply-input-hooks "a" :interactive)]
    (t/is (= "[a!]" (:text r)) "later hooks see earlier transforms")))

(t/deftest test-input-hook-transform-empty
  (skills/clear-input-hooks!)
  (skills/register-input-hook! (fn [_] {:action :transform :text ""}))
  (let [r (skills/apply-input-hooks "hello" :interactive)]
    (t/is (= "" (:text r)) "empty transform text is honored")))

(t/deftest test-input-hook-handled-stops
  (skills/clear-input-hooks!)
  (let [later-called (atom false)]
    (skills/register-input-hook! (fn [_] {:action :handled}))
    (skills/register-input-hook! (fn [_] (reset! later-called true)))
    (let [r (skills/apply-input-hooks "x" :interactive)]
      (t/is (= :handled (:action r)))
      (t/is (false? @later-called) "later hooks must not run after :handled"))))

(t/deftest test-input-hook-error-safe
  (skills/clear-input-hooks!)
  (skills/register-input-hook! (fn [_] (throw (Exception. "boom"))))
  (let [r (skills/apply-input-hooks "x" :interactive)]
    (t/is (= :pass (:action r)))
    (t/is (= "x" (:text r)))))

(t/deftest test-input-hook-source-in-ctx
  (skills/clear-input-hooks!)
  (let [seen (atom nil)]
    (skills/register-input-hook! (fn [{:keys [source]}] (reset! seen source)))
    (skills/apply-input-hooks "x" :interactive)
    (t/is (= :interactive @seen))))

(t/deftest test-input-hook-streaming-behavior-in-ctx
  (skills/clear-input-hooks!)
  (let [seen (atom nil)]
    (skills/register-input-hook! (fn [{:keys [streaming-behavior]}]
                                   (reset! seen streaming-behavior)))
    (skills/apply-input-hooks "x" :interactive {:streaming-behavior :steer})
    (t/is (= :steer @seen))
    (skills/apply-input-hooks "x" :interactive)
    (t/is (nil? @seen) "streaming-behavior is nil when idle")))

(t/deftest test-input-hook-images-in-ctx
  (skills/clear-input-hooks!)
  (let [seen (atom nil)]
    (skills/register-input-hook! (fn [{:keys [images]}] (reset! seen images)))
    (skills/apply-input-hooks "x" :interactive
      {:images [{:type :image :data "AA" :mime-type "image/png"}]})
    (t/is (= [{:type :image :data "AA" :mime-type "image/png"}] @seen))))

(t/deftest test-input-hook-images-transform
  (skills/clear-input-hooks!)
  (skills/register-input-hook!
    (fn [{:keys [images]}]
      {:action :transform :text "t"
       :images (conj images {:type :image :data "BB" :mime-type "image/jpeg"})}))
  (let [r (skills/apply-input-hooks "x" :interactive)]
    (t/is (= :transform (:action r)))
    (t/is (= [{:type :image :data "BB" :mime-type "image/jpeg"}] (:images r)))))

(t/deftest test-input-hook-images-pass-through
  (skills/clear-input-hooks!)
  (let [r (skills/apply-input-hooks "x" :interactive
          {:images [{:type :image :data "AA" :mime-type "image/png"}]})]
    (t/is (= :pass (:action r)))
    (t/is (= [{:type :image :data "AA" :mime-type "image/png"}] (:images r))
          "pass result carries the images")))

(t/deftest test-before-agent-start-hooks-empty
  (skills/clear-before-agent-start-hooks!)
  (let [r (skills/apply-before-agent-start-hooks "hello" "base")]
    (t/is (nil? (:system-prompt r)))
    (t/is (empty? (:messages r)))))

(t/deftest test-before-agent-start-system-prompt
  (skills/clear-before-agent-start-hooks!)
  (skills/register-before-agent-start-hook!
    (fn [{:keys [system-prompt]}]
      {:system-prompt (str system-prompt "\nEXTRA")}))
  (let [r (skills/apply-before-agent-start-hooks "hi" "base")]
    (t/is (= "base\nEXTRA" (:system-prompt r)))))

(t/deftest test-before-agent-start-messages
  (skills/clear-before-agent-start-hooks!)
  (skills/register-before-agent-start-hook!
    (fn [_] {:message {:role :user :content [{:type :text :text "note"}]}}))
  (let [r (skills/apply-before-agent-start-hooks "hi" "base")]
    (t/is (= 1 (count (:messages r))))
    (t/is (= :user (:role (first (:messages r)))))))

(t/deftest test-before-agent-start-chains
  (skills/clear-before-agent-start-hooks!)
  (skills/register-before-agent-start-hook!
    (fn [{:keys [system-prompt]}] {:system-prompt (str system-prompt "+")}))
  (skills/register-before-agent-start-hook!
    (fn [{:keys [system-prompt]}]
      {:system-prompt (str system-prompt "+")
       :message {:role :info :label "ext" :content "note"}}))
  (let [r (skills/apply-before-agent-start-hooks "hi" "base")]
    (t/is (= "base++" (:system-prompt r)) "later hooks see earlier overrides")
    (t/is (= 1 (count (:messages r))))))

(t/deftest test-before-agent-start-error-safe
  (skills/clear-before-agent-start-hooks!)
  (skills/register-before-agent-start-hook! (fn [_] (throw (Exception. "boom"))))
  (let [r (skills/apply-before-agent-start-hooks "hi" "base")]
    (t/is (nil? (:system-prompt r)))
    (t/is (empty? (:messages r)))))
