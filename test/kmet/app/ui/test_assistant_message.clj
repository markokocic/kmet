(ns kmet.app.ui.test-assistant-message
  (:require [clojure.string :as str]
            [clojure.test :as t :refer [deftest is testing]]
            [kmet.tui.core :as core]
            [kmet.app.ui.assistant-message :as am]))

(defn- strip-ansi [s]
  (clojure.string/replace s #"\u001b\[[0-9;]*[a-zA-Z]" ""))

(deftest test-create
  (testing "create assistant message component"
    (let [c (am/make-assistant-message :text "hello")]
      (is (some? c)))))

(deftest test-render-text
  (testing "renders message text"
    (let [c (am/make-assistant-message :text "Hello world")
          lines (mapv strip-ansi (core/render c 40))]
      (is (some #(re-find #"Hello world" %) lines)))))

(deftest test-render-markdown-highlight
  (testing "assistant text renders markdown with syntax-highlighted fences"
    (let [c (am/make-assistant-message :text "```clojure\n(defn f [] 1)\n```")
          lines (core/render c 40)]
      (is (some #(.contains % "\u001b[38;2;86;156;214mdefn\u001b[39m") lines)
          "code fence keywords get syntax colors")
      (is (some #(re-find #"\(defn f \[\] 1\)" %) (mapv strip-ansi lines))
          "fence content renders as markdown, not raw backticks"))))

(deftest test-render-thinking
  (testing "renders thinking text in italic"
    (let [c (am/make-assistant-message :text "response" :thinking "reasoning...")
          rendered (core/render c 40)]
        ;; Should have italic ANSI codes
      (is (some #(re-find #"\u001b\[3m" %) rendered)
          "Thinking should be in italic")
      (is (some #(re-find #"reasoning" %) (mapv strip-ansi rendered))))))

(deftest test-hide-thinking
  (testing "when hidden, shows 'Thinking...' label instead of content"
    (let [c (am/make-assistant-message :text "response" :thinking "secret reasoning"
                                       :hide-thinking? true)
          plain (mapv strip-ansi (core/render c 40))]
      (is (not-any? #(re-find #"secret reasoning" %) plain)
          "Thinking content should be hidden")
      (is (some #(re-find #"Thinking" %) plain)
          "Should show Thinking... label"))))

(deftest test-custom-hidden-label
  (testing "set-hidden-label! replaces the hidden-thinking label (pi:
            setHiddenThinkingLabel)"
    (let [c (am/make-assistant-message :text "response" :thinking "secret"
                                       :hide-thinking? true)
          _ (am/assistant-message-set-hidden-label! c "Thoughts hidden")
          plain (mapv strip-ansi (core/render c 40))]
      (is (some #(re-find #"Thoughts hidden" %) plain)
          "custom label shown")
      (is (not-any? #(re-find #"Thinking\.\.\." %) plain)
          "default label replaced"))))

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
  (testing "empty message renders nothing (Pi-style: no content → no output)"
    (let [c (am/make-assistant-message)
          lines (core/render c 40)]
      (is (zero? (count lines)) "empty message renders []"))))

(deftest test-only-thinking
  (testing "message with only thinking renders correctly"
    (let [c (am/make-assistant-message :thinking "just thinking")
          plain (mapv strip-ansi (core/render c 40))]
      (is (some #(re-find #"just thinking" %) plain)))))

(deftest test-whitespace-only-renders-nothing
  (testing "whitespace-only text/thinking renders no lines (pi: content.text.trim() check)"
    (let [c (am/make-assistant-message :text "   \n  ")]
      (is (= [] (mapv strip-ansi (core/render c 40)))
          "no pad line, no content — the block is invisible"))))

(deftest test-text-trimmed
  (testing "leading/trailing whitespace is trimmed before wrap (pi: text.trim())"
    (let [c (am/make-assistant-message :text "  hello world  ")
          lines (mapv strip-ansi (core/render c 40))]
      (is (some #(re-find #"hello world" %) lines))
      (is (not-any? #(re-find #"^ {2}hello" %) lines)
          "no leading spaces survive"))))
