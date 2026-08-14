(ns kmet.app.ui.model-selector
  "Model selection UI (pi: modes/interactive/components/model-selector.ts +
   model-search.ts): the /model overlay selector, the /scoped-models
   (Ctrl+P cycling) overlay, and the model-switch helpers shared with
   cycling and the footer sync."
  (:require [kmet.app.loop :as agent]
            [kmet.app.models :as models]
            [kmet.app.model-resolver :as resolver]
            [kmet.app.ui :as ui]
            [kmet.app.ui.footer-data-provider :as fdp]
            [kmet.config :as cfg]
            [kmet.tui.core :as tui]
            [kmet.tui.protocols :as protocols]
            [kmet.tui.components.select-list :as select-list]
            [clojure.string :as str]))

(defn- fmt-model [provider model]
  (str (name provider) ":" model))

(defn sync-footer-model!
  "Push the agent's current model/provider/thinking into the footer data
   provider and re-render (the fdp atoms are set once at startup; /model,
   the selector, and cycling must refresh them). The context window follows
   the resolved Model record, falling back to the settings value."
  [cs]
  (let [ag @(:agent-state cs)
        fdp (:footer-provider cs)]
    (fdp/fdp-set-model! fdp @(:model ag))
    (fdp/fdp-set-provider! fdp @(:provider ag))
    (fdp/fdp-set-thinking! fdp @(:thinking ag))
    (fdp/fdp-set-context-window!
     fdp (or (:context-window (models/get-model @(:provider ag) @(:model ag)))
             (:context-window (:config cs))))
    (protocols/invalidate (:footer-comp cs))
    (tui/tui-request-render (:tui cs))
    nil))

(defn model-full-id
  "Full \"provider/id\" id of a Model record (pi: `${provider}/${id}`)."
  [m]
  (str (name (:provider m)) "/" (:id m)))

(defn- scoped-model-snapshot
  "Models matched against first (pi: session scoped models when set, else
   the available snapshot) — feeds /model's cached match and the footer
   provider count. Scoped entries that no longer resolve drop out."
  [ag]
  (let [scoped (agent/get-scoped-models ag)]
    (if (seq scoped)
      (vec (keep (fn [id]
                   (let [slash (str/index-of id "/")]
                     (when slash
                       (models/get-model (keyword (subs id 0 slash))
                                         (subs id (inc slash))))))
                 scoped))
      (models/get-available))))

(defn scoped-or-available-models
  "pi: session scoped models when set, else the available snapshot (feeds
   cycling's fallback, /model, and the footer provider count)."
  [ag]
  (if (seq (agent/get-scoped-models ag))
    (scoped-model-snapshot ag)
    (models/get-available)))

(defn- update-available-provider-count!
  "Footer provider count from the scoped models when set, else the available
   snapshot (pi updateAvailableProviderCount)."
  [cs]
  (ui/fdp-set-provider-count!
   (:footer-provider cs)
   (count (distinct (map :provider (scoped-or-available-models @(:agent-state cs)))))))

(defn apply-model-switch!
  "Switch the agent's model (and optional thinking level), report the switch
   in the chat, and sync the footer (pi setModel + showStatus — shared by
   /model, the selector, and cycling)."
  [cs model thinking-level]
  (let [ag @(:agent-state cs)]
    (agent/set-provider! ag (:provider model))
    (agent/set-model! ag (:id model))
    (when thinking-level
      (agent/set-thinking-level! ag thinking-level))
    (ui/chat-history-add-message! (:chat-history cs)
                                  {:role :assistant
                                   :content (str "Switched to " (fmt-model (:provider model) (:id model))
                                                 (when thinking-level
                                                   (str " (thinking " (name thinking-level) ")")))})
    (sync-footer-model! cs)))

(defn show-model-selector
  "Model selector overlay: a SelectList of available (authenticated) models
   (pi ModelSelectorComponent, simplified — bound to Ctrl+L, bare /model,
   and the /model refresh-failure path with SEARCH-TERM pre-filled)."
  ([cs] (show-model-selector cs nil))
  ([cs search-term]
   (let [available (models/get-available)]
     (if (empty? available)
       (ui/chat-history-add-message! (:chat-history cs)
                                     {:role :assistant
                                      :content "No models available. Configure a provider first (/login)."})
       (let [items (mapv (fn [m]
                           (let [v (str (name (:provider m)) "/" (:id m))]
                             {:value v :label v}))
                         available)
             sl-ref (atom nil)
             on-select (fn [_]
                         (when-let [sel (select-list/select-list-get-selected @sl-ref)]
                           (let [[prov model] (str/split (:value sel) #"/" 2)
                                 m (models/get-model (keyword prov) model)]
                             (tui/tui-hide-overlay (:tui cs))
                             (apply-model-switch! cs m nil)
                             (tui/tui-request-render (:tui cs)))))
             on-escape (fn []
                         (tui/tui-hide-overlay (:tui cs))
                         (tui/tui-request-render (:tui cs)))
             sl (select-list/make-select-list items
                                              :height (min (count items) 15)
                                              :header "Select model"
                                              :on-select on-select
                                              :on-escape on-escape)]
         (reset! sl-ref sl)
         (when search-term
           (select-list/select-list-set-filter! sl search-term))
         (tui/tui-show-overlay (:tui cs) sl :width 55 :height (min (count items) 15))
         (tui/tui-request-render (:tui cs)))))))

(defn resolve-model-ref
  "/model reference resolution against the cached snapshot (pi
   findExactModelMatch — session scoped models when set, else available)."
  [cs term]
  (resolver/resolve-model-reference term (scoped-or-available-models @(:agent-state cs))))

(defn show-scoped-models-selector
  "pi showModelsSelector — /scoped-models opens the enabled-models overlay
   for Ctrl+P cycling. Initial enabled ids: session scoped models when set,
   else the settings :enabled-models patterns resolved through
   resolve-model-scope-models (unresolved patterns survive as [unavailable]
   rows), else nil (all enabled). Changes are session-only until Ctrl+S
   writes :enabled-models; the footer provider count updates live."
  [cs]
  (let [available (models/get-available)
        ag @(:agent-state cs)
        session-scoped (vec (agent/get-scoped-models ag))
        patterns (cfg/get-enabled-models-live (:config cs))
        configured-ids (fn []
                         ;; resolve each pattern; unresolved ones survive as
                         ;; [unavailable] rows (pi: no-match diagnostics are
                         ;; appended to the enabled ids)
                         (loop [ps patterns acc [] warnings []]
                           (if-let [p (first ps)]
                             (let [{:keys [model]}
                                   (resolver/parse-model-pattern p available)]
                               (if model
                                 (recur (rest ps) (conj acc (model-full-id model)) warnings)
                                 (recur (rest ps) (conj acc (str p))
                                        (conj warnings
                                              (str "No models match pattern \"" p "\"")))))
                             (do (doseq [w warnings]
                                   (ui/chat-history-add-message!
                                    (:chat-history cs) {:role :assistant :content w}))
                                 (vec acc)))))
        initial (cond
                  (seq session-scoped) session-scoped
                  (seq patterns) (configured-ids)
                  :else nil)
        available-ids (set (map model-full-id available))
        update-session-models (fn [enabled-ids]
                                ;; pi updateSessionModels: a non-null list with
                                ;; an enabled available model, not covering all
                                ;; available models, becomes the session scoped
                                ;; list; everything else (null = all enabled,
                                ;; nothing enabled, all enabled) clears it
                                (if (and enabled-ids
                                         (some available-ids enabled-ids)
                                         (not (every? (set enabled-ids)
                                                      (map model-full-id available))))
                                  (agent/set-scoped-models! ag enabled-ids)
                                  (agent/set-scoped-models! ag []))
                                (update-available-provider-count! cs)
                                (tui/tui-request-render (:tui cs)))
        sel (ui/make-scoped-models-selector
             available initial
             :on-change update-session-models
             :on-persist (fn [enabled-ids]
                           (let [all-enabled? (or (nil? enabled-ids)
                                                  (and (= (count enabled-ids) (count available))
                                                       (every? available-ids enabled-ids)))]
                             (cfg/set-enabled-models! (when-not all-enabled? enabled-ids))
                             (ui/chat-history-add-message!
                              (:chat-history cs)
                              {:role :assistant
                               :content "Model selection saved to settings."})))
             :on-cancel (fn []
                          (tui/tui-hide-overlay (:tui cs))
                          (tui/tui-request-render (:tui cs))))]
    (tui/tui-show-overlay (:tui cs) sel :width 62 :max-height 24)
    (tui/tui-request-render (:tui cs))))
