(ns kmet.app.test-interactive-ui
  "Tests for the interactive-mode extension UI helpers: the autocomplete
   factory wrapper chain (pi: setupAutocompleteProvider) and the custom
   editor duck-typed transfer (pi: setCustomEditorComponent)."
  (:require [clojure.test :as t :refer [deftest testing]]
            [kmet.tui.autocomplete :as ac]
            [kmet.tui.components.editor :as editor]
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
