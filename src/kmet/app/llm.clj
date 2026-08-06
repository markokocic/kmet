(ns kmet.app.llm
  "LLM API client supporting OpenAI and Anthropic with streaming."
  (:require [clojure.string :as str]
            [cheshire.core :as json]
            [kmet.libs.sse :as sse]
            [kmet.app.proxy :as proxy]
            [kmet.app.tools.core :as tools]))

;; ─── Configuration ─────────────────────────────────────────────────────────

(def default-openai-url "https://api.openai.com/v1/chat/completions")
(def anthropic-url "https://api.anthropic.com/v1/messages")
(def default-anthropic-version "2023-06-01")

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

;; ─── OpenAI request ────────────────────────────────────────────────────────

(defn- openai-request
  [{:keys [api-key model messages tools signal base-url idle-timeout-ms
           on-text on-thinking on-tool-call on-done on-error on-usage]}]
  (future
    (let [url (or base-url default-openai-url)
          payload {:model (or model "gpt-4o")
                   :messages (openai-messages-with-reasoning messages)
                   :stream true
                   :stream_options {:include_usage true}}
          payload (if (seq tools)
                    (assoc payload :tools (mapv tools/tool->openai-schema tools))
                    payload)]
      (try
        (let [response (proxy/post-stream url
                                          {:headers {"Authorization" (str "Bearer " api-key)
                                                     "Content-Type" "application/json"}
                                           :body (json/generate-string payload)
                                           :as :stream
                                           :timeout 120000}
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
                                     idle-timeout-ms)
          (proxy/finish-curl! response signal on-error))
        (catch Exception e
          (when on-error (on-error (or (ex-message e) (str "Request failed: " (.getSimpleName (class e)))))))))))

;; ─── Anthropic request ─────────────────────────────────────────────────────

(defn- anthropic-thinking-config
  "Convert thinking level keyword to Anthropic API thinking config."
  [level]
  (case level
    :low    {:type "enabled" :budget_tokens 2048}
    :medium {:type "enabled" :budget_tokens 8192}
    :high   {:type "enabled" :budget_tokens 16384}
    nil)) ;; :off or anything else

(defn- anthropic-request
  [{:keys [api-key model messages tools signal thinking idle-timeout-ms
           on-text on-tool-call on-done on-error on-usage]}]
  (future
    (let [thinking-cfg (anthropic-thinking-config thinking)
          payload (cond-> {:model (or model "claude-sonnet-4-20250514")
                           :max_tokens (if thinking-cfg 32000 4096)
                           :messages (anthropic-messages messages)
                           :stream true}
                    (seq tools) (assoc :tools (mapv tools/tool->anthropic-schema tools))
                    thinking-cfg (assoc :thinking thinking-cfg))]
      (try
        (let [response (proxy/post-stream anthropic-url
                                          {:headers {"x-api-key" api-key
                                                     "anthropic-version" default-anthropic-version
                                                     "Content-Type" "application/json"}
                                           :body (json/generate-string payload)
                                           :as :stream
                                           :timeout 120000}
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
                                        idle-timeout-ms)
          (proxy/finish-curl! response signal on-error))
        (catch Exception e
          (when on-error (on-error (or (ex-message e) (str "Request failed: " (.getSimpleName (class e)))))))))))

;; ─── Public API ────────────────────────────────────────────────────────────

(defn send-message
  "Send messages to LLM and receive streaming events via callbacks.

   opts:
     :provider    — :openai, :anthropic, or :opencode-go (default :openai)
     :api-type    — :openai or :anthropic (overrides auto-detection from provider)
     :api-key     — API key (required — resolved by caller via cfg/get-api-key)
     :model       — model identifier string
     :base-url    — custom base URL (for OpenAI-compatible providers)
     :thinking    — :off :low :medium :high :max
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
                     stream chunk (OpenAI) or message_start (Anthropic).

   Returns: future that completes when the stream ends."
  [{:keys [provider api-type api-key] :or {provider :openai} :as opts}]
  (if (nil? api-key)
    (future
      (when-let [on-error (:on-error opts)]
        (on-error (str "No API key for " (name provider)
                       ". Set the key in ~/.kmet/agent/auth.edn."))))
    (let [api-type (or api-type provider)]
      (case api-type
        :openai (openai-request (assoc opts :api-key api-key))
        :anthropic (anthropic-request (assoc opts :api-key api-key))
        (future
          (when-let [on-error (:on-error opts)]
            (on-error (str "Unknown provider: " (name provider)))))))))
