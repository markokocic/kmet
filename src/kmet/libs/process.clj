(ns kmet.libs.process
  "Process tree management: collect descendants, kill a process tree, and
   a pid registry for cleanup on shutdown. No project dependencies —
   mirrors the tree-kill npm package / pkill -P."
  (:require [clojure.string :as str]
            [babashka.process :as proc]))

(def windows-os?
  (str/starts-with? (System/getProperty "os.name") "Windows"))

(defn collect-descendant-pids
  "Collect descendant pids of pid by walking the process table.
   Returns a vector ordered parents-first (direct children before
   grandchildren). Portable across Linux and macOS (no /proc dependency
   on macOS)."
  [pid]
  (try
    (let [p (proc/process ["ps" "-eo" "pid=,ppid="] {:out :pipe :err :ignore})
          _ @p
          lines (str/split-lines (slurp (:out p)))
          parent-map (into {}
                           (keep (fn [line]
                                   (when-let [[_ c pp] (re-find #"^\s*(\d+)\s+(\d+)" line)]
                                     [(Long/parseLong (str/trim c)) (Long/parseLong (str/trim pp))])))
                           lines)]
      (loop [frontier #{pid} found []]
        (let [children (filterv (fn [[c p]]
                                  (and (contains? parent-map c)
                                       (contains? frontier p)
                                       (not (contains? frontier c))
                                       (not (some #{c} found))))
                                parent-map)]
          (if (empty? children)
            found
            (let [level (mapv first children)]
              (recur (set level) (into found level)))))))
    (catch Exception _ [])))

(def setsid-path
  "Path to the setsid executable, or nil when unavailable. Spawning a
   command via setsid makes it its own session/process-group leader, so
   kill-process-tree! can group-kill it in one shot — including background
   jobs that shells reparent outside the ppid tree (mksh on Termux forks
   `cmd &` from the sh's parent). Resolved lazily; nil on Windows and
   macOS (no setsid there)."
  (delay
    (when-not windows-os?
      (try
        (let [p (proc/process ["sh" "-c" "command -v setsid"] {:out :pipe :err :ignore})
              _ @p
              path (str/trim (slurp (:out p)))]
          (when (seq path) path))
        (catch Exception _ nil)))))

(defn- group-kill
  "SIGKILL the whole process group of pid (pid must be a group leader).
   Returns the kill exit code (0 = the group existed and was signaled)."
  [pid]
  (try
    (:exit @(proc/process ["kill" "-9" (str "-" pid)]
                          {:out :inherit :err :ignore}))
    (catch Exception _ -1)))

(defn kill-process-tree!
  "Kill a process and all its descendants.
   Group kill first: when the process was spawned via setsid (see
   setsid-path) it is its own group leader, so `kill -9 -PID` reaps the
   whole tree in one shot — including background jobs reparented outside
   the ppid tree (mksh on Termux forks `cmd &` from the sh's parent, so
   ppid-walking alone misses them). For non-leaders the group kill fails
   (ESRCH) and we fall back to walking the process table and killing each
   descendant directly (deepest first) — this also covers ProcessBuilder
   children that share the app's process group."
  [pid]
  (if windows-os?
    ;; Windows: taskkill /T kills the tree natively
    (try
      @(proc/process ["taskkill" "/F" "/T" "/PID" (str pid)]
                     {:out :inherit :err :ignore})
      (catch Exception _e nil))
    (if (zero? (group-kill pid))
      nil
      (doseq [p (concat (reverse (collect-descendant-pids pid)) [pid])]
        (try
          @(proc/process ["kill" "-9" (str p)] {:out :inherit :err :ignore})
          (catch Exception _e nil))))))

;; ─── Pid registry ──────────────────────────────────────────────────────────
;; Processes spawned by the app, killed in bulk on shutdown.

(defonce ^:private tracked-pids (atom #{}))
(defn track-pid! [pid] (swap! tracked-pids conj pid))
(defn untrack-pid! [pid] (swap! tracked-pids disj pid))
(defn kill-tracked-children! []
  (doseq [pid @tracked-pids]
    (try (kill-process-tree! pid) (catch Exception _ nil)))
  (reset! tracked-pids #{}))
