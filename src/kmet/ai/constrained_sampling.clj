(ns kmet.ai.constrained-sampling
  "Port of pi packages/ai/src/api/constrained-sampling.ts: per-tool
   constrained-sampling resolution for the provider request builders.

   The :json-schema strict variant is wired into every kmet wire pi wires
   (openai responses/completions, anthropic, google, mistral, bedrock).
   The :grammar helpers are self-contained and tested — the responses wire
   never activates grammar tool calls (pi parity: no model sets
   supportsOpenAIGrammarTools, so resolve-grammar-constrained-sampling
   always returns nil there too). Schemas are JSON-schema maps with string
   keys (keyword keys are normalized at the entry points)."
  (:require [clojure.string :as str]
            [clojure.walk :as walk]
            [cheshire.core :as json]))

(def ^:private unsupported-strict-schema-keys
  "JSON-schema keywords pi rejects in strict mode."
  #{"$ref" "$defs" "definitions" "allOf" "oneOf" "patternProperties"
    "dependentSchemas" "dependencies" "unevaluatedProperties"
    "propertyNames" "contains" "prefixItems" "not" "if" "then" "else"})

(defn- unsupported! [msg]
  (throw (ex-info msg {:type :unsupported-strict-json-schema})))

(defn- json-schema-object? [value]
  (and (map? value) (not (vector? value))))

(defn- stringify-schema
  "Deep string-key copy of SCHEMA (extension schemas may be built with
   keyword keys/values; the strict subset works on JSON string keys)."
  [schema]
  (walk/postwalk
   (fn [x]
     (cond
       (map? x) (into {} (map (fn [[k v]] [(if (keyword? k) (name k) k) v])) x)
       (keyword? x) (name x)
       :else x))
   schema))

(defn- structured-schema? [schema]
  (if-not (json-schema-object? schema)
    false
    (let [types (if (string? (get schema "type")) [(get schema "type")] (get schema "type"))]
      (boolean
       (or (some #{"object" "array"} types)
           (contains? schema "properties")
           (contains? schema "items"))))))

(defn- schema-allows-null? [schema]
  (if-not (json-schema-object? schema)
    false
    (or (= "null" (get schema "type"))
        (some #(= "null" %) (get schema "type"))
        (and (contains? schema "const") (nil? (get schema "const")))
        (some #(nil? %) (get schema "enum"))
        (some schema-allows-null? (get schema "anyOf")))))

(defn- make-node-strict
  "pi makeJsonSchemaNodeStrict, pure: returns a strictified copy of SCHEMA
   (the pi version mutates a structuredClone). Throws
   :unsupported-strict-json-schema on constructs the strict subset
   rejects."
  [schema]
  (when-not (json-schema-object? schema)
    (unsupported! "boolean schemas are unsupported"))
  (doseq [k unsupported-strict-schema-keys]
    (when (contains? schema k)
      (unsupported! (str k " schemas are unsupported"))))
  (let [any-of (get schema "anyOf")
        items (get schema "items")
        is-object? (= "object" (get schema "type"))]
    (when (contains? schema "anyOf")
      (when-not (and (vector? any-of) (seq any-of))
        (unsupported! "anyOf must contain at least one schema"))
      (doseq [variant any-of]
        (when (structured-schema? variant)
          (unsupported! "object and array unions are unsupported"))))
    (when (contains? schema "items")
      (when (vector? items)
        (unsupported! "tuple schemas are unsupported")))
    (when (and (contains? schema "properties") (not is-object?))
      (unsupported! "properties require type object"))
    (if-not is-object?
      (cond-> schema
        (contains? schema "anyOf") (assoc "anyOf" (mapv make-node-strict any-of))
        (contains? schema "items") (assoc "items" (make-node-strict items)))
      (let [additional (get schema "additionalProperties")
            required (get schema "required")]
        (when (and (contains? schema "additionalProperties") (not= false additional))
          (unsupported! "schema-valued or true additionalProperties is unsupported"))
        (when (and (contains? schema "properties")
                   (not (json-schema-object? (get schema "properties"))))
          (unsupported! "object properties must be a schema map"))
        (when (and (contains? schema "required")
                   (not (and (vector? required) (every? string? required))))
          (unsupported! "object required must be a string array"))
        (let [props (or (get schema "properties") {})
              required-set (set (or required []))]
          (when (seq (remove (set (keys props)) required))
            (unsupported! "required contains an unknown property"))
          (let [strict-props (into {}
                                   (map (fn [[k prop]]
                                          (let [prop' (make-node-strict prop)]
                                            [k (if (or (required-set k) (schema-allows-null? prop))
                                                 prop'
                                                 {"anyOf" [prop' {"type" "null"}]})])))
                                   props)]
            (cond-> schema
              (contains? schema "anyOf") (assoc "anyOf" (mapv make-node-strict any-of))
              (contains? schema "items") (assoc "items" (make-node-strict items))
              true (assoc "properties" strict-props
                          "required" (vec (keys strict-props))
                          "additionalProperties" false))))))))

(defn make-strict-json-schema
  "pi makeStrictJsonSchema: convert a tool parameter schema to the strict
   subset expected by provider constrained sampling (every property
   required or nullable, additionalProperties false, no unions of
   structured types). Throws :unsupported-strict-json-schema on schemas
   outside the subset. SCHEMA is not mutated; keyword keys are normalized
   to string keys."
  [schema]
  (let [strict (make-node-strict (stringify-schema schema))]
    (when-not (= "object" (get strict "type"))
      (unsupported! "root schema must have type object"))
    strict))

(defn resolve-json-schema-strict-sampling
  "pi resolveJsonSchemaStrictSampling: per-tool strict resolution.
   TOOL's :constrained-sampling may be nil/false (no strict), a
   {:type :json-schema :strict :prefer|:require} map, or a :grammar map
   (returns nil here). Returns true when the tool must be sent strict;
   throws when the config demands strict (:require) but the provider or
   the schema cannot support it."
  [tool supports-strict?]
  (let [config (:constrained-sampling tool)]
    (when (and config (= :json-schema (:type config)))
      (if supports-strict?
        (try
          (make-strict-json-schema (:parameters tool))
          true
          (catch Exception e
            (if (and (instance? clojure.lang.ExceptionInfo e)
                     (= :unsupported-strict-json-schema (:type (ex-data e))))
              (when (= :require (:strict config))
                (throw (ex-info (str "Tool \"" (:name tool)
                                     "\" requires JSON-schema constrained sampling, but "
                                     (ex-message e) ".")
                                {:type :constrained-sampling-required})))
              (throw e))))
        (when (= :require (:strict config))
          (throw (ex-info (str "Tool \"" (:name tool)
                               "\" requires JSON-schema constrained sampling, but strict tools are unsupported.")
                          {:type :constrained-sampling-required})))))))

(defn get-json-schema-tool-parameters
  "pi getJsonSchemaToolParameters: strictified parameters when STRICT? is
   true, the tool's parameters otherwise."
  [tool strict?]
  (if (true? strict?)
    (make-strict-json-schema (:parameters tool))
    (:parameters tool)))

;; ─── Grammar constrained sampling (pi: grammar section) ────────────────────
;; Resolution + input-buffer helpers. Providers never activate these — pi
;; gates them behind supportsOpenAIGrammarTools, which no model sets — but
;; extension configs are still validated here (same errors pi throws).

(defn- infer-grammar-input-property
  "pi inferGrammarInputProperty: the single required string property of the
   tool's parameter schema."
  [tool]
  (let [schema (stringify-schema (:parameters tool))]
    (when-not (= "object" (get schema "type"))
      (throw (ex-info "grammar constrained sampling requires an object parameter schema" {})))
    (let [required (get schema "required")]
      (when-not (and (vector? required) (= 1 (count required)) (string? (first required)))
        (throw (ex-info "grammar constrained sampling requires exactly one required string property" {})))
      (let [input-property (first required)]
        (when-not (get-in schema ["properties" input-property])
          (throw (ex-info (str "grammar constrained sampling requires a properties entry for "
                               input-property)
                          {})))
        (when-not (= "string" (get-in schema ["properties" input-property "type"]))
          (throw (ex-info (str "grammar constrained sampling property " input-property
                               " must have type string")
                          {})))
        input-property))))

(defn resolve-grammar-constrained-sampling
  "pi resolveGrammarConstrainedSampling: resolve a tool's :grammar
   constrained-sampling config into {:format :lark|:regex :definition
   :input-property}, or nil when the config is absent or the provider does
   not support OpenAI grammar tools. Throws when grammar is configured but
   unusable (no supported variant, bad schema)."
  [tool supports-openai-grammar-tools?]
  (let [config (:constrained-sampling tool)]
    (when (and config (= :grammar (:type config)) supports-openai-grammar-tools?)
      (let [lark (get-in config [:variants :openai-lark])
            regex (get-in config [:variants :openai-regex])
            has-lark? (and (string? lark) (seq (str/trim lark)))
            has-regex? (and (string? regex) (seq (str/trim regex)))]
        (when-not (or has-lark? has-regex?)
          (throw (ex-info (str "Tool \"" (:name tool)
                               "\" cannot use grammar constrained sampling: no supported grammar variant was provided.")
                          {})))
        (try
          {:format (if has-lark? :lark :regex)
           :definition (if has-lark? lark regex)
           :input-property (infer-grammar-input-property tool)}
          (catch Exception e
            (throw (ex-info (str "Tool \"" (:name tool)
                                 "\" cannot use grammar constrained sampling: " (ex-message e) ".")
                            {}))))))))

(defn create-grammar-tool-input-properties
  "pi createGrammarToolInputProperties: tool name → grammar input property
   for every tool with a resolvable grammar config."
  [tools supports-openai-grammar-tools?]
  (reduce (fn [m tool]
            (if-let [grammar (resolve-grammar-constrained-sampling tool supports-openai-grammar-tools?)]
              (assoc m (:name tool) (:input-property grammar))
              m))
          {} tools))

(defn get-grammar-tool-input
  "pi getGrammarToolInput: the string value of INPUT-PROPERTY in ARGS, or
   throw (the grammar input must be a string)."
  [tool-name args input-property]
  (let [input (get args input-property)]
    (when-not (string? input)
      (throw (ex-info (str "Grammar tool call \"" tool-name "\" requires argument \""
                           input-property "\" to be a string.")
                      {})))
    input))

(defn append-grammar-tool-input-delta
  "pi appendGrammarToolInputDelta: incremental grammar input synthesis.
   BUFFER {:input str :started bool :closed bool} tracks the previously
   emitted input; returns {:delta str-or-nil :buffer updated-buffer}. The
   delta is nil when nothing new to stream; throws when the input changes
   after close or is non-monotonic (pi semantics)."
  [buffer input-property next-input close?]
  (if (:closed buffer)
    (if (and close? (= next-input (:input buffer)))
      {:delta nil :buffer buffer}
      (throw (ex-info (str "grammar tool input for property \"" input-property
                           "\" changed after it was closed")
                      {})))
    (do
      (when-not (str/starts-with? next-input (:input buffer))
        (throw (ex-info (str "grammar tool input for property \"" input-property
                             "\" changed non-monotonically")
                        {})))
      (let [input-delta (subs next-input (count (:input buffer)))]
        (if (and (not close?) (empty? input-delta))
          {:delta nil :buffer buffer}
          (let [escaped-json (json/generate-string input-delta)
                escaped (subs escaped-json 1 (dec (count escaped-json)))
                started (or (:started buffer) false)
                delta (str (when-not started (str "{" (json/generate-string input-property) ":\""))
                           escaped
                           (when close? "\"}"))
                buffer' (cond-> buffer
                          (not started) (assoc :started true)
                          true (assoc :input next-input)
                          close? (assoc :closed true))]
            {:delta delta :buffer buffer'}))))))
