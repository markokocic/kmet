(ns kmet.app.ui.settings-selector
  "Settings selector panel (pi: showSettingsSelector +
   settings-selector.ts) — pi's rows backed by kmet machinery: thinking
   level (the current model's available levels), hide-thinking, auto-compact,
   steering/follow-up queue modes, HTTP idle timeout, cache-miss notices,
   tree filter mode, editor/output padding, autocomplete max items, hardware
   cursor, the retry block (settings.edn :retry — enabled / max-retries /
   base-delay-ms, applied live to the agent), and a theme row."
  (:require [kmet.app.loop :as agent]
            [kmet.app.theme-controller :as theme-ctrl]
            [kmet.ai.api.shared :as shared]
            [kmet.ai.models :as models]
            [kmet.app.ui :as ui]
            [kmet.app.ui.dock :as dock]
            [kmet.app.ui.model-selector :as model-selector]
            [kmet.config :as cfg]
            [kmet.tui.core :as tui]
            [kmet.tui.protocols :as protocols]
            [kmet.tui.theme :as th]
            [kmet.tui.components.container :as container]
            [kmet.tui.components.dynamic-border :as db]
            [kmet.tui.components.settings-list :as settings-list]))

;; pi: HTTP_IDLE_TIMEOUT_CHOICES (http-dispatcher.ts)
(def ^:private http-idle-timeout-choices
  [{:label "30 sec" :ms 30000}
   {:label "1 min" :ms 60000}
   {:label "2 min" :ms 120000}
   {:label "5 min" :ms 300000}
   {:label "disabled" :ms 0}])

;; pi: timeoutMs ?? httpIdleTimeoutMs — the whole-request total deadline.
;; The default (nil) is "use idle" rendered as its own choice; explicit
;; values use the same set as the idle choices; 0 means disabled (uses idle
;; anyway via the fallback, so it renders the same as the default).
(def ^:private http-total-timeout-choices
  (conj http-idle-timeout-choices {:label "use idle" :ms nil}))

(defn- format-idle-timeout
  "pi: formatHttpIdleTimeoutMs — a known choice shows its label; anything
   else renders as \"N sec\"."
  [ms]
  (or (:label (some #(when (= ms (:ms %)) %) http-idle-timeout-choices))
      (str (quot ms 1000) " sec")))

(defn- format-total-timeout
  "pi: formatHttpIdleTimeoutMs — a known choice shows its label; nil renders
   the default 'use idle'; anything else renders as \"N sec\"."
  [ms]
  (if (nil? ms)
    "use idle"
    (format-idle-timeout ms)))

(defn- set-editor-setting!
  "Apply an editor live setting via APPLY! to the app editor and, when it
   is protocol-backed and different, to the currently mounted editor
   (pi applies to the active editor)."
  [cs apply!]
  (when-let [ed (:editor cs)]
    (apply! ed))
  (let [cur (some-> cs :current-editor-atom deref)]
    (when (and cur (not= cur (:editor cs))
               (satisfies? protocols/IEditorComponent cur))
      (apply! cur))))

(defn- bool-row
  "A true/false toggle row (pi: values [\"true\" \"false\"])."
  [id label v]
  {:id id :label label
   :value (if v "true" "false")
   :values ["true" "false"]})

(defn show-settings
  "Settings selector (pi: showSettingsSelector) — rows for every setting
   with a kmet backend; each change applies live and persists to the global
   settings.edn (pi: SettingsManager setters)."
  [cs]
  (let [sel-atom (atom nil)
        ag @(:agent-state cs)
        model (models/get-model @(:provider ag) @(:model ag))
        levels (if model (shared/get-supported-thinking-levels model) [:off])
        current (or (some #{(keyword @(:thinking ag))} levels) (first levels))
        config (:config cs)
        retry-atom (atom (cfg/get-retry-settings-live config))
        ;; or-guard: an explicit nil in settings.edn must not reach quot
        idle-ms (or (cfg/get-setting-live config :http-idle-timeout-ms 300000)
                    300000)
        ;; live value: nil (absent) = use idle; explicit = override
        total-ms (cfg/get-setting-live config :http-total-timeout-ms nil)
        apply-retry! (fn []
                       (let [r @retry-atom]
                         (swap! (:cfg ag) assoc :max-retries (if (:enabled r) (:max-retries r) 0))
                         (swap! (:cfg ag) assoc :base-delay-ms (:base-delay-ms r))))
        save-retry! (fn [path value]
                      (cfg/save-setting! path value)
                      (apply-retry!))
        base-items [(bool-row :auto-compact "Auto-compact" (:auto-compact @(:cfg ag)))
                    {:id :steering-mode
                     :label "Steering mode"
                     :value (name (:steering-mode @(:cfg ag)))
                     :values ["one-at-a-time" "all"]}
                    {:id :follow-up-mode
                     :label "Follow-up mode"
                     :value (name (:follow-up-mode @(:cfg ag)))
                     :values ["one-at-a-time" "all"]}
                    {:id :http-idle-timeout
                     :label "HTTP idle timeout"
                     :value (format-idle-timeout idle-ms)
                     :values (mapv :label http-idle-timeout-choices)}
                    {:id :http-total-timeout
                     :label "HTTP total timeout"
                     :value (format-total-timeout total-ms)
                     :values (mapv :label http-total-timeout-choices)}
                    (bool-row :cache-miss-notices "Cache miss notices"
                              (cfg/get-show-cache-miss-notices config))
                    {:id :tree-filter-mode
                     :label "Tree filter mode"
                     :value (name (cfg/get-tree-filter-mode config))
                     :values ["default" "no-tools" "user-only" "labeled-only" "all"]}
                    {:id :thinking
                     :label "Thinking level"
                     :value current
                     :values levels}
                    {:id :hide-thinking
                     :label "Hide thinking"
                     ;; the live chat-history flag, not the startup config
                     ;; snapshot — Ctrl+T toggles it at runtime
                     :value (if (ui/chat-history-get-thinking-hidden (:chat-history cs)) "on" "off")
                     :values ["off" "on"]}
                    {:id :editor-padding
                     :label "Editor padding"
                     :value (cfg/get-editor-padding-x config)
                     :values [0 1 2 3]}
                    {:id :output-padding
                     :label "Output padding"
                     :value (cfg/get-output-pad config)
                     :values [0 1]}
                    {:id :autocomplete-max-visible
                     :label "Autocomplete max items"
                     :value (cfg/get-autocomplete-max-visible config)
                     :values [3 5 7 10 15 20]}
                    {:id :auto-retry
                     :label "Auto retry"
                     :value (:enabled @retry-atom)
                     :values [true false]}
                    {:id :max-retries
                     :label "Max retries"
                     :value (:max-retries @retry-atom)
                     :values [0 1 2 3 5 8 10]}
                    {:id :base-delay-ms
                     :label "Base delay (ms)"
                     :value (:base-delay-ms @retry-atom)
                     :values [500 1000 2000 4000 8000]}]
        ;; hardware-cursor row needs the live tui, theme row the theme
        ;; controller (tests build a minimal cs without them)
        items (cond-> (if (:tui cs)
                        (conj base-items
                              (bool-row :show-hardware-cursor "Show hardware cursor"
                                        (tui/tui-get-show-hardware-cursor (:tui cs))))
                        base-items)
                (:theme-controller cs)
                (conj {:id :theme
                       :label "Theme"
                       :value (theme-ctrl/get-active-theme-name (:theme-controller cs))
                       :values (sort (keys (th/get-all-themes)))}))
        sl (settings-list/make-settings-list
            items
            :enable-search true
            :on-change (fn [id value]
                         (case id
                           :auto-compact
                           (let [on? (= value "true")]
                             (agent/set-auto-compact! ag on?)
                             (cfg/save-setting! [:auto-compact] on?)
                             (when-let [f (:footer-comp cs)]
                               (ui/footer-set-auto-compact! f on?)))
                           :steering-mode
                           (let [mode (keyword value)]
                             (swap! (:cfg ag) assoc :steering-mode mode)
                             (cfg/save-setting! [:steering-mode] mode))
                           :follow-up-mode
                           (let [mode (keyword value)]
                             (swap! (:cfg ag) assoc :follow-up-mode mode)
                             (cfg/save-setting! [:follow-up-mode] mode))
                           :http-idle-timeout
                           (let [ms (:ms (some #(when (= value (:label %)) %)
                                               http-idle-timeout-choices))]
                             (agent/set-http-idle-timeout-ms! ag ms)
                             (cfg/save-setting! [:http-idle-timeout-ms] ms))
                           :http-total-timeout
                           (let [ms (:ms (some #(when (= value (:label %)) %)
                                               http-total-timeout-choices))]
                             (agent/set-http-total-timeout-ms! ag ms)
                             (cfg/save-setting! [:http-total-timeout-ms] ms))
                           ;; read live at emit time — no runtime state needed
                           :cache-miss-notices
                           (cfg/save-setting! [:show-cache-miss-notices] (= value "true"))
                           :tree-filter-mode
                           (cfg/save-setting! [:tree-filter-mode] (keyword value))
                           :theme
                           (let [result (theme-ctrl/set-theme-name!
                                         (:theme-controller cs) value)]
                             (if (:success result)
                               (cfg/save-setting! [:theme] value)
                               (ui/chat-history-add-message!
                                (:chat-history cs)
                                {:role :info :label "Theme"
                                 :content (str "Failed to load theme \"" value
                                               "\": " (:error result))})))
                           :thinking
                           (let [level (keyword value)]
                             (agent/set-thinking-level! ag level)
                             (cfg/save-setting! [:thinking] level)
                             (model-selector/sync-footer-model! cs))
                           :hide-thinking
                           (let [hidden? (= value "on")]
                             (ui/chat-history-set-thinking-hidden!
                              (:chat-history cs) hidden?)
                             (cfg/set-hide-thinking-block! hidden?))
                           :editor-padding
                           (do (set-editor-setting! cs #(protocols/editor-set-padding-x! % value))
                               (cfg/save-setting! [:editor-padding-x] value))
                           :output-padding
                           (do (ui/chat-history-set-output-pad! (:chat-history cs) value)
                               (cfg/save-setting! [:output-pad] value))
                           :autocomplete-max-visible
                           (do (set-editor-setting!
                                cs #(protocols/editor-set-autocomplete-max-visible! % value))
                               (cfg/save-setting! [:autocomplete-max-visible] value))
                           :show-hardware-cursor
                           (let [on? (= value "true")]
                             (tui/tui-set-show-hardware-cursor! (:tui cs) on?)
                             (cfg/set-show-hardware-cursor! on?))
                           :auto-retry
                           (do (swap! retry-atom assoc :enabled (boolean value))
                               (save-retry! [:retry :enabled] (boolean value)))
                           :max-retries
                           (do (swap! retry-atom assoc :max-retries value)
                               (save-retry! [:retry :max-retries] value))
                           :base-delay-ms
                           (do (swap! retry-atom assoc :base-delay-ms value)
                               (save-retry! [:retry :base-delay-ms] value)))))]
    (settings-list/settings-list-set-on-escape!
     sl (fn []
          ((:done @sel-atom))
          (tui/tui-request-render (:tui cs))))
    ;; Frame the list like pi's SettingsSelectorComponent (DynamicBorder +
    ;; SettingsList + DynamicBorder); the list is the focus target (pi:
    ;; showSelector's focus) since the frame container is inert chrome.
    (let [th (th/get-current-theme)
          frame (container/make-container
                 [(db/make-dynamic-border #(th/fg th :accent %))
                  sl
                  (db/make-dynamic-border #(th/fg th :accent %))])]
      ;; pi: showSelector — mount the framed panel, focus the list
      ;; (focus: the interactive child)
      (reset! sel-atom {:done (dock/mount! cs frame sl)}))))
