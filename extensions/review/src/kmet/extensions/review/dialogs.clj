(ns kmet.extensions.review.dialogs
  "Dialogs for the review extension. kmet has no host `ctx.ui.select` /
   `ctx.ui.editor` like pi — the api's :ui map only carries :custom (mount
   extension-built components), and kmet's SelectList already has builtin
   fuzzy filtering, so the pi branch/commit pickers simplify to a framed
   SelectList inside an overlay dialog.

   Each dialog returns a result via the ui-custom `close` callback.
   Cancel = `close nil` (or escape)."
  (:require [clojure.string :as str]
            [kmet.extension :as ext]
            [kmet.tui.macros :refer [defcomponent]]
            [kmet.tui.protocols :as protocols]
            [kmet.tui.theme :as theme]
            [kmet.tui.hiccup :as h]
            [kmet.tui.components.input :as input]
            [kmet.tui.components.select-list :as select-list]))

;; ─── Framed dialog shell ───────────────────────────────────────────────

(defn- framed-overlay
  "Compile a top/bottom-bordered dialog tree: title, body, hint. TH is
   the live theme map. BODY and HINT are kmet.tui.hiccup elements that
   compile-tree will turn into real components."
  [th title body-element hint-element]
  (h/compile-tree
   [:container {}
    [:dynamic-border {:color-fn (fn [s] (theme/fg th :accent s))}]
    [:text {:padding-x 1 :padding-y 0}
     (theme/fg th :accent (theme/bold title))]
    [:spacer {:lines 1}]
    body-element
    [:spacer {:lines 1}]
    [:text {:padding-x 1 :padding-y 0} (theme/fg th :dim hint-element)]
    [:dynamic-border {:color-fn (fn [s] (theme/fg th :accent s))}]]))

(defn- run-dialog
  "Mount FACTORY via (ext/ui-custom) and deref the resulting promise,
   defaulting to DEFAULT on nil or when no UI is available (headless).
   FACTORY is a (fn [tui th kb close] ...) that returns a component and
   calls CLOSE with the result. OVERLAY-OPTS are forwarded to ui-custom."
  [api factory default & [overlay-opts]]
  (if-let [p (ext/ui-custom
              api factory
              (merge {:overlay true
                      :overlay-options {:anchor :center :width 82}}
                     overlay-opts))]
    (try (deref p 60000 default)
         (catch Exception _ default))
    default))

;; ─── Dialog components (IFocusable forwarding) ─────────────────────────

(defcomponent ReviewSelectDialog nil [overlay select-list focused?-atom]
  (render [this width] (protocols/render (:overlay this) width))
  (handle-input [this data] (protocols/handle-input (:select-list this) data))
  (invalidate [this]
    (protocols/invalidate (:overlay this)))
  (dispose [this]
    (h/dispose-tree! (:overlay this))))

(extend-type ReviewSelectDialog
  protocols/IFocusable
  (focused [this] @(:focused?-atom this))
  (set-focused! [this val]
    (reset! (:focused?-atom this) val)
    (protocols/set-focused! (:select-list this) val)))

(defcomponent ReviewInputDialog nil [overlay input-comp focused?-atom]
  (render [this width] (protocols/render (:overlay this) width))
  (handle-input [this data] (protocols/handle-input (:input-comp this) data))
  (invalidate [this]
    (protocols/invalidate (:overlay this)))
  (dispose [this]
    (h/dispose-tree! (:overlay this))))

(extend-type ReviewInputDialog
  protocols/IFocusable
  (focused [this] @(:focused?-atom this))
  (set-focused! [this val]
    (reset! (:focused?-atom this) val)
    (protocols/set-focused! (:input-comp this) val)))

;; ─── Preset selector (pi: showReviewSelector) ──────────────────────────

(def preset-items
  "The four review presets (stable order — pi REVIEW_PRESETS with PR
   dropped). Items carry :value, :label, :description; the toggle row
   is appended dynamically."
  [{:value :uncommitted
    :label "Review uncommitted changes"
    :description ""}
   {:value :base-branch
    :label "Review against a base branch"
    :description "(local)"}
   {:value :commit
    :label "Review a commit"
    :description ""}
   {:value :folder
    :label "Review a folder (or more)"
    :description "(snapshot, not diff)"}])

(def ^:private toggle-custom-value
  "Sentinel value the preset selector uses for the add/remove
   custom-instructions row (pi: TOGGLE_CUSTOM_INSTRUCTIONS_VALUE)."
  :toggle-custom-instructions)

(defn smart-default
  "Index of the preset the picker should preselect (pi: getSmartDefault
   — uncommitted wins over feature branch, which wins over commit when
   the repo is clean)."
  [uncommitted? on-feature-branch?]
  (cond
    uncommitted? 0
    on-feature-branch? 1
    :else 2))

(defn show-preset-selector!
  "Mount the preset selector. Returns the picked value (one of
   `:uncommitted` `:base-branch` `:commit` `:folder`
   `:toggle-custom-instructions`), or nil on cancel/timeout.

   CUSTOM-INSTRUCTIONS-SET? — true when the custom-instructions row
   should read \"Remove ...\" instead of \"Add ...\".
   INITIAL-IDX — optional preselected index (pi smart default)."
  ([api custom-instructions-set?]
   (show-preset-selector! api custom-instructions-set? (smart-default false false)))
  ([api custom-instructions-set? initial-idx]
   (let [items (conj (vec preset-items)
                     {:value toggle-custom-value
                      :label (if custom-instructions-set?
                               "Remove custom review instructions"
                               "Add custom review instructions")
                      :description (if custom-instructions-set?
                                     "(currently set)"
                                     "(applies to all review modes)")})
         initial (or initial-idx (smart-default false false))]
     (run-dialog
      api
      (fn [_tui th _kb close]
        (let [sl (select-list/make-select-list
                  items
                  :height (min 10 (count items))
                  :theme (theme/get-select-list-theme th)
                  :no-match-text "  No matching presets"
                  :on-select (fn [item] (close (:value item)))
                  :on-escape (fn [] (close nil)))
              _ (select-list/select-list-set-selected! sl initial)
              overlay (framed-overlay
                       th "Select a review preset"
                       [:container {} sl]
                       "Press enter to confirm or esc to go back")]
          (map->ReviewSelectDialog
           {:kind nil
            :overlay overlay
            :select-list sl
            :focused?-atom (atom false)})))
      nil))))

;; ─── Branch selector (pi: showBranchSelector) ──────────────────────────

(defn show-branch-selector!
  "Mount a fuzzy branch picker. BRANCHES is a vector of select items
   (each {:value :label :description}). Returns the picked item, or nil
   on cancel/timeout. The kmet SelectList already has builtin fuzzy
   filtering on :label — typing filters the list live (no separate
   search Input needed)."
  [api branches title]
  (run-dialog
   api
   (fn [_tui th _kb close]
     (let [sl (select-list/make-select-list
               branches
               :height (min 10 (count branches))
               :theme (theme/get-select-list-theme th)
               :no-match-text "  No matching branches"
               :on-select (fn [item] (close item))
               :on-escape (fn [] (close nil)))
           overlay (framed-overlay
                    th title
                    [:container {} sl]
                    "Type to filter • enter to select • esc to cancel")]
       (map->ReviewSelectDialog
        {:kind nil
         :overlay overlay
         :select-list sl
         :focused?-atom (atom false)})))
   nil))

;; ─── Commit selector (pi: showCommitSelector) ──────────────────────────

(defn show-commit-selector!
  "Mount a recent-commits picker. COMMITS is a vector of {:value sha
   :label \"<7> <title>\" :description \"\"}. Returns the picked item,
   or nil on cancel/timeout."
  [api commits]
  (run-dialog
   api
   (fn [_tui th _kb close]
     (let [sl (select-list/make-select-list
               commits
               :height (min 10 (count commits))
               :theme (theme/get-select-list-theme th)
               :no-match-text "  No matching commits"
               :on-select (fn [item] (close item))
               :on-escape (fn [] (close nil)))
           overlay (framed-overlay
                    th "Select commit to review"
                    [:container {} sl]
                    "Type to filter • enter to select • esc to cancel")]
       (map->ReviewSelectDialog
        {:kind nil
         :overlay overlay
         :select-list sl
         :focused?-atom (atom false)})))
   nil))

;; ─── Text input dialogs (pi: ctx.ui.editor) ────────────────────────────

(defn show-text-input!
  "Mount a single-line text input dialog. INITIAL is the pre-filled
   value. Returns the trimmed string, or nil on cancel/empty."
  [api title initial _placeholder]
  (run-dialog
   api
   (fn [_tui th _kb close]
     (let [inp (input/make-input)
           _ (input/input-set-value! inp initial)
           _ (input/input-set-cursor! inp (count (or initial "")))
           _ (input/input-set-on-submit!
              inp (fn [v] (close (str/trim v))))
           _ (input/input-set-on-escape!
              inp (fn [] (close nil)))
           overlay (framed-overlay th title inp
                                   "Enter to confirm · esc to cancel")]
       (map->ReviewInputDialog
        {:kind nil
         :overlay overlay
         :input-comp inp
         :focused?-atom (atom false)})))
   nil))

(defn show-folder-input!
  "Show the folder paths input. Returns a vector of trimmed paths, or
   nil on cancel / empty input. Pi uses a multi-line editor; we use a
   single-line input with space-separated paths to keep the kmet.tui
   surface minimal — paths rarely span newlines in practice."
  [api]
  (let [raw (show-text-input! api
                              "Enter folders/files to review (space-separated)"
                              "."
                              "e.g. src docs")]
    (when (and raw (not (str/blank? raw)))
      (let [paths (->> (str/split raw #"\s+")
                       (map str/trim)
                       (remove str/blank?)
                       vec)]
        (when (seq paths) paths)))))

(defn show-custom-instructions-input!
  "Show the custom review instructions editor. Returns the trimmed
   string, or nil on cancel / empty input."
  [api]
  (let [raw (show-text-input! api
                              "Custom review instructions (applies to all reviews)"
                              ""
                              "type then enter")]
    (when (and raw (not (str/blank? raw))) raw)))

(defn show-review-location-selector!
  "Ask where to start the review: empty branch vs current session
   (pi: \"Start review in:\" selector). Returns :empty-branch or
   :current-session, or nil on cancel/timeout."
  [api]
  (let [items [{:value :empty-branch :label "Empty branch" :description ""}
               {:value :current-session :label "Current session" :description ""}]
        height (count items)]
    (run-dialog
     api
     (fn [_tui th _kb close]
       (let [sl (select-list/make-select-list
                 items
                 :height height
                 :theme (theme/get-select-list-theme th)
                 :no-match-text "  No matching options"
                 :on-select (fn [item] (close (:value item)))
                 :on-escape (fn [] (close nil)))
             overlay (framed-overlay
                      th "Start review in:"
                      [:container {} sl]
                      "↑↓ navigate • enter to select • esc to cancel")]
         (map->ReviewSelectDialog
          {:kind nil
           :overlay overlay
           :select-list sl
           :focused?-atom (atom false)})))
     nil)))

(defn show-end-review-selector!
  "Mount the end-review action selector (pi: ctx.ui.select \"Finish
   review:\", [\"Return only\" \"Return and fix findings\"
   \"Return and summarize\"]). Returns one of :return-only
   :return-and-fix :return-and-summarize, or nil on cancel/timeout."
  [api]
  (let [items [{:value :return-only :label "Return only" :description ""}
               {:value :return-and-fix :label "Return and fix findings" :description ""}
               {:value :return-and-summarize :label "Return and summarize" :description ""}]
        height (count items)]
    (run-dialog
     api
     (fn [_tui th _kb close]
       (let [sl (select-list/make-select-list
                 items
                 :height height
                 :theme (theme/get-select-list-theme th)
                 :no-match-text "  No matching options"
                 :on-select (fn [item] (close (:value item)))
                 :on-escape (fn [] (close nil)))
             overlay (framed-overlay
                      th "Finish review:"
                      [:container {} sl]
                      "↑↓ navigate • enter to select • esc to cancel")]
         (map->ReviewSelectDialog
          {:kind nil
           :overlay overlay
           :select-list sl
           :focused?-atom (atom false)})))
     nil)))
