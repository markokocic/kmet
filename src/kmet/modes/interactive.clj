(ns kmet.modes.interactive
  "Interactive TUI mode — main layout, agent integration, command handling,
   session browsing, bash commands, external editor.
   pi: modes/interactive/interactive-mode.ts."
  (:require [kmet.tui.core :as tui]
            [kmet.tui.terminal :as term]
            [kmet.tui.keys :as keys]
            [kmet.tui.theme :as th]
            [kmet.tui.components.text :as text]
            [kmet.tui.components.spacer :as spacer]
            [kmet.tui.components.editor :as editor]
            [kmet.tui.components.container :as container]
            [kmet.app.ui :as ui]
            [kmet.tui.components.select-list :as select-list]
            [kmet.app.loop :as agent]
            [kmet.app.session :as session]
            [kmet.app.tools.core :as tools]
            [kmet.app.keybindings :as app-kb]
            [kmet.tui.keybindings :as tui-kb]
            [kmet.config :as cfg]
            [kmet.app.skills :as skills]
            [kmet.app.commands :as commands]
            [kmet.app.extensions :as extensions]
            [kmet.app.event-bus :as event-bus]
            [kmet.tui.autocomplete :as ac]
            [kmet.debug :as debug]
            [clojure.string :as str]
            [babashka.fs :as fs]
            [babashka.process :as proc]
            [kmet.app.bash-executor :as bash-exec]
            [kmet.app.ui.bash-execution :as be]
            [kmet.libs.process :as process]))

(declare resume-session show-session-tree)

;; ─── Global config ref ────────────────────────────────────────────────────

(defonce ^:private global-config (atom nil))

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
                      status-indicator
                      session
                      running-turn?
                      config
                      pending-tool-comp
                      bash-running?
                      bash-signal
                      pending-bash-components
                      pending-bash-container])

;; ─── Formatting helpers ────────────────────────────────────────────────────

(defn- fmt-model [provider model]
  (str (name provider) ":" model))

(defn- fmt-status-str [cs]
  (let [bash-running @(:bash-running? cs)
        status (name (agent/get-status (:agent-state cs)))]
    (if bash-running
      (th/fg th/dark-theme :bash-mode "$ bash")
      (case status
        "idle" (th/dim "idle")
        "thinking" (th/fg th/dark-theme :warning "● thinking")
        "executing" (th/fg th/dark-theme :warning "● executing")
        "error" (th/fg th/dark-theme :error "● error")
        (th/dim status)))))

(defn- fmt-header [cs]
  (let [provider @(:provider (:agent-state cs))
        model @(:model (:agent-state cs))
        status (name (agent/get-status (:agent-state cs)))
        sess-id (some-> (:session cs) :id (subs 0 8) (str "..."))
        cwd (System/getProperty "user.dir")
        short-cwd (if (> (count cwd) 30) (str "..." (subs cwd (- (count cwd) 27))) cwd)]
    (str (th/bold (th/fg th/dark-theme :accent " kmet")) " "
         (th/dim (fmt-model provider model))
         " │ " (th/dim "session:") " " (or sess-id "none")
         " │ " (fmt-status-str cs)
         " │ " (th/dim short-cwd))))

(defn- update-header-footer! [cs]
  (text/text-set! (:header-text cs) (fmt-header cs))
  (ui/footer-set-status! (:footer-comp cs) (fmt-status-str cs))
  nil)

;; ─── Command handling ──────────────────────────────────────────────────────

(defn- help-text
  "Help message derived from the live command registry."
  []
  (let [cmd-lines
        (mapv (fn [c]
                (let [name (:name c)
                      hint (:argument-hint c)
                      usage (if hint (str " /" name " " hint) (str " /" name))
                      desc (:description c "")]
                  (str "  " usage
                       (apply str (repeat (max 1 (- 32 (count usage))) " "))
                       desc)))
              (commands/get-commands))]
    (str "Available commands:\n"
         (clojure.string/join "\n" cmd-lines)
         "\n\nShortcuts:\n"
         "  Enter      — Submit message\n"
         "  Escape     — Cancel current turn / bash\n"
         "  Ctrl+C     — Clear editor (press twice to quit)\n"
         "  Ctrl+D     — Quit (when editor is empty)\n"
         "  Ctrl+G     — Open external editor\n"
         "  Ctrl+O     — Toggle tool output\n"
         "  Ctrl+T     — Toggle thinking blocks\n"
         "  Ctrl+L     — Clear terminal\n"
         "  Up/Down    — Scroll chat history")))

(defn- tools-text
  "Text listing all available tools with their parameters."
  []
  (let [tool-list (sort-by :name (vals (tools/get-all-tools)))
        tool-lines
        (mapv (fn [t]
                (let [required (set (:required (:parameters t)))
                      param-lines
                      (mapv (fn [[pname p]]
                              (let [req (if (contains? required (name pname)) " (required)" "")]
                                (str "    " (name pname) " (" (:type p) ")" req " — " (:description p))))
                            (:properties (:parameters t)))]
                  (str "  " (:name t) " — " (:description t)
                       (when (seq param-lines)
                         (str "\n" (str/join "\n" param-lines))))))
              tool-list)]
    (str "Available tools (" (count tool-list) "):\n"
         (str/join "\n" tool-lines))))

(defn- register-builtin-commands!
  "Register kmet's builtin slash commands. Handlers receive [cs args];
   argument completions feed the editor autocomplete dropdown."
  [config]
  (commands/register-command!
    {:name "quit"
     :description "Exit kmet"
     :handler (fn [cs _]
                (debug/log "/quit command")
                (tui/tui-stop (:tui cs)))})
  (commands/register-command!
    {:name "help"
     :description "Show available commands and shortcuts"
     :handler (fn [cs _]
                (ui/chat-history-add-message! (:chat-history cs)
                  {:role :assistant :content (help-text)}))})
  (commands/register-command!
    {:name "tools"
     :description "List available tools with parameters"
     :handler (fn [cs _]
                (ui/chat-history-add-message! (:chat-history cs)
                  {:role :assistant :content (tools-text)}))})
  (commands/register-command!
    {:name "model"
     :description "Switch model"
     :argument-hint "<provider:model>"
     :get-argument-completions
     (fn [_]
       (mapv (fn [m] {:value m :label m})
             (or (:models config) [(:model config)])))
     :handler (fn [cs args]
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
                          "\nUsage: /model <provider:model>")})))})
  (commands/register-command!
    {:name "new"
     :description "Start a new session"
     :handler (fn [cs _]
                (let [new-session (session/create-session (ensure-session-dir))]
                  (debug/log "new session created: " (:id new-session))
                  (ui/chat-history-clear! (:chat-history cs))
                  (reset! (:session cs) new-session)
                  (let [old-ag (:agent-state cs)
                        new-ag (assoc old-ag :session new-session)]
                    (reset! (:agent-state cs) new-ag))
                  (ui/chat-history-add-message! (:chat-history cs)
                    {:role :assistant :content "Started a new session."})))})
  (commands/register-command!
    {:name "resume"
     :description "Browse past sessions"
     :handler (fn [cs _]
                (debug/log "/resume command")
                (resume-session cs ensure-session-dir))})
  (commands/register-command!
    {:name "tree"
     :description "Browse session entry tree"
     :handler (fn [cs _]
                (show-session-tree cs))})
  (commands/register-command!
    {:name "theme"
     :description "Switch theme"
     :argument-hint "<name>"
     :get-argument-completions
     (fn [_]
       (mapv (fn [t] {:value t :label t}) ["dark" "light"]))
     :handler (fn [cs args]
                (if (seq args)
                  (ui/chat-history-add-message! (:chat-history cs)
                    {:role :assistant
                     :content (str "Theme switching not yet implemented. "
                                   "Available themes: dark, light. "
                                   "Current theme: " (cfg/get-theme-name (:config cs)))})
                  (ui/chat-history-add-message! (:chat-history cs)
                    {:role :assistant
                     :content (str "Current theme: " (cfg/get-theme-name (:config cs))
                                   "\nUsage: /theme <name>")})))}))

(defn- handle-command
  "Handle slash commands via the command registry. Returns nil."
  [cs cmd args]
  (if-let [c (commands/find-command cmd)]
    ((:handler c) cs args)
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
                               n-msgs (count @(:entries loaded))]
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
                                      ;; Tool results are stored as :tool_result blocks
                                      ;; (with :content str); others as :text blocks
                                      content (str/join
                                                (keep (fn [b]
                                                        (case (:type b)
                                                          :text (:text b)
                                                          :tool_result (:content b)
                                                          nil))
                                                      (:content e)))]
                                  (ui/chat-history-add-message! (:chat-history cs)
                                    (merge {:role role :content content}
                                      (when (= role :tool)
                                        {:name (or (:name e) "tool")
                                         :is-error (:is-error e false)
                                         :truncation (:truncation e)
                                         :details (:details e)})))))
                              (ui/chat-history-add-message! (:chat-history cs)
                                {:role :assistant
                                 :content (str "Resumed session " short-id ".")})
                              (tui/tui-hide-overlay (:tui cs))
                              (update-header-footer! cs)
                              (tui/tui-request-render (:tui cs)))))
            sl (select-list/make-select-list items
                 :height (min (count items) 15)
                 :header "Resume session"
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
                       :header "Session tree"
                       :on-select on-select-fn
                       :on-escape (fn []
                                    (tui/tui-hide-overlay (:tui cs))
                                    (tui/tui-request-render (:tui cs))))]
              (reset! sl-ref sl)
              (tui/tui-show-overlay (:tui cs) sl :width 70 :height (min (count items) 20))
              (tui/tui-request-render (:tui cs)))))))))

;; ─── Animation timer ────────────────────────────────────────────────────────
;; Drives re-renders while the agent turn is running, so the separate
;; StatusIndicator (Pi-style) between chat and editor animates smoothly.

(defn- start-anim-timer!
  "Start requesting renders every 80ms while the agent turn runs.
   Powers the StatusIndicator spinner animation (Pi-style: separate layer
   between chat and editor)."
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
      (binding [*out* *err*] (println "on-agent-text error:" (ex-message e) (.getClass e))))))

(defn- on-agent-thinking [cs text]
  "Called for each thinking/reasoning delta from the LLM during streaming."
  (try
    (ui/chat-history-append-thinking-text! (:chat-history cs) text)
    (tui/tui-request-render (:tui cs))
    (catch Exception e
      (debug/log "on-agent-thinking callback: " e)
      (binding [*out* *err*] (println "on-agent-thinking error:" (ex-message e) (.getClass e))))))

(defn- on-agent-done [cs]
  "Called when the LLM turn completes.
   Finalize streaming FIRST (captures thinking text), then clear thinking.
   Session persistence is handled by the agent loop internally."
  (try
    (stop-anim-timer! cs)
    (ui/status-indicator-stop! (:status-indicator cs))
    (ui/chat-history-finalize-streaming! (:chat-history cs))
    (ui/chat-history-finalize-thinking! (:chat-history cs))
    (reset! (:running-turn? cs) false)
    (update-header-footer! cs)
    (tui/tui-request-render (:tui cs))
    (debug/log "agent turn completed")
    (catch Exception e
      (debug/log "on-agent-done callback: " e)
      (binding [*out* *err*] (println "on-agent-done error:" (ex-message e) (.getClass e))))))

(defn- on-agent-error [cs error-msg]
  "Called when an error occurs during the agent turn."
  (try
    (stop-anim-timer! cs)
    (ui/status-indicator-stop! (:status-indicator cs))
    ;; If streaming placeholder is still empty, remove it
    ;; so we don't get a blank assistant entry before the error message.
    (let [ch (:chat-history cs)
          streaming @(:streaming-atom ch)]
      (if (and streaming
               (empty? @(:text-atom (:component streaming)))
               (empty? @(:thinking-text-atom (:component streaming))))
        (do (ui/chat-history-remove-last! ch)
            (reset! (:streaming-atom ch) nil))
        (do (ui/chat-history-finalize-streaming! ch)
            (ui/chat-history-finalize-thinking! ch))))
    (ui/chat-history-add-message! (:chat-history cs)
      {:role :assistant :content (th/fg th/dark-theme :error (str "Error: " error-msg))})
    (reset! (:running-turn? cs) false)
    (update-header-footer! cs)
    (tui/tui-request-render (:tui cs))
    (debug/log "agent turn error: " error-msg)
    (catch Exception e
      (debug/log "on-agent-error callback: " e)
      (binding [*out* *err*] (println "on-agent-error error:" (ex-message e) (.getClass e))))))

;; ─── Submit handler ────────────────────────────────────────────────────────

(defn- handle-bash-command
  "Execute a ! or !! bash command.
   !! → exclude-from-context (output not sent to LLM)
   !  → normal execution (output goes to LLM context)
   Pi: handleBashCommand() in interactive-mode.ts"
  [cs command exclude-from-context?]
  (debug/log "bash command: " command " (exclude-context: " exclude-from-context? ")")
  
  (if @(:bash-running? cs)
    (do
      (debug/log "bash: already running, ignoring")
      (ui/show-warning! (:chat-history cs)
        "A bash command is already running. Press Escape to cancel it first."))
    (do
      (reset! (:bash-signal cs) false)
      (reset! (:bash-running? cs) true)
      
      ;; Create the UI component
      (let [bash-comp (be/make-bash-execution
                        :command command
                        :exclude-from-context? exclude-from-context?
                        :theme (cfg/get-theme (:config cs)))
            
            ;; ── Build session env (pi: resolveSpawnContext) ─────────────
            ag (:agent-state cs)
            session-env
            (let [m (transient {})]
              (when-let [sess (:session cs)]
                (assoc! m "KMET_SESSION_ID" (:id sess)))
              (assoc! m "KMET_PROVIDER" (name @(:provider ag)))
              (assoc! m "KMET_MODEL" @(:model ag))
              (when-let [tl @(:thinking ag)]
                (when-not (= tl :off)
                  (assoc! m "KMET_REASONING_LEVEL" (name tl))))
              (persistent! m))
            
            ;; ── Emit user-bash event for extensions (pi: emitUserBash) ──
            _ (event-bus/emit-event!
                {:type :user-bash
                 :command command
                 :exclude-from-context? exclude-from-context?
                 :cwd (System/getProperty "user.dir")})
            
            ;; ── Spawn hook (pi: BashSpawnHook) — extensions can modify command ──
            spawn-hook nil]
        
        ;; Add to chat (or pending container if agent is streaming)
        ;; Pi: pendingMessagesContainer sits between chat and footer
        (if @(:running-turn? cs)
          (do
            (container/container-add-child (:pending-bash-container cs) bash-comp)
            (swap! (:pending-bash-components cs) conj bash-comp))
          (ui/chat-history-add-message! (:chat-history cs)
            {:role :bash :command command
             :component bash-comp}))
        
        (update-header-footer! cs)
        (tui/tui-request-render (:tui cs))
        
        ;; Execute in background
        (future
          (try
            (let [result (bash-exec/execute-bash
                           {:command command
                            :cwd (System/getProperty "user.dir")
                            :env session-env
                            :on-chunk (fn [chunk]
                                        (be/bash-execution-append-output! bash-comp chunk)
                                        (tui/tui-request-render (:tui cs)))
                            :signal (:bash-signal cs)
                            :spawn-hook spawn-hook
                            :timeout 300})
                  {:keys [exit-code cancelled truncated full-output-path]} result]
              (debug/log "bash done: exit=" exit-code " cancelled=" cancelled " truncated=" truncated)
              
              ;; Mark complete on component (pi: truncation metadata from the executor)
              (be/bash-execution-set-complete! bash-comp exit-code cancelled
                :truncation (:truncation result)
                :full-output-path full-output-path)
              
              ;; Record in session
              (when-let [sess (:session cs)]
                (session/record-bash-result! sess command result exclude-from-context?))
              
              ;; Move pending bash from pending container to chat (pi: pendingMessagesContainer)
              (when @(:running-turn? cs)
                (let [pending (:pending-bash-components cs)]
                  (when (seq @pending)
                    (doseq [comp @pending]
                      (container/container-remove-child (:pending-bash-container cs) comp)
                      (ui/chat-history-add-message! (:chat-history cs)
                        {:role :bash :command command :component comp}))
                    (reset! pending []))))
              
              (reset! (:bash-running? cs) false)
              (update-header-footer! cs)
              (tui/tui-request-render (:tui cs)))
            
            (catch Exception e
              (let [err-msg (or (ex-message e) "Unknown error")]
                (debug/log "bash command error: " e)
                (be/bash-execution-set-complete! bash-comp nil false)
                (ui/show-error! (:chat-history cs) err-msg)
                (reset! (:bash-running? cs) false)
                (update-header-footer! cs)
                (tui/tui-request-render (:tui cs))))))))))

(defn- handle-submit [cs text]
  (let [trimmed (str/trim text)]
    (when (seq trimmed)
      (cond
        ;; Slash command
        (str/starts-with? trimmed "/")
        (let [space (str/index-of trimmed " ")
              cmd (if (nil? space) (subs trimmed 1) (subs trimmed 1 space))
              args (if (nil? space) "" (str/trim (subs trimmed (inc space))))]
          (handle-command cs cmd args))
        
        ;; Bash command (! or !!)
        (str/starts-with? trimmed "!")
        (let [exclude-from-context? (str/starts-with? trimmed "!!")
              command (str/trim (subs trimmed (if exclude-from-context? 2 1)))]
          (when (seq command)
            (if @(:bash-running? cs)
              (ui/chat-history-add-message! (:chat-history cs)
                {:role :assistant :content "A bash command is already running. Cancel it first."})
              (do
                (editor/editor-push-history! (:editor cs) trimmed)
                (editor/editor-set-text! (:editor cs) "")
                (handle-bash-command cs command exclude-from-context?)))))
        
        ;; Regular message — agent loop handles session persistence.
        ;; Input hooks (pi: input extension event) run first: a hook can
        ;; consume the input ({:action :handled}) or rewrite it
        ;; ({:action :transform :text ...}); :streaming-behavior tells hooks
        ;; whether the agent is running (input will be steered). Slash and
        ;; bash commands are native UI features and bypass the hooks.
        :else
        (let [streaming? @(:running-turn? cs)
              input (extensions/apply-input-hooks trimmed :interactive
                       {:streaming-behavior (when streaming? :steer)})]
          (if (= :handled (:action input))
            ;; Extension consumed the input — no agent run, nothing displayed
            (debug/log "input handled by extension: " trimmed)
            (let [text (if (contains? input :text) (:text input) trimmed)]
              (if streaming?
                ;; Agent running: steer the current run (pi: steeringQueue).
                ;; Finalize the in-progress assistant message first so the steered
                ;; message lands below it; the next turn streams into a new message.
                (do
                  (debug/log "user steered: " text)
                  (ui/chat-history-finalize-streaming! (:chat-history cs))
                  (ui/chat-history-finalize-thinking! (:chat-history cs))
                  (ui/chat-history-add-message! (:chat-history cs)
                    {:role :user :content text})
                  (agent/steer! (:agent-state cs) text)
                  (update-header-footer! cs)
                  (tui/tui-request-render (:tui cs)))
                (do
                  (reset! (:running-turn? cs) true)
                  (ui/status-indicator-start! (:status-indicator cs))
                  (start-anim-timer! cs)
                  (debug/log "user submitted: " text)
                  (ui/chat-history-add-message! (:chat-history cs)
                    {:role :user :content text})
                  ;; Create streaming placeholder for incoming LLM response.
                  (ui/chat-history-start-streaming! (:chat-history cs))
                  (update-header-footer! cs)
                  (tui/tui-request-render (:tui cs))
                  (agent/run-agent-turn (:agent-state cs)
                    {:message text
                     :on-text #(on-agent-text cs %)
                     :on-thinking #(on-agent-thinking cs %)
                     :on-done (fn [_] (on-agent-done cs))
                     :on-error #(on-agent-error cs %)}))))))))))

(defn- handle-cancel [cs]
  "Cancel the current agent turn or bash command."
  (when @(:bash-running? cs)
    (debug/log "bash command cancelled by user")
    (reset! (:bash-signal cs) true)
    (reset! (:bash-running? cs) false)
    (update-header-footer! cs)
    (tui/tui-request-render (:tui cs)))
  (when @(:running-turn? cs)
    (debug/log "agent turn cancelled by user")
    (stop-anim-timer! cs)
    (ui/status-indicator-stop! (:status-indicator cs))
    (agent/cancel-turn (:agent-state cs))
    ;; Remove empty streaming placeholder if present
    (let [ch (:chat-history cs)]
      (when-let [s @(:streaming-atom ch)]
        (if (and (empty? @(:text-atom (:component s)))
                 (empty? @(:thinking-text-atom (:component s))))
          (do (ui/chat-history-remove-last! ch) (reset! (:streaming-atom ch) nil))
          (do (ui/chat-history-finalize-streaming! ch) (ui/chat-history-finalize-thinking! ch)))))
    (ui/chat-history-add-message! (:chat-history cs)
      {:role :assistant :content (th/dim "(cancelled)")})
    (reset! (:running-turn? cs) false)
    (update-header-footer! cs)
    (tui/tui-request-render (:tui cs))))

;; ─── External editor (pi: handleOpenExternalEditor) ────────────────────────

(defn- handle-external-editor
  "Open the current editor content in $EDITOR (default vi).
   Suspends the TUI (terminal restored to normal mode, input reader paused),
   spawns the external editor on a temp file with inherited stdio, reads the
   result back into the editor, then resumes the TUI. pi: handleOpenExternalEditor
   in interactive-mode.ts."
  [cs]
  (let [ed (:editor cs)
        content (editor/editor-get-expanded-text ed)
        tmp-dir (or (System/getenv "TMPDIR")
                    (System/getProperty "java.io.tmpdir")
                    "/tmp")
        _ (fs/create-dirs tmp-dir)
        tmp-file (str (fs/create-temp-file
                        {:prefix "kmet-editor-" :suffix ".md" :dir tmp-dir}))]
    ;; suspend is inside the try so the finally always resumes the TUI
    (try
      (tui/tui-suspend! (:tui cs))
      (spit tmp-file content)
      ;; pi: external editor command — config > VISUAL > EDITOR > nano
      (let [editor-cmd (or (System/getenv "VISUAL")
                           (System/getenv "EDITOR")
                           "nano")
            parts (str/split editor-cmd #"\s+")
            _ (println "Launching external editor: " editor-cmd)
            _ (println "kmet will resume when the editor exits.")
            result (try
                     (let [p (proc/process (concat parts [tmp-file])
                                           {:out :inherit :err :inherit :in :inherit})
                           exit-code (:exit @p)]
                       (if (zero? exit-code) :ok :cancelled))
                     (catch Exception e
                       (debug/log "external editor error: " e)
                       (ui/chat-history-add-message! (:chat-history cs)
                         {:role :assistant
                          :content (str "External editor failed to start: "
                                        (ex-message e))})
                       :error))]
        (when (= result :ok)
          (let [new-content (try (slurp tmp-file) (catch Exception _ nil))]
            (when (and new-content (not= new-content content))
              ;; pi: strip a single trailing newline added by editors
              (let [new-content (if (and (seq new-content)
                                         (str/ends-with? new-content "\n"))
                                  (subs new-content 0 (dec (count new-content)))
                                  new-content)]
                (editor/editor-set-text! ed new-content)
                (debug/log "external editor content: " (pr-str new-content)))))))
      (finally
        (try (fs/delete-if-exists tmp-file) (catch Exception _ nil))
        (tui/tui-resume! (:tui cs))))
    nil))

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

        ;; Initialize keybindings (global singleton for key-hint + input handling)
        _ (let [kmgr (app-kb/make-agent-keybindings-manager)]
          (tui-kb/set-global-keybindings! kmgr)
          (app-kb/set-key-hint-theme-fns!
            #(th/dim %)
            #(th/fg (cfg/get-theme config) :muted %)))

        ;; Components (define before agent state so on-event can reference them)
        hdr (text/make-text "" 1 0)
        sp1 (spacer/make-spacer 1)
        ch (ui/make-chat-history :theme (cfg/get-theme config))
        pending-tool-comp (atom nil)  ;; Pi: component ref for in-place updates
        cs-ref (atom nil)             ;; CoreState, filled after layout (for :status events)

        ;; Agent state
        ag (agent/make-agent-state
             :model model
             :provider provider
             :system system-prompt
             :session session
             :compact-threshold (:compact-threshold config)
             :thinking (:thinking config :off)
             :on-event (fn [evt]
                         (case (:type evt)
                           :tool-execution-start
                           ;; Pi: create pending component once, update in place
                           (let [msg {:role :tool
                                      :name (:tool-name evt)
                                      :args (:args evt {})
                                      :content ""
                                      :is-error false}]
                             (ui/chat-history-finalize-streaming! ch)
                             (let [comp (ui/chat-history-add-message! ch msg)]
                               ;; Wire invalidate → TUI re-render
                               (ui/tool-execution-set-request-render-fn! comp
                                 #(tui/tui-request-render t))
                               ;; Store tool call ID for correlation
                               (ui/tool-execution-set-tool-call-id! comp (:tool-call-id evt))
                               ;; Args are complete when received (kmet: no streaming args)
                               (ui/tool-execution-set-args-complete! comp)
                               ;; Mark execution started so pending bg + timer activate now
                               (ui/tool-execution-mark-execution-started! comp)
                               (reset! pending-tool-comp comp))
                             (tui/tui-request-render t))
                           :tool-execution-update
                           ;; Pi: live partial content from streaming tools (bash),
                           ;; plus periodic pings that update the elapsed timer
                           (do (when-let [comp @pending-tool-comp]
                                 (when-let [content (:content evt)]
                                   (ui/tool-execution-set-content! comp content)))
                               (tui/tui-request-render t))
                           :tool-execution-end
                           ;; Pi: update the existing component in place
                           (when-let [comp @pending-tool-comp]
                             (let [result (:result evt)]
                               (ui/tool-execution-set-content! comp (:content result))
                               (ui/tool-execution-set-error! comp (:is-error result false))
                               (when-let [truncation (:truncation result)]
                                 (ui/tool-execution-set-truncation! comp truncation))
                               (when-let [details (:details result)]
                                 (ui/tool-execution-set-details! comp details))
                               (when-let [images (:images result)]
                                 (ui/tool-execution-set-images! comp images))
                               (reset! pending-tool-comp nil)
                               (tui/tui-request-render t)))
                           :status
                           ;; Pi: agent status (thinking/executing/idle/error) drives the
                           ;; header/footer status text — kept in sync via the :status event
                           ;; so the yellow "● thinking" / "● executing" indicator appears
                           ;; while the agent is working.
                           (do (when-let [cs @cs-ref]
                                 (update-header-footer! cs))
                               (tui/tui-request-render t))
                           :auto-retry-start
                           ;; Clear partial streaming text so the retried stream starts fresh
                           (ui/chat-history-clear-streaming! ch)
                           (tui/tui-request-render t)
                           :context-replaced
                           ;; Rebuild the chat history to mirror the replaced context
                           (ui/chat-history-rebuild! ch (:messages evt))
                           (tui/tui-request-render t)
                           :message-start
                           ;; before-agent-start injected messages (role :info)
                           ;; display as labeled info boxes above the incoming
                           ;; response; user/assistant message-starts are
                           ;; already mirrored by the UI. Content is normalized
                           ;; from text blocks to a string for the info box.
                           (when (= :info (:role (:message evt)))
                             (let [m (:message evt)
                                   text (if (string? (:content m))
                                          (:content m)
                                          (str/join
                                            (for [b (:content m)
                                                  :when (= :text (:type b))]
                                              (:text b))))]
                               (ui/chat-history-insert-before-streaming! ch
                                 (assoc m :content text))
                               (tui/tui-request-render t)))
                           nil)))
        _ (when (seq (:models config))
            ;; Scoped model list for cycle-model! (pi: _scopedModels)
            (agent/set-models! ag (:models config)))
        sp2 (spacer/make-spacer 1)
        ed (tui/make-editor :height 8 :padding-x 0
            :terminal-rows (fn [] (term/rows @(:terminal t)))
            :border-fn (fn [c] (th/dim c)))
        sp3 (spacer/make-spacer 1)
        ftr (ui/make-footer :status "" :n-msgs 0 :theme (cfg/get-theme config))

        ;; Core state (status-indicator filled in after layout)
        cs (map->CoreState {:tui t
                            :agent-state ag
                            :chat-history ch
                            :editor ed
                            :header-text hdr
                            :anim-timer (atom nil)
                            :footer-comp ftr
                            :status-indicator nil
                            :session session
                            :running-turn? (atom false)
                            :config config
                            :pending-tool-comp pending-tool-comp
                            :bash-running? (atom false)
                            :bash-signal (atom false)
                            :pending-bash-components (atom [])
                            :pending-bash-container (container/make-container)})]

    ;; Expose CoreState to the agent on-event handler (for :status events)
    (reset! cs-ref cs)

    ;; Focus editor
    (tui/tui-set-focus t ed)

    ;; Register builtin slash commands (autocomplete dropdown + dispatch)
    (register-builtin-commands! config)

    ;; Autocomplete provider: slash commands + file paths
    (editor/editor-set-autocomplete-provider! ed
      (ac/make-combined-provider
        :commands-fn #(commands/get-commands)
        :base-path (System/getProperty "user.dir")))
    (editor/editor-set-autocomplete-theme! ed (th/get-select-list-theme (cfg/get-theme config)))

    ;; Status indicator (Pi-style: separate layer between chat and editor)
    (let [si (ui/make-status-indicator :theme (cfg/get-theme config))
          cs (assoc cs :status-indicator si)]

      ;; Add components (pending bash container between chat and status indicator)
      ;; Pi: pendingMessagesContainer sits between chatContainer and footer
      (tui/tui-add-child t hdr)
      (tui/tui-add-child t sp1)
      (tui/tui-add-child t ch)
      (tui/tui-add-child t (:pending-bash-container cs))
      (tui/tui-add-child t si)
      (tui/tui-add-child t sp2)
      (tui/tui-add-child t ed)
      (tui/tui-add-child t sp3)
      (tui/tui-add-child t ftr)

      ;; Wire editor submit
      (editor/editor-set-on-submit! ed
        (fn [text]
          (when text
            (handle-submit cs text)
            (editor/editor-set-text! ed "")
            (tui/tui-request-render t))))

      ;; Editor actions (pi: CustomEditor.onAction) — app keybindings dispatched
      ;; through the editor's action system, which also checks the autocomplete
      ;; dropdown state (e.g. escape closes the dropdown instead of cancelling)
      (editor/editor-set-on-action! ed "app.interrupt"
        ;; pi: onEscape — abort the running agent turn or bash command
        (fn [] (handle-cancel cs)))
      (editor/editor-set-on-action! ed "app.exit"
        (fn [] (tui/tui-stop t)))
      ;; pi: handleCtrlC — single ctrl+c clears the editor, double within
      ;; 500ms quits
      (let [last-ctrl-c (atom 0)]
        (editor/editor-set-on-action! ed "app.clear"
          (fn []
            (let [now (System/currentTimeMillis)]
              (if (< (- now @last-ctrl-c) 500)
                (tui/tui-stop t)
                (do (reset! last-ctrl-c now)
                    (editor/editor-set-text! ed "")
                    (tui/tui-request-render t)))))))
      (editor/editor-set-on-action! ed "app.tools.expand"
        (fn []
          ;; pi: showStatus feedback on toggle
          (let [expanded? (ui/chat-history-toggle-tool-expanded! ch)]
            (ui/chat-history-show-status! ch
              (str "Tool output: " (if expanded? "expanded" "collapsed")))
            (update-header-footer! cs)
            (tui/tui-request-render t))))
      (editor/editor-set-on-action! ed "app.thinking.toggle"
        (fn []
          ;; pi: showStatus feedback on toggle
          (let [hidden? (ui/chat-history-toggle-thinking-hidden! ch)]
            (ui/chat-history-show-status! ch
              (str "Thinking blocks: " (if hidden? "hidden" "visible")))
            (update-header-footer! cs)
            (tui/tui-request-render t))))
      (editor/editor-set-on-action! ed "app.editor.external"
        (fn [] (handle-external-editor cs)))

      ;; Global input listeners — only truly global keys stay here (pi: keep
      ;; app actions in the editor; the TUI keeps only global keys)
      (tui/tui-add-input-listener t
        (fn [data]
          (cond
            (keys/matches-key? data (keys/ctrl "l"))
            (do (term/clear-screen! @(:terminal t))
                (tui/tui-request-render t))
            :else nil)))

      ;; Initialize header/footer
      (text/text-set! hdr (fmt-header cs))
      (ui/footer-set-status! ftr (fmt-status-str cs))

      ;; Pi-style info message on top (expandable with ctrl+o, pi: builtInHeader)
      (let [kmgr (tui-kb/get-global-keybindings)
            expand-key (or (tui-kb/key-text kmgr "app.tools.expand") "Ctrl+O")
            thinking-key (or (tui-kb/key-text kmgr "app.thinking.toggle") "Ctrl+T")
            compact (str "Welcome to " (th/bold "kmet") " — minimal coding agent.\n"
                         (th/dim (str "Press " expand-key " to show full startup help.")))
            full (str "Welcome to " (th/bold "kmet") " — minimal coding agent.\n\n"
                      "Shortcuts:\n"
                      "  " (th/dim "Enter") "      — submit message\n"
                      "  " (th/dim "Escape") "     — cancel current turn / bash\n"
                      "  " (th/dim "Ctrl+C") "     — clear editor (twice to quit)\n"
                      "  " (th/dim "Ctrl+D") "     — exit when editor is empty\n"
                      "  " (th/dim "Ctrl+G") "     — open external editor\n"
                      "  " (th/dim expand-key) "     — toggle tool output\n"
                      "  " (th/dim thinking-key) "     — toggle thinking blocks\n"
                      "  " (th/dim "Ctrl+P") "     — cycle to next model\n"
                      "  " (th/dim "Ctrl+L") "     — clear terminal\n"
                      "  " (th/dim "Alt+Enter") "   — queue follow-up message\n"
                      "  " (th/dim "/") " — commands   " (th/dim "!") " — bash   "
                      "  " (th/dim "!!") " — bash (no context)\n\n"
                      (th/dim "Type a message, or use /help for all commands."))]
        (ui/chat-history-set-info-msg! ch
          {:label "kmet"
           :content compact
           :collapsed-content compact
           :expanded-content full}))

      cs)))

;; ─── Run ───────────────────────────────────────────────────────────────────

(defn run
  "Start the interactive TUI with the given config and CLI opts.
   Loads extensions, resolves the session (:resume/:continue/new), builds the
   layout, and runs the TUI loop until quit. Cleans up the TUI and tracked
   child processes on error, then rethrows for the top-level handler.
   pi: cli.js dispatch to interactive mode."
  [config opts]
  (let [tui-ref (atom nil)]
    (try
      ;; Load extensions (pi: extension discovery)
      (let [ext-dir (cfg/expand-path (:extensions-dir config))]
        (extensions/load-extensions-from-dir ext-dir))

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
        (process/kill-tracked-children!)
        (println "kmet session ended.")
        (:tui cs))
      (catch Exception e
        ;; Restore terminal if TUI was started, then rethrow for -main
        (process/kill-tracked-children!)
        (when-let [t @tui-ref]
          (try (tui/tui-stop t) (catch Exception _)))
        (throw e)))))
