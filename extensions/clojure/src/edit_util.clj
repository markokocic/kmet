;; edit-util — Shared utilities for the Clojure extension tools.
;;
;; Common file I/O, diff, formatting, and zipper helpers used by both
;; edit-tool (clojure_edit) and sexp-tool (clojure_edit_replace_sexp).

(ns edit-util
  (:require [cljfmt.core :as fmt]
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
;; Diff (pure in-memory)
;; ═══════════════════════════════════════════════════════════════════════════════

(defn- ->lines
  "Split content into lines, preserving the trailing newline as an empty
   string so join produces the original."
  [s]
  (str/split s #"\n" -1))

(defn- unified-diff-lines
  "Produce unified diff lines between old-lines and new-lines.
   Returns a seq of formatted strings (no trailing newline)."
  [old-lines new-lines]
  (let [n (count old-lines) m (count new-lines)
        ;; find common prefix
        pre (loop [i 0]
              (if (and (< i n) (< i m) (= (nth old-lines i) (nth new-lines i)))
                (recur (inc i))
                i))
        ;; find common suffix (from the end, after the prefix)
        suf (loop [k 0]
              (if (and (>= (- n 1 k) pre) (>= (- m 1 k) pre)
                       (= (nth old-lines (- n 1 k)) (nth new-lines (- m 1 k))))
                (recur (inc k))
                k))
        old-changed (subvec old-lines pre (- n suf))
        new-changed (subvec new-lines pre (- m suf))
        start (inc pre)
        header (str "@@ -" start "," (count old-changed) " +" start "," (count new-changed) " @@")]
    (concat [header]
            (map #(str "-" %) old-changed)
            (map #(str "+" %) new-changed))))

(defn generate-unified-diff
  "Unified diff of OLD-CONTENT vs NEW-CONTENT.  Returns a unified diff
   string or nil when contents are identical.  Pure in-memory — no temp
   files, no shell."
  [old-content new-content]
  (when (not= old-content new-content)
    (let [old-lines (->lines old-content)
          new-lines (->lines new-content)
          diff-lines (unified-diff-lines old-lines new-lines)]
      (str/join "\n" diff-lines))))

;; ═══════════════════════════════════════════════════════════════════════════════
;; Formatting (cljfmt)
;; ═══════════════════════════════════════════════════════════════════════════════

(def fmt-opts
  "cljfmt formatting options shared by both tools."
  {:indentation?                          true
   :remove-surrounding-whitespace?        true
   :remove-trailing-whitespace?           true
   :insert-missing-whitespace?            true
   :remove-consecutive-blank-lines?       true
   :remove-multiple-non-indenting-spaces? true
   :split-keypairs-over-multiple-lines?   false
   :sort-ns-references?                   false
   :function-arguments-indentation        :community
   :indents                               fmt/default-indents})

(defn format-source-string
  "Format a complete Clojure source string.  Returns formatted string,
   or the original on parse error."
  [s]
  (try (fmt/reformat-string s fmt-opts)
       (catch Exception _ s)))

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
  [form-str target-col]
  (try
    (let [formatted (fmt/reformat-string form-str fmt-opts)]
      (re-indent-to-column formatted target-col))
    (catch Exception _ form-str)))

;; ═══════════════════════════════════════════════════════════════════════════════
;; Zipper: navigation
;; ═══════════════════════════════════════════════════════════════════════════════

(defn walk-back-to-non-comment
  "Walk backward from ZLOC past whitespace and comments."
  [zloc]
  (z/find-next zloc z/prev*
               (fn [z] (not (#{:whitespace :comment} (n/tag (z/node z)))))))

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
