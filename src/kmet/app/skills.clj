(ns kmet.app.skills
  "Skills system for kmet.
   Markdown files loaded from skill directories and appended to the system
   prompt. pi: core/skills.js.

   Extensions (Clojure files, hooks, event bus) live in kmet.app.extensions
   and kmet.app.event-bus."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [babashka.fs :as fs]))

;; ─── Skills ────────────────────────────────────────────────────────────────

(defonce ^:private skills (atom []))

(defn load-skills-from-dir
  "Load all .md skill files from a directory."
  [dir]
  (let [d (io/file dir)]
    (when (fs/directory? d)
      (let [loaded (volatile! [])]
        (doseq [f (fs/list-dir d)]
          (when (and (fs/regular-file? f) (str/ends-with? (fs/file-name f) ".md"))
            (try
              (let [content (slurp (str f))
                    name (fs/file-name f)
                    skill-name (str/replace name #"\.md$" "")]
                (vswap! loaded conj
                  {:name skill-name
                   :file (str (fs/canonicalize f))
                   :content content}))
              (catch Exception e
                (binding [*out* *err*]
                  (println "Warning: Failed to load skill" (fs/file-name f) ":" (.getMessage e)))))))
        (swap! skills into @loaded)))))

(defn register-skill!
  "Register a skill programmatically."
  [name content]
  (swap! skills conj {:name name :file nil :content content}))

(defn get-skills
  "Get all loaded skills."
  []
  @skills)

(defn get-skill
  "Get a skill by name."
  [name]
  (first (filter #(= (:name %) name) @skills)))

(defn build-system-prompt
  "Build a system prompt by combining tool guidelines and skills content.
   base-prompt — the base system prompt string
   opts:
     :tools — seq of Tool records (for guidelines)
   Returns a string with all guidelines and skills appended."
  [base-prompt & {:keys [tools]}]
  (let [guidelines (when (seq tools)
                     (let [lines (mapcat (fn [t]
                                         (cons (str "- " (:name t) ": " (:prompt-snippet t ""))
                                               (map #(str "  - " %) (:prompt-guidelines t []))))
                                       tools)]
                       (str/join "\n" (cons "\nAvailable tools:" lines))))
        skill-texts (mapv :content (sort-by :name @skills))
        skills-section (if (empty? skill-texts)
                         ""
                         (str "\n\n--- Skills ---\n\n"
                              (str/join "\n\n---\n\n" skill-texts)))]
    (str base-prompt
         (or guidelines "")
         skills-section)))
