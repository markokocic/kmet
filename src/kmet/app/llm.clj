(ns kmet.app.llm
  "LLM API client: OpenAI-completions, OpenAI Responses (incl. the codex/
   azure responses variants), Anthropic messages, and Google Generative AI
   — all with streaming.

   The resolved Model record is the unit of truth (pi): dispatch (wire api),
   endpoint URL, thinking shaping, max-token field, static headers and cost
   all derive from it. Every provider must have a catalog entry; unknown
   providers/models error out."
  (:require [clojure.string :as str]
            [cheshire.core :as json]
            [kmet.libs.sse :as sse]
            [kmet.libs.hash :as hash]
            [kmet.app.attribution :as attribution]
            [kmet.app.auth :as auth]
            [kmet.app.aws-sigv4 :as aws-sigv4]
            [kmet.app.config-value :as config-value]
            [kmet.app.google-adc :as google-adc]
            [kmet.app.models :as models]
            [kmet.app.proxy :as proxy]
            [kmet.app.session :as session]
            [kmet.app.tools.core :as tools]))

(def ^:private default-anthropic-version "2023-06-01")

(def ^:private codex-default-base-url
  "pi DEFAULT_CODEX_BASE_URL: the ChatGPT backend the codex endpoint
   appends /codex/responses to."
  "https://chatgpt.com/backend-api")

(def ^:private getenv
  "Process env lookup (System/getenv returns nil for unset vars)."
  (fn [k] (System/getenv k)))

(defn- ambient-auth-available?
  "True when a provider that resolves its own ambient auth (no api key)
   is configured — google-vertex ADC or amazon-bedrock AWS credentials
   (auth/ambient-configured?)."
  [provider]
  (auth/ambient-configured? provider))

;; ─── Wire api + URL construction ───────────────────────────────────────────

(defn- interpolate-base-url
  "Substitute the {CLOUDFLARE_ACCOUNT_ID} / {CLOUDFLARE_GATEWAY_ID}
   placeholders in a cloudflare base URL from the env (pi cloudflare-auth
   resolve — the account/gateway ids are ambient). A missing id is a
   configuration error, not a transport failure: pi's resolveCloudflareEnv
   returns undefined (not configured) unless all of api key + account id
   (+ gateway id for the gateway) are present."
  [base]
  (if (str/includes? base "{CLOUDFLARE_")
    (let [account (getenv "CLOUDFLARE_ACCOUNT_ID")
          gateway (getenv "CLOUDFLARE_GATEWAY_ID")
          missing (cond-> []
                    (and (str/includes? base "{CLOUDFLARE_ACCOUNT_ID}") (nil? account))
                    (conj "CLOUDFLARE_ACCOUNT_ID")
                    (and (str/includes? base "{CLOUDFLARE_GATEWAY_ID}") (nil? gateway))
                    (conj "CLOUDFLARE_GATEWAY_ID"))]
      (when (seq missing)
        (throw (ex-info (str "Cloudflare requires the env vars: "
                             (str/join ", " missing))
                        {:type :cloudflare-config-missing})))
      (-> base
          (str/replace "{CLOUDFLARE_ACCOUNT_ID}" account)
          (str/replace "{CLOUDFLARE_GATEWAY_ID}" gateway)))
    base))

(defn- endpoint-url
  "Full request URL from a wire api + API base + model id (pi: the SDK
   appends the endpoint path)."
  [api base model-id]
  (let [base (interpolate-base-url base)]
    (case api
      :openai-completions (str base "/chat/completions")
      :openai-responses (str base "/responses")
      :anthropic-messages (str base "/v1/messages")
      :google-generative-ai (str base "/models/" model-id ":streamGenerateContent?alt=sse")
      :mistral-conversations (str base "/v1/chat/completions"))))

(defn- codex-endpoint-url
  "pi resolveCodexUrl: the default codex base
   https://chatgpt.com/backend-api; the endpoint is base + /codex/responses
   (bases already ending in /codex or the full path pass through)."
  [base]
  (let [raw (if (str/blank? base) codex-default-base-url base)
        normalized (str/replace raw #"/+$" "")]
    (cond
      (str/ends-with? normalized "/codex/responses") normalized
      (str/ends-with? normalized "/codex") (str normalized "/responses")
      :else (str normalized "/codex/responses"))))

(defn- normalize-azure-base-url
  "pi normalizeAzureBaseUrl: Azure hosts (.openai.azure.com /
   .cognitiveservices.azure.com / .ai.azure.com) with a bare or /openai
   path are forced to /openai/v1 so the deployments path appends correctly."
  [base-url]
  (let [trimmed (str/replace (str/trim base-url) #"/+$" "")]
    (try
      (let [u (java.net.URI. trimmed)
            host (some-> u .getHost)
            path (some-> u .getPath (str/replace #"/+$" ""))]
        (if (and host
                 (or (str/ends-with? host ".openai.azure.com")
                     (str/ends-with? host ".cognitiveservices.azure.com")
                     (str/ends-with? host ".ai.azure.com"))
                 (contains? #{"" "/" "/openai" "/openai/v1/responses"} path))
          (str (java.net.URI. (.getScheme u) (.getUserInfo u) host (.getPort u)
                              "/openai/v1" nil nil))
          trimmed))
      (catch Exception _ trimmed))))

(def ^:private azure-default-api-version "v1")

(defn- azure-deployment-name
  "pi resolveDeploymentName (no per-request override in kmet): the model's
   deployment from the AZURE_OPENAI_DEPLOYMENT_NAME_MAP env var
   (modelId=deploymentName, comma-separated), else the model id."
  [model-id]
  (let [mapped (when-let [env (getenv "AZURE_OPENAI_DEPLOYMENT_NAME_MAP")]
                 (into {}
                       (keep (fn [entry]
                               (let [[mid dep] (str/split (str/trim entry) #"=" 2)]
                                 (when (and (seq mid) (seq dep))
                                   [(str/trim mid) (str/trim dep)]))))
                       (str/split env #",")))]
    (or (get mapped model-id) model-id)))

(defn- azure-resolved-config
  "pi resolveAzureConfig: base URL + api version for an azure request.
   Base precedence: AZURE_OPENAI_BASE_URL → AZURE_OPENAI_RESOURCE_NAME
   (https://<name>.openai.azure.com/openai/v1) → the model's base-url;
   api version: AZURE_OPENAI_API_VERSION → \"v1\". Throws when no base is
   configurable (the request reports it as a stream error)."
  [model-base-url]
  (let [api-version (or (getenv "AZURE_OPENAI_API_VERSION") azure-default-api-version)
        env-base (some-> (getenv "AZURE_OPENAI_BASE_URL") str/trim)
        resource (getenv "AZURE_OPENAI_RESOURCE_NAME")
        resolved (or (not-empty env-base)
                     (when (seq resource)
                       (str "https://" resource ".openai.azure.com/openai/v1"))
                     (when (seq model-base-url) model-base-url))]
    (when-not resolved
      (throw (ex-info "Azure OpenAI base URL is required. Set AZURE_OPENAI_BASE_URL or AZURE_OPENAI_RESOURCE_NAME."
                      {:type :azure-config-missing})))
    {:base-url (normalize-azure-base-url resolved)
     :api-version api-version}))

(defn- azure-endpoint-url
  "pi: base + /deployments/<deployment>/responses?api-version=<v> (the
   AzureOpenAI SDK appends the deployment path)."
  [base deployment api-version]
  (str base "/deployments/" deployment "/responses?api-version=" api-version))

(defn- request-headers
  "Request headers for one request: the provider-attribution layer (session
   headers + origin attribution, pi mergeProviderAttributionHeaders), then
   BASE (api-specific), the model's :headers (static builtin + models.edn
   model-level config values) and the provider's :configured-headers, all
   resolved as config values (literals pass through, $ENV interpolates,
   !command executes — pi resolveConfiguredModelHeaders; unresolvable values
   throw, reported via the builder's on-error). A provider with :auth-header
   adds Authorization: Bearer <api-key> last (pi withConfiguredAuth)."
  [base model provider api-key session-id]
  (let [attribution-hdrs (attribution/merge-provider-attribution-headers
                          model session-id)
        merged (merge attribution-hdrs
                      base
                      (config-value/resolve-headers-or-throw
                       (:headers model)
                       (str "model \"" (name (:provider model)) "/" (:id model) "\""))
                      (config-value/resolve-headers-or-throw
                       (:configured-headers provider)
                       (str "provider \"" (name (:id provider)) "\"")))]
    (cond-> merged
      (:auth-header provider) (assoc "Authorization" (str "Bearer " api-key)))))

;; ─── Thinking levels (pi: clampThinkingLevel / getSupportedThinkingLevels) ─

(def thinking-levels
  "All thinking levels in order (pi ThinkingLevel)."
  [:off :minimal :low :medium :high :xhigh :max])

(defn valid-thinking-level?
  "True when LEVEL is one of the thinking levels (pi isValidThinkingLevel)."
  [level]
  (some #{level} thinking-levels))

(defn get-supported-thinking-levels
  "Levels a model can express (pi getSupportedThinkingLevels — public for
   the /settings selector): non-reasoning models only :off; :xhigh/:max
   require an entry in the model's thinking-level-map; a null map value
   marks a level unsupported (absent entries are supported)."
  [model]
  (if-not (:reasoning model)
    [:off]
    (let [tlm (:thinking-level-map model)
          has-entry? #(and (map? tlm) (contains? tlm %))]
      (into []
            (for [level thinking-levels
                  :when (if (contains? #{:xhigh :max} level)
                          (and (has-entry? level) (some? (get tlm level)))
                          (not (and (has-entry? level) (nil? (get tlm level)))))]
              level)))))

(defn- clamp-thinking-level
  "pi clampThinkingLevel: the requested level when supported, else the
   nearest supported level (searching up from the request, then down), else
   the first supported level."
  [model level]
  (let [available (get-supported-thinking-levels model)]
    (if (some #{level} available)
      level
      (let [idx (first (keep-indexed (fn [i l] (when (= l level) i)) thinking-levels))]
        (if (nil? idx)
          (or (first available) :off)
          (or (first (filter (set available) (drop idx thinking-levels)))
              (first (filter (set available) (reverse (take idx thinking-levels))))
              (first available)
              :off))))))

(defn- effort-value
  "Wire reasoning_effort string for a level: the model's mapped value, else
   the level name (pi: model.thinkingLevelMap?.[level] ?? level)."
  [model level]
  (let [mapped (get-in model [:thinking-level-map level])]
    (if (nil? mapped) (name level) mapped)))

(defn- off-explicitly-null?
  "True when the model's thinking-level-map pins :off to null — the provider
   cannot disable thinking (pi: thinkingLevelMap?.off !== null gates the
   disabled-thinking params)."
  [model]
  (let [tlm (:thinking-level-map model)]
    (and (map? tlm) (contains? tlm :off) (nil? (:off tlm)))))

(defn- effective-effort
  "Clamped thinking level for the resolved model; nil when off (pi
   agent.ts: thinkingLevel === 'off' ? undefined : thinkingLevel, clamped by
   the model's capability)."
  [model thinking]
  (let [clamped (clamp-thinking-level model (or thinking :off))]
    (when-not (= :off clamped) clamped)))

;; ─── Message format conversion ─────────────────────────────────────────────

(defn- bash-execution-text
  "Bash result entry → LLM text (pi: bashExecutionToText)."
  [{:keys [command output exit-code cancelled truncated full-output-path]}]
  (let [output (or output "")
        base (str "Ran `" command "`\n"
                  (if (seq output)
                    (str "```\n" output "\n```")
                    "(no output)"))]
    (str base
         (when cancelled "\n\n(command cancelled)")
         (when (and (not cancelled) (some? exit-code) (not (zero? exit-code)))
           (str "\n\nCommand exited with code " exit-code))
         (when (and truncated full-output-path)
           (str "\n\n[Output truncated. Full output: " full-output-path "]")))))

(defn- content-text
  "Extract plain text from a message content block vector.
   A block has {:type :text :text \"...\"} or {:type \"text\" :text \"...\"}."
  [content]
  (str/join (for [b content
                  :when (or (= (:type b) :text)
                            (= (:type b) "text"))]
              (:text b))))

(defn- image-block?
  "True if a content block is an image block (kmet canonical
   {:type :image :data base64 :mime-type str}, matching pi's read tool
   {type: \"image\", data, mimeType} format)."
  [b]
  (or (= (:type b) :image) (= (:type b) "image")))

(defn- openai-content
  "Convert kmet content blocks to OpenAI content. Returns the plain text
   string when there are no image blocks (backward compat); with image blocks
   returns an array of text/image_url blocks (OpenAI vision format)."
  [content]
  (if (some image-block? content)
    (into []
          (for [b content]
            (if (image-block? b)
              {:type "image_url"
               :image_url {:url (str "data:" (:mime-type b) ";base64," (:data b))}}
              {:type "text" :text (or (:text b) "")})))
    (content-text content)))

(defn- tool-result-content
  "OpenAI tool result content: the text from the tool_result block, plus
   image_url blocks for any :images on the message (pi: the read tool returns
   image blocks inside the tool result content for vision models)."
  [m]
  (let [text (-> m :content first :content)
        images (:images m)]
    (if (seq images)
      (into [{:type "text" :text (or text "")}]
            (for [i images]
              {:type "image_url"
               :image_url {:url (str "data:" (:mime-type i) ";base64," (:data i))}}))
      text)))

(defn openai-messages
  "Map agent messages to OpenAI chat-completion messages.
   Bash entries become user messages (pi: convertToLlm bashExecution);
   excluded ones are dropped. Assistant messages with :thinking send it back
   as reasoning_content (pi: the thinking signature field — DeepSeek thinking
   mode round-trips the CoT). Tested directly by test_llm, hence public."
  [messages]
  (into []
        (keep (fn [m]
                (let [role (name (:role m))]
                  (case role
                    "bash"
                    (when-not (:exclude-from-context? m)
                      {:role "user" :content (bash-execution-text m)})
                    "tool"
                    {:role "tool"
                     :tool_call_id (-> m :content first :tool_use_id)
                     :content (tool-result-content m)}
                    "assistant"
                    (let [text (content-text (:content m))
                          thinking (str/trim (or (:thinking m) ""))
                          msg (cond-> {:role "assistant" :content text}
                                (:tool-calls m)
                                (assoc :tool_calls
                                       (mapv (fn [tc]
                                               {:id (:id tc)
                                                :type "function"
                                                :function {:name (:name tc)
                                                           :arguments (cheshire.core/generate-string
                                                                       (:arguments tc))}})
                                             (:tool-calls m))))]
                      (cond-> msg
                        (seq thinking) (assoc :reasoning_content thinking)))
                    ;; custom messages (pi: convertToLlm custom→user)
                    "custom"
                    {:role "user" :content (openai-content (:content m))}
                    {:role role
                     :content (openai-content (:content m))}))))
        messages))

(defn- openai-messages-with-reasoning
  "Like openai-messages but adds reasoning_content to assistant messages.
   Some providers (e.g., opencode-go/deepseek-v4-flash) require a
   reasoning_content field on assistant messages even when empty; a message's
   own :thinking is sent back verbatim (pi round-trips the thinking
   signature)."
  [messages]
  (into []
        (keep (fn [m]
                (let [role (name (:role m))
                      msg (case role
                            "bash"
                            (when-not (:exclude-from-context? m)
                              {:role "user" :content (bash-execution-text m)})
                            "tool"
                            {:role "tool"
                             :tool_call_id (-> m :content first :tool_use_id)
                             :content (tool-result-content m)}
                            "assistant"
                            (let [text (content-text (:content m))
                                  has-tc (seq (:tool-calls m))
                                  msg (cond-> {:role "assistant"}
                                        (seq text) (assoc :content text)
                                        has-tc (assoc :tool_calls
                                                      (mapv (fn [tc]
                                                              {:id (:id tc)
                                                               :type "function"
                                                               :function {:name (:name tc)
                                                                          :arguments (cheshire.core/generate-string
                                                                                      (:arguments tc))}})
                                                            (:tool-calls m))))]
                              ;; opencode-go requires reasoning_content on assistant messages
                              (assoc msg :reasoning_content (or (str/trim (or (:thinking m) "")) "")))
                            ;; custom messages (pi: convertToLlm custom→user)
                            "custom"
                            {:role "user"
                             :content (openai-content (:content m))}
                            {:role role
                             :content (openai-content (:content m))})]
                  msg)))
        messages))

(defn- anthropic-content-text
  "Extract plain text from Anthropic message content.
   Returns the content as-is if it is a string, otherwise joins text blocks."
  [content]
  (if (string? content)
    content
    (str/join (for [b content
                    :when (or (= (:type b) :text)
                              (= (:type b) "text"))]
                (:text b)))))

(defn- anthropic-content
  "Convert kmet content blocks to Anthropic content. Returns the plain text
   string when there are no image blocks (backward compat); with image blocks
   returns an array of text/image blocks (Anthropic vision format)."
  [content]
  (if (some image-block? content)
    (into []
          (for [b content]
            (if (image-block? b)
              {:type "image"
               :source {:type "base64"
                        :media_type (:mime-type b)
                        :data (:data b)}}
              {:type "text" :text (or (:text b) "")})))
    (anthropic-content-text content)))

(defn- anthropic-tool-result-content
  "Anthropic tool result content: the text from the tool_result block, plus
   image blocks for any :images on the message."
  [m]
  (let [text (-> m :content first :content)
        images (:images m)]
    (if (seq images)
      (into [{:type "text" :text (or text "")}]
            (for [i images]
              {:type "image"
               :source {:type "base64"
                        :media_type (:mime-type i)
                        :data (:data i)}}))
      text)))

(defn- anthropic-messages [messages]
  (into []
        (keep (fn [m]
                (let [role (name (:role m))]
                  (case role
                    "bash"
                    (when-not (:exclude-from-context? m)
                      {:role "user" :content (bash-execution-text m)})
                    "tool"
                    {:role "tool" :content (anthropic-tool-result-content m)}
                    ;; custom messages (pi: convertToLlm custom→user)
                    "custom"
                    {:role "user" :content (anthropic-content (:content m))}
                    (let [content (anthropic-content (:content m))]
                      (cond-> {:role role :content content}
                        (and (= role "assistant") (:tool-calls m))
                        (assoc :content
                               (vec (concat (if (string? (:content m)) [] (:content m))
                                            (mapv (fn [tc]
                                                    {:type "tool_use"
                                                     :id (:id tc)
                                                     :name (:name tc)
                                                     :input (:arguments tc)})
                                                  (:tool-calls m)))))))))))
        messages))

(defn- google-requires-tool-call-id?
  [model-id]
  (let [major (second (re-find #"(?i)^gemini(?:-live)?-(\d+)" model-id))]
    (or (str/starts-with? model-id "claude-")
        (str/starts-with? model-id "gpt-oss-")
        (and major (<= 3 (Long/parseLong major))))))

(defn- google-normalize-tool-call-id
  [id]
  (let [sanitized (str/replace (or id "") #"[^a-zA-Z0-9_-]" "_")]
    (if (> (count sanitized) 64) (subs sanitized 0 64) sanitized)))

(defn- google-messages
  [messages model]
  (let [requires-id? (google-requires-tool-call-id? (:id model))
        system (first (for [m messages
                            :when (= :system (:role m))]
                        (content-text (:content m))))
        msgs (remove #(= :system (:role %)) messages)]
    [(into []
           (keep (fn [m]
                   (case (name (:role m))
                     "bash"
                     (when-not (:exclude-from-context? m)
                       {:role "user" :parts [{:text (bash-execution-text m)}]})
                     "tool"
                     (let [r (first (:content m))
                           text (or (:content r) "")
                           resp (if (:is-error m) {:error text} {:output text})
                           fc (cond-> {:name (:tool-name m)
                                       :response resp}
                                requires-id? (assoc :id (google-normalize-tool-call-id
                                                         (-> m :content first :tool_use_id))))]
                       {:role "user" :parts [{:functionResponse fc}]})
                     "assistant"
                     (let [parts (into []
                                       (concat
                                        (for [b (:content m) :when (= :text (:type b))]
                                          {:text (:text b)})
                                        (for [tc (:tool-calls m)]
                                          {:functionCall (cond-> {:name (:name tc)
                                                                  :args (:arguments tc)}
                                                           requires-id? (assoc :id (google-normalize-tool-call-id (:id tc))))})))]
                       (when (seq parts) {:role "model" :parts parts}))
                     ;; user
                     (let [parts (if (some image-block? (:content m))
                                   (for [b (:content m)]
                                     (if (image-block? b)
                                       {:inlineData {:mime-type (:mime-type b)
                                                     :data (:data b)}}
                                       {:text (or (:text b) "")}))
                                   [{:text (content-text (:content m))}])]
                       {:role "user" :parts parts}))))
           msgs)
     system]))

;; ─── Thinking request shaping (pi per-api) ─────────────────────────────────

(defn- resolve-template-values
  "pi buildChatTemplateValues + resolveChatTemplateKwargValue: resolve the
   compat :chat-template-args/:chat-template-kwargs spec for one request —
   literals pass through, {$var: \"thinking.enabled\"} → the on/off boolean,
   other vars → the thinking-level-map value for the effort (or the :off
   value when off); a value with :omit-when-off is dropped when thinking is
   off. Returns nil when nothing resolves."
  [model effort values]
  (let [resolved (into {}
                       (keep (fn [[k v]]
                               (let [v (if (and (map? v) (:omit-when-off v) (nil? effort))
                                         nil
                                         (cond
                                           (and (map? v) (= "thinking.enabled" (:var v)))
                                           (boolean effort)

                                           (map? v)
                                           (let [mapped (get-in model [:thinking-level-map
                                                                       (or effort :off)])]
                                             (cond
                                               (nil? mapped) effort
                                               (string? mapped) mapped
                                               :else nil))

                                           :else v))]
                                 (when (some? v) [k v]))))
                       values)]
    (when (seq resolved) resolved)))

(defn- openai-thinking-params
  "Thinking params for an openai-completions payload (pi buildParams thinking
   section; kmet's formats: default/openai, deepseek, qwen, openrouter,
   zai, together, baseten, ant-ling, string-thinking, chat-template,
   qwen-chat-template). EFFORT is the clamped level, nil when off."
  [model effort]
  (let [reasoning? (:reasoning model)
        fmt (:thinking-format (:compat model))
        effort? (not= false (:supports-reasoning-effort (:compat model)))]
    (cond
      (and reasoning? (= fmt :openrouter))
      ;; OpenRouter normalizes reasoning across providers via a nested
      ;; reasoning object (pi thinkingFormat "openrouter"); off →
      ;; reasoning: {effort: "none"} unless the model pins :off to null.
      (cond-> {}
        effort (assoc :reasoning {:effort (effort-value model effort)})
        (and (nil? effort) (not (off-explicitly-null? model)))
        (assoc :reasoning {:effort (or (get-in model [:thinking-level-map :off]) "none")}))

      (and reasoning? (= fmt :deepseek))
      (cond-> {}
        effort (assoc :thinking {:type "enabled"})
        (and (nil? effort) (not (off-explicitly-null? model)))
        (assoc :thinking {:type "disabled"})
        (and effort effort?)
        (assoc :reasoning_effort (effort-value model effort)))

      (and reasoning? (= fmt :qwen))
      (cond-> {:enable_thinking (boolean effort)}
        (and effort effort?)
        (assoc :reasoning_effort (effort-value model effort)))

      (and reasoning? (= fmt :zai))
      ;; pi thinkingFormat "zai": thinking {type} + reasoning_effort when
      ;; the provider supports it (zai models always send the thinking
      ;; object — no off:null gate, matching pi).
      (cond-> {:thinking (if effort
                           {:type "enabled" :clear_thinking false}
                           {:type "disabled"})}
        (and effort effort?)
        (assoc :reasoning_effort (effort-value model effort)))

      (and reasoning? (= fmt :together))
      ;; pi thinkingFormat "together": nested reasoning: {enabled} +
      ;; reasoning_effort when supported.
      (cond-> {:reasoning {:enabled (boolean effort)}}
        (and effort effort?)
        (assoc :reasoning_effort (effort-value model effort)))

      (and reasoning? (= fmt :qwen-chat-template))
      {:chat_template_kwargs {:enable_thinking (boolean effort)
                              :preserve_thinking true}}

      (and reasoning? (= fmt :chat-template))
      (cond-> {}
        (seq (:chat-template-kwargs (:compat model)))
        (assoc :chat_template_kwargs
               (resolve-template-values model effort
                                        (:chat-template-kwargs (:compat model)))))

      (and reasoning? (= fmt :baseten))
      ;; pi thinkingFormat "baseten": chat_template_args from the compat
      ;; spec ($var thinking.enabled) + reasoning_effort when supported
      ;; (the off value from the thinking-level-map).
      (cond-> {}
        (seq (:chat-template-args (:compat model)))
        (assoc :chat_template_args
               (resolve-template-values model effort
                                        (:chat-template-args (:compat model))))
        (and effort effort?)
        (assoc :reasoning_effort (effort-value model effort))
        (and (nil? effort) (string? (get-in model [:thinking-level-map :off])))
        (assoc :reasoning_effort (get-in model [:thinking-level-map :off])))

      (and reasoning? (= fmt :ant-ling))
      ;; pi thinkingFormat "ant-ling": the branch is empty — Ring reasons
      ;; by default and ignores explicit effort; the trailing default
      ;; branches must not fire.
      {}

      (and reasoning? (= fmt :string-thinking))
      (cond-> {}
        effort (assoc :thinking (effort-value model effort))
        (and (nil? effort) (not (off-explicitly-null? model)))
        (assoc :thinking (or (get-in model [:thinking-level-map :off]) "none")))

      (and reasoning? effort effort?)
      {:reasoning_effort (effort-value model effort)}

      (and reasoning? (nil? effort) effort?)
      (let [off (get-in model [:thinking-level-map :off])]
        (if (string? off) {:reasoning_effort off} {}))

      :else {})))

(def ^:private thinking-budgets
  "pi adjustMaxTokensForThinking defaultBudgets (minimal/low/medium/high)."
  {:minimal 1024 :low 2048 :medium 8192 :high 16384})

(def ^:private min-answer-tokens 1024)

(defn- anthropic-adaptive-effort
  "pi mapThinkingLevelToEffort: the adaptive output_config effort — the
   model's thinking-level-map value when mapped, else the level collapsed to
   low/medium/high (xhigh/max → high)."
  [model effort]
  (let [mapped (get-in model [:thinking-level-map effort])]
    (cond
      (string? mapped) mapped
      (contains? #{:minimal :low} effort) "low"
      (= :medium effort) "medium"
      :else "high")))

(defn- anthropic-thinking
  "pi streamSimple thinking: the thinking config + max_tokens for an
   anthropic payload. EFFORT is the clamped level; nil when off.
   forceAdaptiveThinking models (kimi-coding, claude 4.6+ per pi) send
   thinking {type adaptive} + output_config {effort} instead of a budget.
   Returns nil when thinking is off and the model allows disabling."
  [model effort]
  (cond
    (not (:reasoning model)) nil
    effort
    (if (:force-adaptive-thinking (:compat model))
      {:thinking {:type "adaptive" :display "summarized"}
       :output_config {:effort (anthropic-adaptive-effort model effort)}
       :max-tokens (or (:max-tokens model) 4096)}
      (let [level (if (contains? #{:xhigh :max} effort) :high effort)
            budget (get thinking-budgets level 0)
            max-tokens (or (:max-tokens model) 4096)
            budget (min budget (max 0 (- max-tokens min-answer-tokens)))]
        {:thinking {:type "enabled" :budget_tokens budget :display "summarized"}
         :max-tokens max-tokens}))
    (off-explicitly-null? model) nil
    :else {:thinking {:type "disabled"}
           :max-tokens (or (:max-tokens model) 4096)}))

;; ─── OpenAI-completions request ────────────────────────────────────────────

(defn- max-tokens-key
  "Payload key for the model's max tokens (pi: compat.maxTokensField —
   :max_tokens when set, :max_completion_tokens by default)."
  [model]
  (if (= :max-tokens (:max-tokens-field (:compat model)))
    :max_tokens
    :max_completion_tokens))

(defn- usage-with-cost
  "Attach the per-message USD cost (pi: calculateCost runs in the wire API)
   to a provider-native usage map, computed from the Model record that
   produced the response. Usage maps without recognizable tokens pass
   through unchanged."
  [model-record usage]
  (if-let [norm (session/entry-usage usage)]
    (assoc usage :cost (models/calculate-cost model-record norm))
    usage))

(defn- responses-events-handler
  "The event dispatch shared by the responses-family request builders
   (openai-responses / azure / codex): text/thinking/tool-call deltas,
   done/usage/error."
  [{:keys [on-text on-thinking on-tool-call on-done on-error on-usage]} model-record]
  (fn [event]
    (case (:type event)
      :text (when on-text (on-text (:content event)))
      :thinking (when on-thinking (on-thinking (:content event)))
      :tool-call (when on-tool-call
                   (on-tool-call {:id (:id event)
                                  :name (:name event)
                                  :arguments (:arguments event)
                                  :index (:index event)}))
      :tool-call-args (when on-tool-call
                        (on-tool-call {:arguments (:arguments event)
                                       :index (:index event)}))
      :done (when on-done (on-done (:stop-reason event)))
      :usage (when on-usage (on-usage (usage-with-cost model-record (:usage event))))
      :error (when on-error (on-error (:message event)))
      nil)))

(def ^:private network-exception-classes
  "JVM exception classes indicating a transport/network failure (connect,
   DNS, timeout, reset). java.net.http can throw these with a nil message
   (e.g. ConnectException on this JDK), so they are classified by class."
  #{"ConnectException" "UnknownHostException" "NoRouteToHostException"
    "UnresolvedAddressException" "SocketTimeoutException"
    "HttpTimeoutException" "SocketException"})

(def ^:private http2-stream-reset-regex
  "Message pattern for HTTP/2 stream resets. java.net.http surfaces a server
   RST_STREAM frame as a plain java.io.IOException whose message is
   'Received RST_STREAM: <code>' (e.g. 'Protocol error', 'CANCEL') — the
   class is too broad to add to network-exception-classes, so these are
   classified by message."
  (re-pattern "(?i)received rst_stream"))

(defn- transport-error-message
  "Message for a transport-layer exception. Network failures carry a stable
   'network error' token so the loop's retry classifier (retryable-error?)
   recognizes them even when the JVM message is nil — 'Request failed:
   ConnectException' matches no retryable pattern, which silently kills
   auto-retry on connect/DNS failures (pi's undici always reports transport
   failures as 'fetch failed'). Non-network exceptions keep their message."
  [e]
  (let [msg (ex-message e)
        cls (some-> (class e) .getSimpleName)]
    (cond
      (contains? network-exception-classes cls)
      (str "network error: " (if (str/blank? msg) cls msg))

      ;; HTTP/2 RST_STREAM arrives as a plain IOException — same class of
      ;; transport reset as SocketException "Connection reset", so it gets
      ;; the same stable retryable token (pi: undici reports these as
      ;; 'fetch failed').
      (re-find http2-stream-reset-regex (or msg ""))
      (str "network error: " (or msg cls))

      (str/blank? msg)
      (str "Request failed: " cls)

      :else msg)))

(defn- openai-payload
  "Request body for an openai-completions request (pi buildParams):
   model/messages/stream/stream_options, tools, thinking params, the
   max-tokens field, then the model's :sampling-params merged last so their
   keys win over the named request fields (pi: Object.assign(params,
   samplingParams) after everything else — samplingParams is the single
   source of sampling truth for a model)."
  [model-record effort messages tools model-id]
  (let [thinking-params (openai-thinking-params model-record effort)
        max-tokens-field (max-tokens-key model-record)
        ;; pi: requiresReasoningContentOnAssistantMessages gates the
        ;; reasoning_content field (deepseek/opencode-go only)
        messages-fn (if (:requires-reasoning-content-on-assistant-messages
                         (:compat model-record))
                      openai-messages-with-reasoning
                      openai-messages)]
    (cond-> {:model model-id
             :messages (messages-fn messages)
             :stream true
             :stream_options {:include_usage true}}
      (seq tools) (assoc :tools (mapv tools/tool->openai-schema tools))
      (seq thinking-params) (merge thinking-params)
      (:max-tokens model-record) (assoc max-tokens-field (:max-tokens model-record))
      (seq (:sampling-params model-record)) (merge (:sampling-params model-record)))))

(defn- openai-request
  [{:keys [model-record provider-record effort api-key messages tools signal base-url
           idle-timeout-ms session-id
           on-text on-thinking on-tool-call on-done on-error
           on-usage] :as opts}]
  (future
    (try
      (let [model-id (or (:model opts) (:id model-record))
            url (or base-url (endpoint-url :openai-completions (:base-url model-record) model-id))
            payload (openai-payload model-record effort messages tools model-id)
            response (proxy/post-stream url
                                        {:headers (request-headers
                                                   {"Authorization" (str "Bearer " api-key)
                                                    "Content-Type" "application/json"}
                                                   model-record provider-record api-key
                                                   session-id)
                                         :body (json/generate-string payload)
                                         :as :stream
                                         ;; Total request deadline = the idle timeout (pi: SDK
                                         ;; timeoutMs ?? httpIdleTimeoutMs); nil when disabled
                                         :timeout (when (pos? (or idle-timeout-ms 0)) idle-timeout-ms)}
                                        signal)]
        (sse/process-openai-stream response
                                   (fn [event]
                                     (case (:type event)
                                       :text (when on-text (on-text (:content event)))
                                       :thinking (when on-thinking (on-thinking (:content event)))
                                       :tool-call (when on-tool-call
                                                    (on-tool-call {:id (:id event)
                                                                   :name (:name event)
                                                                   :arguments (:arguments event)
                                                                   :index (:index event)}))
                                       :tool-call-args (when on-tool-call
                                                         (on-tool-call {:arguments (:arguments event)
                                                                        :index (:index event)}))
                                       :done (when on-done (on-done (:stop-reason event)))
                                       :usage (when on-usage (on-usage (usage-with-cost model-record (:usage event))))
                                       :error (when on-error (on-error (:message event)))
                                       nil))
                                   signal
                                   idle-timeout-ms
                                   (fn [] (proxy/abort-stream! response)))
        (proxy/finish-curl! response signal on-error))
      (catch Exception e
        (when on-error (on-error (transport-error-message e)))))))

;; ─── OpenAI Responses request ─────────────────────────────────────────────

(defn- normalize-id-part
  "pi normalizeIdPart: sanitize a tool-call id to [a-zA-Z0-9_-], cap at 64
   chars, strip trailing underscores; nil for a blank id (omitted from the
   wire, like pi's `id: undefined`)."
  [s]
  (when (seq s)
    (let [sanitized (str/replace s #"[^a-zA-Z0-9_-]" "_")]
      (-> (if (> (count sanitized) 64) (subs sanitized 0 64) sanitized)
          (str/replace #"_+$" "")))))

(defn- responses-user-content
  "User content blocks → responses input content (input_text / input_image
   items; pi convertResponsesMessages)."
  [content]
  (if (some image-block? content)
    (into []
          (for [b content]
            (if (image-block? b)
              {:type "input_image" :detail "auto"
               :image_url (str "data:" (:mime-type b) ";base64," (:data b))}
              {:type "input_text" :text (or (:text b) "")})))
    [{:type "input_text" :text (content-text content)}]))

(defn- responses-tool-result-output
  "pi convertToolResultOutput: the joined tool-result text, plus
   input_image items when the model accepts images; otherwise the plain text
   with pi's image/output placeholders."
  [model m]
  (let [text (or (-> m :content first :content) "")
        images (:images m)]
    (if (and (seq images) (some #{:image} (:input model)))
      (into (if (seq text) [{:type "input_text" :text text}] [])
            (for [i images]
              {:type "input_image" :detail "auto"
               :image_url (str "data:" (:mime-type i) ";base64," (:data i))}))
      (cond
        (seq text) text
        (seq images) "(see attached image)"
        :else "(no tool output)"))))

(defn- responses-message-items
  "One agent message → responses input items (possibly none). pi
   convertResponsesMessages, simplified: no thinking-signature replay (kmet
   sessions don't persist signatures — omitted reasoning is legal with
   store: false) and no deferred/grammar tools (kmet always sends its tools
   up front). Assistant text blocks become message items (stable fallback
   ids), tool calls become function_call items whose call_id matches the
   tool result's; cross-provider ids (no `|` separator) replay as call_id
   only, like pi's different-model path (id omitted avoids the fc_/rs_
   pairing validation)."
  [model m msg-idx]
  (case (name (:role m))
    "bash"
    (when-not (:exclude-from-context? m)
      [{:role "user"
        :content [{:type "input_text" :text (bash-execution-text m)}]}])

    "tool"
    [{:type "function_call_output"
      :call_id (normalize-id-part (first (str/split (-> m :content first :tool_use_id) #"\|")))
      :output (responses-tool-result-output model m)}]

    "assistant"
    (let [blocks (into []
                       (concat
                        (for [[i b] (map-indexed vector (:content m))
                              :when (= :text (:type b))]
                          {:type "message" :role "assistant"
                           :content [{:type "output_text" :text (or (:text b) "")
                                      :annotations []}]
                           :status "completed"
                           :id (str "msg_pi_" msg-idx (when (pos? i) (str "_" i)))})
                        (for [tc (:tool-calls m)]
                          (let [[call-id item-id] (str/split (str (:id tc)) #"\|")]
                            {:type "function_call"
                             :call_id (normalize-id-part call-id)
                             :id (normalize-id-part item-id)
                             :name (:name tc)
                             :arguments (cheshire.core/generate-string (:arguments tc))}))))]
      (when (seq blocks) blocks))

    ;; custom messages (pi: convertToLlm custom→user)
    "custom"
    [{:role "user"
      :content [{:type "input_text" :text (content-text (:content m))}]}]

    [{:role "user" :content (responses-user-content (:content m))}]))

(defn- responses-messages
  "Map agent messages to OpenAI Responses input items (pi
   convertResponsesMessages): the system prompt becomes the first developer
   message (system when the model doesn't support the developer role) —
   unless INCLUDE-SYSTEM? is false, when the caller carries the system
   prompt itself (codex: the instructions field) — followed by the
   converted messages."
  [model messages & [include-system?]]
  (let [system (first (for [m messages :when (= :system (:role m))]
                        (content-text (:content m))))
        role (if (and (:reasoning model)
                      (not= false (:supports-developer-role (:compat model))))
               "developer" "system")]
    (loop [msgs (remove #(= :system (:role %)) messages)
           idx 0
           acc []]
      (if-let [m (first msgs)]
        (recur (rest msgs) (inc idx) (into acc (responses-message-items model m idx)))
        (into (if (and system (not= false include-system?))
                [{:role role :content system}]
                [])
              acc)))))

(defn- responses-tools
  "pi convertResponsesTools: flat function tools with the JSON schema; the
   strict flag is emitted when the provider accepts strict schemas (no
   grammar/custom tools — kmet has no constrained sampling)."
  [tools strict?]
  (mapv (fn [tool]
          (cond-> {:type "function"
                   :name (:name tool)
                   :description (:description tool)
                   :parameters (:parameters tool)}
            strict? (assoc :strict false)))
        tools))

(defn- copilot-vision-input?
  "pi hasCopilotVisionInput: any user or tool-result message carries image
   content (Copilot-Vision-Request header)."
  [messages]
  (boolean
   (some (fn [m]
           (case (name (:role m))
             "user" (some image-block? (:content m))
             "tool" (seq (:images m))
             false))
         messages)))

(defn- copilot-dynamic-headers
  "pi buildCopilotDynamicHeaders: per-request Copilot headers — X-Initiator
   (user vs agent, from the last message role) and Openai-Intent, plus
   Copilot-Vision-Request when any input has images."
  [messages]
  (let [last-role (some-> (peek (vec messages)) :role name)
        ;; pi inferCopilotInitiator: no messages → "user"
        initiator (if (= "user" last-role) "user" "agent")
        headers (array-map "X-Initiator" initiator
                           "Openai-Intent" "conversation-edits")]
    (cond-> headers
      (copilot-vision-input? messages) (assoc "Copilot-Vision-Request" "true"))))

(defn- responses-affinity-headers
  "pi createClient session-affinity headers from the session id (prompt
   caching): openai format sends session_id + x-client-request-id,
   openai-nosession (opencode zen) only x-client-request-id, openrouter
   x-session-id. A nil session-id (or cache-retention :none) → none."
  [model session-id]
  (when session-id
    (let [fmt (or (:session-affinity-format (:compat model))
                  (if (or (= :openrouter (:provider model))
                          (str/includes? (:base-url model) "openrouter.ai"))
                    :openrouter
                    :openai))]
      (case fmt
        :openrouter {"x-session-id" session-id}
        :openai {"session_id" session-id "x-client-request-id" session-id}
        ;; :openai-nosession — no session_id header (opencode zen)
        {"x-client-request-id" session-id}))))

(defn- clamp-prompt-cache-key
  "pi clampOpenAIPromptCacheKey: session ids longer than 64 chars are
   truncated (OpenAI's prompt_cache_key limit)."
  [session-id]
  (when session-id
    (let [chars (count session-id)]
      (if (<= chars 64) session-id (subs session-id 0 64)))))

(defn- responses-payload
  "Request body for an openai-responses request (pi buildParams):
   model/input/stream/store, tools, thinking (reasoning: {effort, summary}
   for a clamped level; reasoning: {effort: off-map-value} when thinking is
   disabled but the model allows it — :xhigh/:max and gpt-5* off:null
   models omit the param and think by default), include for the reasoning
   content (effort requests + xai always), max_output_tokens (min 16),
   prompt-cache params (key from the session id, a 24h retention when long
   and supported, explicit mode when caching is off on a cache-enabled
   model), then :sampling-params merged last so their keys win."
  [model-record effort messages tools model-id cache-retention session-id]
  (let [effort-reasoning (when (and (:reasoning model-record) effort)
                           {:effort (effort-value model-record effort)
                            :summary "auto"})
        off-reasoning (when (and (:reasoning model-record) (nil? effort)
                                 (not (off-explicitly-null? model-record)))
                        {:effort (or (get-in model-record [:thinking-level-map :off]) "none")})
        reasoning (or effort-reasoning off-reasoning)
        retention (or cache-retention :short)
        caching? (not= :none retention)
        compat (:compat model-record)]
    (cond-> {:model model-id
             :input (responses-messages model-record messages)
             :stream true
             :store false}
      (seq tools) (assoc :tools (responses-tools tools
                                                 (:supports-strict-mode compat)))
      (:max-tokens model-record) (assoc :max_output_tokens (max (:max-tokens model-record) 16))
      reasoning (assoc :reasoning reasoning)
      (or effort-reasoning
          (and (= :xai (:provider model-record)) (:reasoning model-record)))
      (assoc :include ["reasoning.encrypted_content"])
      (and caching? session-id) (assoc :prompt_cache_key (clamp-prompt-cache-key session-id))
      ;; pi getCompat: supportsLongCacheRetention defaults to true when absent
      (and (= :long retention) (not= false (:supports-long-cache-retention compat)))
      (assoc :prompt_cache_retention "24h")
      (and (= :none retention) (:supports-explicit-prompt-cache-mode compat))
      (assoc :prompt_cache_options {:mode "explicit"})
      (seq (:sampling-params model-record)) (merge (:sampling-params model-record)))))

(defn- responses-dynamic-headers
  "The per-request headers beyond the base: session-affinity (gated on
   caching like pi's cacheSessionId — :none sends neither the cache key nor
   the affinity headers) + the per-request Copilot dynamic headers."
  [model-record session-id retention messages]
  (merge (when (not= :none retention)
           (responses-affinity-headers model-record session-id))
         (when (= :github-copilot (:provider model-record))
           (copilot-dynamic-headers messages))))

(defn- responses-request-headers
  "The full header map for a responses request (pi createClient + the
   request-headers merge): responses-dynamic-headers, then request-headers'
   standard merge so the request's own headers win collisions."
  [model-record provider-record api-key session-id retention messages]
  (request-headers
   (merge (responses-dynamic-headers model-record session-id retention messages)
          {"Authorization" (str "Bearer " api-key)
           "Content-Type" "application/json"})
   model-record provider-record api-key session-id))

(defn- responses-request
  [{:keys [model-record provider-record effort api-key messages tools signal base-url
           idle-timeout-ms session-id cache-retention on-error]
    :as opts}]
  (future
    ;; the URL interpolation (cloudflare placeholders) can throw for a
    ;; missing env var — report it via on-error, never hang the caller
    (try
      (let [model-id (or (:model opts) (:id model-record))
            url (or base-url (endpoint-url :openai-responses (:base-url model-record) model-id))
            retention (or cache-retention :short)
            payload (responses-payload model-record effort messages tools model-id
                                       retention session-id)
            headers (responses-request-headers model-record provider-record api-key
                                               session-id retention messages)
            response (proxy/post-stream url
                                        {:headers headers
                                         :body (json/generate-string payload)
                                         :as :stream
                                         :timeout (when (pos? (or idle-timeout-ms 0)) idle-timeout-ms)}
                                        signal)]
        (sse/process-responses-stream response
                                      (responses-events-handler opts model-record)
                                      signal
                                      idle-timeout-ms
                                      (fn [] (proxy/abort-stream! response)))
        (proxy/finish-curl! response signal on-error))
      (catch Exception e
        (when on-error (on-error (transport-error-message e)))))))

;; ─── OpenAI Codex responses request (pi: api/openai-codex-responses.ts,  ──
;;    SSE path only — no WebSocket/zstd transports in kmet; the stream
;;    processor is shared with openai-responses) ───────────────────────────

(defn- codex-account-id
  "pi extractAccountId: the chatgpt_account_id claim from the access token's
   JWT payload (base64url decode of the middle segment). Throws when the
   token is not a JWT or carries no claim — the Codex backend requires the
   chatgpt-account-id header."
  [token]
  (let [[_ payload] (str/split token #"\.")
        decoded (try (-> (java.util.Base64/getUrlDecoder)
                         (.decode (or payload ""))
                         (String. "UTF-8"))
                     (catch Exception _ nil))
        claim (try (when decoded
                     (get (json/parse-string decoded)
                          "https://api.openai.com/auth"))
                   (catch Exception _ nil))
        account-id (when (map? claim)
                     (get claim "chatgpt_account_id"))]
    (when-not (and (string? decoded)
                   (string? account-id)
                   (seq account-id))
      (throw (ex-info "Failed to extract accountId from token"
                      {:type :codex-account-id})))
    account-id))

(defn- codex-request-headers
  "pi buildSSEHeaders + buildBaseCodexHeaders: the token as Authorization:
   Bearer, the chatgpt-account-id decoded from the token, originator + the
   pi User-Agent, OpenAI-Beta responses=experimental, plus session-id +
   x-client-request-id when prompt caching is on. SESSION-ID is the
   already-clamped cache key."
  [api-key session-id]
  (cond-> {"Authorization" (str "Bearer " api-key)
           "chatgpt-account-id" (codex-account-id api-key)
           "originator" "pi"
           "User-Agent" (str "pi (" (System/getProperty "os.name")
                             "; " (System/getProperty "os.arch") ")")
           "OpenAI-Beta" "responses=experimental"
           "Content-Type" "application/json"
           "Accept" "text/event-stream"}
    session-id (assoc "session-id" session-id
                      "x-client-request-id" session-id)))

(defn- codex-payload
  "pi buildRequestBody: the codex envelope over the shared responses
   messages/tools — the system prompt goes to the instructions field (not a
   developer message), text verbosity low, reasoning content always
   requested, tool_choice auto + parallel_tool_calls, the prompt-cache key
   when caching. Tools/reasoning/sampling-params merged after the envelope."
  [model-record effort messages tools model-id codex-session-id]
  (let [system (first (for [m messages :when (= :system (:role m))]
                        (content-text (:content m))))
        reasoning (when (and (:reasoning model-record) effort)
                    {:effort (effort-value model-record effort) :summary "auto"})]
    (cond-> {:model model-id
             :store false
             :stream true
             :instructions (or system "You are a helpful assistant.")
             :input (responses-messages model-record messages false)
             :text {:verbosity "low"}
             :include ["reasoning.encrypted_content"]
             :tool_choice "auto"
             :parallel_tool_calls true}
      codex-session-id (assoc :prompt_cache_key codex-session-id)
      (seq tools) (assoc :tools (responses-tools tools
                                                 (:supports-strict-mode (:compat model-record))))
      reasoning (assoc :reasoning reasoning)
      (seq (:sampling-params model-record)) (merge (:sampling-params model-record)))))

(defn- codex-request
  [{:keys [model-record provider-record effort api-key messages tools signal base-url
           idle-timeout-ms session-id cache-retention on-error]
    :as opts}]
  (future
    ;; the envelope computation (account-id decode, cache key) can throw for
    ;; a bad credential — report it via on-error like a transport failure
    ;; (pi surfaces it as a stream error), never hang the caller
    (try
      (let [model-id (or (:model opts) (:id model-record))
            retention (or cache-retention :short)
            codex-session-id (when (and (not= :none retention) session-id)
                               (clamp-prompt-cache-key session-id))
            payload (codex-payload model-record effort messages tools model-id
                                   codex-session-id)
            headers (request-headers
                     (codex-request-headers api-key codex-session-id)
                     model-record provider-record api-key session-id)
            response (proxy/post-stream (or base-url
                                            (codex-endpoint-url (:base-url model-record)))
                                        {:headers headers
                                         :body (json/generate-string payload)
                                         :as :stream
                                         :timeout (when (pos? (or idle-timeout-ms 0)) idle-timeout-ms)}
                                        signal)]
        (sse/process-responses-stream response
                                      (responses-events-handler opts model-record)
                                      signal
                                      idle-timeout-ms
                                      (fn [] (proxy/abort-stream! response)))
        (proxy/finish-curl! response signal on-error))
      (catch Exception e
        (when on-error (on-error (transport-error-message e)))))))

;; ─── Azure OpenAI responses request (pi: api/azure-openai-responses.ts —  ──
;;    shared responses processor; the deployment path + api version are
;;    env-derived) ─────────────────────────────────────────────────────────

(defn- azure-request
  [{:keys [model-record provider-record effort api-key messages tools signal base-url
           idle-timeout-ms session-id cache-retention on-error]
    :as opts}]
  (future
    ;; the config resolution (env base, deployment name) can throw when no
    ;; base is configurable — report it via on-error like a transport failure
    ;; (pi surfaces it as a stream error), never hang the caller
    (try
      (let [model-id (or (:model opts) (:id model-record))
            deployment (azure-deployment-name model-id)
            config (azure-resolved-config (:base-url model-record))
            retention (or cache-retention :short)
            url (or base-url
                    (azure-endpoint-url (:base-url config) deployment
                                        (:api-version config)))
            payload (responses-payload model-record effort messages tools deployment
                                       retention session-id)
            ;; azure sends no session-affinity headers (pi: the Azure client
            ;; sets none) — just the bearer + JSON content type
            headers (request-headers
                     {"Authorization" (str "Bearer " api-key)
                      "Content-Type" "application/json"}
                     model-record provider-record api-key session-id)
            response (proxy/post-stream url
                                        {:headers headers
                                         :body (json/generate-string payload)
                                         :as :stream
                                         :timeout (when (pos? (or idle-timeout-ms 0)) idle-timeout-ms)}
                                        signal)]
        (sse/process-responses-stream response
                                      (responses-events-handler opts model-record)
                                      signal
                                      idle-timeout-ms
                                      (fn [] (proxy/abort-stream! response)))
        (proxy/finish-curl! response signal on-error))
      (catch Exception e
        (when on-error (on-error (transport-error-message e)))))))

;; ─── Anthropic messages request ────────────────────────────────────────────

(defn- anthropic-auth-headers
  "Base auth headers for an anthropic-messages request (pi anthropic provider
   resolve): the resolved provider auth — when ANTHROPIC_AUTH_TOKEN wins the
   resolution order (no credential, no configured key) → Authorization:
   Bearer, else x-api-key with the resolved API key."
  [provider api-key]
  (if-let [t (:bearer (auth/resolve-provider-auth provider))]
    {"Authorization" (str "Bearer " t)}
    {"x-api-key" api-key}))

(defn- anthropic-request
  [{:keys [model-record provider-record effort api-key messages tools signal base-url
           idle-timeout-ms session-id
           on-text on-tool-call on-done on-error on-usage]
    :as opts}]
  (future
    (let [model-id (or (:model opts) (:id model-record))
          thinking (anthropic-thinking model-record effort)
          payload (cond-> {:model model-id
                           :max_tokens (:max-tokens thinking (or (:max-tokens model-record) 4096))
                           :messages (anthropic-messages messages)
                           :stream true}
                    (seq tools) (assoc :tools (mapv tools/tool->anthropic-schema tools))
                    (:thinking thinking) (assoc :thinking (:thinking thinking))
                    ;; adaptive thinking (pi forceAdaptiveThinking): the
                    ;; output_config effort rides alongside the thinking block
                    (:output_config thinking) (assoc :output_config (:output_config thinking)))]
      (try
        (let [response (proxy/post-stream (or base-url (endpoint-url :anthropic-messages (:base-url model-record) model-id))
                                          {:headers (request-headers
                                                     (merge {"anthropic-version" default-anthropic-version
                                                             "Content-Type" "application/json"}
                                                            (anthropic-auth-headers (:id provider-record) api-key))
                                                     model-record provider-record api-key
                                                     session-id)
                                           :body (json/generate-string payload)
                                           :as :stream
                                           ;; Total request deadline = the idle timeout (pi: SDK
                                           ;; timeoutMs ?? httpIdleTimeoutMs); nil when disabled
                                           :timeout (when (pos? (or idle-timeout-ms 0)) idle-timeout-ms)}
                                          signal)
              ;; curl-backed (SOCKS) responses: EOF without a message_stop is
              ;; a transport failure reported by finish-curl! — don't let it
              ;; surface as a fake :connection-closed success.
              curl-backed (some? (:proc response))]
          (sse/process-anthropic-stream response
                                        (fn [event]
                                          (case (:type event)
                                            :text (when on-text (on-text (:content event)))
                                            :tool-call (when on-tool-call
                                                         (on-tool-call {:id (:id event)
                                                                        :name (:name event)
                                                                        :arguments (:arguments event)}))
                                            :done (when (and on-done
                                                             (or (not curl-backed)
                                                                 (not= :connection-closed (:stop-reason event))))
                                                    (on-done (:stop-reason event)))
                                            :usage (when on-usage (on-usage (usage-with-cost model-record (:usage event))))
                                            :error (when on-error (on-error (:message event)))
                                            nil))
                                        signal
                                        idle-timeout-ms
                                        (fn [] (proxy/abort-stream! response)))
          (proxy/finish-curl! response signal on-error))
        (catch Exception e
          (when on-error (on-error (transport-error-message e))))))))

;; ─── Google Generative AI request ──────────────────────────────────────────

(defn- google-thinking-level
  "pi getThinkingLevel: gemini-3 pro collapses to LOW/HIGH; gemma4 to
   MINIMAL/HIGH; the default maps each level to its own wire value."
  [model effort]
  (let [id (:id model)
        pro? (re-matches #"(?i).*gemini-?3(?:\.\d+)?-pro.*" id)
        gemma? (re-matches #"(?i).*gemma-?4.*" id)]
    (cond
      (and pro? (contains? #{:minimal :low} effort)) "LOW"
      (and pro? (contains? #{:medium :high} effort)) "HIGH"
      (and gemma? (contains? #{:minimal :low} effort)) "MINIMAL"
      (and gemma? (contains? #{:medium :high} effort)) "HIGH"
      (= effort :minimal) "MINIMAL"
      (= effort :low) "LOW"
      (= effort :medium) "MEDIUM"
      :else "HIGH")))

(defn- google-thinking-budget
  "pi getGoogleBudget: 2.5-pro / 2.5-flash(-lite) budgets, -1 (dynamic)
   for other models. Only reached for non-gemini3/gemma4 models."
  [model effort]
  (let [id (:id model)]
    (cond
      (and (str/includes? id "2.5-pro") (= effort :minimal)) 128
      (and (str/includes? id "2.5-pro") (= effort :low)) 2048
      (and (str/includes? id "2.5-pro") (= effort :medium)) 8192
      (and (str/includes? id "2.5-pro") (= effort :high)) 32768
      (and (str/includes? id "2.5-flash-lite") (= effort :minimal)) 512
      (and (str/includes? id "2.5-flash-lite") (= effort :low)) 2048
      (and (str/includes? id "2.5-flash-lite") (= effort :medium)) 8192
      (and (str/includes? id "2.5-flash-lite") (= effort :high)) 24576
      (and (str/includes? id "2.5-flash") (= effort :minimal)) 128
      (and (str/includes? id "2.5-flash") (= effort :low)) 2048
      (and (str/includes? id "2.5-flash") (= effort :medium)) 8192
      (and (str/includes? id "2.5-flash") (= effort :high)) 24576
      :else -1)))

(defn- google-thinking-config
  "pi: gemini-3 pro/flash + gemma4 use thinkingLevel; other models use
   thinkingBudget (getGoogleBudget; -1 = dynamic). Returns nil when the
   model cannot think."
  [model effort]
  (when (:reasoning model)
    (if (or (re-matches #"(?i).*gemini-?3(?:\.\d+)?-pro.*" (:id model))
            (re-matches #"(?i).*gemini-?3(?:\.\d+)?-flash.*" (:id model))
            (re-matches #"(?i).*gemma-?4.*" (:id model)))
      (if effort
        {:includeThoughts true :thinkingLevel (google-thinking-level model effort)}
        {:thinkingLevel (if (re-matches #"(?i).*gemini-?3(?:\.\d+)?-pro.*" (:id model))
                          "LOW" "MINIMAL")})
      (if effort
        {:includeThoughts true :thinkingBudget (google-thinking-budget model effort)}
        {:thinkingBudget 0}))))

(defn- google-request
  [{:keys [model-record provider-record effort api-key messages tools signal base-url
           idle-timeout-ms session-id
           on-text on-thinking on-tool-call on-done on-error
           on-usage]
    :as opts}]
  (future
    (let [model-id (or (:model opts) (:id model-record))
          [contents system] (google-messages messages model-record)
          thinking-config (google-thinking-config model-record effort)
          payload (cond-> {:contents contents
                           :generationConfig (cond-> {}
                                               (:max-tokens model-record)
                                               (assoc :maxOutputTokens (:max-tokens model-record))
                                               thinking-config
                                               (assoc :thinkingConfig thinking-config))}
                    system (assoc :systemInstruction {:parts [{:text system}]})
                    (seq tools) (assoc :tools [{:functionDeclarations
                                                (mapv tools/tool->google-schema tools)}]))]
      (try
        (let [response (proxy/post-stream (or base-url (endpoint-url :google-generative-ai (:base-url model-record) model-id))
                                          {:headers (request-headers
                                                     {"x-goog-api-key" api-key
                                                      "Content-Type" "application/json"}
                                                     model-record provider-record api-key
                                                     session-id)
                                           :body (json/generate-string payload)
                                           :as :stream
                                           :timeout (when (pos? (or idle-timeout-ms 0)) idle-timeout-ms)}
                                          signal)]
          (sse/process-google-stream response
                                     (fn [event]
                                       (case (:type event)
                                         :text (when on-text (on-text (:content event)))
                                         :thinking (when on-thinking (on-thinking (:content event)))
                                         :tool-call (when on-tool-call
                                                      (on-tool-call {:id (:id event)
                                                                     :name (:name event)
                                                                     :arguments (:arguments event)
                                                                     :index (:index event)}))
                                         :done (when on-done (on-done (:stop-reason event)))
                                         :usage (when on-usage (on-usage (usage-with-cost model-record (:usage event))))
                                         :error (when on-error (on-error (:message event)))
                                         nil))
                                     signal
                                     idle-timeout-ms
                                     (fn [] (proxy/abort-stream! response)))
          (proxy/finish-curl! response signal on-error))
        (catch Exception e
          (when on-error (on-error (transport-error-message e))))))))

;; ─── Mistral request (pi: api/mistral-conversations.ts) ───────────────────

(def ^:private mistral-tool-call-id-length 9)

(defn- derive-mistral-tool-call-id
  "pi deriveMistralToolCallId: attempt 0 returns the id when it is already 9
   alphanumeric chars; otherwise a 9-char shortHash of the seed (attempts
   append :N to break collisions)."
  [id attempt]
  (let [normalized (str/replace id #"[^a-zA-Z0-9]" "")]
    (if (and (zero? attempt) (= mistral-tool-call-id-length (count normalized)))
      normalized
      (let [seed-base (if (seq normalized) normalized id)
            seed (if (zero? attempt) seed-base (str seed-base ":" attempt))]
        (-> (hash/short-hash seed)
            (str/replace #"[^a-zA-Z0-9]" "")
            (subs 0 mistral-tool-call-id-length))))))

(defn- make-mistral-tool-call-id-normalizer
  "pi createMistralToolCallIdNormalizer: per-request map from session tool
   call ids to 9-char alphanumeric ids (collisions resolved by appending
   attempt counters)."
  []
  (let [id-map (atom {})
        reverse-map (atom {})]
    (fn [id]
      (if-let [existing (get @id-map id)]
        existing
        (loop [attempt 0]
          (let [candidate (derive-mistral-tool-call-id id attempt)
                owner (get @reverse-map candidate)]
            (if (and owner (not= owner id))
              (recur (inc attempt))
              (do (swap! id-map assoc id candidate)
                  (swap! reverse-map assoc candidate id)
                  candidate))))))))

(defn- mistral-tool-result-text
  "pi buildToolResultText: the tool text with the error prefix; image-only
   results degrade to pi's placeholder strings."
  [text has-images? supports-images? is-error?]
  (let [trimmed (str/trim text)
        error-prefix (when is-error? "[tool error] ")]
    (cond
      (seq trimmed)
      (str error-prefix trimmed
           (when (and has-images? (not supports-images?))
             "\n[tool image omitted: model does not support images]"))
      has-images?
      (if supports-images?
        (if is-error? "[tool error] (see attached image)" "(see attached image)")
        (if is-error?
          "[tool error] (image omitted: model does not support images)"
          "(image omitted: model does not support images)"))
      :else (if is-error? "[tool error] (no tool output)" "(no tool output)"))))

(defn- mistral-messages
  "Map agent messages to Mistral chat messages (pi toChatMessages): user
   content with text/image_url parts, assistant content with thinking +
   tool_calls (ids normalized to 9-char alphanumeric), tool results with
   toolCallId + text/image parts. Bash entries become user messages; custom
   messages map to user (pi convertToLlm)."
  [messages model normalize-id]
  (let [supports-images? (some #{:image} (:input model))]
    (into []
          (keep (fn [m]
                  (let [role (name (:role m))]
                    (case role
                      "bash"
                      (when-not (:exclude-from-context? m)
                        {:role "user" :content (bash-execution-text m)})
                      "system"
                      {:role "system" :content (content-text (:content m))}
                      "tool"
                      (let [text (or (-> m :content first :content) "")
                            images (:images m)
                            parts (into [{:type "text"
                                          :text (mistral-tool-result-text text (seq images)
                                                                          supports-images? (:is-error m))}]
                                        (when (and supports-images? (seq images))
                                          (for [i images]
                                            {:type "image_url"
                                             :image_url (str "data:" (:mime-type i) ";base64," (:data i))})))]
                        {:role "tool"
                         :tool_call_id (normalize-id (or (-> m :content first :tool_use_id) ""))
                         :name (:tool-name m)
                         :content parts})
                      "assistant"
                      (let [parts (into []
                                        (concat
                                         (when-let [t (not-empty (content-text (:content m)))]
                                           [{:type "text" :text t}])
                                         (when-let [th (not-empty (str/trim (or (:thinking m) "")))]
                                           [{:type "thinking"
                                             :thinking [{:type "text" :text th}]}])))
                            tool-calls (mapv (fn [tc]
                                               {:id (normalize-id (:id tc))
                                                :type "function"
                                                :function {:name (:name tc)
                                                           :arguments (json/generate-string (:arguments tc))}
                                                :index 0})
                                             (:tool-calls m))]
                        (when (or (seq parts) (seq tool-calls))
                          (cond-> {:role "assistant"}
                            (seq parts) (assoc :content parts)
                            (seq tool-calls) (assoc :tool_calls tool-calls))))
                      ;; custom + user
                      (let [had-images? (some image-block? (:content m))
                            parts (remove nil?
                                          (for [b (:content m)]
                                            (cond
                                              (= :text (:type b)) {:type "text" :text (:text b)}
                                              (and (image-block? b) supports-images?)
                                              {:type "image_url"
                                               :image_url (str "data:" (:mime-type b) ";base64," (:data b))}
                                              :else nil)))]
                        (cond
                          (seq parts) {:role "user" :content parts}
                          had-images? {:role "user"
                                       :content [{:type "text"
                                                  :text "(image omitted: model does not support images)"}]}
                          :else nil))))))
          messages)))

(defn- mistral-uses-reasoning-effort?
  "pi usesReasoningEffort: models that expose the reasoning_effort option
   (the others use prompt_mode: \"reasoning\")."
  [model]
  (contains? #{"mistral-small-2603" "mistral-small-latest" "mistral-medium-3.5"}
             (:id model)))

(defn- mistral-thinking
  "pi streamSimple thinking: prompt_mode \"reasoning\" for models without a
   reasoning_effort option; reasoning_effort (tlm-mapped ?? \"high\") for
   the effort models. EFFORT is the clamped level, nil when off."
  [model effort]
  (cond
    (not (and (:reasoning model) effort)) {}
    (mistral-uses-reasoning-effort? model)
    {:reasoning_effort (or (get-in model [:thinking-level-map effort]) "high")}
    :else {:prompt_mode "reasoning"}))

(defn- mistral-tool
  "pi toFunctionTools: Mistral function tool with the JSON schema and
   strict: false (kmet has no constrained sampling)."
  [tool]
  {:type "function"
   :function {:name (:name tool)
              :description (:description tool)
              :parameters (:parameters tool)
              :strict false}})

(defn- mistral-payload
  "Mistral chat payload (pi buildChatPayload + toMistralWirePayload — the
   camelCase options are remapped to their snake_case wire names: maxTokens
   → max_tokens, reasoningEffort → reasoning_effort, promptMode →
   prompt_mode, promptCacheKey → prompt_cache_key)."
  [model-record effort messages tools model-id session-id cache-retention]
  (let [thinking (mistral-thinking model-record effort)]
    (cond-> {:model model-id
             :stream true
             :messages messages}
      (seq tools) (assoc :tools (mapv mistral-tool tools))
      (seq thinking) (merge thinking)
      (:max-tokens model-record) (assoc :max_tokens (:max-tokens model-record))
      (and (not= :none cache-retention) (seq session-id))
      (assoc :prompt_cache_key session-id))))

(defn- mistral-request
  [{:keys [model-record provider-record effort api-key messages tools signal base-url
           idle-timeout-ms session-id cache-retention on-error]
    :as opts}]
  (future
    (try
      (let [model-id (or (:model opts) (:id model-record))
            url (or base-url (endpoint-url :mistral-conversations (:base-url model-record) model-id))
            payload (mistral-payload model-record effort
                                     (mistral-messages messages model-record
                                                       (make-mistral-tool-call-id-normalizer))
                                     tools model-id session-id cache-retention)
            base-headers (request-headers
                          {"Authorization" (str "Bearer " api-key)
                           "Content-Type" "application/json"
                           "Accept" "text/event-stream"}
                          model-record provider-record api-key session-id)
            ;; pi: x-affinity session header when caching, unless the model
            ;; or request headers already set it explicitly
            headers (if (and (not= :none cache-retention) (seq session-id)
                             (not-any? #(str/includes? (str/lower-case (name %)) "x-affinity")
                                       (keys base-headers)))
                      (assoc base-headers "x-affinity" session-id)
                      base-headers)
            response (proxy/post-stream url
                                        {:headers headers
                                         :body (json/generate-string payload)
                                         :as :stream
                                         :timeout (when (pos? (or idle-timeout-ms 0)) idle-timeout-ms)}
                                        signal)]
        (sse/process-mistral-stream response
                                    (responses-events-handler opts model-record)
                                    signal
                                    idle-timeout-ms
                                    (fn [] (proxy/abort-stream! response)))
        (proxy/finish-curl! response signal on-error))
      (catch Exception e
        (when on-error (on-error (transport-error-message e)))))))

;; ─── Google Vertex request (pi: api/google-vertex.ts) ──────────────────────

(def ^:private vertex-base-url
  "The Vertex endpoint template (pi VERTEX_BASE_URL — the SDK substitutes
   project/location; kmet constructs the URL itself)."
  "https://{location}-aiplatform.googleapis.com")

(defn- vertex-endpoint-url
  "pi: the Vertex streamGenerateContent URL with project/location from the
   env (GOOGLE_CLOUD_PROJECT / GCLOUD_PROJECT / GOOGLE_CLOUD_LOCATION). A
   model base-url containing {location} (or empty) resolves the location
   from the env; any other base-url is used verbatim (custom endpoints)."
  [model-base-url model-id]
  (let [project (or (getenv "GOOGLE_CLOUD_PROJECT") (getenv "GCLOUD_PROJECT"))
        location (getenv "GOOGLE_CLOUD_LOCATION")]
    (when-not (seq project)
      (throw (ex-info "Vertex AI requires a project ID. Set GOOGLE_CLOUD_PROJECT/GCLOUD_PROJECT."
                      {:type :vertex-config-missing})))
    (when-not (seq location)
      (throw (ex-info "Vertex AI requires a location. Set GOOGLE_CLOUD_LOCATION."
                      {:type :vertex-config-missing})))
    (let [base (if (str/includes? (or model-base-url "") "{location}")
                 (str/replace vertex-base-url "{location}" location)
                 model-base-url)]
      (str base "/v1/projects/" project "/locations/" location
           "/publishers/google/models/" model-id ":streamGenerateContent?alt=sse"))))

(defn- vertex-request
  [{:keys [model-record provider-record effort api-key messages tools signal base-url
           idle-timeout-ms session-id on-error]
    :as opts}]
  (future
    (let [model-id (or (:model opts) (:id model-record))
          [contents system] (google-messages messages model-record)
          thinking-config (google-thinking-config model-record effort)
          payload (cond-> {:contents contents
                           :generationConfig (cond-> {}
                                               (:max-tokens model-record)
                                               (assoc :maxOutputTokens (:max-tokens model-record))
                                               thinking-config
                                               (assoc :thinkingConfig thinking-config))}
                    system (assoc :systemInstruction {:parts [{:text system}]})
                    (seq tools) (assoc :tools [{:functionDeclarations
                                                (mapv tools/tool->google-schema tools)}]))
          ;; auth: GOOGLE_CLOUD_API_KEY (x-goog-api-key) or ADC
          ;; (Authorization: Bearer — the token is fetched + cached here)
          api-key (or api-key (auth/resolve-api-key :google-vertex))
          auth-header (if api-key "x-goog-api-key" "Authorization")
          auth-value (or api-key (google-adc/access-token!))]
      (if-not auth-value
        (when on-error
          (on-error (str "No API key for google-vertex. Set GOOGLE_CLOUD_API_KEY "
                         "or configure Application Default Credentials.")))
        (try
          (let [response (proxy/post-stream (or base-url (vertex-endpoint-url (:base-url model-record) model-id))
                                            {:headers (request-headers
                                                       {auth-header (str (when-not api-key "Bearer ") auth-value)
                                                        "Content-Type" "application/json"}
                                                       model-record provider-record api-key session-id)
                                             :body (json/generate-string payload)
                                             :as :stream
                                             :timeout (when (pos? (or idle-timeout-ms 0)) idle-timeout-ms)}
                                            signal)]
            (sse/process-google-stream response
                                       (responses-events-handler opts model-record)
                                       signal
                                       idle-timeout-ms
                                       (fn [] (proxy/abort-stream! response)))
            (proxy/finish-curl! response signal on-error))
          (catch Exception e
            (when on-error (on-error (transport-error-message e)))))))))

;; ─── AWS Bedrock request (pi: api/bedrock-converse-stream.ts; SigV4 ───────
;;    signing + the binary ConverseStream frames replace the AWS SDK) ──────

(defn- bedrock-is-claude?
  "pi isAnthropicClaudeModel: id/name mention Anthropic Claude (also matches
   application inference profiles whose ARNs don't contain the name)."
  [model]
  (let [id (str/lower-case (:id model))
        name (str/lower-case (or (:name model) ""))]
    (or (str/includes? id "anthropic.claude")
        (str/includes? id "anthropic/claude")
        (str/includes? name "anthropic.claude")
        (str/includes? name "anthropic/claude")
        (str/includes? name "claude"))))

(defn- bedrock-model-candidates
  "id and normalized name (pi getModelMatchCandidates — application
   inference profiles may carry the model name only in :name)."
  [model]
  (let [id (str/lower-case (:id model))
        name (str/lower-case (or (:name model) ""))]
    (into [id] (when (seq name) [(str/replace name #"[\s_.:]+" "-")]))))

(defn- bedrock-supports-adaptive-thinking?
  "pi supportsAdaptiveThinking: Opus 4.6+/Sonnet 4.6/Fable 5 (id AND name)."
  [model]
  (let [adaptive? #(or (str/includes? % "opus-4-6") (str/includes? % "opus-4-7")
                       (str/includes? % "opus-4-8") (str/includes? % "opus-5")
                       (str/includes? % "sonnet-4-6") (str/includes? % "sonnet-5")
                       (str/includes? % "fable-5"))]
    (boolean (some adaptive? (bedrock-model-candidates model)))))

(defn- bedrock-supports-prompt-caching?
  "pi supportsPromptCaching: Claude 3.5 Haiku / 3.7 Sonnet / 4.x / 5 (or
   AWS_BEDROCK_FORCE_CACHE=1 for application inference profiles)."
  [model]
  (let [candidates (bedrock-model-candidates model)]
    (cond
      (not (some #(str/includes? % "claude") candidates))
      (= "1" (getenv "AWS_BEDROCK_FORCE_CACHE"))
      (some #(or (str/includes? % "fable-5") (str/includes? % "opus-5")
                 (str/includes? % "sonnet-5"))
            candidates)
      true
      (some #(str/includes? % "-4-") candidates)
      true
      (some #(str/includes? % "claude-3-7-sonnet") candidates)
      true
      (some #(str/includes? % "claude-3-5-haiku") candidates)
      true
      :else false)))

(defn- bedrock-image-block
  "pi createImageBlock: the Converse image block (source.bytes carries the
   base64 string — the wire format's bytes field is base64-encoded text)."
  [mime-type data]
  {:image {:format (case mime-type
                     "image/jpeg" "jpeg"
                     "image/jpg" "jpeg"
                     "image/png" "png"
                     "image/gif" "gif"
                     "image/webp" "webp"
                     (throw (ex-info (str "Unknown image type: " mime-type)
                                     {:type :bedrock-image-type})))
           :source {:bytes data}}})

(def ^:private bedrock-empty-text-placeholder "<empty>")

(defn- bedrock-text-block
  "A non-blank text block; nil when the text is blank (pi
   createNonBlankTextBlock)."
  [text]
  (when (seq (str/trim (or text "")))
    {:text text}))

(defn- bedrock-tool-result-content
  "Tool result content blocks (pi convertToolResultContent): text blocks
   plus image blocks; an empty result degrades to the <empty> placeholder."
  [text images]
  (let [blocks (into (if (seq (str/trim (or text ""))) [{:text text}] [])
                     (for [i images]
                       (bedrock-image-block (:mime-type i) (:data i))))]
    (if (seq blocks) blocks [{:text bedrock-empty-text-placeholder}])))

(defn- bedrock-messages
  "ConverseStream messages (pi convertMessages): user content with
   text/image blocks (blank text degrades to the <empty> placeholder),
   assistant content with text/toolUse/reasoningContent blocks, consecutive
   tool results merged into one user message with toolResult blocks. A
   cache point is appended to the last user message for cache-capable
   Claude models when caching is enabled.

   Returns [messages system-blocks] — the system prompt (pi
   buildSystemPrompt) carries its own cache point."
  [messages model cache-retention]
  (let [system (first (for [m messages
                            :when (= :system (:role m))]
                        (content-text (:content m))))
        caching? (and (not= :none cache-retention)
                      (bedrock-supports-prompt-caching? model))
        cache-block {:cachePoint (cond-> {:type "default"}
                                   (= :long cache-retention) (assoc :ttl "1h"))}
        system-blocks (when (seq system)
                        (cond-> [{:text system}]
                          caching? (conj cache-block)))
        result (loop [msgs (remove #(= :system (:role %)) messages)
                      out []]
                 (if-let [m (first msgs)]
                   (let [role (name (:role m))]
                     (cond
                       (= role "bash")
                       (if (:exclude-from-context? m)
                         (recur (rest msgs) out)
                         (recur (rest msgs)
                                (conj out {:role "user"
                                           :content [{:text (bash-execution-text m)}]})))
                       (= role "tool")
                       ;; merge consecutive tool results into one user message
                       (let [tool-results (take-while #(= :tool (:role %)) msgs)
                             blocks (mapv (fn [tr]
                                            {:toolResult {:toolUseId (-> tr :content first :tool_use_id)
                                                          :content (bedrock-tool-result-content
                                                                    (or (-> tr :content first :content) "")
                                                                    (:images tr))
                                                          :status (if (:is-error tr) "error" "success")}})
                                          tool-results)]
                         (recur (drop (count tool-results) msgs)
                                (conj out {:role "user" :content blocks})))
                       (= role "assistant")
                       (let [content-blocks (into []
                                                  (concat
                                                   (keep (fn [b]
                                                           (when (= :text (:type b))
                                                             (bedrock-text-block (:text b))))
                                                         (:content m))
                                                   (for [tc (:tool-calls m)]
                                                     {:toolUse {:toolUseId (:id tc)
                                                                :name (:name tc)
                                                                :input (:arguments tc {})}})
                                                   ;; kmet stores thinking as text only — no
                                                   ;; reasoning signatures — so Claude's replayed
                                                   ;; reasoning falls back to plain text (pi's
                                                   ;; no-signature path: Bedrock rejects a
                                                   ;; reasoningContent block without a signature);
                                                   ;; non-Claude models take reasoningContent
                                                   ;; without a signature (pi)
                                                   (when-let [th (not-empty (str/trim (or (:thinking m) "")))]
                                                     (if (bedrock-is-claude? model)
                                                       [{:text th}]
                                                       [{:reasoningContent {:reasoningText {:text th}}}]))))]
                         (if (seq content-blocks)
                           (recur (rest msgs) (conj out {:role "assistant" :content content-blocks}))
                           (recur (rest msgs) out)))
                       :else
                       ;; user
                       (let [content (:content m)
                             blocks (if (string? content)
                                      [{:text content}]
                                      (let [blocks (into []
                                                         (keep (fn [b]
                                                                 (cond
                                                                   (= :text (:type b)) (bedrock-text-block (:text b))
                                                                   (image-block? b) (bedrock-image-block (:mime-type b) (:data b))
                                                                   :else nil)))
                                                         content)]
                                        (if (seq blocks) blocks [{:text bedrock-empty-text-placeholder}])))]
                         (recur (rest msgs) (conj out {:role "user" :content blocks})))))
                   out))]
    [(cond-> result
       (and caching? (seq result)
            (= "user" (get-in result [(dec (count result)) :role])))
       (update (dec (count result)) update :content conj cache-block))
     system-blocks]))

(defn- bedrock-tool-config
  "ConverseStream toolConfig (pi convertToolConfig): toolSpec blocks with
   the JSON input schema; toolChoice auto when tools are present."
  [tools]
  (when (seq tools)
    {:tools (mapv (fn [tool]
                    {:toolSpec {:name (:name tool)
                                :description (:description tool)
                                :inputSchema {:json (:parameters tool)}
                                :strict false}})
                  tools)
     :toolChoice {:auto {}}}))

(defn- bedrock-additional-fields
  "pi buildAdditionalModelRequestFields: the thinking config for Claude
   models — adaptive (opus-4.6+/sonnet-4.6+/fable-5) or budget-based with
   the interleaved-thinking beta; non-Claude models get no thinking.
   EFFORT is the clamped level, nil when off."
  [model effort]
  (when (and (:reasoning model) effort (bedrock-is-claude? model))
    (let [display "summarized"]
      (if (bedrock-supports-adaptive-thinking? model)
        {:thinking {:type "adaptive" :display display}
         :output_config {:effort (anthropic-adaptive-effort model effort)}}
        (let [level (if (contains? #{:xhigh :max} effort) :high effort)
              budget (min (get thinking-budgets level 0)
                          (max 0 (- (or (:max-tokens model) 4096) min-answer-tokens)))]
          {:thinking {:type "enabled" :budget_tokens budget :display display}
           :anthropic_beta ["interleaved-thinking-2025-05-14"]})))))

(defn- bedrock-endpoint-url
  "pi: the ConverseStream URL. Standard bedrock-runtime endpoints are used
   as-is unless a region or ambient AWS_PROFILE overrides them (then the
   regional endpoint wins); custom endpoints always win."
  [model-base-url model-id]
  (let [endpoint-region (when-let [host (some-> (java.net.URI. model-base-url) .getHost)]
                          (second (re-find #"(?i)^bedrock-runtime(?:-fips)?\.([a-z0-9-]+)\.amazonaws\.com(?:\.cn)?$"
                                           host)))
        configured-region (or (getenv "AWS_REGION") (getenv "AWS_DEFAULT_REGION"))
        ambient-profile? (boolean (getenv "AWS_PROFILE"))
        explicit? (or (nil? endpoint-region)
                      (and (nil? configured-region) (not ambient-profile?)))
        region (if explicit?
                 (or endpoint-region configured-region "us-east-1")
                 (or configured-region "us-east-1"))]
    {:url (if explicit?
            (str model-base-url "/model/" model-id "/converse-stream")
            (str "https://bedrock-runtime." region ".amazonaws.com/model/" model-id "/converse-stream"))
     :region region}))

(defn- bedrock-request
  [{:keys [model-record provider-record effort api-key messages tools signal base-url
           idle-timeout-ms session-id cache-retention on-error]
    :as opts}]
  (future
    (try
      (let [model-id (or (:model opts) (:id model-record))
            retention (or cache-retention :short)
            {:keys [url region]} (if base-url
                                   {:url (str base-url "/model/" model-id "/converse-stream")
                                    :region (or (getenv "AWS_REGION") "us-east-1")}
                                   (bedrock-endpoint-url (:base-url model-record) model-id))
            bearer (or api-key (getenv "AWS_BEARER_TOKEN_BEDROCK"))
            skip-auth? (= "1" (getenv "AWS_BEDROCK_SKIP_AUTH"))
            creds (when-not (or bearer skip-auth?)
                    (aws-sigv4/resolve-credentials))]
        (if-not (or bearer creds skip-auth?)
          (when on-error
            (on-error (str "No API key for amazon-bedrock. Set AWS_ACCESS_KEY_ID + "
                           "AWS_SECRET_ACCESS_KEY, AWS_PROFILE, AWS_BEARER_TOKEN_BEDROCK, "
                           "or configure AWS credentials.")))
          (let [additional (bedrock-additional-fields model-record effort)
                [msgs system-blocks] (bedrock-messages messages model-record retention)
                payload (json/generate-string
                         (cond-> {:modelId model-id
                                  :messages msgs
                                  :inferenceConfig (cond-> {}
                                                     (and (bedrock-is-claude? model-record)
                                                          (:max-tokens model-record))
                                                     (assoc :maxTokens (:max-tokens model-record)))}
                           (seq system-blocks) (assoc :system system-blocks)
                           (seq tools) (assoc :toolConfig (bedrock-tool-config tools))
                           (seq additional) (assoc :additionalModelRequestFields additional)))
                sha (aws-sigv4/sha256-hex payload)
                ;; the request's own headers (attribution + configured) first,
                ;; then the SigV4/bearer headers — AWS requires content-type
                ;; in the signature and reserved headers (authorization,
                ;; x-amz-*) must never be overridden by caller headers (pi
                ;; addCustomHeadersMiddleware skips them)
                headers (merge (request-headers {"Content-Type" "application/json"}
                                                model-record provider-record api-key session-id)
                               (if (and bearer (not skip-auth?))
                                 {"Authorization" (str "Bearer " bearer)}
                                 (aws-sigv4/sign-request {:method "POST"
                                                          :url url
                                                          :region region
                                                          :service "bedrock"
                                                          :access-key (or (:access-key creds) "dummy-access-key")
                                                          :secret-key (or (:secret-key creds) "dummy-secret-key")
                                                          :session-token (:session-token creds)
                                                          :headers {"Content-Type" "application/json"}
                                                          :payload-hash sha})))
                response (proxy/post-stream url
                                            {:headers headers
                                             :body payload
                                             :as :stream
                                             :timeout (when (pos? (or idle-timeout-ms 0)) idle-timeout-ms)}
                                            signal)]
            (sse/process-bedrock-stream response
                                        (responses-events-handler opts model-record)
                                        signal
                                        idle-timeout-ms
                                        (fn [] (proxy/abort-stream! response)))
            (proxy/finish-curl! response signal on-error))))
      (catch Exception e
        (when on-error (on-error (transport-error-message e)))))))

;; ─── Public API ────────────────────────────────────────────────────────────

(defn send-message
  "Send messages to LLM and receive streaming events via callbacks.

   opts:
     :provider    — provider keyword (:opencode-go, :opencode, :deepseek,
                    :github-copilot, :openai, :xai, :openai-codex,
                    :azure-openai-responses, :mistral, :google-vertex,
                    :amazon-bedrock, ...)
     :model       — model id string, resolved against the provider's catalog
     :api-type    — wire api override (:openai-completions,
                    :openai-responses, :openai-codex-responses,
                    :azure-openai-responses, :anthropic-messages,
                    :google-generative-ai, :mistral-conversations,
                    :google-vertex, :bedrock-converse-stream); wins over the
                    resolved model's :api
     :base-url    — full endpoint URL override (e.g. local test servers);
                    wins over the model-derived URL
     :api-key     — API key (required — resolved by caller via cfg/get-api-key)
     :thinking    — :off :minimal :low :medium :high :xhigh :max; clamped by
                    the resolved model's capability
     :messages    — vector of message maps
     :tools       — vector of Tool records
     :signal      — atom; set to true to cancel
     :idle-timeout-ms — per-byte idle timeout on the stream in ms (pi:
                     httpIdleTimeoutMs — undici bodyTimeout semantics); nil
                     or non-positive disables it
     :cache-retention — :short (default) | :long | :none — prompt-cache
                     params for openai-responses (pi CacheRetention; :none
                     disables the cache key + affinity headers — compaction
                     summaries pass it); ignored by the other apis
     :on-text     — (fn [text-delta])
     :on-tool-call — (fn [{:keys [id name arguments]}])
     :on-done     — (fn [stop-reason])
     :on-error    — (fn [message])
     :on-usage    — (fn [usage-map]) — provider-native usage from the final
                     stream chunk.

   Returns: future that completes when the stream ends."
  [{:keys [provider model api-key] :or {provider :opencode-go} :as opts}]
  (let [auth (auth/resolve-provider-auth provider)
        ;; Resolve the key here when the caller didn't provide one (pi:
        ;; prepareRequest resolves auth per request) — so a direct call with
        ;; auth.edn / env credentials works, and the builders never see a nil
        ;; key that would produce an empty Authorization header.
        api-key (or api-key (:api-key auth))
        ;; An oauth credential's to-auth carries a per-credential base-url
        ;; (pi applyAuth: auth.baseUrl overrides the model's — Copilot's
        ;; proxy-ep endpoint); an explicit agent-level :base-url wins.
        opts (cond-> opts
               (and (:base-url auth) (nil? (:base-url opts)))
               (assoc :base-url (:base-url auth)))]
    (cond
      ;; google-vertex (ADC) and amazon-bedrock (ambient AWS credentials)
      ;; resolve their own auth — the api-key check is per-request below
      (and (nil? api-key) (nil? (:bearer auth))
           (not (ambient-auth-available? provider)))
      (future
        (when-let [on-error (:on-error opts)]
          (on-error (str "No API key for " (name provider)
                         ". Set the key in ~/.kmet/agent/auth.edn."))))

      :else
      (let [m (models/get-model provider model)]
        (cond
          ;; Catalog provider with an unknown model id → error
          (and (some? (models/get-provider provider)) (nil? m))
          (future
            (when-let [on-error (:on-error opts)]
              (on-error (str "Unknown model: " (name provider) "/" model))))

          ;; Unknown provider (no catalog entry) → error
          (nil? m)
          (future
            (when-let [on-error (:on-error opts)]
              (on-error (str "Unknown provider: " (name provider)))))

          :else
          (let [api (or (:api-type opts) (:api m))
                p (models/get-provider provider)
                effort (effective-effort m (:thinking opts))]
            (case api
              :openai-completions (openai-request (assoc opts :model-record m :provider-record p
                                                         :effort effort :api-key api-key))
              :openai-responses (responses-request (assoc opts :model-record m :provider-record p
                                                          :effort effort :api-key api-key))
              :openai-codex-responses (codex-request (assoc opts :model-record m :provider-record p
                                                            :effort effort :api-key api-key))
              :azure-openai-responses (azure-request (assoc opts :model-record m :provider-record p
                                                            :effort effort :api-key api-key))
              :anthropic-messages (anthropic-request (assoc opts :model-record m :provider-record p
                                                            :effort effort :api-key api-key))
              :google-generative-ai (google-request (assoc opts :model-record m :provider-record p
                                                           :effort effort :api-key api-key))
              :mistral-conversations (mistral-request (assoc opts :model-record m :provider-record p
                                                             :effort effort :api-key api-key))
              :google-vertex (vertex-request (assoc opts :model-record m :provider-record p
                                                    :effort effort :api-key api-key))
              :bedrock-converse-stream (bedrock-request (assoc opts :model-record m :provider-record p
                                                               :effort effort :api-key api-key))
              (future
                (when-let [on-error (:on-error opts)]
                  (on-error (str "Unknown api-type: " (name (:api-type opts)))))))))))))
