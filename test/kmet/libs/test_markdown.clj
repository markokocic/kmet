(ns kmet.libs.test-markdown
  "Parser tests: kmet.libs.markdown produces a pure-data AST — no ANSI,
   no width. Renderer behavior is covered by the tui component tests."
  (:require [clojure.test :as t]
            [clojure.string :as str]
            [kmet.libs.markdown :as md]))

;; ─── parse-inline ──────────────────────────────────────────────────────────

(t/deftest test-inline-plain-text
  (t/is (= [{:type :text :s "hello world"}]
           (md/parse-inline "hello world"))))

(t/deftest test-inline-bold
  (t/is (= [{:type :strong :content [{:type :text :s "bold"}]}]
           (md/parse-inline "**bold**"))))

(t/deftest test-inline-italic
  (t/is (= [{:type :em :content [{:type :text :s "italic"}]}]
           (md/parse-inline "*italic*"))))

(t/deftest test-inline-strikethrough
  (t/is (= [{:type :del :content [{:type :text :s "gone"}]}]
           (md/parse-inline "~~gone~~"))))

(t/deftest test-inline-code
  (t/is (= [{:type :text :s "Use "}
            {:type :code :s "code"}
            {:type :text :s " here"}]
           (md/parse-inline "Use `code` here"))))

(t/deftest test-inline-link
  (t/is (= [{:type :link :url "http://example.com"
             :text [{:type :text :s "text"}]}]
           (md/parse-inline "[text](http://example.com)"))))

(t/deftest test-inline-nested-formatting
  (t/is (= [{:type :strong :content [{:type :text :s "a"}]}
            {:type :text :s " and "}
            {:type :em :content [{:type :text :s "b"}]}]
           (md/parse-inline "**a** and *b*")))
  ;; The end-marker search consumes the inner marker: ***x*** is strong("*x") + "*"
  (t/is (= [{:type :strong :content [{:type :text :s "*x"}]}
            {:type :text :s "*"}]
           (md/parse-inline "***x***"))))

(t/deftest test-inline-unclosed-markers-are-plain-text
  (t/is (= [{:type :text :s "*unclosed"}]
           (md/parse-inline "*unclosed")))
  (t/is (= [{:type :text :s "[broken"}]
           (md/parse-inline "[broken"))))

;; ─── parse: blocks ─────────────────────────────────────────────────────────

(t/deftest test-parse-paragraph
  (t/is (= [{:type :paragraph :content [{:type :text :s "hello"}]}]
           (md/parse "hello"))))

(t/deftest test-parse-blank-line
  (t/is (= [{:type :paragraph :content [{:type :text :s "a"}]}
            {:type :blank}
            {:type :paragraph :content [{:type :text :s "b"}]}]
           (md/parse "a\n\nb"))))

(t/deftest test-parse-heading
  (t/is (= [{:type :heading :level 2 :content [{:type :text :s "Section"}]}]
           (md/parse "## Section")))
  (t/is (= [{:type :heading :level 1 :content [{:type :text :s "Title"}]}]
           (md/parse "# Title"))))

(t/deftest test-parse-fenced-code
  (t/is (= [{:type :code :lang "clojure" :text "(defn f [] 1)"}]
           (md/parse "```clojure\n(defn f [] 1)\n```")))
  (t/is (= [{:type :code :lang "" :text ""}]
           (md/parse "```\n```"))))

(t/deftest test-parse-code-in-list-item
  ;; An indent-deeper fence stays inside the item as a :blocks entry; the
  ;; fence's leading whitespace is stripped from interior lines
  (t/is (= [{:type :ul
             :items [{:content [[{:type :text :s "a"}]]
                      :blocks [{:type :code :lang "clj" :text "(defn f [] 1)"}]}]}]
           (md/parse "- a\n  ```clj\n  (defn f [] 1)\n  ```"))))

(t/deftest test-parse-table
  (t/is (= [{:type :table
             :header [[{:type :text :s "a"}] [{:type :text :s "b"}]]
             :rows [[[{:type :text :s "1"}] [{:type :text :s "2"}]]]
             :aligns [nil nil]
             :raw "| a | b |\n|---|---|\n| 1 | 2 |"}]
           (md/parse "| a | b |\n|---|---|\n| 1 | 2 |"))))

(t/deftest test-parse-table-alignments
  (t/is (= [:left :center :right nil]
           (:aligns (first (md/parse "| l | c | r | n |\n|:--|:--:|--:|--|\n| 1 | 2 | 3 | 4 |"))))))

(t/deftest test-parse-table-inline-formatting
  (let [t (first (md/parse "| **b** | `c` |\n|---|---|\n| x | y |"))]
    (t/is (= :strong (:type (first (first (:header t))))))
    (t/is (= :code (:type (first (second (:header t))))))))

(t/deftest test-parse-table-no-separator-is-paragraph
  ;; A pipe row without a following separator line is just a paragraph
  (t/is (= :paragraph (:type (first (md/parse "| a | b |")))))
  (t/is (= :paragraph (:type (first (md/parse "| a | b |\n| c | d |"))))))

(t/deftest test-parse-table-short-rows-padded
  (let [t (first (md/parse "| a | b | c |\n|---|---|---|\n| 1 | 2 |"))]
    (t/is (= 3 (count (:header t))))
    (t/is (= 3 (count (first (:rows t)))))
    (t/is (= [] (nth (first (:rows t)) 2)))))

(t/deftest test-parse-table-after-content
  ;; Table detection must not swallow the preceding paragraph
  (t/is (= [:paragraph :table]
           (mapv :type (md/parse "intro\n| a |\n|---|\n| 1 |")))))

(t/deftest test-parse-quoted-pipe-line
  ;; A pipe inside a blockquote stays a quote, not a table
  (t/is (= :quote (:type (first (md/parse "> | a |"))))))

(t/deftest test-parse-ul-ol
  (t/is (= [{:type :ul :items [{:content [[{:type :text :s "a"}]]}
                               {:content [[{:type :text :s "b"}]]}]}]
           (md/parse "- a\n- b")))
  (t/is (= [{:type :ol :items [{:content [[{:type :text :s "first"}]]}
                               {:content [[{:type :text :s "second"}]]}]}]
           (md/parse "1. first\n2. second"))))

(t/deftest test-parse-nested-ul
  (t/is (= [{:type :ul
             :items [{:content [[{:type :text :s "a"}]]}
                     {:content [[{:type :text :s "b"}]]
                      :blocks [{:type :ul
                                :items [{:content [[{:type :text :s "c"}]]}]}]}]}]
           (md/parse "- a\n- b\n  - c"))))

(t/deftest test-parse-deeply-nested-list
  (t/is (= [{:type :ul
             :items [{:content [[{:type :text :s "l1"}]]
                      :blocks [{:type :ul
                                :items [{:content [[{:type :text :s "l2"}]]
                                         :blocks [{:type :ul
                                                   :items [{:content [[{:type :text :s "l3"}]]}]}]}]}]}]}]
           (md/parse "- l1\n  - l2\n    - l3"))))

(t/deftest test-parse-mixed-ul-ol-nesting
  (t/is (= [{:type :ol
             :items [{:content [[{:type :text :s "ordered"}]]
                      :blocks [{:type :ul
                                :items [{:content [[{:type :text :s "nested"}]]}
                                        {:content [[{:type :text :s "another"}]]}]}]}
                     {:content [[{:type :text :s "second"}]]}]}]
           (md/parse "1. ordered\n   - nested\n   - another\n2. second"))))

(t/deftest test-parse-list-unindent-returns-to-root
  ;; A shallower list line closes the nested list and continues the root list
  (t/is (= [{:type :ul
             :items [{:content [[{:type :text :s "a"}]]
                      :blocks [{:type :ul :items [{:content [[{:type :text :s "nested"}]]}]}]}
                     {:content [[{:type :text :s "b"}]]}]}]
           (md/parse "- a\n  - nested\n- b"))))

(t/deftest test-parse-blank-line-inside-list
  ;; A blank line does NOT close the list: it becomes a :blank pseudo-item
  ;; (loose list), so following items stay nested.
  (t/is (= [{:type :ul
             :items [{:content [[{:type :text :s "a"}]]}
                     {:type :blank}
                     {:content [[{:type :text :s "b"}]]}]}]
           (md/parse "- a\n\n- b")))
  (t/is (= [{:type :ul
             :items [{:content [[{:type :text :s "a"}]]
                      :blocks [{:type :ul
                                :items [{:content [[{:type :text :s "b"}]]}
                                        {:type :blank}
                                        {:content [[{:type :text :s "c"}]]}]}]}]}]
           (md/parse "- a\n  - b\n\n  - c"))))

(t/deftest test-parse-list-continuation-lines
  ;; Indented non-list lines continue the last item as extra :content lines
  (t/is (= [{:type :ul
             :items [{:content [[{:type :text :s "first line"}]
                                [{:type :text :s "continuation"}]]}
                     {:content [[{:type :text :s "second"}]]}]}]
           (md/parse "- first line\n  continuation\n- second")))
  (t/is (= [{:type :ul
             :items [{:content [[{:type :text :s "a"}]
                                [{:type :text :s "cont"}]]
                      :blocks [{:type :ul :items [{:content [[{:type :text :s "b"}]]}]}]}]}]
           (md/parse "- a\n  cont\n  - b"))))

(t/deftest test-parse-list-marker-type-change
  ;; A marker-type change at the same indent starts a NEW list; both nested
  ;; lists attach to the same parent item (:lists is a vector).
  (t/is (= [{:type :ul
             :items [{:content [[{:type :text :s "a"}]]
                      :blocks [{:type :ul
                                :items [{:content [[{:type :text :s "b"}]]}]}
                               {:type :ol
                                :items [{:content [[{:type :text :s "c"}]]}]}]}]}]
           (md/parse "- a\n  - b\n  1. c")))
  ;; At root level the two lists are siblings
  (t/is (= [{:type :ul :items [{:content [[{:type :text :s "a"}]]}]}
            {:type :ol :items [{:content [[{:type :text :s "b"}]]}]}]
           (md/parse "- a\n1. b"))))

(t/deftest test-parse-quote
  (t/is (= [{:type :quote :content [{:type :text :s "quoted"}]}]
           (md/parse "> quoted"))))

(t/deftest test-parse-hr
  (t/is (= [{:type :hr}] (md/parse "---")))
  (t/is (= [{:type :hr}] (md/parse "___")))
  (t/is (= [{:type :hr}] (md/parse "***"))))

(t/deftest test-parse-empty-input
  ;; split-lines "" yields [""] and ^\s*$ matches empty, so a faithful port
  ;; produces one :blank (the original renderer emitted one padded line).
  (t/is (= [{:type :blank}] (md/parse "")))
  (t/is (= [{:type :blank}] (md/parse nil))))

;; ─── Inline edge cases ─────────────────────────────────────────────────────

(t/deftest test-inline-unclosed-markers
  (t/is (= [{:type :text :s "**x"}] (md/parse-inline "**x")))
  (t/is (= [{:type :text :s "*"}] (md/parse-inline "*")))
  (t/is (= [{:type :text :s "~"}] (md/parse-inline "~")))
  (t/is (= [{:type :text :s "~~x"}] (md/parse-inline "~~x")))
  (t/is (= [{:type :text :s "[a]("}] (md/parse-inline "[a](")))
  (t/is (= [{:type :text :s "a*b"}] (md/parse-inline "a*b"))))

(t/deftest test-inline-empty-link
  (t/is (= [{:type :link :url "" :text []}] (md/parse-inline "[]()"))))

(t/deftest test-inline-bold-containing-code
  (t/is (= [{:type :strong
             :content [{:type :text :s "a "}
                       {:type :code :s "b"}
                       {:type :text :s " c"}]}]
           (md/parse-inline "**a `b` c**"))))

(t/deftest test-inline-nested-em-in-strong
  (t/is (= [{:type :strong
             :content [{:type :text :s "a "}
                       {:type :em :content [{:type :text :s "b"}]}
                       {:type :text :s " c"}]}]
           (md/parse-inline "**a *b* c**"))))

(t/deftest test-inline-backslash-is-literal
  ;; No backslash escapes: \* stays literal (pi has preserveBackslashEscapes)
  (t/is (= [{:type :text :s "\\*x"}] (md/parse-inline "\\*x"))))

(t/deftest test-inline-link-with-formatting
  (t/is (= [{:type :link :url "http://x.com"
             :text [{:type :strong :content [{:type :text :s "b"}]}]}]
           (md/parse-inline "[**b**](http://x.com)"))))

;; ─── Block edge cases ──────────────────────────────────────────────────────

(t/deftest test-parse-hr-spaced
  ;; CommonMark thematic breaks: 3+ of the SAME marker, spaces allowed
  (t/is (= [{:type :hr}] (md/parse "* * *")))
  (t/is (= [{:type :hr}] (md/parse "- - -")))
  (t/is (= [{:type :hr}] (md/parse "*  *  *")))
  (t/is (= [{:type :hr}] (md/parse "   ---")))
  ;; Mixed markers or two markers are NOT hr
  (t/is (not= [{:type :hr}] (md/parse "-*-")))
  (t/is (not= [{:type :hr}] (md/parse "* *")))
  ;; A single marker with content stays a list item
  (t/is (= :ul (:type (first (md/parse "* item"))))))

(t/deftest test-parse-heading-no-space-is-paragraph
  (t/is (= :paragraph (:type (first (md/parse "###")))))
  (t/is (= :paragraph (:type (first (md/parse "#no-space"))))))

(t/deftest test-parse-fence-lang-variants
  (t/is (= "c++" (:lang (first (md/parse "```c++\nint x;\n```")))))
  (t/is (= "f#" (:lang (first (md/parse "```f#\nlet x = 1\n```")))))
  (t/is (= "bash-script" (:lang (first (md/parse "```bash-script\necho hi\n```")))))
  ;; CommonMark info string: optional space between fence and lang
  (t/is (= "clojure" (:lang (first (md/parse "``` clojure\n(println 1)\n```"))))))

(t/deftest test-parse-code-preserves-interior-blanks
  (t/is (= "a\n\nb" (:text (first (md/parse "```\na\n\nb\n```")))))
  (t/is (= "a\n" (:text (first (md/parse "```\na\n\n```")))))
  (t/is (= "" (:text (first (md/parse "```\n```"))))))

(t/deftest test-parse-unclosed-fence-flushed
  ;; EOF without a closing fence still emits the accumulated code
  (t/is (= [{:type :code :lang "" :text "code"}]
           (md/parse "```\ncode")))
  (t/is (= [{:type :ul
             :items [{:content [[{:type :text :s "a"}]]
                      :blocks [{:type :code :lang "clj" :text "(x)"}]}]}]
           (md/parse "- a\n  ```clj\n  (x)"))))

(t/deftest test-parse-code-in-deep-list
  (t/is (= [{:type :ul
             :items [{:content [[{:type :text :s "a"}]]
                      :blocks [{:type :ul
                                :items [{:content [[{:type :text :s "b"}]]
                                         :blocks [{:type :code :lang "" :text "x"}]}]}]}]}]
           (md/parse "- a\n  - b\n    ```\n    x\n    ```"))))

(t/deftest test-parse-table-no-leading-pipe
  (t/is (= :table (:type (first (md/parse "a | b\n--- | ---\n1 | 2")))))
  (t/is (= 2 (count (:header (first (md/parse "a | b\n--- | ---\n1 | 2")))))))

(t/deftest test-parse-table-row-formatting
  (let [t (first (md/parse "| a | b |\n|---|---|\n| **1** | `2` |"))]
    (t/is (= :strong (:type (first (first (first (:rows t)))))))
    (t/is (= :code (:type (first (second (first (:rows t)))))))))

(t/deftest test-parse-table-at-start
  (t/is (= [:table] (mapv :type (md/parse "| a |\n|---|\n| 1 |")))))

(t/deftest test-parse-list-tab-indent
  (t/is (= [{:type :ul
             :items [{:content [[{:type :text :s "a"}]]
                      :blocks [{:type :ul :items [{:content [[{:type :text :s "b"}]]}]}]}]}]
           (md/parse "- a\n\t- b"))))

(t/deftest test-parse-list-nested-unindent-one-level
  ;; - c (indent 4) nests under b; - d (indent 2) is b's sibling
  (t/is (= [{:type :ul
             :items [{:content [[{:type :text :s "a"}]]
                      :blocks [{:type :ul
                                :items [{:content [[{:type :text :s "b"}]]
                                         :blocks [{:type :ul :items [{:content [[{:type :text :s "c"}]]}]}]}
                                        {:content [[{:type :text :s "d"}]]}]}]}]}]
           (md/parse "- a\n  - b\n    - c\n  - d"))))

(t/deftest test-parse-list-multiple-blanks
  (t/is (= [{:type :ul
             :items [{:content [[{:type :text :s "a"}]]}
                     {:type :blank}
                     {:type :blank}
                     {:content [[{:type :text :s "b"}]]}]}]
           (md/parse "- a\n\n\n- b"))))

(t/deftest test-parse-list-continuation-formatting
  (t/is (= [{:type :ul
             :items [{:content [[{:type :text :s "a"}]
                                [{:type :strong :content [{:type :text :s "bold"}]}
                                 {:type :text :s " cont"}]]}]}]
           (md/parse "- a\n  **bold** cont"))))

(t/deftest test-parse-list-empty-marker-is-paragraph
  ;; "- " with no content is not a list line (needs \s+ content)
  (t/is (= :paragraph (:type (first (md/parse "- "))))))

(t/deftest test-parse-continuation-after-nested-is-root
  ;; Pinned behavior: a line at the nested list's own indent closes the lists
  ;; and becomes a root paragraph (documented limitation)
  (t/is (= [{:type :ul
             :items [{:content [[{:type :text :s "a"}]]
                      :blocks [{:type :ul :items [{:content [[{:type :text :s "b"}]]}]}]}]}
            {:type :paragraph :content [{:type :text :s "  cont"}]}]
           (md/parse "- a\n  - b\n  cont"))))

(t/deftest test-parse-quote-with-list-marker
  (t/is (= [{:type :quote :content [{:type :text :s "- x"}]}]
           (md/parse "> - x"))))

;; ─── Malformed / degenerate input ──────────────────────────────────────────

(t/deftest test-malformed-inline-marker-runs
  ;; Degenerate runs with no content between markers stay literal (pi: marked
  ;; requires content between ~~, **)
  (t/is (= [{:type :text :s "****"}] (md/parse-inline "****")))
  (t/is (= [{:type :text :s "**"}] (md/parse-inline "**")))
  (t/is (= [{:type :text :s "~~~~"}] (md/parse-inline "~~~~")))
  (t/is (= [{:type :text :s "~~~~~~"}] (md/parse-inline "~~~~~~")))
  (t/is (= [{:type :text :s "~~~"}] (md/parse-inline "~~~")))
  (t/is (= [{:type :text :s "*x"}] (md/parse-inline "*x"))))

(t/deftest test-malformed-headings
  (t/is (= :paragraph (:type (first (md/parse "####### seven")))))
  (t/is (= 6 (:level (first (md/parse "###### six")))))
  (t/is (= :paragraph (:type (first (md/parse "#"))))))

(t/deftest test-malformed-unclosed-backtick
  ;; An unmatched trailing backtick stays literal text
  (t/is (= [{:type :code :s "a"} {:type :text :s "b`"}]
           (md/parse-inline "`a`b`"))))

(t/deftest test-malformed-link-followed-by-parens
  (t/is (= [{:type :link :url "b" :text [{:type :text :s "a"}]}
            {:type :text :s "(c)"}]
           (md/parse-inline "[a](b)(c)"))))

(t/deftest test-malformed-image-is-text-plus-link
  ;; No image support: ![alt](img) parses as "!" + link
  (t/is (= [{:type :text :s "!"}
            {:type :link :url "img" :text [{:type :text :s "alt"}]}]
           (md/parse-inline "![alt](img)"))))

(t/deftest test-malformed-reference-link-is-paragraph
  (t/is (= :paragraph (:type (first (md/parse "[x]: http://url"))))))

(t/deftest test-malformed-list-markers
  ;; No task-list or 1)-style marker support: they stay literal content
  (t/is (= [{:type :ul :items [{:content [[{:type :text :s "[ ] task"}]]}]}]
           (md/parse "- [ ] task")))
  (t/is (= :paragraph (:type (first (md/parse "1) item")))))
  (t/is (= :paragraph (:type (first (md/parse "-"))))))

(t/deftest test-malformed-quotes
  (t/is (= [{:type :quote :content [{:type :text :s "> x"}]}] (md/parse ">> x")))
  (t/is (= [{:type :quote :content []}] (md/parse "> ")))
  (t/is (= [{:type :quote :content []}] (md/parse ">"))))

(t/deftest test-malformed-tables
  ;; || produces an empty middle cell; a pipe inside backticks splits (GFM
  ;; requires escaping); rows are padded/truncated to the header width
  (t/is (= 3 (count (:header (first (md/parse "| a || b |\n|---|---|"))))))
  (t/is (= 3 (count (:header (first (md/parse "| `x|y` | 2 |\n|-----|---|"))))))
  (t/is (= 1 (count (:header (first (md/parse "| a |\n|---|\n| 1 | 2 |"))))))
  (t/is (= [[] [] []] (:header (first (md/parse "||||\n|---|---|")))))
  (t/is (= :paragraph (:type (first (md/parse "|"))))))

(t/deftest test-malformed-fences
  (t/is (= "`" (:lang (first (md/parse "````\nx\n````")))))
  (t/is (= [:code :code] (mapv :type (md/parse "```\n```\n```\n```"))))
  (t/is (= [:code :paragraph] (mapv :type (md/parse "```\n```\ntext"))))
  (t/is (= :paragraph (:type (first (md/parse "~~~\nx\n~~~"))))))

(t/deftest test-malformed-indentation
  ;; No indented-code support: 4-space lines are paragraphs; tab-only lines
  ;; are blank
  (t/is (= :paragraph (:type (first (md/parse "    four spaces indent")))))
  (t/is (= [{:type :blank}] (md/parse "\t\t")))
  (t/is (= [{:type :paragraph :content [{:type :text :s "\t\ttab tab"}]}]
           (md/parse "\t\ttab tab"))))

(t/deftest test-malformed-list-vs-table
  ;; A pipe inside list content must not turn the item into a table header
  (t/is (= [{:type :ul :items [{:content [[{:type :text :s "a | b"}]]}]}
            {:type :paragraph :content [{:type :text :s "---|---"}]}
            {:type :paragraph :content [{:type :text :s "| 1 | 2 |"}]}]
           (md/parse "- a | b\n---|---\n| 1 | 2 |"))))

(t/deftest test-malformed-crlf
  (t/is (= [{:type :paragraph :content [{:type :text :s "a"}]}
            {:type :ul :items [{:content [[{:type :text :s "b"}]]}]}]
           (md/parse "a\r\n- b\r\n")))
  (t/is (= "x\ny" (:text (first (md/parse "```\r\nx\r\ny\r\n```"))))))

(t/deftest test-malformed-double-spaces
  ;; No hard-break (two-space line ending) support; runs of spaces are kept
  (t/is (= [{:type :paragraph :content [{:type :text :s "a  b"}]}]
           (md/parse "a  b"))))

(t/deftest test-malformed-numbering
  (t/is (= :ol (:type (first (md/parse "3. three\n4. four")))))
  (t/is (= :ol (:type (first (md/parse "999999999999999999999. big"))))))

(t/deftest test-malformed-long-unbreakable-word
  (let [ast (md/parse (apply str (repeat 300 "x")))]
    (t/is (= :paragraph (:type (first ast))))
    (t/is (= 300 (count (:s (first (:content (first ast)))))))))

(t/deftest test-malformed-deep-list-does-not-crash
  (let [src (str/join "\n" (map #(str (apply str (repeat (* 2 %) \space)) "- item") (range 12)))
        ast (md/parse src)]
    (t/is (= 1 (count ast)))
    (t/is (= :ul (:type (first ast))))))

(t/deftest test-parse-code-fence-inside-list-attaches-to-last-item
  ;; The fence belongs to the item that introduced the deeper indent
  (t/is (= [{:type :ul
             :items [{:content [[{:type :text :s "a"}]
                                [{:type :text :s "cont"}]]
                      :blocks [{:type :code :lang "" :text "x"}]}]}]
           (md/parse "- a\n  cont\n  ```\n  x\n  ```"))))

;; ─── parse: round-trip sanity ─────────────────────────────────────────────

(t/deftest test-parse-mixed-document
  (let [ast (md/parse "# Title\n\nSome **bold** text.\n\n```clj\n(println 1)\n```\n\n- item")]
    (t/is (= [:heading :blank :paragraph :blank :code :blank :ul]
             (mapv :type ast)))
    (t/is (some #(= :strong (:type %)) (:content (nth ast 2))))))
