#!/usr/bin/env bb
;; End-to-end smoke: load the extension against the nullable api with a
;; real config pointing at the fake stdio server, then drive the proxy
;; tool: status → search → connect → call → disconnect → status.
;; Usage: bb e2e.bb <fake-mcp-server.bb>
(require '[clojure.string :as str]
         '[clojure.java.io :as io]
         '[kmet.extension :as ext]
         '[extensions.mcp-adapter :as mcp]
         '[extensions.mcp-adapter.config :as config]
         '[extensions.mcp-adapter.metadata :as metadata])

(def failures (atom 0))
(defn check [label ok]
  (println (if ok "PASS" "FAIL") label)
  (when-not ok (swap! failures inc)))

(let [[fake-stdio] *command-line-args*
      fake-stdio (str (System/getProperty "user.dir") "/" fake-stdio)
      global (str (System/getProperty "user.dir") "/.e2e-global-" (System/nanoTime) ".edn")
      cache-file (str (System/getProperty "user.dir") "/.e2e-cache-" (System/nanoTime) ".edn")]
  (spit global (pr-str {:mcp-servers
                        {"e2e" {:command "bb" :args [fake-stdio] :lifecycle :lazy}}}))
  (with-redefs [config/global-config-path (delay global)
                config/project-config-path (fn [& _] (str global ".project"))
                metadata/cache-path (constantly cache-file)]
    (try
      (let [{:keys [api state]} (ext/create-nullable-api)
            _ (mcp/init api)
            proxy-tool (get-in @state [:tools "mcp"])
            execute (:execute proxy-tool)
            s (fn [params] (execute params))
            result (s {})]
        (check "status text lists server"
               (str/includes? (:content result) "e2e"))
        (let [r (s {:search "echo"})]
          (check "search finds tool"
                 (str/includes? (:content r) "echo")))
        (let [r (s {:connect "e2e"})]
          (check "connect lists tools"
                 (and (not (:is-error r)) (str/includes? (:content r) "e2e_ping_mid"))))
        (let [r (s {:tool "echo" :args {:message "hello"}})]
          (check "tool call through proxy"
                 (str/includes? (:content r) "echo: hello")))
        (let [r (s {:tool "boom" :args {}})]
          (check "tool error surfaces" (and (:is-error r) (= "kaboom" (:content r)))))
        (let [r (s {:describe "echo"})]
          (check "describe shows params"
                 (str/includes? (:content r) "message")))
        (let [r (s {:disconnect "e2e"})]
          (check "disconnect" (str/includes? (:content r) "Disconnected")))
        (let [r (s {:server "e2e"})]
          (check "server list after disconnect (cached)"
                 (str/includes? (:content r) "not connected, cached")))
        (mcp/shutdown api)
        (check "shutdown" true))
      (finally
        (io/delete-file global true)
        (io/delete-file cache-file true))))
  (println "\n" (if (zero? @failures) "ALL PASS" (str @failures " FAILURES")))
  (System/exit (if (zero? @failures) 0 1)))
