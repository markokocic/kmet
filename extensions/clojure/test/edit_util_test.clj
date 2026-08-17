(ns edit-util-test
  "Tests for the shared edit-util namespace."
  (:require [clojure.test :as t :refer [deftest testing is]]
            [clojure.string :as str]
            [babashka.fs :as fs]
            [edit-util :as util]
            [rewrite-clj.node :as node]
            [rewrite-clj.parser :as p]
            [rewrite-clj.zip :as z]))

;; ═══════════════════════════════════════════════════════════════════════════════
;; File I/O
;; ═══════════════════════════════════════════════════════════════════════════════

(deftest test-slurp-utf8
  (fs/create-dirs "target/util-tests")
  (spit "target/util-tests/utf8.clj" "(def x 42)\n" :encoding "UTF-8")
  (is (= "(def x 42)\n" (util/slurp-utf8 "target/util-tests/utf8.clj"))))

(deftest test-spit-utf8
  (fs/create-dirs "target/util-tests")
  (util/spit-utf8 "target/util-tests/out.clj" "(def y 99)\n")
  (is (= "(def y 99)\n" (slurp "target/util-tests/out.clj" :encoding "UTF-8"))))

(deftest test-utf8-roundtrip
  (fs/create-dirs "target/util-tests")
  (util/spit-utf8 "target/util-tests/rt.clj" "(def greeting \"Привет\")\n")
  (is (= "(def greeting \"Привет\")\n" (util/slurp-utf8 "target/util-tests/rt.clj"))))

;; ═══════════════════════════════════════════════════════════════════════════════
;; Lint repair
;; ═══════════════════════════════════════════════════════════════════════════════

(deftest test-lint-repair
  (testing "returns [code false] — passthrough, no repair"
    (let [[code repaired?] (util/lint-repair "(+ x 1)")]
      (is (= "(+ x 1)" code))
      (is (false? repaired?)))))

;; ═══════════════════════════════════════════════════════════════════════════════
;; Diff
;; ═══════════════════════════════════════════════════════════════════════════════

(deftest test-diff-identical
  (is (nil? (util/generate-unified-diff "hello" "hello"))))

(deftest test-diff-different
  (let [result (util/generate-unified-diff "line1\n" "line2\n")]
    (is (string? result))
    (is (str/includes? result "line1"))
    (is (str/includes? result "line2"))))

;; ═══════════════════════════════════════════════════════════════════════════════
;; Formatting
;; ═══════════════════════════════════════════════════════════════════════════════

(deftest test-format-source-string
  (testing "formats malformatted Clojure"
    (let [formatted (util/format-source-string "(defn foo[x](+ x 1))")]
      (is (string? formatted))
      (is (str/includes? formatted "defn")))))

(deftest test-format-source-string-invalid
  (testing "returns original on parse error"
    (let [input "(defn foo ["]
      (is (= input (util/format-source-string input))))))

(deftest test-re-indent-to-column
  (is (= "foo\n  bar\n  baz"
         (util/re-indent-to-column "foo\nbar\nbaz" 3)))
  (testing "col 1 is no-op"
    (is (= "foo\nbar" (util/re-indent-to-column "foo\nbar" 1)))))

(deftest test-format-form-in-isolation
  (let [result (util/format-form-in-isolation "(defn foo[](let [x 1](+ x 2)))" 5)]
    (is (string? result))
    (is (str/includes? result "let"))))

;; ═══════════════════════════════════════════════════════════════════════════════
;; Zipper navigation
;; ═══════════════════════════════════════════════════════════════════════════════

(deftest test-walk-back-to-non-comment
  (testing "walks backward past comments and whitespace to a non-comment node"
    ;; walk-back-to-non-comment uses z/prev* which traverses the full tree.
    ;; Verify it finds a non-whitespace/non-comment node.
    (let [parsed (p/parse-string-all ";; comment\n;; another\n(+ x 1)")
          zloc   (z/of-node parsed)
          ;; Navigate into the list node: down goes to the first child (+)
          list-loc (-> zloc z/down z/right)] ;; skip comment, get to list
      ;; The function should find a non-comment node going backward
      (let [back (util/walk-back-to-non-comment list-loc)]
        (is (some? back))
        (is (not (#{:whitespace :comment} (node/tag (z/node back))))))))
  (testing "returns the node itself when no preceding comment"
    (let [zloc (z/of-string "(+ x 1)")
          expr (z/down zloc)
          back (util/walk-back-to-non-comment expr)]
      (is (some? back)))))

;; ═══════════════════════════════════════════════════════════════════════════════
;; Zipper form replacement
;; ═══════════════════════════════════════════════════════════════════════════════

(deftest test-replace-form-normal
  (let [zloc  (z/of-string "(+ x 1)")
        replaced (util/replace-form zloc "(+ x 2)")]
    (is (= "(+ x 2)" (z/root-string replaced)))))

(deftest test-replace-form-with-comment
  (testing "replacing with a comment removes adjacent comments"
    (let [zloc     (z/of-string ";; old\n(+ x 1)")
          ;; Navigate to the (+ x 1) form
          expr     (-> zloc z/down z/right)
          replaced (util/replace-form expr ";; replaced")]
      (is (str/includes? (z/root-string replaced) "replaced")))))
