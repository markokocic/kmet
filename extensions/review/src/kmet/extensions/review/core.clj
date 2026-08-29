(ns kmet.extensions.review.core
  "Code review extension - port of pi-review to kmet with a
   language-agnostic rubric. Provides /review and /end-review slash
   commands, fresh-session branching with a code-review label, a
   Review active widget, project-level REVIEW_GUIDELINES.md loading,
   and shared custom review instructions persisted as session entries.

   PR review mode was dropped: pi-review needs the gh CLI plus a
   clean work tree to `gh pr checkout`, both environment-fragile. The
   same review is reachable via `branch <remote-branch>` once a PR's
   head is fetched.

   Module-level state - the active review's origin leaf - is process-
   local. A single review is active at a time (the UI and /end-review
   assume so); the origin is re-derived from the session on
   :session-start and :session-tree via the review-session entry."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [kmet.extension :as ext]
            [kmet.extensions.review.dialogs :as dlg]
            [kmet.extensions.review.git :as git]
            [kmet.extensions.review.prompts :as prompts]
            [kmet.tui.hiccup :as h]
            [kmet.tui.theme :as theme]))

;; -- Constants ------------------------------------------------------------

(def ^:private review-state-type "review-session")
(def ^:private review-anchor-type "review-anchor")
(def ^:private review-settings-type "review-settings")

(def ^:private pending-changes-message
  "Cannot switch branches with uncommitted changes. Please commit or stash them first.")

;; -- Module-local state ---------------------------------------------------

(defonce ^:private review-origin-id (atom nil))
(defonce ^:private end-review-in-progress (atom false))
(defonce ^:private review-custom-instructions (atom nil))

(defn- active-review? [] (some? @review-origin-id))

;; -- Project guidelines (pi: loadProjectReviewGuidelines) ----------------

(defn- project-review-guidelines
  "Read REVIEW_GUIDELINES.md beside the .kmet (or .pi) project marker.
   Walks up from CWD until a marker dir is found. The .pi fallback
   covers projects that still use the upstream pi monorepo convention
   (kmet already swaps its marker to .kmet)."
  [cwd]
  (let [current (atom (str cwd))]
    (loop []
      (let [dir @current
            kmet-dir (str dir "/.kmet")
            pi-dir (str dir "/.pi")
            guidelines (str dir "/REVIEW_GUIDELINES.md")
            found? (or (and (fs/exists? kmet-dir) (fs/directory? kmet-dir))
                       (and (fs/exists? pi-dir) (fs/directory? pi-dir)))]
        (cond
          found?
          (let [content (when (fs/exists? guidelines)
                          (try (slurp guidelines) (catch Exception _ nil)))]
            (when (and content (seq (str/trim content)))
              (str/trim content)))

          :else
          (let [parent (fs/parent dir)]
            (if (= parent dir)
              nil
              (do (reset! current (str parent)) (recur)))))))))

;; -- Session state read/write --------------------------------------------

(defn- read-review-state
  "Most recent review-session custom entry on the active branch, or nil."
  [api]
  (let [entries ((:get-branch (ext/session api)))]
    (some (fn [entry]
            (when (and (= "custom" (:type entry))
                       (= review-state-type (:custom-type entry)))
              (:data entry)))
          (reverse entries))))

(defn- read-review-settings
  "Most recent review-settings custom entry, or
   {:custom-instructions nil}."
  [api]
  (let [entries ((:get-entries (ext/session api)) review-settings-type)]
    (if-let [entry (last (sort-by :created-at entries))]
      (let [data (:data entry)]
        {:custom-instructions (some-> data :custom-instructions str/trim not-empty)})
      {:custom-instructions nil})))

;; -- Widget (pi: setReviewWidget) ----------------------------------------

(defn- set-review-widget! [api active?]
  (ext/ui-set-widget api "review" nil)
  (when active?
    (ext/ui-set-widget
     api "review"
     (fn [_tui th]
       (h/compile-tree
        [:container {}
         [:text {:padding-x 1 :padding-y 0}
          (theme/fg th :warning "Review session active, return with /end-review")]]))
     {:placement :above-editor})))

;; -- Settings persistence ------------------------------------------------

(defn- persist-review-settings! [api]
  ((:append-entry! (ext/session api))
   review-settings-type
   {:custom-instructions @review-custom-instructions}))

(defn- set-review-custom-instructions! [api instructions]
  (reset! review-custom-instructions
          (when (and instructions (seq (str/trim instructions)))
            (str/trim instructions)))
  (persist-review-settings! api))

;; -- State restore on session start / tree ------------------------------

(defn- apply-review-settings! [api]
  (let [{:keys [custom-instructions]} (read-review-settings api)]
    (reset! review-custom-instructions custom-instructions)))

(defn- apply-review-state!
  "Restore the active review from the session branch and refresh the
   widget. Called on :session-start and :session-tree."
  [api]
  (let [state (read-review-state api)]
    (if (and (:active state) (:origin-id state))
      (do (reset! review-origin-id (:origin-id state))
          (set-review-widget! api true))
      (do (reset! review-origin-id nil)
          (set-review-widget! api false)))))

;; -- Helpers: arg parsing (pi: tokenizeArgs / parseArgs) ----------------

(defn- tokenize-args
  "Sh+quoted argument tokenizer (pi: tokenizeArgs). Supports single
   and double quotes with backslash escapes. Returns a vector of
   token strings."
  [value]
  (loop [tokens []
         current ""
         quote nil
         i 0]
    (if (>= i (count value))
      (if (seq current)
        (conj tokens current)
        tokens)
      (let [char (.charAt value i)]
        (cond
          quote
          (if (= char \\)
            (if (>= (inc i) (count value))
              (recur tokens current quote (inc i))
              (recur tokens (str current (.charAt value (inc i))) quote (+ i 2)))
            (if (= char quote)
              (recur tokens current nil (inc i))
              (recur tokens (str current char) quote (inc i))))

          (or (= char \") (= char \'))
          (recur tokens current char (inc i))

          (Character/isWhitespace char)
          (recur (if (seq current) (conj tokens current) tokens)
                 ""
                 nil
                 (inc i))

          :else
          (recur tokens (str current char) nil (inc i)))))))

(defn- parse-paths [value]
  (->> (str/split (or value "") #"\s+")
       (map str/trim)
       (remove str/blank?)
       vec))

(def ^:private target-types
  "Valid /review subcommand values (pi: switch cases). Note: pi
   dispatches on the subcommand string directly (\"branch\" /\"
   commit\"), not on a normalized type name."
  #{:uncommitted :branch :commit :folder})

(defn- parse-args
  "Parse the /review argument string. Returns
   {:target ... :extra-instruction ... :error ...} where TARGET is one
   of {:type :uncommitted}, {:type :base-branch :branch str},
   {:type :commit :sha str :title str-or-nil}, or
   {:type :folder :paths [str ...]}. --extra extracts the per-call
   extra instruction."
  [args]
  (if (str/blank? args)
    {:target nil}
    (let [;; pre-extract --extra=<rest-of-string> as a single value
          ;; before tokenizing (pi: --key=val captures the rest as
          ;; one token, even when it contains spaces)
          [args pre-extra] (let [trimmed (str/trim args)
                                 m (re-matches #"(?s)(.*?)--extra=(.*)" trimmed)]
                             (if m
                               [(str (str/trim (second m)) " " (str/trim (nth m 2)))
                                (str/trim (nth m 2))]
                               [trimmed nil]))
          raw (tokenize-args args)
          parts+extra (reduce
                       (fn [acc part]
                         (cond
                           (and (str/starts-with? part "--extra=")
                                (not (:in-extra acc)))
                           (assoc acc :in-extra true
                                  :extra-instruction (subs part 8))

                           (and (= part "--extra")
                                (not (:in-extra acc)))
                           (assoc acc :in-extra true)

                           (:in-extra acc)
                           (assoc acc :in-extra false
                                  :extra-instruction part)

                           :else
                           (update acc :parts conj part)))
                       {:parts [] :extra-instruction nil :in-extra false}
                       raw)
          _ (when (:in-extra parts+extra)
              (throw (ex-info "Missing value for --extra" {:args args})))
          parts (:parts parts+extra)
          extra (:extra-instruction parts+extra)
          sub (when (seq parts) (keyword (str/lower-case (first parts))))]
      (if (and sub (not (contains? target-types sub)))
        (if (or extra pre-extra)
          {:target nil :extra-instruction (or extra pre-extra)}
          {:target nil})
        (case sub
          nil {:target nil :extra-instruction (or extra pre-extra)}
          :uncommitted {:target {:type :uncommitted}
                        :extra-instruction (or extra pre-extra)}
          :branch (let [branch (second parts)]
                    (if branch
                      {:target {:type :base-branch :branch branch}
                       :extra-instruction (or extra pre-extra)}
                      {:target nil :extra-instruction (or extra pre-extra)}))
          :commit (let [sha (second parts)]
                    (if sha
                      (let [rest-parts (drop 2 parts)
                            title (when (seq rest-parts)
                                    (let [s (str/join " " rest-parts)]
                                      (when (seq (str/trim s)) s)))]
                        {:target {:type :commit :sha sha :title title}
                         :extra-instruction (or extra pre-extra)})
                      {:target nil :extra-instruction (or extra pre-extra)}))
          :folder (let [paths (parse-paths (str/join " " (rest parts)))]
                    (if (seq paths)
                      {:target {:type :folder :paths paths}
                       :extra-instruction (or extra pre-extra)}
                      {:target nil :extra-instruction (or extra pre-extra)})))))))

;; -- Merge-base resolution -----------------------------------------------

(defn- resolve-merge-base [api branch]
  (or (git/merge-base api branch)
      (git/merge-base-fallback api branch)))

;; -- Preset state --------------------------------------------------------

(defn- preset-state [api]
  (let [uncommitted? (git/uncommitted? api)
        current (git/current-branch api)
        default (git/default-branch api)
        on-feature? (and current (not= current default))
        smart (dlg/smart-default uncommitted? on-feature?)
        custom-set? (some? @review-custom-instructions)]
    {:smart-default smart
     :custom-set? custom-set?}))

;; -- Picking a target via the dialogs ------------------------------------

(defn- show-target-dialog!
  "Walk the user through the preset selector and any sub-dialogs to
   arrive at a target map. Returns nil on cancel."
  [api]
  (loop []
    (let [{:keys [custom-set?]} (preset-state api)
          picked (dlg/show-preset-selector! api custom-set?)]
      (cond
        (nil? picked) nil

        (= :toggle-custom-instructions picked)
        (do (if custom-set?
              (do (set-review-custom-instructions! api nil)
                  (ext/ui-notify api "Custom review instructions removed" :info))
              (when-let [text (dlg/show-custom-instructions-input! api)]
                (set-review-custom-instructions! api text)
                (ext/ui-notify api "Custom review instructions saved" :info)))
            (recur))

        (= :uncommitted picked)
        {:type :uncommitted}

        (= :base-branch picked)
        (let [branches (git/local-branches api)
              current (git/current-branch api)
              default (git/default-branch api)
              candidates (if current
                           (filterv #(not= % current) branches)
                           (vec branches))]
          (if (empty? candidates)
            (do (ext/ui-notify api
                               (str "No other branches found"
                                    (when current (str " (current branch: " current ")")))
                               :error)
                nil)
            (let [sorted (vec (sort-by (fn [b]
                                         [(not= b default) (str/lower-case b)])
                                       candidates))
                  items (mapv (fn [b]
                                {:value b
                                 :label b
                                 :description (when (= b default) "(default)")})
                              sorted)
                  picked-item (dlg/show-branch-selector! api items
                                                         "Select base branch")]
              (when picked-item
                {:type :base-branch :branch (:value picked-item)}))))

        (= :commit picked)
        (let [commits (git/recent-commits api 20)]
          (if (empty? commits)
            (do (ext/ui-notify api "No commits found" :error) nil)
            (let [items (mapv (fn [c]
                                {:value (:sha c)
                                 :label (str (subs (:sha c) 0 (min 7 (count (:sha c))))
                                             " " (:title c))
                                 :description ""})
                              commits)
                  picked-item (dlg/show-commit-selector! api items)]
              (when picked-item
                {:type :commit
                 :sha (:value picked-item)
                 :title (:title picked-item)}))))

        (= :folder picked)
        (when-let [paths (dlg/show-folder-input! api)]
          {:type :folder :paths paths})

        :else nil))))

;; -- Build the full review prompt ---------------------------------------

(defn- assemble-review-prompt [_api cwd target extra-instruction]
  (let [body (prompts/build-review-prompt target)
        project (project-review-guidelines cwd)]
    (str prompts/review-rubric
         "\n\n---\n\nPlease perform a code review with the following focus:\n\n"
         body
         (when-let [custom @review-custom-instructions]
           (str "\n\nShared custom review instructions (applies to all reviews):\n\n"
                custom))
         (when (and extra-instruction (seq (str/trim extra-instruction)))
           (str "\n\nAdditional user-provided review instruction:\n\n"
                (str/trim extra-instruction)))
         (when project
           (str "\n\nThis project has additional instructions for code reviews:\n\n"
                project)))))

;; -- Execute the review (pi: executeReview) ------------------------------

(defn- execute-review
  [api ctx target use-fresh-session extra-instruction]
  (cond
    (active-review?)
    (do (ext/ui-notify api "Already in a review. Use /end-review to finish first."
                       :warning)
        false)

    (git/pending-changes? api)
    (do (ext/ui-notify api pending-changes-message :error) false)

    :else
    (let [merge-base (when (= :base-branch (:type target))
                       (resolve-merge-base api (:branch target)))
          target (assoc target :merge-base merge-base)]
      (when use-fresh-session
        (let [leaf ((:get-leaf-id (ext/session api)))]
          (if leaf
            (reset! review-origin-id leaf)
            (do
              ((:append-entry! (ext/session api))
               review-anchor-type
               {:created-at (str (java.util.Date.))})
              (let [leaf2 ((:get-leaf-id (ext/session api)))]
                (when leaf2 (reset! review-origin-id leaf2)))))))
      (let [origin @review-origin-id]
        (when (or (not use-fresh-session) origin)
          (when (and use-fresh-session origin)
            (let [entries ((:get-branch (ext/session api)))
                  first-user (some (fn [e]
                                     (when (and (= :message (:type e))
                                                (= "user" (:role (:message e))))
                                       e))
                                   entries)]
              (when first-user
                (try
                  (let [result (ctx :navigate-tree (:id first-user)
                                    {:summarize false :label "code-review"})]
                    (when (:cancelled result)
                      (reset! review-origin-id nil)
                      (throw (ex-info "navigate-tree cancelled" {}))))
                  (catch Exception _
                    (reset! review-origin-id nil)
                    (throw (ex-info "navigate-tree failed" {}))))
                (ctx :set-editor-text "")))
            (set-review-widget! api true)
            ((:append-entry! (ext/session api))
             review-state-type
             {:active true :origin-id origin}))
          (let [prompt (assemble-review-prompt api (or (:cwd ctx) (System/getProperty "user.dir")) target extra-instruction)
                hint (prompts/user-facing-hint target)
                mode-hint (when use-fresh-session " (fresh session)")]
            (ext/ui-notify api (str "Starting review: " hint mode-hint) :info)
            (ext/send-user-message api prompt)
            true))))))

;; -- /review command -----------------------------------------------------

(defn- handle-review-command [api ctx args]
  (cond
    (not= :interactive (:mode ctx))
    (ext/ui-notify api "Review requires interactive mode" :error)

    (active-review?)
    (ext/ui-notify api "Already in a review. Use /end-review to finish first."
                   :warning)

    (not (git/in-git-repo? api))
    (ext/ui-notify api "Not a git repository" :error)

    :else
    (let [{:keys [target extra-instruction error]}
          (try (parse-args args)
               (catch Exception e
                 {:error (ex-message e)}))]
      (if error
        (ext/ui-notify api error :error)
        (when-let [target (or target
                              (show-target-dialog! api))]
          (let [entries ((:get-branch (ext/session api)))
                message-count (count (filter #(= :message (:type %)) entries))
                use-fresh? (zero? message-count)]
            (execute-review api ctx target use-fresh? extra-instruction)))))))

;; -- End review ----------------------------------------------------------

(defn- get-active-origin [api _ctx]
  (or @review-origin-id
      (let [state (read-review-state api)]
        (cond
          (and (:active state) (:origin-id state))
          (do (reset! review-origin-id (:origin-id state))
              (:origin-id state))

          (:active state)
          (do (set-review-widget! api false)
              ((:append-entry! (ext/session api))
               review-state-type {:active false})
              (ext/ui-notify api
                             "Review state was missing origin info; cleared review status."
                             :warning)
              nil)

          :else nil))))

(defn- clear-review-state! [api]
  (set-review-widget! api false)
  (reset! review-origin-id nil)
  ((:append-entry! (ext/session api))
   review-state-type {:active false}))

(defn- navigate-back [_api ctx origin summarize?]
  (try
    (let [result (ctx :navigate-tree origin
                      (cond-> {:label "code-review"}
                        true (assoc :summarize (boolean summarize?))
                        summarize? (assoc :custom-instructions
                                          prompts/review-summary-prompt
                                          :replace-instructions true)))]
      (cond
        (:cancelled result) {:ok? false :cancelled? true}
        :else {:ok? true}))
    (catch Exception e
      {:ok? false
       :error (or (ex-message e) (str e))})))

(defn- end-review! [api ctx action]
  (let [origin (get-active-origin api ctx)]
    (cond
      (nil? origin)
      (do (when-not (read-review-state api)
            (ext/ui-notify api
                           (str "Not in a review branch (use /review first, or "
                                "review was started in current session mode)")
                           :info))
          :error)

      (= :return-only action)
      (let [r (navigate-back api ctx origin false)]
        (cond
          (:cancelled? r)
          (do (ext/ui-notify api "Navigation cancelled. Use /end-review to try again." :info)
              :cancelled)
          (:error r)
          (do (ext/ui-notify api (str "Failed to return: " (:error r)) :error) :error)
          :else
          (do (clear-review-state! api)
              (ext/ui-notify api "Review complete! Returned to original position." :info)
              :ok)))

      :else
      (let [r (navigate-back api ctx origin true)]
        (cond
          (:cancelled? r)
          (do (ext/ui-notify api "Summarization cancelled. Use /end-review to try again." :info)
              :cancelled)
          (:error r)
          (do (ext/ui-notify api (str "Summarization failed: " (:error r)) :error) :error)
          :else
          (do (clear-review-state! api)
              (case action
                :return-and-summarize
                (do (ext/ui-set-editor-text api "Act on the review findings")
                    (ext/ui-notify api "Review complete! Returned and summarized." :info)
                    :ok)

                :return-and-fix
                (do (ext/send-user-message api prompts/review-fix-findings-prompt
                                           {:deliver-as :follow-up})
                    (ext/ui-notify api
                                   "Review complete! Returned and queued a follow-up to fix findings."
                                   :info)
                    :ok))))))))

(defn- handle-end-review-command [api ctx]
  (if @end-review-in-progress
    (ext/ui-notify api "/end-review is already running" :info)
    (do (reset! end-review-in-progress true)
        (try
          (let [choice (dlg/show-text-input!
                        api
                        "Finish review (return only | return and fix | return and summarize)"
                        "return only"
                        "type the action")]
            (when choice
              (let [c (str/lower-case (str/trim choice))]
                (cond
                  (or (str/includes? c "summariz") (str/includes? c "summary"))
                  (end-review! api ctx :return-and-summarize)

                  (or (str/includes? c "fix") (str/includes? c "find"))
                  (end-review! api ctx :return-and-fix)

                  :else
                  (end-review! api ctx :return-only)))))
          (finally
            (reset! end-review-in-progress false))))))

;; -- Init / shutdown -----------------------------------------------------

(defn init [api]
  (ext/on-event api :session-start
                (fn [_ev _ctx]
                  (apply-review-settings! api)
                  (apply-review-state! api)))
  (ext/on-event api :session-tree
                (fn [_ev _ctx]
                  (apply-review-settings! api)
                  (apply-review-state! api)))
  (ext/register-command!
   api
   {:name "review"
    :description "Review code changes (uncommitted, base branch, commit, or folder)"
    :handler (fn [ctx args]
               (handle-review-command api ctx args))})
  (ext/register-command!
   api
   {:name "end-review"
    :description "Complete review and return to the original position"
    :handler (fn [ctx _args]
               (handle-end-review-command api ctx))}))

(defn shutdown [_api] nil)

