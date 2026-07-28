(ns kmet.agent.tools
  "Tool definitions and execution for the LLM agent.
   Port of @earendil-works/pi-agent tool system.
   Built-in tools: read, write, edit, bash, grep, find, ls."
  (:require [clojure.string :as str]
            [clojure.java.io :as io]))

;; ─── Tool record ────────────────────────────────────────────────────────────

(defrecord Tool [name label description parameters execute])

;; ─── Parameter helpers ──────────────────────────────────────────────────────

(defn- param [name type description & {:keys [optional?]}]
  (merge {:type type :description description}
         (when optional? {:optional true})))

(defn- ->json-schema [params]
  {:type "object"
   :properties (reduce-kv (fn [m k v]
                            (assoc m (name k)
                              (cond-> {:type (name (:type v))
                                       :description (:description v)}
                                (:optional v) (assoc :optional true))))
                          {} params)
   :required (vec (->> params (remove #(:optional (val %))) (map key) (map name)))})

;; ─── Tool implementations ───────────────────────────────────────────────────

(defn- tool-read
  "Read file contents with optional offset/limit."
  [{:keys [path offset limit]}]
  (try
    (let [f (io/file path)]
      (if-not (.exists f)
        {:content (str "File not found: " path) :is-error true}
        (let [content (slurp f)
              lines (str/split-lines content)
              offset (or offset 0)
              limit (or limit (count lines))
              selected (->> lines (drop offset) (take limit))
              total (count lines)
              result (str/join "\n" selected)]
          {:content (str result
                         (when (> total (+ offset limit))
                           (str "\n\n[..." (- total (+ offset limit)) " more lines]"))
                         (when (pos? offset)
                           (str "\n\n[showing " (count selected) " of " total " lines]")))})))
    (catch Exception e
      {:content (str "Error reading " path ": " (.getMessage e)) :is-error true})))

(defn- tool-write
  "Write content to a file (create or overwrite)."
  [{:keys [path content]}]
  (try
    (let [f (io/file path)]
      (.mkdirs (.getParentFile f))
      (spit f content)
      {:content (str "Written " (count content) " bytes to " path)})
    (catch Exception e
      {:content (str "Error writing to " path ": " (.getMessage e)) :is-error true})))

(defn- tool-edit
  "Precise text replacement in a file."
  [{:keys [path old-text new-text]}]
  (try
    (let [f (io/file path)]
      (if-not (.exists f)
        {:content (str "File not found: " path) :is-error true}
        (let [content (slurp f)
              idx (.indexOf content old-text)]
          (if (neg? idx)
            {:content (str "Could not find old-text in " path) :is-error true}
            (let [result (str/replace-first content old-text new-text)
                  replaced (count old-text)
                  new-len (count new-text)]
              (spit f result)
              {:content (str "Replaced " replaced " chars with " new-len " chars in " path)})))))
    (catch Exception e
      {:content (str "Error editing " path ": " (.getMessage e)) :is-error true})))

(defn- tool-bash
  "Execute a bash command with optional timeout."
  [{:keys [command timeout]}]
  (try
    (let [proc (.exec (Runtime/getRuntime) (into-array String ["sh" "-c" command]))
          _ (when timeout
              (future
                (Thread/sleep (* timeout 1000))
                (.destroy proc)))
          exit-code (.waitFor proc)
          stdout (slurp (.getInputStream proc))
          stderr (slurp (.getErrorStream proc))
          output (str (when (seq stdout) stdout)
                      (when (and (seq stdout) (seq stderr)) "\n")
                      (when (seq stderr) stderr))]
      {:content output
       :is-error (not= exit-code 0)})
    (catch Exception e
      {:content (str "Error executing command: " (.getMessage e)) :is-error true})))

(defn- tool-grep
  "Search file contents with a pattern."
  [{:keys [pattern path]}]
  (try
    (let [f (if path (io/file path) (io/file "."))
          results (volatile! [])]
      (if (.isFile f)
        (with-open [rdr (io/reader f)]
          (doseq [[idx line] (map-indexed vector (line-seq rdr))]
            (when (re-find (re-pattern pattern) line)
              (vswap! results conj (str (.getName f) ":" (inc idx) ": " line)))))
        (doseq [file (file-seq f)
                :when (.isFile file)]
          (try
            (with-open [rdr (io/reader file)]
              (doseq [[idx line] (map-indexed vector (line-seq rdr))]
                (when (re-find (re-pattern pattern) line)
                  (vswap! results conj (str (.getPath file) ":" (inc idx) ": " line)))))
            (catch Exception _))))
      (let [r @results]
        (if (empty? r)
          {:content (str "No matches for \"" pattern "\"")}
          {:content (str/join "\n" (take 100 r))
           :truncated (> (count r) 100)})))
    (catch Exception e
      {:content (str "Error searching: " (.getMessage e)) :is-error true})))

(defn- tool-find
  "Find files matching a pattern."
  [{:keys [pattern path]}]
  (try
    (let [dir (if path (io/file path) (io/file "."))
          results (volatile! [])]
      (doseq [file (file-seq dir)
              :when (.isFile file)]
        (let [name (.getName file)]
          (when (or (re-find (re-pattern pattern) name)
                    (re-find (re-pattern pattern) (.getPath file)))
            (vswap! results conj (.getPath file)))))
      (let [r @results]
        (if (empty? r)
          {:content (str "No files matching \"" pattern "\"")}
          {:content (str/join "\n" (take 200 r))
           :truncated (> (count r) 200)})))
    (catch Exception e
      {:content (str "Error finding: " (.getMessage e)) :is-error true})))

(defn- tool-ls
  "List directory contents."
  [{:keys [path long?]}]
  (try
    (let [dir (if path (io/file path) (io/file "."))]
      (if-not (.isDirectory dir)
        {:content (str "Not a directory: " path) :is-error true}
        (let [files (.listFiles dir)
              entries (sort-by #(.getName %) files)
              result (str/join "\n"
                      (map (fn [f]
                             (let [name (.getName f)
                                   type (if (.isDirectory f) "d" "-")
                                   size (.length f)]
                               (if long?
                                 (str type " " (format "%10d" size) " " name)
                                 name)))
                           entries))]
          {:content (str "Contents of " (.getAbsolutePath dir) ":\n" result)})))
    (catch Exception e
      {:content (str "Error listing: " (.getMessage e)) :is-error true})))

;; ─── Tool registry ─────────────────────────────────────────────────────────

(def built-in-tools
  "Map of tool name → Tool record for all built-in tools."
  {"read"  (map->Tool
             {:name "read"
              :label "Read file"
              :description "Read the contents of a file. Supports offset/limit for large files."
              :parameters (->json-schema
                            {:path     (param :path :string "File path to read")
                             :offset   (param :offset :number "Line offset (0-indexed)" :optional? true)
                             :limit    (param :limit :number "Max lines to read" :optional? true)})
              :execute tool-read})
   "write" (map->Tool
             {:name "write"
              :label "Write file"
              :description "Write content to a file. Creates the file if it doesn't exist, overwrites if it does."
              :parameters (->json-schema
                            {:path    (param :path :string "File path to write to")
                             :content (param :content :string "Content to write")})
              :execute tool-write})
   "edit"  (map->Tool
             {:name "edit"
              :label "Edit file"
              :description "Precise text replacement in a file. The old-text must match exactly."
              :parameters (->json-schema
                            {:path    (param :path :string "File path to edit")
                             :old-text (param :old-text :string "Exact text to find and replace")
                             :new-text (param :new-text :string "Replacement text")})
              :execute tool-edit})
   "bash"  (map->Tool
             {:name "bash"
              :label "Execute command"
              :description "Execute a bash command with a timeout. For long-running commands, keep the timeout reasonable."
              :parameters (->json-schema
                            {:command (param :command :string "Bash command to execute")
                             :timeout (param :timeout :number "Timeout in seconds" :optional? true)})
              :execute tool-bash})
   "grep"  (map->Tool
             {:name "grep"
              :label "Search files"
              :description "Search file contents using a regular expression pattern."
              :parameters (->json-schema
                            {:pattern (param :pattern :string "Regex pattern to search for")
                             :path    (param :path :string "File or directory to search" :optional? true)})
              :execute tool-grep})
   "find"  (map->Tool
             {:name "find"
              :label "Find files"
              :description "Find files matching a pattern in their name or path."
              :parameters (->json-schema
                            {:pattern (param :pattern :string "Pattern to match")
                             :path    (param :path :string "Directory to search" :optional? true)})
              :execute tool-find})
   "ls"    (map->Tool
             {:name "ls"
              :label "List directory"
              :description "List contents of a directory."
              :parameters (->json-schema
                            {:path  (param :path :string "Directory path" :optional? true)
                             :long? (param :long? :boolean "Show detailed listing" :optional? true)})
              :execute tool-ls})})

;; ─── Tool schema helpers ────────────────────────────────────────────────────

(defn tool->anthropic-schema
  "Convert a Tool record to Anthropic tool schema format."
  [tool]
  {:name (:name tool)
   :description (:description tool)
   :input_schema (:parameters tool)})

(defn tool->openai-schema
  "Convert a Tool record to OpenAI tool schema format."
  [tool]
  {:type "function"
   :function {:name (:name tool)
              :description (:description tool)
              :parameters (:parameters tool)}})

;; ─── Extended tool registry ────────────────────────────────────────────────

(defonce ^:private custom-tools (atom {}))

(defn register-tool!
  "Register a custom tool."
  [tool]
  (swap! custom-tools assoc (:name tool) tool))

(defn unregister-tool!
  "Remove a custom tool."
  [name]
  (swap! custom-tools dissoc name))

(defn get-all-tools
  "Get all available tools (built-in + custom)."
  []
  (merge built-in-tools @custom-tools))

(defn get-tool
  "Get a tool by name."
  [name]
  (or (get built-in-tools name) (get @custom-tools name)))

;; ─── Execution ─────────────────────────────────────────────────────────────

(defn execute-tool
  "Execute a tool by name with given arguments.
   Returns {:content str :is-error bool}."
  [tool-name args]
  (if-let [tool (get-tool tool-name)]
    (try
      ((:execute tool) args)
      (catch Exception e
        {:content (str "Error executing " tool-name ": " (.getMessage e))
         :is-error true}))
    {:content (str "Unknown tool: " tool-name) :is-error true}))
