(ns kmet.app.test-extensions
  "Extension runtime tests: the init/shutdown contract, load/unload/reload
   lifecycle, per-extension deregistration, and the nullable api fixture
   (kmet.extension/create-nullable-api) for testing extensions in isolation."
  (:require [clojure.test :as t :refer [testing]]
            [clojure.string :as str]
            [babashka.fs :as fs]
            [kmet.extension :as ext]
            [kmet.ai.models :as models]
            [kmet.app.extensions :as extensions]
            [kmet.app.commands :as commands]
            [kmet.app.event-bus :as event-bus]
            [kmet.app.prompts :as prompts]
            [kmet.app.session :as session]
            [kmet.app.skills :as skills]
            [kmet.app.tools.core :as tools]
            [kmet.ai.hooks :as ai-hooks]
            [kmet.tui.theme :as theme]))

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

(t/deftest test-nullable-api-p2-capabilities
  (let [{:keys [api state]} (ext/create-nullable-api)]
    (testing "ui-custom opts pass through untouched (pi: ctx.ui.custom options)"
      (let [factory (fn [_ _ _ _] nil)
            opts {:overlay true :overlay-options {:anchor :center :width 82}}]
        (ext/ui-custom api factory opts)
        (t/is (some (fn [[k f o]] (and (= k :custom) (identical? f factory) (= o opts)))
                    (:ui-calls @state)))))
    (testing "models register/unregister are captured (pi: ctx.registerProvider)"
      (ext/models-register-provider! api :ext-prov {:models [{:id "m"}]})
      (ext/models-unregister-provider! api :ext-prov)
      (t/is (some #(= [:register-provider! :ext-prov {:models [{:id "m"}]}] %)
                  (:model-calls @state)))
      (t/is (some #(= [:unregister-provider! :ext-prov] %) (:model-calls @state))))))

(t/deftest test-theme-lookup-shared-directly
  (testing "theme lookups come from the shared kmet.tui.theme layer, not the
            api — extensions use them directly"
    (t/is (= "dark" (:name (theme/get-theme "dark"))))
    (t/is (= "dark" (:name (theme/get-theme "does-not-exist")))
          "get-theme falls back to dark (pi getTheme)")
    (t/is (nil? (theme/get-theme-by-name "does-not-exist")))
    (t/is (map? (theme/get-all-themes)))))

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

;; ─── Provider registration through the api (:models facades) ─────────────

(t/deftest test-extension-provider-registration
  (testing "api :models register-provider! / unregister-provider! (pi ctx.registerProvider)"
    (let [api ((var extensions/create-extension-api)
               {:name "prov-test" :path "target/test-ext-prov.clj"})]
      (models/load-catalogs!)
      (models/clear-extension-providers!)
      (try
        (t/is (not (some #(= "ext-1" (:id %)) (extensions/get-all-models))))
        (let [config {:base-url "https://ext.example/v1" :api :openai-completions
                      :api-key "sk-ext" :auth-header true
                      :models [{:id "ext-1" :reasoning true}]}
              _ ((:register-provider! (:models api)) :ext-prov config)]
          (t/is (= "ext-prov" (:name (models/get-provider :ext-prov)))
                "name defaults to provider id")
          (t/is (some #(= "ext-1" (:id %)) (extensions/get-all-models))
                "registered model appears in get-all")
          (t/is (= config (extensions/get-registered-provider-config :ext-prov)))
          (testing "unregister drops the extension provider"
            ((:unregister-provider! (:models api)) :ext-prov)
            (t/is (not (some #(= "ext-1" (:id %)) (extensions/get-all-models))))
            (t/is (nil? (extensions/get-registered-provider-config :ext-prov)))))
        (testing "broken config throws without touching stored state"
          (t/is (thrown? Exception
                         ((:register-provider! (:models api)) :ext-prov
                                                              {:models [{:id "bad"}]})))
          (t/is (nil? (extensions/get-registered-provider-config :ext-prov))))
        (testing "oauth block registers an OAuthAuth on the composed provider"
          (let [login (fn [_] {:type :oauth :access "a" :refresh "r" :expires 1})
                to-auth (fn [c] {:api-key (str "Bearer " (:access c))})
                config {:base-url "https://ext.example/v1" :api :openai-completions
                        :oauth {:name "Ext SSO" :is-subscription? true
                                :login login :refresh-token (fn [c _] c) :to-auth to-auth}
                        :models [{:id "ext-1"}]}
                _ ((:register-provider! (:models api)) :ext-oauth config)
                p (models/get-provider :ext-oauth)]
            (t/is (instance? kmet.ai.oauth.OAuthAuth (:oauth p)))
            (t/is (= "Ext SSO" (:name (:oauth p))))
            (t/is (true? (:is-subscription? (:oauth p))))
            (t/is (= login (:login (:oauth p))))
            (t/is (= "Bearer a" (get-in ((:to-auth (:oauth p)) {:type :oauth :access "a"})
                                        [:api-key])))
            (t/is (= "a" (get-in ((:refresh (:oauth p)) {:type :oauth :access "a"} nil)
                                 [:access])))
            ((:unregister-provider! (:models api)) :ext-oauth)
            (t/is (nil? (:oauth (models/get-provider :ext-oauth))))))
        (testing "a broken oauth block throws at register time (pi validateExtensionProvider)"
          (t/is (thrown-with-msg? Exception #"requires :login and :to-auth"
                                  ((:register-provider! (:models api)) :ext-oauth
                                                                       {:base-url "https://ext.example/v1"
                                                                        :api :openai-completions
                                                                        :models [{:id "ext-1"}]
                                                                        :oauth {:name "broken"}}))))
        (finally
          (models/clear-extension-providers!))))))

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

(t/deftest test-unload-extension-nil-noop
  (extensions/clear-extensions!)
  ;; the load result map carries :extension (the name), not the Extension
  ;; record — passing nil to unload-extension! used to throw a cryptic
  ;; (deref nil) NPE; it must be a silent no-op
  (t/is (nil? (extensions/unload-extension! nil)))
  (let [result (extensions/load-extension! "test/fixtures/ext-single/hello_ext.clj")]
    (t/is (nil? (:error result)))
    (testing "unload still works on the real record"
      (extensions/unload-all-extensions!)
      (t/is (empty? (extensions/get-loaded-extensions))))))

(t/deftest test-load-manifest-extension-via-symlink
  ;; extension dirs are commonly installed as symlinks into a repo checkout;
  ;; the ns-file scan must follow the link or every own-file require fails
  ;; (skipped on Windows: creating symlinks needs SeCreateSymbolicLinkPrivilege)
  (when-not (fs/windows?)
    (extensions/clear-extensions!)
    (let [link "target/ext-dir-link"]
      (fs/create-dirs "target")
      (fs/delete-if-exists link)
      (fs/create-sym-link link (fs/absolutize "test/fixtures/ext-dir"))
      (try
        (let [result (extensions/load-extension! link)]
          (t/is (nil? (:error result)) (str "loaded: " (:error result)))
          (testing "multi-file extension loads through a symlinked dir"
            (t/is (= "multi-ok" (:content (tools/execute-tool "multi-ext-tool" {}))))))
        (finally
          (extensions/unload-all-extensions!)
          (fs/delete-if-exists link))))))

(t/deftest test-extension-context-has-slurp-spit
  ;; slurp/spit are absent from SCI's builtin clojure.core; the context
  ;; injects the host fns (see build-context-namespaces), so extensions
  ;; can read/write files without babashka.fs workarounds
  (extensions/clear-extensions!)
  (let [dir "target/test-ext-slurp"]
    (fs/delete-tree dir)
    (fs/create-dirs (str dir "/src"))
    (spit (str dir "/extension.edn") "{:name \"slurp-ext\" :entry \"src/main.clj\"}\n")
    (spit (str dir "/src/main.clj")
          (str "(ns slurp-ext.main\n  (:require [kmet.extension :as ext]))\n"
               "(defn init [api]\n"
               "  (ext/register-tool! api {:name \"slurp-tool\"\n"
               "                           :description \"reads/writes via slurp/spit\"\n"
               "                           :execute (fn [_]\n"
               "                                      (spit \"target/slurp-ext-out.txt\" \"written\")\n"
               "                                      {:content (slurp \"target/slurp-ext-in.txt\")})}))\n"))
    (spit "target/slurp-ext-in.txt" "input")
    (try
      (let [result (extensions/load-extension! dir)]
        (t/is (nil? (:error result)) (str "loaded: " (:error result)))
        (t/is (= "input" (:content (tools/execute-tool "slurp-tool" {}))))
        (t/is (= "written" (slurp "target/slurp-ext-out.txt"))))
      (finally
        (extensions/unload-all-extensions!)
        (fs/delete-tree dir)
        (fs/delete-if-exists "target/slurp-ext-out.txt")
        (fs/delete-if-exists "target/slurp-ext-in.txt")))))

(t/deftest test-extension-gets-bundled-tools-reader-port
  ;; bb's tools.reader is a reduced custom port; the Maven copies have
  ;; deftypes implementing java.io.Closeable and fail under SCI. The
  ;; context injects the port by reference (bundled-port-namespaces), so
  ;; extensions can require clojure.tools.reader* without deps.edn pins
  (extensions/clear-extensions!)
  (let [dir "target/test-ext-tools-reader"]
    (fs/delete-tree dir)
    (fs/create-dirs (str dir "/src"))
    (spit (str dir "/extension.edn") "{:name \"tr-ext\" :entry \"src/main.clj\"}\n")
    (spit (str dir "/src/main.clj")
          (str "(ns tr-ext.main\n  (:require [kmet.extension :as ext]\n            [clojure.tools.reader :as r]\n            [clojure.tools.reader.reader-types :as rt]))\n"
               "(defn init [api]\n"
               "  (ext/register-tool! api {:name \"tr-tool\"\n"
               "                           :description \"uses the bundled tools.reader port\"\n"
               "                           :execute (fn [_]\n"
               "                                      {:content (str (rt/read-char (rt/string-push-back-reader \"hi\"))\n"
               "                                                     (r/read-string \"(1 2 3)\"))})}))\n"))
    (try
      (let [result (extensions/load-extension! dir)]
        (t/is (nil? (:error result)) (str "loaded: " (:error result)))
        (t/is (= "h(1 2 3)" (:content (tools/execute-tool "tr-tool" {})))))
      (finally
        (extensions/unload-all-extensions!)
        (fs/delete-tree dir)))))

(t/deftest test-load-missing-require-error
  ;; a require the loader cannot serve must surface an actionable message,
  ;; not a bare NullPointerException (the load-fn used to call a nil
  ;; deps-resolver when the extension had no deps.edn)
  (extensions/clear-extensions!)
  (let [dir "target/test-ext-missing-req"]
    (fs/delete-tree dir)
    (fs/create-dirs (str dir "/src"))
    (spit (str dir "/extension.edn") "{:name \"missing-req\" :entry \"src/main.clj\"}\n")
    (spit (str dir "/src/main.clj")
          "(ns missing-req.main\n  (:require [no.such.namespace]))\n(defn init [api] nil)\n")
    (let [result (extensions/load-extension! dir)]
      (t/is (str/includes? (:error result) "no.such.namespace")
            (str "actionable error, got: " (:error result)))
      (t/is (empty? (extensions/get-loaded-extensions))))
    (fs/delete-tree dir)))

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
      (t/is (false? ((:is-project-trusted ctx))))
      (t/is (nil? ((:get-name (:session ctx))))
            "ctx carries the read-only session facade (pi: ctx.sessionManager)")))
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
                             "may depend only on kmet.extension, kmet.tui.* and kmet.libs.*"))))
    (testing "a kmet.tui.* typo is rejected (sci would NPE silently)"
      (let [result (load "typo" "(ns bad-typo (:require [kmet.tui.theem :as t]))\n(defn init [api] nil)\n")]
        (t/is (some? (:error result)))
        (t/is (str/includes? (:error result)
                             "not part of the kmet.tui.* library shared with extensions"))))
    (testing "a kmet.libs.* typo is rejected (sci would NPE silently)"
      (let [result (load "lib-typo" "(ns bad-lib-typo (:require [kmet.libs.hashh :as h]))\n(defn init [api] nil)\n")]
        (t/is (some? (:error result)))
        (t/is (str/includes? (:error result)
                             "not part of the kmet.libs.* library shared with extensions"))))
    (testing "valid kmet.tui.* requires load and share the real library"
      (let [result (load "tui" "(ns good-tui\n  (:require [kmet.tui.components.text :as text]\n            [kmet.tui.protocols :as protocols]))\n(defn init [api]\n  (let [c (text/make-text \"hi\")]\n    (when-not (vector? (protocols/render c 20))\n      (throw (ex-info \"render failed\" {})))))\n")]
        (t/is (nil? (:error result)) (str "loaded: " (:error result)))))
    (testing "valid kmet.libs.* requires load and share the real library"
      (let [result (load "lib" "(ns good-lib\n  (:require [kmet.libs.hash :as hash]\n            [kmet.libs.yaml-lite :as yaml]))\n(defn init [api]\n  (let [s (hash/short-hash \"hi\")]\n    (when-not (string? s)\n      (throw (ex-info \"hash failed\" {})))))\n")]
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
  (let [dir (str "target/test-ext-sess-" (System/currentTimeMillis))
        sess (session/create-session dir)]
    (extensions/set-session! sess)
    (try
      (let [id (extensions/append-custom-entry! "st" {:n 1})]
        (t/is (some? id)))
      (finally
        (extensions/set-session! nil)
        (fs/delete-tree dir)))))

(t/deftest test-markdown-transformers
  (testing "applied in registration order, last wins per position"
    (extensions/clear-extensions!)
    (let [d1 (extensions/register-markdown-transformer!
              (fn [md _ctx] (str "a[" md "]")))
          d2 (extensions/register-markdown-transformer!
              (fn [md _ctx] (str "b[" md "]")))]
      (t/is (= "b[a[hi]]"
               (extensions/apply-markdown-transformers
                "hi" {:message-type :user :is-streaming false :available-width 80}))
            "transformers chain in registration order (pi: applyMarkdownTransformers)")
      (d1)
      (t/is (= "b[hi]" (extensions/apply-markdown-transformers "hi" {}))
            "dereg removes exactly its own transformer")
      (d2)
      (t/is (= "hi" (extensions/apply-markdown-transformers "hi" {})))))
  (testing "a throwing transformer is skipped, the chain continues"
    (extensions/clear-extensions!)
    (let [d1 (extensions/register-markdown-transformer!
              (fn [_md _ctx] (throw (ex-info "boom" {}))))
          d2 (extensions/register-markdown-transformer!
              (fn [md _ctx] (str "ok[" md "]")))]
      (t/is (= "ok[hi]" (extensions/apply-markdown-transformers "hi" {}))
            "pi: keep the current markdown and continue")
      (d1) (d2)))
  (testing "non-string results are ignored"
    (extensions/clear-extensions!)
    (let [d1 (extensions/register-markdown-transformer!
              (fn [_md _ctx] {:not :a-string}))
          d2 (extensions/register-markdown-transformer!
              (fn [md _ctx] (str md "!")))]
      (t/is (= "hi!" (extensions/apply-markdown-transformers "hi" {})))
      (d1) (d2))))

(t/deftest test-register-shortcut-headless
  (testing "headless register-shortcut! returns a no-op dereg fn"
    (let [dereg (extensions/register-shortcut! "ctrl+alt+x"
                                               {:description "d"
                                                :handler (fn [_] nil)})]
      (t/is (fn? dereg))
      (dereg) ;; must not throw headless
      (dereg))))

(t/deftest test-send-message-headless
  (testing "headless send-message! persists a custom message to the live session"
    (let [dir (str "target/test-ext-send-" (System/currentTimeMillis))
          sess (session/create-session dir)]
      (extensions/set-session! sess)
      (try
        (extensions/send-message! {:custom-type :note :content "hello"
                                   :display true :details {:x 1}})
        (let [entries (session/get-branch sess)
              e (last entries)]
          (t/is (= :custom-message (:role e)))
          (t/is (= :note (:custom-type e)))
          (t/is (= "hello" (:content e)))
          (t/is (= {:x 1} (:details e)))
          (t/is (= [{:role :custom
                     :custom-type :note
                     :content [{:type :text :text "hello"}]
                     :display true
                     :details {:x 1}}]
                   (session/context-messages e))
                "the entry projects into LLM context as a custom (user) message"))
        (finally
          (extensions/set-session! nil)
          (fs/delete-tree dir))))))

(t/deftest test-provider-event-bridges
  (testing "bus events drive the ai-layer hooks; last non-nil handler result wins"
    (let [u1 (event-bus/on-event :context
                                 (fn [_ev] {:messages ["first"]}))
          u2 (event-bus/on-event :context
                                 (fn [_ev] nil)) ;; nil result — first wins
          u3 (event-bus/on-event :context
                                 (fn [_ev] {:messages ["second"]}))]
      (t/is (= ["second"] (ai-hooks/apply-context-hook ["orig"]))
            "last non-nil handler result wins (pi chains)")
      (u1) (u2) (u3))
    (testing "a throwing handler is skipped"
      (let [u1 (event-bus/on-event :context
                                   (fn [_ev] (throw (ex-info "boom" {}))))
            u2 (event-bus/on-event :context
                                   (fn [_ev] {:messages ["kept"]}))]
        (t/is (= ["kept"] (ai-hooks/apply-context-hook ["orig"])))
        (u1) (u2))
      (t/is (= ["orig"] (ai-hooks/apply-context-hook ["orig"]))
            "no handlers — passthrough"))
    (testing "before-provider-headers: the returned map replaces the headers"
      (let [u1 (event-bus/on-event :before-provider-headers
                                   (fn [_ev] {"x-api-key" "k"}))]
        (t/is (= {"x-api-key" "k"}
                 (ai-hooks/apply-before-provider-headers-hook {"a" "b"})))
        (u1))
      (t/is (= {"a" "b"} (ai-hooks/apply-before-provider-headers-hook {"a" "b"}))))
    (testing "before-provider-request: the last non-nil result replaces the payload"
      (let [u1 (event-bus/on-event :before-provider-request
                                   (fn [_ev] {:payload :replaced}))]
        (t/is (= {:payload :replaced}
                 (ai-hooks/apply-before-provider-request-hook {:payload :orig}))
              "the bridge returns the handler's replacement payload")
        (u1))
      (t/is (= {:payload :orig} (ai-hooks/apply-before-provider-request-hook {:payload :orig}))))
    (testing "after-provider-response fires with status/headers"
      (let [seen (atom nil)
            u1 (event-bus/on-event :after-provider-response
                                   (fn [ev] (reset! seen ev)))]
        (ai-hooks/apply-after-provider-response-hook 200 {"h" "v"})
        (t/is (= 200 (:status @seen)))
        (t/is (= {"h" "v"} (:headers @seen)))
        (u1)))))

(t/deftest test-resources-discover
  (extensions/clear-extensions!)
  (event-bus/clear-event-listeners!)
  (let [dir (str "target/test-ext-resources-" (System/currentTimeMillis))
        skill-dir (str dir "/skill")
        prompt-dir (str dir "/prompt")
        theme-dir (str dir "/theme")
        colors (into {} (map (fn [k] [(name k) "#000000"])
                             (concat theme/FG-TOKENS theme/BG-TOKENS)))
        unsubs (atom [])
        seen-reasons (atom [])]
    (fs/create-dirs skill-dir)
    (fs/create-dirs prompt-dir)
    (fs/create-dirs theme-dir)
    (spit (str skill-dir "/SKILL.md")
          "---\nname: ext-skill\ndescription: From an extension\n---\nSkill body")
    (spit (str prompt-dir "/ext-prompt.md") "Prompt body from an extension")
    (spit (str theme-dir "/ext-theme.edn")
          (pr-str {:name "ext-theme" :colors colors}))
    (try
      (testing "handler results are all collected and applied (pi: emitResourcesDiscover)"
        (swap! unsubs conj
               (event-bus/on-event :resources-discover
                                   (fn [ev]
                                     (swap! seen-reasons conj (:reason ev))
                                     (t/is (string? (:cwd ev)))
                                     {:skill-paths [skill-dir]
                                      :prompt-paths [prompt-dir]
                                      :theme-paths [theme-dir]})))
        (let [result (extensions/discover-resources! :startup)]
          (t/is (some? (skills/get-skill "ext-skill"))
                "contributed skill dir loads into the skills registry")
          (t/is (some? (prompts/get-prompt-template "ext-prompt"))
                "contributed prompt dir loads into the prompts registry")
          (t/is (some? (theme/get-theme "ext-theme"))
                "contributed theme dir loads into the theme store")
          (t/is (= {:skill-paths [skill-dir]
                    :prompt-paths [prompt-dir]
                    :theme-paths [theme-dir]}
                   result)
                "the collected path lists are returned")))
      (testing "re-discovery after session-start dedups (pi: mergePaths)"
        (extensions/discover-resources! :startup)
        (t/is (= 1 (count (filter #(= "ext-prompt" (:name %))
                                  (prompts/get-prompt-templates))))
              "the prompt is not loaded twice"))
      (testing "a throwing handler is skipped, others still contribute"
        (swap! unsubs conj
               (event-bus/on-event :resources-discover
                                   (fn [_] (throw (ex-info "boom" {})))))
        (extensions/discover-resources! :reload)
        (t/is (some? (skills/get-skill "ext-skill"))
              "the good handler's paths were already applied")
        (t/is (= [:startup :startup :reload] @seen-reasons)
              "every discovery carried its reason (:startup | :reload)"))
      (finally
        (doseq [u @unsubs] (u))
        (event-bus/clear-event-listeners!)
        (extensions/clear-extensions!)
        (skills/clear-skills!)
        (prompts/clear-prompt-templates!)
        (fs/delete-tree dir)))))
