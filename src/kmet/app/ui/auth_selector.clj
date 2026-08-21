(ns kmet.app.ui.auth-selector
  "Auth provider selector (pi: modes/interactive/components/oauth-selector.ts
   OAuthSelectorComponent): the /login and /logout provider picker — a
   visible search filter over \"name id auth-type method-name\", clamped
   (non-wrapping) arrow navigation, per-provider auth status indicators
   (\"✓ configured\" / \"✓ env: VAR\" / \"• unconfigured\") and muted
   [subscription]/[API key] type labels when both types are listed."
  (:require [clojure.string :as str]
            [kmet.app.keybindings :as app-kb]
            [kmet.tui.components.container :as container]
            [kmet.tui.components.dynamic-border :as db]
            [kmet.tui.components.input :as input]
            [kmet.tui.components.spacer :as spacer]
            [kmet.tui.components.text :as text]
            [kmet.tui.components.truncated-text :as truncated-text]
            [kmet.tui.fuzzy :as fuzzy]
            [kmet.tui.keybindings :as kb]
            [kmet.tui.macros :refer [defcomponent]]
            [kmet.tui.protocols :as protocols]
            [kmet.tui.theme :as theme]))

(def ^:private max-visible 8)

(defn- format-auth-type
  "pi formatAuthSelectorProviderType — the human label for an entry's auth
   type."
  [auth-type]
  (if (= :oauth auth-type) "subscription" "API key"))

(defn- search-text
  "pi fuzzyFilter getText — `${name} ${id} ${authType} ${method?.name}`."
  [entry]
  (str (:name entry) " " (:id entry) " "
       (name (:auth-type entry)) " " (or (:method-name entry) "")))

(defn- filtered-entries
  "Entries matching the search string (pi filterProviders — no query means
   all entries in their given order)."
  [entries query]
  (if (str/blank? query)
    (vec entries)
    (fuzzy/fuzzy-filter entries query search-text)))

(defn- status-indicator
  "pi formatStatusIndicator — the trailing auth-status segment of a row:
   unconfigured (muted), a type mismatch warning, or ✓ configured / ✓ env:
   VARS / ✓ <source> (success)."
  [th {:keys [status auth-type]}]
  (cond
    (not status)
    (theme/fg th :muted " • unconfigured")

    (not= (:type status) auth-type)
    (str (theme/fg th :muted " • ")
         (theme/fg th :warning
                   (if (= :oauth (:type status))
                     "subscription configured" "API key configured")))

    (or (str/blank? (:source status))
        (#{"OAuth" "stored credential"} (:source status)))
    (theme/fg th :success " ✓ configured")

    (re-matches #"[A-Z][A-Z0-9_]*(?:, [A-Z][A-Z0-9_]*)*" (:source status))
    (theme/fg th :success (str " ✓ env: " (:source status)))

    :else
    (theme/fg th :success (str " ✓ " (:source status)))))

(declare refresh-list!)

(defcomponent AuthSelector nil
              [container search-input list-container state-atom mode
               on-select-atom on-cancel-atom focused? cache-atom]

  (render [this width] (protocols/render (:container this) width))

  (handle-input [this data]
    (let [kmgr (kb/get-global-keybindings)
          st @state-atom
          n (count (:filtered st))]
      (cond
        ;; Up/Down — clamp at the ends, no wrap (pi OAuthSelectorComponent)
        (or (kb/matches-key kmgr data "tui.select.up")
            (kb/matches-key kmgr data "tui.select.down"))
        (do (when (pos? n)
              (let [idx (:selected-idx st)
                    next (if (kb/matches-key kmgr data "tui.select.up")
                           (max 0 (dec idx))
                           (min (dec n) (inc idx)))]
                (when-not (= idx next)
                  (swap! state-atom assoc :selected-idx next)
                  (refresh-list! this))))
            nil)

        ;; Enter — select the highlighted provider (pi tui.select.confirm;
        ;; the search input's onSubmit does the same)
        (kb/matches-key kmgr data "tui.select.confirm")
        (do (when-let [entry (when (pos? n)
                               (nth (:filtered st) (min (:selected-idx st) (dec n))))]
              (when-let [cb @on-select-atom]
                (cb (:id entry) (:auth-type entry))))
            nil)

        ;; Escape / Ctrl+C — cancel (pi tui.select.cancel)
        (kb/matches-key kmgr data "tui.select.cancel")
        (do (when-let [cb @on-cancel-atom] (cb)) nil)

        ;; Everything else — the search input (the visible filter, pi)
        :else
        (do (protocols/handle-input search-input data)
            (let [value (input/input-get-value search-input)]
              (when (not= value (:search st))
                ;; pi filterProviders: clamp the selection to the new
                ;; filtered count — it does not reset to the top
                (swap! state-atom assoc :search value)
                (refresh-list! this)))
            nil)))))

;; ─── List rendering (pi updateList) ────────────────────────────────────────

(defn- refresh-list!
  "Rebuild the rows from the current state (pi updateList): a viewport of
   MAX-VISIBLE rows centered on the selection, muted [subscription]/[API key]
   labels when both types are present, the status indicator, the scroll
   position, and the empty-state message."
  [this]
  (let [{:keys [entries search selected-idx]} @(:state-atom this)
        mode (:mode this)
        th (theme/get-current-theme)
        filtered (filtered-entries entries search)
        n (count filtered)
        selected (max 0 (min selected-idx (max 0 (dec n))))
        _ (swap! (:state-atom this) assoc :filtered filtered :selected-idx selected)
        show-types? (< 1 (count (distinct (map :auth-type filtered))))
        start-idx (max 0 (min (- selected (quot max-visible 2))
                              (- n max-visible)))
        end-idx (min (+ start-idx max-visible) n)
        rows (container/make-container)]
    (doseq [i (range start-idx end-idx)]
      (let [entry (nth filtered i)
            is-selected (= i selected)
            name-text (if is-selected
                        (str (theme/fg th :accent "→ ")
                             (theme/fg th :accent (:name entry)))
                        (str "  " (theme/fg th :text (:name entry))))
            type-label (when show-types?
                         (theme/fg th :muted
                                   (str " [" (format-auth-type (:auth-type entry)) "]")))
            status (status-indicator th entry)]
        (container/container-add-child
         rows (truncated-text/make-truncated-text
               (str name-text type-label status)
               :padding-x 1))))
    (when (or (pos? start-idx) (< end-idx n))
      (container/container-add-child
       rows (truncated-text/make-truncated-text
             (theme/fg th :muted (str "  (" (inc selected) "/" n ")"))
             :padding-x 1)))
    (when (zero? n)
      (container/container-add-child
       rows (truncated-text/make-truncated-text
             (theme/fg th :muted
                       (str "  " (if (empty? entries)
                                   (if (= :login mode)
                                     "No providers available"
                                     "No providers logged in. Use /login first.")
                                   "No matching providers")))
             :padding-x 1)))
    (container/container-set-children! (:list-container this) @(:children rows))))

(defn make-auth-selector
  "Create the auth provider selector (pi OAuthSelectorComponent).
   MODE — :login or :logout (title + empty-state wording); ENTRIES —
   {:id :name :auth-type :method-name? :status?} maps; ON-SELECT receives
   [provider-id auth-type]; ON-CANCEL fires on escape. SEARCH pre-fills the
   filter (pi initialSearchInput)."
  [mode entries on-select on-cancel & [search]]
  (let [th (theme/get-current-theme)
        entries (vec entries)
        search-input (input/make-input)
        list-container (container/make-container)
        c (container/make-container)
        add (fn [child] (container/container-add-child c child))
        title (if (= :login mode)
                "Select provider to configure:"
                "Select provider to logout:")
        sel (map->AuthSelector
             {:container c
              :search-input search-input
              :list-container list-container
              :state-atom (atom {:entries entries
                                 :search (or search "")
                                 :selected-idx 0
                                 :filtered entries})
              :mode mode
              :on-select-atom (atom on-select)
              :on-cancel-atom (atom on-cancel)
              :focused? (atom false)
              :cache-atom (atom nil)})]
    (add (db/make-dynamic-border #(theme/fg th :accent %)))
    (add (spacer/make-spacer 1))
    (add (text/make-text (theme/fg th :accent (theme/bold title)) 1 0))
    (add (spacer/make-spacer 1))
    (add search-input)
    (add (spacer/make-spacer 1))
    (add list-container)
    (add (spacer/make-spacer 1))
    (add (db/make-dynamic-border #(theme/fg th :accent %)))
    (when (seq search)
      (input/input-set-value! search-input search))
    (refresh-list! sel)
    sel))

;; ─── IFocusable — forward to the search input (IME cursor positioning) ─────

(extend-type AuthSelector
  protocols/IFocusable
  (focused [this] @(:focused? this))
  (set-focused! [this val]
    (reset! (:focused? this) val)
    (protocols/set-focused! (:search-input this) val)))

;; ─── Auth-method selector (pi ExtensionSelectorComponent) ─────────────────
;; The plain string-option list showLoginAuthTypeSelector renders: no
;; filtering, all options visible, clamped navigation, and the
;; "↑↓ navigate  enter select  escape cancel" hint line.

(defn- method-hint-text
  "pi ExtensionSelectorComponent hint row: rawKeyHint(↑↓, navigate) +
   keyHint(confirm, select) + keyHint(cancel, cancel), two spaces apart."
  []
  (let [th (theme/get-current-theme)
        hint (fn [k desc]
               (str (theme/fg th :dim k)
                    (theme/fg th :muted (str " " desc))))]
    (text/make-text
     (str (hint "↑↓" "navigate") "  "
          (hint (app-kb/key-text "tui.select.confirm") "select") "  "
          (hint (app-kb/key-text "tui.select.cancel") "cancel"))
     1 0)))

(declare refresh-rows!)

(defcomponent AuthMethodSelector nil
              [container list-container options selected-idx-atom on-select-atom
               on-cancel-atom focused? cache-atom]

  (render [this width] (protocols/render (:container this) width))

  (handle-input [this data]
    (let [kmgr (kb/get-global-keybindings)
          n (count options)]
      (cond
        ;; Up/k — clamp at the top (pi ExtensionSelectorComponent)
        (or (kb/matches-key kmgr data "tui.select.up") (= data "k"))
        (do (swap! selected-idx-atom #(max 0 (dec %)))
            (refresh-rows! this)
            nil)

        ;; Down/j — clamp at the bottom
        (or (kb/matches-key kmgr data "tui.select.down") (= data "j"))
        (do (when (pos? n)
              (swap! selected-idx-atom #(min (dec n) (inc %)))
              (refresh-rows! this))
            nil)

        ;; Enter — select (pi tui.select.confirm / "\n"; pi guards on the
        ;; selected option's truthiness — nth on an empty/stale index must
        ;; not throw here)
        (kb/matches-key kmgr data "tui.select.confirm")
        (do (when (and (pos? n) (< @selected-idx-atom n))
              (when-let [cb @on-select-atom]
                (cb (nth options @selected-idx-atom))))
            nil)

        ;; Escape / Ctrl+C — cancel (pi tui.select.cancel)
        (kb/matches-key kmgr data "tui.select.cancel")
        (do (when-let [cb @on-cancel-atom] (cb)) nil)

        :else nil))))

(extend-type AuthMethodSelector
  protocols/IFocusable
  (focused [this] @(:focused? this))
  (set-focused! [this val] (reset! (:focused? this) val)))

(defn- refresh-rows!
  "Rebuild the option rows (pi updateList): accent → selection, plain text
   otherwise."
  [this]
  (let [th (theme/get-current-theme)
        rows (container/make-container)]
    (doseq [i (range (count (:options this)))]
      (let [option (nth (:options this) i)
            is-selected (= i @(:selected-idx-atom this))]
        (container/container-add-child
         rows (text/make-text
               (if is-selected
                 (str (theme/fg th :accent "→ ") (theme/fg th :accent option))
                 (str "  " (theme/fg th :text option)))
               1 0))))
    (container/container-set-children! (:list-container this) @(:children rows))))

(defn make-auth-method-selector
  "Create the auth-method selector (pi showLoginAuthTypeSelector's
   ExtensionSelectorComponent). TITLE — dialog title; OPTIONS — vector of
   label strings; ON-SELECT receives the chosen string; ON-CANCEL fires on
   escape."
  [title options on-select on-cancel]
  (let [th (theme/get-current-theme)
        c (container/make-container)
        list-container (container/make-container)
        add (fn [child] (container/container-add-child c child))
        sel (map->AuthMethodSelector
             {:container c
              :list-container list-container
              :options (vec options)
              :selected-idx-atom (atom 0)
              :on-select-atom (atom on-select)
              :on-cancel-atom (atom on-cancel)
              :focused? (atom false)
              :cache-atom (atom nil)})]
    (add (db/make-dynamic-border #(theme/fg th :accent %)))
    (add (spacer/make-spacer 1))
    (add (text/make-text (theme/fg th :accent (theme/bold title)) 1 0))
    (add (spacer/make-spacer 1))
    (add list-container)
    (add (spacer/make-spacer 1))
    (add (method-hint-text))
    (add (spacer/make-spacer 1))
    (add (db/make-dynamic-border #(theme/fg th :accent %)))
    (refresh-rows! sel)
    sel))
