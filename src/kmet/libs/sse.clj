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
   yields :timeout while clean EOF still yields -1. Closing the reader
   (with-open) unblocks the daemon thread, which exits."
  [rdr idle-ms]
  (let [q (java.util.concurrent.LinkedBlockingQueue.)]
    (doto (Thread.
           (fn []
             (try
               (loop []
                 (let [c (.read rdr)]
                   (.put q c)
                   (when-not (neg? c) (recur))))
               (catch Exception _ nil))))
      (.setDaemon true)
      (.start))
    (fn []
      (let [v (.poll q idle-ms java.util.concurrent.TimeUnit/MILLISECONDS)]
        (cond
          (nil? v) :timeout
          :else (int v))))))

(defn- read-line-from
  "Assemble one line from an idle-char-reader fn. Returns the line string,
   nil at EOF, or :timeout when the reader stalled."
  [read-char]
  (let [sb (StringBuilder.)]
    (loop []
      (let [c (read-char)]
        (cond
          (= :timeout c) :timeout
          (neg? c) (when (pos? (.length sb)) (str sb)) ;; EOF mid-line
          (= c (int \newline)) (str sb)
          :else (do (.append sb (char c)) (recur)))))))

(defn- make-idle-line-reader
  "Line reader with a per-byte idle timeout (undici bodyTimeout semantics).
   Returns a no-arg fn producing lines; nil at EOF, :timeout on stall."
  [rdr idle-ms]
  (if (and idle-ms (pos? idle-ms))
    (let [read-char (make-idle-char-reader rdr idle-ms)]
      #(read-line-from read-char))
    #(.readLine rdr)))

(defn process-openai-stream
  "Read an OpenAI stream response body line by line, calling handler with
   each parsed event. handler receives one arg per event. signal (an atom)
   cancels the loop when set to true. Errors are reported via
   {:type :error} events. idle-timeout-ms — per-byte idle timeout (undici
   bodyTimeout semantics); nil or non-positive disables it."
  [response handler signal & [idle-timeout-ms]]
  (try
    (with-open [rdr (io/reader (:body response))]
      (let [read-line (make-idle-line-reader rdr idle-timeout-ms)]
        (loop []
          (let [line (read-line)]
            (cond
              (= :timeout line)
              (handler {:type :error
                        :message (str "Stream idle timeout after " (or idle-timeout-ms 0)
                                      " ms (no data received)")})
              (nil? line) nil
              (and signal @signal) nil
              :else
              (let [[_ data] (parse-sse-line line)]
                (when data
                  (handler (parse-openai-event data)))
                (recur)))))))
    (catch Exception e
      (handler {:type :error :message (str "Stream error: " (ex-message e))}))))

(defn process-anthropic-stream
  "Read an Anthropic stream response body, buffering multi-line event data.
   Calls handler with each parsed event. signal (an atom) cancels the loop.
   Errors are reported via {:type :error} events. idle-timeout-ms — per-byte
   idle timeout (undici bodyTimeout semantics); nil or non-positive disables
   it."
  [response handler signal & [idle-timeout-ms]]
  (try
    (with-open [rdr (io/reader (:body response))]
      (let [read-line (make-idle-line-reader rdr idle-timeout-ms)]
        (loop [event-name nil buf ""]
          (let [line (read-line)]
            (cond
              (= :timeout line)
              (handler {:type :error
                        :message (str "Stream idle timeout after " (or idle-timeout-ms 0)
                                      " ms (no data received)")})
              (nil? line)
              (handler {:type :done :stop-reason :connection-closed})
              (and signal @signal) nil
              :else
              (let [[ev data] (parse-sse-line line)]
                (cond
                  ev (recur data buf)
                  data (recur event-name (str buf data))
                  (and (empty? line) (seq buf))
                  (do (when-let [evt (parse-anthropic-event event-name buf)]
                        (handler evt))
                      (recur nil ""))
                  :else (recur event-name buf))))))))
    (catch Exception e
      (handler {:type :error :message (str "Stream error: " (ex-message e))}))))
