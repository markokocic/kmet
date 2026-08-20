;; paren-repair — Delimiter repair for Clojure files.
;;
;; Port of clojure-mcp paren_repair/{core,tool}.clj: detects unbalanced
;; delimiters with edamame, repairs with parinferish (indent mode), then
;; formats with cljfmt (honoring the project's cljfmt.edn) and writes the
;; file back with a unified diff.

(ns paren-repair
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [edit-util :as util]
            [kmet.app.ui.tool-renderers :as renderers]
            [kmet.libs.edit-diff :as edit-diff]))

;; ═══════════════════════════════════════════════════════════════════════════════
;; Core repair logic
;; ═══════════════════════════════════════════════════════════════════════════════

(defn- clojure-file?
  "True for Clojure-related extensions (.clj .cljs .cljc .cljd .bb .edn .lpy)."
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

(defn repair-string
  "Repair delimiters in SOURCE and optionally format with cljfmt.
   Returns {:content str :delimiter-fixed? bool :formatted? bool}."
  [source format?]
  (let [[repaired delimiter-fixed?] (util/repair-delimiters source)
        fmt-opts (util/project-fmt-opts ".")
        formatted (if format?
                    (util/format-source-string repaired fmt-opts)
                    repaired)]
    {:content formatted
     :delimiter-fixed? delimiter-fixed?
     :formatted? (not= repaired formatted)}))

(defn repair-file!
  "Repair delimiter errors in FILE-PATH and optionally format.
   Returns {:success bool :message str :diff str-or-nil
            :delimiter-fixed? bool :formatted? bool}."
  [file-path format?]
  (cond
    (not (fs/exists? file-path))
    {:success false :message (str "File does not exist: " file-path)}

    (not (clojure-file? file-path))
    {:success false :message (str "Not a Clojure file (skipping): " file-path)}

    :else
    (try
      (let [original (util/slurp-utf8 file-path)
            result (repair-string original format?)
            final-content (:content result)
            changed? (not= original final-content)]
        (if changed?
          (do (util/spit-utf8 file-path final-content)
              (let [diff-str (edit-diff/generate-display-diff original final-content)
                    status-parts (cond-> []
                                   (:delimiter-fixed? result) (conj "delimiter-fixed")
                                   (:formatted? result) (conj "formatted"))]
                {:success true
                 :message (str "Fixed [" (str/join ", " status-parts) "]")
                 :delimiter-fixed? (:delimiter-fixed? result)
                 :formatted? (:formatted? result)
                 :diff diff-str}))
          {:success true
           :message "No changes needed (no delimiter errors)"
           :delimiter-fixed? false
           :formatted? false
           :diff nil}))
      (catch Exception e
        {:success false
         :message (str "Error: " (ex-message e))
         :delimiter-fixed? false
         :formatted? false
         :diff nil}))))

;; ═══════════════════════════════════════════════════════════════════════════════
;; Tool execute
;; ═══════════════════════════════════════════════════════════════════════════════

(defn execute
  "Tool entry point.  Returns {:content str :is-error bool}."
  [{:keys [file_path format]}]
  (let [format? (if (nil? format) true (boolean format))]
    (cond
      (str/blank? file_path)
      {:content "Missing required parameter: file_path" :is-error true}

      :else
      (let [result (repair-file! file_path format?)]
        (if (:success result)
          (cond-> {:content (:message result)}
            (:diff result) (assoc :details {:diff (:diff result)}))
          {:content (:message result) :is-error true})))))

;; ═══════════════════════════════════════════════════════════════════════════════
;; Tool registration
;; ═══════════════════════════════════════════════════════════════════════════════

(defn register!
  "Register clojure_paren_repair as a kmet tool."
  [api]
  ((:register-tool! api)
   {:name            "clojure_paren_repair"
    :label           "Repair delimiters"
    :description
    "Fix delimiter errors (unbalanced parentheses, brackets, braces) in a Clojure file using parinferish.\n\nUse this tool when:\n- A file has unbalanced delimiters causing parse errors\n- You need to repair a file after an errant edit\n- The file won't compile due to unbalanced parens/brackets\n\nDetection is via edamame (a parse error carrying an unclosed opener); repair is indentation-based: opens at the end of a line are closed, and stray closes are dropped. The file is then formatted with cljfmt (honoring the project's cljfmt.edn) unless format=false.\n\nReturns a status message and diff showing what changed."
    :render-call renderers/render-edit-call
    :render-result renderers/render-edit-result
    :render-shell :self
    :prompt-snippet "Fix unbalanced delimiters (parens/brackets/braces) in a Clojure file"
    :prompt-guidelines
    ["Use clojure_paren_repair when a Clojure file has unbalanced delimiters causing parse errors — after an errant edit or when the file won't compile."
     "The tool detects delimiter errors with edamame and repairs them with parinferish (indent mode), then formats with cljfmt honoring the project's cljfmt.edn."
     "Pass format=false to only fix delimiters without reformatting."
     "The tool returns a diff of the changes."]
    :parameters
    {:type       "object"
     :required   ["file_path"]
     :properties
     {"file_path" {:type        "string"
                   :description "Path to the Clojure file to repair (.clj, .cljs, .cljc, .bb, .edn)"}
      "format"    {:type        "boolean"
                   :description "Format the file with cljfmt after repairing delimiters (default: true)"}}}
    :execute execute}))
