(ns kmet.app.tools
  "Tool definitions and execution for the LLM agent.
   Port of @earendil-works/pi-agent tool system.
   Public API: Tool record, parameter helpers, registry, execution.
   Individual tool implementations live in kmet.app.tools/*."
  (:require [kmet.app.tools.registry :as registry]))

;; ─── Tool record & parameter helpers ─────────────────────────────────────────

(defrecord Tool [name label description prompt-snippet prompt-guidelines
                 parameters execute render-call render-result])

(defn param
  "Define a tool parameter for JSON schema generation."
  [name type description & {:keys [optional?]}]
  (merge {:type type :description description}
         (when optional? {:optional true})))

(defn ->json-schema
  "Convert a map of param definitions to a JSON schema map."
  [params]
  {:type "object"
   :properties (reduce-kv (fn [m k v]
                            (assoc m (name k)
                              {:type (name (:type v))
                               :description (:description v)}))
                          {} params)
   :required (vec (->> params (remove #(:optional (val %))) (map key) (map name)))})

(defn make-tool
  "Create a Tool record."
  [& {:keys [name label description prompt-snippet prompt-guidelines parameters execute render-call render-result]}]
  (map->Tool
    {:name name :label label :description description
     :prompt-snippet prompt-snippet :prompt-guidelines prompt-guidelines
     :parameters parameters :execute execute
     :render-call render-call :render-result render-result}))

;; ─── Re-export public API from registry ──────────────────────────────────────

(def get-all-tools registry/get-all-tools)
(def execute-tool registry/execute-tool)
(def tool->openai-schema registry/tool->openai-schema)
(def tool->anthropic-schema registry/tool->anthropic-schema)
(def register-tool! registry/register-tool!)
(def unregister-tool! registry/unregister-tool!)
(def get-tool registry/get-tool)
