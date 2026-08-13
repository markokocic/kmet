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
            [kmet.config :as cfg]
            [kmet.app.auth :as auth]
            [kmet.app.config-value :as config-value]
            [kmet.app.model-config :as model-config]
            [kmet.app.oauth :as oauth]
            [kmet.app.provider-composer :as composer]))

;; ─── Records (pi: types.ts Model / models.ts Provider) ─────────────────────

(defrecord Model [id            ;; string, e.g. "deepseek-v4-flash"
                  name          ;; string
                  provider      ;; keyword, e.g. :deepseek
                  api           ;; :openai-completions | :anthropic-messages | :google-generative-ai | :openai-responses
                  base-url      ;; string API base, e.g. "https://api.deepseek.com"
                  reasoning     ;; boolean
                  thinking-level-map ;; {level -> string|nil}, pi ThinkingLevelMap; optional
                  input         ;; [:text] | [:text :image]
                  cost          ;; {:input $/M :output $/M :cache-read $/M :cache-write $/M
                                ;;  :tiers [{:input-tokens-above N + 4 rates}] (pi ModelCost.tiers)}
                  context-window
                  max-tokens
                  sampling-params ;; optional map merged verbatim into openai-completions request bodies
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
                     api-key     ;; raw models.edn/extension api-key config value (Phase 6)
                     auth-header ;; bool: send Authorization: Bearer <key> (models.edn)
                     configured-headers ;; raw provider-level config headers (models.edn)
                     oauth       ;; OAuthAuth record (Phase 10; github-copilot)
                     ])

;; ─── Cost (pi: models.ts calculateCost, minus cacheWrite1h) ─────────────────

(defn calculate-cost
  "USD cost of a normalized usage map ({:input :output :cache-read
   :cache-write} — e.g. session/entry-usage's output (whose :input already
   excludes cache tokens, so cached tokens are never double-priced) at the
   model's $/M rates. Returns the pi-shaped breakdown map
   {:input :output :cache-read :cache-write :total}. Models with zero rates
   yield zero cost. Called at response time (llm) so each message is priced
   with the model that produced it, keeping totals correct across model
   switches.

   Cost tiers (pi ModelCost.tiers): a tier supplies a complete alternate
   rate set applied to the whole request when total input usage
   (input + cacheRead + cacheWrite) exceeds :input-tokens-above; the
   highest matching threshold wins (openai gpt-5.x long-context pricing)."
  [model usage]
  (let [{tokens-in :input tokens-out :output
         tokens-cr :cache-read tokens-cw :cache-write} usage
        total-in (+ (long (or tokens-in 0))
                    (long (or tokens-cr 0))
                    (long (or tokens-cw 0)))
        rates (or (->> (:tiers (:cost model))
                       (filter #(< (:input-tokens-above %) total-in))
                       (sort-by :input-tokens-above)
                       last)
                  (:cost model))
        {rate-in :input rate-out :output
         rate-cr :cache-read rate-cw :cache-write} rates
        f (fn [tokens rate]
            (/ (* (double (long (or tokens 0))) (double (or rate 0))) 1000000.0))
        cost-in (f tokens-in rate-in)
        cost-out (f tokens-out rate-out)
        cost-cr (f tokens-cr rate-cr)
        cost-cw (f tokens-cw rate-cw)]
    {:input cost-in :output cost-out :cache-read cost-cr :cache-write cost-cw
     :total (+ cost-in cost-out cost-cr cost-cw)}))

;; ─── Registry (pi: MutableModels) ──────────────────────────────────────────

(defonce providers-atom (atom {}))

;; Pristine catalog providers — compose-model-provider's base layer (pi:
;; builtins are never mutated; composed providers live in providers-atom).
(defonce ^:private builtins-atom (atom {}))

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

(defn- oauth-available-model-ids
  "Available model ids from the provider's stored oauth credential (pi
   filterModels: credential.availableModelIds must be a string array), nil
   when absent or malformed — the full model list passes through."
  [provider-id]
  (let [cred (get-in (auth/get-credentials) [provider-id])]
    (when (and (map? cred) (= :oauth (:type cred))
               (vector? (:available-model-ids cred))
               (every? string? (:available-model-ids cred)))
      (:available-model-ids cred))))

(defn- provider-models-with-oauth
  "A provider's models filtered to what the account can use: when a stored
   oauth credential carries :available-model-ids, only those models are
   returned (pi getAvailable → provider.filterModels — Copilot login
   shrinks the registry to the account's plans)."
  [p]
  (if-let [ids (oauth-available-model-ids (:id p))]
    (let [available (set ids)]
      (filterv #(contains? available (:id %)) (:models p)))
    (:models p)))

(defn get-available
  "Models whose provider has complete auth (pi: Models.getAvailable) —
   auth.edn credential (api-key or oauth), configured key, or env var per
   the kmet.app.auth table; oauth credentials additionally filter by the
   account's :available-model-ids. With PROV-ID, only that provider's
   available models."
  ([]
   (get-available nil))
  ([prov-id]
   (into []
         (for [p (if prov-id
                   (when-let [p (get-provider prov-id)] [p])
                   (get-providers))
               :when (and p (auth/configured? (:id p)))
               m (provider-models-with-oauth p)]
           m))))

;; ─── Catalog loading (pi: generated providers/data/*.json) ─────────────────

(def model-data-dir
  "Directory of committed provider catalog EDN files (resolved from the
   project root — the bb tasks and the app always run from there)."
  (str (fs/path (fs/cwd) "src" "kmet" "app" "model_data")))

(defn- builtin-oauth
  "The OAuthAuth record for a builtin catalog provider (pi: the provider
   factories declare auth.oauth — kmet attaches them at catalog load).
   model-ids is a thunk over the provider's loaded models so the enable-all
   login step has the catalog list (pi imports GITHUB_COPILOT_MODELS
   statically)."
  [provider]
  (case (:id provider)
    :github-copilot
    (oauth/make-github-copilot-oauth
     (fn [] (mapv :id (:models provider))))
    :openai-codex
    (oauth/make-openai-codex-oauth)
    nil))

(defn- read-edn-file
  "Parse an EDN file as data, nil when unreadable."
  [path]
  (try (edn/read-string (slurp path))
       (catch Exception _ nil)))

;; Parsed catalog blobs keyed by file path → {:mtime ms :size bytes :data}.
;; The committed catalogs are static at runtime, and tests reload them many
;; times, so a fresh EDN parse per load-catalogs! is pure waste; the cache
;; invalidates on any file change (mtime+size).
(defonce ^:private catalog-cache (atom {}))

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
  "Read + validate one catalog file (parsed result cached by mtime+size);
   nil when missing."
  [file]
  (let [path (str file)
        stat {:mtime (fs/last-modified-time file) :size (fs/size file)}
        cached (get @catalog-cache path)]
    (if (= stat (select-keys cached [:mtime :size]))
      (:data cached)
      (let [data (read-edn-file path)]
        (when data
          (validate-catalog! file data)
          (swap! catalog-cache assoc path (assoc stat :data data))
          data)))))

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
   replacing whatever was registered before. Also snapshots the pristine
   builtins (compose-model-provider's base layer — pi never mutates its
   builtins map) and installs the auth config-key source (models.edn /
   extension :api-key), so auth resolution reflects composed providers.
   Returns the providers map. Call once at startup (pi registers its
   generated providers at creation)."
  []
  (auth/set-config-key-source! (fn [provider-id] (:api-key (get-provider provider-id))))
  (auth/set-oauth-source! (fn [provider-id] (:oauth (get-provider provider-id))))
  (if (fs/exists? model-data-dir)
    (let [providers (into {}
                          (for [f (catalog-files)
                                :let [data (load-catalog-file f)]]
                            [(:id (:provider data)) (catalog->provider data)]))
          providers (into {}
                          (for [[pid p] providers]
                            [pid (assoc p :oauth (builtin-oauth p))]))]
      (reset! builtins-atom providers)
      (reset! providers-atom providers)
      providers)
    (do (reset! builtins-atom {})
        (reset! providers-atom {})
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

;; ─── Provider composition (pi: ModelRuntime rebuildProviders /           ──
;;    recomposeProvider / registerProvider / registerNativeProvider /        ──
;;    unregisterProvider)                                                    ──

(def ^:private composition-errors (atom {}))
(defonce ^:private extension-providers (atom {}))
(defonce ^:private native-extension-providers (atom {}))

(defn- recompose-provider!
  "Compose ONE provider from its layers (pi ModelRuntime.recomposeProvider):
   base = native extension provider ?? pristine builtin; extension = the
   registered extension config; config = models.edn. No layers at all → the
   provider is removed. Base with no overlays → the pristine builtin
   registers untouched (exact auth/stream behavior, pi). Composition errors
   fall back to the base (or drop) and are recorded for
   get-model-config-error."
  [provider-id]
  (let [base (or (get @native-extension-providers provider-id)
                 (get @builtins-atom provider-id))
        extension (get @extension-providers provider-id)
        config (model-config/get-provider provider-id)]
    (cond
      (and (nil? base) (nil? config) (nil? extension))
      (do (unregister-provider! provider-id)
          (swap! composition-errors dissoc provider-id))

      (and base (nil? config) (nil? extension))
      (do (register-provider! base)
          (swap! composition-errors dissoc provider-id))

      :else
      (try
        (register-provider!
         (-> (composer/compose-model-provider provider-id base config extension)
             (update :models #(mapv map->Model %))
             map->Provider))
        (swap! composition-errors dissoc provider-id)
        (catch Exception e
          (swap! composition-errors assoc provider-id (ex-message e))
          (when base (register-provider! base)))))))

(defn- merge-defined
  "pi: re-registration merges defined values over the previous registration
   and preserves undefined (nil) ones."
  [previous config]
  (merge previous (into {} (remove (comp nil? val)) config)))

(defn- ensure-auth-hook!
  "Install the auth config-key source if missing (load-catalogs! sets it;
   extensions may register providers without going through a catalog load)."
  []
  (when-not (auth/config-key-source-installed?)
    (auth/set-config-key-source! (fn [provider-id] (:api-key (get-provider provider-id))))))

(defn register-provider-config!
  "Register/replace an extension provider by config map (pi
   ModelRuntime.registerProvider + ctx.registerProvider(name, config)):
   validates the incoming registration eagerly (a broken config throws
   without touching stored state), merges it over any previous registration
   preserving unset fields, then recomposes the provider (builtin + models.edn
   + extension layers). Returns the effective config."
  [provider-id config]
  (let [provider-id (keyword provider-id)
        base (or (get @native-extension-providers provider-id)
                 (get @builtins-atom provider-id))
        models-config (model-config/get-provider provider-id)
        effective (merge-defined (get @extension-providers provider-id) config)]
    (ensure-auth-hook!)
    (composer/validate-extension-provider provider-id base models-config config)
    (swap! extension-providers assoc provider-id effective)
    (swap! native-extension-providers dissoc provider-id)
    (recompose-provider! provider-id)
    effective))

(defn unregister-provider-config!
  "Remove an extension provider registration (pi ModelRuntime.unregisterProvider
   + ctx.unregisterProvider): the provider falls back to its builtin (or
   disappears when it had none), keeping the models.edn layer."
  [provider-id]
  (let [provider-id (keyword provider-id)]
    (swap! extension-providers dissoc provider-id)
    (swap! native-extension-providers dissoc provider-id)
    (recompose-provider! provider-id)
    nil))

(defn register-native-provider!
  "Register a complete Provider record from an extension (pi
   ModelRuntime.registerNativeProvider + ctx.registerProvider(provider)):
   clears any config registration for the id, stores the provider as the
   base, recomposes. Throws on an empty provider id."
  [provider]
  (when (str/blank? (str (:id provider)))
    (throw (ex-info "Provider id must not be empty." {:type :model-config-invalid})))
  (ensure-auth-hook!)
  (swap! extension-providers dissoc (:id provider))
  (swap! native-extension-providers assoc (:id provider) provider)
  (recompose-provider! (:id provider))
  provider)

(defn clear-extension-providers!
  "Remove all extension provider registrations (native + config) and
   recompose back to builtins + models.edn. Exported for tests."
  []
  (reset! extension-providers {})
  (reset! native-extension-providers {})
  (doseq [pid (keys @providers-atom)]
    (recompose-provider! pid))
  nil)

(defn get-registered-provider-config
  "The registered extension config for a provider (pi
   ModelRegistry.getRegisteredProviderConfig), or nil."
  [provider-id]
  (get @extension-providers (keyword provider-id)))

(defn get-registered-native-provider
  "The registered native extension Provider for a provider (pi
   ModelRegistry.getRegisteredNativeProvider), or nil."
  [provider-id]
  (get @native-extension-providers (keyword provider-id)))

(defn get-registered-provider-ids
  "Provider ids with an extension registration (config or native, pi
   ModelRegistry.getRegisteredProviderIds)."
  []
  (vec (into (sorted-set)
             (concat (keys @extension-providers) (keys @native-extension-providers)))))

;; ─── Extension facade (pi ModelRegistry) ───────────────────────────────────

(defn has-configured-auth
  "True when a model's (or provider id's) provider has complete auth (pi
   ModelRegistry.hasConfiguredAuth)."
  [model-or-provider]
  (auth/configured? (if (map? model-or-provider)
                      (:provider model-or-provider)
                      model-or-provider)))

(defn get-provider-auth-status
  "Auth status for a provider (pi ModelRegistry.getProviderAuthStatus):
   {:configured bool :source kw} — a stored oauth credential, then the
   auth.edn api-key, then the models.edn/extension api-key config
   (configuredRequestAuthStatus — a configured-but-unresolvable key reports
   {:configured false}, blocking the env fallback like resolve), then any
   other configured auth (env vars, native provider keys — pi's snapshot
   auth check). :source ∈ :oauth | :stored | :models-json-key |
   :models-json-command | :environment | :fallback."
  [provider-id]
  (let [stored (get-in (auth/get-credentials) [provider-id])
        provider (get-provider provider-id)
        configured (when provider
                     (composer/configured-request-auth-status
                      (model-config/get-provider provider-id)
                      (get @extension-providers provider-id)))]
    (cond
      ;; a stored oauth credential needs a registered OAuthAuth, like
      ;; auth/configured? (pi checkAuth); without one it is not configured
      (and (map? stored) (= :oauth (:type stored)))
      (if (some? (:oauth provider))
        {:configured true :source :oauth}
        {:configured false})
      stored {:configured true :source :stored}
      configured configured
      (auth/configured? provider-id) {:configured true :source :environment}
      :else {:configured false})))

(defn get-api-key-and-headers
  "Resolved request auth for a model (pi ModelRegistry.getApiKeyAndHeaders):
   {:ok true :api-key str? :headers map?} — the resolved key (or the
   Authorization bearer for an anthropic AUTH_TOKEN resolution) plus the
   model/provider configured headers resolved as config values — or
   {:ok false :error str}: unknown provider, no key when the provider
   requires one (:auth-header), or an unresolvable configured header (pi:
   getAuth throws → ok:false with the message)."
  [model]
  (let [provider-id (:provider model)
        provider (get-provider provider-id)]
    (if (nil? provider)
      {:ok false :error (str "Unknown provider: " (name (or provider-id :unknown)))}
      (try
        (let [auth (auth/resolve-provider-auth provider-id)
              api-key (:api-key auth)
              bearer (:bearer auth)
              headers (config-value/resolve-headers-or-throw
                       (merge (:headers model) (:configured-headers provider))
                       (str "model \"" (name provider-id) "/" (:id model) "\""))
              headers (if bearer
                        (assoc headers "Authorization" (str "Bearer " bearer))
                        headers)]
          (if (and (nil? api-key) (nil? bearer) (:auth-header provider))
            {:ok false :error (str "No API key found for \"" (name provider-id) "\"")}
            {:ok true :api-key api-key :headers headers}))
        (catch Exception e
          {:ok false :error (ex-message e)})))))

(defn load-models-config!
  "Load models.edn and recompose every provider (pi ModelRuntime.refresh →
   rebuildProviders): builtin ∪ models.edn ∪ extension provider ids, with
   the models.edn and extension layers applied. Restores the pristine
   builtin catalogs first so repeated calls (startup, /reload) never stack
   the layers onto already-composed providers (pi: composed providers live
   in the runtime collection, the builtins map is never mutated — removed
   providers/models disappear on reload). A models.edn parse/schema failure
   keeps the built-ins and is surfaced via get-model-config-error;
   per-provider composition errors fall back to the built-in for that
   provider (a config-only provider that fails to compose is dropped).
   Registers the auth config-key source (models.edn/extension :api-key), so
   auth resolution and availability reflect configured keys."
  []
  (load-catalogs!)
  (model-config/load-config!)
  (clear-providers!)
  (reset! composition-errors {})
  (let [ids (into (sorted-set)
                  (concat (keys @builtins-atom)
                          (model-config/get-provider-ids)
                          (keys @extension-providers)
                          (keys @native-extension-providers)))]
    (doseq [pid ids]
      (recompose-provider! pid)))
  @providers-atom)

(defn get-model-config-error
  "models.edn load + composition error string (pi ModelRuntime.getError):
   the config parse/schema error plus each per-provider composition failure
   ('Provider \"X\": <error>'). nil when clean."
  []
  (let [config-error (model-config/get-error)
        comp-errors (->> @composition-errors
                         (map (fn [[pid err]]
                                (str "Provider \"" (name pid) "\": " err)))
                         sort
                         vec)]
    (when (or config-error (seq comp-errors))
      (str (when config-error config-error)
           (when (and config-error (seq comp-errors)) "\n\n")
           (str/join "\n\n" comp-errors)))))

;; ─── Config interop ────────────────────────────────────────────────────────

(defn resolve-config-model
  "Model id for a config: the :model setting, else the config provider's
   default model from the registry (pi: resolveModelConfig — settings.edn
   semantics unchanged, provider defaults now come from the catalog instead
   of default-config's :providers map)."
  [config]
  (or (:model config)
      (:id (default-model-for (cfg/get-provider config)))))
