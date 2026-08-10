(ns kmet.app.session
  "EDNL session storage — line-delimited EDN with parent-child IDs for tree branching.
   Each entry is an EDN map on one line: {:id str :parent-id str-or-nil :role keyword ...}
   Port of @earendil-works/pi-agent session storage."
  (:require [clojure.java.io :as io]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [babashka.fs :as fs]))

;; ─── Session record ─────────────────────────────────────────────────────────
;; :lock (a ReentrantLock) serializes all file mutations (pi:
;; JsonlSessionStorage.enqueue — a promise chain serializes
;; appends/rewrites). Without it, two concurrent appends (e.g. a ! bash
;; result landing on its future thread while a submitted message appends on
;; the agent thread) both read the same leaf and write sibling entries — the
;; branch walk then orphans one of them, so a restored session silently
;; loses messages.

(defrecord Session [file id header entries leaf-id lock])

(defmacro with-session-lock
  "Run BODY under the session's mutation lock (pi: storage.enqueue).
   Reentrant, so nested session operations inside the body are safe."
  [session & body]
  `(let [lock# (:lock ~session)]
     (.lock lock#)
     (try ~@body
          (finally (.unlock lock#)))))

(defn- generate-id
  "Generate a unique entry id: hex timestamp + 16-bit hex random, collision
   checked against the session's existing entry ids (pi: generateId —
   collision-checked against the index)."
  [entries]
  (let [existing (into #{} (map :id) entries)]
    (loop []
      (let [id (str (format "%x" (System/currentTimeMillis))
                    "-" (format "%x" (rand-int 0xFFFF)))]
        (if (contains? existing id)
          (recur)
          id)))))

(defn- timestamp []
  (java.time.Instant/now))

(defn- build-entry
  "Pure: assign id (collision-checked against entries), parent-id (current
   leaf), timestamp."
  [entries leaf-id entry]
  (assoc entry
         :id (generate-id entries)
         :parent-id leaf-id
         :timestamp (str (timestamp))))

(defn- publish-file!
  "Atomically write the session file: the header line followed by the
   entries, via temp file + rename so a crash mid-write can't corrupt the
   file (pi: temp-file publication). Creates the session dir. Callers must
   hold the session lock."
  [session entries]
  (let [file (:file session)
        tmp (str file ".tmp")]
    (fs/create-dirs (fs/parent file))
    (spit tmp (apply str (map prn-str (cons (:header session) entries))))
    (fs/move tmp file {:replace-existing true})))

(defn- persist-if-needed!
  "Lazy file creation (pi: _persist — G4): write the file the first time the
   branch contains an assistant message; before that, entries accumulate in
   memory only and no file exists on disk. No-op once the file exists.
   Callers must hold the session lock."
  [session]
  (when (and (not (fs/exists? (:file session)))
             (some #(= :assistant (:role %)) @(:entries session)))
    (publish-file! session @(:entries session))))

(defn- write-entries!
  "Atomically replace the session file's contents and in-memory state (pi:
   temp-file publication). Rewrites whenever the file exists; a
   not-yet-persisted session (lazy creation — G4) is only written once the
   branch has an assistant message. Callers must hold the session lock."
  [session entries]
  (let [entries (vec entries)]
    (when (or (fs/exists? (:file session))
              (some #(= :assistant (:role %)) entries))
      (publish-file! session entries))
    (reset! (:entries session) entries)
    (reset! (:leaf-id session) (some-> entries last :id))))

;; ─── Layout (pi: cwd-encoded session dirs) ───────────────────────────────

(defn encode-cwd
  "Encode a cwd path into a safe directory name: `--` + the cwd with its
   leading slash stripped and / \\ : replaced by - + `--` (pi:
   getDefaultSessionDirPath — sessions/<--cwd-->/)."
  [cwd]
  (str "--" (-> (str cwd)
                (str/replace #"^[/\\]" "")
                (str/replace #"[/\\:]" "-"))
       "--"))

(defn session-dir-for-cwd
  "Session directory for a cwd under a base sessions dir: BASE/<--cwd-->/
   (pi: default layout sessions/<--cwd-->/)."
  [base-dir cwd]
  (str (fs/path base-dir (encode-cwd cwd))))

;; ─── CRUD ───────────────────────────────────────────────────────────────────

(defn create-session
  "Create a new session record in DIR. No file is written until the first
   assistant message (pi: newSession + lazy _persist — G4); the record
   carries a :header {:type :session :version 1 :id :created-at :cwd
   :parent-session?} (G1) and the computed :file
   <timestamp>_<id>.ednl. opts: :cwd (defaults to the process cwd),
   :parent-session (source session file, for forks)."
  ([dir] (create-session dir nil))
  ([dir {:keys [cwd parent-session]}]
   (let [id (generate-id [])
         ts (str (timestamp))
         header (cond-> {:type :session
                         :version 1
                         :id id
                         :created-at ts
                         :cwd (or cwd (str (fs/cwd)))}
                  parent-session (assoc :parent-session parent-session))
         file (io/file dir (str (str/replace ts #"[:.]" "-") "_" id ".ednl"))]
     (fs/create-dirs dir)
     (map->Session {:file (str (fs/canonicalize file))
                    :id id
                    :header header
                    :entries (atom [])
                    :leaf-id (atom nil)
                    :lock (java.util.concurrent.locks.ReentrantLock.)}))))

(defn load-session
  "Load an existing session from file path. Returns Session record.
   A leading :session header line (G1) is parsed into :header and excluded
   from the entries; legacy files without a header load with :header nil
   and :id derived from the filename."
  [path]
  (let [file (io/file path)
        content (slurp file)
        lines (str/split-lines content)
        parsed (vec (keep identity
                          (for [line lines]
                            (let [trimmed (str/trim line)]
                              (when (seq trimmed)
                                (try (edn/read-string trimmed)
                                     (catch Exception ex
                                       (binding [*out* *err*]
                                         (println "Warning: Skipping invalid entry in" path ":" (ex-message ex)))
                                       nil)))))))
        header? (and (seq parsed) (= :session (:type (first parsed))))
        header (when header? (first parsed))
        entries (if header? (subvec parsed 1) parsed)
        leaf-id (some-> entries last :id)]
    (map->Session {:file (str (fs/canonicalize file))
                   :id (or (:id header)
                           (str/replace (fs/file-name file) #"\.ednl$" ""))
                   :header header
                   :entries (atom entries)
                   :leaf-id (atom leaf-id)
                   :lock (java.util.concurrent.locks.ReentrantLock.)})))

(defn append-entry
  "Append an entry to the session atom, persisting it to the file (pi:
   storage.appendEntry is enqueued; _persist defers the file write until the
   first assistant message — lazy creation G4). Serialized per session so
   concurrent appends (bash-result future + agent loop) can't produce
   orphaned sibling entries."
  [session entry]
  (with-session-lock session
    (let [entry (build-entry @(:entries session) @(:leaf-id session) entry)
          file (:file session)]
      (swap! (:entries session) conj entry)
      (reset! (:leaf-id session) (:id entry))
      (if (fs/exists? file)
        (spit file (prn-str entry) :append true)
        (persist-if-needed! session))
      entry)))

(defn replace-entries!
  "Atomically replace the session with a fresh linear branch built from raw
   entries: ids/parents/timestamps assigned in order (pi: the session file is
   rewritten to mirror a replaced context). Serialized and published via
   temp-file + rename so a crash mid-write can't corrupt the file."
  [session raw-entries]
  (with-session-lock session
    (write-entries! session
                    (loop [raw (seq raw-entries) leaf nil built []]
                      (if-let [e (first raw)]
                        (let [e (build-entry built leaf e)]
                          (recur (next raw) (:id e) (conj built e)))
                        built)))))

(defn get-branch
  "Get entries from root to leaf-id (active branch)."
  [session]
  (let [entries @(:entries session)]
    (if (empty? entries)
      []
      (let [index (reduce (fn [m e] (assoc m (:id e) e)) {} entries)
            path (volatile! [])]
        (loop [id @(:leaf-id session)]
          (when id
            (when-let [e (get index id)]
              (vswap! path conj e)
              (recur (:parent-id e)))))
        (vec (reverse @path))))))

;; ─── Context build (pi: buildSessionContext) ─────────────────────────────

(defn context-entries
  "Pure port of pi buildContextEntries over a branch vector (entries in
   root→leaf order). With a compaction on the path: [compaction, ...entries
   from its first-kept-id] — summarized history is excluded from context but
   stays in the file. Without one: the full branch. Multiple compactions:
   the latest wins (pi walks the path and keeps the last compaction seen)."
  [branch]
  (let [compaction-idx (last (keep-indexed (fn [i e] (when (= :compaction (:role e)) i))
                                           branch))]
    (if (nil? compaction-idx)
      branch
      (let [compaction (nth branch compaction-idx)
            kept (subvec branch 0 compaction-idx)
            kept-tail (drop-while #(not= (:id %) (:first-kept-id compaction)) kept)
            after-compaction (subvec branch (inc compaction-idx))]
        (into [compaction] (concat kept-tail after-compaction))))))

(defn build-context
  "Context entries along the active branch (pi: buildContextEntries — see
   context-entries)."
  [session]
  (context-entries (get-branch session)))

(defn context-messages
  "Project a session entry into LLM context messages (pi:
   sessionEntryToContextMessages). Message entries pass through unchanged;
   compaction entries become a single :user message carrying the summary
   (kmet providers don't know pi's compactionSummary role — the :user mapping
   mirrors the pre-append-only summary entry); :info and :session_info are
   metadata and excluded. Excluded :bash entries are kept here and dropped
   later by the LLM conversion (pi: convertToLlm filters excludeFromContext)."
  [entry]
  (case (:role entry)
    :compaction [{:role :user :content [{:type :text :text (str (:summary entry))}]}]
    (:user :assistant :tool :bash) [entry]
    []))

;; ─── Session display name (pi: /name command) ─────────────────────────────

(defn sanitize-session-name
  "Sanitize a session display name: collapse newlines to spaces, trim
   (pi: appendSessionInfo — [\\r\\n]+ → space)."
  [name]
  (-> (str name)
      (str/replace #"[\r\n]+" " ")
      str/trim))

(defn append-session-info!
  "Set the session display name: appends a session_info entry carrying the
   sanitized name (pi: appendSessionInfo). Returns the entry."
  [session name]
  (append-entry session {:role :session_info :name (sanitize-session-name name)}))

(defn get-session-name
  "Current session display name from the latest session_info entry, or nil.
   Empty names explicitly clear the title (pi: getSessionName — walks
   entries in reverse; the latest session_info entry wins, even when its
   name is empty)."
  [session]
  (loop [entries (reverse @(:entries session))]
    (when-let [e (first entries)]
      (if (= :session_info (:role e))
        (let [n (str/trim (str (:name e "")))]
          (when (seq n) n))
        (recur (rest entries))))))

(defn get-tree
  "Build a tree structure from session entries.
   Returns map of {:id info, :children [...]}"
  [session]
  (let [entries @(:entries session)
        children (fn [parent-id]
                   (filter #(= (:parent-id %) parent-id) entries))
        root-children (filter #(nil? (:parent-id %)) entries)]
    (letfn [(build-node [entry]
              {:id (:id entry)
               :role (:role entry)
               :summary (let [content (:content entry)
                              text (if (string? content) content
                                       (str/join (map :text (filter #(= (:type %) :text) content))))
                              trimmed (str/trim text)]
                          (cond
                            (seq trimmed) (subs trimmed 0 (min 60 (count trimmed)))
                            ;; session_info entries carry the display name
                            ;; instead of message content (pi: tree shows
                            ;; "[title: name]")
                            (= (:role entry) :session_info) (or (:name entry) "(empty)")
                            ;; compaction entries carry their summary text
                            (:summary entry) (subs (:summary entry) 0 (min 60 (count (:summary entry))))
                            :else "(empty)"))
               :children (mapv build-node (children (:id entry)))})]
      (mapv build-node root-children))))

(defn compact!
  "Count-based fallback compaction: append a placeholder compaction entry
   covering the oldest entries beyond the threshold (used when LLM
   summarization is unavailable — see compact-with-summary!). Append-only:
   the summarized entries stay in the file; build-context excludes them from
   the LLM context while keeping them reachable via get-branch/get-tree/fork.
   Returns the compaction entry, or nil when there is nothing to compact."
  [session max-entries]
  (with-session-lock session
    (let [entries @(:entries session)
          n (count entries)
          keep-count (quot max-entries 2)]
      (when (and (> n max-entries) (pos? keep-count))
        (append-entry session
                      {:role :compaction
                       :summary (str "[Compacted " (- n keep-count) " messages]")
                       :first-kept-id (:id (nth entries (- n keep-count)))})))))

(defn compact-with-summary!
  "Append a compaction entry summarizing everything before first-kept-id (pi:
   appendCompaction). Append-only: the summarized entries stay in the file;
   build-context returns [compaction, ...from first-kept-id], so old content
   stays reachable (tree, fork) while being excluded from the LLM context.
   opts may carry :tokens-before, :usage, :details (pi: CompactionEntry).
   Returns the compaction entry, or nil when first-kept-id is not found."
  [session summary first-kept-id & [opts]]
  (when (some #(= (:id %) first-kept-id) @(:entries session))
    (append-entry session
                  (merge {:role :compaction
                          :summary summary
                          :first-kept-id first-kept-id}
                         (select-keys opts [:tokens-before :usage :details])))))

(defn fork-session
  "Create a new session forked at the given entry-id (pi:
   createBranchedSession). The fork lives in the same session directory and
   its header links :parent-session to the source file. The branch up to
   entry-id is copied. Note: entries are re-id'd — pi keeps entry ids and
   re-chains around labels (G18, fixed in the branching phase)."
  [session entry-id]
  (let [entries @(:entries session)
        index (reduce (fn [m e] (assoc m (:id e) e)) {} entries)
        target (get index entry-id)]
    (when target
      (let [branch (loop [id entry-id result []]
                     (if id
                       (if-let [e (get index id)]
                         (recur (:parent-id e) (conj result e))
                         result)
                       result))
            fork (create-session (str (fs/parent (:file session)))
                                 {:cwd (get-in session [:header :cwd])
                                  :parent-session (:file session)})]
        (doseq [e (reverse branch)]
          (let [clean (dissoc e :id :parent-id :timestamp)]
            (append-entry fork clean)))
        fork))))

;; ─── Bash result recording ─────────────────────────────────────────────────

(defn make-bash-entry
  "Build the session entry for a !/!! bash command result.
   exclude-from-context? mirrors pi's !! behavior (output not sent to the LLM)."
  [command result exclude-from-context?]
  {:role :bash
   :command command
   :output (:output result "")
   :exit-code (:exit-code result)
   :cancelled (:cancelled result false)
   :exclude-from-context? exclude-from-context?
   :truncated (:truncated result false)
   :full-output-path (:full-output-path result)})

(defn record-bash-result!
  "Record the result of a !/!! bash command in the session.
   When exclude-from-context? is true, the output is not included in
   the conversation history visible to the LLM (matching pi's !! behavior)."
  [session command result exclude-from-context?]
  (append-entry session (make-bash-entry command result exclude-from-context?)))

;; ─── Usage tracking ────────────────────────────────────────────────────────

(defn entry-usage
  "Normalize a message :usage map (OpenAI, Anthropic, or Google shapes) into
   {:input :output :cache-read :cache-write :cost}. :input EXCLUDES cache
   tokens (pi normalizeUsage — otherwise cached tokens would be priced at
   both the input and cache-read rates): OpenAI's prompt_tokens includes
   cached/cache-write tokens, so they're subtracted; Anthropic's
   input_tokens and Google's normalized :input already exclude them. :cost
   is the per-message USD total attached by llm (models/calculate-cost), 0
   when the message predates cost tracking. Returns nil when the map has no
   recognizable token fields."
  [usage]
  (when (and usage (map? usage))
    (let [cache-read (or (get-in usage [:prompt_tokens_details :cached_tokens])
                         (:prompt_cache_hit_tokens usage)
                         (:cache_read_input_tokens usage)
                         (:cache-read usage))
          cache-write (or (get-in usage [:prompt_tokens_details :cache_write_tokens])
                          (:cache_creation_input_tokens usage)
                          (:cache-write usage))
          prompt (:prompt_tokens usage)
          input (cond
                  prompt (max 0 (- (long prompt)
                                   (long (or cache-read 0))
                                   (long (or cache-write 0))))
                  (:input_tokens usage) (:input_tokens usage)
                  (:input usage) (:input usage))
          output (or (:completion_tokens usage) (:output_tokens usage) (:output usage))
          cost (or (get-in usage [:cost :total]) 0)]
      (when (or input output cache-read cache-write)
        {:input (long (or input 0))
         :output (long (or output 0))
         :cache-read (long (or cache-read 0))
         :cache-write (long (or cache-write 0))
         :cost (double cost)}))))

(defn usage-totals
  "Sum normalized usage (incl. USD :cost) across session entries carrying
   :usage (pi: FooterComponent accumulates usage from all session entries)."
  [session]
  (reduce (fn [totals e]
            (if-let [u (entry-usage (:usage e))]
              (merge-with + totals u)
              totals))
          {:input 0 :output 0 :cache-read 0 :cache-write 0 :cost 0.0}
          @(:entries session)))

;; ─── Convenience ───────────────────────────────────────────────────────────

(defn- entry-text
  "Plain trimmed text of an entry's content blocks (pi: extractTextContent)."
  [e]
  (let [content (:content e)]
    (if (string? content)
      (str/trim content)
      (str/trim (str/join (map :text (filter #(= :text (:type %)) content)))))))

(defn get-first-message
  "First user message text of the session (pi: buildSessionInfo firstMessage
   — the first user message with text content, else \"(no messages)\")."
  [session]
  (or (some (fn [e]
              (when (= :user (:role e))
                (let [t (entry-text e)]
                  (when (seq t) t))))
            @(:entries session))
      "(no messages)"))

(defn get-message-count
  "Number of message entries in the session (pi: buildSessionInfo
   messageCount — message types only, session_info excluded)."
  [session]
  (count (filter #(contains? #{:user :assistant :tool :bash :info} (:role %))
                 @(:entries session))))

(defn get-last-activity-ms
  "Last message activity time as epoch ms (pi: modified — the latest message
   timestamp), falling back to the file mtime, then the header :created-at
   (a lazy session has no file until the first assistant message — G4).
   An unparseable timestamp (corrupt/legacy file) falls back rather than
   throwing."
  [session]
  (let [last-ts (some-> (filter #(contains? % :timestamp) @(:entries session))
                        last
                        :timestamp)]
    (or (when last-ts
          (try (-> (java.time.Instant/parse last-ts) (.toEpochMilli))
               (catch Exception _ nil)))
        (when (fs/exists? (:file session))
          (.toMillis (fs/last-modified-time (:file session))))
        (when-let [created (:created-at (:header session))]
          (try (-> (java.time.Instant/parse created) (.toEpochMilli))
               (catch Exception _ nil)))
        0)))

(defn list-sessions
  "List all session files under a session directory, newest first. When DIR
   is a base sessions dir, its cwd-encoded subdirectories are walked too
   (pi: listAll — sessions/<--cwd-->/); when DIR is a single cwd-encoded
   dir, it is listed flat. Returns canonical absolute paths."
  [dir]
  (let [d (io/file dir)]
    (when (fs/directory? d)
      (let [dirs (cons d (filter fs/directory? (fs/list-dir d)))]
        (->> dirs
             (mapcat (fn [sub]
                       (->> (fs/list-dir sub)
                            (filter #(and (str/ends-with? (fs/file-name %) ".ednl")
                                          (fs/regular-file? %)))
                            (map #(str (fs/canonicalize %))))))
             (sort-by #(.toMillis (fs/last-modified-time %)) >)
             vec)))))

(defn- read-session-header
  "Best-effort header read for discovery (pi: readSessionHeaderForDiscovery —
   a bounded leading-line scan): returns the first :session header of the
   file, or nil for headerless, corrupt, or unreadable files. Blank and
   malformed leading lines are skipped; the scan stops at the first
   parseable non-header entry."
  [path]
  (try
    (with-open [r (io/reader path)]
      (loop [lines (line-seq r)]
        (if-let [line (first lines)]
          (let [trimmed (str/trim line)]
            (if (seq trimmed)
              (let [e (try (edn/read-string trimmed) (catch Exception _ ::invalid))]
                (cond
                  (and (map? e) (= :session (:type e))) e
                  (= ::invalid e) (recur (rest lines))
                  :else nil))
              (recur (rest lines))))
          nil)))
    (catch Exception _ nil)))

(defn find-most-recent-session
  "Most recent session file in DIR whose header :cwd matches CWD (pi:
   findMostRecentSession — header-based discovery, best-effort; continue is
   scoped to the current project). Returns the canonical path, or nil when
   DIR holds no matching session. Legacy headerless files and files from
   other cwds are excluded — no fallback."
  [dir cwd]
  (let [resolved-cwd (str (fs/absolutize cwd))
        d (io/file dir)]
    (when (fs/directory? d)
      (->> (fs/list-dir d)
           (filter #(and (str/ends-with? (fs/file-name %) ".ednl")
                         (fs/regular-file? %)))
           (keep (fn [f]
                   (let [path (str (fs/canonicalize f))
                         header (read-session-header path)]
                     (when (and header
                                (seq (:cwd header))
                                (= (str (fs/absolutize (:cwd header))) resolved-cwd))
                       path))))
           (sort-by #(.toMillis (fs/last-modified-time %)) >)
           first))))

(defn delete-session!
  "Delete a session file."
  [session]
  (let [f (:file session)]
    (when (fs/exists? f) (fs/delete f))))
