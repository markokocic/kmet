(ns kmet.app.tools
  "Tool definitions and execution for the LLM agent.
   Port of @earendil-works/pi-agent tool system.
   Public API: re-exports from kmet.app.tools.registry.
   Tool record lives in registry; this ns provides make-tool convenience."
  (:require [kmet.app.tools.registry :as registry]))

;; Re-export public API from registry
(def get-all-tools registry/get-all-tools)
(def execute-tool registry/execute-tool)
(def tool->openai-schema registry/tool->openai-schema)
(def tool->anthropic-schema registry/tool->anthropic-schema)
(def register-tool! registry/register-tool!)
(def unregister-tool! registry/unregister-tool!)
(def get-tool registry/get-tool)

;; Re-export Tool record constructor for external use
(defn make-tool
  "Create a Tool record. See registry/Tool for fields."
  [& {:keys [name label description prompt-snippet prompt-guidelines parameters execute render-call render-result]}]
  (registry/map->Tool
    {:name name :label label :description description
     :prompt-snippet prompt-snippet :prompt-guidelines prompt-guidelines
     :parameters parameters :execute execute
     :render-call render-call :render-result render-result}))
