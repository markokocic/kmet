(ns kmet.app.api.anthropic-messages
  "Anthropic messages wire API (pi: api/anthropic-messages.ts)."
  (:require
   [kmet.app.auth :as auth]
   [cheshire.core :as json]
   [kmet.app.proxy :as proxy]
   [kmet.libs.sse :as sse]
   [clojure.string :as str]
   [kmet.app.tools.core :as tools]
   [kmet.app.api.shared :refer [anthropic-thinking bash-execution-text endpoint-url image-block? request-headers transport-error-message usage-with-cost]]))

(def default-anthropic-version "2023-06-01")

(defn anthropic-content-text
  "Extract plain text from Anthropic message content.
   Returns the content as-is if it is a string, otherwise joins text blocks."
  [content]
  (if (string? content)
    content
    (str/join (for [b content
                    :when (or (= (:type b) :text)
                              (= (:type b) "text"))]
                (:text b)))))

(defn anthropic-content
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

(defn anthropic-tool-result-content
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

(defn anthropic-messages [messages]
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

(defn anthropic-auth-headers
  "Base auth headers for an anthropic-messages request (pi anthropic provider
   resolve): the resolved provider auth — when ANTHROPIC_AUTH_TOKEN wins the
   resolution order (no credential, no configured key) → Authorization:
   Bearer, else x-api-key with the resolved API key."
  [provider api-key]
  (if-let [t (:bearer (auth/resolve-provider-auth provider))]
    {"Authorization" (str "Bearer " t)}
    {"x-api-key" api-key}))

(defn anthropic-request
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
