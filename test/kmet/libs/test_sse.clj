(ns kmet.libs.test-sse
  (:require [clojure.test :as t]
            [clojure.string :as str]
            [kmet.libs.sse :as sse]))

;; ─── Event parsing ─────────────────────────────────────────────────────────

(t/deftest test-parse-sse-line
  (t/is (= [nil "hi"] (sse/parse-sse-line "data: hi")))
  (t/is (= ["event" nil] (sse/parse-sse-line "event: event")))
  (t/is (= [nil nil] (sse/parse-sse-line "")))
  (t/is (= [nil "hi"] (sse/parse-sse-line "data:   hi")))
  (t/is (= [nil nil] (sse/parse-sse-line ":comment"))))

(t/deftest test-parse-openai-event-done
  (t/is (= [{:type :done :stop-reason :stop}]
           (sse/parse-openai-event "[DONE]")))
  (t/is (= [{:type :done :stop-reason :stop}]
           (sse/parse-openai-event nil))))

(t/deftest test-parse-openai-event-text
  (t/is (= [{:type :text :content "hi"}]
           (sse/parse-openai-event "{\"choices\":[{\"delta\":{\"content\":\"hi\"}}]}"))))

(t/deftest test-parse-openai-event-thinking
  ;; reasoning_content streams as a :thinking event (deepseek thinking mode)
  (t/is (= [{:type :thinking :content "let me think"}]
           (sse/parse-openai-event
            "{\"choices\":[{\"delta\":{\"reasoning_content\":\"let me think\"}}]}")))
  ;; other OpenAI-compatible endpoints use `reasoning` (pi: reasoningFields)
  (t/is (= [{:type :thinking :content "via reasoning"}]
           (sse/parse-openai-event
            "{\"choices\":[{\"delta\":{\"reasoning\":\"via reasoning\"}}]}")))
  (t/is (= [{:type :thinking :content "via reasoning_text"}]
           (sse/parse-openai-event
            "{\"choices\":[{\"delta\":{\"reasoning_text\":\"via reasoning_text\"}}]}")))
  ;; first non-empty reasoning field wins when several are present (pi:
  ;; reasoning_content has priority over reasoning)
  (t/is (= [{:type :thinking :content "b"}]
           (sse/parse-openai-event
            "{\"choices\":[{\"delta\":{\"reasoning\":\"a\",\"reasoning_content\":\"b\"}}]}"))))

(t/deftest test-parse-openai-event-finish
  (t/is (= [{:type :done :stop-reason :tool_calls}]
           (sse/parse-openai-event
            "{\"choices\":[{\"delta\":{},\"finish_reason\":\"tool_calls\"}]}"))))

(t/deftest test-parse-openai-event-both-text-and-thinking
  ;; A chunk can carry a content delta AND a reasoning delta — both events
  ;; fire (pi never uses an else-branch assuming one field per chunk)
  (t/is (= [{:type :text :content "answer"}
            {:type :thinking :content "reason"}]
           (sse/parse-openai-event
            "{\"choices\":[{\"delta\":{\"content\":\"answer\",\"reasoning_content\":\"reason\"}}]}")))
  ;; empty-string content must not produce a spurious text event
  (t/is (= [{:type :delta :chunk {:choices [{:delta {:content ""}}]}}]
           (sse/parse-openai-event
            "{\"choices\":[{\"delta\":{\"content\":\"\"}}]}"))))

(t/deftest test-parse-openai-event-multi-tool-calls
  ;; pi iterates ALL tool call deltas in a chunk, not just the first
  (t/is (= [{:type :tool-call :id "a" :name "read" :arguments "" :index 0}
            {:type :tool-call :id "b" :name "bash" :arguments "" :index 1}]
           (sse/parse-openai-event
            (str "{\"choices\":[{\"delta\":{\"tool_calls\":["
                 "{\"id\":\"a\",\"index\":0,\"function\":{\"name\":\"read\"}},"
                 "{\"id\":\"b\",\"index\":1,\"function\":{\"name\":\"bash\"}}]}}]}")))))

;; ─── OpenAI Responses event parsing (pi processResponsesStream) ────────────

(declare make-pipe)

(defn- responses-state [] (atom {:event-name nil :buf "" :slots {} :saw-terminal? false}))

(t/deftest test-parse-responses-event-text-and-thinking
  (t/is (= [{:type :text :content "hel"}]
           (sse/parse-responses-event
            "response.output_text.delta"
            "{\"output_index\":0,\"delta\":\"hel\"}"
            (responses-state))))
  (t/is (= [{:type :thinking :content "let me think"}]
           (sse/parse-responses-event
            "response.reasoning_text.delta"
            "{\"output_index\":0,\"delta\":\"let me think\"}"
            (responses-state))))
  (t/is (= [{:type :thinking :content "summary"}]
           (sse/parse-responses-event
            "response.reasoning_summary_text.delta"
            "{\"output_index\":0,\"delta\":\"summary\"}"
            (responses-state))))
  ;; refusal deltas stream as text (pi appends them to the text block)
  (t/is (= [{:type :text :content "I cannot"}]
           (sse/parse-responses-event
            "response.refusal.delta"
            "{\"output_index\":0,\"delta\":\"I cannot\"}"
            (responses-state)))))

(t/deftest test-parse-responses-event-tool-call
  (let [st (responses-state)
        start (sse/parse-responses-event
               "response.output_item.added"
               "{\"output_index\":0,\"item\":{\"type\":\"function_call\",\"id\":\"fc_123\",\"call_id\":\"call_abc\",\"name\":\"read\",\"arguments\":\"\"}}"
               st)]
    (t/is (= [{:type :tool-call :id "call_abc|fc_123" :name "read"
               :arguments "" :index 0}]
             start)
          "function_call items create a :tool-call with the pi id format")
    (t/is (= [{:type :tool-call-args :arguments "{\"path\":\"" :index 0}]
             (sse/parse-responses-event
              "response.function_call_arguments.delta"
              "{\"output_index\":0,\"delta\":\"{\\\"path\\\":\\\"\"}"
              st))
          "argument deltas stream as :tool-call-args")
    (t/is (= [{:type :tool-call-args :arguments "a.txt\"}" :index 0}]
             (sse/parse-responses-event
              "response.function_call_arguments.done"
              "{\"output_index\":0,\"arguments\":\"{\\\"path\\\":\\\"a.txt\\\"}\"}"
              st))
          "the final arguments emit only the delta past the accumulated partial")
    (t/is (= {:kind :tool-call :id "call_abc|fc_123" :name "read"
              :partial-json "{\"path\":\"a.txt\"}"}
             (get-in @st [:slots 0]))
          "the slot accumulates the full partial JSON")))

(t/deftest test-parse-responses-event-terminal
  (t/testing "completed → usage + :done :stop"
    (let [st (responses-state)
          evts (sse/parse-responses-event
                "response.completed"
                (str "{\"response\":{\"id\":\"resp_1\",\"status\":\"completed\","
                     "\"usage\":{\"input_tokens\":100,\"output_tokens\":50,\"total_tokens\":150,"
                     "\"input_tokens_details\":{\"cached_tokens\":10,\"cache_write_tokens\":5},"
                     "\"output_tokens_details\":{\"reasoning_tokens\":20}}}}")
                st)]
      (t/is (= [{:type :usage
                 :usage {:input_tokens 100 :output_tokens 50 :total_tokens 150
                         :input_tokens_details {:cached_tokens 10 :cache_write_tokens 5}
                         :output_tokens_details {:reasoning_tokens 20}}}
                {:type :done :stop-reason :stop}]
               evts))
      (t/is (:saw-terminal? @st))))
  (t/testing "completed with a tool-call slot → :done :tool-use (pi remap)"
    (let [st (responses-state)
          _ (sse/parse-responses-event
             "response.output_item.added"
             "{\"output_index\":0,\"item\":{\"type\":\"function_call\",\"id\":\"fc_1\",\"call_id\":\"call_1\",\"name\":\"bash\",\"arguments\":\"\"}}"
             st)
          evts (sse/parse-responses-event
                "response.completed"
                "{\"response\":{\"status\":\"completed\"}}"
                st)]
      (t/is (= [{:type :done :stop-reason :tool-use}] evts))))
  (t/testing "incomplete + max_output_tokens → :done :length"
    (let [evts (sse/parse-responses-event
                "response.incomplete"
                "{\"response\":{\"status\":\"incomplete\",\"incomplete_details\":{\"reason\":\"max_output_tokens\"}}}"
                (responses-state))]
      (t/is (= [{:type :done :stop-reason :length}] evts))))
  (t/testing "incomplete + other reason → error (pi stopReason 'error')"
    (let [evts (sse/parse-responses-event
                "response.incomplete"
                "{\"response\":{\"status\":\"incomplete\",\"incomplete_details\":{\"reason\":\"content_filter\"}}}"
                (responses-state))]
      (t/is (= [{:type :error :message "Response incomplete: content_filter"}] evts))))
  (t/testing "failed → error with the provider code+message"
    (let [evts (sse/parse-responses-event
                "response.failed"
                "{\"response\":{\"status\":\"failed\",\"error\":{\"code\":\"server_error\",\"message\":\"boom\"}}}"
                (responses-state))]
      (t/is (= [{:type :error :message "server_error: boom"}] evts)))))

(t/deftest test-responses-stream-completes
  (let [[in out] (make-pipe)
        events (atom [])
        f (future
            (sse/process-responses-stream {:body in}
                                          (fn [e] (swap! events conj e))
                                          nil)
            :done)]
    (.write out (.getBytes "event: response.output_text.delta\ndata: {\"output_index\":0,\"delta\":\"hi\"}\n\n"))
    (.write out (.getBytes "event: response.completed\ndata: {\"response\":{\"id\":\"resp_1\",\"status\":\"completed\",\"usage\":{\"input_tokens\":10,\"output_tokens\":5,\"total_tokens\":15}}}\n\n"))
    (.flush out)
    ;; pipes don't unblock on close-while-reading — let the reader consume first
    (Thread/sleep 50)
    (.close out)
    (t/is (= :done (deref f 3000 :timeout)))
    (t/is (= [{:type :text :content "hi"}
              {:type :usage :usage {:input_tokens 10 :output_tokens 5 :total_tokens 15}}
              {:type :done :stop-reason :stop}]
             @events))
    (.close in)))

(t/deftest test-responses-stream-premature-end
  ;; no terminal event before EOF → error (pi: 'OpenAI Responses stream
  ;; ended before a terminal response event'). signal is always an atom in
  ;; production (the loop passes :signal), so pass one here — SCI derefs nil
  ;; as an NPE.
  (let [events (atom [])
        body (java.io.ByteArrayInputStream.
              (.getBytes "event: response.output_text.delta\ndata: {\"output_index\":0,\"delta\":\"hi\"}\n\n"))]
    (sse/process-responses-stream {:body body}
                                  (fn [e] (swap! events conj e))
                                  (atom false))
    (t/is (= [{:type :text :content "hi"}
              {:type :error
               :message "OpenAI Responses stream ended before a terminal response event"}]
             @events))))

;; ─── Idle timeout (pi: httpIdleTimeoutMs / undici bodyTimeout) ────────────

(defn- make-pipe
  "A connected PipedInputStream/PipedOutputStream pair."
  []
  (let [in (java.io.PipedInputStream.)
        out (java.io.PipedOutputStream. in)]
    [in out]))

(t/deftest ^:slow test-openai-stream-idle-timeout
  (let [[in out] (make-pipe)
        events (atom [])
        f (future
            (sse/process-openai-stream {:body in}
                                       (fn [e] (swap! events conj e))
                                       nil
                                       100)
            :done)]
    (.write out (.getBytes "data: {\"choices\":[{\"delta\":{\"content\":\"hi\"}}]}\n\n"))
    (.flush out)
    ;; Do not write or close further — the stream must error via the idle
    ;; timeout instead of hanging. Closing the write side afterwards lets the
    ;; daemon reader thread see EOF (tests use pipes, which don't unblock on
    ;; close-while-reading).
    (Thread/sleep 300)
    (.close out)
    (t/is (= :done (deref f 3000 :timeout)))
    (t/is (= 2 (count @events)))
    (t/is (= :text (:type (first @events))))
    (t/is (= "hi" (:content (first @events))))
    (t/is (= :error (:type (second @events))))
    (t/is (str/includes? (:message (second @events)) "idle timeout"))
    (.close in)))

(t/deftest ^:slow test-openai-stream-completes-without-timeout
  (let [[in out] (make-pipe)
        events (atom [])
        f (future
            (sse/process-openai-stream {:body in}
                                       (fn [e] (swap! events conj e))
                                       nil
                                       300)
            :done)]
    (.write out (.getBytes "data: {\"choices\":[{\"delta\":{\"content\":\"hi\"}}]}\n\n"))
    (.flush out)
    (Thread/sleep 50)
    (.write out (.getBytes "data: [DONE]\n\n"))
    (.flush out)
    (.close out)
    (t/is (= :done (deref f 3000 :timeout)))
    (t/is (= [{:type :text :content "hi"}
              {:type :done :stop-reason :stop}]
             @events))
    (.close in)))

(t/deftest test-openai-stream-no-idle-timeout-when-disabled
  (let [[in out] (make-pipe)
        events (atom [])
        f (future
            (sse/process-openai-stream {:body in}
                                       (fn [e] (swap! events conj e))
                                       nil
                                       nil)
            :done)]
    (.write out (.getBytes "data: [DONE]\n\n"))
    (.flush out)
    (.close out)
    (t/is (= :done (deref f 3000 :timeout)))
    (t/is (= [{:type :done :stop-reason :stop}] @events))
    (.close in)))

(t/deftest ^:slow test-anthropic-stream-idle-timeout
  (let [[in out] (make-pipe)
        events (atom [])
        f (future
            (sse/process-anthropic-stream {:body in}
                                          (fn [e] (swap! events conj e))
                                          nil
                                          100)
            :done)]
    ;; Nothing is ever written — the stream must error via the idle timeout.
    ;; Close the write side to let the daemon reader thread see EOF.
    (Thread/sleep 300)
    (.close out)
    (t/is (= :done (deref f 3000 :timeout)))
    (t/is (= :error (:type (first @events))))
    (t/is (str/includes? (:message (first @events)) "idle timeout"))
    (.close in)))

(t/deftest test-anthropic-stream-completes
  ;; Regression: the event-name from an `event:` line must survive the
  ;; buffering (the cond previously stored the data value, so every event
  ;; parsed as :unknown and the stream produced no text).
  (let [[in out] (make-pipe)
        events (atom [])
        f (future
            (sse/process-anthropic-stream {:body in}
                                          (fn [e] (swap! events conj e))
                                          nil)
            :done)]
    (.write out (.getBytes (str "event: content_block_delta\n"
                                "data: {\"type\":\"content_block_delta\",\"index\":0,"
                                "\"delta\":{\"type\":\"text_delta\",\"text\":\"hi\"}}\n\n")))
    (.write out (.getBytes "event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n"))
    (.flush out)
    (Thread/sleep 50)
    (.close out)
    (t/is (= :done (deref f 3000 :timeout)))
    (t/is (= [{:type :text :content "hi"}
              {:type :done :stop-reason :end-turn}]
             @events))
    (.close in)))

(t/deftest test-openai-stream-read-error-surfaces-immediately
  ;; A transport read failure (e.g. HTTP/2 RST_STREAM thrown by
  ;; java.net.http mid-stream) must surface as an accurate error right away
  ;; — not stall to the idle deadline and report a misleading idle timeout.
  ;; Regression for "No retry after: Received RST_STREAM: Protocol error":
  ;; the daemon reader used to swallow read exceptions, so the classifier
  ;; never saw the real message.
  (let [events (atom [])
        throwing-reader
        (proxy [java.io.Reader] []
          (read
            ([] (throw (java.io.IOException. "Received RST_STREAM: Protocol error")))
            ([cbuf off len] (throw (java.io.IOException. "Received RST_STREAM: Protocol error")))))
        f (future
            (sse/process-openai-stream {:body throwing-reader}
                                       (fn [e] (swap! events conj e))
                                       nil
                                       300000) ;; 5-min idle — must not be reached
            :done)]
    (t/is (= :done (deref f 3000 :timeout)))
    (t/is (= [{:type :error :message "Stream error: Received RST_STREAM: Protocol error"}]
             @events))))

(t/deftest ^:slow test-openai-stream-idle-resets-on-flow
  ;; Data arriving well within the idle window over a total duration longer
  ;; than the timeout must not stall — the clock resets per byte (undici
  ;; bodyTimeout semantics, not a total deadline). Idle 200ms with 50ms gaps
  ;; (4x headroom) over a 400ms total (2x the idle).
  (let [[in out] (make-pipe)
        events (atom [])
        f (future
            (sse/process-openai-stream {:body in}
                                       (fn [e] (swap! events conj e))
                                       nil
                                       200)
            :done)]
    (dotimes [_ 8]
      (.write out (.getBytes "data: {\"choices\":[{\"delta\":{\"content\":\"x\"}}]}\n\n"))
      (.flush out)
      (Thread/sleep 50))
    (.write out (.getBytes "data: [DONE]\n\n"))
    (.flush out)
    (.close out)
    (t/is (= :done (deref f 3000 :timeout)))
    (t/is (= 8 (count (filter #(= :text (:type %)) @events))))
    (t/is (= :done (:type (last @events))))
    (.close in)))
