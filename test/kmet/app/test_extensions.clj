(ns kmet.app.test-extensions
  "Extension runtime tests: the init/shutdown contract, load/unload/reload
   lifecycle, per-extension deregistration, and the nullable api fixture
   (kmet.extension/create-nullable-api) for testing extensions in isolation."
  (:require [clojure.test :as t :refer [testing]]
            [babashka.fs :as fs]
            [kmet.extension :as ext]
            [kmet.app.extensions :as extensions]
            [kmet.app.commands :as commands]
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
      (load-file "target/test-ext-single/hello_ext.clj")
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
  (let [result (extensions/load-extension! "target/test-ext-single/hello_ext.clj")]
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
      (t/is (nil? (extensions/get-flag "ext-hello"))))
    (remove-ns 'hello-ext)))

(t/deftest test-load-manifest-extension
  (extensions/clear-extensions!)
  (let [result (extensions/load-extension! "target/test-ext-dir")]
    (t/is (nil? (:error result)) (str "loaded: " (:error result)))
    (testing "multi-file: helper ns + entry ns both load; tool from helper"
      (t/is (some? (tools/get-tool "multi-ext-tool")))
      (t/is (= "multi-ok" (:content (tools/execute-tool "multi-ext-tool" {})))))
    (testing "unload removes the tool and the namespaces"
      (extensions/unload-all-extensions!)
      (t/is (nil? (tools/get-tool "multi-ext-tool")))
      (t/is (nil? (find-ns 'multi-ext.main)))
      (t/is (nil? (find-ns 'multi-ext.helper))))))

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
      (remove-ns 'bad-ext)
      (fs/delete-tree dir))))

(t/deftest test-reload-extensions
  (testing "reload-extensions! (container dirs) unloads + reloads"
    (let [container (str "target/test-ext-container-" (System/currentTimeMillis))]
      (fs/create-dirs container)
      (fs/copy-tree "target/test-ext-single" container)
      (fs/copy-tree "target/test-ext-dir" (str container "/multi-ext"))
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
      (fs/delete-tree container)
      (remove-ns 'hello-ext)
      (remove-ns 'multi-ext.main)
      (remove-ns 'multi-ext.helper))))

;; ─── Session facades through the api ──────────────────────────────────────

(t/deftest test-session-api-facades
  (let [sess (session/create-session (str "target/test-ext-sess-" (System/currentTimeMillis)))]
    (extensions/set-session! sess)
    (try
      (let [id (extensions/append-custom-entry! "st" {:n 1})]
        (t/is (some? id)))
      (finally
        (extensions/set-session! nil)))))
