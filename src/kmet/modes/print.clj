(ns kmet.modes.print
  "Print mode — non-interactive: send message, print response, exit.
   pi: modes/print-mode.ts."
  (:require [clojure.string :as str]
            [babashka.fs :as fs]
            [kmet.app.loop :as agent]
            [kmet.ai.models :as models]
            [kmet.app.skills :as skills]
            [kmet.app.tools.core :as tools]
            [kmet.libs.context :as context]
            [kmet.app.prompts :as prompts]
            [kmet.app.extensions :as extensions]
            [kmet.config :as cfg]))

(defn- extension-before-tool-call
  "Chain extension tool-call hooks (pi: beforeToolCall) — print mode wires
   them like interactive mode, so write-reject / edit-warn hooks fire in
   headless runs too. Returns nil | {:block true :reason} | {:args ...}."
  [ctx]
  (loop [hooks (extensions/get-tool-call-hooks)
         blocked nil
         args (:args ctx)]
    (if-let [hook (first hooks)]
      (let [r (try (hook (assoc ctx :args args))
                   (catch Exception e
                     {:block true
                      :reason (str "tool-call hook error: " (ex-message e))}))]
        (cond
          (:block r)
          (recur (next hooks) (or blocked {:block true :reason (:reason r)}) args)
          (contains? r :args)
          (recur (next hooks) blocked (:args r))
          :else
          (recur (next hooks) blocked args)))
      (or blocked (when (not= args (:args ctx)) {:args args})))))

(defn- extension-after-tool-call
  "Chain extension tool-result hooks (pi: afterToolCall) — print mode wires
   them like interactive mode. Returns the (possibly rewritten) result."
  [ctx]
  (reduce (fn [result hook]
            (if-let [r (try (hook (assoc ctx :result result
                                         :is-error (:is-error result false)))
                            (catch Exception e
                              {:content (str "tool-result hook error: " (ex-message e))
                               :is-error true}))]
              (cond-> result
                (:content r) (assoc :content (:content r))
                (contains? r :is-error) (assoc :is-error (:is-error r)))
              result))
          (:result ctx)
          (extensions/get-tool-result-hooks)))

(defn run
  "Run in non-interactive mode: send message, print response, exit.
   opts: :model, :provider, :messages, :system-prompt, :append-system-prompt, :config"
  [opts]
  (let [{:keys [model provider messages config]} opts
        config (cfg/apply-cli-overrides (or config (cfg/load-config :no-env? true)) opts)
        _ (doseq [d (cfg/resource-dirs config :skills-dir ".kmet/skills")]
            (skills/load-skills-from-dir d))
        _ (doseq [d (cfg/resource-dirs config :prompts-dir ".kmet/prompts")]
            (prompts/load-prompt-templates-from-dir d))
        system-prompt-opts {:custom-prompt (cfg/get-custom-prompt config)
                            :append-prompt (cfg/get-append-system-prompt config)
                            :context-files (context/load-project-context-files
                                            (cfg/get-agent-dir) (str (fs/cwd)))
                            :tools (vals (tools/get-all-tools))}
        system-prompt (apply skills/build-system-prompt
                             (mapcat identity system-prompt-opts))
        resolved-provider (or provider (cfg/get-provider config))
        resolved-model (or model (models/resolve-config-model config))
        ag (agent/make-agent-state
            :model resolved-model
            :provider resolved-provider
            :system system-prompt
            :system-prompt-opts system-prompt-opts
            :before-tool-call extension-before-tool-call
            :after-tool-call extension-after-tool-call
            ;; pi: retry settings (settings.edn :retry block — enabled gates
            ;; max-retries to 0)
            :max-retries (let [retry (cfg/get-retry-settings config)]
                           (if (:enabled retry) (:max-retries retry) 0))
            :base-delay-ms (:base-delay-ms (cfg/get-retry-settings config)))
        _ (agent/init-scoped-models! ag config)
        result-promise (promise)
        ;; pi: session.prompt expands skill commands + prompt templates
        message (-> (str/join " " messages)
                    (skills/expand-skill-command)
                    (prompts/expand-prompt-template (prompts/get-prompt-templates)))]
    (agent/run-agent-turn ag
                          {:message message
                           :on-text (fn [t] (print t) (flush))
                           :on-done (fn [text] (println) (deliver result-promise text))
                           :on-error (fn [e] (binding [*out* *err*] (println "Error:" e))
                                       (deliver result-promise nil))})
    @result-promise))
