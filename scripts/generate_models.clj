;; scripts/generate_models.clj — regenerate src/kmet/app/model_data/*.edn from
;; models.dev (pi: packages/ai/scripts/generate-models.ts, ported to the 4
;; providers kmet ships: opencode-go, opencode, deepseek, github-copilot).
;;
;; Wire APIs out of kmet's scope (openai-responses etc.) are skipped and
;; logged. The generated EDN + manifest.edn are committed; the offline
;; half (validate-committed!) also runs as test/kmet/app/test_model_data.clj
;; so drift is caught without network.
;;
;; Run via: bb generate-models   (network)
;; Check via: bb check-model-data (offline)

(ns generate-models
  (:require [babashka.fs :as fs]
            [babashka.http-client :as http]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]))

;; ─── Provider metadata (kmet decisions, not derivable from models.dev) ─────

(def providers
  "Target providers, in catalog file order: id → info block (name, env vars
   in pi env-api-keys order, catalog default model)."
  (array-map
   :opencode-go {:name "OpenCode Go"
                 :env-vars ["OPENCODE_API_KEY"]
                 :default-model "deepseek-v4-flash"}
   :opencode {:name "OpenCode (Zen)"
              :env-vars ["OPENCODE_API_KEY"]
              :default-model "kimi-k2.6"}
   :deepseek {:name "DeepSeek"
              :env-vars ["DEEPSEEK_API_KEY"]
              :default-model "deepseek-v4-pro"}
   :github-copilot {:name "GitHub Copilot"
                    :env-vars ["COPILOT_GITHUB_TOKEN"]
                    :default-model "claude-sonnet-4.5"}))

(def opencode-variants
  "pi opencodeVariants: models.dev key → kmet provider id + API base path."
  [{:provider :opencode :key "opencode" :base-path "https://opencode.ai/zen"}
   {:provider :opencode-go :key "opencode-go" :base-path "https://opencode.ai/zen/go"}])

(def copilot-static-headers
  "pi COPILOT_STATIC_HEADERS."
  (array-map "User-Agent" "GitHubCopilotChat/0.35.0"
             "Editor-Version" "vscode/1.107.0"
             "Editor-Plugin-Version" "copilot-chat/0.35.0"
             "Copilot-Integration-Id" "vscode-chat"))

(def deepseek-compat
  {:requires-reasoning-content-on-assistant-messages true
   :thinking-format :deepseek})

(def deepseek-v4-models
  "Hardcoded V4 pair — models.dev lags the DeepSeek API, pi carries them as
   deepseekV4Models (cost + 1M context). Ported verbatim; deepseek is the one
   provider pi does not process from models.dev."
  [(array-map :id "deepseek-v4-flash" :name "DeepSeek V4 Flash"
              :provider :deepseek :api :openai-completions
              :base-url "https://api.deepseek.com"
              :reasoning true :input [:text]
              :cost (array-map :input 0.14 :output 0.28 :cache-read 0.0028 :cache-write 0)
              :context-window 1000000 :max-tokens 384000
              :compat deepseek-compat)
   (array-map :id "deepseek-v4-pro" :name "DeepSeek V4 Pro"
              :provider :deepseek :api :openai-completions
              :base-url "https://api.deepseek.com"
              :reasoning true :input [:text]
              :cost (array-map :input 0.435 :output 0.87 :cache-read 0.003625 :cache-write 0)
              :context-window 1000000 :max-tokens 384000
              :compat deepseek-compat)])

(def data-dir
  "Committed catalog directory, relative to the project root (bb tasks run
   from there, matching kmet.app.models/model-data-dir)."
  "src/kmet/app/model_data")

;; ─── Small helpers ─────────────────────────────────────────────────────────

(defn- sha256-hex
  "sha256 hex digest (pi createHash('sha256'))."
  [s]
  (let [md (java.security.MessageDigest/getInstance "SHA-256")]
    (format "%064x" (BigInteger. 1 (.digest md (.getBytes s "UTF-8"))))))

(defn- generated-at
  "UTC timestamp, second precision (ISO-8601)."
  []
  (str (subs (str (java.time.Instant/now)) 0 19) "Z"))

(defn- input-modalities
  "pi: modalities.input includes image ? [text image] : [text] (kmet ignores
   pdf/audio/video modalities)."
  [m]
  (if (some #{"image"} (get-in m ["modalities" "input"]))
    [:text :image]
    [:text]))

(defn- cost-map
  "pi getModelsDevCost, flat 4-field form (kmet has no cost tiers)."
  [m]
  (array-map :input (or (get-in m ["cost" "input"]) 0)
             :output (or (get-in m ["cost" "output"]) 0)
             :cache-read (or (get-in m ["cost" "cache_read"]) 0)
             :cache-write (or (get-in m ["cost" "cache_write"]) 0)))

(defn- model-map
  "One Model in canonical EDN field order (pi Model shape, kmet key names).
   OPT: :compat (ordered), :headers (copilot static headers)."
  [provider api base-url m mid {:keys [compat headers context-default max-default]}]
  (cond-> (array-map
           :id mid
           :name (or (get m "name") mid)
           :provider provider
           :api api
           :base-url base-url
           :reasoning (true? (get m "reasoning"))
           :input (input-modalities m)
           :cost (cost-map m)
           :context-window (or (get-in m ["limit" "context"]) context-default 4096)
           :max-tokens (or (get-in m ["limit" "output"]) max-default 4096))
    (seq compat) (assoc :compat compat)
    headers (assoc :headers headers)))

;; ─── opencode / opencode-go (pi opencodeVariants) ─────────────────────────

(defn- opencode-model
  "One models.dev opencode variant model, or nil when it needs a wire API
   kmet does not implement yet (@ai-sdk/openai → openai-responses; skipped
   and logged by the caller)."
  [variant mid m]
  (let [npm (get-in m ["provider" "npm"])
        base-path (:base-path variant)
        api (cond
              (= npm "@ai-sdk/openai") nil
              (= npm "@ai-sdk/anthropic") :anthropic-messages
              (= npm "@ai-sdk/google") :google-generative-ai
              :else :openai-completions)
        base (cond
               (nil? api) nil
               (= api :anthropic-messages) base-path
               :else (str base-path "/v1"))
        ;; Known npm mismatches: models.dev reports these as @ai-sdk/anthropic
        ;; but OpenCode Go serves them through the OpenAI-compatible path.
        go-override? (and (= :opencode-go (:provider variant))
                          (contains? #{"minimax-m2.7" "qwen3.5-plus" "qwen3.6-plus"} mid))
        api (if go-override? :openai-completions api)
        base (if go-override? (str base-path "/v1") base)
        compat (cond-> {}
                 (and (= :opencode (:provider variant)) (= mid "grok-build-0.1"))
                 (assoc :supports-reasoning-effort false)
                 (and (#{:opencode :opencode-go} (:provider variant)) (= mid "kimi-k2.6"))
                 ;; OpenCode Kimi K2.6 accepts Anthropic-style thinking objects
                 ;; and rejects string thinking values / combined reasoning_effort.
                 (assoc :thinking-format :deepseek :supports-reasoning-effort false)
                 (and (= :opencode-go (:provider variant))
                      (contains? #{"qwen3.5-plus" "qwen3.6-plus"} mid))
                 ;; Qwen/DashScope uses enable_thinking at the top level.
                 (assoc :thinking-format :qwen)
                 (= api :openai-completions)
                 (assoc :max-tokens-field :max-tokens))]
    (when api
      (model-map (:provider variant) api base m mid {:compat compat}))))

(defn- log-skips
  [label ids]
  (when (seq ids)
    (println (str "  skipped (" label "): " (str/join ", " (sort ids))))))

(defn- process-opencode
  [data]
  (let [skipped (volatile! [])]
    (let [models (doall
                  (for [variant opencode-variants
                        [mid m] (or (get-in data [(:key variant) "models"]) {})
                        :when (and (true? (get m "tool_call"))
                                   (not= "deprecated" (get m "status")))]
                    (let [mm (opencode-model variant mid m)]
                      (when (nil? mm)
                        (vswap! skipped conj mid))
                      mm)))]
      (log-skips "opencode, openai-responses" @skipped)
      models)))

;; ─── github-copilot ────────────────────────────────────────────────────────

(defn- process-copilot
  [data]
  (let [skipped (volatile! [])]
    (let [models (doall
                  (for [[mid m] (or (get-in data ["github-copilot" "models"]) {})
                        :when (and (true? (get m "tool_call"))
                                   (not= "deprecated" (get m "status")))]
                    (let [claude? (boolean (re-find #"^claude-(haiku|sonnet|opus)-[45]([.\-]|$)" mid))
                          needs-responses? (or (= mid "grok-4.5")
                                               (str/starts-with? mid "gpt-5")
                                               (str/starts-with? mid "oswe")
                                               (str/starts-with? mid "mai-"))]
                      (cond
                        claude? (model-map :github-copilot :anthropic-messages
                                           "https://api.individual.githubcopilot.com" m mid
                                           {:headers copilot-static-headers
                                            :context-default 128000 :max-default 8192})
                        needs-responses? (do (vswap! skipped conj mid) nil)
                        :else (model-map :github-copilot :openai-completions
                                         "https://api.individual.githubcopilot.com" m mid
                                         {:headers copilot-static-headers
                                          :context-default 128000 :max-default 8192
                                          :compat {:supports-store false
                                                   :supports-developer-role false
                                                   :supports-reasoning-effort false}})))))]
      (log-skips "github-copilot, openai-responses" @skipped)
      models)))

;; ─── deepseek-v4 compat normalization (pi, after all providers) ────────────

(defn- normalize-deepseek-v4
  "Every openai-completions deepseek-v4 model gets DeepSeek's thinking compat.
   opencode (zen) preserves native reasoning effort, so it only gains
   requires-reasoning-content-on-assistant-messages (pi behavior)."
  [models]
  (map (fn [mm]
         (if (and (= :openai-completions (:api mm))
                  (str/includes? (:id mm) "deepseek-v4"))
           (update mm :compat
                   merge (if (= :opencode (:provider mm))
                           {:requires-reasoning-content-on-assistant-messages true}
                           deepseek-compat))
           mm))
       models))

;; ─── Generation ────────────────────────────────────────────────────────────

(defn- fetch-models-dev
  []
  (let [resp (http/get "https://models.dev/api.json"
                       {:headers {"User-Agent" "kmet-generate-models"}
                        :timeout 60000})]
    (when-not (= 200 (:status resp))
      (throw (ex-info (str "models.dev returned HTTP " (:status resp))
                      {:type :http-error :status (:status resp)})))
    (json/parse-string (:body resp) false)))

(defn- models-by-id
  "Vector of model maps → {model-id -> model}."
  [models]
  (into {} (map (juxt :id identity)) models))

(defn- group-models
  "Vector of model maps → {api -> {model-id -> model}} (the validate-groups!
   shape, matching what read-catalogs produces for the offline check)."
  [models]
  (into {} (for [[api ms] (group-by :api models)]
             [api (models-by-id ms)])))

(defn- generate-models-data
  "Build {provider-id -> [model-map ...]} from a models.dev payload."
  [data]
  (let [all (->> (concat (process-opencode data)
                         (process-copilot data)
                         deepseek-v4-models)
                 (remove nil?))
        grouped (group-by :provider (normalize-deepseek-v4 all))]
    (into (array-map)
          (for [[pid _] providers]
            [pid (get grouped pid [])]))))

;; ─── Strict validation (pi validateModelValue / validateModelDataDirectory) ─

(defn- validate-groups!
  "Strict per-model validation of one provider's {api -> {model-id -> model}}.
   Returns the error strings (pi collects and reports all of them)."
  [pid groups]
  (let [errors (volatile! [])
        fail! (fn [label msg] (vswap! errors conj (str label ": " msg)))]
    (doseq [[api models] groups
            [mid mm] models]
      (let [label (str pid "/" mid)]
        (when-not (= mid (:id mm))
          (fail! label (str "has id " (pr-str (:id mm)) ", expected " (pr-str mid))))
        (when-not (= pid (:provider mm))
          (fail! label (str "has provider " (pr-str (:provider mm)) ", expected " (pr-str pid))))
        (when-not (= api (:api mm))
          (fail! label (str "has api " (pr-str (:api mm)) ", expected " (pr-str api))))
        (when-not (and (string? (:name mm)) (seq (:name mm)))
          (fail! label "has no model name"))
        (when-not (string? (:base-url mm))
          (fail! label "has no base-url string"))
        (when-not (boolean? (:reasoning mm))
          (fail! label "has no reasoning boolean"))
        (when-not (and (vector? (:input mm)) (seq (:input mm))
                       (every? #{:text :image} (:input mm)))
          (fail! label "has invalid input modalities"))
        (when-not (and (number? (:context-window mm)) (pos? (:context-window mm)))
          (fail! label "has invalid context-window"))
        (when-not (and (number? (:max-tokens mm)) (pos? (:max-tokens mm)))
          (fail! label "has invalid max-tokens"))
        (when-not (and (map? (:cost mm))
                       (every? (fn [k] (let [v (get-in mm [:cost k])]
                                         (and (number? v) (not (neg? v)))))
                               [:input :output :cache-read :cache-write]))
          (fail! label "has invalid cost (4 non-negative numeric fields)"))))
    (doseq [[api models] groups]
      (doseq [dup (->> (vals (group-by key models))
                       (filter #(< 1 (count %))))]
        (fail! (str pid "/" (ffirst dup))
               (str "duplicate model id in api group " (name api)))))
    (let [all-ids (mapcat keys (vals groups))]
      (doseq [dup (->> (group-by identity all-ids) vals (filter #(< 1 (count %))))]
        (fail! (str pid "/" (first dup)) "model id appears in more than one api group")))
    @errors))

(defn- structure-hash
  "sha256 over the canonical sorted {provider -> {model-id -> api}} (pi
   modelDataStructureHash; identical to kmet.app.models/catalog-structure so
   the offline manifest check agrees)."
  [catalogs]
  (let [structure (into (sorted-map)
                        (for [[pid models] catalogs]
                          [(name pid)
                           (into (sorted-map)
                                 (for [[api ms] (group-by :api models)
                                       mm ms]
                                   [(name (:id mm)) (name api)]))]))]
    (sha256-hex (pr-str structure))))

;; ─── EDN writer (deterministic; mirrors the committed catalog style) ───────

(def ^:private width 80)

(def ^:private key-priority
  "Global canonical key order for the EDN output. Hash-map iteration order is
   not stable, so every map is rendered via sorted-entries; unknown keywords
   sort alphabetically after known ones, string keys last."
  (into {}
        (map-indexed (fn [i k] [k i]))
        [:schema-version :generated-at
         :id :name :env-vars :default-model
         :provider :api :base-url :reasoning :input :cost
         :context-window :max-tokens :headers :compat
         :output :cache-read :cache-write
         :supports-store :supports-developer-role :supports-reasoning-effort
         :requires-reasoning-content-on-assistant-messages :thinking-format
         :max-tokens-field
         :structure-hash :files]))

(defn- key-rank
  [k]
  (cond
    (keyword? k) (if (contains? key-priority k)
                   [0 (get key-priority k)]
                   [1 (name k)])
    :else [2 (str k)]))

(defn- sorted-entries
  [m]
  (sort-by (comp key-rank key) m))

(defn- num-str
  "Number → EDN literal: integers as longs, floats rounded to 6 decimals with
   trailing zeros stripped (pi roundCost → toFixed(6))."
  [v]
  (let [n (double v)]
    (if (== n (Math/floor n))
      (str (long n))
      (str/replace (str (.movePointLeft (BigDecimal. (Math/round (* n 1000000.0))) 6))
                   #"0+$" ""))))

(defn- key-str
  [k]
  (if (keyword? k) (str k) (pr-str k)))

(defn- spaces
  [n]
  (apply str (repeat n " ")))

(defn- flat
  "Single-line rendering (used for width checks and scalar/vector output)."
  [v]
  (cond
    (string? v) (pr-str v)
    (keyword? v) (str v)
    (symbol? v) (str v)
    (nil? v) "nil"
    (true? v) "true"
    (false? v) "false"
    (number? v) (num-str v)
    (vector? v) (str "[" (str/join " " (map flat v)) "]")
    (map? v) (str "{" (str/join " " (map (fn [[k v]] (str (key-str k) " " (flat v))) (sorted-entries v))) "}")
    :else (str v)))

(declare render-value)

(defn- render-map
  "Multi-line map: `{` + first entry inline, continuation entries aligned at
   col+1 (the committed catalog style)."
  [m col]
  (let [ecol (inc col)]
    (str "{" (str/join (for [[i [k v]] (map-indexed vector (sorted-entries m))]
                         (str (if (zero? i) "" (str "\n" (spaces ecol)))
                              (key-str k)
                              (render-value k v ecol))))
         "}")))

(defn- render-value
  [k v ecol]
  (cond
    (not (map? v)) (str " " (flat v))
    (<= (+ ecol (count (key-str k)) 1 (count (flat v))) width) (str " " (flat v))
    (every? #(not (map? %)) (vals v)) (str " " (render-map v (+ ecol (count (key-str k)) 1)))
    :else (str "\n" (spaces ecol) (render-map v ecol))))

(defn- render
  "Render a value (maps at top level go multi-line)."
  [v]
  (if (and (map? v) (> (count (flat v)) width))
    (render-map v 0)
    (flat v)))

;; ─── Catalog assembly + writing ────────────────────────────────────────────

(defn- catalog-blob
  [pid models generated]
  (array-map :schema-version 1
             :generated-at generated
             :provider (array-map :id pid
                                  :name (get-in providers [pid :name])
                                  :env-vars (get-in providers [pid :env-vars])
                                  :default-model (get-in providers [pid :default-model]))
             :models (into (array-map)
                           (for [[api ms] (sort-by (comp name key) (group-by :api models))]
                             [api (into (array-map)
                                        (for [mm (sort-by :id ms)]
                                          [(:id mm) mm]))]))))

(defn- read-catalogs
  "Catalogs in DIR as {provider-id -> {api -> {model-id -> model}}},
   sorted by filename (manifest.edn excluded)."
  [dir]
  (into (array-map)
        (for [f (->> (fs/list-dir dir)
                     (filter fs/regular-file?)
                     (map fs/file)
                     (filter #(str/ends-with? (str %) ".edn"))
                     (remove #(= "manifest.edn" (fs/file-name %)))
                     (sort-by fs/file-name))
              :let [data (edn/read-string (slurp f))]]
          [(get-in data [:provider :id]) (:models data)])))

(defn- catalog-file-contents
  "Catalog filenames + raw content in DIR, sorted by filename (manifest.edn
   excluded)."
  [dir]
  (for [f (->> (fs/list-dir dir)
               (filter fs/regular-file?)
               (map fs/file)
               (filter #(str/ends-with? (str %) ".edn"))
               (remove #(= "manifest.edn" (fs/file-name %)))
               (sort-by fs/file-name))]
    [(fs/file-name f) (slurp f)]))

(defn- flatten-catalogs
  "{provider-id -> {api -> {model-id -> model}}} → {provider-id -> [model ...]}
   (the generate-models-data shape, for structure-hash)."
  [catalogs]
  (into {} (for [[pid groups] catalogs]
             [pid (vec (mapcat (fn [[_ ms]] (vals ms)) groups))])))

(defn validate-committed!
  "Offline strict validation of the committed catalog files + manifest
   consistency (pi check-model-data.ts). Returns the error strings. With
   DIR, validates that directory instead (tests)."
  ([] (validate-committed! data-dir))
  ([dir]
   (let [catalogs (read-catalogs dir)
         errors (volatile! (into []
                                 (mapcat (fn [[pid groups]]
                                           (if (seq groups)
                                             (validate-groups! pid groups)
                                             [(str pid ": catalog has no :models groups")]))
                                         catalogs)))
         manifest (try (edn/read-string (slurp (str dir "/manifest.edn")))
                       (catch Exception _ nil))]
     (if (and (map? manifest) (:structure-hash manifest) (:files manifest))
       (do (when-not (= (:structure-hash manifest) (structure-hash (flatten-catalogs catalogs)))
             (vswap! errors conj "manifest structure-hash does not match the catalog structure"))
           (doseq [[fname content] (catalog-file-contents dir)]
             (when-not (= (get-in manifest [:files fname]) (sha256-hex content))
               (vswap! errors conj (str fname " does not match its manifest hash")))))
       (vswap! errors conj "manifest.edn is missing or incomplete (needs :structure-hash and :files)"))
     @errors)))

(defn- write-catalogs!
  [catalogs]
  (fs/create-dirs data-dir)
  (let [generated (generated-at)
        file-contents (into (array-map)
                            (for [[pid models] catalogs]
                              [(str (name pid) ".edn")
                               (str (render (catalog-blob pid models generated)) "\n")]))
        manifest (array-map :schema-version 1
                            :generated-at generated
                            :structure-hash (structure-hash catalogs)
                            :files (into (sorted-map)
                                         (for [[fname content] file-contents]
                                           [fname (sha256-hex content)])))]
    (doseq [[fname content] file-contents]
      (spit (str data-dir "/" fname) content))
    (spit (str data-dir "/manifest.edn") (str (render manifest) "\n"))
    ;; Safety: the regenerated files must pass the same offline gate as CI.
    (let [errors (validate-committed!)]
      (when (seq errors)
        (throw (ex-info (str "Regenerated catalogs failed validation:\n"
                             (str/join "\n" errors))
                        {:type :catalog-invalid}))))))

(defn- report-and-exit!
  "Print an error headline + lines, exit 1 (fail loudly, don't clobber the
   committed catalogs on bad input)."
  [headline lines]
  (println headline)
  (run! #(println "  -" %) lines)
  (System/exit 1))

(defn -main
  "Fetch models.dev, regenerate the committed catalogs + manifest, validate."
  [& _]
  (println "Fetching https://models.dev/api.json ...")
  (let [data (fetch-models-dev)
        catalogs (generate-models-data data)
        errors (into []
                     (mapcat (fn [[pid models]]
                               (validate-groups! pid (group-models models)))
                             catalogs))
        empties (for [[pid models] catalogs :when (empty? models)] (name pid))]
    (cond
      (seq errors)
      (report-and-exit! "Invalid generated model data:" errors)

      (seq empties)
      (report-and-exit!
       (str "Provider(s) generated no models: " (str/join ", " empties)
            " — models.dev may have dropped or restructured the provider;")
       ["fix the script before writing."])

      :else
      (do (write-catalogs! catalogs)
          (println "Generated provider catalogs under" data-dir "/")
          (doseq [[pid models] catalogs]
            (println (format "  %-16s %d models" (name pid) (count models))))
          (println "Manifest written.")))))
