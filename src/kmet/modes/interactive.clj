(ns kmet.modes.interactive
  "Interactive TUI mode — main layout, agent integration, command handling,
   session browsing, bash commands, external editor.
   pi: modes/interactive/interactive-mode.ts."
  (:require [kmet.tui.core :as tui]
            [kmet.tui.protocols :as protocols]
            [kmet.tui.terminal :as term]
            [kmet.tui.keys :as keys]
            [kmet.tui.theme :as th]
            [kmet.tui.components.text :as text]
            [kmet.tui.components.spacer :as spacer]
            [kmet.tui.components.editor :as editor]
            [kmet.tui.components.expandable-text :as expandable-text]
            [kmet.tui.components.container :as container]
            [kmet.app.ui :as ui]
            [kmet.app.ui.footer-data-provider :as fdp]
            [kmet.app.theme-controller :as theme-ctrl]
            [kmet.tui.components.select-list :as select-list]
            [kmet.app.loop :as agent]
            [kmet.app.session :as session]
            [kmet.app.tools.core :as tools]
            [kmet.app.keybindings :as app-kb]
            [kmet.tui.keybindings :as tui-kb]
            [kmet.config :as cfg]
            [kmet.app.skills :as skills]
            [kmet.app.context :as context]
            [kmet.app.prompts :as prompts]
            [kmet.app.commands :as commands]
            [kmet.app.extensions :as extensions]
            [kmet.app.event-bus :as event-bus]
            [kmet.tui.autocomplete :as ac]
            [kmet.debug :as debug]
            [clojure.string :as str]
            [babashka.fs :as fs]
            [babashka.process :as proc]
            [kmet.app.bash-executor :as bash-exec]
            [kmet.app.tools.bash :as bash-tool]
            [kmet.app.ui.bash-execution :as be]
            [kmet.app.ui.extension-dialogs :as dialogs]
            [kmet.tui.components.spinner :as spinner]
            [kmet.libs.process :as process]))

(declare resume-session show-session-tree build-extension-ui-registry
         editor-text-get editor-text-set! editor-text-get-expanded
         build-loaded-resource-sections)

;; ─── Global config ref ────────────────────────────────────────────────────

(defonce ^:private global-config (atom nil))

;; ─── Session helpers ───────────────────────────────────────────────────────

(defn- get-session-dir []
  (if-let [c @global-config]
    (cfg/get-session-dir c)
    (str (System/getProperty "user.home") "/.kmet/sessions")))

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
                      current-editor-atom
                      header-comp
                      loaded-resources-comp
                      anim-timer
                      footer-comp
                      footer-provider
                      status-indicator
                      status-container
                      pending-messages-comp
                      session-atom
                      running-turn?
                      config
                      pending-tool-comp
                      bash-running?
                      bash-signal
                      pending-bash-components
                      pending-messages-container
                      theme-controller])

;; ─── Formatting helpers ────────────────────────────────────────────────────

(defn- fmt-model [provider model]
  (str (name provider) ":" model))

(defn- fmt-key-hint
  "Pi: keyHint — dim key + muted description, from the live keybindings."
  [id desc]
  (app-kb/key-hint id desc))

(defn- fmt-raw-hint
  "Pi: rawKeyHint — dim literal key text + muted description."
  [key desc]
  (str (th/dim key) (th/fg (th/get-current-theme) :muted (str " " desc))))

(defn- fmt-header-logo
  "Pi: logo — bold accent app name."
  []
  (th/bold (th/fg (th/get-current-theme) :accent "kmet")))

(defn- fmt-header-compact
  "Compact welcome header (pi: compactInstructions + compactOnboarding)."
  []
  (let [expand-key (or (app-kb/key-text "app.tools.expand") "Ctrl+O")
        compact-instructions
        (str/join (th/dim " · ")
                  [(fmt-key-hint "app.interrupt" "interrupt")
                   (fmt-raw-hint (str (or (app-kb/key-text "app.clear") "Ctrl+C")
                                      "/" (or (app-kb/key-text "app.exit") "Ctrl+D"))
                                 "clear/exit")
                   (fmt-raw-hint "/" "commands")
                   (fmt-raw-hint "!" "bash")
                   (fmt-key-hint "app.tools.expand" "more")])
        compact-onboarding (th/dim (str "Press " expand-key " to show full startup help and loaded resources."))]
    (str (fmt-header-logo) "\n" compact-instructions "\n" compact-onboarding)))

(defn- fmt-header-full
  "Full welcome header (pi: expandedInstructions)."
  []
  (let [clear-key (or (app-kb/key-text "app.clear") "Ctrl+C")
        expanded-instructions
        (str/join "\n"
                  [(fmt-key-hint "app.interrupt" "to interrupt")
                   (fmt-key-hint "app.clear" "to clear")
                   (fmt-raw-hint (str clear-key " twice") "to exit")
                   (fmt-key-hint "app.exit" "to exit (empty)")
                   (fmt-key-hint "app.thinking.cycle" "to cycle thinking level")
                   (fmt-key-hint "app.model.cycleForward" "to cycle models")
                   (fmt-key-hint "app.model.select" "to select model")
                   (fmt-key-hint "app.tools.expand" "to expand tools")
                   (fmt-key-hint "app.thinking.toggle" "to expand thinking")
                   (fmt-key-hint "app.editor.external" "for external editor")
                   (fmt-raw-hint "/" "for commands")
                   (fmt-raw-hint "!" "to run bash")
                   (fmt-raw-hint "!!" "to run bash (no context)")
                   (fmt-key-hint "app.message.followUp" "to queue follow-up")
                   (fmt-key-hint "app.message.dequeue" "to edit all queued messages")])
        onboarding (th/dim "kmet can explain its own features. Ask it how to use or extend kmet.")]
    (str (fmt-header-logo) "\n" expanded-instructions "\n\n" onboarding)))

(defn- update-footer!
  "Sync the footer's data source (session) and request a re-render. The
   header is static (ExpandableText welcome — rebuilt on theme change by the
   theme callback); the footer reads the agent's model/provider/thinking
   atoms live, so only session changes need wiring here."
  [cs]
  (ui/fdp-set-session! (:footer-provider cs) @(:session-atom cs))
  ;; Provider atoms are read inside helper fns — not lexically tracked by
  ;; track! — so invalidate explicitly on every sync.
  (protocols/invalidate (:footer-comp cs))
  (tui/tui-request-render (:tui cs))
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

(declare handle-reload)

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
                 (reset! (:session-atom cs) new-session)
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
   {:name "reload"
    :description "Reload keybindings, extensions, skills, prompts, themes, and context files"
    :handler handle-reload})
  (commands/register-command!
   {:name "compact"
    :description "Manually compact the session context"
    :argument-hint "<instructions>"
    :handler (fn [cs args]
               (let [{:keys [agent-state chat-history]} cs
                     instructions (when (seq args) args)]
                 (cond
                   (not= :idle (agent/get-status agent-state))
                   (ui/chat-history-add-message! chat-history
                                                 {:role :info :label "Compact"
                                                  :content "Wait for the current response to finish before compacting."})

                   @(:compacting? agent-state)
                   (ui/chat-history-add-message! chat-history
                                                 {:role :info :label "Compact"
                                                  :content "Compaction already in progress."})

                   :else
                   ;; Runs on a future so the input thread stays live and
                   ;; escape can cancel the compaction (pi: session.compact
                   ;; is async). The :compaction-end event reports an
                   ;; aborted compaction; only the result replies go here.
                   (future
                     (let [result (agent/compact-context! agent-state instructions)]
                       (when-not (= :aborted result)
                         (ui/chat-history-add-message!
                          chat-history
                          {:role :info :label "Compact"
                           :content (if result
                                      "Session compacted."
                                      "Nothing to compact (session too small).")})))))))})
  (commands/register-command!
   {:name "theme"
    :description "Switch theme"
    :argument-hint "<name>"
    :get-argument-completions
    (fn [_]
      (mapv (fn [t] {:value t :label t})
            (sort (keys (th/get-all-themes)))))
    :handler (fn [cs args]
               (let [tc (:theme-controller cs)]
                 (if (seq args)
                   (let [name (first args)
                         result (theme-ctrl/set-theme-name! tc name)]
                     (ui/chat-history-add-message! (:chat-history cs)
                                                   {:role :assistant
                                                    :content (if (:success result)
                                                               (str "Switched to theme \"" name "\".")
                                                               (str "Failed to load theme \"" name "\": "
                                                                    (:error result)))}))
                   (ui/chat-history-add-message! (:chat-history cs)
                                                 {:role :assistant
                                                  :content (str "Current theme: "
                                                                (theme-ctrl/get-active-theme-name tc)
                                                                "\nAvailable themes: "
                                                                (str/join ", " (sort (keys (th/get-all-themes))))
                                                                "\nUsage: /theme <name>")}))))}))

(defn- command-not-implemented
  "In-chat reply for pi slash commands kmet does not implement yet."
  [cs name]
  (ui/chat-history-add-message! (:chat-history cs)
                                {:role :assistant
                                 :content (str "Command /" name " is not implemented in kmet yet.")}))

(defn- handle-reload
  "Reload settings, extensions, skills, prompts, themes, context files, and
   rebuild the system prompt (pi: interactive-mode handleReloadCommand →
   session.reload → _rebuildSystemPrompt). Refuses while the agent is
   running (pi warns to wait for the current response)."
  [cs _]
  (let [{:keys [agent-state chat-history]} cs]
    (if-not (= :idle (agent/get-status agent-state))
      (ui/chat-history-add-message! chat-history
                                    {:role :info :label "Reload"
                                     :content "Wait for the current response to finish before reloading."})
      (try
        ;; pi: settingsManager.reload() + theme re-registration
        (let [config (cfg/init!)
        ;; pi: session.reload → extension shutdown/start
              _ (extensions/ui-reset!)
              _ (extensions/clear-extensions!)
              _ (doseq [d (cfg/resource-dirs config :extensions-dir ".kmet/extensions")]
                  (extensions/load-extensions-from-dir d))
              ;; pi: resourceLoader.reload (skills, prompts)
              _ (skills/clear-skills!)
              _ (doseq [d (cfg/resource-dirs config :skills-dir ".kmet/skills")]
                  (skills/load-skills-from-dir d))
              _ (prompts/clear-prompt-templates!)
              _ (doseq [d (cfg/resource-dirs config :prompts-dir ".kmet/prompts")]
                  (prompts/load-prompt-templates-from-dir d))
              ;; pi: _rebuildSystemPrompt with new sources
              system-prompt (skills/build-system-prompt
                             :custom-prompt (cfg/get-custom-prompt config)
                             :append-prompt (cfg/get-append-system-prompt config)
                             :context-files (context/load-project-context-files
                                             (cfg/get-agent-dir) (str (fs/cwd))))]
          (reset! global-config config)
          (theme-ctrl/set-config! (:theme-controller cs) config)
          (agent/set-system-prompt! agent-state system-prompt)
          (ui/loaded-resources-set-sections!
           (:loaded-resources-comp cs) (build-loaded-resource-sections))
          (update-footer! cs)
          ;; pi: reload re-emits session_start so extensions re-register UI.
          ;; Runs on a future — handlers may block on dialog promises, which
          ;; must never happen on the input thread.
          (future
            (try (event-bus/emit-event! {:type :session-start :reason :reload})
                 (catch Exception e (debug/log "session-start: " e))))
          (ui/chat-history-add-message! chat-history
                                        {:role :info :label "Reload"
                                         :content "Reloaded keybindings, extensions, skills, prompts, themes, and context files."}))
        (catch Exception e
          (ui/chat-history-add-message! chat-history
                                        {:role :info :label "Reload"
                                         :content (str "Reload failed: " (ex-message e))}))))))

(defn- register-not-implemented-commands!
  "Register pi's builtin slash commands that kmet does not implement yet,
   all bound to the command-not-implemented handler. Keeps the command list
   in sync with pi (packages/coding-agent/src/core/slash-commands.ts) so
   /help and autocomplete show the full surface."
  []
  (doseq [{:keys [name description argument-hint]}
          [{:name "settings" :description "Open settings menu"}
           {:name "scoped-models" :description "Enable/disable models for Ctrl+P cycling"}
           {:name "export" :description "Export session (HTML default, or specify path: .html/.jsonl)"
            :argument-hint "<path>"}
           {:name "import" :description "Import and resume a session from a JSONL file"}
           {:name "share" :description "Share session as a secret GitHub gist"}
           {:name "copy" :description "Copy last agent message to clipboard"}
           {:name "name" :description "Set session display name"}
           {:name "session" :description "Show session info and stats"}
           {:name "changelog" :description "Show changelog entries"}
           {:name "hotkeys" :description "Show all keyboard shortcuts"}
           {:name "fork" :description "Create a new fork from a previous user message"}
           {:name "clone" :description "Duplicate the current session at the current position"}
           {:name "trust" :description "Save project trust decision for future sessions"}
           {:name "login" :description "Configure provider authentication"
            :argument-hint "<provider>"}
           {:name "logout" :description "Remove provider authentication"}]]
    (commands/register-command!
     {:name name
      :description description
      :argument-hint argument-hint
      :handler (fn [cs _]
                 (command-not-implemented cs name))})))

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
                               (reset! (:session-atom cs) sess)
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
                               (update-footer! cs)
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
  (let [sess @(:session-atom cs)]
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
                items (vec (flatten-tree tree 0))
                sl-ref (atom nil)
                on-select-fn (fn []
                               (when-let [sel (select-list/select-list-get-selected @sl-ref)]
                                 (let [entry (:entry sel)
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
                                   (update-footer! cs)
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
            (tui/tui-request-render (:tui cs))))))))

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

;; ─── Status indicator swap model (pi: showStatusIndicator/clearStatusIndicator) ──
;; The status container holds one indicator at a time. The default child is
;; the working StatusIndicator (renders the idle two rows when inactive);
;; retry/compaction indicators are transient swaps. All indicators render
;; the same two-row shape so the editor and footer never jump.

(defn- show-status-indicator!
  "Replace the status container child with the given indicator (pi:
   showStatusIndicator — disposes the active indicator)."
  [cs indicator]
  (ui/status-indicator-stop! (:status-indicator cs))
  (container/container-clear (:status-container cs))
  (container/container-add-child (:status-container cs) indicator)
  (tui/tui-request-render (:tui cs)))

(defn- clear-status-indicator!
  "Restore the idle two-row status (pi: clearStatusIndicator → idleStatus)."
  [cs]
  (container/container-clear (:status-container cs))
  (container/container-add-child (:status-container cs) (:status-indicator cs))
  (ui/status-indicator-stop! (:status-indicator cs))
  (tui/tui-request-render (:tui cs)))

;; ─── Pending messages display (pi: updatePendingMessagesDisplay) ──────────

(defn- fmt-key-display
  "Pi: formatKeyText capitalize — 'alt+up' → 'Alt+Up'."
  [k]
  (->> (str/split (or k "") #"\+")
       (map (fn [part]
              (if (seq part)
                (str (str/upper-case (subs part 0 1)) (subs part 1))
                part)))
       (str/join "+")))

(defn- update-pending-messages!
  "Refresh the queued steering/follow-up display (pi:
   updatePendingMessagesDisplay)."
  [cs]
  (let [{:keys [steering follow-up]} (agent/queued-messages (:agent-state cs))]
    (ui/pending-messages-set-queues! (:pending-messages-comp cs)
                                     steering follow-up))
  (tui/tui-request-render (:tui cs)))

(defn- compaction-status-message
  "Pi: CompactionStatusIndicator label — reason-specific, with the cancel
   hint (escape aborts compaction)."
  [reason]
  (let [cancel (str " (" (fmt-key-display (app-kb/key-text "app.interrupt"))
                    " to cancel)")]
    (case reason
      :manual (str "Compacting context..." cancel)
      :overflow (str "Context overflow detected, auto-compacting..." cancel)
      (str "Auto-compacting..." cancel))))

;; ─── Agent response handler ────────────────────────────────────────────────

(defn- on-agent-text
  "Called for each text delta from the LLM during streaming."
  [cs text]
  (try
    (ui/chat-history-append-streaming-text! (:chat-history cs) text)
    (tui/tui-request-render (:tui cs))
    (catch Exception e
      (debug/log "on-agent-text callback: " e)
      (binding [*out* *err*] (println "on-agent-text error:" (ex-message e) (.getClass e))))))

(defn- on-agent-thinking
  "Called for each thinking/reasoning delta from the LLM during streaming."
  [cs text]
  (try
    (ui/chat-history-append-thinking-text! (:chat-history cs) text)
    (tui/tui-request-render (:tui cs))
    (catch Exception e
      (debug/log "on-agent-thinking callback: " e)
      (binding [*out* *err*] (println "on-agent-thinking error:" (ex-message e) (.getClass e))))))

(defn- on-agent-done
  "Called when the LLM turn completes.
   Finalize streaming FIRST (captures thinking text), then clear thinking.
   Session persistence is handled by the agent loop internally."
  [cs]
  (try
    (stop-anim-timer! cs)
    (clear-status-indicator! cs)
    (ui/chat-history-finalize-streaming! (:chat-history cs))
    (ui/chat-history-finalize-thinking! (:chat-history cs))
    (reset! (:running-turn? cs) false)
    (update-footer! cs)
    (tui/tui-request-render (:tui cs))
    (debug/log "agent turn completed")
    (catch Exception e
      (debug/log "on-agent-done callback: " e)
      (binding [*out* *err*] (println "on-agent-done error:" (ex-message e) (.getClass e))))))

(defn- on-agent-error
  "Called when an error occurs during the agent turn."
  [cs error-msg]
  (try
    (stop-anim-timer! cs)
    (clear-status-indicator! cs)
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
    (update-footer! cs)
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
                       :theme (th/get-current-theme))

            ;; ── Build session env (pi: resolveSpawnContext) ─────────────
            ag (:agent-state cs)
            session-env
            (let [tl @(:thinking ag)]
              (cond-> {"KMET_PROVIDER" (name @(:provider ag))
                       "KMET_MODEL" @(:model ag)}
                (:session-atom cs) (assoc "KMET_SESSION_ID" (:id @(:session-atom cs)))
                (and tl (not= tl :off)) (assoc "KMET_REASONING_LEVEL" (name tl))))

            ;; ── Emit user-bash event for extensions (pi: emitUserBash) ──
            ;; Bind the ! cancel signal so extension handlers reacting to
            ;; user-bash can run cancellable bash via execute-tool.
            _ (binding [bash-tool/*cancel-signal* (:bash-signal cs)]
                (event-bus/emit-event!
                 {:type :user-bash
                  :command command
                  :exclude-from-context? exclude-from-context?
                  :cwd (System/getProperty "user.dir")}))

            ;; ── Spawn hook (pi: BashSpawnHook) — extensions can modify command ──
            spawn-hook nil]

        ;; Add to chat (or pending container if agent is streaming)
        ;; Pi: pendingMessagesContainer sits between chat and footer
        (if @(:running-turn? cs)
          (do
            (container/container-add-child (:pending-messages-container cs) bash-comp)
            (swap! (:pending-bash-components cs) conj bash-comp))
          (ui/chat-history-add-message! (:chat-history cs)
                                        {:role :bash :command command
                                         :component bash-comp}))

        (update-footer! cs)
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
              (when-let [sess @(:session-atom cs)]
                (session/record-bash-result! sess command result exclude-from-context?))

              ;; Move pending bash from pending container to chat (pi: pendingMessagesContainer)
              (when @(:running-turn? cs)
                (let [pending (:pending-bash-components cs)]
                  (when (seq @pending)
                    (doseq [comp @pending]
                      (container/container-remove-child (:pending-messages-container cs) comp)
                      (ui/chat-history-add-message! (:chat-history cs)
                                                    {:role :bash :command command :component comp}))
                    (reset! pending []))))

              (reset! (:bash-running? cs) false)
              (update-footer! cs)
              (tui/tui-request-render (:tui cs)))

            (catch Exception e
              (let [err-msg (or (ex-message e) "Unknown error")]
                (debug/log "bash command error: " e)
                (be/bash-execution-set-complete! bash-comp nil false)
                (ui/show-error! (:chat-history cs) err-msg)
                (reset! (:bash-running? cs) false)
                (update-footer! cs)
                (tui/tui-request-render (:tui cs))))))))))

;; ─── Message submission (pi: session.prompt input event + agent run) ──────

(defn- send-message
  "Send text to the agent: steer while streaming, else start a new turn.
   Input hooks must already have been applied (pi: agent run happens after
   hook/expansion processing). Returns nil."
  [cs text]
  (if @(:running-turn? cs)
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
      (update-footer! cs)
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
      (update-footer! cs)
      (tui/tui-request-render (:tui cs))
      (agent/run-agent-turn (:agent-state cs)
                            {:message text
                             :on-text #(on-agent-text cs %)
                             :on-thinking #(on-agent-thinking cs %)
                             :on-done (fn [_] (on-agent-done cs))
                             :on-error #(on-agent-error cs %)}))))

(defn- apply-hooks
  "Run extension input hooks on text; returns the (possibly transformed)
   text, or nil when a hook consumed the input (pi: session.prompt input
   event)."
  [cs text]
  (let [input (extensions/apply-input-hooks text :interactive
                                            {:streaming-behavior (when @(:running-turn? cs) :steer)})]
    (if (= :handled (:action input))
      (do (debug/log "input handled by extension: " text) nil)
      (if (contains? input :text) (:text input) text))))

(defn- submit-message
  "Run input hooks on text, then send it to the agent (pi: session.prompt —
   input event, then agent run). Returns nil."
  [cs text]
  (when-let [text (apply-hooks cs text)]
    (send-message cs text)))

(defn- handle-submit [cs text]
  (let [trimmed (str/trim text)]
    (when (seq trimmed)
      (cond
        ;; Slash command; else skill command (/skill:name), prompt template
        ;; (/name), or fall through to the agent (pi: commands dispatch
        ;; first, then skill/template expansion)
        (str/starts-with? trimmed "/")
        (let [space (str/index-of trimmed " ")
              cmd (if (nil? space) (subs trimmed 1) (subs trimmed 1 space))
              args (if (nil? space) "" (str/trim (subs trimmed (inc space))))]
          (if-let [c (commands/find-command cmd)]
            (do ((:handler c) cs args)
                (update-footer! cs))
            ;; pi: input hooks → skill command → prompt template → fall
            ;; through to the agent (unknown /cmd is sent as a message)
            (when-let [text (apply-hooks cs trimmed)]
              (send-message cs
                            (-> text
                                (skills/expand-skill-command)
                                (prompts/expand-prompt-template (prompts/get-prompt-templates)))))))

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
        (submit-message cs trimmed)))))

(defn- handle-follow-up
  "Pi: handleFollowUp — Alt+Enter. While the agent is running, queue the
   editor text as a follow-up (processed after the run settles); when idle,
   submit like regular Enter."
  [cs]
  (let [ed @(:current-editor-atom cs)
        text (str/trim (editor-text-get ed))]
    (when (seq text)
      (editor/editor-push-history! ed text)
      (editor-text-set! ed "")
      (if @(:running-turn? cs)
        (do (agent/follow-up! (:agent-state cs) text)
            (ui/chat-history-add-message! (:chat-history cs)
                                          {:role :user :content text})
            (update-pending-messages! cs))
        (handle-submit cs text))
      (tui/tui-request-render (:tui cs)))))

(defn- handle-dequeue
  "Pi: handleDequeue — Alt+Up. Restore all queued steering/follow-up
   messages to the editor, combined with the current text."
  [cs]
  (let [{:keys [steering follow-up]} (agent/queued-messages (:agent-state cs))
        all (into (vec steering) follow-up)]
    (if (seq all)
      (let [ed @(:current-editor-atom cs)
            current (editor-text-get ed)
            queued-text (str/join "\n\n" all)
            combined (str/join "\n\n" (remove str/blank? [queued-text current]))]
        (agent/clear-queues! (:agent-state cs))
        (editor-text-set! ed combined)
        (ui/chat-history-show-status!
         (:chat-history cs)
         (str "Restored " (count all) " queued message"
              (when (> (count all) 1) "s") " to editor")))
      (ui/chat-history-show-status! (:chat-history cs)
                                    "No queued messages to restore"))
    (tui/tui-request-render (:tui cs))))

(defn- handle-cancel
  "Cancel the current agent turn, bash command, or in-progress compaction."
  [cs]
  (when @(:compacting? (:agent-state cs))
    ;; Escape during compaction aborts the summarization (pi: onEscape →
    ;; abortCompaction). The compaction-end event clears the indicator and
    ;; reports the cancellation.
    (debug/log "compaction cancelled by user")
    (reset! (:signal (:agent-state cs)) true)
    (tui/tui-request-render (:tui cs)))
  (when @(:bash-running? cs)
    (debug/log "bash command cancelled by user")
    (reset! (:bash-signal cs) true)
    (reset! (:bash-running? cs) false)
    (update-footer! cs)
    (tui/tui-request-render (:tui cs)))
  (when @(:running-turn? cs)
    (debug/log "agent turn cancelled by user")
    (stop-anim-timer! cs)
    (clear-status-indicator! cs)
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
    (update-footer! cs)
    (tui/tui-request-render (:tui cs))))

;; ─── External editor (pi: handleOpenExternalEditor) ────────────────────────

(defn- handle-external-editor
  "Open the current editor content in $EDITOR (default vi).
   Suspends the TUI (terminal restored to normal mode, input reader paused),
   spawns the external editor on a temp file with inherited stdio, reads the
   result back into the editor, then resumes the TUI. pi: handleOpenExternalEditor
   in interactive-mode.ts."
  [cs]
  (let [content (editor-text-get-expanded @(:current-editor-atom cs))
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
                (editor-text-set! @(:current-editor-atom cs) new-content)
                (debug/log "external editor content: " (pr-str new-content)))))))
      (finally
        (try (fs/delete-if-exists tmp-file) (catch Exception _ nil))
        (tui/tui-resume! (:tui cs))))
    nil))

;; ─── Loaded resources (pi: showLoadedResources) ────────────────────────────

(defn- display-path
  "Home-relative display path for a loaded resource (pi: formatDisplayPath)."
  [p]
  (let [p (str p)
        home (System/getProperty "user.home")]
    (if (and (seq home) (str/starts-with? p home))
      (str "~" (subs p (count home)))
      p)))

(defn- build-loaded-resource-sections
  "Pi: showLoadedResources — one section per loaded resource group (Context,
   Skills, Prompts, Extensions). Sections carry raw items; styling happens
   in the LoadedResources component render."
  []
  (let [context-files (context/load-project-context-files
                       (cfg/get-agent-dir) (str (fs/cwd)))
        skills (skills/get-skills)
        templates (prompts/get-prompt-templates)
        extensions (extensions/get-loaded-extensions)
        sections (cond-> []
                   (seq context-files)
                   (conj {:name "Context"
                          :items (mapv (comp display-path :path) context-files)
                          :expanded-items (mapv #(str "  " (display-path (:path %))) context-files)})
                   (seq skills)
                   (conj {:name "Skills"
                          :items (mapv :name skills)
                          :expanded-items (mapv (fn [s]
                                                  (str "  " (or (:file-path s) (:name s))))
                                                skills)})
                   (seq templates)
                   (conj {:name "Prompts"
                          :items (mapv #(str "/" (:name %)) templates)
                          :expanded-items (mapv (fn [t] (str "  /" (:name t))) templates)})
                   (seq extensions)
                   (conj {:name "Extensions"
                          :items (mapv :name extensions)
                          :expanded-items (mapv (fn [e]
                                                  (str "  " (display-path (:file e))))
                                                extensions)}))]
    sections))

;; ─── Layout setup ──────────────────────────────────────────────────────────

(defn- build-layout
  "Create TUI layout and return CoreState."
  [config session]
  (let [jline-term (term/create-terminal)
        t (tui/create-tui jline-term)

        ;; Resolve model and provider from config
        provider (cfg/get-provider config)
        model (cfg/get-model config)

        ;; Load skills and prompt templates (pi: global + project + explicit
        ;; paths load simultaneously)
        _ (doseq [d (cfg/resource-dirs config :skills-dir ".kmet/skills")]
            (skills/load-skills-from-dir d))
        _ (doseq [d (cfg/resource-dirs config :prompts-dir ".kmet/prompts")]
            (prompts/load-prompt-templates-from-dir d))
        system-prompt (skills/build-system-prompt
                       :custom-prompt (cfg/get-custom-prompt config)
                       :append-prompt (cfg/get-append-system-prompt config)
                       :context-files (context/load-project-context-files
                                       (cfg/get-agent-dir) (str (fs/cwd))))

        ;; Initialize keybindings (global singleton for key-hint + input handling)
        _ (let [kmgr (app-kb/make-agent-keybindings-manager)]
            (tui-kb/set-global-keybindings! kmgr)
            (app-kb/set-key-hint-theme-fns!
             #(th/dim %)
             #(th/fg (cfg/get-theme config) :muted %)))

        ;; Components (define before agent state so on-event can reference them)
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
            :compact-token-threshold (:compact-token-threshold config)
            :keep-recent-tokens (or (:keep-recent-tokens config) 20000)
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
                           ;; Pi: agent status changes keep the footer/status
                           ;; layer in sync via the :status event
                          (do (when-let [cs @cs-ref]
                                (update-footer! cs))
                              (tui/tui-request-render t))
                          :queue-update
                           ;; Queued steering/follow-up messages changed (pi:
                           ;; queue_update → updatePendingMessagesDisplay)
                          (do (when-let [cs @cs-ref]
                                (update-pending-messages! cs))
                              (tui/tui-request-render t))
                          :auto-retry-start
                           ;; Clear partial streaming text so the retried stream
                           ;; starts fresh, and show the retry countdown (pi:
                           ;; auto_retry_start → RetryStatusIndicator)
                          (do (ui/chat-history-clear-streaming! ch)
                              (when-let [cs @cs-ref]
                                (show-status-indicator!
                                 cs
                                 (ui/make-retry-status-indicator
                                  (:attempt evt) (:max-attempts evt) (:delay-ms evt)
                                  :cancel-hint (fmt-key-display
                                                (app-kb/key-text "app.interrupt")))))
                              (tui/tui-request-render t))
                          :auto-retry-end
                           ;; Retry finished — restore the idle status (pi:
                           ;; auto_retry_end → clearStatusIndicator("retry"))
                          (do (when-let [cs @cs-ref]
                                (clear-status-indicator! cs))
                              (tui/tui-request-render t))
                          :compaction-start
                           ;; Session compaction in progress (pi:
                           ;; compaction_start → CompactionStatusIndicator);
                           ;; the hint is truthful — escape aborts it
                          (do (when-let [cs @cs-ref]
                                (show-status-indicator!
                                 cs (ui/make-compaction-status-indicator
                                     :message (compaction-status-message
                                               (:reason evt)))))
                              (tui/tui-request-render t))
                          :compaction-end
                           ;; Compaction done — restore the idle status (pi:
                           ;; compaction_end → clearStatusIndicator). For the
                           ;; manual path the /compact future skips its reply
                           ;; on abort, so the status is the only feedback;
                           ;; an in-loop abort is already reported by the
                           ;; full turn cancel ("(cancelled)") — no double
                           ;; report.
                          (do (when-let [cs @cs-ref]
                                (clear-status-indicator! cs)
                                (when (and (:aborted evt)
                                           (= :manual (:reason evt)))
                                  (ui/chat-history-show-status!
                                   ch "Compaction cancelled")))
                              (tui/tui-request-render t))
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
        ;; B.1: welcome header — ExpandableText with compact/full variants
        ;; (pi: builtInHeader), toggled by app.tools.expand
        hdr (expandable-text/make-expandable-text
             fmt-header-compact fmt-header-full
             :expanded? false :padding-x 1 :padding-y 0)
        ;; B.2: loaded resources between header and chat (pi: showLoadedResources)
        lr (ui/make-loaded-resources :theme (cfg/get-theme config))
        ;; B.3: queued steering/follow-up display (pi: updatePendingMessagesDisplay)
        pm (ui/make-pending-messages
            :hint (fmt-key-display (app-kb/key-text "app.message.dequeue")))
        ;; B.5: editor dynamic height — max(5, rows*0.3) via :terminal-rows;
        ;; the fixed :height fallback stays at the default 12
        ed (tui/make-editor :padding-x 0
                            :terminal-rows (fn [] (term/rows @(:terminal t)))
                            :border-fn (fn [c] (th/dim c)))
        ;; B.6: footer data provider + two-line footer (pi: FooterComponent)
        fdp (ui/make-footer-data-provider
             :session session
             :provider-count (count (keys (:providers config)))
             :context-window (:context-window config)
             :model @(:model ag) :provider @(:provider ag) :thinking @(:thinking ag))
        ftr (ui/make-footer :theme (cfg/get-theme config)
                            :provider fdp
                            :auto-compact (boolean (or (:compact-threshold config)
                                                       (:compact-token-threshold config))))

        ;; Core state (status-indicator/status-container filled in after layout)
        cs (map->CoreState {:tui t
                            :agent-state ag
                            :chat-history ch
                            :editor ed
                            :current-editor-atom (atom ed)
                            :header-comp hdr
                            :loaded-resources-comp lr
                            :anim-timer (atom nil)
                            :footer-comp ftr
                            :footer-provider fdp
                            :status-indicator nil
                            :status-container nil
                            :pending-messages-comp pm
                            :session-atom (atom session)
                            :running-turn? (atom false)
                            :config config
                            :pending-tool-comp pending-tool-comp
                            :bash-running? (atom false)
                            :bash-signal (atom false)
                            :pending-bash-components (atom [])
                            :pending-messages-container (container/make-container [pm])})]

    ;; Expose CoreState to the agent on-event handler (for :status events)
    (reset! cs-ref cs)

    ;; Initial loaded-resources sections (rebuilt on /reload)
    (ui/loaded-resources-set-sections! lr (build-loaded-resource-sections))

    ;; Focus editor
    (tui/tui-set-focus t ed)

    ;; Register builtin slash commands (autocomplete dropdown + dispatch)
    (register-builtin-commands! config)
    (register-not-implemented-commands!)

    ;; Autocomplete provider: slash commands + prompt templates + skill
    ;; commands + file paths
    (editor/editor-set-autocomplete-provider! ed
                                              (ac/make-combined-provider
                                               :commands-fn #(vec (concat (commands/get-commands)
                                                                          (prompts/as-command-maps (prompts/get-prompt-templates))
                                                                          (skills/as-command-maps (skills/get-skills))))
                                               :base-path (System/getProperty "user.dir")))
    (editor/editor-set-autocomplete-theme! ed (th/get-select-list-theme (cfg/get-theme config)))

    ;; Status indicator (Pi-style: separate layer between chat and editor)
    (let [si (ui/make-status-indicator :theme (cfg/get-theme config))
          cs (assoc cs :status-indicator si)
          ;; Pi layout (interactive-mode.ts setupUiLayout): the TUI root
          ;; renders children top-to-bottom; Containers are layout-neutral
          ;; grouping handles (pi: Container class) — the hierarchy mirrors
          ;; pi's addChild order exactly: header, loaded resources, chat,
          ;; pending messages, status, widgets above the editor, editor,
          ;; widgets below, footer. Like pi, the chat renders unbounded —
          ;; the terminal's own scrollback is the chat history, so scrolling
          ;; up (swipe/mouse wheel) shows earlier messages exactly like pi.
          header-container (container/make-container [sp1 hdr sp1])
          loaded-resources-container (container/make-container [lr])
          chat-container (container/make-container [ch])
          pending-messages-container (:pending-messages-container cs)
          status-container (container/make-container [si])
          ;; pi: renderWidgets initializes the above-editor container with a
          ;; default spacer when no extension widgets are registered
          widget-container-above (container/make-container [sp2])
          editor-container (container/make-container [ed])
          widget-container-below (container/make-container)
          cs (assoc cs :status-container status-container)]

      ;; Add components in pi's addChild order (header, loaded resources,
      ;; chat, pending messages, status, widgets above, editor, widgets
      ;; below, footer)
      (tui/tui-add-child t header-container)
      (tui/tui-add-child t loaded-resources-container)
      (tui/tui-add-child t chat-container)
      (tui/tui-add-child t pending-messages-container)
      (tui/tui-add-child t status-container)
      (tui/tui-add-child t widget-container-above)
      (tui/tui-add-child t editor-container)
      (tui/tui-add-child t widget-container-below)
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
          ;; pi: the same toggle drives tool expansion, the builtInHeader, and
          ;; the loaded-resources sections (getStartupExpansionState)
                                      (let [expanded? (ui/chat-history-toggle-tool-expanded! ch)]
                                        (expandable-text/expandable-text-set-expanded! hdr expanded?)
                                        (ui/loaded-resources-set-expanded! lr expanded?)
                                        (ui/chat-history-show-status! ch
                                                                      (str "Tool output: " (if expanded? "expanded" "collapsed")))
                                        (tui/tui-request-render t))))
      (editor/editor-set-on-action! ed "app.thinking.toggle"
                                    (fn []
          ;; pi: showStatus feedback on toggle
                                      (let [hidden? (ui/chat-history-toggle-thinking-hidden! ch)]
                                        (ui/chat-history-show-status! ch
                                                                      (str "Thinking blocks: " (if hidden? "hidden" "visible")))
                                        (tui/tui-request-render t))))
      (editor/editor-set-on-action! ed "app.editor.external"
                                    (fn [] (handle-external-editor cs)))
      ;; B.3: Alt+Enter queues a follow-up (pi: handleFollowUp); Alt+Up
      ;; restores queued messages to the editor (pi: handleDequeue)
      (editor/editor-set-on-action! ed "app.message.followUp"
                                    (fn [] (handle-follow-up cs)))
      (editor/editor-set-on-action! ed "app.message.dequeue"
                                    (fn [] (handle-dequeue cs)))

      ;; Global input listeners — only truly global keys stay here (pi: keep
      ;; app actions in the editor; the TUI keeps only global keys). Chat
      ;; scrolling is the terminal's own scrollback (pi parity), so there are
      ;; no chat scroll keys.
      (tui/tui-add-input-listener t
                                  (fn [data]
                                    (when (keys/matches-key? data (keys/ctrl "l"))
                                      (term/clear-screen! @(:terminal t))
                                      (tui/tui-request-render t))
                                    nil))

      ;; Initialize footer (header content is produced lazily by the
      ;; ExpandableText fns on first render)
      (update-footer! cs)

      ;; Theme controller (pi: InteractiveThemeController) — applies the
      ;; configured theme, drives auto light/dark sync via color-scheme
      ;; notifications, and re-themes the app components live on change
      ;; (the on-changed callback — pi: notifyChanged → onChanged).
      (let [tc (theme-ctrl/make-theme-controller
                t config
                (fn [msg] (tui/tui-flash! t msg :duration-ms 3000))
                (fn []
                  (let [current-theme (th/get-current-theme)]
                    ;; key-hint theme fns first — the header rebuild re-runs
                    ;; the content fns that use them
                    (app-kb/set-key-hint-theme-fns!
                     #(th/dim %) #(th/fg current-theme :muted %))
                    (ui/chat-history-set-theme! ch current-theme)
                    (ui/footer-set-theme! ftr current-theme)
                    (ui/status-indicator-set-theme! si current-theme)
                    (ui/loaded-resources-set-theme! lr current-theme)
                    (expandable-text/expandable-text-rebuild! hdr)
                    (editor/editor-set-autocomplete-theme!
                     ed (th/get-select-list-theme current-theme))
                    (tui/tui-request-render t))))
            cs (assoc cs :theme-controller tc)]

      ;; Extension UI registry (pi: ExtensionUIContext) — installed after the
      ;; layout is live so extensions can drive the UI from event handlers
        (build-extension-ui-registry cs
                                     {:ed ed :ftr ftr :hdr hdr :ch ch
                                      :sp1 sp1 :fdp fdp
                                      :header-container header-container
                                      :editor-container editor-container
                                      :widget-container-above widget-container-above
                                      :widget-container-below widget-container-below}
                                     tc)

        cs))))

;; ─── Extension UI registry (pi: ExtensionUIContext) ────────────────────────
;; build-layout installs this registry after the layout is live; extensions
;; call the kmet.app.extensions ui-* fns, which dispatch through it. All
;; closures capture the layout pieces they mutate.

(def ^:private MAX-WIDGET-LINES 10)

(defn- render-extension-widgets!
  "Rebuild the widget containers (pi: renderWidgets): the above-editor
   container gets a leading Spacer + widgets (bare Spacer when empty); the
   below-editor container gets widgets only."
  [t widget-above widget-below widgets-above widgets-below]
  (container/container-clear widget-above)
  (container/container-clear widget-below)
  (container/container-add-child widget-above (spacer/make-spacer 1))
  (doseq [w (vals @widgets-above)]
    (container/container-add-child widget-above w))
  (doseq [w (vals @widgets-below)]
    (container/container-add-child widget-below w))
  (tui/tui-request-render t))

(defn- make-extension-widget-component
  "pi: string arrays wrap in a Container of Text lines truncated to
   MAX_WIDGET_LINES with a '... (widget truncated)' tail; factory functions
   produce the component directly."
  [t content]
  (if (vector? content)
    (let [c (container/make-container)]
      (doseq [line (take MAX-WIDGET-LINES content)]
        (container/container-add-child c (text/make-text line 1 0)))
      (when (> (count content) MAX-WIDGET-LINES)
        (container/container-add-child c
                                       (text/make-text
                                        (th/fg (th/get-current-theme) :muted "... (widget truncated)")
                                        1 0)))
      c)
    (content t (th/get-current-theme))))

(defn- normalize-custom-component
  "Accept either an IComponent or a plain render map {:render :handle-input
   :invalidate} from an extension factory (pi: custom() accepts both a
   Component and a duck-typed object)."
  [x]
  (if (satisfies? tui/IComponent x)
    x
    (when (and (map? x) (fn? (:render x)))
      (let [m x]
        (reify tui/IComponent
          (render [_ width] ((:render m) width))
          (handle-input [_ data] (when-let [f (:handle-input m)] (f data)))
          (invalidate [_] (when-let [f (:invalidate m)] (f))))))))

(defn- transfer-editor!
  "Copy the app editor's wiring onto a custom editor component (pi:
   setCustomEditorComponent). Components implementing IEditorComponent get
   the method-based transfer (pi: setText/setPaddingX/setAutocomplete…);
   others get pi's duck-typed property copy of the record fields, plus the
   CustomEditor action-handler/keybinding extras in both cases."
  [app-ed custom-ed keybindings]
  (if (satisfies? protocols/IEditorComponent custom-ed)
    (do (protocols/editor-set-on-submit! custom-ed @(:on-submit app-ed))
        (protocols/editor-set-on-change! custom-ed @(:on-change app-ed))
        (protocols/editor-set-padding-x! custom-ed @(:padding-x app-ed))
        (protocols/editor-set-autocomplete-max-visible!
         custom-ed @(:autocomplete-max-visible app-ed))
        (when-let [p @(:autocomplete-provider app-ed)]
          (protocols/editor-set-autocomplete-provider! custom-ed p)))
    (doseq [field [:on-submit :on-change :padding-x
                   :autocomplete-provider :terminal-rows-atom]]
      (when (contains? custom-ed field)
        (reset! (get custom-ed field) @(get app-ed field)))))
  ;; pi: appearance properties are assigned whenever the target has them,
  ;; regardless of protocol (borderColor, kmet's dynamic-height source)
  (doseq [field [:border-fn :terminal-rows-atom]]
    (when (contains? custom-ed field)
      (reset! (get custom-ed field) @(get app-ed field))))
  (when (contains? custom-ed :action-handlers)
    (doseq [[action-id f] @(:action-handlers app-ed)]
      (swap! (:action-handlers custom-ed) assoc action-id f)))
  (when (contains? custom-ed :keybindings)
    (reset! (:keybindings custom-ed) keybindings))
  nil)

(defn- editor-text-get
  "Read the editor text through IEditorComponent when available, falling
   back to the field-based editor fn (duck-typed custom editors)."
  [ed]
  (if (satisfies? protocols/IEditorComponent ed)
    (protocols/editor-get-text ed)
    (editor/editor-get-text ed)))

(defn- editor-text-set!
  "Replace the editor text through IEditorComponent when available, falling
   back to the field-based editor fn (duck-typed custom editors)."
  [ed text]
  (if (satisfies? protocols/IEditorComponent ed)
    (protocols/editor-set-text! ed text)
    (editor/editor-set-text! ed text))
  nil)

(defn- editor-text-get-expanded
  "Read the editor text with paste markers expanded through IEditorComponent
   when available, falling back to the field-based editor fn (pi:
   getEditorText = getExpandedText ?? getText)."
  [ed]
  (if (satisfies? protocols/IEditorComponent ed)
    (protocols/editor-get-expanded-text ed)
    (editor/editor-get-expanded-text ed)))

(defn- normalize-autocomplete-provider
  "Accept either an AutocompleteProvider or a duck-typed map with
   :get-suggestions (fn [state]) and optional :apply-completion,
   :should-trigger-file-completion, :get-trigger-characters (pi-style
   object). Returns a provider or nil for anything else."
  [x]
  (cond
    (satisfies? ac/AutocompleteProvider x) x
    (map? x) (reify ac/AutocompleteProvider
               (get-suggestions [_ lines cursor-line cursor-col opts]
                 (when-let [f (:get-suggestions x)]
                   (f {:lines lines :cursor-line cursor-line
                       :cursor-col cursor-col :opts opts})))
               (apply-completion [_ lines cursor-line cursor-col item prefix]
                 (if-let [f (:apply-completion x)]
                   (f {:lines lines :cursor-line cursor-line
                       :cursor-col cursor-col :item item :prefix prefix})
                   ;; default: replace the prefix with the item value
                   (let [line (nth lines cursor-line "")
                         start (max 0 (- cursor-col (count prefix)))
                         new-line (str (subs line 0 start) (:value item)
                                       (subs line cursor-col))]
                     {:lines (assoc lines cursor-line new-line)
                      :cursor-line cursor-line
                      :cursor-col (+ start (count (:value item)))})))
               (should-trigger-file-completion [_ lines cursor-line cursor-col]
                 (boolean (and (:should-trigger-file-completion x)
                               ((:should-trigger-file-completion x)
                                {:lines lines :cursor-line cursor-line
                                 :cursor-col cursor-col}))))
               (get-trigger-characters [_]
                 (vec (:get-trigger-characters x []))))
    :else nil))

(defn- build-extension-ui-registry
  "Create the ExtensionUIContext implementation for the live layout
   (pi: createExtensionUIContext). Returns the capability map installed via
   extensions/set-ui-registry!."
  [{:keys [tui cs]}
   {:keys [ed ftr hdr ch sp1 fdp header-container editor-container
           widget-container-above widget-container-below]}
   theme-controller]
  (let [t tui
        widgets-above (atom {})
        widgets-below (atom {})
        custom-footer-atom (atom nil)
        custom-header-atom (atom nil)
        ;; the ACTIVE editor — the default or a swapped-in custom editor
        ;; (pi: this.editor is rebound by setCustomEditorComponent); the atom
        ;; lives on CoreState so action handlers outside this closure (e.g.
        ;; the external-editor flow) see the active editor too
        current-editor-atom (:current-editor-atom cs)
        editor-factory-atom (atom nil)
        extension-autocomplete-factories (atom [])
        terminal-input-unsubscribers (atom [])
        show-dialog (fn [dialog]
                      ;; pi: extension dialogs replace the editor container
                      ;; content and take focus (IME propagation handled by
                      ;; the dialog records)
                      (container/container-clear editor-container)
                      (container/container-add-child editor-container dialog)
                      (tui/tui-set-focus t dialog)
                      (tui/tui-request-render t))
        hide-dialog (fn []
                      (container/container-clear editor-container)
                      (container/container-add-child editor-container @current-editor-atom)
                      (tui/tui-set-focus t @current-editor-atom)
                      (tui/tui-request-render t))
        rebuild-autocomplete-provider! (fn []
                                         ;; pi: setupAutocompleteProvider — each
                                         ;; extension factory wraps the provider
                                         ;; chain; nil results keep the base
                                         (let [base (ac/make-combined-provider
                                                     :commands-fn #(vec (concat
                                                                         (commands/get-commands)
                                                                         (prompts/as-command-maps (prompts/get-prompt-templates))
                                                                         (skills/as-command-maps (skills/get-skills))))
                                                     :base-path (System/getProperty "user.dir"))
                                               provider (reduce (fn [prov factory]
                                                                  (or (normalize-autocomplete-provider
                                                                       (factory prov))
                                                                      prov))
                                                                base
                                                                @extension-autocomplete-factories)]
                                           (when (contains? @current-editor-atom
                                                            :autocomplete-provider)
                                             (editor/editor-set-autocomplete-provider!
                                              @current-editor-atom provider))
                                           nil))
        footer-data {:get-git-branch (fn [] (fdp/fdp-get-git-branch fdp))
                     :get-extension-statuses (fn []
                                               @(:extension-statuses-atom ftr))
                     :on-branch-change (fn [_f] (fn []))}
        registry
        {:select (fn [title options]
                   (let [p (promise)]
                     (show-dialog (dialogs/make-extension-selector
                                   title options
                                   (fn [opt] (hide-dialog) (deliver p opt))
                                   (fn [] (hide-dialog) (deliver p nil))
                                   (th/get-current-theme)))
                     p))
         :confirm (fn [title message]
                    (let [p (promise)]
                      (show-dialog (dialogs/make-extension-selector
                                    (str title "\n" message) ["Yes" "No"]
                                    (fn [opt] (hide-dialog) (deliver p (= opt "Yes")))
                                    (fn [] (hide-dialog) (deliver p false))
                                    (th/get-current-theme)))
                      p))
         :input (fn [title _placeholder]
                  (let [p (promise)]
                    (show-dialog (dialogs/make-extension-input
                                  title
                                  (fn [v] (hide-dialog) (deliver p v))
                                  (fn [] (hide-dialog) (deliver p nil))
                                  (th/get-current-theme)))
                    p))
         :editor (fn [title prefill]
                   (let [p (promise)]
                     (show-dialog (dialogs/make-extension-editor
                                   title prefill
                                   (fn [v] (hide-dialog) (deliver p v))
                                   (fn [] (hide-dialog) (deliver p nil))
                                   (th/get-current-theme)
                                   (fn [] (term/rows @(:terminal t)))))
                     p))
         :notify (fn [message _type]
                   (tui/tui-flash! t message)
                   nil)
         :custom (fn [factory {:keys [overlay overlay-options on-handle]}]
                   (let [p (promise)
                         saved-text (editor-text-get @current-editor-atom)
                         closed (atom false)
                         close (fn [result]
                                 (when-not @closed
                                   (reset! closed true)
                                   (if overlay
                                     (tui/tui-hide-overlay t)
                                     (do (hide-dialog)
                                         (editor-text-set!
                                          @current-editor-atom saved-text)))
                                   (deliver p result)))]
                     (try
                       (let [component (normalize-custom-component
                                        (factory t (th/get-current-theme) (tui-kb/get-global-keybindings) close))]
                         (when (and (nil? component) (not @closed))
                           (throw (ex-info "ui-custom factory returned no component" {})))
                         (when-not @closed
                           (if overlay
                             (let [opts (if (fn? overlay-options)
                                          (overlay-options)
                                          overlay-options)
                                   handle (tui/tui-show-overlay t component opts)]
                               (when on-handle (on-handle handle)))
                             (do (container/container-clear editor-container)
                                 (container/container-add-child editor-container component)
                                 (tui/tui-set-focus t component)
                                 (tui/tui-request-render t)))))
                       (catch Exception e
                         (when-not @closed
                           (reset! closed true)
                           (when-not overlay (hide-dialog))
                           (tui/tui-flash! t (str "Extension UI error: " (ex-message e)))
                           (deliver p nil))))
                     p))
         :set-status (fn [key text]
                       (ui/footer-set-extension-status! ftr key text)
                       (tui/tui-request-render t))
         :set-widget (fn [key content options]
                       (let [placement (or (:placement options) :above-editor)
                             m (if (= :below-editor placement) widgets-below widgets-above)]
                         (swap! m dissoc key)
                         (when content
                           (swap! m assoc key
                                  (make-extension-widget-component t content)))
                         (render-extension-widgets! t widget-container-above
                                                    widget-container-below
                                                    widgets-above widgets-below)))
         :set-footer (fn [factory]
                       (when-let [cf @custom-footer-atom]
                         (tui/tui-remove-child t cf))
                       (tui/tui-remove-child t ftr)
                       (if factory
                         (let [cf (factory t (th/get-current-theme) footer-data)]
                           (reset! custom-footer-atom cf)
                           (tui/tui-add-child t cf))
                         (do (reset! custom-footer-atom nil)
                             (tui/tui-add-child t ftr)))
                       (tui/tui-request-render t))
         :set-header (fn [factory]
                       (let [child (if factory (factory t (th/get-current-theme)) hdr)]
                         (container/container-clear header-container)
                         (container/container-add-child header-container sp1)
                         (container/container-add-child header-container child)
                         (container/container-add-child header-container sp1)
                         (when-not factory
                           (expandable-text/expandable-text-rebuild! hdr))
                         (tui/tui-request-render t)))
         :set-title (fn [title]
                      (when-let [term @(:terminal t)] (term/set-title! term title)))
         :on-terminal-input (fn [handler]
                              (tui/tui-add-input-listener t handler)
                              (let [unsub (fn [] (tui/tui-remove-input-listener t handler))]
                                (swap! terminal-input-unsubscribers conj unsub)
                                unsub))
         :set-editor-text (fn [text]
                            (editor-text-set! @current-editor-atom text)
                            (tui/tui-request-render t))
         :get-editor-text (fn [] (editor-text-get-expanded @current-editor-atom))
         :paste-to-editor (fn [text]
                            (tui/handle-input @current-editor-atom
                                              (str "\u001b[200~" text "\u001b[201~")))
         :set-working-indicator (fn [options]
                                  (spinner/spinner-set-indicator!
                                   (:spinner (:status-indicator cs)) options)
                                  (tui/tui-request-render t))
         :set-working-message (fn [message]
                                (ui/status-indicator-set-text! (:status-indicator cs)
                                                               (or message "Working..."))
                                (tui/tui-request-render t))
         :set-working-visible (fn [visible?]
                                (if visible?
                                  (when @(:running-turn? cs)
                                    (ui/status-indicator-start! (:status-indicator cs)))
                                  (ui/status-indicator-stop! (:status-indicator cs)))
                                (tui/tui-request-render t))
         :set-hidden-thinking-label (fn [label]
                                      (ui/chat-history-set-hidden-thinking-label! ch label)
                                      (tui/tui-request-render t))
         :set-editor-component (fn [factory]
                                 (let [current-text (editor-text-get @current-editor-atom)]
                                   (container/container-clear editor-container)
                                   (if factory
                                     (let [new-ed (factory t (th/get-current-theme) (tui-kb/get-global-keybindings))]
                                       (transfer-editor! ed new-ed (tui-kb/get-global-keybindings))
                                       (editor-text-set! new-ed current-text)
                                       (container/container-add-child editor-container new-ed)
                                       (tui/tui-set-focus t new-ed)
                                       (reset! current-editor-atom new-ed))
                                     (do (editor-text-set! ed current-text)
                                         (container/container-add-child editor-container ed)
                                         (tui/tui-set-focus t ed)
                                         (reset! current-editor-atom ed)))
                                   (reset! editor-factory-atom factory)
                                   (tui/tui-request-render t)))
         :add-autocomplete-provider (fn [factory]
                                      (swap! extension-autocomplete-factories conj factory)
                                      (rebuild-autocomplete-provider!))
         :get-theme (fn [] (th/get-current-theme))
         :get-all-themes (fn [] (th/get-all-themes))
         :set-theme (fn [theme-or-name]
                      ;; pi: setTheme — Theme instances go through
                      ;; setThemeInstance; names through setThemeName (which
                      ;; disables auto-sync). kmet has no settings write
                      ;; path — the switch is applied live only.
                      (if (instance? kmet.tui.theme.Theme theme-or-name)
                        (theme-ctrl/set-theme-instance! theme-controller theme-or-name)
                        (theme-ctrl/set-theme-name! theme-controller theme-or-name true)))
         :get-tools-expanded (fn [] (ui/chat-history-get-tool-expanded ch))
         :set-tools-expanded (fn [expanded?]
                               (let [current? (ui/chat-history-get-tool-expanded ch)]
                                 (when (not= current? expanded?)
                                   (ui/chat-history-toggle-tool-expanded! ch)))
                               (tui/tui-request-render t))
         :reset (fn []
                  ;; pi: resetExtensionUI — dispose widgets, restore
                  ;; footer/header/editor, clear statuses + working
                  ;; customization, drop terminal input listeners
                  (reset! widgets-above {})
                  (reset! widgets-below {})
                  (render-extension-widgets! t widget-container-above
                                             widget-container-below
                                             widgets-above widgets-below)
                  (when @custom-footer-atom
                    (tui/tui-remove-child t @custom-footer-atom)
                    (reset! custom-footer-atom nil)
                    (tui/tui-add-child t ftr))
                  (when @custom-header-atom
                    (reset! custom-header-atom nil)
                    (container/container-clear header-container)
                    (container/container-add-child header-container sp1)
                    (container/container-add-child header-container hdr)
                    (container/container-add-child header-container sp1)
                    (expandable-text/expandable-text-rebuild! hdr))
                  (doseq [unsub @terminal-input-unsubscribers]
                    (try (unsub) (catch Exception _)))
                  (reset! terminal-input-unsubscribers [])
                  (doseq [key (keys @(:extension-statuses-atom ftr))]
                    (ui/footer-set-extension-status! ftr key nil))
                  (spinner/spinner-set-indicator! (:spinner (:status-indicator cs)) nil)
                  (ui/status-indicator-set-text! (:status-indicator cs) "Working...")
                  (ui/chat-history-set-hidden-thinking-label! ch nil)
                  (reset! extension-autocomplete-factories [])
                  (rebuild-autocomplete-provider!)
                  (when @editor-factory-atom
                    (let [current-text (editor-text-get @current-editor-atom)]
                      (container/container-clear editor-container)
                      (editor-text-set! ed current-text)
                      (container/container-add-child editor-container ed)
                      (tui/tui-set-focus t ed)
                      (reset! current-editor-atom ed))
                    (reset! editor-factory-atom nil))
                  ;; restore any open dialog
                  (container/container-clear editor-container)
                  (container/container-add-child editor-container ed)
                  (tui/tui-set-focus t ed)
                  (reset! current-editor-atom ed)
                  (when (tui/tui-has-overlay? t) (tui/tui-hide-overlay t))
                  (tui/tui-request-render t))}]
    (extensions/set-ui-registry! registry)
    registry))

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
      (let [config (cfg/apply-cli-overrides config opts)
            _ (reset! global-config config)
            session (cond
                      (:resume opts) nil
                      (:continue opts) (find-session)
                      :else (session/create-session (ensure-session-dir)))
            cs (build-layout config session)]
        (reset! tui-ref (:tui cs))
        (when (:resume opts) (resume-session cs ensure-session-dir))
        ;; pi: start the UI before initializing extensions so session_start
        ;; handlers can use interactive dialogs — kmet loads extensions
        ;; earlier, so the event fires once the layout + UI registry are
        ;; live and the render loop is running (the future waits for it).
        (future
          (try
            (loop []
              (when-not (or @(:running? (:tui cs))
                            @(:stopped? (:tui cs)))
                (Thread/sleep 20)
                (recur)))
            ;; skipped entirely when the TUI already stopped (immediate quit)
            (when @(:running? (:tui cs))
              (event-bus/emit-event!
               {:type :session-start
                :reason (cond (:resume opts) :resume
                              (:continue opts) :continue
                              :else :new)}))
            (catch Exception e
              (debug/log "session-start: " e))))
        ;; Theme detection + application (pi: startup applyFromSettings) —
        ;; waits for the render loop so OSC 11 / color-scheme responses are
        ;; consumed by the input path.
        (future
          (try
            (loop []
              (when-not (or @(:running? (:tui cs))
                            @(:stopped? (:tui cs)))
                (Thread/sleep 20)
                (recur)))
            (when @(:running? (:tui cs))
              (theme-ctrl/apply-from-settings! (:theme-controller cs)))
            (catch Exception e
              (debug/log "theme detection: " e))))
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
