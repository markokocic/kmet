(ns kmet.app.ui.thinking-selector
  "Thinking-level selector panel (pi: modes/interactive/components/
   thinking-selector.ts): a bordered panel with a visible search filter and
   one row per level the current model can express — the active level marked
   with a ✓ and the settings default noted \"· default\". Enter applies the
   selected level to the session (pi selectThinkingLevel without persist),
   Ctrl+S also saves it as the default thinking level (pi persist: true),
   Esc cancels. Mounted in place of the editor (pi: showSelector)."
  (:require [clojure.string :as str]
            [kmet.ai.api.shared :as shared]
            [kmet.ai.models :as models]
            [kmet.app.keybindings :as app-kb]
            [kmet.app.ui.dock :as dock]
            [kmet.config :as cfg]
            [kmet.tui.components.container :as container]
            [kmet.tui.components.input :as input]
            [kmet.tui.components.text :as text]
            [kmet.tui.core :as tui]
            [kmet.tui.hiccup :as h]
            [kmet.tui.keys :as keys]
            [kmet.tui.keybindings :as kb]
            [kmet.tui.macros :refer [defcomponent]]
            [kmet.tui.protocols :as protocols]
            [kmet.tui.theme :as theme]))

;; ─── Level metadata (pi THINKING_DESCRIPTIONS) ────────────────────────────

(def level-descriptions
  "One-line description per thinking level (pi: thinking-selector.ts +
   settings-selector.ts THINKING_DESCRIPTIONS)."
  {:off "No reasoning"
   :minimal "Very brief reasoning (~1k tokens)"
   :low "Light reasoning (~2k tokens)"
   :medium "Moderate reasoning (~8k tokens)"
   :high "Deep reasoning (~16k tokens)"
   :xhigh "Extra-high reasoning (~32k tokens)"
   :max "Maximum reasoning"})

(defn level-description
  "Description of LEVEL, or \"\" for unknown levels."
  [level]
  (get level-descriptions level ""))

(defn- key-or
  "The resolved key text for a keybinding id, or FALLBACK when unbound."
  [id fallback]
  (let [t (app-kb/key-text id)]
    (if (seq t) t fallback)))

(defn- fuzzy-match?
  "Subsequence fuzzy match (same as the SelectList filter)."
  [pattern text]
  (let [pl (count pattern) tl (count text)]
    (if (zero? pl)
      true
      (loop [pi 0 ti 0]
        (if (>= pi pl)
          true
          (if (>= ti tl)
            false
            (if (= (nth pattern pi) (nth text ti))
              (recur (inc pi) (inc ti))
              (recur pi (inc ti)))))))))

(defn- filtered-levels
  "LEVELS matching the search query, fuzzy over level name + description."
  [st]
  (let [query (str/lower-case (:search st))]
    (if (str/blank? query)
      (:levels st)
      (vec (filter (fn [level]
                     (fuzzy-match? query
                                   (str/lower-case
                                    (str (name level) " " (level-description level)))))
                   (:levels st))))))

;; ─── Component ─────────────────────────────────────────────────────────────

(declare thinking-refresh!)

(defcomponent ThinkingSelector nil
              [container rows-container search-input state-atom
               on-select-atom on-persist-atom on-cancel-atom focused? cache-atom]

  (render [this width] (protocols/render (:container this) width))

  (handle-input [this data]
    (let [kmgr (kb/get-global-keybindings)
          st @state-atom
          filtered (filtered-levels st)
          n (count filtered)]
      (cond
        ;; Navigation (pi tui.select.up/down — wraps; rebuilds the rows so
        ;; the selection arrow moves)
        (kb/matches-key kmgr data "tui.select.up")
        (do (when (pos? n)
              (swap! state-atom assoc
                     :selected-idx (if (zero? (:selected-idx st))
                                     (dec n)
                                     (dec (:selected-idx st))))
              (thinking-refresh! this))
            nil)

        (kb/matches-key kmgr data "tui.select.down")
        (do (when (pos? n)
              (swap! state-atom assoc
                     :selected-idx (if (= (:selected-idx st) (dec n))
                                     0
                                     (inc (:selected-idx st))))
              (thinking-refresh! this))
            nil)

        ;; Enter — apply the selected level to the session (pi
        ;; tui.select.confirm → selectThinkingLevel(level, false))
        (kb/matches-key kmgr data "tui.select.confirm")
        (let [level (when (pos? n)
                      (nth filtered (min (:selected-idx st) (dec n))))]
          (when level
            (when-let [cb @on-select-atom]
              (cb level)))
          nil)

        ;; Ctrl+S — apply AND persist as the default thinking level (pi:
        ;; ThinkingSelectorComponent.handleInput matches ctrl+s before the
        ;; select-list navigation and calls onSelectAsDefault)
        (keys/matches-key? data (keys/ctrl "s"))
        (let [level (when (pos? n)
                      (nth filtered (min (:selected-idx st) (dec n))))]
          (when level
            (when-let [cb @on-persist-atom]
              (cb level)))
          nil)

        ;; Ctrl+C — clear the search, or cancel when already empty
        (keys/matches-key? data (keys/ctrl "c"))
        (if (str/blank? (:search st))
          (do (when-let [cb @on-cancel-atom] (cb)) nil)
          (do (swap! state-atom assoc :search "" :selected-idx 0)
              (input/input-set-value! search-input "")
              (thinking-refresh! this)
              nil))

        ;; Escape — cancel
        (keys/matches-key? data "escape")
        (do (when-let [cb @on-cancel-atom] (cb)) nil)

        ;; Everything else — the search input (the visible filter, pi)
        :else
        (do (protocols/handle-input search-input data)
            (let [value (input/input-get-value search-input)]
              (when (not= value (:search st))
                (swap! state-atom assoc :search value :selected-idx 0)
                (thinking-refresh! this)))
            nil)))))

;; ─── Rendering helpers (pi updateList / SelectList rows) ──────────────────

(defn- think-row
  "One level row: selection arrow, ✓ mark for the ACTIVE level (pi label
   prefix \"✓ \"), the level name, and the muted description (pi second
   column; the settings default gets a \"· default\" note)."
  [th level selected? current? default? name-width]
  (let [prefix (if selected? (theme/fg th :accent "→ ") "  ")
        mark (if current? (theme/fg th :success "✓ ") "  ")
        name (str (name level)
                  (apply str (repeat (max 0 (- name-width (count (name level))))
                                     \space)))
        name-text (if selected? (theme/fg th :accent name) name)
        desc (theme/fg th :muted
                       (str " " (level-description level)
                            (when default? " · default")))]
    (str prefix mark name-text desc)))

(defn thinking-refresh!
  "Rebuild the level rows from the current state (pi updateList — the rows
   move with the selection and the search filter)."
  [this]
  (let [st @(:state-atom this)
        th (theme/get-current-theme)
        filtered (filtered-levels st)
        n (count filtered)
        selected (min (:selected-idx st) (max 0 (dec n)))
        _ (swap! (:state-atom this) assoc :selected-idx selected)
        rows (container/make-container)
        name-width (reduce (fn [w l] (max w (count (name l))))
                           0 (:levels st))]
    (if (zero? n)
      (container/container-add-child
       rows (text/make-text (theme/fg th :muted "  No matching levels") 1 0))
      (doseq [i (range n)]
        (let [level (nth filtered i)]
          (container/container-add-child
           rows (text/make-text
                 (think-row th level
                            (= i selected)
                            (= level (:current st))
                            (= level (:default st))
                            name-width)
                 1 0)))))
    (container/container-set-children! (:rows-container this) @(:children rows))
    nil))

;; ─── Construction ──────────────────────────────────────────────────────────

(defn make-thinking-selector
  "Create the thinking-level selector (pi ThinkingSelectorComponent).
   LEVELS — the levels the current model can express; CURRENT — the active
   level (marked ✓ and selected initially); DEFAULT — the settings default
   (its description gains a \"· default\" note, pi defaultThinkingLevel).
   Callbacks: :on-select (fn [level]) — Enter, session-level change;
   :on-persist (fn [level]) — Ctrl+S, also the settings default;
   :on-cancel."
  [levels current default & {:keys [on-select on-persist on-cancel]}]
  (let [th (theme/get-current-theme)
        st (atom {:levels (vec levels)
                  :current current
                  :default default
                  :selected-idx 0
                  :search ""})
        search-input (input/make-input)
        rows-container (container/make-container)
        cycle-key (key-or "app.thinking.cycle" "Shift+Tab")
        ;; The frame is a compiled hiccup tree (dsl.md): the border/spacer/
        ;; title/hint chrome is DSL-owned; the search input and the rebuilt
        ;; rows splice foreign (imperative, updated via setters).
        c (h/compile-tree
           [:container {}
            [:dynamic-border {:color-fn #(theme/fg th :accent %)}]
            [:spacer {:lines 1}]
            [:text {:padding-x 1 :padding-y 0}
             (theme/fg th :accent (theme/bold "Thinking Level"))]
            [:text {:padding-x 1 :padding-y 0}
             (theme/fg th :muted
                       (str cycle-key " cycles thinking levels in-session"))]
            [:spacer {:lines 1}]
            search-input
            [:spacer {:lines 1}]
            rows-container
            [:spacer {:lines 1}]
            [:text {:padding-x 1 :padding-y 0}
             (theme/dim "  Enter to select · Ctrl+S to set as default · Esc to cancel")]
            [:spacer {:lines 1}]
            [:dynamic-border {:color-fn #(theme/fg th :accent %)}]])
        sel (map->ThinkingSelector
             {:container c
              :rows-container rows-container
              :search-input search-input
              :state-atom st
              :on-select-atom (atom on-select)
              :on-persist-atom (atom on-persist)
              :on-cancel-atom (atom on-cancel)
              :focused? (atom false)
              :cache-atom (atom nil)})]
    ;; initial selection: the active level when present, else the top row
    ;; (pi preselects the current level in the SelectList)
    (let [idx (first (keep-indexed (fn [i l] (when (= l current) i))
                                   levels))]
      (swap! st assoc :selected-idx (or idx 0)))
    (thinking-refresh! sel)
    sel))

;; ─── IFocusable — forward to the search input (IME cursor positioning) ─────

(extend-type ThinkingSelector
  protocols/IFocusable
  (focused [this] @(:focused? this))
  (set-focused! [this val]
    (reset! (:focused? this) val)
    (protocols/set-focused! (:search-input this) val)))

;; ─── Public helpers ────────────────────────────────────────────────────────

(defn thinking-selector-get-levels
  "The selector's level list (tests + callers inspecting a mounted selector)."
  [sel]
  (:levels @(:state-atom sel)))

(defn available-levels
  "Levels the agent's current model can express (pi getAvailableThinkingLevels):
   the model's supported levels, or [:off] when the model is unknown."
  [cs]
  (let [ag @(:agent-state cs)
        model (models/get-model @(:provider ag) @(:model ag))]
    (if model
      (shared/get-supported-thinking-levels model)
      [:off])))

(defn default-thinking-level
  "The settings default thinking level (pi SettingsManager
   getDefaultThinkingLevel): the live settings.edn :thinking, else the
   config snapshot value. Invalid values fall back to :off."
  [cs]
  (let [v (cfg/get-setting-live (:config cs) :thinking :off)]
    (if (keyword? v) v :off)))

(defn show-thinking-selector
  "pi showThinkingSelector — mount the thinking-level selector for the
   current model in place of the editor. ON-SELECT (fn [level]) applies a
   session-level change (pi selectThinkingLevel(level, false)); ON-PERSIST
   additionally records the level as the settings default (pi Ctrl+S →
   selectThinkingLevel(level, true)). The caller guards models that only
   express :off (pi opens the selector regardless; kmet's cycle parity —
   \"Current model does not support thinking\")."
  [cs & {:keys [on-select on-persist]}]
  (let [ag @(:agent-state cs)
        sel-atom (atom nil)
        sel (make-thinking-selector
             (available-levels cs)
             @(:thinking ag)
             (default-thinking-level cs)
             :on-select (fn [level]
                          ((:done @sel-atom))
                          (on-select level)
                          (tui/tui-request-render (:tui cs)))
             :on-persist (fn [level]
                           ((:done @sel-atom))
                           (on-persist level)
                           (tui/tui-request-render (:tui cs)))
             :on-cancel (fn []
                          ((:done @sel-atom))
                          (tui/tui-request-render (:tui cs))))]
    ;; pi: showSelector — the selector replaces the editor dock
    (reset! sel-atom {:done (dock/mount! cs sel)})
    (tui/tui-request-render (:tui cs))))
