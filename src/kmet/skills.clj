(ns kmet.skills
  "Skills & Extensions system for kmet.
   Skills: markdown files loaded from skill directories.
   Extensions: Clojure files loaded from extension directories."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

;; ─── Skills ────────────────────────────────────────────────────────────────

(defonce ^:private skills (atom []))
(defonce ^:private extensions (atom []))

(defn load-skills-from-dir
  "Load all .md skill files from a directory."
  [dir]
  (let [d (io/file dir)]
    (when (.isDirectory d)
      (let [loaded (volatile! [])]
        (doseq [f (sort (.listFiles d (fn [_ name] (.endsWith name ".md"))))]
          (try
            (let [content (slurp f)
                  name (.getName f)
                  skill-name (str/replace name #"\.md$" "")]
              (vswap! loaded conj
                {:name skill-name
                 :file (.getAbsolutePath f)
                 :content content}))
            (catch Exception e
              (binding [*out* *err*]
                (println "Warning: Failed to load skill" (.getName f) ":" (.getMessage e))))))
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
  "Build a system prompt by combining skills content.
   Returns a string with all skills appended."
  [base-prompt]
  (let [skill-texts (mapv :content (sort-by :name @skills))]
    (if (empty? skill-texts)
      base-prompt
      (str base-prompt "\n\n--- Skills ---\n\n"
           (str/join "\n\n---\n\n" skill-texts)))))

;; ─── Extensions ────────────────────────────────────────────────────────────

(defn load-extensions-from-dir
  "Load all .clj extension files from a directory.
   Each file is loaded with load-file for side effects."
  [dir]
  (let [d (io/file dir)]
    (when (.isDirectory d)
      (doseq [f (sort (.listFiles d (fn [_ name] (.endsWith name ".clj"))))]
        (try
          (let [code (slurp f)]
            (load-string code)
            (swap! extensions conj
              {:name (.getName f) :file (.getAbsolutePath f)}))
          (catch Exception e
            (binding [*out* *err*]
              (println "Warning: Failed to load extension" (.getName f) ":" (.getMessage e)))))))))
