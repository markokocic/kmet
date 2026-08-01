(ns kmet.app.skills
  "Skills system for kmet, aligned with pi's implementation (pi: core/skills.js,
   docs/skills.md). A skill is a directory containing SKILL.md with YAML
   frontmatter, or a flat .md file. Skills are discovered recursively,
   validated per the Agent Skills spec, and listed in the system prompt as an
   <available_skills> XML block; the agent reads the SKILL.md file on demand
   via the read tool (progressive disclosure).

   Deviations from pi: no .gitignore/.ignore/.fdignore support (kmet scans only
   its own skills dirs); frontmatter parses via kmet.libs.yaml-lite (a minimal YAML
   subset parser — babashka has no YAML lib)."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [babashka.fs :as fs]
            [kmet.debug :as debug]
            [kmet.app.frontmatter :as fm]))

;; ─── Constants (pi: MAX_NAME_LENGTH / MAX_DESCRIPTION_LENGTH) ─────────────

(def ^:private max-name-length 64)
(def ^:private max-description-length 1024)

;; ─── Skill state ──────────────────────────────────────────────────────────

(defonce ^:private skills (atom []))

;; ─── Validation (pi: validateName / validateDescription) ──────────────────

(defn- validate-name
  "Per Agent Skills spec. Returns seq of error messages (empty if valid)."
  [name]
  (cond-> []
    (> (count name) max-name-length)
    (conj (str "name exceeds " max-name-length " characters (" (count name) ")"))
    (not (re-matches #"[a-z0-9-]+" name))
    (conj "name contains invalid characters (must be lowercase a-z, 0-9, hyphens only)")
    (str/starts-with? name "-")
    (conj "name must not start or end with a hyphen")
    (str/ends-with? name "-")
    (conj "name must not start or end with a hyphen")
    (str/includes? name "--")
    (conj "name must not contain consecutive hyphens")))

(defn- validate-description
  "Per Agent Skills spec. Returns seq of error messages (empty if valid)."
  [description]
  (cond-> []
    (> (count description) max-description-length)
    (conj (str "description exceeds " max-description-length " characters ("
               (count description) ")"))))

;; ─── Loading (pi: loadSkillFromFile / loadSkillsFromDirInternal) ──────────

(defn- load-skill-from-file
  "Load a skill from a SKILL.md (or flat .md) file. Name falls back to the
   parent directory name when frontmatter has no name (pi: frontmatter.name ||
   parentDirName). Skills with a missing description are not loaded.
   Returns {:skill map-or-nil :diagnostics [warning-maps]}."
  [file-path]
  (try
    (let [raw (slurp file-path)
          {:keys [frontmatter]} (fm/parse-frontmatter raw)
          name (str (or (get frontmatter "name") (fs/file-name (fs/parent file-path))))
          description (some-> (get frontmatter "description") str str/trim)
          errors (concat (if (str/blank? description)
                           ["description is required"]
                           (validate-description description))
                         (validate-name name))
          diagnostics (mapv #(hash-map :type "warning" :message % :path (str file-path)) errors)]
      (if (str/blank? description)
        {:skill nil :diagnostics diagnostics}
        {:skill {:name name
                 :description description
                 :file-path (str file-path)
                 :base-dir (str (fs/parent file-path))
                 :disable-model-invocation (true? (get frontmatter "disable-model-invocation"))}
         :diagnostics diagnostics}))
    (catch Exception e
      {:skill nil
       :diagnostics [{:type "warning" :message (ex-message e) :path (str file-path)}]})))

(defn- load-skills-dir
  "pi: loadSkillsFromDirInternal. Discovery rules:
   - a dir containing SKILL.md is a skill root — load it, do not recurse
   - otherwise load direct .md children and recurse into subdirectories
   Hidden entries and node_modules are skipped.
   Returns seq of {:skill ... :diagnostics ...}."
  [dir]
  (let [entries (try (fs/list-dir dir) (catch Exception _ []))
        skill-md (some #(when (= "SKILL.md" (fs/file-name %)) %) entries)]
    (if skill-md
      [(load-skill-from-file (str skill-md))]
      (concat (map #(load-skill-from-file (str %))
                   (filter #(and (fs/regular-file? %)
                                 (str/ends-with? (fs/file-name %) ".md")
                                 (not (str/starts-with? (fs/file-name %) ".")))
                           entries))
              (mapcat load-skills-dir
                      (filter #(and (fs/directory? %)
                                    (not (str/starts-with? (fs/file-name %) "."))
                                    (not= "node_modules" (fs/file-name %)))
                              entries))))))

;; ─── Public API ────────────────────────────────────────────────────────────

(defn load-skills-from-dir
  "Load skills from a directory using pi's discovery rules. Skills are added
   to the registry; on name collision the first skill found wins (pi: keep the
   first, emit a collision diagnostic). Returns the diagnostics for this dir
   (warning/collision maps), printed to stderr."
  [dir]
  (let [d (io/file dir)]
    (if-not (fs/directory? d)
      []
      (let [results (load-skills-dir (str d))
            collisions (volatile! [])]
        (doseq [{:keys [skill]} results]
          (when skill
            (if-let [existing (first (filter #(= (:name %) (:name skill)) @skills))]
              (vswap! collisions conj
                      {:type "collision"
                       :message (str "name \"" (:name skill) "\" collision")
                       :path (:file-path skill)
                       :winner-path (:file-path existing)
                       :loser-path (:file-path skill)})
              (swap! skills conj skill))))
        (let [diagnostics (into (vec (mapcat :diagnostics results)) @collisions)]
          (doseq [{:keys [type message path]} diagnostics]
            (binding [*out* *err*]
              (println (str "Warning: skill " type " at " path ": " message))))
          diagnostics)))))

(defn register-skill!
  "Register a programmatic skill (no backing file, so the model cannot read it
   on demand — pi has no equivalent; kept for kmet's API and tests)."
  [name description]
  (swap! skills conj {:name name
                      :description description
                      :file-path nil
                      :base-dir nil
                      :disable-model-invocation false}))

(defn get-skills
  []
  @skills)

(defn get-skill
  [name]
  (first (filter #(= name (:name %)) @skills)))

(defn expand-skill-command
  "Expand /skill:name args into the skill body wrapped in a <skill> block
   (pi: agent-session _expandSkillCommand). Returns text unchanged when not
   a /skill: command, the skill is unknown, or the file cannot be read."
  [text]
  (if-not (str/starts-with? text "/skill:")
    text
    (let [space-idx (str/index-of text " ")
          skill-name (if (nil? space-idx) (subs text 7) (subs text 7 space-idx))
          args (if (nil? space-idx) "" (str/trim (subs text (inc space-idx))))]
      (if-let [skill (get-skill skill-name)]
        (try
          (let [body (str/trim (:body (fm/parse-frontmatter (slurp (:file-path skill)))))
                block (str "<skill name=\"" (:name skill) "\" location=\"" (:file-path skill) "\">\n"
                           "References are relative to " (:base-dir skill) ".\n\n"
                           body "\n</skill>")]
            (if (seq args) (str block "\n\n" args) block))
          (catch Exception e
            (debug/log "skill expansion failed: " e)
            text))
        text))))

(defn as-command-maps
  "Skills in slash-command shape for the editor autocomplete provider
   (pi: interactive-mode skillCommandList — /skill:name commands, enabled
   by default)."
  [skill-list]
  (mapv (fn [s] {:name (str "skill:" (:name s))
                 :description (:description s)})
        skill-list))

;; ─── System prompt (pi: formatSkillsForPrompt) ────────────────────────────

(defn- escape-xml
  "XML-escape a string for use in element text."
  [s]
  (-> (str s)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")
      (str/replace "\"" "&quot;")
      (str/replace "'" "&apos;")))

(defn format-skills-for-prompt
  "Format skills as an <available_skills> XML block per the Agent Skills
   standard (pi: formatSkillsForPrompt). Skills with
   disable-model-invocation=true are excluded — they can only be invoked
   explicitly. Returns \"\" when no visible skills."
  [skill-list]
  (let [visible (remove :disable-model-invocation skill-list)]
    (if (empty? visible)
      ""
      (str "\n\nThe following skills provide specialized instructions for specific tasks.\n"
           "Use the read tool to load a skill's file when the task matches its description.\n"
           "When a skill file references a relative path, resolve it against the skill directory (parent of SKILL.md / dirname of the path) and use that absolute path in tool commands.\n"
           "\n<available_skills>\n"
           (str/join "\n"
                     (map (fn [s]
                            (str "  <skill>\n"
                                 "    <name>" (escape-xml (:name s)) "</name>\n"
                                 "    <description>" (escape-xml (:description s)) "</description>\n"
                                 (when (:file-path s)
                                   (str "    <location>" (escape-xml (:file-path s)) "</location>\n"))
                                 "  </skill>"))
                          visible))
           "\n</available_skills>"))))

(defn build-system-prompt
  "Build a system prompt by combining tool guidelines and skills.
   base-prompt — the base system prompt string
   opts:
     :tools — seq of Tool records (for guidelines)
   The skills section is appended only when the read tool is available, since
   skills are loaded on demand via read (pi: hasRead check)."
  [base-prompt & {:keys [tools]}]
  (let [guidelines (when (seq tools)
                     (let [lines (mapcat (fn [t]
                                         (cons (str "- " (:name t) ": " (:prompt-snippet t ""))
                                               (map #(str "  - " %) (:prompt-guidelines t []))))
                                       tools)]
                       (str/join "\n" (cons "\nAvailable tools:" lines))))
        has-read (or (nil? tools) (some #(= "read" (:name %)) tools))
        skills-block (if has-read (format-skills-for-prompt (get-skills)) "")]
    (str base-prompt
         (or guidelines "")
         skills-block)))
