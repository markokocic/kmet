(ns kmet.app.ui.test-footer-data-provider
  (:require [clojure.test :as t :refer [deftest is testing]]
            [babashka.fs :as fs]
            [kmet.app.session :as session]
            [kmet.app.ui.footer-data-provider :as fdp]))

(defn- with-session
  "Create a session in target/ and run f, cleaning up the dir after."
  [f]
  (let [dir (str "target/kmet-fdp-test-" (System/currentTimeMillis) "-" (rand-int 999))
        sess (session/create-session dir)]
    (try
      (f sess)
      (finally (fs/delete-tree dir)))))

(deftest test-basics
  (testing "constructs with defaults"
    (let [p (fdp/make-footer-data-provider)]
      (is (some? p))
      (is (nil? (fdp/fdp-get-session p)))
      (is (= 1 (fdp/fdp-get-provider-count p)))
      (is (nil? (fdp/fdp-get-context-window p))))))

(deftest test-session-swap
  (testing "session is swappable"
    (let [p (fdp/make-footer-data-provider)]
      (is (nil? (fdp/fdp-get-session p)))
      (fdp/fdp-set-session! p :a-session)
      (is (= :a-session (fdp/fdp-get-session p))))))

(deftest test-usage-totals
  (testing "usage totals sum across session entries"
    (with-session
      (fn [sess]
        (session/append-entry sess {:role :user :content [{:type :text :text "hi"}]})
        (session/append-entry sess {:role :assistant
                                    :content [{:type :text :text "hello"}]
                                    :usage {:prompt_tokens 100 :completion_tokens 20
                                            :prompt_tokens_details {:cached_tokens 30}}})
        (session/append-entry sess {:role :assistant
                                    :content [{:type :text :text "again"}]
                                    :usage {:input_tokens 200 :output_tokens 40
                                            :cache_read_input_tokens 50
                                            :cache_creation_input_tokens 10}})
        (let [p (fdp/make-footer-data-provider :session sess)
              totals (fdp/fdp-usage-totals p)]
          (is (= {:input 300 :output 60 :cache-read 80 :cache-write 10} totals)))))))

(deftest test-context-tokens
  (testing "context tokens estimate the session branch"
    (with-session
      (fn [sess]
        (session/append-entry sess {:role :user :content [{:type :text :text "hello world"}]})
        (session/append-entry sess {:role :assistant :content [{:type :text :text "hi there"}]})
        (let [p (fdp/make-footer-data-provider :session sess)]
          (is (pos? (fdp/fdp-context-tokens p))))))))

(deftest test-latest-cache-hit-rate
  (testing "cache hit rate comes from the most recent usage entry"
    (with-session
      (fn [sess]
        (session/append-entry sess {:role :assistant :content [{:type :text :text "a"}]
                                    :usage {:prompt_tokens 100 :completion_tokens 1
                                            :prompt_tokens_details {:cached_tokens 50}}})
        (session/append-entry sess {:role :assistant :content [{:type :text :text "b"}]
                                    :usage {:prompt_tokens 200 :completion_tokens 1
                                            :prompt_tokens_details {:cached_tokens 100}}})
        (let [p (fdp/make-footer-data-provider :session sess)]
          (is (= (/ 100.0 3.0) (fdp/fdp-latest-cache-hit-rate p))))))))

(deftest test-model-provider-thinking
  (testing "model/provider/thinking accessors"
    (let [p (fdp/make-footer-data-provider :model "m" :provider :openai :thinking :high)]
      (is (= "m" (fdp/fdp-get-model p)))
      (is (= :openai (fdp/fdp-get-provider p)))
      (is (= :high (fdp/fdp-get-thinking p))))))
