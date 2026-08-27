(ns kmet.extensions.tree-sitter.symbols
  "Structural code intelligence over `parse --wasm` trees.

   The CLI's `query` subcommand cannot load WASM grammars (it is
   native-dlopen only — verified against 0.26.13), so instead of .scm
   queries we parse files to s-expression trees (the CLI's default
   output format) and walk them with small per-language rule sets
   shipped as resources (queries/<lang>.edn). Rule contract:

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
   find_callers / find_callees work without any query-engine support.

   Tree nodes come from kmet.extensions.tree-sitter.sexp:
   [type [srow scol] [erow ecol] child...] where positions are BYTE
   offsets (tree-sitter's native coordinates — verified against unicode
   input). The sexp form carries no source text (unlike XML), so symbol
   and callee names are recovered by slicing the source at those byte
   ranges. The CLI prints one tree per file (argument order) and — for
   files with syntax problems — a tab-separated stats line after the
   tree; split-trees pairs trees back to paths by argument order."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [kmet.extensions.tree-sitter.cli :as cli]
            [kmet.extensions.tree-sitter.paths :as paths]
            [kmet.extensions.tree-sitter.sexp :as sexp]))

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

;; ─── byte-offset text slicing ────────────────────────────────────────────

(defn- line-encoding
  "Per-line ASCII? flag vector — lets byte-slice skip the byte[] round-trip
   on the overwhelmingly common all-ASCII line."
  [src-lines]
  (mapv (fn [l]
          (and (some? l) (every? (fn [ch] (<= (int ch) 127)) l)))
        src-lines))

(defn- byte-slice
  "Source text for a node's byte range on one line: LINE is the 0-based
   source line, [bstart bend) are byte offsets into it. Positions come
   from the CLI (byte-based), so the string is decoded at the byte
   boundaries — names in non-ASCII files must survive this (verified:
   tree-sitter reports byte columns, e.g. `händler` is [0,6]-[0,14]).
   ASCII lines take the subs fast path (no byte[] round-trip)."
  ([src-lines line bstart bend] (byte-slice src-lines (line-encoding src-lines) line bstart bend))
  ([src-lines ascii? line bstart bend]
   (let [l (when (and src-lines line (integer? line)) (nth src-lines line nil))]
     (when (and l (integer? bstart) (integer? bend) (<= 0 bstart bend))
       (if (str/blank? l)
         nil
         (let [s (min (count l) bstart)
               e (min (count l) bend)]
           (when (<= s e)
             (if (nth ascii? line true)
               (subs l s e)
               (let [bytes (.getBytes l "UTF-8")
                     n (count bytes)
                     bs (min n bstart)
                     be (min n bend)]
                 (String. bytes bs (- be bs) "UTF-8"))))))))))

(defn- node-text
  "Text covered by a node (single-line only — names/callees are), or nil
   when the node is absent/spans lines/the range is out of bounds."
  ([src-lines node] (node-text src-lines (line-encoding src-lines) node))
  ([src-lines ascii? node]
   (when (and node (sexp/node? node))
     (let [[sr sc] (sexp/start-pos node)
           [er ec] (sexp/end-pos node)]
       (when (= sr er)
         (let [t (byte-slice src-lines ascii? sr sc ec)]
           (when t (str/trim t))))))))

;; ─── Source-text helpers ──────────────────────────────────────────────────

(defn body-lines
  "Source lines covered by a node (1-based inclusive), from the file's
   line vector and the node's start/end rows."
  [src-lines node]
  (let [[srow _] (sexp/start-pos node)
        [erow ecol] (sexp/end-pos node)
        erow (min (dec (count src-lines)) erow)
        last-line (if (pos? ecol) erow (max srow (dec erow)))]
    (subvec src-lines srow (inc last-line))))

(defn- signature-of
  "First line of a definition, trimmed of trailing block-openers and
   truncated — enough context to recognize the symbol, never the body.
   Returns nil for nodes that cover no source lines (malformed trees)."
  [src-lines node]
  (let [line (some->> (body-lines src-lines node)
                      first
                      str/trim)
        line (when line
               (if (contains? #{\( \{ \:} (last line))
                 (subs line 0 (dec (count line)))
                 line))]
    (when-not (str/blank? line)
      (subs line 0 (min (count line) 120)))))

;; ─── Rule application ─────────────────────────────────────────────────────

(defn- children-nodes
  "Node children, unwrapping {:name f :node n} field wrappers."
  [node]
  (keep #(if (map? %) (:node %) %) (sexp/children node)))

(defn- rule-index
  "Rules grouped by the node type they match, so the walk only tries
   rules whose :type equals the node's — list_lit nodes never scan
   python/ts rules and vice versa. The first-sym-in / value-types /
   except sets are precomputed once per rule instead of per node."
  [rule-set]
  (letfn [(prepare [rules]
            (mapv (fn [rule]
                    (cond-> rule
                      (:first-sym-in rule)
                      (assoc :first-sym-in (set (:first-sym-in rule)))
                      (:value-types rule)
                      (assoc :value-types (set (:value-types rule)))
                      (:callee-first-sym-except rule)
                      (assoc :callee-first-sym-except
                             (set (:callee-first-sym-except rule)))))
                  rules))]
    {:defs (reduce-kv (fn [m type rules]
                        (assoc m type (prepare rules)))
                      {}
                      (group-by :type (:defs rule-set)))
     :calls (reduce-kv (fn [m type rules]
                         (assoc m type (prepare rules)))
                       {}
                       (group-by :type (:calls rule-set)))}))

(defn- def-name-node
  "Name node for NODE under a def rule, or nil when the rule doesn't match.
   With :value-types, NODE must additionally have a child at field \"value\"
   whose type is in the set (e.g. only function-valued const bindings).
   For :first-sym-in rules (clojure) the head sym's text is recovered from
   SRC-LINES via the precomputed ASCII flag vector — the sexp tree
   carries no text."
  [node src-lines ascii? {:keys [type name-field first-sym-in value-types]}]
  (when (= type (sexp/node-type node))
    (when (or (empty? value-types)
              (contains? value-types
                         (some-> (sexp/child-by-field node "value")
                                 sexp/node-type)))
      (cond
        name-field (sexp/child-by-field node name-field)
        first-sym-in (let [syms (filter #(= "sym_lit" (sexp/node-type %))
                                        (children-nodes node))
                           head (some->> (first syms) (node-text src-lines ascii?))]
                       (when (contains? first-sym-in head)
                         (second syms)))))))

(defn- call-callee
  "Invoked-name string for NODE under a call rule, or nil when no match.
   HEAD-TEXT is the already-sliced first-sym text for clojure list_lits
   (computed once in collect); nil for other node types."
  [node src-lines ascii? head-text {:keys [type callee-field attr-field-by-type
                                           callee-first-sym-except]}]
  (when (= type (sexp/node-type node))
    (cond
      callee-field (let [c (sexp/child-by-field node callee-field)]
                     (cond
                       (nil? c) nil
                       (and attr-field-by-type
                            (contains? attr-field-by-type (sexp/node-type c)))
                       (node-text src-lines ascii?
                                  (sexp/child-by-field
                                   c (get attr-field-by-type (sexp/node-type c))))
                       :else (node-text src-lines ascii? c)))
      callee-first-sym-except
      (let [sym head-text]
        (when (and sym (not (contains? callee-first-sym-except sym))
                   (not (str/starts-with? sym ":")))
          sym)))))

(defn collect
  "Walk one tree root with the language rules. Returns
   {:symbols [{:name :kind :line :end-line :signature} ...]
    :calls   [{:name :line :enclosing} ...]}
   Lines are 1-based; :enclosing is the nearest enclosing def's name.
   Names come from SRC-LINES via byte-range slicing (the sexp tree
   carries no text)."
  [root src-lines rule-set]
  (let [idx (rule-index rule-set)
        ascii? (line-encoding src-lines)
        acc (atom {:symbols [] :calls []})]
    (letfn [(visit [node enclosing]
              ;; first rule whose shape matches wins — several defs can
              ;; share a node type (clojure list_lit), so kind must come
              ;; from the SAME rule that matched the name, not a re-scan
              (let [t (sexp/node-type node)
                    def-rules (get (:defs idx) t)
                    call-rules (get (:calls idx) t)
                    kids (children-nodes node)
                    syms (when (= t "list_lit")
                           (filter #(= "sym_lit" (sexp/node-type %)) kids))
                    head-text (some->> syms first (node-text src-lines ascii?))
                    match (when def-rules
                            (some (fn [rule]
                                    (if-let [fsi (:first-sym-in rule)]
                                      (when (contains? fsi head-text)
                                        [rule (second syms)])
                                      (when-some [n (def-name-node node src-lines ascii? rule)]
                                        [rule n])))
                                  def-rules))]
                (if match
                  (let [[def-rule name-node] match
                        name (node-text src-lines ascii? name-node)]
                    (if-not (str/blank? name)
                      (do (swap! acc update :symbols conj
                                 {:name name
                                  :kind (:kind def-rule)
                                  :line (inc (first (sexp/start-pos node)))
                                  :end-line (inc (first (sexp/end-pos node)))
                                  :signature (signature-of src-lines node)})
                          (doseq [c kids] (visit c name)))
                      ;; matched shape but no usable name -> keep walking
                      (doseq [c kids] (visit c enclosing))))
                  (do (doseq [rule call-rules]
                        (when-some [callee (call-callee node src-lines ascii? head-text rule)]
                          (swap! acc update :calls conj
                                 {:name callee
                                  :line (inc (first (sexp/start-pos node)))
                                  :enclosing enclosing})))
                      (doseq [c kids] (visit c enclosing))))))]
      (visit root nil))
    @acc))

;; ─── CLI plumbing ─────────────────────────────────────────────────────────

(def batch-size
  "Files per `parse` invocation (arg-vector length safety)."
  40)

(defn- libdir-env
  ([] (libdir-env nil))
  ([base] {"TREE_SITTER_LIBDIR" (str (paths/libs-dir base))}))

(defn- stats-line?
  "Stats lines are tab-separated (`path<TAB>Parse: …<TAB>…<TAB>(ERROR|MISSING …)`),
   emitted after a file's tree only when that file has syntax problems."
  [line]
  (str/includes? line "\t"))

(defn- split-trees
  "Pair each parsed tree with its path. The CLI prints trees in argument
   order with a stats line (tab-separated) after problem files; empty
   files print nothing. Returns a map path -> tree-string."
  [paths out]
  (let [out (str out)
        parts (str/split out #"\n\(")
        ;; re-attach the consumed '(' and drop trailing stats lines
        ;; (tab-separated, after a problem file's tree) and blanks
        clean (fn [part]
                (let [part (if (str/starts-with? part "(")
                             part
                             (str "(" part))]
                  ;; stats lines (path<TAB>Parse: …<TAB>(ERROR|MISSING …))
                  ;; only appear after problem files — skip the split-lines
                  ;; dance unless a tab is actually present
                  (if (str/includes? part "\t")
                    (->> (str/split-lines part)
                         (remove stats-line?)
                         (remove #(str/blank? (str/trim %)))
                         (str/join "\n"))
                    part)))
        tree-strs (->> parts
                       (map clean)
                       (map str/trim)
                       (remove str/blank?))]
    (->> (interleave paths tree-strs)
         (partition 2)
         (map vec)
         (into {}))))

(def ^:private tree-cache
  "path-string -> [stamp tree] for trees parsed this process. Project-wide
   tools (find_callers/find_definition) re-scan the same files repeatedly
   in one session; the stamp (size+mtime) detects edits so the cache can
   never serve stale trees."
  (atom {}))

(defn- stamp
  [path]
  (when (fs/regular-file? path)
    [(fs/size path) (.toMillis (fs/last-modified-time path))]))

(defn- parse-one
  "Parse a single tree-string into a tree (nil when the file printed
   nothing — empty files have no tree)."
  [tree-str]
  (sexp/parse-tree tree-str))

(defn parse-files!
  "Parse paths (all of one language — the CLI discovers language per file
   extension) through the cached grammar; returns a map path-string ->
   parsed tree root, or nil for files with no output (empty files print
   no tree). Throws ::parse-failed on CLI errors.
   Opts: {:base dir :parse-runner fn :cache bool} — :cache nil disables
   the per-process tree cache (tests)."
  ([paths lang] (parse-files! paths lang nil))
  ([paths _lang {:keys [base parse-runner cache] :as _opts}]
   (let [cache? (if (nil? cache) true cache)
         paths (mapv str paths)
         cached (when cache?
                  (into {}
                        (keep (fn [p]
                                (when-let [[st tree] (get @tree-cache p)]
                                  (when (and st (= st (stamp p)))
                                    [p tree]))))
                        paths))
         fresh (vec (remove cached paths))]
     (if (empty? fresh)
       cached
       (let [runner (or parse-runner cli/exec!)
             res (runner (into ["parse" "--wasm"
                                "--config-path" (str (paths/config-path base))]
                               (map str fresh))
                         {:base base
                          :env (merge {} (System/getenv) (libdir-env base))})]
         (when (:error res)
           ;; infra failure (spawn/timeout) — never a parse result
           (throw (ex-info (str "tree-sitter parse failed: " (:reason res))
                           {:type ::parse-failed :paths fresh
                            :result res})))
         ;; The CLI exits non-zero when ANY file has syntax problems, but
         ;; still prints every tree (problem files additionally get a stats
         ;; line). Only a non-zero exit with NO tree output is a failure.
         (when (and (not (zero? (or (:exit res) 1)))
                    (str/blank? (str/trim (str (:out res)))))
           (throw (ex-info (str "tree-sitter parse failed for "
                                (str/join ", " (take 3 (map str fresh)))
                                (when-not (str/blank? (str (:err res)))
                                  (str ": " (str/trim (:err res)))))
                           {:type ::parse-failed :paths fresh
                            :result res})))
         (let [parsed (->> (split-trees fresh (:out res))
                           (reduce (fn [m [p tree-str]]
                                     (assoc m p (parse-one tree-str)))
                                   {}))]
           (when cache?
             (doseq [[p tree] parsed]
               (swap! tree-cache assoc p [(stamp p) tree])))
           (merge cached parsed)))))))

(def ^:private result-cache
  "path-string -> [stamp {:symbols [...] :calls [...]}] — the collect
   output per file, so repeated project-wide queries (find_callers /
   find_definition in one session) don't re-walk every tree."
  (atom {}))

(defn file-symbols-and-calls
  "Symbols + calls for one already-parsed tree (cached per file stamp)."
  ([tree src-lines lang] (file-symbols-and-calls tree src-lines lang nil))
  ([tree src-lines lang path]
   (if-not path
     (collect tree src-lines (rules lang))
     (let [st (stamp path)
           [cst res] (get @result-cache path)
           fresh (or (not st) (not= cst st))]
       (if fresh
         (let [res (collect tree src-lines (rules lang))]
           (when st
             (swap! result-cache assoc path [st res]))
           res)
         res)))))

(defn analyze-file!
  "Parse one file and return its symbols+calls map.
   Opts: {:base dir :parse-runner fn}."
  ([path lang] (analyze-file! path lang nil))
  ([path lang opts]
   (let [src-text (slurp (str path))
         src-lines (str/split-lines src-text)
         path-str (str path)
         tree (get (parse-files! [path-str] lang opts) path-str)]
     (if tree
       (assoc (file-symbols-and-calls tree src-lines lang path-str)
              :src-lines src-lines)
       (throw (ex-info (str "parser returned no tree for " path-str)
                       {:type ::no-tree :path path-str}))))))
