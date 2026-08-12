(ns kmet.app.test-llm
  (:require [clojure.test :as t]
            [clojure.string :as str]
            [kmet.libs.sse :as sse]
            [kmet.app.auth :as auth]
            [kmet.app.config-value :as config-value]
            [kmet.app.llm :as llm]
            [kmet.app.models :as m]
            [kmet.app.tools.core :as tools]))

;; ─── Module loads ─────────────────────────────────────────────────────────

(t/deftest test-llm-loaded
  (t/is (fn? llm/send-message))
  (m/load-catalogs!)
  (t/is (= 4 (count (m/get-providers))))
  (t/is (fn? m/get-model)))

;; ─── Model resolution & dispatch ───────────────────────────────────────────

(t/deftest test-llm-unknown-model
  ;; a catalog provider with an unknown model id → error (pi: unknown model)
  (m/load-catalogs!)
  (let [errors (atom [])]
    @(llm/send-message {:provider :opencode-go
                        :model "no-such-model"
                        :api-key "test"
                        :on-error (fn [e] (swap! errors conj e))})
    (t/is (= ["Unknown model: opencode-go/no-such-model"] @errors))))

(t/deftest test-llm-unknown-provider
  (m/load-catalogs!)
  (let [errors (atom [])]
    @(llm/send-message {:provider :unknown
                        :api-key "test"
                        :on-error (fn [e] (swap! errors conj e))})
    (t/is (= ["Unknown provider: unknown"] @errors)))
  (t/testing "providers without a catalog entry are unknown (no legacy fallback)"
    (let [errors (atom [])]
      @(llm/send-message {:provider :openai :model "gpt-4o"
                          :api-key "test"
                          :on-error (fn [e] (swap! errors conj e))})
      (t/is (= ["Unknown provider: openai"] @errors)))))

;; ─── Endpoint URL construction (each api owns it) ──────────────────────────

(t/deftest test-endpoint-urls
  (t/is (= "https://api.deepseek.com/chat/completions"
           (@#'llm/endpoint-url :openai-completions "https://api.deepseek.com" "deepseek-v4-flash")))
  (t/is (= "https://opencode.ai/zen/go/v1/chat/completions"
           (@#'llm/endpoint-url :openai-completions "https://opencode.ai/zen/go/v1" "qwen3.6-plus")))
  (t/is (= "https://api.individual.githubcopilot.com/v1/messages"
           (@#'llm/endpoint-url :anthropic-messages "https://api.individual.githubcopilot.com" "claude-sonnet-4.5")))
  (t/is (= "https://opencode.ai/zen/v1/models/gemini-3.1-pro:streamGenerateContent?alt=sse"
           (@#'llm/endpoint-url :google-generative-ai "https://opencode.ai/zen/v1" "gemini-3.1-pro"))))

;; ─── send-message with no API key ─────────────────────────────────────────

(t/deftest test-llm-no-api-key
  (let [errors (atom [])
        fut (llm/send-message
             {:provider :opencode-go
              :on-error (fn [e] (swap! errors conj e))})]
    @fut  ;; wait for future
    (t/is (pos? (count @errors)))
    (t/is (.contains (first @errors) "No API key"))))

(t/deftest test-llm-no-api-key-anthropic
  (let [errors (atom [])
        fut (llm/send-message
             {:provider :deepseek
              :on-error (fn [e] (swap! errors conj e))})]
    @fut
    (t/is (pos? (count @errors)))
    (t/is (.contains (first @errors) "No API key"))))

;; ─── Tool schema consistency ──────────────────────────────────────────────

(t/deftest test-llm-tool-schemas
  (let [tools (vals (tools/get-all-tools))]
    (doseq [t tools]
      (let [openai-schema (tools/tool->openai-schema t)
            anthropic-schema (tools/tool->anthropic-schema t)]
        (t/is (= "function" (:type openai-schema)))
        (t/is (= (:name t) (get-in openai-schema [:function :name])))
        (t/is (= (:name t) (:name anthropic-schema)))
        (t/is (map? (get-in openai-schema [:function :parameters])))
        (t/is (map? (:input_schema anthropic-schema)))))))

;; ─── SSE parsing helpers ──────────────────────────────────────────────────

(t/deftest test-llm-send-message-returns-future
  (m/load-catalogs!)
  (let [fut (llm/send-message {:provider :opencode-go :model "deepseek-v4-flash"
                               :api-key "test" :on-error (fn [_])})]
    (t/is (future? fut))))

;; ─── Edge: empty tools list ───────────────────────────────────────────────

(t/deftest test-llm-no-tools
  (let [errors (atom [])
        fut (llm/send-message
             {:provider :opencode-go
              :model "deepseek-v4-flash"
              :tools []
              :on-error (fn [e] (swap! errors conj e))})]
    @fut
    (t/is (pos? (count @errors)))))

;; ─── Multiple providers ──────────────────────────────────────────────────

(t/deftest test-llm-all-providers-resolve
  (m/load-catalogs!)
  (t/is (= 4 (count (m/get-providers))))
  (doseq [p [:opencode-go :opencode :deepseek :github-copilot]]
    (t/is (some? (m/get-provider p)) (str p " has a catalog entry"))))

;; ─── Image block conversion ───────────────────────────────────────────────

(t/deftest test-llm-openai-image-conversion
  (let [msgs [{:role :user
               :content [{:type :text :text "look"}
                         {:type :image :data "AA" :mime-type "image/png"}]}]
        converted (@#'llm/openai-messages msgs)]
    (t/is (= [{:type "text" :text "look"}
              {:type "image_url"
               :image_url {:url "data:image/png;base64,AA"}}]
             (:content (first converted)))
          "image blocks convert to OpenAI image_url blocks")))

(t/deftest test-llm-anthropic-image-conversion
  (let [msgs [{:role :user
               :content [{:type :text :text "look"}
                         {:type :image :data "AA" :mime-type "image/png"}]}]
        converted (@#'llm/anthropic-messages msgs)]
    (t/is (= [{:type "text" :text "look"}
              {:type "image"
               :source {:type "base64" :media_type "image/png" :data "AA"}}]
             (:content (first converted)))
          "image blocks convert to Anthropic image blocks")))

(t/deftest test-llm-custom-role-maps-to-user
  ;; G10: custom messages (from custom_message entries) are sent as user
  ;; messages (pi: convertToLlm custom→user)
  (let [msgs [{:role :custom
               :custom-type :note
               :content [{:type :text :text "hello from an extension"}]}]
        openai (@#'llm/openai-messages msgs)
        openai-reasoning (@#'llm/openai-messages-with-reasoning msgs)
        anthropic (@#'llm/anthropic-messages msgs)
        [google _] (@#'llm/google-messages msgs)]
    (t/is (= "user" (:role (first openai))))
    (t/is (= "user" (:role (first openai-reasoning))))
    (t/is (= "user" (:role (first anthropic))))
    (t/is (= "user" (:role (first google))))
    (t/is (= "hello from an extension" (:content (first openai)))
          "content preserved (openai-content returns plain text without images)")
    (t/is (= "hello from an extension" (:text (first (:parts (first google)))))
          "google content preserved"))
  ;; string content is normalized by the context projection (context-messages),
  ;; but the converters accept string content via the shared content helpers
  (let [msgs [{:role :custom :custom-type :note :content "plain string"}]
        openai (@#'llm/openai-messages msgs)]
    (t/is (= "user" (:role (first openai))))))

(t/deftest test-llm-tool-result-images-conversion
  (let [msgs [{:role :tool
               :content [{:type :tool_result :tool_use_id "t1" :content "saw it"}]
               :images [{:data "AA" :mime-type "image/png"}]}]
        openai (@#'llm/openai-messages msgs)
        anthropic (@#'llm/anthropic-messages msgs)]
    (t/is (= [{:type "text" :text "saw it"}
              {:type "image_url"
               :image_url {:url "data:image/png;base64,AA"}}]
             (:content (first openai)))
          "tool-result :images convert to OpenAI image_url blocks")
    (t/is (= [{:type "text" :text "saw it"}
              {:type "image"
               :source {:type "base64" :media_type "image/png" :data "AA"}}]
             (:content (first anthropic)))
          "tool-result :images convert to Anthropic image blocks")))

(t/deftest test-llm-no-images-backward-compat
  (let [msgs [{:role :user :content [{:type :text :text "hi"}]}]
        openai (@#'llm/openai-messages msgs)
        anthropic (@#'llm/anthropic-messages msgs)]
    (t/is (= "hi" (:content (first openai)))
          "text-only messages keep string content for OpenAI")
    (t/is (= "hi" (:content (first anthropic)))
          "text-only messages keep string content for Anthropic")))

(t/deftest test-llm-assistant-thinking-roundtrip
  ;; pi round-trips the thinking signature: assistant messages with :thinking
  ;; send it back as reasoning_content (DeepSeek thinking mode)
  (let [msgs [{:role :assistant
               :content [{:type :text :text "answer"}]
               :thinking "let me think\nabout it"}]
        openai (@#'llm/openai-messages msgs)
        reasoning (@#'llm/openai-messages-with-reasoning msgs)]
    (t/is (= "let me think\nabout it" (:reasoning_content (first openai)))
          "plain openai-messages sends the thinking back")
    (t/is (= "let me think\nabout it" (:reasoning_content (first reasoning)))
          "with-reasoning variant uses the message thinking"))
  ;; messages without thinking keep the empty-field compat for
  ;; requires-reasoning-content-on-assistant-messages providers
  (let [msgs [{:role :assistant :content [{:type :text :text "answer"}]}]]
    (t/is (nil? (:reasoning_content (first (@#'llm/openai-messages msgs)))))
    (t/is (= "" (:reasoning_content (first (@#'llm/openai-messages-with-reasoning msgs)))))))

;; ─── Bash result conversion (pi: convertToLlm bashExecution) ──────────────

(t/deftest test-llm-bash-conversion
  (let [msgs [{:role :bash :command "git st" :output "clean\n" :exit-code 0
               :exclude-from-context? false}
              {:role :bash :command "git st" :output "clean\n" :exit-code 0
               :exclude-from-context? true}]
        openai (@#'llm/openai-messages msgs)
        reasoning (@#'llm/openai-messages-with-reasoning msgs)
        anthropic (@#'llm/anthropic-messages msgs)]
    (doseq [converted [openai reasoning anthropic]]
      (t/is (= 1 (count converted)) "excluded bash entries are dropped")
      (t/is (= "user" (:role (first converted))) "bash entries become user messages")
      (let [text (:content (first converted))]
        (t/is (str/includes? text "Ran `git st`"))
        (t/is (str/includes? text "clean"))))))

(t/deftest test-llm-bash-conversion-format
  ;; pi: bashExecutionToText shape — output block, exit code, truncation note
  (let [msgs [{:role :bash :command "false" :output "" :exit-code 1
               :exclude-from-context? false
               :truncated true :full-output-path "/tmp/out"}]
        openai (@#'llm/openai-messages msgs)
        text (:content (first openai))]
    (t/is (str/includes? text "Ran `false`"))
    (t/is (str/includes? text "(no output)"))
    (t/is (str/includes? text "Command exited with code 1"))
    (t/is (str/includes? text "[Output truncated. Full output: /tmp/out]"))))

(t/deftest test-llm-bash-cancelled-no-exit-code
  (let [msgs [{:role :bash :command "sleep 10" :output "" :exit-code nil
               :cancelled true :exclude-from-context? false}]
        openai (@#'llm/openai-messages msgs)
        text (:content (first openai))]
    (t/is (str/includes? text "(command cancelled)"))
    (t/is (not (str/includes? text "Command exited with code")))))

(t/deftest test-llm-transport-error-message
  ;; A connect-time failure on this JDK surfaces as a ConnectException with a
  ;; nil message — transport-error-message must still mark it retryable
  ;; (pi: undici always reports transport failures as "fetch failed"), or
  ;; auto-retry on network errors silently dies.
  (let [te @#'llm/transport-error-message]
    (t/is (= "network error: ConnectException"
             (te (java.net.ConnectException. nil))))
    (t/is (= "network error: request timed out"
             (te (java.net.http.HttpTimeoutException. "request timed out"))))
    (t/is (= "network error: Connection reset"
             (te (java.net.SocketException. "Connection reset"))))
    (t/is (= "network error: UnknownHostException"
             (te (java.net.UnknownHostException. nil))))
    ;; HTTP/2 RST_STREAM surfaces as a plain IOException (java.net.http
    ;; builds the message from the frame's error code) — same class of
    ;; transport reset as SocketException "Connection reset", so it gets
    ;; the same stable retryable token.
    (t/is (= "network error: Received RST_STREAM: Protocol error"
             (te (java.io.IOException. "Received RST_STREAM: Protocol error"))))
    (t/is (= "network error: Received RST_STREAM: CANCEL"
             (te (java.io.IOException. "Received RST_STREAM: CANCEL"))))
    ;; Other IOExceptions are not network errors
    (t/is (= "Broken pipe" (te (java.io.IOException. "Broken pipe"))))
    ;; Non-network exceptions keep their message / legacy fallback
    (t/is (= "Invalid API key" (te (ex-info "Invalid API key" {}))))
    (t/is (= "Request failed: ExceptionInfo" (te (ex-info nil {}))))))

;; ─── Thinking level machinery (pi: clampThinkingLevel) ─────────────────────

(defn- tmodel
  "Model map for thinking tests."
  [& {:keys [id reasoning tlm compat max-tokens]
      :or {id "m1" reasoning true compat nil max-tokens 32000}}]
  {:id id :name "M1" :reasoning reasoning
   :thinking-level-map tlm :compat compat :max-tokens max-tokens})

(t/deftest test-supported-thinking-levels
  (t/is (= [:off] (@#'llm/supported-thinking-levels (tmodel :reasoning false)))
        "non-reasoning models only support :off")
  (t/is (= [:off :minimal :low :medium :high]
           (@#'llm/supported-thinking-levels (tmodel :tlm nil)))
        "no map → everything except :xhigh/:max")
  (t/is (= [:off :high :max]
           (@#'llm/supported-thinking-levels
            (tmodel :tlm {:minimal nil :low nil :medium nil :high "high" :max "max"})))
        "null map values mark levels unsupported")
  (t/is (= [:high]
           (@#'llm/supported-thinking-levels
            (tmodel :tlm {:off nil :minimal nil :low nil :medium nil})))
        "a null :off means thinking cannot be disabled"))

(t/deftest test-clamp-thinking-level
  (let [deepseek (tmodel :tlm {:minimal nil :low nil :medium nil :high "high" :max "max"})]
    (t/is (= :high (@#'llm/clamp-thinking-level deepseek :low))
          "unsupported levels clamp up to the nearest supported")
    (t/is (= :max (@#'llm/clamp-thinking-level deepseek :max)))
    (t/is (= :off (@#'llm/clamp-thinking-level deepseek :off)))
    (t/is (= :max (@#'llm/clamp-thinking-level deepseek :xhigh))
          "xhigh unsupported → clamps up to :max"))
  (t/is (= :high (@#'llm/clamp-thinking-level (tmodel :tlm nil) :max))
        "no map → :max clamps to :high")
  (t/is (= :off (@#'llm/clamp-thinking-level (tmodel :reasoning false) :high))
        "non-reasoning model → everything clamps to :off"))

(t/deftest test-effective-effort
  (t/is (nil? (@#'llm/effective-effort (tmodel) :off)))
  (t/is (= :low (@#'llm/effective-effort (tmodel) :low)))
  (t/is (= :high (@#'llm/effective-effort (tmodel) :max))
        "no map → :max clamps to :high"))

;; ─── Thinking request shaping (pi per-api) ─────────────────────────────────

(t/deftest test-openai-thinking-params
  (let [default-model (tmodel :compat nil)
        deepseek (tmodel :compat {:thinking-format :deepseek})
        deepseek-no-off (tmodel :compat {:thinking-format :deepseek}
                                :tlm {:off nil})
        qwen (tmodel :compat {:thinking-format :qwen})
        no-effort (tmodel :compat {:supports-reasoning-effort false})]
    (t/is (= {:reasoning_effort "high"} (@#'llm/openai-thinking-params default-model :high))
          "default format: reasoning_effort = level name")
    (t/is (= {:reasoning_effort "none"}
             (@#'llm/openai-thinking-params
              (tmodel :tlm {:off "none"}) nil))
          "default format off: map :off value when present")
    (t/is (= {} (@#'llm/openai-thinking-params default-model nil))
          "default format off without map :off → no params")
    (t/is (= {:thinking {:type "enabled"}
              :reasoning_effort "high"}
             (@#'llm/openai-thinking-params deepseek :high))
          "deepseek on: thinking enabled + reasoning_effort")
    (t/is (= {:thinking {:type "disabled"}}
             (@#'llm/openai-thinking-params deepseek nil))
          "deepseek off: thinking disabled")
    (t/is (= {} (@#'llm/openai-thinking-params deepseek-no-off nil))
          "deepseek off with :off null → no disabled param")
    (t/is (= {:enable_thinking true :reasoning_effort "high"}
             (@#'llm/openai-thinking-params qwen :high))
          "qwen on: enable_thinking + reasoning_effort")
    (t/is (= {:enable_thinking false} (@#'llm/openai-thinking-params qwen nil))
          "qwen off: enable_thinking false")
    (t/is (= {} (@#'llm/openai-thinking-params no-effort :high))
          "supports-reasoning-effort false → no effort params")
    (t/is (= {} (@#'llm/openai-thinking-params (tmodel :reasoning false) :high))
          "non-reasoning model → no thinking params")))

(t/deftest test-anthropic-thinking
  (let [model (tmodel :max-tokens 32000)]
    (t/is (= {:thinking {:type "enabled" :budget_tokens 2048}
              :max-tokens 32000}
             (@#'llm/anthropic-thinking model :low))
          "budget-based thinking, max_tokens from the model")
    (t/is (= {:thinking {:type "enabled" :budget_tokens 16384}
              :max-tokens 32000}
             (@#'llm/anthropic-thinking model :high)))
    (t/is (= {:thinking {:type "enabled" :budget_tokens 16384}
              :max-tokens 32000}
             (@#'llm/anthropic-thinking model :max))
          ":max clamps to :high budget")
    (t/is (= {:thinking {:type "disabled"} :max-tokens 32000}
             (@#'llm/anthropic-thinking model nil))
          "off → thinking disabled")
    (t/is (nil? (@#'llm/anthropic-thinking
                 (tmodel :tlm {:off nil}) nil))
          "off with :off null → no thinking param")
    (t/is (nil? (@#'llm/anthropic-thinking (tmodel :reasoning false) :high)))
    (t/is (= {:thinking {:type "enabled" :budget_tokens 2048} :max-tokens 4096}
             (@#'llm/anthropic-thinking (tmodel :max-tokens nil) :low))
          "legacy anthropic (no max-tokens) falls back to 4096")))

(t/deftest test-google-thinking-config
  (let [gemini-pro (tmodel :id "gemini-3.1-pro"
                           :tlm {:off nil :minimal nil :low "LOW" :medium nil :high "HIGH"})]
    (t/is (= {:includeThoughts true :thinkingLevel "LOW"}
             (@#'llm/google-thinking-config gemini-pro :low)))
    (t/is (= {:includeThoughts true :thinkingLevel "HIGH"}
             (@#'llm/google-thinking-config gemini-pro :high)))
    (t/is (= {:thinkingLevel "LOW"}
             (@#'llm/google-thinking-config gemini-pro nil))
          "gemini-3 pro off → lowest thinking level, no includeThoughts"))
  (t/is (= {:thinkingLevel "MINIMAL"}
           (@#'llm/google-thinking-config (tmodel :id "gemini-3.5-flash") nil))
        "gemini-3 flash off → MINIMAL"))

;; ─── Google message conversion ─────────────────────────────────────────────

(t/deftest test-google-messages
  (let [[contents system] (@#'llm/google-messages
                           [{:role :system :content [{:type :text :text "sys"}]}
                            {:role :user :content [{:type :text :text "hi"}]}
                            {:role :assistant :content [{:type :text :text "ok"}]
                             :tool-calls [{:id "t1" :name "read" :arguments {:path "a"}}]}
                            {:role :tool :tool-name "read" :is-error false
                             :content [{:type :tool_result :tool_use_id "t1" :content "saw it"}]}])]
    (t/is (= "sys" system))
    (t/is (= [{:role "user" :parts [{:text "hi"}]}
              {:role "model" :parts [{:text "ok"}
                                     {:functionCall {:name "read" :args {:path "a"}}}]}
              {:role "user" :parts [{:functionResponse {:name "read" :response {:output "saw it"}}}]}]
             contents))))

(t/deftest test-google-event-parsing
  (t/is (= [{:type :text :content "Hi"}]
           (sse/parse-google-event
            "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"Hi\"}]}}]}")))
  (t/is (= [{:type :thinking :content "think"}]
           (sse/parse-google-event
            "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"think\",\"thought\":true}]}}]}")))
  (let [evts (sse/parse-google-event
              "{\"candidates\":[{\"content\":{\"parts\":[{\"functionCall\":{\"name\":\"read\",\"args\":{\"path\":\"a\"}}}]}}]}")]
    (t/is (= :tool-call (:type (first evts))))
    (t/is (= "read" (:name (first evts))))
    (t/is (= {:path "a"} (:arguments (first evts)))))
  (t/is (= [{:type :done :stop-reason :stop}]
           (sse/parse-google-event
            "{\"candidates\":[{\"finishReason\":\"STOP\"}]}")))
  (t/is (= :length (:stop-reason (first (sse/parse-google-event
                                         "{\"candidates\":[{\"finishReason\":\"MAX_TOKENS\"}]}")))))
  (t/testing "a chunk can carry several events (pi reads all parts per chunk)"
    (let [evts (sse/parse-google-event
                "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"ok\"},{\"functionCall\":{\"name\":\"read\",\"args\":{}}}]},\"finishReason\":\"STOP\"}],\"usageMetadata\":{\"promptTokenCount\":10,\"candidatesTokenCount\":20,\"totalTokenCount\":30}}")]
      (t/is (= [:usage :text :tool-call :done] (mapv :type evts))
            "usage, all parts, and finish are all emitted from one chunk")))
  (t/is (= [{:type :usage :usage {:input 10 :output 20 :cache-read 0 :cache-write 0
                                  :reasoning 0 :total-tokens 30}}]
           (sse/parse-google-event
            "{\"usageMetadata\":{\"promptTokenCount\":10,\"candidatesTokenCount\":20,\"totalTokenCount\":30}}"))))

;; ─── Model headers merge ───────────────────────────────────────────────────

(t/deftest test-model-headers-merge
  (let [model {:headers {"User-Agent" "GitHubCopilotChat/0.35.0"}
               :provider :github-copilot :id "claude-sonnet-4.5"}
        provider {:id :github-copilot
                  :configured-headers {"X-Custom" "literal"}
                  :auth-header false}
        merged (@#'llm/request-headers {"Content-Type" "application/json"} model provider "k" nil)]
    (t/is (= "application/json" (get merged "Content-Type")))
    (t/is (= "GitHubCopilotChat/0.35.0" (get merged "User-Agent")))
    (t/is (= "literal" (get merged "X-Custom")))))

(t/deftest test-request-headers-auth-header
  (t/testing "auth-header true adds Authorization: Bearer <key> last"
    (let [merged (@#'llm/request-headers {"x-api-key" "k"}
                                         {:provider :p :id "m"}
                                         {:id :p :auth-header true}
                                         "secret" nil)]
      (t/is (= "Bearer secret" (get merged "Authorization")))
      (t/is (= "k" (get merged "x-api-key")))))
  (t/testing "no auth-header → no Authorization header"
    (let [merged (@#'llm/request-headers {"x-api-key" "k"}
                                         {:provider :p :id "m"}
                                         {:id :p}
                                         "secret" nil)]
      (t/is (nil? (get merged "Authorization")))))
  (t/testing "configured header values resolve as config values ($ENV)"
    (with-redefs [config-value/getenv (fn [k] (when (= k "TEST_LLM_HEADER") "hdr"))]
      (let [merged (@#'llm/request-headers {}
                                           {:provider :p :id "m"}
                                           {:id :p :configured-headers {"X-Custom" "$TEST_LLM_HEADER"}}
                                           "k" nil)]
        (t/is (= "hdr" (get merged "X-Custom")))))))

(t/deftest test-max-tokens-key
  (t/is (= :max_tokens (@#'llm/max-tokens-key (tmodel :compat {:max-tokens-field :max-tokens})))
        "max-tokens-field :max-tokens → :max_tokens (opencode/deepseek)")
  (t/is (= :max_completion_tokens (@#'llm/max-tokens-key (tmodel :compat nil)))
        "default → :max_completion_tokens"))

(t/deftest test-openai-payload-sampling-params
  (t/testing "sampling-params merged verbatim into the payload, keys win (pi: Object.assign last)"
    (let [model (assoc (tmodel) :sampling-params {:temperature 1.0 :min_p 0.0})
          payload (@#'llm/openai-payload model nil [] [] "m1")]
      (t/is (= 1.0 (:temperature payload)))
      (t/is (= 0.0 (:min_p payload)))
      (t/is (= "m1" (:model payload)))
      (t/is (= true (:stream payload)))))
  (t/testing "sampling-params override pi-named fields (temperature, max tokens)"
    (let [model (assoc (tmodel) :sampling-params {:max_completion_tokens 5})
          payload (@#'llm/openai-payload model nil [] [] "m1")]
      (t/is (= 5 (:max_completion_tokens payload)) "sampling key beats the model :max-tokens")))
  (t/testing "no sampling-params → payload unchanged"
    (let [payload (@#'llm/openai-payload (tmodel) nil [] [] "m1")]
      (t/is (= 32000 (:max_completion_tokens payload)))
      (t/is (nil? (:temperature payload))))))

(t/deftest test-reasoning-content-gating-data
  ;; pi: requiresReasoningContentOnAssistantMessages gates reasoning_content
  ;; on assistant messages — carried by deepseek/opencode-go deepseek-v4
  ;; models, absent elsewhere.
  (m/load-catalogs!)
  (t/is (true? (:requires-reasoning-content-on-assistant-messages
                (:compat (m/get-model :deepseek "deepseek-v4-flash")))))
  (t/is (true? (:requires-reasoning-content-on-assistant-messages
                (:compat (m/get-model :opencode-go "deepseek-v4-flash")))))
  (t/is (nil? (:requires-reasoning-content-on-assistant-messages
               (:compat (m/get-model :opencode "glm-5.2")))))
  (t/is (nil? (:requires-reasoning-content-on-assistant-messages
               (:compat (m/get-model :github-copilot "claude-sonnet-4.5"))))))

;; ─── Transport total timeout follows the configured idle timeout ──────────

(t/deftest ^:slow test-llm-request-timeout-follows-idle
  (m/load-catalogs!)
  ;; A server that accepts the request but never responds: the request must
  ;; error via the configured total timeout (~idle-timeout-ms), not the old
  ;; hardcoded 120s (pi: SDK timeoutMs ?? httpIdleTimeoutMs).
  (let [ss (java.net.ServerSocket. 0)
        port (.getLocalPort ss)
        _ (doto (Thread.
                 (fn []
                   (try
                     (let [s (.accept ss)
                           rdr (java.io.BufferedReader.
                                (java.io.InputStreamReader. (.getInputStream s)))]
                       ;; drain request headers, then stall — never respond
                       (while (seq (.readLine rdr)) nil)
                       (Thread/sleep 5000)
                       (.close s))
                     (catch Exception _ nil))))
            (.setDaemon true)
            (.start))
        t0 (System/currentTimeMillis)
        errors (atom [])
        fut (llm/send-message {:provider :opencode-go
                               :api-key "sk-test"
                               :base-url (str "http://localhost:" port "/v1/chat/completions")
                               :model "deepseek-v4-flash"
                               :messages [{:role "user" :content "hi"}]
                               :idle-timeout-ms 1500
                               :on-error (fn [e] (swap! errors conj e))})]
    (try
      @fut
      (t/is (= 1 (count @errors)))
      (let [elapsed (- (System/currentTimeMillis) t0)]
        ;; Window 20s: the error message assertion below guards the mechanism
        ;; (must contain "timed out" — a broken total timeout yields the EOF
        ;; error instead); this window only allows for scheduling delay on
        ;; loaded hosts while still rejecting the old 120s hardcoded timeout.
        (t/is (< elapsed 20000)
              (str "errored via the configured timeout (took " elapsed "ms)"))
        (t/is (str/includes? (first @errors) "timed out")))
      (finally
        (.close ss)))))

(t/deftest ^:slow test-llm-body-stall-idle-timeout-completes
  (m/load-catalogs!)
  ;; A server that sends response headers then stalls the body: the SSE idle
  ;; timeout must fire and the request future must complete promptly. Before
  ;; the interrupt/join-before-close fix, closing the java.net.http body
  ;; stream while the daemon read was in flight deadlocked the future.
  (let [ss (java.net.ServerSocket. 0)
        port (.getLocalPort ss)
        _ (doto (Thread.
                 (fn []
                   (try
                     (let [s (.accept ss)
                           rdr (java.io.BufferedReader.
                                (java.io.InputStreamReader. (.getInputStream s)))]
                       (while (seq (.readLine rdr)) nil)
                       (let [out (.getOutputStream s)]
                         (.write out (.getBytes "HTTP/1.1 200 OK\r\nContent-Length: 100\r\n\r\n"))
                         (.flush out)
                         (Thread/sleep 30000))
                       (.close s))
                     (catch Exception _ nil))))
            (.setDaemon true)
            (.start))
        errors (atom [])
        fut (llm/send-message {:provider :opencode-go
                               :api-key "sk-test"
                               :base-url (str "http://localhost:" port "/v1/chat/completions")
                               :model "deepseek-v4-flash"
                               :messages [{:role "user" :content [{:type :text :text "hi"}]}]
                               :idle-timeout-ms 1000
                               :on-error (fn [e] (swap! errors conj e))})]
    (try
      (t/is (not= :timeout (deref fut 8000 :timeout))
            "request future completes instead of deadlocking on close")
      (t/is (= ["Stream idle timeout after 1000 ms (no data received)"]
               @errors))
      (finally
        (.close ss)))))

;; ─── Per-message cost attachment (Phase 5) ────────────────────────────────

(t/deftest test-usage-with-cost
  (m/load-catalogs!)
  (let [model (m/get-model :deepseek "deepseek-v4-pro")
        norm {:input 800 :output 500 :cache-read 200 :cache-write 0}]
    (t/testing "provider-native usage gains the pi-shaped :cost breakdown"
      (let [usage (@#'llm/usage-with-cost model
                                          {:prompt_tokens 1000 :completion_tokens 500
                                           :prompt_tokens_details {:cached_tokens 200}})]
        (t/is (= 1000 (:prompt_tokens usage)) "provider-native keys preserved")
        (t/is (= (m/calculate-cost model norm) (:cost usage))
              "cost equals calculate-cost over the normalized tokens")))
    (t/testing "google's already-normalized usage is priced too"
      (let [usage (@#'llm/usage-with-cost model {:input 10 :output 20
                                                 :cache-read 0 :cache-write 0})]
        (t/is (= 10 (:input usage)))
        (t/is (= (m/calculate-cost model {:input 10 :output 20
                                          :cache-read 0 :cache-write 0})
                 (:cost usage)))))
    (t/testing "unrecognized usage passes through unchanged (no :cost)"
      (t/is (= {:foo 1} (@#'llm/usage-with-cost model {:foo 1}))))))

(t/deftest test-request-headers-attribution
  (t/testing "opencode session headers flow through request-headers"
    (let [model {:provider :opencode :id "qwen3.6-plus"
                 :base-url "https://opencode.ai/zen/v1"}
          provider {:id :opencode}
          merged (@#'llm/request-headers {"Authorization" "Bearer k"} model provider "k" "sess-42")]
      (t/is (= "sess-42" (get merged "x-opencode-session")))
      (t/is (= "pi" (get merged "x-opencode-client")))))
  (t/testing "no session id → no session headers"
    (let [model {:provider :opencode :id "qwen3.6-plus"}
          provider {:id :opencode}
          merged (@#'llm/request-headers {} model provider "k" nil)]
      (t/is (nil? (get merged "x-opencode-session"))))))

;; ─── Phase 9: anthropic auth-token (Authorization: Bearer) ─────────────────

(t/deftest test-anthropic-auth-headers
  (t/testing "the :anthropic provider with AUTH_TOKEN → Authorization: Bearer (pi anthropic resolve)"
    (with-redefs [auth/getenv (fn [k] (when (= k "ANTHROPIC_AUTH_TOKEN") "tok"))]
      (t/is (= {"Authorization" "Bearer tok"}
               (@#'llm/anthropic-auth-headers :anthropic "ignored-key")))))
  (t/testing "no AUTH_TOKEN → x-api-key with the resolved key"
    (with-redefs [auth/getenv (fn [_] nil)]
      (t/is (= {"x-api-key" "api-key"}
               (@#'llm/anthropic-auth-headers :anthropic "api-key")))))
  (t/testing "other providers never take the anthropic bearer path"
    (with-redefs [auth/getenv (fn [k] (when (= k "ANTHROPIC_AUTH_TOKEN") "tok"))]
      (t/is (= {"x-api-key" "k"}
               (@#'llm/anthropic-auth-headers :github-copilot "k"))))))

(t/deftest test-llm-anthropic-bearer-only
  (m/load-catalogs!)
  (m/clear-extension-providers!)
  (try
    (m/register-provider-config! :anthropic
                                 {:base-url "https://api.anthropic.com" :api :anthropic-messages
                                  :models [{:id "claude-sonnet-4.5"}]})
    (t/testing "non-anthropic providers still reject a missing api key"
      (with-redefs [auth/getenv (fn [k] (when (= k "ANTHROPIC_AUTH_TOKEN") "tok"))]
        (let [errors (atom [])]
          @(llm/send-message {:provider :deepseek
                              :on-error (fn [e] (swap! errors conj e))})
          (t/is (pos? (count @errors)))
          (t/is (str/includes? (first @errors) "No API key")))))
    (t/testing "the :anthropic provider with AUTH_TOKEN: proceeds past the key check"
      (with-redefs [auth/getenv (fn [k] (when (= k "ANTHROPIC_AUTH_TOKEN") "tok"))]
        (let [errors (atom [])]
          @(llm/send-message {:provider :anthropic
                              :model "claude-sonnet-4.5"
                              :on-error (fn [e] (swap! errors conj e))})
          (t/is (not-any? #(str/includes? (or % "") "No API key") @errors)
                "reaches the request (fails on the network, not on auth)"))))
    (finally
      (m/clear-extension-providers!)
      (m/load-catalogs!))))

(t/deftest test-llm-anthropic-broken-configured-key-blocks-bearer
  ;; A configured-but-unresolvable models.edn key blocks the whole resolution
  ;; (pi resolveConfigValueOrThrow: rawKey present → inherited env never
  ;; reached) — even with AUTH_TOKEN set, the request must NOT proceed with a
  ;; nil x-api-key.
  (m/load-catalogs!)
  (m/clear-extension-providers!)
  (try
    (m/register-provider-config! :anthropic
                                 {:base-url "https://api.anthropic.com" :api :anthropic-messages
                                  :api-key "$KMT_MISSING_ANTHROPIC_KEY"
                                  :models [{:id "claude-sonnet-4.5"}]})
    (with-redefs [auth/getenv (fn [k] (when (= k "ANTHROPIC_AUTH_TOKEN") "tok"))
                  config-value/getenv (fn [_] nil)]
      (let [errors (atom [])]
        @(llm/send-message {:provider :anthropic
                            :model "claude-sonnet-4.5"
                            :on-error (fn [e] (swap! errors conj e))})
        (t/is (pos? (count @errors)))
        (t/is (str/includes? (first @errors) "No API key")
              "unresolvable configured key wins over AUTH_TOKEN (pi)")))
    (finally
      (m/clear-extension-providers!)
      (m/load-catalogs!))))
