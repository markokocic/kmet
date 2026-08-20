;; edit-util — Shared utilities for the Clojure extension tools.
;;
;; Common file I/O, diff, formatting, and zipper helpers used by both
;; edit-tool (clojure_edit) and sexp-tool (clojure_edit_replace_sexp).

(ns edit-util
  (:require [babashka.fs :as fs]
            [cljfmt.config :as config]
            [cljfmt.core :as fmt]
            [clojure.string :as str]
            [edamame.core :as e]
            [kmet.libs.edit-diff :as edit-diff]
            [parinferish.core :as parinferish]
            [rewrite-clj.node :as n]
            [rewrite-clj.parser :as p]
            [rewrite-clj.zip :as z]))

;; ═══════════════════════════════════════════════════════════════════════════════
;; File I/O (UTF-8)
;; ═══════════════════════════════════════════════════════════════════════════════

(defn slurp-utf8 [f]
  (slurp f :encoding "UTF-8"))

(defn clojure-file?
  "True for Clojure-related file paths (.clj .cljs .cljc .cljd .bb .edn .lpy)."
  [file-path]
  (when file-path
    (let [lower (str/lower-case file-path)]
      (or (str/ends-with? lower ".clj")
          (str/ends-with? lower ".cljs")
          (str/ends-with? lower ".cljc")
          (str/ends-with? lower ".cljd")
          (str/ends-with? lower ".bb")
          (str/ends-with? lower ".edn")
          (str/ends-with? lower ".lpy")))))

(defn not-clojure-file-msg
  "Error message for TOOL-NAME operating on a non-Clojure FILE-PATH."
  [tool-name file-path]
  (str tool-name " only operates on .clj/.cljs/.cljc/.cljd/.bb/.edn/.lpy files: " file-path))

(defn spit-utf8 [f content]
  (spit f content :encoding "UTF-8"))

;; ═══════════════════════════════════════════════════════════════════════════════
;; Lint
;; ═══════════════════════════════════════════════════════════════════════════════

(defn delimiter-details
  "Nil when S has no delimiter error; otherwise the edamame ex-data map
   with :row :col :edamame/expected-delimiter :edamame/opened-delimiter
   and :edamame/opened-delimiter-loc. Only delimiter errors are returned —
   other parse failures (bad token etc.) return nil. When available the map
   carries precise row/col so callers can report actionable locations."
  [s]
  (try
    (e/parse-string-all s {:all true
                           :features #{:bb :clj :cljs :cljr :default}
                           :read-cond :allow
                           :readers (fn [_tag] (fn [data] data))
                           :auto-resolve name})
    nil
    (catch clojure.lang.ExceptionInfo ex
      (let [data (ex-data ex)]
        (when (= :edamame/error (:type data))
          (cond
            (contains? data :edamame/opened-delimiter) data
            (contains? data :edamame/expected-delimiter) data
            :else nil))))
    (catch Exception _ nil)))

(defn delimiter-error?
  "True when S has a delimiter error (unbalanced parens/brackets/braces).
   Detection via edamame: parse with all reader features enabled; an
   :edamame/error carrying an unclosed opener or an unexpected closer is a
   delimiter error. Non-delimiter parse failures (e.g. bad token) and
   unknown exceptions are NOT delimiter errors."
  [s]
  (boolean (delimiter-details s)))

(defn- parinferish-repair
  "Repair delimiters in S with parinferish indent mode. Returns the
   repaired string, or nil when repair failed (or produced code that still
   has delimiter errors)."
  [s]
  (try
    (let [repaired (parinferish/flatten (parinferish/parse s {:mode :indent}))]
      (when (and (some? repaired) (not (delimiter-error? repaired)))
        repaired))
    (catch Exception _ nil)))

(defn repair-delimiters
  "Fix unbalanced delimiters in S. Returns [text fixed?]:
   - no delimiter error → [s false]
   - error repaired → [repaired true]
   - error but repair failed → [s false] (the error surfaces downstream)"
  [s]
  (if (delimiter-error? s)
    (if-let [repaired (parinferish-repair s)]
      [repaired true]
      [s false])
    [s false]))

(defn lint-repair
  "Lint-repair CODE: auto-fix unbalanced delimiters via parinferish.
   Returns [code repaired?]. Broken delimiters are repaired in place —
   the common agent mistake (an extra or missing paren) — so downstream
   parse/format steps see balanced code."
  [code]
  (repair-delimiters code))

;; ═══════════════════════════════════════════════════════════════════════════════
;; Formatting (cljfmt, honoring the project's cljfmt.edn)
;; ═══════════════════════════════════════════════════════════════════════════════

(defn project-fmt-opts
  "cljfmt options for FILE-PATH mirroring cljfmt.tool/fix (what `bb format`
   produces): the project's cljfmt.edn — walked up from the file's directory —
   merged over cljfmt's defaults, so :extra-indents/:indents/:alias-map etc.
   apply to custom macros (defcomponent, defsetter, ...). Falls back to plain
   defaults when no config file exists or the file's directory does not exist
   (load-config cannot search a nonexistent directory)."
  [file-path]
  (let [parent (when file-path (fs/parent file-path))]
    (if (and parent (fs/exists? parent))
      (config/load-config (str parent))
      (config/load-config "."))))

(defn format-source-string
  "Format a complete Clojure source string.  Returns formatted string,
   or the original on parse error.  With no opts, resolves the project
   config from the current directory."
  ([s] (format-source-string s (project-fmt-opts ".")))
  ([s opts]
   (try (fmt/reformat-string s opts)
        (catch Exception _ s))))

(defn re-indent-to-column
  "Re-indent all lines after the first to TARGET-COL (1-based)."
  [form-str target-col]
  (if (<= target-col 1)
    form-str
    (let [lines      (str/split form-str #"\n" -1)
          indent-str (apply str (repeat (dec target-col) " "))]
      (str/join "\n"
                (cons (first lines)
                      (map (fn [l] (if (str/blank? l) l (str indent-str l)))
                           (rest lines)))))))

(defn format-form-in-isolation
  "Format FORM-STR with cljfmt, then re-indent to TARGET-COL."
  ([form-str target-col]
   (format-form-in-isolation form-str target-col (project-fmt-opts ".")))
  ([form-str target-col opts]
   (try
     (let [formatted (fmt/reformat-string form-str opts)]
       (re-indent-to-column formatted target-col))
     (catch Exception _ form-str))))

;; ═══════════════════════════════════════════════════════════════════════════════
;; Zipper: navigation
;; ═══════════════════════════════════════════════════════════════════════════════

(defn walk-back-to-non-comment
  "Walk backward from ZLOC past whitespace and comments."
  [zloc]
  (z/find-next zloc z/prev*
               (fn [z] (not (#{:whitespace :comment} (n/tag (z/node z)))))))

(defn walk-forward-past-trailing-comments
  "Walk forward from ZLOC past whitespace and comments sitting on the same
   line — the form's own trailing trivia (e.g. `(defn a ...) ;; note`).
   Stops before any node that starts a new line: a comment there leads the
   NEXT form and must stay with it. Returns the last node of the same-line
   run, which is the right anchor for insert-after."
  [zloc]
  (loop [loc zloc]
    (let [nxt (z/right* loc)]
      (if-not nxt
        loc
        (let [tag (n/tag (z/node nxt))]
          (cond
            (= tag :comment) (recur nxt)
            (= tag :whitespace)
            (if (str/includes? (n/string (z/node nxt)) "\n")
              loc
              (recur nxt))
            :else loc))))))

;; ═══════════════════════════════════════════════════════════════════════════════
;; Zipper: form replacement
;; ═══════════════════════════════════════════════════════════════════════════════

(defn- remove-whitespace-and-comments
  "Remove consecutive whitespace and comment nodes starting at ZLOC."
  [zloc]
  (loop [loc zloc]
    (if (n/whitespace-or-comment? (z/node loc))
      (recur (z/next* (z/remove* loc)))
      loc)))

(defn replace-form
  "Replace FORM-ZLOC with CONTENT-STR.  When CONTENT-STR is a comment
   (starts with ;), walks back and removes adjacent comments first."
  [form-zloc content-str]
  (if (-> content-str str/trim (str/starts-with? ";"))
    (-> (walk-back-to-non-comment form-zloc)
        (z/next*)
        (remove-whitespace-and-comments)
        (z/replace (p/parse-string-all content-str)))
    (z/replace form-zloc (p/parse-string-all content-str))))

;; ═══════════════════════════════════════════════════════════════════════════════
;; Zipper: insert helpers (shared by clojure_edit and clojure_edit_replace_sexp)
;; ═══════════════════════════════════════════════════════════════════════════════

(defn insert-before-form
  "Insert CONTENT-STR as a new form immediately before ZLOC, separated by a
   blank line, returning the inserted form's zipper location."
  [zloc content-str]
  (-> zloc
      walk-back-to-non-comment z/next*
      (z/insert-left* (p/parse-string-all "\n\n"))
      z/left
      (z/insert-left* (p/parse-string-all content-str))
      z/left))

(defn insert-after-form
  "Insert CONTENT-STR as a new form immediately after ZLOC (past the form's
   own same-line trailing trivia), separated by a blank line, returning the
   inserted form's zipper location. A comment anchor already ends its line,
   so it takes a single \"\\n\" before (blank line) and after (line
   terminator) the content."
  [zloc content-str]
  (let [anchor (walk-forward-past-trailing-comments zloc)
        comment-anchor? (= :comment (n/tag (z/node anchor)))
        sep (p/parse-string-all (if comment-anchor? "\n" "\n\n"))
        at-content (-> anchor
                       (z/insert-right* sep)
                       z/right
                       (z/insert-right* (p/parse-string-all content-str))
                       z/right)]
    (if comment-anchor?
      (z/insert-right* at-content (p/parse-string-all "\n"))
      at-content)))

(defn edit-pipeline
  "Run the shared edit pipeline: load FILE-PATH, call FIND-EDIT-FN with the
   parsed zipper (which must return either {:error msg :similar-matches ...}
   or {:zloc updated-zloc}), format the result with the project's cljfmt
   config, write it back, and return the tool result map.
   When REPAIR-INPUTS is a seq of [raw repaired] pairs, the success message
   notes when any input was auto-repaired, and the not-found message explains
   the repair. FIND-EDIT-FN may return nil instead of {:error ...} — that
   becomes the not-found error."
  [file-path find-edit-fn repair-inputs]
  (try
    (let [original (slurp-utf8 file-path)
          zloc     (z/of-string original {:track-position? true})
          result   (find-edit-fn zloc)]
      (if (and result (:error result))
        (let [similar (:similar-matches result)
              similar-note (when (seq similar)
                             (str "\n\nSimilar forms found (check the form name/type and dispatch value):\n"
                                  (str/join "\n" (map (fn [m]
                                                        (str "  - (" (:tag m) " " (:qualified-name m) " ...)"))
                                                      similar))))]
          {:content  (str (:message result) similar-note)
           :is-error true})
        (if-not result
          {:content  (str "Could not find the target in " file-path
                          (when-let [repaired (seq (filter #(not= (first %) (second %)) repair-inputs))]
                            (str "\nNote: input was unbalanced and auto-repaired: "
                                 (str/join ", " (map (fn [[_ repaired]] (pr-str repaired)) repaired))
                                 "\nIf the repaired form is not what you meant, pass the complete, balanced expression exactly as it appears in the file."))
                          "\nThe match is content-based — whitespace/newlines are ignored, but the structure (parens, brackets, braces, keywords, symbols) must match.")
           :is-error true}
          (let [new-source (z/root-string (:zloc result))
                fmt-opts   (project-fmt-opts file-path)
                formatted  (format-source-string new-source fmt-opts)]
            (spit-utf8 file-path formatted)
            (let [diff-str (edit-diff/generate-display-diff original formatted)
                  repaired (seq (filter (fn [[raw rep]] (not= raw rep)) repair-inputs))
                  repaired-note (when repaired
                                  (str "\nNote: unbalanced input was auto-repaired: "
                                       (str/join ", " (map (fn [[_ rep]] (pr-str rep)) repaired))))]
              {:content (if repaired-note
                          (str "Edit applied." repaired-note)
                          "Edit applied.")
               :details (when diff-str {:diff diff-str})})))))
    (catch Exception e
      {:content (str "Error editing " file-path ": " (ex-message e))
       :is-error true})))
