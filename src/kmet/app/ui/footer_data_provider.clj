(ns kmet.app.ui.footer-data-provider
  "FooterDataProvider — data source for FooterComponent (pi:
   core/footer-data-provider.ts). Pure data, no TUI deps: cwd, git branch
   (cached, resolved lazily), session (usage totals + context estimates),
   configured provider count, and the model context window."
  (:require [clojure.string :as str]
            [babashka.process :as proc]
            [kmet.app.session :as session]
            [kmet.app.compaction :as compaction]))

(defrecord FooterDataProvider [cwd-atom git-branch-atom git-branch-resolved?-atom
                               session-atom provider-count-atom context-window-atom
                               model-atom provider-atom thinking-atom])

(defn- resolve-git-branch
  "Resolve the current git branch via `git branch --show-current`; nil
   outside a git repository."
  []
  (try
    (let [r (proc/shell {:out :string :err :string}
                        "git" "branch" "--show-current")]
      (when (and r (str/blank? (:err r)))
        (let [b (str/trim (:out r))]
          (when (seq b) b))))
    (catch Exception _ nil)))

(defn make-footer-data-provider
  "Create a FooterDataProvider.
   Options:
     :cwd            — process cwd (default (System/getProperty \"user.dir\"))
     :session        — kmet.app.session Session record or nil
     :provider-count — number of configured providers (pi: availableProviderCount)
     :context-window — model context window in tokens, or nil when unknown"
  [& {:keys [cwd session provider-count context-window model provider thinking]
      :or {cwd (System/getProperty "user.dir")
           provider-count 1}}]
  (map->FooterDataProvider
   {:cwd-atom (atom cwd)
    :git-branch-atom (atom nil)
    :git-branch-resolved?-atom (atom false)
    :session-atom (atom session)
    :provider-count-atom (atom provider-count)
    :context-window-atom (atom context-window)
    :model-atom (atom model)
    :provider-atom (atom provider)
    :thinking-atom (atom thinking)}))

;; ─── Accessors ─────────────────────────────────────────────────────────────

(defn fdp-get-cwd [provider] @(:cwd-atom provider))

(defn fdp-set-cwd! [provider cwd]
  (reset! (:cwd-atom provider) cwd)
  ;; Branch depends on cwd — invalidate the lazy resolution
  (reset! (:git-branch-atom provider) nil)
  (reset! (:git-branch-resolved?-atom provider) false)
  nil)

(defn fdp-get-git-branch
  "Resolve the git branch once, then return the cached value (pi:
   FooterDataProvider.getGitBranch)."
  [provider]
  (when-not @(:git-branch-resolved?-atom provider)
    (reset! (:git-branch-resolved?-atom provider) true)
    (reset! (:git-branch-atom provider) (resolve-git-branch)))
  @(:git-branch-atom provider))

(defn fdp-get-session [provider] @(:session-atom provider))

(defn fdp-set-session! [provider session]
  (reset! (:session-atom provider) session)
  nil)

(defn fdp-get-session-name
  "Session display name from the latest session_info entry (pi:
   getSessionName), or nil when the session has no name."
  [provider]
  (when-let [sess (fdp-get-session provider)]
    (session/get-session-name sess)))

(defn fdp-get-provider-count [provider] @(:provider-count-atom provider))

(defn fdp-get-context-window [provider] @(:context-window-atom provider))

(defn fdp-get-model [provider] @(:model-atom provider))

(defn fdp-get-provider [provider] @(:provider-atom provider))

(defn fdp-get-thinking [provider] @(:thinking-atom provider))

(defn fdp-set-model! [provider model]
  (reset! (:model-atom provider) model)
  nil)

(defn fdp-set-provider! [provider provider-id]
  (reset! (:provider-atom provider) provider-id)
  nil)

(defn fdp-set-thinking! [provider level]
  (reset! (:thinking-atom provider) level)
  nil)

(defn fdp-set-context-window! [provider window]
  (reset! (:context-window-atom provider) window)
  nil)

(defn fdp-usage-totals
  "Cumulative usage (incl. USD :cost) across all session entries (pi:
   FooterComponent accumulates usage from ALL session entries, not just
   post-compaction)."
  [provider]
  (if-let [sess (fdp-get-session provider)]
    (session/usage-totals sess)
    {:input 0 :output 0 :cache-read 0 :cache-write 0 :cost 0.0}))

(defn fdp-latest-cache-hit-rate
  "Cache hit rate of the most recent assistant message with usage (pi:
   latestCacheHitRate — cacheRead / promptTokens of the latest message), or
   nil when no message reports usage."
  [provider]
  (if-let [sess (fdp-get-session provider)]
    (some (fn [e]
            (when-let [u (session/entry-usage (:usage e))]
              (let [total (+ (:input u) (:cache-read u) (:cache-write u))]
                (when (pos? total)
                  (double (/ (* 100.0 (:cache-read u)) total))))))
          (reverse (session/get-branch sess)))
    nil))

(defn fdp-context-tokens
  "Estimated tokens of the active session branch (pi: getContextUsage →
   session context estimate)."
  [provider]
  (if-let [sess (fdp-get-session provider)]
    (reduce + 0 (map compaction/estimate-tokens (session/get-branch sess)))
    0))
