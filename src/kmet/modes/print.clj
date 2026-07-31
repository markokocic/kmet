(ns kmet.modes.print
  "Print mode — non-interactive: send message, print response, exit.
   pi: modes/print-mode.ts."
  (:require [clojure.string :as str]
            [kmet.app.loop :as agent]
            [kmet.app.tools.core :as tools]
            [kmet.app.skills :as skills]
            [kmet.config :as cfg]))

(defn run
  "Run in non-interactive mode: send message, print response, exit.
   opts: :model, :provider, :messages, :config"
  [{:keys [model provider messages config]}]
  (let [config (or config (cfg/load-config :no-env? true))
        _ (skills/load-skills-from-dir (cfg/expand-path (:skills-dir config)))
        base-prompt (or (:system-prompt config)
                        "You are kmet, a minimal coding agent. Help the user with their tasks.
Use the available tools to read, write, edit files, and execute commands.
Be precise and concise in your responses.")
        system-prompt (skills/build-system-prompt base-prompt
                        :tools (vals (tools/get-all-tools)))
        resolved-provider (or provider (cfg/get-provider config))
        resolved-model (or model (cfg/get-model config))
        ag (agent/make-agent-state
             :model resolved-model
             :provider resolved-provider
             :system system-prompt)
        _ (when (seq (:models config))
            (agent/set-models! ag (:models config)))
        result-promise (promise)]
    (agent/run-agent-turn ag
      {:message (str/join " " messages)
       :on-text (fn [t] (print t) (flush))
       :on-done (fn [text] (println) (deliver result-promise text))
       :on-error (fn [e] (binding [*out* *err*] (println "Error:" e))
                   (deliver result-promise nil))})
    @result-promise))
