(ns kmet.extensions.review.dialogs
  "Dialogs for the review extension. kmet has no host `ctx.ui.select` /
   `ctx.ui.editor` like pi — the api's :ui map only carries :custom (mount
   extension-built components), and kmet's SelectList already has builtin
   fuzzy filtering, so the pi branch/commit pickers simplify to a framed
   SelectList inside an overlay dialog.

   Each dialog returns a result via the ui-custom `close` callback.
   Cancel = `close nil` (or a tab/escape key)."
  (:require [clojure.string :as str]
            [kmet.extension :as ext]
            [kmet.tui.keys :as keys]
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
    [:text {:padding-x 1 :padding-y 0 :fg :dim} hint-element]
    [:dynamic-border {:color-fn (fn [s] (theme/fg th :accent s))}]]))

(defn- run-dialog
  "Mount FACTORY via (ext/ui-custom) and deref the resulting promise,
   defaulting to DEFAULT on nil. FACTORY is a (fn [tui th kb close] ...)
   that returns a component (or a duck-typed map) and calls CLOSE with
   the result. OVERLAY-OPTS are forwarded to ui-custom."
  [api factory default & [overlay-opts]]
  (let [result (atom default)
        close-promise (ext/ui-custom
                       api factory
                       (merge {:overlay true
                               :overlay-options {:anchor :center :width 82}}
                              overlay-opts))]
    (or @result (deref close-promise 60000 default))))

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
   should read \"Remove ...\" instead of \"Add ...\"."
  [api custom-instructions-set?]
  (let [items (conj (vec preset-items)
                    {:value toggle-custom-value
                     :label (if custom-instructions-set?
                              "Remove custom review instructions"
                              "Add custom review instructions")
                     :description (if custom-instructions-set?
                                    "(currently set)"
                                    "(applies to all review modes)")})
        initial (smart-default false false)]
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
                      "Type to filter · enter to confirm · esc to cancel")]
         {:render (fn [w] (protocols/render overlay w))
          :handle-input (fn [data]
                          (cond
                            (keys/matches-key? data "escape")
                            (close nil)
                            :else
                            (protocols/handle-input sl data)))
          :invalidate (fn []
                        (protocols/invalidate sl)
                        (protocols/invalidate overlay))
          :dispose (fn []
                     (protocols/dispose sl)
                     (h/dispose-tree! overlay))}))
     nil)))

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
                    "Type to filter · enter to select · esc to cancel")]
       {:render (fn [w] (protocols/render overlay w))
        :handle-input (fn [data]
                        (cond
                          (keys/matches-key? data "escape")
                          (close nil)
                          :else
                          (protocols/handle-input sl data)))
        :invalidate (fn []
                      (protocols/invalidate sl)
                      (protocols/invalidate overlay))
        :dispose (fn []
                   (protocols/dispose sl)
                   (h/dispose-tree! overlay))}))
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
                    "Type to filter · enter to select · esc to cancel")]
       {:render (fn [w] (protocols/render overlay w))
        :handle-input (fn [data]
                        (cond
                          (keys/matches-key? data "escape")
                          (close nil)
                          :else
                          (protocols/handle-input sl data)))
        :invalidate (fn []
                      (protocols/invalidate sl)
                      (protocols/invalidate overlay))
        :dispose (fn []
                   (protocols/dispose sl)
                   (h/dispose-tree! overlay))}))
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
           _ (input/input-set-on-submit!
              inp (fn [v] (close (str/trim v))))
           _ (input/input-set-on-escape!
              inp (fn [] (close nil)))
           body [:container {}
                 [:text {:padding-x 1 :padding-y 0} "│"]
                 [:spacer {:lines 1}]
                 inp
                 [:spacer {:lines 1}]
                 [:text {:padding-x 1 :padding-y 0 :fg :dim} "Enter to confirm · esc to cancel"]]
           overlay (framed-overlay th title body
                                   "Enter to confirm · esc to cancel")]
       {:render (fn [w] (protocols/render overlay w))
        :handle-input (fn [data]
                        (cond
                          (keys/matches-key? data "escape")
                          (close nil)
                          :else
                          (protocols/handle-input inp data)))
        :invalidate (fn []
                      (protocols/invalidate inp)
                      (protocols/invalidate overlay))
        :dispose (fn []
                   (protocols/dispose inp)
                   (h/dispose-tree! overlay))}))
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
