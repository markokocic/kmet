;; edit-util — Shared utilities for the Clojure extension tools.
;;
;; Common file I/O, diff, formatting, and zipper helpers used by both
;; edit-tool (clojure_edit) and sexp-tool (clojure_edit_replace_sexp).

(ns edit-util
  (:require [babashka.fs :as fs]
            [cljfmt.config :as config]
            [cljfmt.core :as fmt]
            [clojure.string :as str]
            [rewrite-clj.node :as n]
            [rewrite-clj.parser :as p]
            [rewrite-clj.zip :as z]))

;; ═══════════════════════════════════════════════════════════════════════════════
;; File I/O (UTF-8)
;; ═══════════════════════════════════════════════════════════════════════════════

(defn slurp-utf8 [f]
  (slurp f :encoding "UTF-8"))

(defn spit-utf8 [f content]
  (spit f content :encoding "UTF-8"))

;; ═══════════════════════════════════════════════════════════════════════════════
;; Lint
;; ═══════════════════════════════════════════════════════════════════════════════

(defn lint-repair
  "Returns [code false]. Delimiter auto-repair is unavailable: it needs the
   parinfer JVM lib, and kmet extensions can't load third-party classes —
   broken delimiters surface as an error downstream instead."
  [code]
  [code false])

;; ═══════════════════════════════════════════════════════════════════════════════
;; Formatting (cljfmt, honoring the project's cljfmt.edn)
;; ═══════════════════════════════════════════════════════════════════════════════

(defn project-fmt-opts
  "cljfmt options for FILE-PATH mirroring cljfmt.tool/fix (what `bb format`
   produces): the project's cljfmt.edn — walked up from the file's directory —
   merged over cljfmt's defaults, so :extra-indents/:indents/:alias-map etc.
   apply to custom macros (defcomponent, defsetter, ...). Falls back to plain
   defaults when no config file exists."
  [file-path]
  (config/load-config (str (or (fs/parent file-path) "."))))

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
