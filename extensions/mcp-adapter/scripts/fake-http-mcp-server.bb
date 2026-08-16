#!/usr/bin/env bb
;; Fake streamable-HTTP / legacy-SSE MCP server for validating the
;; mcp-adapter client (extensions/mcp-adapter/scripts/fake-http-mcp-server.bb
;; — §12.2). Babashka has no HTTP server, so this is a plain
;; java.net.ServerSocket loop speaking HTTP/1.1.
;;
;; Endpoints (single port, path routing):
;;   POST /mcp          — streamable HTTP: JSON or SSE responses; captures
;;                         Mcp-Session-Id on initialize and echoes it back
;;   POST /mcp?slow=1   — sleeps before answering (exercises the timeout
;;                         path when the client timeout is short)
;;   GET  /sse          — legacy SSE stream (endpoint + message events)
;;   POST /sse          — request endpoint for the legacy SSE transport
;;                         (answers 202; the result arrives on the stream)
;;
;; Usage: bb fake-http-mcp-server.bb [port]
;; Prints "PORT <n>" on stdout so the caller can read the assigned port.
(require '[cheshire.core :as json]
         '[clojure.string :as str]
         '[clojure.java.io :as io]
         '[clojure.core.async :as async])

(def port (Long/parseLong (or (first *command-line-args*) "0")))

(def tools
  [{:name "http-echo" :description "Echo the message back over HTTP"
    :inputSchema {:type "object"
                  :properties {"message" {:type "string"}}
                  :required ["message"]}}
   {:name "http-add" :description "Add two numbers over HTTP"
    :inputSchema {:type "object"
                  :properties {"a" {:type "number"} "b" {:type "number"}}
                  :required ["a" "b"]}}
   {:name "http-slow" :description "Sleeeeps (2s)"
    :inputSchema {:type "object" :properties {} :required []}}])

(def state (atom {:session-id nil
                  :sse-chan nil}))

(defn- http-response
  ([status body] (http-response status body {"Content-Type" "application/json"}))
  ([status body headers]
   (let [body (str body)
         head (str "HTTP/1.1 " status " "
                   ({200 "OK" 202 "Accepted" 400 "Bad Request" 401 "Unauthorized"
                     404 "Not Found" 405 "Method Not Allowed"} status "OK")
                   "\r\n"
                   (str/join "" (map (fn [[k v]] (str k ": " v "\r\n")) headers))
                   "Content-Length: " (count (.getBytes body "UTF-8")) "\r\n"
                   "Connection: close\r\n\r\n")]
     (str head body))))

(defn- read-request-from
  "Read the headers + body after the request line."
  [reader line]
  (let [headers (loop [headers {}]
                  (let [h (.readLine reader)]
                    (if (and h (seq h))
                      (let [[k v] (str/split h #":" 2)]
                        (recur (assoc headers (str/lower-case (or k ""))
                                      (str/trim (or v "")))))
                      headers)))
        [method target] (str/split line #"\s+" 3)
        [path query] (str/split (or target "") #"\?" 2)
        content-length (Long/parseLong (or (get headers "content-length") "0"))
        body (when (pos? content-length)
               (let [buf (char-array content-length)]
                 (.read reader buf 0 content-length)
                 (String. buf)))]
    {:method method :path path :query query :headers headers :body body}))

(defn- read-request
  "Read one HTTP request from IN (request line + headers + body)."
  [in]
  (let [reader (io/reader in)
        line (.readLine reader)]
    (when line
      (read-request-from reader line))))

(defn- handle-json-rpc
  "Handle one JSON-RPC message; returns the response map or nil for
   notifications."
  [body session-id]
  (let [msg (json/parse-string body true)
        id (:id msg)
        method (:method msg)]

    (case method
      "initialize"
      (do (reset! state (assoc @state :session-id (or session-id "sess-1")))
          {:jsonrpc "2.0" :id id
           :result {:protocolVersion (get-in msg [:params :protocolVersion])
                    :capabilities {:tools {}
                                   :prompts {:listChanged false}
                                   :resources {:listChanged false}}
                    :serverInfo {:name "fake-http-mcp-server" :version "1.0.0"}}})
      "notifications/initialized" nil
      "tools/list"
      {:jsonrpc "2.0" :id id :result {:tools tools}}
      "tools/call"
      (let [name (get-in msg [:params :name])
            args (get-in msg [:params :arguments])]
        (case name
          "http-echo" {:jsonrpc "2.0" :id id
                       :result {:content [{:type "text"
                                           :text (str "http-echo: " (:message args))}]}}
          "http-add" {:jsonrpc "2.0" :id id
                      :result {:content [{:type "text"
                                          :text (str (+ (:a args) (:b args)))}]}}
          "http-slow" (do (Thread/sleep 2000)
                          {:jsonrpc "2.0" :id id
                           :result {:content [{:type "text" :text "finally"}]}})
          {:jsonrpc "2.0" :id id
           :result {:content [{:type "text" :text "unknown"}]
                    :isError true}}))
      "prompts/list"
      {:jsonrpc "2.0" :id id
       :result {:prompts [{:name "http-brief"
                           :description "Summarize a topic briefly"
                           :arguments [{:name "topic" :required true}]}]}}
      "prompts/get"
      {:jsonrpc "2.0" :id id
       :result {:messages [{:role "user"
                           :content {:type "text"
                                     :text (str "http brief: "
                                                (get-in msg [:params :arguments :topic]))}}]}}
      "resources/list"
      {:jsonrpc "2.0" :id id
       :result {:resources [{:name "HTTP doc" :uri "http://fake/doc"
                             :description "A fake http resource"}]}}
      "resources/read"
      {:jsonrpc "2.0" :id id
       :result {:contents [{:type "text" :uri (get-in msg [:params :uri])
                            :text "http resource content"}]}}
      {:jsonrpc "2.0" :id id
       :error {:code -32601 :message (str "Method not found: " method)}})))

(defn- handle-streamable
  "POST /mcp — JSON or SSE response per the Accept header."
  [req]
  (let [session-id (get-in req [:headers "mcp-session-id"])
        slow? (str/includes? (or (:query req) "") "slow")
        body-msg (json/parse-string (:body req) true)
        is-initialize? (= "initialize" (:method body-msg))
        is-slow-call? (and (= "tools/call" (:method body-msg))
                           (= "http-slow" (get-in body-msg [:params :name])))
        response (handle-json-rpc (:body req) session-id)]
    ;; notifications get an empty 200 (never close without a response —
    ;; java.net.http reports that as an error)
    (if (nil? response)
      (http-response 200 "" {"Content-Type" "application/json"})
      (do
        (when slow? (Thread/sleep 2000))
        (let [session-header (when is-initialize?
                               {"Mcp-Session-Id" (or session-id "sess-1")})]
          (if (str/includes? (str (get-in req [:headers "accept"])) "text/event-stream")
            (let [sse-parts (if is-slow-call?
                              ;; progress notifications before the result
                              (apply str
                                     (map (fn [p]
                                            (str "event: message\ndata: "
                                                 (json/generate-string
                                                  {:jsonrpc "2.0"
                                                   :method "notifications/progress"
                                                   :params {:progress p :total 100
                                                            :message (str "p" p)}})
                                                 "\n\n"))
                                          [10 50]))
                              "")
                  body (str sse-parts
                            "event: message\ndata: "
                            (json/generate-string response) "\n\n")]
              (http-response 200 body
                             (merge {"Content-Type" "text/event-stream"} session-header)))
            (http-response 200 (json/generate-string response)
                           (merge {"Content-Type" "application/json"} session-header))))))))

(defn- sse-handler
  "The GET /sse stream: endpoint event, then every POSTed result as a
   message event, until the client disconnects."
  [socket]
  (try
    (with-open [in (.getInputStream socket)
                out (.getOutputStream socket)]
      (let [writer (io/writer out)
            ch (async/chan 64)]
        (.write writer (str "HTTP/1.1 200 OK\r\n"
                            "Content-Type: text/event-stream\r\n"
                            "Cache-Control: no-cache\r\n\r\n"
                            "event: endpoint\ndata: /sse\n\n"))
        (.flush writer)
        (reset! state (assoc @state :sse-chan ch))
        (loop []
          (let [result (async/<!! ch)]
            (when result
              (.write writer (str "event: message\ndata: "
                                  (json/generate-string result) "\n\n"))
              (.flush writer)
              (recur))))))
    (catch Exception _ nil)
    (finally
      (swap! state dissoc :sse-chan))))

(defn- handle-conn
  "Handle one accepted connection: SSE streams go to sse-handler (with the
   already-read request line); everything else is a plain request/response
   read through READER (the buffered reader that consumed the request
   line)."
  [socket line reader]
  (try
    (if (and line (str/includes? line "GET /sse"))
      (sse-handler socket)
      (with-open [out (.getOutputStream socket)]
        (let [req (read-request-from reader line)]
          (when req
            (let [path (:path req)
                  method (:method req)
                  response (cond
                             (and (= path "/sse") (= method "POST"))
                             (let [result (handle-json-rpc (:body req) nil)]
                               (when-let [ch (:sse-chan @state)]
                                 (async/>!! ch result))
                               (http-response 202 "" {"Content-Type" "application/json"}))

                             (= path "/mcp")
                             (handle-streamable req)

                             :else (http-response 404 "not found"))]
              (when response
                (.write out (.getBytes response "UTF-8"))
                (.flush out)))))))
    (catch Exception e
      (binding [*out* *err*] (println "DBG handle-conn-error:" (ex-message e))))))

(defn -main [& _]
  (let [server (java.net.ServerSocket. port 10
                                       (java.net.InetAddress/getByName "127.0.0.1"))]
    (reset! state (assoc @state :port (.getLocalPort server)))
    (println "PORT" (.getLocalPort server))
    (flush)
    (loop []
      (try
        (let [socket (.accept server)]
          (future
            (try
              (let [reader (io/reader (.getInputStream socket))
                    line (.readLine reader)]
                (handle-conn socket line reader))
              (catch Exception e
                (binding [*out* *err*] (println "DBG conn-error:" (ex-message e)))))))
        (catch Exception e
          (binding [*out* *err*] (println "DBG accept-error:" (ex-message e)))))
      (recur))))

(-main)
