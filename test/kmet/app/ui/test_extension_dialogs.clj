(ns kmet.app.ui.test-extension-dialogs
  "Extension dialog component tests — selector, input, editor framing and
   IFocusable propagation (pi: ExtensionSelector/Input/EditorComponent)."
  (:require [clojure.string :as str]
            [clojure.test :as t :refer [deftest testing]]
            [kmet.tui.core :as core]
            [kmet.tui.keybindings :as kb]
            [kmet.tui.theme :as theme]
            [kmet.app.keybindings :as app-kb]
            [kmet.app.ui.extension-dialogs :as d]))

(defn- strip-ansi [s]
  (str/replace s #"\u001b\[[0-9;]*[a-zA-Z]" ""))

(defn- render-plain [comp width]
  (->> (core/render comp width) (mapv strip-ansi)))

(deftest test-selector-framing
  (testing "selector renders title, options, and border lines"
    (let [comp (d/make-extension-selector "Pick one" ["A" "B"]
                                          (fn [_] nil) (fn []) theme/dark-theme)
          lines (render-plain comp 40)]
      (t/is (some #(re-find #"Pick one" %) lines) "title shown")
      (t/is (some #(re-find #"A" %) lines) "option A shown")
      (t/is (some #(re-find #"B" %) lines) "option B shown")
      (t/is (some #(re-find #"─+" %) lines) "border line shown"))))

(deftest test-selector-select-and-cancel
  (testing "selecting fires on-select with the option; escape fires on-cancel"
    (let [selected (atom nil)
          cancelled (atom 0)
          comp (d/make-extension-selector "Pick" ["X" "Y"]
                                          #(reset! selected %)
                                          #(swap! cancelled inc)
                                          theme/dark-theme)]
      (core/handle-input comp "\u001b[B")  ;; down
      (core/handle-input comp "\r")        ;; enter
      (t/is (= "Y" @selected) "enter selects the highlighted option")
      (core/handle-input comp "\u001b")    ;; escape
      (t/is (= 1 @cancelled) "escape cancels"))))

(deftest test-input-dialog-focus-propagation
  (testing "ExtensionInputDialog implements IFocusable and forwards to the
            inner Input (pi: Focusable propagation for IME)"
    (let [comp (d/make-extension-input "Name" (fn [_] nil) (fn []) theme/dark-theme)]
      (t/is (satisfies? core/IFocusable comp))
      (t/is (false? (core/focused comp)))
      (core/set-focused! comp true)
      (t/is (true? (core/focused comp)))
      (t/is (true? (core/focused (:input-comp comp)))
            "inner input receives the focus flag"))))

(deftest test-input-dialog-submit
  (testing "typing + enter delivers the value"
    (let [result (atom nil)
          comp (d/make-extension-input "Name" #(reset! result %) (fn []) theme/dark-theme)]
      (core/set-focused! comp true)
      (doseq [c "abc"] (core/handle-input comp (str c)))
      (core/handle-input comp "\r")
      (t/is (= "abc" @result)))))

(deftest test-editor-dialog-focus-propagation
  (testing "ExtensionEditorDialog forwards focus to the inner editor"
    (let [comp (d/make-extension-editor "Title" nil (fn [_] nil) (fn [])
                                        theme/dark-theme (constantly 30))]
      (t/is (satisfies? core/IFocusable comp))
      (core/set-focused! comp true)
      (t/is (true? (core/focused (:editor-comp comp)))))))

(deftest test-editor-dialog-prefill-and-submit
  (testing "prefill is loaded and enter submits the text"
    (let [result (atom nil)
          comp (d/make-extension-editor "Edit" "hello" #(reset! result %)
                                        (fn []) theme/dark-theme (constantly 30))]
      (t/is (= "hello" (core/editor-get-text (:editor-comp comp))))
      (core/set-focused! comp true)
      (core/handle-input comp "\r")
      (t/is (= "hello" @result) "submit fires with the prefill text"))))

(deftest test-editor-dialog-external-editor
  (testing "ctrl+g dispatches app.editor.external to the on-external-editor
            handler with the dialog editor (pi: ExtensionEditorComponent)"
    (kb/set-global-keybindings! (app-kb/make-agent-keybindings-manager))
    (let [opened (atom nil)
          comp (d/make-extension-editor "Edit" "hello" (fn [_] nil) (fn [])
                                        theme/dark-theme (constantly 30)
                                        (fn [ed] (reset! opened ed)))]
      (core/set-focused! comp true)
      (core/handle-input comp "\u0007")  ;; ctrl+g (BEL)
      (t/is (identical? (:editor-comp comp) @opened)
            "handler receives the dialog editor"))
    (testing "no handler wired leaves the text untouched"
      (let [comp (d/make-extension-editor "Edit" "x" (fn [_] nil) (fn [])
                                          theme/dark-theme (constantly 30))]
        (core/set-focused! comp true)
        (core/handle-input comp "\u0007")
        (t/is (= "x" (core/editor-get-text (:editor-comp comp))))))))

(deftest test-editor-dialog-dynamic-height
  (testing "dialog editor height follows terminal rows (pi: 30% of rows, min 5)"
    (let [prefill (str/join "\n" (map #(str "line" %) (range 20)))
          ed-30 (:editor-comp (d/make-extension-editor
                               "Edit" prefill (fn [_] nil) (fn [])
                               theme/dark-theme (constantly 30)))
          ed-50 (:editor-comp (d/make-extension-editor
                               "Edit" prefill (fn [_] nil) (fn [])
                               theme/dark-theme (constantly 50)))]
      (t/is (= 11 (count (render-plain ed-30 40))) "rows 30 → 9 visible lines")
      (t/is (= 17 (count (render-plain ed-50 40))) "rows 50 → 15 visible lines"))))
