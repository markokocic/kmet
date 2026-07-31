(ns kmet.app.test-skills
  (:require [clojure.test :as t]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [kmet.app.skills :as skills]))

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
      (t/is (str/starts-with? result "Base prompt")))))

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
      (t/is (str/includes? (:content loaded) "Test Skill")))))
