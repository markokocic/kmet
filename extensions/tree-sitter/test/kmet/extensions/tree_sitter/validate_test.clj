(ns kmet.extensions.tree-sitter.validate-test
  "Unit tests on canned XML / strings; the parse integration is guarded by
   cache presence (never downloads)."
  (:require [babashka.fs :as fs]
            [clojure.data.xml :as xml]
            [clojure.string :as str]
            [clojure.test :refer [are deftest is testing]]
            [kmet.extensions.tree-sitter.paths :as paths]
            [kmet.extensions.tree-sitter.validate :as validate]))

(defn- parse-el [s]
  (->> (:content (xml/parse-str s))
       (filter #(= :source (:tag %)))
       first
       :content
       (filter map?)
       first))

(def error-fixture
  "<?xml version=\"1.0\"?>
<sources>
  <source name=\"bad.py\">
    <module srow=\"0\" scol=\"0\" erow=\"2\" ecol=\"0\">
      <ERROR srow=\"0\" scol=\"10\" erow=\"0\" ecol=\"11\"></ERROR>
      <function_definition srow=\"1\" scol=\"0\" erow=\"1\" ecol=\"9\">
        <ERROR srow=\"1\" scol=\"8\" erow=\"1\" ecol=\"9\"></ERROR>
      </function_definition>
    </module>
  </source>
</sources>")

(def src ["def broken(:" "    return"])

(deftest problems-from-tree-test
  (let [problems (validate/problems-from-tree (parse-el error-fixture) src)]
    (is (= 2 (count problems)))
    (is (= {:kind :error :line 1 :col 11}
           (select-keys (first problems) [:kind :line :col])))
    (is (= "def broken(:" (:snippet (first problems))))))

(deftest missing-node-test
  (let [xml "<?xml version=\"1.0\"?>
<sources>
  <source name=\"m.ts\">
    <program srow=\"0\" scol=\"0\" erow=\"0\" ecol=\"4\">
      <MISSING:token srow=\"0\" scol=\"3\" erow=\"0\" ecol=\"3\"></MISSING:token>
    </program>
  </source>
</sources>"
        el (->> (:content (xml/parse-str xml :namespace-aware false))
                (filter #(= :source (:tag %)))
                first
                :content
                (filter map?)
                first)
        problems (validate/problems-from-tree el ["abc"])]
    (is (= [{:kind :missing :line 1 :col 4 :expected "token" :snippet "abc"}]
           problems))))

(defn- run-delimiter-case [s]
  (first (validate/delimiter-problems s)))

(deftest delimiter-balanced-test
  (are [s] (nil? (run-delimiter-case s))
    "(defn f [x] {:a 1})"
    "(def s \")\") ; a ) inside a string/comment"
    "[{:k \"x\"}]"
    "(multi\n   line \"strings\")"))

(deftest delimiter-unclosed-test
  (let [p (run-delimiter-case "(defn f [x]")]
    (is (= :unclosed (:kind p)))
    (is (= 1 (:line p) (:col p)))
    (is (= ")" (:expected p)))))

(deftest delimiter-stray-closer-test
  ;; the first ) closes the opener; the second ) is stray (nothing open),
  ;; reported at its own position with no expectation
  (let [p (run-delimiter-case "(defn f [x]))")]
    (is (= :stray-closer (:kind p)))
    (is (= 13 (:col p)))
    (is (nil? (:expected p)))))

(deftest report-text-test
  (let [report (validate/report-text
                [{:kind :error :line 2 :col 3 :snippet "(x"}])]
    (is (str/includes? report "line 2, col 3: syntax error"))
    (is (str/includes? report "(x"))))

;; ─── integration against the real CLI (guarded, never downloads) ──────────

(defn- cache-ready? []
  (and (fs/exists? (paths/bin-path nil))
       (fs/exists? (fs/path (paths/libs-dir nil) "python.wasm"))))

(deftest ^:integration parse-problems!-test
  (when (cache-ready?)
    (testing "broken python reports problems"
      (let [r (validate/parse-problems!
               "bad.py" "x = (1 +" "python")]
        (is (= :tree-sitter (:via r)))
        (is (seq (:problems r)))))
    (testing "clean python reports none"
      (let [r (validate/parse-problems!
               "ok.py" "def f():\n    return 1\n" "python")]
        (is (empty? (:problems r)))))))
