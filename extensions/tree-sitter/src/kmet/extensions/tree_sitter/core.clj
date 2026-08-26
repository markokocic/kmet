;; tree-sitter extension — structural code intelligence for kmet.
;;
;; Registers:
;;   list_symbols, find_definition, get_symbol_body,
;;   find_callers, find_callees — parse-on-demand tools over the
;;   cached tree-sitter CLI + WASM grammars (auto-downloaded).
;;   write-block / edit-warn syntax-validation hooks over the same
;;   grammars (clojure-family files defer to the clojure extension).

(ns kmet.extensions.tree-sitter.core
  (:require [kmet.extension :as ext]
            [kmet.extensions.tree-sitter.dispatch :as dispatch]
            [kmet.extensions.tree-sitter.hooks :as hooks]
            [kmet.extensions.tree-sitter.paths :as paths]
            [kmet.extensions.tree-sitter.tools :as tools]))

(defn init [api]
  ;; bundled EDN resources resolve against the installed extension dir
  (paths/set-extension-dir! (:extension-dir api))
  ;; clojure-extension presence is checked lazily at hook time
  (dispatch/set-api! api)
  (doseq [tool (tools/tool-defs)]
    (ext/register-tool! api tool))
  (ext/on-tool-call api hooks/on-tool-call)
  (ext/on-tool-result api hooks/on-tool-result))

;; unload unregisters everything this extension added (tools + hooks)
;; automatically — nothing to tear down by hand
(defn shutdown [_api] nil)
