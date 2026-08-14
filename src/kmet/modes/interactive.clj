(ns kmet.modes.interactive
  "Interactive TUI mode — main layout, agent integration, command handling,
   session browsing, bash commands, external editor.
   pi: modes/interactive/interactive-mode.ts."
  (:require [kmet.tui.core :as tui]
            [kmet.tui.protocols :as protocols]
            [kmet.tui.terminal :as term]
            [kmet.tui.theme :as th]
            [kmet.tui.components.text :as text]
            [kmet.tui.components.spacer :as spacer]
            [kmet.tui.components.editor :as editor]
            [kmet.tui.components.expandable-text :as expandable-text]
            [kmet.tui.components.container :as container]
            [kmet.app.ui :as ui]
            [kmet.app.ui.external-editor :refer [editor-text-get editor-text-get-expanded
                                                 editor-text-set! handle-external-editor]]
            [kmet.app.ui.fork-selector :refer [show-fork-selector]]
            [kmet.app.ui.model-selector :refer [apply-model-switch! model-full-id
                                                resolve-model-ref scoped-or-available-models
                                                show-model-selector show-scoped-models-selector
                                                sync-footer-model!]]
            [kmet.app.ui.settings-selector :refer [show-settings]]
            [kmet.app.ui.session-selector :refer [show-session-selector]]
            [kmet.app.ui.tree-selector :refer [show-session-tree]]
            [kmet.app.ui.footer :as footer]
            [kmet.app.ui.footer-data-provider :as fdp]
            [kmet.app.theme-controller :as theme-ctrl]
            [kmet.tui.components.select-list :as select-list]
            [kmet.app.loop :as agent]
            [kmet.ai.models :as models]
            [kmet.app.model-resolver :as resolver]
            [kmet.ai.auth :as auth]
            [kmet.app.session :as session]
            [kmet.app.session-export :as session-export]
            [kmet.app.clipboard :as clipboard]
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
            [kmet.libs.process :as process]
            [kmet.libs.terminal :as lib-term]))

(declare clone-current-session! fork-at! restore-session!
         build-extension-ui-registry ask-branch-summary
         build-loaded-resource-sections start-agent-run!
         show-status-indicator! clear-status-indicator!
         maybe-show-cache-miss-notice!)

;; ─── Global config ref ────────────────────────────────────────────────────

(defonce ^:private global-config (atom nil))

;; ─── Session helpers ───────────────────────────────────────────────────────

(defn- get-session-dir []
  (if-let [c @global-config]
    (cfg/get-session-dir c)
    (str (System/getProperty "user.home") "/.kmet/sessions")))

(defn- ensure-session-dir
  "The base sessions dir (created). Listings (resume-session) walk it plus
   its cwd-encoded subdirectories (pi: listAll — G2), so legacy flat session
   files remain visible alongside per-project ones."
  []
  (let [d (get-session-dir)]
    (fs/create-dirs d)
    d))

(defn- ensure-cwd-session-dir
  "The sessions dir for the current cwd — where new sessions are placed:
   BASE/<--cwd-->/ (pi: getDefaultSessionDir — per-project isolation, G2)."
  []
  (let [d (session/session-dir-for-cwd (get-session-dir) (str (fs/cwd)))]
    (fs/create-dirs d)
    d))

(defn- find-session
  "Most recent session for the current cwd (pi: continueRecent →
   findMostRecentSession — header-based discovery in the cwd-encoded dir;
   legacy headerless sessions and other-cwd files are excluded, no fallback).
   Used by --continue so the current project's last session is resumed."
  []
  (session/find-most-recent-session (ensure-cwd-session-dir) (str (fs/cwd))))

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
                      theme-controller
                      active-status-kind])

;; ─── Formatting helpers ────────────────────────────────────────────────────

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
  "Sync the footer's session data source and request a re-render. The
   footer's model/provider/thinking live in the fdp atoms (set once at
   startup; sync-footer-model! refreshes them on model changes), so only
   session changes need wiring here."
  [cs]
  (ui/fdp-set-session! (:footer-provider cs) @(:session-atom cs))
  ;; The fdp atoms are read inside helper fns — not lexically tracked by
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
         "  Ctrl+L     — Select model
"
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

;; ─── Session info (/session) ────────────────────────────────────────────────

(defn- session-info-text
  "Pi: handleSessionCommand — stats + name + token/cost breakdown. Plain
   text with theme styling, rendered as an assistant message."
  [sess]
  (let [stats (session/get-session-stats sess)
        name (session/get-session-name sess)
        {:keys [input output cache-read cache-write]} (:tokens stats)
        prompt-tokens (+ input cache-read cache-write)
        cache-total (+ cache-read cache-write)
        breakdown (session/usage-breakdown sess)]
    (str (th/bold "Session Info") "\n\n"
         (when (seq name) (str (th/dim "Name:") " " name "\n"))
         (th/dim "File:") " " (:file stats) "\n"
         (th/dim "ID:") " " (:id stats) "\n\n"
         (th/bold "Messages") "\n"
         (th/dim "Total:") " " (:total-messages stats) "\n"
         (th/dim "User:") " " (:user-messages stats) "\n"
         (th/dim "Assistant:") " " (:assistant-messages stats) "\n"
         (th/dim "Tools:") " " (:tool-calls stats) " calls, " (:tool-results stats) " results\n\n"
         (th/bold "Tokens") "\n"
         (th/dim "Input:") " " prompt-tokens "\n"
         (when (pos? cache-total)
           (str (th/dim "  Cached:") " " cache-read
                " (" (format "%.1f" (* 100 (/ (double cache-read) (max 1 prompt-tokens)))) "%)\n"
                (th/dim "  Uncached:") " " (+ input cache-write)
                (when (pos? cache-write) (str " (" cache-write " written to cache)"))
                "\n"))
         (th/dim "Output:") " " output "\n"
         (th/dim "Total:") " " (:total (:tokens stats)) "\n"
         (when (pos? (:cost stats))
           (str "\n" (th/bold "Cost") "\n"
                (th/dim "Total:") " $" (format "%.3f" (:cost stats))
                (when (> (count breakdown) 1)
                  (apply str (for [b breakdown]
                               (str "\n  " (th/dim (str (:key b) ":"))
                                    " $" (format "%.3f" (:cost b))
                                    " " (th/dim (str "(" (footer/format-tokens (:tokens b)) " tokens)"))))))
                "\n")))))

(defn- parse-export-path
  "Pi: getPathCommandArgument — strip surrounding quotes, else take the
   first whitespace-delimited token. Returns nil when there is no
   argument."
  [args]
  (let [args (str/trim args)]
    (when (seq args)
      (let [first-char (first args)]
        (if (contains? #{\" \'} first-char)
          (when-let [end (str/index-of args first-char 1)]
            (subs args 1 end))
          (let [ws (str/index-of args " ")]
            (if ws (subs args 0 ws) args)))))))

;; ─── Share (/share — gh gist) ───────────────────────────────────────────────

(defn- gh-auth-status
  "Pi: handleShareCommand — :ok when gh is installed and authenticated,
   :not-logged-in when gh exists but auth is missing, :not-installed when
   gh cannot be spawned, :timed-out when gh did not respond in time."
  []
  (try
    (let [p (proc/process ["gh" "auth" "status"]
                          {:out :string :err :string
                           :in (fs/file (if process/windows-os? "NUL" "/dev/null"))})
          r (deref p 10000 :timed-out)]
      (cond
        (= r :timed-out)
        (do (when-let [pid (try (-> p :proc .pid) (catch Exception _ nil))]
              (process/kill-process-tree! pid))
            :timed-out)
        (zero? (:exit r)) :ok
        :else :not-logged-in))
    (catch Exception _ :not-installed)))

(defn- share-session!
  "Export the current session to a temp HTML file and create a secret gist
   (pi: handleShareCommand). Runs on a future with a spinner status
   indicator; the chat reply carries the gist URL."
  [cs]
  (let [sess @(:session-atom cs)
        chat (:chat-history cs)
        indicator (spinner/make-spinner :text "Creating gist..." :active true)
        done (promise)]
    (show-status-indicator! cs :share indicator)
    (tui/tui-request-render (:tui cs))
    ;; render driver: tick the spinner while the gist creation runs
    (future
      (while (not (realized? done))
        (Thread/sleep 100)
        (tui/tui-request-render (:tui cs))))
    (future
      (let [result
            (try
              (let [tmp-dir (or (System/getenv "TMPDIR")
                                (System/getProperty "java.io.tmpdir"))
                    tmp (fs/create-temp-file {:prefix "kmet-share-" :suffix ".html" :dir tmp-dir})]
                (try
                  (session-export/export-to-html! sess {:path (str tmp)})
                  (let [p (proc/process ["gh" "gist" "create" "--public=false" (str tmp)]
                                        {:out :string :err :string
                                         :in (fs/file (if process/windows-os? "NUL" "/dev/null"))})
                        r (deref p 60000 :timed-out)]
                    (if (= r :timed-out)
                      (do (when-let [pid (try (-> p :proc .pid) (catch Exception _ nil))]
                            (process/kill-process-tree! pid))
                          {:error "Gist creation timed out."})
                      (if (zero? (:exit r))
                        {:url (str/trim (:out r))}
                        {:error (str/trim (:err r))})))
                  (finally (fs/delete-if-exists tmp))))
              (catch Exception e
                {:error (or (ex-message e) (str e))}))]
        (deliver done result)))
    (future
      (let [result (deref done 90000 :timeout)]
        (clear-status-indicator! cs :share)
        (ui/chat-history-add-message!
         chat
         (cond
           (= result :timeout)
           {:role :info :label "Share" :content "Gist creation timed out."}

           (:error result)
           {:role :info :label "Share"
            :content (str "Failed to create gist: " (:error result))}

           :else
           {:role :info :label "Share" :content (str "Share URL: " (:url result))}))))))

;; ─── Login/logout auth-type selection (Phase 10; pi getLoginProviderOptions /
;;    showLoginAuthTypeSelector / showLoginDialog / showApiKeyLoginDialog) ────

(def ^:private api-key-login-label "Sign in with an API key")

(defn- login-methods
  "Auth methods a provider offers, oauth first (pi AUTH_TYPE_ORDER + the
   provider's declared auth): an oauth provider only offers the api-key path
   when it has one (env vars, models.edn configured key, or :auth-header) —
   openai-codex is oauth-only (no api-key login)."
  [p]
  (if (:oauth p)
    (cond-> [:oauth]
      (or (seq (:env-vars p)) (:api-key p) (:auth-header p)) (conj :api-key))
    [:api-key]))

(defn- oauth-prompt!
  "Show a dialog for an OAuth prompt and return the entered string (pi
   AuthPrompt → LoginDialogComponent; the flow runs on a future, so kmet
   blocks on the overlay promise). :select shows a select-list; :text/
   :secret/:manual-code show an input dialog (the prompt's :placeholder
   becomes the input prefill). Dialog cancel sets SIGNAL and throws
   \"Login cancelled\". PROMPT-STATE — an atom tracking the active dialog
   {:promise p :tui tui} — lets a loopback flow's :abort-prompt! close a
   still-open dialog when the browser callback wins the race."
  [cs prompt signal prompt-state]
  (let [p (promise)
        ;; Resolving (submit or cancel) clears the tracked dialog so the
        ;; throw-path hide below can never pop an overlay twice (or one the
        ;; user already dismissed).
        finish (fn [v] (reset! prompt-state nil) (deliver p v))
        cancel (fn [] (reset! prompt-state nil) (reset! signal true) (deliver p ::cancelled))
        track! (fn [] (reset! prompt-state {:promise p :tui (:tui cs)}))]
    (case (:type prompt)
      :select
      (let [options (:options prompt)]
        (track!)
        (tui/tui-show-overlay
         (:tui cs)
         (dialogs/make-extension-selector
          (:message prompt)
          (mapv :label options)
          (fn [label]
            (tui/tui-hide-overlay (:tui cs))
            (finish (or (:id (first (filter #(= label (:label %)) options)))
                        label)))
          (fn []
            (tui/tui-hide-overlay (:tui cs))
            (cancel))
          (th/get-current-theme))
         :width 60 :height (min (count options) 10)))
      (do
        (track!)
        (tui/tui-show-overlay
         (:tui cs)
         (dialogs/make-extension-input
          (:message prompt)
          (fn [value]
            (tui/tui-hide-overlay (:tui cs))
            (finish value))
          (fn []
            (tui/tui-hide-overlay (:tui cs))
            (cancel))
          (th/get-current-theme)
          (:placeholder prompt))
         :width 60 :height 9)))
    (tui/tui-request-render (:tui cs))
    ;; Block until the dialog resolves; a 10-min safety timeout or any
    ;; non-string delivery (::cancelled — user cancel or the loopback
    ;; flow's :abort-prompt!) throws "Login cancelled" like pi's abort
    ;; signal. A stale dialog from an abort that won the race with the
    ;; overlay show is hidden here.
    (let [value (deref p 600000 :timeout)]
      (if (string? value)
        value
        (do (when (and @prompt-state (= p (:promise @prompt-state)))
              (tui/tui-hide-overlay (:tui @prompt-state))
              (tui/tui-request-render (:tui @prompt-state)))
            (throw (ex-info "Login cancelled" {:type :login-cancelled})))))))

(defn- oauth-notify!
  "Map an OAuth AuthEvent onto the chat history (pi notifyAuthDialog):
   :device-code shows the code + verification URI; :auth-url/:info/:progress
   post info messages. Renders from the flow future — tui-request-render
   only sets a flag, safe off the input thread."
  [cs event]
  (case (:type event)
    :device-code
    (ui/chat-history-add-message! (:chat-history cs)
                                  {:role :info :label "Login"
                                   :content (str "Open " (:verification-uri event)
                                                 " and enter the code: " (:user-code event))})
    :auth-url
    (ui/chat-history-add-message! (:chat-history cs)
                                  {:role :info :label "Login"
                                   :content (str "Open " (:url event)
                                                 (when (:instructions event)
                                                   (str "\n" (:instructions event))))})
    :info
    (ui/chat-history-add-message! (:chat-history cs)
                                  {:role :info :label "Login"
                                   :content (:message event)})
    :progress
    (ui/chat-history-add-message! (:chat-history cs)
                                  {:role :info :label "Login"
                                   :content (:message event)}))
  (when-let [tui (:tui cs)]
    (tui/tui-request-render tui))
  nil)

(defn- oauth-login!
  "Run an OAuthAuth login flow on a future (pi showLoginDialog): the
   interaction prompts via overlays and notifies via the chat history; on
   success the credential is persisted to auth.edn. Availability refreshes
   automatically — models/get-available reads the auth atom live, and the
   oauth credential's :available-model-ids shrink the model list.
   :abort-prompt! — the loopback flows' race hook — closes the manual-paste
   dialog when the browser callback wins (pi manualAbort.abort())."
  [cs provider]
  (let [oauth (:oauth provider)
        signal (atom false)
        prompt-cancelled (atom false)
        prompt-state (atom nil)
        abort-prompt! (fn []
                        (reset! prompt-cancelled true)
                        (when-let [{:keys [promise tui]} @prompt-state]
                          (deliver promise ::cancelled)
                          (tui/tui-hide-overlay tui)
                          (tui/tui-request-render tui)))
        prompt-fn (fn [prompt]
                    (when @prompt-cancelled
                      (throw (ex-info "Login cancelled" {:type :login-cancelled})))
                    (oauth-prompt! cs prompt signal prompt-state))
        interaction {:signal signal
                     :prompt prompt-fn
                     :abort-prompt! abort-prompt!
                     :notify (fn [event] (oauth-notify! cs event))}]
    (future
      (try
        (let [credential ((:login oauth) interaction)]
          (auth/set-oauth-credential! (:id provider) credential)
          (ui/chat-history-add-message! (:chat-history cs)
                                        {:role :assistant
                                         :content (str "Logged in to " (:name provider)
                                                       ". Credentials saved to auth.edn.")})
          (when (and (:session-atom cs) (:footer-comp cs) (:footer-provider cs))
            (update-footer! cs)))
        (catch Exception e
          (if (str/includes? (or (ex-message e) "") "Login cancelled")
            (ui/chat-history-add-message! (:chat-history cs)
                                          {:role :info :label "Login"
                                           :content "Login cancelled."})
            (ui/show-warning! (:chat-history cs)
                              (str "Failed to login to " (:name provider) ": " (ex-message e)))))
        (when-let [tui (:tui cs)]
          (tui/tui-request-render tui))))))

(defn- api-key-login!
  "The api-key login flow (pi showApiKeyLoginDialog): prompt for the key via
   the extension-input overlay and save it to auth.edn."
  [cs p]
  (tui/tui-show-overlay
   (:tui cs)
   (dialogs/make-extension-input
    (str "API key for " (:name p))
    (fn [value]
      (tui/tui-hide-overlay (:tui cs))
      (let [key (str/trim value)]
        (if (seq key)
          (do (auth/set-credential! (:id p) key)
              (ui/chat-history-add-message! (:chat-history cs)
                                            {:role :assistant
                                             :content (str "Saved API key for " (:name p) ".")}))
          (ui/show-warning! (:chat-history cs)
                            "No API key entered — nothing saved.")))
      (tui/tui-request-render (:tui cs)))
    (fn []
      (tui/tui-hide-overlay (:tui cs))
      (tui/tui-request-render (:tui cs)))
    (th/get-current-theme))
   :width 60 :height 9)
  (tui/tui-request-render (:tui cs)))

(defn- show-login-method-selector!
  "Offer a provider's auth methods (pi showLoginAuthTypeSelector): the oauth
   subscription label (or \"Sign in with an account\") and \"Sign in with an
   API key\" in a select-list overlay; a single method starts directly."
  [cs p methods]
  (let [oauth-label (or (:login-label (:oauth p)) "Sign in with an account")
        options (vec (concat (when (some #{:oauth} methods) [oauth-label])
                             (when (some #{:api-key} methods) [api-key-login-label])))
        choose (fn [label]
                 (if (= label oauth-label)
                   (oauth-login! cs p)
                   (api-key-login! cs p)))]
    (if (= 1 (count options))
      (choose (first options))
      (do
        (tui/tui-show-overlay
         (:tui cs)
         (dialogs/make-extension-selector
          (str "Select authentication method for " (:name p) ":")
          options
          (fn [label]
            (tui/tui-hide-overlay (:tui cs))
            (choose label))
          (fn []
            (tui/tui-hide-overlay (:tui cs))
            (tui/tui-request-render (:tui cs)))
          (th/get-current-theme))
         :width 60 :height (min (count options) 10))
        (tui/tui-request-render (:tui cs))))))

(defn- register-builtin-commands!
  "Register kmet's builtin slash commands. Handlers receive [cs args];
   argument completions feed the editor autocomplete dropdown."
  [_config]
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
    :argument-hint "<provider:model[:thinking]>"
    :get-argument-completions
    (fn [_]
      (mapv (fn [m] (let [v (str (name (:provider m)) "/" (:id m))]
                      {:value v :label v}))
            (models/get-available)))
    :handler
    (fn [cs args]
      (if (seq args)
        (let [{:keys [model thinking-level]} (resolve-model-ref cs args)]
          (if model
            (apply-model-switch! cs model thinking-level)
            ;; pi: no cached match → selector with the term pre-filled (kmet
            ;; catalogs are static — no catalog refresh on a miss)
            (show-model-selector cs args)))
        (show-model-selector cs)))})
  (commands/register-command!
   {:name "scoped-models"
    :description "Enable/disable models for Ctrl+P cycling"
    :handler (fn [cs _] (show-scoped-models-selector cs))})
  (commands/register-command!
   {:name "settings"
    :description "Open settings menu"
    :handler (fn [cs _] (show-settings cs))})
  (commands/register-command!
   {:name "new"
    :description "Start a new session"
    :handler (fn [cs _]
               (let [new-session (session/create-session (ensure-cwd-session-dir))]
                 (debug/log "new session created: " (:id new-session))
                 (ui/chat-history-clear! (:chat-history cs))
                 (reset! (:session-atom cs) new-session)
                 (extensions/set-session! new-session)
                 (let [old-ag @(:agent-state cs)
                       new-ag (assoc old-ag :session new-session)]
                   (reset! (:agent-state cs) new-ag))
                 (ui/chat-history-add-message! (:chat-history cs)
                                               {:role :assistant :content "Started a new session."})))})
  (commands/register-command!
   {:name "resume"
    :description "Browse past sessions"
    :handler (fn [cs _]
               (debug/log "/resume command")
               (show-session-selector cs ensure-session-dir
                                      (fn [path]
                                        (let [sess (session/load-session path)
                                              short-id (subs (:id sess) 0 (min 8 (count (:id sess))))]
                                          (restore-session! cs sess true)
                                          (ui/chat-history-add-message! (:chat-history cs)
                                                                        {:role :assistant
                                                                         :content (str "Resumed session " short-id ".")})
                                          (tui/tui-request-render (:tui cs))))))})
  (commands/register-command!
   {:name "continue"
    :description "Continue where the agent left off (e.g. after a network error)"
    :handler (fn [cs _]
               (let [{:keys [chat-history]} cs
                     agent-state @(:agent-state cs)]
                 (cond
                   ;; A run may have ended in :error (network failure, retries
                   ;; exhausted) — that's exactly when /continue is useful, so
                   ;; only actively-running states refuse. Both the UI turn flag
                   ;; (set synchronously on submit) and the agent status
                   ;; (:thinking/:executing, set by the run future) are checked.
                   (or @(:running-turn? cs)
                       (contains? #{:thinking :executing} (agent/get-status agent-state)))
                   (ui/chat-history-add-message! chat-history
                                                 {:role :info :label "Continue"
                                                  :content "Wait for the current response to finish before continuing."})

                   @(:compacting? agent-state)
                   (ui/chat-history-add-message! chat-history
                                                 {:role :info :label "Continue"
                                                  :content "Wait for the in-progress compaction to finish before continuing."})

                   (empty? (agent/get-context agent-state))
                   (ui/chat-history-add-message! chat-history
                                                 {:role :info :label "Continue"
                                                  :content "No conversation to continue."})

                   :else
                   (do (debug/log "/continue command")
                       (start-agent-run! cs)))))})
  (commands/register-command!
   {:name "tree"
    :description "Navigate session tree (switch branches)"
    :handler (fn [cs _]
               (show-session-tree cs
                                  (fn [entry]
                                    (ask-branch-summary cs @(:session-atom cs) entry))))})
  (commands/register-command!
   {:name "fork"
    :description "Create a new fork from a previous user message"
    :handler (fn [cs _]
               (show-fork-selector cs (fn [entry-id] (fork-at! cs entry-id))))})
  (commands/register-command!
   {:name "clone"
    :description "Duplicate the current session at the current position"
    :handler (fn [cs _]
               (clone-current-session! cs))})
  (commands/register-command!
   {:name "name"
    :description "Set session display name"
    :argument-hint "<name>"
    :handler (fn [cs args]
               (let [sess @(:session-atom cs)]
                 (if (nil? sess)
                   (ui/chat-history-add-message! (:chat-history cs)
                                                 {:role :assistant
                                                  :content "No active session."})
                   (if (seq args)
                     (let [sanitized (session/sanitize-session-name args)]
                       (session/append-session-info! sess sanitized)
                       (when-not (= args sanitized)
                         ;; pi: warn when normalization changed the input
                         (ui/show-warning!
                          (:chat-history cs)
                          (str "Session name was normalized from " (pr-str args)
                               " to " (pr-str sanitized))))
                       (ui/chat-history-add-message! (:chat-history cs)
                                                     {:role :info :label "Name"
                                                      :content (str "Session name set: " sanitized)}))
                     (if-let [current (session/get-session-name sess)]
                       (ui/chat-history-add-message! (:chat-history cs)
                                                     {:role :info :label "Name"
                                                      :content (str "Session name: " current)})
                       (ui/show-warning! (:chat-history cs)
                                         "Usage: /name <name>"))))))})
  (commands/register-command!
   {:name "session"
    :description "Show session info and stats"
    :handler (fn [cs _]
               (let [sess @(:session-atom cs)
                     chat (:chat-history cs)]
                 (if (nil? sess)
                   (ui/chat-history-add-message! chat
                                                 {:role :info :label "Session"
                                                  :content "No active session."})
                   (ui/chat-history-add-message! chat
                                                 {:role :assistant
                                                  :content (session-info-text sess)}))))})
  (commands/register-command!
   {:name "export"
    :description "Export session to HTML (JSONL is not supported by design)"
    :argument-hint "<path>"
    :handler (fn [cs args]
               (let [sess @(:session-atom cs)
                     chat (:chat-history cs)
                     arg (parse-export-path args)]
                 (cond
                   (nil? sess)
                   (ui/chat-history-add-message! chat
                                                 {:role :info :label "Export"
                                                  :content "No active session."})

                   (str/ends-with? (str/lower-case (or arg "")) ".jsonl")
                   (ui/chat-history-add-message! chat
                                                 {:role :info :label "Export"
                                                  :content "JSONL export is not supported — kmet sessions are EDN-only. Use /export or /export <path.html>."})

                   :else
                   (try
                     (let [ag @(:agent-state cs)
                           system-prompt (or @(:system-prompt-override ag) @(:system ag))
                           tool-defs (vals (tools/get-all-tools))
                           opts (cond-> (when arg {:path arg})
                                  system-prompt (assoc :system-prompt system-prompt)
                                  (seq tool-defs) (assoc :tools tool-defs))
                           path (session-export/export-to-html! sess opts)]
                       (ui/chat-history-add-message! chat
                                                     {:role :info :label "Export"
                                                      :content (str "Session exported to: " path)}))
                     (catch Exception e
                       (ui/chat-history-add-message! chat
                                                     {:role :info :label "Export"
                                                      :content (str "Failed to export session: "
                                                                    (or (ex-message e) (str e)))}))))))})
  (commands/register-command!
   {:name "share"
    :description "Share session as a secret GitHub gist"
    :handler (fn [cs _]
               (let [chat (:chat-history cs)]
                 (case (gh-auth-status)
                   :not-installed
                   (ui/chat-history-add-message! chat
                                                 {:role :info :label "Share"
                                                  :content "GitHub CLI (gh) is not installed. Install it from https://cli.github.com/"})

                   :not-logged-in
                   (ui/chat-history-add-message! chat
                                                 {:role :info :label "Share"
                                                  :content "GitHub CLI is not logged in. Run 'gh auth login' first."})

                   :timed-out
                   (ui/chat-history-add-message! chat
                                                 {:role :info :label "Share"
                                                  :content "GitHub CLI did not respond (timed out)."})

                   :else
                   (if (nil? @(:session-atom cs))
                     (ui/chat-history-add-message! chat
                                                   {:role :info :label "Share"
                                                    :content "No active session."})
                     (share-session! cs)))))})
  (commands/register-command!
   {:name "copy"
    :description "Copy last agent message to clipboard"
    :handler (fn [cs _]
               (let [sess @(:session-atom cs)
                     chat (:chat-history cs)]
                 (cond
                   (nil? sess)
                   (ui/chat-history-add-message! chat
                                                 {:role :info :label "Copy"
                                                  :content "No active session."})

                   :else
                   (if-let [text (session/get-last-assistant-text sess)]
                     (if (clipboard/copy-text! text)
                       (tui/tui-flash! (:tui cs) "Copied!")
                     ;; No platform tool — fall back to the OSC 52 terminal
                     ;; protocol (pi: copyToClipboard remote fallback).
                       (if (lib-term/osc52-copy! (term/write-fn @(:terminal (:tui cs)))
                                                 text)
                         (tui/tui-flash! (:tui cs) "Copied!")
                         (ui/show-warning! chat
                                           "No clipboard tool available on this system.")))
                     (ui/show-warning! chat
                                       "No agent messages to copy yet.")))))})
  (commands/register-command!
   {:name "reload"
    :description "Reload keybindings, extensions, skills, prompts, themes, and context files"
    :handler handle-reload})
  (commands/register-command!
   {:name "compact"
    :description "Manually compact the session context"
    :argument-hint "<instructions>"
    :handler (fn [cs args]
               (let [{:keys [chat-history]} cs
                     agent-state @(:agent-state cs)
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
                     (when (:success result)
                       (cfg/save-setting! [:theme] name))
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
                                                                "\nUsage: /theme <name>")}))))})
  (commands/register-command!
   {:name "login"
    :description "Configure provider authentication"
    :argument-hint "<provider>"
    :get-argument-completions
    (fn [_]
      (mapv (fn [p] {:value (name (:id p)) :label (:name p)})
            (models/get-providers)))
    :handler
    (fn [cs args]
      (let [provider (some-> (first (str/split args #"\s+")) str/trim not-empty keyword)]
        (cond
          (nil? provider)
          (ui/chat-history-add-message! (:chat-history cs)
                                        {:role :assistant
                                         :content (str "Usage: /login <provider>"
                                                       "\nProviders: "
                                                       (str/join ", " (map (comp name :id) (models/get-providers))))})

          (nil? (models/get-provider provider))
          (ui/show-warning! (:chat-history cs) (str "Unknown provider: " (name provider)))

          :else
          (let [p (models/get-provider provider)
                methods (login-methods p)]
            (if (= 1 (count methods))
              (if (= :oauth (first methods))
                (oauth-login! cs p)
                (api-key-login! cs p))
              (show-login-method-selector! cs p methods))))))})
  (commands/register-command!
   {:name "logout"
    :description "Remove provider authentication"
    :argument-hint "<provider>"
    :get-argument-completions
    (fn [_]
      (let [configured (auth/get-credentials)]
        (mapv (fn [p] {:value (name (:id p)) :label (:name p)})
              (filter #(contains? configured (:id %)) (models/get-providers)))))
    :handler
    (fn [cs args]
      (let [provider (some-> (first (str/split args #"\s+")) str/trim not-empty keyword)
            configured (auth/get-credentials)
            credential-label (fn [cred]
                               (if (= :oauth (:type cred)) "OAuth credential" "API key"))]
        (cond
          (nil? provider)
          (ui/chat-history-add-message! (:chat-history cs)
                                        {:role :assistant
                                         :content (if (seq configured)
                                                    (str "Usage: /logout <provider>"
                                                         "\nSaved credentials: "
                                                         (str/join ", "
                                                                   (map (fn [[k v]]
                                                                          (str (name k) " (" (credential-label v) ")"))
                                                                        configured)))
                                                    "No stored credentials to remove. /logout only removes credentials saved by /login; environment variables are unchanged.")})

          (nil? (models/get-provider provider))
          (ui/show-warning! (:chat-history cs) (str "Unknown provider: " (name provider)))

          (not (contains? configured provider))
          (ui/chat-history-add-message! (:chat-history cs)
                                        {:role :assistant
                                         :content (str "No saved credential for " (name provider)
                                                       " — environment variables are unchanged.")})

          :else
          (let [kind (credential-label (get configured provider))]
            (auth/remove-credential! provider)
            (ui/chat-history-add-message! (:chat-history cs)
                                          {:role :assistant
                                           :content (str "Removed stored " kind " for " (name provider)
                                                         ". Environment variables are unchanged.")})
            (when (and (:session-atom cs) (:footer-comp cs) (:footer-provider cs))
              (update-footer! cs))))))}))

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
  (let [{:keys [chat-history]} cs
        agent-state @(:agent-state cs)]
    (if-not (= :idle (agent/get-status agent-state))
      (ui/chat-history-add-message! chat-history
                                    {:role :info :label "Reload"
                                     :content "Wait for the current response to finish before reloading."})
      (try
        ;; pi: settingsManager.reload() + theme re-registration
        (let [config (cfg/init!)
              ;; pi: keybindings.reload() — re-read keybindings.edn
              _ (app-kb/reload-agent-keybindings!)
        ;; pi: session.reload → extension shutdown/start
              _ (extensions/ui-reset!)
              _ (extensions/clear-extensions!)
              _ (doseq [d (cfg/resource-dirs config :extensions-dir ".kmet/extensions")]
                  (extensions/load-extensions-from-dir d))
              ;; pi: model-runtime.refresh — recompose providers from models.edn
              _ (models/load-models-config!)
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
          ;; pi: restoreChatBeforeSessionStart — re-apply hideThinkingBlock
          ;; from settings to existing chat messages
          (ui/chat-history-set-thinking-hidden! chat-history
                                                (cfg/get-hide-thinking-block config))
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
                                         :content (str "Reloaded keybindings, extensions, skills, prompts, themes, context files, and models.edn."
                                                       (when-let [err (models/get-model-config-error)]
                                                         (str " [models.edn: " err "]")))}))
        (catch Exception e
          (debug/log "reload failed: " e)
          (ui/chat-history-add-message! chat-history
                                        {:role :info :label "Reload"
                                         :content (str "Reload failed: "
                                                       (or (ex-message e)
                                                           (.getName (class e))))}))))))

(defn- register-not-implemented-commands!
  "Register pi's builtin slash commands that kmet does not implement yet,
   all bound to the command-not-implemented handler. Keeps the command list
   in sync with pi (packages/coding-agent/src/core/slash-commands.ts) so
   /help and autocomplete show the full surface."
  []
  (doseq [{:keys [name description argument-hint]}
          [{:name "import" :description "Import and resume a session from a JSONL file"}
           {:name "hotkeys" :description "Show all keyboard shortcuts"}]]
    (commands/register-command!
     {:name name
      :description description
      :argument-hint argument-hint
      :handler (fn [cs _]
                 (command-not-implemented cs name))})))

;; ─── Resume session ────────────────────────────────────────────────────────

(defn- custom-message-text
  "Plain text of a :custom message's content (string or text blocks) — the
   display text for custom_message entries/messages in the TUI (pi renders
   custom messages as labeled info boxes)."
  [m]
  (if (string? (:content m))
    (:content m)
    (str/join (for [b (:content m)
                    :when (= :text (:type b))]
                (:text b)))))

(defn- replay-branch!
  "Replay a session's active branch into the chat history (pi:
   renderInitialMessages — only message entries; session_info, label and
   model/thinking change entries are metadata and never rendered; custom
   (extension state) entries render when an extension registered a renderer
   for their custom-type; compaction and branch_summary entries render their
   summary text; custom_message entries render as labeled info boxes when
   their display flag is set)."
  [cs sess]
  (ui/chat-history-clear! (:chat-history cs))
  (doseq [e (session/get-branch sess)
          :when (not (contains? #{:session_info :label :model-change
                                  :thinking-level-change} (:role e)))]
    (let [role (:role e)]
      (cond
        (= role :custom)
        ;; extension state entries render only with a registered renderer
        ;; (pi: registerEntryRenderer + CustomEntryComponent)
        (when-let [renderer (extensions/get-entry-renderer (:custom-type e))]
          (when-let [msg (renderer e)]
            (ui/chat-history-add-message! (:chat-history cs) msg)))

        (= role :custom-message)
        ;; extension custom messages render only when display is set (pi:
        ;; the display flag controls TUI rendering); content may be a string
        ;; or a block vector (pi CustomMessageEntry)
        (when (:display e)
          (ui/chat-history-add-message! (:chat-history cs)
                                        {:role :info
                                         :content (custom-message-text e)
                                         :label (:custom-type e)}))

        :else
        (let [content (if (contains? #{:compaction :branch-summary} role)
                        (or (:summary e) "")
                        (str/join
                         (keep (fn [b]
                                 (case (:type b)
                                   :text (:text b)
                                   :tool_result (:content b)
                                   nil))
                               (:content e))))]
          (ui/chat-history-add-message! (:chat-history cs)
                                        (cond-> {:role role :content content}
                                          (= role :assistant) (assoc :thinking (:thinking e))
                                          (= role :info) (assoc :label (:label e))
                                          (= role :tool)
                                          (assoc :name (or (:name e) "tool")
                                                 :is-error (:is-error e false)
                                                 :truncation (:truncation e)
                                                 :details (:details e)))))))))

(defn- restore-session!
  "Restore a session into the UI and the agent: swap the active session,
   rebuild the agent's in-memory context from the session branch (pi: the
   session is the source of truth — buildContextEntries; steered and
   follow-up user messages live in the branch and must come back for the
   next LLM call), and replay the branch into the chat history. session_info
   entries are metadata — never rendered (pi: only message entries are
   replayed on resume). When APPLY-SETTINGS? is true (startup resume,
   /resume — pi: createAgentSession), the session-derived model/thinking are
   applied to the agent and the footer refreshes; fork and clone pass false
   (pi: navigateTree keeps the current agent state)."
  [cs sess apply-settings?]
  (reset! (:session-atom cs) sess)
  (extensions/set-session! sess)
  (let [new-ag (assoc @(:agent-state cs) :session sess)]
    (reset! (:agent-state cs) new-ag))
  (agent/restore-session-context! @(:agent-state cs))
  (when apply-settings?
    (agent/apply-session-settings! @(:agent-state cs))
    (sync-footer-model! cs))
  (replay-branch! cs sess)
  (update-footer! cs))

;; ─── Session tree navigation (pi: TreeSelectorComponent) ──────────────────

(defn- session-entry-text
  "Plain trimmed text of a session entry's content blocks (pi:
   extractUserMessageText — used for fork/tree editor restore)."
  [e]
  (let [content (:content e)]
    (if (string? content)
      (str/trim content)
      (str/trim (str/join (map :text (filter #(= :text (:type %)) content)))))))

(defn- complete-tree-navigation!
  "Apply a tree navigation (pi: navigateTree tail): branch the session leaf
   (with an optional branch summary), rebuild the agent context and chat
   history from the new branch, restore USER-MSG-TEXT into the editor when
   navigating to a user message (only when the editor is empty), attach
   LABEL to the summary/target entry when given, and emit :session-tree."
  [cs sess old-leaf target-leaf summary user-msg-text from-extension? label]
  (try
    (let [summary-entry (if summary
                          (session/branch-with-summary! sess target-leaf summary)
                          (do (if (nil? target-leaf)
                                (session/reset-leaf! sess)
                                (session/branch! sess target-leaf))
                              nil))
          label-target (if summary-entry (:id summary-entry) target-leaf)]
      (when (and label label-target)
        (session/set-label! sess label-target label))
      (agent/restore-session-context! @(:agent-state cs))
      (replay-branch! cs sess)
      ;; pi: restore the user message only when the editor is empty — a draft
      ;; the user is composing is not clobbered by navigation
      (when (and user-msg-text
                 (str/blank? (editor-text-get (:editor cs))))
        (editor-text-set! (:editor cs) user-msg-text))
      (update-footer! cs)
      (event-bus/emit-event!
       (cond-> {:type :session-tree
                :new-leaf-id @(:leaf-id sess)
                :old-leaf-id old-leaf
                :from-extension? (boolean from-extension?)}
         summary-entry (assoc :summary-entry summary-entry)))
      (ui/chat-history-add-message! (:chat-history cs)
                                    {:role :assistant
                                     :content (if summary
                                                "Navigated to the selected point (branch summarized)."
                                                "Navigated to the selected point.")})
      (tui/tui-request-render (:tui cs)))
    (catch Exception e
      (debug/log "tree navigation failed: " e)
      (ui/chat-history-add-message! (:chat-history cs)
                                    {:role :info :label "Tree"
                                     :content (str "Navigation failed: " (ex-message e))})
      (tui/tui-request-render (:tui cs)))))

(defn- branch-summarize-and-apply!
  "Run the LLM branch summarization (pi: navigateTree summarize) with the
   BranchSummaryStatusIndicator and editor-escape abort, then branch with
   the summary. On abort/failure the branch is unchanged. PREP is the
   :session-before-tree preparation map; ABORT-ATOM cancels the call."
  [cs sess old-leaf target-leaf user-msg-text prep abort-atom custom-instructions]
  (let [ag @(:agent-state cs)
        ed (:editor cs)
        prev-interrupt (get @(:action-handlers ed) "app.interrupt")
        indicator (ui/make-branch-summary-status-indicator)
        done (promise)]
    ;; escape → abort (pi: defaultEditor.onEscape = abortBranchSummary)
    (editor/editor-set-on-action! ed "app.interrupt"
                                  (fn [] (reset! abort-atom true)))
    (show-status-indicator! cs :branch-summary indicator)
    (tui/tui-request-render (:tui cs))
    ;; render driver: tick the indicator while the summarization runs
    (future
      (while (not (realized? done))
        (Thread/sleep 100)
        (tui/tui-request-render (:tui cs))))
    (future
      (try
        (deliver done (agent/generate-branch-summary
                       ag (:entries-to-summarize prep) custom-instructions abort-atom))
        (catch Exception e
          (debug/log "branch summarization failed: " e)
          (deliver done nil))))
    (future
      (let [result (deref done 120000 :timeout)]
        (editor/editor-set-on-action! ed "app.interrupt" prev-interrupt)
        (clear-status-indicator! cs :branch-summary)
        (cond
          (= result :timeout)
          (ui/chat-history-add-message! (:chat-history cs)
                                        {:role :info :label "Tree"
                                         :content "Branch summarization timed out — branch unchanged."})

          (nil? result)
          (ui/chat-history-add-message! (:chat-history cs)
                                        {:role :info :label "Tree"
                                         :content "Branch summarization failed — branch unchanged."})

          (:aborted result)
          (ui/chat-history-add-message! (:chat-history cs)
                                        {:role :info :label "Tree"
                                         :content "Branch summarization cancelled — branch unchanged."})

          :else
          (complete-tree-navigation! cs sess old-leaf target-leaf
                                     (:summary result) user-msg-text false nil))
        (tui/tui-request-render (:tui cs))))))

(defn- navigate-tree!
  "Branch the session to the selected tree entry (pi: navigateTree):
   selecting a user message re-opens it in the editor (leaf = its parent),
   any other entry becomes the new leaf. Emits :session-before-tree
   (extensions may cancel, supply the summary, or override
   custom-instructions/label), optionally summarizes the abandoned path,
   branches, and emits :session-tree."
  [cs sess entry wants-summary custom-instructions]
  (let [old-leaf @(:leaf-id sess)
        target-leaf (if (= :user (:role entry)) (:parent-id entry) (:id entry))
        entries (session/branch-summary-entries sess old-leaf (:id entry))
        user-msg-text (when (= :user (:role entry)) (session/session-entry-text entry))
        abort-atom (atom false)
        prep {:target-id (:id entry)
              :old-leaf-id old-leaf
              :common-ancestor-id (session/common-ancestor-id sess old-leaf (:id entry))
              :entries-to-summarize entries
              :user-wants-summary (boolean wants-summary)
              :custom-instructions custom-instructions
              :replace-instructions false
              :label nil}
        ext-result (event-bus/emit-event! {:type :session-before-tree
                                           :preparation prep
                                           :signal abort-atom})
        custom-instructions (or (:custom-instructions ext-result) custom-instructions)]
    (cond
      (and wants-summary (empty? entries))
      ;; nothing abandoned to summarize — branch without a summary
      (complete-tree-navigation! cs sess old-leaf target-leaf nil user-msg-text
                                 false (:label ext-result))

      (:cancel ext-result)
      (ui/chat-history-add-message! (:chat-history cs)
                                    {:role :info :label "Tree"
                                     :content "Navigation cancelled by an extension."})

      (and wants-summary (:summary ext-result))
      (complete-tree-navigation! cs sess old-leaf target-leaf
                                 (:summary ext-result) user-msg-text true
                                 (:label ext-result))

      (not wants-summary)
      (complete-tree-navigation! cs sess old-leaf target-leaf nil user-msg-text
                                 false (:label ext-result))

      :else
      (branch-summarize-and-apply! cs sess old-leaf target-leaf user-msg-text
                                   prep abort-atom custom-instructions))))

(defn- prompt-custom-summary!
  "Ask for custom summarization instructions, then navigate with them
   (pi: 'Summarize with custom prompt'). Escape loops back to the summarize
   choice."
  [cs sess entry]
  (tui/tui-show-overlay
   (:tui cs)
   (dialogs/make-extension-input
    "Custom branch summarization instructions"
    (fn [instructions]
      (tui/tui-hide-overlay (:tui cs))
      (navigate-tree! cs sess entry true (str/trim instructions)))
    (fn []
      (tui/tui-hide-overlay (:tui cs))
      (ask-branch-summary cs sess entry))
    (th/get-current-theme)))
  (tui/tui-request-render (:tui cs)))

(defn- ask-branch-summary
  "Ask whether to summarize the abandoned branch before branching (pi: the
   Summarize branch? selector), then navigate. Escape re-opens the tree."
  [cs sess entry]
  (let [items [{:value "none" :label "No summary"}
               {:value "summarize" :label "Summarize"}
               {:value "custom" :label "Summarize with custom prompt"}]
        sl-ref (atom nil)
        on-select (fn [_]
                    (when-let [sel (select-list/select-list-get-selected @sl-ref)]
                      (tui/tui-hide-overlay (:tui cs))
                      (case (:value sel)
                        "none" (navigate-tree! cs sess entry false nil)
                        "summarize" (navigate-tree! cs sess entry true nil)
                        "custom" (prompt-custom-summary! cs sess entry))))
        on-escape (fn []
                    (tui/tui-hide-overlay (:tui cs))
                    (show-session-tree cs
                                       (fn [entry]
                                         (ask-branch-summary cs @(:session-atom cs) entry))))
        sl (select-list/make-select-list items
                                         :height 3
                                         :header "Summarize branch?"
                                         :on-select on-select
                                         :on-escape on-escape)]
    (reset! sl-ref sl)
    (tui/tui-show-overlay (:tui cs) sl :width 42 :height 3)
    (tui/tui-request-render (:tui cs))))

;; ─── Fork / clone (pi: /fork, /clone) ─────────────────────────────────────

(defn- fork-at!
  "Fork the session before the given user message and switch to the fork
   (pi: runtimeHost.fork — the new session starts at the message's parent;
   the message text is restored to the editor for re-editing). Forking the
   first user message (no parent) starts an empty session linked to this
   one."
  [cs entry-id]
  (if @(:running-turn? cs)
    (ui/chat-history-add-message! (:chat-history cs)
                                  {:role :assistant
                                   :content "Wait for the current response to finish before forking."})
    (let [sess @(:session-atom cs)
          entry (session/get-entry sess entry-id)]
      (if (nil? entry)
        (ui/chat-history-add-message! (:chat-history cs)
                                      {:role :assistant :content "Invalid entry for forking."})
        (try
          (let [fork (if (:parent-id entry)
                       (session/fork-session sess (:parent-id entry))
                       (session/create-session (ensure-cwd-session-dir)
                                               {:parent-session (:file sess)}))]
            (if (nil? fork)
              (ui/chat-history-add-message! (:chat-history cs)
                                            {:role :assistant :content "Failed to create forked session."})
              (do
                (debug/log "forked session " (:id fork) " from " (:id sess))
                (restore-session! cs fork false)
                (editor-text-set! (:editor cs) (session/session-entry-text entry))
                (ui/chat-history-add-message! (:chat-history cs)
                                              {:role :assistant
                                               :content (str "Forked to new session " (subs (:id fork) 0 8) ".")})
                (tui/tui-request-render (:tui cs)))))
          (catch Exception e
            (debug/log "fork failed: " e)
            (ui/chat-history-add-message! (:chat-history cs)
                                          {:role :info :label "Fork"
                                           :content (str "Fork failed: " (ex-message e))})
            (tui/tui-request-render (:tui cs))))))))

(defn- clone-current-session!
  "Duplicate the session at its current position (pi: /clone → fork at the
   current leaf) and switch to the clone."
  [cs]
  (let [sess @(:session-atom cs)]
    (cond
      @(:running-turn? cs)
      (ui/chat-history-add-message! (:chat-history cs)
                                    {:role :assistant
                                     :content "Wait for the current response to finish before cloning."})

      (nil? sess)
      (ui/chat-history-add-message! (:chat-history cs)
                                    {:role :assistant :content "No active session."})

      (nil? @(:leaf-id sess))
      (ui/chat-history-add-message! (:chat-history cs)
                                    {:role :assistant :content "Nothing to clone yet."})

      (not (fs/exists? (:file sess)))
      (ui/chat-history-add-message! (:chat-history cs)
                                    {:role :assistant
                                     :content "Wait for the first assistant response before cloning."})

      :else
      (try
        (let [fork (session/clone-session sess)]
          (if (nil? fork)
            (ui/chat-history-add-message! (:chat-history cs)
                                          {:role :assistant :content "Failed to clone session."})
            (do
              (debug/log "cloned session " (:id fork) " from " (:id sess))
              (restore-session! cs fork false)
              (ui/chat-history-add-message! (:chat-history cs)
                                            {:role :assistant
                                             :content (str "Cloned to new session " (subs (:id fork) 0 8) ".")})
              (tui/tui-request-render (:tui cs)))))
        (catch Exception e
          (debug/log "clone failed: " e)
          (ui/chat-history-add-message! (:chat-history cs)
                                        {:role :info :label "Clone"
                                         :content (str "Clone failed: " (ex-message e))})
          (tui/tui-request-render (:tui cs)))))))

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
;; :active-status-kind records which indicator is in the container (:working /
;; :retry / :compaction / nil when idle) so a stale end event can't stop an
;; indicator that was already replaced (pi: clearStatusIndicator(kind) checks
;; the active kind and no-ops on mismatch).

(defn- show-status-indicator!
  "Replace the status container child with the given indicator (pi:
   showStatusIndicator — disposes the active indicator). KIND records which
   indicator is active for kind-gated clears."
  [cs kind indicator]
  (ui/status-indicator-stop! (:status-indicator cs))
  (container/container-clear (:status-container cs))
  (container/container-add-child (:status-container cs) indicator)
  (reset! (:active-status-kind cs) kind)
  (tui/tui-request-render (:tui cs)))

(defn- activate-working-indicator!
  "Restore the default working StatusIndicator as the container child and
   activate it (pi: agent_start → showStatusIndicator(new
   WorkingStatusIndicator)). Used when a new LLM call starts after a retry
   backoff or compaction, which swapped in a transient indicator."
  [cs]
  (container/container-clear (:status-container cs))
  (container/container-add-child (:status-container cs) (:status-indicator cs))
  (ui/status-indicator-start! (:status-indicator cs))
  (reset! (:active-status-kind cs) :working)
  (tui/tui-request-render (:tui cs)))

(defn- clear-status-indicator!
  "Restore the idle two-row status (pi: clearStatusIndicator → idleStatus).
   With KIND, only clears when that indicator is currently active — a stale
   end event (e.g. auto-retry-end arriving after the working indicator was
   revived) then no-ops instead of stopping the working spinner."
  [cs & [kind]]
  (when (or (nil? kind) (= kind @(:active-status-kind cs)))
    (container/container-clear (:status-container cs))
    (container/container-add-child (:status-container cs) (:status-indicator cs))
    (ui/status-indicator-stop! (:status-indicator cs))
    (reset! (:active-status-kind cs) nil)
    (tui/tui-request-render (:tui cs))))

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
  (let [{:keys [steering follow-up]} (agent/queued-messages @(:agent-state cs))]
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
            ag @(:agent-state cs)
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

              ;; Record in context + session — deferred while the agent is
              ;; streaming so tool_use/tool_result ordering is preserved
              ;; (pi: recordBashResult queues pending bash messages, flushed
              ;; by run-agent-turn once the run settles)
              (agent/add-bash-result! @(:agent-state cs) command result exclude-from-context?)

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

(defn- start-agent-run!
  "Start an agent run: set turn state, show the working indicator + animation
   timer, wire the streaming callbacks. The user message and the assistant
   streaming placeholder are created from the loop's :message-start events
   (pi: message_start → addMessageToChat / new streaming component), so the
   initial prompt and consumed steering/follow-up messages all land in the
   chat at the same lifecycle point.
   MESSAGE — optional initial user message; when nil the run continues on the
   existing context without adding a message (the /continue path, where the
   last entry is an unanswered user message or a dangling tool result the
   model must pick up)."
  [cs & [message]]
  (reset! (:running-turn? cs) true)
  (activate-working-indicator! cs)
  (start-anim-timer! cs)
  (update-footer! cs)
  (tui/tui-request-render (:tui cs))
  (agent/run-agent-turn @(:agent-state cs)
                        (cond-> {:on-text #(on-agent-text cs %)
                                 :on-thinking #(on-agent-thinking cs %)
                                 :on-done (fn [_] (on-agent-done cs))
                                 :on-error #(on-agent-error cs %)}
                          message (assoc :message message))))

(defn- send-message
  "Send text to the agent: steer while streaming, else start a new turn.
   Input hooks must already have been applied (pi: agent run happens after
   hook/expansion processing). Returns nil."
  [cs text]
  (if @(:running-turn? cs)
    ;; Agent running: steer the current run (pi: steeringQueue). The message
    ;; is only queued (and shown in the pending display) — it lands in the
    ;; chat as a user message when the loop consumes it (:message-start,
    ;; pi: message_start → addMessageToChat). The in-flight response keeps
    ;; streaming into its own message until then.
    (do
      (debug/log "user steered: " text)
      (agent/steer! @(:agent-state cs) text)
      (update-pending-messages! cs)
      (update-footer! cs)
      (tui/tui-request-render (:tui cs)))
    (do
      (debug/log "user submitted: " text)
      (start-agent-run! cs text))))

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
   input event, then agent run). Records the submitted text in the editor
   history so Up/Down can browse it (pi: editor.addToHistory on submit —
   regular messages, steered messages, and follow-ups all land there).
   Returns nil."
  [cs text]
  (when-let [text (apply-hooks cs text)]
    (let [ed @(:current-editor-atom cs)]
      ;; IEditorComponent when available (custom editors), else the
      ;; field-based fn (duck-typed editors — same pattern as editor-text-set!)
      (if (satisfies? protocols/IEditorComponent ed)
        (protocols/editor-add-to-history! ed text)
        (editor/editor-push-history! ed text)))
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
        ;; Pi: handleFollowUp queues a follow-up (processed after the run
        ;; settles). Not added to the chat here — like steering, it appears
        ;; as a user message when the loop consumes it (:message-start,
        ;; pi: message_start → addMessageToChat).
        (do (agent/follow-up! @(:agent-state cs) text)
            (update-pending-messages! cs))
        (handle-submit cs text))
      (tui/tui-request-render (:tui cs)))))

(defn- restore-queued-messages!
  "Restore queued steering/follow-up messages to the editor, combined with
   the current text, and clear the queues (pi: restoreQueuedMessagesToEditor).
   Returns the number of messages restored."
  [cs]
  (let [{:keys [steering follow-up]} (agent/queued-messages @(:agent-state cs))
        all (into (vec steering) follow-up)]
    (when (seq all)
      (let [ed @(:current-editor-atom cs)
            current (editor-text-get ed)
            queued-text (str/join "\n\n" all)
            combined (str/join "\n\n" (remove str/blank? [queued-text current]))]
        (agent/clear-queues! @(:agent-state cs))
        (editor-text-set! ed combined)))
    (count all)))

(defn- handle-dequeue
  "Pi: handleDequeue — Alt+Up. Restore all queued steering/follow-up
   messages to the editor, combined with the current text."
  [cs]
  (let [restored (restore-queued-messages! cs)]
    (ui/chat-history-show-status!
     (:chat-history cs)
     (if (pos? restored)
       (str "Restored " restored " queued message"
            (when (> restored 1) "s") " to editor")
       "No queued messages to restore"))
    (tui/tui-request-render (:tui cs))))

(defn- handle-cancel
  "Cancel the current agent turn, bash command, or in-progress compaction."
  [cs]
  (when @(:compacting? @(:agent-state cs))
    ;; Escape during compaction aborts the summarization (pi: onEscape →
    ;; abortCompaction). The compaction-end event clears the indicator and
    ;; reports the cancellation.
    (debug/log "compaction cancelled by user")
    (reset! (:signal @(:agent-state cs)) true)
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
    ;; pi: restoreQueuedMessagesToEditor({abort: true}) — queued steering/
    ;; follow-up messages return to the editor instead of vanishing when
    ;; cancel-turn clears the queues (they reach the chat only once the
    ;; loop consumes them, so cancel would otherwise lose them entirely).
    (let [restored (restore-queued-messages! cs)]
      (agent/cancel-turn @(:agent-state cs))
      ;; Remove empty streaming placeholder if present
      (let [ch (:chat-history cs)]
        (when-let [s @(:streaming-atom ch)]
          (if (and (empty? @(:text-atom (:component s)))
                   (empty? @(:thinking-text-atom (:component s))))
            (do (ui/chat-history-remove-last! ch) (reset! (:streaming-atom ch) nil))
            (do (ui/chat-history-finalize-streaming! ch) (ui/chat-history-finalize-thinking! ch)))))
      (ui/chat-history-add-message! (:chat-history cs)
                                    {:role :assistant :content (th/dim "(cancelled)")})
      (when (pos? restored)
        (ui/chat-history-show-status!
         (:chat-history cs)
         (str "Restored " restored " queued message"
              (when (> restored 1) "s") " to editor"))))
    (reset! (:running-turn? cs) false)
    (update-footer! cs)
    (tui/tui-request-render (:tui cs))))

;; ─── External editor (pi: handleOpenExternalEditor) ────────────────────────

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
        model (models/resolve-config-model config)

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

        ;; Migrate legacy keybinding ids in keybindings.edn (pi: migrations
        ;; runner calls migrateKeybindingsConfigFile at startup)
        _ (app-kb/migrate-keybindings-config-file!)

        ;; Initialize keybindings (global singleton for key-hint + input
        ;; handling); persisted overrides load from keybindings.edn
        ;; (pi: KeybindingsManager.create)
        _ (let [kmgr (app-kb/create-agent-keybindings-manager)]
            (tui-kb/set-global-keybindings! kmgr)
            (app-kb/set-key-hint-theme-fns!
             #(th/dim %)
             #(th/fg (cfg/get-theme config) :muted %)))

        ;; Components (define before agent state so on-event can reference them)
        sp1 (spacer/make-spacer 1)
        ch (ui/make-chat-history :theme (cfg/get-theme config)
                                 :thinking-hidden (cfg/get-hide-thinking-block config))
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
            :http-idle-timeout-ms (:http-idle-timeout-ms config)
            :thinking (:thinking config :off)
            ;; pi: retry settings (settings.edn :retry block — enabled gates
            ;; max-retries to 0)
            :max-retries (let [retry (cfg/get-retry-settings config)]
                           (if (:enabled retry) (:max-retries retry) 0))
            :base-delay-ms (:base-delay-ms (cfg/get-retry-settings config))
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
                           ;; Pi: live partial content from streaming tools (bash);
                           ;; no periodic pings — the render is cached (track!), so
                           ;; the elapsed timer ticks when content arrives here
                           ;; (a silent long-running tool freezes Elapsed until the
                           ;; next chunk or completion, matching pi's cached render)
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
                          :agent-end
                           ;; Pi: maybeShowCacheMissNotice — a significant
                           ;; prompt-cache miss on the completed turn
                          (do (when-let [cs @cs-ref]
                                (maybe-show-cache-miss-notice! cs))
                              (tui/tui-request-render t))
                          :queue-update
                           ;; Queued steering/follow-up messages changed (pi:
                           ;; queue_update → updatePendingMessagesDisplay)
                          (do (when-let [cs @cs-ref]
                                (update-pending-messages! cs))
                              (tui/tui-request-render t))
                          :turn-start
                           ;; A new LLM call is starting. After a retry backoff
                           ;; or compaction the status container holds a
                           ;; transient indicator (or the stopped working
                           ;; indicator); revive the working spinner so the
                           ;; call streams under "Working..." (pi: the session
                           ;; emits a fresh agent_start after retry/compaction
                           ;; via agent.continue(), re-showing the
                           ;; WorkingStatusIndicator — kmet's loop recurs
                           ;; in-turn, so turn-start is the equivalent signal).
                          (do (when-let [cs @cs-ref]
                                (when (and @(:running-turn? cs)
                                           (not= :working @(:active-status-kind cs)))
                                  (activate-working-indicator! cs)))
                              (tui/tui-request-render t))
                          :auto-retry-start
                           ;; Clear partial streaming text so the retried stream
                           ;; starts fresh, and show the retry countdown (pi:
                           ;; auto_retry_start → RetryStatusIndicator)
                          (do (ui/chat-history-clear-streaming! ch)
                              (when-let [cs @cs-ref]
                                (show-status-indicator!
                                 cs :retry
                                 (ui/make-retry-status-indicator
                                  (:attempt evt) (:max-attempts evt) (:delay-ms evt)
                                  :cancel-hint (fmt-key-display
                                                (app-kb/key-text "app.interrupt")))))
                              (tui/tui-request-render t))
                          :auto-retry-end
                           ;; Retry finished (pi: auto_retry_end →
                           ;; clearStatusIndicator("retry")). Kind-gated: when
                           ;; the retried call already started (turn-start
                           ;; revived the working indicator) this no-ops and
                           ;; the working spinner keeps spinning.
                          (do (when-let [cs @cs-ref]
                                (clear-status-indicator! cs :retry))
                              (tui/tui-request-render t))
                          :compaction-start
                           ;; Session compaction in progress (pi:
                           ;; compaction_start → CompactionStatusIndicator);
                           ;; the hint is truthful — escape aborts it
                          (do (when-let [cs @cs-ref]
                                (show-status-indicator!
                                 cs :compaction
                                 (ui/make-compaction-status-indicator
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
                                (clear-status-indicator! cs :compaction)
                                (when (and (:aborted evt)
                                           (= :manual (:reason evt)))
                                  (ui/chat-history-show-status!
                                   ch "Compaction cancelled")))
                              (tui/tui-request-render t))
                          :context-replaced
                           ;; Rebuild the chat history to mirror the replaced
                           ;; context; custom messages honor the display flag
                           ;; (pi: display controls TUI rendering — hidden
                           ;; ones stay in the LLM context only)
                          (do (ui/chat-history-rebuild!
                               ch
                               (map (fn [m]
                                      (if (and (= :custom (:role m)) (:display m))
                                        (assoc m :role :info
                                               :content (custom-message-text m)
                                               :label (:custom-type m))
                                        m))
                                    (remove #(and (= :custom (:role %))
                                                  (not (:display %)))
                                            (:messages evt))))
                              (tui/tui-request-render t))
                          :message-start
                           ;; Pi: message_start → user messages (the initial
                           ;; prompt and consumed steering/follow-up messages)
                           ;; land in the chat here; assistant message starts
                           ;; finalize the previous turn's streaming placeholder
                           ;; and open a fresh one, so a follow-up continuation
                           ;; never merges into the prior response; before-
                           ;; agent-start injected messages (role :info) display
                           ;; as labeled info boxes above the response. Content
                           ;; is normalized from text blocks to a string for
                           ;; the info box.
                          (case (:role (:message evt))
                            :user (do (ui/chat-history-add-message! ch (:message evt))
                                      (when-let [cs @cs-ref]
                                        (update-pending-messages! cs))
                                      (tui/tui-request-render t))
                            :assistant (do (ui/chat-history-finalize-streaming! ch)
                                           (ui/chat-history-finalize-thinking! ch)
                                           (ui/chat-history-start-streaming! ch)
                                           (tui/tui-request-render t))
                            :info (let [m (:message evt)
                                        text (if (string? (:content m))
                                               (:content m)
                                               (str/join
                                                (for [b (:content m)
                                                      :when (= :text (:type b))]
                                                  (:text b))))]
                                    (ui/chat-history-insert-before-streaming! ch
                                                                              (assoc m :content text))
                                    (tui/tui-request-render t))
                            ;; extension custom messages (pi: custom messages
                            ;; render as labeled info boxes when display=true)
                            :custom (do (when (:display (:message evt))
                                          (let [m (:message evt)]
                                            (ui/chat-history-insert-before-streaming!
                                             ch (assoc m :role :info
                                                       :content (custom-message-text m)
                                                       :label (:custom-type m)))))
                                        (tui/tui-request-render t))
                            nil))))
            ;; Session scoped model list for cycle-model! / the scoped-models
            ;; selector (pi: resolveModelScope → session.scopedModels at
            ;; startup — full "provider/id" refs so cycling can switch
            ;; providers)
        _ (when (seq (:models config))
            (let [{:keys [models warnings]}
                  (resolver/resolve-model-scope-models (:models config)
                                                       (models/get-models))]
              (doseq [w warnings]
                (binding [*out* *err*] (println "Warning:" w)))
              (agent/set-scoped-models! ag (mapv model-full-id models))))
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
             :provider-count (count (distinct (map :provider (scoped-or-available-models ag))))
             ;; Phase 2: context window from the resolved Model record, falling
             ;; back to the settings value when the model is unknown (pi footer
             ;; contextPercentDisplay)
             :context-window (or (:context-window (models/get-model provider model))
                                 (:context-window config))
             :model @(:model ag) :provider @(:provider ag) :thinking @(:thinking ag))
        ftr (ui/make-footer :theme (cfg/get-theme config)
                            :provider fdp
                            :auto-compact (boolean (or (:compact-threshold config)
                                                       (:compact-token-threshold config))))

        ;; Core state (status-indicator/status-container filled in after layout)
        cs (map->CoreState {:tui t
                            :agent-state (atom ag)
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
                            :pending-messages-container (container/make-container [pm])
                            :active-status-kind (atom nil)})]

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
          ;; Theme controller (pi: InteractiveThemeController) — created in
          ;; the layout so CoreState carries it for all handlers (slash
          ;; commands, /reload, extension registry); applies the configured
          ;; theme, drives auto light/dark sync via color-scheme
          ;; notifications, and re-themes the app components live on change
          ;; (the on-changed callback — pi: notifyChanged → onChanged). The
          ;; callback runs only after the layout is fully bound.
          tc (theme-ctrl/make-theme-controller
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
          cs (assoc cs :status-indicator si :theme-controller tc)
          ;; Pi layout (interactive-mode.ts setupUiLayout): the TUI root is a
          ;; single flat document — the transcript (header, loaded resources,
          ;; chat) followed top-to-bottom by pending messages, status, widgets
          ;; above the editor, editor, widgets below, footer. The render loop
          ;; keeps the viewport pinned to the document end: when the document
          ;; grows past the screen height it scrolls natively into the
          ;; terminal scrollback, so the whole interface scrolls together and
          ;; the editor (document end) stays visible at the bottom.
          header-container (container/make-container [sp1 hdr sp1])
          loaded-resources-container (container/make-container [lr])
          chat-container (container/make-container [ch])
          document-container (container/make-container [header-container
                                                        loaded-resources-container
                                                        chat-container])
          pending-messages-container (:pending-messages-container cs)
          status-container (container/make-container [si])
          ;; pi: renderWidgets initializes the above-editor container with a
          ;; default spacer when no extension widgets are registered
          widget-container-above (container/make-container [sp2])
          editor-container (container/make-container [ed])
          widget-container-below (container/make-container)
          cs (assoc cs :status-container status-container)]

      ;; Add components in pi's layout-root order: the transcript document
      ;; first, then the dock children top-to-bottom (pending messages,
      ;; status, widgets above, editor, widgets below, footer)
      (tui/tui-add-child t document-container)
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
          ;; pi: showStatus feedback on toggle + persist hideThinkingBlock to
          ;; settings so the state survives restarts (SettingsManager.save;
          ;; write errors are recorded — the toggle still applies)
                                      (let [hidden? (ui/chat-history-toggle-thinking-hidden! ch)]
                                        (try (cfg/set-hide-thinking-block! hidden?)
                                             (catch Exception e
                                               (debug/log "Failed to persist hide-thinking-block: " e)))
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
      ;; Model selection/cycling (pi: onAction selectModel/cycleModelForward/
      ;; cycleModelBackward — the session scoped list feeds cycling (set via
      ;; /scoped-models, seeded from --models / settings :models); without
      ;; scoped models all available models cycle; a scoped entry may switch
      ;; the provider)
      (editor/editor-set-on-action! ed "app.model.select"
                                    (fn [] (show-model-selector cs)))
      (editor/editor-set-on-action! ed "app.model.cycleForward"
                                    (fn []
                                      (if (agent/cycle-model! @(:agent-state cs) 1)
                                        (sync-footer-model! cs)
                                        (ui/chat-history-show-status!
                                         (:chat-history cs)
                                         (if (seq (agent/get-scoped-models @(:agent-state cs)))
                                           "Only one model in scope"
                                           "Only one model available")))))
      (editor/editor-set-on-action! ed "app.model.cycleBackward"
                                    (fn []
                                      (if (agent/cycle-model! @(:agent-state cs) -1)
                                        (sync-footer-model! cs)
                                        (ui/chat-history-show-status!
                                         (:chat-history cs)
                                         (if (seq (agent/get-scoped-models @(:agent-state cs)))
                                           "Only one model in scope"
                                           "Only one model available")))))

      ;; Initialize footer (header content is produced lazily by the
      ;; ExpandableText fns on first render)
      (update-footer! cs)

      ;; Extension UI registry (pi: ExtensionUIContext) — installed after the
      ;; layout is live so extensions can drive the UI from event handlers
      (build-extension-ui-registry {:tui t :cs cs}
                                   {:ed ed :ftr ftr :hdr hdr :ch ch
                                    :sp1 sp1 :fdp fdp
                                    :header-container header-container
                                    :editor-container editor-container
                                    :widget-container-above widget-container-above
                                    :widget-container-below widget-container-below}
                                   tc)

      ;; Expose the fully-built CoreState to the agent on-event handler (for
      ;; :status events) — after the layout assocs so the status
      ;; indicator/container and theme controller are present
      (reset! cs-ref cs)

      ;; Restore a --continue session into the chat history AND the agent
      ;; context (pi: renderInitialMessages from buildContextEntries) — the
      ;; agent's in-memory messages must mirror the session branch or the
      ;; next LLM call loses the whole restored conversation (steered and
      ;; follow-up messages included)
      (when (and session (seq @(:entries session)))
        (restore-session! cs session true))

      cs)))

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
                                   (fn [] (term/rows @(:terminal t)))
                                   ;; pi: ExtensionEditorComponent wires
                                   ;; app.editor.external (ctrl+g) to its own
                                   ;; external-editor flow
                                   (fn [ed] (handle-external-editor cs ed))))
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
                                ;; Pi: setWorkingVisible — clearStatusIndicator("working")
                                ;; when hiding (kind-gated: a transient retry/
                                ;; compaction indicator stays), re-show the working
                                ;; indicator when showing (only while the turn runs).
                                (if visible?
                                  (when @(:running-turn? cs)
                                    (activate-working-indicator! cs))
                                  (clear-status-indicator! cs :working))
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
    ;; Live session + context injection for extensions (pi:
    ;; ctx.sessionManager / custom messages flowing through the agent loop).
    ;; Installed once with the registry; reload mutates the same CoreState
    ;; (agent, session atom), so the wiring stays valid across reloads —
    ;; only /new, /resume, fork and clone re-register the session.
    (extensions/set-session! @(:session-atom cs))
    (extensions/set-context-sink!
     (fn [msg] (agent/add-context-message! @(:agent-state cs) msg)))
    (extensions/set-entry-sink!
     (fn [entry]
       (when-let [renderer (extensions/get-entry-renderer (:custom-type entry))]
         (when-let [msg (renderer entry)]
           (ui/chat-history-add-message! (:chat-history cs) msg)))))
    registry))

;; ─── Run ───────────────────────────────────────────────────────────────────

(defn- maybe-show-cache-miss-notice!
  "pi: maybeShowCacheMissNotice — when :show-cache-miss-notices is on and
   the last assistant message paid for a significant prompt-cache miss, add
   a transcript notice. Display floor: >= 20k tokens re-billed (pi's
   `missedTokens < 20_000 && missedCost < 0.1` is simplified to the token
   arm — kmet has no per-message price lookup here)."
  [cs]
  (when (cfg/get-show-cache-miss-notices (:config cs))
    (when-let [sess @(:session-atom cs)]
      (when-let [miss (session/detect-cache-miss (session/get-branch sess))]
        (when (>= (:missed-tokens miss) 20000)
          (ui/chat-history-add-message!
           (:chat-history cs)
           {:role :info
            :label (if (:model-changed miss)
                     "Cache miss after model switch"
                     "Cache miss")
            :content (str (:missed-tokens miss) " tokens re-billed")}))))))

(defn run
  "Start the interactive TUI with the given config and CLI opts.
   Loads extensions, resolves the session (:resume/:continue/new), builds the
   layout, and runs the TUI loop until quit. Cleans up the TUI and tracked
   child processes on error, then rethrows for the top-level handler.
   pi: cli.js dispatch to interactive mode."
  [config opts]
  (let [tui-ref (atom nil)]
    (try
      ;; Extensions were loaded before dispatch (core/-main, pi: extension
      ;; discovery before model resolution); /reload re-loads them.

      ;; Apply command-line overrides
      (let [config (cfg/apply-cli-overrides config opts)
            _ (reset! global-config config)
            session (cond
                      (:resume opts) nil
                      (:continue opts) (if-let [path (find-session)]
                                         ;; find-session returns the session
                                         ;; file path — load it into a Session
                                         ;; record so the context and chat can
                                         ;; be restored below
                                         (session/load-session path)
                                         ;; pi: continueRecent — no session to
                                         ;; continue → start a fresh one
                                         (session/create-session (ensure-cwd-session-dir)))
                      :else (session/create-session (ensure-cwd-session-dir)))
            cs (build-layout config session)]
        (reset! tui-ref (:tui cs))
        (when (:resume opts)
          (show-session-selector cs ensure-session-dir
                                 (fn [path]
                                   (let [sess (session/load-session path)
                                         short-id (subs (:id sess) 0 (min 8 (count (:id sess))))]
                                     (restore-session! cs sess true)
                                     (ui/chat-history-add-message! (:chat-history cs)
                                                                   {:role :assistant
                                                                    :content (str "Resumed session " short-id ".")})
                                     (tui/tui-request-render (:tui cs))))))
        ;; start the UI before initializing extensions so session_start
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
