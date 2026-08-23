;; Repro: stream from commandcode/deepseek/deepseek-v4-flash with the exact
;; payload kmet builds, and print which event types arrive (thinking vs text)
;; so we can see how reasoning is classified on the wire.
(require '[kmet.ai.llm :as llm]
         '[kmet.ai.models :as models]
         '[kmet.ai.api.shared :as shared]
         '[kmet.ai.auth :as auth])

(models/load-catalogs!)
(models/load-models-config!)
(auth/load-auth!)

(def provider :commandcode)
(def model-id "deepseek/deepseek-v4-flash")

(when-let [m (models/get-model provider model-id)]
  (println "compat:" (:compat m))
  (println "reasoning:" (:reasoning m))
  (println "api:" (:api m) "base-url:" (:base-url m))
  (println "payload thinking params (effort :max):"
           (pr-str (shared/openai-thinking-params m :max)))
  (println "payload messages fn used:"
           (if (:requires-reasoning-content-on-assistant-messages (:compat m))
             :with-reasoning :plain)))

(def done-promise (promise))

(llm/send-message
 {:provider provider
  :model model-id
  :messages [{:role :user :content [{:type :text :text "What is 17*23? Answer very briefly."}]}]
  :tools []
  :thinking :max
  :on-text (fn [t] (print "[TEXT]" t) (flush))
  :on-thinking (fn [t] (print "[THINK]" t) (flush))
  :on-done (fn [reason] (println "\nDONE stop-reason:" reason) (deliver done-promise :ok))
  :on-error (fn [e] (println "\nERROR:" e) (deliver done-promise :error))})

(deref done-promise 60000 :timeout)
