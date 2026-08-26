(ns kmet.extensions.tree-sitter.hooks
  "Syntax-validation hooks mirroring extensions/clojure/src/paren_repair.clj:

   - write  → pre-hook, blocking: content IS the file, validate directly
              and block with a precise report + fix hint.
   - edit   → post-result hook, non-blocking: newText in isolation is not
              the file (a fragment can be unbalanced alone yet correct in
              context), so the RESULTING file is re-read and validated; a
              ⚠️ report is appended when it no longer parses.

   Never-throw rule (addition to the paren_repair pattern): every failure
   of our infrastructure — missing binary, download failed, spawn error,
   timeout — returns nil (pass-through). Only OBSERVED syntax problems
   produce blocks/warnings. Because the host converts hook exceptions into
   blocks, letting infra errors escape would brick all editing."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [kmet.extensions.tree-sitter.dispatch :as dispatch]
            [kmet.extensions.tree-sitter.validate :as validate]))

(defn- arg
  "Tool args may carry keyword or string keys."
  [args k]
  (or (get args k) (get args (name k))))

(defn- write-path [args]
  (or (arg args :path) (arg args :file_path)))

(defn- ext-of [path]
  (some-> (re-find #"\.([^.]+)$" (str path)) second str/lower-case))

(defn- validate-content!
  "Run the route's validator over content. Returns
   {:problems [...] :via route}, or nil when clean/deferred/unknown.
   Infra failures become nil (pass-through) after logging to stderr; only
   real problem vectors flow on."
  [path content opts]
  (try
    (let [ext (ext-of path)
          route (dispatch/route ext opts)]
      (case route
        :tree-sitter (when-some [r (validate/parse-problems! path content
                                             nil opts)]
                       (assoc r :via :tree-sitter))
        :delimiter {:problems (validate/delimiter-problems content)
                    :via :delimiter}
        nil))
    (catch Exception e
      (binding [*out* *err*]
        (println "tree-sitter hooks:" (some-> e ex-message)))
      nil)))

(defn- hint-for [problem]
  (case (:kind problem)
    :error "fix the reported syntax error before writing."
    :missing "add the reported token before writing."
    :unclosed "close the reported delimiter before writing."
    :stray-closer "remove or match the reported closing delimiter before writing."
    "fix the syntax error before writing."))

(defn- warn-hint-for [path _problem]
  (str "Fix the reported syntax error in " path
       " (e.g. with the edit tool)."))

(defn on-tool-call
  "Before-tool hook: only intercepts `write`. Blocks any write whose
   content does not parse under its route's validator, stating exactly
   what is wrong and where. Deferred/unknown routes pass through; infra
   failures pass through (never-throw)."
  [{:keys [tool-name args]}]
  (when (= "write" tool-name)
    (let [path (write-path args)
          content (arg args :content)]
      (when (and (string? content) (seq (str path)))
        (when-some [{:keys [problems]} (validate-content! path content {})]
          (let [report (validate/report-text problems)
                hint (hint-for (first problems))]
            {:block true
             :reason (str report "\nWrite blocked — " hint)}))))))

(defn on-tool-result
  "After-tool hook: only inspects `edit` results that succeeded. Warns
   (non-blocking) when the resulting file no longer parses under its
   route's validator, appending ⚠️ + report + fix hint to the result the
   model sees. Deferred/unknown routes and infra failures pass through."
  [{:keys [tool-name args result is-error]}]
  (when (and (= "edit" tool-name) (not is-error) (map? result))
    (let [path (write-path args)]
      (when (and (seq (str path)) (fs/exists? path))
        (try
          (when-some [{:keys [problems]}
                      (validate-content! path (slurp (str path)) {})]
            (let [report (validate/report-text problems)
                  hint (warn-hint-for path (first problems))]
              {:content (str (or (:content result) "")
                             "\n\n⚠️ " report "\n" hint)}))
          (catch Exception _ nil))))))
