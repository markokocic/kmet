(ns kmet.app.ui.settings-selector
  "Settings selector overlay (pi: showSettingsSelector +
   settings-selector.ts, simplified) — the thinking-level row (the current
   model's available levels, persisted to settings :thinking), the
   hide-thinking toggle, and the retry block (settings.edn :retry —
   enabled / max-retries / base-delay-ms, applied live to the agent)."
  (:require [kmet.app.loop :as agent]
            [kmet.app.theme-controller :as theme-ctrl]
            [kmet.ai.api.shared :as shared]
            [kmet.ai.models :as models]
            [kmet.app.ui :as ui]
            [kmet.app.ui.model-selector :as model-selector]
            [kmet.config :as cfg]
            [kmet.tui.core :as tui]
            [kmet.tui.theme :as th]
            [kmet.tui.components.settings-list :as settings-list]))

(defn show-settings
  "Settings selector (pi: showSettingsSelector) — kmet implements the
   thinking-level row (the current model's available levels, persisted to
   settings :thinking), the hide-thinking toggle, and the retry block
   (settings.edn :retry — enabled / max-retries / base-delay-ms, applied
   live to the agent); the rest of pi's settings surface stays on the
   not-implemented list."
  [cs]
  (let [ag @(:agent-state cs)
        model (models/get-model @(:provider ag) @(:model ag))
        levels (if model (shared/get-supported-thinking-levels model) [:off])
        current (or (some #{(keyword @(:thinking ag))} levels) (first levels))
        retry-atom (atom (cfg/get-retry-settings-live (:config cs)))
        apply-retry! (fn []
                       (let [r @retry-atom]
                         (agent/set-max-retries! ag (if (:enabled r) (:max-retries r) 0))
                         (agent/set-base-delay-ms! ag (:base-delay-ms r))))
        save-retry! (fn [path value]
                      (cfg/save-setting! path value)
                      (apply-retry!))
        base-items [{:id :thinking
                     :label "Thinking level"
                     :value current
                     :values levels}
                    {:id :hide-thinking
                     :label "Hide thinking"
                     ;; the live chat-history flag, not the startup config
                     ;; snapshot — Ctrl+T toggles it at runtime
                     :value (if (ui/chat-history-get-thinking-hidden (:chat-history cs)) "on" "off")
                     :values ["off" "on"]}
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
        ;; theme row only when a controller is present (tests build a minimal
        ;; cs without one)
        items (if (:theme-controller cs)
                (conj base-items
                      {:id :theme
                       :label "Theme"
                       :value (theme-ctrl/get-active-theme-name (:theme-controller cs))
                       :values (sort (keys (th/get-all-themes)))})
                base-items)
        sl (settings-list/make-settings-list
            items
            :on-change (fn [id value]
                         (case id
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
          (tui/tui-hide-overlay (:tui cs))
          (tui/tui-request-render (:tui cs))))
    (tui/tui-show-overlay (:tui cs) sl :width 55 :height 9)
    (tui/tui-request-render (:tui cs))))
