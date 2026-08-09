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

(defrecord Session [file id entries leaf-id lock])

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

(defn- write-entries!
  "Atomically replace the session file's contents and in-memory state (pi:
   temp-file publication — a crash mid-write can't leave a corrupt file).
   Callers must hold the session lock."
  [session entries]
  (let [entries (vec entries)
        file (:file session)
        tmp (str file ".tmp")]
    (spit tmp (apply str (map prn-str entries)))
    (fs/move tmp file {:replace-existing true})
    (reset! (:entries session) entries)
    (reset! (:leaf-id session) (some-> entries last :id))))

;; ─── CRUD ───────────────────────────────────────────────────────────────────

(defn create-session
  "Create a new session file in dir. Returns Session record."
  [dir]
  (let [session-dir (io/file dir)]
    (fs/create-dirs session-dir)
    (let [id (generate-id [])
          file (io/file session-dir (str id ".ednl"))]
      (spit file "")
      (map->Session {:file (str (fs/canonicalize file))
                     :id id
                     :entries (atom [])
                     :leaf-id (atom nil)
                     :lock (java.util.concurrent.locks.ReentrantLock.)}))))

(defn load-session
  "Load an existing session from file path. Returns Session record."
  [path]
  (let [file (io/file path)
        content (slurp file)
        lines (str/split-lines content)
        entries (vec (keep identity
                           (for [line lines]
                             (let [trimmed (str/trim line)]
                               (when (seq trimmed)
                                 (try (edn/read-string trimmed)
                                      (catch Exception ex
                                        (binding [*out* *err*]
                                          (println "Warning: Skipping invalid entry in" path ":" (ex-message ex)))
                                        nil)))))))
        leaf-id (some-> entries last :id)]
    (map->Session {:file (str (fs/canonicalize file))
                   :id (str/replace (fs/file-name file) #"\.ednl$" "")
                   :entries (atom entries)
                   :leaf-id (atom leaf-id)
                   :lock (java.util.concurrent.locks.ReentrantLock.)})))

(defn append-entry
  "Append an entry to the session file and atom. Serialized per session so
   concurrent appends (bash-result future + agent loop) can't produce
   orphaned sibling entries (pi: storage.appendEntry is enqueued)."
  [session entry]
  (with-session-lock session
    (let [entry (build-entry @(:entries session) @(:leaf-id session) entry)
          file (:file session)]
      (spit file (prn-str entry) :append true)
      (swap! (:entries session) conj entry)
      (reset! (:leaf-id session) (:id entry))
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
  "Create a new session forked at the given entry-id."
  [session entry-id]
  (let [entries @(:entries session)
        index (reduce (fn [m e] (assoc m (:id e) e)) {} entries)
        target (get index entry-id)]
    (when target
      (let [fork-dir (io/file (str (fs/parent (:file session))) "forks")]
        (fs/create-dirs fork-dir)
        (let [fork (create-session (str (fs/canonicalize fork-dir)))
              ;; Copy branch up to target
              branch (loop [id entry-id result []]
                       (if id
                         (if-let [e (get index id)]
                           (recur (:parent-id e) (conj result e))
                           result)
                         result))]
          (doseq [e (reverse branch)]
            (let [clean (dissoc e :id :parent-id :timestamp)]
              (append-entry fork clean)))
          fork)))))

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
   timestamp), falling back to the file mtime. An unparseable timestamp
   (corrupt/legacy file) also falls back to mtime rather than throwing."
  [session]
  (let [last-ts (some-> (filter #(contains? % :timestamp) @(:entries session))
                        last
                        :timestamp)]
    (or (when last-ts
          (try (-> (java.time.Instant/parse last-ts) (.toEpochMilli))
               (catch Exception _ nil)))
        (.toMillis (fs/last-modified-time (:file session))))))

(defn list-sessions
  "List all session files in a directory, newest first."
  [dir]
  (let [d (io/file dir)]
    (when (fs/directory? d)
      (->> (fs/list-dir d)
           (filter #(and (str/ends-with? (fs/file-name %) ".ednl")
                         (fs/regular-file? %)))
           (sort-by #(.toMillis (fs/last-modified-time %)) >)
           (mapv #(str (fs/canonicalize %)))))))

(defn delete-session!
  "Delete a session file."
  [session]
  (let [f (:file session)]
    (when (fs/exists? f) (fs/delete f))))
