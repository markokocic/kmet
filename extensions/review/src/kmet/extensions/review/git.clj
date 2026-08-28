(ns kmet.extensions.review.git
  "Git helpers for the review extension. Each helper takes the extension
   api (for `exec`) and returns parsed data — no UI concerns here.

   PR / GitHub-CLI support was dropped: pi's flow needs `gh` plus a clean
   work tree to `gh pr checkout`, which is environment-fragile. The same
   review is available with `branch <remote-branch>` once a PR's head is
   fetched; we do not need to be opinionated about it here."
  (:require [clojure.string :as str]
            [kmet.extension :as ext]))

;; ─── Low-level exec helper ──────────────────────────────────────────────

(defn- run
  "Run a command via (ext/exec). Returns {:exit int :out str :err str}.
   The api parameter shadows the protocol fn (ext/exec api) — calling
   it inline avoids having to thread the api through every helper's
   caller."
  [api command args]
  (ext/exec api command args nil))

(defn- trim
  "Trim a command's stdout, returning nil when empty. Pi's helpers all
   use this pattern: `if (code !== 0) return …; return stdout.trim()`."
  [s]
  (when (and s (seq (str/triml s)))
    (str/trim s)))

(defn- exit-ok?
  "True when the run returned exit 0 (or nil for missing commands)."
  [{:keys [exit]}] (and exit (zero? exit)))

;; ─── Merge base (pi: getMergeBase) ───────────────────────────────────────

(defn merge-base
  "The merge base between HEAD and BRANCH. Tries the upstream tracking
   branch first; falls back to the branch name itself. Returns the SHA
   string, or nil when git cannot resolve one (e.g. no common history)."
  [api branch]
  (let [upstream-result (run api "git" ["rev-parse" "--abbrev-ref"
                                        (str branch "@{upstream}")])]
    (when (and (exit-ok? upstream-result)
               (trim (:out upstream-result)))
      (let [upstream (trim (:out upstream-result))
            mb (run api "git" ["merge-base" "HEAD" upstream])]
        (when (exit-ok? mb)
          (trim (:out mb)))))))

(defn merge-base-fallback
  "Same as `merge-base` but tries BRANCH directly when the upstream
   lookup is not available (used by the fallback prompt when
   `merge-base` returns nil)."
  [api branch]
  (let [r (run api "git" ["merge-base" "HEAD" branch])]
    (when (exit-ok? r)
      (trim (:out r)))))

;; ─── Branches (pi: getLocalBranches) ────────────────────────────────────

(defn local-branches
  "Local branch short-names (e.g. \"main\"), no \"* \" marker, no
   `remotes/` entries. Empty vector on non-zero exit."
  [api]
  (let [r (run api "git" ["branch" "--format=%(refname:short)"])]
    (if-not (exit-ok? r)
      []
      (->> (str/split-lines (:out r))
           (keep trim)
           vec))))

;; ─── Recent commits (pi: getRecentCommits) ──────────────────────────────

(defn recent-commits
  "Up to LIMIT recent commits as a vector of {:sha str :title str} maps
   (NEWEST FIRST, pi order). Oneline format: `<sha> <title>`. Returns
   [] on non-zero exit."
  [api limit]
  (let [r (run api "git" ["log" "--oneline" "-n" (str limit)])]
    (if-not (exit-ok? r)
      []
      (->> (str/split-lines (:out r))
           (keep (fn [line]
                   (let [line (str/trim line)]
                     (when (seq line)
                       (let [[sha & rest] (str/split line #" " 2)]
                         (when (and sha (seq sha))
                           {:sha sha
                            :title (or (first rest) "")}))))))
           vec))))

;; ─── Repo state (pi: hasUncommittedChanges / hasPendingChanges) ─────────

(defn- status-tracked-changes?
  "True when `git status --porcelain` shows any line that does not start
   with `??` (untracked). Tracked changes block branch switching;
   untracked files do not (pi: hasPendingChanges)."
  [api]
  (let [r (run api "git" ["status" "--porcelain"])]
    (when (exit-ok? r)
      (boolean (some (fn [line]
                       (and (seq (str/triml line))
                            (not (str/starts-with? (str/triml line) "??"))))
                     (str/split-lines (:out r)))))))

(defn uncommitted?
  "True when there are any porcelain entries (staged, unstaged, or
   untracked) — used for the smart-default heuristic
   (pi: hasUncommittedChanges)."
  [api]
  (boolean
   (let [r (run api "git" ["status" "--porcelain"])]
     (and (exit-ok? r) (seq (str/trim (:out r)))))))

(defn pending-changes?
  "True when tracked files have staged or unstaged changes (would
   prevent `git checkout` from switching branches). Untracked files
   are ignored (pi: hasPendingChanges)."
  [api]
  (boolean (status-tracked-changes? api)))

;; ─── Current branch / default branch (pi: getCurrentBranch /
;;    getDefaultBranch) ─────────────────────────────────────────────────

(defn current-branch
  "The current branch short-name, or nil on detached HEAD / non-zero
   exit."
  [api]
  (let [r (run api "git" ["branch" "--show-current"])]
    (when (exit-ok? r)
      (trim (:out r)))))

(defn default-branch
  "The default branch: origin/HEAD's short-name when set, else
   \"main\" / \"master\" / \"main\" (fallback). Used by the smart default
   and the branch picker sort."
  [api]
  (or
   (let [r (run api "git" ["symbolic-ref" "refs/remotes/origin/HEAD" "--short"])]
     (when (exit-ok? r)
       (let [v (trim (:out r))]
         (when v
           (str/replace v #"^origin/" "")))))
   (let [branches (local-branches api)]
     (cond
       (some #(= "main" %) branches) "main"
       (some #(= "master" %) branches) "master"
       :else "main"))))

;; ─── In-repo sanity (pi: rev-parse --git-dir) ──────────────────────────

(defn in-git-repo?
  "True when CWD is inside a git work tree. Used to gate the /review
   command on git presence (pi: handler check)."
  [api]
  (boolean (exit-ok? (run api "git" ["rev-parse" "--git-dir"]))))
