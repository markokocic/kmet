(ns kmet.libs.test-frontmatter
  "Tests for kmet.libs.frontmatter (pi: utils/frontmatter.js)."
  (:require [clojure.test :as t :refer [deftest is]]
            [kmet.libs.frontmatter :as fm]))

(deftest no-frontmatter
  (is (= {:frontmatter {} :body "hello\nworld"}
         (fm/parse-frontmatter "hello\nworld"))))

(deftest basic-frontmatter
  (is (= {:frontmatter {"name" "foo" "description" "bar baz"}
          :body "Body text"}
         (fm/parse-frontmatter "---\nname: foo\ndescription: bar baz\n---\nBody text"))))

(deftest empty-frontmatter
  (is (= {:frontmatter {} :body "Body text"}
         (fm/parse-frontmatter "---\n---\nBody text"))))

(deftest crlf-normalized
  (is (= {:frontmatter {"name" "foo"} :body "Body"}
         (fm/parse-frontmatter "---\r\nname: foo\r\n---\r\nBody"))))

(deftest unterminated-frontmatter-is-body
  (is (= {:frontmatter {} :body "---\nname: foo\n"}
         (fm/parse-frontmatter "---\nname: foo\n"))))
