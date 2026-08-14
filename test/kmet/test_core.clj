(ns kmet.test-core
  "CLI surface tests — --list-models output (pi cli/list-models.ts) and its
   token-count formatting."
  (:require [clojure.string :as str]
            [clojure.test :as t]
            [kmet.core :as core]
            [kmet.ai.models :as models]))

(t/deftest test-format-token-count
  (t/is (= "200K" (@#'core/format-token-count 200000)))
  (t/is (= "1M" (@#'core/format-token-count 1000000)))
  (t/is (= "1.5M" (@#'core/format-token-count 1500000)))
  (t/is (= "128K" (@#'core/format-token-count 128000)))
  (t/is (= "4.1K" (@#'core/format-token-count 4096)) "pi toFixed(1)"))

(defn- tmodel [provider id context max-out reasoning]
  (models/map->Model {:id id :name id :provider provider
                      :api :openai-completions :base-url "https://x"
                      :reasoning reasoning :input [:text :image]
                      :cost {:input 0 :output 0 :cache-read 0 :cache-write 0}
                      :context-window context :max-tokens max-out}))

(t/deftest test-list-models-table
  (let [out (with-out-str
              (with-redefs [models/get-model-config-error (fn [] nil)
                            models/get-available
                            (fn [] [(tmodel :alpha "zeta" 1000000 8192 true)
                                    (tmodel :alpha "alpha-1" 128000 4096 false)
                                    (tmodel :beta "beta-1" 200000 16384 true)])]
                (@#'core/list-models nil)))]
    (t/is (str/includes? out "provider  model") "header row")
    (t/is (str/includes? out "alpha     alpha-1") "two-space aligned columns")
    (t/is (str/includes? out "1M") "context formatted")
    (t/is (str/includes? out "128K") "context formatted (K)")
    (t/is (str/includes? out "yes") "thinking flag")
    (t/is (str/includes? out "no") "non-reasoning flag")
    (t/is (str/includes? out "yes") "images flag (input includes :image)")
    ;; sorted by provider then id: alpha-1 < zeta, then beta
    (t/is (< (str/index-of out "alpha-1") (str/index-of out "zeta")))
    (t/is (< (str/index-of out "zeta") (str/index-of out "beta-1")))))

(t/deftest test-list-models-search
  (let [out (with-out-str
              (with-redefs [models/get-model-config-error (fn [] nil)
                            models/get-available
                            (fn [] [(tmodel :alpha "zeta" 1000 100 true)
                                    (tmodel :beta "beta-1" 1000 100 false)])]
                (@#'core/list-models "beta")))]
    (t/is (str/includes? out "beta-1"))
    (t/is (not (str/includes? out "zeta")) "fuzzy search filters"))

  (let [out (with-out-str
              (with-redefs [models/get-model-config-error (fn [] nil)
                            models/get-available
                            (fn [] [(tmodel :alpha "zeta" 1000 100 true)])]
                (@#'core/list-models "nope")))]
    (t/is (str/includes? out "No models matching \"nope\""))))

(t/deftest test-list-models-no-available
  (let [out (with-out-str
              (with-redefs [models/get-model-config-error (fn [] nil)
                            models/get-available (fn [] [])]
                (@#'core/list-models nil)))]
    (t/is (str/includes? out "No models available"))))

(t/deftest test-parse-args-ext-flags
  (let [opts (core/parse-args ["hello" "--ext-string" "v1" "--ext-bool"])]
    (t/is (= ["hello"] (:messages opts)))
    (t/is (= "v1" (get-in opts [:ext-flags "ext-string"])))
    (t/is (true? (get-in opts [:ext-flags "ext-bool"])))))
