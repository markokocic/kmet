(ns kmet.test-config
  (:require [clojure.test :as t]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [babashka.fs :as fs]
            [kmet.config :as cfg]
            [kmet.ai.auth :as auth]))

;; ─── Defaults ──────────────────────────────────────────────────────────────

(t/deftest test-default-config
  (let [c cfg/default-config]
    (t/is (map? c))
    (t/is (= :opencode-go (:provider c)))
    (t/is (= "dark" (:theme c)))
    (t/is (contains? c :session-dir))
    (t/is (= "deepseek-v4-flash" (:model c)))))

(t/deftest test-default-config-has-no-provider-map
  ;; Phase 0: the :providers map is replaced by the models registry — the
  ;; provider defaults live in the catalog EDN, not in default-config.
  (t/is (not (contains? cfg/default-config :providers))))

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
  (t/is (= "deepseek-v4-flash" (cfg/get-model cfg/default-config))))

(t/deftest test-get-model-explicit
  (let [c (assoc cfg/default-config :model "gpt-4o-mini")]
    (t/is (= "gpt-4o-mini" (cfg/get-model c)))))

(t/deftest test-get-model-no-provider-fallback
  ;; Phase 0: get-model no longer falls back through :providers — provider
  ;; defaults come from the models registry (models/resolve-config-model).
  (let [c (assoc cfg/default-config :provider :anthropic :model nil)]
    (t/is (nil? (cfg/get-model c)))))

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
;; Phase 0: provider-configs / get-provider-config / get-provider-base-url /
;; get-provider-api-type are deleted — base-url/api-type come from the models
;; registry (kmet.ai.models), not from config (covered by test_models).

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

;; ─── API key ───────────────────────────────────────────────────────────────

(t/deftest test-get-api-key-returns-string-or-nil
  ;; get-api-key returns a string (if key available) or nil
  (let [key (cfg/get-api-key :deepseek)]
    (t/is (or (nil? key) (string? key)))))

(t/deftest test-get-api-key-unknown-provider
  ;; providers without an env entry → nil (auth.edn aside); env lookup is
  ;; pinned to nil so the result doesn't depend on the host environment
  (with-redefs [auth/getenv (fn [_] nil)]
    (t/is (nil? (cfg/get-api-key :nonexistent)))
    (t/is (nil? (cfg/get-api-key :openai)))))

;; ─── get-theme ─────────────────────────────────────────────────────────────

(t/deftest test-get-theme
  (let [t (cfg/get-theme cfg/default-config)]
    (t/is (some? t))
    (t/is (= "dark" (:name t)))))

(t/deftest test-get-theme-light-config
  (let [c (assoc cfg/default-config :theme "light")
        t (cfg/get-theme c)]
    (t/is (= "light" (:name t)))))

;; ─── System prompt sources (pi: SYSTEM.md / APPEND_SYSTEM.md) ─────────────

(t/deftest test-get-custom-prompt
  (t/testing "config value wins over files"
    (t/is (= "Custom" (cfg/get-custom-prompt {:system-prompt "Custom"}))))
  (t/testing "config value naming an existing file is read as content (pi resolvePromptInput)"
    (let [tmp (str (fs/absolutize (fs/file "target" (str "test-prompt-" (System/currentTimeMillis)))))
          f (str tmp "/prompt.md")]
      (io/make-parents f)
      (spit f "From file")
      (try
        (t/is (= "From file" (cfg/get-custom-prompt {:system-prompt f})))
        (finally (fs/delete-tree tmp)))))
  (t/testing "no config value, no files yields nil"
    (with-redefs [cfg/prompt-file-candidates (fn [_] [])]
      (t/is (nil? (cfg/get-custom-prompt {}))))))

(t/deftest test-get-custom-prompt-file-discovery
  (t/testing "project file wins over global file (pi order)"
    (let [tmp (str (fs/absolutize (fs/file "target" (str "test-prompt-dir-" (System/currentTimeMillis)))))
          project-file (str tmp "/.kmet/SYSTEM.md")
          global-file (str tmp "/agent/SYSTEM.md")]
      (io/make-parents project-file)
      (io/make-parents global-file)
      (spit project-file "Project prompt")
      (spit global-file "Global prompt")
      (try
        (with-redefs [cfg/prompt-file-candidates (fn [_] [project-file global-file])]
          (t/is (= "Project prompt" (cfg/get-custom-prompt {}))))
        (finally (fs/delete-tree tmp)))))
  (t/testing "global file used when no project file"
    (let [tmp (str (fs/absolutize (fs/file "target" (str "test-prompt-dir2-" (System/currentTimeMillis)))))
          global-file (str tmp "/agent/SYSTEM.md")]
      (io/make-parents global-file)
      (spit global-file "Global prompt")
      (try
        (with-redefs [cfg/prompt-file-candidates (fn [_] [global-file])]
          (t/is (= "Global prompt" (cfg/get-custom-prompt {}))))
        (finally (fs/delete-tree tmp))))))

(t/deftest test-get-append-system-prompt
  (t/testing "config value wins"
    (t/is (= "Extra" (cfg/get-append-system-prompt {:append-system-prompt "Extra"}))))
  (t/testing "no config value, no files yields nil"
    (with-redefs [cfg/prompt-file-candidates (fn [_] [])]
      (t/is (nil? (cfg/get-append-system-prompt {}))))))

(t/deftest test-apply-cli-overrides
  (let [base {:model "a" :provider :openai}
        opts {:model "b"
              :provider :anthropic
              :system-prompt "Custom"
              :append-system-prompt ["One" "Two"]}]
    (t/is (= "b" (:model (cfg/apply-cli-overrides base opts))))
    (t/is (= :anthropic (:provider (cfg/apply-cli-overrides base opts))))
    (t/is (= "Custom" (:system-prompt (cfg/apply-cli-overrides base opts))))
    (t/testing "repeatable append-system-prompt joins with newlines (pi)"
      (t/is (= "One\n\nTwo" (:append-system-prompt (cfg/apply-cli-overrides base opts)))))
    (t/testing "absent keys pass through untouched"
      (t/is (= base (cfg/apply-cli-overrides base {}))))))

;; ─── hide-thinking-block (pi: hideThinkingBlock in settings.json) ─────────

(t/deftest test-get-hide-thinking-block
  (t/is (false? (cfg/get-hide-thinking-block {})))
  (t/is (false? (cfg/get-hide-thinking-block {:hide-thinking-block false})))
  (t/is (true? (cfg/get-hide-thinking-block {:hide-thinking-block true}))))

(t/deftest test-get-enabled-models
  (t/is (nil? (cfg/get-enabled-models {})))
  (t/is (= ["a" "b"] (cfg/get-enabled-models {:enabled-models ["a" "b"]}))))

(t/deftest test-get-enabled-models-live
  (let [tmp (str (fs/absolutize (fs/file "target" (str "test-enabled-models-live-" (System/currentTimeMillis)))))
        settings-file (str tmp "/settings.edn")]
    (fs/create-dirs tmp)
    (try
      (with-redefs [cfg/global-settings-path (fn [] settings-file)]
        (t/testing "reads the file (pi: mutable settings object)"
          (spit settings-file "{:enabled-models [\"a\"]}\n")
          (t/is (= ["a"] (cfg/get-enabled-models-live {:enabled-models ["stale"]}))))
        (t/testing "missing file falls back to the config value"
          (fs/delete-tree tmp)
          (t/is (= ["stale"] (cfg/get-enabled-models-live {:enabled-models ["stale"]}))))
        (t/testing "file without the key falls back to the config (project override)"
          (fs/create-dirs tmp)
          (spit settings-file "{:provider :opencode-go}\n")
          (t/is (= ["stale"] (cfg/get-enabled-models-live {:enabled-models ["stale"]}))))
        (t/testing "unreadable file falls back to the config value"
          (fs/create-dirs tmp)
          (spit settings-file "not-edn[")
          (t/is (= ["stale"] (cfg/get-enabled-models-live {:enabled-models ["stale"]})))))
      (finally (fs/delete-tree tmp)))))

(t/deftest test-get-retry-settings
  (t/testing "defaults when :retry is absent"
    (t/is (= {:enabled true :max-retries 3 :base-delay-ms 2000}
             (cfg/get-retry-settings {}))))
  (t/testing "partial :retry maps merge with defaults (deep-merged config)"
    (t/is (= {:enabled false :max-retries 5 :base-delay-ms 2000}
             (cfg/get-retry-settings {:retry {:enabled false :max-retries 5}})))
    (t/is (= {:enabled true :max-retries 0 :base-delay-ms 500}
             (cfg/get-retry-settings {:retry {:max-retries 0 :base-delay-ms 500}}))
          "0 is a valid max-retries (off)")))

(t/deftest test-get-retry-settings-live
  (let [tmp (str (fs/absolutize (fs/file "target" (str "test-retry-live-" (System/currentTimeMillis)))))
        settings-file (str tmp "/settings.edn")]
    (fs/create-dirs tmp)
    (try
      (with-redefs [cfg/global-settings-path (fn [] settings-file)]
        (t/testing "reads the file :retry block"
          (spit settings-file "{:retry {:enabled false :max-retries 10}}\n")
          (t/is (= {:enabled false :max-retries 10 :base-delay-ms 2000}
                   (cfg/get-retry-settings-live {:retry {:max-retries 5}}))))
        (t/testing "missing file falls back to the config value"
          (fs/delete-tree tmp)
          (t/is (= {:enabled true :max-retries 5 :base-delay-ms 2000}
                   (cfg/get-retry-settings-live {:retry {:max-retries 5}}))))
        (t/testing "file without :retry falls back to the config (project override)"
          (fs/create-dirs tmp)
          (spit settings-file "{:provider :opencode-go}\n")
          (t/is (= {:enabled true :max-retries 5 :base-delay-ms 2000}
                   (cfg/get-retry-settings-live {:retry {:max-retries 5}}))))
        (t/testing "unreadable file falls back to the config value"
          (fs/create-dirs tmp)
          (spit settings-file "not-edn[")
          (t/is (= {:enabled true :max-retries 5 :base-delay-ms 2000}
                   (cfg/get-retry-settings-live {:retry {:max-retries 5}})))))
      (finally (fs/delete-tree tmp)))))

(t/deftest test-set-enabled-models!
  (let [tmp (str (fs/absolutize (fs/file "target" (str "test-enabled-models-" (System/currentTimeMillis)))))
        settings-file (str tmp "/settings.edn")]
    (fs/create-dirs tmp)
    (try
      (with-redefs [cfg/global-settings-path (fn [] settings-file)]
        (cfg/set-enabled-models! ["opencode-go/deepseek-v4-flash"])
        (t/is (= {:enabled-models ["opencode-go/deepseek-v4-flash"]}
                 (edn/read-string (slurp settings-file))))
        (t/testing "nil removes the filter (all enabled)"
          (cfg/set-enabled-models! nil)
          (t/is (= {:enabled-models nil} (edn/read-string (slurp settings-file))))
          (t/is (nil? (cfg/get-enabled-models (edn/read-string (slurp settings-file)))))))
      (finally (fs/delete-tree tmp)))))

(t/deftest test-set-hide-thinking-block!
  (let [tmp (str (fs/absolutize (fs/file "target" (str "test-settings-" (System/currentTimeMillis)))))
        settings-file (str tmp "/settings.edn")]
    (fs/create-dirs tmp)
    (try
      (with-redefs [cfg/global-settings-path (fn [] settings-file)]
        (t/testing "writes to global settings file, merging existing keys"
          (spit settings-file "{:provider :openai}\n")
          (cfg/set-hide-thinking-block! true)
          (t/is (= {:provider :openai :hide-thinking-block true}
                   (edn/read-string (slurp settings-file))))
          (t/testing "second toggle updates the same key"
            (cfg/set-hide-thinking-block! false)
            (t/is (= {:provider :openai :hide-thinking-block false}
                     (edn/read-string (slurp settings-file))))))
        (t/testing "creates the file when missing"
          (fs/delete-tree tmp)
          (cfg/set-hide-thinking-block! true)
          (t/is (= {:hide-thinking-block true} (edn/read-string (slurp settings-file)))))
        (t/testing "non-map file content is replaced, not merged"
          (spit settings-file "[1 2 3]\n")
          (cfg/set-hide-thinking-block! true)
          (t/is (= {:hide-thinking-block true} (edn/read-string (slurp settings-file))))))
      (finally (fs/delete-tree tmp)))))

(t/deftest test-save-setting-pretty-format
  (t/testing "one entry per line, closing brace on its own line (pi: JSON.stringify(,2))"
    (let [tmp (str (fs/absolutize (fs/file "target" (str "test-settings-pretty-" (System/currentTimeMillis)))))
          settings-file (str tmp "/settings.edn")]
      (fs/create-dirs tmp)
      (try
        (with-redefs [cfg/global-settings-path (fn [] settings-file)]
          (cfg/save-setting! [:provider] :opencode-go)
          (t/is (= "{:provider :opencode-go\n}\n" (slurp settings-file))))
        (finally (fs/delete-tree tmp))))))

(t/deftest test-save-setting-preserves-comments
  (t/testing "in-place update keeps unrelated lines"
    (let [tmp (str (fs/absolutize (fs/file "target" (str "test-settings-comments-" (System/currentTimeMillis)))))
          settings-file (str tmp "/settings.edn")]
      (fs/create-dirs tmp)
      (try
        (with-redefs [cfg/global-settings-path (fn [] settings-file)]
          (spit settings-file "{:provider :opencode-go\n ;; keep me\n :hide-thinking-block true\n}\n")
          (cfg/set-hide-thinking-block! false)
          (t/is (= "{:provider :opencode-go\n ;; keep me\n :hide-thinking-block false\n}\n"
                   (slurp settings-file))))
        (finally (fs/delete-tree tmp)))))
  (t/testing "inserting a new key preserves comments, and later updates stay in-place"
    (let [tmp (str (fs/absolutize (fs/file "target" (str "test-settings-comments2-" (System/currentTimeMillis)))))
          settings-file (str tmp "/settings.edn")]
      (fs/create-dirs tmp)
      (try
        (with-redefs [cfg/global-settings-path (fn [] settings-file)]
          (spit settings-file "{:provider :opencode-go\n ;; keep me\n}\n")
          (cfg/set-hide-thinking-block! true)
          (t/is (= "{:provider :opencode-go\n ;; keep me\n :hide-thinking-block true\n}\n"
                   (slurp settings-file)))
          (t/testing "second toggle updates in place, comment still there"
            (cfg/set-hide-thinking-block! false)
            (t/is (= "{:provider :opencode-go\n ;; keep me\n :hide-thinking-block false\n}\n"
                     (slurp settings-file)))))
        (finally (fs/delete-tree tmp))))))

(t/deftest test-save-setting-nested-merge
  (t/testing "nested fields merge leaf-wise, other nested keys survive (pi: persistScopedSettings)"
    (let [tmp (str (fs/absolutize (fs/file "target" (str "test-settings-nested-" (System/currentTimeMillis)))))
          settings-file (str tmp "/settings.edn")]
      (fs/create-dirs tmp)
      (try
        (with-redefs [cfg/global-settings-path (fn [] settings-file)]
          (cfg/save-setting! [:terminal :show-images] false)
          (cfg/save-setting! [:terminal :image-width-cells] 80)
          (t/is (= {:terminal {:show-images false :image-width-cells 80}}
                   (edn/read-string (slurp settings-file)))))
        (finally (fs/delete-tree tmp))))))

(t/deftest test-concurrent-setting-saves
  (t/testing "lock serializes writes — no lost update (pi: proper-lockfile)"
    (let [tmp (str (fs/absolutize (fs/file "target" (str "test-settings-lock-" (System/currentTimeMillis)))))
          settings-file (str tmp "/settings.edn")]
      (fs/create-dirs tmp)
      (try
        (with-redefs [cfg/global-settings-path (fn [] settings-file)]
          (let [futs (doall (for [[k v] [[:hide-thinking-block true] [:provider :anthropic]]]
                              (future (cfg/save-setting! k v))))]
            (doseq [f futs] @f))
          (t/is (= {:hide-thinking-block true :provider :anthropic}
                   (edn/read-string (slurp settings-file)))))
        (finally (fs/delete-tree tmp))))))
