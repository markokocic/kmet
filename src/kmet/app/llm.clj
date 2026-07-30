(ns kmet.app.llm
  "LLM API client supporting OpenAI and Anthropic with streaming."
  (:require [babashka.http-client :as http]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [cheshire.core :as json]
            [kmet.app.tools.core :as tools]))

;; ─── Configuration ─────────────────────────────────────────────────────────

(def default-openai-url "https://api.openai.com/v1/chat/completions")
(def anthropic-url "https://api.anthropic.com/v1/messages")
(def default-anthropic-version "2023-06-01")

;; ─── SSE parsing ───────────────────────────────────────────────────────────

(defn- parse-sse-line [line]
  (cond
    (str/starts-with? line "event:") [(str/trim (subs line 6)) nil]
    (str/starts-with? line "data:")  [nil (str/trim (subs line 5))]
    :else [nil nil]))

;; ─── OpenAI event parsing ──────────────────────────────────────────────────

(defn- parse-openai-event [data]
  (if (or (nil? data) (= data "[DONE]"))
    {:type :done :stop-reason :stop}
    (try
      (let [chunk (cheshire.core/parse-string data true)
            choices (get chunk :choices [])
            delta (get-in (first choices) [:delta] {})
            finish (get-in (first choices) [:finish_reason])
            tc-delta (get delta :tool_calls)]
        (cond
          finish {:type :done :stop-reason (keyword finish)}
          (and tc-delta (get-in (first tc-delta) [:function :name]))
          (let [tc (first tc-delta)]
            {:type :tool-call :id (:id tc)
             :name (get-in tc [:function :name])
             :arguments (get-in tc [:function :arguments] "")
             :index (:index tc)})
          (and tc-delta (get-in (first tc-delta) [:function :arguments]))
          (let [tc (first tc-delta)]
            {:type :tool-call-args :id (:id tc)
             :arguments (get-in tc [:function :arguments] "")
             :index (:index tc)})
          (get delta :content)
          {:type :text :content (get delta :content)}
          (get delta :reasoning_content)
          {:type :thinking :content (get delta :reasoning_content)}
          :else {:type :delta :chunk chunk}))
      (catch Exception e
        {:type :error :message (str "Parse error: " (.getMessage e))}))))

;; ─── Anthropic event parsing ───────────────────────────────────────────────

(defn- parse-anthropic-event [event data]
  (case event
    "content_block_start"
    (when data
      (let [block (cheshire.core/parse-string data true)
            cb (:content_block block)]
        (case (:type cb)
          "tool_use"
          {:type :tool-call :id (:id cb) :name (:name cb)
           :arguments (:input cb {})}
          {:type :content-block-start})))
    "content_block_delta"
    (when data
      (let [delta (cheshire.core/parse-string data true)]
        (case (get-in delta [:delta :type])
          "text_delta"
          {:type :text :content (get-in delta [:delta :text])}
          "input_json_delta"
          {:type :tool-call-args
           :arguments (get-in delta [:delta :partial_json])}
          {:type :delta :delta delta})))
    "message_delta"
    (when data
      (let [d (cheshire.core/parse-string data true)]
        {:type :message-delta
         :stop-reason (get-in d [:delta :stop_reason])}))
    "message_stop"
    {:type :done :stop-reason :end-turn}
    "ping"
    {:type :ping}
    {:type :unknown :event event :data data}))

;; ─── Message format conversion ─────────────────────────────────────────────

(defn- content-text
  "Extract plain text from a message content block vector.
   A block has {:type :text :text \"...\"} or {:type \"text\" :text \"...\"}."
  [content]
  (str/join (for [b content
                  :when (or (= (:type b) :text)
                            (= (:type b) "text"))]
              (:text b))))

(defn- openai-messages [messages]
  (mapv (fn [m]
          (let [role (name (:role m))]
            (case role
              "tool"
              {:role "tool"
               :tool_call_id (-> m :content first :tool_use_id)
               :content (-> m :content first :content)}
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
               :content (content-text (:content m))})))
        messages))

(defn- openai-messages-with-reasoning
  "Like openai-messages but adds reasoning_content to assistant messages.
   Some providers (e.g., opencode-go/deepseek-v4-flash) require a
   reasoning_content field on assistant messages even when empty."
  [messages]
  (mapv (fn [m]
          (let [role (name (:role m))
                msg (case role
                      "tool"
                      {:role "tool"
                       :tool_call_id (-> m :content first :tool_use_id)
                       :content (-> m :content first :content)}
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
                       :content (content-text (:content m))})]
            msg))
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

(defn- anthropic-messages [messages]
  (mapv (fn [m]
          (let [role (name (:role m))
                content (anthropic-content-text (:content m))]
            (cond-> {:role role :content content}
              (and (= role "assistant") (:tool-calls m))
              (assoc :content
                (vec (concat (if (string? (:content m)) [] (:content m))
                             (mapv (fn [tc]
                                     {:type "tool_use"
                                      :id (:id tc)
                                      :name (:name tc)
                                      :input (:arguments tc)})
                                   (:tool-calls m))))))))
        messages))

;; ─── Stream processing ─────────────────────────────────────────────────────

(defn- process-openai-stream [response handler signal]
  (try
    (with-open [rdr (io/reader (:body response))]
      (doseq [line (line-seq rdr)]
        (when (and line (not (and signal @signal)))
          (let [[_ data] (parse-sse-line line)]
            (when data
              (let [event (parse-openai-event data)]
                (handler event)))))))
    (catch Exception e
      (handler {:type :error :message (str "Stream error: " (.getMessage e))}))))

(defn- process-anthropic-stream [response handler signal]
  (try
    (with-open [rdr (io/reader (:body response))]
      (loop [event-name nil buf ""]
        (let [line (.readLine rdr)]
          (if (nil? line)
            (handler {:type :done :stop-reason :connection-closed})
            (when (and (not (and signal @signal)))
              (let [[ev data] (parse-sse-line line)]
                (cond
                  ev (recur data buf)
                  data (recur event-name (str buf data))
                  (and (empty? line) (seq buf))
                  (do (when-let [evt (parse-anthropic-event event-name buf)]
                        (handler evt))
                      (recur nil ""))
                  :else (recur event-name buf))))))))
    (catch Exception e
      (handler {:type :error :message (str "Stream error: " (.getMessage e))}))))

;; ─── OpenAI request ────────────────────────────────────────────────────────

(defn- openai-request
  [{:keys [api-key model messages tools signal base-url
           on-text on-thinking on-tool-call on-done on-error]}]
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
        (let [response (http/post url
                         {:headers {"Authorization" (str "Bearer " api-key)
                                    "Content-Type" "application/json"}
                          :body (json/generate-string payload)
                          :as :stream
                          :timeout 120000})]
          (process-openai-stream response
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
                :error (when on-error (on-error (:message event)))
                nil))
            signal))
        (catch Exception e
          (when on-error (on-error (.getMessage e))))))))

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
  [{:keys [api-key model messages tools signal thinking
           on-text on-tool-call on-done on-error]}]
  (future
    (let [thinking-cfg (anthropic-thinking-config thinking)
          payload (cond-> {:model (or model "claude-sonnet-4-20250514")
                           :max_tokens (if thinking-cfg 32000 4096)
                           :messages (anthropic-messages messages)
                           :stream true}
                    (seq tools) (assoc :tools (mapv tools/tool->anthropic-schema tools))
                    thinking-cfg (assoc :thinking thinking-cfg))]
      (try
        (let [response (http/post anthropic-url
                         {:headers {"x-api-key" api-key
                                    "anthropic-version" default-anthropic-version
                                    "Content-Type" "application/json"}
                          :body (json/generate-string payload)
                          :as :stream
                          :timeout 120000})]
          (process-anthropic-stream response
            (fn [event]
              (case (:type event)
                :text (when on-text (on-text (:content event)))
                :tool-call (when on-tool-call
                             (on-tool-call {:id (:id event)
                                            :name (:name event)
                                            :arguments (:arguments event)}))
                :done (when on-done (on-done (:stop-reason event)))
                :error (when on-error (on-error (:message event)))
                nil))
            signal))
        (catch Exception e
          (when on-error (on-error (.getMessage e))))))))

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
     :on-text     — (fn [text-delta])
     :on-tool-call — (fn [{:keys [id name arguments]}])
     :on-done     — (fn [stop-reason])
     :on-error    — (fn [message])

   Returns: future that completes when the stream ends."
  [{:keys [provider api-type api-key] :or {provider :openai} :as opts}]
  (if (nil? api-key)
    (future
      (when-let [on-error (:on-error opts)]
        (on-error (str "No API key for " (name provider)
                       ". Set the key in ~/.config/kmet/auth.edn."))))
    (let [api-type (or api-type provider)]
      (case api-type
        :openai (openai-request (assoc opts :api-key api-key))
        :anthropic (anthropic-request (assoc opts :api-key api-key))
        (future
          (when-let [on-error (:on-error opts)]
            (on-error (str "Unknown provider: " (name provider)))))))))
