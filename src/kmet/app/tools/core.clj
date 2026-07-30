(ns kmet.app.tools.core
  "Tool system public API — Tool record, registry, and execution.
   Re-exports from kmet.app.tools.tool and kmet.app.tools.registry."
  (:require [kmet.app.tools.tool :as tool]
            [kmet.app.tools.registry :as registry]))

;; ─── From tool.clj (Tool record + helpers) ──────────────────────────────────

(def map->Tool tool/map->Tool)
(def param tool/param)
(def ->json-schema tool/->json-schema)

(defn make-tool
  "Create a Tool record."
  [& {:keys [name label description prompt-snippet prompt-guidelines parameters execute render-call render-result]}]
  (tool/map->Tool
    {:name name :label label :description description
     :prompt-snippet prompt-snippet :prompt-guidelines prompt-guidelines
     :parameters parameters :execute execute
     :render-call render-call :render-result render-result}))

;; ─── From registry.clj (registry + execution) ───────────────────────────────

(def get-all-tools registry/get-all-tools)
(def execute-tool registry/execute-tool)
(def tool->openai-schema registry/tool->openai-schema)
(def tool->anthropic-schema registry/tool->anthropic-schema)
(def register-tool! registry/register-tool!)
(def unregister-tool! registry/unregister-tool!)
(def get-tool registry/get-tool)
