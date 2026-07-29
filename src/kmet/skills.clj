(ns kmet.skills
  "Skills & Extensions system for kmet.
   Skills: markdown files loaded from skill directories.
   Extensions: Clojure files loaded from extension directories.
   Event system: global event bus for extensions to hook into agent lifecycle." 
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [babashka.fs :as fs]))

;; ─── Extension event system ────────────────────────────────────────────────
;; Defined first to avoid any load-order issues in SCI.

(defonce ^:private event-listeners (atom {}))

(defn on-event
  "Register a callback for an event type.
   event-type — keyword :session-start, :tool-call, :tool-result, :message-start, :status
   callback   — (fn [event-map])
   Returns a deregister function."
  [event-type callback]
  (let [id (java.util.UUID/randomUUID)]
    (swap! event-listeners update event-type assoc id callback)
    (fn [] (swap! event-listeners update event-type dissoc id))))

(defn clear-event-listeners!
  "Remove all event listeners (for testing)."
  []
  (reset! event-listeners {}))

(defn emit-event!
  "Emit an event to all registered listeners.
   event — map with :type keyword and any additional data.
   Runs all callbacks in a doseq (synchronous)."
  [event]
  (let [type (:type event)
        listeners (get @event-listeners type)]
    (when listeners
      (doseq [[_ cb] listeners]
        (try
          (cb event)
          (catch Exception e
            (binding [*out* *err*]
              (println "Warning: extension event handler error:" (.getMessage e)))))))))

(defn get-event-types
  "List all registered event types."
  []
  (keys @event-listeners))

;; ─── Skills ────────────────────────────────────────────────────────────────

(defonce ^:private skills (atom []))
(defonce ^:private extensions (atom []))

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

;; ─── Extensions ────────────────────────────────────────────────────────────

(defn load-extensions-from-dir
  "Load all .clj extension files from a directory.
   Each file is loaded with load-string for side effects."
  [dir]
  (let [d (io/file dir)]
    (when (fs/directory? d)
      (doseq [f (fs/list-dir d)]
        (when (str/ends-with? (fs/file-name f) ".clj")
          (try
            (let [code (slurp f)]
              (load-string code)
              (swap! extensions conj
                {:name (fs/file-name f) :file (str (fs/canonicalize f))}))
            (catch Exception e
              (binding [*out* *err*]
                (println "Warning: Failed to load extension" (fs/file-name f) ":" (.getMessage e))))))))))
