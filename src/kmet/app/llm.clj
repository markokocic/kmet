(ns kmet.app.llm
  "LLM API client: OpenAI-completions, Anthropic messages, and Google
   Generative AI — all with streaming.

   The resolved Model record is the unit of truth (pi): dispatch (wire api),
   endpoint URL, thinking shaping, max-token field, static headers and cost
   all derive from it. Every provider must have a catalog entry; unknown
   providers/models error out."
  (:require [clojure.string :as str]
            [cheshire.core :as json]
            [kmet.libs.sse :as sse]
            [kmet.app.models :as models]
            [kmet.app.proxy :as proxy]
            [kmet.app.tools.core :as tools]))

(def ^:private default-anthropic-version "2023-06-01")

;; ─── Wire api + URL construction ───────────────────────────────────────────

(defn- endpoint-url
  "Full request URL from a wire api + API base + model id (pi: the SDK
   appends the endpoint path)."
  [api base model-id]
  (case api
    :openai-completions (str base "/chat/completions")
    :anthropic-messages (str base "/v1/messages")
    :google-generative-ai (str base "/models/" model-id ":streamGenerateContent?alt=sse")))

(defn- merge-model-headers
  "Request headers with the model's static :headers merged in last (pi:
   model.headers, e.g. COPILOT_STATIC_HEADERS)."
  [base model]
  (cond-> base
    (seq (:headers model)) (into (:headers model))))

;; ─── Thinking levels (pi: clampThinkingLevel / getSupportedThinkingLevels) ─

(def ^:private thinking-levels
  [:off :minimal :low :medium :high :xhigh :max])

(defn- supported-thinking-levels
  "Levels a model can express (pi getSupportedThinkingLevels): non-reasoning
   models only :off; :xhigh/:max require an entry in the model's
   thinking-level-map; a null map value marks a level unsupported (absent
   entries are supported)."
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
  (let [available (supported-thinking-levels model)]
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
   excluded ones are dropped. Tested directly by test_llm, hence public."
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
                    (let [text (content-text (:content m))]
                      (cond-> {:role "assistant" :content text}
                        (:tool-calls m)
                        (assoc :tool_calls
                               (mapv (fn [tc]
                                       {:id (:id tc)
                                        :type "function"
                                        :function {:name (:name tc)
                                                   :arguments (cheshire.core/generate-string
                                                               (:arguments tc))}})
                                     (:tool-calls m)))))
                    {:role role
                     :content (openai-content (:content m))}))))
        messages))

(defn- openai-messages-with-reasoning
  "Like openai-messages but adds reasoning_content to assistant messages.
   Some providers (e.g., opencode-go/deepseek-v4-flash) require a
   reasoning_content field on assistant messages even when empty."
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
                              (assoc msg :reasoning_content ""))
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

(defn- google-messages
  "Convert kmet messages to Google contents (pi convertMessages for
   google-generative-ai): text/image parts, functionCall parts on model
   turns, functionResponse parts on user turns. Returns [contents system] —
   the :system message (if any) becomes the systemInstruction."
  [messages]
  (let [system (first (for [m messages
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
                           resp (if (:is-error m) {:error text} {:output text})]
                       {:role "user"
                        :parts [{:functionResponse {:name (:tool-name m)
                                                    :response resp}}]})
                     "assistant"
                     (let [parts (into []
                                       (concat
                                        (for [b (:content m) :when (= :text (:type b))]
                                          {:text (:text b)})
                                        (for [tc (:tool-calls m)]
                                          {:functionCall {:name (:name tc)
                                                          :args (:arguments tc)}})))]
                       (when (seq parts) {:role "model" :parts parts}))
                     ;; user
                     (let [parts (if (some image-block? (:content m))
                                   (for [b (:content m)]
                                     (if (image-block? b)
                                       {:inlineData {:mime-type (:mime-type b)
                                                     :data (:data b)}}
                                       {:text (or (:text b) "")}))
                                   [{:text (content-text (:content m))}])]
                       {:role "user" :parts parts})))
                 msgs))
     system]))

;; ─── Thinking request shaping (pi per-api) ─────────────────────────────────

(defn- openai-thinking-params
  "Thinking params for an openai-completions payload (pi buildParams thinking
   section; kmet's formats: default/openai, deepseek, qwen). EFFORT is the
   clamped level, nil when off."
  [model effort]
  (let [reasoning? (:reasoning model)
        fmt (:thinking-format (:compat model))
        effort? (not= false (:supports-reasoning-effort (:compat model)))]
    (cond
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

(defn- anthropic-thinking
  "pi streamSimple budget-based thinking: the thinking config + max_tokens
   for an anthropic payload. EFFORT is the clamped level; nil when off.
   Returns nil when thinking is off and the model allows disabling."
  [model effort]
  (cond
    (not (:reasoning model)) nil
    effort
    (let [level (if (contains? #{:xhigh :max} effort) :high effort)
          budget (get thinking-budgets level 0)
          max-tokens (or (:max-tokens model) 4096)
          budget (min budget (max 0 (- max-tokens min-answer-tokens)))]
      {:thinking {:type "enabled" :budget_tokens budget}
       :max-tokens max-tokens})
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

(defn- openai-request
  [{:keys [model-record effort api-key messages tools signal base-url
           idle-timeout-ms on-text on-thinking on-tool-call on-done on-error
           on-usage] :as opts}]
  (future
    (let [model-id (or (:model opts) (:id model-record))
          thinking-params (openai-thinking-params model-record effort)
          max-tokens-field (max-tokens-key model-record)
          ;; pi: requiresReasoningContentOnAssistantMessages gates the
          ;; reasoning_content field (deepseek/opencode-go only)
          messages-fn (if (:requires-reasoning-content-on-assistant-messages
                           (:compat model-record))
                        openai-messages-with-reasoning
                        openai-messages)
          url (or base-url (endpoint-url :openai-completions (:base-url model-record) model-id))
          payload (cond-> {:model model-id
                           :messages (messages-fn messages)
                           :stream true
                           :stream_options {:include_usage true}}
                    (seq tools) (assoc :tools (mapv tools/tool->openai-schema tools))
                    (seq thinking-params) (merge thinking-params)
                    (:max-tokens model-record)
                    (assoc max-tokens-field (:max-tokens model-record)))]
      (try
        (let [response (proxy/post-stream url
                                          {:headers (merge-model-headers
                                                     {"Authorization" (str "Bearer " api-key)
                                                      "Content-Type" "application/json"}
                                                     model-record)
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
                                         :usage (when on-usage (on-usage (:usage event)))
                                         :error (when on-error (on-error (:message event)))
                                         nil))
                                     signal
                                     idle-timeout-ms
                                     (fn [] (proxy/abort-stream! response)))
          (proxy/finish-curl! response signal on-error))
        (catch Exception e
          (when on-error (on-error (or (ex-message e) (str "Request failed: " (.getSimpleName (class e)))))))))))

;; ─── Anthropic messages request ────────────────────────────────────────────

(defn- anthropic-request
  [{:keys [model-record effort api-key messages tools signal base-url
           idle-timeout-ms on-text on-tool-call on-done on-error on-usage]
    :as opts}]
  (future
    (let [model-id (or (:model opts) (:id model-record))
          thinking (anthropic-thinking model-record effort)
          payload (cond-> {:model model-id
                           :max_tokens (:max-tokens thinking (or (:max-tokens model-record) 4096))
                           :messages (anthropic-messages messages)
                           :stream true}
                    (seq tools) (assoc :tools (mapv tools/tool->anthropic-schema tools))
                    (:thinking thinking) (assoc :thinking (:thinking thinking)))]
      (try
        (let [response (proxy/post-stream (or base-url (endpoint-url :anthropic-messages (:base-url model-record) model-id))
                                          {:headers (merge-model-headers
                                                     {"x-api-key" api-key
                                                      "anthropic-version" default-anthropic-version
                                                      "Content-Type" "application/json"}
                                                     model-record)
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
                                            :usage (when on-usage (on-usage (:usage event)))
                                            :error (when on-error (on-error (:message event)))
                                            nil))
                                        signal
                                        idle-timeout-ms
                                        (fn [] (proxy/abort-stream! response)))
          (proxy/finish-curl! response signal on-error))
        (catch Exception e
          (when on-error (on-error (or (ex-message e) (str "Request failed: " (.getSimpleName (class e)))))))))))

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
  [{:keys [model-record effort api-key messages tools signal base-url
           idle-timeout-ms on-text on-thinking on-tool-call on-done on-error
           on-usage]
    :as opts}]
  (future
    (let [model-id (or (:model opts) (:id model-record))
          [contents system] (google-messages messages)
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
                                          {:headers (merge-model-headers
                                                     {"x-goog-api-key" api-key
                                                      "Content-Type" "application/json"}
                                                     model-record)
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
                                         :usage (when on-usage (on-usage (:usage event)))
                                         :error (when on-error (on-error (:message event)))
                                         nil))
                                     signal
                                     idle-timeout-ms
                                     (fn [] (proxy/abort-stream! response)))
          (proxy/finish-curl! response signal on-error))
        (catch Exception e
          (when on-error (on-error (or (ex-message e) (str "Request failed: " (.getSimpleName (class e)))))))))))

;; ─── Public API ────────────────────────────────────────────────────────────

(defn send-message
  "Send messages to LLM and receive streaming events via callbacks.

   opts:
     :provider    — provider keyword (:opencode-go, :opencode, :deepseek,
                    :github-copilot)
     :model       — model id string, resolved against the provider's catalog
     :api-type    — wire api override (:openai-completions,
                    :anthropic-messages, :google-generative-ai); wins over
                    the resolved model's :api
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
     :on-text     — (fn [text-delta])
     :on-tool-call — (fn [{:keys [id name arguments]}])
     :on-done     — (fn [stop-reason])
     :on-error    — (fn [message])
     :on-usage    — (fn [usage-map]) — provider-native usage from the final
                     stream chunk.

   Returns: future that completes when the stream ends."
  [{:keys [provider model api-key] :or {provider :opencode-go} :as opts}]
  (cond
    (nil? api-key)
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
              effort (effective-effort m (:thinking opts))]
          (case api
            :openai-completions (openai-request (assoc opts :model-record m :effort effort :api-key api-key))
            :anthropic-messages (anthropic-request (assoc opts :model-record m :effort effort :api-key api-key))
            :google-generative-ai (google-request (assoc opts :model-record m :effort effort :api-key api-key))
            (future
              (when-let [on-error (:on-error opts)]
                (on-error (str "Unknown api-type: " (name (:api-type opts))))))))))))
