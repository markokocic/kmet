(ns kmet.agent.ui.test-custom-message
  (:require [clojure.test :as t :refer [deftest is testing]]
            [kmet.tui.core :as core]
            [kmet.agent.ui.custom-message :as cm]))

(defn- strip-ansi [s]
  (clojure.string/replace s #"\u001b\[[0-9;]*[a-zA-Z]" ""))

(deftest test-create
  (testing "create custom message component"
    (let [c (cm/make-custom-message :label "info" :content "hello")]
      (is (some? c)))))

(deftest test-render-label
  (testing "renders label in brackets"
    (let [c (cm/make-custom-message :label "system" :content "message")]
      (let [plain (mapv strip-ansi (core/render c 40))]
        (is (some #(re-find #"\[system\]" %) plain)
            "Label should show as [system]")))))

(deftest test-render-content
  (testing "renders content text"
    (let [c (cm/make-custom-message :label "info" :content "Welcome to kmet")]
      (let [plain (mapv strip-ansi (core/render c 40))]
        (is (some #(re-find #"Welcome to kmet" %) plain))))))

(deftest test-no-label
  (testing "renders without label"
    (let [c (cm/make-custom-message :content "just content")]
      (let [plain (mapv strip-ansi (core/render c 40))]
        (is (some #(re-find #"just content" %) plain))
        (is (not-any? #(re-find #"\[" %) plain)
            "No label should mean no brackets")))))

(deftest test-empty-content
  (testing "empty content still renders box padding"
    (let [c (cm/make-custom-message :label "test")]
      ;; Even with empty content, the box padding lines render
      (is (pos? (count (core/render c 40)))))))

(deftest test-set-label
  (testing "set-label! updates label"
    (let [c (cm/make-custom-message :label "old" :content "text")]
      (cm/custom-message-set-label! c "new")
      (let [plain (mapv strip-ansi (core/render c 40))]
        (is (some #(re-find #"\[new\]" %) plain))
        (is (not-any? #(re-find #"\[old\]" %) plain))))))

(deftest test-set-content
  (testing "set-content! updates content"
    (let [c (cm/make-custom-message :label "info" :content "old")]
      (cm/custom-message-set-content! c "new content")
      (let [plain (mapv strip-ansi (core/render c 40))]
        (is (some #(re-find #"new content" %) plain))))))

(deftest test-set-theme
  (testing "set-theme! does not crash"
    (let [c (cm/make-custom-message :label "test" :content "test")]
      (cm/custom-message-set-theme! c (kmet.tui.theme/make-theme {:name "dark"}))
      (is (pos? (count (core/render c 40)))))))

(deftest test-set-output-pad
  (testing "set-output-pad! changes padding"
    (let [c (cm/make-custom-message :label "test" :content "test" :output-pad 2)]
      (cm/custom-message-set-output-pad! c 4)
      (is (pos? (count (core/render c 40)))))))

(deftest test-background
  (testing "renders with custom-message-bg background"
    (let [c (cm/make-custom-message :label "info" :content "test")]
      (let [lines (core/render c 40)]
        (is (every? #(re-find #"\u001b\[48" %) lines)
            "All lines should have background ANSI codes")))))

(deftest test-long-content-wraps
  (testing "long content wraps to fit width"
    (let [c (cm/make-custom-message :label "info"
                                    :content (apply str (repeat 200 "x")))]
      (let [lines (core/render c 30)]
        (is (> (count lines) 3) "Long content should wrap to multiple lines")))))
