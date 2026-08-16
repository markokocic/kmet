#!/usr/bin/env bb
;; Fake stdio MCP server for validating the mcp-adapter client
;; (extensions/mcp-adapter/scripts/fake-mcp-server.bb — §12.1).
;;
;; Implements: initialize (negotiation), notifications/initialized echo,
;; tools/list (with a cursor page), tools/call (echo + error path), a
;; notification mid-request, and clean exit on SIGTERM/EOF.
;;
;; Usage: bb fake-mcp-server.bb  (speaks JSON-RPC over stdin/stdout)
(require '[cheshire.core :as json]
         '[clojure.string :as str])

(def tools
  [{:name "echo" :description "Echo the message back"
    :inputSchema {:type "object"
                  :properties {"message" {:type "string" :description "Text to echo"}}
                  :required ["message"]}}
   {:name "add" :description "Add two numbers"
    :inputSchema {:type "object"
                  :properties {"a" {:type "number"} "b" {:type "number"}}
                  :required ["a" "b"]}}
   {:name "slow" :description "Sleeps then returns"
    :inputSchema {:type "object"
                  :properties {"ms" {:type "number" :description "Sleep ms"}}
                  :required []}}
   {:name "boom" :description "Always fails with an error result"
    :inputSchema {:type "object" :properties {} :required []}}
   {:name "ping-mid" :description "Sends a notification mid-request"
    :inputSchema {:type "object" :properties {} :required []}}])

(def prompts
  [{:name "brief" :description "Summarize a topic briefly"
    :arguments [{:name "topic" :description "The topic" :required true}]}
   {:name "review" :description "Review code"
    :arguments [{:name "path" :description "File path" :required true}
                {:name "focus" :description "Focus area" :required false}]}])

(def resources
  [{:name "README" :uri "file:///README.md" :description "The project readme"}
   {:name "schema" :uri "file:///schema.json" :description "JSON schema"}])

(defn- send! [msg]
  (println (json/generate-string msg))
  (flush))

(defn- send-result! [id result]
  (send! {:jsonrpc "2.0" :id id :result result}))

(defn- send-error! [id code message]
  (send! {:jsonrpc "2.0" :id id :error {:code code :message message}}))

(defn- handle-call [id params]
  (let [name (:name params)
        args (:arguments params)]
    (case name
      "echo" (send-result! id {:content [{:type "text" :text (str "echo: " (:message args))}]})
      "add" (send-result! id {:content [{:type "text" :text (str (+ (:a args) (:b args)))}]})
      "slow" (do (doseq [i [25 50 75]]
                   (send! {:jsonrpc "2.0" :method "notifications/progress"
                           :params {:progress i :total 100
                                    :message (str "working " i "%")}})
                   (Thread/sleep (long (/ (or (:ms args) 100) 4))))
                 (send-result! id {:content [{:type "text" :text "slept"}]}))
      "boom" (send-result! id {:content [{:type "text" :text "kaboom"}]
                               :isError true})
      "ping-mid"
      (do (send! {:jsonrpc "2.0" :method "notifications/progress"
                  :params {:progress 0.5 :progressToken "t"}})
          (Thread/sleep 50)
          (send-result! id {:content [{:type "text" :text "pong"}]}))
      (send-error! id -32602 (str "Unknown tool: " name)))))

(defn- handle-line [line]
  (when (seq (str/trim line))
    (let [msg (json/parse-string line true)
          id (:id msg)
          method (:method msg)]
      (case method
        "initialize"
        (do (send-result! id {:protocolVersion (:protocolVersion (:params msg))
                              :capabilities {:tools {}
                                             :prompts {:listChanged false}
                                             :resources {:listChanged false}}
                              :serverInfo {:name "fake-mcp-server" :version "1.0.0"}})
            (send! {:jsonrpc "2.0" :method "notifications/initialized" :params {}}))
        "notifications/initialized" nil
        "tools/list"
        (let [cursor (:cursor (:params msg))
              page1 (subvec (vec tools) 0 2)
              page2 (subvec (vec tools) 2)]
          (if (nil? cursor)
            (send-result! id {:tools page1 :nextCursor "p2"})
            (send-result! id {:tools page2})))
        "tools/call" (handle-call id (:params msg))
        "prompts/list" (send-result! id {:prompts prompts})
        "prompts/get"
        (let [name (:name (:params msg))
              args (or (:arguments (:params msg)) {})]
          (case name
            "brief" (send-result! id {:description "Summarize a topic briefly"
                                      :messages [{:role "user"
                                                  :content {:type "text"
                                                            :text (str "Briefly summarize: " (:topic args))}}]})
            "review" (send-result! id {:description "Review code"
                                       :messages [{:role "user"
                                                   :content {:type "text"
                                                             :text (str "Review " (:path args))}}
                                                  {:role "assistant"
                                                   :content {:type "text"
                                                             :text (str "Focus: " (or (:focus args) "overall"))}}]})
            (send-error! id -32602 (str "Unknown prompt: " name))))
        "resources/list" (send-result! id {:resources resources})
        "resources/read"
        (let [uri (:uri (:params msg))]
          (case uri
            "file:///README.md" (send-result! id {:contents [{:type "text"
                                                              :uri uri
                                                              :text "# Fake README\ncontent"}]})
            "file:///schema.json" (send-result! id {:contents [{:type "text"
                                                                :uri uri
                                                                :text "{\"type\": \"object\"}"}]})
            (send-error! id -32602 (str "Unknown resource: " uri))))
        (send-error! id -32601 (str "Method not found: " method))))))

;; clean exit on EOF (client killed the pipe)
(doseq [line (line-seq (java.io.BufferedReader. *in*))]
  (try (handle-line line) (catch Exception e (println "ERR" (ex-message e)))))
