(ns kmet.app.test-llm
  (:require [clojure.test :as t]
            [clojure.string :as str]
            [kmet.libs.sse :as sse]
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
  (let [errors (atom [])]
    @(llm/send-message {:provider :unknown
                        :api-key "test"
                        :on-error (fn [e] (swap! errors conj e))})
    (t/is (= ["Unknown provider: unknown"] @errors))))

(t/deftest test-llm-legacy-fallback
  ;; legacy :openai/:anthropic have no catalog entry → built-in defaults
  (m/load-catalogs!)
  (t/is (nil? (m/get-provider :openai)))
  (let [fut (llm/send-message {:provider :openai :model "gpt-4o"
                               :api-key "test"
                               :on-error (fn [_])})]
    (t/is (future? fut))))

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
             {:provider :openai
              :on-error (fn [e] (swap! errors conj e))})]
    @fut  ;; wait for future
    (t/is (pos? (count @errors)))
    (t/is (.contains (first @errors) "No API key"))))

(t/deftest test-llm-no-api-key-anthropic
  (let [errors (atom [])
        fut (llm/send-message
             {:provider :anthropic
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
  (let [fut (llm/send-message {:provider :openai :api-key "test"})]
    (t/is (future? fut))))

;; ─── Edge: empty tools list ───────────────────────────────────────────────

(t/deftest test-llm-no-tools
  (let [errors (atom [])
        fut (llm/send-message
             {:provider :openai
              :tools []
              :on-error (fn [e] (swap! errors conj e))})]
    @fut
    (t/is (pos? (count @errors)))))

;; ─── Multiple providers ──────────────────────────────────────────────────

(t/deftest test-llm-provider-keywords
  (t/is (= :openai (-> {:provider :openai} :provider)))
  (t/is (= :anthropic (-> {:provider :anthropic} :provider))))

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
  (t/is (= {:type :text :content "Hi"}
           (sse/parse-google-event
            "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"Hi\"}]}}]}")))
  (t/is (= {:type :thinking :content "think"}
           (sse/parse-google-event
            "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"think\",\"thought\":true}]}}]}")))
  (let [evt (sse/parse-google-event
             "{\"candidates\":[{\"content\":{\"parts\":[{\"functionCall\":{\"name\":\"read\",\"args\":{\"path\":\"a\"}}}]}}]}")]
    (t/is (= :tool-call (:type evt)))
    (t/is (= "read" (:name evt)))
    (t/is (= {:path "a"} (:arguments evt))))
  (t/is (= {:type :done :stop-reason :stop}
           (sse/parse-google-event
            "{\"candidates\":[{\"finishReason\":\"STOP\"}]}")))
  (t/is (= :length (:stop-reason (sse/parse-google-event
                                  "{\"candidates\":[{\"finishReason\":\"MAX_TOKENS\"}]}"))))
  (t/is (= {:type :usage :usage {:input 10 :output 20 :cache-read 0 :cache-write 0
                                 :reasoning 0 :total-tokens 30}}
           (sse/parse-google-event
            "{\"usageMetadata\":{\"promptTokenCount\":10,\"candidatesTokenCount\":20,\"totalTokenCount\":30}}"))))

;; ─── Model headers merge ───────────────────────────────────────────────────

(t/deftest test-model-headers-merge
  (let [model {:headers {"User-Agent" "GitHubCopilotChat/0.35.0"}}
        merged (@#'llm/merge-model-headers {"Content-Type" "application/json"} model)]
    (t/is (= "application/json" (get merged "Content-Type")))
    (t/is (= "GitHubCopilotChat/0.35.0" (get merged "User-Agent")))))

(t/deftest test-max-tokens-key
  (t/is (= :max_tokens (@#'llm/max-tokens-key (tmodel :compat {:max-tokens-field :max-tokens})))
        "max-tokens-field :max-tokens → :max_tokens (opencode/deepseek)")
  (t/is (= :max_completion_tokens (@#'llm/max-tokens-key (tmodel :compat nil)))
        "default → :max_completion_tokens"))

;; ─── Transport total timeout follows the configured idle timeout ──────────

(t/deftest ^:slow test-llm-request-timeout-follows-idle
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
        fut (llm/send-message {:provider :openai
                               :api-key "sk-test"
                               :base-url (str "http://localhost:" port "/v1/chat/completions")
                               :model "gpt-4o"
                               :messages [{:role "user" :content "hi"}]
                               :idle-timeout-ms 1500
                               :on-error (fn [e] (swap! errors conj e))})]
    (try
      @fut
      (t/is (= 1 (count @errors)))
      (let [elapsed (- (System/currentTimeMillis) t0)]
        (t/is (< elapsed 10000)
              (str "errored via the configured timeout (took " elapsed "ms)"))
        (t/is (str/includes? (first @errors) "timed out")))
      (finally
        (.close ss)))))

(t/deftest ^:slow test-llm-body-stall-idle-timeout-completes
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
        fut (llm/send-message {:provider :openai
                               :api-key "sk-test"
                               :base-url (str "http://localhost:" port "/v1/chat/completions")
                               :model "gpt-4o"
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
