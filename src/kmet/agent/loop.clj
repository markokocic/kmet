(ns kmet.agent.loop
  "Agent conversation loop — orchestrates user input, LLM calls, and tool execution.
   State machine: IDLE → THINKING → EXECUTING → THINKING → ... → IDLE"
  (:require [clojure.string :as str]
            [cheshire.core :as json]
            [kmet.agent.llm :as llm]
            [kmet.agent.tools :as tools]
            [kmet.agent.session :as session]
            [kmet.config :as cfg]))

;; ─── Agent state ───────────────────────────────────────────────────────────

(defrecord AgentState [status        ;; :idle :thinking :executing :done :error
                       messages      ;; atom of conversation message vectors
                       session       ;; Session record or nil
                       model         ;; model identifier
                       provider      ;; :openai :anthropic :opencode-go
                       system        ;; system prompt string
                       signal        ;; atom for cancellation
                       compact-threshold ;; int: auto-compact when entries exceed this
                       thinking      ;; :off :low :medium :high :max
                       on-event      ;; callback for state updates
                       base-url      ;; custom base URL (for OpenAI-compatible providers)
                       api-type])    ;; :openai or :anthropic

(defn make-agent-state
  "Create a new agent state.
   opts: :model, :provider, :system, :session, :on-event, :compact-threshold, :thinking, :base-url, :api-type"
  [& {:keys [model provider system session on-event compact-threshold thinking base-url api-type]
      :or {provider :openai
           thinking :off
           system "You are kmet, a minimal coding agent. Help the user with their tasks.
Use the available tools to read, write, edit files, and execute commands.
Be precise and concise in your responses."}}]
  (map->AgentState {:status (atom :idle)
                    :messages (atom [])
                    :session session
                    :model (atom model)
                    :provider (atom provider)
                    :system (atom system)
                    :signal (atom false)
                    :compact-threshold compact-threshold
                    :thinking (atom thinking)
                    :on-event on-event
                    :base-url base-url
                    :api-type api-type}))

;; ─── Helpers ───────────────────────────────────────────────────────────────

(defn- emit [agent event]
  (when-let [cb (:on-event agent)]
    (cb event)))

(defn- user-message [text]
  {:role :user :content [{:type :text :text text}]})

(defn- assistant-message [text tool-calls]
  (let [content (if (seq text) [{:type :text :text text}] [])]
    (cond-> {:role :assistant :content content}
      (seq tool-calls) (assoc :tool-calls tool-calls))))

(defn- tool-result-message [tc-id _tc-name result]
  {:role :tool
   :content [{:type :tool_result
              :tool_use_id tc-id
              :content (:content result)}]
   :is-error (:is-error result false)})

;; ─── Tool call accumulator ─────────────────────────────────────────────────

(defn- make-tc-accumulator []
  (let [pending (atom {})]
    [(fn [tc]
       (let [id (:id tc)]
         (if-let [name (:name tc)]
           (swap! pending assoc id
             {:name name :arguments (or (:arguments tc) "")})
           (swap! pending update-in [id :arguments]
             (fn [old] (str (or old "") (or (:arguments tc) "")))))))
     (fn []
       (let [result (into []
                      (for [[id {:keys [name arguments]}] @pending]
                        {:id id :name name
                         :arguments (try
                                      (json/parse-string arguments)
                                      (catch Exception _ arguments))}))]
         (reset! pending {})
         result))]))

;; ─── LLM call wrapper ─────────────────────────────────────────────────────

(defn- call-llm
  "Send messages to LLM, return a promise that delivers {:text str :tool-calls [...] :stop-reason kw}.
   Calls on-text for text deltas during streaming."
  [agent api-key text-buf on-text on-thinking]
  (let [done-promise (promise)
        [tc-add tc-flush] (make-tc-accumulator)
        provider @(:provider agent)
        system @(:system agent)
        messages (if system
                   (into [{:role :system :content [{:type :text :text system}]}]
                         @(:messages agent))
                   @(:messages agent))]
    (llm/send-message
      {:provider provider
       :api-type (or (:api-type agent) (cfg/get-provider-api-type provider))
       :model @(:model agent)
       :api-key api-key
       :base-url (or (:base-url agent) (cfg/get-provider-base-url provider))
       :messages messages
       :tools (when (cfg/provider-supports-tools? provider)
                (vals (tools/get-all-tools)))
       :signal (:signal agent)
       :thinking @(:thinking agent)
       :on-text (fn [t]
                  (swap! text-buf str t)
                  (when on-text (on-text t)))
       :on-thinking on-thinking
       :on-tool-call (fn [tc] (tc-add tc))
       :on-done (fn [reason]
                  (let [tool-calls (tc-flush)]
                    (deliver done-promise
                      {:text @text-buf
                       :tool-calls tool-calls
                       :stop-reason reason})))
       :on-error (fn [e]
                   (deliver done-promise {:error e}))})
    done-promise))

;; ─── Agent turn ────────────────────────────────────────────────────────────

(defn run-agent-turn
  "Run a single turn of the agent loop.
   agent    — AgentState record
   opts:
     :message  — user message string (required)
     :on-text  — (fn [text-delta]) streaming text callback
     :on-done  — (fn [response-text]) final response callback
     :on-error — (fn [error]) error callback

   Returns: future that completes when the turn is done."
  [agent {:keys [message on-text on-thinking on-done on-error]}]
  (reset! (:signal agent) false)
  (let [provider @(:provider agent)
        api-key (cfg/get-api-key provider)]
    (if (nil? api-key)
      (do (when on-error
            (on-error (str "No API key for " (name provider)
                           ". Set the key in ~/.config/kmet/auth.edn or the appropriate environment variable.")))
          (future))
      (future
        (try
          ;; Add user message to history
          (let [user-msg (user-message message)]
            (swap! (:messages agent) conj user-msg)
            (when (:session agent)
              (session/append-entry (:session agent)
                {:role :user :content (:content user-msg)})))

          ;; Auto-compact session if needed
          (when-let [sess (:session agent)]
            (when-let [threshold (:compact-threshold agent)]
              (let [n-entries (count @(:entries sess))]
                (when (>= n-entries threshold)
                  (session/compact! sess (quot threshold 2))
                  (binding [*out* *err*]
                    (println "Compacted session:" n-entries "→" (count @(:entries sess)) "entries"))))))

          ;; State: thinking
          (reset! (:status agent) :thinking)
          (emit agent {:type :status :status :thinking})

          ;; Main loop: LLM → tools → LLM → ... → done
          (let [text-buf (atom "")
                max-turns 20]
            (loop [turn 0]
              (if (>= turn max-turns)
                (do (when on-error (on-error "Max turn limit reached"))
                    (reset! (:status agent) :error))
                (let [promise (do (reset! text-buf "") (call-llm agent api-key text-buf on-text on-thinking))
                      result (deref promise 120000 :timeout)]
                  (if (= :timeout result)
                    (do (reset! (:signal agent) true)
                        (when on-error (on-error "LLM call timed out after 120s"))
                        (reset! (:status agent) :error))
                    (if (:error result)
                    (do (when on-error (on-error (:error result)))
                        (reset! (:status agent) :error))
                    (let [text (:text result)
                          tool-calls (:tool-calls result)]
                      (if (seq tool-calls)
                        ;; Execute tool calls
                        (let [assistant-msg (assistant-message text tool-calls)]
                          (swap! (:messages agent) conj assistant-msg)
                          (when (:session agent)
                            (session/append-entry (:session agent) assistant-msg))

                          ;; State: executing
                          (reset! (:status agent) :executing)
                          (emit agent {:type :status :status :executing
                                       :tool-calls tool-calls})

                          ;; Execute each tool
                          (doseq [tc tool-calls]
                            (let [result (tools/execute-tool (:name tc) (:arguments tc))
                                  result-msg (tool-result-message (:id tc) (:name tc) result)]
                              (swap! (:messages agent) conj result-msg)
                              (when (:session agent)
                                (session/append-entry (:session agent) result-msg))
                              (emit agent {:type :tool-result
                                           :id (:id tc)
                                           :name (:name tc)
                                           :result result})))

                          ;; State: thinking again, continue loop
                          (reset! (:status agent) :thinking)
                          (emit agent {:type :status :status :thinking})
                          (recur (inc turn)))

                        ;; Final response
                        (let [assistant-msg (assistant-message text nil)]
                          (swap! (:messages agent) conj assistant-msg)
                          (when (:session agent)
                            (session/append-entry (:session agent) assistant-msg))
                          (reset! (:status agent) :idle)
                          (emit agent {:type :status :status :idle})
                          (when on-done (on-done @text-buf)))))))))))

          (catch Exception e
            (reset! (:status agent) :error)
            (emit agent {:type :error :message (.getMessage e)})
            (when on-error (on-error (.getMessage e)))))))))

;; ─── Cancellation ──────────────────────────────────────────────────────────

(defn cancel-turn [agent]
  (reset! (:signal agent) true)
  (reset! (:status agent) :idle)
  (emit agent {:type :status :status :idle})
  nil)

;; ─── State helpers ─────────────────────────────────────────────────────────

(defn get-context [agent]
  @(:messages agent))

(defn set-system-prompt! [agent prompt]
  (reset! (:system agent) prompt))

(defn set-model! [agent model]
  (reset! (:model agent) model))

(defn set-provider! [agent provider]
  (reset! (:provider agent) provider))

(defn get-status [agent]
  @(:status agent))
