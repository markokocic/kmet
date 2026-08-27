(ns kmet.extensions.tree-sitter.symbols-test
  "Walker + rules tests run on hand-written sexp fixtures mirroring the
   CLI's default `parse` output; the integration test at the bottom
   exercises the real CLI only when binary+grammar are already cached
   (never downloads)."
  (:require [babashka.fs :as fs]
            [clojure.test :refer [deftest is testing]]
            [kmet.extensions.tree-sitter.grammars :as grammars]
            [kmet.extensions.tree-sitter.paths :as paths]
            [kmet.extensions.tree-sitter.sexp :as sexp]
            [kmet.extensions.tree-sitter.symbols :as symbols]
            [kmet.extensions.tree-sitter.test-util :as tu]))

(def clj-sexp
  "(source [0, 0] - [3, 0]
  (list_lit [0, 0] - [1, 19]
    value: (sym_lit [0, 1] - [0, 5]
      name: (sym_name [0, 1] - [0, 5]))
    value: (sym_lit [0, 6] - [0, 11]
      name: (sym_name [0, 6] - [0, 11]))
    value: (vec_lit [0, 12] - [0, 18]
      value: (sym_lit [0, 13] - [0, 17]
        name: (sym_name [0, 13] - [0, 17])))
    value: (list_lit [1, 2] - [1, 18]
      value: (sym_lit [1, 3] - [1, 6]
        name: (sym_name [1, 3] - [1, 6]))
      value: (str_lit [1, 7] - [1, 12])
      value: (sym_lit [1, 13] - [1, 17]
        name: (sym_name [1, 13] - [1, 17]))))
  (list_lit [2, 0] - [2, 11]
    value: (sym_lit [2, 1] - [2, 6]
      name: (sym_name [2, 1] - [2, 6]))
    value: (str_lit [2, 7] - [2, 10])))")

(def py-sexp
  "(module [0, 0] - [2, 0]
  (class_definition [0, 0] - [1, 35]
    name: (identifier [0, 6] - [0, 9])
    body: (block [1, 4] - [1, 35]
      (function_definition [1, 4] - [1, 35]
        name: (identifier [1, 8] - [1, 11])
        parameters: (parameters [1, 11] - [1, 17]
          (identifier [1, 12] - [1, 16]))
        body: (block [1, 19] - [1, 35]
          (return_statement [1, 19] - [1, 35]
            (call [1, 26] - [1, 35]
              function: (identifier [1, 26] - [1, 32])
              arguments: (argument_list [1, 32] - [1, 35]
                (integer [1, 33] - [1, 34])))))))))")

(def ts-sexp
  "(program [0, 0] - [1, 0]
  (export_statement [0, 0] - [0, 44]
    declaration: (function_declaration [0, 7] - [0, 44]
      name: (identifier [0, 16] - [0, 19])
      parameters: (formal_parameters [0, 19] - [0, 21])
      body: (statement_block [0, 22] - [0, 44]
        (return_statement [0, 24] - [0, 42]
          (call_expression [0, 31] - [0, 41]
            function: (member_expression [0, 31] - [0, 39]
              object: (identifier [0, 31] - [0, 34])
              property: (property_identifier [0, 35] - [0, 39]))
            arguments: (arguments [0, 39] - [0, 41])))))))")

(def ^:private clj-src ["(defn greet [name]" "  (str \"hi \" name))" "(greet \"x\")"])
(def ^:private py-src ["class Foo:" "    def bar(self): return helper(1)"])
(def ^:private ts-src ["export function top() { return obj.meth(); }"])

(defn- parse-el [s]
  (sexp/parse-tree s))

(deftest collect-clojure-test
  (let [el (parse-el clj-sexp)
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
  (let [el (parse-el py-sexp)
        {:keys [symbols calls]} (symbols/collect el py-src (symbols/rules "python"))]
    (testing "class + method defs"
      (is (= [["Foo" "class"] ["bar" "function"]]
             (mapv (juxt :name :kind) symbols))))
    (testing "call records nearest enclosing def (the method)"
      (is (= [{:name "helper" :line 2 :enclosing "bar"}] calls)))))

(deftest collect-typescript-test
  (let [el (parse-el ts-sexp)
        {:keys [symbols calls]} (symbols/collect el ts-src (symbols/rules "typescript"))]
    (testing "function declaration captured"
      (is (= [["top" "function"]] (mapv (juxt :name :kind) symbols))))
    (testing "member call resolves to property name"
      (is (= [{:name "meth" :enclosing "top"}]
             (mapv #(select-keys % [:name :enclosing]) calls))))))

(deftest body-lines-test
  ;; first element child of the source root is the defn list_lit
  (let [defn-el (first (sexp/children (parse-el clj-sexp)))]
    (testing "node range slice incl. both endpoints"
      (is (= ["(defn greet [name]" "  (str \"hi \" name))"]
             (symbols/body-lines clj-src defn-el))))))

(defn- var-kind-sexp []
  "(source [0, 0] - [5, 0]
  (list_lit [0, 0] - [0, 6]
    value: (sym_lit [0, 1] - [0, 3]
      name: (sym_name [0, 1] - [0, 3]))
    value: (sym_lit [0, 4] - [0, 5]
      name: (sym_name [0, 4] - [0, 5])))
  (list_lit [2, 0] - [2, 15]
    value: (sym_lit [2, 1] - [2, 4]
      name: (sym_name [2, 1] - [2, 4]))
    value: (sym_lit [2, 5] - [2, 11]
      name: (sym_name [2, 5] - [2, 11]))
    value: (num_lit [2, 12] - [2, 14]))
  (list_lit [4, 0] - [4, 22]
    value: (sym_lit [4, 1] - [4, 5]
      name: (sym_name [4, 1] - [4, 5]))
    value: (sym_lit [4, 6] - [4, 15]
      name: (sym_name [4, 6] - [4, 15]))
    value: (vec_lit [4, 16] - [4, 19]
      value: (sym_lit [4, 17] - [4, 18]
        name: (sym_name [4, 17] - [4, 18])))
    value: (sym_lit [4, 20] - [4, 21]
      name: (sym_name [4, 20] - [4, 21]))))")

(defn- def-forms-sexp []
  "(source [0, 0] - [5, 0]
  (list_lit [0, 0] - [0, 6]
    value: (sym_lit [0, 1] - [0, 3]
      name: (sym_name [0, 1] - [0, 3]))
    value: (sym_lit [0, 4] - [0, 5]
      name: (sym_name [0, 4] - [0, 5])))
  (list_lit [1, 0] - [1, 23]
    value: (sym_lit [1, 1] - [1, 10]
      name: (sym_name [1, 1] - [1, 10]))
    value: (sym_lit [1, 11] - [1, 16]
      name: (sym_name [1, 11] - [1, 16]))
    value: (vec_lit [1, 17] - [1, 22]
      value: (sym_lit [1, 18] - [1, 19]
        name: (sym_name [1, 18] - [1, 19]))
      value: (sym_lit [1, 20] - [1, 21]
        name: (sym_name [1, 20] - [1, 21]))))
  (list_lit [2, 0] - [2, 15]
    value: (sym_lit [2, 1] - [2, 12]
      name: (sym_name [2, 1] - [2, 12]))
    value: (sym_lit [2, 13] - [2, 14]
      name: (sym_name [2, 13] - [2, 14])))
  (list_lit [3, 0] - [3, 15]
    value: (sym_lit [3, 1] - [3, 9]
      name: (sym_name [3, 1] - [3, 9]))
    value: (sym_lit [3, 10] - [3, 14]
      name: (sym_name [3, 10] - [3, 14])))
  (list_lit [4, 0] - [4, 9]
    value: (sym_lit [4, 1] - [4, 4]
      name: (sym_name [4, 1] - [4, 4]))
    value: (sym_lit [4, 5] - [4, 6]
      name: (sym_name [4, 5] - [4, 6]))
    value: (num_lit [4, 7] - [4, 8])))")

(deftest collect-clojure-var-kind-test
  ;; regression: several defs share the list_lit node type — each symbol's
  ;; kind must come from ITS matching rule, not the first rule of that type
  (let [el (parse-el (var-kind-sexp))
        src ["(ns v)" "" "(def answer 42)" "" "(defn double-it [x] x)"]
        syms (:symbols (symbols/collect el src (symbols/rules "clojure")))]
    (is (= [["answer" "var"] ["double-it" "function"]]
           (mapv (juxt :name :kind) syms)))))

(deftest collect-clojure-def-forms-test
  ;; defrecord/defprotocol/defmulti are symbols with their own kinds — and
  ;; defining heads must never leak through as bogus calls
  (let [el (parse-el (def-forms-sexp))
        src ["(ns r)" "(defrecord Point [x y])" "(defprotocol P)" "(defmulti area)" "(def x 1)"]
        res (symbols/collect el src (symbols/rules "clojure"))]
    (is (= [["Point" "type"] ["P" "protocol"] ["area" "multimethod"] ["x" "var"]]
           (mapv (juxt :name :kind) (:symbols res))))
    (testing "no defining heads leak as calls"
      (is (= [] (:calls res))))))

(deftest collect-ts-binding-filter-test
  ;; const bindings count only when function-valued
  (let [sexp "(program [0, 0] - [2, 0]
  (lexical_declaration [0, 0] - [0, 12]
    (variable_declarator [0, 6] - [0, 11]
      name: (identifier [0, 6] - [0, 7])
      value: (number [0, 10] - [0, 11])))
  (lexical_declaration [1, 0] - [1, 18]
    (variable_declarator [1, 6] - [1, 17]
      name: (identifier [1, 6] - [1, 7])
      value: (arrow_function [1, 10] - [1, 17]
        parameters: (formal_parameters [1, 10] - [1, 12])
        body: (number [1, 16] - [1, 17])))))"
        el (parse-el sexp)
        src ["const n = 5;" "const f = () => 1;"]
        syms (:symbols (symbols/collect el src (symbols/rules "typescript")))]
    (is (= [["f" "binding"]] (mapv (juxt :name :kind) syms)))))

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

;; ─── split-trees unit tests (no CLI needed) ───────────────────────────────

(deftest split-trees-test
  (testing "pairs trees to paths in order"
    (let [out "(source [0,0]-[1,0])\n(source [0,0]-[1,0])\n"
          pairs (#'symbols/split-trees ["/a.clj" "/b.clj"] out)]
      (is (= {"/a.clj" "(source [0,0]-[1,0])"
              "/b.clj" "(source [0,0]-[1,0])"}
             pairs))))
  (testing "stats lines (problem files) are dropped, not paired"
    (let [out "(source [0,0]-[1,0])\n/some/bad.py\tParse: 0.1 ms\t1 bytes/ms\t(ERROR [0, 0] - [1, 0])\n(source [0,0]-[1,0])\n"
          pairs (#'symbols/split-trees ["/a.py" "/b.py"] out)]
      (is (= {"/a.py" "(source [0,0]-[1,0])"
              "/b.py" "(source [0,0]-[1,0])"}
             pairs))))
  (testing "empty files (no tree output) are skipped"
    (let [out "(source [0,0]-[1,0])\n"
          pairs (#'symbols/split-trees ["/a.clj" "/empty.clj"] out)]
      (is (= {"/a.clj" "(source [0,0]-[1,0])"} pairs)))))
