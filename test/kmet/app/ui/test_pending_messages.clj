(ns kmet.app.ui.test-pending-messages
  (:require [clojure.string :as str]
            [clojure.test :as t :refer [deftest is testing]]
            [kmet.tui.core :as core]
            [kmet.app.ui.pending-messages :as pm]))

(defn- strip-ansi [s]
  (str/replace s #"\u001b\[[0-9;]*[a-zA-Z]" ""))

(defn- render-plain [c width]
  (mapv strip-ansi (core/render c width)))

(deftest test-empty-renders-nothing
  (testing "empty queues render no lines"
    (let [c (pm/make-pending-messages)]
      (is (= [] (core/render c 40))))))

(deftest test-steering-lines
  (testing "steering messages render as dim prefixed lines"
    (let [c (pm/make-pending-messages :hint "Alt+Up")]
      (pm/pending-messages-set-queues! c ["first steer"] [])
      (let [plain (render-plain c 40)]
        (is (= 3 (count plain)) "blank spacer + message + hint")
        (is (.contains (nth plain 1) "Steering: first steer"))
        (is (.contains (nth plain 2) "↳ Alt+Up to edit all queued messages"))))))

(deftest test-follow-up-lines
  (testing "follow-up messages render with their prefix"
    (let [c (pm/make-pending-messages)]
      (pm/pending-messages-set-queues! c [] ["do it later"])
      (let [plain (render-plain c 40)]
        (is (some #(.contains % "Follow-up: do it later") plain))))))

(deftest test-mixed-queues
  (testing "steering and follow-up lines interleave in order"
    (let [c (pm/make-pending-messages)]
      (pm/pending-messages-set-queues! c ["s1"] ["f1" "f2"])
      (let [plain (render-plain c 40)]
        (is (= 5 (count plain)))
        (is (some #(.contains % "Steering: s1") plain))
        (is (some #(.contains % "Follow-up: f1") plain))
        (is (some #(.contains % "Follow-up: f2") plain))))))

(deftest test-clear
  (testing "clearing queues renders nothing again"
    (let [c (pm/make-pending-messages)]
      (pm/pending-messages-set-queues! c ["x"] [])
      (is (seq (core/render c 40)))
      (pm/pending-messages-set-queues! c [] [])
      (is (= [] (core/render c 40))))))

(deftest test-truncation
  (testing "long messages truncate to the width"
    (let [c (pm/make-pending-messages)]
      (pm/pending-messages-set-queues! c [(apply str (repeat 100 "x"))] [])
      (let [plain (render-plain c 30)]
        (is (every? #(<= (count %) 30) plain))))))
