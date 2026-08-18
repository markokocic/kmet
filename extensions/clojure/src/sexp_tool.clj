;; sexp-tool — S-expression replacement for kmet.
;;
;; Port of clojure-mcp form_edit/{core,pipeline,tool}.clj (sexp variant).
;; Finds s-expressions by content match and replaces/inserts them.
;;
;; Tool parameters:
;;   file_path           — path to .clj/.cljs/.cljc/.bb/.edn file
;;   match_form          — s-expression(s) to find
;;   new_form            — replacement s-expression(s)
;;   replace_all         — replace all occurrences (default false)
;;   operation           — "replace" (default) | "insert_before" | "insert_after"

(ns sexp-tool
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [edit-util :as util]
            [rewrite-clj.node :as n]
            [rewrite-clj.parser :as p]
            [rewrite-clj.zip :as z]))

;; ═══════════════════════════════════════════════════════════════════════════════
;; Zipper: semantic node helpers
;; ═══════════════════════════════════════════════════════════════════════════════

(def ^:dynamic *match-clean* false)

(defn- semantic-nodes? [node]
  (if *match-clean*
    (not (n/whitespace-or-comment? node))
    (not (n/whitespace? node))))

(defn- normalize-whitespace-node [node]
  (if (= :forms (n/tag node))
    (n/forms-node (map normalize-whitespace-node (n/children node)))
    (if (n/inner? node)
      (let [children (n/children node)
            filtered (->> children
                          (remove n/whitespace?)
                          (map normalize-whitespace-node)
                          (interpose (n/spaces 1))
                          vec)]
        (n/replace-children node filtered))
      node)))

(defn- normalize-and-clean-node [node]
  (cond
    (not (semantic-nodes? node)) nil
    (= :forms (n/tag node))
    (n/forms-node (->> (n/children node)
                       (map normalize-and-clean-node)
                       (filter some?)))
    (n/inner? node)
    (let [children (n/children node)
          cleaned  (->> children
                        (map normalize-and-clean-node)
                        (filter some?)
                        (interpose (n/spaces 1))
                        vec)]
      (n/replace-children node cleaned))
    :else node))

(defn- node->match-expr [node]
  (when-let [n (if *match-clean*
                 (normalize-and-clean-node node)
                 (normalize-whitespace-node node))]
    (n/string n)))

(defn- zchild-match-exprs [zloc]
  (let [nodes (if (= :forms (z/tag zloc))
                (n/children (z/node zloc))
                (->> (iterate z/right* zloc)
                     (take-while some?)
                     (map z/node)))]
    (->> nodes
         (filter (bound-fn* semantic-nodes?))
         (keep (bound-fn* node->match-expr)))))

(defn- str-forms->sexps [str-forms]
  (zchild-match-exprs (z/of-node (p/parse-string-all str-forms))))

(defn- match-multi-sexp [match-sexprs zloc]
  (let [len         (count match-sexprs)
        zloc-sexprs (zchild-match-exprs zloc)
        matched     (map = match-sexprs zloc-sexprs)]
    (and (every? identity matched)
         (= (count matched) len))))

;; ═══════════════════════════════════════════════════════════════════════════════
;; Zipper: multi-sexp operations
;; ═══════════════════════════════════════════════════════════════════════════════

(defn- iterate-to-n [f x n]
  (->> (iterate f x) (take n) last))

(defn- zright-n [zloc n]
  (iterate-to-n z/right zloc n))

(defn- remove-match-expr [zloc match-exp]
  (loop [zloc' zloc]
    (when (and zloc' (not (z/end? zloc')))
      (let [node (z/node zloc')]
        (when (and (semantic-nodes? node)
                   (not= match-exp (node->match-expr node)))
          (throw (ex-info "Bad match state" {:node      (n/string node)
                                             :match-exp match-exp})))
        (if (and (semantic-nodes? node)
                 (= match-exp (node->match-expr node)))
          (z/remove* zloc')
          (recur (-> zloc' z/remove* z/next*)))))))

(defn- remove-match-exprs [zloc match-exprs]
  (let [end (last match-exprs)]
    (reduce
     (fn [zloc' match-expr]
       (let [zl (remove-match-expr zloc' match-expr)]
         (cond
           (= match-expr end) zl
           (not (z/end? zl)) (z/next* zl))))
     zloc
     match-exprs)))

(defn- truncate-matched-expression [zloc [start & match-exprs]]
  (when-let [after-truncated (remove-match-exprs (z/right* zloc) match-exprs)]
    (z/find after-truncated z/prev*
            (fn [zloc]
              (let [node (z/node zloc)]
                (and (semantic-nodes? node)
                     (= start (node->match-expr node))))))))

(defn- replace-multi-helper [zloc match-exprs content-str]
  (if (= 1 (count match-exprs))
    (util/replace-form zloc content-str)
    (let [truncated-zloc (truncate-matched-expression zloc match-exprs)]
      (util/replace-form truncated-zloc content-str))))

(defn- replace-multi [zloc match-sexprs content-str]
  (if (or (nil? content-str) (zero? (count content-str)))
    (let [after-loc (remove-match-exprs zloc match-sexprs)]
      {:edit-span-loc after-loc :after-loc after-loc})
    (let [edit-span-loc (replace-multi-helper zloc match-sexprs content-str)]
      {:edit-span-loc edit-span-loc
       :after-loc      (or (z/right edit-span-loc)
                           (z/next edit-span-loc))})))

(defn- insert-before-multi [zloc _match-sexprs replacement-node]
  (let [edit-loc (-> zloc
                     util/walk-back-to-non-comment z/next*
                     (z/insert-left replacement-node)
                     z/left)]
    {:edit-span-loc edit-loc
     :after-loc     (-> edit-loc z/splice
                        (zright-n (count (n/child-sexprs replacement-node))))}))

(defn- insert-after-multi [zloc match-sexprs replacement-node]
  (let [anchor (-> (take (count match-sexprs) (iterate z/right zloc))
                   last
                   ;; past the match's own same-line trailing trivia, so a
                   ;; trailing comment stays with the matched form
                   util/walk-forward-past-trailing-comments)
        comment-anchor? (= :comment (n/tag (z/node anchor)))
        sep-node (p/parse-string-all (if comment-anchor? "\n" "\n\n"))
        edit-loc (-> anchor
                     (z/insert-right* sep-node)
                     z/right
                     (z/insert-right* replacement-node)
                     z/right)
        ;; a comment anchor already ends its line — terminate the inserted
        ;; content's line too, or it glues to the next form
        edit-loc (if comment-anchor?
                   (z/insert-right* edit-loc (p/parse-string-all "\n"))
                   edit-loc)]
    {:edit-span-loc edit-loc
     :after-loc     (-> edit-loc z/splice
                        (zright-n (count (n/child-sexprs replacement-node))))}))

(defn- find-multi-sexp [zloc match-sexprs]
  (->> (iterate z/next zloc)
       (take-while (complement z/end?))
       (filter z/sexpr-able?)
       (filter #(match-multi-sexp match-sexprs %))
       first))

(defn- find-and-edit-one-multi-sexp
  ([zloc operation match-form new-form]
   (find-and-edit-one-multi-sexp zloc operation match-form new-form nil))
  ([zloc operation match-form new-form _reindent-fn]
   (when-not (and (str/blank? new-form) (#{:insert-before :insert-after} operation))
     (let [match-sexprs (str-forms->sexps match-form)]
       (when-let [found-loc (find-multi-sexp zloc match-sexprs)]
         (let [new-node (when-not (str/blank? new-form)
                          (p/parse-string-all new-form))]
           (condp = operation
             :insert-before (insert-before-multi found-loc match-sexprs new-node)
             :insert-after  (insert-after-multi found-loc match-sexprs new-node)
             (replace-multi found-loc match-sexprs new-form))))))))

(defn- find-and-edit-all-multi-sexp [zloc operation match-form new-form]
  (when-not (and (str/blank? new-form) (#{:insert-before :insert-after} operation))
    (loop [loc zloc
           locations []]
      (if-let [{:keys [after-loc edit-span-loc]}
               (find-and-edit-one-multi-sexp loc operation match-form new-form)]
        (recur after-loc (conj locations edit-span-loc))
        (when (seq locations)
          {:zloc loc :locations locations})))))

(defn- find-and-edit-multi-sexp* [zloc match-form new-form {:keys [operation all?]}]
  (if all?
    (find-and-edit-all-multi-sexp zloc operation match-form new-form)
    (when-let [{:keys [after-loc edit-span-loc]}
               (find-and-edit-one-multi-sexp zloc operation match-form new-form)]
      {:zloc after-loc :locations [edit-span-loc]})))

(defn- find-and-edit-multi-sexp [zloc match-form new-form opts]
  (or (find-and-edit-multi-sexp* zloc match-form new-form opts)
      (binding [*match-clean* true]
        (find-and-edit-multi-sexp* zloc match-form new-form opts))))

;; ═══════════════════════════════════════════════════════════════════════════════
;; Main execute
;; ═══════════════════════════════════════════════════════════════════════════════

(defn execute
  "Tool entry point.  Returns {:content str :is-error bool}."
  [{:keys [file_path match_form new_form replace_all operation dry_run]}]
  (let [op-kw (case operation
                "replace"       :replace
                "insert_before" :insert-before
                "insert_after"  :insert-after
                :replace)
        ;; Original: replace_all forced false for insert operations
        replace-all? (and (boolean replace_all)
                          (not= op-kw :insert-before)
                          (not= op-kw :insert-after))]
    (cond
      (str/blank? file_path)
      {:content "Missing required parameter: file_path" :is-error true}

      (not (fs/exists? file_path))
      {:content (str "File not found: " file_path) :is-error true}

      (str/blank? match_form)
      {:content "Missing required parameter: match_form" :is-error true}

      ;; Original: validate match_form is parseable and contains at least one sexpr
      (let [parsed (try (p/parse-string-all match_form) (catch Exception _ nil))]
        (or (nil? parsed)
            (zero? (count (n/child-sexprs parsed)))))
      {:content "match_form must contain at least one valid S-expression (not just comments or whitespace)"
       :is-error true}

      (str/blank? new_form)
      {:content "Missing required parameter: new_form" :is-error true}

      ;; Original: validate new_form is parseable
      (try (p/parse-string-all new_form) nil
           (catch Exception _ true))
      {:content "Invalid Clojure code in new_form" :is-error true}

      :else
      (try
        (let [;; 1. Lint-repair both match_form and new_form
              match' (first (util/lint-repair match_form))
              new'   (first (util/lint-repair new_form))

              ;; 2. Load + parse
              original (util/slurp-utf8 file_path)
              zloc     (z/of-string original {:track-position? true})

              ;; 3. Find + edit
              result   (find-and-edit-multi-sexp
                        zloc match' new'
                        {:operation op-kw :all? replace-all?})]
          (if-not result
            {:content  (str "Could not find s-expression: " match_form)
             :is-error true}
            ;; 4. Format + write + diff
            (let [new-source (z/root-string (:zloc result))
                  fmt-opts  (util/project-fmt-opts file_path)
                  formatted (util/format-source-string new-source fmt-opts)]
              (case dry_run
                "new-source"
                {:content formatted}
                "diff"
                (let [d (util/generate-unified-diff original formatted)]
                  {:content (or d "No changes.")})
                ;; Normal mode — write file
                (do
                  (util/spit-utf8 file_path formatted)
                  (let [diff-str (util/generate-unified-diff original formatted)]
                    {:content  (if diff-str
                                 (str "Edit applied.\n\n" diff-str)
                                 "Edit applied — no visible diff.")
                     :details  (when diff-str {:diff diff-str})}))))))
        (catch Exception e
          {:content (str "Error editing " file_path ": " (ex-message e))
           :is-error true})))))

;; ═══════════════════════════════════════════════════════════════════════════════
;; Tool registration
;; ═══════════════════════════════════════════════════════════════════════════════

(defn register!
  "Register clojure_edit_replace_sexp as a kmet tool."
  [api]
  ((:register-tool! api)
   {:name            "clojure_edit_replace_sexp"
    :label           "Clojure s-expression edit"
    :description
    "Replaces Clojure expressions in a file.\n\nThis tool provides targeted replacement of Clojure expressions within forms. For complete top-level form operations, use clojure_edit instead.\n\nKEY BENEFITS:\n- Syntax-aware matching that understands Clojure code structure\n- Ignores whitespace differences by default, focusing on actual code meaning\n- Matches expressions regardless of formatting, indentation, or spacing\n- Prevents errors from mismatched text or irrelevant formatting differences\n- Can replace all occurrences with replace_all: true\n\nCONSTRAINTS:\n- match_form must contain one or more complete Clojure expressions\n- new_form must contain zero or more complete Clojure expressions\n- Both must be valid Clojure code that can be parsed\n\nWARNING: Incomplete forms like (defn foo, (try, or (let [x 1] will cause errors. match_form must be a COMPLETE expression (balanced parens) — trailing fragments like \"...x]]\" or \"x])\" are rejected.\n\nFor insert_before/insert_after, pass ONLY the new content (never repeat the matched form). The inserted form lands outside the match's own line: a same-line trailing comment stays with the matched form, and a comment on its own line stays with the next form.\n\nExamples:\n- Replace a calculation: match_form: (+ x 2)  new_form: (* x 2)\n- Rename a symbol everywhere: match_form: old-name  new_form: new-name  replace_all: true\n- Remove debug statements: match_form: (println \"Debug\")  new_form: (empty)\n- Replace multiple expressions: match_form: (validate x) (transform x)  new_form: (-> x validate transform)"
    :prompt-snippet "Replace s-expressions in Clojure code (find by content, replace all or one)"
    :prompt-guidelines
    ["Use clojure_edit_replace_sexp to change a specific expression inside a function without touching the surrounding code."
     "Use clojure_edit when replacing an entire top-level form (defn, defmethod, etc.)."
     "match_form must be a valid, parseable Clojure expression (not just comments or whitespace)."
     "new_form must be valid Clojure code."
     "replace_all is forced to false for insert_before and insert_after."
     "Multiple consecutive expressions in match_form are matched as a sequence."]
    :parameters
    {:type       "object"
     :required   ["file_path" "match_form" "new_form" "operation"]
     :properties
     {"file_path"   {:type        "string"
                     :description "Path to the file to edit"}
      "match_form"  {:type        "string"
                     :description "The s-expression to find (include # for anonymous functions)"}
      "new_form"    {:type        "string"
                     :description "The s-expression to use for the operation"}
      "replace_all" {:type        "boolean"
                     :description "Whether to replace all occurrences (default: false)"}
      "operation"   {:type        "string"
                     :enum        ["replace" "insert_before" "insert_after"]
                     :description "The editing operation to perform (default: replace)"}}}
    :execute execute}))
