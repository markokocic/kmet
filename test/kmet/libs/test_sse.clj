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
  (t/is (= {:type :done :stop-reason :stop} (sse/parse-openai-event "[DONE]")))
  (t/is (= {:type :done :stop-reason :stop} (sse/parse-openai-event nil))))

(t/deftest test-parse-openai-event-text
  (t/is (= {:type :text :content "hi"}
           (sse/parse-openai-event "{\"choices\":[{\"delta\":{\"content\":\"hi\"}}]}"))))

(t/deftest test-parse-openai-event-finish
  (t/is (= {:type :done :stop-reason :tool_calls}
           (sse/parse-openai-event
            "{\"choices\":[{\"delta\":{},\"finish_reason\":\"tool_calls\"}]}"))))

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
    ;; timeout instead of hanging. Closing the write side afterwards releases
    ;; the reader thread (PipedInputStream close would deadlock on a blocked
    ;; read — a test-only artifact of pipes).
    (Thread/sleep 300)
    (.close out)
    (t/is (= :done (deref f 3000 :timeout)))
    (t/is (= 2 (count @events)))
    (t/is (= :text (:type (first @events))))
    (t/is (= "hi" (:content (first @events))))
    (t/is (= :error (:type (second @events))))
    (t/is (str/includes? (:message (second @events)) "idle timeout"))
    (.close out)
    (.close in)))

(t/deftest test-openai-stream-completes-without-timeout
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
    ;; Close the write side to release the reader thread before deref'ing.
    (Thread/sleep 300)
    (.close out)
    (t/is (= :done (deref f 3000 :timeout)))
    (t/is (= :error (:type (first @events))))
    (t/is (str/includes? (:message (first @events)) "idle timeout"))
    (.close out)
    (.close in)))

(t/deftest test-openai-stream-idle-resets-on-flow
  ;; Data arriving well within the idle window over a total duration longer
  ;; than the timeout must not stall — the clock resets per byte (undici
  ;; bodyTimeout semantics, not a total deadline).
  (let [[in out] (make-pipe)
        events (atom [])
        f (future
            (sse/process-openai-stream {:body in}
                                       (fn [e] (swap! events conj e))
                                       nil
                                       100)
            :done)]
    (dotimes [_ 5]
      (.write out (.getBytes "data: {\"choices\":[{\"delta\":{\"content\":\"x\"}}]}\n\n"))
      (.flush out)
      (Thread/sleep 50))
    (.write out (.getBytes "data: [DONE]\n\n"))
    (.flush out)
    (.close out)
    (t/is (= :done (deref f 3000 :timeout)))
    (t/is (= 5 (count (filter #(= :text (:type %)) @events))))
    (t/is (= :done (:type (last @events))))
    (.close in)))
