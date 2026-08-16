(ns kmet.libs.test-edn-store
  (:require [clojure.test :as t :refer [deftest is testing]]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [babashka.fs :as fs]
            [kmet.libs.edn-store :as eds]))

;; ─── expand-path ───────────────────────────────────────────────────────────

(deftest expand-path-no-tilde
  (is (= "/tmp/foo" (eds/expand-path "/tmp/foo"))))

(deftest expand-path-with-tilde
  (let [home (System/getProperty "user.home")
        expanded (eds/expand-path "~/.kmet/agent/settings.edn")]
    (is (str/starts-with? expanded home))
    (is (str/ends-with? expanded "/.kmet/agent/settings.edn"))))

(deftest expand-path-relative
  (is (= ".kmet/settings.edn" (eds/expand-path ".kmet/settings.edn"))))

;; ─── deep-merge ────────────────────────────────────────────────────────────

(deftest deep-merge-test
  (testing "nested maps merge key-by-key (pi: project overrides global, objects merge)"
    (let [base {:theme "dark"
                :providers {:openai {:model "gpt-4o" :base-url "u"}
                            :anthropic {:model "claude"}}}
          user {:providers {:openai {:model "gpt-4o-mini"}}}
          merged (eds/deep-merge base user)]
      (is (= "dark" (:theme merged)))
      (is (= "gpt-4o-mini" (get-in merged [:providers :openai :model])))
      (is (= "u" (get-in merged [:providers :openai :base-url])))
      (is (= "claude" (get-in merged [:providers :anthropic :model])))))
  (testing "non-map values: later wins; vectors replaced, not merged"
    (let [merged (eds/deep-merge {:a 1 :v [1 2]} {:a 2 :v [3]})]
      (is (= 2 (:a merged)))
      (is (= [3] (:v merged)))))
  (testing "scalar vs map conflict: later value wins without crashing"
    (is (= {:a {:x 2}} (eds/deep-merge {:a 1} {:a {:x 2}})))
    (is (= {:a 1} (eds/deep-merge {:a {:x 2}} {:a 1}))))
  (testing "pi settings.md example: compaction partial override"
    (let [global {:compaction {:enabled true :reserveTokens 16384}}
          project {:compaction {:reserveTokens 8192}}
          merged (eds/deep-merge global project)]
      (is (= true (get-in merged [:compaction :enabled])))
      (is (= 8192 (get-in merged [:compaction :reserveTokens]))))))

;; ─── pretty-edn ────────────────────────────────────────────────────────────

(deftest pretty-edn-test
  (is (= "{:provider :opencode-go\n}\n"
         (eds/pretty-edn {:provider :opencode-go})))
  (testing "empty map"
    (is (= "{\n}\n" (eds/pretty-edn {}))))
  (testing "multiple entries"
    (let [result (eds/pretty-edn {:a 1 :b 2})]
      (is (str/starts-with? result "{"))
      (is (str/ends-with? result "}\n"))
      (is (str/includes? result ":a 1"))
      (is (str/includes? result ":b 2")))))

;; ─── safe-parse-edn-map ────────────────────────────────────────────────────

(deftest safe-parse-edn-map-test
  (is (= {:a 1} (eds/safe-parse-edn-map "{:a 1}")))
  (is (nil? (eds/safe-parse-edn-map "not-edn[")))
  (is (nil? (eds/safe-parse-edn-map "[1 2 3]")))
  (is (nil? (eds/safe-parse-edn-map ""))))

;; ─── update-setting-text ───────────────────────────────────────────────────

(deftest update-setting-text-replace
  (let [text "{:provider :openai\n :model \"gpt-4o\"\n}\n"
        result (eds/update-setting-text text :provider :anthropic)]
    (is (str/includes? result ":provider :anthropic"))
    (is (str/includes? result ":model \"gpt-4o\""))))

(deftest update-setting-text-insert
  (let [text "{:provider :openai\n}\n"
        result (eds/update-setting-text text :model "gpt-4o")]
    (is (str/includes? result ":provider :openai"))
    (is (str/includes? result ":model \"gpt-4o\""))))

(deftest update-setting-text-preserves-comments
  (let [text "{:provider :openai\n ;; keep me\n}\n"
        result (eds/update-setting-text text :model "gpt-4o")]
    (is (str/includes? result ";; keep me"))
    (is (str/includes? result ":model \"gpt-4o\""))))

;; ─── save-edn-setting! ─────────────────────────────────────────────────────

(deftest save-edn-setting-creates-file
  (let [tmp (str (fs/absolutize (fs/file "target" (str "test-edn-settings-create-" (System/currentTimeMillis)))))
        f (str tmp "/settings.edn")]
    (fs/create-dirs tmp)
    (try
      (eds/save-edn-setting! f [:provider] :opencode-go)
      (is (= {:provider :opencode-go} (edn/read-string (slurp f))))
      (finally (fs/delete-tree tmp)))))

(deftest save-edn-setting-pretty-format
  (let [tmp (str (fs/absolutize (fs/file "target" (str "test-edn-settings-pretty-" (System/currentTimeMillis)))))
        f (str tmp "/settings.edn")]
    (fs/create-dirs tmp)
    (try
      (eds/save-edn-setting! f [:provider] :opencode-go)
      (is (= "{:provider :opencode-go\n}\n" (slurp f)))
      (finally (fs/delete-tree tmp)))))

(deftest save-edn-setting-merges-existing
  (let [tmp (str (fs/absolutize (fs/file "target" (str "test-edn-settings-merge-" (System/currentTimeMillis)))))
        f (str tmp "/settings.edn")]
    (fs/create-dirs tmp)
    (try
      (spit f "{:provider :openai}\n")
      (eds/save-edn-setting! f [:hide-thinking-block] true)
      (is (= {:provider :openai :hide-thinking-block true}
             (edn/read-string (slurp f))))
      (finally (fs/delete-tree tmp)))))

(deftest save-edn-setting-nested-merge
  (let [tmp (str (fs/absolutize (fs/file "target" (str "test-edn-settings-nested-" (System/currentTimeMillis)))))
        f (str tmp "/settings.edn")]
    (fs/create-dirs tmp)
    (try
      (eds/save-edn-setting! f [:terminal :show-images] false)
      (eds/save-edn-setting! f [:terminal :image-width-cells] 80)
      (is (= {:terminal {:show-images false :image-width-cells 80}}
             (edn/read-string (slurp f))))
      (finally (fs/delete-tree tmp)))))

(deftest save-edn-setting-preserves-comments
  (let [tmp (str (fs/absolutize (fs/file "target" (str "test-edn-settings-comments-" (System/currentTimeMillis)))))
        f (str tmp "/settings.edn")]
    (fs/create-dirs tmp)
    (try
      (spit f "{:provider :openai\n ;; keep me\n :hide-thinking-block true\n}\n")
      (eds/save-edn-setting! f [:hide-thinking-block] false)
      (is (= "{:provider :openai\n ;; keep me\n :hide-thinking-block false\n}\n"
             (slurp f)))
      (finally (fs/delete-tree tmp)))))

(deftest save-edn-setting-non-map-replaced
  (let [tmp (str (fs/absolutize (fs/file "target" (str "test-edn-settings-replace-" (System/currentTimeMillis)))))
        f (str tmp "/settings.edn")]
    (fs/create-dirs tmp)
    (try
      (spit f "[1 2 3]\n")
      (eds/save-edn-setting! f [:hide-thinking-block] true)
      (is (= {:hide-thinking-block true} (edn/read-string (slurp f))))
      (finally (fs/delete-tree tmp)))))
