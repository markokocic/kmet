(ns kmet.extensions.tree-sitter.symbols-test
  "Walker + rules tests run on hand-written XML fixtures mirroring the
   CLI's `parse -x` output; the integration test at the bottom exercises
   the real CLI only when binary+grammar are already cached (never
   downloads)."
  (:require [babashka.fs :as fs]
            [clojure.data.xml :as xml]
            [clojure.test :refer [deftest is testing]]
            [kmet.extensions.tree-sitter.grammars :as grammars]
            [kmet.extensions.tree-sitter.paths :as paths]
            [kmet.extensions.tree-sitter.symbols :as symbols]
            [kmet.extensions.tree-sitter.test-util :as tu]))

(def clj-xml
  "<?xml version=\"1.0\"?>
<sources>
  <source name=\"t.clj\">
    <source srow=\"0\" scol=\"0\" erow=\"3\" ecol=\"0\">
      <list_lit srow=\"0\" scol=\"0\" erow=\"1\" ecol=\"19\">
        (
        <sym_lit field=\"value\" srow=\"0\" scol=\"1\" erow=\"0\" ecol=\"5\">
          <sym_name field=\"name\" srow=\"0\" scol=\"1\" erow=\"0\" ecol=\"5\">defn</sym_name>
        </sym_lit>
        <sym_lit field=\"value\" srow=\"0\" scol=\"6\" erow=\"0\" ecol=\"11\">
          <sym_name field=\"name\" srow=\"0\" scol=\"6\" erow=\"0\" ecol=\"11\">greet</sym_name>
        </sym_lit>
        <vec_lit field=\"value\" srow=\"0\" scol=\"12\" erow=\"0\" ecol=\"18\">[</vec_lit>
        <list_lit field=\"value\" srow=\"1\" scol=\"2\" erow=\"1\" ecol=\"18\">
          (
          <sym_lit field=\"value\" srow=\"1\" scol=\"3\" erow=\"1\" ecol=\"6\">
            <sym_name field=\"name\" srow=\"1\" scol=\"3\" erow=\"1\" ecol=\"6\">str</sym_name>
          </sym_lit>
          )
        </list_lit>
      </list_lit>
      <list_lit srow=\"2\" scol=\"0\" erow=\"2\" ecol=\"9\">
        (
        <sym_lit field=\"value\" srow=\"2\" scol=\"1\" erow=\"2\" ecol=\"8\">
          <sym_name field=\"name\" srow=\"2\" scol=\"1\" erow=\"2\" ecol=\"8\">greet</sym_name>
        </sym_lit>
        )
      </list_lit>
    </source>
  </source>
</sources>")

(def py-xml
  "<?xml version=\"1.0\"?>
<sources>
  <source name=\"t.py\">
    <module srow=\"0\" scol=\"0\" erow=\"2\" ecol=\"0\">
      <class_definition srow=\"0\" scol=\"0\" erow=\"2\" ecol=\"20\">
        class
        <identifier field=\"name\" srow=\"0\" scol=\"6\" erow=\"0\" ecol=\"9\">Foo</identifier>
        :
        <block field=\"body\" srow=\"1\" scol=\"4\" erow=\"1\" ecol=\"26\">
          <function_definition srow=\"1\" scol=\"4\" erow=\"1\" ecol=\"26\">
            def
            <identifier field=\"name\" srow=\"1\" scol=\"8\" erow=\"1\" ecol=\"11\">bar</identifier>
            (
            <parameters field=\"parameters\" srow=\"1\" scol=\"12\" erow=\"1\" ecol=\"18\">self</parameters>
            ):
            <block field=\"body\" srow=\"1\" scol=\"24\" erow=\"1\" ecol=\"26\">
              <call field=\"body\" srow=\"1\" scol=\"11\" erow=\"1\" ecol=\"26\">
                <identifier field=\"function\" srow=\"1\" scol=\"11\" erow=\"1\" ecol=\"17\">helper</identifier>
                (
                <argument_list field=\"arguments\" srow=\"1\" scol=\"18\" erow=\"1\" ecol=\"25\">1</argument_list>
                )
              </call>
            </block>
          </function_definition>
        </block>
      </class_definition>
    </module>
  </source>
</sources>")

(def ts-xml
  "<?xml version=\"1.0\"?>
<sources>
  <source name=\"t.ts\">
    <program srow=\"0\" scol=\"0\" erow=\"2\" ecol=\"0\">
      <function_declaration srow=\"0\" scol=\"0\" erow=\"1\" ecol=\"40\">
        export function
        <identifier field=\"name\" srow=\"0\" scol=\"16\" erow=\"0\" ecol=\"19\">top</identifier>
        {
        <return_statement field=\"body\" srow=\"0\" scol=\"36\" erow=\"0\" ecol=\"39\">
          <call_expression field=\"body\" srow=\"0\" scol=\"9\" erow=\"0\" ecol=\"39\">
            <member_expression field=\"function\" srow=\"0\" scol=\"9\" erow=\"0\" ecol=\"17\">
              obj
              <identifier field=\"property\" srow=\"0\" scol=\"13\" erow=\"0\" ecol=\"17\">meth</identifier>
            </member_expression>
            ()
          </call_expression>
        </return_statement>
        }
      </function_declaration>
    </program>
  </source>
</sources>")

(def ^:private clj-src ["(defn greet [name]" "  (str \"hi \" name))" "(greet \"x\")"])
(def ^:private py-src ["class Foo:" "    def bar(self): return helper(1)"])
(def ^:private ts-src ["export function top() { return obj.meth(); }"])

(defn- parse-el [s]
  (->> (:content (xml/parse-str s))
       (filter #(= :source (:tag %)))
       first
       :content
       (filter map?)
       first))

(deftest collect-clojure-test
  (let [el (parse-el clj-xml)
        {:keys [symbols calls]} (symbols/collect el clj-src (symbols/rules "clojure"))]
    (testing "defs found with kinds and ranges"
      (is (= [{:name "greet" :kind "function"}]
             (mapv #(select-keys % [:name :kind]) symbols)))
      (is (= 1 (:line (first symbols))))
      (is (= 2 (:end-line (first symbols))))
      (is (= "(defn greet [name]" (:signature (first symbols)))))
    (testing "(str ...) is a call inside greet; (greet ...) is top-level"
      (is (= [{:name "str" :line 2 :enclosing "greet"}
              {:name "greet" :line 3 :enclosing nil}]
             calls)))))

(deftest collect-python-test
  (let [el (parse-el py-xml)
        {:keys [symbols calls]} (symbols/collect el py-src (symbols/rules "python"))]
    (testing "class + method defs"
      (is (= [["Foo" "class"] ["bar" "function"]]
             (mapv (juxt :name :kind) symbols))))
    (testing "call records nearest enclosing def (the method)"
      (is (= [{:name "helper" :line 2 :enclosing "bar"}] calls)))))

(deftest collect-typescript-test
  (let [el (parse-el ts-xml)
        {:keys [symbols calls]} (symbols/collect el ts-src (symbols/rules "typescript"))]
    (testing "function declaration captured"
      (is (= [["top" "function"]] (mapv (juxt :name :kind) symbols))))
    (testing "member call resolves to property name"
      (is (= [{:name "meth" :enclosing "top"}]
             (mapv #(select-keys % [:name :enclosing]) calls))))))

(deftest body-lines-test
  ;; parse-el returns the tree-root <source> node; its first element child
  ;; is the defn list_lit
  (let [defn-el (first (filter map? (:content (parse-el clj-xml))))]
    (testing "node range slice incl. both endpoints"
      (is (= ["(defn greet [name]" "  (str \"hi \" name))"]
             (symbols/body-lines clj-src defn-el))))))

(deftest collect-clojure-var-kind-test
  ;; regression: several defs share the list_lit node type — each symbol's
  ;; kind must come from ITS matching rule, not the first rule of that type
  (let [xml "<?xml version=\"1.0\"?>
<sources>
  <source name=\"v.clj\">
    <source srow=\"0\" scol=\"0\" erow=\"4\" ecol=\"0\">
      <list_lit srow=\"2\" scol=\"0\" erow=\"2\" ecol=\"14\">
        (<sym_lit><sym_name>def</sym_name></sym_lit>
         <sym_lit><sym_name>answer</sym_name></sym_lit>)
      </list_lit>
      <list_lit srow=\"4\" scol=\"0\" erow=\"5\" ecol=\"20\">
        (<sym_lit><sym_name>defn</sym_name></sym_lit>
         <sym_lit><sym_name>double-it</sym_name></sym_lit>)
      </list_lit>
    </source>
  </source>
</sources>"
        el (parse-el xml)
        src ["(ns v)" "" "(def answer 42)" "" "(defn double-it [x] x)"]
        syms (:symbols (symbols/collect el src (symbols/rules "clojure")))]
    (is (= [["answer" "var"] ["double-it" "function"]]
           (mapv (juxt :name :kind) syms)))))

(deftest rules-integrity-test
  (doseq [lang ["clojure" "python" "typescript" "tsx"]]
    (let [r (symbols/rules lang)]
      (testing (str lang)
        (is (vector? (:defs r)))
        (is (seq (:defs r)))
        (is (vector? (:calls r)))
        (is (seq (:calls r)))
        (is (every? #(string? (:kind %)) (:defs r)) lang))))
  ;; memoization returns identical table
  (is (identical? (symbols/rules "python") (symbols/rules "python"))))

;; ─── integration against the real CLI (guarded, never downloads) ──────────

(defn- cache-ready?
  []
  (and (fs/exists? (paths/bin-path nil))
       (fs/exists? (grammars/wasm-path "clojure" nil))))

(defn- integration-fixture!
  [dir]
  (spit (str (fs/path dir "fixture.clj"))
        "(ns fixture)\n\n(defn helper [x]\n  (inc x))\n\n(defn user [n]\n  (helper n))\n"))

(when-not (cache-ready?)
  (println "[symbols-test] tree-sitter cache incomplete — skipping CLI "
           "integration test (populate via ensure-binary!/ensure-grammar!; "
           "tests never download)"))

(deftest ^:integration analyze-file!-test
  (when (cache-ready?)
    (let [dir (tu/temp-dir! "ts-sym-it")]
      (try
        (integration-fixture! dir)
        (let [f (str (fs/path dir "fixture.clj"))
              {:keys [symbols calls]} (symbols/analyze-file! f "clojure")]
          (is (= [["helper" "function"] ["user" "function"]]
                 (mapv (juxt :name :kind) symbols)))
          (testing "user calls helper, enclosing resolved (ns form also seen)"
            (is (= [{:name "helper" :enclosing "user"}]
                   (filterv #(= "helper" (:name %))
                            (mapv #(select-keys % [:name :enclosing]) calls))))))
        (finally
          (fs/delete-tree dir))))))
