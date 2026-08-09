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

;; ─── Idle timeout (pi: httpIdleTimeoutMs / undici bodyTimeout) ────────────

(defn- make-pipe
  "A connected PipedInputStream/PipedOutputStream pair."
  []
  (let [in (java.io.PipedInputStream.)
        out (java.io.PipedOutputStream. in)]
    [in out]))

(t/deftest test-openai-stream-idle-timeout
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

(t/deftest test-openai-stream-completes-without-timeout
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

(t/deftest test-anthropic-stream-idle-timeout
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

(t/deftest test-openai-stream-idle-resets-on-flow
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
