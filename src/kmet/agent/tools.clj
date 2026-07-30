(ns kmet.agent.tools
  "Tool definitions and execution for the LLM agent.
   Port of @earendil-works/pi-agent tool system.
   Public API: re-exports from kmet.agent.tools.registry.
   Individual tool implementations live in kmet.agent.tools/*."
  (:require [kmet.agent.tools.registry :as registry]
            [kmet.agent.tools.protocol :as protocol]))

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
  "Create a Tool record. See protocol/Tool for fields."
  [& {:keys [name label description prompt-snippet prompt-guidelines parameters execute render-call render-result]}]
  (protocol/map->Tool
    {:name name
     :label label
     :description description
     :prompt-snippet prompt-snippet
     :prompt-guidelines prompt-guidelines
     :parameters parameters
     :execute execute
     :render-call render-call
     :render-result render-result}))
