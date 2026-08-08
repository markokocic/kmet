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
  "Parse an OpenAI chat-completions SSE data payload into a kmet event map:
   :text, :thinking, :tool-call, :tool-call-args, :done (with :stop-reason),
   :delta, or :error."
  [data]
  (if (or (nil? data) (= data "[DONE]"))
    {:type :done :stop-reason :stop}
    (try
      (let [chunk (json/parse-string data true)
            choices (get chunk :choices [])
            delta (get-in (first choices) [:delta] {})
            finish (get-in (first choices) [:finish_reason])
            tc-delta (get delta :tool_calls)]
        (cond
          finish {:type :done :stop-reason (keyword finish)}
          (and tc-delta (get-in (first tc-delta) [:function :name]))
          (let [tc (first tc-delta)]
            {:type :tool-call :id (:id tc)
             :name (get-in tc [:function :name])
             :arguments (get-in tc [:function :arguments] "")
             :index (:index tc)})
          (and tc-delta (get-in (first tc-delta) [:function :arguments]))
          (let [tc (first tc-delta)]
            {:type :tool-call-args :id (:id tc)
             :arguments (get-in tc [:function :arguments] "")
             :index (:index tc)})
          (get delta :content)
          {:type :text :content (get delta :content)}
          (get delta :reasoning_content)
          {:type :thinking :content (get delta :reasoning_content)}
          (get chunk :usage)
          ;; Final chunk with include_usage: no choices, only usage totals
          {:type :usage :usage (get chunk :usage)}
          :else {:type :delta :chunk chunk}))
      (catch Exception e
        {:type :error :message (str "Parse error: " (ex-message e))}))))

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

(defn- make-idle-char-reader
  "Idle-timeout char reader over a BufferedReader (undici bodyTimeout
   semantics — the clock measures time between received bytes and resets on
   every byte). A daemon thread performs the blocking reads and hands chars
   to a queue; the caller polls with the idle deadline, so a stalled stream
   yields :timeout while clean EOF still yields -1 (EOF is only observable
   through a blocking read — ready()/available() stay 0 at EOF on
   java.net.http streams).

   Returns [read-char stop thread]:
     read-char — no-arg fn: next char (int), -1 at EOF, :timeout on stall,
                 :aborted when the cancel signal fired mid-poll
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
                 (let [c (.read rdr)]
                   (.put q c)
                   (when-not (neg? c) (recur))))
               (catch Exception _ nil))))]
    (.setDaemon t true)
    (.start t)
    [(fn []
       (let [deadline (+ (System/currentTimeMillis) idle-ms)]
         (loop []
           (let [v (.poll q 100 java.util.concurrent.TimeUnit/MILLISECONDS)]
             (cond
               (some? v) (int v)
               (and signal @signal) :aborted
               (>= (System/currentTimeMillis) deadline) :timeout
               :else (recur))))))
     (fn [] (.interrupt t))
     t]))

(defn- read-line-from
  "Assemble one line from an idle-char-reader fn. Returns the line string,
   nil at EOF, or the reader's :timeout/:aborted sentinel."
  [read-char]
  (let [sb (StringBuilder.)]
    (loop []
      (let [c (read-char)]
        (cond
          (or (= :timeout c) (= :aborted c)) c
          (neg? c) (when (pos? (.length sb)) (str sb)) ;; EOF mid-line
          (= c (int \newline)) (str sb)
          :else (do (.append sb (char c)) (recur)))))))

(defn- make-idle-line-reader
  "Line reader with a per-byte idle timeout (undici bodyTimeout semantics).
   Returns {:read-line f :stop f :thread t} — f produces lines, nil at EOF,
   :timeout on stall, :aborted on cancel; stop releases a blocked read;
   thread is the daemon thread (nil when the idle timeout is disabled)."
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

   Returns :aborted when cancelled, :timeout when idle limit reached, or
   :eof when the stream ended cleanly (nil read without cancel/timeout).
   The caller can distinguish clean EOF from premature termination."
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
                                        (let [event (parse-openai-event data)]
                                          (when (= :done (:type event))
                                            (reset! saw-done true))
                                          (handler event)))))
                                  (fn []
                                    (handler {:type :error
                                              :message (str "Stream idle timeout after " (or idle-timeout-ms 0)
                                                            " ms (no data received)")})))]
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
                                        ev (reset! state {:event-name data :buf buf})
                                        data (reset! state {:event-name event-name :buf (str buf data)})
                                        (and (empty? line) (seq buf))
                                        (do (when-let [evt (parse-anthropic-event event-name buf)]
                                              (when (= :done (:type evt))
                                                (reset! saw-message-stop true))
                                              (handler evt))
                                            (reset! state {:event-name nil :buf ""}))
                                        :else nil)))
                                  (fn []
                                    (handler {:type :error
                                              :message (str "Stream idle timeout after " (or idle-timeout-ms 0)
                                                            " ms (no data received)")})))]
      ;; pi: sawMessageStart && !sawMessageEnd → error. kmet is stricter:
      ;; the stream is always expected to end with message_stop regardless
      ;; of whether message_start was seen (the envelope event is always
      ;; present for a valid Anthropic SSE stream).
      (when (and (= :eof end-reason) (not @saw-message-stop) (not @signal))
        (handler {:type :error
                  :message "Anthropic stream ended before message_stop"})))
    (catch Exception e
      (handler {:type :error :message (str "Stream error: " (ex-message e))}))))
