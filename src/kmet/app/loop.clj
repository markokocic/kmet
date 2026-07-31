(ns kmet.app.loop
  "Agent conversation loop — orchestrates user input, LLM calls, and tool execution.
   State machine: IDLE → THINKING → EXECUTING → THINKING → ... → IDLE

   Queues (pi: steeringQueue / followUpQueue):
     :steering  — user messages injected mid-run. Polled between turns (after
                   tool results, before the next LLM call). Use steer!.
     :follow-up — user messages processed after the current run settles.
                   Polled when the inner loop drains; the run continues with
                   them. Use follow-up!.
   :steering-mode / :follow-up-mode (:all default) control how many queued
   messages are drained per poll cycle: :all takes everything,
   :one-at-a-time takes one. Applied per queue.

   Emits lifecycle events (see kmet.app.events) to the :on-event callback
   (UI) and to kmet.app.skills/emit-event! (extension system).

   Auto-retry (pi: agent-session auto-retry): transient LLM errors are retried
   with exponential backoff (base-delay-ms * 2^(attempt-1)), up to max-retries.
   Quota/billing errors and context overflow are never retried. Retry emits
   :auto-retry-start / :auto-retry-end events; cancellation during backoff
   aborts the run quietly.

   Tool hooks (pi: beforeToolCall / afterToolCall): registered via
   set-before-tool-call! / set-after-tool-call!. The before hook can block
   execution ({:block true :reason ...}); the after hook can rewrite the
   result (:content / :is-error)."
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [kmet.app.llm :as llm]
            [kmet.app.tools.core :as tools]
            [kmet.app.session :as session]
            [kmet.app.skills :as skills]
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
                       api-type      ;; :openai or :anthropic
                       steering      ;; atom of vector of queued steer messages
                       follow-up     ;; atom of vector of queued follow-up messages
                       steering-mode ;; :all | :one-at-a-time (drain mode)
                       follow-up-mode ;; :all | :one-at-a-time (drain mode)
                       active-call      ;; atom of in-flight LLM promise (for cancel)
                       max-retries      ;; int: max auto-retry attempts on transient LLM errors (pi default 3)
                       base-delay-ms    ;; int: exponential backoff base in ms (pi default 2000)
                       retry-count      ;; atom of int: retries performed for the in-flight LLM call
                       before-tool-call ;; atom of (fn [ctx]) → {:block true :reason} | nil
                       after-tool-call]) ;; atom of (fn [ctx]) → override map | nil

(defn make-agent-state
  "Create a new agent state.
   opts: :model, :provider, :system, :session, :on-event, :compact-threshold,
         :thinking, :base-url, :api-type, :steering-mode, :follow-up-mode,
         :max-retries (default 3), :base-delay-ms (default 2000),
         :before-tool-call, :after-tool-call"
  [& {:keys [model provider system session on-event compact-threshold thinking base-url api-type steering-mode follow-up-mode max-retries base-delay-ms before-tool-call after-tool-call]
      :or {provider :openai
           thinking :off
           steering-mode :all
           follow-up-mode :all
           max-retries 3
           base-delay-ms 2000
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
                    :api-type api-type
                    :steering (atom [])
                    :follow-up (atom [])
                    :steering-mode steering-mode
                    :follow-up-mode follow-up-mode
                    :active-call (atom nil)
                    :max-retries max-retries
                    :base-delay-ms base-delay-ms
                    :retry-count (atom 0)
                    :before-tool-call (atom before-tool-call)
                    :after-tool-call (atom after-tool-call)}))

;; ─── Helpers ───────────────────────────────────────────────────────────────

(defn- emit
  "Route an event to the UI callback (:on-event) and the extension system
   (skills/emit-event!). Extension listeners run inside emit-event!, which
   catches per-listener exceptions so a broken extension can't kill the loop."
  [agent event]
  (when-let [cb (:on-event agent)]
    (cb event))
  (skills/emit-event! event))

(defn- user-message [text]
  {:role :user :content [{:type :text :text text}]})

(defn- assistant-message [text tool-calls]
  (let [content (if (seq text) [{:type :text :text text}] [])]
    (cond-> {:role :assistant :content content}
      (seq tool-calls) (assoc :tool-calls tool-calls))))

(defn- tool-result-message [tc-id _tc-name result]
  (cond-> {:role :tool
           :content [{:type :tool_result
                      :tool_use_id tc-id
                      :content (:content result)}]
           :is-error (:is-error result false)}
    (:images result) (assoc :images (:images result))))

;; ─── Error classification (auto-retry) ─────────────────────────────────────

(def ^:private non-retryable-error-regex
  "Combined regex for quota/billing/account-limit error messages — never retried.
   Mirrors pi's NON_RETRYABLE_PROVIDER_LIMIT_ERROR_PATTERN
   (packages/ai/src/utils/retry.ts)."
  (re-pattern (str "(?i)"
                   (str/join "|"
                             ["GoUsageLimitError"
                              "FreeUsageLimitError"
                              "Monthly usage limit reached"
                              "available balance"
                              "insufficient_quota"
                              "out of budget"
                              "quota exceeded"
                              "billing"]))))

(def ^:private retryable-error-regex
  "Combined regex for transient provider/transport error messages — retryable.
   Mirrors pi's RETRYABLE_PROVIDER_ERROR_PATTERN (packages/ai/src/utils/retry.ts)."
  (re-pattern (str "(?i)"
                   (str/join "|"
                             ["overloaded"
                              "rate.?limit"
                              "too many requests"
                              "429" "500" "502" "503" "504" "524"
                              "service.?unavailable"
                              "server.?error"
                              "internal.?error"
                              "provider.?returned.?error"
                              "network.?error"
                              "connection.?error"
                              "connection.?refused"
                              "connection.?lost"
                              "other side closed"
                              "fetch failed"
                              "getaddrinfo"
                              "ENOTFOUND"
                              "EAI_AGAIN"
                              "upstream.?connect"
                              "reset before headers"
                              "socket hang up"
                              "socket connection was closed"
                              "timed? out"
                              "timeout"
                              "terminated"
                              "websocket.?closed"
                              "websocket.?error"
                              "ended without"
                              "stream ended before message_stop"
                              "stream ended before a terminal response event"
                              "http2 request did not get a response"
                              "retry delay"
                              "you can retry your request"
                              "try your request again"
                              "please retry your request"
                              "ResourceExhausted"]))))

(def ^:private overflow-error-regex
  "Combined regex for context-window overflow error messages.
   Mirrors pi's OVERFLOW_PATTERNS (packages/ai/src/utils/overflow.ts)."
  (re-pattern (str "(?i)"
                   (str/join "|"
                             ["prompt is too long"
                              "request_too_large"
                              "input is too long for requested model"
                              "exceeds the context window"
                              "exceeds (?:the )?(?:model'?s )?maximum context length(?: of [\\d,]+ tokens?|\\s*\\([\\d,]+\\))"
                              "input token count.*exceeds the maximum"
                              "maximum prompt length is \\d+"
                              "reduce the length of the messages"
                              "maximum context length is \\d+ tokens"
                              "exceeds (?:the )?maximum allowed input length of [\\d,]+ tokens?"
                              "input \\(\\d+ tokens\\) is longer than the model'?s context length \\(\\d+ tokens\\)"
                              "exceeds the limit of \\d+"
                              "exceeds the available context size"
                              "greater than the context length"
                              "context window exceeds limit"
                              "exceeded model token limit"
                              "too large for model with \\d+ maximum context length"
                              "prompt has [\\d,]+ tokens?, but the configured context size is [\\d,]+ tokens?"
                              "model_context_window_exceeded"
                              "prompt too long; exceeded (?:max )?context length"
                              "range of input length should be"
                              "context[_ ]length[_ ]exceeded"
                              "too many tokens"
                              "token limit exceeded"
                              "^4(?:00|13)\\s*(?:status code)?\\s*\\(no body\\)"]))))

(def ^:private non-overflow-error-regex
  "Combined regex for errors that look like overflow but are actually throttling
   (e.g. Bedrock 'Throttling error: Too many tokens'). Mirrors pi's
   NON_OVERFLOW_PATTERNS."
  (re-pattern (str "(?i)"
                   (str/join "|"
                             ["^(Throttling error|Service unavailable):"
                              "rate limit"
                              "too many requests"]))))

(defn retryable-error?
  "True if an error message looks like a transient provider/transport failure
   worth retrying (rate limit, 5xx, connection loss, timeout, ...).
   Quota/billing/account-limit errors are never retried.
   Mirrors pi's isRetryableAssistantError."
  [error-message]
  (and (string? error-message)
       (not (re-find non-retryable-error-regex error-message))
       (re-find retryable-error-regex error-message)))

(defn context-overflow?
  "True if an error message indicates a context-window overflow. Overflow is
   NOT auto-retried (it needs compaction) — mirrors pi's isContextOverflow
   error-message case."
  [error-message]
  (and (string? error-message)
       (not (re-find non-overflow-error-regex error-message))
       (re-find overflow-error-regex error-message)))

;; ─── Queue helpers ─────────────────────────────────────────────────────────

(defn- drain-queue!
  "Atomically remove and return queued messages.
   mode :all drains everything; :one-at-a-time drains at most one."
  [queue-atom mode]
  (loop []
    (let [v @queue-atom]
      (if (empty? v)
        []
        (let [taken (if (= mode :all) v (subvec v 0 1))
              rest-v (if (= mode :all) [] (subvec v 1))]
          (if (compare-and-set! queue-atom v rest-v)
            taken
            (recur)))))))

(defn- add-user-message!
  "Add a user message to context and session, emitting :message-start."
  [agent text]
  (let [user-msg (user-message text)]
    (swap! (:messages agent) conj user-msg)
    (when (:session agent)
      (session/append-entry (:session agent)
        {:role :user :content (:content user-msg)}))
    (emit agent {:type :message-start :message user-msg})))

(defn- add-assistant-message!
  "Add a final assistant message to context and session, emitting
   :message-end. Returns the message."
  [agent text tool-calls]
  (let [assistant-msg (assistant-message text tool-calls)]
    (swap! (:messages agent) conj assistant-msg)
    (when (:session agent)
      (session/append-entry (:session agent) assistant-msg))
    (emit agent {:type :message-end :message assistant-msg})
    assistant-msg))

(defn- run-tool-call
  "Execute one tool call, polling every 200ms for progress pings.
   Returns the result map {:content ... :is-error ...}."
  [agent tc-id tc-name tc-args]
  (let [f (future (tools/execute-tool tc-name tc-args))]
    (loop []
      (let [v (deref f 200 :pending)]
        (if (= :pending v)
          (do (emit agent {:type :tool-execution-update :tool-call-id tc-id})
              (recur))
          v)))))

(defn- before-tool-hook-result
  "Run the before-tool-call hook if registered (pi: beforeToolCall).
   Returns {:block true :reason ...} to block execution, or nil to allow.
   A throwing hook blocks with the error message as reason."
  [agent tc-id tc-name tc-args assistant-msg]
  (when-let [hook @(:before-tool-call agent)]
    (try
      (hook {:assistant-message assistant-msg
             :tool-call-id tc-id
             :tool-name tc-name
             :args tc-args})
      (catch Exception e
        {:block true
         :reason (str "before-tool-call hook error: " (.getMessage e))}))))

(defn- after-tool-hook-result
  "Run the after-tool-call hook if registered (pi: afterToolCall), merging any
   returned :content / :is-error overrides into the result. A throwing hook
   turns the result into an error."
  [agent tc-id tc-name tc-args result assistant-msg]
  (if-let [hook @(:after-tool-call agent)]
    (try
      (let [hook-result (hook {:assistant-message assistant-msg
                               :tool-call-id tc-id
                               :tool-name tc-name
                               :args tc-args
                               :result result
                               :is-error (:is-error result false)})]
        (if hook-result
          (cond-> result
            (:content hook-result) (assoc :content (:content hook-result))
            (contains? hook-result :is-error) (assoc :is-error (:is-error hook-result)))
          result))
      (catch Exception e
        {:content (str "after-tool-call hook error: " (.getMessage e))
         :is-error true}))
    result))

(defn- execute-tool-calls!
  "Execute tool calls sequentially, emitting execution events and appending
   results to context and session. Runs before-tool-call/after-tool-call hooks
   when registered (see set-before-tool-call! / set-after-tool-call!).
   Returns the vector of result maps."
  [agent tool-calls assistant-msg]
  (let [tool-results (atom [])]
    (doseq [tc tool-calls]
      (let [tc-id (:id tc)
            tc-name (:name tc)
            tc-args (:arguments tc)]
        (emit agent {:type :tool-execution-start
                     :tool-call-id tc-id
                     :tool-name tc-name
                     :args tc-args})
        (let [before (before-tool-hook-result agent tc-id tc-name tc-args assistant-msg)
              result (if (:block before)
                       {:content (or (:reason before) "Tool execution was blocked")
                        :is-error true}
                       (run-tool-call agent tc-id tc-name tc-args))
              result (after-tool-hook-result agent tc-id tc-name tc-args result assistant-msg)
              result-msg (tool-result-message tc-id tc-name result)]
          (swap! (:messages agent) conj result-msg)
          (when (:session agent)
            (session/append-entry (:session agent) result-msg))
          (swap! tool-results conj result)
          (emit agent {:type :tool-execution-end
                       :tool-call-id tc-id
                       :tool-name tc-name
                       :args tc-args
                       :result result
                       :is-error (:is-error result false)}))))
    @tool-results))

;; ─── Tool call accumulator ─────────────────────────────────────────────────

(defn- make-tc-accumulator []
  (let [pending (atom {})]
    [(fn [tc]
       (let [idx (:index tc)
             name (:name tc)]
         (if name
           (swap! pending assoc idx
             {:id (:id tc) :name name :arguments (or (:arguments tc) "")})
           (swap! pending update-in [idx :arguments]
             (fn [old] (str (or old "") (or (:arguments tc) "")))))))
     (fn []
       (let [result (into []
                      (for [[idx {:keys [id name arguments]}] @pending]
                        {:id id :name name
                         :arguments (try
                                      (json/parse-string arguments true)
                                      (catch Exception _ arguments))}))]
         (reset! pending {})
         result))]))

;; ─── LLM call wrapper ─────────────────────────────────────────────────────

(defn- call-llm
  "Send messages to LLM, return a promise that delivers {:text str :tool-calls [...] :stop-reason kw}.
   Calls on-text for text deltas during streaming."
  [agent api-key text-buf on-text on-thinking]
  (let [done-promise (promise)
        thinking-buf (atom "")
        [tc-add tc-flush] (make-tc-accumulator)
        provider @(:provider agent)
        system @(:system agent)
        messages (if system
                   (into [{:role :system :content [{:type :text :text system}]}]
                         @(:messages agent))
                   @(:messages agent))]
    ;; Assistant message lifecycle: streaming begins
    (emit agent {:type :message-start
                 :message {:role :assistant :content []}})
    (llm/send-message
      {:provider provider
       :api-type (or (:api-type agent) (cfg/get-provider-api-type provider))
       :model @(:model agent)
       :api-key api-key
       :base-url (or (:base-url agent) (cfg/get-provider-base-url provider))
       :messages messages
       :tools (vals (tools/get-all-tools))
       :signal (:signal agent)
       :thinking @(:thinking agent)
       :on-text (fn [t]
                  (swap! text-buf str t)
                  (when on-text (on-text t))
                  (emit agent {:type :message-update
                               :message {:role :assistant
                                         :content [{:type :text :text @text-buf}]}
                               :delta {:type :text :content t}}))
       :on-thinking (fn [t]
                      (swap! thinking-buf str t)
                      (when on-thinking (on-thinking t))
                      (emit agent {:type :message-update
                                   :message {:role :assistant
                                             :content []
                                             :thinking @thinking-buf}
                                   :delta {:type :thinking :content t}}))
       :on-tool-call (fn [tc]
                       (tc-add tc)
                       (emit agent {:type :message-update
                                    :message {:role :assistant :content []}
                                    :delta (assoc (select-keys tc [:id :name :arguments :index])
                                                  :type :tool-call)}))
       :on-done (fn [reason]
                  (let [tool-calls (tc-flush)]
                    (deliver done-promise
                      {:text @text-buf
                       :tool-calls tool-calls
                       :stop-reason reason})))
       :on-error (fn [e]
                   (deliver done-promise {:error e}))})
    done-promise))

;; ─── Queues ────────────────────────────────────────────────────────────────

(defn steer!
  "Queue a user message for mid-turn injection.
   The agent loop polls the steering queue between turns (after tool results,
   before the next LLM call) and injects queued messages into the context."
  [agent text]
  (swap! (:steering agent) conj text)
  (emit agent {:type :queue-update
               :steering @(:steering agent)
               :follow-up @(:follow-up agent)})
  nil)

(defn follow-up!
  "Queue a user message to be processed after the current run settles.
   The outer loop drains the follow-up queue when the inner loop finishes and
   continues the run with the queued messages."
  [agent text]
  (swap! (:follow-up agent) conj text)
  (emit agent {:type :queue-update
               :steering @(:steering agent)
               :follow-up @(:follow-up agent)})
  nil)

(defn clear-queues!
  "Drop all pending steering and follow-up messages."
  [agent]
  (reset! (:steering agent) [])
  (reset! (:follow-up agent) [])
  (emit agent {:type :queue-update :steering [] :follow-up []})
  nil)

(defn queued-messages
  "Return {:steering [...] :follow-up [...]} of currently queued messages."
  [agent]
  {:steering @(:steering agent)
   :follow-up @(:follow-up agent)})

(defn has-queued-messages?
  "True if either queue has pending messages."
  [agent]
  (or (seq @(:steering agent))
      (seq @(:follow-up agent))))

;; ─── Agent run ─────────────────────────────────────────────────────────────

(defn- backoff-sleep!
  "Sleep delay-ms in 100ms increments, aborting early if the cancel signal is
   set. Returns true if the full delay elapsed, false if cancelled."
  [agent delay-ms]
  (let [end-ms (+ (System/currentTimeMillis) delay-ms)]
    (loop []
      (if @(:signal agent)
        false
        (let [remaining (- end-ms (System/currentTimeMillis))]
          (if (<= remaining 0)
            true
            (do (Thread/sleep (min 100 remaining))
                (recur))))))))

(defn run-agent-turn
  "Run the agent loop until it settles.
   agent    — AgentState record
   opts:
     :message  — optional initial user message string
     :on-text  — (fn [text-delta]) streaming text callback
     :on-done  — (fn [response-text]) final response callback
     :on-error — (fn [error]) error callback

   Loop structure (mirrors pi):
     outer: drain follow-up queue → continue inner
     inner: LLM call → tool execution → drain steering queue → repeat
            until no tool calls, no steering messages, and at least one
            turn has run.

   Cancellation: cancel-turn sets the signal and delivers {:cancelled true}
   to the in-flight LLM promise (active-call); the loop exits quietly
   (no on-error, no on-done).

   Emits lifecycle events (see kmet.app.events) to the UI callback and the
   extension system via emit.
   Returns: future that completes when the agent run is done."
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
          (let [msg-count-before (count @(:messages agent))]
            ;; Initial user message
            (when message
              (add-user-message! agent message))

            ;; Auto-compact session if needed
            (when-let [sess (:session agent)]
              (when-let [threshold (:compact-threshold agent)]
                (let [n-entries (count @(:entries sess))]
                  (when (>= n-entries threshold)
                    (session/compact! sess (quot threshold 2))
                    (binding [*out* *err*]
                      (println "Compacted session:" n-entries "→" (count @(:entries sess)) "entries"))))))

            ;; Agent lifecycle: start
            (emit agent {:type :agent-start})
            (reset! (:status agent) :thinking)
            (emit agent {:type :status :status :thinking})

            (let [text-buf (atom "")
                  max-turns 20
                  agent-end (fn [error]
                              (emit agent {:type :agent-end
                                           :messages (subvec @(:messages agent) msg-count-before)
                                           :error error}))]
              ;; Outer loop: follow-up
              (loop [turn 0]
                (if (>= turn max-turns)
                  (do (when on-error (on-error "Max turn limit reached"))
                      (reset! (:status agent) :error)
                      (emit agent {:type :error :message "Max turn limit reached"})
                      (agent-end "Max turn limit reached"))
                  ;; Inner loop: LLM → tools → steer → ... → settle
                  (let [inner (loop [t turn prev-tool-calls [] must-run true]
                                (if (or (>= t max-turns)
                                        @(:signal agent)
                                        (and (not must-run)
                                             (empty? prev-tool-calls)
                                             (empty? @(:steering agent))))
                                  (if @(:signal agent)
                                    (do (reset! (:status agent) :idle)
                                        (emit agent {:type :status :status :idle})
                                        (emit agent {:type :agent-end
                                                     :messages (subvec @(:messages agent) msg-count-before)})
                                        {:aborted true}) ;; cancelled — exit quietly
                                    {:settled t})
                                  (let [steer-msgs (drain-queue! (:steering agent)
                                                                  (:steering-mode agent))]
                                    (doseq [m steer-msgs]
                                      (add-user-message! agent m))
                                    (emit agent {:type :turn-start :turn-index t})
                                    (let [promise (do (reset! text-buf "")
                                                      (call-llm agent api-key text-buf on-text on-thinking))]
                                      (reset! (:active-call agent) promise)
                                      (let [result (deref promise 120000 :timeout)]
                                        (reset! (:active-call agent) nil)
                                        (if (:cancelled result)
                                          (do (emit agent {:type :agent-end
                                                           :messages (subvec @(:messages agent) msg-count-before)})
                                              {:aborted true})
                                          (if (= :timeout result)
                                            (do (reset! (:signal agent) true)
                                                (when on-error (on-error "LLM call timed out after 120s"))
                                                (reset! (:status agent) :error)
                                                (emit agent {:type :error :message "LLM call timed out after 120s"})
                                                (agent-end "LLM call timed out after 120s")
                                                {:aborted true})
                                            (if (:error result)
                                              (let [err (:error result)
                                                    max-retries (:max-retries agent)]
                                                (if (and (<= (inc @(:retry-count agent)) max-retries)
                                                         (not (context-overflow? err))
                                                         (retryable-error? err))
                                                  ;; Auto-retry with exponential backoff (pi: _prepareRetry)
                                                  (let [attempt (inc @(:retry-count agent))
                                                        delay-ms (* (:base-delay-ms agent)
                                                                    (long (Math/pow 2 (dec attempt))))]
                                                    (reset! (:retry-count agent) attempt)
                                                    (emit agent {:type :auto-retry-start
                                                                 :attempt attempt
                                                                 :max-attempts max-retries
                                                                 :delay-ms delay-ms
                                                                 :error-message err})
                                                    (if (backoff-sleep! agent delay-ms)
                                                      ;; Same turn, same context — no new user message
                                                      (recur t prev-tool-calls must-run)
                                                      ;; Cancelled during backoff
                                                      (do (emit agent {:type :auto-retry-end
                                                                       :success false
                                                                       :attempt attempt
                                                                       :final-error "Retry cancelled"})
                                                          (reset! (:retry-count agent) 0)
                                                          (reset! (:status agent) :idle)
                                                          (emit agent {:type :status :status :idle})
                                                          (emit agent {:type :agent-end
                                                                       :messages (subvec @(:messages agent) msg-count-before)})
                                                          {:aborted true})))
                                                  ;; Terminal error (non-retryable or retries exhausted)
                                                  (do (when (pos? @(:retry-count agent))
                                                        (let [n @(:retry-count agent)]
                                                          (emit agent {:type :auto-retry-end
                                                                       :success false
                                                                       :attempt n
                                                                       :final-error err})
                                                          (reset! (:retry-count agent) 0)))
                                                      (when on-error (on-error err))
                                                      (reset! (:status agent) :error)
                                                      (emit agent {:type :error :message err})
                                                      (agent-end err)
                                                      {:aborted true})))
                                              (let [retried @(:retry-count agent)]
                                                ;; A retried LLM call succeeded — reset the budget
                                                (when (pos? retried)
                                                  (reset! (:retry-count agent) 0)
                                                  (emit agent {:type :auto-retry-end
                                                               :success true
                                                               :attempt retried}))
                                                (let [text (:text result)
                                                      tool-calls (:tool-calls result)]
                                                  (if (seq tool-calls)
                                                    ;; Execute tool calls
                                                    (let [assistant-msg (add-assistant-message! agent text tool-calls)]
                                                      (reset! (:status agent) :executing)
                                                      (emit agent {:type :status :status :executing
                                                                   :tool-calls tool-calls})
                                                      (let [tool-results (execute-tool-calls! agent tool-calls assistant-msg)]
                                                        (reset! (:status agent) :thinking)
                                                        (emit agent {:type :status :status :thinking})
                                                        (emit agent {:type :turn-end
                                                                     :message assistant-msg
                                                                     :tool-results tool-results})
                                                        (recur (inc t) tool-calls false)))
                                                    ;; Final response
                                                    (let [assistant-msg (add-assistant-message! agent text nil)]
                                                      (emit agent {:type :turn-end
                                                                   :message assistant-msg
                                                                   :tool-results []})
                                                      (recur (inc t) [] false)))))))))))))]
                    (if (:aborted inner)
                      nil ;; aborted (error or cancel) — exit without follow-ups
                      (let [turn' (:settled inner)]
                        ;; Outer: poll follow-up queue
                        (let [follow-ups (drain-queue! (:follow-up agent)
                                                        (:follow-up-mode agent))]
                          (if (seq follow-ups)
                            (do (doseq [m follow-ups]
                                  (add-user-message! agent m))
                                (reset! (:status agent) :thinking)
                                (emit agent {:type :status :status :thinking})
                                (recur turn'))
                            (do (reset! (:status agent) :idle)
                                (emit agent {:type :status :status :idle})
                                (emit agent {:type :agent-end
                                             :messages (subvec @(:messages agent) msg-count-before)})
                                (when on-done (on-done @text-buf))))))))))))

          (catch Exception e
            (reset! (:status agent) :error)
            (emit agent {:type :error :message (.getMessage e)})
            (when on-error (on-error (.getMessage e)))))))))

;; ─── Cancellation ──────────────────────────────────────────────────────────

(defn cancel-turn
  "Cancel the current agent run: signal the LLM stream, drop queued messages,
   release the in-flight LLM call, return status to :idle."
  [agent]
  (reset! (:signal agent) true)
  (clear-queues! agent)
  (when-let [p @(:active-call agent)]
    (when-not (realized? p)
      (deliver p {:cancelled true})))
  (reset! (:status agent) :idle)
  (emit agent {:type :status :status :idle})
  nil)

;; ─── State helpers ─────────────────────────────────────────────────────────

(defn get-context [agent]
  @(:messages agent))

(defn set-system-prompt! [agent prompt]
  (reset! (:system agent) prompt))

(defn set-before-tool-call!
  "Register a before-tool-call hook (pi: beforeToolCall).
   Hook: (fn [{:keys [assistant-message tool-call-id tool-name args]}])
   Return {:block true :reason \"...\"} to prevent execution, or nil to allow."
  [agent hook]
  (reset! (:before-tool-call agent) hook))

(defn set-after-tool-call!
  "Register an after-tool-call hook (pi: afterToolCall).
   Hook: (fn [{:keys [assistant-message tool-call-id tool-name args result is-error]}])
   Return a map with :content and/or :is-error to rewrite the result, or nil
   to keep it unchanged."
  [agent hook]
  (reset! (:after-tool-call agent) hook))

(defn set-model! [agent model]
  (reset! (:model agent) model))

(defn set-provider! [agent provider]
  (reset! (:provider agent) provider))

(defn get-status [agent]
  @(:status agent))
