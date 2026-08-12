(ns kmet.app.model-config
  "models.edn loading + schema validation (pi: model-config.ts ModelConfig).

   models.edn is the user-config layer over the provider registry:
   ~/.kmet/agent/models.edn (global) + .kmet/models.edn (project overrides,
   merged like settings.edn — kmet deviation from pi's single global
   models.json). One immutable load per startup/reload; a parse or schema
   failure yields an error string and the registry keeps the built-ins.

   Validation is a manual walker (no TypeBox) producing path-style messages
   ('providers.my-provider.models[0].cost: expected number'), collected
   rather than thrown. Unknown keys pass (pi's TypeBox schemas allow
   additional properties)."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [babashka.fs :as fs]
            [kmet.config :as cfg]))

(defonce config-atom (atom {:providers {} :error nil}))

;; ─── Schema validation (pi validateModelsConfig, manual walker) ────────────

(def ^:private thinking-levels
  #{:off :minimal :low :medium :high :xhigh :max})

(def ^:private cost-rate-keys
  [:input :output :cache-read :cache-write])

(defn- valid-string?
  "Non-empty string (pi Type.String({ minLength: 1 }))."
  [x]
  (and (string? x) (pos? (count x))))

(defn- api-value?
  "Accept a keyword or non-empty string; normalized to keywords later."
  [x]
  (or (keyword? x) (valid-string? x)))

(defn- check
  "Conjoin a PATH: MESSAGE validation error onto ERRORS when TEST fails."
  [errors test path message]
  (if test errors (conj errors (str path ": " message))))

(defn- when-present
  "Conjoin a PATH: MESSAGE error when KEY is present in M and fails TEST."
  [errors m key path test message]
  (if (and (contains? m key) (not (test (get m key))))
    (conj errors (str path ": " message))
    errors))

(defn- validate-string-map
  "Every value a string (pi Record(Type.String(), Type.String()) — header
   values have no minLength)."
  [errors path m]
  (reduce (fn [errors [k v]]
            (check errors (string? v) (str path "." (name k)) "expected string"))
          errors m))

(defn- validate-provider-compat
  "Flat kmet compat map: known boolean keys type-checked, :thinking-format /
   :max-tokens-field keyword-or-string; unknown keys pass (pi TypeBox
   allows additional properties)."
  [errors path compat]
  (reduce (fn [errors [k v]]
            (let [p (str path "." (name k))]
              (cond
                (contains? #{:supports-reasoning-effort :supports-store
                             :supports-developer-role
                             :requires-reasoning-content-on-assistant-messages
                             :supports-usage-in-streaming :supports-finish-reason
                             :requires-tool-result-name
                             :requires-assistant-after-tool-result
                             :requires-thinking-as-text :supports-strict-mode} k)
                (check errors (boolean? v) p "expected boolean")

                (contains? #{:thinking-format :max-tokens-field} k)
                (check errors (api-value? v) p "expected keyword or string")

                :else errors)))
          errors compat))

(defn- validate-thinking-level-map
  "Map of thinking levels to string-or-nil (pi ThinkingLevelMapSchema)."
  [errors path m]
  (reduce (fn [errors [k v]]
            (let [p (str path "." (name k))]
              (cond-> errors
                (not (contains? thinking-levels k))
                (conj (str p ": unknown thinking level"))
                (and (some? v) (not (string? v)))
                (conj (str p ": expected string or nil")))))
          errors m))

(defn- validate-input
  "Vector of :text / :image (pi Array(Union(Literal('text'), Literal('image'))))."
  [errors path input]
  (reduce (fn [errors x]
            (check errors (contains? #{:text :image} x) path "expected :text or :image"))
          errors input))

(defn- validate-cost-rates
  "All four rate fields required numbers (pi ModelCostSchema)."
  [errors path cost]
  (reduce (fn [errors k]
            (check errors (number? (get cost k)) (str path "." (name k)) "expected number"))
          errors cost-rate-keys))

(defn- validate-optional-cost-rates
  "Override cost: present fields must be numbers (pi ModelOverrideSchema —
   partial)."
  [errors path cost]
  (reduce (fn [errors [k v]]
            (check errors (number? v) (str path "." (name k)) "expected number"))
          errors cost))

(defn- validate-model-definition
  "models.edn model definition (pi ModelDefinitionSchema): id required;
   optional fields type-checked when present."
  [errors path m]
  (let [errors (-> errors
                   (check (valid-string? (:id m)) (str path ".id") "required non-empty string")
                   (when-present m :name (str path ".name") valid-string? "expected non-empty string")
                   (when-present m :api (str path ".api") api-value? "expected keyword or string")
                   (when-present m :base-url (str path ".base-url") valid-string? "expected non-empty string")
                   (when-present m :reasoning (str path ".reasoning") boolean? "expected boolean")
                   (when-present m :thinking-level-map (str path ".thinking-level-map") map? "expected map")
                   (when-present m :input (str path ".input") vector? "expected array")
                   (when-present m :cost (str path ".cost") map? "expected map")
                   (when-present m :context-window (str path ".context-window") number? "expected number")
                   (when-present m :max-tokens (str path ".max-tokens") number? "expected number")
                   (when-present m :sampling-params (str path ".sampling-params") map? "expected map")
                   (when-present m :headers (str path ".headers") map? "expected map")
                   (when-present m :compat (str path ".compat") map? "expected map"))]
    (cond-> errors
      (map? (:thinking-level-map m)) (validate-thinking-level-map (str path ".thinking-level-map") (:thinking-level-map m))
      (vector? (:input m)) (validate-input (str path ".input") (:input m))
      (map? (:cost m)) (validate-cost-rates (str path ".cost") (:cost m))
      (map? (:headers m)) (validate-string-map (str path ".headers") (:headers m))
      (map? (:compat m)) (validate-provider-compat (str path ".compat") (:compat m)))))

(defn- validate-model-override
  "Model override (pi ModelOverrideSchema): same as a definition minus
   id/api/base-url; cost rates are individually optional."
  [errors path m]
  (let [errors (-> errors
                   (when-present m :name (str path ".name") valid-string? "expected non-empty string")
                   (when-present m :reasoning (str path ".reasoning") boolean? "expected boolean")
                   (when-present m :thinking-level-map (str path ".thinking-level-map") map? "expected map")
                   (when-present m :input (str path ".input") vector? "expected array")
                   (when-present m :cost (str path ".cost") map? "expected map")
                   (when-present m :context-window (str path ".context-window") number? "expected number")
                   (when-present m :max-tokens (str path ".max-tokens") number? "expected number")
                   (when-present m :sampling-params (str path ".sampling-params") map? "expected map")
                   (when-present m :headers (str path ".headers") map? "expected map")
                   (when-present m :compat (str path ".compat") map? "expected map"))]
    (cond-> errors
      (map? (:thinking-level-map m)) (validate-thinking-level-map (str path ".thinking-level-map") (:thinking-level-map m))
      (vector? (:input m)) (validate-input (str path ".input") (:input m))
      (map? (:cost m)) (validate-optional-cost-rates (str path ".cost") (:cost m))
      (map? (:headers m)) (validate-string-map (str path ".headers") (:headers m))
      (map? (:compat m)) (validate-provider-compat (str path ".compat") (:compat m)))))

(defn- validate-models
  "Validate a provider's :models vector (pi Array(ModelDefinitionSchema)).
   PATH is the provider path; entries are reported as PATH.models[i]."
  [errors path models]
  (reduce (fn [errors [i dm]]
            (if-not (map? dm)
              (conj errors (str path ".models[" i "]: expected map"))
              (validate-model-definition errors (str path ".models[" i "]") dm)))
          errors (map-indexed vector models)))

(defn- validate-model-overrides
  "Validate a provider's :model-overrides map (pi Record(ModelOverrideSchema)).
   PATH is the provider path; entries are reported as PATH.model-overrides.<id>."
  [errors path overrides]
  (reduce (fn [errors [mid override]]
            (if-not (map? override)
              (conj errors (str path ".model-overrides." (name mid) ": expected map"))
              (validate-model-override errors (str path ".model-overrides." (name mid)) override)))
          errors overrides))

(defn- validate-provider
  "Provider config (pi ProviderConfigSchema)."
  [errors path m]
  (let [errors (-> errors
                   (when-present m :name (str path ".name") valid-string? "expected non-empty string")
                   (when-present m :base-url (str path ".base-url") valid-string? "expected non-empty string")
                   (when-present m :api-key (str path ".api-key") valid-string? "expected non-empty string")
                   (when-present m :api (str path ".api") api-value? "expected keyword or string")
                   (when-present m :oauth (str path ".oauth") #(or (= "radius" %) (= :radius %))
                                 "expected \"radius\"")
                   (when-present m :headers (str path ".headers") map? "expected map")
                   (when-present m :compat (str path ".compat") map? "expected map")
                   (when-present m :auth-header (str path ".auth-header") boolean? "expected boolean")
                   (when-present m :models (str path ".models") vector? "expected array")
                   (when-present m :model-overrides (str path ".model-overrides") map? "expected map"))]
    (cond-> errors
      (map? (:headers m)) (validate-string-map (str path ".headers") (:headers m))
      (map? (:compat m)) (validate-provider-compat (str path ".compat") (:compat m))
      (map? (:model-overrides m))
      (validate-model-overrides path (:model-overrides m))
      (vector? (:models m))
      (validate-models path (:models m)))))

(defn validate-config
  "Manual schema validation of a models.edn map (pi validateModelsConfig).
   Returns a vector of path-style error strings
   (\"providers.my-provider.models[0].cost: expected number\"); empty when
   valid."
  [config]
  (if-not (map? config)
    ["root: expected object"]
    (let [providers (:providers config)]
      (if-not (map? providers)
        ["providers: expected object"]
        (reduce (fn [errors [pid m]]
                  (if-not (map? m)
                    (conj errors (str "providers." (name pid) ": expected map"))
                    (validate-provider errors (str "providers." (name pid)) m)))
                [] providers)))))

;; ─── File loading (pi ModelConfig.load) ────────────────────────────────────

(defn models-edn-paths
  "Global then project models.edn paths (project overrides global, like
   settings.edn)."
  []
  [(str (fs/path (System/getProperty "user.home") ".kmet" "agent" "models.edn"))
   (str (fs/path (fs/cwd) ".kmet" "models.edn"))])

(defn- load-config-file
  "Read + parse one models.edn file. Returns {:data map} (missing file →
   {:data nil}) or {:error str} on parse failure or a non-map root."
  [path]
  (if-not (fs/exists? path)
    {:data nil}
    (if-let [content (try (slurp path) (catch Exception _ nil))]
      (try
        (let [parsed (edn/read-string content)]
          (if (map? parsed)
            {:data parsed}
            {:error (str "Invalid models.edn schema:\n  - root: expected object\n\nFile: " path)}))
        (catch Exception e
          {:error (str "Failed to parse models.edn: " (ex-message e) "\n\nFile: " path)}))
      {:error (str "Failed to load models.edn\n\nFile: " path)})))

(defn- normalize-provider-ids
  "models.edn provider keys may be keywords or strings (pi models.json uses
   string ids); kmet's registry is keyword-keyed, so ids normalize to
   keywords (a string key would otherwise compose a provider that
   (get-provider :kw) can never find)."
  [providers]
  (into {} (map (fn [[k v]] [(keyword k) v])) providers))

(defn load-config
  "One immutable load of models.edn (global + project merged, project wins —
   pi loads only the global file). Returns {:providers {provider-id config}
   :error str-or-nil}; a parse or schema failure yields :error with empty
   :providers (the registry keeps the built-ins)."
  []
  (let [results (mapv load-config-file (models-edn-paths))
        errors (keep :error results)]
    (if (seq errors)
      {:providers {} :error (str/join "\n\n" errors)}
      (if (not-any? (fn [{:keys [data error]}] (or data error)) results)
        {:providers {} :error nil}
        (let [config (reduce (fn [acc {:keys [data]}] (cfg/deep-merge acc data)) {} results)]
          (if-let [errors (seq (validate-config config))]
            {:providers {}
             :error (str "Invalid models.edn schema:\n"
                         (str/join "\n" (map #(str "  - " %) errors))
                         "\n\nFile: " (str/join ", " (models-edn-paths)))}
            {:providers (normalize-provider-ids (or (:providers config) {}))
             :error nil}))))))

(defn load-config!
  "Load models.edn into the config atom; returns the config map. Called at
   startup (models/load-models-config!) and from /reload."
  []
  (let [config (load-config)]
    (reset! config-atom config)
    config))

(defn get-providers
  "The loaded models.edn providers map (empty when none or on load error)."
  []
  (:providers @config-atom))

(defn get-provider
  "models.edn provider config for PROVIDER-ID, or nil."
  [provider-id]
  (get (:providers @config-atom) provider-id))

(defn get-provider-ids
  "Provider ids defined in models.edn."
  []
  (keys (:providers @config-atom)))

(defn get-error
  "models.edn load error string (parse/schema), nil when clean."
  []
  (:error @config-atom))
