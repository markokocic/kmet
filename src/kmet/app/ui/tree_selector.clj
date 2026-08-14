(ns kmet.app.ui.tree-selector
  "Session tree navigation overlay (pi: TreeSelectorComponent): browse the
   entry tree (active branch first, labels shown, current leaf marked) and
   select an entry to branch there. Filter modes (ctrl+d/t/u/l/a/o) and
   label editing (shift+l) work inside the overlay. ON-NAVIGATE receives
   the chosen entry — the mode performs the branch (pi: the component emits
   the navigation event, interactive-mode navigates)."
  (:require [kmet.app.ui :as ui]
            [kmet.app.session :as session]
            [kmet.app.ui.extension-dialogs :as dialogs]
            [kmet.tui.core :as tui]
            [kmet.tui.theme :as th]
            [kmet.tui.keys :as keys]
            [kmet.tui.components.select-list :as select-list]
            [clojure.string :as str]))

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
  "Session tree navigation overlay (pi: TreeSelectorComponent): browse the
   entry tree (active branch first, labels shown, current leaf marked) and
   select an entry to branch there. Filter modes (ctrl+d/t/u/l/a/o) and
   label editing (shift+l) work inside the overlay. ON-NAVIGATE receives
   the chosen entry; the caller performs the branch."
  [cs on-navigate]
  (let [sess @(:session-atom cs)]
    (if (nil? sess)
      (ui/chat-history-add-message! (:chat-history cs)
                                    {:role :assistant :content "No active session."})
      (let [leaf-id @(:leaf-id sess)
            active-ids (set (map :id (session/get-branch sess)))
            tree (session/get-tree sess)]
        (if (empty? tree)
          (ui/chat-history-add-message! (:chat-history cs)
                                        {:role :assistant :content "Session is empty."})
          (let [filter-mode (atom :default)
                sl-ref (atom nil)
                build-items (fn []
                              (let [flatten-tree (fn flatten-tree [nodes depth]
                                                   (mapcat (fn [n]
                                                             (if (passes-tree-filter? n @filter-mode)
                                                               (let [prefix (apply str (repeat depth "  "))
                                                                     role-str (name (:role n))
                                                                     label (str prefix role-str ": " (:summary n)
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
                           (select-list/select-list-set-items! @sl-ref (build-items))
                           (select-list/select-list-set-header!
                            @sl-ref (str "Session tree" (tree-filter-mode-label @filter-mode)))
                           (tui/tui-request-render (:tui cs)))
                edit-label! (fn []
                              (when-let [sel (select-list/select-list-get-selected @sl-ref)]
                                (let [entry-id (:value sel)
                                      current (session/get-label sess entry-id)]
                                  (tui/tui-show-overlay
                                   (:tui cs)
                                   (dialogs/make-extension-input
                                    "Edit tree label"
                                    (fn [label]
                                      (tui/tui-hide-overlay (:tui cs))
                                      (let [label (str/trim label)]
                                        (session/set-label! sess entry-id
                                                            (when (seq label) label))
                                        (refresh!)))
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
                         (cond
                           (keys/matches-key? data (keys/ctrl "d"))
                           (do (reset! filter-mode :default) (refresh!) true)
                           (keys/matches-key? data (keys/ctrl "t"))
                           (do (reset! filter-mode (if (= :no-tools @filter-mode) :default :no-tools))
                               (refresh!) true)
                           (keys/matches-key? data (keys/ctrl "u"))
                           (do (reset! filter-mode (if (= :user-only @filter-mode) :default :user-only))
                               (refresh!) true)
                           (keys/matches-key? data (keys/ctrl "l"))
                           (do (reset! filter-mode (if (= :labeled-only @filter-mode) :default :labeled-only))
                               (refresh!) true)
                           (keys/matches-key? data (keys/ctrl "a"))
                           (do (reset! filter-mode (if (= :all @filter-mode) :default :all))
                               (refresh!) true)
                           (keys/matches-key? data (keys/ctrl "o"))
                           (do (cycle-filter! 1) true)
                           (keys/matches-key? data (keys/ctrl-shift "o"))
                           (do (cycle-filter! -1) true)
                           ;; shift+l edits the label (legacy terminals send a
                           ;; bare uppercase letter — pi: app.tree.editLabel)
                           (or (keys/matches-key? data (keys/shift "l"))
                               (keys/matches-key? data "L"))
                           (do (edit-label!) true)
                           :else false))
                on-select-fn (fn [_]
                               (when-let [sel (select-list/select-list-get-selected @sl-ref)]
                                 (let [entry (:entry sel)]
                                   (tui/tui-hide-overlay (:tui cs))
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
                items (build-items)
                sl (select-list/make-select-list items
                                                 :height (min (count items) 20)
                                                 :header "Session tree"
                                                 :on-key on-key
                                                 :on-select on-select-fn
                                                 :on-escape (fn []
                                                              (tui/tui-hide-overlay (:tui cs))
                                                              (tui/tui-request-render (:tui cs))))]
            (reset! sl-ref sl)
            (tui/tui-show-overlay (:tui cs) sl :width 70 :height (min (count items) 20))
            (tui/tui-request-render (:tui cs))))))))
