(ns kmet.app.test-model-config
  "Phase 6: models.edn loading (global + project merge), schema validation
   error paths, get-error on parse failure (pi model-config.ts)."
  (:require [clojure.test :as t]
            [clojure.string :as str]
            [babashka.fs :as fs]
            [kmet.app.model-config :as mc]))

(defn- with-models-edn-paths
  "Run F with models.edn redirected to a temp global/project pair."
  [f]
  (let [tmp (str (fs/absolutize (fs/file "target" (str "test-model-config-" (System/currentTimeMillis)))))
        global (str tmp "/agent/models.edn")
        project (str tmp "/project/models.edn")]
    (fs/create-dirs (fs/parent global))
    (fs/create-dirs (fs/parent project))
    (try
      (with-redefs [mc/models-edn-paths (fn [] [global project])]
        (f global project))
      (finally (fs/delete-tree tmp)))))

(defn- write! [path content]
  (spit path content))

;; ─── Loading ───────────────────────────────────────────────────────────────

(t/deftest test-load-no-files
  (with-models-edn-paths
    (fn [_global _project]
      (t/is (= {:providers {} :error nil} (mc/load-config)))
      (t/is (= {:providers {} :error nil} (mc/load-config!)))
      (t/is (= nil (mc/get-error)))
      (t/is (= {} (mc/get-providers))))))

(t/deftest test-load-global-only
  (with-models-edn-paths
    (fn [global _project]
      (write! global "{:providers {:my {:base-url \"https://x/v1\" :api :openai-completions
                                        :models [{:id \"m1\"}]}}}\n")
      (let [config (mc/load-config)]
        (t/is (nil? (:error config)))
        (t/is (= "https://x/v1" (get-in config [:providers :my :base-url])))
        (t/is (= [{:id "m1"}] (get-in config [:providers :my :models])))))))

(t/deftest test-load-project-overrides-global
  (with-models-edn-paths
    (fn [global project]
      (write! global "{:providers {:my {:base-url \"https://global/v1\" :api :openai-completions
                                        :models [{:id \"m1\"}]}
                                   :other {:base-url \"https://other/v1\" :api :anthropic-messages
                                           :models [{:id \"m2\"}]}}}\n")
      (write! project "{:providers {:my {:base-url \"https://project/v1\"}}}\n")
      (let [config (mc/load-config)]
        (t/is (nil? (:error config)))
        (t/testing "project base-url wins; untouched providers survive the deep merge"
          (t/is (= "https://project/v1" (get-in config [:providers :my :base-url])))
          (t/is (= "https://other/v1" (get-in config [:providers :other :base-url])))
          (t/is (= [{:id "m1"}] (get-in config [:providers :my :models]))))))))

(t/deftest test-load-parse-error
  (with-models-edn-paths
    (fn [global _project]
      (write! global "{:providers {:my {:base-url")
      (let [config (mc/load-config)]
        (t/is (= {} (:providers config)))
        (t/is (some? (:error config)))
        (t/is (str/starts-with? (:error config) "Failed to parse models.edn:"))
        (t/is (str/includes? (:error config) "File: "))))))

(t/deftest test-load-non-map-root
  (with-models-edn-paths
    (fn [global _project]
      (write! global "[1 2 3]\n")
      (let [config (mc/load-config)]
        (t/is (= {} (:providers config)))
        (t/is (str/includes? (:error config) "root: expected object"))))))

(t/deftest test-load-into-atom
  (with-models-edn-paths
    (fn [global _project]
      (write! global "{:providers {:my {:base-url \"https://x/v1\" :api :openai-completions
                                        :models [{:id \"m1\"}]}}}\n")
      (t/is (= {:providers {:my {:base-url "https://x/v1" :api :openai-completions :models [{:id "m1"}]}}
                :error nil}
               (mc/load-config!)))
      (t/is (= {:my {:base-url "https://x/v1" :api :openai-completions :models [{:id "m1"}]}}
               (mc/get-providers)))
      (t/is (= [:my] (mc/get-provider-ids)))
      (t/is (= "https://x/v1" (get-in (mc/get-provider :my) [:base-url])))
      (t/is (nil? (mc/get-provider :nope)))
      (t/is (nil? (mc/get-error))))))

;; ─── Schema validation ─────────────────────────────────────────────────────

(t/deftest test-validate-config-valid
  (t/is (= [] (mc/validate-config
               {:providers
                {:my {:name "My Provider"
                      :base-url "https://api.example.com/v1"
                      :api-key "$MY_PROVIDER_KEY"
                      :api :openai-completions
                      :headers {"X-Custom" "value"}
                      :compat {:supports-reasoning-effort true}
                      :auth-header true
                      :models [{:id "my-model" :name "My Model"
                                :reasoning true :input [:text :image]
                                :thinking-level-map {:high "high" :max nil}
                                :context-window 200000 :max-tokens 16384
                                :cost {:input 0.5 :output 1.5 :cache-read 0.1 :cache-write 0.5}
                                :headers {"X-Model" "m"}
                                :compat {:max-tokens-field :max-tokens}}]
                      :model-overrides
                      {"deepseek-v4-flash" {:context-window 128000
                                            :cost {:input 0.2}}}}}}))))

(t/deftest test-validate-config-errors
  (t/testing "missing providers key"
    (t/is (= ["providers: expected object"] (mc/validate-config {}))))
  (t/testing "non-map root"
    (t/is (= ["root: expected object"] (mc/validate-config [1]))))
  (t/testing "model missing id and definition cost requires all four rates"
    (t/is (= ["providers.my.models[0].id: required non-empty string"
              "providers.my.models[0].cost.input: expected number"
              "providers.my.models[0].cost.cache-read: expected number"
              "providers.my.models[0].cost.cache-write: expected number"]
             (mc/validate-config {:providers {:my {:models [{:cost {:output 1}}]}}}))))
  (t/testing "full path-style messages"
    (t/is (= ["providers.my.models[0].cost: expected map"]
             (mc/validate-config {:providers {:my {:models [{:id "m" :cost 5}]}}})))
    (t/is (= ["providers.my.models[0].context-window: expected number"]
             (mc/validate-config {:providers {:my {:models [{:id "m" :context-window "big"}]}}})))
    (t/is (= ["providers.my.model-overrides.deepseek-v4-flash.cost.input: expected number"]
             (mc/validate-config {:providers {:my {:model-overrides {"deepseek-v4-flash" {:cost {:input "x"}}}}}})))
    (t/is (= ["providers.my.models[0].compat.supports-reasoning-effort: expected boolean"]
             (mc/validate-config {:providers {:my {:models [{:id "m" :compat {:supports-reasoning-effort "yes"}}]}}})))
    (t/is (= ["providers.my.headers.X-Custom: expected string"]
             (mc/validate-config {:providers {:my {:headers {"X-Custom" 5}}}})))
    (t/is (= ["providers.my.models[0].input: expected :text or :image"]
             (mc/validate-config {:providers {:my {:models [{:id "m" :input [:text :video]}]}}})))
    (t/is (= ["providers.my.models[0].thinking-level-map.ultra: unknown thinking level"]
             (mc/validate-config {:providers {:my {:models [{:id "m" :thinking-level-map {:ultra "x"}}]}}}))))
  (t/testing "unknown keys pass (pi TypeBox allows additional properties)"
    (t/is (= [] (mc/validate-config {:providers {:my {:models [{:id "m" :typo-key 1}]}}})))))

(t/deftest test-string-provider-id-normalized
  (t/testing "string provider keys normalize to keywords (pi models.json ids are strings)"
    (with-models-edn-paths
      (fn [global _project]
        (write! global "{:providers {\"string-prov\" {:base-url \"https://x/v1\" :api :openai-completions
                                                     :models [{:id \"m\"}]}}}\n")
        (let [config (mc/load-config)]
          (t/is (nil? (:error config)))
          (t/is (= [:string-prov] (keys (:providers config))))
          (t/is (= "https://x/v1" (get-in config [:providers :string-prov :base-url]))))))))
