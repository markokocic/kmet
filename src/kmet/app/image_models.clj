(ns kmet.app.image-models
  "Image-generation mirror of the chat model subsystem (pi: images.ts +
   images-models.ts + images-api-registry.ts + api/openrouter-images.ts +
   image-models.generated.ts, adapted to Babashka + static EDN).

   A pure API surface — pi has no coding-agent consumer and kmet ports it
   as-is (no /imagine tool). Records + a registry atom mirror
   kmet.app.models; auth reuses kmet.app.auth (:openrouter already resolves
   env + the Phase 16 OAuth credential); the one wire API is
   :openrouter-images (non-stream chat/completions with modalities). The
   committed catalog is one static provider (openrouter, 42 models) — no
   dynamic providers yet, so pi's refreshModels machinery is not ported."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [kmet.app.auth :as auth]
            [kmet.app.models :as models]
            [kmet.app.proxy :as proxy]))

;; ─── Records (pi types.ts ImagesModel / images-models.ts ImagesProvider) ──

(defrecord ImagesModel [id            ;; string id, e.g. "black-forest-labs/flux.2-flex"
                        name          ;; string
                        api           ;; :openrouter-images (extensible)
                        provider      ;; keyword, e.g. :openrouter
                        base-url      ;; string, e.g. "https://openrouter.ai/api/v1"
                        input         ;; [:text] | [:image] | [:text :image]
                        output        ;; [:image] | [:text :image]
                        cost])        ;; {:input $/M :output $/M :cache-read $/M :cache-write $/M}

(defrecord ImagesProvider [id          ;; keyword
                           name        ;; string
                           models])    ;; vector of ImagesModel

;; ─── Registry (pi images-models.ts ImagesModels collection) ────────────────

(defonce providers-atom (atom {}))

(defn register-provider!
  "Register (or replace by :id) an ImagesProvider (pi setProvider)."
  [provider]
  (swap! providers-atom assoc (:id provider) provider)
  nil)

(defn unregister-provider!
  "Remove an ImagesProvider by :id (pi deleteProvider)."
  [id]
  (swap! providers-atom dissoc id)
  nil)

(defn clear-providers!
  "Remove all providers (pi clearProviders; test + reload seam)."
  []
  (reset! providers-atom {})
  nil)

(defn get-providers
  "All registered ImagesProviders."
  []
  (vals @providers-atom))

(defn get-provider
  "An ImagesProvider by :id, or nil."
  [id]
  (get @providers-atom id))

(defn get-models
  "Last-known models: all providers (best-effort) or one provider by :id
   (pi getModels)."
  ([] (mapcat :models (get-providers)))
  ([provider-id]
   (some-> (get-provider provider-id) :models)))

(defn get-model
  "A model by provider :id + model id, or nil (pi getModel)."
  [provider-id model-id]
  (first (filter #(= model-id (:id %)) (get-models provider-id))))

;; ─── Wire-api registry (pi images-api-registry.ts) ─────────────────────────
;; Lookup is keyed by the model's :api, so a mismatched model can never reach
;; a wire fn through generate-images (pi's wrapGenerateImages guard is
;; structural here). Extensions register their own :api values.

(defonce ^:private api-registry (atom {}))

(defn register-images-api-provider!
  "Register a wire-api generation fn keyed by API (pi
   registerImagesApiProvider). GENERATE-FN is (fn [model context api-key] →
   AssistantImages)."
  [api generate-fn]
  (swap! api-registry assoc api generate-fn)
  nil)

(defn get-images-api-provider
  "The registered generation fn for an API, or nil (pi getImagesApiProvider)."
  [api]
  (get @api-registry api))

;; ─── :openrouter-images wire api (pi api/openrouter-images.ts) ─────────────

(def ^:private images-request-timeout-ms 120000)

(defn- images-context->content
  "pi buildParams: ImagesContext input items → chat content parts (image
   items become data: URLs)."
  [context]
  (mapv (fn [item]
          (if (= :text (:type item))
            {:type "text" :text (str (:text item))}
            {:type "image_url"
             :image_url {:url (str "data:" (:mime-type item) ";base64," (:data item))}}))
        (:input context)))

(defn parse-images-usage
  "pi parseUsage: prompt_tokens minus cache (OpenAI's prompt_tokens includes
   cached/cache_write tokens) → the normalized usage map + :cost via the
   Phase 5 calculate-cost at the model's $/M rates."
  [raw-usage model]
  (let [prompt-tokens (or (:prompt_tokens raw-usage) 0)
        reported-cached (or (get-in raw-usage [:prompt_tokens_details :cached_tokens]) 0)
        cache-write (or (get-in raw-usage [:prompt_tokens_details :cache_write_tokens]) 0)
        cache-read (if (pos? cache-write)
                     (max 0 (- reported-cached cache-write))
                     reported-cached)
        input (max 0 (- prompt-tokens cache-read cache-write))
        output (or (:completion_tokens raw-usage) 0)
        usage {:input input :output output :cache-read cache-read :cache-write cache-write}]
    (assoc usage
           :total-tokens (+ input output cache-read cache-write)
           :cost (models/calculate-cost model usage))))

(defn- generate-openrouter-images
  "The :openrouter-images wire api (pi api/openrouter-images.ts): non-stream
   POST chat/completions with modalities; the response carries text and
   data:-URL images under choices[0].message. Returns an AssistantImages
   map with :stop-reason :stop."
  [model context api-key]
  (let [{:keys [body]} (proxy/request-json
                        (str (:base-url model) "/chat/completions")
                        {:method :post
                         :headers {"Authorization" (str "Bearer " api-key)
                                   "Content-Type" "application/json"}
                         :body {"model" (:id model)
                                "messages" [{"role" "user"
                                             "content" (images-context->content context)}]
                                "stream" false
                                "modalities" (if (some #{:text} (:output model))
                                               ["image" "text"]
                                               ["image"])}
                         :timeout images-request-timeout-ms}
                        nil)
        output (atom [])
        choice (first (:choices body))]
    (when choice
      (let [message (:message choice)]
        (when (and (string? (:content message)) (seq (:content message)))
          (swap! output conj {:type :text :text (:content message)}))
        (doseq [image (:images message)]
          (let [image-url (if (string? (:image_url image))
                            (:image_url image)
                            (:url (:image_url image)))]
            (when (and (string? image-url) (str/starts-with? image-url "data:"))
              (when-let [[_ mime-type data] (re-matches #"data:([^;]+);base64,(.+)" image-url)]
                (swap! output conj {:type :image :mime-type mime-type :data data})))))))
    {:api (:api model)
     :provider (:provider model)
     :model (:id model)
     :output @output
     :response-id (:id body)
     :usage (when (:usage body) (parse-images-usage (:usage body) model))
     :stop-reason :stop
     :timestamp (System/currentTimeMillis)}))

;; ─── Generation (pi images.ts generateImages + ImagesModels.generateImages) ─

(defn generate-images
  "Generate images through the owning provider (pi ImagesModels.generateImages):
   resolve auth via kmet.app.auth, look up the wire api from the model's
   :api, generate. Never throws — failures (unknown provider, missing API
   key, unregistered api, transport/parse errors) are returned as an
   AssistantImages with :stop-reason :error (:aborted when the :signal atom
   option is set). Explicit :api-key wins over resolved auth."
  [model context & [options]]
  (let [provider-id (:provider model)]
    (try
      (let [provider (get-provider provider-id)]
        (when-not provider
          (throw (ex-info (str "Unknown provider: " provider-id)
                          {:type :images-unknown-provider})))
        (let [api-key (or (:api-key options)
                          (:api-key (auth/resolve-provider-auth provider-id)))]
          (when-not api-key
            (throw (ex-info (str "No API key for provider: " provider-id)
                            {:type :images-no-api-key})))
          (let [wire (get-images-api-provider (:api model))]
            (when-not wire
              (throw (ex-info (str "No API provider registered for api: " (:api model))
                              {:type :images-unknown-api})))
            (wire model context api-key))))
      (catch Exception e
        {:api (:api model)
         :provider (:provider model)
         :model (:id model)
         :output []
         :stop-reason (if (and (:signal options) @(:signal options)) :aborted :error)
         :error-message (ex-message e)
         :timestamp (System/currentTimeMillis)}))))

;; ─── Catalog loading (pi image-models.generated.ts, committed EDN) ─────────

(def ^:private catalog-dir
  (str (fs/path (fs/cwd) "src" "kmet" "app" "image_model_data")))

(def ^:private required-model-keys
  [:id :name :api :provider :base-url :input :output :cost])

(defn- validate-model-entry
  "Light structural validation of one catalog model map; throws on a missing
   required key (strict validation lives in the generator + offline test)."
  [id m]
  (doseq [k required-model-keys]
    (when-not (contains? m k)
      (throw (ex-info (str "Image model missing " k ": " id)
                      {:type :images-invalid-catalog}))))
  (map->ImagesModel (assoc m :id id)))

(defn load-image-catalogs!
  "Load the committed image-model catalog (image_model_data/image-models.edn)
   into the registry, replacing whatever was registered before. Also ensures
   the builtin :openrouter-images wire api is registered."
  []
  (let [path (fs/file catalog-dir "image-models.edn")
        data (when (fs/exists? path)
               (try (edn/read-string (slurp path))
                    (catch Exception _ nil)))
        provider-info (:provider data)]
    (when-not (and (map? data) (map? provider-info) (map? (:models data)))
      (throw (ex-info "Invalid image-model catalog" {:type :images-invalid-catalog})))
    (register-images-api-provider! :openrouter-images generate-openrouter-images)
    (clear-providers!)
    (register-provider!
     (map->ImagesProvider {:id (:id provider-info)
                           :name (:name provider-info)
                           :models (mapv (fn [[id m]] (validate-model-entry id m))
                                         (:models data))}))
    nil))
