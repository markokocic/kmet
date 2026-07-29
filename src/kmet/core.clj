(ns kmet.core
  "Main entry point for kmet — minimal coding agent TUI.
   CLI interface, main layout, agent integration, and command handling."
  (:require [kmet.tui.core :as tui]
            [kmet.tui.terminal :as term]
            [kmet.tui.keys :as keys]
            [kmet.tui.components.text :as text]
            [kmet.tui.components.spacer :as spacer]
            [kmet.tui.components.editor :as editor]
            [kmet.agent.ui :as ui]
            [kmet.tui.components.select-list :as select-list]
            [kmet.agent.loop :as agent]
            [kmet.agent.session :as session]
            [kmet.agent.tools :as tools]
            [kmet.config :as cfg]
            [kmet.skills :as skills]
            [kmet.debug :as debug]
            [clojure.string :as str]
            [babashka.fs :as fs]))

(declare resume-session show-session-tree)

;; ─── ANSI ──────────────────────────────────────────────────────────────────

(def ^:private RST "\u001b[0m")
(def ^:private BLD "\u001b[1m")
(def ^:private DIM "\u001b[2m")
(def ^:private RED "\u001b[31m")
(def ^:private GRN "\u001b[32m")
(def ^:private YLW "\u001b[33m")
(def ^:private CYN "\u001b[36m")

;; ─── Global config ref ────────────────────────────────────────────────────

(defonce ^:private global-config (atom nil))

;; ─── Theme shortcuts ─────────────────────────────────────────────────────

(def ^:private RST "\u001b[0m")
(def ^:private BLD "\u001b[1m")
(def ^:private DIM "\u001b[2m")
(def ^:private RED "\u001b[31m")
(def ^:private GRN "\u001b[32m")
(def ^:private YLW "\u001b[33m")
(def ^:private CYN "\u001b[36m")

;; ─── Session helpers ───────────────────────────────────────────────────────

(defn- get-session-dir []
  (if-let [c @global-config]
    (cfg/get-session-dir c)
    (str (System/getProperty "user.home") "/.local/share/kmet/sessions")))

(defn- ensure-session-dir []
  (let [d (get-session-dir)]
    (fs/create-dirs d)
    d))

(defn- find-session
  "Find the most recent existing session, or nil."
  []
  (let [dir (ensure-session-dir)
        existing (session/list-sessions dir)]
    (when (seq existing)
      (session/load-session (first existing)))))

;; ─── Core state ────────────────────────────────────────────────────────────

(defrecord CoreState [tui
                      agent-state
                      chat-history
                      editor
                      header-text
                      anim-timer
                      footer-comp
                      session
                      running-turn?
                      config])

;; ─── Formatting helpers ────────────────────────────────────────────────────

(defn- fmt-model [provider model]
  (str (name provider) ":" model))

(defn- fmt-status-str [cs]
  (let [status (name (agent/get-status (:agent-state cs)))]
    (case status
      "idle" (str DIM "idle" RST)
      "thinking" (str YLW "● thinking" RST)
      "executing" (str YLW "● executing" RST)
      "error" (str RED "● error" RST)
      (str DIM status RST))))

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
         " │ " (fmt-status-str cs)
         " │ " DIM short-cwd RST)))

(defn- fmt-footer-str [cs]
  (let [n-msgs (count (ui/chat-history-get-messages (:chat-history cs)))
        expanded? (ui/chat-history-get-tool-expanded (:chat-history cs))
        thinking-hidden? (ui/chat-history-get-thinking-hidden (:chat-history cs))
        toggles (str (when expanded? (str CYN " O" RST))
                     (when thinking-hidden? (str YLW " T" RST)))
        cmds (str DIM "/quit" RST " " DIM "/help" RST " "
                   DIM "/model" RST " " DIM "/new" RST " "
                   DIM "/resume" RST)]
    (str "msgs:" n-msgs
         toggles
         " │ " cmds)))

(defn- update-header-footer! [cs]
  (text/text-set! (:header-text cs) (fmt-header cs))
  (ui/footer-set-status! (:footer-comp cs) (fmt-status-str cs))
  nil)

;; ─── Command handling ──────────────────────────────────────────────────────

(defn- handle-command
  "Handle slash commands. Returns nil."
  [cs cmd args]
  (case cmd
    "quit"
    (do (debug/log "/quit command")
        (tui/tui-stop (:tui cs)))

    "help"
    (let [help-text (str
                     "Available commands:\n"
                     "  /quit   — Exit kmet\n"
                     "  /help   — Show this help\n"
                     "  /model <provider:model> — Switch model\n"
                     "  /new    — Start a new session\n"
                     "  /resume — Browse past sessions\n"
                     "  /tree   — Browse session entry tree\n"
                     "  /theme <name> — Switch theme\n"
                     "\n"
                     "Shortcuts:\n"
                     "  Enter      — Submit message\n"
                     "  Escape     — Cancel current turn\n"
                     "  Ctrl+Z     — Quit\n"
                     "  Ctrl+C     — Cancel / clear editor\n"
                     "  Ctrl+L     — Clear terminal\n"
                     "  Up/Down    — Scroll chat history")]
      (ui/chat-history-add-message! (:chat-history cs)
        {:role :assistant :content help-text}))

    "model"
    (if (seq args)
      (let [parts (str/split args #":" 2)
            provider (keyword (or (first parts) "openai"))
            model (or (second parts) (:model (:config cs)))]
        (agent/set-provider! (:agent-state cs) provider)
        (agent/set-model! (:agent-state cs) model)
        (ui/chat-history-add-message! (:chat-history cs)
          {:role :assistant :content (str "Switched to " (fmt-model provider model))}))
      (ui/chat-history-add-message! (:chat-history cs)
        {:role :assistant :content
         (str "Current model: " (fmt-model @(:provider (:agent-state cs))
                                            @(:model (:agent-state cs)))
              "\nUsage: /model <provider:model>")}))

    "new"
    (let [new-session (session/create-session (ensure-session-dir))]
      (debug/log "new session created: " (:id new-session))
      (ui/chat-history-clear! (:chat-history cs))
      ;; Update both CoreState and AgentState session references
      (reset! (:session cs) new-session)
      (let [old-ag (:agent-state cs)
            new-ag (assoc old-ag :session new-session)]
        (reset! (:agent-state cs) new-ag))
      (ui/chat-history-add-message! (:chat-history cs)
        {:role :assistant :content "Started a new session."}))

    "resume"
    (do (debug/log "/resume command")
        (resume-session cs ensure-session-dir))

    "tree"
    (show-session-tree cs)

    "theme"
    (if (seq args)
      (ui/chat-history-add-message! (:chat-history cs)
        {:role :assistant
         :content (str "Theme switching not yet implemented. "
                       "Available themes: dark, light. "
                       "Current theme: " (cfg/get-theme-name (:config cs)))})
      (ui/chat-history-add-message! (:chat-history cs)
        {:role :assistant
         :content (str "Current theme: " (cfg/get-theme-name (:config cs))
                       "\nUsage: /theme <name>")}))

    ;; Unknown command
    (ui/chat-history-add-message! (:chat-history cs)
      {:role :assistant
       :content (str "Unknown command: /" cmd ". Type /help for available commands.")}))
  (update-header-footer! cs))

;; ─── Resume session ────────────────────────────────────────────────────────

(defn- resume-session
  "Browse past sessions via SelectList overlay."
  [cs session-dir-fn]
  (let [sessions (session/list-sessions (session-dir-fn))]
    (if (empty? sessions)
      (ui/chat-history-add-message! (:chat-history cs)
        {:role :assistant :content "No past sessions found."})
      (let [items (vec (for [s sessions]
                         (let [fname (str/replace s #".*/" "")
                               short-id (subs fname 0 (min 8 (count fname)))
                               loaded (session/load-session s)
                               n-msgs (count (:entries loaded))]
                           {:label (str short-id "... " n-msgs " msgs")
                            :value s})))
            sl-ref (atom nil)
            on-select-fn (fn []
                          (when-let [sel (select-list/select-list-get-selected @sl-ref)]
                            (let [sess (session/load-session (:value sel))
                                  entries (session/get-branch sess)
                                  fname (str/replace (:value sel) #".*/" "")
                                  short-id (subs fname 0 (min 8 (count fname)))]
                              (ui/chat-history-clear! (:chat-history cs))
                              (reset! (:session cs) sess)
                              (let [new-ag (assoc (:agent-state cs) :session sess)]
                                (reset! (:agent-state cs) new-ag))
                              (doseq [e entries]
                                (let [role (:role e)
                                      texts (filter #(= (:type %) :text) (:content e))
                                      content (str/join (map :text texts))]
                                  (ui/chat-history-add-message! (:chat-history cs)
                                    (merge {:role role :content content}
                                      (when (= role :tool)
                                        {:name (or (:name e) "tool")})))))
                              (ui/chat-history-add-message! (:chat-history cs)
                                {:role :assistant
                                 :content (str "Resumed session " short-id ".")})
                              (tui/tui-hide-overlay (:tui cs))
                              (update-header-footer! cs)
                              (tui/tui-request-render (:tui cs)))))
            sl (select-list/make-select-list items
                 :height (min (count items) 15)
                 :on-select on-select-fn
                 :on-escape (fn []
                              (tui/tui-hide-overlay (:tui cs))
                              (tui/tui-request-render (:tui cs))))]
        (reset! sl-ref sl)
        (tui/tui-show-overlay (:tui cs) sl :width 50 :height (min (count items) 15))
        (tui/tui-request-render (:tui cs))))))

;; ─── Session tree ─────────────────────────────────────────────────────────

(defn- show-session-tree
  "Browse the current session's entry tree via SelectList overlay."
  [cs]
  (let [sess (:session cs)]
    (if (nil? sess)
      (ui/chat-history-add-message! (:chat-history cs)
        {:role :assistant :content "No active session."})
      (let [tree (session/get-tree sess)]
        (if (empty? tree)
          (ui/chat-history-add-message! (:chat-history cs)
            {:role :assistant :content "Session is empty."})
          (let [flatten-tree (fn flatten-tree [nodes depth]
                              (mapcat (fn [n]
                                        (let [prefix (apply str (repeat depth "  "))
                                              role-str (name (:role n))
                                              label (str prefix role-str ": " (:summary n))]
                                          (cons {:label label
                                                 :value (:id n)
                                                 :depth depth
                                                 :entry n}
                                                (flatten-tree (:children n) (inc depth)))))
                                      nodes))
                items (vec (flatten-tree tree 0))]
            (let [sl-ref (atom nil)
                  on-select-fn (fn []
                                (when-let [sel (select-list/select-list-get-selected @sl-ref)]
                                  (let [entry (:entry sel)
                                        entry-id (:value sel)
                                        role (:role entry)
                                        texts (if (string? (:content entry))
                                                [(:content entry)]
                                                (map :text (filter #(= (:type %) :text) (:content entry))))
                                        content (str/join texts)]
                                    (ui/chat-history-add-message! (:chat-history cs)
                                      (merge {:role (or role :unknown) :content content}
                                        (when (= role :tool)
                                          {:name (or (:name entry) "tool")})))
                                    (tui/tui-hide-overlay (:tui cs))
                                    (update-header-footer! cs)
                                    (tui/tui-request-render (:tui cs)))))
                  sl (select-list/make-select-list items
                       :height (min (count items) 20)
                       :on-select on-select-fn
                       :on-escape (fn []
                                    (tui/tui-hide-overlay (:tui cs))
                                    (tui/tui-request-render (:tui cs))))]
              (reset! sl-ref sl)
              (tui/tui-show-overlay (:tui cs) sl :width 70 :height (min (count items) 20))
              (tui/tui-request-render (:tui cs)))))))))

;; ─── Animation timer ────────────────────────────────────────────────────────
;; Drives re-renders while the agent turn is running, so the inline working
;; indicator in the streaming assistant message animates smoothly.

(defn- start-anim-timer!
  "Start requesting renders every 80ms while the agent turn runs.
   Powers the inline working indicator animation in streaming messages."
  [cs]
  (let [t (future
            (try
              (loop []
                (when (and @(:running? (:tui cs))
                           @(:running-turn? cs))
                  (Thread/sleep 80)
                  (tui/tui-request-render (:tui cs))
                  (recur)))
              (catch Exception e
                (debug/log "anim timer: " e))))]
    (reset! (:anim-timer cs) t)))

(defn- stop-anim-timer!
  "Cancel the animation timer."
  [cs]
  (when-let [t @(:anim-timer cs)]
    (future-cancel t)
    (reset! (:anim-timer cs) nil)))

;; ─── Agent response handler ────────────────────────────────────────────────

(defn- on-agent-text [cs text]
  "Called for each text delta from the LLM during streaming."
  (try
    (ui/chat-history-append-streaming-text! (:chat-history cs) text)
    (tui/tui-request-render (:tui cs))
    (catch Exception e
      (debug/log "on-agent-text callback: " e)
      (binding [*out* *err*] (println "on-agent-text error:" (.getMessage e) (.getClass e))))))

(defn- on-agent-thinking [cs text]
  "Called for each thinking/reasoning delta from the LLM during streaming."
  (try
    (ui/chat-history-append-thinking-text! (:chat-history cs) text)
    (tui/tui-request-render (:tui cs))
    (catch Exception e
      (debug/log "on-agent-thinking callback: " e)
      (binding [*out* *err*] (println "on-agent-thinking error:" (.getMessage e) (.getClass e))))))

(defn- on-agent-done [cs]
  "Called when the LLM turn completes.
   Finalize streaming FIRST (captures thinking text), then clear thinking.
   Session persistence is handled by the agent loop internally."
  (try
    (stop-anim-timer! cs)
    (ui/chat-history-finalize-streaming! (:chat-history cs))
    (ui/chat-history-finalize-thinking! (:chat-history cs))
    (reset! (:running-turn? cs) false)
    (update-header-footer! cs)
    (tui/tui-request-render (:tui cs))
    (debug/log "agent turn completed")
    (catch Exception e
      (debug/log "on-agent-done callback: " e)
      (binding [*out* *err*] (println "on-agent-done error:" (.getMessage e) (.getClass e))))))

(defn- on-agent-error [cs error-msg]
  "Called when an error occurs during the agent turn."
  (try
    (stop-anim-timer! cs)
    ;; If streaming placeholder is still empty (just working indicator), remove it
    ;; so we don't get a blank assistant entry before the error message.
    (let [ch (:chat-history cs)
          streaming @(:streaming-atom ch)]
      (if (and streaming
               (empty? @(:text-atom streaming))
               (empty? @(:thinking-text-atom streaming)))
        (do (ui/chat-history-remove-last! ch)
            (reset! (:streaming-atom ch) nil))
        (do (ui/chat-history-finalize-streaming! ch)
            (ui/chat-history-finalize-thinking! ch))))
    (ui/chat-history-add-message! (:chat-history cs)
      {:role :assistant :content (str RED "Error: " error-msg RST)})
    (reset! (:running-turn? cs) false)
    (update-header-footer! cs)
    (tui/tui-request-render (:tui cs))
    (debug/log "agent turn error: " error-msg)
    (catch Exception e
      (debug/log "on-agent-error callback: " e)
      (binding [*out* *err*] (println "on-agent-error error:" (.getMessage e) (.getClass e))))))

;; ─── Submit handler ────────────────────────────────────────────────────────

(defn- handle-submit [cs text]
  (let [trimmed (str/trim text)]
    (when (seq trimmed)
      (if (str/starts-with? trimmed "/")
        ;; Command
        (let [space (str/index-of trimmed " ")
              cmd (if (nil? space) (subs trimmed 1) (subs trimmed 1 space))
              args (if (nil? space) "" (str/trim (subs trimmed (inc space))))]
          (handle-command cs cmd args))
        ;; Regular message — agent loop handles session persistence
        (when-not @(:running-turn? cs)
          (reset! (:running-turn? cs) true)
          (start-anim-timer! cs)
          (debug/log "user submitted: " trimmed)
          (ui/chat-history-add-message! (:chat-history cs)
            {:role :user :content trimmed})
          ;; Pre-create streaming placeholder so the inline working indicator
          ;; appears immediately while waiting for the first LLM delta.
          (ui/chat-history-start-streaming! (:chat-history cs))
          (update-header-footer! cs)
          (tui/tui-request-render (:tui cs))
          (agent/run-agent-turn (:agent-state cs)
            {:message trimmed
             :on-text #(on-agent-text cs %)
             :on-thinking #(on-agent-thinking cs %)
             :on-done (fn [_] (on-agent-done cs))
             :on-error #(on-agent-error cs %)}))))))

(defn- handle-cancel [cs]
  "Cancel the current agent turn."
  (when @(:running-turn? cs)
    (debug/log "agent turn cancelled by user")
    (stop-anim-timer! cs)
    (agent/cancel-turn (:agent-state cs))
    ;; Remove empty streaming placeholder if present
    (let [ch (:chat-history cs)]
      (when-let [s @(:streaming-atom ch)]
        (if (and (empty? @(:text-atom s)) (empty? @(:thinking-text-atom s)))
          (do (ui/chat-history-remove-last! ch) (reset! (:streaming-atom ch) nil))
          (do (ui/chat-history-finalize-streaming! ch) (ui/chat-history-finalize-thinking! ch)))))
    (ui/chat-history-add-message! (:chat-history cs)
      {:role :assistant :content (str DIM "(cancelled)" RST)})
    (reset! (:running-turn? cs) false)
    (update-header-footer! cs)
    (tui/tui-request-render (:tui cs))))

;; ─── Layout setup ──────────────────────────────────────────────────────────

(defn- build-layout
  "Create TUI layout and return CoreState."
  [config session]
  (let [jline-term (term/create-terminal)
        t (tui/create-tui jline-term)

        ;; Resolve model and provider from config
        provider (cfg/get-provider config)
        model (cfg/get-model config)

        ;; Load skills and build system prompt
        _ (skills/load-skills-from-dir (cfg/expand-path (:skills-dir config)))
        base-prompt (or (:system-prompt config)
                        "You are kmet, a minimal coding agent. Help the user with their tasks.
Use the available tools to read, write, edit files, and execute commands.
Be precise and concise in your responses.")
        system-prompt (skills/build-system-prompt base-prompt)

        ;; Components
        hdr (text/make-text "" 1 0)
        sp1 (spacer/make-spacer 1)

        ;; Agent state
        ag (agent/make-agent-state
             :model model
             :provider provider
             :system system-prompt
             :session session
             :compact-threshold (:compact-threshold config)
             :thinking (:thinking config :off)
             :on-event (fn [evt]
                         ;; Forward events to extension system
                         (skills/emit-event! evt)))

        ch (ui/make-chat-history :theme (cfg/get-theme config))
        sp2 (spacer/make-spacer 1)
        ed (tui/make-editor :height 8 :padding-x 2
            :border-fn (fn [c] (str DIM c RST)))
        sp3 (spacer/make-spacer 1)
        ftr (ui/make-footer :status "" :n-msgs 0)

        ;; Core state
        cs (map->CoreState {:tui t
                            :agent-state ag
                            :chat-history ch
                            :editor ed
                            :header-text hdr
                            :anim-timer (atom nil)
                            :footer-comp ftr
                            :session session
                            :running-turn? (atom false)
                            :config config})]

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
          (do (let [w (.writer (.terminal jline-term))]
                (.write w "\u001b[2J\u001b[H")
                (.flush w))
              (tui/tui-request-render t))

          (keys/matches-key? data "escape")
          (when (and @(:running-turn? cs)
                     (not (tui/tui-has-overlay? t)))
            (handle-cancel cs))

          (keys/matches-key? data (keys/ctrl "c"))
          (if @(:running-turn? cs)
            (handle-cancel cs)
            (do (editor/editor-set-text! ed "")
                (tui/tui-request-render t)))

          ;; Pi-style: Ctrl+O toggles tool output expansion
          (keys/matches-key? data (keys/ctrl "o"))
          (do (ui/chat-history-toggle-tool-expanded! ch)
              (update-header-footer! cs)
              (tui/tui-request-render t))

          ;; Pi-style: Ctrl+T toggles thinking block visibility
          (keys/matches-key? data (keys/ctrl "t"))
          (do (ui/chat-history-toggle-thinking-hidden! ch)
              (update-header-footer! cs)
              (tui/tui-request-render t))

          :else nil)))

    ;; Initialize header/footer
    (text/text-set! hdr (fmt-header cs))
    (ui/footer-set-status! ftr (fmt-status-str cs))

    ;; Pi-style info message on top
    (ui/chat-history-set-info-msg! ch
      {:label "kmet"
       :content (str "Welcome to kmet — minimal coding agent.\n"
                     "Type a message, /help for commands, or use:\n"
                     "  " DIM "Ctrl+O" RST " — toggle tool output  " DIM "Ctrl+T" RST " — toggle thinking blocks")})

    ;; Welcome message
    (ui/chat-history-add-message! ch
      {:role :assistant
       :content (str "Welcome to " BLD "kmet" RST
                     " — minimal coding agent.\n"
                     "Type your message or /help for commands.")})

    cs))

;; ─── Non-interactive mode (--print) ────────────────────────────────────────

(defn- run-print-mode
  "Run in non-interactive mode: send message, print response, exit."
  [{:keys [model provider messages config]}]
  (let [config (or config (cfg/load-config :no-env? true))
        _ (skills/load-skills-from-dir (cfg/expand-path (:skills-dir config)))
        base-prompt (or (:system-prompt config)
                        "You are kmet, a minimal coding agent. Help the user with their tasks.
Use the available tools to read, write, edit files, and execute commands.
Be precise and concise in your responses.")
        system-prompt (skills/build-system-prompt base-prompt
                        :tools (vals (tools/get-all-tools)))
        resolved-provider (or provider (cfg/get-provider config))
        resolved-model (or model (cfg/get-model config))
        ag (agent/make-agent-state
             :model resolved-model
             :provider resolved-provider
             :system system-prompt)
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
         opts {:provider nil
               :model nil
               :print false
               :continue false
               :resume false
               :debug false
               :messages []}]
    (if (empty? args)
      opts
      (let [arg (first args)
            rest-args (rest args)]
        (cond
          (#{"-d" "--debug"} arg)
          (recur rest-args (assoc opts :debug true))

          (#{"-p" "--print"} arg)
          (recur rest-args (assoc opts :print true))

          (#{"-c" "--continue"} arg)
          (recur rest-args (assoc opts :continue true))

          (#{"-t" "--thinking"} arg)
          (if (seq rest-args)
            (let [level (keyword (first rest-args))]
              (recur (rest rest-args) (assoc opts :thinking level)))
            (recur rest-args opts))

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

          (str/starts-with? arg "@")
          (let [file-path (subs arg 1)]
            (if (seq file-path)
              (let [file-content (try (slurp file-path)
                                      (catch Exception _
                                        (binding [*out* *err*]
                                          (println "Warning: could not read" file-path))
                                        ""))]
                (recur rest-args (update opts :messages conj file-content)))
              (do (binding [*out* *err*] (println "Warning: empty path after @"))
                  (recur rest-args opts))))

          :else
          (recur rest-args (update opts :messages conj arg)))))))

(defn- print-usage []
  (println "Usage: kmet [options] [@files...] [messages...]")
  (println)
  (println "Options:")
  (println "  -d, --debug           Log to debug.log")
  (println "  -p, --print           Print response and exit (non-interactive)")
  (println "  -c, --continue        Continue most recent session")
  (println "  -r, --resume          Browse sessions")
  (println "  --model <id>          Model to use")
  (println "  --provider <name>     Provider (openai, anthropic, opencode-go)")
  (println "  -t, --thinking <level> Thinking level (off, low, medium, high)")
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
          (do (run-print-mode (assoc opts :messages [msg] :config (cfg/load-config :no-env? true)))
              (System/exit 0)))))
    (when (:debug opts)
      (debug/enable!)
      (debug/log "kmet started with --debug"))

    (println "Starting kmet...")

    ;; Initialize configuration and themes
    (let [config (cfg/init!)
          _ (reset! global-config config)
          tui-ref (atom nil)]
      (try
        ;; Load extensions
        (let [ext-dir (cfg/expand-path (:extensions-dir config))]
          (skills/load-extensions-from-dir ext-dir))

        ;; Apply command-line overrides
        (let [config (cond-> config
                       (:model opts) (assoc :model (:model opts))
                       (:provider opts) (assoc :provider (:provider opts))
                       (:thinking opts) (assoc :thinking (:thinking opts)))
              _ (reset! global-config config)
              session (cond
                        (:resume opts) nil
                        (:continue opts) (find-session)
                        :else (session/create-session (ensure-session-dir)))
              cs (build-layout config session)]
          (reset! tui-ref (:tui cs))
          (when (:resume opts) (resume-session cs ensure-session-dir))
          (tui/tui-start (:tui cs))
          (println "kmet session ended."))
        (catch Exception e
        ;; Restore terminal if TUI was started
          (when-let [t @tui-ref]
            (try (tui/tui-stop t) (catch Exception _)))
          (debug/log-error "unhandled exception: " e)
          (binding [*out* *err*]
            (println "Error:" (.getMessage e))
            (.printStackTrace e))
          (System/exit 1))))))
