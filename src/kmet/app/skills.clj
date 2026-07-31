(ns kmet.app.skills
  "Skills & Extensions system for kmet.
   Skills: markdown files loaded from skill directories.
   Extensions: Clojure files loaded from extension directories.
   Event system: global event bus for extensions to hook into agent lifecycle.
   Extension hooks: input hooks (intercept/rewrite user input before the agent
   runs, pi: pi.on('input')) and before-agent-start hooks (override the system
   prompt / inject context messages for a run, pi: pi.on('before_agent_start'))." 
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [babashka.fs :as fs]))

;; ─── Extension event system ────────────────────────────────────────────────
;; Defined first to avoid any load-order issues in SCI.

(defonce ^:private event-listeners (atom {}))

(defn on-event
  "Register a callback for an event type.
   event-type — keyword from kmet.app.events/event-types
                (e.g. :agent-start, :turn-start, :message-update,
                 :tool-execution-start, :user-bash, :status)
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

;; ─── Extension input / before-agent-start hooks ────────────────────────────
;; pi: extensions register via pi.on("input") and pi.on("before_agent_start");
;; AgentSession.prompt() consults them per submission. kmet: the interactive
;; input path (core.clj handle-submit) applies input hooks before the agent
;; runs; run-agent-turn applies before-agent-start hooks after the user
;; message is added, before the first LLM call.

(defonce ^:private input-hooks (atom []))
(defonce ^:private before-agent-start-hooks (atom []))

(defn register-input-hook!
  "Register an input hook (pi: pi.on('input')).
   Fires for agent messages submitted from the interactive input path
   (core.clj handle-submit) — slash and bash commands are native UI features
   with their own extension hooks and bypass input hooks.
   Hook: (fn [{:keys [text source streaming-behavior images]}])
   Return {:action :handled} to consume the input (no agent run),
   {:action :transform :text new-text :images new-images} to rewrite it
   (later hooks see the rewritten text and images), or nil to leave it
   unchanged."
  [hook]
  (swap! input-hooks conj hook)
  nil)

(defn register-before-agent-start-hook!
  "Register a before-agent-start hook (pi: pi.on('before_agent_start')).
   Hook: (fn [{:keys [prompt system-prompt]}])
   Return a map with :system-prompt (per-run override; later hooks see the
   overridden prompt) and/or :message (a message map injected into the
   context), or nil."
  [hook]
  (swap! before-agent-start-hooks conj hook)
  nil)

(defn apply-input-hooks
  "Run all input hooks in registration order over text and images
   (pi: emitInput).
   Hook ctx: {:text t :source s :streaming-behavior b :images [...]} —
   streaming-behavior is :steer when the agent is already running (input will
   be queued), nil when idle (input starts a fresh run); :images is the
   vector of attached image content blocks (possibly empty).
   Returns:
     {:action :handled}                  — a hook consumed the input
     {:action :transform :text t :images i}  — hooks rewrote the input (later
                                     hooks saw the rewritten text and images)
     {:action :pass :text t :images i}   — no hook changed anything"
  [text source & [{:keys [streaming-behavior images]}]]
  (let [initial-images (or images [])]
    (loop [hooks @input-hooks
           current text
           current-images initial-images]
      (if-let [hook (first hooks)]
        (let [result (try
                       (hook {:text current :source source
                              :streaming-behavior streaming-behavior
                              :images current-images})
                       (catch Exception e
                         (binding [*out* *err*]
                           (println "Warning: input hook error:" (.getMessage e)))
                         nil))]
          (cond
            (= :handled (:action result)) {:action :handled}
            (= :transform (:action result))
            (recur (next hooks) (:text result current)
                   (if (contains? result :images) (:images result) current-images))
            :else (recur (next hooks) current current-images)))
        (if (and (= current text) (= current-images initial-images))
          {:action :pass :text text :images current-images}
          {:action :transform :text current :images current-images})))))

(defn apply-before-agent-start-hooks
  "Run all before-agent-start hooks in registration order (pi: emitBeforeAgentStart).
   Later hooks see the system prompt as modified by earlier hooks.
   Returns {:system-prompt string-or-nil :messages [msg ...]} —
   :system-prompt is the overridden prompt or nil when unchanged; :messages
   are the custom messages returned by the hooks in order."
  [prompt system-prompt]
  (loop [hooks @before-agent-start-hooks
         current-prompt system-prompt
         messages []]
    (if-let [hook (first hooks)]
      (let [result (try
                     (hook {:prompt prompt :system-prompt current-prompt})
                     (catch Exception e
                       (binding [*out* *err*]
                         (println "Warning: before-agent-start hook error:" (.getMessage e)))
                       nil))]
        (recur (next hooks)
               (if (and result (contains? result :system-prompt))
                 (:system-prompt result)
                 current-prompt)
               (if (and result (:message result))
                 (conj messages (:message result))
                 messages)))
      {:system-prompt (when (not= current-prompt system-prompt) current-prompt)
       :messages messages})))

(defn clear-input-hooks!
  "Remove all input hooks (for testing)."
  []
  (reset! input-hooks []))

(defn clear-before-agent-start-hooks!
  "Remove all before-agent-start hooks (for testing)."
  []
  (reset! before-agent-start-hooks []))

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
            (let [code (slurp (str f))]
              (load-string code)
              (swap! extensions conj
                {:name (fs/file-name f) :file (str (fs/canonicalize f))}))
            (catch Exception e
              (binding [*out* *err*]
                (println "Warning: Failed to load extension" (fs/file-name f) ":" (.getMessage e))))))))))
