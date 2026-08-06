(ns kmet.app.test-llm
  (:require [clojure.test :as t]
            [clojure.string :as str]
            [kmet.app.llm :as llm]
            [kmet.app.tools.core :as tools]))

;; ─── Module loads ─────────────────────────────────────────────────────────

(t/deftest test-llm-loaded
  (t/is (fn? llm/send-message))
  (t/is (string? llm/default-openai-url))
  (t/is (string? llm/anthropic-url)))

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

;; ─── Unknown provider ────────────────────────────────────────────────────

(t/deftest test-llm-unknown-provider
  (let [errors (atom [])
        fut (llm/send-message
             {:provider :unknown
              :api-key "test"
              :on-error (fn [e] (swap! errors conj e))})]
    @fut
    (t/is (pos? (count @errors)))
    (t/is (.contains (first @errors) "Unknown provider"))))

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
