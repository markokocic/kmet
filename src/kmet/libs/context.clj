(ns kmet.libs.context
  "Project context files (AGENTS.md/CLAUDE.md) loaded into the system prompt.
   pi: core/resource-loader.js loadProjectContextFiles + loadContextFileFromDir."
  (:require [babashka.fs :as fs]))

(def ^:private context-file-names
  "Candidate names checked in order per directory (pi: loadContextFileFromDir)."
  ["AGENTS.md" "AGENTS.MD" "CLAUDE.md" "CLAUDE.MD"])

(defn- load-context-file-from-dir
  "First existing AGENTS.md/CLAUDE.md in dir (pi: loadContextFileFromDir).
   Returns {:path str :content str} or nil."
  [dir]
  (some (fn [name]
          (let [f (str (fs/path dir name))]
            (when (and (fs/exists? f) (fs/regular-file? f))
              {:path f :content (slurp f)})))
        context-file-names))

(defn- norm-dir
  "Absolute, home-expanded directory string for stable dedupe."
  [d]
  (str (fs/absolutize (fs/expand-home (str d)))))

(defn load-project-context-files
  "Load context files for the system prompt (pi: loadProjectContextFiles):
   the agent dir's own AGENTS.md/CLAUDE.md first, then those found walking up
   from cwd to the filesystem root, deduped by path, nearest ancestor first.
   Deviations from pi: no git-worktree shadow detection (kmet has no worktree
   support)."
  [agent-dir cwd]
  (let [results (volatile! [])
        seen (volatile! #{})
        add! (fn [f]
               (when (and f (not (contains? @seen (:path f))))
                 (vswap! seen conj (:path f))
                 (vswap! results conj f)))]
    (add! (load-context-file-from-dir (norm-dir agent-dir)))
    (loop [dir (fs/path (norm-dir cwd))]
      (add! (load-context-file-from-dir dir))
      (when-let [parent (fs/parent dir)]
        (when (not= (str parent) (str dir))
          (recur parent))))
    @results))
