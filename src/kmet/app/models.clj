(ns kmet.app.models
  "Provider/model registry (pi: packages/ai types.ts + models.ts Models
   collection, adapted to EDN).

   The Model record is the unit of truth: all request shaping (URL, api
   dispatch, thinking, max tokens, cost) derives from the resolved Model,
   never from ad-hoc provider switches. Providers are data — an EDN blob
   registered in an atom — which is what later makes extension
   registerProvider trivial (pi: MutableModels.setProvider)."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [babashka.fs :as fs]
            [kmet.config :as cfg]))

;; ─── Records (pi: types.ts Model / models.ts Provider) ─────────────────────

(defrecord Model [id            ;; string, e.g. "deepseek-v4-flash"
                  name          ;; string
                  provider      ;; keyword, e.g. :deepseek
                  api           ;; :openai-completions | :anthropic-messages | :google-generative-ai
                  base-url      ;; string API base, e.g. "https://api.deepseek.com"
                  reasoning     ;; boolean
                  thinking-level-map ;; {level -> string|nil}, pi ThinkingLevelMap; optional
                  input         ;; [:text] | [:text :image]
                  cost          ;; {:input $/M :output $/M :cache-read $/M :cache-write $/M}
                  context-window
                  max-tokens
                  headers       ;; optional static headers map (copilot)
                  compat        ;; map, api-specific; see models.md
                  ])

(defrecord Provider [id          ;; keyword
                     name        ;; string
                     api-types   ;; set of apis used by its models
                     models      ;; vector of Model (loaded from catalog)
                     env-vars    ;; [env-var-name ...], pi env-api-keys order
                     default-model ;; string id
                     base-url    ;; optional provider-level fallback
                     headers     ;; optional static headers (copilot)
                     ])

;; ─── Registry (pi: MutableModels) ──────────────────────────────────────────

(defonce providers-atom (atom {}))

(defn register-provider!
  "Upsert a Provider by :id (pi: MutableModels.setProvider — provider ids are
   unique; registering again replaces the existing entry)."
  [provider]
  (swap! providers-atom assoc (:id provider) provider)
  provider)

(defn unregister-provider!
  "Remove a provider by :id (pi: MutableModels.deleteProvider)."
  [id]
  (swap! providers-atom dissoc id))

(defn clear-providers!
  "Remove all providers (pi: MutableModels.clearProviders)."
  []
  (reset! providers-atom {}))

(defn get-providers
  "All registered providers, in registration order."
  []
  (vec (vals @providers-atom)))

(defn get-provider
  "Provider record by keyword id, or nil when unknown."
  [id]
  (get @providers-atom id))

(defn get-models
  "All models of one provider (pi: Models.getModels), or of every provider
   when PROV-ID is omitted."
  ([]
   (into [] (mapcat :models) (get-providers)))
  ([prov-id]
   (or (:models (get-provider prov-id)) [])))

(defn get-model
  "Model record for PROV-ID + model id, or nil (pi: Models.getModel)."
  [prov-id model-id]
  (first (filter #(= model-id (:id %)) (get-models prov-id))))

(defn default-model-for
  "Default Model record for a provider: its :default-model id, or the first
   catalog model when unset (pi: defaultModelPerProvider)."
  [prov-id]
  (let [p (get-provider prov-id)]
    (or (get-model prov-id (:default-model p))
        (first (:models p)))))

(defn get-available
  "Models whose provider has complete auth (pi: Models.getAvailable).
   Phase 0 uses cfg/get-api-key (auth.edn → env vars); Phase 3 rewires the
   check to the kmet.app.auth table. With PROV-ID, only that provider's
   available models."
  ([]
   (get-available nil))
  ([prov-id]
   (into []
         (for [p (if prov-id
                   (when-let [p (get-provider prov-id)] [p])
                   (get-providers))
               :when (and p (cfg/get-api-key (:id p)))
               m (:models p)]
           m))))

;; ─── Catalog loading (pi: generated providers/data/*.json) ─────────────────

(def model-data-dir
  "Directory of committed provider catalog EDN files (resolved from the
   project root — the bb tasks and the app always run from there)."
  (str (fs/path (fs/cwd) "src" "kmet" "app" "model_data")))

(defn- read-edn-file
  "Parse an EDN file as data, nil when unreadable."
  [path]
  (try (edn/read-string (slurp path))
       (catch Exception _ nil)))

(def ^:private required-model-keys
  [:id :name :provider :api :base-url :reasoning :input :cost
   :context-window :max-tokens])

(defn- validate-catalog!
  "Structural sanity of a catalog blob: :provider info block, non-empty
   :models groups, required Model fields, no model id in two api groups.
   Throws ex-info naming the file (strict per-model validation is Phase 1's
   generator job)."
  [file data]
  (when-not (map? data)
    (throw (ex-info (str "Catalog " file " is not a map") {:type :catalog-invalid :file file})))
  (let [prov (:provider data)
        groups (:models data)]
    (when-not (and (map? prov) (:id prov))
      (throw (ex-info (str "Catalog " file " has no :provider info block")
                      {:type :catalog-invalid :file file})))
    (when-not (and (map? groups) (seq groups))
      (throw (ex-info (str "Catalog " file " has no :models groups")
                      {:type :catalog-invalid :file file})))
    (doseq [[api models] groups
            [mid mm] models]
      (when-not (and (map? mm)
                     (every? #(contains? mm %) required-model-keys))
        (throw (ex-info (str "Catalog " file " model " mid " is missing required fields")
                        {:type :catalog-invalid :file file :model mid})))
      (when (not= (name api) (name (:api mm)))
        (throw (ex-info (str "Catalog " file " model " mid " is in the " api
                             " group but declares :api " (:api mm))
                        {:type :catalog-invalid :file file :model mid}))))
    (doseq [[api models] groups]
      (when-let [dup (first (filter #(< 1 (count %))
                                    (vals (group-by key models))))]
        (throw (ex-info (str "Catalog " file " has duplicate model id in group " api)
                        {:type :catalog-invalid :file file :model (ffirst dup)}))))
    (let [all-ids (mapcat keys (vals groups))]
      (when-let [dup (first (filter #(< 1 (count %)) (vals (group-by identity all-ids))))]
        (throw (ex-info (str "Catalog " file " has model " (first dup) " in more than one api group")
                        {:type :catalog-invalid :file file :model (first dup)}))))))

(defn- catalog-files
  "Catalog EDN paths (excludes manifest.edn), sorted by filename."
  []
  (->> (fs/list-dir model-data-dir)
       (filter fs/regular-file?)
       (map fs/file)
       (filter #(str/ends-with? (str %) ".edn"))
       (remove #(= "manifest.edn" (fs/file-name %)))
       (sort-by fs/file-name)))

(defn- load-catalog-file
  "Read + validate one catalog file; nil when missing."
  [file]
  (let [data (read-edn-file file)]
    (when data
      (validate-catalog! file data)
      data)))

(defn- catalog->provider
  "Build a Provider record from a catalog blob: provider info from the
   :provider block, Model records from the :models api groups."
  [{:keys [provider models]}]
  (let [model-records (into []
                            (for [[_api group] models
                                  [_id mm] group]
                              (map->Model mm)))
        api-types (set (keys models))]
    (map->Provider (assoc provider
                          :api-types api-types
                          :models model-records))))

(defn load-catalogs!
  "Load all committed provider catalogs from model_data/ into the registry,
   replacing whatever was registered before. Returns the providers map.
   Call once at startup (pi registers its generated providers at creation)."
  []
  (if (fs/exists? model-data-dir)
    (let [providers (into {}
                          (for [f (catalog-files)
                                :let [data (load-catalog-file f)]]
                            [(:id (:provider data)) (catalog->provider data)]))]
      (reset! providers-atom providers)
      providers)
    (do (reset! providers-atom {})
        {})))

;; ─── Manifest (pi: scripts/model-data.ts createModelDataManifest) ─────────

(defn- sha256-hex
  "sha256 hex digest of a string (pi: createHash('sha256')) — MessageDigest
   is the portable babashka way."
  [s]
  (let [md (java.security.MessageDigest/getInstance "SHA-256")]
    (format "%064x" (BigInteger. 1 (.digest md (.getBytes s "UTF-8"))))))

(defn- catalog-structure
  "Canonical sorted {provider -> sorted {model-id -> api}} over the catalog
   files (pi: ModelDataStructure). Used for the structure hash."
  []
  (into (sorted-map)
        (for [f (catalog-files)
              :let [data (load-catalog-file f)
                    pid (name (get-in data [:provider :id]))]]
          [pid (into (sorted-map)
                     (for [[api models] (:models data)
                           [mid _] models]
                       [(name mid) (name api)]))])))

(defn- structure-hash
  "sha256 of the canonical printed structure (pi: modelDataStructureHash over
   JSON.stringify — pr-str of the sorted map is the EDN equivalent)."
  []
  (sha256-hex (pr-str (catalog-structure))))

(defn compute-manifest
  "The manifest that the committed catalog files should be covered by
   (pi: createModelDataManifest): :structure-hash over the canonical sorted
   {provider -> {model-id -> api}} plus a sha256 per catalog file.
   :generated-at is metadata, not part of the identity."
  []
  {:schema-version 1
   :generated-at nil
   :structure-hash (structure-hash)
   :files (into (sorted-map)
                (for [f (catalog-files)]
                  [(fs/file-name f) (sha256-hex (slurp f))]))})

(defn manifest-matches?
  "True when the committed manifest.edn covers the current catalog files
   (structure hash + file hashes match; pi's manifest check catches
   uncommitted regenerations)."
  []
  (let [committed (read-edn-file (str (fs/path model-data-dir "manifest.edn")))
        current (compute-manifest)]
    (and (map? committed)
         (= (:structure-hash committed) (:structure-hash current))
         (= (:files committed) (:files current)))))

;; ─── Config interop ────────────────────────────────────────────────────────

(defn resolve-config-model
  "Model id for a config: the :model setting, else the config provider's
   default model from the registry (pi: resolveModelConfig — settings.edn
   semantics unchanged, provider defaults now come from the catalog instead
   of default-config's :providers map)."
  [config]
  (or (:model config)
      (:id (default-model-for (cfg/get-provider config)))))
