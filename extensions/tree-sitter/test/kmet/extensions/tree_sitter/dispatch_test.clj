(ns kmet.extensions.tree-sitter.dispatch-test
  "Route-table tests: pure decisions via opts, live clojure-extension
   detection via a stubbed api."
  (:require [clojure.test :refer [are deftest is testing]]
            [kmet.extensions.tree-sitter.dispatch :as dispatch]))

(defn- route [ext enabled?]
  (dispatch/route ext {:clojure-enabled? enabled?}))

(deftest route-table-test
  (testing "grammar-backed languages always validate"
    (is (= :tree-sitter (route "py" false)))
    (is (= :tree-sitter (route "py" true)))
    (is (= :tree-sitter (route "ts" true)))
    (is (= :tree-sitter (route "tsx" true))))
  (testing "clojure family defers when the clojure extension is enabled"
    (is (= :defer (route "clj" true)))
    (is (= :defer (route "edn" true)))
    (is (= :defer (route "bb" true))))
  (testing "clojure family falls back to delimiter balance otherwise"
    (is (= :delimiter (route "clj" false)))
    (is (= :delimiter (route "cljs" false))))
  (testing "unknown extensions are never validated"
    (are [ext] (nil? (route ext false))
      "rs" "md" "json" ""))
  (testing "case-insensitive"
    (is (= :tree-sitter (route "PY" true)))
    (is (= :defer (route "CLJ" true)))))

(deftest live-clojure-detection-test
  (testing "clojure tools present -> defer"
    (dispatch/set-api! {:get-all-tools
                        (fn [] [{:name "clojure_edit"}
                                {:name "read"}])})
    (is (= :defer (dispatch/route "clj")))
    (is (true? (dispatch/clojure-extension-enabled?))))
  (testing "no clojure tools -> delimiter fallback"
    (dispatch/set-api! {:get-all-tools (fn [])})
    (is (= :delimiter (dispatch/route "clj")))
    (is (false? (dispatch/clojure-extension-enabled?)))))
