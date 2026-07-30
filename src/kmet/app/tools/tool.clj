(ns kmet.app.tools.tool
  "Tool record type and parameter schema helpers."
  (:require [clojure.string :as str]))

(defrecord Tool [name label description prompt-snippet prompt-guidelines
                 parameters execute render-call render-result])

(defn param
  "Define a tool parameter for JSON schema generation."
  [name type description & {:keys [optional?]}]
  (merge {:type type :description description}
         (when optional? {:optional true})))

(defn ->json-schema
  "Convert a map of param definitions to a JSON schema map."
  [params]
  {:type "object"
   :properties (reduce-kv (fn [m k v]
                            (assoc m (name k)
                              {:type (name (:type v))
                               :description (:description v)}))
                          {} params)
   :required (vec (->> params (remove #(:optional (val %))) (map key) (map name)))})
