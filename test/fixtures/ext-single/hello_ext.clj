(ns hello-ext
  "Sample single-file extension for tests."
  (:require [kmet.extension :as ext]))

(defn init [api]
  (ext/register-command! api
                         {:name "hello-ext"
                          :description "Test extension command"
                          :handler (fn [_cs args] (str "hello-ext:" args))})
  (ext/on-event api :session-start (fn [_ev] nil))
  (ext/register-flag! api "ext-hello" {:type :boolean :default false})
  (ext/register-tool! api {:name "hello-ext-tool"
                           :description "test tool"
                           :params {:x {:type :string}}
                           :execute (fn [args] {:content (str "tool:" (:x args))})})
  (ext/ui-set-status api "hello-ext" "loaded"))

(defn shutdown [api]
  (ext/ui-set-status api "hello-ext" nil))
