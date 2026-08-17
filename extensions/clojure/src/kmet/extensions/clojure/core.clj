;; clojure extension — Clojure-aware tools for kmet.
;;
;; Registers:
;;   clojure_edit              — structure-aware form editing
;;   clojure_edit_replace_sexp — s-expression replacement

(ns kmet.extensions.clojure.core
  (:require [edit-tool]
            [sexp-tool]))

(defn init [api]
  (edit-tool/register! api)
  (sexp-tool/register! api))

(defn shutdown [_api]
  nil)
