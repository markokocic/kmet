(ns kmet.app.ui.fork-selector
  "Fork-from-message picker (pi: UserMessageSelectorComponent): rows are the
   session's user messages (truncated); selecting one calls ON-SELECT with
   the entry id. The fork itself is mode-level (pi: the component emits the
   selection, interactive-mode performs forkSessionAtEntry)."
  (:require [kmet.app.ui :as ui]
            [kmet.app.session :as session]
            [kmet.tui.core :as tui]
            [kmet.tui.components.select-list :as select-list]
            [babashka.fs :as fs]))

(defn show-fork-selector
  "Select a user message to fork from (pi: UserMessageSelectorComponent).
   ON-SELECT receives the chosen entry id; the caller performs the fork."
  [cs on-select]
  (let [sess @(:session-atom cs)]
    (if (nil? sess)
      (ui/chat-history-add-message! (:chat-history cs)
                                    {:role :assistant :content "No active session."})
      (if-not (fs/exists? (:file sess))
        (ui/chat-history-add-message! (:chat-history cs)
                                      {:role :assistant
                                       :content "Wait for the first assistant response before forking."})
        (let [msgs (->> @(:entries sess)
                        ;; pi: getUserMessagesForForking iterates ALL entries
                        ;; (every branch), not just the active path
                        (filter #(= :user (:role %)))
                        (keep (fn [e]
                                (let [t (session/session-entry-text e)]
                                  (when (seq t) {:entry e :text t})))))
              items (mapv (fn [{:keys [entry text]}]
                            {:label (subs text 0 (min 60 (count text)))
                             :value (:id entry)})
                          msgs)]
          (if (empty? items)
            (ui/chat-history-add-message! (:chat-history cs)
                                          {:role :assistant :content "No messages to fork from."})
            (let [sl-ref (atom nil)
                  on-select (fn [_]
                              (when-let [sel (select-list/select-list-get-selected @sl-ref)]
                                (tui/tui-hide-overlay (:tui cs))
                                (on-select (:value sel))))
                  on-escape (fn []
                              (tui/tui-hide-overlay (:tui cs))
                              (tui/tui-request-render (:tui cs)))
                  sl (select-list/make-select-list items
                                                   :height (min (count items) 15)
                                                   :header "Fork from message"
                                                   :on-select on-select
                                                   :on-escape on-escape)]
              (reset! sl-ref sl)
              (tui/tui-show-overlay (:tui cs) sl :width 70 :height (min (count items) 15))
              (tui/tui-request-render (:tui cs)))))))))
