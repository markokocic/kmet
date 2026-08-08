(ns kmet.app.test-models
  "Phase 0: registry semantics, catalog loading, EDN shape, manifest."
  (:require [clojure.test :as t]
            [kmet.app.models :as m]
            [kmet.app.auth :as auth]))

;; ─── Registry semantics (pi: MutableModels) ────────────────────────────────

(defn- test-provider
  [id & [models]]
  (m/map->Provider {:id id :name (str id) :api-types #{:openai-completions}
                    :models (or models []) :env-vars [] :default-model nil}))

(t/deftest test-register-override-clear
  (m/clear-providers!)
  (m/register-provider! (test-provider :alpha))
  (t/is (= :alpha (:id (m/get-provider :alpha))))
  (t/is (= 1 (count (m/get-providers))))
  (t/testing "registering the same id replaces the entry (pi setProvider upsert)"
    (m/register-provider! (assoc (test-provider :alpha) :name "Alpha 2"))
    (t/is (= "Alpha 2" (:name (m/get-provider :alpha))))
    (t/is (= 1 (count (m/get-providers)))))
  (t/testing "unregister removes only that id"
    (m/register-provider! (test-provider :beta))
    (m/unregister-provider! :alpha)
    (t/is (nil? (m/get-provider :alpha)))
    (t/is (= :beta (:id (m/get-provider :beta)))))
  (t/testing "clear removes everything"
    (m/clear-providers!)
    (t/is (empty? (m/get-providers)))))

(t/deftest test-get-models-and-get-model
  (m/clear-providers!)
  (let [a (m/map->Model {:id "m1" :name "M1" :provider :alpha
                         :api :openai-completions :base-url "https://a.example/v1"
                         :reasoning false :input [:text]
                         :cost {:input 0 :output 0 :cache-read 0 :cache-write 0}
                         :context-window 1000 :max-tokens 100})
        b (m/map->Model {:id "m2" :name "M2" :provider :alpha
                         :api :openai-completions :base-url "https://a.example/v1"
                         :reasoning true :input [:text :image]
                         :cost {:input 1 :output 2 :cache-read 3 :cache-write 4}
                         :context-window 2000 :max-tokens 200})
        c (m/map->Model {:id "m1" :name "M1" :provider :beta
                         :api :openai-completions :base-url "https://b.example/v1"
                         :reasoning false :input [:text]
                         :cost {:input 0 :output 0 :cache-read 0 :cache-write 0}
                         :context-window 1000 :max-tokens 100})]
    (m/register-provider! (test-provider :alpha [a b]))
    (m/register-provider! (test-provider :beta [c]))
    (t/testing "get-models scoped to one provider (pi Models.getModels)"
      (t/is (= ["m1" "m2"] (mapv :id (m/get-models :alpha))))
      (t/is (= ["m1"] (mapv :id (m/get-models :beta))))
      (t/is (= [] (m/get-models :unknown))))
    (t/testing "get-models across all providers"
      (t/is (= ["m1" "m2" "m1"] (mapv :id (m/get-models)))))
    (t/testing "get-model by provider + id (pi Models.getModel)"
      (t/is (= b (m/get-model :alpha "m2")))
      (t/is (nil? (m/get-model :alpha "nope")))
      (t/is (nil? (m/get-model :unknown "m1"))))
    (t/testing "same id in different providers are distinct models"
      (t/is (not= (m/get-model :alpha "m1") (m/get-model :beta "m1"))))))

(t/deftest test-default-model-for
  (m/clear-providers!)
  (m/register-provider! (m/map->Provider {:id :p
                                          :name "P"
                                          :api-types #{:openai-completions}
                                          :models [(m/map->Model {:id "a" :name "A"
                                                                  :provider :p
                                                                  :api :openai-completions
                                                                  :base-url "u"
                                                                  :reasoning false
                                                                  :input [:text]
                                                                  :cost {:input 0 :output 0
                                                                         :cache-read 0
                                                                         :cache-write 0}
                                                                  :context-window 1
                                                                  :max-tokens 1})]
                                          :env-vars []
                                          :default-model "a"
                                          :base-url nil :headers nil}))
  (t/is (= "a" (:id (m/default-model-for :p))))
  (t/testing "falls back to the first catalog model when :default-model is unset"
    (m/register-provider! (assoc (m/get-provider :p) :default-model nil))
    (t/is (= "a" (:id (m/default-model-for :p))))
    (t/testing "unknown provider → nil"
      (m/unregister-provider! :p)
      (t/is (nil? (m/default-model-for :p))))))

;; ─── Catalog loading ───────────────────────────────────────────────────────

(t/deftest test-load-catalogs
  (let [providers (m/load-catalogs!)]
    (t/is (map? providers))
    (t/is (= #{:opencode-go :opencode :deepseek :github-copilot} (set (keys providers))))
    (t/testing "provider records carry catalog metadata"
      (let [og (m/get-provider :opencode-go)]
        (t/is (= "OpenCode Go" (:name og)))
        (t/is (= ["OPENCODE_API_KEY"] (:env-vars og)))
        (t/is (= "deepseek-v4-flash" (:default-model og)))
        (t/is (= #{:openai-completions :anthropic-messages}
                 (:api-types og))))
      (t/is (= #{:openai-completions :anthropic-messages :google-generative-ai}
               (:api-types (m/get-provider :opencode))))
      (t/is (= #{:openai-completions} (:api-types (m/get-provider :deepseek))))
      (t/is (= #{:openai-completions :anthropic-messages}
               (:api-types (m/get-provider :github-copilot))))
      (t/is (= ["DEEPSEEK_API_KEY"] (:env-vars (m/get-provider :deepseek))))
      (t/is (= ["COPILOT_GITHUB_TOKEN"] (:env-vars (m/get-provider :github-copilot)))))
    (t/testing "default provider/model resolves against the opencode-go catalog"
      (t/is (= "deepseek-v4-flash" (:id (m/default-model-for :opencode-go)))))))

(t/deftest test-catalog-edn-shape
  (m/load-catalogs!)
  (let [models (m/get-models)]
    (t/is (seq models))
    (doseq [mod models]
      (t/is (record? mod) "catalog entries become Model records")
      (t/is (every? #(contains? mod %) [:id :name :provider :api :base-url
                                        :reasoning :input :cost
                                        :context-window :max-tokens])
            "Model record carries every required field")
      (t/is (string? (:id mod)) (str "id is a string: " (:id mod)))
      (t/is (string? (:name mod)))
      (t/is (keyword? (:provider mod)))
      (t/is (contains? #{:openai-completions :anthropic-messages :google-generative-ai}
                       (:api mod)))
      (t/is (string? (:base-url mod)))
      (t/is (boolean? (:reasoning mod)))
      (t/is (vector? (:input mod)))
      (t/is (seq (:input mod)))
      (let [{:keys [input output cache-read cache-write]} (:cost mod)]
        (t/is (every? number? [input output cache-read cache-write])
              (str "cost has 4 numeric fields: " (:cost mod))))
      (t/is (pos? (:context-window mod)))
      (t/is (pos? (:max-tokens mod)))
      (t/is (= (:id mod) (-> (m/get-model (:provider mod) (:id mod)) :id))
            "registered model is reachable via get-model"))
    (t/testing "copilot models carry static headers (COPILOT_STATIC_HEADERS)"
      (let [copilot (m/get-model :github-copilot "claude-sonnet-4.5")]
        (t/is (= "GitHubCopilotChat/0.35.0" (get-in copilot [:headers "User-Agent"]))))
      (t/is (nil? (:headers (m/get-model :deepseek "deepseek-v4-pro")))))))
(t/deftest test-catalog-validation
  (t/testing "missing :provider info block is rejected"
    (t/is (thrown-with-msg? Exception #"no :provider info block"
                            (@#'m/validate-catalog! "x.edn" {:models {:openai-completions {}}}))))
  (t/testing "model in two api groups is rejected"
    (let [model {:id "m" :name "M" :provider :p :api :openai-completions
                 :base-url "u" :reasoning false :input [:text]
                 :cost {:input 0 :output 0 :cache-read 0 :cache-write 0}
                 :context-window 1 :max-tokens 1}
          data {:provider {:id :p}
                :models {:openai-completions {"m" model}
                         :anthropic-messages {"m" (assoc model :api :anthropic-messages)}}}]
      (t/is (thrown-with-msg? Exception #"more than one api group"
                              (@#'m/validate-catalog! "x.edn" data)))))
  (t/testing "model missing required fields is rejected"
    (t/is (thrown-with-msg? Exception #"missing required fields"
                            (@#'m/validate-catalog! "x.edn"
                                                    {:provider {:id :p}
                                                     :models {:openai-completions
                                                              {"m" {:id "m" :name "M"}}}})))))

;; ─── Manifest (pi: modelDataManifest) ──────────────────────────────────────

(t/deftest test-manifest-matches-committed-catalogs
  (t/is (m/manifest-matches?)
        "committed manifest.edn covers the committed catalog files (regenerate
         the manifest when catalogs change)"))

(t/deftest test-compute-manifest-shape
  (let [manifest (m/compute-manifest)]
    (t/is (= 1 (:schema-version manifest)))
    (t/is (re-matches #"[0-9a-f]{64}" (:structure-hash manifest)))
    (t/is (map? (:files manifest)))
    (t/testing "every catalog file is covered, manifest.edn excluded"
      (t/is (every? #(re-matches #".+\.edn" %) (keys (:files manifest))))
      (t/is (not (contains? (:files manifest) "manifest.edn")))
      (t/is (every? #(re-matches #"[0-9a-f]{64}" %) (vals (:files manifest)))))))

;; ─── get-available (auth check = auth/configured?, Phase 3) ───────────────

(t/deftest test-get-available
  (m/load-catalogs!)
  (t/testing "no keys → nothing available"
    (with-redefs [auth/configured? (fn [_] false)]
      (t/is (empty? (m/get-available)))))
  (t/testing "all keys → every model available"
    (with-redefs [auth/configured? (fn [_] true)]
      (let [available (m/get-available)]
        (t/is (= (count (m/get-models)) (count available)))
        (t/is (seq (m/get-available :deepseek))))))
  (t/testing "per-provider filter"
    (with-redefs [auth/configured? (fn [p] (= p :deepseek))]
      (let [available (m/get-available)]
        (t/is (= (mapv :id (m/get-models :deepseek)) (mapv :id available)))))))

;; ─── Config interop ────────────────────────────────────────────────────────

(t/deftest test-resolve-config-model
  (m/load-catalogs!)
  (t/testing ":model setting wins"
    (t/is (= "custom" (m/resolve-config-model {:provider :opencode-go
                                               :model "custom"}))))
  (t/testing "no :model → provider default from the registry"
    (t/is (= "deepseek-v4-flash" (m/resolve-config-model {:provider :opencode-go})))
    (t/is (= "deepseek-v4-pro" (m/resolve-config-model {:provider :deepseek}))))
  (t/testing "unknown provider → nil"
    (t/is (nil? (m/resolve-config-model {:provider :openai})))))
