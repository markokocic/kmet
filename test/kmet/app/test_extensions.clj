(ns kmet.app.test-extensions
  "Extension runtime tests: the init/shutdown contract, load/unload/reload
   lifecycle, per-extension deregistration, and the nullable api fixture
   (kmet.extension/create-nullable-api) for testing extensions in isolation."
  (:require [clojure.test :as t :refer [testing]]
            [clojure.string :as str]
            [babashka.fs :as fs]
            [kmet.extension :as ext]
            [kmet.app.extensions :as extensions]
            [kmet.app.commands :as commands]
            [kmet.app.event-bus :as event-bus]
            [kmet.app.session :as session]
            [kmet.app.tools.core :as tools]))

;; ─── Nullable api (extension tests in isolation) ──────────────────────────

(t/deftest test-nullable-api-captures-registrations
  (let [{:keys [api state]} (ext/create-nullable-api)]
    (ext/register-command! api {:name "nc" :description "d" :handler (fn [_ _] nil)})
    (ext/register-tool! api {:name "nt" :description "d" :execute (fn [_] {:content "x"})})
    (ext/on-event api :session-start (fn [_]))
    (ext/register-flag! api "nf" {:type :boolean})
    (ext/register-entry-renderer! api "er" (fn [_] {:role :info}))
    (ext/register-message-renderer! api "mr" (fn [_] {:role :info}))
    (ext/on-tool-call api (fn [_] nil))
    (t/is (contains? (:commands @state) "nc"))
    (t/is (contains? (:tools @state) "nt"))
    (t/is (= 1 (count (get-in @state [:handlers :session-start]))))
    (t/is (contains? (:flags @state) "nf"))
    (t/is (contains? (:entry-renderers @state) "er"))
    (t/is (contains? (:message-renderers @state) "mr"))
    (t/is (= 1 (count (:tool-call-hooks @state))))))

(t/deftest test-nullable-api-deregister-fns
  (let [{:keys [api state]} (ext/create-nullable-api)
        dereg-cmd (ext/register-command! api {:name "dc" :handler (fn [_ _] nil)})
        dereg-ev (ext/on-event api :agent-end (fn [_]))]
    (dereg-cmd)
    (dereg-ev)
    (t/is (not (contains? (:commands @state) "dc")))
    (t/is (empty? (get-in @state [:handlers :agent-end])))))

(t/deftest test-nullable-api-extension-init
  (testing "a sample extension's init registers what it declares"
    (let [{:keys [api state]} (ext/create-nullable-api)]
      (load-file "test/fixtures/ext-single/hello_ext.clj")
      (let [ns-sym (find-ns 'hello-ext)
            init-var (ns-resolve ns-sym 'init)]
        (init-var api)
        (t/is (contains? (:commands @state) "hello-ext"))
        (t/is (contains? (:tools @state) "hello-ext-tool"))
        (t/is (contains? (:flags @state) "ext-hello"))
        (t/is (= 1 (count (get-in @state [:handlers :session-start]))))
        (t/is (some #(= [:set-status "hello-ext" "loaded"] %) (:ui-calls @state))
              "ui calls captured"))
      (remove-ns 'hello-ext))))

;; ─── Load / unload / reload lifecycle (real runtime) ─────────────────────

(t/deftest test-load-single-file-extension
  (extensions/clear-extensions!)
  (let [result (extensions/load-extension! "test/fixtures/ext-single/hello_ext.clj")]
    (t/is (nil? (:error result)) (str "loaded: " (:error result)))
    (t/is (some #(= "hello_ext.clj" (:name %)) (extensions/get-loaded-extensions)))
    (testing "registrations live in the real registries"
      (t/is (some? (commands/find-command "hello-ext")))
      (t/is (some? (tools/get-tool "hello-ext-tool")))
      (t/is (some? (extensions/get-flag "ext-hello"))))
    (testing "unload removes everything"
      (extensions/unload-all-extensions!)
      (t/is (empty? (extensions/get-loaded-extensions)))
      (t/is (nil? (commands/find-command "hello-ext")))
      (t/is (nil? (tools/get-tool "hello-ext-tool")))
      (t/is (nil? (extensions/get-flag "ext-hello"))))))

(t/deftest test-load-manifest-extension
  (extensions/clear-extensions!)
  (let [result (extensions/load-extension! "test/fixtures/ext-dir")]
    (t/is (nil? (:error result)) (str "loaded: " (:error result)))
    (testing "multi-file: helper ns + entry ns load isolated; tool from helper works"
      (t/is (some? (tools/get-tool "multi-ext-tool")))
      (t/is (= "multi-ok" (:content (tools/execute-tool "multi-ext-tool" {}))))
      (t/is (nil? (find-ns 'multi-ext.main))
            "extension namespaces never enter the global registry")
      (t/is (nil? (find-ns 'multi-ext.helper))))
    (testing "unload removes the tool"
      (extensions/unload-all-extensions!)
      (t/is (nil? (tools/get-tool "multi-ext-tool")))
      (t/is (empty? (extensions/get-loaded-extensions))))))

(t/deftest ^:slow test-extension-lib-version-isolation
  (extensions/clear-extensions!)
  (let [ra (extensions/load-extension! "test/fixtures/ext-iso-a")
        rb (extensions/load-extension! "test/fixtures/ext-iso-b")]
    (t/is (nil? (:error ra)) (str "a: " (:error ra)))
    (t/is (nil? (:error rb)) (str "b: " (:error rb)))
    (testing "each extension serves its own declared version"
      (let [jars-a (extensions/extension-jars "iso-a")
            jars-b (extensions/extension-jars "iso-b")]
        (t/is (some #(str/includes? % "tools.cli-0.4.1.jar") jars-a))
        (t/is (some #(str/includes? % "tools.cli-1.0.206.jar") jars-b))
        (t/is (not-any? #(str/includes? % "tools.cli-1.0.206.jar") jars-a))
        (t/is (not-any? #(str/includes? % "tools.cli-0.4.1.jar") jars-b))))
    (testing "both extensions' tools work simultaneously"
      (t/is (= "iso-a" (:content (tools/execute-tool "iso-a" {}))))
      (t/is (= "iso-b" (:content (tools/execute-tool "iso-b" {})))))
    (testing "reload keeps each extension on its declared version"
      (extensions/unload-all-extensions!)
      (t/is (nil? (:error (extensions/load-extension! "test/fixtures/ext-iso-a"))))
      (t/is (nil? (:error (extensions/load-extension! "test/fixtures/ext-iso-b"))))
      (t/is (some #(str/includes? % "tools.cli-0.4.1.jar")
                  (extensions/extension-jars "iso-a")))
      (t/is (some #(str/includes? % "tools.cli-1.0.206.jar")
                  (extensions/extension-jars "iso-b")))
      (t/is (= "iso-a" (:content (tools/execute-tool "iso-a" {}))))
      (t/is (= "iso-b" (:content (tools/execute-tool "iso-b" {})))))
    (testing "unload releases both"
      (extensions/unload-all-extensions!)
      (t/is (empty? (extensions/get-loaded-extensions)))
      (t/is (nil? (tools/get-tool "iso-a")))
      (t/is (nil? (tools/get-tool "iso-b"))))))

(t/deftest test-load-failure-rolls-back
  (extensions/clear-extensions!)
  (testing "a file without init fails and leaves no trace"
    (let [dir "target/test-ext-bad"]
      (fs/create-dirs dir)
      (spit (str dir "/bad.clj")
            "(ns bad-ext)\n(defn not-init [api] nil)\n")
      (let [result (extensions/load-extension! (str dir "/bad.clj"))]
        (t/is (some? (:error result)))
        (t/is (empty? (extensions/get-loaded-extensions))))
      (fs/delete-tree dir))))

(t/deftest test-extension-context
  (testing "headless default context is complete and callable"
    (let [ctx (extensions/build-extension-context)]
      (t/is (= :print (:mode ctx)))
      (t/is (false? (:has-ui ctx)))
      (t/is (string? (:cwd ctx)))
      (t/is (nil? (:model ctx)))
      (t/is (= [] (:scoped-models ctx)))
      (t/is (true? ((:is-idle ctx))))
      (t/is (false? ((:has-pending-messages ctx))))
      (t/is (nil? ((:signal ctx))))
      (t/is (nil? ((:get-context-usage ctx))))
      (t/is (nil? ((:get-system-prompt ctx))))
      (t/is (nil? ((:wait-for-idle ctx))))
      (t/is (= {:cancelled true} ((:new-session ctx))))
      (t/is (= {:cancelled true} ((:fork ctx) "e1")))
      (t/is (= {:cancelled true} ((:navigate-tree ctx) "e1")))
      (t/is (= {:cancelled true} ((:switch-session ctx) "/x")))
      (t/is (false? ((:is-project-trusted ctx))))))
  (testing "event handlers receive (event ctx) — fixed arity-2 contract"
    (let [calls (atom [])
          w (extensions/wrap-event-handler
             (fn [ev ctx] (swap! calls conj [(:type ev) (:mode ctx)])))]
      (w {:type :agent-start})
      (t/is (= [[:agent-start :print]] @calls))
      (t/is (thrown? clojure.lang.ArityException
                     ((extensions/wrap-event-handler (fn [_] :legacy))
                      {:type :agent-end}))
            "arity-1 handlers fail fast — no legacy shim")))
  (testing "sci handlers receive the ctx through the real bus path"
    (extensions/clear-extensions!)
    (let [dir "target/test-ext-ctx-events"
          probes (atom [])
          unsub-a (event-bus/on-event :probe-a
                                      (fn [ev] (swap! probes conj [:a ev])))
          unsub-b (event-bus/on-event :probe-b
                                      (fn [ev] (swap! probes conj [:b (:mode ev)])))
          content (str "(ns ctx-events (:require [kmet.extension :as ext]))\n"
                       "(defn init [api]\n"
                       "  (ext/register-command! api {:name \"probe-cmd\"\n"
                       "                                :description \"pc\"\n"
                       "                                :handler (fn [c a] nil)})\n"
                       "  (ext/on-event api :session-start\n"
                       "    (fn [ev ctx] (ext/emit-event! api {:type :probe-a\n"
                       "                                        :from (:type ev)\n"
                       "                                        :cmds (ext/get-commands api)})))\n"
                       "  (ext/on-event api :session-tree\n"
                       "    (fn [ev ctx] (ext/emit-event! api {:type :probe-b :mode (:mode ctx)}))))\n")]
      (fs/create-dirs dir)
      (spit (str dir "/ext.clj") content)
      (let [result (extensions/load-extension! (str dir "/ext.clj"))]
        (t/is (nil? (:error result)) (str "loaded: " (:error result)))
        (event-bus/emit-event! {:type :session-start})
        (event-bus/emit-event! {:type :session-tree :new-leaf-id "x"})
        (let [[[_ probe-a] [_ mode-b]] @probes]
          (t/is (= :session-start (:from probe-a))
                "arity-2 handler received the event")
          (t/is (= :print mode-b) "arity-2 handler got the ctx")
          (testing "get-commands is sanitized — handlers stay private"
            (t/is (seq (:cmds probe-a)))
            (t/is (some #(= "probe-cmd" (:name %)) (:cmds probe-a))
                  "the extension's own command is visible")
            (t/is (every? #(and (string? (:name %)) (string? (:description %)))
                          (:cmds probe-a)))
            (t/is (every? #(not (contains? % :handler)) (:cmds probe-a)))
            (t/is (every? #(not (contains? % :extension-handler)) (:cmds probe-a))))))
      (unsub-a)
      (unsub-b)
      (fs/delete-tree dir))))
(testing "extension commands carry :extension-handler for ctx dispatch"
  (extensions/clear-extensions!)
  (let [dir "target/test-ext-ctx-cmd"]
    (fs/create-dirs dir)
    (spit (str dir "/ext.clj")
          (str "(ns ctx-cmd (:require [kmet.extension :as ext]))\n"
               "(defn init [api]\n"
               "  (ext/register-command! api\n"
               "    {:name \"ctx-test\" :description \"d\"\n"
               "     :handler (fn [ctx args] {:mode (:mode ctx) :args args})}))\n"))
    (let [result (extensions/load-extension! (str dir "/ext.clj"))]
      (t/is (nil? (:error result)) (str "loaded: " (:error result)))
      (let [c (commands/find-command "ctx-test")]
        (t/is (fn? (:extension-handler c)))
        (t/is (= {:mode :print :args "hi"}
                 ((:extension-handler c) (extensions/build-extension-context) "hi"))))
      (fs/delete-tree dir))))

(t/deftest test-extension-namespace-isolation
  (extensions/clear-extensions!)
  (let [load (fn [name content]
               (let [dir (str "target/test-ext-iso-" name)]
                 (fs/create-dirs dir)
                 (spit (str dir "/ext.clj") content)
                 (let [result (extensions/load-extension! (str dir "/ext.clj"))]
                   (fs/delete-tree dir)
                   result)))]
    (testing "kmet.app.* requires are rejected with an actionable error"
      (let [result (load "app" "(ns bad-app (:require [kmet.app.commands :as c]))\n(defn init [api] nil)\n")]
        (t/is (some? (:error result)))
        (t/is (str/includes? (:error result)
                             "may depend only on kmet.extension and kmet.tui.*"))))
    (testing "a kmet.tui.* typo is rejected (sci would NPE silently)"
      (let [result (load "typo" "(ns bad-typo (:require [kmet.tui.theem :as t]))\n(defn init [api] nil)\n")]
        (t/is (some? (:error result)))
        (t/is (str/includes? (:error result)
                             "not part of the kmet.tui.* library shared with extensions"))))
    (testing "valid kmet.tui.* requires load and share the real library"
      (let [result (load "tui" "(ns good-tui\n  (:require [kmet.tui.components.text :as text]\n            [kmet.tui.protocols :as protocols]))\n(defn init [api]\n  (let [c (text/make-text \"hi\")]\n    (when-not (vector? (protocols/render c 20))\n      (throw (ex-info \"render failed\" {})))))\n")]
        (t/is (nil? (:error result)) (str "loaded: " (:error result)))))))

(t/deftest ^:slow test-extension-bad-deps-fails-load
  (extensions/clear-extensions!)
  (let [dir "target/test-ext-bad-deps"]
    (fs/create-dirs dir)
    (spit (str dir "/extension.edn") "{:name \"bad-deps\" :entry \"src/main.clj\"}\n")
    (spit (str dir "/deps.edn") "{:deps {org.clojure/does-not-exist {:mvn/version \"9.9.9\"}}}\n")
    (fs/create-dirs (str dir "/src"))
    (spit (str dir "/src/main.clj")
          "(ns bad-deps.main (:require [org.clojure.does-not-exist :as bad]))\n(defn init [api] nil)\n")
    (testing "an unresolvable dep fails the load without killing the process"
      (let [result (extensions/load-extension! dir)]
        (t/is (some? (:error result)))
        (t/is (empty? (extensions/get-loaded-extensions)))))
    (fs/delete-tree dir)))

(t/deftest test-reload-extensions
  (testing "reload-extensions! (container dirs) unloads + reloads"
    (let [container (str "target/test-ext-container-" (System/currentTimeMillis))]
      (fs/create-dirs container)
      (fs/copy-tree "test/fixtures/ext-single" container)
      (fs/copy-tree "test/fixtures/ext-dir" (str container "/multi-ext"))
      (extensions/clear-extensions!)
      (let [results (extensions/reload-extensions! [container])]
        (t/is (= 2 (count (filter #(nil? (:error %)) results)))
              (str "both loaded: " (pr-str results)))
        (t/is (some? (commands/find-command "hello-ext")))
        (t/is (some? (tools/get-tool "multi-ext-tool")))
        (testing "reload again: old state fully replaced, no duplicates"
          (extensions/reload-extensions! [container])
          (t/is (= 1 (count (filter #(= "hello_ext.clj" (:name %))
                                    (extensions/get-loaded-extensions))))
                "no duplicate extensions after reload")
          (t/is (some? (commands/find-command "hello-ext")))))
      (extensions/unload-all-extensions!)
      (fs/delete-tree container))))

;; ─── Session facades through the api ──────────────────────────────────────

(t/deftest test-session-api-facades
  (let [sess (session/create-session (str "target/test-ext-sess-" (System/currentTimeMillis)))]
    (extensions/set-session! sess)
    (try
      (let [id (extensions/append-custom-entry! "st" {:n 1})]
        (t/is (some? id)))
      (finally
        (extensions/set-session! nil)))))
