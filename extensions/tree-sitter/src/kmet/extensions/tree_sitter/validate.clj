(ns kmet.extensions.tree-sitter.validate
  "Turn file content into a syntax-problem report.

   Backends behind the dispatch routes:

   - tree-sitter: `parse --wasm`, walk the sexp tree for ERROR nodes and
     zero-width named nodes (missing), plus the trailing per-file stats
     line which carries MISSING records for tokens that never made it
     into the tree (`def f(:` → `(MISSING \") …)`). Capped. Note that
     error-TOLERANT recovery can mask some mistakes entirely (python's
     `def f(:` recovers to a clean tree and the MISSING record is the
     only signal) — the hook catches most, not all, mistakes. Same
     trade-off rab / pi-tree-sitter make.
   - delimiter: comment/string-aware bracket-balance scan (clojure flavor:
     ; line comments, backslash escapes, multi-line strings), reporting
     the first unmatched closer or unclosed opener with its position.

   A problem is {:kind :error|:missing|:unclosed|:stray-closer
                 :line :col :expected :snippet} — lines/cols are 1-based."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [kmet.extensions.tree-sitter.cli :as cli]
            [kmet.extensions.tree-sitter.paths :as paths]
            [kmet.extensions.tree-sitter.sexp :as sexp]))

(def ^:private max-problems 10)

(def ^:private closers {\( \) \[ \] \{ \}})

;; ─── tree-sitter backend ──────────────────────────────────────────────────

(defn- pos->line-col
  "1-based line/col from a CLI [row col] position."
  [[row col]]
  [(inc row) (inc col)])

(defn stats-record
  "The (ERROR …) / (MISSING …) record on a file's stats line, parsed into
   {:kind :line :col :expected}, or nil when the line carries none."
  [stats-line]
  (when-let [m (re-find #"\((ERROR|MISSING) (.*)\)$" (str/trim (str stats-line)))]
    (let [[_ kind detail] m
          kind-kw (if (= "ERROR" kind) :error :missing)
          pos (re-find #"\[(\d+),\s*(\d+)\]" detail)
          expected (when (and (= :missing kind-kw) pos)
                     (let [tok (subs detail 0 (str/index-of detail "["))
                           tok (str/trim tok)
                           tok (if (str/starts-with? tok "\"")
                                 (subs tok 1)
                                 tok)
                           tok (if (str/ends-with? tok "\"")
                                 (subs tok 0 (dec (count tok)))
                                 tok)]
                       (not-empty tok)))]
      {:kind kind-kw
       :line (inc (Long/parseLong (nth pos 1)))
       :col (inc (Long/parseLong (nth pos 2)))
       :expected expected})))

(defn- zero-width?
  "Missing nodes surface as zero-width named children (e.g.
   `condition: (identifier [1, 6] - [1, 6])`). Only real nodes — field
   wrappers are not nodes."
  [node]
  (and (sexp/node? node)
       (= (sexp/start-pos node) (sexp/end-pos node))))

(defn- tree-problems
  "ERROR nodes + zero-width (missing) nodes from a parsed tree, capped."
  [tree]
  (letfn [(children-nodes [node]
            (keep #(if (map? %) (:node %) %) (sexp/children node)))
          (walk [node]
            (if-not (sexp/node? node)
              nil
              (cond
                (= "ERROR" (sexp/node-type node))
                (let [[l c] (pos->line-col (sexp/start-pos node))]
                  (cons {:kind :error :line l :col c :expected nil :snippet nil}
                        (mapcat walk (children-nodes node))))
                (zero-width? node)
                (let [[l c] (pos->line-col (sexp/start-pos node))]
                  (cons {:kind :missing :line l :col c
                         :expected (sexp/node-type node) :snippet nil}
                        (mapcat walk (children-nodes node))))
                :else (mapcat walk (children-nodes node)))))]
    (take max-problems (walk tree))))

(defn- with-snippets
  "Attach 1-based :line snippet text from SRC-LINES to each problem."
  [src-lines problems]
  (map (fn [p]
         (assoc p :snippet
                (not-empty
                 (str/trim (str (nth src-lines (dec (:line p)) nil))))))
       problems))

(defn problems-from-tree
  "ERROR + zero-width (missing) problems from a parsed tree, with
   snippet text from SRC-LINES. Capped."
  [tree src-lines]
  (vec (take max-problems (with-snippets src-lines (tree-problems tree)))))

(defn- problems-from-output
  "Problems from a file's tree + stats line, with snippet text from
   SRC-LINES. The stats MISSING record is the ONLY signal for some
   recoverable errors (python `def f(:`) — include it when the tree walk
   didn't already flag the same position."
  [tree stats-line src-lines]
  (let [tree-ps (tree-problems tree)
        stats-p (when-let [sp (stats-record stats-line)]
                  (when-not (some #(and (= (:kind %) (:kind sp))
                                        (= (:line %) (:line sp)))
                                  tree-ps)
                    sp))]
    (vec (take max-problems (with-snippets src-lines (concat tree-ps (when stats-p [stats-p])))))))

(defn parse-problems!
  "Tree-sitter backend: write CONTENT to a temp file with PATH's extension,
   parse it through the cached grammar and return
   {:problems [...] :via :tree-sitter}. Throws ::parse-failed on CLI or
   infra failure (callers treat that as pass-through). LANG is accepted for
   call-site symmetry and is currently unused (the CLI discovers the
   language per file extension). Opts: {:base dir :parse-runner fn}."
  ([path content lang] (parse-problems! path content lang nil))
  ([path content _lang {:keys [base] :as opts}]
   (let [ext (last (str/split (str path) #"\."))
         ;; unique per invocation: hooks run in parallel tool calls and a
         ;; fixed name would race on the same temp file
         tmp (fs/path (paths/root base)
                      (str "validate-" (System/nanoTime) "." ext))]
     (spit (str tmp) content)
     (try
       (let [res ((or (:parse-runner opts) cli/exec!)
                  ["parse" "--wasm"
                   "--config-path" (str (paths/config-path base))
                   (str tmp)]
                  {:base base
                   :env {"TREE_SITTER_LIBDIR" (str (paths/libs-dir base))}})
             out (str (:out res))
             ;; the tree is the leading '('... form; the stats line (with a
             ;; tab) trails it when the file has problems — keep only the
             ;; tree portion for sexp parsing
             tree-text (if (str/starts-with? (str/trim out) "(")
                         (first (str/split out #"\n(?=[^\s])"))
                         (first (str/split-lines out)))
             stats-line (some #(when (str/includes? % "\t") %)
                              (str/split-lines out))
             tree (sexp/parse-tree tree-text)
             src-lines (str/split-lines (str content))
             problems (if tree
                        (problems-from-output tree stats-line src-lines)
                        ;; no tree at all (empty content?) — nothing to report
                        [])]
         ;; nil when clean — hooks treat any non-nil result as a block,
         ;; so an empty problem vector must not flow on
         (when (seq problems)
           {:problems problems :via :tree-sitter}))
       (finally (fs/delete-if-exists tmp))))))

;; ─── delimiter backend (clojure family fallback) ──────────────────────────

(defn- delimiter-problem
  "First imbalance in SOURCE (clojure flavor: ; line comments, backslash
   escapes, multi-line strings), or nil when balanced."
  [content]
  (let [n (count content)
        closer-set (set (vals closers))
        open (fn [[opener line col]]
               {:kind :unclosed :line line :col col
                :expected (str (get closers opener))
                :snippet (str opener)})]
    (loop [i 0, line 1, col 1, stack (), str-open nil, state :code]
      (cond
        (>= i n)
        (or (some-> (first stack) open)
            (when str-open
              {:kind :unclosed :line (:line str-open) :col (:col str-open)
               :expected nil :snippet "string"}))

        :else
        (let [ch (nth content i)
              nl? (= ch \newline)
              i' (inc i)
              line' (if nl? (inc line) line)
              col' (if nl? 1 (inc col))]
          (case state
            :comment (recur i' line' col' stack str-open
                            (if nl? :code :comment))

            :string (cond
                      ;; a backslash escapes exactly one following character
                      (= ch \\) (recur (min n (+ i 2)) line (+ col 2)
                                       stack str-open :string)
                      (= ch \") (recur i' line' col' stack nil :code)
                      :else (recur i' line' col' stack str-open :string))

            :code (cond
                    (= ch \;) (recur i' line' col' stack str-open :comment)
                    (= ch \") (recur i' line' col' stack
                                     {:line line :col col} :string)
                    (= ch \\) (recur (min n (+ i 2)) line' col' stack
                                     str-open :code)
                    (contains? closers ch)
                    (recur i' line' col' (conj stack [ch line col]) str-open
                           :code)
                    (contains? closer-set ch)
                    (let [[opener _oline _ocol] (first stack)]
                      (if (and opener (= (get closers opener) ch))
                        (recur i' line' col' (rest stack) str-open :code)
                        {:kind :stray-closer :line line :col col
                         :expected (some-> opener str closers)
                         :snippet (str ch)}))
                    :else (recur i' line' col' stack str-open :code))))))))

(defn delimiter-problems
  "Vector wrapper over delimiter-problem: 0 or 1 entries."
  [content]
  (if-some [p (delimiter-problem content)] [p] []))

;; ─── reports ──────────────────────────────────────────────────────────────

(defn report-text
  "Multi-line human-readable report from a problems vector."
  [problems]
  (str/join "\n"
            (for [{:keys [kind line col expected snippet]} problems]
              (str "line " line ", col " col ": "
                   (case kind
                     :error (str "syntax error"
                                 (when snippet (str " — " snippet)))
                     :missing (str "missing " (or expected "token"))
                     :unclosed (str "'" snippet "' opened here is never closed"
                                    (when expected (str " — expected " expected)))
                     :stray-closer (str "'" snippet "' does not match anything"
                                        (when expected
                                          (str " — expected " expected)))
                     "problem")))))
