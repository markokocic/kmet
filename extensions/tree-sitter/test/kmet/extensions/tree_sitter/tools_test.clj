(ns kmet.extensions.tree-sitter.tools-test
  "Tool-level tests: param validation and error mapping run without the
   CLI; the integration test exercises the real five tools end-to-end only
   when the tree-sitter cache is already populated (never downloads)."
  (:require [babashka.fs :as fs]
            [clojure.test :refer [deftest is testing]]
            [kmet.extensions.tree-sitter.grammars :as grammars]
            [kmet.extensions.tree-sitter.paths :as paths]
            [kmet.extensions.tree-sitter.symbols :as symbols]
            [kmet.extensions.tree-sitter.test-util :as tu]
            [kmet.extensions.tree-sitter.tools :as tools]))

;; ─── validation & error mapping (no CLI needed) ───────────────────────────

;; The never-throw-wrapped execute fn as registered.
(defn- wrapped [name]
  (:execute (some #(when (= name (:name %)) %) (tools/tool-defs))))

(deftest list-symbols-validation-test
  (testing "missing path"
    (let [r ((wrapped "list_symbols") {})]
      (is (:is-error r))
      (is (re-find #"Missing required parameter" (:content r)))))
  (testing "nonexistent file"
    (let [r ((wrapped "list_symbols") {:path "/no/such/file.clj"})]
      (is (:is-error r))
      (is (re-find #"File not found" (:content r))))))

(deftest unsupported-extension-test
  ;; .rs has no language table entry -> clean error result, no exception
  (let [dir (tu/temp-dir! "ts-tools")
        f (fs/path dir "code.rs")]
    (spit (str f) "fn main() {}")
    (let [r ((wrapped "list_symbols") {:path (str f)})]
      (is (:is-error r))
      (is (re-find #"no tree-sitter grammar configured" (:content r))))))

(deftest never-throw-test
  ;; an infrastructure blow-up surfaces as a normal error result
  (with-redefs [symbols/analyze-file! (fn [& _] (throw (ex-info "boom" {})))
                grammars/resolve-lang (constantly "clojure")
                ;; provisioning must stay stubbed: the real ensure-grammar!
                ;; downloads the CLI into the user cache on a cold machine
                grammars/ensure-grammar! (constantly {:lang "clojure" :status :cached})]
    (let [dir (tu/temp-dir! "ts-throw")
          f (fs/path dir "x.clj")]
      (spit (str f) "(def x 1)")
      ;; go through the registered execute fn — that's where the never-throw
      ;; wrapper lives
      (let [execute (some #(when (= "list_symbols" (:name %)) (:execute %))
                          (tools/tool-defs))
            r (execute {:path (str f)})]
        (is (:is-error r))
        (is (re-find #"tree-sitter: boom" (:content r)))))))

;; ─── integration against the real CLI (guarded, never downloads) ──────────

(defn- cache-ready? []
  (and (fs/exists? (paths/bin-path nil))
       (fs/exists? (grammars/wasm-path "clojure" nil))))

(when-not (cache-ready?)
  (println "[tools-test] tree-sitter cache incomplete — skipping CLI "
           "integration test"))

(defn- project-fixture!
  [root]
  (spit (str (fs/path root "lib.clj"))
        "(ns lib)\n\n(defn helper [x]\n  (inc x))\n")
  (spit (str (fs/path root "app.clj"))
        "(ns app)\n\n(defn user [n]\n  (helper n))\n"))

(deftest ^:integration five-tools-end-to-end-test
  (when (cache-ready?)
    (let [root (tu/temp-dir! "ts-tools-it")
          lib (str (fs/path root "lib.clj"))
          app (str (fs/path root "app.clj"))]
      (try
        (project-fixture! root)
        (testing "list_symbols"
          (let [r (tools/list-symbols* {:path lib})]
            (is (not (:is-error r)))
            (is (= 1 (:count (:details r))))
            (is (re-find #"function helper \(line 3\)" (:content r)))))
        (testing "find_definition"
          (let [r (tools/find-definition* {:symbol "helper" :root root})]
            (is (not (:is-error r)))
            (is (re-find #"lib\.clj:\d+ — function helper" (:content r)))))
        (testing "get_symbol_body"
          (let [r (tools/get-symbol-body* {:path lib :symbol "helper"})]
            (is (not (:is-error r)))
            (is (= 2 (:line-count (:details r))))
            (is (re-find #"\(inc x\)" (:body (:details r))))))
        (testing "find_callers"
          (let [r (tools/find-callers* {:symbol "helper" :root root})]
            (is (not (:is-error r)))
            (is (re-find #"(?s)user \(" (:content r)))))
        (testing "find_callees"
          (let [r (tools/find-callees* {:path app :symbol "user"})]
            (is (not (:is-error r)))
            (is (re-find #"helper \(" (:content r)))))
        (finally
          (fs/delete-tree root))))))
