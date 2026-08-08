(ns kmet.app.test-auth
  "Phase 3: credential resolution, env precedence (auth.edn over env, pi env
   order), configured?, and auth.edn persistence."
  (:require [clojure.test :as t]
            [clojure.edn :as edn]
            [babashka.fs :as fs]
            [kmet.app.auth :as auth]
            [kmet.config :as cfg]))

(defn- with-auth-file
  "Run F with auth.edn redirected to a temp file and an empty auth atom."
  [f]
  (let [tmp (str (fs/absolutize (fs/file "target" (str "test-auth-" (System/currentTimeMillis)))))
        path (str tmp "/auth.edn")]
    (fs/create-dirs tmp)
    (try
      (with-redefs [auth/auth-file-path (fn [] path)
                    auth/auth-atom (atom {})]
        (f path))
      (finally (fs/delete-tree tmp)))))

;; ─── Env var table (pi env-api-keys.ts) ────────────────────────────────────

(t/deftest test-provider-env-vars-table
  (t/testing "pi env-api-keys order"
    (t/is (= ["OPENCODE_API_KEY"] (auth/provider-env-vars :opencode-go)))
    (t/is (= ["OPENCODE_API_KEY"] (auth/provider-env-vars :opencode)))
    (t/is (= ["DEEPSEEK_API_KEY"] (auth/provider-env-vars :deepseek)))
    (t/is (= ["COPILOT_GITHUB_TOKEN"] (auth/provider-env-vars :github-copilot)))
    (t/is (= ["OPENAI_API_KEY"] (auth/provider-env-vars :openai)))
    (t/is (= ["ANTHROPIC_API_KEY"] (auth/provider-env-vars :anthropic)))
    (t/is (= ["GEMINI_API_KEY"] (auth/provider-env-vars :google))))
  (t/testing "unknown provider → empty"
    (t/is (= [] (auth/provider-env-vars :nonexistent)))))

;; ─── resolve-api-key precedence ────────────────────────────────────────────

(t/deftest test-resolve-api-key-precedence
  (t/testing "auth.edn credential wins over env var (pi: auth.json before env)"
    (with-redefs [auth/auth-atom (atom {:deepseek {:key "file-key"}})
                  auth/getenv (fn [_] "env-key")]
      (t/is (= "file-key" (auth/resolve-api-key :deepseek)))))
  (t/testing "env var used when no auth.edn entry"
    (with-redefs [auth/auth-atom (atom {})
                  auth/getenv #(when (= % "DEEPSEEK_API_KEY") "env-key")]
      (t/is (= "env-key" (auth/resolve-api-key :deepseek)))))
  (t/testing "first present env var in pi order wins"
    (with-redefs [auth/auth-atom (atom {})
                  auth/provider-env-vars (fn [_] ["TEST_A" "TEST_B"])
                  auth/getenv #(get {"TEST_A" "a" "TEST_B" "b"} %)]
      (t/is (= "a" (auth/resolve-api-key :fake))))
    (with-redefs [auth/auth-atom (atom {})
                  auth/provider-env-vars (fn [_] ["TEST_A" "TEST_B"])
                  auth/getenv #(get {"TEST_B" "b"} %)]
      (t/is (= "b" (auth/resolve-api-key :fake)))))
  (t/testing "unknown provider → nil"
    (with-redefs [auth/auth-atom (atom {})
                  auth/getenv (fn [_] nil)]
      (t/is (nil? (auth/resolve-api-key :nonexistent))))))

;; ─── configured? semantics ─────────────────────────────────────────────────

(t/deftest test-configured?
  (t/testing "auth.edn entry → true even without env vars"
    (with-redefs [auth/auth-atom (atom {:deepseek {:key "k"}})
                  auth/getenv (fn [_] nil)]
      (t/is (true? (auth/configured? :deepseek)))))
  (t/testing "any env var present → true"
    (with-redefs [auth/auth-atom (atom {})
                  auth/getenv #(when (= % "DEEPSEEK_API_KEY") "k")]
      (t/is (true? (auth/configured? :deepseek)))))
  (t/testing "neither → false; unknown provider → false"
    (with-redefs [auth/auth-atom (atom {})
                  auth/getenv (fn [_] nil)]
      (t/is (false? (auth/configured? :deepseek)))
      (t/is (false? (auth/configured? :nonexistent))))))

;; ─── auth.edn persistence ──────────────────────────────────────────────────

(t/deftest test-set-remove-credential!
  (with-auth-file
    (fn [path]
      (t/testing "set-credential! writes auth.edn and refreshes the atom"
        (auth/set-credential! :deepseek "sk-123")
        (t/is (= {:deepseek {:key "sk-123"}} (edn/read-string (slurp path))))
        (t/is (= "sk-123" (auth/resolve-api-key :deepseek))))
      (t/testing "set-credential! keeps other providers"
        (auth/set-credential! :opencode-go "ok-456")
        (t/is (= {:deepseek {:key "sk-123"} :opencode-go {:key "ok-456"}}
                 (edn/read-string (slurp path)))))
      (t/testing "remove-credential! drops only that provider"
        (auth/remove-credential! :deepseek)
        (t/is (= {:opencode-go {:key "ok-456"}} (edn/read-string (slurp path))))
        (t/is (nil? (auth/resolve-api-key :deepseek))))
      (t/testing "remove-credential! is a no-op when absent"
        (auth/remove-credential! :deepseek)
        (t/is (= {:opencode-go {:key "ok-456"}} (edn/read-string (slurp path))))))))

(t/deftest test-set-credential-replaces-non-map-file
  (with-auth-file
    (fn [path]
      (t/testing "non-map content is replaced, not merged (pi settings behavior)"
        (spit path "[1 2 3]\n")
        (auth/set-credential! :deepseek "sk-1")
        (t/is (= {:deepseek {:key "sk-1"}} (edn/read-string (slurp path))))
        (t/is (= "sk-1" (auth/resolve-api-key :deepseek))))
      (t/testing "load-auth! treats non-map content as empty"
        (spit path "[1 2 3]\n")
        (t/is (= {} (auth/load-auth!)))))))

(t/deftest test-load-auth!
  (with-auth-file
    (fn [path]
      (spit path "{:opencode-go {:key \"ok-1\"}}\n")
      (t/is (= {:opencode-go {:key "ok-1"}} (auth/load-auth!)))
      (t/is (= "ok-1" (auth/resolve-api-key :opencode-go))))))

(t/deftest test-auth-file-pretty-format
  (with-auth-file
    (fn [path]
      (auth/set-credential! :deepseek "sk-1")
      (t/is (= "{:deepseek {:key \"sk-1\"}\n}\n" (slurp path)))
      (t/testing "the lock file is cleaned up after the write"
        (t/is (false? (fs/exists? (str path ".lock"))))))))

(t/deftest test-concurrent-credential-writes
  (t/testing "lock serializes read-modify-write — no lost update"
    (with-auth-file
      (fn [path]
        (let [futs (doall (for [[p k] [[:deepseek "sk-1"] [:opencode-go "ok-1"]]]
                            (future (auth/set-credential! p k))))]
          (doseq [f futs] @f)
          (t/is (= {:deepseek {:key "sk-1"} :opencode-go {:key "ok-1"}}
                   (edn/read-string (slurp path)))))))))

;; ─── config delegation ─────────────────────────────────────────────────────

(t/deftest test-config-get-api-key-delegates
  (t/testing "cfg/get-api-key resolves through kmet.app.auth (Phase 3)"
    (with-redefs [auth/auth-atom (atom {:deepseek {:key "file-key"}})
                  auth/getenv (fn [_] nil)]
      (t/is (= "file-key" (cfg/get-api-key :deepseek))))
    (with-redefs [auth/auth-atom (atom {})
                  auth/getenv (fn [_] nil)]
      (t/is (nil? (cfg/get-api-key :deepseek))))))
