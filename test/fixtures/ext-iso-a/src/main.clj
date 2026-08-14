(ns iso-a.main
  "Extension pinning tools.cli 0.4.1."
  (:require [clojure.tools.cli :as cli]
            [kmet.extension :as ext]))

(defn init [api]
  (ext/register-tool! api {:name "iso-a"
                           :description "tools.cli 0.4.1"
                           :params {:x {:type :string}}
                           :execute (fn [_args] {:content "iso-a"})}))
