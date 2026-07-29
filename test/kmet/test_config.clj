(ns kmet.test-config
  (:require [clojure.test :as t]
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
        expanded (cfg/expand-path "~/.config/kmet/settings.edn")]
    (t/is (.startsWith expanded home))
    (t/is (.endsWith expanded "/.config/kmet/settings.edn"))))

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
        c (assoc cfg/default-config :session-dir "~/.local/share/kmet/sessions")
        dir (cfg/get-session-dir c)]
    (t/is (.startsWith dir home))
    (t/is (.endsWith dir "/.local/share/kmet/sessions"))))

(t/deftest test-get-theme-name
  (let [c (assoc cfg/default-config :theme "light")]
    (t/is (= "light" (cfg/get-theme-name c)))))

(t/deftest test-get-theme-name-default
  (let [c (dissoc cfg/default-config :theme)]
    (t/is (= "dark" (cfg/get-theme-name c)))))

;; ─── Configuration merging ────────────────────────────────────────────────

(t/deftest test-config-merging
  (t/testing "User config overrides defaults, project overrides user"
    (let [base {:theme "dark" :provider :openai}
          user {:theme "light"}
          project {:provider :anthropic}
          merged (merge base user project)]
      (t/is (= "light" (:theme merged)))
      (t/is (= :anthropic (:provider merged))))))
