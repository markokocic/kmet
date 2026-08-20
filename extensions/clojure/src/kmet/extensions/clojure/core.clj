;; clojure extension — Clojure-aware tools for kmet.
;;
;; Registers:
;;   clojure_edit              — structure-aware form editing
;;   clojure_edit_replace_sexp — s-expression replacement
;;   clojure_paren_repair      — delimiter repair

(ns kmet.extensions.clojure.core
  (:require [edit-tool]
            [paren-repair]
            [sexp-tool]
            [kmet.extension :as ext]))

(defn init [api]
  (edit-tool/register! api)
  (sexp-tool/register! api)
  (paren-repair/register! api)
  ;; contribute the editing-guidelines skill (pi: extendResourcesFromExtensions)
  (ext/on-event api :resources-discover
                (fn [_event _ctx]
                  {:skill-paths [(str (:extension-dir api) "/skills/clojure-edit")]})))

(defn shutdown [_api]
  nil)
