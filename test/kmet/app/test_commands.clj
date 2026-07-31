(ns kmet.app.test-commands
  "Tests for kmet.app.commands — slash command registry."
  (:require [clojure.test :as t]
            [kmet.app.commands :as commands]))

(t/use-fixtures :each
  (fn [f]
    (commands/clear-commands!)
    (f)
    (commands/clear-commands!)))

(t/deftest register-and-find
  (commands/register-command!
    {:name "test" :description "Test command" :handler (fn [_ _] :ok)})
  (t/is (= "test" (:name (commands/find-command "test"))))
  (t/is (nil? (commands/find-command "missing"))))

(t/deftest register-replaces-by-name
  (commands/register-command!
    {:name "dup" :description "first" :handler (fn [_ _] :one)})
  (commands/register-command!
    {:name "dup" :description "second" :handler (fn [_ _] :two)})
  (t/is (= 1 (count (filter #(= (:name %) "dup") (commands/get-commands)))))
  (t/is (= "second" (:description (commands/find-command "dup")))))

(t/deftest unregister-removes
  (commands/register-command!
    {:name "gone" :description "x" :handler (fn [_ _] nil)})
  (commands/unregister-command! "gone")
  (t/is (nil? (commands/find-command "gone"))))

(t/deftest handler-receives-cs-and-args
  (let [received (atom nil)]
    (commands/register-command!
      {:name "echo"
       :description "echo args"
       :handler (fn [cs args] (reset! received [cs args]))})
    (let [cs {:fake :state}]
      ((:handler (commands/find-command "echo")) cs "hello"))
    (t/is (= [{:fake :state} "hello"] @received))))

(t/deftest clear-removes-all
  (commands/register-command!
    {:name "a" :description "a" :handler (fn [_ _] nil)})
  (commands/register-command!
    {:name "b" :description "b" :handler (fn [_ _] nil)})
  (commands/clear-commands!)
  (t/is (empty? (commands/get-commands))))
