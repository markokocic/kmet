(ns kmet.app.tools.registry
  "Tool registry — built-in tool map, custom tool registration, schema conversion, execution.
   Tool record definition lives here; tools.clj re-exports it."
  (:require [clojure.string :as str]
            [babashka.fs :as fs]
            [kmet.app.tools.read :as read]
            [kmet.app.tools.write :as write]
            [kmet.app.tools.edit :as edit]
            [kmet.app.tools.bash :as bash]
            [kmet.app.tools.grep :as grep]
            [kmet.app.tools.find :as find]
            [kmet.app.tools.ls :as ls]))

;; ─── Tool record & parameter helpers ─────────────────────────────────────────

(defrecord Tool [name label description prompt-snippet prompt-guidelines
                 parameters execute render-call render-result])

(defn param
  "Define a tool parameter for JSON schema generation."
  [name type description & {:keys [optional?]}]
  (merge {:type type :description description}
         (when optional? {:optional true})))

(defn ->json-schema
  "Convert a map of param definitions to a JSON schema map."
  [params]
  {:type "object"
   :properties (reduce-kv (fn [m k v]
                            (assoc m (name k)
                              {:type (name (:type v))
                               :description (:description v)}))
                          {} params)
   :required (vec (->> params (remove #(:optional (val %))) (map key) (map name)))})

;; ─── Built-in tools ─────────────────────────────────────────────────────────

(def built-in-tools
  "Map of tool name → Tool record for all built-in tools."
  {"read"  (map->Tool
             {:name "read"
              :label "Read file"
              :description "Read the contents of a file. Supports offset/limit for large files. Output is truncated to 2000 lines or 50KB (whichever is hit first)."
              :prompt-snippet "Read file contents"
              :prompt-guidelines ["Use read to examine files instead of cat or sed"]
              :parameters (->json-schema
                            {:path     (param :path :string "File path to read (relative or absolute)")
                             :offset   (param :offset :number "Line number to start reading from (0-indexed)" :optional? true)
                             :limit    (param :limit :number "Maximum number of lines to read" :optional? true)})
              :execute read/execute})
   "write" (map->Tool
             {:name "write"
              :label "Write file"
              :description "Write content to a file. Creates the file if it doesn't exist, overwrites if it does. Automatically creates parent directories."
              :prompt-snippet "Create or overwrite files"
              :prompt-guidelines ["Use write only for new files or complete rewrites"]
              :parameters (->json-schema
                            {:path    (param :path :string "File path to write to (relative or absolute)")
                             :content (param :content :string "Content to write to the file")})
              :execute write/execute})
   "edit"  (map->Tool
             {:name "edit"
              :label "Edit file"
              :description "Make precise file edits with exact text replacement. When changing multiple separate locations in one file, use one edit call with multiple entries."
              :prompt-snippet "Make precise file edits with exact text replacement"
              :prompt-guidelines ["Use edit for precise changes (edits[].oldText must match exactly)"
                                  "When changing multiple separate locations in one file, use one edit call with multiple entries in edits[] instead of multiple edit calls"]
              :parameters (->json-schema
                            {:path    (param :path :string "File path to edit")
                             :old-text (param :old-text :string "Exact text to find and replace — must match exactly including whitespace")
                             :new-text (param :new-text :string "Replacement text")})
              :execute edit/execute})
   "bash"  (map->Tool
             {:name "bash"
              :label "Execute command"
              :description "Execute a bash command with a timeout. For long-running commands, keep the timeout reasonable. Standard streams (stdout/stderr) are captured and returned."
              :prompt-snippet "Execute bash commands"
              :prompt-guidelines []
              :parameters (->json-schema
                            {:command (param :command :string "Bash command to execute")
                             :timeout (param :timeout :number "Timeout in seconds (optional)" :optional? true)})
              :execute bash/execute})
   ;; grep, find, ls — disabled
   })

;; ─── Tool schema helpers ────────────────────────────────────────────────────

(defn tool->anthropic-schema
  "Convert a tool map to Anthropic tool schema format."
  [tool]
  {:name (:name tool)
   :description (:description tool)
   :input_schema (:parameters tool)})

(defn tool->openai-schema
  "Convert a tool map to OpenAI tool schema format."
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
