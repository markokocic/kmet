(ns kmet.app.test-llm
  (:require [clojure.test :as t]
            [cheshire.core :as json]
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
  (t/is (= 28 (count (m/get-providers))))
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
      @(llm/send-message {:provider :nosuch :model "gpt-4o"
                          :api-key "test"
                          :on-error (fn [e] (swap! errors conj e))})
      (t/is (= ["Unknown provider: nosuch"] @errors)))))

;; ─── Endpoint URL construction (each api owns it) ──────────────────────────

(t/deftest test-endpoint-urls
  (t/is (= "https://api.deepseek.com/chat/completions"
           (@#'llm/endpoint-url :openai-completions "https://api.deepseek.com" "deepseek-v4-flash")))
  (t/is (= "https://opencode.ai/zen/go/v1/chat/completions"
           (@#'llm/endpoint-url :openai-completions "https://opencode.ai/zen/go/v1" "qwen3.6-plus")))
  (t/is (= "https://api.individual.githubcopilot.com/v1/messages"
           (@#'llm/endpoint-url :anthropic-messages "https://api.individual.githubcopilot.com" "claude-sonnet-4.5")))
  (t/is (= "https://opencode.ai/zen/v1/models/gemini-3.1-pro:streamGenerateContent?alt=sse"
           (@#'llm/endpoint-url :google-generative-ai "https://opencode.ai/zen/v1" "gemini-3.1-pro")))
  (t/is (= "https://api.openai.com/v1/responses"
           (@#'llm/endpoint-url :openai-responses "https://api.openai.com/v1" "gpt-5.4"))))

;; ─── OpenAI Codex + Azure responses (shared processor, new URL + auth) ────

(defn- test-jwt
  "A fake JWT carrying the chatgpt_account_id claim (codex request auth)."
  [account-id]
  (let [enc (fn [s] (.encodeToString (java.util.Base64/getUrlEncoder)
                                     (.getBytes s "UTF-8")))
        payload (str "{\"https://api.openai.com/auth\":{\"chatgpt_account_id\":\""
                     account-id "\"}}")]
    (str (enc "{\"alg\":\"none\"}") "." (enc payload) ".sig")))

(defn- codex-model
  "A minimal codex Model record for payload tests."
  [& [overrides]]
  (m/map->Model
   (merge {:id "gpt-5.4" :name "GPT-5.4" :provider :openai-codex
           :api :openai-codex-responses :base-url "https://chatgpt.com/backend-api"
           :reasoning true :input [:text :image]
           :cost {:input 2.5 :output 15 :cache-read 0.25 :cache-write 0}
           :context-window 272000 :max-tokens 128000
           :thinking-level-map {:minimal "low" :low "low" :medium "medium"
                                :high "high" :xhigh "xhigh" :max "max"}}
          overrides)))

(t/deftest test-llm-codex-url
  (t/is (= "https://chatgpt.com/backend-api/codex/responses"
           (@#'llm/codex-endpoint-url "https://chatgpt.com/backend-api")))
  (t/is (= "https://chatgpt.com/backend-api/codex/responses"
           (@#'llm/codex-endpoint-url nil))
        "blank base → the default codex base")
  (t/is (= "https://custom.example/codex/responses"
           (@#'llm/codex-endpoint-url "https://custom.example/codex"))
        "base ending in /codex gains /responses")
  (t/is (= "https://custom.example/codex/responses"
           (@#'llm/codex-endpoint-url "https://custom.example/codex/responses/"))
        "the full path passes through (trailing slash trimmed)"))

(t/deftest test-llm-codex-account-id
  (t/is (= "acc-1" (@#'llm/codex-account-id (test-jwt "acc-1")))
        "chatgpt_account_id claim from the JWT payload")
  (t/is (thrown-with-msg? Exception #"Failed to extract accountId"
                          (@#'llm/codex-account-id "not-a-jwt")))
  (t/is (thrown-with-msg? Exception #"Failed to extract accountId"
                          (@#'llm/codex-account-id
                           (test-jwt ""))) "no claim in the payload"))

(t/deftest test-llm-codex-payload
  (let [msgs [{:role :system :content [{:type :text :text "S"}]}
              {:role :user :content [{:type :text :text "hi"}]}]
        payload (@#'llm/codex-payload (codex-model) :high msgs [] "gpt-5.4"
                                      "sess-123")]
    (t/is (= "gpt-5.4" (:model payload)))
    (t/is (= "S" (:instructions payload)) "the system prompt goes to instructions")
    (t/is (false? (:store payload)))
    (t/is (= "low" (get-in payload [:text :verbosity])))
    (t/is (= ["reasoning.encrypted_content"] (:include payload)))
    (t/is (= "auto" (:tool_choice payload)))
    (t/is (true? (:parallel_tool_calls payload)))
    (t/is (= "sess-123" (:prompt_cache_key payload)))
    (t/is (= {:effort "high" :summary "auto"} (:reasoning payload)))
    (t/is (not-any? #(= "developer" (:role %)) (:input payload))
          "no developer message — the codex envelope uses instructions")
    (t/is (= [{:role "user"
               :content [{:type "input_text" :text "hi"}]}]
             (:input payload))
          "converted messages without the system prompt"))
  (t/testing "no prompt-cache key when caching is off"
    (let [payload (@#'llm/codex-payload (codex-model) nil [] [] "gpt-5.4" nil)]
      (t/is (nil? (:prompt_cache_key payload)))
      (t/is (nil? (:reasoning payload)) "off → no reasoning field (codex thinks by default)")))
  (t/testing "tools + sampling-params merged last"
    (let [tool (tools/make-tool :name "bash" :description "run"
                                :parameters {:type "object"
                                             :properties {:cmd {:type "string"}}}
                                :execute (fn [_] nil))
          payload (@#'llm/codex-payload (codex-model {:sampling-params {:temperature 0.2}})
                                        :high [] [tool] "gpt-5.4" nil)]
      (t/is (= "bash" (get-in payload [:tools 0 :name])))
      (t/is (= 0.2 (:temperature payload)) "sampling-params win over the envelope"))))

(t/deftest test-llm-codex-request-headers
  (let [token (test-jwt "acc-9")
        headers (@#'llm/codex-request-headers token "sess-123")]
    (t/is (= (str "Bearer " token) (get headers "Authorization")))
    (t/is (= "acc-9" (get headers "chatgpt-account-id")))
    (t/is (= "pi" (get headers "originator")))
    (t/is (str/starts-with? (get headers "User-Agent") "pi (") "pi User-Agent (pi buildBaseCodexHeaders)")
    (t/is (= "responses=experimental" (get headers "OpenAI-Beta")))
    (t/is (= "text/event-stream" (get headers "Accept")))
    (t/is (= "sess-123" (get headers "session-id")))
    (t/is (= "sess-123" (get headers "x-client-request-id"))))
  (t/testing "no session headers when caching is off"
    (let [headers (@#'llm/codex-request-headers (test-jwt "acc-9") nil)]
      (t/is (nil? (get headers "session-id")))
      (t/is (nil? (get headers "x-client-request-id"))))))

(t/deftest test-llm-azure-url
  (t/testing "normalizeAzureBaseUrl forces azure hosts to /openai/v1"
    (t/is (= "https://res.openai.azure.com/openai/v1"
             (@#'llm/normalize-azure-base-url "https://res.openai.azure.com")))
    (t/is (= "https://res.openai.azure.com/openai/v1"
             (@#'llm/normalize-azure-base-url "https://res.openai.azure.com/openai/v1/responses")))
    (t/is (= "https://res.cognitiveservices.azure.com/openai/v1"
             (@#'llm/normalize-azure-base-url "https://res.cognitiveservices.azure.com/openai/")))
    (t/is (= "https://res.ai.azure.com/openai/v1"
             (@#'llm/normalize-azure-base-url "https://res.ai.azure.com")))
    (t/is (= "https://res.openai.azure.com/custom/path"
             (@#'llm/normalize-azure-base-url "https://res.openai.azure.com/custom/path"))
          "other paths pass through"))
  (t/testing "azureEndpointUrl appends the deployment + api version"
    (t/is (= "https://res.openai.azure.com/openai/v1/deployments/gpt-5.4/responses?api-version=v1"
             (@#'llm/azure-endpoint-url "https://res.openai.azure.com/openai/v1"
                                        "gpt-5.4" "v1"))))
  (t/testing "deployment name resolution (AZURE_OPENAI_DEPLOYMENT_NAME_MAP)"
    (with-redefs [llm/getenv (fn [k]
                               (when (= k "AZURE_OPENAI_DEPLOYMENT_NAME_MAP")
                                 "gpt-5.4=my-gpt-54, gpt-5.5 = my-gpt-55"))]
      (t/is (= "my-gpt-54" (@#'llm/azure-deployment-name "gpt-5.4")))
      (t/is (= "my-gpt-55" (@#'llm/azure-deployment-name "gpt-5.5")))
      (t/is (= "gpt-4o" (@#'llm/azure-deployment-name "gpt-4o"))
            "unmapped models use the model id"))
    (with-redefs [llm/getenv (fn [_] nil)]
      (t/is (= "gpt-5.4" (@#'llm/azure-deployment-name "gpt-5.4")))))
  (t/testing "resolved config: env base wins, resource name derives, api version"
    (with-redefs [llm/getenv (fn [k]
                               (case k
                                 "AZURE_OPENAI_BASE_URL" "https://res.openai.azure.com"
                                 "AZURE_OPENAI_API_VERSION" "2024-02-01"
                                 nil))]
      (t/is (= {:base-url "https://res.openai.azure.com/openai/v1" :api-version "2024-02-01"}
               (@#'llm/azure-resolved-config ""))))
    (with-redefs [llm/getenv (fn [k]
                               (when (= k "AZURE_OPENAI_RESOURCE_NAME") "myres"))]
      (t/is (= {:base-url "https://myres.openai.azure.com/openai/v1" :api-version "v1"}
               (@#'llm/azure-resolved-config ""))))
    (with-redefs [llm/getenv (fn [_] nil)]
      (t/is (= {:base-url "https://model.example/v1" :api-version "v1"}
               (@#'llm/azure-resolved-config "https://model.example/v1"))
            "model base-url fallback")
      (t/is (thrown-with-msg? Exception #"Azure OpenAI base URL is required"
                              (@#'llm/azure-resolved-config ""))))))

(t/deftest test-llm-azure-payload-uses-deployment
  (let [payload (@#'llm/responses-payload (codex-model) :high [] [] "my-deployment"
                                          :short "sess-123")]
    (t/is (= "my-deployment" (:model payload))
          "the deployment name is the model field on the wire")
    (t/is (= "sess-123" (:prompt_cache_key payload)))
    (t/is (= {:effort "high" :summary "auto"} (:reasoning payload)))))

(t/deftest test-llm-codex-invalid-token-reports-error
  (m/load-catalogs!)
  (let [errors (atom [])]
    @(llm/send-message {:provider :openai-codex
                        :api-key "not-a-jwt"
                        :model "gpt-5.4"
                        :on-error (fn [e] (swap! errors conj e))})
    (t/is (= ["Failed to extract accountId from token"] @errors)
          "an invalid codex token reports via on-error (never hangs the loop)")))

(t/deftest test-llm-azure-missing-config-reports-error
  (m/load-catalogs!)
  (let [errors (atom [])]
    ;; the config is resolved inside the request future, so the env redef
    ;; must stay active until the future completes
    (with-redefs [llm/getenv (fn [_] nil)]
      @(llm/send-message {:provider :azure-openai-responses
                          :api-key "sk"
                          :model "gpt-5.4"
                          :on-error (fn [e] (swap! errors conj e))}))
    (t/is (= ["Azure OpenAI base URL is required. Set AZURE_OPENAI_BASE_URL or AZURE_OPENAI_RESOURCE_NAME."]
             @errors)
          "a missing azure base config reports via on-error (never hangs the loop)")))

;; ─── OpenAI Responses messages + payload ──────────────────────────────────

(defn- responses-model
  "A minimal gpt-5-style Model record for payload tests."
  [& [overrides]]
  (m/map->Model
   (merge {:id "gpt-5.4" :name "GPT-5.4" :provider :openai
           :api :openai-responses :base-url "https://api.openai.com/v1"
           :reasoning true :input [:text]
           :cost {:input 2.5 :output 15 :cache-read 0.25 :cache-write 0}
           :context-window 272000 :max-tokens 128000
           :compat {:supports-strict-mode true}
           :thinking-level-map {:off "none" :minimal nil :low "low" :medium "medium"
                                :high "high" :xhigh "xhigh" :max nil}}
          overrides)))

(t/deftest test-llm-responses-messages
  (let [model (responses-model)
        msgs [{:role :system :content [{:type :text :text "You are helpful"}]}
              {:role :user :content [{:type :text :text "hi"}]}
              {:role :assistant :content [{:type :text :text "answer"}]
               :tool-calls [{:id "call_abc|fc_123" :name "read" :arguments {:path "x"}}]}
              {:role :tool :content [{:type :tool-result :tool_use_id "call_abc|fc_123" :content "file"}]}]
        items (@#'llm/responses-messages model msgs)]
    (t/is (= {:role "developer" :content "You are helpful"} (first items))
          "reasoning models get the system prompt as a developer message")
    (t/is (= {:role "user" :content [{:type "input_text" :text "hi"}]}
             (second items)))
    (let [assistant (nth items 2)
          tool-call (nth items 3)]
      (t/is (= {:type "message" :role "assistant"
                :content [{:type "output_text" :text "answer" :annotations []}]
                :status "completed" :id "msg_pi_1"}
               assistant))
      (t/is (= {:type "function_call" :call_id "call_abc" :id "fc_123"
                :name "read" :arguments "{\"path\":\"x\"}"}
               tool-call)
            "tool calls replay with the split call_id and the stored item id"))
    (t/is (= {:type "function_call_output" :call_id "call_abc" :output "file"}
             (nth items 4))
          "tool results reference the call_id part")))

(t/deftest test-llm-responses-messages-non-reasoning-system-role
  (let [model (responses-model {:reasoning false})
        items (@#'llm/responses-messages
               model
               [{:role :system :content [{:type :text :text "S"}]}
                {:role :user :content [{:type :text :text "u"}]}])]
    (t/is (= {:role "system" :content "S"} (first items))
          "non-reasoning models get the system prompt as a system message")
    (t/is (= [{:role "user" :content [{:type "input_text" :text "u"}]}]
             (rest items)))))

(t/deftest test-llm-responses-messages-cross-provider-tool-call
  ;; a tool call id without the `|` separator replays call_id-only (pi's
  ;; different-model path — the id is omitted to avoid fc_/rs_ pairing
  ;; validation)
  (let [model (responses-model)
        items (@#'llm/responses-messages
               model
               [{:role :user :content [{:type :text :text "u"}]}
                {:role :assistant :content []
                 :tool-calls [{:id "toolu_01ABC" :name "bash" :arguments {:cmd "ls"}}]}
                {:role :tool :content [{:type :tool-result :tool_use_id "toolu_01ABC" :content "out"}]}])
        tool-call (nth items 1)]
    (t/is (= {:type "function_call" :call_id "toolu_01ABC" :id nil
              :name "bash" :arguments "{\"cmd\":\"ls\"}"}
             tool-call))
    (t/is (= {:type "function_call_output" :call_id "toolu_01ABC" :output "out"}
             (nth items 2)))))

(t/deftest test-llm-responses-payload
  (t/testing "thinking on → reasoning {effort, summary} + include"
    (let [payload (@#'llm/responses-payload (responses-model) :high [] [] "gpt-5.4" nil nil)]
      (t/is (= {:effort "high" :summary "auto"} (:reasoning payload)))
      (t/is (= ["reasoning.encrypted_content"] (:include payload)))
      (t/is (= 128000 (:max_output_tokens payload)))
      (t/is (false? (:store payload)))
      (t/is (true? (:stream payload)))))
  (t/testing "thinking off with an explicit off value → reasoning {effort 'none'}"
    (let [payload (@#'llm/responses-payload (responses-model) nil [] [] "gpt-5.4" nil nil)]
      (t/is (= {:effort "none"} (:reasoning payload)))
      (t/is (nil? (:include payload)))))
  (t/testing "off pinned to null (always-thinking) → no reasoning param"
    (let [model (responses-model {:thinking-level-map {:off nil :low "low" :medium "medium"
                                                       :high "high" :xhigh "xhigh" :max "max"}})
          payload (@#'llm/responses-payload model nil [] [] "gpt-5" nil nil)]
      (t/is (nil? (:reasoning payload)))
      (t/is (nil? (:include payload)))))
  (t/testing "xai always includes the reasoning content; off:null means
             always-thinking (no reasoning param)"
    (let [model (responses-model {:provider :xai :thinking-level-map {:off nil :minimal nil
                                                                      :low "low" :medium "medium" :high "high"}})
          payload (@#'llm/responses-payload model nil [] [] "grok-4.5" nil nil)]
      (t/is (nil? (:reasoning payload)))
      (t/is (= ["reasoning.encrypted_content"] (:include payload)))))
  (t/testing "max_output_tokens floors at 16 (pi #6265)"
    (let [payload (@#'llm/responses-payload (responses-model {:max-tokens 10}) :high [] [] "gpt-5.4" nil nil)]
      (t/is (= 16 (:max_output_tokens payload)))))
  (t/testing "tools carry the JSON schema + strict flag when supported"
    (let [tool (tools/map->Tool {:name "read" :label "Read" :description "d"
                                 :parameters {:type "object" :properties {:path {:type "string"}}}})
          payload (@#'llm/responses-payload (responses-model) :high [] [tool] "gpt-5.4" nil nil)]
      (t/is (= [{:type "function" :name "read" :description "d"
                 :parameters {:type "object" :properties {:path {:type "string"}}}
                 :strict false}]
               (:tools payload)))))
  (t/testing "no strict flag when the provider doesn't support strict mode"
    (let [tool (tools/map->Tool {:name "read" :label "Read" :description "d"
                                 :parameters {:type "object"}})
          payload (@#'llm/responses-payload
                   (responses-model {:compat {}}) :high [] [tool] "grok-4.5" nil nil)]
      (t/is (= [{:type "function" :name "read" :description "d"
                 :parameters {:type "object"}}]
               (:tools payload))))))

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
  (t/is (= 28 (count (m/get-providers))))
  (doseq [p [:opencode-go :opencode :deepseek :github-copilot :openai :xai
             :openai-codex :azure-openai-responses :anthropic :google :groq
             :cerebras :huggingface :moonshotai :moonshotai-cn :xiaomi
             :xiaomi-token-plan-cn :xiaomi-token-plan-ams :xiaomi-token-plan-sgp
             :qwen-token-plan :qwen-token-plan-cn :qwen-token-plan-individual
             :minimax :minimax-cn :nvidia :openrouter :fireworks
             :vercel-ai-gateway]]
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
  (t/is (= [:off] (llm/get-supported-thinking-levels (tmodel :reasoning false)))
        "non-reasoning models only support :off")
  (t/is (= [:off :minimal :low :medium :high]
           (llm/get-supported-thinking-levels (tmodel :tlm nil)))
        "no map → everything except :xhigh/:max")
  (t/is (= [:off :high :max]
           (llm/get-supported-thinking-levels
            (tmodel :tlm {:minimal nil :low nil :medium nil :high "high" :max "max"})))
        "null map values mark levels unsupported")
  (t/is (= [:high]
           (llm/get-supported-thinking-levels
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
    (t/is (= {:reasoning {:effort "high"}}
             (@#'llm/openai-thinking-params
              (tmodel :compat {:thinking-format :openrouter}) :high))
          "openrouter on: nested reasoning: {effort}")
    (t/is (= {:reasoning {:effort "none"}}
             (@#'llm/openai-thinking-params
              (tmodel :compat {:thinking-format :openrouter}) nil))
          "openrouter off: reasoning: {effort: none} (pi)")
    (t/is (= {}
             (@#'llm/openai-thinking-params
              (tmodel :compat {:thinking-format :openrouter} :tlm {:off nil}) nil))
          "openrouter off pinned to null → no params (always thinking)")
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

(t/deftest ^:slow test-llm-anthropic-bearer-only
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

(t/deftest ^:slow test-llm-resolves-key-from-auth
  ;; send-message resolves the key itself when the caller passes none (pi
  ;; prepareRequest) — an auth.edn credential lets a bare call proceed
  ;; instead of erroring with an empty Authorization header.
  (m/load-catalogs!)
  (with-redefs [auth/auth-atom (atom {:opencode-go {:key "file-key"}})
                auth/getenv (fn [_] nil)]
    (let [errors (atom [])]
      @(llm/send-message {:provider :opencode-go
                          :model "deepseek-v4-flash"
                          :on-error (fn [e] (swap! errors conj e))})
      (t/is (not-any? #(str/includes? (or % "") "No API key") @errors)
            "proceeds with the auth.edn key (fails on the network, not on auth)"))))

;; ─── OpenAI Responses end-to-end (mock server) ────────────────────────────

(t/deftest ^:slow test-llm-responses-stream-end-to-end
  (m/load-catalogs!)
  (let [ss (java.net.ServerSocket. 0)
        port (.getLocalPort ss)
        request-body (atom nil)
        request-headers (atom {})
        _ (doto (Thread.
                 (fn []
                   (try
                     (let [s (.accept ss)
                           din (java.io.DataInputStream. (.getInputStream s))
                           rdr (java.io.BufferedReader. (java.io.InputStreamReader. din))
                           ;; capture the request headers + body: read the
                           ;; headers to the blank line, then the
                           ;; Content-Length body (single-line JSON)
                           clen (atom 0)
                           req-headers (atom {})
                           _ (loop []
                               (let [line (.readLine rdr)]
                                 (when-not (empty? line)
                                   (when (str/starts-with? (str/lower-case (or line "")) "content-length:")
                                     (reset! clen (Long/parseLong (str/trim (subs line 15)))))
                                   (when-let [colon (str/index-of line ":")]
                                     (reset! req-headers
                                             (assoc @req-headers
                                                    (str/trim (subs line 0 colon))
                                                    (str/trim (subs line (inc colon))))))
                                   (recur))))
                           _ (reset! request-headers @req-headers)
                           ;; Read the body from the SAME reader: the
                           ;; BufferedReader may already hold body bytes past
                           ;; the header terminator in its buffer — reading
                           ;; din directly would deadlock on those.
                           body-sb (StringBuilder.)
                           _ (loop [n 0]
                               (if (< n @clen)
                                 (let [buf (char-array (- @clen n))
                                       m (.read rdr buf)]
                                   (when (pos? m)
                                     (.append body-sb buf 0 m)
                                     (recur (+ n m))))
                                 nil))
                           _ (reset! request-body (str body-sb))
                           out (.getOutputStream s)
                           stream-body (str "event: response.output_item.added\n"
                                            "data: {\"output_index\":0,\"item\":{\"type\":\"function_call\",\"id\":\"fc_1\",\"call_id\":\"call_1\",\"name\":\"bash\",\"arguments\":\"\"}}\n\n"
                                            "event: response.function_call_arguments.delta\n"
                                            "data: {\"output_index\":0,\"delta\":\"{\\\"cmd\\\":\\\"ls\\\"}\"}\n\n"
                                            "event: response.output_text.delta\n"
                                            "data: {\"output_index\":0,\"delta\":\"hello\"}\n\n"
                                            "event: response.completed\n"
                                            "data: {\"response\":{\"id\":\"resp_1\",\"status\":\"completed\",\"usage\":{\"input_tokens\":100,\"output_tokens\":20,\"total_tokens\":120,\"input_tokens_details\":{\"cached_tokens\":10},\"output_tokens_details\":{\"reasoning_tokens\":5}}}}\n\n")]
                       (.write out (.getBytes (str "HTTP/1.1 200 OK\r\n"
                                                   "Content-Type: text/event-stream\r\n"
                                                   "Content-Length: " (count stream-body) "\r\n\r\n"
                                                   stream-body)))
                       (.flush out)
                       (.close s))
                     (catch Exception _ nil))))
            (.setDaemon true)
            (.start))
        text (atom "")
        tool-calls (atom [])
        done-reason (atom nil)
        usage (atom nil)
        errors (atom [])
        fut (llm/send-message {:provider :openai
                               :api-key "sk-test"
                               :base-url (str "http://localhost:" port "/responses")
                               :model "gpt-5.4"
                               :messages [{:role :system :content [{:type :text :text "S"}]}
                                          {:role :user :content [{:type :text :text "hi"}]}]
                               :session-id "sess-123"
                               :thinking :high
                               :on-text (fn [t] (swap! text str t))
                               :on-tool-call (fn [tc] (swap! tool-calls conj tc))
                               :on-done (fn [r] (reset! done-reason r))
                               :on-usage (fn [u] (reset! usage u))
                               :on-error (fn [e] (swap! errors conj e))})]
    (try
      @fut
      (t/is (= [] @errors) (str "no stream errors: " @errors))
      (t/is (= "hello" @text))
      (t/is (= [{:id "call_1|fc_1" :name "bash" :arguments "" :index 0}
                {:arguments "{\"cmd\":\"ls\"}" :index 0}]
               @tool-calls)
            "tool-call start + argument deltas (the loop's accumulator merges them)")
      (t/is (= :tool-use @done-reason)
            "completed with a tool call maps to :tool-use (pi remap)")
      (t/is (= {:input_tokens 100 :output_tokens 20 :total_tokens 120
                :input_tokens_details {:cached_tokens 10}
                :output_tokens_details {:reasoning_tokens 5}
                :cost {:input 2.25E-4 :output 3.0E-4 :cache-read 2.5E-6
                       :cache-write 0.0 :total 5.275E-4}}
               @usage)
            "usage gains the per-message cost (gpt-5.4 rates: 90 input = 2.5/1M)")
      (t/testing "the wire payload is responses-shaped"
        (let [payload (json/parse-string @request-body true)]
          (t/is (= "sess-123" (:prompt_cache_key payload))
                "short retention sends the prompt cache key from the session id")
          (t/is (= "sess-123" (get @request-headers "session_id"))
                "openai affinity headers carry the session id")
          (t/is (= "sess-123" (get @request-headers "x-client-request-id")))
          (t/is (= "gpt-5.4" (:model payload)))
          (t/is (= {:role "developer" :content "S"} (first (:input payload))))
          (t/is (= {:effort "high" :summary "auto"} (:reasoning payload)))
          (t/is (= ["reasoning.encrypted_content"] (:include payload)))
          (t/is (= 128000 (:max_output_tokens payload)))
          (t/is (false? (:store payload)))))
      (finally
        (.close ss)))))

;; ─── OpenAI Responses caching + copilot headers (pi parity) ───────────────

(t/deftest test-llm-responses-cache-params
  (t/testing "short retention (default) sends the prompt cache key from the session id"
    (let [payload (@#'llm/responses-payload (responses-model) :high [] [] "gpt-5.4"
                                            :short "sess-123")]
      (t/is (= "sess-123" (:prompt_cache_key payload)))
      (t/is (nil? (:prompt_cache_retention payload)))))
  (t/testing "long retention → prompt_cache_retention 24h (absent compat defaults
             to true, pi getCompat)"
    (let [payload (@#'llm/responses-payload (responses-model) :high [] [] "gpt-5.4"
                                            :long "sess-123")]
      (t/is (= "24h" (:prompt_cache_retention payload))))
    (let [payload (@#'llm/responses-payload
                   (responses-model {:compat {:supports-long-cache-retention false}})
                   :high [] [] "gpt-5.4" :long "sess-123")]
      (t/is (nil? (:prompt_cache_retention payload)) "unsupported models don't get the retention param")))
  (t/testing "none → no key; explicit prompt-cache mode on cache-enabled models"
    (let [payload (@#'llm/responses-payload (responses-model) :high [] [] "gpt-5.4" :none nil)]
      (t/is (nil? (:prompt_cache_key payload)))
      (t/is (nil? (:prompt_cache_options payload)) "gpt-5.4 has no explicit-prompt-cache compat"))
    (let [model (responses-model {:compat {:supports-explicit-prompt-cache-mode true}})
          payload (@#'llm/responses-payload model :high [] [] "gpt-5.4" :none nil)]
      (t/is (= {:mode "explicit"} (:prompt_cache_options payload)))))
  (t/testing "session ids over 64 chars are clamped (pi clampOpenAIPromptCacheKey)"
    (let [long-id (apply str (repeat 80 "x"))
          payload (@#'llm/responses-payload (responses-model) :high [] [] "gpt-5.4" :short long-id)]
      (t/is (= 64 (count (:prompt_cache_key payload))))
      (t/is (str/starts-with? long-id (:prompt_cache_key payload))
            "the clamped key is the first 64 chars of the session id"))))

(t/deftest test-llm-responses-affinity-headers
  (t/testing "openai format → session_id + x-client-request-id"
    (t/is (= {"session_id" "s1" "x-client-request-id" "s1"}
             (@#'llm/responses-affinity-headers (responses-model) "s1"))))
  (t/testing "opencode zen (openai-nosession compat) → x-client-request-id only"
    (t/is (= {"x-client-request-id" "s1"}
             (@#'llm/responses-affinity-headers
              (responses-model {:compat {:session-affinity-format :openai-nosession}}) "s1"))))
  (t/testing "openrouter detection (provider or base-url) → x-session-id"
    (t/is (= {"x-session-id" "s1"}
             (@#'llm/responses-affinity-headers
              (responses-model {:provider :openrouter}) "s1")))
    (t/is (= {"x-session-id" "s1"}
             (@#'llm/responses-affinity-headers
              (responses-model {:base-url "https://openrouter.ai/api/v1"}) "s1"))))
  (t/testing "no session id → no headers"
    (t/is (nil? (@#'llm/responses-affinity-headers (responses-model) nil)))))

(t/deftest test-llm-copilot-dynamic-headers
  (t/testing "X-Initiator from the last message role (pi inferCopilotInitiator)"
    (t/is (= {"X-Initiator" "user" "Openai-Intent" "conversation-edits"}
             (@#'llm/copilot-dynamic-headers
              [{:role :user :content [{:type :text :text "hi"}]}])))
    (t/is (= {"X-Initiator" "agent" "Openai-Intent" "conversation-edits"}
             (@#'llm/copilot-dynamic-headers
              [{:role :user :content [{:type :text :text "hi"}]}
               {:role :assistant :content [{:type :text :text "ok"}]}])))
    (t/is (= {"X-Initiator" "agent" "Openai-Intent" "conversation-edits"}
             (@#'llm/copilot-dynamic-headers
              [{:role :user :content [{:type :text :text "hi"}]}
               {:role :tool :content [{:type :tool-result :tool_use_id "t" :content "out"}]}]))))
  (t/testing "Copilot-Vision-Request when any user/tool-result message has images"
    (t/is (= {"X-Initiator" "user" "Openai-Intent" "conversation-edits"
              "Copilot-Vision-Request" "true"}
             (@#'llm/copilot-dynamic-headers
              [{:role :user :content [{:type :text :text "look"}
                                      {:type :image :data "AA" :mime-type "image/png"}]}])))
    (t/is (= {"X-Initiator" "agent" "Openai-Intent" "conversation-edits"
              "Copilot-Vision-Request" "true"}
             (@#'llm/copilot-dynamic-headers
              [{:role :user :content [{:type :text :text "hi"}]}
               {:role :tool :content [{:type :tool-result :tool_use_id "t" :content "out"}]
                :images [{:type :image :data "AA" :mime-type "image/png"}]}]))))
  (t/testing "no images → no vision header"
    (t/is (nil? (get (@#'llm/copilot-dynamic-headers
                      [{:role :user :content [{:type :text :text "hi"}]}])
                     "Copilot-Vision-Request")))))

(t/deftest test-llm-responses-tool-result-placeholder
  (let [model (responses-model {:input [:text]})]
    (t/testing "images on a text-only model → pi's (see attached image)"
      (t/is (= "(see attached image)"
               (@#'llm/responses-tool-result-output
                model {:content [{:type :tool-result :tool_use_id "t" :content ""}]
                       :images [{:type :image :data "AA" :mime-type "image/png"}]}))))
    (t/testing "no text and no images → (no tool output)"
      (t/is (= "(no tool output)"
               (@#'llm/responses-tool-result-output
                model {:content [{:type :tool-result :tool_use_id "t" :content ""}]}))))
    (t/testing "plain text passes through"
      (t/is (= "out"
               (@#'llm/responses-tool-result-output
                model {:content [{:type :tool-result :tool_use_id "t" :content "out"}]}))))))

(t/deftest test-llm-responses-request-headers
  ;; the affinity headers are gated on caching (pi cacheSessionId): :none
  ;; sends neither the key nor the affinity headers
  (let [model (responses-model)
        provider (m/map->Provider {:id :openai :name "OpenAI" :api-types #{:openai-responses}
                                   :models [model] :env-vars [] :default-model nil})]
    (t/testing "short (default) with a session id → affinity headers"
      (let [h (#'llm/responses-request-headers model provider "sk" "sess-1" :short [])]
        (t/is (= "sess-1" (get h "session_id")))
        (t/is (= "sess-1" (get h "x-client-request-id")))))
    (t/testing ":none → no affinity headers even with a session id"
      (let [h (#'llm/responses-request-headers model provider "sk" "sess-1" :none [])]
        (t/is (nil? (get h "session_id")))
        (t/is (nil? (get h "x-client-request-id")))))
    (t/testing "no session id → no affinity headers"
      (let [h (#'llm/responses-request-headers model provider "sk" nil :short [])]
        (t/is (nil? (get h "session_id")))))
    (t/testing "copilot requests carry the dynamic headers (incl. over :none)"
      (let [copilot (responses-model {:provider :github-copilot})
            p2 (m/map->Provider {:id :github-copilot :name "Copilot"
                                 :api-types #{:openai-responses} :models [copilot]
                                 :env-vars [] :default-model nil})
            h (#'llm/responses-request-headers
               copilot p2 "sk" "sess-1" :none
               [{:role :user :content [{:type :text :text "hi"}]}])]
        (t/is (= "user" (get h "X-Initiator")))
        (t/is (= "conversation-edits" (get h "Openai-Intent")))
        (t/is (nil? (get h "session_id")) "copilot still skips affinity headers under :none")))))

(t/deftest ^:slow test-llm-copilot-responses-end-to-end
  ;; the copilot responses path: per-request dynamic headers (X-Initiator /
  ;; Openai-Intent / Copilot-Vision-Request) + the static COPILOT headers +
  ;; the session-affinity headers, on the real send-message flow
  (m/load-catalogs!)
  (let [ss (java.net.ServerSocket. 0)
        port (.getLocalPort ss)
        request-body (atom nil)
        request-headers (atom {})
        _ (doto (Thread.
                 (fn []
                   (try
                     (let [s (.accept ss)
                           din (java.io.DataInputStream. (.getInputStream s))
                           rdr (java.io.BufferedReader. (java.io.InputStreamReader. din))
                           clen (atom 0)
                           req-headers (atom {})
                           _ (loop []
                               (let [line (.readLine rdr)]
                                 (when-not (empty? line)
                                   (when (str/starts-with? (str/lower-case (or line "")) "content-length:")
                                     (reset! clen (Long/parseLong (str/trim (subs line 15)))))
                                   (when-let [colon (str/index-of line ":")]
                                     (reset! req-headers
                                             (assoc @req-headers
                                                    (str/trim (subs line 0 colon))
                                                    (str/trim (subs line (inc colon))))))
                                   (recur))))
                           _ (reset! request-headers @req-headers)
                           ;; Read the body from the SAME reader: the
                           ;; BufferedReader may already hold body bytes past
                           ;; the header terminator in its buffer — reading
                           ;; din directly would deadlock on those.
                           body-sb (StringBuilder.)
                           _ (loop [n 0]
                               (if (< n @clen)
                                 (let [buf (char-array (- @clen n))
                                       m (.read rdr buf)]
                                   (when (pos? m)
                                     (.append body-sb buf 0 m)
                                     (recur (+ n m))))
                                 nil))
                           _ (reset! request-body (str body-sb))
                           out (.getOutputStream s)
                           stream-body (str "event: response.output_text.delta\n"
                                            "data: {\"output_index\":0,\"delta\":\"hello\"}\n\n"
                                            "event: response.completed\n"
                                            "data: {\"response\":{\"id\":\"resp_1\",\"status\":\"completed\",\"usage\":{\"input_tokens\":10,\"output_tokens\":5,\"total_tokens\":15}}}\n\n")]
                       (.write out (.getBytes (str "HTTP/1.1 200 OK\r\n"
                                                   "Content-Type: text/event-stream\r\n"
                                                   "Content-Length: " (count stream-body) "\r\n\r\n"
                                                   stream-body)))
                       (.flush out)
                       (.close s))
                     (catch Exception _ nil))))
            (.setDaemon true)
            (.start))
        text (atom "")
        done-reason (atom nil)
        errors (atom [])
        fut (llm/send-message {:provider :github-copilot
                               :api-key "sk-test"
                               :base-url (str "http://localhost:" port "/responses")
                               :model "gpt-5.4"
                               :messages [{:role :system :content [{:type :text :text "S"}]}
                                          {:role :user :content [{:type :text :text "hi"}
                                                                 {:type :image :data "AA" :mime-type "image/png"}]}]
                               :session-id "sess-copilot"
                               :on-text (fn [t] (swap! text str t))
                               :on-done (fn [r] (reset! done-reason r))
                               :on-error (fn [e] (swap! errors conj e))})]
    (try
      @fut
      (t/is (= [] @errors) (str "no stream errors: " @errors))
      (t/is (= "hello" @text))
      (t/is (= :stop @done-reason))
      (t/testing "copilot dynamic + affinity headers on the wire"
        (t/is (= "user" (get @request-headers "X-Initiator"))
              "last message is a user message with an image")
        (t/is (= "conversation-edits" (get @request-headers "Openai-Intent")))
        (t/is (= "true" (get @request-headers "Copilot-Vision-Request"))
              "user message has image content")
        (t/is (= "sess-copilot" (get @request-headers "session_id")))
        (t/is (= "sess-copilot" (get @request-headers "x-client-request-id")))
        (t/is (= "GitHubCopilotChat/0.35.0" (get @request-headers "User-Agent"))
              "static COPILOT_STATIC_HEADERS merge in"))
      (t/testing "the payload is responses-shaped with the cache key"
        (let [payload (json/parse-string @request-body true)]
          (t/is (= "sess-copilot" (:prompt_cache_key payload)))
          (t/is (= {:role "developer" :content "S"} (first (:input payload))))))
      (finally
        (.close ss)))))

;; ─── Codex + Azure responses end-to-end (mock server) ─────────────────────

(t/deftest ^:slow test-llm-codex-responses-end-to-end
  (m/load-catalogs!)
  (let [token (test-jwt "acc-1")
        ss (java.net.ServerSocket. 0)
        port (.getLocalPort ss)
        request-line (atom nil)
        request-body (atom nil)
        request-headers (atom {})
        _ (doto (Thread.
                 (fn []
                   (try
                     (let [s (.accept ss)
                           din (java.io.DataInputStream. (.getInputStream s))
                           rdr (java.io.BufferedReader. (java.io.InputStreamReader. din))
                           line1 (.readLine rdr)
                           _ (reset! request-line line1)
                           clen (atom 0)
                           req-headers (atom {})
                           _ (loop []
                               (let [line (.readLine rdr)]
                                 (when-not (empty? line)
                                   (when (str/starts-with? (str/lower-case (or line "")) "content-length:")
                                     (reset! clen (Long/parseLong (str/trim (subs line 15)))))
                                   (when-let [colon (str/index-of line ":")]
                                     (reset! req-headers
                                             (assoc @req-headers
                                                    (str/trim (subs line 0 colon))
                                                    (str/trim (subs line (inc colon))))))
                                   (recur))))
                           _ (reset! request-headers @req-headers)
                           body-sb (StringBuilder.)
                           _ (loop [n 0]
                               (if (< n @clen)
                                 (let [buf (char-array (- @clen n))
                                       m (.read rdr buf)]
                                   (when (pos? m)
                                     (.append body-sb buf 0 m)
                                     (recur (+ n m))))
                                 nil))
                           _ (reset! request-body (str body-sb))
                           out (.getOutputStream s)
                           stream-body (str "event: response.output_text.delta\n"
                                            "data: {\"output_index\":0,\"delta\":\"hello\"}\n\n"
                                            "event: response.done\n"
                                            "data: {\"response\":{\"id\":\"resp_1\",\"status\":\"completed\",\"usage\":{\"input_tokens\":10,\"output_tokens\":5,\"total_tokens\":15}}}\n\n")]
                       (.write out (.getBytes (str "HTTP/1.1 200 OK\r\n"
                                                   "Content-Type: text/event-stream\r\n"
                                                   "Content-Length: " (count stream-body) "\r\n\r\n"
                                                   stream-body)))
                       (.flush out)
                       (.close s))
                     (catch Exception _ nil))))
            (.setDaemon true)
            (.start))
        text (atom "")
        done-reason (atom nil)
        errors (atom [])
        fut (llm/send-message {:provider :openai-codex
                               :api-key token
                               :base-url (str "http://localhost:" port)
                               :model "gpt-5.4"
                               :messages [{:role :system :content [{:type :text :text "S"}]}
                                          {:role :user :content [{:type :text :text "hi"}]}]
                               :session-id "sess-codex"
                               :thinking :high
                               :on-text (fn [t] (swap! text str t))
                               :on-done (fn [r] (reset! done-reason r))
                               :on-error (fn [e] (swap! errors conj e))})]
    (try
      @fut
      (t/is (= [] @errors) (str "no stream errors: " @errors))
      (t/is (= "hello" @text))
      (t/is (= :stop @done-reason) "codex response.done → :stop")
      (t/testing "codex headers on the wire"
        (t/is (= (str "Bearer " token) (get @request-headers "Authorization")))
        (t/is (= "acc-1" (get @request-headers "chatgpt-account-id")))
        (t/is (= "pi" (get @request-headers "originator")))
        (t/is (= "responses=experimental" (get @request-headers "OpenAI-Beta")))
        (t/is (= "sess-codex" (get @request-headers "session-id")))
        (t/is (= "sess-codex" (get @request-headers "x-client-request-id"))))
      (t/testing "the codex envelope is on the wire"
        (let [payload (json/parse-string @request-body true)]
          (t/is (= "S" (:instructions payload)))
          (t/is (= "sess-codex" (:prompt_cache_key payload))
                "default :short retention sends the cache key")
          (t/is (= {:effort "high" :summary "auto"} (:reasoning payload)))
          (t/is (= "low" (get-in payload [:text :verbosity])))))
      (finally
        (.close ss)))))

(t/deftest ^:slow test-llm-azure-responses-end-to-end
  (m/load-catalogs!)
  (let [ss (java.net.ServerSocket. 0)
        port (.getLocalPort ss)
        request-line (atom nil)
        request-body (atom nil)
        request-headers (atom {})
        _ (doto (Thread.
                 (fn []
                   (try
                     (let [s (.accept ss)
                           din (java.io.DataInputStream. (.getInputStream s))
                           rdr (java.io.BufferedReader. (java.io.InputStreamReader. din))
                           line1 (.readLine rdr)
                           _ (reset! request-line line1)
                           clen (atom 0)
                           req-headers (atom {})
                           _ (loop []
                               (let [line (.readLine rdr)]
                                 (when-not (empty? line)
                                   (when (str/starts-with? (str/lower-case (or line "")) "content-length:")
                                     (reset! clen (Long/parseLong (str/trim (subs line 15)))))
                                   (when-let [colon (str/index-of line ":")]
                                     (reset! req-headers
                                             (assoc @req-headers
                                                    (str/trim (subs line 0 colon))
                                                    (str/trim (subs line (inc colon))))))
                                   (recur))))
                           _ (reset! request-headers @req-headers)
                           body-sb (StringBuilder.)
                           _ (loop [n 0]
                               (if (< n @clen)
                                 (let [buf (char-array (- @clen n))
                                       m (.read rdr buf)]
                                   (when (pos? m)
                                     (.append body-sb buf 0 m)
                                     (recur (+ n m))))
                                 nil))
                           _ (reset! request-body (str body-sb))
                           out (.getOutputStream s)
                           stream-body (str "event: response.output_text.delta\n"
                                            "data: {\"output_index\":0,\"delta\":\"hi\"}\n\n"
                                            "event: response.completed\n"
                                            "data: {\"response\":{\"id\":\"resp_1\",\"status\":\"completed\",\"usage\":{\"input_tokens\":10,\"output_tokens\":5,\"total_tokens\":15}}}\n\n")]
                       (.write out (.getBytes (str "HTTP/1.1 200 OK\r\n"
                                                   "Content-Type: text/event-stream\r\n"
                                                   "Content-Length: " (count stream-body) "\r\n\r\n"
                                                   stream-body)))
                       (.flush out)
                       (.close s))
                     (catch Exception _ nil))))
            (.setDaemon true)
            (.start))
        text (atom "")
        done-reason (atom nil)
        errors (atom [])
        ;; the azure config is resolved inside the request future, so the
        ;; env redef must stay active until the future completes
        _ (with-redefs [llm/getenv (fn [k]
                                     (case k
                                       "AZURE_OPENAI_BASE_URL" (str "http://localhost:" port)
                                       "AZURE_OPENAI_DEPLOYMENT_NAME_MAP" "gpt-5.4=my-deploy"
                                       nil))]
            (let [f (llm/send-message {:provider :azure-openai-responses
                                       :api-key "sk-azure"
                                       :model "gpt-5.4"
                                       :messages [{:role :system :content [{:type :text :text "S"}]}
                                                  {:role :user :content [{:type :text :text "hi"}]}]
                                       :session-id "sess-azure"
                                       :thinking :high
                                       :on-text (fn [t] (swap! text str t))
                                       :on-done (fn [r] (reset! done-reason r))
                                       :on-error (fn [e] (swap! errors conj e))})]
              @f))]
    (try
      (t/is (= [] @errors) (str "no stream errors: " @errors))
      (t/is (= "hi" @text))
      (t/is (= :stop @done-reason))
      (t/testing "the deployment path + api version are on the wire"
        (t/is (str/includes? @request-line "/deployments/my-deploy/responses?api-version=v1")
              (str "request line: " @request-line)))
      (t/testing "no session-affinity headers for azure (pi)"
        (t/is (= (str "Bearer " "sk-azure") (get @request-headers "Authorization")))
        (t/is (nil? (get @request-headers "session_id")))
        (t/is (nil? (get @request-headers "x-client-request-id"))))
      (t/testing "the deployment name is the model field, the cache key is sent"
        (let [payload (json/parse-string @request-body true)]
          (t/is (= "my-deploy" (:model payload)))
          (t/is (= "sess-azure" (:prompt_cache_key payload)))))
      (finally
        (.close ss)))))
