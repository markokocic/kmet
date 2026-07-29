(ns kmet.core
  "Main entry point for kmet — minimal coding agent TUI.
   CLI interface, main layout, agent integration, and command handling."
  (:require [kmet.tui.core :as tui]
            [kmet.tui.terminal :as term]
            [kmet.tui.keys :as keys]
            [kmet.tui.components.text :as text]
            [kmet.tui.components.spacer :as spacer]
            [kmet.tui.components.editor :as editor]
            [kmet.tui.components.chat-history :as chat]
            [kmet.agent.loop :as agent]
            [kmet.agent.session :as session]
            [clojure.string :as str])
  (:import [java.io File]))

;; ─── ANSI ──────────────────────────────────────────────────────────────────

(def ^:private RST "\u001b[0m")
(def ^:private BLD "\u001b[1m")
(def ^:private DIM "\u001b[2m")
(def ^:private RED "\u001b[31m")
(def ^:private GRN "\u001b[32m")
(def ^:private YLW "\u001b[33m")
(def ^:private CYN "\u001b[36m")

;; ─── Defaults ──────────────────────────────────────────────────────────────

(def default-model "claude-sonnet-4-20250514")
(def default-provider :openai)

(def default-system-prompt
  "You are kmet, a minimal coding agent. Help the user with their tasks.
Use the available tools to read, write, edit files, and execute commands.
Be precise and concise in your responses.")

(def session-base-dir
  (str (System/getProperty "user.home") "/.local/share/kmet/sessions"))

;; ─── Commands ──────────────────────────────────────────────────────────────

(def ^:private commands
  "Known slash commands map."
  {"quit"  "Exit kmet"
   "help"  "Show available commands"
   "model" "Switch model. Usage: /model <provider:model>"
   "new"   "Start a new session"})

;; ─── Session helpers ───────────────────────────────────────────────────────

(defn- ensure-session-dir []
  (let [d (File. session-base-dir)]
    (.mkdirs d)
    session-base-dir))

(defn- find-or-create-session []
  (let [dir (ensure-session-dir)
        existing (session/list-sessions dir)]
    (if (seq existing)
      (session/load-session (first existing))
      (session/create-session dir))))

;; ─── Core state ────────────────────────────────────────────────────────────

(defrecord CoreState [tui
                      agent-state
                      chat-history
                      editor
                      header-text
                      footer-text
                      session
                      running-turn?])

;; ─── Formatting helpers ────────────────────────────────────────────────────

(defn- fmt-model [provider model]
  (str (name provider) ":" model))

(defn- fmt-header [cs]
  (let [provider @(:provider (:agent-state cs))
        model @(:model (:agent-state cs))
        status (name (agent/get-status (:agent-state cs)))
        sess-id (some-> (:session cs) :id (subs 0 8) (str "..."))
        cwd (System/getProperty "user.dir")
        short-cwd (if (> (count cwd) 30) (str "..." (subs cwd (- (count cwd) 27))) cwd)]
    (str BLD CYN " kmet" RST " "
         DIM (fmt-model provider model) RST
         " │ " DIM "session:" RST " " (or sess-id "none")
         " │ " (case status
                 "idle" (str DIM "idle" RST)
                 "thinking" (str YLW "● thinking" RST)
                 "executing" (str YLW "● executing" RST)
                 "error" (str RED "● error" RST)
                 (str DIM status RST))
         " │ " DIM short-cwd RST)))

(defn- fmt-footer [cs]
  (let [n-msgs (count (chat/chat-history-get-messages (:chat-history cs)))]
    (str "msgs:" n-msgs " │ "
         DIM "/quit" RST " " DIM "/help" RST " "
         DIM "/model" RST " " DIM "/new" RST)))

(defn- update-header-footer! [cs]
  (text/text-set! (:header-text cs) (fmt-header cs))
  (text/text-set! (:footer-text cs) (fmt-footer cs))
  nil)

;; ─── Command handling ──────────────────────────────────────────────────────

(defn- handle-command [cs cmd args]
  (case cmd
    "quit"
    (tui/tui-stop (:tui cs))

    "help"
    (let [help-text (str
                     "Available commands:\n"
                     "  /quit  — Exit kmet\n"
                     "  /help  — Show this help\n"
                     "  /model <provider:model> — Switch model\n"
                     "  /new   — Start a new session\n"
                     "\n"
                     "Shortcuts:\n"
                     "  Enter      — Submit message\n"
                     "  Escape     — Cancel current turn\n"
                     "  Ctrl+Z     — Quit\n"
                     "  Up/Down    — Scroll chat history\n"
                     "  Ctrl+L     — Clear terminal")]
      (chat/chat-history-add-message! (:chat-history cs)
        {:role :assistant :content help-text}))

    "model"
    (if (seq args)
      (let [parts (str/split args #":" 2)
            provider (keyword (or (first parts) "openai"))
            model (or (second parts) default-model)]
        (agent/set-provider! (:agent-state cs) provider)
        (agent/set-model! (:agent-state cs) model)
        (chat/chat-history-add-message! (:chat-history cs)
          {:role :assistant :content (str "Switched to " (fmt-model provider model))}))
      (chat/chat-history-add-message! (:chat-history cs)
        {:role :assistant :content
         (str "Current model: " (fmt-model @(:provider (:agent-state cs))
                                            @(:model (:agent-state cs)))
              "\nUsage: /model <provider:model>")}))

    "new"
    (let [new-session (session/create-session (ensure-session-dir))]
      (chat/chat-history-clear! (:chat-history cs))
      ;; Update both CoreState and AgentState session references
      (reset! (:session cs) new-session)
      (let [old-ag (:agent-state cs)
            new-ag (assoc old-ag :session new-session)]
        (reset! (:agent-state cs) new-ag))
      (chat/chat-history-add-message! (:chat-history cs)
        {:role :assistant :content "Started a new session."}))

    ;; Unknown command
    (chat/chat-history-add-message! (:chat-history cs)
      {:role :assistant
       :content (str "Unknown command: /" cmd ". Type /help for available commands.")}))
  (update-header-footer! cs))

;; ─── Agent response handler ────────────────────────────────────────────────

(defn- on-agent-text [cs text]
  "Called for each text delta from the LLM during streaming."
  (chat/chat-history-append-streaming-text! (:chat-history cs) text)
  (update-header-footer! cs)
  (tui/tui-request-render (:tui cs)))

(defn- on-agent-done [cs response-text]
  "Called when the LLM turn completes.
   Session persistence is handled by the agent loop internally."
  (chat/chat-history-finalize-streaming! (:chat-history cs))
  (reset! (:running-turn? cs) false)
  (update-header-footer! cs)
  (tui/tui-request-render (:tui cs)))

(defn- on-agent-error [cs error-msg]
  "Called when an error occurs during the agent turn."
  (chat/chat-history-finalize-streaming! (:chat-history cs))
  (chat/chat-history-add-message! (:chat-history cs)
    {:role :assistant :content (str RED "Error: " error-msg RST)})
  (reset! (:running-turn? cs) false)
  (update-header-footer! cs)
  (tui/tui-request-render (:tui cs)))

;; ─── Submit handler ────────────────────────────────────────────────────────

(defn- handle-submit [cs text]
  (let [trimmed (str/trim text)]
    (when (seq trimmed)
      (if (.startsWith trimmed "/")
        ;; Command
        (let [space (.indexOf trimmed " ")
              cmd (if (neg? space) (subs trimmed 1) (subs trimmed 1 space))
              args (if (neg? space) "" (str/trim (subs trimmed (inc space))))]
          (handle-command cs cmd args))
        ;; Regular message — agent loop handles session persistence
        (when-not @(:running-turn? cs)
          (reset! (:running-turn? cs) true)
          (chat/chat-history-add-message! (:chat-history cs)
            {:role :user :content trimmed})
          (update-header-footer! cs)
          (tui/tui-request-render (:tui cs))
          (agent/run-agent-turn (:agent-state cs)
            {:message trimmed
             :on-text #(on-agent-text cs %)
             :on-done #(on-agent-done cs %)
             :on-error #(on-agent-error cs %)}))))))

(defn- handle-cancel [cs]
  "Cancel the current agent turn."
  (when @(:running-turn? cs)
    (agent/cancel-turn (:agent-state cs))
    (chat/chat-history-finalize-streaming! (:chat-history cs))
    (chat/chat-history-add-message! (:chat-history cs)
      {:role :assistant :content (str DIM "(cancelled)" RST)})
    (reset! (:running-turn? cs) false)
    (update-header-footer! cs)
    (tui/tui-request-render (:tui cs))))

;; ─── Layout setup ──────────────────────────────────────────────────────────

(defn- build-layout
  "Create TUI layout and return CoreState."
  [& {:keys [model provider session]}]
  (let [jline-term (term/create-terminal)
        t (tui/create-tui jline-term)

        ;; Agent state — pass session so the agent loop persists entries
        ag (agent/make-agent-state
             :model (or model default-model)
             :provider (or provider default-provider)
             :system default-system-prompt
             :session session)

        ;; Components
        hdr (text/make-text "" 1 1)
        sp1 (spacer/make-spacer 1)
        ch (chat/make-chat-history :max-lines 100)
        sp2 (spacer/make-spacer 1)
        ed (tui/make-editor :height 8 :padding-x 2
            :border-fn (fn [c] (str DIM c RST)))
        sp3 (spacer/make-spacer 1)
        ftr (text/make-text "" 1 1)

        ;; Core state
        cs (map->CoreState {:tui t
                            :agent-state ag
                            :chat-history ch
                            :editor ed
                            :header-text hdr
                            :footer-text ftr
                            :session session
                            :running-turn? (atom false)})]

    ;; Wire editor submit
    (editor/editor-set-on-submit! ed
      (fn [text]
        (when text
          (handle-submit cs text)
          (editor/editor-set-text! ed "")
          (tui/tui-request-render t))))

    ;; Focus editor
    (tui/tui-set-focus t ed)

    ;; Add components
    (tui/tui-add-child t hdr)
    (tui/tui-add-child t sp1)
    (tui/tui-add-child t ch)
    (tui/tui-add-child t sp2)
    (tui/tui-add-child t ed)
    (tui/tui-add-child t sp3)
    (tui/tui-add-child t ftr)

    ;; Global input listeners
    (tui/tui-add-input-listener t
      (fn [data]
        (cond
          (keys/matches-key? data (keys/ctrl "z"))
          (tui/tui-stop t)

          (keys/matches-key? data (keys/ctrl "l"))
          (do (term/write-output jline-term "\u001b[2J\u001b[H")
              (tui/tui-request-render t))

          (keys/matches-key? data "escape")
          (when @(:running-turn? cs)
            (handle-cancel cs))

          (keys/matches-key? data (keys/ctrl "c"))
          (if @(:running-turn? cs)
            (handle-cancel cs)
            (do (editor/editor-set-text! ed "")
                (tui/tui-request-render t)))

          :else nil)))

    ;; Initialize header/footer
    (text/text-set! hdr (fmt-header cs))
    (text/text-set! ftr (fmt-footer cs))

    ;; Welcome message
    (chat/chat-history-add-message! ch
      {:role :assistant
       :content (str "Welcome to " BLD "kmet" RST
                     " — minimal coding agent.\n"
                     "Type your message or /help for commands.")})

    cs))

;; ─── Non-interactive mode (--print) ────────────────────────────────────────

(defn- run-print-mode
  "Run in non-interactive mode: send message, print response, exit."
  [{:keys [model provider messages]}]
  (let [ag (agent/make-agent-state
             :model model
             :provider provider
             :system default-system-prompt)
        result-promise (promise)]
    (agent/run-agent-turn ag
      {:message (str/join " " messages)
       :on-text (fn [t] (print t) (flush))
       :on-done (fn [text] (println) (deliver result-promise text))
       :on-error (fn [e] (binding [*out* *err*] (println "Error:" e))
                   (deliver result-promise nil))})
    @result-promise))

;; ─── CLI argument parsing ──────────────────────────────────────────────────

(defn- parse-args [args]
  (loop [args args
         opts {:provider default-provider
               :model default-model
               :print false
               :continue false
               :resume false
               :messages []}]
    (if (empty? args)
      opts
      (let [arg (first args)
            rest-args (rest args)]
        (cond
          (#{"-p" "--print"} arg)
          (recur rest-args (assoc opts :print true))

          (#{"-c" "--continue"} arg)
          (recur rest-args (assoc opts :continue true))

          (#{"-r" "--resume"} arg)
          (recur rest-args (assoc opts :resume true))

          (#{"--model"} arg)
          (if (seq rest-args)
            (recur (rest rest-args) (assoc opts :model (first rest-args)))
            (recur rest-args opts))

          (#{"--provider"} arg)
          (if (seq rest-args)
            (let [p (keyword (first rest-args))]
              (recur (rest rest-args) (assoc opts :provider p)))
            (recur rest-args opts))

          (#{"-h" "--help"} arg)
          (assoc opts :help true)

          (.startsWith arg "@")
          (let [file-path (subs arg 1)
                file-content (try (slurp file-path)
                                  (catch Exception _ ""))]
            (recur rest-args (update opts :messages conj file-content)))

          :else
          (recur rest-args (update opts :messages conj arg)))))))

(defn- print-usage []
  (println "Usage: kmet [options] [@files...] [messages...]")
  (println)
  (println "Options:")
  (println "  -p, --print           Print response and exit (non-interactive)")
  (println "  -c, --continue        Continue most recent session")
  (println "  -r, --resume          Browse sessions")
  (println "  --model <id>          Model to use")
  (println "  --provider <name>     Provider (openai, anthropic)")
  (println "  -h, --help            Show this help")
  (println)
  (println "Examples:")
  (println "  kmet                    Start interactive TUI")
  (println "  kmet -p \"list files\"    Print response and exit")
  (println "  kmet --model gpt-4o     Start with specific model")
  (println "  kmet @tasks.md         Start with file content"))

;; ─── Main ──────────────────────────────────────────────────────────────────

(defn -main
  "Entry point. Parses CLI args and runs the agent."
  [& args]
  (let [opts (parse-args args)]

    (when (:help opts)
      (print-usage)
      (System/exit 0))

    (when (:print opts)
      (let [msg (str/join " " (:messages opts))]
        (if (empty? msg)
          (do (println "No message provided. Usage: kmet -p \"your message\"")
              (System/exit 1))
          (do (run-print-mode (assoc opts :messages [msg]))
              (System/exit 0))))))

  (println "Starting kmet...")

  (let [tui-ref (atom nil)]
    (try
      (let [session (find-or-create-session)
            cs (build-layout :session session)]
        (reset! tui-ref (:tui cs))
        (tui/tui-start (:tui cs))
        (println "kmet session ended."))
      (catch Exception e
        ;; Restore terminal if TUI was started
        (when-let [t @tui-ref]
          (try (tui/tui-stop t) (catch Exception _)))
        (binding [*out* *err*]
          (println "Error:" (.getMessage e))
          (.printStackTrace e))
        (System/exit 1)))))
