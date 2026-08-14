(ns kmet.app.test-image-models
  "Deferred B: image models registry — catalog loading + offline validation
   (the generator script's validate-committed! is the single source of
   truth), the registry semantics, the :openrouter-images wire (mocked
   HTTP), usage/cost, and the never-throw generate-images contract."
  (:require [clojure.string :as str]
            [clojure.test :as t :refer [testing]]
            [kmet.app.auth :as auth]
            [kmet.app.image-models :as im]
            [kmet.app.proxy :as proxy]))

(defn- validate-committed!
  "Run the generator script's offline validation over the committed catalog."
  []
  (let [f (delay
            (load-file "scripts/generate_image_models.clj")
            (ns-resolve 'generate-image-models 'validate-committed!))]
    (when-not @f
      (throw (ex-info "scripts/generate_image_models.clj did not define validate-committed!"
                      {:type :script-invalid})))
    (@f)))

;; ─── Catalog loading + offline validation ──────────────────────────────────

(t/deftest test-image-catalog-loads
  (im/load-image-catalogs!)
  (let [providers (im/get-providers)
        models (im/get-models)]
    (t/is (= [:openrouter] (mapv :id providers)))
    (t/is (= "OpenRouter" (:name (im/get-provider :openrouter))))
    (t/is (seq models))
    (t/is (= (count models) (count (distinct (map :id models)))) "no duplicate ids")
    (doseq [m models]
      (t/is (string? (:id m)))
      (t/is (string? (:name m)))
      (t/is (= :openrouter-images (:api m)))
      (t/is (= :openrouter (:provider m)))
      (t/is (str/starts-with? (:base-url m) "https://"))
      (t/is (seq (:input m)))
      (t/is (some #{:image} (:output m)))
      (t/is (every? number? ((juxt :input :output :cache-read :cache-write) (:cost m)))))
    (t/is (some? (im/get-model :openrouter "openrouter/auto")))
    (t/is (nil? (im/get-model :openrouter "no-such-model")))
    (t/is (empty? (im/get-models :no-such-provider)))))

(t/deftest test-image-catalog-validates
  (t/is (empty? (validate-committed!)) "committed catalog passes the offline gate"))

;; ─── Registry semantics ────────────────────────────────────────────────────

(t/deftest test-image-registry
  (im/clear-providers!)
  (let [p (im/map->ImagesProvider {:id :test :name "Test" :models []})]
    (im/register-provider! p)
    (t/is (= :test (:id (im/get-provider :test))))
    (t/is (= 1 (count (im/get-providers))))
    (im/unregister-provider! :test)
    (t/is (nil? (im/get-provider :test)))
    (t/is (empty? (im/get-providers)))))

(t/deftest test-image-api-registry
  (im/register-images-api-provider! :fake (fn [_ _ _] :ok))
  (t/is (= :ok ((im/get-images-api-provider :fake) nil nil nil)))
  (t/is (nil? (im/get-images-api-provider :unregistered))))

(t/deftest test-image-get-auth
  (testing "unconfigured provider → nil"
    (with-redefs [auth/resolve-provider-auth (fn [_] nil)]
      (t/is (nil? (im/get-auth :openrouter)))))
  (testing "configured → the resolved api key"
    (with-redefs [auth/resolve-provider-auth (fn [_] {:api-key "k"})]
      (t/is (= "k" (:api-key (im/get-auth :openrouter)))))))

;; ─── :openrouter-images wire (mocked HTTP) ─────────────────────────────────

(t/deftest test-images-context->content
  (t/is (= [{:type "text" :text "hi"}
            {:type "image_url" :image_url {:url "data:image/png;base64,AAA"}}]
           (@#'im/images-context->content
            {:input [{:type :text :text "hi"}
                     {:type :image :mime-type "image/png" :data "AAA"}]}))))

(t/deftest test-parse-images-usage
  (let [model (im/map->ImagesModel
               {:id "m" :name "m" :api :openrouter-images :provider :openrouter
                :base-url "https://x" :input [:text] :output [:image]
                :cost {:input 10 :output 20 :cache-read 2 :cache-write 4}})]
    (testing "cached tokens subtracted from input (pi)"
      (let [u (im/parse-images-usage {:prompt_tokens 100
                                      :prompt_tokens_details {:cached_tokens 30}
                                      :completion_tokens 50}
                                     model)]
        (t/is (= 70 (:input u)))
        (t/is (= 30 (:cache-read u)))
        (t/is (= 50 (:output u)))
        (t/is (= 0 (:cache-write u)))
        (t/is (= 150 (:total-tokens u)))
        (let [c (:cost u)]
          (t/is (< (Math/abs (- (:input c) 0.0007)) 1e-9))
          (t/is (< (Math/abs (- (:output c) 0.001)) 1e-9))
          (t/is (< (Math/abs (- (:cache-read c) 0.00006)) 1e-9))
          (t/is (< (Math/abs (- (:total c) 0.00176)) 1e-9)))))
    (testing "cache_write splits the reported cached tokens"
      (let [u (im/parse-images-usage {:prompt_tokens 100
                                      :prompt_tokens_details {:cached_tokens 30
                                                              :cache_write_tokens 10}}
                                     model)]
        (t/is (= 70 (:input u)))
        (t/is (= 20 (:cache-read u)))
        (t/is (= 10 (:cache-write u)))))
    (testing "no usage fields → zeros"
      (let [u (im/parse-images-usage {} model)]
        (t/is (zero? (:total-tokens u)))))))

(t/deftest test-generate-images-wire
  (im/load-image-catalogs!)
  (let [model (im/get-model :openrouter "openrouter/auto")
        context {:input [{:type :text :text "a red cube"}
                         {:type :image :mime-type "image/png" :data "BASE64"}]}
        captured (atom nil)]
    (with-redefs [proxy/request-json
                  (fn [url opts _]
                    (reset! captured [url opts])
                    ;; request-json parses with keyword keys (json/parse-string
                    ;; body true), so the mock body is keyword-keyed.
                    {:status 200
                     :body {:id "gen-1"
                            :usage {:prompt_tokens 100
                                    :prompt_tokens_details {:cached_tokens 40}
                                    :completion_tokens 50}
                            :choices [{:message
                                       {:content "Here is your image:"
                                        :images [{:image_url "data:image/png;base64,AAAA"}
                                                 {:image_url {:url "data:image/jpeg;base64,BBBB"}}]}}]}})]
      (let [r (im/generate-images model context {:api-key "test-key"})]
        (t/is (= :stop (:stop-reason r)))
        (t/is (= "gen-1" (:response-id r)))
        (t/is (= "openrouter" (name (:provider r))))
        (t/is (= [{:type :text :text "Here is your image:"}
                  {:type :image :mime-type "image/png" :data "AAAA"}
                  {:type :image :mime-type "image/jpeg" :data "BBBB"}]
                 (:output r)))
        (let [u (:usage r)]
          (t/is (= 60 (:input u)) "100 prompt minus 40 cached")
          (t/is (= 50 (:output u)))
          (t/is (= 40 (:cache-read u)))
          (t/is (map? (:cost u))))
        (let [[url opts] @captured]
          (t/is (= "https://openrouter.ai/api/v1/chat/completions" url))
          (t/is (= "Bearer test-key" (get-in opts [:headers "Authorization"])))
          (t/is (= ["image" "text"] (get-in opts [:body "modalities"]))
                "auto outputs text too")
          (t/is (= "user" (get-in opts [:body "messages" 0 "role"])))
          (t/is (= "data:image/png;base64,BASE64"
                   (get-in opts [:body "messages" 0 "content" 1 :image_url :url]))))))))

;; ─── generate-images contract (never throws) ───────────────────────────────

(t/deftest test-generate-images-errors
  (im/load-image-catalogs!)
  (let [model (im/get-model :openrouter "openrouter/auto")]
    (testing "unknown provider → :error"
      (let [r (im/generate-images (assoc model :provider :nope) {:input []})]
        (t/is (= :error (:stop-reason r)))
        (t/is (str/includes? (:error-message r) "Unknown provider"))
        (t/is (empty? (:output r)))))
    (testing "missing api key → :error"
      (with-redefs [auth/resolve-provider-auth (fn [_] nil)]
        (let [r (im/generate-images model {:input []})]
          (t/is (= :error (:stop-reason r)))
          (t/is (str/includes? (:error-message r) "No API key")))))
    (testing "unregistered api → :error"
      (with-redefs [auth/resolve-provider-auth (fn [_] {:api-key "k"})]
        (let [r (im/generate-images (assoc model :api :nope-api) {:input []})]
          (t/is (= :error (:stop-reason r)))
          (t/is (str/includes? (:error-message r) "No API provider")))))
    (testing "wire failure → :error"
      (with-redefs [auth/resolve-provider-auth (fn [_] {:api-key "k"})
                    im/get-images-api-provider
                    (fn [_] (fn [_ _ _] (throw (ex-info "boom" {}))))]
        (let [r (im/generate-images model {:input []})]
          (t/is (= :error (:stop-reason r)))
          (t/is (= "boom" (:error-message r))))))
    (testing "signal set on failure → :aborted"
      (with-redefs [auth/resolve-provider-auth (fn [_] {:api-key "k"})
                    im/get-images-api-provider
                    (fn [_] (fn [_ _ _] (throw (ex-info "boom" {}))))]
        (let [r (im/generate-images model {:input []} {:signal (atom true)})]
          (t/is (= :aborted (:stop-reason r))))))
    (testing "explicit :api-key wins over resolved auth"
      (with-redefs [auth/resolve-provider-auth (fn [_] (throw (ex-info "should not resolve" {})))
                    im/get-images-api-provider
                    (fn [_] (fn [m _c k] {:stop-reason :stop :api (:api m) :provider (:provider m)
                                          :model (:id m) :output [] :api-key-used k :timestamp 1}))]
        (let [r (im/generate-images model {:input []} {:api-key "explicit"})]
          (t/is (= :stop (:stop-reason r)))
          (t/is (= "explicit" (:api-key-used r))))))))
