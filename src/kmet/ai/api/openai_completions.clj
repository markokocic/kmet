(ns kmet.ai.api.openai-completions
  "OpenAI Completions wire API (pi: api/openai-completions.ts)."
  (:require
   [cheshire.core :as json]
   [kmet.ai.proxy :as proxy]
   [kmet.libs.sse :as sse]
   [kmet.ai.api.shared :refer [endpoint-url max-tokens-key openai-messages openai-messages-with-reasoning openai-thinking-params apply-before-provider-request-hook request-headers tool->openai-schema transport-error-message usage-with-cost]]))

(defn openai-payload
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
      (seq tools) (assoc :tools (mapv tool->openai-schema tools))
      (seq thinking-params) (merge thinking-params)
      (:max-tokens model-record) (assoc max-tokens-field (:max-tokens model-record))
      (seq (:sampling-params model-record)) (merge (:sampling-params model-record)))))

(defn openai-request
  [{:keys [model-record provider-record effort api-key messages tools signal base-url
           idle-timeout-ms session-id
           on-text on-thinking on-tool-call on-done on-error
           on-usage] :as opts}]
  (future
    (try
      (let [model-id (or (:model opts) (:id model-record))
            url (or base-url (endpoint-url :openai-completions (:base-url model-record) model-id))
            payload (apply-before-provider-request-hook
                     (openai-payload model-record effort messages tools model-id))
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
