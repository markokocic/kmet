(ns kmet.extensions.tree-sitter.symbols
  "Structural code intelligence over `parse --wasm -x` trees.

   The CLI's `query` subcommand cannot load WASM grammars (it is
   native-dlopen only — verified against 0.26.13), so instead of .scm
   queries we parse files to XML and walk them with small per-language
   rule sets shipped as resources (queries/<lang>.edn). Rule contract:

     :defs  [{:type NODE :kind \"fn\" (:name-field F | :first-sym-in [..])
              [:value-types [NODES]]}]
       - :name-field  -> name is the text of that field's child node
       - :first-sym-in + kind on a list-like node (clojure): matched when
         the first sym child's text is in the set; the name is the next
         non-meta sym child.
       - :value-types -> optional shape filter: a child at field \"value\"
         must exist and have one of these types (function-valued consts).
     :calls [{:type NODE (:callee-field F [:attr-field-by-type {T F}])
              | :callee-first-sym-except [..]}]
       - callee-field -> invoked name from that field's child; when that
         child's type maps via :attr-field-by-type, descend one level
         (obj.meth(..) -> meth).
       - callee-first-sym-except (clojure): leading sym text unless excluded.

   Every call records its nearest enclosing def, which is what makes
   find_callers / find_callees work without any query-engine support."
  (:require [clojure.data.xml :as xml]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [kmet.extensions.tree-sitter.cli :as cli]
            [kmet.extensions.tree-sitter.paths :as paths]))

(def ^:private rules-cache
  "lang -> parsed rules; resource reads are cheap but rules are hot."
  (atom {}))

(defn rules
  "Extraction rule set for lang, or nil when the language ships none."
  [lang]
  (or (get @rules-cache lang)
      (when-let [r (paths/bundled-resource (str "queries/" lang ".edn"))]
        (let [parsed (edn/read-string (slurp r))]
          (swap! rules-cache assoc lang parsed)
          parsed))))

;; ─── XML tree accessors (clojure.data.xml element maps) ───────────────────

(defn- el? [x] (and (map? x) (:tag x)))
(defn- tag-of [el] (name (:tag el)))
(defn- attr
  "Attribute value by name; data.xml versions differ on whether attr keys
   are strings or keywords, so try both."
  [el k]
  (let [a (:attrs el)]
    (or (get a (keyword k)) (get a (str k)))))
(defn- children [el] (filter el? (:content el)))

(defn- text-of
  "Concatenation of all character data under el (source text for leaves)."
  [el]
  (->> (tree-seq #(and (map? %) (seq (:content %))) :content el)
       (filter string?)
       (apply str)))

(defn- start-row [el] (Long/parseLong (str (attr el "srow"))))
(defn- end-row [el] (Long/parseLong (str (attr el "erow"))))

(defn- child-by-field [el f]
  (->> (children el)
       (filter #(= f (attr % "field")))
       first))

;; ─── Source-text helpers ──────────────────────────────────────────────────

(defn body-lines
  "Source lines covered by a node (1-based inclusive), from the file's
   line vector and the node's srow/erow attributes."
  [src-lines el]
  (let [srow (start-row el)
        erow (min (dec (count src-lines)) (end-row el))
        ecol (Long/parseLong (str (attr el "ecol")))
        last-line (if (pos? ecol) erow (max srow (dec erow)))]
    (subvec src-lines srow (inc last-line))))

(defn- signature-of
  "First line of a definition, trimmed of trailing block-openers and
   truncated — enough context to recognize the symbol, never the body.
   Returns nil for nodes that cover no source lines (malformed trees)."
  [src-lines el]
  (let [line (some->> (body-lines src-lines el)
                      first
                      str/trim)
        line (when line
               (if (contains? #{\( \{ \:} (last line))
                 (subs line 0 (dec (count line)))
                 line))]
    (when-not (str/blank? line)
      (subs line 0 (min (count line) 120)))))

;; ─── Rule application ─────────────────────────────────────────────────────

(defn- def-name-el
  "Name node for el under a def rule, or nil when the rule doesn't match.
   With :value-types, el must additionally have a child at field \"value\"
   whose type is in the set (e.g. only function-valued const bindings)."
  [el {:keys [type name-field first-sym-in value-types]}]
  (when (= type (tag-of el))
    (when (or (empty? value-types)
              (contains? (set value-types)
                         (some-> (child-by-field el "value") tag-of)))
      (cond
        name-field (child-by-field el name-field)
        first-sym-in (let [syms (filter #(= "sym_lit" (tag-of %)) (children el))
                           head (some->> (first syms) text-of str/trim)]
                       (when (contains? (set first-sym-in) head)
                         ;; first non-head, non-metadata sym after the head
                         (->> (rest syms)
                              (map (fn [s]
                                     [s (str/trim (text-of s))]))
                              (some (fn [[s t]]
                                      (when (and (not (str/starts-with? t ":"))
                                                 (not (contains? (set first-sym-in) t)))
                                        s))))))))))

(defn- call-callee
  "Invoked-name string for el under a call rule, or nil when no match."
  [el {:keys [type callee-field attr-field-by-type callee-first-sym-except]}]
  (when (= type (tag-of el))
    (cond
      callee-field (let [c (child-by-field el callee-field)]
                     (cond
                       (nil? c) nil
                       (and attr-field-by-type
                            (contains? attr-field-by-type (tag-of c)))
                       (some-> (child-by-field c (get attr-field-by-type (tag-of c)))
                               text-of str/trim not-empty)
                       :else (not-empty (str/trim (text-of c)))))
      callee-first-sym-except
      (let [syms (filter #(= "sym_lit" (tag-of %)) (children el))]
        (when (seq syms)
          (let [head (str/trim (text-of (first syms)))]
            (when-not (contains? (set callee-first-sym-except) head)
              (not-empty head))))))))

(defn collect
  "Walk one <source> element with the language rules. Returns
   {:symbols [{:name :kind :line :end-line :signature} ...]
    :calls   [{:name :line :enclosing} ...]}
   Lines are 1-based; :enclosing is the nearest enclosing def's name."
  [source-el src-lines rule-set]
  (let [acc (atom {:symbols [] :calls []})]
    (letfn [(visit [el enclosing]
              ;; first rule whose shape matches wins — several defs can
              ;; share a node type (clojure list_lit), so kind must come
              ;; from the SAME rule that matched the name, not a re-scan
              (if-let [[def-rule name-el] (some (fn [rule]
                                                  (when-some [n (def-name-el el rule)]
                                                    [rule n]))
                                                (:defs rule-set))]
                (let [name (str/trim (text-of name-el))]
                  (if-not (str/blank? name)
                    (do (swap! acc update :symbols conj
                               {:name name
                                :kind (:kind def-rule)
                                :line (inc (start-row el))
                                :end-line (inc (end-row el))
                                :signature (signature-of src-lines el)})
                        (doseq [c (children el)] (visit c name)))
                    ;; matched shape but no usable name -> keep walking
                    (doseq [c (children el)] (visit c enclosing))))
                (do (doseq [rule (:calls rule-set)]
                      (when-let [callee (call-callee el rule)]
                        (swap! acc update :calls conj
                               {:name callee
                                :line (inc (start-row el))
                                :enclosing enclosing})))
                    (doseq [c (children el)] (visit c enclosing)))))]
      (visit source-el nil))
    @acc))

;; ─── CLI plumbing ─────────────────────────────────────────────────────────

(def batch-size
  "Files per `parse` invocation (arg-vector length safety)."
  40)

(defn- libdir-env
  ([] (libdir-env nil))
  ([base] {"TREE_SITTER_LIBDIR" (str (paths/libs-dir base))}))

(defn parse-files!
  "Parse paths (all of one language, kept in the signature for symmetry
   with callers; the CLI discovers language per file extension) through the
   cached grammar; returns the <sources> XML element. Throws ::parse-failed
   on CLI errors. Opts: {:base dir :parse-runner fn}."
  ([paths lang] (parse-files! paths lang nil))
  ([paths _lang {:keys [base parse-runner] :as _opts}]
   (let [runner (or parse-runner cli/exec!)
         res (runner (into ["parse" "--wasm"
                            "--config-path" (str (paths/config-path base))
                            "-x"]
                           (map str paths))
                     {:base base
                      :env (merge {} (System/getenv) (libdir-env base))})]
     (when-not (zero? (or (:exit res) 1))
       (throw (ex-info (str "tree-sitter parse failed for "
                            (str/join ", " (take 3 (map str paths)))
                            (when-not (str/blank? (str (:err res)))
                              (str ": " (str/trim (:err res)))))
                       {:type ::parse-failed :paths (vec paths)
                        :result res})))
     (xml/parse-str (str/trim (str (:out res)))
                                :namespace-aware false))))

(defn sources-by-path
  "<source name=...> elements of a parsed <sources>, keyed by their name
   attribute (exactly the path strings passed to parse-files!)."
  [sources-el]
  (into {} (map (fn [s] [(str (attr s "name")) s]))
        (filter #(= :source (:tag %)) (:content sources-el))))

(defn file-symbols-and-calls
  "Symbols + calls for one already-parsed source element."
  [source-el src-lines lang]
  (collect source-el src-lines (rules lang)))

(defn analyze-file!
  "Parse one file and return its symbols+calls map.
   Opts: {:base dir :parse-runner fn}."
  ([path lang] (analyze-file! path lang nil))
  ([path lang opts]
   (let [src-text (slurp (str path))
         src-lines (str/split-lines src-text)
         path-str (str path)
         sources (sources-by-path (parse-files! [path-str] lang opts))]
     (if-let [el (get sources path-str)]
       (assoc (file-symbols-and-calls el src-lines lang) :src-lines src-lines)
       (throw (ex-info (str "parser returned no tree for " path-str)
                       {:type ::no-tree :path path-str}))))))
