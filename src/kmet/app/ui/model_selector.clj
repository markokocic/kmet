(ns kmet.app.ui.model-selector
  "Model selection UI (pi: modes/interactive/components/model-selector.ts +
   model-search.ts): the /model overlay selector (pi ModelSelectorComponent —
   visible search filter, wrap-around navigation, current-model ✓, all/scoped
   Tab toggle), the /scoped-models (Ctrl+P cycling) overlay, and the
   model-switch helpers shared with cycling and the footer sync."
  (:require [clojure.string :as str]
            [kmet.app.keybindings :as app-kb]
            [kmet.app.loop :as agent]
            [kmet.ai.models :as models]
            [kmet.app.model-resolver :as resolver]
            [kmet.app.ui.chat-history :as chat-history]
            [kmet.app.ui.footer-data-provider :as fdp]
            [kmet.app.ui.model-info :as model-info]
            [kmet.app.ui.scoped-models-selector :as scoped-models-selector]
            [kmet.config :as cfg]
            [kmet.tui.components.container :as container]
            [kmet.tui.components.dynamic-border :as db]
            [kmet.tui.components.input :as input]
            [kmet.tui.components.spacer :as spacer]
            [kmet.tui.components.text :as text]
            [kmet.tui.core :as tui]
            [kmet.tui.fuzzy :as fuzzy]
            [kmet.tui.keybindings :as kb]
            [kmet.tui.macros :refer [defcomponent]]
            [kmet.tui.protocols :as protocols]
            [kmet.tui.theme :as theme]))

(defn- fmt-model [provider model]
  (str (name provider) ":" model))

(defn sync-footer-model!
  "Push the agent's current model/provider/thinking/reasoning into the footer
   data provider and re-render (the fdp atoms are set once at startup; /model,
   the selector, and cycling must refresh them). The context window follows
   the resolved Model record, falling back to the settings value — synced to
   the agent too, so the proactive compaction check tracks model switches."
  [cs]
  (let [ag @(:agent-state cs)
        fdp (:footer-provider cs)
        m (models/get-model @(:provider ag) @(:model ag))
        window (or (:context-window m)
                   (:context-window (:config cs)))]
    (fdp/fdp-set-model! fdp @(:model ag))
    (fdp/fdp-set-provider! fdp @(:provider ag))
    (fdp/fdp-set-thinking! fdp @(:thinking ag))
    (fdp/fdp-set-reasoning! fdp (boolean (:reasoning m)))
    (fdp/fdp-set-context-window! fdp window)
    (agent/set-context-window! ag window)
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
  (fdp/fdp-set-provider-count!
   (:footer-provider cs)
   (count (distinct (map :provider (scoped-or-available-models @(:agent-state cs)))))))

(defn apply-model-switch!
  "Switch the agent's model (and optional explicit thinking level) with
   position-preserving thinking (the kmet rank rule — agent/switch-thinking-
   level: an old model's highest stays the new model's highest, second-
   highest → second-highest, ...; pi diverges here by keeping the level
   name clamped). Persists the selection as the new default (pi
   settingsManager.setDefaultModelAndProvider), reports the switch in the
   chat, and syncs the footer (pi setModel + showStatus — shared by /model,
   the selector, and cycling)."
  [cs model thinking-level]
  (let [ag @(:agent-state cs)
        old-model (models/get-model @(:provider ag) @(:model ag))
        clamped (agent/switch-thinking-level old-model model @(:thinking ag) thinking-level)]
    (agent/set-provider! ag (:provider model))
    (agent/set-model! ag (:id model))
    (cfg/set-default-model! (:provider model) (:id model))
    (agent/set-thinking-level! ag clamped)
    (chat-history/chat-history-add-message! (:chat-history cs)
                                            {:role :assistant
                                             :content (str "Switched to " (fmt-model (:provider model) (:id model))
                                                           (when (not= clamped :off)
                                                             (str " (thinking " (name clamped) ")")))})
    (sync-footer-model! cs)))

;; ─── ModelSelector component (pi ModelSelectorComponent) ───────────────────

(declare model-refresh! filtered-items models-equal?)

(defcomponent ModelSelector nil
              [container search-input list-container state-atom
               scope-text scope-hint-text on-select-atom on-cancel-atom
               focused? cache-atom]

  (render [this width] (protocols/render (:container this) width))

  (handle-input [this data]
    (let [kmgr (kb/get-global-keybindings)
          st @state-atom
          filtered (filtered-items st)
          n (count filtered)]
      (cond
        ;; Tab — toggle the all/scoped scope (pi tui.input.tab; consumed
        ;; even when no scoped models exist, pi parity)
        (kb/matches-key kmgr data "tui.input.tab")
        (do (when (seq (:scoped-models st))
              (let [new-scope (if (= :all (:scope st)) :scoped :all)
                    ;; pi setScope: reset selection to current model position
                    new-active (if (= :scoped new-scope)
                                 (:scoped-models st)
                                 (:all-models st))
                    cur (:current st)
                    idx (or (first (keep-indexed
                                    (fn [i m] (when (models-equal? cur (:model m)) i))
                                    new-active))
                            0)]
                (swap! state-atom assoc :scope new-scope :selected-idx idx)
                (model-refresh! this)))
            nil)

        ;; Navigation (pi tui.select.up/down — wraps; rebuilds the rows so
        ;; the selection arrow moves, pi updateList)
        (kb/matches-key kmgr data "tui.select.up")
        (do (when (pos? n)
              (swap! state-atom assoc
                     :selected-idx (if (zero? (:selected-idx st))
                                     (dec n)
                                     (dec (:selected-idx st))))
              (model-refresh! this))
            nil)

        (kb/matches-key kmgr data "tui.select.down")
        (do (when (pos? n)
              (swap! state-atom assoc
                     :selected-idx (if (= (:selected-idx st) (dec n))
                                     0
                                     (inc (:selected-idx st))))
              (model-refresh! this))
            nil)

        ;; Enter — select the highlighted model (pi tui.select.confirm; no-op
        ;; when nothing is filtered — pi guards on selectedModel)
        (kb/matches-key kmgr data "tui.select.confirm")
        (let [item (when (pos? n) (nth filtered (min (:selected-idx st) (dec n))))]
          (when (and item (:model item))
            (when-let [cb @on-select-atom]
              (cb (:model item))))
          nil)

        ;; Escape / Ctrl+C — cancel (pi tui.select.cancel)
        (kb/matches-key kmgr data "tui.select.cancel")
        (do (when-let [cb @on-cancel-atom] (cb)) nil)

        ;; Everything else — the search input (the visible filter, pi)
        :else
        (do (protocols/handle-input search-input data)
            (let [value (input/input-get-value search-input)]
              (when (not= value (:search st))
                (swap! state-atom assoc :search value :selected-idx 0)
                (model-refresh! this)))
            nil)))))

;; ─── Helpers (pi sortModels / filterModels / updateList / getScopeText) ────

(defn- models-equal?
  "Same provider + id (pi modelsAreEqual)."
  [a b]
  (and a b (= (:provider a) (:provider b)) (= (:id a) (:id b))))

(defn- sort-models
  "Current model first, then by provider name (pi sortModels)."
  [current models]
  (sort-by (juxt (complement (partial models-equal? current))
                 (comp str name :provider))
           models))

(defn- filtered-items
  "Active-scope models matching the search (pi filterModels — fuzzy over
   \"provider/id\" + model name)."
  [st]
  (let [active (if (= :scoped (:scope st)) (:scoped-models st) (:all-models st))
        query (str/lower-case (:search st))]
    (if (str/blank? query)
      (mapv (fn [m] {:model m}) active)
      (mapv (fn [m] {:model m})
            (fuzzy/fuzzy-filter active query
                                (fn [m] (let [nm (:name m)
                                              name-part (if nm (str " " nm) "")]
                                          (str (name (:provider m)) " "
                                               (name (:provider m)) "/" (:id m) " "
                                               (name (:provider m)) " " (:id m)
                                               name-part))))))))

(defn- scope-text-str
  "Pi getScopeText — the active scope accented."
  [st]
  (let [th (theme/get-current-theme)
        scope (fn [s] (if (= s (:scope st))
                        (theme/fg th :accent (name s))
                        (theme/fg th :muted (name s))))]
    (str (theme/fg th :muted "Scope: ")
         (scope :all) (theme/fg th :muted " | ") (scope :scoped))))

(defn- scope-hint-str
  "Pi getScopeHintText — the Tab scope hint, styled with dim key + muted
   description (pi keyHint + theme.fg muted)."
  []
  (str (app-kb/key-hint "tui.input.tab" "scope")
       (let [th (theme/get-current-theme)]
         (theme/fg th :muted " (all/scoped)"))))

(defn- model-refresh!
  "Rebuild the list rows and scope text from the current state (pi
   ModelSelectorComponent.updateList + filterModels)."
  [this]
  (let [st @(:state-atom this)
        th (theme/get-current-theme)
        filtered (filtered-items st)
        n (count filtered)
        selected (min (:selected-idx st) (max 0 (dec n)))
        _ (swap! (:state-atom this) assoc :selected-idx selected)
        max-visible 10
        start-idx (max 0 (min (- selected (quot max-visible 2))
                              (- n max-visible)))
        end-idx (min (+ start-idx max-visible) n)
        rows (container/make-container)]
    (if (zero? n)
      (container/container-add-child
       rows (text/make-text (theme/fg th :muted "  No matching models") 1 0))
      (doseq [i (range start-idx end-idx)]
        (let [{:keys [model]} (nth filtered i)
              is-selected (= i selected)
              is-current (models-equal? (:current st) model)
              prefix (if is-selected (theme/fg th :accent "→ ") "  ")
              id-text (if is-selected
                        (theme/fg th :accent (:id model))
                        (:id model))
              badge (theme/fg th :muted (str " [" (name (:provider model)) "]"))
              check (when is-current (theme/fg th :success " ✓"))]
          (container/container-add-child
           rows (text/make-text (str prefix id-text badge check) 1 0)))))
    (when (or (pos? start-idx) (< end-idx n))
      (container/container-add-child
       rows (text/make-text (theme/fg th :muted
                                      (str "  (" (inc selected) "/" n ")"))
                            1 0)))
    (when (pos? n)
      (let [selected-model (:model (nth filtered selected))]
        (container/container-add-child rows (spacer/make-spacer 1))
        (doseq [line (model-info/model-info-lines selected-model)]
          (container/container-add-child
           rows (text/make-text line 1 0)))))
    (container/container-set-children! (:list-container this) @(:children rows))
    (when-let [stx (:scope-text this)]
      (text/text-set! stx (scope-text-str st)))
    (when-let [sh (:scope-hint-text this)]
      (text/text-set! sh (scope-hint-str)))))

(defn make-model-selector
  "Create the model selector overlay component (pi ModelSelectorComponent).
   MODELS — all available models (sorted current-first); SCOPED-MODELS — the
   session scoped models (may be empty); CURRENT-MODEL — the model in use
   (marked with ✓ and selected initially). Options: :search (pre-filled
   filter), :on-select (fn [model]), :on-cancel (fn)."
  [models scoped-models current-model & {:keys [search on-select on-cancel]}]
  (let [th (theme/get-current-theme)
        sorted (vec (sort-models current-model models))
        st (atom {:all-models sorted
                  :scoped-models (vec scoped-models)
                  :scope (if (seq scoped-models) :scoped :all)
                  :current current-model
                  :selected-idx 0
                  :search (or search "")})
        search-input (input/make-input)
        list-container (container/make-container)
        c (container/make-container)
        scope-text (when (seq scoped-models) (text/make-text "" 1 0))
        scope-hint-text (when (seq scoped-models) (text/make-text "" 1 0))
        hint-text (when-not (seq scoped-models)
                    (text/make-text
                     (theme/fg th :warning
                               "Only showing models from configured providers. Use /login to add providers.")
                     1 0))
        add (fn [child] (container/container-add-child c child))]
    (add (db/make-dynamic-border #(theme/fg th :accent %)))
    (add (spacer/make-spacer 1))
    (when hint-text (add hint-text))
    (when scope-text (add scope-text))
    (when scope-hint-text (add scope-hint-text))
    (add (spacer/make-spacer 1))
    (add search-input)
    (add (spacer/make-spacer 1))
    (add list-container)
    (add (spacer/make-spacer 1))
    (add (db/make-dynamic-border #(theme/fg th :accent %)))
    (let [sel (map->ModelSelector
               {:container c
                :search-input search-input
                :list-container list-container
                :state-atom st
                :scope-text scope-text
                :scope-hint-text scope-hint-text
                :on-select-atom (atom on-select)
                :on-cancel-atom (atom on-cancel)
                :focused? (atom false)
                :cache-atom (atom nil)})]
      (when (seq search)
        (input/input-set-value! search-input search))
      ;; initial selection: the current model when present, else the top row
      ;; (pi loadModelsFromSnapshot — the index comes from the ACTIVE list:
      ;; the scoped models when scoped, else all models); a pre-filled
      ;; search moves to the top
      (let [active (if (seq scoped-models) (vec scoped-models) sorted)
            idx (first (keep-indexed (fn [i m] (when (models-equal? current-model m) i))
                                     active))]
        (swap! st assoc :selected-idx (if (seq search) 0 (or idx 0))))
      (model-refresh! sel)
      sel)))

;; ─── IFocusable — forward to the search input (IME cursor positioning) ─────

(extend-type ModelSelector
  protocols/IFocusable
  (focused [this] @(:focused? this))
  (set-focused! [this val]
    (reset! (:focused? this) val)
    (protocols/set-focused! (:search-input this) val)))

(defn show-model-selector
  "Model selector overlay (pi ModelSelectorComponent): a visible search
   filter, wrap-around arrow navigation, the current model marked with ✓ —
   bound to Ctrl+L, bare /model, and the /model resolution-failure path
   with SEARCH-TERM pre-filled. When session scoped models are set the
   selector opens scoped (Tab toggles all/scoped)."
  ([cs] (show-model-selector cs nil))
  ([cs search-term]
   (let [ag @(:agent-state cs)
         available (models/get-available)]
     (if (empty? available)
       (chat-history/chat-history-add-message! (:chat-history cs)
                                               {:role :assistant
                                                :content "No models available. Configure a provider first (/login)."})
       (let [scoped (vec (keep (fn [id]
                                 (let [slash (str/index-of id "/")]
                                   (when slash
                                     (models/get-model (keyword (subs id 0 slash))
                                                       (subs id (inc slash))))))
                               (agent/get-scoped-models ag)))
             current (models/get-model @(:provider ag) @(:model ag))
             sel (make-model-selector
                  available scoped current
                  :search search-term
                  :on-select (fn [m]
                               (tui/tui-hide-overlay (:tui cs))
                               (apply-model-switch! cs m nil)
                               (tui/tui-request-render (:tui cs)))
                  :on-cancel (fn []
                               (tui/tui-hide-overlay (:tui cs))
                               (tui/tui-request-render (:tui cs))))]
         (tui/tui-show-overlay (:tui cs) sel :width 55 :max-height 24)
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
                                   (chat-history/chat-history-add-message!
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
        sel (scoped-models-selector/make-scoped-models-selector
             available initial
             :on-change update-session-models
             :on-persist (fn [enabled-ids]
                           (let [all-enabled? (or (nil? enabled-ids)
                                                  (and (= (count enabled-ids) (count available))
                                                       (every? available-ids enabled-ids)))]
                             (cfg/set-enabled-models! (when-not all-enabled? enabled-ids))
                             (chat-history/chat-history-add-message!
                              (:chat-history cs)
                              {:role :assistant
                               :content "Model selection saved to settings."})))
             :on-cancel (fn []
                          (tui/tui-hide-overlay (:tui cs))
                          (tui/tui-request-render (:tui cs))))]
    (tui/tui-show-overlay (:tui cs) sel :width 62 :max-height 24)
    (tui/tui-request-render (:tui cs))))
