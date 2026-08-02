(ns kmet.app.session
  "EDNL session storage — line-delimited EDN with parent-child IDs for tree branching.
   Each entry is an EDN map on one line: {:id str :parent-id str-or-nil :role keyword ...}
   Port of @earendil-works/pi-agent session storage."
  (:require [clojure.java.io :as io]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [babashka.fs :as fs]))

;; ─── Session record ─────────────────────────────────────────────────────────

(defrecord Session [file id entries leaf-id])

(defn- generate-id []
  (let [now (System/currentTimeMillis)
        rand (rand-int 0xFFFF)]
    (str (format "%x" now) "-" (format "%x" rand))))

(defn- timestamp []
  (java.time.Instant/now))

;; ─── CRUD ───────────────────────────────────────────────────────────────────

(defn create-session
  "Create a new session file in dir. Returns Session record."
  [dir]
  (let [session-dir (io/file dir)]
    (fs/create-dirs session-dir)
    (let [id (generate-id)
          file (io/file session-dir (str id ".ednl"))]
      (spit file "")
      (map->Session {:file (str (fs/canonicalize file))
                     :id id
                     :entries (atom [])
                     :leaf-id (atom nil)}))))

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
                   :leaf-id (atom leaf-id)})))

(defn append-entry
  "Append an entry to the session file and atom."
  [session entry]
  (let [entry (assoc entry :id (generate-id)
                     :parent-id @(:leaf-id session)
                     :timestamp (str (timestamp)))
        file (:file session)]
    (spit file (prn-str entry) :append true)
    (swap! (:entries session) conj entry)
    (reset! (:leaf-id session) (:id entry))
    entry))

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

(defn get-tree
  "Build a tree structure from session entries.
   Returns map of {:id info, :children [...]}"
  [session]
  (let [entries @(:entries session)
        index (reduce (fn [m e] (assoc m (:id e) e)) {} entries)
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
                          (if (seq trimmed)
                            (subs trimmed 0 (min 60 (count trimmed)))
                            "(empty)"))
               :children (mapv build-node (children (:id entry)))})]
      (mapv build-node root-children))))

(defn compact!
  "Count-based fallback compaction: summarize older entries beyond a threshold
   by replacing them with a placeholder summary (used when LLM summarization is
   unavailable — see compact-with-summary!)."
  [session max-entries]
  (let [entries @(:entries session)
        n (count entries)]
    (when (> n max-entries)
      (let [keep (vec (take-last (quot max-entries 2) entries))
            summarize (vec (drop-last (quot max-entries 2) (drop-last (count keep) entries)))]
        (when (seq summarize)
          (let [summary-text (str "[Compacted " (count summarize)
                                  " messages — " (first summarize) " to "
                                  (last summarize) "]")
                summary-entry {:role :system
                               :content [{:type :text :text summary-text}]
                               :summary summary-text
                               :id (generate-id)
                               :parent-id nil
                               :timestamp (str (timestamp))}
                keep (assoc-in keep [0 :parent-id] (:id summary-entry))
                new-entries (into [summary-entry] keep)]
            (reset! (:entries session) new-entries)
            (reset! (:leaf-id session) (:id (last new-entries)))
            ;; Rewrite file
            (spit (:file session) (apply str (map prn-str new-entries))))))
      @(:leaf-id session))))

(defn compact-with-summary!
  "Replace all entries before first-kept-id with a single summary entry (pi:
   the session manager saves a compaction entry and reloads from
   firstKeptEntryId; kmet physically removes the summarized entries). The
   first kept entry is re-parented to the summary entry so the active branch
   includes it. Returns the summary entry, or nil when first-kept-id is not
   found."
  [session summary first-kept-id]
  (let [entries @(:entries session)
        idx (first (keep-indexed (fn [i e] (when (= (:id e) first-kept-id) i)) entries))]
    (when idx
      (let [summary-entry {:role :system
                           :content [{:type :text :text summary}]
                           :summary summary
                           :id (generate-id)
                           :parent-id nil
                           :timestamp (str (timestamp))}
            keep (assoc-in (vec (subvec entries idx)) [0 :parent-id] (:id summary-entry))
            new-entries (into [summary-entry] keep)]
        (reset! (:entries session) new-entries)
        (reset! (:leaf-id session) (:id (last new-entries)))
        (spit (:file session) (apply str (map prn-str new-entries)))
        summary-entry))))

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

(defn record-bash-result!
  "Record the result of a !/!! bash command in the session.
   When exclude-from-context? is true, the output is not included in
   the conversation history visible to the LLM (matching pi's !! behavior)."
  [session command result exclude-from-context?]
  (let [entry {:role :bash
               :command command
               :output (:output result "")
               :exit-code (:exit-code result)
               :cancelled (:cancelled result false)
               :exclude-from-context? exclude-from-context?
               :truncated (:truncated result false)
               :full-output-path (:full-output-path result)}]
    (append-entry session entry)))

;; ─── Convenience ───────────────────────────────────────────────────────────

(defn list-sessions
  "List all session files in a directory, newest first."
  [dir]
  (let [d (io/file dir)]
    (when (fs/directory? d)
      (->> (fs/list-dir d)
           (filter #(str/ends-with? (fs/file-name %) ".ednl"))
           (sort-by #(.toMillis (fs/last-modified-time %)) >)
           (mapv #(str (fs/canonicalize %)))))))

(defn delete-session!
  "Delete a session file."
  [session]
  (let [f (:file session)]
    (when (fs/exists? f) (fs/delete f))))
