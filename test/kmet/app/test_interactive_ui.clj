(ns kmet.app.test-interactive-ui
  "Tests for the interactive-mode extension UI helpers: the autocomplete
   factory wrapper chain (pi: setupAutocompleteProvider) and the custom
   editor duck-typed transfer (pi: setCustomEditorComponent)."
  (:require [clojure.test :as t :refer [deftest testing]]
            [kmet.tui.autocomplete :as ac]
            [kmet.tui.components.editor :as editor]
            [kmet.modes.interactive :as inter]))

(defn- transfer-editor! [app-ed custom-ed kb]
  ((var inter/transfer-editor!) app-ed custom-ed kb))

(defn- normalize [x]
  ((var inter/normalize-autocomplete-provider) x))

(deftest test-normalize-autocomplete-provider-protocol
  (testing "an AutocompleteProvider passes through unchanged"
    (let [p (ac/make-combined-provider :commands-fn (constantly []))]
      (t/is (identical? p (normalize p))))))

(deftest test-normalize-autocomplete-provider-map
  (testing "a duck-typed map provider is adapted to the protocol"
    (let [p (normalize {:get-suggestions (fn [_state] {:items [{:value "x" :label "X"}]
                                                       :prefix "x"})
                        :get-trigger-characters ["@"]})]
      (t/is (satisfies? ac/AutocompleteProvider p))
      (let [res (ac/get-suggestions p ["xa"] 0 1 {})]
        (t/is (= "x" (:prefix res)))
        (t/is (= "X" (-> res :items first :label))))
      (t/is (= ["@"] (ac/get-trigger-characters p)))
      ;; default apply-completion replaces the prefix
      (let [st (ac/apply-completion p ["xa"] 0 1 {:value "xyz" :label "xyz"} "x")]
        (t/is (= "xyza" (nth (:lines st) 0)))
        (t/is (= 3 (:cursor-col st)))))))

(deftest test-normalize-autocomplete-provider-nil
  (testing "non-provider values normalize to nil"
    (t/is (nil? (normalize nil)))
    (t/is (nil? (normalize 42)))))

(deftest test-transfer-editor!
  (testing "transfer-editor! copies text callbacks, appearance, provider,
            and action handlers onto a custom editor (pi duck-typing)"
    (let [app-ed (editor/make-editor)
          custom (editor/make-editor)
          _ (editor/editor-set-on-submit! app-ed (fn [t] (println t)))
          _ (editor/editor-set-on-action! app-ed "app.interrupt" (fn []))
          _ (editor/editor-set-autocomplete-provider!
             app-ed (ac/make-combined-provider :commands-fn (constantly [])))]
      (transfer-editor! app-ed custom nil)
      (t/is (identical? @(:on-submit app-ed) @(:on-submit custom))
            "on-submit handler copied")
      (t/is (contains? @(:action-handlers custom) "app.interrupt")
            "app action handlers copied")
      (t/is (some? @(:autocomplete-provider custom))
            "autocomplete provider copied"))))

(deftest test-transfer-editor!-non-editor
  (testing "transfer to a non-editor component is a no-op"
    (let [app-ed (editor/make-editor)
          plain {:render (fn [_] [""])}]
      (t/is (nil? (transfer-editor! app-ed plain nil)))
      (t/is (= [:render] (keys plain)) "plain map untouched"))))
