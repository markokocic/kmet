(ns kmet.extensions.tree-sitter.validate-test
  "Unit tests on canned sexp strings / plain strings; the parse
   integration is guarded by cache presence (never downloads)."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [are deftest is testing]]
            [kmet.extensions.tree-sitter.paths :as paths]
            [kmet.extensions.tree-sitter.sexp :as sexp]
            [kmet.extensions.tree-sitter.validate :as validate]))

(defn- parse-el [s]
  (sexp/parse-tree s))

(def error-fixture
  "(module [0, 0] - [2, 0]
  (ERROR [0, 10] - [0, 11])
  (function_definition [1, 0] - [1, 9]
    (ERROR [1, 8] - [1, 9])))")

(def src ["def broken(:" "    return"])

(deftest problems-from-tree-test
  (let [problems (validate/problems-from-tree (parse-el error-fixture) src)]
    (is (= 2 (count problems)))
    (is (= {:kind :error :line 1 :col 11}
           (select-keys (first problems) [:kind :line :col])))
    (is (= "def broken(:" (:snippet (first problems))))))

(deftest zero-width-missing-test
  ;; a missing named node surfaces as a zero-width child in the tree
  ;; (`if :` → condition: (identifier [1, 6] - [1, 6]))
  (let [sexp "(module [0, 0] - [2, 0]
  (if_statement [0, 0] - [1, 8]
    condition: (identifier [0, 2] - [0, 2])
    consequence: (block [1, 4] - [1, 8]
      (pass_statement [1, 4] - [1, 8]))))"
        tree (parse-el sexp)
        problems (validate/problems-from-tree tree ["if :" "    pass"])]
    (is (= [{:kind :missing :line 1 :col 3 :expected "identifier" :snippet "if :"}]
           problems))))

(deftest stats-record-test
  ;; MISSING tokens (like `def f(:` → `(MISSING ")" ...)`) never appear in
  ;; the tree — only on the stats line
  (let [line "/tmp/bad.py\tParse: 0.09 ms\t170 bytes/ms\t(MISSING \") [0, 6] - [0, 6])"
        r (validate/stats-record line)]
    (is (= {:kind :missing :line 1 :col 7 :expected ")"} r)))
  (let [line "/tmp/bad.clj\tParse: 0.05 ms\t100 bytes/ms\t(ERROR [0, 0] - [2, 0])"
        r (validate/stats-record line)]
    (is (= {:kind :error :line 1 :col 1 :expected nil} r)))
  (testing "clean stats line (no record) -> nil"
    (is (nil? (validate/stats-record "/tmp/ok.py\tParse: 0.1 ms\t200 bytes/ms")))))

(deftest delimiter-balanced-test
  (are [s] (nil? (first (validate/delimiter-problems s)))
    "(defn f [x] {:a 1})"
    "(def s \")\") ; a ) inside a string/comment"
    "[{:k \"x\"}]"
    "(multi\n   line \"strings\")"))

(deftest delimiter-unclosed-test
  (let [p (first (validate/delimiter-problems "(defn f [x]"))]
    (is (= :unclosed (:kind p)))
    (is (= 1 (:line p) (:col p)))
    (is (= ")" (:expected p)))))

(deftest delimiter-stray-closer-test
  ;; the first ) closes the opener; the second ) is stray (nothing open),
  ;; reported at its own position with no expectation
  (let [p (first (validate/delimiter-problems "(defn f [x]))"))]
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
    (testing "broken python reports problems (missing token via stats line)"
      (let [r (validate/parse-problems!
               "bad.py" "def f(:\n    pass\n" "python")]
        (is (= :tree-sitter (:via r)))
        (is (seq (:problems r)))
        (is (= :missing (:kind (first (:problems r)))))))
    (testing "clean python returns nil (hooks treat non-nil as a block)"
      (is (nil? (validate/parse-problems!
                 "ok.py" "def f():\n    return 1\n" "python"))))
    (testing "broken clojure reports ERROR"
      (let [r (validate/parse-problems!
               "bad.clj" "(defn f [x]\n  (str x)\n" "clojure")]
        (is (= :error (:kind (first (:problems r)))))))))
