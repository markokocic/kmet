(ns kmet.app.ui.test-user-message
  (:require [clojure.string :as str]
            [clojure.test :as t :refer [deftest is testing]]
            [kmet.tui.theme :as theme]
            [kmet.tui.core :as core]
            [kmet.app.ui.user-message :as um]))

(defn- strip-ansi [s]
  (clojure.string/replace s #"\u001b\[[0-9;]*[a-zA-Z]" ""))

(deftest test-create
  (testing "create user message component"
    (let [c (um/make-user-message :text "hello")]
      (is (some? c))
      (is (satisfies? core/IComponent c)))))

(deftest test-render-shows-content
  (testing "render shows the message text"
    (let [c (um/make-user-message :text "Hello world")
          lines (core/render c 40)]
      (is (pos? (count lines)))
      (is (some #(re-find #"Hello world" %) (mapv strip-ansi lines)))
      "User message content should be visible")))

(deftest test-render-no-old-header
  (testing "no old-style ─── You header"
    (let [c (um/make-user-message :text "test")]
      (is (not-any? #(re-find #"───" %) (mapv strip-ansi (core/render c 40)))))))

(deftest test-render-background
  (testing "renders with user-message-bg background"
    (let [c (um/make-user-message :text "test")
          lines (core/render c 40)]
      (is (every? #(re-find #"\u001b\[48" %) lines)
          "All lines should have background ANSI codes"))))

(deftest test-set-text
  (testing "set-text! updates content"
    (let [c (um/make-user-message :text "original")]
      (is (some #(re-find #"original" %) (mapv strip-ansi (core/render c 40))))
      (um/user-message-set-text! c "updated")
      (is (some #(re-find #"updated" %) (mapv strip-ansi (core/render c 40)))))))

(deftest test-set-theme
  (testing "set-theme! does not crash"
    (let [c (um/make-user-message :text "hello")]
      (um/user-message-set-theme! c (kmet.tui.theme/make-theme {:name "dark"}))
      (is (pos? (count (core/render c 40)))))))

(deftest test-set-output-pad
  (testing "set-output-pad! changes padding"
    (let [c (um/make-user-message :text "hello" :output-pad 3)]
      (um/user-message-set-output-pad! c 5)
      (is (pos? (count (core/render c 40)))))))

(deftest test-empty-text
  (testing "empty text renders lines (box padding)"
    (let [c (um/make-user-message :text "")]
      (is (pos? (count (core/render c 40)))))))

(deftest test-long-text-wraps
  (testing "long text wraps to fit width"
    (let [c (um/make-user-message :text (apply str (repeat 200 "x")))
          lines (core/render c 40)]
      (is (> (count lines) 3) "Long text should wrap to multiple lines"))))
