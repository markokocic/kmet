(ns kmet.tui.components.test-assistant-message
  (:require [clojure.test :as t :refer [deftest is testing]]
            [kmet.tui.core :as core]
            [kmet.tui.components.assistant-message :as am]))

(defn- strip-ansi [s]
  (clojure.string/replace s #"\u001b\[[0-9;]*[a-zA-Z]" ""))

(deftest test-create
  (testing "create assistant message component"
    (let [c (am/make-assistant-message :text "hello")]
      (is (some? c)))))

(deftest test-render-text
  (testing "renders message text"
    (let [c (am/make-assistant-message :text "Hello world" :finalized? true)]
      (let [lines (mapv strip-ansi (core/render c 40))]
        (is (some #(re-find #"Hello world" %) lines))))))

(deftest test-render-thinking
  (testing "renders thinking text in italic"
    (let [c (am/make-assistant-message :text "response" :thinking "reasoning...")]
      (let [rendered (core/render c 40)]
        ;; Should have italic ANSI codes
        (is (some #(re-find #"\u001b\[3m" %) rendered)
            "Thinking should be in italic")
        (is (some #(re-find #"reasoning" %) (mapv strip-ansi rendered)))))))

(deftest test-hide-thinking
  (testing "when hidden, shows 'Thinking...' label instead of content"
    (let [c (am/make-assistant-message :text "response" :thinking "secret reasoning"
                                       :hide-thinking? true)]
      (let [plain (mapv strip-ansi (core/render c 40))]
        (is (not-any? #(re-find #"secret reasoning" %) plain)
            "Thinking content should be hidden")
        (is (some #(re-find #"Thinking" %) plain)
            "Should show Thinking... label")))))

(deftest test-append-text
  (testing "append-text! updates content during streaming"
    (let [c (am/make-assistant-message)]
      (am/assistant-message-append-text! c "Hello ")
      (am/assistant-message-append-text! c "world")
      (is (= "Hello world" (am/assistant-message-get-text c))))))

(deftest test-append-thinking
  (testing "append-thinking! updates thinking during streaming"
    (let [c (am/make-assistant-message)]
      (am/assistant-message-append-thinking! c "step1 ")
      (am/assistant-message-append-thinking! c "step2")
      (is (= "step1 step2" (am/assistant-message-get-thinking c))))))

(deftest test-finalize-removes-cursor
  (testing "finalize removes the cursor indicator"
    (let [c (am/make-assistant-message :text "hello")]
      ;; Before finalize: should have cursor
      (let [before (core/render c 40)]
        (is (some #(re-find #"▍" %) before)
            "Streaming message should have cursor"))
      (am/assistant-message-finalize! c)
      ;; After finalize: cursor gone
      (let [after (core/render c 40)]
        (is (not-any? #(re-find #"▍" %) after)
            "Finalized message should not have cursor")))))

(deftest test-set-text
  (testing "set-text! replaces content"
    (let [c (am/make-assistant-message :text "old")]
      (am/assistant-message-set-text! c "new")
      (is (= "new" (am/assistant-message-get-text c))))))

(deftest test-set-thinking
  (testing "set-thinking! replaces thinking"
    (let [c (am/make-assistant-message :thinking "old")]
      (am/assistant-message-set-thinking! c "new")
      (is (= "new" (am/assistant-message-get-thinking c))))))

(deftest test-set-hide-thinking
  (testing "set-hide-thinking! toggles the label"
    (let [c (am/make-assistant-message :thinking "reasoning..." :hide-thinking? true)]
      (am/assistant-message-set-hide-thinking! c false)
      (let [plain (mapv strip-ansi (core/render c 40))]
        (is (some #(re-find #"reasoning" %) plain)
            "Unhiding should show thinking content")))))

(deftest test-empty-message
  (testing "empty message renders nothing"
    (let [c (am/make-assistant-message :finalized? true)]
      (is (empty? (core/render c 40))))))

(deftest test-only-thinking
  (testing "message with only thinking renders correctly"
    (let [c (am/make-assistant-message :thinking "just thinking")]
      (let [plain (mapv strip-ansi (core/render c 40))]
        (is (some #(re-find #"just thinking" %) plain))))))

(deftest test-cursor-shown-during-streaming
  (testing "cursor shown when there's content during streaming"
    (let [c (am/make-assistant-message :text "partial")]
      (is (some #(re-find #"▍" %) (core/render c 40))))))

(deftest test-no-cursor-for-empty-streaming
  (testing "no cursor when streaming is empty"
    (let [c (am/make-assistant-message)]
      (is (not-any? #(re-find #"▍" %) (core/render c 40))
          "Empty streaming should not show cursor"))))
