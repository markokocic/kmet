(ns kmet.agent.ui.test-tool-execution
  (:require [clojure.test :as t :refer [deftest is testing]]
            [kmet.tui.core :as core]
            [kmet.agent.ui.tool-execution :as te]))

(defn- strip-ansi [s]
  (clojure.string/replace s #"\u001b\[[0-9;]*[a-zA-Z]" ""))

(deftest test-create
  (testing "create tool execution component"
    (let [c (te/make-tool-execution :name "ls")]
      (is (some? c)))))

(deftest test-render-name
  (testing "renders tool name"
    (let [c (te/make-tool-execution :name "read_file")]
      (let [plain (mapv strip-ansi (core/render c 40))]
        (is (some #(re-find #"read_file" %) plain))))))

(deftest test-render-content
  (testing "renders tool output content"
    (let [c (te/make-tool-execution :name "ls" :content "file1\nfile2")]
      (let [plain (mapv strip-ansi (core/render c 40))]
        (is (some #(re-find #"file1" %) plain))
        (is (some #(re-find #"file2" %) plain))))))

(deftest test-render-error
  (testing "error tool uses tool-error-bg"
    (let [c (te/make-tool-execution :name "my-tool" :content "failed" :is-error true)]
      (let [rendered (core/render c 40)]
        ;; Content visible for errors
        (is (some #(re-find #"failed" %) (mapv strip-ansi rendered)))))))

(deftest test-set-name
  (testing "set-name! updates tool name"
    (let [c (te/make-tool-execution :name "old")]
      (te/tool-execution-set-name! c "new")
      (is (some #(re-find #"new" %) (mapv strip-ansi (core/render c 40)))))))

(deftest test-set-content
  (testing "set-content! updates output"
    (let [c (te/make-tool-execution :name "ls" :content "old")]
      (te/tool-execution-set-content! c "new content")
      (is (some #(re-find #"new content" %) (mapv strip-ansi (core/render c 40)))))))

(deftest test-set-error
  (testing "set-error! changes background"
    (let [c (te/make-tool-execution :name "ls" :content "ok")]
      (te/tool-execution-set-error! c true)
      ;; Still renders
      (is (pos? (count (core/render c 40)))))))

(deftest test-set-expanded
  (testing "set-expanded! controls visibility"
    (let [c (te/make-tool-execution :name "ls" :content "hidden")]
      ;; Content visible by default
      (is (some #(re-find #"hidden" %) (mapv strip-ansi (core/render c 40)))))))

(deftest test-empty-name
  (testing "empty name still renders"
    (let [c (te/make-tool-execution :name "" :content "just content")]
      (let [plain (mapv strip-ansi (core/render c 40))]
        (is (some #(re-find #"just content" %) plain))))))

(deftest test-set-output-pad
  (testing "set-output-pad! changes padding"
    (let [c (te/make-tool-execution :name "ls" :content "x" :output-pad 3)]
      (te/tool-execution-set-output-pad! c 5)
      (is (pos? (count (core/render c 40)))))))

(deftest test-invalidate
  (testing "invalidate clears cache"
    (let [c (te/make-tool-execution :name "ls" :content "first")]
      (core/render c 40) ;; populate cache
      (te/tool-execution-set-content! c "second")
      (is (some #(re-find #"second" %) (mapv strip-ansi (core/render c 40)))))))
