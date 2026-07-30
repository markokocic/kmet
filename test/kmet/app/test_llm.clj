(ns kmet.app.test-llm
  (:require [clojure.test :as t]
            [kmet.app.llm :as llm]
            [kmet.app.tools :as tools]))

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
