(ns kmet.libs.sse
  "Server-Sent Events parsing and stream processing for LLM responses.
   Generic — no app concepts. Handles the OpenAI chat-completions stream
   format and the Anthropic messages stream format. In the JS world this
   is the eventsource-parser npm package; kept in-house because no
   Babashka-compatible Clojure equivalent exists."
  (:require [clojure.string :as str]
            [clojure.java.io :as io]
            [cheshire.core :as json]))

(defn parse-sse-line
  "Parse one SSE line. Returns [event-name data] — exactly one is non-nil
   (:event or :data), both nil for blank/comment lines."
  [line]
  (cond
    (str/starts-with? line "event:") [(str/trim (subs line 6)) nil]
    (str/starts-with? line "data:")  [nil (str/trim (subs line 5))]
    :else [nil nil]))

(defn parse-openai-event
  "Parse an OpenAI chat-completions SSE data payload into a vector of kmet
   events: :text, :thinking, :tool-call, :tool-call-args, :done (with
   :stop-reason), :delta, :usage, or :error.
   A single chunk can carry a content delta AND a reasoning delta — both are
   emitted (pi emits text_delta and thinking_delta for the same chunk; never
   an else-branch that assumes one field per chunk). Reasoning is read from
   the first non-empty of reasoning_content / reasoning / reasoning_text
   (pi: iterateOpenAiEvents reasoningFields — llama.cpp uses
   reasoning_content, other OpenAI-compatible endpoints use reasoning)."
  [data]
  (if (or (nil? data) (= data "[DONE]"))
    [{:type :done :stop-reason :stop}]
    (try
      (let [chunk (json/parse-string data true)
            choices (get chunk :choices [])
            first-choice (first choices)
            delta (get-in first-choice [:delta] {})
            finish (get-in first-choice [:finish_reason])
            tc-delta (get delta :tool_calls)
            events (volatile! [])]
        ;; Tool call deltas — a chunk can carry several (pi iterates all)
        (doseq [tc (seq tc-delta)]
          (let [fname (get-in tc [:function :name])
                fargs (get-in tc [:function :arguments])]
            (cond
              (some? fname)
              (vswap! events conj {:type :tool-call :id (:id tc)
                                   :name fname
                                   :arguments (or fargs "")
                                   :index (:index tc)})
              (some? fargs)
              (vswap! events conj {:type :tool-call-args :id (:id tc)
                                   :arguments fargs
                                   :index (:index tc)}))))
        ;; Content delta
        (let [content (get delta :content)]
          (when (and (string? content) (pos? (count content)))
            (vswap! events conj {:type :text :content content})))
        ;; Reasoning delta — first non-empty reasoning field (pi)
        (let [reasoning (some (fn [f]
                                (let [v (get delta f)]
                                  (when (and (string? v) (pos? (count v))) v)))
                              [:reasoning_content :reasoning :reasoning_text])]
          (when reasoning
            (vswap! events conj {:type :thinking :content reasoning})))
        ;; Final chunk with include_usage: no choices, only usage totals
        (when-let [usage (get chunk :usage)]
          (vswap! events conj {:type :usage :usage usage}))
        ;; finish_reason — terminal event, emitted LAST so any tool-call /
        ;; content deltas in the same chunk are processed before the run's
        ;; on-done flushes the tool-call accumulator (pi pushes one done
        ;; event after the whole stream is consumed)
        (when finish
          (vswap! events conj {:type :done :stop-reason (keyword finish)}))
        (if (seq @events)
          @events
          [{:type :delta :chunk chunk}]))
      (catch Exception e
        [{:type :error :message (str "Parse error: " (ex-message e))}]))))

(defn parse-anthropic-event
  "Parse an Anthropic messages SSE (event-name, data) pair into a kmet event
   map: :text, :tool-call, :tool-call-args, :message-delta, :done, :ping,
   :content-block-start, or :unknown."
  [event data]
  (case event
    "message_start"
    (when data
      (let [d (json/parse-string data true)]
        {:type :usage :usage (get-in d [:message :usage])}))
    "content_block_start"
    (when data
      (let [block (json/parse-string data true)
            cb (:content_block block)]
        (case (:type cb)
          "tool_use"
          {:type :tool-call :id (:id cb) :name (:name cb)
           :arguments (:input cb {})}
          {:type :content-block-start})))
    "content_block_delta"
    (when data
      (let [delta (json/parse-string data true)]
        (case (get-in delta [:delta :type])
          "text_delta"
          {:type :text :content (get-in delta [:delta :text])}
          "input_json_delta"
          {:type :tool-call-args
           :arguments (get-in delta [:delta :partial_json])}
          {:type :delta :delta delta})))
    "message_delta"
    (when data
      (let [d (json/parse-string data true)]
        {:type :message-delta
         :stop-reason (get-in d [:delta :stop_reason])}))
    "error"
    {:type :error :message (or data "Anthropic stream error")}
    "message_stop"
    {:type :done :stop-reason :end-turn}
    "ping"
    {:type :ping}
    {:type :unknown :event event :data data}))

;; ─── OpenAI Responses stream (pi: api/openai-responses-shared.ts           ──
;;    processResponsesStream — ported to kmet's event vocabulary; the
;;    output-slot state (per output_index tool-call partial JSON) lives in
;;    the state atom owned by process-responses-stream) ─────────────────────

(declare stream-loop)

(defn- responses-tool-call-id
  "The stored tool call id for a responses function_call item: pi's
   `call_id|item_id` format so replay can split it back into the
   function_call_output call_id."
  [item]
  (str (or (:call_id item) "") "|" (or (:id item) "")))

(defn- responses-terminal-events
  "Events for the terminal response.completed / response.incomplete events
   (pi finalizeResponse): the provider-native usage (input_tokens includes
   cached/cache-write tokens — session/entry-usage subtracts them) and the
   stop reason. Completed responses with tool-call slots map :stop to
   :tool-use (pi: output.content has a toolCall). Non-max-output incomplete
   responses surface as an error (pi: stopReason 'error' + errorMessage).
   response.done is codex's terminal event (pi mapCodexEvents maps it to
   response.completed, status normalized — known statuses pass through);
   unknown/missing statuses error rather than silently ending the stream."
  [d state]
  (let [response (:response d)
        status (:status response)
        incomplete-reason (get-in response [:incomplete_details :reason])
        usage (:usage response)
        tool-calls? (seq (:slots @state))]
    (swap! state assoc :saw-terminal? true)
    (cond-> []
      usage (conj {:type :usage :usage usage})
      (and (= "completed" status) tool-calls?) (conj {:type :done :stop-reason :tool-use})
      (and (= "completed" status) (not tool-calls?)) (conj {:type :done :stop-reason :stop})
      (and (= "incomplete" status) (= "max_output_tokens" incomplete-reason))
      (conj {:type :done :stop-reason :length})
      (and (= "incomplete" status) (not= "max_output_tokens" incomplete-reason))
      (conj {:type :error
             :message (str "Response incomplete: " (or incomplete-reason "unknown reason"))})
      ;; unknown status (codex normalizeCodexStatus can strip values)
      (and (not (#{"completed" "incomplete"} status)) (seq status))
      (conj {:type :error :message (str "Response ended with status " status)})
      (and (not (#{"completed" "incomplete"} status)) (nil? status))
      (conj {:type :error :message "Response ended without a status"}))))

(defn- responses-failed-message
  "pi response.failed handling: the error code+message, else the
   incomplete_details reason."
  [d]
  (let [response (:response d)
        error (:error response)
        details (:incomplete_details response)]
    (cond
      error (str (or (:code error) "unknown") ": " (or (:message error) "no message"))
      details (str "incomplete: " (:reason details))
      :else "Unknown error (no error details in response)")))

(defn parse-responses-event
  "Parse one OpenAI Responses SSE (event-name, data) pair into a vector of
   kmet events: :text, :thinking, :tool-call (start, with the id/name),
   :tool-call-args (argument deltas), :usage, :done (with :stop-reason), or
   :error. STATE is the processor's atom — it owns the per-output-index
   tool-call slots (partial JSON accumulation, pi processResponsesStream's
   outputSlots) and the terminal-event flag.

   Text/thinking content arrives as deltas (response.output_text.delta,
   response.reasoning_*.delta); tool arguments stream via
   response.function_call_arguments.delta with a final .done that may
   extend the accumulated partial. function_call items are created on
   response.output_item.added; the id is `call_id|item_id` (pi), so the
   tool executor and session replay treat it opaquely."
  [event data state]
  (if (nil? data)
    []
    (try
      (let [d (json/parse-string data true)
            slots (:slots @state)
            add-slot! (fn [idx slot] (swap! state assoc-in [:slots idx] slot))]
        (case event
          "response.output_item.added"
          (let [item (:item d) idx (:output_index d)]
            (case (:type item)
              "function_call"
              (let [slot {:kind :tool-call
                          :id (responses-tool-call-id item)
                          :name (:name item)
                          :partial-json (or (:arguments item) "")}]
                (add-slot! idx slot)
                [{:type :tool-call :id (:id slot) :name (:name slot)
                  :arguments (or (:arguments item) "") :index idx}])
              []))

          "response.reasoning_summary_text.delta"
          [{:type :thinking :content (:delta d)}]
          "response.reasoning_summary_part.done"
          [{:type :thinking :content "\n\n"}]
          "response.reasoning_text.delta"
          [{:type :thinking :content (:delta d)}]
          "response.output_text.delta"
          [{:type :text :content (:delta d)}]
          "response.refusal.delta"
          [{:type :text :content (:delta d)}]

          "response.function_call_arguments.delta"
          (let [slot (get slots (:output_index d))]
            (if (and slot (= :tool-call (:kind slot)))
              (do (swap! state assoc-in [:slots (:output_index d) :partial-json]
                         (str (:partial-json slot) (:delta d)))
                  [{:type :tool-call-args :arguments (:delta d)
                    :index (:output_index d)}])
              []))

          "response.function_call_arguments.done"
          (let [slot (get slots (:output_index d))
                final (or (:arguments d) "")]
            (if (and slot (= :tool-call (:kind slot)))
              (let [previous (:partial-json slot)]
                (swap! state assoc-in [:slots (:output_index d) :partial-json] final)
                ;; The final arguments normally extend the accumulated partial
                ;; (pi: emit only the extension as a delta); when they don't
                ;; (complete arguments arrived in the item), nothing to add.
                (if (and (str/starts-with? final previous)
                         (pos? (- (count final) (count previous))))
                  [{:type :tool-call-args :arguments (subs final (count previous))
                    :index (:output_index d)}]
                  []))
              []))

          "response.output_item.done"
          ;; Text/thinking/tool-call content already streamed as deltas; the
          ;; final item only carries the same values (no signature replay in
          ;; kmet — sessions don't persist signatures).
          []

          "response.completed" (responses-terminal-events d state)
          "response.incomplete" (responses-terminal-events d state)
          "response.done" (responses-terminal-events d state)
          "response.failed"
          (do (swap! state assoc :saw-terminal? true)
              [{:type :error :message (responses-failed-message d)}])
          "error"
          [{:type :error :message (str "Error Code " (:code d) ": " (:message d))}]
          []))
      (catch Exception e
        [{:type :error :message (str "Parse error: " (ex-message e))}]))))

(defn process-responses-stream
  "Read an OpenAI Responses stream response body, buffering multi-line event
   data (like process-anthropic-stream). Calls handler with each parsed
   event; the processor's output-slot state lives in an internal atom.
   signal (an atom) cancels the loop; errors via {:type :error} events;
   idle-timeout-ms is the per-byte idle timeout; abort-fn kills the transport
   on stall.

   Detects premature stream end: when the stream ends before a terminal
   event (response.completed / response.incomplete / response.failed) is
   received, reports {:type :error} (pi: 'OpenAI Responses stream ended
   before a terminal response event')."
  [response handler signal & [idle-timeout-ms abort-fn]]
  (try
    (let [rdr (io/reader (:body response))
          state (atom {:event-name nil :buf "" :slots {} :saw-terminal? false})
          saw-done (atom false)
          end-reason (stream-loop rdr idle-timeout-ms signal abort-fn
                                  (fn [line]
                                    (let [[ev data] (parse-sse-line line)
                                          {:keys [event-name buf]} @state]
                                      (cond
                                        ev (swap! state assoc :event-name ev)
                                        data (swap! state assoc :buf (str buf data))
                                        (and (empty? line) (seq buf))
                                        (do (doseq [event (parse-responses-event event-name buf state)]
                                              (when (= :done (:type event))
                                                (reset! saw-done true))
                                              (handler event))
                                            (swap! state assoc :event-name nil :buf ""))
                                        :else nil)))
                                  (fn
                                    ([] (handler {:type :error
                                                  :message (str "Stream idle timeout after " (or idle-timeout-ms 0)
                                                                " ms (no data received)")}))
                                    ([e] (handler {:type :error
                                                   :message (str "Stream error: " (ex-message e))}))))]
      (when (and (= :eof end-reason) (not @saw-done) (not (:saw-terminal? @state)) (not @signal))
        (handler {:type :error
                  :message "OpenAI Responses stream ended before a terminal response event"})))
    (catch Exception e
      (handler {:type :error :message (str "Stream error: " (ex-message e))}))))

(defn- google-stop-reason
  "pi mapStopReasonString: STOP → :stop, MAX_TOKENS → :length, rest :error."
  [reason]
  (case reason
    "STOP" :stop
    "MAX_TOKENS" :length
    :error))

(defn- google-usage
  "pi: usage from GenerateContentResponse.usageMetadata (camelCase wire
   fields; cache read is the cached-content token count)."
  [u]
  {:input (- (or (:promptTokenCount u) 0) (or (:cachedContentTokenCount u) 0))
   :output (+ (or (:candidatesTokenCount u) 0) (or (:thoughtsTokenCount u) 0))
   :cache-read (or (:cachedContentTokenCount u) 0)
   :cache-write 0
   :reasoning (or (:thoughtsTokenCount u) 0)
   :total-tokens (or (:totalTokenCount u) 0)})

(defn parse-google-event
  "Parse a Google Generative AI streamGenerateContent SSE data payload (the
   REST wire format — camelCase fields) into a vector of kmet events:
   :text, :thinking (thought parts), :tool-call (functionCall parts with the
   full args object), :usage (per-chunk usageMetadata), :done (finishReason),
   or :error. A single chunk can yield several events (usage + parts +
   finish — pi reads all parts and usageMetadata per chunk).

   Google streams have no terminal sentinel: the last chunk carries
   finishReason, which process-google-stream uses for premature-end
   detection."
  [data]
  (if (or (nil? data) (str/blank? data))
    [{:type :delta :chunk {}}]
    (try
      (let [chunk (json/parse-string data true)
            candidate (first (:candidates chunk))
            parts (get-in candidate [:content :parts])
            finish (:finishReason candidate)
            usage (:usageMetadata chunk)
            part-events (keep (fn [[i p]]
                                (cond
                                  (:functionCall p)
                                  (let [fc (:functionCall p)]
                                    {:type :tool-call
                                     :id (or (:id fc) (str (:name fc) "_" (System/currentTimeMillis) "_" i))
                                     :name (:name fc)
                                     :arguments (:args fc {})
                                     :index i})
                                  (:thought p) {:type :thinking :content (:text p)}
                                  (contains? p :text) {:type :text :content (:text p)}))
                              (map-indexed vector parts))
            events (concat (when usage [{:type :usage :usage (google-usage usage)}])
                           part-events
                           (when finish [{:type :done :stop-reason (google-stop-reason finish)}]))]
        (if (seq events) (vec events) [{:type :delta :chunk chunk}]))
      (catch Exception e
        [{:type :error :message (str "Parse error: " (ex-message e))}]))))

(defn- make-idle-char-reader
  "Idle-timeout char reader over a BufferedReader (undici bodyTimeout
   semantics — the clock measures time between received bytes and resets on
   every byte). A daemon thread performs the blocking reads and hands chars
   to a queue; the caller polls with the idle deadline, so a stalled stream
   yields :timeout while clean EOF still yields -1 (EOF is only observable
   through a blocking read — ready()/available() stay 0 at EOF on
   java.net.http streams).

   Returns [read-char stop thread]:
     read-char — no-arg fn: next char (int), -1 at EOF, the read
                 exception when the underlying stream failed, :timeout on
                 stall, :aborted when the cancel signal fired mid-poll
     stop      — interrupts the daemon so a blocked read releases (required
                  before closing the reader, which deadlocks while a read is
                  in flight on java.net.http streams)
     thread    — the daemon thread, for join-before-close"
  [rdr idle-ms signal]
  (let [q (java.util.concurrent.LinkedBlockingQueue.)
        t (Thread.
           (fn []
             (try
               (loop []
                 (let [c (try (.read rdr) (catch Exception e e))]
                   (.put q c)
                   ;; A read failure (e.g. HTTP/2 RST_STREAM) is put on the
                   ;; queue like data so the caller reports the real error
                   ;; instead of stalling to the idle deadline.
                   (when-not (or (neg? c) (instance? Exception c))
                     (recur))))
               (catch Exception _ nil))))]
    (.setDaemon t true)
    (.start t)
    [(fn []
       (let [deadline (+ (System/currentTimeMillis) idle-ms)]
         (loop []
           (let [v (.poll q 100 java.util.concurrent.TimeUnit/MILLISECONDS)]
             (cond
               (instance? Exception v) v
               (some? v) (int v)
               (and signal @signal) :aborted
               (>= (System/currentTimeMillis) deadline) :timeout
               :else (recur))))))
     (fn [] (.interrupt t))
     t]))

(defn- read-line-from
  "Assemble one line from an idle-char-reader fn. Returns the line string,
   nil at EOF, the read exception when the stream failed, or the reader's
   :timeout/:aborted sentinel."
  [read-char]
  (let [sb (StringBuilder.)]
    (loop []
      (let [c (read-char)]
        (cond
          (instance? Exception c) c
          (or (= :timeout c) (= :aborted c)) c
          (neg? c) (when (pos? (.length sb)) (str sb)) ;; EOF mid-line
          (= c (int \newline)) (str sb)
          :else (do (.append sb (char c)) (recur)))))))

(defn- make-idle-line-reader
  "Line reader with a per-byte idle timeout (undici bodyTimeout semantics).
   Returns {:read-line f :stop f :thread t} — f produces lines, nil at EOF,
   the read exception when the stream failed, :timeout on stall, :aborted on
   cancel; stop releases a blocked read; thread is the daemon thread (nil
   when the idle timeout is disabled)."
  [rdr idle-ms signal]
  (if (and idle-ms (pos? idle-ms))
    (let [[read-char stop thread] (make-idle-char-reader rdr idle-ms signal)]
      {:read-line #(read-line-from read-char)
       :stop stop
       :thread thread})
    {:read-line #(.readLine rdr)
     :stop (fn [])
     :thread nil}))

(defn- stream-loop
  "Shared driver for both stream processors. Reads lines via the idle-aware
   line reader and calls handle-line per line. On a stall the abort-fn (if
   any) kills the transport (curl) so the blocked read releases, then the
   error is reported via error-fn. Cleanup on every exit path: the daemon is
   interrupted and joined before the reader is closed — closing while a read
   is in flight deadlocks java.net.http streams, and process pipes (curl)
   need the abort-fn since interrupts don't unblock them.

   Returns :aborted when cancelled, :timeout when idle limit reached,
   :error when the underlying read failed (the exception is reported via
   error-fn), or :eof when the stream ended cleanly (nil read without
   cancel/timeout). The caller can distinguish clean EOF from premature
   termination."
  [rdr idle-timeout-ms signal abort-fn handle-line error-fn]
  (let [idle (make-idle-line-reader rdr idle-timeout-ms signal)]
    (try
      (loop []
        (let [line ((:read-line idle))]
          (cond
            (= :aborted line)
            (do ((:stop idle)) :aborted)
            (= :timeout line)
            (do ((:stop idle))
                (when abort-fn (abort-fn))
                (error-fn)
                :timeout)
            (nil? line) :eof
            (and signal @signal)
            (do ((:stop idle)) :aborted)
            ;; A transport read failure (RST_STREAM, connection reset, ...)
            ;; surfaces immediately — cancel wins if it raced with the error.
            ;; abort-fn kills a possibly-still-alive curl transport so
            ;; finish-curl!'s process deref doesn't block on it.
            (instance? Exception line)
            (do ((:stop idle))
                (when abort-fn (abort-fn))
                (error-fn line)
                :error)
            :else
            (do (handle-line line)
                (recur)))))
      (finally
        ((:stop idle))
        (when-let [t (:thread idle)]
          (.join t 2000))
        (.close rdr)))))

(defn process-openai-stream
  "Read an OpenAI stream response body line by line, calling handler with
   each parsed event. handler receives one arg per event. signal (an atom)
   cancels the loop when set to true. Errors are reported via
   {:type :error} events. idle-timeout-ms — per-byte idle timeout (undici
   bodyTimeout semantics); nil or non-positive disables it. abort-fn (optional)
   — called on idle timeout to kill the transport so a blocked read releases.

   Detects premature stream end: when the stream ends before [DONE] is
   received, reports {:type :error} (pi: iterateOpenAiEvents throws
   'Stream ended without finish_reason')."
  [response handler signal & [idle-timeout-ms abort-fn]]
  (try
    (let [rdr (io/reader (:body response))
          saw-done (atom false)
          end-reason (stream-loop rdr idle-timeout-ms signal abort-fn
                                  (fn [line]
                                    (let [[_ data] (parse-sse-line line)]
                                      (when data
                                        (doseq [event (parse-openai-event data)]
                                          (when (= :done (:type event))
                                            (reset! saw-done true))
                                          (handler event)))))
                                  (fn
                                    ([] (handler {:type :error
                                                  :message (str "Stream idle timeout after " (or idle-timeout-ms 0)
                                                                " ms (no data received)")}))
                                    ;; Transport read failure (RST_STREAM, connection
                                    ;; reset, ...) — the accurate error surfaces instead
                                    ;; of a misleading idle timeout.
                                    ([e] (handler {:type :error
                                                   :message (str "Stream error: " (ex-message e))}))))]
      (when (and (= :eof end-reason) (not @saw-done) (not @signal))
        (handler {:type :error
                  :message "Stream ended before a terminal response event"})))
    (catch Exception e
      (handler {:type :error :message (str "Stream error: " (ex-message e))}))))

(defn process-anthropic-stream
  "Read an Anthropic stream response body, buffering multi-line event data.
   Calls handler with each parsed event. signal (an atom) cancels the loop.
   Errors are reported via {:type :error} events. idle-timeout-ms — per-byte
   idle timeout (undici bodyTimeout semantics); nil or non-positive disables
   it. abort-fn (optional) — called on idle timeout to kill the transport so
   a blocked read releases.

   Detects premature stream end: when the stream ends before message_stop is
   received, reports {:type :error} (pi: iterateAnthropicEvents throws
   'Anthropic stream ended before message_stop')."
  [response handler signal & [idle-timeout-ms abort-fn]]
  (try
    (let [rdr (io/reader (:body response))
          state (atom {:event-name nil :buf ""})
          saw-message-stop (atom false)
          end-reason (stream-loop rdr idle-timeout-ms signal abort-fn
                                  (fn [line]
                                    (let [[ev data] (parse-sse-line line)
                                          {:keys [event-name buf]} @state]
                                      (cond
                                        ev (reset! state {:event-name ev :buf buf})
                                        data (reset! state {:event-name event-name :buf (str buf data)})
                                        (and (empty? line) (seq buf))
                                        (do (when-let [evt (parse-anthropic-event event-name buf)]
                                              (when (= :done (:type evt))
                                                (reset! saw-message-stop true))
                                              (handler evt))
                                            (reset! state {:event-name nil :buf ""}))
                                        :else nil)))
                                  (fn
                                    ([] (handler {:type :error
                                                  :message (str "Stream idle timeout after " (or idle-timeout-ms 0)
                                                                " ms (no data received)")}))
                                    ;; Transport read failure (RST_STREAM, connection
                                    ;; reset, ...) — the accurate error surfaces instead
                                    ;; of a misleading idle timeout.
                                    ([e] (handler {:type :error
                                                   :message (str "Stream error: " (ex-message e))}))))]
      ;; pi: sawMessageStart && !sawMessageEnd → error. kmet is stricter:
      ;; the stream is always expected to end with message_stop regardless
      ;; of whether message_start was seen (the envelope event is always
      ;; present for a valid Anthropic SSE stream).
      (when (and (= :eof end-reason) (not @saw-message-stop) (not @signal))
        (handler {:type :error
                  :message "Anthropic stream ended before message_stop"})))
    (catch Exception e
      (handler {:type :error :message (str "Stream error: " (ex-message e))}))))

(defn process-google-stream
  "Read a Google streamGenerateContent response body line by line, calling
   handler with each parsed event (mirrors process-openai-stream). signal (an
   atom) cancels the loop; errors via {:type :error}; idle-timeout-ms is the
   per-byte idle timeout; abort-fn kills the transport on stall.

   Detects premature stream end: when the stream ends before a finishReason
   chunk is received, reports {:type :error}."
  [response handler signal & [idle-timeout-ms abort-fn]]
  (try
    (let [rdr (io/reader (:body response))
          saw-done (atom false)
          end-reason (stream-loop rdr idle-timeout-ms signal abort-fn
                                  (fn [line]
                                    (let [[_ data] (parse-sse-line line)]
                                      (when data
                                        (doseq [event (parse-google-event data)]
                                          (when (= :done (:type event))
                                            (reset! saw-done true))
                                          (handler event)))))
                                  (fn
                                    ([] (handler {:type :error
                                                  :message (str "Stream idle timeout after " (or idle-timeout-ms 0)
                                                                " ms (no data received)")}))
                                    ;; Transport read failure (RST_STREAM, connection
                                    ;; reset, ...) — the accurate error surfaces instead
                                    ;; of a misleading idle timeout.
                                    ([e] (handler {:type :error
                                                   :message (str "Stream error: " (ex-message e))}))))]
      (when (and (= :eof end-reason) (not @saw-done) (not @signal))
        (handler {:type :error
                  :message "Stream ended before a terminal response event"})))
    (catch Exception e
      (handler {:type :error :message (str "Stream error: " (ex-message e))}))))

