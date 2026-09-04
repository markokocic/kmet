;; cljfmt fixture — mirrors the user clojure extension's deps: cljfmt's
;; config ns requires clojure.spec.alpha (bb-bundled port) and its io ns
;; uses file-seq (absent from SCI's core), so this exercises the spec port
;; injection, the file-seq injection and the bundled-artifact? closure
;; exclusion in one load.

(ns cljfmt-ext.main
  (:require [kmet.extension :as ext]
            [cljfmt.config :as config]
            [cljfmt.core :as fmt]))

(defn init [api]
  (ext/register-tool! api
                      {:name        "cljfmt-fmt"
                       :description "formats a Clojure string via cljfmt"
                       :execute     (fn [{:keys [code]}]
                                      {:content (fmt/reformat-string code config/default-config)})}))
