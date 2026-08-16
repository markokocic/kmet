(ns extensions.mcp-adapter.script
  "mcpScript for kmet (pi: mcp-code.ts + mcp-script-worker.mjs, ported to
   babashka: the script language is Clojure, the isolation model is the
   same — the user code runs in a fresh `bb` subprocess that has NO host
   access, only the tools bridge).

   The tool takes trusted Clojure code that uses:
     (tools/search {:query s :server s :limit n :offset n}) →
       {:items [{:path :name :server :description :score}] :total
        :has-more :next-offset}          ;; not an {ok data} envelope
     (tools/describe {:path p})          ;; tool descriptor or
                                         ;; {:path :error {:code :message}}
     (tools/call path args)              ;; → {:ok true :data ...} or
                                         ;;   {:ok false :error {:code :message}}
     (tools.<prefixed-name> args)        ;; flat call shorthand
     (emit value)                        ;; user-visible output
     console.log/info/warn/error/debug   ;; captured → emitted
   The return value of the script body is emitted as the final output
   block (strings raw, other values pretty JSON / pr-str).

   Protocol: the child prints one JSON line per message on stdout
   ({:type call|search|describe|emit|done|error}), the parent answers
   {:type result :id n :envelope {...}} on stdin. User stdout/stderr are
   captured inside the child (never touch the protocol stream) and flushed
   as a [stdout] emit at the end. Timeout (default 30s, :timeoutMs param)
   kills the process tree and reports; in-flight calls appear in details
   :calls with ok false / error \"incomplete\" (pi parity)."
  (:require [babashka.fs :as fs]
            [babashka.process :as proc]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [extensions.mcp-adapter.output-guard :as guard]
            [extensions.mcp-adapter.proxy :as proxy]
            [kmet.libs.process :as process]))

(def default-timeout-ms 30000)

(defn- spawn
  "Start a daemon thread running F (future is not available in the
   extension sci context). Exceptions in F are dropped."
  [f]
  (let [t (Thread. (fn [] (try (f) (catch Throwable _ nil))))]
    (.setDaemon t true)
    (.start t)
    t))

;; ─── The embedded child runtime ───────────────────────────────────────────
;; Evaluated by a fresh `bb` subprocess (full Clojure — no sci restrictions
;; there). The user code file path arrives as argv; the runtime binds the
;; tools bridge, redirects *out*/*err* into capture buffers so user
;; printing can never corrupt the protocol stream, then load-files the
;; code and reports the return value or the error.

(def ^:private tools-ns-source
  ;; The tools bridge as a real namespace so `(tools/call path args)` /
  ;; `(tools/search {...})` / `(tools/describe {...})` resolve as vars
  ;; (pi's JS Proxy forwards unknown properties as calls; Clojure cannot
  ;; do that with a map — the flat `tools.<name>` shorthand is a recorded
  ;; deviation, the skill teaches tools/call).
  (str "(ns mcp-script.tools)\n"
       "(defn call [path & [args]]\n"
       "  (if (or (not (string? path)) (clojure.string/blank? (clojure.string/trim path)))\n"
       "    {:ok false :error {:code \"invalid_tool_path\"\n"
       "                       :message \"tools/call requires a non-empty tool path.\"}}\n"
       "    (mcp-script-runtime/rpc \"call\" {:path path :args args})))\n"
       "(defn search [& [input]] (mcp-script-runtime/rpc \"search\" {:input input}))\n"
       "(defn describe [& [input]] (mcp-script-runtime/rpc \"describe\" {:input input}))\n"))

(def ^:private console-ns-source
  ;; console as a namespace so `(console/log ...)` resolves (pi's captured
  ;; console object; emits are user-visible output)
  (str "(ns mcp-script.console)\n"
       "(defn- emit [tag & args]\n"
       "  (mcp-script-runtime/emit (str \"[console.\" tag \"] \"\n"
       "                              (clojure.string/join \" \"\n"
       "                                              (map mcp-script-runtime/format-value args)))))\n"
       "(defn log [& args] (apply emit \"log\" args))\n"
       "(defn info [& args] (apply emit \"info\" args))\n"
       "(defn warn [& args] (apply emit \"warn\" args))\n"
       "(defn error [& args] (apply emit \"error\" args))\n"
       "(defn debug [& args] (apply emit \"debug\" args))\n"))

(def ^:private runtime-source
  (str "(ns mcp-script-runtime)\n"
       "(require '[cheshire.core :as json] '[clojure.string :as str])\n"
       "(def tools-ns-path (first *command-line-args*))\n"
       "(def console-ns-path (second *command-line-args*))\n"
       "(def code-path (nth *command-line-args* 2))\n"
       "(def ^:private proto-out (java.io.BufferedWriter. (java.io.OutputStreamWriter. System/out)))\n"
       "(defn- proto! [m] (.write proto-out (str (json/generate-string m) \"\\n\")) (.flush proto-out))\n"
       "(def ^:private user-out (java.io.ByteArrayOutputStream.))\n"
       "(def ^:private user-err (java.io.ByteArrayOutputStream.))\n"
       "(def ^:private user-out-writer (java.io.PrintWriter. user-out))\n"
       "(def ^:private user-err-writer (java.io.PrintWriter. user-err))\n"
       "(defn format-value [v]\n"
       "  (cond (string? v) v\n"
       "        :else (try (json/generate-string v {:pretty true})\n"
       "                   (catch Exception _ (pr-str v)))))\n"
       "(defn emit [value] (proto! {:type \"emit\" :value (format-value value)}))\n"
       "(defn rpc [type payload]\n"
       "  (let [id (swap! (atom 0) inc)]\n"
       "    (proto! (assoc payload :type type :id id))\n"
       "    (loop []\n"
       "      (let [line (read-line)]\n"
       "        (if (nil? line)\n"
       "          {:ok false :error {:code \"transport_closed\" :message \"mcpScript host closed the pipe\"}}\n"
       "          (let [msg (try (json/parse-string line true) (catch Exception _ nil))]\n"
       "            (if (and (map? msg) (= id (:id msg))) (:envelope msg) (recur))))))))\n"
       "(load-file tools-ns-path)\n"
       "(load-file console-ns-path)\n"
       "(ns mcp-script-runtime)\n"
       "(alias 'tools 'mcp-script.tools)\n"
       "(alias 'console 'mcp-script.console)\n"
       "(defn- flush-captured! []\n"
       "  (.flush user-out-writer)\n"
       "  (.flush user-err-writer)\n"
       "  (let [out (str user-out) err (str user-err)]\n"
       "    (when (seq out) (proto! {:type \"emit\" :value (str \"[stdout]\\n\" out)}))\n"
       "    (when (seq err) (proto! {:type \"emit\" :value (str \"[stderr]\\n\" err)}))))\n"
       "(try\n"
       "  ;; set! on *out*/*err* is not allowed in bb (root binding) — bind\n"
       "  (binding [*out* user-out-writer *err* user-err-writer]\n"
       "    (let [return-value (load-file code-path)]\n"
       "      (flush-captured!)\n"
       "      (proto! (if (nil? return-value)\n"
       "                {:type \"done\"}\n"
       "                {:type \"done\" :return (format-value return-value)}))))\n"
       "  (catch Throwable t\n"
       "    (flush-captured!)\n"
       "    (proto! {:type \"error\"\n"
       "             :message (str (some-> t class .getSimpleName) \": \" (or (ex-message t) (str t)))})))\n"))

;; ─── Child process + protocol ─────────────────────────────────────────────

(defn- bb-binary
  "The babashka binary for the script subprocess: KMET_BB env override,
   else bb on PATH. nil when not found."
  []
  (or (let [env (System/getenv "KMET_BB")]
        (when (and env (seq (str/trim env))) (str/trim env)))
      (fs/which "bb")))

(defn- temp-dir-root
  "A writable temp dir: TMPDIR env when set (Termux), else the JVM tmpdir."
  []
  (or (System/getenv "TMPDIR") (System/getProperty "java.io.tmpdir")))

(defn- write-temp-script
  "Write TEXT to a fresh temp file; returns the path."
  [prefix text]
  ;; fs/create-temp-dir is broken in this bb version (NoSuchFileException)
  ;; — build the dir manually
  (let [dir (str (temp-dir-root) "/mcp-script-" (System/nanoTime))
        path (str dir "/" prefix ".bb")]
    (fs/create-dirs dir)
    (spit path text)
    path))

(defn- parse-line
  [line]
  (try (json/parse-string line true) (catch Exception _ nil)))

(defn- format-value
  "Format an emit/return value for display (mirror the runtime's rules)."
  [v]
  (if (string? v)
    v
    (try (json/generate-string v {:pretty true})
         (catch Exception _ (pr-str v)))))

(defn- call-envelope
  "Execute one tools/call request (path = prefixed name) and build the
   worker envelope (pi callTool): {:ok true :data ...} or {:ok false
   :error {:code :message}}."
  [state-atom path args]
  (let [state @state-atom
        started-at (System/currentTimeMillis)
        match (proxy/find-tool-for-path state path)]
    (if (nil? match)
      {:ok false
       :error {:code "tool_not_found"
               :message (str "Tool \"" path "\" not found. Use (tools/search {:query \"...\"}) inside mcpScript.")}}
      (let [result (proxy/call-mcp-tool state (:server match) (:name match)
                                        (or args {})
                                        {})
            duration (- (System/currentTimeMillis) started-at)]
        (if (:is-error result)
          {:ok false
           :error {:code "call_failed"
                   :message (:content result)}
           :_duration duration}
          {:ok true
           :data (or (get-in result [:details :mcp-result]) (:content result))
           :_duration duration})))))

(defn- search-envelope
  [state-atom input]
  (let [state @state-atom
        input (or input {})
        query (if (string? (:query input)) (:query input) "")
        server (when (string? (:server input)) (:server input))
        limit (if (number? (:limit input)) (:limit input) 12)
        offset (if (number? (:offset input)) (:offset input) 0)]
    (if (str/blank? query)
      {:items [] :total 0 :has-more false :next-offset nil}
      (proxy/search-items state query server limit offset))))

(defn- describe-envelope
  [state-atom input]
  (let [state @state-atom
        path (if (and (map? input) (string? (:path input))) (:path input) "")]
    (proxy/describe-item state path)))

(defn- reader-loop
  "Read the child's stdout protocol lines; answers requests by writing
   result lines to STDIN-WRITER. Emits stream via ON-UPDATE and collects
   output blocks into OUTPUT-ATOM. Delivers {:ok :return|:error} to DONE."
  [state p stdin-writer on-update output-atom done calls-atom]
  (try
    (with-open [rdr (io/reader (:out p))]
      (doseq [line (line-seq rdr)]
        (let [msg (parse-line line)]
          (when (map? msg)
            (case (:type msg)
              "call" (let [envelope (call-envelope state (:path msg) (:args msg))]
                       (swap! calls-atom conj
                              {:operation "call" :path (:path msg)
                               :ok (:ok envelope)
                               :duration-ms (:_duration envelope)
                               :error (when-not (:ok envelope)
                                        (get-in envelope [:error :code]))})
                       (io/copy (str (json/generate-string
                                      {:type "result" :id (:id msg)
                                       :envelope (dissoc envelope :_duration)})
                                     "\n")
                                stdin-writer))
              "search" (io/copy (str (json/generate-string
                                      {:type "result" :id (:id msg)
                                       :envelope (search-envelope state (:input msg))})
                                     "\n")
                                stdin-writer)
              "describe" (io/copy (str (json/generate-string
                                        {:type "result" :id (:id msg)
                                         :envelope (describe-envelope state (:input msg))})
                                       "\n")
                                  stdin-writer)
              "emit" (let [text (format-value (:value msg))]
                       (swap! output-atom conj text)
                       (when on-update
                         (on-update {:content text :is-partial true})))
              "done" (do (deliver done {:ok true :return (:return msg)})
                         (reduced nil))
              "error" (do (deliver done {:ok false :error (:message msg)})
                          (reduced nil))
              nil)))))
    (catch Exception _ nil)
    (finally
      (when-not (realized? done)
        (deliver done {:ok false :error "mcpScript worker exited unexpectedly"})))))

(defn- drain-stderr
  "Background stderr reader: keep the last 20 lines for diagnostics."
  [err tail]
  (try
    (with-open [rdr (io/reader err)]
      (doseq [line (line-seq rdr)]
        (swap! tail (fn [lines]
                      (vec (take-last 20 (conj (vec lines) line)))))))
    (catch Exception _ nil)))

(defn run-script
  "Run CODE in the bb sandbox. OPTS: {:timeout-ms n (default 30000)
   :on-update (fn [partial])}. Returns the kmet tool result shape with
   details {:mode \"script\" :timeout-ms :error? :calls [...]} and the
   output guard applied."
  [state code & [opts]]
  (let [timeout-ms (or (:timeout-ms opts) default-timeout-ms)
        on-update (:on-update opts)
        bb (bb-binary)]
    (if (nil? bb)
      {:content "mcpScript requires the bb binary (set KMET_BB or add bb to PATH)."
       :is-error true}
      (let [runtime-path (write-temp-script "runtime" runtime-source)
            tools-path (write-temp-script "tools" tools-ns-source)
            console-path (write-temp-script "console" console-ns-source)
            code-path (write-temp-script "script" (str code))
            p (proc/process [bb runtime-path tools-path console-path code-path]
                            {:in :stream :out :stream :err :stream})
            pid (process/process-pid p)
            done (promise)
            output (atom [])
            calls (atom [])
            stderr-tail (atom [])]
        (when pid (process/track-pid! pid))
        (spawn #(reader-loop state p (:in p) on-update output done calls))
        (spawn #(drain-stderr (:err p) stderr-tail))
        (let [result (deref done timeout-ms ::timeout)
              guard-options (guard/resolve-options (:settings (:config @state)))
              finish (fn [text error-code]
                       (let [body (if (seq @output)
                                    (str (str/join "\n" @output)
                                         (when (and (seq text) (not (str/blank? text)))
                                           (str "\n" text)))
                                    (or text "(no output)"))
                             guarded (guard/guard-text body guard-options)]
                         {:content (:text guarded)
                          :is-error (boolean error-code)
                          :details (cond-> {:mode "script"
                                            :timeout-ms timeout-ms}
                                     error-code (assoc :error error-code)
                                     (seq @calls) (assoc :calls @calls)
                                     (:guard guarded) (assoc :output-guard (:guard guarded)))}))]
          (cond
            (= ::timeout result)
            (do
              (when pid (process/kill-process-tree! pid))
              (finish (str "mcpScript timed out after " timeout-ms "ms") "timeout"))

            (:ok result)
            (finish (:return result) nil)

            :else
            (do
              (when pid (process/kill-process-tree! pid))
              (finish (:error result) "script_error"))))))))
