(ns kmet.test-config
  (:require [clojure.test :as t]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [babashka.fs :as fs]
            [kmet.config :as cfg]))

;; ─── Defaults ──────────────────────────────────────────────────────────────

(t/deftest test-default-config
  (let [c cfg/default-config]
    (t/is (map? c))
    (t/is (= :opencode-go (:provider c)))
    (t/is (= "dark" (:theme c)))
    (t/is (contains? c :session-dir))
    (t/is (contains? c :providers))
    (t/is (contains? (:providers c) :openai))
    (t/is (contains? (:providers c) :anthropic))
    (t/is (= 500 (:max-session-entries c)))))

(t/deftest test-default-model-per-provider
  (t/is (= "deepseek-v4-flash" (get-in cfg/default-config [:providers :opencode-go :model])))
  (t/is (= "claude-sonnet-4-20250514" (get-in cfg/default-config [:providers :anthropic :model])))
  (t/is (= "gpt-4o" (get-in cfg/default-config [:providers :openai :model]))))

;; ─── Path expansion ────────────────────────────────────────────────────────

(t/deftest test-expand-path-no-tilde
  (t/is (= "/tmp/foo" (cfg/expand-path "/tmp/foo"))))

(t/deftest test-expand-path-with-tilde
  (let [home (System/getProperty "user.home")
        expanded (cfg/expand-path "~/.kmet/agent/settings.edn")]
    (t/is (str/starts-with? expanded home))
    (t/is (str/ends-with? expanded "/.kmet/agent/settings.edn"))))

(t/deftest test-expand-path-relative
  (t/is (= ".kmet/settings.edn" (cfg/expand-path ".kmet/settings.edn"))))

;; ─── Load config ──────────────────────────────────────────────────────────

(t/deftest test-load-config-defaults
  (let [c (cfg/load-config :no-env? true)]
    (t/is (map? c))
    (t/is (= :opencode-go (:provider c)))
    (t/is (= "dark" (:theme c)))))

;; ─── Accessors ─────────────────────────────────────────────────────────────

(t/deftest test-get-provider
  (let [c cfg/default-config]
    (t/is (= :opencode-go (cfg/get-provider c)))))

(t/deftest test-get-model-from-default
  (let [c (assoc cfg/default-config :model nil)]
    (t/is (= "deepseek-v4-flash" (cfg/get-model c)))))

(t/deftest test-get-model-explicit
  (let [c (assoc cfg/default-config :model "gpt-4o-mini")]
    (t/is (= "gpt-4o-mini" (cfg/get-model c)))))

(t/deftest test-get-model-anthropic
  (let [c (assoc cfg/default-config :provider :anthropic :model nil)]
    (t/is (= "claude-sonnet-4-20250514" (cfg/get-model c)))))

(t/deftest test-get-session-dir
  (let [c (assoc cfg/default-config :session-dir "/tmp/kmet-sessions")]
    (t/is (= "/tmp/kmet-sessions" (cfg/get-session-dir c)))))

(t/deftest test-get-session-dir-tilde-expanded
  (let [home (System/getProperty "user.home")
        c (assoc cfg/default-config :session-dir "~/.kmet/sessions")
        dir (cfg/get-session-dir c)]
    (t/is (str/starts-with? dir home))
    (t/is (str/ends-with? dir "/.kmet/sessions"))))

(t/deftest test-get-theme-name
  (let [c (assoc cfg/default-config :theme "light")]
    (t/is (= "light" (cfg/get-theme-name c)))))

(t/deftest test-get-theme-name-default
  (let [c (dissoc cfg/default-config :theme)]
    (t/is (= "dark" (cfg/get-theme-name c)))))

;; ─── Configuration merging ────────────────────────────────────────────────

(t/deftest test-deep-merge
  (t/testing "nested maps merge key-by-key (pi: project overrides global, objects merge)"
    (let [base {:theme "dark"
                :providers {:openai {:model "gpt-4o" :base-url "u"}
                            :anthropic {:model "claude"}}}
          user {:providers {:openai {:model "gpt-4o-mini"}}}
          merged (cfg/deep-merge base user)]
      (t/is (= "dark" (:theme merged)))
      (t/is (= "gpt-4o-mini" (get-in merged [:providers :openai :model])))
      (t/is (= "u" (get-in merged [:providers :openai :base-url])))
      (t/is (= "claude" (get-in merged [:providers :anthropic :model])))))
  (t/testing "non-map values: later wins; vectors replaced, not merged"
    (let [merged (cfg/deep-merge {:a 1 :v [1 2]} {:a 2 :v [3]})]
      (t/is (= 2 (:a merged)))
      (t/is (= [3] (:v merged)))))
  (t/testing "scalar vs map conflict: later value wins without crashing"
    (t/is (= {:a {:x 2}} (cfg/deep-merge {:a 1} {:a {:x 2}})))
    (t/is (= {:a 1} (cfg/deep-merge {:a {:x 2}} {:a 1}))))
  (t/testing "pi settings.md example: compaction partial override"
    (let [global {:compaction {:enabled true :reserveTokens 16384}}
          project {:compaction {:reserveTokens 8192}}
          merged (cfg/deep-merge global project)]
      (t/is (= true (get-in merged [:compaction :enabled])))
      (t/is (= 8192 (get-in merged [:compaction :reserveTokens]))))))

;; ─── Scope-relative path resolution ────────────────────────────────────────

(t/deftest test-scope-path-resolution
  (let [resolve-paths @#'cfg/resolve-scope-paths
        home (System/getProperty "user.home")]
    (t/testing "relative paths resolve against their scope dir"
      (let [global (resolve-paths {:session-dir "sessions" :model "x"} "/g/base")
            project (resolve-paths {:extensions-dir ".kmet/ext" :prompts-dir "prompts"} "/p/base")]
        (t/is (= "/g/base/sessions" (:session-dir global)))
        (t/is (= "x" (:model global)))
        (t/is (= "/p/base/.kmet/ext" (:extensions-dir project)))
        (t/is (= "/p/base/prompts" (:prompts-dir project)))))
    (t/testing "tilde and absolute paths pass through"
      (let [res (resolve-paths {:session-dir "~/.kmet/sessions"
                                :skills-dir "/abs/skills"} "/base")]
        (t/is (str/starts-with? (:session-dir res) home))
        (t/is (= "/abs/skills" (:skills-dir res)))))
    (t/testing "non-string values and nil config pass through"
      (t/is (= {} (resolve-paths nil "/base")))
      (t/is (= {:model "x"} (resolve-paths {:model "x"} "/base")))
      (t/is (= {:session-dir nil} (resolve-paths {:session-dir nil} "/base"))))))

;; ─── Provider config ───────────────────────────────────────────────────────

(t/deftest test-resource-dirs
  (let [canon (fn [p] (str (fs/canonicalize (io/file p))))
        global (canon (str (System/getProperty "user.home") "/.kmet/agent/skills"))
        project (canon (str (System/getProperty "user.dir") "/.kmet/skills"))]
    (t/testing "defaults: merged == global default, deduped to [global project]"
      (t/is (= [global project]
               (cfg/resource-dirs cfg/default-config :skills-dir ".kmet/skills"))))
    (t/testing "explicit override loads after the defaults (pi additive paths)"
      (let [c (assoc cfg/default-config :skills-dir "/custom/skills")
            dirs (cfg/resource-dirs c :skills-dir ".kmet/skills")]
        (t/is (= [global project "/custom/skills"] dirs))))
    (t/testing "duplicate paths are deduped"
      (let [c (assoc cfg/default-config :skills-dir global)
            dirs (cfg/resource-dirs c :skills-dir global)]
        (t/is (= [global] dirs))))))

(t/deftest test-get-provider-config-known
  (let [c (cfg/get-provider-config :openai)]
    (t/is (map? c))
    (t/is (= :openai (:api-type c)))
    (t/is (some? (:base-url c)))))

(t/deftest test-get-provider-config-unknown
  (let [c (cfg/get-provider-config :nonexistent)]
    (t/is (some? c))
    (t/is (= :nonexistent (:api-type c)))
    (t/is (nil? (:base-url c)))))

(t/deftest test-get-provider-base-url
  (t/is (some? (cfg/get-provider-base-url :openai)))
  (t/is (nil? (cfg/get-provider-base-url :nonexistent))))

(t/deftest test-get-provider-api-type
  (t/is (= :openai (cfg/get-provider-api-type :openai)))
  (t/is (= :anthropic (cfg/get-provider-api-type :anthropic))))

;; ─── API key ───────────────────────────────────────────────────────────────

(t/deftest test-get-api-key-returns-string-or-nil
  ;; get-api-key returns a string (if key available) or nil
  (let [key (cfg/get-api-key :openai)]
    (t/is (or (nil? key) (string? key)))))

(t/deftest test-get-api-key-unknown-provider
  (t/is (nil? (cfg/get-api-key :nonexistent))))

;; ─── load-auth ─────────────────────────────────────────────────────────────

(t/deftest test-load-auth-returns-map
  ;; load-auth should return a map (possibly empty if no auth file)
  (let [auth (cfg/load-auth)]
    (t/is (map? auth))))

;; ─── get-theme ─────────────────────────────────────────────────────────────

(t/deftest test-get-theme
  (let [t (cfg/get-theme cfg/default-config)]
    (t/is (some? t))
    (t/is (= "dark" (:name t)))))

(t/deftest test-get-theme-light-config
  (let [c (assoc cfg/default-config :theme "light")
        t (cfg/get-theme c)]
    (t/is (= "light" (:name t)))))
