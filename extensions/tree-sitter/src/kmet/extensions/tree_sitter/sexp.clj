(ns kmet.extensions.tree-sitter.sexp
  "Parser for the tree-sitter CLI's default s-expression output.

   The CLI's `parse` subcommand prints, per file, one tree in this form
   (named nodes only — anonymous tokens and source text are omitted):

     (source [0, 0] - [8, 0]
       (list_lit [0, 0] - [1, 38]
         value: (sym_lit [0, 1] - [0, 3]
           name: (sym_name [0, 1] - [0, 3]))
         (str_lit [4, 7] - [4, 15])))

   Node = [type [row col] [row col] child...]; leaves are bare types with
   a range. Fields appear as a \"name:\" token prefixing a child. Anonymous
   nodes (the `(` / `value` text of clojure lists, string contents) never
   appear, so the tree is roughly half the size of the XML output.

   When a file has syntax problems the CLI appends a stats line (tab-
   separated) after its tree; clean files emit none. ERROR nodes appear in
   the tree; MISSING nodes never do — they surface only as a zero-width
   named child (e.g. `condition: (identifier [1, 6] - [1, 6])`) or in the
   stats line's (MISSING ...) record.

   This replaces the previous clojure.data.xml walk: the dependency is
   bb-shipped but undocumented, and the sexp form carries exactly the
   information the walkers need (type, fields, byte ranges) at half the
   transfer size."
  (:require [clojure.string :as str]))

;; ─── node model ───────────────────────────────────────────────────────────

(defn node?
  "True for a parsed node: [type [srow scol] [erow ecol] child...]."
  [x]
  (and (vector? x) (>= (count x) 3) (string? (nth x 0))
       (vector? (nth x 1)) (vector? (nth x 2))))

(defn node-type
  "Node's type name, or nil for leaves."
  [node]
  (when (node? node) (nth node 0)))

(defn start-pos
  "Node start [row col] (0-based, as printed by the CLI)."
  [node]
  (when (node? node) (nth node 1)))

(defn end-pos
  "Node end [row col] (0-based)."
  [node]
  (when (node? node) (nth node 2)))

(defn children
  "Direct child nodes (including field-prefixed ones; the field name is
   dropped — field queries go through child-by-field)."
  [node]
  (if (node? node) (subvec node 3) []))

(defn child-by-field
  "First child that was printed with FIELD:, or nil."
  [node field]
  (when (node? node)
    (some (fn [c]
            (when (and (map? c) (= field (:name c)))
              (:node c)))
          (subvec node 3))))

;; ─── parsing ──────────────────────────────────────────────────────────────

(defn- tokenize
  "Split CLI output into tokens: parens, brackets (ranges), quoted
   strings, and bare words (node types, `field:` prefixes)."
  [s]
  (re-seq #"[()]|\[[^\]]*\]|\"[^\"]*\"|[^\s()]+" s))

(defn- parse-range
  "Parse a [row, col] token into [row col], or nil when the token isn't
   a range (malformed input — callers treat nil as absent)."
  [tok]
  (when-let [[_ r c] (re-matches #"\[(\d+),\s*(\d+)\]" (str tok))]
    [(Long/parseLong r) (Long/parseLong c)]))

(defn- parse-node
  "Recursive descent over TOKENS starting at index i; returns
   [node next-index]. A node is [type start end child...]; a leaf
   (no children) is [type start end]. `field:` tokens prefix their child
   and come back as {:name field :node child}."
  [tokens n i]
  (let [t (nth tokens i)]
    (if-not (= t "(")
      ;; bare token — either a `field:` prefix or a stray leaf; when a
      ;; field prefix is followed by a node, consume the node into
      ;; {:name field :node child}
      (if (and (str/ends-with? t ":")
               (< (inc i) n)
               (= "(" (nth tokens (inc i))))
        (let [[child j'] (parse-node tokens n (inc i))]
          [{:name (subs t 0 (dec (count t))) :node child} j'])
        [(if (str/ends-with? t ":")
           {:name (subs t 0 (dec (count t))) :node nil}
           t)
         (inc i)])
      (let [type (nth tokens (inc i))
            start (parse-range (nth tokens (+ i 2)))
            end (parse-range (nth tokens (+ i 4)))
            open (+ i 5)]
        (loop [j open, acc []]
          (if (>= j n)
            [nil j]                    ; unbalanced input: bail, never throw
            (let [t (nth tokens j)]
              (cond
                (= t ")") [(into [type start end] acc) (inc j)]
                :else
                (let [[child j'] (parse-node tokens n j)]
                  (if (nil? child)
                    [nil n]
                    (recur j' (conj acc child))))))))))))

(defn parse-tree
  "Parse one CLI tree string into its root node, or nil when the output
   is malformed (missing parens etc. — treat as absent, never throw)."
  [s]
  (let [tokens (vec (tokenize s))
        n (count tokens)]
    (when (pos? n)
      (first (parse-node tokens n 0)))))

