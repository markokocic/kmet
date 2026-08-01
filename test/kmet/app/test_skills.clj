(ns kmet.app.test-skills
  (:require [clojure.test :as t]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [kmet.app.skills :as skills]))

;; ─── Skills ────────────────────────────────────────────────────────────────

(t/deftest test-register-skill
  (let [name "test-skill"
        description "A skill for testing."]
    (skills/register-skill! name description)
    (let [loaded (skills/get-skill name)]
      (t/is (some? loaded))
      (t/is (= name (:name loaded)))
      (t/is (= description (:description loaded))))))

(t/deftest test-get-skill-not-found
  (t/is (nil? (skills/get-skill "nonexistent"))))

(t/deftest test-build-system-prompt
  (t/testing "build-system-prompt starts with base prompt and appends skills"
    (let [result (skills/build-system-prompt "Base prompt")]
      (t/is (str/starts-with? result "Base prompt"))))
  (t/testing "skills are listed as available_skills XML"
    (let [name "test-prompt-skill"
          description "Prompt skill description."
          _ (skills/register-skill! name description)
          result (skills/build-system-prompt "Base prompt")]
      (t/is (str/includes? result "<available_skills>"))
      (t/is (str/includes? result name))
      (t/is (str/includes? result description)))))

(t/deftest test-get-skills-returns-list
  (let [name "test-gs"
        description "GS description"]
    (skills/register-skill! name description)
    (let [all (skills/get-skills)]
      (t/is (sequential? all))
      (t/is (some #(= name (:name %)) all)))))

(t/deftest test-load-skills-from-dir-non-existent
  (t/testing "Loading from non-existent dir should not throw"
    (t/is (empty? (skills/load-skills-from-dir "/nonexistent/skills")))))

(t/deftest test-load-skills-from-dir
  (t/testing "SKILL.md directories are discovered as skill roots"
    (let [tmp-dir (str "target/test-skills-" (System/currentTimeMillis))
          skill-dir (str tmp-dir "/my-skill")
          skill-file (str skill-dir "/SKILL.md")]
      (io/make-parents skill-file)
      (spit skill-file
            "---\nname: my-skill\ndescription: Test skill for discovery.\n---\n# My Skill\nDo the thing.")
      (spit (str tmp-dir "/note.txt") "not a skill")
      (skills/load-skills-from-dir tmp-dir)
      (let [loaded (skills/get-skill "my-skill")]
        (t/is (some? loaded))
        (t/is (= "my-skill" (:name loaded)))
        (t/is (= "Test skill for discovery." (:description loaded)))))))

(t/deftest test-load-skills-flat-md-fallback
  (t/testing "flat .md files load when a dir has no SKILL.md (pi discovery)"
    (let [tmp-dir (str "target/test-skills-flat-" (System/currentTimeMillis))
          f (str tmp-dir "/flat-skill.md")]
      (io/make-parents f)
      (spit f "---\nname: flat-skill\ndescription: Flat skill description.\n---\n# Flat Skill")
      (skills/load-skills-from-dir tmp-dir)
      (let [loaded (skills/get-skill "flat-skill")]
        (t/is (some? loaded))
        (t/is (= "Flat skill description." (:description loaded)))))))

(t/deftest test-load-skills-skips-missing-description
  (t/testing "skills without a description are not loaded (pi validation)"
    (let [tmp-dir (str "target/test-skills-nodesc-" (System/currentTimeMillis))
          skill-dir (str tmp-dir "/no-desc-skill")
          skill-file (str skill-dir "/SKILL.md")]
      (io/make-parents skill-file)
      (spit skill-file "---\nname: no-desc-skill\n---\n# No description")
      (skills/load-skills-from-dir tmp-dir)
      (t/is (nil? (skills/get-skill "no-desc-skill"))))))

(t/deftest test-load-skills-name-fallback
  (t/testing "name falls back to parent dir name when frontmatter has none (pi)"
    (let [tmp-dir (str "target/test-skills-fallback-" (System/currentTimeMillis))
          skill-dir (str tmp-dir "/fallback-skill")
          skill-file (str skill-dir "/SKILL.md")]
      (io/make-parents skill-file)
      (spit skill-file "---\ndescription: No name in frontmatter.\n---\n# Fallback")
      (skills/load-skills-from-dir tmp-dir)
      (let [loaded (skills/get-skill "fallback-skill")]
        (t/is (some? loaded))
        (t/is (= "fallback-skill" (:name loaded)))))))

(t/deftest test-load-skills-empty-frontmatter
  (t/testing "empty frontmatter (---\n---) does not crash; skill skipped (no description)"
    (let [tmp-dir (str "target/test-skills-emptyfm-" (System/currentTimeMillis))
          skill-dir (str tmp-dir "/empty-fm")
          skill-file (str skill-dir "/SKILL.md")]
      (io/make-parents skill-file)
      (spit skill-file "---\n---\n# Empty frontmatter")
      (let [diags (skills/load-skills-from-dir tmp-dir)]
        (t/is (some #(= "description is required" (:message %)) diags)))
      (t/is (nil? (skills/get-skill "empty-fm"))))))

(t/deftest test-load-skills-nested-metadata
  (t/testing "nested YAML parses into its own structure without polluting top-level fields"
    (let [tmp-dir (str "target/test-skills-nested-" (System/currentTimeMillis))
          skill-dir (str tmp-dir "/nested-meta")
          skill-file (str skill-dir "/SKILL.md")]
      (io/make-parents skill-file)
      (spit skill-file
            "---\nname: nested-meta\ndescription: Real description.\nmetadata:\n  description: FAKE\n  name: FAKE\n---\n# Nested")
      (skills/load-skills-from-dir tmp-dir)
      (let [loaded (skills/get-skill "nested-meta")]
        (t/is (some? loaded))
        (t/is (= "nested-meta" (:name loaded)))
        (t/is (= "Real description." (:description loaded)))))))

(t/deftest test-load-skills-collision-first-wins
  (t/testing "same name from two files keeps the first (pi) and reports collision"
    (let [tmp-dir (str "target/test-skills-collision-" (System/currentTimeMillis))
          f1 (str tmp-dir "/dup-one/SKILL.md")
          f2 (str tmp-dir "/dup-two/SKILL.md")]
      (io/make-parents f1)
      (io/make-parents f2)
      (spit f1 "---\nname: collision-skill\ndescription: First version.\n---\n# One")
      (spit f2 "---\nname: collision-skill\ndescription: Second version.\n---\n# Two")
      (let [diags (skills/load-skills-from-dir tmp-dir)
            loaded (skills/get-skill "collision-skill")]
        (t/is (= "First version." (:description loaded)))
        (t/is (some #(= "collision" (:type %)) diags))))))

(t/deftest test-expand-skill-command
  (let [tmp-dir (str "target/test-skills-expand-" (System/currentTimeMillis))
        skill-dir (str tmp-dir "/expand-target")
        skill-file (str skill-dir "/SKILL.md")]
    (io/make-parents skill-file)
    (spit skill-file "---\nname: expand-target\ndescription: Test.\n---\n# My Skill\nDo the thing.")
    (skills/load-skills-from-dir tmp-dir)
    (t/testing "/skill:name expands to a <skill> block (frontmatter stripped)"
      (let [expanded (skills/expand-skill-command "/skill:expand-target")]
        (t/is (str/includes? expanded "<skill name=\"expand-target\""))
        (t/is (str/includes? expanded "References are relative to"))
        (t/is (str/includes? expanded "# My Skill\nDo the thing."))
        (t/is (str/ends-with? expanded "</skill>"))))
    (t/testing "args are appended raw after the block (pi)"
      (let [expanded (skills/expand-skill-command "/skill:expand-target arg1 \"a b\"")]
        (t/is (str/includes? expanded "Do the thing.\n</skill>\n\narg1 \"a b\""))))
    (t/testing "unknown skill passes through"
      (t/is (= "/skill:nope x" (skills/expand-skill-command "/skill:nope x"))))
    (t/testing "non-skill text passes through"
      (t/is (= "hello" (skills/expand-skill-command "hello"))))))

(t/deftest test-as-command-maps
  (let [skills-list [{:name "code-review" :description "Review code."}
                     {:name "hidden" :description "Hidden." :disable-model-invocation true}]]
    (t/is (= [{:name "skill:code-review" :description "Review code."}
              {:name "skill:hidden" :description "Hidden."}]
             (skills/as-command-maps skills-list)))))

(t/deftest test-format-skills-for-prompt
  (let [skill {:name "xml-skill"
               :description "Uses & <angle> \"quotes\""
               :file-path "/skills/xml-skill/SKILL.md"
               :disable-model-invocation false}
        hidden (assoc skill :name "hidden-skill" :disable-model-invocation true)
        prompt (skills/format-skills-for-prompt [skill hidden])]
    (t/testing "disable-model-invocation skills are excluded"
      (t/is (str/includes? prompt "xml-skill"))
      (t/is (not (str/includes? prompt "hidden-skill"))))
    (t/testing "name, description, location are XML-escaped"
      (t/is (str/includes? prompt "Uses &amp; &lt;angle&gt; &quot;quotes&quot;"))
      (t/is (str/includes? prompt "<location>/skills/xml-skill/SKILL.md</location>")))
    (t/testing "empty input yields empty output"
      (t/is (= "" (skills/format-skills-for-prompt []))))))
