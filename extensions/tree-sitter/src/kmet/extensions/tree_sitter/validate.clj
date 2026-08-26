(ns kmet.extensions.tree-sitter.validate
  "Turn file content into a syntax-problem report.

   Two backends behind the dispatch routes:

   - tree-sitter: `parse --wasm -x`, walk the parsed tree and collect
     ERROR / MISSING nodes (capped). Note that error-TOLERANT recovery can
     mask some mistakes entirely (python's `def f(:` recovers without an
     ERROR node) — the hook catches most, not all, mistakes. Same
     trade-off rab / pi-tree-sitter make.
   - delimiter: comment/string-aware bracket-balance scan (clojure flavor:
     ; line comments, backslash escapes, multi-line strings), reporting
     the first unmatched closer or unclosed opener with its position.

   A problem is {:kind :error|:missing|:unclosed|:stray-closer
                 :line :col :expected :snippet} — lines/cols are 1-based."
  (:require [babashka.fs :as fs]
            [clojure.data.xml :as xml]
            [clojure.string :as str]
            [kmet.extensions.tree-sitter.cli :as cli]
            [kmet.extensions.tree-sitter.paths :as paths]))

(def ^:private max-problems 10)

(def ^:private closers {\( \) \[ \] \{ \}})

;; ─── tree-sitter backend ──────────────────────────────────────────────────

(defn- tag-of [el] (some-> (:tag el) name))

(defn- problem-node?
  [el]
  (let [t (tag-of el)]
    (and t (or (= "ERROR" t) (str/starts-with? t "MISSING")))))

(defn- problem-from
  "One problem map from an ERROR/MISSING element + source lines."
  [src-lines el]
  (let [tag (tag-of el)
        kind (if (= "ERROR" tag) :error :missing)
        expected (when (= :missing kind)
                   (some-> tag
                           (str/replace #"^MISSING" "")
                           (str/replace #"^:" "")
                           str/trim
                           not-empty))
        line (inc (Long/parseLong (str (get (:attrs el) :srow))))
        col (inc (Long/parseLong (str (get (:attrs el) :scol))))]
    {:kind kind :line line :col col :expected expected
     :snippet (not-empty (str/trim (str (nth src-lines (dec line) nil))))}))

(defn problems-from-tree
  "Collect ERROR/MISSING problems (capped) from a parsed <source> element."
  [source-el src-lines]
  (->> (tree-seq #(and (map? %) (:tag %)) :content source-el)
       (filter problem-node?)
       (map #(problem-from src-lines %))
       (take max-problems)
       vec))

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
         tmp (fs/path (paths/root base) (str "validate." ext))]
     (spit (str tmp) content)
     (try
       (let [res ((or (:parse-runner opts) cli/exec!)
                  ["parse" "--wasm"
                   "--config-path" (str (paths/config-path base))
                   "-x" (str tmp)]
                  {:base base
                   :env {"TREE_SITTER_LIBDIR" (str (paths/libs-dir base))}})
             root (xml/parse-str (str/trim (str (:out res)))
                                 :namespace-aware false)
             src-el (first (filter #(= :source (:tag %)) (:content root)))
             tree (or src-el root)
             src-lines (str/split-lines (str content))]
         {:problems (problems-from-tree tree src-lines) :via :tree-sitter})
       (finally (fs/delete-if-exists tmp))))))

;; ─── delimiter backend (clojure family fallback) ──────────────────────────

(defn- delimiter-problem
  "First imbalance in SOURCE (clojure flavor: ; line comments, backslash
   escapes, multi-line strings), or nil when balanced."
  [content]
  (let [n (count content)]
    (loop [i 0, line 1, col 1, stack (), str-open nil, state :code]
      (cond
        (>= i n)
        (or (when-some [[opener oline ocol] (first stack)]
              {:kind :unclosed :line oline :col ocol
               :expected (str (get closers opener)) :snippet (str opener)})
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
                    (contains? (set (vals closers)) ch)
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
