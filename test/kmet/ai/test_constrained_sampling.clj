(ns kmet.ai.test-constrained-sampling
  "Port tests for pi packages/ai/src/api/constrained-sampling.ts (strict
   json-schema tools + grammar helpers)."
  (:require [clojure.test :as t]
            [kmet.ai.constrained-sampling :as cs]))

(def ^:private sample-tool
  {:name "t"
   :parameters {"type" "object"
                "properties" {"a" {"type" "string"}
                              "b" {"type" "integer"}}
                "required" ["a"]}})

(t/deftest test-make-strict-json-schema
  (t/testing "optional properties become nullable, everything required"
    (let [strict (cs/make-strict-json-schema (:parameters sample-tool))]
      (t/is (= {"type" "object"
                "properties" {"a" {"type" "string"}
                              "b" {"anyOf" [{"type" "integer"} {"type" "null"}]}}
                "required" ["a" "b"]
                "additionalProperties" false}
               strict)))
    (t/testing "the input schema is not mutated"
      (t/is (= {"type" "integer"} (get-in (:parameters sample-tool) ["properties" "b"]))))
    (t/testing "keyword-keyed schemas are normalized to string keys"
      (let [strict (cs/make-strict-json-schema
                    {:type :object
                     :properties {:a {:type :string}}
                     :required [:a]})]
        (t/is (= {"a" {"type" "string"}} (get strict "properties")))
        (t/is (= ["a"] (get strict "required"))))))
  (t/testing "non-object roots are rejected"
    (t/is (thrown-with-msg? clojure.lang.ExceptionInfo #"root schema must have type object"
                            (cs/make-strict-json-schema {:type "string"}))))
  (t/testing "unsupported constructs are rejected"
    (t/is (thrown-with-msg? clojure.lang.ExceptionInfo #"oneOf schemas are unsupported"
                            (cs/make-strict-json-schema {:type "object" :oneOf []})))
    (t/is (thrown-with-msg? clojure.lang.ExceptionInfo #"required contains an unknown property"
                            (cs/make-strict-json-schema {:type "object"
                                                         :properties {"a" {:type "string"}}
                                                         :required ["missing"]})))
    (t/is (thrown-with-msg? clojure.lang.ExceptionInfo #"schema-valued or true additionalProperties is unsupported"
                            (cs/make-strict-json-schema {:type "object"
                                                         :properties {}
                                                         :additionalProperties true})))
    (t/is (thrown-with-msg? clojure.lang.ExceptionInfo #"object and array unions are unsupported"
                            (cs/make-strict-json-schema {:type "object"
                                                         :properties {"a" {:anyOf [{:type "object"
                                                                                    :properties {}}
                                                                                   {:type "null"}]}}}))))
  (t/testing "already-nullable optional properties stay as-is"
    (let [strict (cs/make-strict-json-schema
                  {"type" "object"
                   "properties" {"a" {"anyOf" [{"type" "string"} {"type" "null"}]}}
                   "required" []})]
      (t/is (= {"a" {"anyOf" [{"type" "string"} {"type" "null"}]}}
               (get strict "properties"))))))

(t/deftest test-resolve-json-schema-strict-sampling
  (t/testing "no config or false → nil"
    (t/is (nil? (cs/resolve-json-schema-strict-sampling sample-tool true)))
    (t/is (nil? (cs/resolve-json-schema-strict-sampling (assoc sample-tool :constrained-sampling false) true))))
  (t/testing "grammar config resolves to nil here"
    (t/is (nil? (cs/resolve-json-schema-strict-sampling
                 (assoc sample-tool :constrained-sampling {:type :grammar}) true))))
  (t/testing "prefer: strict when supported, silent nil otherwise"
    (let [tool (assoc sample-tool :constrained-sampling {:type :json-schema :strict :prefer})]
      (t/is (true? (cs/resolve-json-schema-strict-sampling tool true)))
      (t/is (nil? (cs/resolve-json-schema-strict-sampling tool false)))
      (t/is (nil? (cs/resolve-json-schema-strict-sampling tool nil)))))
  (t/testing "require: throws when the provider or schema cannot do strict"
    (let [tool (assoc sample-tool :constrained-sampling {:type :json-schema :strict :require})]
      (t/is (true? (cs/resolve-json-schema-strict-sampling tool true)))
      (t/is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"requires JSON-schema constrained sampling, but strict tools are unsupported"
                              (cs/resolve-json-schema-strict-sampling tool false)))
      (t/is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"requires JSON-schema constrained sampling, but required contains an unknown property"
                              (cs/resolve-json-schema-strict-sampling
                               (assoc tool :parameters {:type "object"
                                                        :properties {"a" {:type "string"}}
                                                        :required ["missing"]})
                               true))))))

(t/deftest test-get-json-schema-tool-parameters
  (t/is (= (:parameters sample-tool)
           (cs/get-json-schema-tool-parameters sample-tool false)))
  (t/is (= (cs/make-strict-json-schema (:parameters sample-tool))
           (cs/get-json-schema-tool-parameters sample-tool true))))

;; ─── Grammar helpers (pi: grammar section — inert on every provider) ───────

(def ^:private grammar-tool
  {:name "g"
   :constrained-sampling {:type :grammar :variants {:openai-lark "start: x"}}
   :parameters {:type "object"
                :properties {"input" {:type "string"}}
                :required ["input"]}})

(t/deftest test-resolve-grammar-constrained-sampling
  (t/testing "nil without config, without provider support, or for json_schema config"
    (t/is (nil? (cs/resolve-grammar-constrained-sampling sample-tool true)))
    (t/is (nil? (cs/resolve-grammar-constrained-sampling grammar-tool false)))
    (t/is (nil? (cs/resolve-grammar-constrained-sampling
                 (assoc grammar-tool :constrained-sampling {:type :json-schema}) true))))
  (t/testing "resolves lark/regex variants with the inferred input property"
    (t/is (= {:format :lark :definition "start: x" :input-property "input"}
             (cs/resolve-grammar-constrained-sampling grammar-tool true)))
    (t/is (= :regex (:format (cs/resolve-grammar-constrained-sampling
                              (assoc-in grammar-tool [:constrained-sampling :variants]
                                        {:openai-regex "a+"})
                              true)))))
  (t/testing "no supported variant → throws; bad schema → throws with tool name"
    (t/is (thrown-with-msg? clojure.lang.ExceptionInfo #"no supported grammar variant was provided"
                            (cs/resolve-grammar-constrained-sampling
                             (assoc grammar-tool :constrained-sampling {:type :grammar :variants {}})
                             true)))
    (t/is (thrown-with-msg? clojure.lang.ExceptionInfo #"Tool \"g\" cannot use grammar constrained sampling"
                            (cs/resolve-grammar-constrained-sampling
                             (assoc grammar-tool :parameters {:type "object" :properties {} :required []})
                             true)))))

(t/deftest test-grammar-input-helpers
  (t/is (= {"g" "input"} (cs/create-grammar-tool-input-properties [grammar-tool] true)))
  (t/is (= {} (cs/create-grammar-tool-input-properties [grammar-tool] false)))
  (t/is (= "hi" (cs/get-grammar-tool-input "g" {"input" "hi"} "input")))
  (t/is (thrown-with-msg? clojure.lang.ExceptionInfo #"requires argument \"input\" to be a string"
                          (cs/get-grammar-tool-input "g" {"input" 42} "input")))
  (t/testing "append-grammar-tool-input-delta synthesizes the JSON buffer incrementally"
    (let [r1 (cs/append-grammar-tool-input-delta {:input "" :started false :closed false}
                                                 "input" "he" false)
          r2 (cs/append-grammar-tool-input-delta (:buffer r1) "input" "hello" true)]
      (t/is (= "{\"input\":\"he" (:delta r1)))
      (t/is (= "llo\"}" (:delta r2)))
      (t/is (= {:input "hello" :started true :closed true} (:buffer r2)))
      (t/is (= "{\"input\":\"hello\"}" (str (:delta r1) (:delta r2))))))
  (t/testing "non-monotonic input and post-close changes throw"
    (let [buf {:input "hello" :started true :closed true}]
      (t/is (nil? (:delta (cs/append-grammar-tool-input-delta buf "input" "hello" true)))
            "close with the same input is a no-op")
      (t/is (thrown-with-msg? clojure.lang.ExceptionInfo #"changed after it was closed"
                              (cs/append-grammar-tool-input-delta buf "input" "hello2" false)))
      (t/is (thrown-with-msg? clojure.lang.ExceptionInfo #"changed non-monotonically"
                              (cs/append-grammar-tool-input-delta {:input "he" :started true :closed false}
                                                                  "input" "ha" false))))
    (t/testing "empty delta streams nothing until close"
      (let [r (cs/append-grammar-tool-input-delta {:input "" :started false :closed false} "input" "" false)]
        (t/is (nil? (:delta r)))
        (t/is (= {:input "" :started false :closed false} (:buffer r)))))))
