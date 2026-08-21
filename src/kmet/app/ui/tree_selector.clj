(ns kmet.app.ui.tree-selector
  "Session tree navigation panel (pi: TreeSelectorComponent, mounted in
   place of the editor): browse the entry tree (active branch first,
   labels shown, current leaf marked) and select an entry to branch there.
   Filter modes (ctrl+d/t/u/l/a) and label editing (shift+l) work inside
   the panel. ON-NAVIGATE receives the chosen entry — the mode performs
   the branch (pi: the component emits the navigation event,
   interactive-mode navigates)."
  (:require [clojure.string :as str]
            [kmet.app.session :as session]
            [kmet.app.ui :as ui]
            [kmet.app.ui.dialogs :as dialogs]
            [kmet.app.ui.dock :as dock]
            [kmet.tui.components.container :as container]
            [kmet.tui.components.dynamic-border :as db]
            [kmet.tui.components.select-list :as select-list]
            [kmet.tui.components.text :as text]
            [kmet.tui.core :as tui]
            [kmet.tui.keys :as keys]
            [kmet.tui.keybindings :as tui-kb]
            [kmet.tui.theme :as th]))

(def ^:private tree-filter-modes
  "pi: FilterMode — the /tree selector filter modes (default hides
   bookkeeping entries; children of hidden nodes are hidden with them)."
  [:default :no-tools :user-only :labeled-only :all])

(defn- tree-filter-mode-label
  [mode]
  (case mode
    :no-tools " [no-tools]"
    :user-only " [user]"
    :labeled-only " [labeled]"
    :all " [all]"
    ""))

(defn- passes-tree-filter?
  "True when a tree node passes MODE (pi: TreeSelectorComponent applyFilter —
   default hides bookkeeping entries: labels, session_info, model/thinking
   change entries)."
  [node mode]
  (let [settings-entry? (contains? #{:label :session_info :model-change :thinking-level-change}
                                   (:role node))]
    (case mode
      :user-only (= :user (:role node))
      :no-tools (and (not= :tool (:role node)) (not settings-entry?))
      :labeled-only (some? (:label node))
      :all true
      (not settings-entry?))))

(defn- order-tree-for-selector
  "Order tree nodes for the selector: nodes on the active branch path first,
   the rest in file order (pi: TreeSelectorComponent current-branch-first)."
  [nodes active-ids]
  (let [{on-path true off-path false} (group-by #(contains? active-ids (:id %)) nodes)]
    (concat on-path off-path)))

(defn show-session-tree
  "Session tree navigation panel (pi: TreeSelectorComponent): browse the
   entry tree (active branch first, labels shown, current leaf marked) and
   select an entry to branch there. Filter modes (ctrl+d/t/u/l/a/o) and
   label editing (shift+l) work inside the panel. ON-NAVIGATE receives
   the chosen entry; the caller performs the branch."
  [cs on-navigate]
  (let [sess @(:session-atom cs)]
    (if (nil? sess)
      (ui/chat-history-add-message! (:chat-history cs)
                                    {:role :assistant :content "No active session."})
      (let [th-current (th/get-current-theme)
            leaf-id @(:leaf-id sess)
            active-ids (set (map :id (session/get-branch sess)))
            tree (session/get-tree sess)]
        (if (empty? tree)
          (ui/chat-history-add-message! (:chat-history cs)
                                        {:role :assistant :content "Session is empty."})
          (let [filter-mode (atom :default)
                sl-ref (atom nil)
                ;; late binding: select callbacks reach the dock's done via
                ;; this atom (pi: done() from showSelector)
                sel-atom (atom nil)
                ;; pi: lastSelectedId — the selection survives filter
                ;; changes by entry id
                last-selected-id (atom nil)
                ;; the tree snapshot from open time; label edits patch it in
                ;; place (one consistent view for the panel's lifetime)
                tree-vol (volatile! tree)
                help-text (text/make-text "" 0 0)
                set-help! (fn []
                            (text/text-set!
                             help-text
                             (th/fg th-current :muted
                                    (str "Filters: "
                                         (or (tui-kb/key-text (tui-kb/get-global-keybindings)
                                                              "app.tree.filter.cycleForward") "ctrl+o")
                                         " cycle · "
                                         (or (tui-kb/key-text (tui-kb/get-global-keybindings)
                                                              "app.tree.editLabel") "shift+l")
                                         " edit label"
                                         (tree-filter-mode-label @filter-mode)))))
                build-items (fn [tree]
                              (let [flatten-tree (fn flatten-tree [nodes depth]
                                                   (mapcat (fn [n]
                                                             (if (passes-tree-filter? n @filter-mode)
                                                               (let [prefix (apply str (repeat depth "  "))
                                                                     role (:role n)
                                                                     role-str (name role)
                                                                     summary (:summary n)
                                                                     ;; pi getEntryDisplayText: colored role
                                                                     ;; prefixes; an empty assistant completion
                                                                     ;; shows as a muted "(no content)" — its
                                                                     ;; only visual trace
                                                                     label (str prefix
                                                                                (case role-str
                                                                                  "user" (th/fg th-current :accent "user: ")
                                                                                  "assistant" (th/fg th-current :success "assistant: ")
                                                                                  (str role-str ": "))
                                                                                (if (and (= role :assistant) (= summary "(no content)"))
                                                                                  (th/fg th-current :muted summary)
                                                                                  summary)
                                                                                (when (:label n) (str " [" (:label n) "]"))
                                                                                (when (= (:id n) leaf-id) " ◀"))]
                                                                 (cons {:label label
                                                                        :value (:id n)
                                                                        :depth depth
                                                                        :entry n}
                                                                       (flatten-tree (order-tree-for-selector (:children n) active-ids)
                                                                                     (inc depth))))
                                                               nil))
                                                           (order-tree-for-selector nodes active-ids)))]
                                (vec (flatten-tree tree 0))))
                refresh! (fn []
                           (let [items (build-items @tree-vol)]
                             ;; pi applyFilter: remember the selection before
                             ;; rebuilding, then restore it by entry id — or
                             ;; land on the nearest visible ancestor when the
                             ;; node itself is filtered out
                             (when-let [sel (select-list/select-list-get-selected @sl-ref)]
                               (reset! last-selected-id (:value sel)))
                             (select-list/select-list-set-items! @sl-ref items)
                             (when-let [id @last-selected-id]
                               (let [idx-of (fn [entry-id]
                                              (first (keep-indexed
                                                      (fn [i item] (when (= entry-id (:value item)) i))
                                                      items)))
                                     parent-of (into {}
                                                     (map (fn [e] [(:id e) (:parent-id e)]))
                                                     @(:entries sess))]
                                 (loop [p id]
                                   (if-let [i (idx-of p)]
                                     (select-list/select-list-set-selected! @sl-ref i)
                                     (when-let [pp (get parent-of p)]
                                       (recur pp))))))
                             (select-list/select-list-set-header!
                              @sl-ref (str "Session tree" (tree-filter-mode-label @filter-mode)))
                             (set-help!)
                             (tui/tui-request-render (:tui cs))))
                edit-label! (fn []
                              (when-let [sel (select-list/select-list-get-selected @sl-ref)]
                                (let [entry-id (:value sel)
                                      current (session/get-label sess entry-id)]
                                  (tui/tui-show-overlay
                                   (:tui cs)
                                   (dialogs/make-input-dialog
                                    "Edit tree label"
                                    (fn [label]
                                      (tui/tui-hide-overlay (:tui cs))
                                      (let [label (str/trim label)
                                            label' (when (seq label) label)]
                                        (session/set-label! sess entry-id label')
                                        ;; patch the snapshot so the row shows
                                        ;; the label without an O(n^2) rebuild
                                        (letfn [(patch-node [n]
                                                  (cond-> n
                                                    (= (:id n) entry-id) (assoc :label label')
                                                    true (update :children #(mapv patch-node %))))]
                                          (vswap! tree-vol #(mapv patch-node %))))
                                      (refresh!))
                                    (fn []
                                      (tui/tui-hide-overlay (:tui cs))
                                      (tui/tui-request-render (:tui cs)))
                                    (th/get-current-theme)
                                    ;; pi: LabelInput prefills the current
                                    ;; label; empty submit clears it
                                    current))
                                  (tui/tui-request-render (:tui cs)))))
                cycle-filter! (fn [dir]
                                (let [i (first (keep-indexed (fn [i m] (when (= m @filter-mode) i))
                                                             tree-filter-modes))
                                      n (count tree-filter-modes)
                                      nxt (nth tree-filter-modes (mod (+ i dir) n))]
                                  (reset! filter-mode nxt)
                                  (refresh!)))
                on-key (fn [_ data]
                         (let [kmgr (or (tui-kb/get-global-keybindings)
                                        ;; no manager installed (tests,
                                        ;; pre-startup) — use the defaults
                                        (tui-kb/make-tui-keybindings-manager))
                               filter-key? (fn [id]
                                             (tui-kb/matches-key kmgr data id))]
                           (cond
                             (filter-key? "app.tree.filter.default")
                             (do (reset! filter-mode :default) (refresh!) true)
                             (filter-key? "app.tree.filter.noTools")
                             (do (reset! filter-mode (if (= :no-tools @filter-mode) :default :no-tools))
                                 (refresh!) true)
                             (filter-key? "app.tree.filter.userOnly")
                             (do (reset! filter-mode (if (= :user-only @filter-mode) :default :user-only))
                                 (refresh!) true)
                             (filter-key? "app.tree.filter.labeledOnly")
                             (do (reset! filter-mode (if (= :labeled-only @filter-mode) :default :labeled-only))
                                 (refresh!) true)
                             (filter-key? "app.tree.filter.all")
                             (do (reset! filter-mode (if (= :all @filter-mode) :default :all))
                                 (refresh!) true)
                             (filter-key? "app.tree.filter.cycleForward")
                             (do (cycle-filter! 1) true)
                             (filter-key? "app.tree.filter.cycleBackward")
                             (do (cycle-filter! -1) true)
                             ;; shift+l edits the label (legacy terminals send a
                             ;; bare uppercase letter — pi: app.tree.editLabel)
                             (or (filter-key? "app.tree.editLabel")
                                 (keys/matches-key? data "L"))
                             (do (edit-label!) true)
                             :else false)))
                on-select-fn (fn [_]
                               (when-let [sel (select-list/select-list-get-selected @sl-ref)]
                                 (let [entry (:entry sel)]
                                   ((:done @sel-atom))
                                   (cond
                                     (= (:id entry) leaf-id)
                                     (ui/chat-history-add-message! (:chat-history cs)
                                                                   {:role :assistant :content "Already at this point."})

                                     @(:running-turn? cs)
                                     (ui/chat-history-add-message! (:chat-history cs)
                                                                   {:role :assistant
                                                                    :content "Wait for the current response to finish before navigating the session tree."})

                                     :else
                                     (on-navigate entry)))))
                items (build-items @tree-vol)
                sl (select-list/make-select-list items
                                                 :height (min (count items) 20)
                                                 :header "Session tree"
                                                 :on-key on-key
                                                 :on-select on-select-fn
                                                 :on-escape (fn []
                                                              ((:done @sel-atom))))]
            (reset! sl-ref sl)
            ;; pi: showSelector — the tree replaces the editor dock, framed
            ;; like TreeSelectorComponent (border / title+help / border)
            (let [th (th/get-current-theme)]
              (set-help!)
              (let [panel (container/make-container
                           [(db/make-dynamic-border #(th/fg th :accent %))
                            (text/make-text (th/bold "  Session Tree") 0 0)
                            help-text
                            (db/make-dynamic-border #(th/fg th :accent %))
                            sl
                            (db/make-dynamic-border #(th/fg th :accent %))])]
              ;; pi: focus the interactive child (focus: treeList)
                (reset! sel-atom {:done (dock/mount! cs panel sl)})
                (reset! sl-ref sl)
                (tui/tui-request-render (:tui cs))))))))))
