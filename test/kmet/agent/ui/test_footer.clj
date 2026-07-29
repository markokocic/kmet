(ns kmet.agent.ui.test-footer
  (:require [clojure.test :as t :refer [deftest is testing]]
            [kmet.tui.core :as core]
            [kmet.agent.ui.footer :as ft]))

(defn- strip-ansi [s]
  (clojure.string/replace s #"\u001b\[[0-9;]*[a-zA-Z]" ""))

(deftest test-create
  (testing "create footer component"
    (let [c (ft/make-footer :status "idle" :n-msgs 5)]
      (is (some? c)))))

(deftest test-render-shows-kmet
  (testing "renders kmet in the footer"
    (let [c (ft/make-footer :status "idle" :n-msgs 3)]
      (let [plain (mapv strip-ansi (core/render c 40))]
        (is (some #(re-find #"kmet" %) plain)
            "FooterComponent should show app name")
        (is (some #(re-find #"msgs:3" %) plain)
            "FooterComponent should show message count")))))

(deftest test-render-status
  (testing "renders status text"
    (let [c (ft/make-footer :status "● thinking" :n-msgs 2)]
      (let [plain (mapv strip-ansi (core/render c 40))]
        (is (some #(re-find #"thinking" %) plain))))))

(deftest test-set-status
  (testing "set-status! updates status"
    (let [c (ft/make-footer :status "idle" :n-msgs 0)]
      (ft/footer-set-status! c "● working")
      (let [plain (mapv strip-ansi (core/render c 40))]
        (is (some #(re-find #"working" %) plain))
        (is (not-any? #(re-find #"idle" %) plain))))))

(deftest test-set-n-msgs
  (testing "set-n-msgs! updates message count"
    (let [c (ft/make-footer :status "" :n-msgs 0)]
      (ft/footer-set-n-msgs! c 42)
      (let [plain (mapv strip-ansi (core/render c 40))]
        (is (some #(re-find #"msgs:42" %) plain))))))

(deftest test-empty-status
  (testing "empty status renders without extra space"
    (let [c (ft/make-footer :status "" :n-msgs 0)]
      (let [plain (mapv strip-ansi (core/render c 40))]
        (is (some #(re-find #"kmet" %) plain))))))

(deftest test-separator-line
  (testing "first line is a separator"
    (let [c (ft/make-footer :status "" :n-msgs 0)]
      (let [lines (core/render c 40)]
        (is (>= (count lines) 2) "FooterComponent should have at least 2 lines")
        (let [plain (mapv strip-ansi lines)]
          ;; First line should be dashes
          (is (re-find #"^─+" (first plain))))))))

(deftest test-wide-footer
  (testing "footer handles wide terminal"
    (let [c (ft/make-footer :status "idle" :n-msgs 100)]
      (let [lines (core/render c 120)]
        ;; Should not crash on wide terminal
        (is (pos? (count lines)))))))
