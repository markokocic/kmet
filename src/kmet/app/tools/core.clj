(ns kmet.app.tools.core
  "Tool system public API — Tool record, registry, and execution.
   Re-exports from kmet.app.tools.tool and kmet.app.tools.registry."
  (:require [kmet.app.tools.tool :as tool]
            [kmet.app.tools.registry :as registry]))

;; ─── From tool.clj (Tool record + helpers) ──────────────────────────────────

(def map->Tool tool/map->Tool)
(def param tool/param)
(def ->json-schema tool/->json-schema)
(def make-tool tool/make-tool)

;; ─── From registry.clj (registry + execution) ───────────────────────────────

(def get-all-tools registry/get-all-tools)
(def execute-tool registry/execute-tool)
(def tool->openai-schema registry/tool->openai-schema)
(def tool->anthropic-schema registry/tool->anthropic-schema)
(def tool->google-schema registry/tool->google-schema)
(def register-tool! registry/register-tool!)
(def unregister-tool! registry/unregister-tool!)
(def get-tool registry/get-tool)
