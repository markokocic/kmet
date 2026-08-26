(ns extensions.lsp-adapter.tools
  "The agent-facing `lsp` tool (plan §11, Rev 2): operation dispatch with
   camelCase aliases, the single 1-based→0-based coordinate conversion,
   token-economical result shaping, and diagnostics rendering. Empty
   results report \"(no results)\"."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [kmet.libs.jsonrpc :as jrpc]
            [extensions.lsp-adapter.lsp :as lsp]
            [extensions.lsp-adapter.runtime :as runtime]))

;; ─── Operation table ──────────────────────────────────────────────────────

(def ^:private operations
  {"definition"           {:method "textDocument/definition"}
   "references"           {:method "textDocument/references"
                           :params (fn [td pos]
                                     {:textDocument td :position pos
                                      :context {:includeDeclaration true}})}
   "hover"                {:method "textDocument/hover"}
   "documentSymbol"       {:method "textDocument/documentSymbol"
                           :file-only true}
   "workspaceSymbol"      {:method "workspace/symbol"
                           :symbol-query true}
   "implementation"       {:method "textDocument/implementation"}
   "prepareCallHierarchy" {:method "textDocument/prepareCallHierarchy"}
   "incomingCalls"        {:hierarchy "callHierarchy/incomingCalls"}
   "outgoingCalls"        {:hierarchy "callHierarchy/outgoingCalls"}
   "diagnostics"          {:diagnostics true}})

(def ^:private aliases
  {"goToDefinition" "definition"
   "findReferences" "references"
   "goToImplementation" "implementation"})

(def ^:private usage
  (str "usage: lsp({operation, filePath[, line, character, query]}) — "
       "operations: definition references hover documentSymbol "
       "workspaceSymbol implementation prepareCallHierarchy incomingCalls "
       "outgoingCalls diagnostics; line/character are 1-based"))

(defn- canonical-op
  "Canonical operation KEYWORD (:definition …) or nil."
  [op]
  ;; NB: bind the NAME, not the matched spec map
  (let [name (or (and (contains? operations op) op)
                 (get aliases op))]
    (when name (keyword name))))

;; ─── Shaping (token economy) ─────────────────────────────────────────────

(def ^:private max-lines 50)
(def ^:private max-hover-chars 2000)

(defn- rel-path [path cwd]
  (let [p (str path)]
    (if (and cwd (str/starts-with? p (str cwd "/")))
      (subs p (inc (count (str cwd))))
      p)))

(defn- kind-name [k] (or ({1 "file" 2 "module" 3 "namespace" 5 "class"
                           6 "method" 7 "property" 8 "field" 9 "constructor"
                           10 "enum" 11 "interface" 12 "fn" 13 "var"
                           14 "const" 20 "struct" 25 "type-param"} k)
                         (str "kind:" k)))

(defn- shape-locations
  "Array-or-single Location → deduped `path:line:col` lines, capped."
  [res]
  (let [locs (cond (map? res) [res]
                   (vector? res) res
                   :else [])
        lines (distinct
               (keep (fn [{:keys [uri range]}]
                       (when (and uri range)
                         (format "%s:%s:%s"
                                 (lsp/uri->path uri)
                                 (inc (get-in range [:start :line]))
                                 (inc (get-in range [:start :character])))))
                     locs))]
    (if (empty? lines)
      nil
      (concat (take max-lines lines)
              (when (> (count lines) max-lines)
                [(str "… and " (- (count lines) max-lines) " more")])))))

(defn- shape-hover [res]
  (let [contents (:contents res)]
    (when contents
      (let [text (cond
                   (string? contents) contents
                   (map? contents) (or (:value contents) "")
                   (vector? contents) (str/join "\n"
                                                (map (fn [c]
                                                       (or (:value c)
                                                           (if (string? c) c "")))
                                                     contents))
                   :else (str contents))]
        (when-not (str/blank? text)
          [(if (> (count text) max-hover-chars)
             (str (subs text 0 max-hover-chars) "…")
             text)])))))

(defn- flatten-symbol
  ([sym] (flatten-symbol sym 0))
  ([sym depth]
   (concat [(str (apply str (repeat depth "  "))
                 (or (:name sym) "?") " " (kind-name (:kind sym)))]
           (mapcat #(flatten-symbol % (inc depth)) (:children sym [])))))

(defn- shape-symbols [res]
  (let [lines (mapcat #(flatten-symbol %) (or res []))]
    (when (seq lines) (take max-lines lines))))

(defn- shape-call-item [item]
  (format "%s %s @ %s:%s"
          (or (:name item) "?")
          (kind-name (:kind item))
          (some-> item :uri lsp/uri->path)
          (some-> item :range :start :line inc)))

(defn- shape-hierarchy
  "Incoming/outgoing call results → caller/callee lines with site counts."
  [res]
  (let [lines (for [{:keys [from fromRanges]} (or res [])
                    :when (map? from)]
                (let [n (count fromRanges)]
                  (str (shape-call-item from)
                       (when (pos? n)
                         (str " (" n " site" (when (> n 1) "s") ")")))))]
    (when (seq lines) lines)))

(defn- shape-result
  "Per-operation shaping of one conn's raw result; nil when empty.
   OP is the canonical keyword (:definition …)."
  [op res]
  (case op
    :definition (shape-locations res)
    :references (shape-locations res)
    :implementation (shape-locations res)
    :hover (shape-hover res)
    :documentSymbol (shape-symbols res)
    :workspaceSymbol (shape-symbols res)
    :prepareCallHierarchy (let [items (mapv shape-call-item (or res []))]
                            (when (seq items) items))
    :incomingCalls (shape-hierarchy res)
    :outgoingCalls (shape-hierarchy res)
    nil))

(defn- assemble
  "Multi-server output: labelled sections per conn (already shaped),
   failures listed last, nil when everything came back empty."
  [results errors cwd]
  (let [sections (for [{:keys [name root lines]} results
                       :when (seq lines)]
                   (into [(str "── " name " (" (rel-path root cwd) ") ──")]
                         lines))
        failure-lines (map (fn [{:keys [name message]}]
                             (str name ": " message))
                           errors)
        body (concat (apply concat sections) failure-lines)]
    (when-not (every? str/blank? body)
      (str/join "\n" body))))

;; ─── Diagnostics rendering ───────────────────────────────────────────────

(def ^:private severity-names {1 "ERROR" 2 "WARN" 3 "INFO" 4 "HINT"})
(def ^:private max-per-file 8)
(def ^:private flood-threshold 40)
(def ^:private max-project-files 5)

(defn- render-diag
  [{:keys [severity range message source]}]
  (format "%s [%s:%s] %s%s"
          (get severity-names severity "ERROR")
          (inc (get-in range [:start :line]))
          (inc (get-in range [:start :character]))
          (str/trim (or message ""))
          (if source (str " (" source ")") "")))

(defn- render-file-diags
  "Capped per-file block; a flood collapses to one line — a flood is almost
   never caused by the agent, and a wall of noise is the opposite of useful."
  [entries]
  (let [sorted (vec (sort-by #(get-in % [:range :start :line]) entries))
        n (count sorted)]
    (cond
      (= 0 n) nil
      (>= n flood-threshold)
      [(format "%d diagnostics (flood suppressed)" n)]
      :else
      (concat (map render-diag (take max-per-file sorted))
              (when (> n max-per-file)
                [(str "… and " (- n max-per-file) " more")])))))

(defn diagnostics-report
  "The `diagnostics` operation: queried file plus up to MAX-PROJECT-FILES
   other files with errors, across every live conn claiming PATH. Reads
   collected push state only — no server round-trip."
  [st path]
  (let [cwd (str (fs/cwd))
        file-uri (lsp/path->uri path)
        claimed (set (map (comp str :root) (runtime/claiming-specs st path)))
        relevant (filter (fn [conn]
                           (contains? claimed (str (:root conn))))
                         (runtime/all-conns st))
        file-blocks (for [conn relevant
                          :let [entry (get (runtime/diagnostics-for conn)
                                           file-uri)
                                block (render-file-diags (:diagnostics entry []))]
                          :when block]
                      {:label (str (:name conn) " @ "
                                   (rel-path (:root conn) cwd))
                       :lines block})
        others (for [conn relevant
                     [uri {:keys [diagnostics]}]
                     (runtime/diagnostics-for conn)
                     :let [errs (filter #(= 1 (:severity %)) diagnostics)]
                     :when (and (not= uri file-uri) (seq errs))]
                 {:path (lsp/uri->path uri) :errors (count errs)})
        top-others (->> others (group-by :path)
                        (map (fn [[p xs]]
                               {:path p
                                :errors (transduce (map :errors) + 0 xs)}))
                        (sort-by :errors >)
                        (take max-project-files))]
    (if (and (empty? file-blocks) (empty? top-others))
      "(no results)"
      (str/join "\n"
                (concat
                 (mapcat (fn [{:keys [label lines]}]
                           (into [(str "── " label " ──")] lines))
                         file-blocks)
                 (when (seq top-others)
                   (into ["── project (files with errors) ──"]
                         (map (fn [{:keys [path errors]}]
                                (str (rel-path path cwd) ": " errors " error"
                                     (when (> errors 1) "s")))
                              top-others))))))))

;; ─── Tool entry point ────────────────────────────────────────────────────

(defn- resolve-path [cwd p]
  (let [p (str p)]
    (if (fs/absolute? p) p (str (fs/path cwd p)))))

(defn- usage-error [msg]
  (throw (ex-info (str msg ". " usage) {:type ::usage})))

(defn execute
  "Tool executor. SIGNAL is the run's abort atom — polled before starting,
   so ESC skips the round-trip entirely (in-flight waits stay bounded by
   request timeouts). Returns the shaped multi-server string."
  [st signal args]
  (let [args (or args {})
        raw-op (:operation args)
        op (canonical-op raw-op)
        spec (some-> op name operations)
        cwd (str (fs/cwd))]
    (cond
      (str/blank? (str raw-op)) (usage-error "operation is required")
      (nil? op) (usage-error (str "unknown operation: " raw-op))
      (and signal @signal) "(aborted)"
      (not (:filePath args)) (usage-error "filePath is required")
      :else
      (let [path (resolve-path cwd (:filePath args))
            line (:line args)
            character (:character args)
            position-op? (not (or (:file-only spec) (:symbol-query spec)
                                  (:diagnostics spec)))]
        (when position-op?
          (when-not (and (number? line) (number? character))
            (usage-error (str (name op) " requires numeric 1-based line and "
                              "character"))))
        (when (and (:symbol-query spec) (str/blank? (:query args)))
          (usage-error "workspaceSymbol requires query"))
        (if (:diagnostics spec)
          (diagnostics-report st path)
          (do
            (when-not (fs/exists? path)
              (throw (ex-info (str "no such file: " path) {:type ::usage})))
            (let [td {:uri (lsp/path->uri path)}
                  ;; 1-based wire input, clamped against 0/negative values
                  pos {:line (max 0 (dec (or line 1)))
                       :character (max 0 (dec (or character 1)))}
                  ;; position ops without a custom :params fn still must
                  ;; carry the position — servers 500 without one (-32603)
                  params (cond
                           (:params spec) ((:params spec) td pos)
                           (:symbol-query spec) {:query (:query args)}
                           position-op? {:textDocument td :position pos}
                           :else {:textDocument td})
                  request (fn [conn method prm]
                            (jrpc/request! (:client conn) method prm {}))
                  hierarchy? (boolean (:hierarchy spec))
                  {:keys [results errors]}
                  (runtime/for-file st path
                                    (fn [conn]
                                      (if hierarchy?
                                        (let [prep (request
                                                    conn
                                                    "textDocument/prepareCallHierarchy"
                                                    {:textDocument td
                                                     :position pos})
                                              item (first prep)]
                                          (if item
                                            (request conn (:hierarchy spec)
                                                     {:item item})
                                            ::none))
                                        (request conn (:method spec) params))))
                  shaped (for [{:keys [name root value]} results]
                           {:name name
                            :root root
                            :lines (if (= ::none value)
                                     []
                                     (or (shape-result op value) []))})]
              (or (assemble shaped errors cwd)
                  "(no results)"))))))))
