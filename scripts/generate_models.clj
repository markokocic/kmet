;; scripts/generate_models.clj — regenerate src/kmet/app/model_data/*.edn from
;; models.dev (pi: packages/ai/scripts/generate-models.ts, ported to the 8
;; providers kmet ships: opencode-go, opencode, deepseek, github-copilot,
;; openai, xai, openai-codex, azure-openai-responses).
;;
;; Wire APIs out of kmet's scope (bedrock, google-vertex, mistral, etc.)
;; are skipped and logged. The generated EDN + manifest.edn are committed;
;; the offline half (validate-committed!) also runs as
;; test/kmet/app/test_model_data.clj so drift is caught without network.
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
                    :default-model "claude-sonnet-4.5"}
   :openai {:name "OpenAI"
            :env-vars ["OPENAI_API_KEY"]
            :default-model "gpt-5.5"}
   :xai {:name "xAI"
         :env-vars ["XAI_API_KEY"]
         :default-model "grok-4.5"}
   :openai-codex {:name "OpenAI Codex"
                  :env-vars []            ;; OAuth (ChatGPT) — no env var
                  :default-model "gpt-5.5"}
   :azure-openai-responses {:name "Azure OpenAI"
                            :env-vars ["AZURE_OPENAI_API_KEY"]
                            :default-model "gpt-5.4"}))

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

;; ─── OpenAI / xAI constants (pi generate-models.ts, ported verbatim) ───────

(def ^:private openai-long-context-input-threshold 272000)

(def ^:private models-dev-openai-unsupported-model-ids
  "models.dev lists this alias, but it is not accepted by OpenAI APIs."
  #{"gpt-5.6"})

(def ^:private openai-tool-search-model-ids
  #{"gpt-5.4" "gpt-5.4-mini" "gpt-5.4-pro" "gpt-5.5"
    "gpt-5.6-sol" "gpt-5.6-terra" "gpt-5.6-luna"})

;; Public OpenAI documents additional_tools for applications that load tools
;; outside the normal tool-search flow; the openai provider supports both.
(def ^:private openai-additional-tools-model-ids openai-tool-search-model-ids)

(def ^:private openai-short-context-capped-model-ids
  #{"gpt-5.4" "gpt-5.5" "gpt-5.6-sol" "gpt-5.6-terra" "gpt-5.6-luna"})

(def ^:private openai-long-context-pricing-model-ids
  #{"gpt-5.4" "gpt-5.4-pro" "gpt-5.5" "gpt-5.5-pro"
    "gpt-5.6-sol" "gpt-5.6-terra" "gpt-5.6-luna"})

;; OpenAI reduced GPT-5.6 Terra and Luna prices on 2026-07-30. Keep these
;; authoritative values until models.dev and passthrough catalogs catch up.
;; https://developers.openai.com/api/docs/pricing
(def ^:private openai-gpt-56-standard-costs
  {"gpt-5.6-luna" {:input 0.2 :output 1.2 :cache-read 0.02 :cache-write 0.25}
   "gpt-5.6-terra" {:input 2 :output 12 :cache-read 0.2 :cache-write 2.5}})

;; Models that accept `reasoning: {effort: "none"}` to disable thinking;
;; every other gpt-5* responses model pins :off to null (always-thinking).
(def ^:private openai-responses-none-reasoning-models
  #{"gpt-5.1" "gpt-5.2" "gpt-5.3-codex" "gpt-5.4" "gpt-5.4-mini"
    "gpt-5.4-nano" "gpt-5.5" "gpt-5.6-sol" "gpt-5.6-terra" "gpt-5.6-luna"})

(def ^:private xai-responses-model-id "grok-4.5")

(def ^:private xai-builtin-excluded-model-ids
  #{"grok-3" "grok-3-fast" "grok-4.20-0309-non-reasoning"
    "grok-4.20-0309-reasoning" "grok-code-fast-1"})

(def ^:private xai-responses-effort-level-map
  {:off nil :minimal nil})

(def ^:private xai-responses-compat
  {:supports-long-cache-retention false})

;; Copilot models served with an extended 1M context (pi override).
(def ^:private github-copilot-extended-context-models
  #{"claude-fable-5" "claude-opus-4.6" "claude-opus-4.7" "claude-opus-4.8"
    "claude-opus-5" "claude-sonnet-4.6" "claude-sonnet-5"
    "gpt-5.3-codex" "gpt-5.4" "gpt-5.5"})

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
  "pi getModelsDevCost: the flat 4-field form plus context tiers
   (tier.tier.type === 'context' with a size → inputTokensAbove; each tier
   supplies a complete alternate rate set for the whole request)."
  [m]
  (let [tiers (->> (get-in m ["cost" "tiers"])
                   (keep (fn [tier]
                           (let [ctx (get tier "tier")]
                             (when (and (= "context" (get ctx "type"))
                                        (some? (get ctx "size")))
                               (array-map :input-tokens-above (long (get ctx "size"))
                                          :input (or (get tier "input") 0)
                                          :output (or (get tier "output") 0)
                                          :cache-read (or (get tier "cache_read") 0)
                                          :cache-write (or (get tier "cache_write") 0))))))
                   vec)]
    (cond-> (array-map :input (or (get-in m ["cost" "input"]) 0)
                       :output (or (get-in m ["cost" "output"]) 0)
                       :cache-read (or (get-in m ["cost" "cache_read"]) 0)
                       :cache-write (or (get-in m ["cost" "cache_write"]) 0))
      (seq tiers) (assoc :tiers tiers))))

(defn- round-cost
  "pi roundCost — toFixed(6)."
  [v]
  (Double/parseDouble (format "%.6f" (double v))))

(defn- with-openai-long-context-pricing
  "pi withOpenAiLongContextPricing: keep the short-context tier as the base
   rates, add the long-context tier at the 272000-token threshold."
  [cost]
  (assoc cost
         :tiers [(array-map :input-tokens-above openai-long-context-input-threshold
                            :input (round-cost (* 2 (or (:input cost) 0)))
                            :output (round-cost (* 1.5 (or (:output cost) 0)))
                            :cache-read (round-cost (* 2 (or (:cache-read cost) 0)))
                            :cache-write (round-cost (* 2 (or (:cache-write cost) 0))))]))

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

(declare apply-thinking-maps)

;; ─── opencode / opencode-go (pi opencodeVariants) ─────────────────────────

(defn- opencode-model
  "One models.dev opencode variant model, or nil when it needs a wire API
   kmet does not implement yet (@ai-sdk/openai → openai-responses; skipped
   and logged by the caller)."
  [variant mid m]
  (let [npm (get-in m ["provider" "npm"])
        base-path (:base-path variant)
        api (cond
              (= npm "@ai-sdk/openai") :openai-responses
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
                 (= api :openai-responses)
                 ;; opencode zen's responses path has no session affinity
                 ;; (pi: sessionAffinityFormat "openai-nosession")
                 (assoc :session-affinity-format :openai-nosession)
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
      (apply-thinking-maps (model-map (:provider variant) api base m mid {:compat compat}) m))))

(defn- process-opencode
  [data]
  (doall
   (for [variant opencode-variants
         [mid m] (or (get-in data [(:key variant) "models"]) {})
         :when (and (true? (get m "tool_call"))
                    (not= "deprecated" (get m "status"))
                    ;; pi drops this alias from the opencode variants
                    (not= mid "gpt-5.3-codex-spark"))]
     (opencode-model variant mid m))))

;; ─── github-copilot ────────────────────────────────────────────────────────

(defn- process-copilot
  [data]
  (doall
   (for [[mid m] (or (get-in data ["github-copilot" "models"]) {})
         :when (and (true? (get m "tool_call"))
                    (not= "deprecated" (get m "status")))]
     (let [claude? (boolean (re-find #"^claude-(haiku|sonnet|opus)-[45]([.\-]|$)" mid))
           needs-responses? (or (= mid "grok-4.5")
                                (str/starts-with? mid "gpt-5")
                                (str/starts-with? mid "oswe")
                                (str/starts-with? mid "mai-"))]
       (cond
         claude? (apply-thinking-maps
                  (model-map :github-copilot :anthropic-messages
                             "https://api.individual.githubcopilot.com" m mid
                             {:headers copilot-static-headers
                              :context-default 128000 :max-default 8192})
                  m)
         needs-responses? (apply-thinking-maps
                           ;; Grok 4.5 / gpt-5 / oswe / MAI-Code models are only
                           ;; served through the Copilot /responses endpoint
                           ;; (pi: needsResponsesApi). No explicit compat — the
                           ;; strict/tool-search/explicit-cache passes don't
                           ;; apply to copilot (pi), only grammar tools do.
                           (model-map :github-copilot :openai-responses
                                      "https://api.individual.githubcopilot.com" m mid
                                      {:headers copilot-static-headers
                                       :context-default 128000 :max-default 8192})
                           m)
         :else (apply-thinking-maps
                (model-map :github-copilot :openai-completions
                           "https://api.individual.githubcopilot.com" m mid
                           {:headers copilot-static-headers
                            :context-default 128000 :max-default 8192
                            :compat {:supports-store false
                                     :supports-developer-role false
                                     :supports-reasoning-effort false}})
                m))))))

;; ─── openai (pi: all models.dev openai models → :openai-responses) ─────────

(def ^:private openai-base-url "https://api.openai.com/v1")

(defn- process-openai
  [data]
  (doall
   (for [[mid m] (or (get-in data ["openai" "models"]) {})
         :when (and (true? (get m "tool_call"))
                    (not= "deprecated" (get m "status"))
                    (not (contains? models-dev-openai-unsupported-model-ids mid)))]
     (apply-thinking-maps
      (model-map :openai :openai-responses openai-base-url m mid {})
      m))))

;; ─── xai (pi: grok-4.5 → :openai-responses, the rest openai-completions) ──

(def ^:private xai-base-url "https://api.x.ai/v1")

(defn- process-xai
  [data]
  (doall
   (for [[mid m] (or (get-in data ["xai" "models"]) {})
         :when (and (true? (get m "tool_call"))
                    (not= "deprecated" (get m "status"))
                    (not (contains? xai-builtin-excluded-model-ids mid)))]
     (let [responses? (= mid xai-responses-model-id)]
       (apply-thinking-maps
        (model-map :xai (if responses? :openai-responses :openai-completions)
                   xai-base-url m mid
                   {:compat (if responses?
                              xai-responses-compat
                              {:supports-store false
                               :supports-developer-role false
                               :supports-reasoning-effort false})})
        m)))))

;; ─── Missing openai models (pi: added when models.dev lags) ───────────────

(defn- missing-openai-models
  [existing-ids]
  (remove (fn [mm] (contains? existing-ids (:id mm)))
          [(array-map :id "gpt-5.6-sol" :name "GPT-5.6 Sol"
                      :provider :openai :api :openai-responses
                      :base-url openai-base-url
                      :reasoning true :input [:text :image]
                      :cost (with-openai-long-context-pricing
                              {:input 5 :output 30 :cache-read 0.5 :cache-write 6.25})
                      :context-window openai-long-context-input-threshold
                      :max-tokens 128000)
           (array-map :id "gpt-5.6-terra" :name "GPT-5.6 Terra"
                      :provider :openai :api :openai-responses
                      :base-url openai-base-url
                      :reasoning true :input [:text :image]
                      :cost (with-openai-long-context-pricing
                              (get openai-gpt-56-standard-costs "gpt-5.6-terra"))
                      :context-window openai-long-context-input-threshold
                      :max-tokens 128000)
           (array-map :id "gpt-5.6-luna" :name "GPT-5.6 Luna"
                      :provider :openai :api :openai-responses
                      :base-url openai-base-url
                      :reasoning true :input [:text :image]
                      :cost (with-openai-long-context-pricing
                              (get openai-gpt-56-standard-costs "gpt-5.6-luna"))
                      :context-window openai-long-context-input-threshold
                      :max-tokens 128000)
           (array-map :id "gpt-5-chat-latest" :name "GPT-5 Chat Latest"
                      :provider :openai :api :openai-responses
                      :base-url openai-base-url
                      :reasoning false :input [:text :image]
                      :cost {:input 1.25 :output 10 :cache-read 0.125 :cache-write 0}
                      :context-window 128000 :max-tokens 16384)]))

;; ─── openai-codex (pi: hardcoded codexModels — ChatGPT OAuth models; the
;;     list is kept small to avoid aliases) ─────────────────────────────────

(def ^:private codex-base-url "https://chatgpt.com/backend-api")
(def ^:private codex-context 272000)
(def ^:private codex-spark-context 128000)
(def ^:private codex-max-tokens 128000)

(defn- codex-model
  "One hardcoded codex model map (pi codexModels entry)."
  [id name input cost context]
  (array-map :id id :name name
             :provider :openai-codex :api :openai-codex-responses
             :base-url codex-base-url
             :reasoning true :input input
             :cost cost
             :context-window context :max-tokens codex-max-tokens))

(defn- process-codex
  "The hardcoded codex catalog (pi codexModels; gpt-5.4/5.5/5.6 carry the
   long-context pricing tier, gpt-5.6 standard costs like openai)."
  []
  [(codex-model "gpt-5.3-codex-spark" "GPT-5.3 Codex Spark" [:text]
                (array-map :input 1.75 :output 14 :cache-read 0.175 :cache-write 0)
                codex-spark-context)
   (codex-model "gpt-5.4" "GPT-5.4" [:text :image]
                (with-openai-long-context-pricing
                 (array-map :input 2.5 :output 15 :cache-read 0.25 :cache-write 0))
                codex-context)
   (codex-model "gpt-5.4-mini" "GPT-5.4 mini" [:text :image]
                (array-map :input 0.75 :output 4.5 :cache-read 0.075 :cache-write 0)
                codex-context)
   (codex-model "gpt-5.5" "GPT-5.5" [:text :image]
                (with-openai-long-context-pricing
                 (array-map :input 5 :output 30 :cache-read 0.5 :cache-write 0))
                codex-context)
   (codex-model "gpt-5.6-luna" "GPT-5.6 Luna" [:text :image]
                (with-openai-long-context-pricing
                 (get openai-gpt-56-standard-costs "gpt-5.6-luna"))
                codex-context)
   (codex-model "gpt-5.6-sol" "GPT-5.6 Sol" [:text :image]
                (with-openai-long-context-pricing
                 (array-map :input 5 :output 30 :cache-read 0.5 :cache-write 6.25))
                codex-context)
   (codex-model "gpt-5.6-terra" "GPT-5.6 Terra" [:text :image]
                (with-openai-long-context-pricing
                 (get openai-gpt-56-standard-costs "gpt-5.6-terra"))
                codex-context)])

;; ─── azure-openai-responses (pi: azureOpenAiModels — the mirror of the
;;     openai responses models; Azure Foundry deploys them with larger
;;     context windows, cost tiers are dropped) ─────────────────────────────

(def ^:private azure-context-window-overrides
  {"gpt-5.4" 1050000 "gpt-5.5" 1050000
   "gpt-5.6-luna" 1050000 "gpt-5.6-sol" 1050000 "gpt-5.6-terra" 1050000})

(defn- process-azure
  "The azure mirror (pi azureOpenAiModels): every openai-responses openai
   model becomes an azure-openai-responses model with an empty base-url
   (the deployment config comes from AZURE_OPENAI_* env vars at request
   time) and the context override; cost keeps the flat rates only."
  [openai-models]
  (for [mm openai-models
        :when (and (= :openai (:provider mm))
                   (= :openai-responses (:api mm)))]
    (assoc mm
           :api :azure-openai-responses
           :provider :azure-openai-responses
           :base-url ""
           :cost (select-keys (:cost mm) [:input :output :cache-read :cache-write])
           :context-window (get azure-context-window-overrides (:id mm)
                                (:context-window mm)))))

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

;; ─── Thinking level maps (pi applyModelsDevReasoningOptionMetadata +
;;     applyThinkingLevelMetadata, kmet's providers only) ─────────────────────

(def ^:private thinking-levels
  [:minimal :low :medium :high :xhigh :max])

(defn- effort-thinking-level-map
  "pi getEffortThinkingLevelMap: reasoning_options effort values →
   {level -> wire string | nil}. :off ← \"none\" (nil when unsupported);
   levels without a verified value are nil (unsupported). Returns nil when
   there are no usable effort values."
  [reasoning-options]
  (let [effort-values (into []
                            (comp (filter #(= "effort" (get % "type")))
                                  (mapcat #(get % "values")))
                            reasoning-options)
        supported (set effort-values)]
    (when (and (seq effort-values)
               (or (some supported (map name thinking-levels))
                   (contains? supported "none")))
      (into (array-map :off (if (contains? supported "none") "none" nil))
            (for [level thinking-levels]
              [level (if (contains? supported (name level)) (name level) nil)])))))

(defn- merge-thinking-level-map
  [mm extra]
  (update mm :thinking-level-map merge extra))

(defn- apply-effort-thinking-map
  "pi applyModelsDevReasoningOptionMetadata: reasoning_options effort values
   → thinking-level-map for openai-completions models using the default
   (openai) thinking format and supporting reasoning_effort (kmet: no
   explicit :thinking-format, :supports-reasoning-effort not false), and
   unconditionally for openai-responses models (pi supportsDirectReasoningEffort
   — the responses API has native reasoning.effort). google/anthropic models
   get their maps from the explicit metadata rules only."
  [mm m]
  (if (and (contains? #{:openai-completions :openai-responses} (:api mm))
           (:reasoning mm)
           (nil? (:thinking-format (:compat mm)))
           (not= false (:supports-reasoning-effort (:compat mm)))
           (get m "reasoning_options"))
    (if-let [tlm (effort-thinking-level-map (get m "reasoning_options"))]
      (assoc mm :thinking-level-map tlm)
      mm)
    mm))

(defn- supports-openai-xhigh?
  "pi supportsOpenAiXhigh: gpt-5.2+ models expose xhigh."
  [id]
  (or (str/includes? id "gpt-5.2")
      (str/includes? id "gpt-5.3")
      (str/includes? id "gpt-5.4")
      (str/includes? id "gpt-5.5")
      (str/includes? id "gpt-5.6")))

(defn- supports-openai-max?
  "pi supportsOpenAiMax: gpt-5.6 on the responses/completions family."
  [mm]
  (and (str/includes? (:id mm) "gpt-5.6")
       (contains? #{:openai-responses :azure-openai-responses
                    :openai-codex-responses :openai-completions} (:api mm))))

(defn- apply-thinking-level-metadata
  "pi applyThinkingLevelMetadata rules for kmet's providers (deepseek-v4
   maps, opencode-go glm-5.2/kimi-k2.6, opencode grok-build-0.1, copilot
   claude overrides + adaptive thinking maps, gemini-3 maps)."
  [mm]
  (let [id (:id mm)
        provider (:provider mm)
        deepseek-v4? (and (= :openai-completions (:api mm))
                          (str/includes? id "deepseek-v4"))
        adaptive-high? (or (str/includes? id "opus-4.7")
                           (str/includes? id "opus-4.8")
                           (str/includes? id "opus-5")
                           (str/includes? id "sonnet-5"))
        adaptive-max? (or (str/includes? id "opus-4.6")
                          (str/includes? id "sonnet-4.6")
                          adaptive-high?)
        gemini3-pro? (re-matches #"(?i).*gemini-3(?:\.\d+)?-pro.*" id)
        gemini3-flash? (or (re-matches #"(?i).*gemini-3(?:\.\d+)?-flash.*" id)
                           (= id "gemini-flash-latest")
                           (= id "gemini-flash-lite-latest"))
        gemma4? (re-matches #"(?i).*gemma-?4.*" id)
        copilot-override (get {"claude-opus-4.7" {:minimal "low"}
                               "claude-opus-4.8" {:minimal "low"}
                               "claude-opus-5" {:minimal "low"}
                               "claude-sonnet-4.6" {:minimal "low" :max "max"}}
                              id)]
    (cond-> mm
      ;; openai-responses gpt-5* cannot disable thinking (pi: off: null);
      ;; the explicit none-reasoning set below overrides back to "none".
      (and (contains? #{:openai-responses :azure-openai-responses} (:api mm))
           (str/starts-with? id "gpt-5"))
      (merge-thinking-level-map {:off nil})
      (and (= :github-copilot provider) (str/starts-with? id "gpt-5"))
      (merge-thinking-level-map {:minimal "low"})
      (and (= :openai-responses (:api mm))
           (= :openai provider)
           (contains? openai-responses-none-reasoning-models id))
      (merge-thinking-level-map {:off "none"})
      (and (= :xai provider)
           (= :openai-responses (:api mm))
           (= id xai-responses-model-id))
      (merge-thinking-level-map xai-responses-effort-level-map)
      (supports-openai-xhigh? id)
      (merge-thinking-level-map {:xhigh "xhigh"})
      (supports-openai-max? mm)
      (merge-thinking-level-map {:max "max"})
      (and (= :openai-codex provider) (supports-openai-xhigh? id))
      (merge-thinking-level-map {:minimal "low"})
      (and (= :openai provider) (= id "gpt-5.5"))
      (merge-thinking-level-map {:minimal nil})
      (str/ends-with? id "gpt-5.5-pro")
      (merge-thinking-level-map {:off nil :minimal nil :low nil})
      deepseek-v4?
      (merge-thinking-level-map {:minimal nil :low nil :medium nil
                                 :high "high" :max "max"})
      (and (= :opencode-go provider) (= id "glm-5.2"))
      (merge-thinking-level-map {:off nil :minimal nil :low nil :medium nil
                                 :high "high" :max "max"})
      (and (= :opencode-go provider) (= id "kimi-k2.6"))
      (merge-thinking-level-map {:minimal nil :low nil :medium nil})
      (and (= :opencode provider) (= id "grok-build-0.1"))
      (merge-thinking-level-map {:off nil :minimal nil :low nil :medium nil})
      adaptive-max?
      (merge-thinking-level-map {:max "max"})
      adaptive-high?
      (merge-thinking-level-map {:xhigh "xhigh" :max "max"})
      (str/includes? id "fable-5")
      (merge-thinking-level-map {:off nil :xhigh "xhigh" :max "max"})
      copilot-override
      (merge-thinking-level-map copilot-override)
      gemini3-pro?
      (merge-thinking-level-map {:off nil :minimal nil :low "LOW"
                                 :medium nil :high "HIGH"})
      gemini3-flash?
      (merge-thinking-level-map {:off nil})
      gemma4?
      (merge-thinking-level-map {:off nil :minimal "MINIMAL" :low nil
                                 :medium nil :high "HIGH"}))))

(defn- apply-thinking-maps
  "thinking-level-map pipeline (pi order): effort maps from the models.dev
   reasoning options, then the explicit metadata rules (later merges win)."
  [mm m]
  (-> mm (apply-effort-thinking-map m) apply-thinking-level-metadata))

;; ─── Responses-family compat metadata (pi apply*CompatMetadata passes) ────

(defn- merge-compat
  [mm ks]
  (update mm :compat merge ks))

(defn- apply-strict-tool-compat
  "pi applyStrictToolCompatMetadata: openai responses models accept strict
   JSON-schema tool definitions."
  [mm]
  (if (and (= :openai (:provider mm)) (= :openai-responses (:api mm)))
    (merge-compat mm {:supports-strict-mode true})
    mm))

(defn- apply-openai-grammar-tool-compat
  "pi applyOpenAIGrammarToolCompatMetadata: responses endpoints verified to
   pass OpenAI custom grammar tools through — gpt-5+ only (OpenAI rejects
   type: 'custom' tools for pre-GPT-5 models)."
  [mm]
  (if (and (contains? #{:openai-responses :azure-openai-responses
                        :openai-codex-responses}
                      (:api mm))
           (contains? #{:openai :openai-codex :azure-openai-responses
                        :github-copilot :opencode :cloudflare-ai-gateway}
                      (:provider mm))
           (let [match (re-matches #"gpt-(\d+).*" (:id mm))]
             (and match (<= 5 (Long/parseLong (second match))))))
    (merge-compat mm {:supports-openai-grammar-tools true})
    mm))

(def ^:private openai-codex-additional-tools-model-ids
  #{"gpt-5.6-sol" "gpt-5.6-terra" "gpt-5.6-luna"})

(defn- apply-openai-tool-search-metadata
  "pi applyOpenAIToolSearchMetadata: gpt-5.4+ openai responses models can
   load tools at a specific point in the input (tool search) and accept
   additional_tools items (the codex responses family adds its own gpt-5.6
   additional-tools set)."
  [mm]
  (let [is-openai? (and (= :openai (:provider mm)) (= :openai-responses (:api mm)))
        is-codex? (and (= :openai-codex (:provider mm))
                       (= :openai-codex-responses (:api mm)))]
    (if (and (or is-openai? is-codex?)
             (contains? openai-tool-search-model-ids (:id mm)))
      (merge-compat mm
                    (cond-> {:supports-tool-search true}
                      (or (and is-openai?
                               (contains? openai-additional-tools-model-ids (:id mm)))
                          (and is-codex?
                               (contains? openai-codex-additional-tools-model-ids (:id mm))))
                      (assoc :supports-additional-tools true)))
      mm)))

(defn- apply-openai-explicit-prompt-cache-metadata
  "pi applyOpenAIExplicitPromptCacheMetadata: OpenAI charges prompt-cache
   writes starting with the GPT-5.6 family, and exactly those models accept
   prompt_cache_options (older models reject the parameter)."
  [mm]
  (if (and (= :openai (:provider mm)) (= :openai-responses (:api mm))
           (pos? (or (get-in mm [:cost :cache-write]) 0)))
    (merge-compat mm {:supports-explicit-prompt-cache-mode true})
    mm))

(defn- apply-compat-metadata
  "The post-processing compat passes (pi order: strict tools, grammar tools,
   tool search, explicit prompt cache)."
  [mm]
  (-> mm apply-strict-tool-compat
      apply-openai-grammar-tool-compat
      apply-openai-tool-search-metadata
      apply-openai-explicit-prompt-cache-metadata))

;; ─── Post-merge overrides (pi generateModels temporary overrides) ──────────

(defn- normalize-openai
  "Keep direct OpenAI requests in the short-context pricing tier by default
   (users can opt into the larger context through model overrides, so the
   long-context cost metadata stays on the capped models); apply the
   long-context pricing tiers and the reduced GPT-5.6 standard costs."
  [mm]
  (let [id (:id mm)]
    (cond-> mm
      (and (= :openai (:provider mm))
           (contains? openai-short-context-capped-model-ids id))
      (assoc :context-window openai-long-context-input-threshold
             :max-tokens 128000)

      (and (= :openai (:provider mm))
           (contains? openai-long-context-pricing-model-ids id))
      (assoc :cost (with-openai-long-context-pricing
                    (or (get openai-gpt-56-standard-costs id)
                        (:cost mm))))

      ;; models.dev reports gpt-5-pro output as 272000 (a duplicate of the
      ;; input sub-limit); the actual max output is 128000.
      (and (= :openai (:provider mm)) (= id "gpt-5-pro"))
      (assoc :max-tokens 128000))))

(defn- normalize-context-overrides
  "pi generateModels override loop for kmet's providers: the opencode
   variants' claude/gpt-5.4 limits, copilot's extended-context models, the
   opencode 1M-context claude pair, and the azure deployment context
   windows."
  [mm]
  (let [id (:id mm)
        prov (:provider mm)
        variant? (contains? #{:opencode :opencode-go} prov)]
    (cond-> mm
      (and (= :azure-openai-responses prov)
           (contains? azure-context-window-overrides id))
      (assoc :context-window (get azure-context-window-overrides id))

      (and (= :github-copilot prov)
           (contains? github-copilot-extended-context-models id))
      (assoc :context-window 1000000)

      (and variant? (contains? #{"claude-opus-4-6" "claude-sonnet-4-6"
                                 "claude-opus-4.6" "claude-sonnet-4.6"} id))
      (assoc :context-window 1000000)

      ;; OpenCode variants list Claude Sonnet 4/4.5 with 1M context, actual
      ;; limit is 200K.
      (and variant? (contains? #{"claude-sonnet-4-5" "claude-sonnet-4"} id))
      (assoc :context-window 200000)

      (and variant? (= id "gpt-5.4"))
      (assoc :context-window 272000 :max-tokens 128000))))

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
  "Build {provider-id -> [model-map ...]} from a models.dev payload (pi
   generateModels order: all model sources, context/cost overrides, missing
   gpt models, codex + the azure mirror, deepseek-v4 compat normalization,
   then the metadata passes)."
  [data]
  (let [all (->> (concat (process-opencode data)
                         (process-copilot data)
                         (process-xai data)
                         (process-openai data)
                         (map #(apply-thinking-maps % nil) deepseek-v4-models)
                         (map #(apply-thinking-maps % nil) (process-codex)))
                 (remove nil?))
        ;; Hardcoded fallbacks still get the thinking metadata rules (pi runs
        ;; applyThinkingLevelMetadata over allModels — the missing models
        ;; carry no models.dev reasoning_options, so no effort map).
        with-missing (concat all (map #(apply-thinking-maps % nil)
                                      (missing-openai-models (set (map :id all)))))
        normalized (map #(-> % normalize-openai normalize-context-overrides)
                        with-missing)
        ;; the azure mirror derives from the normalized openai models (pi:
        ;; after the section overrides, before the metadata passes) and gets
        ;; the thinking metadata rules on top (off:null for gpt-5* etc.)
        azure (map #(apply-thinking-maps % nil) (process-azure normalized))
        grouped (group-by :provider
                          (->> (concat normalized azure)
                               (map apply-compat-metadata)
                               (normalize-deepseek-v4)))]
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
                               [:input :output :cache-read :cache-write])
                       (let [tiers (get-in mm [:cost :tiers])]
                         (or (nil? tiers)
                             (and (vector? tiers)
                                  (every? (fn [tier]
                                            (and (map? tier)
                                                 (number? (get tier :input-tokens-above))
                                                 (pos? (get tier :input-tokens-above))
                                                 (every? (fn [k] (let [v (get tier k)]
                                                                   (and (number? v) (not (neg? v)))))
                                                         [:input :output :cache-read :cache-write])))
                                          tiers)))))
          (fail! label "has invalid cost (4 non-negative numeric fields + optional context tiers)"))))
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
         :provider :api :base-url :reasoning :thinking-level-map :input :cost
         :tiers :input-tokens-above
         :context-window :max-tokens :headers :compat
         :output :cache-read :cache-write
         :supports-store :supports-developer-role :supports-reasoning-effort
         :requires-reasoning-content-on-assistant-messages :thinking-format
         :max-tokens-field :session-affinity-format
         :supports-long-cache-retention :supports-strict-mode
         :supports-openai-grammar-tools :supports-tool-search
         :supports-additional-tools :supports-explicit-prompt-cache-mode
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
