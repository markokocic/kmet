#!/usr/bin/env bb
;; mcpScript end-to-end validation (§12.6 Phase 2): load the extension
;; against create-nullable-api with a config pointing at the fake stdio
;; server, then drive the registered mcpScript tool: search/describe/call
;; envelopes, emit + return value, error path, timeout, tool_not_found.
;;
;; Usage: bb validate-script.bb scripts/fake-mcp-server.bb
(require '[clojure.string :as str]
         '[clojure.java.io :as io]
         '[kmet.extension :as ext]
         '[extensions.mcp-adapter :as mcp]
         '[extensions.mcp-adapter.config :as config])

(def failures (atom 0))

(defn check [label ok]
  (println (if ok "PASS" "FAIL") label)
  (when-not ok (swap! failures inc)))

(def fake-stdio (or (first *command-line-args*)
                    (do (println "Usage: bb validate-script.bb <fake-mcp-server.bb>")
                        (System/exit 2))))

(def home (System/getProperty "user.dir"))
(def global (str home "/.mcp-script-g-" (System/nanoTime) ".edn"))
(def project (str home "/.mcp-script-p-" (System/nanoTime) ".edn"))

(defn script-exec
  "Run CODE through the registered mcpScript tool; returns the tool
   result map."
  [tool code & [timeout-ms]]
  (let [params (cond-> {:code code}
                 timeout-ms (assoc :timeoutMs timeout-ms))]
    ((:execute tool) params)))

(spit global (pr-str {:settings {}
                      :mcp-servers {"fake" {:command "bb" :args [fake-stdio]}}}))
(spit project "{}\n")

(with-redefs [config/global-config-path (delay global)
              config/project-config-path (fn [& _] project)]
  (let [{:keys [api state]} (ext/create-nullable-api)]
    (mcp/init api)
    (let [tool (get-in @state [:tools "mcpScript"])]
      (check "mcpScript tool registered" (some? tool))
      (check "mcpScript params" (contains? (get-in tool [:parameters :properties]) "code"))

      ;; connect the fake server first (search/describe/call resolve from
      ;; the metadata cache — pi parity: the script tool sees tools that
      ;; were cached by a connect)
      (let [proxy-tool (get-in @state [:tools "mcp"])
            r ((:execute proxy-tool) {:connect "fake"})]
        (check "proxy connect for cache" (false? (:is-error r))))

      (println "\n── script execution ──")
      ;; search + emit + return value
      (let [r (script-exec tool "(emit (tools/search {:query \"echo\"})) (+ 1 2)")]
        (check "search envelope + return"
               (and (str/includes? (:content r) "echo")
                    (str/includes? (:content r) "3")
                    (false? (:is-error r)))))
      ;; describe
      (let [r (script-exec tool "(emit (tools/describe {:path \"fake_echo\"})) \"done\"")]
        (check "describe envelope"
               (and (str/includes? (:content r) "fake_echo")
                    (str/includes? (:content r) "done"))))
      ;; call with args + emit
      (let [r (script-exec tool "(emit ((tools/call \"fake_echo\" {:message \"hi from script\"}) :data))")]
        (check "tools/call envelope"
               (str/includes? (:content r) "echo: hi from script")))
      ;; call error: unknown tool
      (let [r (script-exec tool "(emit (tools/call \"nope\" {}))")]
        (check "tools/call tool_not_found"
               (str/includes? (:content r) "tool_not_found")))
      ;; call error: server error result (boom -> isError)
      (let [r (script-exec tool "(emit (tools/call \"fake_boom\" {}))")]
        (check "tools/call error result"
               (str/includes? (:content r) "kaboom")))
      ;; capture stdout + console
      (let [r (script-exec tool "(println \"captured\") (console/log \"logged\") \"ok\"")]
        (check "stdout + console captured"
               (and (str/includes? (:content r) "captured")
                    (str/includes? (:content r) "logged"))))
      ;; throw -> script_error
      (let [r (script-exec tool "(throw (ex-info \"boom\" {}))")]
        (check "script error"
               (and (true? (:is-error r))
                    (= "script_error" (get-in r [:details :error]))
                    (str/includes? (:content r) "boom"))))
      ;; timeout kills the worker
      (let [r (script-exec tool "(loop [] (recur))" 1500)]
        (check "timeout"
               (and (true? (:is-error r))
                    (= "timeout" (get-in r [:details :error]))
                    (str/includes? (:content r) "timed out after 1500ms"))))
      ;; invalid path never reaches dispatch
      (let [r (script-exec tool "(emit (tools/call \"\" {}))")]
        (check "invalid_tool_path"
               (str/includes? (:content r) "invalid_tool_path")))
      ;; progress streaming: slow call with on-update partials
      (let [partials (atom [])
            tool (get-in @state [:tools "mcpScript"])
            r ((:execute tool) {:code "(emit (tools/call \"fake_slow\" {:ms 600}))"}
               (fn [partial] (swap! partials conj (:content partial))))]
        (check "streaming partials arrive" (seq @partials))
        (check "slow call result" (str/includes? (:content r) "slept")))
      ;; detail: call trace present
      (let [r (script-exec tool "(tools/call \"fake_echo\" {:message \"x\"}) nil")]
        (check "details :calls"
               (some (fn [c] (and (= "call" (:operation c))
                                  (= "fake_echo" (:path c))
                                  (true? (:ok c))))
                     (get-in r [:details :calls]))))
      (mcp/shutdown api)
      (check "shutdown after scripts" true))))

(io/delete-file global true)
(io/delete-file project true)
(println "\n" (if (zero? @failures) "ALL PASS" (str @failures " FAILURES")))
(System/exit (if (zero? @failures) 0 1))
