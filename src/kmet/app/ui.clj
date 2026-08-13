(ns kmet.app.ui
  "Re-exports for all agent UI components — the app-specific layer
   that builds on the generic kmet.tui library.
   Analogous to pi's coding-agent interactive-mode components."
  (:require [kmet.app.ui.user-message :as user-message]
            [kmet.app.ui.assistant-message :as assistant-message]
            [kmet.app.ui.tool-execution :as tool-execution]
            [kmet.app.ui.custom-message :as custom-message]
            [kmet.app.ui.footer :as footer]
            [kmet.app.ui.footer-data-provider :as footer-data-provider]
            [kmet.app.ui.pending-messages :as pending-messages]
            [kmet.app.ui.loaded-resources :as loaded-resources]
            [kmet.app.ui.chat-history :as chat-history]
            [kmet.app.ui.status-indicator :as status-indicator]
            [kmet.app.ui.bash-execution :as bash-execution]
            [kmet.app.ui.scoped-models-selector :as scoped-models-selector]))

;; UserMessageComponent
(def make-user-message user-message/make-user-message)
(def user-message-set-text! user-message/user-message-set-text!)
(def user-message-set-theme! user-message/user-message-set-theme!)
(def user-message-set-output-pad! user-message/user-message-set-output-pad!)

;; AssistantMessageComponent
(def make-assistant-message assistant-message/make-assistant-message)
(def assistant-message-set-text! assistant-message/assistant-message-set-text!)
(def assistant-message-append-text! assistant-message/assistant-message-append-text!)
(def assistant-message-set-thinking! assistant-message/assistant-message-set-thinking!)
(def assistant-message-append-thinking! assistant-message/assistant-message-append-thinking!)
(def assistant-message-set-hide-thinking! assistant-message/assistant-message-set-hide-thinking!)
(def assistant-message-get-text assistant-message/assistant-message-get-text)
(def assistant-message-get-thinking assistant-message/assistant-message-get-thinking)

;; ToolExecutionComponent
(def make-tool-execution tool-execution/make-tool-execution)
(def tool-execution-set-content! tool-execution/tool-execution-set-content!)
(def tool-execution-set-error! tool-execution/tool-execution-set-error!)
(def tool-execution-set-expanded! tool-execution/tool-execution-set-expanded!)
(def tool-execution-set-render-call-fn! tool-execution/tool-execution-set-render-call-fn!)
(def tool-execution-set-render-result-fn! tool-execution/tool-execution-set-render-result-fn!)
(def tool-execution-set-truncation! tool-execution/tool-execution-set-truncation!)
(def tool-execution-set-details! tool-execution/tool-execution-set-details!)
(def tool-execution-set-images! tool-execution/tool-execution-set-images!)
(def tool-execution-set-tool-call-id! tool-execution/tool-execution-set-tool-call-id!)
(def tool-execution-get-tool-call-id tool-execution/tool-execution-get-tool-call-id)
(def tool-execution-set-args-complete! tool-execution/tool-execution-set-args-complete!)
(def tool-execution-mark-execution-started! tool-execution/tool-execution-mark-execution-started!)
(def tool-execution-set-request-render-fn! tool-execution/tool-execution-set-request-render-fn!)

;; CustomMessageComponent
(def make-custom-message custom-message/make-custom-message)
(def custom-message-set-content! custom-message/custom-message-set-content!)
(def custom-message-set-collapsible-content! custom-message/custom-message-set-collapsible-content!)
(def custom-message-collapsible? custom-message/custom-message-collapsible?)
(def custom-message-set-expanded! custom-message/custom-message-set-expanded!)
(def custom-message-get-expanded custom-message/custom-message-get-expanded)
;; BashExecutionComponent
(def make-bash-execution bash-execution/make-bash-execution)
(def bash-execution-set-expanded! bash-execution/bash-execution-set-expanded!)
(def bash-execution-set-theme! bash-execution/bash-execution-set-theme!)
(def bash-execution-append-output! bash-execution/bash-execution-append-output!)
(def bash-execution-set-complete! bash-execution/bash-execution-set-complete!)
(def bash-execution-get-output bash-execution/bash-execution-get-output)
(def bash-execution-get-command bash-execution/bash-execution-get-command)

;; Footer
(def make-footer footer/make-footer)
(def footer-set-extension-status! footer/footer-set-extension-status!)
(def footer-set-theme! footer/footer-set-theme!)
(def footer-set-provider! footer/footer-set-provider!)
(def footer-set-auto-compact! footer/footer-set-auto-compact!)

;; FooterDataProvider
(def make-footer-data-provider footer-data-provider/make-footer-data-provider)
(def fdp-set-session! footer-data-provider/fdp-set-session!)
(def fdp-set-provider-count! footer-data-provider/fdp-set-provider-count!)

;; ScopedModelsSelector
(def make-scoped-models-selector scoped-models-selector/make-scoped-models-selector)
(def scoped-models-get-enabled-ids scoped-models-selector/scoped-models-get-enabled-ids)

;; PendingMessages
(def make-pending-messages pending-messages/make-pending-messages)
(def pending-messages-set-queues! pending-messages/pending-messages-set-queues!)
(def pending-messages-set-hint! pending-messages/pending-messages-set-hint!)

;; LoadedResources
(def make-loaded-resources loaded-resources/make-loaded-resources)
(def loaded-resources-set-sections! loaded-resources/loaded-resources-set-sections!)
(def loaded-resources-set-expanded! loaded-resources/loaded-resources-set-expanded!)
(def loaded-resources-set-theme! loaded-resources/loaded-resources-set-theme!)

;; StatusIndicator
(def make-status-indicator status-indicator/make-status-indicator)
(def status-indicator-start! status-indicator/status-indicator-start!)
(def status-indicator-stop! status-indicator/status-indicator-stop!)
(def status-indicator-set-text! status-indicator/status-indicator-set-text!)
(def status-indicator-set-theme! status-indicator/status-indicator-set-theme!)
(def make-retry-status-indicator status-indicator/make-retry-status-indicator)
(def make-compaction-status-indicator status-indicator/make-compaction-status-indicator)
(def make-branch-summary-status-indicator status-indicator/make-branch-summary-status-indicator)

;; ChatHistoryComponent
(def make-chat-history chat-history/make-chat-history)
(def chat-history-add-message! chat-history/chat-history-add-message!)
(def chat-history-add-messages! chat-history/chat-history-add-messages!)
(def chat-history-remove-last! chat-history/chat-history-remove-last!)
(def chat-history-insert-before-streaming! chat-history/chat-history-insert-before-streaming!)
(def chat-history-start-streaming! chat-history/chat-history-start-streaming!)
(def chat-history-append-streaming-text! chat-history/chat-history-append-streaming-text!)
(def chat-history-append-thinking-text! chat-history/chat-history-append-thinking-text!)
(def chat-history-finalize-streaming! chat-history/chat-history-finalize-streaming!)
(def chat-history-finalize-thinking! chat-history/chat-history-finalize-thinking!)
(def chat-history-get-streaming-text chat-history/chat-history-get-streaming-text)
(def chat-history-clear-streaming! chat-history/chat-history-clear-streaming!)
(def chat-history-clear! chat-history/chat-history-clear!)
(def chat-history-rebuild! chat-history/chat-history-rebuild!)
(def chat-history-get-messages chat-history/chat-history-get-messages)
(def chat-history-set-max-lines! chat-history/chat-history-set-max-lines!)
(def chat-history-set-theme! chat-history/chat-history-set-theme!)
(def chat-history-set-info-msg! chat-history/chat-history-set-info-msg!)
(def chat-history-clear-info-msg! chat-history/chat-history-clear-info-msg!)
(def chat-history-toggle-tool-expanded! chat-history/chat-history-toggle-tool-expanded!)
(def chat-history-toggle-thinking-hidden! chat-history/chat-history-toggle-thinking-hidden!)
(def chat-history-set-thinking-hidden! chat-history/chat-history-set-thinking-hidden!)
(def chat-history-get-tool-expanded chat-history/chat-history-get-tool-expanded)
(def chat-history-get-thinking-hidden chat-history/chat-history-get-thinking-hidden)
(def chat-history-set-hidden-thinking-label! chat-history/chat-history-set-hidden-thinking-label!)
(def chat-history-show-status! chat-history/chat-history-show-status!)
(def chat-history-set-output-pad! chat-history/chat-history-set-output-pad!)

;; ─── General UI helpers (pi: showError / showWarning) ─────────────────────

(defn show-error!
  "Display an error message in the chat history.
   Pi: showError — adds spacer + Text with error color to chatContainer.
   Rendered as a plain Spacer(1) + error Text (no background box), not
   persisted (session persistence is driven by the agent loop)."
  [chat msg]
  (chat-history-add-message! chat {:role :error :content msg}))

(defn show-warning!
  "Display a warning message in the chat history.
   Pi: showWarning — adds spacer + Text with warning color to chatContainer.
   Rendered as a plain Spacer(1) + warning Text (no background box), not
   persisted (session persistence is driven by the agent loop)."
  [chat msg]
  (chat-history-add-message! chat {:role :warning :content msg}))
