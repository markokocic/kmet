(ns kmet.libs.test-yaml-lite
  (:require [clojure.test :as t]
            [kmet.libs.yaml-lite :as yaml]))

;; ─── Plain scalars ─────────────────────────────────────────────────────────

(t/deftest test-plain-strings
  (t/is (= {"a" "foo"} (yaml/parse "a: foo")))
  (t/is (= {"a" "foo bar baz"} (yaml/parse "a: foo bar baz")))
  (t/is (= {"a" "http://x"} (yaml/parse "a: http://x")))
  (t/is (= {"description" "see http://x for details"}
           (yaml/parse "description: see http://x for details")))
  (t/is (= {"a" "x#y"} (yaml/parse "a: x#y")))
  (t/is (= {"a" {"colon" "inside"}} (yaml/parse "a: colon: inside")))
  (t/is (= {"a" "spaced"} (yaml/parse "a:   spaced  ")))  ; plain scalars are trimmed
  (t/is (= {"a" "with"} (yaml/parse "a: with # in middle")))  ; # after space = comment
  (t/is (= {"a" "-dash"} (yaml/parse "a: -dash"))))

(t/deftest test-booleans-and-null
  (t/is (= {"a" true} (yaml/parse "a: true")))
  (t/is (= {"a" true} (yaml/parse "a: True")))
  (t/is (= {"a" true} (yaml/parse "a: TRUE")))
  (t/is (= {"a" false} (yaml/parse "a: false")))
  (t/is (= {"a" false} (yaml/parse "a: False")))
  (t/is (= {"a" false} (yaml/parse "a: FALSE")))
  (t/is (= {"a" nil} (yaml/parse "a: null")))
  (t/is (= {"a" nil} (yaml/parse "a: Null")))
  (t/is (= {"a" nil} (yaml/parse "a: NULL")))
  (t/is (= {"a" nil} (yaml/parse "a: ~")))
  (t/is (= {"a" nil} (yaml/parse "a:")))
  (t/is (= {"a" nil} (yaml/parse "a: # comment")))
  (t/testing "YAML 1.2 core: yes/no/on/off stay strings"
    (t/is (= {"a" "yes"} (yaml/parse "a: yes")))
    (t/is (= {"a" "no"} (yaml/parse "a: no")))
    (t/is (= {"a" "on"} (yaml/parse "a: on")))
    (t/is (= {"a" "off"} (yaml/parse "a: off")))))

(t/deftest test-numbers
  (t/is (= {"a" 42} (yaml/parse "a: 42")))
  (t/is (= {"a" -7} (yaml/parse "a: -7")))
  (t/is (= {"a" 5} (yaml/parse "a: +5")))
  (t/is (= {"a" 0} (yaml/parse "a: 0")))
  (t/is (= {"a" 3.14} (yaml/parse "a: 3.14")))
  (t/is (= {"a" -2.5} (yaml/parse "a: -2.5")))
  (t/is (= {"a" 0.5} (yaml/parse "a: .5")))
  (t/is (= {"a" 100000.0} (yaml/parse "a: 1e5")))
  (t/is (= {"a" 100000.0} (yaml/parse "a: 1E5")))
  (t/is (= {"a" 0.0015} (yaml/parse "a: 1.5e-3")))
  (t/testing "underscores are NOT digit separators (pi yaml keeps 1_000 a string)"
    (t/is (= {"a" "1_000"} (yaml/parse "a: 1_000")))
    (t/is (= {"a" "1_000.5"} (yaml/parse "a: 1_000.5"))))
  (t/testing "non-numbers stay strings"
    (t/is (= {"a" "1."} (yaml/parse "a: 1.")))
    (t/is (= {"a" "1.2.3"} (yaml/parse "a: 1.2.3")))
    (t/is (= {"a" "123abc"} (yaml/parse "a: 123abc")))
    (t/is (= {"a" "0x1F"} (yaml/parse "a: 0x1F")))
    (t/is (= {"a" "1,000"} (yaml/parse "a: 1,000")))
    (t/testing "overflowing ints fall back to the string (bb parse-long returns nil)"
      (t/is (= {"a" "99999999999999999999999"} (yaml/parse "a: 99999999999999999999999"))))))

;; ─── Quoted scalars ────────────────────────────────────────────────────────

(t/deftest test-double-quoted
  (t/is (= {"a" "plain"} (yaml/parse "a: \"plain\"")))
  (t/is (= {"a" "line\nbreak"} (yaml/parse "a: \"line\\nbreak\"")))
  (t/is (= {"a" "tab\tchar"} (yaml/parse "a: \"tab\\tchar\"")))
  (t/is (= {"a" "carriage\rreturn"} (yaml/parse "a: \"carriage\\rreturn\"")))
  (t/is (= {"a" "quote\"inside"} (yaml/parse "a: \"quote\\\"inside\"")))
  (t/is (= {"a" "back\\slash"} (yaml/parse "a: \"back\\\\slash\"")))
  (t/is (= {"a" "a/b"} (yaml/parse "a: \"a\\/b\"")))
  (t/is (= {"a" "A"} (yaml/parse "a: \"\\x41\"")))
  (t/is (= {"a" "A"} (yaml/parse "a: \"\\u0041\"")))
  (t/is (= {"a" "a # b"} (yaml/parse "a: \"a # b\"")))
  (t/is (= {"a" "a: b"} (yaml/parse "a: \"a: b\"")))
  (t/testing "unknown escapes keep the char (lenient; pi throws)"
    (t/is (= {"a" "q"} (yaml/parse "a: \"\\q\"")))))

(t/deftest test-single-quoted
  (t/is (= {"a" "plain"} (yaml/parse "a: 'plain'")))
  (t/is (= {"a" "it's"} (yaml/parse "a: 'it''s'")))
  (t/testing "single quotes do not process backslash escapes (YAML)"
    (t/is (= {"a" "no \\n escape"} (yaml/parse "a: 'no \\n escape'")))))

(t/deftest test-quote-leniency
  (t/testing "unterminated/trailing-junk quotes fall back to plain (lenient)"
    (t/is (= {"a" "\"foo"} (yaml/parse "a: \"foo")))
    (t/is (= {"a" "\"foo\" bar"} (yaml/parse "a: \"foo\" bar"))))
  (t/testing "quotes open only at token start (mid-word ' is literal)"
    (t/is (= {"a" "it's"} (yaml/parse "a: it's # comment")))
    (t/is (= {"a" "it's fine"} (yaml/parse "a: it's fine"))))
  (t/testing "# inside block scalar content is literal, not a comment"
    (t/is (= {"a" "use #hash tags\n"} (yaml/parse "a: |\n  use #hash tags\n")))
    (t/is (= {"a" "he said \"hi\"\n"} (yaml/parse "a: |\n  he said \"hi\"\n"))))
  (t/testing "quoted list item with a colon stays a string"
    (t/is (= ["x: y" "z"] (yaml/parse "- \"x: y\"\n- z")))))

;; ─── Comments ──────────────────────────────────────────────────────────────

(t/deftest test-comments
  (t/is (= {"a" "foo"} (yaml/parse "a: foo # comment")))
  (t/is (= {"a" "foo#bar"} (yaml/parse "a: foo#bar # real comment")))
  (t/is (= {"a" "foo"} (yaml/parse "# leading comment\na: foo")))
  (t/is (nil? (yaml/parse "# only comment")))
  (t/is (= {"a" "foo"} (yaml/parse "a: foo\t# tab before comment")))
  (t/is (= {"a" "x#y"} (yaml/parse "a: x#y")))
  (t/is (= {"a" "quoted #"} (yaml/parse "a: \"quoted #\""))))

;; ─── Block scalars ─────────────────────────────────────────────────────────

(t/deftest test-literal-block-scalars
  (t/testing "clip chomping (default): exactly one trailing newline"
    (t/is (= {"a" "x\ny\n"} (yaml/parse "a: |\n  x\n  y\n")))
    (t/is (= {"a" "x\n"} (yaml/parse "a: |\n  x\n")))
    (t/is (= {"a" "x\n"} (yaml/parse "a: |\n  x\n\n")))
    (t/is (= {"a" "x\n"} (yaml/parse "a: |\n  x\n\n\n"))))
  (t/testing "strip chomping (-)"
    (t/is (= {"a" "x\ny"} (yaml/parse "a: |-\n  x\n  y\n")))
    (t/is (= {"a" "x"} (yaml/parse "a: |-\n  x\n\n"))))
  (t/testing "keep chomping (+): trailing newlines preserved"
    (t/is (= {"a" "x\n"} (yaml/parse "a: |+\n  x\n")))
    (t/is (= {"a" "x\n\n"} (yaml/parse "a: |+\n  x\n\n"))))
  (t/testing "explicit indent indicator (relative to key)"
    (t/is (= {"a" "x\ny\n"} (yaml/parse "a: |2\n  x\n  y\n"))))
  (t/testing "indent indicator combined with chomping"
    (t/is (= {"a" "x\ny"} (yaml/parse "a: |2-\n  x\n  y\n"))))
  (t/testing "empty block"
    (t/is (= {"a" ""} (yaml/parse "a: |\n")))
    (t/is (= {"a" ""} (yaml/parse "a: |-\n")))))

(t/deftest test-folded-block-scalars
  (t/is (= {"a" "x y\n"} (yaml/parse "a: >\n  x\n  y\n")))
  (t/is (= {"a" "x y z\n"} (yaml/parse "a: >\n  x\n  y\n  z\n")))
  (t/is (= {"a" "x\ny\n"} (yaml/parse "a: >\n  x\n\n  y\n")))
  (t/is (= {"a" "x\n\ny\n"} (yaml/parse "a: >\n  x\n\n\n  y\n")))
  (t/is (= {"a" "x y"} (yaml/parse "a: >-\n  x\n  y\n"))))

(t/deftest test-block-scalars-in-context
  (t/is (= ["x\ny\n" "plain"] (yaml/parse "- |\n  x\n  y\n- plain")))
  (t/is (= {"meta" {"desc" "line1\nline2\n"}}
           (yaml/parse "meta:\n  desc: |\n    line1\n    line2\n"))))

;; ─── Structure ─────────────────────────────────────────────────────────────

(t/deftest test-nested-maps
  (t/is (= {"meta" {"a" "b"}} (yaml/parse "meta:\n  a: b")))
  (t/is (= {"a" {"b" {"c" "d"}}} (yaml/parse "a:\n  b:\n    c: d")))
  (t/is (= {"a" {"b" "c"} "d" "e"} (yaml/parse "a: b: c\nd: e")))
  (t/is (= {"a" {"b" {"c" "d"}}} (yaml/parse "a: b: c: d")))
  (t/testing "compact mapping in list items continues with deeper entries"
    (t/is (= [{"name" "a", "age" 1} {"name" "b"}]
             (yaml/parse "- name: a\n  age: 1\n- name: b")))
    (t/is (= [{"name" "a", "meta" {"x" 1}} {"name" "b"}]
             (yaml/parse "- name: a\n  meta:\n    x: 1\n- name: b")))))

(t/deftest test-lists
  (t/is (= ["a" "b"] (yaml/parse "- a\n- b")))
  (t/is (= [1 "two" true] (yaml/parse "- 1\n- two\n- true")))
  (t/is (= [["a" "b"] "c"] (yaml/parse "- - a\n  - b\n- c")))
  (t/is (= [nil "b"] (yaml/parse "-\n- b")))
  (t/is (= [nil] (yaml/parse "-")))
  (t/is (= {"tags" ["a" "b"]} (yaml/parse "tags:\n  - a\n  - b")))
  (t/is (= {"matrix" [["x" "y"] ["z"]]}
           (yaml/parse "matrix:\n  - - x\n    - y\n  - - z")))
  (t/testing "list item with nested block value"
    (t/is (= [{"name" "a"} {"name" "b"}]
             (yaml/parse "- name: a\n- name: b")))))

;; ─── Keys ──────────────────────────────────────────────────────────────────

(t/deftest test-keys
  (t/is (= {"my key" "v"} (yaml/parse "\"my key\": v")))
  (t/is (= {"k'e'y" "v"} (yaml/parse "'k'e'y': v")))
  (t/testing "keys stay strings (pi JS object keys)"
    (t/is (= {"123" "v"} (yaml/parse "123: v")))
    (t/is (= {"true" "v"} (yaml/parse "true: v"))))
  (t/is (= {"a" "first" "b" "second"} (yaml/parse "a: first\nb: second")))
  (t/testing "duplicate keys: last wins (pi throws)"
    (t/is (= {"a" "second"} (yaml/parse "a: first\na: second")))))

;; ─── Input edge cases ──────────────────────────────────────────────────────

(t/deftest test-input-edge-cases
  (t/is (nil? (yaml/parse nil)))
  (t/is (nil? (yaml/parse "")))
  (t/is (nil? (yaml/parse "   ")))
  (t/is (nil? (yaml/parse "\n\n")))
  (t/is (nil? (yaml/parse "# comment only")))
  (t/testing "CRLF and lone CR normalized"
    (t/is (= {"a" "b"} (yaml/parse "a: b\r\n")))
    (t/is (= {"a" "b", "c" "d"} (yaml/parse "a: b\rc: d"))))
  (t/is (= {"a" "b"} (yaml/parse "a: b\n\n")))
  (t/is (= {"a" "b"} (yaml/parse "a: b\n   ")))
  (t/is (= {"a" "b"} (yaml/parse "a: b"))))

;; ─── Real-world frontmatter ────────────────────────────────────────────────

(t/deftest test-real-world-frontmatter
  (t/is (= {"name" "my-skill"
            "description" "This skill does something\nvery useful across lines.\n"
            "license" "MIT"
            "metadata" {"category" "dev", "tags" ["cli" "docs"]}
            "allowed-tools" ["read" "edit"]
            "disable-model-invocation" false}
           (yaml/parse (str "name: my-skill\n"
                            "description: |\n"
                            "  This skill does something\n"
                            "  very useful across lines.\n"
                            "license: MIT\n"
                            "metadata:\n"
                            "  category: dev\n"
                            "  tags:\n"
                            "    - cli\n"
                            "    - docs\n"
                            "allowed-tools:\n"
                            "  - read\n"
                            "  - edit\n"
                            "disable-model-invocation: false\n"))))
  (t/is (= {"name" "s2", "description" "One line."}
           (yaml/parse "---\nname: s2\ndescription: One line.\n---\n# body"))))
