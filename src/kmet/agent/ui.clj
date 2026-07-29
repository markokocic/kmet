(ns kmet.agent.ui
  "Re-exports for all agent UI components — the app-specific layer
   that builds on the generic kmet.tui library.
   Analogous to pi's coding-agent interactive-mode components."
  (:require [kmet.agent.ui.user-message :as user-message]
            [kmet.agent.ui.assistant-message :as assistant-message]
            [kmet.agent.ui.tool-execution :as tool-execution]
            [kmet.agent.ui.custom-message :as custom-message]
            [kmet.agent.ui.footer :as footer]
            [kmet.agent.ui.chat-history :as chat-history]))

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
(def assistant-message-finalize! assistant-message/assistant-message-finalize!)
(def assistant-message-set-hide-thinking! assistant-message/assistant-message-set-hide-thinking!)
(def assistant-message-get-text assistant-message/assistant-message-get-text)
(def assistant-message-get-thinking assistant-message/assistant-message-get-thinking)

;; ToolExecutionComponent
(def make-tool-execution tool-execution/make-tool-execution)
(def tool-execution-set-content! tool-execution/tool-execution-set-content!)
(def tool-execution-set-error! tool-execution/tool-execution-set-error!)
(def tool-execution-set-expanded! tool-execution/tool-execution-set-expanded!)

;; CustomMessageComponent
(def make-custom-message custom-message/make-custom-message)
(def custom-message-set-content! custom-message/custom-message-set-content!)

;; Footer
(def make-footer footer/make-footer)
(def footer-set-status! footer/footer-set-status!)
(def footer-set-n-msgs! footer/footer-set-n-msgs!)

;; ChatHistoryComponent
(def make-chat-history chat-history/make-chat-history)
(def chat-history-add-message! chat-history/chat-history-add-message!)
(def chat-history-add-messages! chat-history/chat-history-add-messages!)
(def chat-history-remove-last! chat-history/chat-history-remove-last!)
(def chat-history-start-streaming! chat-history/chat-history-start-streaming!)
(def chat-history-append-streaming-text! chat-history/chat-history-append-streaming-text!)
(def chat-history-append-thinking-text! chat-history/chat-history-append-thinking-text!)
(def chat-history-finalize-streaming! chat-history/chat-history-finalize-streaming!)
(def chat-history-finalize-thinking! chat-history/chat-history-finalize-thinking!)
(def chat-history-get-streaming-text chat-history/chat-history-get-streaming-text)
(def chat-history-clear! chat-history/chat-history-clear!)
(def chat-history-get-messages chat-history/chat-history-get-messages)
(def chat-history-set-max-lines! chat-history/chat-history-set-max-lines!)
(def chat-history-set-theme! chat-history/chat-history-set-theme!)
(def chat-history-set-info-msg! chat-history/chat-history-set-info-msg!)
(def chat-history-clear-info-msg! chat-history/chat-history-clear-info-msg!)
(def chat-history-toggle-tool-expanded! chat-history/chat-history-toggle-tool-expanded!)
(def chat-history-toggle-thinking-hidden! chat-history/chat-history-toggle-thinking-hidden!)
(def chat-history-get-tool-expanded chat-history/chat-history-get-tool-expanded)
(def chat-history-get-thinking-hidden chat-history/chat-history-get-thinking-hidden)
(def chat-history-set-output-pad! chat-history/chat-history-set-output-pad!)
