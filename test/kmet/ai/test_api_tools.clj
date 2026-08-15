(ns kmet.ai.test-api-tools
  "Wire-level tests for constrained-sampling strict tools across the API
   builders (pi: resolveJsonSchemaStrictSampling + per-wire gating)."
  (:require [clojure.test :as t]
            [kmet.ai.api.shared :as shared]
            [kmet.ai.api.openai-responses :as or]
            [kmet.ai.api.mistral-conversations :as mc]
            [kmet.ai.api.bedrock-converse-stream :as bc]))

(def ^:private plain-tool
  {:name "read" :description "Read a file"
   :parameters {:type "object"
                :properties {"path" {:type "string"}}
                :required ["path"]}})

(def ^:private strict-tool
  (assoc plain-tool
         :constrained-sampling {:type :json-schema :strict :prefer}
         :parameters {:type "object"
                      :properties {"path" {:type "string"}
                                   "offset" {:type "integer"}}
                      :required ["path"]}))

(defn- strictified? [params]
  (and (= ["path" "offset"] (get params "required"))
       (false? (get params "additionalProperties"))
       (some? (get-in params ["properties" "offset" "anyOf"]))))

(t/deftest test-responses-tools
  (t/testing "strict tools get strictified parameters + :strict true"
    (let [tools (or/responses-tools [strict-tool plain-tool] true)]
      (t/is (= true (get-in tools [0 :strict])))
      (t/is (strictified? (get-in tools [0 :parameters])))
      (t/is (= false (get-in tools [1 :strict])))
      (t/is (= (:parameters plain-tool) (get-in tools [1 :parameters])))))
  (t/testing "no :strict key when the provider does not support strict"
    (let [tools (or/responses-tools [strict-tool] nil)]
      (t/is (nil? (get-in tools [0 :strict])))
      (t/is (= (:parameters strict-tool) (get-in tools [0 :parameters]))))))

(t/deftest test-openai-schema
  (t/testing "strict only with provider support (pi: supportsStrictMode !== false)"
    (let [t (shared/tool->openai-schema strict-tool true)]
      (t/is (= true (get-in t [:function :strict])))
      (t/is (strictified? (get-in t [:function :parameters]))))
    (let [t (shared/tool->openai-schema strict-tool false)]
      (t/is (nil? (get-in t [:function :strict])))
      (t/is (= (:parameters strict-tool) (get-in t [:function :parameters]))))))

(t/deftest test-anthropic-schema
  (t/testing "strict only with supportsStrictTools compat"
    (let [t (shared/tool->anthropic-schema strict-tool true)]
      (t/is (= true (:strict t)))
      (t/is (strictified? (:input_schema t))))
    (let [t (shared/tool->anthropic-schema strict-tool false)]
      (t/is (nil? (:strict t)))
      (t/is (= (:parameters strict-tool) (:input_schema t))))))

(t/deftest test-google-schema
  (t/testing "gemini 3+ supports strict; older models do not (pi: supportsGoogleStrictToolSampling)"
    (t/is (true? (shared/google-supports-strict-tool-sampling? "gemini-3-pro-preview")))
    (t/is (true? (shared/google-supports-strict-tool-sampling? "gemini-3.1-pro-preview")))
    (t/is (false? (shared/google-supports-strict-tool-sampling? "gemini-2.5-pro")))
    (t/is (false? (shared/google-supports-strict-tool-sampling? "gemma-3-27b-it")))
    (let [t (shared/tool->google-schema strict-tool true)]
      (t/is (strictified? (:parameters t))))
    (let [t (shared/tool->google-schema strict-tool false)]
      (t/is (= (:parameters strict-tool) (:parameters t))))))

(t/deftest test-mistral-tool
  (t/testing "mistral always supports strict (pi: resolve with true)"
    (t/is (= true (get-in (mc/mistral-tool strict-tool) [:function :strict])))
    (t/is (strictified? (get-in (mc/mistral-tool strict-tool) [:function :parameters])))
    (t/is (= false (get-in (mc/mistral-tool plain-tool) [:function :strict])))))

(t/deftest test-bedrock-tool-config
  (t/testing "strict gated on supports-strict-mode compat"
    (let [tools (bc/bedrock-tool-config [strict-tool plain-tool] true)]
      (t/is (= true (get-in tools [:tools 0 :toolSpec :strict])))
      (t/is (strictified? (get-in tools [:tools 0 :toolSpec :inputSchema :json])))
      (t/is (nil? (get-in tools [:tools 1 :toolSpec :strict])))
      (t/is (= (:parameters plain-tool) (get-in tools [:tools 1 :toolSpec :inputSchema :json]))))
    (let [tools (bc/bedrock-tool-config [strict-tool] false)]
      (t/is (nil? (get-in tools [:tools 0 :toolSpec :strict])))
      (t/is (= (:parameters strict-tool) (get-in tools [:tools 0 :toolSpec :inputSchema :json]))))))
