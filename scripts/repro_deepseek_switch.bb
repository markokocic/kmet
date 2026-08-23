;; Repro 2: mid-session switch — history built by ANOTHER model (anthropic-
;; style thinking + tool round) is replayed against commandcode deepseek,
;; exactly what happens after /model switch. Observe event classification.
(require '[kmet.ai.llm :as llm]
         '[kmet.ai.models :as models]
         '[kmet.ai.api.shared :as shared]
         '[kmet.ai.auth :as auth])

(models/load-catalogs!)
(models/load-models-config!)
(auth/load-auth!)

(def provider :commandcode)
(def model-id "deepseek/deepseek-v4-flash")

(require '[kmet.ai.api.openai-completions :as oc])

(def m (models/get-model provider model-id))
(println "== payload kmet would build ==")
(println (pr-str (oc/openai-payload
                  m :max
                  [{:role :user :content [{:type :text :text "list files"}]}
                   {:role :assistant :content [{:type :text :text "Checking."}]
                    :thinking "I should run ls to see the files."
                    :tool-calls [{:id "call_1" :name "bash"
                                  :arguments {:command "ls"}}]}
                   {:role :tool
                    :content [{:type :tool_result :tool_use_id "call_1"
                               :content "a.txt b.txt"}]
                    :tool-name "bash"}]
                  [] "deepseek/deepseek-v4-flash")))
(println)

(def done-promise (promise))
(llm/send-message
 {:provider provider
  :model model-id
  :messages
  [{:role :user :content [{:type :text :text "list the files"}]}
   {:role :assistant :content [{:type :text :text "Checking."}]
    ;; thinking captured while running the PREVIOUS model (ox-alpha)
    :thinking "The user wants files; run ls."
    :tool-calls [{:id "call_1" :name "bash" :arguments {:command "ls"}}]}
   {:role :tool
    :content [{:type :tool_result :tool_use_id "call_1" :content "a.txt\nb.txt"}]
    :tool-name "bash"}]
  :tools []
  :thinking :max
  :on-text (fn [t] (print "[TEXT]" t) (flush))
  :on-thinking (fn [t] (print "[THINK]" t) (flush))
  :on-done (fn [reason] (println "\nDONE stop-reason:" reason) (deliver done-promise :ok))
  :on-error (fn [e] (println "\nERROR:" e) (deliver done-promise :error))})

(deref done-promise 60000 :timeout)
