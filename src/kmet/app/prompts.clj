(ns kmet.app.prompts
  "Prompt templates for kmet, aligned with pi (pi: core/prompt-templates.js,
   docs/prompt-templates.md). A template is a .md file in the prompts dir
   (non-recursive) with optional YAML frontmatter: description (falls back to
   the first non-empty body line, truncated to 60 chars) and argument-hint.
   The filename without .md is the command name: /name args expands to the
   template body with $1, $@, ${1:-default}, ${@:N} placeholders substituted."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [babashka.fs :as fs]
            [kmet.libs.yaml :as yaml]))

;; ─── Template state ────────────────────────────────────────────────────────

(defonce ^:private templates (atom []))

;; ─── Argument parsing (pi: parseCommandArgs) ──────────────────────────────

(defn- js-whitespace?
  "True for characters JS /\\s/ treats as whitespace: Java's
   Character/isWhitespace misses the NBSP-family (incl. U+FEFF BOM), so union
   with isSpaceChar plus U+FEFF (pi: /\\s/ in parseCommandArgs)."
  [c]
  (or (Character/isWhitespace c)
      (Character/isSpaceChar c)
      (= c \ufeff)))

(defn parse-command-args
  "Parse command arguments respecting quoted strings (bash-style, pi:
   parseCommandArgs). Returns a vector of argument strings."
  [args-string]
  (let [args (volatile! [])
        cur (volatile! [])
        q (volatile! nil)]
    (doseq [c args-string]
      (if @q
        (if (= c @q) (vreset! q nil) (vswap! cur conj c))
        (cond
          (or (= c \") (= c \')) (vreset! q c)
          (js-whitespace? c)
          (do (when (seq @cur) (vswap! args conj (str/join @cur)))
              (vreset! cur []))
          :else (vswap! cur conj c))))
    (when (seq @cur) (vswap! args conj (str/join @cur)))
    @args))

;; ─── Argument substitution (pi: substituteArgs) ───────────────────────────

(defn- parse-long-safe
  "parse-long returning nil on non-numeric/overflow input (pi's parseInt
   clamps instead of throwing)."
  [s]
  (try (parse-long s) (catch Exception _ nil)))

(defn substitute-args
  "Substitute argument placeholders in template content (pi: substituteArgs):
   $1, $2, ... positional; $@ and $ARGUMENTS for all args; ${N:-default} with
   a default when missing/empty; ${@:-default} / ${ARGUMENTS:-default}; and
   bash-style slicing ${@:N} / ${@:N:L} (1-indexed). Single pass — values are
   not recursively substituted."
  [content args]
  (let [all-args (str/join " " args)
        n (count args)]
    (str/replace content
                 #"\$\{(\d+|ARGUMENTS|@):-([^}]*)\}|\$\{@:(\d+)(?::(\d+))?\}|\$(ARGUMENTS|@|\d+)"
                 (fn [[_ default-target default-value slice-start slice-length simple]]
                   (cond
                     default-target
                     (if (or (= default-target "@") (= default-target "ARGUMENTS"))
                       (if (seq all-args) all-args default-value)
                       (let [i (parse-long-safe default-target)
                             value (when (and i (>= i 1) (<= i n)) (nth args (dec i)))]
                         (if (seq value) value default-value)))
                     slice-start
                     (let [start (if-let [i (parse-long-safe slice-start)]
                                   (min n (max 0 (dec i)))
                                   n)
                           end (if-let [l (parse-long-safe slice-length)]
                                 (min n (+ start l))
                                 n)]
                       (str/join " " (subvec args start end)))
                     :else
                     (if (or (= simple "@") (= simple "ARGUMENTS"))
                       all-args
                       (if-let [i (parse-long-safe simple)]
                         (nth args (dec i) "")
                         "")))))))

;; ─── Loading (pi: loadTemplateFromFile / loadTemplatesFromDir) ────────────

(defn- load-template-from-file
  "Load a prompt template from a .md file. Name = filename without .md.
   Description = frontmatter description, else the first non-empty body line
   truncated to 60 chars with \"...\" (pi: loadTemplateFromFile).
   Returns the template map, or nil on read/parse failure."
  [file-path]
  (try
    (let [raw (slurp file-path)
          {:keys [frontmatter body]} (yaml/parse-frontmatter raw)
          name (str/replace (fs/file-name file-path) #"\.md$" "")
          fm-desc (some-> (get frontmatter "description") str str/trim)
          first-line (first (filter #(seq (str/trim %)) (str/split-lines body)))
          description (cond
                        (seq fm-desc) fm-desc
                        first-line (if (> (count first-line) 60)
                                     (str (subs first-line 0 60) "...")
                                     first-line)
                        :else "")]
      (cond-> {:name name
               :description description
               :content body
               :file-path (str file-path)}
        (seq (str (get frontmatter "argument-hint")))
        (assoc :argument-hint (str (get frontmatter "argument-hint")))))
    (catch Exception _
      ;; pi: loadTemplateFromFile returns null on read/parse failure (silent)
      nil)))

(defn load-prompt-templates-from-dir
  "Load .md prompt templates from a directory (non-recursive, pi:
   loadTemplatesFromDir). Adds them to the registry; returns the loaded
   templates."
  [dir]
  (let [d (io/file dir)]
    (if-not (fs/directory? d)
      []
      (let [loaded (volatile! [])]
        (doseq [f (fs/list-dir d)]
          (when (and (fs/regular-file? f)
                     (str/ends-with? (fs/file-name f) ".md"))
            (when-let [t (load-template-from-file (str f))]
              (vswap! loaded conj t))))
        (let [ts @loaded]
          (swap! templates into ts)
          ts)))))

(defn get-prompt-templates
  []
  @templates)

(defn clear-prompt-templates!
  "Remove all loaded prompt templates (pi: resourceLoader.reload re-discovers
   from scratch). Used by /reload."
  []
  (reset! templates []))

(defn get-prompt-template
  [name]
  (first (filter #(= name (:name %)) @templates)))

;; ─── Expansion (pi: expandPromptTemplate) ─────────────────────────────────

(defn expand-prompt-template
  "Expand /name args into the template body when name matches a template.
   Returns the expanded content, or the original text when not a template
   (pi: expandPromptTemplate)."
  [text template-list]
  (if-not (str/starts-with? text "/")
    text
    (if-let [[_ name args-string] (re-matches #"^/([^\s]+)(?:\s+([\s\S]*))?$" text)]
      (if-let [t (first (filter #(= name (:name %)) template-list))]
        (substitute-args (:content t) (parse-command-args (or args-string "")))
        text)
      text)))

;; ─── Autocomplete (pi: interactive-mode templateCommands) ─────────────────

(defn as-command-maps
  "Templates in slash-command shape for the editor autocomplete provider
   (pi: interactive-mode converts templates to SlashCommand format)."
  [template-list]
  (mapv (fn [t]
          (cond-> {:name (:name t)
                   :description (:description t)}
            (:argument-hint t) (assoc :argument-hint (:argument-hint t))))
        template-list))
