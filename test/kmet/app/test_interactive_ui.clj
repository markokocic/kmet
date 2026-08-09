(ns kmet.app.test-interactive-ui
  "Tests for the interactive-mode extension UI helpers: the autocomplete
   factory wrapper chain (pi: setupAutocompleteProvider) and the custom
   editor duck-typed transfer (pi: setCustomEditorComponent)."
  (:require [clojure.test :as t :refer [deftest testing]]
            [kmet.tui.autocomplete :as ac]
            [kmet.tui.components.editor :as editor]
            [kmet.tui.components.container :as container]
            [kmet.tui.protocols :as protocols]
            [kmet.tui.core :as tui]
            [kmet.modes.interactive :as inter]
            [kmet.app.commands :as commands]
            [kmet.app.ui :as ui]
            [kmet.app.models :as m]
            [kmet.app.auth :as auth]
            [kmet.app.loop :as agent]
            [kmet.config :as cfg]))

(defn- transfer-editor! [app-ed custom-ed kb]
  ((var inter/transfer-editor!) app-ed custom-ed kb))

(defn- normalize [x]
  ((var inter/normalize-autocomplete-provider) x))

(deftest test-normalize-autocomplete-provider-protocol
  (testing "an AutocompleteProvider passes through unchanged"
    (let [p (ac/make-combined-provider :commands-fn (constantly []))]
      (t/is (identical? p (normalize p))))))

(deftest test-normalize-autocomplete-provider-map
  (testing "a duck-typed map provider is adapted to the protocol"
    (let [p (normalize {:get-suggestions (fn [_state] {:items [{:value "x" :label "X"}]
                                                       :prefix "x"})
                        :get-trigger-characters ["@"]})]
      (t/is (satisfies? ac/AutocompleteProvider p))
      (let [res (ac/get-suggestions p ["xa"] 0 1 {})]
        (t/is (= "x" (:prefix res)))
        (t/is (= "X" (-> res :items first :label))))
      (t/is (= ["@"] (ac/get-trigger-characters p)))
      ;; default apply-completion replaces the prefix
      (let [st (ac/apply-completion p ["xa"] 0 1 {:value "xyz" :label "xyz"} "x")]
        (t/is (= "xyza" (nth (:lines st) 0)))
        (t/is (= 3 (:cursor-col st)))))))

(deftest test-normalize-autocomplete-provider-nil
  (testing "non-provider values normalize to nil"
    (t/is (nil? (normalize nil)))
    (t/is (nil? (normalize 42)))))

(deftest test-transfer-editor!
  (testing "transfer-editor! copies text callbacks, appearance, provider,
            and action handlers onto a custom editor (pi duck-typing)"
    (let [app-ed (editor/make-editor)
          custom (editor/make-editor)
          _ (editor/editor-set-on-submit! app-ed (fn [t] (println t)))
          _ (editor/editor-set-on-action! app-ed "app.interrupt" (fn []))
          _ (editor/editor-set-autocomplete-provider!
             app-ed (ac/make-combined-provider :commands-fn (constantly [])))
          _ (reset! (:border-fn app-ed) (fn [s] s))
          _ (reset! (:terminal-rows-atom app-ed) (fn [] 24))
          _ (reset! (:padding-x app-ed) 3)]
      (transfer-editor! app-ed custom nil)
      (t/is (identical? @(:on-submit app-ed) @(:on-submit custom))
            "on-submit handler copied")
      (t/is (contains? @(:action-handlers custom) "app.interrupt")
            "app action handlers copied")
      (t/is (some? @(:autocomplete-provider custom))
            "autocomplete provider copied")
      (t/is (identical? @(:border-fn app-ed) @(:border-fn custom))
            "border fn copied (pi: borderColor property)")
      (t/is (= 3 @(:padding-x custom)) "padding copied")
      (t/is (some? @(:terminal-rows-atom custom))
            "dynamic-height source copied"))))

(deftest test-transfer-editor!-non-editor
  (testing "transfer to a non-editor component is a no-op"
    (let [app-ed (editor/make-editor)
          plain {:render (fn [_] [""])}]
      (t/is (nil? (transfer-editor! app-ed plain nil)))
      (t/is (= [:render] (keys plain)) "plain map untouched"))))

;; ─── Builtin auth commands (Phase 3) ───────────────────────────────────────

(deftest test-builtin-login-logout-registered
  (testing "login/logout are real builtins inside register-builtin-commands!
            (not dropped or left as top-level forms)"
    (commands/clear-commands!)
    ((var inter/register-builtin-commands!) cfg/default-config)
    (let [login (commands/find-command "login")
          logout (commands/find-command "logout")]
      (t/is (some? login) "login registered")
      (t/is (= "Configure provider authentication" (:description login)))
      (t/is (some? (:handler login)) "login has a handler")
      (t/is (some? logout) "logout registered")
      (t/is (= "Remove provider authentication" (:description logout)))
      (t/is (some? (:handler logout)) "logout has a handler"))))

(deftest test-login-logout-unknown-provider
  (testing "login/logout validate the provider against the registry"
    (commands/clear-commands!)
    ((var inter/register-builtin-commands!) cfg/default-config)
    (let [warned (atom nil)]
      (with-redefs [ui/show-warning! (fn [_ msg] (reset! warned msg))
                    ui/chat-history-add-message! (fn [_ _] nil)]
        ((:handler (commands/find-command "login")) {} "nonexistent-provider")
        (t/is (= "Unknown provider: nonexistent-provider" @warned))
        (reset! warned nil)
        ((:handler (commands/find-command "logout")) {} "nonexistent-provider")
        (t/is (= "Unknown provider: nonexistent-provider" @warned))))))

(deftest test-model-command-switches-model
  (testing "/model resolves provider/model patterns and switches the agent"
    (commands/clear-commands!)
    (m/load-catalogs!)
    ((var inter/register-builtin-commands!) cfg/default-config)
    (let [ag (agent/make-agent-state :provider :opencode-go :model "deepseek-v4-flash")
          cs {:agent-state (atom ag)
              :chat-history nil
              :footer-comp nil
              :footer-provider nil
              :config cfg/default-config
              :tui nil}]
      (with-redefs [auth/configured? (fn [_] true)
                    ui/chat-history-add-message! (fn [_ _] nil)
                    inter/sync-footer-model! (fn [_] nil)]
        (testing "provider/model pattern"
          ((:handler (commands/find-command "model")) cs "deepseek/deepseek-v4-pro")
          (t/is (= :deepseek @(:provider ag)))
          (t/is (= "deepseek-v4-pro" @(:model ag))))
        (testing ":thinking suffix sets the agent thinking level"
          ((:handler (commands/find-command "model")) cs "deepseek/deepseek-v4-pro:high")
          (t/is (= :high @(:thinking ag))))
        (testing "unmatched pattern reports a clear failure"
          (let [last-msg (atom nil)]
            (with-redefs [ui/chat-history-add-message! (fn [_ msg] (reset! last-msg msg))]
              ((:handler (commands/find-command "model")) cs "nope")
              (t/is (= "No model matches \"nope\"." (:content @last-msg))))))))))

;; ─── /continue command ─────────────────────────────────────────────────────

(deftest test-continue-registered
  (testing "/continue is a real builtin inside register-builtin-commands!"
    (commands/clear-commands!)
    ((var inter/register-builtin-commands!) cfg/default-config)
    (let [c (commands/find-command "continue")]
      (t/is (some? c) "continue registered")
      (t/is (= "Continue where the agent left off (e.g. after a network error)"
               (:description c)))
      (t/is (some? (:handler c)) "continue has a handler"))))

(deftest test-continue-refuses-while-running
  (testing "/continue refuses while the agent is running"
    (commands/clear-commands!)
    ((var inter/register-builtin-commands!) cfg/default-config)
    (let [ag (agent/make-agent-state)
          msg (atom nil)
          cs {:agent-state (atom ag)
              :chat-history nil
              :running-turn? (atom true)
              :tui nil}]
      (reset! (:status ag) :thinking)
      (swap! (:messages ag) conj {:role :user :content [{:type :text :text "hi"}]})
      (with-redefs [ui/chat-history-add-message! (fn [_ m] (reset! msg m))]
        ((:handler (commands/find-command "continue")) cs ""))
      (t/is (= "Wait for the current response to finish before continuing."
               (:content @msg))
            "refuses while a turn is running"))))

(deftest test-continue-refuses-empty-context
  (testing "/continue refuses when there is no conversation to continue"
    (commands/clear-commands!)
    ((var inter/register-builtin-commands!) cfg/default-config)
    (let [ag (agent/make-agent-state)
          msg (atom nil)
          cs {:agent-state (atom ag)
              :chat-history nil
              :running-turn? (atom false)
              :tui nil}]
      (with-redefs [ui/chat-history-add-message! (fn [_ m] (reset! msg m))]
        ((:handler (commands/find-command "continue")) cs ""))
      (t/is (= "No conversation to continue." (:content @msg))))))

(deftest test-continue-starts-run-without-message
  (testing "/continue starts an agent run on the existing context with no new
            user message — the model picks up the interrupted turn (e.g. after
            a network error the last entry is an unanswered user message)"
    (commands/clear-commands!)
    ((var inter/register-builtin-commands!) cfg/default-config)
    (let [ag (agent/make-agent-state)
          _ (swap! (:messages ag) conj
                   {:role :user :content [{:type :text :text "fix the bug"}]})
          started (atom nil)
          cs {:agent-state (atom ag)
              :chat-history nil
              :running-turn? (atom false)
              :tui nil
              :footer-comp nil
              :footer-provider nil
              :status-container nil
              :status-indicator nil
              :active-status-kind (atom nil)
              :anim-timer (atom nil)}]
      (with-redefs [ui/chat-history-add-message! (fn [_ _] nil)
                    ui/chat-history-start-streaming! (fn [_] nil)
                    agent/run-agent-turn (fn [a opts]
                                           (reset! started [a opts])
                                           (future))
                    inter/activate-working-indicator! (fn [_] nil)
                    inter/start-anim-timer! (fn [_] nil)
                    inter/update-footer! (fn [_] nil)
                    tui/tui-request-render (fn [_] nil)]
        ((:handler (commands/find-command "continue")) cs ""))
      (t/is (some? @started) "run-agent-turn called")
      (t/is (identical? ag (first @started)) "runs on the current agent state")
      (t/is (not (contains? (second @started) :message))
            "no :message option — no new user message is added")
      (t/is (true? @(:running-turn? cs)) "UI turn flag set"))))

;; ─── Status indicator swap model (pi: showStatusIndicator/clearStatusIndicator) ──
;; The working indicator must survive mid-turn transient swaps: after a retry
;; backoff or compaction the next turn-start revives it, and a stale end event
;; (auto-retry-end / compaction-end) must not stop an indicator that was
;; already replaced (pi: clearStatusIndicator(kind) is kind-gated).

(defn- test-status-cs
  "A minimal CoreState-like map for the status swap helpers."
  []
  (let [si (ui/make-status-indicator :text "Working...")
        sc (container/make-container [si])]
    {:tui {:render-requested? (atom false)}
     :status-indicator si
     :status-container sc
     :active-status-kind (atom nil)
     :running-turn? (atom true)}))

(defn- status-lines [sc]
  (protocols/render sc 60))

(defn- working-status? [sc]
  (let [lines (status-lines sc)]
    (and (= 2 (count lines))
         (boolean (some #(re-find #"Working" %) lines)))))

(defn- blank-status? [sc]
  (let [lines (status-lines sc)]
    (and (= 2 (count lines))
         (every? #(re-find #"^\s*$" %) lines))))

(deftest test-status-indicator-survives-retry
  (testing "the working indicator is revived after a retry backoff and kept
            by the kind-gated auto-retry-end (pi: agent_start after
            continue() re-shows WorkingStatusIndicator)"
    (let [cs (test-status-cs)
          sc (:status-container cs)]
      (testing "submit activates the working indicator"
        ((var inter/activate-working-indicator!) cs)
        (t/is (working-status? sc)))
      (testing "auto-retry-start swaps in the retry countdown"
        ((var inter/show-status-indicator!) cs :retry
                                            (ui/make-retry-status-indicator 1 3 2000))
        (t/is (not (working-status? sc))))
      (testing "turn-start after the backoff revives the working indicator"
        ((var inter/activate-working-indicator!) cs)
        (t/is (working-status? sc)))
      (testing "auto-retry-end no-ops once the working indicator is active"
        ((var inter/clear-status-indicator!) cs :retry)
        (t/is (working-status? sc))
        (t/is (= :working @(:active-status-kind cs))))
      (testing "turn end clears to the idle two rows"
        ((var inter/clear-status-indicator!) cs)
        (t/is (blank-status? sc))))))

(deftest test-status-indicator-survives-compaction
  (testing "in-loop compaction clears at compaction-end and the following
            turn-start revives the working indicator"
    (let [cs (test-status-cs)
          sc (:status-container cs)]
      ((var inter/activate-working-indicator!) cs)
      ((var inter/show-status-indicator!) cs :compaction
                                          (ui/make-compaction-status-indicator))
      (t/is (not (working-status? sc)))
      ((var inter/clear-status-indicator!) cs :compaction)
      (t/is (blank-status? sc))
      ((var inter/activate-working-indicator!) cs)
      (t/is (working-status? sc)))))

(deftest test-status-indicator-kind-gated-clear
  (testing "a stale end event cannot stop an indicator it didn't own"
    (let [cs (test-status-cs)
          sc (:status-container cs)]
      ((var inter/activate-working-indicator!) cs)
      ((var inter/show-status-indicator!) cs :compaction
                                          (ui/make-compaction-status-indicator))
      ;; auto-retry-end arriving while compaction is active must no-op
      ((var inter/clear-status-indicator!) cs :retry)
      (t/is (not (working-status? sc)))
      (t/is (= :compaction @(:active-status-kind cs)))
      (testing "cancel during backoff clears unconditionally; the late
                retry-end no-ops"
        ((var inter/clear-status-indicator!) cs)
        (t/is (blank-status? sc))
        ((var inter/clear-status-indicator!) cs :retry)
        (t/is (blank-status? sc))
        (t/is (nil? @(:active-status-kind cs)))))))
