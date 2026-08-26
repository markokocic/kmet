(ns kmet.extensions.tree-sitter.grammars-test
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [are deftest is testing]]
            [kmet.extensions.tree-sitter.fetch :as fetch]
            [kmet.extensions.tree-sitter.cli :as cli]
            [kmet.extensions.tree-sitter.grammars :as g]
            [kmet.extensions.tree-sitter.paths :as paths]
            [kmet.extensions.tree-sitter.test-util :as tu]))

(def ^:private fixture-wasm-bytes "fixture-wasm-bytes-v1")

(def ^:private fixture-sha
  ;; sha256 of the fixture blob, computed once per JVM via a scratch file
  (let [tmp (tu/temp-dir! "ts-g-fix")
        p (fs/path tmp "blob")]
    (spit (str p) fixture-wasm-bytes)
    (fetch/sha256 p)))

(def ^:private fixture-table
  {"testlang"
   {:source :direct
    :version "9.9.9"
    :url "https://example.invalid/testlang.wasm"
    :sha256 fixture-sha
    :file-types ["tl" ".TL"]
    :probe "probe-source"}})

(deftest languages-table-integrity-test
  (let [langs (g/languages)]
    (testing "launch set pinned: clojure, python, typescript, tsx"
      (is (= #{"clojure" "python" "typescript" "tsx"} (set (keys langs)))))
    (doseq [[lang entry] langs]
      (testing (str lang)
        (is (#{"zed" "direct"} (name (:source entry))) lang)
        (is (re-matches #"[0-9a-f]{64}" (:sha256 entry)) lang)
        (is (seq (:file-types entry)) lang)
        (is (not (str/blank? (str (:probe entry)))) lang)
        (case (:source entry)
          :zed (do (is (string? (:id entry)) lang)
                   (is (re-matches #"\d+\.\d+\.\d+" (:version entry)) lang))
          :direct (do (is (re-find #"^https://" (:url entry)) lang)
                      (is (re-matches #"\d+\.\d+\.\d+" (:version entry)) lang)))))))

(deftest resolve-lang-test
  (testing "manifest-backed routing"
    (are [ext lang] (= lang (g/resolve-lang ext))
      "py" "python"
      ".PY" "python"
      "ts" "typescript"
      "tsx" "tsx"
      "clj" "clojure"
      "cljs" "clojure"
      "edn" "clojure"
      "rs" nil
      "" nil
      nil nil))
  (testing "overridden table"
    (is (= "testlang" (g/resolve-lang "TL" {:langs fixture-table})))))

(deftest scaffold-layout-test
  (let [base (tu/temp-dir! "ts-g-scaffold")]
    (g/scaffold! "python" {:base base})
    (let [dir (g/scaffold-dir "python" base)]
      (testing "proven layout exists"
        (is (fs/exists? (fs/path dir "tree-sitter.json")))
        (is (fs/exists? (fs/path dir "src/grammar.json")))
        (is (fs/exists? (fs/path dir "src/parser.c"))))
      (testing "grammar.json names the grammar"
        (is (= {"name" "python"}
               (json/parse-string (slurp (str (fs/path dir "src/grammar.json")))))))
      (testing "tree-sitter.json registers file-types + scope"
        (let [tsj (json/parse-string (slurp (str (fs/path dir "tree-sitter.json"))))]
          (is (= [{"name" "python" "file-types" ["py"] "scope" "source.python"}]
                 (get-in tsj ["grammars"])))))
      (testing "stub parser.c mtime is ancient -> wasms always newer"
        (let [now-file (fs/path base "now.txt")]
          (spit (str now-file) "x")
          (is (< (.toMillis (fs/last-modified-time (fs/path dir "src/parser.c")))
                 (.toMillis (fs/last-modified-time now-file)))))))
    (testing "config.json points parser-directories at grammars-dir"
      (let [cfg (json/parse-string (slurp (str (paths/config-path base))))]
        (is (= [(str (paths/grammars-dir base))]
               (get cfg "parser-directories")))))
    (testing "idempotent re-scaffold keeps layout valid"
      (g/scaffold! "python" {:base base})
      (is (fs/exists? (fs/path (g/scaffold-dir "python" base) "src/parser.c"))))))

(deftest ensure-grammar-cache-hit-test
  (let [base (tu/temp-dir! "ts-g-hit")]
    (paths/ensure-dirs! base)
    (spit (str (g/wasm-path "testlang" base)) fixture-wasm-bytes)
    ;; binary provisioning is stubbed: the real one downloads on a clean
    ;; base, and this test must stay fully offline
    (with-redefs [cli/ensure-binary! (constantly {:path :stub :version "0.0.0"})]
      (let [wasm (g/wasm-path "testlang" base)
            before (fs/last-modified-time wasm)
            calls (atom 0)
            result (g/ensure-grammar!
                    "testlang"
                    {:base base :langs fixture-table
                     :parse-runner (fn [& _] (swap! calls inc) {:exit 0 :out ""})})]
        (testing "short-circuits without touching acquisition or load-check"
          (is (= {:lang "testlang" :status :cached} result))
          (is (zero? @calls))
          (is (= before (fs/last-modified-time wasm))))
        (testing "scaffold materialized alongside the cached hit"
          (is (fs/exists? (fs/path (g/scaffold-dir "testlang" base) "src/grammar.json")))
          (is (fs/exists? (paths/config-path base))))))))

(deftest ensure-grammar-unknown-lang-test
  (is (nil? (g/ensure-grammar! "nosuchlang" {:base (tu/temp-dir! "ts-g-none")}))))

(deftest load-check-test
  (let [base (tu/temp-dir! "ts-g-check")]
    (paths/ensure-dirs! base)
    (testing "clean parse output accepted"
      (is (nil? (g/load-check! "testlang" {:base base :langs fixture-table}
                               (fn [_ _] {:exit 0 :out "(module)"})))))
    (testing "probe temp file removed afterwards"
      (is (empty? (filter #(str/includes? (str %) "load-check-")
                          (fs/list-dir base)))))
    (testing "ERROR nodes rejected"
      (is (thrown-with-msg? Exception #"load-check failed"
                            (g/load-check! "testlang" {:base base :langs fixture-table}
                                           (fn [_ _] {:exit 0 :out "(module (ERROR))"})))))
    (testing "non-zero exit rejected"
      (is (thrown-with-msg? Exception #"load-check failed"
                            (g/load-check! "testlang" {:base base :langs fixture-table}
                                           (fn [_ _] {:exit 1 :out ""})))))
    (testing "infra failure rejected (never-throw boundary stays outside)"
      (is (thrown-with-msg? Exception #"load-check failed"
                            (g/load-check! "testlang" {:base base :langs fixture-table}
                                           (fn [_ _] {:error :spawn-failure})))))))

(deftest wasm-path-test
  (let [base (tu/temp-dir! "ts-g-wasm")]
    (is (= (fs/path (paths/libs-dir base) "python.wasm")
           (g/wasm-path "python" base)))))
