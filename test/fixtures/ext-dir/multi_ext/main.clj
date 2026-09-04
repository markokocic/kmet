(ns multi-ext.main
  "Sample multi-file (manifest) extension for tests."
  (:require [kmet.extension :as ext]
            [multi-ext.helper :as helper]))

(defn init [api]
  (ext/register-tool! api {:name (helper/helper-tool-name)
                           :description "manifest tool"
                           :execute (fn [_] {:content "multi-ok"})})
  (ext/on-event api :agent-end (fn [_ev] nil)))

(defn shutdown [api]
  nil)
