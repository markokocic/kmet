(ns iso-b.main
  "Extension pinning tools.cli 1.0.206."
  (:require [clojure.tools.cli :as cli]
            [kmet.extension :as ext]))

(defn init [api]
  (ext/register-tool! api {:name "iso-b"
                           :description "tools.cli 1.0.206"
                           :params {:x {:type :string}}
                           :execute (fn [_args] {:content "iso-b"})}))
