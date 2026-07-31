(ns kmet.app.extensions
  "Extensions system for kmet.
   Clojure files loaded from extension directories.

   Extension hooks (pi: pi.on('input') and pi.on('before_agent_start')):
   - input hooks intercept/rewrite user input before the agent runs
     (applied at the interactive input path, core.clj/modes.interactive
     handle-submit)
   - before-agent-start hooks override the system prompt / inject context
     messages for a run (applied by kmet.app.loop/run-agent-turn)

   Extension events flow through kmet.app.event-bus (pi: pi.on('<event>')).
   pi: core/extensions/runner.js."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [babashka.fs :as fs]))

;; ─── Extension input / before-agent-start hooks ────────────────────────────
;; pi: extensions register via pi.on("input") and pi.on("before_agent_start");
;; AgentSession.prompt() consults them per submission. kmet: the interactive
;; input path (modes.interactive handle-submit) applies input hooks before the
;; agent runs; run-agent-turn applies before-agent-start hooks after the user
;; message is added, before the first LLM call.

(defonce ^:private input-hooks (atom []))
(defonce ^:private before-agent-start-hooks (atom []))

(defn register-input-hook!
  "Register an input hook (pi: pi.on('input')).
   Fires for agent messages submitted from the interactive input path
   (modes.interactive handle-submit) — slash and bash commands are native UI
   features with their own extension hooks and bypass input hooks.
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

;; ─── Extension loading ─────────────────────────────────────────────────────

(defonce ^:private extensions (atom []))

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
