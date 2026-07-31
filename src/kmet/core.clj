(ns kmet.core
  "Main entry point for kmet — minimal coding agent TUI.
   CLI argument parsing and mode dispatch. The interactive TUI lives in
   kmet.modes.interactive; print mode in kmet.modes.print.
   pi: dist/cli.js."
  (:require [kmet.modes.interactive :as interactive]
            [kmet.modes.print :as print-mode]
            [kmet.config :as cfg]
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

          (#{"--provider"} arg)
          (if (seq rest-args)
            (let [p (keyword (first rest-args))]
              (recur (rest rest-args) (assoc opts :provider p)))
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
  (println "  --model <id>          Model to use")
  (println "  --provider <name>     Provider (openai, anthropic, opencode-go)")
  (println "  -t, --thinking <level> Thinking level (off, low, medium, high)")
  (println "  -h, --help            Show this help")
  (println)
  (println "Examples:")
  (println "  kmet                    Start interactive TUI")
  (println "  kmet -p \"list files\"    Print response and exit")
  (println "  kmet --model gpt-4o     Start with specific model")
  (println "  kmet @tasks.md         Start with file content"))

;; ─── Main ──────────────────────────────────────────────────────────────────

(defn -main
  "Entry point. Parses CLI args and dispatches to the requested mode."
  [& args]
  (let [opts (parse-args args)]

    (when (:help opts)
      (print-usage)
      (System/exit 0))

    (when (:print opts)
      (let [msg (str/join " " (:messages opts))]
        (if (empty? msg)
          (do (println "No message provided. Usage: kmet -p \"your message\"")
              (System/exit 1))
          (do (print-mode/run (assoc opts :messages [msg]))
              (System/exit 0)))))

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
          (System/exit 1))))))
