(ns kmet.app.ui.extension-dialogs
  "Extension dialogs — selector, input, and editor components hosted in the
   editor container while an extension ui.select / ui.input / ui.editor
   dialog is active (pi: ExtensionSelectorComponent / ExtensionInputComponent
   / ExtensionEditorComponent). Each dialog is framed with DynamicBorders
   and a bold accent title; input/editor dialogs implement IFocusable and
   forward focus to the inner component so the IME candidate window follows
   the cursor (pi: Focusable propagation convention)."
  (:require [clojure.string :as str]
            [kmet.tui.protocols :as protocols]
            [kmet.tui.theme :as theme]
            [kmet.tui.keybindings :as kb]
            [kmet.tui.components.container :as container]
            [kmet.tui.components.text :as text]
            [kmet.tui.components.spacer :as spacer]
            [kmet.tui.components.dynamic-border :as db]
            [kmet.tui.components.select-list :as select-list]
            [kmet.tui.components.input :as input]
            [kmet.tui.components.editor :as editor]))

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

(defrecord ExtensionSelectorDialog [container select-list focused?-atom]
  protocols/IComponent
  (render [this width] (protocols/render (:container this) width))
  (handle-input [this data] (protocols/handle-input (:select-list this) data))
  (invalidate [this] (protocols/invalidate (:container this)))
  protocols/IFocusable
  (focused [this] @(:focused?-atom this))
  (set-focused! [this val]
    (reset! (:focused?-atom this) val)
    (protocols/set-focused! (:select-list this) val)))

(defn make-extension-selector
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
    (map->ExtensionSelectorDialog
     {:container (frame th title sl :hint-keys [["tui.select.up" "navigate"]
                                                ["tui.select.down" "navigate"]
                                                ["tui.select.confirm" "select"]
                                                ["tui.select.cancel" "cancel"]])
      :select-list sl
      :focused?-atom (atom false)})))

;; ─── Input (pi: ExtensionInputComponent) ───────────────────────────────────

(defrecord ExtensionInputDialog [container input-comp focused?-atom]
  protocols/IComponent
  (render [this width] (protocols/render (:container this) width))
  (handle-input [this data] (protocols/handle-input (:input-comp this) data))
  (invalidate [this] (protocols/invalidate (:container this)))
  protocols/IFocusable
  (focused [this] @(:focused?-atom this))
  (set-focused! [this val]
    (reset! (:focused?-atom this) val)
    (protocols/set-focused! (:input-comp this) val)))

(defn make-extension-input
  "Create a one-line input dialog. TITLE — dialog title; ON-SUBMIT receives
   the entered string; ON-CANCEL fires on escape. TH — theme map."
  [title on-submit on-cancel th]
  (let [inp (input/make-input)
        _ (input/input-set-on-submit! inp on-submit)
        _ (input/input-set-on-escape! inp on-cancel)]
    (map->ExtensionInputDialog
     {:container (frame th title inp
                        :hint-keys [["tui.select.confirm" "submit"]
                                    ["tui.select.cancel" "cancel"]])
      :input-comp inp
      :focused?-atom (atom false)})))

;; ─── Editor (pi: ExtensionEditorComponent) ─────────────────────────────────

(defrecord ExtensionEditorDialog [container editor-comp focused?-atom]
  protocols/IComponent
  (render [this width] (protocols/render (:container this) width))
  (handle-input [this data] (protocols/handle-input (:editor-comp this) data))
  (invalidate [this] (protocols/invalidate (:container this)))
  protocols/IFocusable
  (focused [this] @(:focused?-atom this))
  (set-focused! [this val]
    (reset! (:focused?-atom this) val)
    (protocols/set-focused! (:editor-comp this) val)))

(defn make-extension-editor
  "Create a multi-line editor dialog. TITLE — dialog title; PREFILL — initial
   text; ON-SUBMIT receives the entered text; ON-CANCEL fires on escape
   (app.interrupt). TH — theme map; TERMINAL-ROWS — (fn [] rows) driving the
   editor's dynamic height (pi: Editor default, 30% of rows, min 5);
   ON-EXTERNAL-EDITOR — (fn [editor-comp]) invoked on ctrl+g (app.editor.external),
   nil disables (pi: ExtensionEditorComponent.handleOpenExternalEditor)."
  [title prefill on-submit on-cancel th terminal-rows & [on-external-editor]]
  (let [ed (editor/make-editor :padding-x 0
                               :terminal-rows terminal-rows
                               :border-fn (fn [c] (theme/fg th :dim c)))
        _ (editor/editor-set-text! ed (or prefill ""))
        _ (editor/editor-set-on-submit! ed on-submit)
        ;; pi: escape cancels the dialog (the app's interrupt action)
        _ (editor/editor-set-on-action! ed "app.interrupt" on-cancel)
        ;; pi: ctrl+g opens the external editor on the dialog's content
        _ (when on-external-editor
            (editor/editor-set-on-action! ed "app.editor.external"
                                          (fn [] (on-external-editor ed))))]
    (map->ExtensionEditorDialog
     {:container (frame th title ed
                        :hint-keys [["tui.select.confirm" "submit"]
                                    ["tui.input.newLine" "newline"]
                                    ["tui.select.cancel" "cancel"]
                                    ["app.editor.external" "external editor"]])
      :editor-comp ed
      :focused?-atom (atom false)})))
