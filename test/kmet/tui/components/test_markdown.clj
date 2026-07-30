(ns kmet.tui.components.test-markdown
  (:require [clojure.test :as t]
            [kmet.tui.core :as core]
            [kmet.tui.components.markdown :as md]))

;; ─── Construction ───────────────────────────────────────────────────────────

(t/deftest test-markdown-create
  (let [m (md/make-markdown "hello")]
    (t/is (satisfies? core/IComponent m))
    (t/is (= "hello" (md/markdown-get-text m)))))

(t/deftest test-markdown-create-empty
  (let [m (md/make-markdown "")]
    (t/is (satisfies? core/IComponent m))
    (t/is (= "" (md/markdown-get-text m)))))

;; ─── Render plain text ─────────────────────────────────────────────────────

(t/deftest test-markdown-render-plain
  (let [m (md/make-markdown "hello" :padding-x 0)]
    (let [lines (core/render m 20)]
      (t/is (pos? (count lines)))
      (t/is (some #(.contains % "hello") lines)))))

(t/deftest test-markdown-render-padding
  (let [m (md/make-markdown "hello" :padding-x 2)]
    (let [lines (core/render m 20)]
      (t/is (pos? (count lines)))
      (t/is (pos? (count (first lines)))))))

;; ─── Headings ──────────────────────────────────────────────────────────────

(t/deftest test-markdown-heading-h1
  (let [m (md/make-markdown "# Title" :padding-x 0)]
    (let [lines (core/render m 20)]
      (t/is (pos? (count lines)))
      (t/is (some #(.contains % "Title") lines)))))

(t/deftest test-markdown-heading-h2
  (let [m (md/make-markdown "## Section" :padding-x 0)]
    (let [lines (core/render m 20)]
      (t/is (pos? (count lines)))
      (t/is (some #(.contains % "Section") lines)))))

(t/deftest test-markdown-headings-add-underline
  (let [m (md/make-markdown "# Title\n\n## Section" :padding-x 0)]
    (let [lines (core/render m 20)]
      (t/is (>= (count lines) 4)))))

;; ─── Code blocks ───────────────────────────────────────────────────────────

(t/deftest test-markdown-code-block
  (let [m (md/make-markdown "```clojure\n(defn hello []\n  (println \"hi\"))\n```" :padding-x 0)]
    (let [lines (core/render m 40)]
      (t/is (pos? (count lines)))
      (t/is (some #(.contains % "defn") lines)))))

;; ─── Inline formatting ─────────────────────────────────────────────────────

(t/deftest test-markdown-bold
  (let [m (md/make-markdown "**bold text**" :padding-x 0)]
    (let [lines (core/render m 20)]
      (t/is (pos? (count lines)))
      (t/is (some #(.contains % "bold") lines)))))

(t/deftest test-markdown-italic
  (let [m (md/make-markdown "*italic text*" :padding-x 0)]
    (let [lines (core/render m 20)]
      (t/is (some #(.contains % "italic") lines)))))

(t/deftest test-markdown-code-inline
  (let [m (md/make-markdown "Use `code` here" :padding-x 0)]
    (let [lines (core/render m 20)]
      (t/is (some #(.contains % "code") lines)))))

;; ─── Lists ─────────────────────────────────────────────────────────────────

(t/deftest test-markdown-unordered-list
  (let [m (md/make-markdown "- item one\n- item two" :padding-x 0)]
    (let [lines (core/render m 20)]
      (t/is (some #(.contains % "item one") lines))
      (t/is (some #(.contains % "item two") lines)))))

(t/deftest test-markdown-ordered-list
  (let [m (md/make-markdown "1. first\n2. second" :padding-x 0)]
    (let [lines (core/render m 20)]
      (t/is (some #(.contains % "first") lines))
      (t/is (some #(.contains % "second") lines)))))

;; ─── Blockquotes ───────────────────────────────────────────────────────────

(t/deftest test-markdown-blockquote
  (let [m (md/make-markdown "> quoted text" :padding-x 0)]
    (let [lines (core/render m 30)]
      (t/is (some #(.contains % "quoted") lines)))))

;; ─── Horizontal rules ──────────────────────────────────────────────────────

(t/deftest test-markdown-hr
  (let [m (md/make-markdown "---" :padding-x 0)]
    (let [lines (core/render m 20)]
      (t/is (pos? (count lines))))))

;; ─── Links ─────────────────────────────────────────────────────────────────

(t/deftest test-markdown-link
  (let [m (md/make-markdown "[text](http://example.com)" :padding-x 0)]
    (let [lines (core/render m 40)]
      (t/is (some #(.contains % "text") lines)))))

;; ─── set-text! / append! ───────────────────────────────────────────────────

(t/deftest test-markdown-set-text
  (let [m (md/make-markdown "old")]
    (md/markdown-set-text! m "new")
    (t/is (= "new" (md/markdown-get-text m)))))

(t/deftest test-markdown-append
  (let [m (md/make-markdown "line1")]
    (md/markdown-append! m "line2")
    (t/is (.contains (md/markdown-get-text m) "line1"))
    (t/is (.contains (md/markdown-get-text m) "line2"))))

;; ─── Invalidate ────────────────────────────────────────────────────────────

(t/deftest test-markdown-invalidate
  (let [m (md/make-markdown "hello" :padding-x 0)]
    (core/render m 20)
    (t/is (some? @(:cache-atom m)))
    (core/invalidate m)
    (t/is (nil? @(:cache-atom m)))))

;; ─── Render caching ────────────────────────────────────────────────────────

(t/deftest test-markdown-render-cache
  (let [m (md/make-markdown "hello world" :padding-x 0)]
    (let [lines1 (core/render m 30)
          lines2 (core/render m 30)]
      (t/is (= lines1 lines2)))))

;; ─── Default theme ─────────────────────────────────────────────────────────

(t/deftest test-markdown-default-theme
  (t/is (some? md/default-theme)))
