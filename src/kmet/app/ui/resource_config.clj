(ns kmet.app.ui.resource-config
  "The `kmet config` resource-configuration screen (pi:
   modes/interactive/components/config-selector.ts — the package-resource
   part; kmet has no top-level settings resource entries, so only
   package-origin groups are listed, see alignment.md).

   Layout (pi parity): top spacer, top border, spacer, two header lines
   (title · action hints, scope hint), spacer, the search input line, a
   blank line, then group rows, subgroup rows and resource rows, bottom
   spacer and bottom border. Space/enter toggles the selected resource,
   Tab switches the global/project write scope, escape closes, ctrl+c
   exits. In project scope every row cycles inherit/load/unload — the
   writes are +pattern/−pattern entries in the project settings :packages
   entry, and an inherited user resource gets a fresh :autoload false
   delta entry when the project has none (pi setProjectPackageOverride);
   inherited rows are dimmed and carry their override suffix. The search
   input filters by resource name, type and path.

   Only package-origin resources are listed. The screen re-reads the
   settings files after every toggle, so what it shows always matches what
   the loaders will do on the next start/reload. Single-extension packages
   (a file source or an extension.edn directory) ignore per-type filters,
   so those rows are marked “always loaded” and cannot be toggled."
  (:require [clojure.string :as str]
            [babashka.fs :as fs]
            [kmet.app.keybindings :as app-kb]
            [kmet.app.packages :as pkgs]
            [kmet.config :as cfg]
            [kmet.tui.components.input :as input]
            [kmet.tui.keys :as keys]
            [kmet.tui.macros :refer [defcomponent track!]]
            [kmet.tui.protocols :as protocols]
            [kmet.tui.theme :as th]
            [kmet.tui.utils :as u]))

(def ^:private type-order
  "pi typeOrder — subgroup order inside a group."
  {:extensions 0 :skills 1 :prompts 2 :themes 3})

(def ^:private chrome-lines
  "Fixed lines around the list rows (pi ConfigSelectorComponent chrome):
   top spacer, top border, spacer, two header lines, spacer, search input
   line, blank line, bottom spacer, bottom border."
  11)

;; ─── View model (pi: buildGroups / ResourceItem / filterItems) ────────────

(defn- display-name
  "pi displayName rules: extensions name their file (with the parent folder
   prefix when the extension does not live in a conventional extensions/
   dir); SKILL.md skills name their folder; prompts/themes their file."
  [item]
  (let [path (:path item)
        file (fs/file-name path)
        parent (fs/file-name (fs/parent path))]
    (cond
      (and (= (:resource-type item) :extensions) (not= parent "extensions"))
      (if (and parent file) (str parent "/" file) (or file path))

      (and (= (:resource-type item) :skills) (= file "SKILL.md"))
      (or parent path)

      :else (or file path))))

(defn- build-groups
  "pi buildGroups — resolved PackageItems grouped per package (metadata
   scope + source) with per-type subgroups; user scope before project,
   sources sorted, items sorted by display name."
  [items-by-type]
  (let [all (mapcat items-by-type (keys items-by-type))
        by-group (group-by (fn [item]
                             {:scope (get-in item [:metadata :scope])
                              :source (get-in item [:metadata :source])})
                           all)]
    (vec
     (for [[gk group-items] (sort-by (fn [[gk _]]
                                       [(if (= :user (:scope gk)) 0 1) (:source gk)])
                                     by-group)]
       {:scope (:scope gk)
        :label (str (:source gk) " (" (name (:scope gk)) ")")
        :subgroups (vec
                    (for [type (sort-by type-order
                                        (distinct (map :resource-type group-items)))]
                      {:type type
                       :items (vec (sort-by display-name
                                            (filter #(= type (:resource-type %))
                                                    group-items)))}))}))))

(defn- query-match?
  "pi filterItems — display name, resource type name or path contains the
   lower-cased QUERY."
  [item query]
  (or (str/blank? query)
      (str/includes? (str/lower-case (display-name item)) query)
      (str/includes? (name (:resource-type item)) query)
      (str/includes? (str/lower-case (:path item)) query)))

(defn- build-flat
  "The filtered flat row list of GROUPS (pi buildFlatList + filterItems):
   group rows, subgroup rows and item rows, keeping only the containers of
   matching items. Returns {:rows [...] :item-rows [...]}."
  [groups query]
  (let [query (str/lower-case (str/trim (or query "")))
        match? (fn [it] (query-match? it query))
        groups (into []
                     (keep (fn [g]
                             (let [subs (into []
                                              (keep (fn [sg]
                                                      (let [items (filterv match? (:items sg))]
                                                        (when (seq items)
                                                          (assoc sg :items items))))
                                                    (:subgroups g)))]
                               (when (seq subs)
                                 (assoc g :subgroups subs)))))
                     groups)
        rows (vec (mapcat
                   (fn [g]
                     (cons {:kind :group :group g}
                           (mapcat (fn [sg]
                                     (cons {:kind :subgroup :group g :subgroup sg}
                                           (map (fn [item]
                                                  {:kind :item :group g :subgroup sg :item item})
                                                (:items sg))))
                                   (:subgroups g))))
                   groups))]
    {:rows rows
     :item-rows (filterv #(= :item (:kind %)) rows)}))

(defn- view-of
  "The resolved items of the WRITE-SCOPE's view (user settings only for the
   global scope, merged for the project scope)."
  [views write-scope]
  (get views (if (= write-scope :project) :project :user)))

(defn- item-enabled-of
  "The resolved enabled state of ITEM in the view that applies to
   WRITE-SCOPE (pi renders the item's resolved state under an :inherit
   override)."
  [item write-scope views]
  (let [items (view-of views write-scope)
        key (pkgs/item-key item)]
    (boolean (:enabled (first (filter #(= key (pkgs/item-key %))
                                      (get items (:resource-type item))))))))

;; ─── Rendering helpers ────────────────────────────────────────────────────

(defn- hint
  "A key hint: the current binding text for BINDING-ID (or FALLBACK) plus
   the action description (pi keyHint/rawKeyHint)."
  [binding-id fallback desc]
  (let [key-text (app-kb/key-text binding-id)]
    (str (if (seq key-text) key-text fallback) " " desc)))

(defn- render-checkbox
  "pi renderCheckbox."
  [t write-scope state enabled]
  (if (= write-scope :project)
    (case state
      :load (th/fg t :success "[+]")
      :unload (th/fg t :warning "[-]")
      (th/fg t :dim (if enabled "[x]" "[ ]")))
    (if enabled (th/fg t :success "[x]") (th/fg t :dim "[ ]"))))

(defn- override-suffix
  "pi getItemSuffix — project-scope override suffixes."
  [t write-scope state inherited?]
  (if (not= write-scope :project)
    ""
    (case state
      :load (th/fg t :muted "  project load")
      :unload (th/fg t :muted "  project unload")
      (when inherited? (th/fg t :dim "  inherited global")))))

(defn- row-line
  "Render one visible row (pi ResourceList.render rows)."
  [t state width row selected?]
  (let [write-scope (:write-scope state)]
    (case (:kind row)
      :group
      (let [group (:group row)
            inherited? (and (= write-scope :project) (= :user (:scope group)))
            label (str "  " (th/bold (:label group))
                       (when inherited?
                         (str " · " (th/fg t :dim "inherited global"))))]
        (u/truncate-to-width
         (if inherited? (th/fg t :dim label) (th/fg t :accent label))
         width ""))

      :subgroup
      (let [inherited? (and (= write-scope :project) (= :user (:scope (:group row))))]
        (str "    " (th/fg t (if inherited? :dim :muted)
                           (get pkgs/resource-type-labels (:type (:subgroup row))))))

      :item
      (let [item (:item row)
            ov (:override-state row)
            inherited? (:inherited? row)
            ;; a single-extension package always loads — no filter can
            ;; disable it (see packages/single-extension-item?)
            single? (pkgs/single-extension-item? item)
            dimmed? (and (= write-scope :project) inherited? (= ov :inherit))
            cursor (if selected? (th/fg t :accent "> ") "  ")
            name (display-name item)
            name (if (and selected? (not dimmed?)) (th/bold name) name)
            name (if dimmed? (th/fg t :dim name) name)
            suffix (if single?
                     (th/fg t :muted "  always loaded")
                     (override-suffix t write-scope ov inherited?))]
        (u/truncate-to-width
         (str cursor "    "
              (render-checkbox t write-scope (if single? :inherit ov) (:enabled row))
              " " name suffix)
         width "...")))))

;; ─── Screen state ─────────────────────────────────────────────────────────

(defn- read-views
  "Resolve both package views from the settings files on disk:
   {:user user-only-resolution :project merged-resolution}."
  []
  (let [user-settings (cfg/read-global-settings-map)
        project-settings (cfg/read-project-settings-map)]
    {:user (pkgs/resolve-package-items user-settings nil)
     :project (pkgs/resolve-package-items user-settings project-settings)}))

(defn- item-state-of
  "The per-row state of ITEM in the current WRITE-SCOPE (pi reads the
   settings live at render time): the project override state, the inherited
   (global) enabled flag and the effective enabled flag."
  [item write-scope views inherited-keys]
  (let [scope (get-in item [:metadata :scope])
        key (pkgs/item-key item)
        inherited? (or (= scope :user) (contains? inherited-keys key))
        inherited-enabled (if (contains? inherited-keys key)
                            (item-enabled-of item :user views)
                            true)]
    {:inherited? inherited?
     :inherited-enabled inherited-enabled
     :override-state (pkgs/override-state-of item (pkgs/project-packages))
     :enabled (item-enabled-of item write-scope views)}))

(defn- rebuild-layout!
  "(Re)build the screen's flat rows: re-read the settings files, resolve
   both views and derive every row's state (pi: buildGroups +
   buildFlatList + filterItems — run on construction, scope switches and
   search changes; toggles update the rows in place like pi's updateItem,
   they never re-resolve)."
  [this]
  (let [st @(:state-atom this)
        views (read-views)
        write-scope (:write-scope st)
        groups (build-groups (view-of views write-scope))
        {:keys [rows]} (build-flat groups (:query st))
        inherited-keys (into #{}
                             (map pkgs/item-key)
                             (mapcat #(get (:user views) %)
                                     (keys (:user views))))
        enriched (mapv (fn [entry]
                         (if (not= :item (:kind entry))
                           entry
                           (merge entry
                                  (item-state-of (:item entry) write-scope
                                                 views inherited-keys))))
                       rows)
        item-rows (filterv #(= :item (:kind %)) enriched)
        ;; pi: the selection starts on the first item row (buildFlatList)
        first-item (first (keep-indexed (fn [i r] (when (= :item (:kind r)) i)) enriched))
        selected (if first-item
                   first-item
                   (min (or (:selected st) 0) (max 0 (dec (count enriched)))))]
    (swap! (:state-atom this)
           assoc :views views
           :groups groups
           :rows enriched
           :item-rows item-rows
           :selected selected)))

(defn- update-row-state!
  "pi updateItem — after a toggle, refresh the selected row's derived state
   (override state from the live project settings; enabled per the applied
   state) without re-resolving the layout."
  [this {:keys [enabled override-state]}]
  (swap! (:state-atom this)
         (fn [st]
           (let [rows (:rows st)
                 idx (min (:selected st) (max 0 (dec (count rows))))]
             (if (and (= :item (:kind (nth rows idx))) (seq rows))
               (let [row (nth rows idx)
                     row (merge row
                                {:override-state override-state
                                 :enabled enabled})
                     rows (assoc rows idx row)]
                 (assoc st :rows rows
                        :item-rows (filterv #(= :item (:kind %)) rows)))
               st)))))

(defn- find-next-item
  "pi findNextItem — the next item row from FROM-INDEX in DIRECTION."
  [rows from-index direction]
  (let [n (count rows)]
    (loop [idx (+ from-index direction)]
      (cond
        (or (< idx 0) (>= idx n)) from-index
        (= :item (:kind (nth rows idx))) idx
        :else (recur (+ idx direction))))))

(defn- move-selection!
  [this direction]
  (swap! (:state-atom this)
         (fn [st]
           (let [rows (:rows st)]
             (if (seq rows)
               (assoc st :selected (find-next-item rows (:selected st) direction))
               st)))))

(defn- page-target
  "pi pageUp/pageDown scan — the item row at or nearest TARGET in
   DIRECTION (+1 forward, -1 backward), nil when the scan leaves ROWS."
  [rows target direction]
  (let [n (count rows)]
    (loop [idx target]
      (cond
        (or (< idx 0) (>= idx n)) nil
        (= :item (:kind (nth rows idx))) idx
        :else (recur (+ idx direction))))))

(defn- page-selection!
  [this data]
  (swap! (:state-atom this)
         (fn [st]
           (let [rows (:rows st)
                 max-visible (max 5 (- (:rows-count this) chrome-lines))
                 sel (:selected st)
                 n (count rows)
                 up? (keys/matches-key? data "pageUp")
                 target (if up?
                          (max 0 (- sel max-visible))
                          (min (max 0 (dec n)) (+ sel max-visible)))
                 found (page-target rows target (if up? 1 -1))]
             (if found
               (assoc st :selected found)
               st)))))

(defn- toggle-selected!
  "Space/enter on an item row: global scope flips the enabled state (pi
   toggleResource global branch); project scope cycles inherit/load/unload
   (pi getNextOverrideState + setProjectPackageOverride). The row's state
   updates in place (pi updateItem) — the layout is not re-resolved.
   Single-extension package rows cannot be toggled (their filters are
   ignored at resolve time)."
  [this]
  (let [st @(:state-atom this)
        rows (:rows st)
        row (when (seq rows) (nth rows (min (:selected st) (dec (count rows))) nil))]
    (when (and row (= :item (:kind row)))
      (let [item (:item row)]
        (when-not (pkgs/single-extension-item? item)
          (if (= :global (:write-scope st))
            (when (= :user (get-in item [:metadata :scope]))
              (let [enabled (not (:enabled row))]
                (pkgs/apply-global-toggle! item enabled)
                (update-row-state! this {:enabled enabled
                                         :override-state :inherit})))
            (let [next-state (pkgs/next-override-state (:override-state row)
                                                       (:inherited-enabled row))]
              (when (not= next-state (:override-state row))
                (pkgs/apply-project-override! item next-state)
                (update-row-state!
                 this
                 {:override-state next-state
                  :enabled (if (= :inherit next-state)
                             (:inherited-enabled row)
                             (= :load next-state))})))))))))

(defn- refresh-filter!
  "Apply the search input's value to the row list (pi: searchInput change
   → filterItems)."
  [this]
  (swap! (:state-atom this) assoc :query (input/input-get-value (:search-input this)))
  (rebuild-layout! this))

(defn- switch-scope!
  "Tab: switch the write scope and rebuild the layout from the settings of
   the new scope (pi: setWriteScope)."
  [this]
  (swap! (:state-atom this) update :write-scope
         (fn [s] (if (= s :global) :project :global)))
  (rebuild-layout! this))

(defn- close!
  [this]
  (when-let [f @(:on-close-atom this)] (f)))

;; ─── The screen component ─────────────────────────────────────────────────

(defcomponent ResourceConfigScreen nil
              [state-atom search-input on-close-atom rows-count project-mode? cache-atom]

  (render [this width]
    (track! this width
      (let [t (th/get-current-theme)
            st @(:state-atom this)
            write-scope (:write-scope st)
            rows (:rows st)
            item-rows (:item-rows st)
            n (count rows)
            item-total (count item-rows)
            max-visible (max 5 (- (:rows-count this) chrome-lines))
            sel (min (or (:selected st) 0) (max 0 (dec n)))
            start-idx (max 0 (min (- sel (quot max-visible 2)) (- n max-visible)))
            visible (subvec rows start-idx (min (+ start-idx max-visible) n))
            border-fn #(th/fg t :accent %)
            border (border-fn (apply str (repeat (max 1 width) "─")))
            title (th/bold (if (= write-scope :project)
                             "Project Local Resources"
                             "Global Resources"))
            sep (th/fg t :muted " · ")
            switch-hint (when (:project-mode? this)
                          (hint "tui.input.tab" "tab" "switch mode"))
            action (if (= write-scope :project)
                     "space cycle inherit/+/-"
                     "space toggle")
            hints (str (when switch-hint (str switch-hint sep))
                       action sep "esc close")
            title-line (u/truncate-to-width
                        (str title (apply str (repeat (max 1 (- width (u/visible-width title)
                                                                (u/visible-width hints)))
                                                      " "))
                             hints)
                        width "")
            scope-hint (th/fg t :muted
                              (if (= write-scope :global)
                                "~/.kmet/agent/settings.edn"
                                (str ".kmet/settings.edn"
                                     (when (:project-mode? this)
                                       " · inherited global resources are dimmed"))))
            row-lines (vec (for [[rel row] (map-indexed vector visible)]
                             (row-line t st width row
                                       (and (= :item (:kind row))
                                            (= (+ start-idx rel) sel)))))
            clipped? (or (pos? start-idx) (< (+ start-idx max-visible) n))
            scroll-line (when clipped?
                          (let [cur (count (filterv #(= :item (:kind %))
                                                    (subvec rows 0 (inc sel))))]
                            (th/fg t :dim (str "  (" cur "/" item-total ")"))))]
        (into []
              (concat [""]
                      [border "" title-line scope-hint ""]
                      (protocols/render (:search-input this) width)
                      [""]
                      row-lines
                      (when scroll-line [scroll-line])
                      ["" border])))))

  (handle-input [this data]
    (cond
      (or (keys/matches-key? data "up") (keys/matches-key? data (keys/ctrl "p")))
      (do (move-selection! this -1) nil)

      (or (keys/matches-key? data "down") (keys/matches-key? data (keys/ctrl "n")))
      (do (move-selection! this 1) nil)

      (keys/matches-key? data "pageUp")
      (do (page-selection! this data) nil)

      (keys/matches-key? data "pageDown")
      (do (page-selection! this data) nil)

      (keys/matches-key? data "escape")
      (do (close! this) nil)

      (keys/matches-key? data "ctrl+c")
      (do (close! this) nil)

      (keys/matches-key? data "tab")
      (do (when (:project-mode? this) (switch-scope! this)) nil)

      (or (= data " ") (keys/matches-key? data "enter"))
      (do (toggle-selected! this) nil)

      :else
      (do (protocols/handle-input (:search-input this) data)
          (refresh-filter! this)
          nil))))

;; ─── Construction & test helpers ──────────────────────────────────────────

(defn make-resource-config-screen
  "Build the config screen. OPTS:
   :write-scope     — :global (default) or :project (pi: config -l starts
                      in project-local mode)
   :project-mode?   — whether Tab may switch scopes (pi:
                      projectModeAvailable — kmet: the .kmet project dir
                      exists or -l was passed)
   :rows            — terminal height in rows
   :on-close        — called when the user closes the screen (escape or
                      ctrl+c)"
  [& {:keys [write-scope project-mode? rows on-close]}]
  (let [search-input (input/make-input)
        state-atom (atom {:write-scope (or write-scope :global)
                          :query ""
                          :selected 0
                          :rows []})
        screen (map->ResourceConfigScreen
                {:state-atom state-atom
                 :search-input search-input
                 :on-close-atom (atom on-close)
                 :rows-count (or rows 24)
                 :project-mode? (boolean project-mode?)
                 :cache-atom (atom nil)})]
    (rebuild-layout! screen)
    screen))

(defn screen-write-scope
  "The screen's current write scope (tests)."
  [screen]
  (:write-scope @(:state-atom screen)))

(defn screen-rows
  "The screen's current flat rows (tests)."
  [screen]
  (:rows @(:state-atom screen)))

(defn screen-selected
  "The screen's selected row index (tests)."
  [screen]
  (:selected @(:state-atom screen)))

(defn screen-set-write-scope!
  "Switch the write scope (tests; Tab in the UI)."
  [screen write-scope]
  (swap! (:state-atom screen) assoc :write-scope write-scope)
  (rebuild-layout! screen))

(defn screen-set-query!
  "Set the search query and re-filter (tests)."
  [screen query]
  (input/input-set-value! (:search-input screen) (str query))
  (swap! (:state-atom screen) assoc :query (str query))
  (rebuild-layout! screen))
