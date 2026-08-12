(ns kmet.core
  "Main entry point for kmet — minimal coding agent TUI.
   CLI argument parsing and mode dispatch. The interactive TUI lives in
   kmet.modes.interactive; print mode in kmet.modes.print.
   pi: dist/cli.js."
  (:require [kmet.modes.interactive :as interactive]
            [kmet.modes.print :as print-mode]
            [kmet.config :as cfg]
            [kmet.app.models :as models]
            [kmet.app.model-resolver :as resolver]
            [kmet.debug :as debug]
            [clojure.string :as str]))

;; ─── CLI argument parsing ──────────────────────────────────────────────────

(defn- parse-args [args]
  (loop [args args
         opts {:provider nil
               :model nil
               :print false
               :continue false
               :resume false
               :debug false
               :messages []}]
    (if (empty? args)
      opts
      (let [arg (first args)
            rest-args (rest args)]
        (cond
          (#{"-d" "--debug"} arg)
          (recur rest-args (assoc opts :debug true))

          (#{"-p" "--print"} arg)
          (recur rest-args (assoc opts :print true))

          (#{"-c" "--continue"} arg)
          (recur rest-args (assoc opts :continue true))

          (#{"-t" "--thinking"} arg)
          (if (seq rest-args)
            (let [level (keyword (first rest-args))]
              (recur (rest rest-args) (assoc opts :thinking level)))
            (recur rest-args opts))

          (#{"-r" "--resume"} arg)
          (recur rest-args (assoc opts :resume true))

          (#{"--model"} arg)
          (if (seq rest-args)
            (recur (rest rest-args) (assoc opts :model (first rest-args)))
            (recur rest-args opts))

          (#{"--system-prompt"} arg)
          (if (seq rest-args)
            (recur (rest rest-args) (assoc opts :system-prompt (first rest-args)))
            (recur rest-args opts))

          (#{"--append-system-prompt"} arg)
          (if (seq rest-args)
            (recur (rest rest-args) (update opts :append-system-prompt (fnil conj []) (first rest-args)))
            (recur rest-args opts))

          (#{"--provider"} arg)
          (if (seq rest-args)
            (let [p (keyword (first rest-args))]
              (recur (rest rest-args) (assoc opts :provider p)))
            (recur rest-args opts))

          (#{"--models"} arg)
          (if (seq rest-args)
            (recur (rest rest-args)
                   (assoc opts :models (mapv str/trim (str/split (first rest-args) #","))))
            (recur rest-args opts))

          (#{"-h" "--help"} arg)
          (assoc opts :help true)

          (str/starts-with? arg "@")
          (let [file-path (subs arg 1)]
            (if (seq file-path)
              (let [file-content (try (slurp file-path)
                                      (catch Exception _
                                        (binding [*out* *err*]
                                          (println "Warning: could not read" file-path))
                                        ""))]
                (recur rest-args (update opts :messages conj file-content)))
              (do (binding [*out* *err*] (println "Warning: empty path after @"))
                  (recur rest-args opts))))

          :else
          (recur rest-args (update opts :messages conj arg)))))))

(defn- print-usage []
  (println "Usage: kmet [options] [@files...] [messages...]")
  (println)
  (println "Options:")
  (println "  -d, --debug           Log to debug.log")
  (println "  -p, --print           Print response and exit (non-interactive)")
  (println "  -c, --continue        Continue most recent session")
  (println "  -r, --resume          Browse sessions")
  (println "  --model <id>          Model to use (pattern: provider/model[:thinking])")
  (println "  --provider <name>     Provider (opencode-go, opencode, deepseek,\n                        github-copilot)")
  (println "  --models <patterns>   Comma-separated model patterns for Ctrl+P cycling")
  (println "  --system-prompt <txt> Replace the system prompt (or path to a file)")
  (println "  --append-system-prompt <txt> Append to the system prompt (repeatable)")
  (println "  -t, --thinking <level> Thinking level (off, minimal, low, medium, high, xhigh, max)")
  (println "  -h, --help            Show this help")
  (println)
  (println "Examples:")
  (println "  kmet                    Start interactive TUI")
  (println "  kmet -p \"list files\"    Print response and exit")
  (println "  kmet --model deepseek-v4-flash  Start with a specific model")
  (println "  kmet @tasks.md         Start with file content"))

;; ─── Main ──────────────────────────────────────────────────────────────────

(defn- resolve-cli-model-opts
  "Resolve CLI --model/--models against the catalog registry (pi
   resolveCliModel wired at dispatch): patterns resolve with provider
   inference and :thinking suffixes; failures exit 1; warnings print to
   stderr."
  [opts]
  (let [opts (if-let [cli-model (:model opts)]
               (let [result (resolver/resolve-cli-model (:provider opts) cli-model
                                                        (models/get-models))]
                 (if (:error result)
                   (do (binding [*out* *err*] (println "Error:" (:error result)))
                       (System/exit 1))
                   (do (when (:warning result)
                         (binding [*out* *err*] (println "Warning:" (:warning result))))
                       (cond-> opts
                         (:provider result) (assoc :provider (:provider result))
                         (:model-id result) (assoc :model (:model-id result))
                         (and (:thinking-level result) (nil? (:thinking opts)))
                         (assoc :thinking (:thinking-level result))))))
               opts)
        opts (if-let [patterns (:models opts)]
               (let [{:keys [models warnings]}
                     (resolver/resolve-model-scope patterns (models/get-models))]
                 (doseq [w warnings]
                   (binding [*out* *err*] (println "Warning:" w)))
                 (assoc opts :models models))
               opts)]
    opts))

(defn -main
  "Entry point. Parses CLI args and dispatches to the requested mode."
  [& args]
  (let [opts (parse-args args)]

    (when (:help opts)
      (print-usage)
      (System/exit 0))

    ;; Provider/model registry — loads the committed catalogs (pi registers
    ;; its generated providers at startup), then the models.edn user config
    ;; layer (custom providers + overrides; errors surface as warnings)
    (models/load-catalogs!)
    (models/load-models-config!)
    (when-let [err (models/get-model-config-error)]
      (binding [*out* *err*]
        (println "Warning: models.edn:" err)))

    ;; CLI --model/--models patterns resolve against the registry (pi
    ;; resolveCliModel at dispatch) before mode dispatch
    (let [opts (resolve-cli-model-opts opts)]
      (when (:print opts)
        (let [msg (str/join " " (:messages opts))]
          (if (empty? msg)
            (do (println "No message provided. Usage: kmet -p \"your message\"")
                (System/exit 1))
            (let [result (print-mode/run (assoc opts :messages [msg]))]
              ;; exit 1 when the run errored (nil result = no response text)
              (System/exit (if (nil? result) 1 0))))))
      (when (:debug opts)
        (debug/enable!)
        (debug/log "kmet started with --debug"))

      (println "Starting kmet...")

      ;; Initialize configuration and themes
      (let [config (cfg/init!)]
        (try
          (interactive/run config opts)
          (catch Exception e
            (debug/log-error "unhandled exception: " e)
            (binding [*out* *err*]
              (println "Error:" (ex-message e))
              (.printStackTrace e))
            (System/exit 1)))))))
