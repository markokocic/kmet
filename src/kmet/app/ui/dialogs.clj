(ns kmet.app.ui.dialogs
  "The app's dialog kit — selector and input prompt components (framed
   with DynamicBorders and a bold accent title; the input dialog
   implements IFocusable and forwards focus to the inner component so the
   IME candidate window follows the cursor). Used by the
   interactive mode's OAuth/API-key/auth-method prompts and the /tree
   flow. The app's own kit: extensions build their own dialogs with
   kmet.tui.* and mount them via ui-custom (kmet.extension docstring)."
  (:require [clojure.string :as str]
            [kmet.tui.macros :refer [defcomponent]]
            [kmet.tui.protocols :as protocols]
            [kmet.tui.theme :as theme]
            [kmet.tui.keybindings :as kb]
            [kmet.tui.components.container :as container]
            [kmet.tui.components.text :as text]
            [kmet.tui.components.spacer :as spacer]
            [kmet.tui.components.dynamic-border :as db]
            [kmet.tui.components.select-list :as select-list]
            [kmet.tui.components.input :as input]))

;; ─── Shared frame helpers ──────────────────────────────────────────────────

(defn- title-text
  "Bold accent title line (pi: theme.fg('accent', theme.bold(title)))."
  [th title]
  (text/make-text (theme/fg th :accent (theme/bold title)) 1 0))

(defn- hint-text
  "Dim keybinding hint line built from the global keybindings
   (pi: keyHint/rawKeyHint)."
  [th & [key-ids]]
  (let [kmgr (kb/get-global-keybindings)
        hint (str/join " • "
                       (keep (fn [[k desc]]
                               (when-let [ktext (kb/key-text kmgr k)]
                                 (str ktext " " desc)))
                             key-ids))]
    (text/make-text (theme/fg th :dim hint) 1 0)))

(defn- frame
  "Container framing the CONTENT component with DynamicBorder + title."
  [th title content & [{:keys [hint-keys]}]]
  (let [c (container/make-container)]
    (container/container-add-child c (db/make-dynamic-border #(theme/fg th :accent %)))
    (container/container-add-child c (spacer/make-spacer 1))
    (container/container-add-child c (title-text th title))
    (container/container-add-child c (spacer/make-spacer 1))
    (container/container-add-child c content)
    (container/container-add-child c (spacer/make-spacer 1))
    (container/container-add-child c (hint-text th hint-keys))
    (container/container-add-child c (spacer/make-spacer 1))
    (container/container-add-child c (db/make-dynamic-border #(theme/fg th :accent %)))
    c))

;; ─── Selector (pi: ExtensionSelectorComponent) ─────────────────────────────

(defcomponent SelectorDialog nil [container select-list focused?-atom]
  (render [this width] (protocols/render (:container this) width))
  (handle-input [this data] (protocols/handle-input (:select-list this) data))
  (invalidate [this] (protocols/invalidate (:container this))))

(extend-type SelectorDialog
  protocols/IFocusable
  (focused [this] @(:focused?-atom this))
  (set-focused! [this val]
    (reset! (:focused?-atom this) val)
    (protocols/set-focused! (:select-list this) val)))

(defn make-selector-dialog
  "Create a selector dialog over a SelectList.
   TITLE — dialog title; OPTIONS — vector of strings; ON-SELECT receives
   the chosen string; ON-CANCEL fires on escape. TH — theme map."
  [title options on-select on-cancel th]
  (let [items (mapv (fn [o] {:value o :label o}) options)
        sl (select-list/make-select-list
            items
            :height (min (count options) 10)
            :theme (theme/get-select-list-theme th)
            :on-select (fn [item] (on-select (:label item)))
            :on-escape on-cancel)]
    (map->SelectorDialog
     {:container (frame th title sl :hint-keys [["tui.select.up" "navigate"]
                                                ["tui.select.down" "navigate"]
                                                ["tui.select.confirm" "select"]
                                                ["tui.select.cancel" "cancel"]])
      :select-list sl
      :focused?-atom (atom false)})))

;; ─── Input (pi: ExtensionInputComponent) ───────────────────────────────────

(defcomponent InputDialog nil [container input-comp focused?-atom]
  (render [this width] (protocols/render (:container this) width))
  (handle-input [this data] (protocols/handle-input (:input-comp this) data))
  (invalidate [this] (protocols/invalidate (:container this))))

(extend-type InputDialog
  protocols/IFocusable
  (focused [this] @(:focused?-atom this))
  (set-focused! [this val]
    (reset! (:focused?-atom this) val)
    (protocols/set-focused! (:input-comp this) val)))

(defn make-input-dialog
  "Create a one-line input dialog. TITLE — dialog title; ON-SUBMIT receives
   the entered string; ON-CANCEL fires on escape. TH — theme map.
   PREFILL — optional initial text (default \"\")."
  [title on-submit on-cancel th & [prefill]]
  (let [inp (input/make-input)
        _ (when (seq prefill)
            (input/input-set-value! inp prefill)
            ;; pi: LabelInput places the cursor after the prefilled text
            (reset! (:cursor-atom inp) (count prefill)))
        _ (input/input-set-on-submit! inp on-submit)
        _ (input/input-set-on-escape! inp on-cancel)]
    (map->InputDialog
     {:container (frame th title inp
                        :hint-keys [["tui.select.confirm" "submit"]
                                    ["tui.select.cancel" "cancel"]])
      :input-comp inp
      :focused?-atom (atom false)})))
