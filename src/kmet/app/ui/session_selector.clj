(ns kmet.app.ui.session-selector
  "Session browsing overlay (pi: SessionSelectorComponent + the streaming
   session-info population). Rows show the session name or first message
   with message count + age on the right; typing filters. Session files are
   streamed per-file with bounded concurrency (pi:
   buildSessionInfosWithConcurrency — G15); the header shows loading
   progress until ready. Escaping during the load cancels the pending
   population. ON-SELECT receives the chosen session path; the caller
   performs the restore (pi: the component emits the selection, the mode
   resumes the session)."
  (:require [kmet.app.session :as session]
            [kmet.app.ui :as ui]
            [kmet.tui.core :as tui]
            [kmet.tui.components.select-list :as select-list]
            [kmet.debug :as debug]))

(defn- format-session-age
  "pi: formatSessionDate — now / Nm / Nh / Nd / Nw / Nmo / Ny."
  [ms]
  (let [diff (- (System/currentTimeMillis) ms)
        mins (quot diff 60000)
        hours (quot mins 60)
        days (quot hours 24)]
    (cond
      (< mins 1) "now"
      (< mins 60) (str mins "m")
      (< days 1) (str hours "h")
      (< days 7) (str days "d")
      (< days 30) (str (quot days 7) "w")
      (< days 365) (str (quot days 30) "mo")
      :else (str (quot days 365) "y"))))

(defn show-session-selector
  "Browse past sessions via SelectList overlay (pi: SessionSelectorComponent).
   SESSION-DIR-FN returns the sessions dir to list; ON-SELECT receives the
   chosen session path after the overlay is hidden — the caller restores the
   session (mode-level)."
  [cs session-dir-fn on-select]
  (let [cancelled (atom false)
        sl-ref (atom nil)
        on-select-fn (fn [_]
                       (when-let [sel (select-list/select-list-get-selected @sl-ref)]
                         (on-select (:value sel))
                         (tui/tui-hide-overlay (:tui cs))
                         (tui/tui-request-render (:tui cs))))
        sl (select-list/make-select-list []
                                         :height 15
                                         :header "Loading sessions…"
                                         :no-match-text "  No sessions found"
                                         :on-select on-select-fn
                                         :on-escape (fn []
                                                      (reset! cancelled true)
                                                      (tui/tui-hide-overlay (:tui cs))
                                                      (tui/tui-request-render (:tui cs))))
        handle (tui/tui-show-overlay (:tui cs) sl :width 60 :height 15)]
    (reset! sl-ref sl)
    (tui/tui-request-render (:tui cs))
    (future
      (try
        (let [infos (session/list-sessions-info
                     (session-dir-fn)
                     (fn [loaded total]
                       (when-not @cancelled
                         (select-list/select-list-set-header!
                          sl (str "Loading sessions… (" loaded "/" total ")"))
                         (tui/tui-request-render (:tui cs)))))]
          (when-not @cancelled
            (if (empty? infos)
              ;; identity-based hide: the user may have escaped and opened a new
              ;; overlay between the cancelled check and this call — tui-hide-
              ;; overlay pops the topmost, which could be the wrong one
              (do ((:hide handle))
                  (tui/tui-request-render (:tui cs))
                  (ui/chat-history-add-message! (:chat-history cs)
                                                {:role :assistant :content "No past sessions found."}))
              (let [items (vec (for [info infos]
                                 {:label (or (:name info) (:first-message info))
                                  :description (str (:message-count info) " "
                                                    (format-session-age (:modified info)))
                                  :value (:path info)}))]
                (select-list/select-list-set-header! sl "Resume Session")
                (select-list/select-list-set-items! sl items)
                (tui/tui-request-render (:tui cs))))))
        (catch Exception e
          (debug/log "resume-session: " e)
          (when-not @cancelled
            ((:hide handle))
            (tui/tui-request-render (:tui cs))))))))
