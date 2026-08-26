;; tree-sitter extension — structural code intelligence for kmet.
;;
;; Registers:
;;   list_symbols, find_definition, get_symbol_body,
;;   find_callers, find_callees — parse-on-demand tools over the
;;   cached tree-sitter CLI + WASM grammars (auto-downloaded).
;;
;; Syntax-validation hooks (write-block / edit-warn) arrive in a later
;; phase per tree-sitter.md; core stays wiring-only.

(ns kmet.extensions.tree-sitter.core
  (:require [kmet.extension :as ext]
            [kmet.extensions.tree-sitter.paths :as paths]
            [kmet.extensions.tree-sitter.tools :as tools]))

(defn init [api]
  ;; bundled EDN resources resolve against the installed extension dir
  (paths/set-extension-dir! (:extension-dir api))
  (paths/set-extension-dir! (:extension-dir api))
  (doseq [tool (tools/tool-defs)]
    (ext/register-tool! api tool)))

(defn shutdown [api]
  (doseq [tool (tools/tool-defs)]
    (ext/unregister-tool! api (:name tool))))
