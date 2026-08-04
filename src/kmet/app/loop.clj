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

   Emits lifecycle events (see kmet.app.event-bus) to the :on-event callback
   (UI) and to kmet.app.event-bus/emit-event! (extension system).

   Auto-retry (pi: agent-session auto-retry): transient LLM errors are retried
   with exponential backoff (base-delay-ms * 2^(attempt-1)), up to max-retries.
   Quota/billing errors and context overflow are never retried. Retry emits
   :auto-retry-start / :auto-retry-end events; cancellation during backoff
   aborts the run quietly.

   Tool hooks (pi: beforeToolCall / afterToolCall): registered via
   set-before-tool-call! / set-after-tool-call!. The before hook can block
   execution ({:block true :reason ...}); the after hook can rewrite the
   result (:content / :is-error).

   Tool execution mode (pi: toolExecution): tools default to :parallel;
   if any tool call targets a :sequential tool the whole batch runs
   sequentially (see :execution-mode on the Tool record).

   Per-turn hooks (pi: prepareNextTurn / shouldStopAfterTurn / transformContext):
   set-prepare-next-turn! / set-should-stop-after-turn! run after each turn
   (in pi order); set-transform-context! rewrites the conversation before each
   LLM call. set-system-prompt-override! sets a per-run system prompt override.

   Compaction (pi: auto-compaction): the session is compacted proactively
   (entry-count / token-estimate thresholds) and reactively after a
   context-overflow error (compact once, then retry). Compaction summarizes
   the pre-cut conversation via the LLM (kmet.app.compaction, pi:
   core/compaction) and replaces it with a summary entry; falls back to
   count-based truncation when summarization is unavailable.

   Model management (pi: setModel / cycleModel): set-models! sets the scoped
   model list; cycle-model! moves through it emitting :model-select.
   set-get-api-key! registers a dynamic API key resolver.

   Input hooks (pi: input extension event): applied at the interactive input
   path (modes.interactive handle-submit) via extensions/apply-input-hooks —
   extensions can consume ({:action :handled}) or rewrite
   ({:action :transform :text ...}) user input before the agent runs.

   before-agent-start hooks (pi: before_agent_start extension event): applied
   by run-agent-turn after the user message is added, before the first LLM
   call. Extensions can override the system prompt for the run
   ({:system-prompt s}) and inject context messages ({:message m}); injected
   messages default to role :info — display-only (rendered in the UI, excluded
   from the LLM context). Other roles pass through to the provider.

   Image attachments (pi: prompt(text, images)): run-agent-turn accepts
   :images — a vector of {:type :image :data base64 :mime-type str} blocks
   attached to the initial user message; they flow to the provider as OpenAI
   image_url / Anthropic image blocks. Input hooks receive and can transform
   :images (extensions/apply-input-hooks)."
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [kmet.app.llm :as llm]
            [kmet.app.compaction :as compaction]
            [kmet.app.tools.core :as tools]
            [kmet.app.tools.bash :as bash-tool]
            [kmet.app.session :as session]
            [kmet.app.extensions :as extensions]
            [kmet.app.event-bus :as event-bus]
            [kmet.debug :as debug]
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
                       after-tool-call  ;; atom of (fn [ctx]) → override map | nil
                       system-prompt-override ;; atom of string or nil (per-run override, pi: _systemPromptOverride)
                       transform-context      ;; atom of (fn [messages]) → messages (pi: transformContext)
                       prepare-next-turn      ;; atom of (fn [ctx]) → update map | nil (pi: prepareNextTurn)
                       should-stop-after-turn ;; atom of (fn [ctx]) → boolean (pi: shouldStopAfterTurn)
                       get-api-key            ;; atom of (fn [provider]) → key | nil (dynamic auth)
                       models                 ;; atom of vector of model ids (scoped list for cycling)
                       overflow-recovered     ;; atom of bool: context-overflow compacted once this run
                       compact-token-threshold ;; int or nil: compact when estimated tokens exceed this
                       keep-recent-tokens     ;; int: cut-point budget in tokens (pi: keepRecentTokens, default 20000)
                       compacting?])           ;; atom of bool: a compaction is in progress (escape cancels it)

(defn make-agent-state
  "Create a new agent state.
   opts: :model, :provider, :system, :session, :on-event, :compact-threshold,
         :thinking, :base-url, :api-type, :steering-mode, :follow-up-mode,
         :max-retries (default 3), :base-delay-ms (default 2000),
         :before-tool-call, :after-tool-call, :system-prompt-override,
         :transform-context, :prepare-next-turn, :should-stop-after-turn,
         :get-api-key, :models (default []), :compact-token-threshold,
         :keep-recent-tokens (default 20000, pi: keepRecentTokens)"
  [& {:keys [model provider system session on-event compact-threshold thinking base-url api-type steering-mode follow-up-mode max-retries base-delay-ms before-tool-call after-tool-call system-prompt-override transform-context prepare-next-turn should-stop-after-turn get-api-key models compact-token-threshold keep-recent-tokens]
      :or {provider :openai
           thinking :off
           steering-mode :all
           follow-up-mode :all
           max-retries 3
           base-delay-ms 2000
           models []
           keep-recent-tokens 20000
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
                    :after-tool-call (atom after-tool-call)
                    :system-prompt-override (atom system-prompt-override)
                    :transform-context (atom transform-context)
                    :prepare-next-turn (atom prepare-next-turn)
                    :should-stop-after-turn (atom should-stop-after-turn)
                    :get-api-key (atom get-api-key)
                    :models (atom models)
                    :overflow-recovered (atom false)
                    :compact-token-threshold compact-token-threshold
                    :keep-recent-tokens keep-recent-tokens
                    :compacting? (atom false)}))

;; ─── Helpers ───────────────────────────────────────────────────────────────

(defn- emit
  "Route an event to the UI callback (:on-event) and the extension system
   (event-bus/emit-event!). Extension listeners run inside emit-event!, which
   catches per-listener exceptions so a broken extension can't kill the loop."
  [agent event]
  (when-let [cb (:on-event agent)]
    (cb event))
  (event-bus/emit-event! event))

(defn- user-message
  "Build a user message map. text — string; images — optional vector of
   {:type :image :data base64 :mime-type str} blocks appended after the
   text block (pi: user message content with image attachments)."
  [text & [images]]
  {:role :user
   :content (into [{:type :text :text text}]
                  (or images []))})

(defn- assistant-message [text tool-calls usage]
  (let [content (if (seq text) [{:type :text :text text}] [])]
    (cond-> {:role :assistant :content content}
      (seq tool-calls) (assoc :tool-calls tool-calls)
      usage (assoc :usage usage))))

(defn- tool-result-message [tc-id _tc-name result]
  (cond-> {:role :tool
           :content [{:type :tool_result
                      :tool_use_id tc-id
                      :content (:content result)}]
           :is-error (:is-error result false)}
    (:images result) (assoc :images (:images result))
    (:truncation result) (assoc :truncation (:truncation result))
    (:details result) (assoc :details (:details result))))

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
  "Add a user message to context and session, emitting :message-start.
   images — optional vector of image content blocks (pi: image attachments)."
  [agent text & [images]]
  (let [user-msg (user-message text images)]
    (swap! (:messages agent) conj user-msg)
    (when (:session agent)
      (session/append-entry (:session agent)
                            {:role :user :content (:content user-msg)}))
    (emit agent {:type :message-start :message user-msg})))

(defn- add-assistant-message!
  "Add a final assistant message to context and session, emitting
   :message-end. Returns the message."
  [agent text tool-calls usage]
  (let [assistant-msg (assistant-message text tool-calls usage)]
    (swap! (:messages agent) conj assistant-msg)
    (when (:session agent)
      (session/append-entry (:session agent) assistant-msg))
    (emit agent {:type :message-end :message assistant-msg})
    assistant-msg))

(defn- add-custom-message!
  "Add a before-agent-start injected message to context and session, emitting
   :message-start. Role defaults to :info (display-only — rendered in the UI,
   excluded from the LLM context); other roles pass through to the provider.
   String :content is normalized to a text block so the message matches the
   canonical kmet message format (session persistence and resume display)."
  [agent m]
  (let [content (:content m)
        content (cond
                  (string? content) [{:type :text :text content}]
                  (nil? content) []
                  :else content)
        msg (cond-> (assoc m :content content)
              (not (contains? m :role)) (assoc :role :info))]
    (swap! (:messages agent) conj msg)
    (when (:session agent)
      (session/append-entry (:session agent) msg))
    (emit agent {:type :message-start :message msg})))

(defn- tool-execution-mode
  "Execution mode for a tool name: :sequential or :parallel.
   Unregistered/unknown tools default to :parallel (pi default)."
  [tool-name]
  (or (:execution-mode (tools/get-tool tool-name)) :parallel))

(defn- has-sequential-tool-call?
  "True if any tool call targets a :sequential tool — the whole batch then runs
   sequentially (pi: hasSequentialToolCall)."
  [tool-calls]
  (boolean (some #(= :sequential (tool-execution-mode (:name %))) tool-calls)))

(defn- run-tool-call!
  "Await a tool future with 100ms progress pings. Returns the result map."
  [agent tc-id f]
  (loop []
    (let [v (deref f 100 :pending)]
      (if (= :pending v)
        (do (emit agent {:type :tool-execution-update :tool-call-id tc-id})
            (recur))
        v))))

(defn- await-all-tool-results!
  "Poll all pending tool futures concurrently, emitting progress pings, until
   every future completes. Returns a map tool-call-id → result in approximate
   completion order (newest completions discovered per 100ms poll batch).
   Each cycle blocks on the next pending future (max 100ms) instead of a
   fixed sleep, so fast tools finish without artificial delay."
  [agent futures]
  (let [results (atom {})]
    (loop []
      (let [remaining (remove (fn [[id _]] (contains? @results id)) futures)]
        (if (empty? remaining)
          @results
          (do (doseq [[tc-id f] remaining]
                (when-not (= :pending (deref f 0 :pending))
                  (swap! results assoc tc-id @f)))
              (doseq [[tc-id _] remaining]
                (when-not (contains? @results tc-id)
                  (emit agent {:type :tool-execution-update :tool-call-id tc-id})))
              (when-let [[_ f] (first (filter (fn [[_ f]] (= :pending (deref f 0 :pending)))
                                              remaining))]
                ;; Block until this future completes; the 100ms timeout only
                ;; paces the progress pings for long-running tools.
                (deref f 100 :pending))
              (recur)))))))

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
         :reason (str "before-tool-call hook error: " (ex-message e))}))))

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
        {:content (str "after-tool-call hook error: " (ex-message e))
         :is-error true}))
    result))

(defn- tool-on-update
  "Streaming callback for a tool execution (pi: tool onUpdate) — emits
   :tool-execution-update with partial content so the UI can show live
   output (e.g. bash) while the tool is still running."
  [agent tc-id]
  (fn [partial]
    (when-let [content (:content partial)]
      (emit agent {:type :tool-execution-update
                   :tool-call-id tc-id
                   :content content
                   :is-partial true}))))

(defn- execute-tool-calls-parallel!
  "Execute tool calls concurrently (pi: executeToolCallsParallel).
   Preparation (start events + before hooks) is sequential; execution is
   concurrent; tool-execution-end events fire in completion order; results
   are appended to context in source order. Returns results in source order."
  [agent tool-calls assistant-msg]
  (let [prepared (mapv (fn [tc]
                         (let [tc-id (:id tc)
                               tc-name (:name tc)
                               tc-args (:arguments tc)]
                           (emit agent {:type :tool-execution-start
                                        :tool-call-id tc-id
                                        :tool-name tc-name
                                        :args tc-args})
                           (let [before (before-tool-hook-result agent tc-id tc-name tc-args assistant-msg)]
                             (if (:block before)
                               (assoc tc :kmet/blocked
                                      {:content (or (:reason before) "Tool execution was blocked")
                                       :is-error true})
                               tc))))
                       tool-calls)
        pending (filterv #(not (contains? % :kmet/blocked)) prepared)
        ;; pi: tools receive the session AbortSignal — Escape during a tool
        ;; call must kill the child process, not just abandon the future.
        ;; Each future records its own completion (the swap! runs before the
        ;; future is realized), so `completion-order` holds true completion
        ;; order — a hash-map can't express it, and iterating one gives
        ;; arbitrary tool-execution-end event order.
        completion-order (atom [])
        ;; The cancel signal comes from the run-level binding in run-agent-turn
        ;; (conveyed into these futures), so no per-future binding needed.
        futures (into {} (map (fn [tc]
                                [(:id tc)
                                 (future (let [tc-id (:id tc)
                                               result (tools/execute-tool (:name tc) (:arguments tc)
                                                                          (tool-on-update agent tc-id))]
                                           (swap! completion-order conj [tc-id result])
                                           result))])
                              pending))
        raw-results (await-all-tool-results! agent futures)
        finalized (into {}
                        (map (fn [tc]
                               (let [tc-id (:id tc)
                                     raw (or (:kmet/blocked tc) (get raw-results tc-id))
                                     result (after-tool-hook-result agent tc-id (:name tc) (:arguments tc) raw assistant-msg)]
                                 [tc-id result])))
                        prepared)]
    ;; tool-execution-end in completion order: blocked first (prep order), then completion order
    (doseq [tc prepared :when (contains? tc :kmet/blocked)]
      (let [result (get finalized (:id tc))]
        (emit agent {:type :tool-execution-end
                     :tool-call-id (:id tc)
                     :tool-name (:name tc)
                     :args (:arguments tc)
                     :result result
                     :is-error (:is-error result false)})))
    (doseq [[tc-id _] @completion-order]
      (let [tc (first (filter #(= (:id %) tc-id) prepared))
            result (get finalized tc-id)]
        (emit agent {:type :tool-execution-end
                     :tool-call-id tc-id
                     :tool-name (:name tc)
                     :args (:arguments tc)
                     :result result
                     :is-error (:is-error result false)})))
    ;; context + session in source order
    (doseq [tc prepared]
      (let [result (get finalized (:id tc))
            result-msg (tool-result-message (:id tc) (:name tc) result)]
        (swap! (:messages agent) conj result-msg)
        (when (:session agent)
          (session/append-entry (:session agent) result-msg))))
    (mapv #(get finalized (:id %)) tool-calls)))

(defn- execute-tool-calls-sequential!
  "Execute tool calls one at a time (pi: executeToolCallsSequential).
   Same hooks and events as the parallel path; results in source order."
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
                       (run-tool-call! agent tc-id
                                       (future (tools/execute-tool tc-name tc-args
                                                                   (tool-on-update agent tc-id)))))
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

(defn- execute-tool-calls!
  "Execute tool calls. If any target tool is :sequential the whole batch runs
   sequentially; otherwise tools run in parallel (pi: toolExecution mode).
   Runs before/after-tool-call hooks and returns results in source order."
  [agent tool-calls assistant-msg]
  (if (has-sequential-tool-call? tool-calls)
    (execute-tool-calls-sequential! agent tool-calls assistant-msg)
    (execute-tool-calls-parallel! agent tool-calls assistant-msg)))

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
                          (for [[_idx {:keys [id name arguments]}] @pending]
                            {:id id :name name
                             :arguments (try
                                          (json/parse-string arguments true)
                                          (catch Exception _ arguments))}))]
         (reset! pending {})
         result))]))

;; ─── LLM call wrapper ─────────────────────────────────────────────────────

(defn- call-llm
  "Send messages to LLM, return a promise that delivers {:text str :tool-calls [...] :stop-reason kw}.
   Calls on-text for text deltas during streaming.
   Applies the transform-context hook (pi: transformContext) to the conversation
   before the system prompt is prepended, and prefers the per-run
   system-prompt-override over the base system prompt."
  [agent api-key text-buf on-text on-thinking]
  (let [done-promise (promise)
        thinking-buf (atom "")
        usage-buf (atom nil)
        [tc-add tc-flush] (make-tc-accumulator)
        provider @(:provider agent)
        system (or @(:system-prompt-override agent) @(:system agent))
        messages (if-let [tf @(:transform-context agent)]
                   (tf @(:messages agent))
                   @(:messages agent))
        ;; Display-only :info messages (injected by before-agent-start hooks)
        ;; never reach the provider — they exist for the UI/session only.
        messages (into [] (remove #(= :info (:role %))) messages)
        messages (if system
                   (into [{:role :system :content [{:type :text :text system}]}]
                         (vec messages))
                   (vec messages))]
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
      :on-usage (fn [usage]
                  ;; Provider-native usage map — stored on the assistant
                  ;; message for session persistence (pi: message.usage).
                  (reset! usage-buf usage))
      :on-done (fn [reason]
                 (let [tool-calls (tc-flush)]
                   (deliver done-promise
                            {:text @text-buf
                             :tool-calls tool-calls
                             :usage @usage-buf
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

(declare resolve-api-key)

(defn- truncate-context!
  "Keep only the last n messages in the in-memory context, aligned with a
   count-based session compaction."
  [agent n]
  (swap! (:messages agent) #(vec (take-last n %))))

(defn- summarize!
  "LLM summarization of the pre-cut entries (pi: generateSummaryWithUsage).
   Returns the summary text, or nil when no API key is available, the call
   fails/times out/returns empty, or the run's cancel signal fired mid-call
   (the signal watcher delivers nil so cancellation doesn't wait for the
   stream to die)."
  [agent prep & [custom-instructions]]
  (let [provider @(:provider agent)
        api-key (resolve-api-key agent)]
    (when api-key
      (let [done (promise)
            text-buf (atom "")
            signal (:signal agent)
            msgs (compaction/summarization-messages
                  (:messages prep) (:previous-summary prep) custom-instructions)]
        (llm/send-message
         {:provider provider
          :api-type (or (:api-type agent) (cfg/get-provider-api-type provider))
          :model @(:model agent)
          :api-key api-key
          :base-url (or (:base-url agent) (cfg/get-provider-base-url provider))
          :messages msgs
          :signal signal
          :on-text (fn [t] (swap! text-buf str t))
          :on-done (fn [_] (deliver done @text-buf))
          :on-error (fn [_] (deliver done nil))})
        ;; Cancel watch: abort the deref the moment the signal fires. The
        ;; stream may not deliver an event on cancel (killed curl), so this
        ;; is what makes escape abort compaction promptly.
        (add-watch signal :kmet/summarize-cancel
                   (fn [_ _ _ v] (when v (deliver done nil))))
        (when @signal (deliver done nil))
        (let [result (try (deref done 120000 :timeout)
                          (finally (remove-watch signal :kmet/summarize-cancel)))]
          (when (and (string? result) (seq result)) result))))))

(defn- sync-context-after-compaction!
  "Rebuild the in-memory context from the compacted session branch: the
   summary entry (as a :user message so both providers accept it) followed by
   the kept context-visible entries (pi: the agent context is rebuilt from the
   session after compaction)."
  [agent]
  (when-let [sess (:session agent)]
    (let [branch (session/get-branch sess)
          summary-entry (first branch)
          context (into [{:role :user :content (:content summary-entry)}]
                        (keep (fn [e]
                                (case (:role e)
                                  (:user :assistant :tool) e
                                  nil)))
                        (rest branch))]
      (reset! (:messages agent) context))))

(defn- count-based-compact!
  "Fallback: count-based compaction (previous behavior). Keeps the most
   recent half of the session entries and aligns the in-memory context.
   Returns true when compaction happened."
  [agent]
  (if-let [sess (:session agent)]
    (let [n (count @(:entries sess))
          _ (session/compact! sess (quot n 2))
          new-n (count @(:entries sess))]
      (when (< new-n n)
        (truncate-context! agent new-n)
        (debug/log "compacted session: " n " → " new-n " entries"))
      (< new-n n))
    false))

(defn compact-context!
  "LLM-based compaction (pi: prepareCompaction → compact): summarize the
   pre-cut entries, replace the session with [summary, kept...], and rebuild
   the in-memory context to mirror it. Falls back to count-based truncation
   when summarization is unavailable or fails. Also the manual /compact path
   (pi: session.compact) — custom-instructions are appended to the
   summarization prompt.

   Emits :compaction-start/:compaction-end around the work (pi:
   compaction_start/compaction_end); the end event carries :aborted true
   when the run's cancel signal fired mid-compaction (escape), in which
   case the session is left untouched.

   Returns true when a compaction happened, false when there was nothing to
   compact (or compaction is already in progress), and :aborted when the
   user cancelled mid-compaction."
  [agent & [custom-instructions reason]]
  (if @(:compacting? agent)
    false
    (let [reason (if custom-instructions :manual (or reason :auto))]
      (emit agent {:type :compaction-start :reason reason})
      (reset! (:compacting? agent) true)
      (try
        (let [result
              (if-let [sess (:session agent)]
                (if @(:signal agent)
                  :aborted
                  (let [entries (session/get-branch sess)
                        prep (compaction/prepare entries (or (:keep-recent-tokens agent) 20000))]
                    (if (or (nil? prep) (empty? (:messages prep)))
                      false
                      (if-let [summary (summarize! agent prep custom-instructions)]
                        (if @(:signal agent)
                          ;; cancelled during summarization — session unchanged
                          :aborted
                          (do (session/compact-with-summary! sess summary (:first-kept-id prep))
                              (sync-context-after-compaction! agent)
                              (debug/log "compacted session with LLM summary")
                              true))
                        (if @(:signal agent)
                          :aborted
                          (do (debug/log "Warning: summarization failed; falling back to count-based compaction")
                              (count-based-compact! agent)))))))
                false)]
          (emit agent {:type :compaction-end
                       :reason reason
                       :aborted (= result :aborted)
                       :result (and (not= result :aborted) result)
                       :will-retry false})
          result)
        (finally
          (reset! (:compacting? agent) false))))))

(defn maybe-compact!
  "Proactively compact before a run (pi: _checkCompaction — threshold case).
   Triggers on entry count (:compact-threshold) or estimated tokens
   (:compact-token-threshold). Returns true when compaction happened."
  [agent]
  (if-let [sess (:session agent)]
    (let [entries (session/get-branch sess)
          n (count entries)
          threshold (:compact-threshold agent)
          token-threshold (:compact-token-threshold agent)
          tokens (reduce + 0 (map compaction/estimate-tokens entries))]
      (if (or (and threshold (>= n threshold))
              (and token-threshold (>= tokens token-threshold)))
        (let [result (compact-context! agent nil :threshold)]
          (and (not= :aborted result) result))
        false))
    false))

(defn- compact-for-overflow!
  "Force-compact after a context-overflow error so the retried call fits
   (pi: _checkCompaction — overflow case). Returns true when compaction
   happened."
  [agent]
  (when-let [sess (:session agent)]
    (when (pos? (count @(:entries sess)))
      (compact-context! agent nil :overflow))))

(defn- replace-context!
  "Replace the in-memory conversation, rebuild the session file to match, and
   emit :context-replaced so the UI can mirror the new context
   (pi: prepareNextTurnWithContext context replacement)."
  [agent messages]
  (let [msgs (vec messages)]
    (reset! (:messages agent) msgs)
    (when-let [sess (:session agent)]
      ;; Rebuild the session as a fresh linear branch mirroring the new context
      (spit (:file sess) "")
      (reset! (:entries sess) [])
      (reset! (:leaf-id sess) nil)
      (doseq [m msgs]
        (session/append-entry sess m)))
    (emit agent {:type :context-replaced :messages msgs})))

(defn- apply-next-turn-update!
  "Apply a prepare-next-turn update map to the agent state.
   Supported keys: :model, :system, :thinking, :system-prompt-override, :context
   (:context replaces the conversation and rebuilds the session to match)."
  [agent update]
  (when update
    (when-let [m (:model update)]
      (let [previous @(:model agent)]
        (when-not (= previous m)
          (reset! (:model agent) m)
          (emit agent {:type :model-select :model m :previous-model previous :source :set}))))
    (when-let [s (:system update)] (reset! (:system agent) s))
    (when-let [th (:thinking update)] (reset! (:thinking agent) th))
    (when-let [o (:system-prompt-override update)]
      (reset! (:system-prompt-override agent) o))
    (when-let [c (:context update)] (replace-context! agent c))))

(defn- after-turn!
  "Run post-turn hooks in pi order: prepareNextTurn then shouldStopAfterTurn.
   Returns true if the loop should stop."
  [agent turn-index assistant-msg tool-results]
  (when-let [f @(:prepare-next-turn agent)]
    (let [update (f {:turn-index turn-index
                     :message assistant-msg
                     :tool-results tool-results
                     :messages @(:messages agent)})]
      (apply-next-turn-update! agent update)))
  (boolean
   (when-let [f @(:should-stop-after-turn agent)]
     (f {:turn-index turn-index
         :message assistant-msg
         :tool-results tool-results
         :messages @(:messages agent)}))))

(defn- resolve-api-key
  "Resolve the API key for the agent's provider, preferring the dynamic
   get-api-key hook (pi: config.getApiKey, resolved per LLM call)."
  [agent]
  (if-let [kf @(:get-api-key agent)]
    (kf @(:provider agent))
    (cfg/get-api-key @(:provider agent))))

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
     :images   — optional vector of image content blocks
                 ({:type :image :data base64 :mime-type str}) attached to
                 the initial user message (pi: image attachments)
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

   Emits lifecycle events (see kmet.app.event-bus) to the UI callback and the
   extension system via emit.
   Returns: future that completes when the agent run is done."
  [agent {:keys [message images on-text on-thinking on-done on-error]}]
  (reset! (:signal agent) false)
  (let [provider @(:provider agent)
        api-key (resolve-api-key agent)]
    (if (nil? api-key)
      (do (when on-error
            (on-error (str "No API key for " (name provider)
                           ". Set the key in ~/.kmet/agent/auth.edn or the appropriate environment variable.")))
          (future))
      ;; Bind the run's cancel signal for the whole run: it conveys into the
      ;; tool futures AND into synchronous extension code (custom tool
      ;; executes, event handlers) that calls tools/execute-tool, so Escape
      ;; cancels bash everywhere, not just in the loop's own tool futures.
      (binding [bash-tool/*cancel-signal* (:signal agent)]
        (future
          (try
            (let [msg-count-before (count @(:messages agent))]
            ;; Per-run resets (pi: _systemPromptOverride, _overflowRecoveryAttempted)
              (reset! (:system-prompt-override agent) nil)
              (reset! (:overflow-recovered agent) false)
            ;; Initial user message
              (when message
                (add-user-message! agent message images))

            ;; before-agent-start hooks (pi: emitBeforeAgentStart) — extensions
            ;; can override the system prompt for this run and inject context
            ;; messages; runs once per submission, after the user message.
              (let [bas (extensions/apply-before-agent-start-hooks
                         message @(:system agent))]
                (when (:system-prompt bas)
                  (reset! (:system-prompt-override agent) (:system-prompt bas)))
                (doseq [m (:messages bas)]
                  (add-custom-message! agent m)))

            ;; Proactive auto-compact before the run (entry count + token estimate)
              (maybe-compact! agent)

            ;; Agent lifecycle: start
              (emit agent {:type :agent-start})
              (reset! (:status agent) :thinking)
              (emit agent {:type :status :status :thinking})

              (let [text-buf (atom "")
                    max-turns 20
                    agent-end (fn [& [error]]
                                (emit agent {:type :agent-end
                                             :messages (subvec @(:messages agent) msg-count-before)
                                             :error error})
                                ;; pi: agent_settled fires in a finally block after
                                ;; every run — success, error, timeout, or abort —
                                ;; the agent is fully idle (agent-session.js).
                                (emit agent {:type :agent-settled}))]
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
                                          (agent-end)
                                          {:aborted true}) ;; cancelled — exit quietly
                                      {:settled t})
                                    (let [steer-msgs (drain-queue! (:steering agent)
                                                                   (:steering-mode agent))]
                                      (doseq [m steer-msgs]
                                        (add-user-message! agent m))
                                      ;; Consumed messages left the queue — refresh the
                                      ;; pending display (pi: message_start → queue_update)
                                      (when (seq steer-msgs)
                                        (emit agent {:type :queue-update
                                                     :steering @(:steering agent)
                                                     :follow-up @(:follow-up agent)}))
                                      (emit agent {:type :turn-start :turn-index t})
                                      (let [promise (do (reset! text-buf "")
                                                        (call-llm agent (resolve-api-key agent) text-buf on-text on-thinking))]
                                        (reset! (:active-call agent) promise)
                                        (let [result (deref promise 120000 :timeout)]
                                          (reset! (:active-call agent) nil)
                                          (if (:cancelled result)
                                            (do (agent-end)
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
                                                  (cond
                                                  ;; Context overflow → compact once, then retry
                                                  ;; (pi: overflow is compaction territory, not auto-retry)
                                                    (and (not @(:overflow-recovered agent))
                                                         (context-overflow? err)
                                                         (:session agent)
                                                         (:compact-threshold agent))
                                                    (do (reset! (:overflow-recovered agent) true)
                                                        (compact-for-overflow! agent)
                                                        (recur t prev-tool-calls must-run))

                                                    (and (<= (inc @(:retry-count agent)) max-retries)
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
                                                            (agent-end)
                                                            {:aborted true})))

                                                  ;; Terminal error (non-retryable or retries exhausted)
                                                    :else
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
                                                  (when @(:overflow-recovered agent)
                                                  ;; A non-error message ends overflow recovery (pi resets on success)
                                                    (reset! (:overflow-recovered agent) false))
                                                  (let [text (:text result)
                                                        tool-calls (:tool-calls result)
                                                        usage (:usage result)]
                                                    (if (seq tool-calls)
                                                    ;; Execute tool calls
                                                      (let [assistant-msg (add-assistant-message! agent text tool-calls usage)]
                                                        (reset! (:status agent) :executing)
                                                        (emit agent {:type :status :status :executing
                                                                     :tool-calls tool-calls})
                                                        (let [tool-results (execute-tool-calls! agent tool-calls assistant-msg)]
                                                          (reset! (:status agent) :thinking)
                                                          (emit agent {:type :status :status :thinking})
                                                          (emit agent {:type :turn-end
                                                                       :message assistant-msg
                                                                       :tool-results tool-results})
                                                        ;; Proactive mid-run compaction (pi: after tool results, before next call)
                                                          (maybe-compact! agent)
                                                          (if (after-turn! agent t assistant-msg tool-results)
                                                            {:settled (inc t)}
                                                            (recur (inc t) tool-calls false))))
                                                    ;; Final response
                                                      (let [assistant-msg (add-assistant-message! agent text nil (:usage result))
                                                            tool-results []]
                                                        (emit agent {:type :turn-end
                                                                     :message assistant-msg
                                                                     :tool-results tool-results})
                                                        (if (after-turn! agent t assistant-msg tool-results)
                                                          {:settled (inc t)}
                                                          (recur (inc t) [] false))))))))))))))]
                      (if (:aborted inner)
                        nil ;; aborted (error or cancel) — exit without follow-ups
                        (let [turn' (:settled inner)
                            ;; Outer: poll follow-up queue
                              follow-ups (drain-queue! (:follow-up agent)
                                                       (:follow-up-mode agent))]
                          (if (seq follow-ups)
                            (do (doseq [m follow-ups]
                                  (add-user-message! agent m))
                                (emit agent {:type :queue-update
                                             :steering @(:steering agent)
                                             :follow-up @(:follow-up agent)})
                                (reset! (:status agent) :thinking)
                                (emit agent {:type :status :status :thinking})
                                (recur turn'))
                            (do (reset! (:status agent) :idle)
                                (emit agent {:type :status :status :idle})
                                (agent-end)
                                (when on-done (on-done @text-buf)))))))))))

            (catch Exception e
              (reset! (:status agent) :error)
              (emit agent {:type :error :message (ex-message e)})
              (when on-error (on-error (ex-message e))))))))))

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

(defn set-model!
  "Set the active model, emitting :model-select (pi: setModel)."
  [agent model]
  (let [previous @(:model agent)]
    (when-not (= previous model)
      (reset! (:model agent) model)
      (emit agent {:type :model-select
                   :model model
                   :previous-model previous
                   :source :set}))))

(defn set-models!
  "Set the scoped model list used by cycle-model! (pi: _scopedModels)."
  [agent models]
  (reset! (:models agent) (vec models)))

(defn cycle-model!
  "Cycle to the next/previous model in the scoped model list (pi: cycleModel).
   direction — 1 (forward) or -1 (backward). Returns the new model, or nil if
   no models are registered."
  [agent direction]
  (let [models @(:models agent)]
    (when (seq models)
      (let [current @(:model agent)
            idx (or (first (keep-indexed (fn [i m] (when (= m current) i)) models))
                    -1)
            next-model (nth models (mod (+ idx direction) (count models)))]
        (reset! (:model agent) next-model)
        (emit agent {:type :model-select
                     :model next-model
                     :previous-model current
                     :source :cycle})
        next-model))))

(defn set-system-prompt-override!
  "Set the per-run system prompt override (pi: _systemPromptOverride).
   call-llm prefers this over the base system prompt until the run resets it."
  [agent prompt]
  (reset! (:system-prompt-override agent) prompt))

(defn set-transform-context!
  "Register a transform-context hook (pi: transformContext).
   Hook: (fn [messages]) → messages — applied to the conversation before the
   system prompt is prepended on every LLM call."
  [agent hook]
  (reset! (:transform-context agent) hook))

(defn set-prepare-next-turn!
  "Register a prepare-next-turn hook (pi: prepareNextTurn).
   Hook: (fn [{:keys [turn-index message tool-results messages]}])
   Return a map with :model, :system, :thinking, :system-prompt-override,
   and/or :context to update state for the remaining turns, or nil."
  [agent hook]
  (reset! (:prepare-next-turn agent) hook))

(defn set-should-stop-after-turn!
  "Register a should-stop-after-turn hook (pi: shouldStopAfterTurn).
   Hook: (fn [{:keys [turn-index message tool-results messages]}])
   Return truthy to stop the loop gracefully after this turn."
  [agent hook]
  (reset! (:should-stop-after-turn agent) hook))

(defn set-get-api-key!
  "Register a dynamic API key resolver (pi: config.getApiKey).
   Hook: (fn [provider]) → key string — resolved before each LLM call,
   preferred over cfg/get-api-key."
  [agent hook]
  (reset! (:get-api-key agent) hook))

(defn set-provider! [agent provider]
  (reset! (:provider agent) provider))

(defn get-status [agent]
  @(:status agent))
