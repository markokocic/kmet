(ns kmet.agent.ui.test-chat-history
  (:require [clojure.test :as t :refer [deftest is testing]]
            [kmet.tui.core :as core]
            [kmet.agent.ui.chat-history :as ch]))

(defn- strip-ansi [s]
  (clojure.string/replace s #"\u001b\[[0-9;]*[a-zA-Z]" ""))

(defn- plain-lines [component width]
  "Render component and strip ANSI codes for easier testing."
  (mapv strip-ansi (core/render component width)))

(deftest test-create
  (testing "create chat history component"
    (let [ch (ch/make-chat-history)]
      (is (some? ch))
      (is (= [] (ch/chat-history-get-messages ch)))
      (is (= "" (ch/chat-history-get-streaming-text ch))))))

(deftest test-add-message
  (testing "add a user message"
    (let [ch (ch/make-chat-history)]
      (ch/chat-history-add-message! ch {:role :user :content "hello"})
      (is (= 1 (count (ch/chat-history-get-messages ch))))
      (is (= :user (:role (first (ch/chat-history-get-messages ch)))))
      (is (= "hello" (:content (first (ch/chat-history-get-messages ch))))))))

(deftest test-render-user
  (testing "render a user message with Pi-style user-message-bg box"
    (let [ch (ch/make-chat-history)]
      (ch/chat-history-add-message! ch {:role :user :content "Hello world"})
      (let [lines (plain-lines ch 40)]
        (is (pos? (count lines)))
        (is (some #(re-find #"Hello world" %) lines)
            "User message content should be visible")
        ;; Should not have old-style headers
        (is (not-any? #(re-find #"─── You" %) lines)
            "Should not have old-style '─── You' header")))))

(deftest test-render-assistant
  (testing "render an assistant message with Pi-style plain text"
    (let [ch (ch/make-chat-history)]
      (ch/chat-history-add-message! ch {:role :assistant :content "Hi there!"})
      (let [lines (plain-lines ch 40)]
        (is (pos? (count lines)))
        (is (some #(re-find #"Hi there!" %) lines)
            "Assistant message content should be visible")
        (is (not-any? #(re-find #"─── Assistant" %) lines)
            "Should not have old-style '─── Assistant' header")))))

(deftest test-render-tool
  (testing "render a tool message with Pi-style box"
    (let [ch (ch/make-chat-history)]
      (ch/chat-history-add-message! ch
        {:role :tool :name "my-tool" :content "file contents" :is-error false})
      (let [lines (plain-lines ch 40)]
        (is (pos? (count lines)))
        (is (some #(re-find #"my-tool" %) lines))
        (is (some #(re-find #"file contents" %) lines))))))

(deftest test-render-tool-error
  (testing "render a tool error message with Pi-style box"
    (let [ch (ch/make-chat-history)]
      (ch/chat-history-add-message! ch
        {:role :tool :name "my-tool" :content "command not found" :is-error true})
      (let [lines (plain-lines ch 40)]
        (is (pos? (count lines)))
        (is (some #(re-find #"my-tool" %) lines))
        (is (some #(re-find #"command not found" %) lines))))))

(deftest test-render-info
  (testing "render an info message with Pi-style custom-message-bg"
    (let [ch (ch/make-chat-history)]
      (ch/chat-history-set-info-msg! ch {:label "system" :content "Info message"})
      (ch/chat-history-add-message! ch {:role :user :content "hello"})
      (let [lines (plain-lines ch 40)]
        (is (some #(re-find #"system" %) lines)
            "Info label should be visible")
        (is (some #(re-find #"Info message" %) lines)
            "Info content should be visible")))))

(deftest test-streaming
  (testing "streaming text renders with Pi-style"
    (let [ch (ch/make-chat-history)]
      (ch/chat-history-append-streaming-text! ch "Hello")
      (ch/chat-history-append-thinking-text! ch "Thinking...")
      (let [lines (plain-lines ch 40)]
        (is (some #(re-find #"Hello" %) lines))
        (is (some #(re-find #"Thinking" %) lines))))))

(deftest test-append-streaming
  (testing "append streaming text"
    (let [ch (ch/make-chat-history)]
      (ch/chat-history-append-streaming-text! ch "Hello ")
      (ch/chat-history-append-streaming-text! ch "world")
      (is (= "Hello world" (ch/chat-history-get-streaming-text ch))))))

(deftest test-finalize-streaming
  (testing "finalize streaming text into a message"
    (let [ch (ch/make-chat-history)]
      (ch/chat-history-append-streaming-text! ch "Final message")
      (let [msg (ch/chat-history-finalize-streaming! ch)]
        (is (some? msg))
        ;; After finalize, streaming text cleared, message is in history
        (is (= "" (ch/chat-history-get-streaming-text ch)))
        (let [msgs (ch/chat-history-get-messages ch)]
          (is (= 1 (count msgs)))
          (is (= :assistant (:role (first msgs))))
          (is (= "Final message" (:content (first msgs)))))))))

(deftest test-finalize-empty
  (testing "finalize empty streaming returns nil"
    (let [ch (ch/make-chat-history)]
      (is (nil? (ch/chat-history-finalize-streaming! ch)))
      (is (= [] (ch/chat-history-get-messages ch))))))

(deftest test-clear
  (testing "clear all messages and info"
    (let [ch (ch/make-chat-history)]
      (ch/chat-history-add-message! ch {:role :user :content "hello"})
      (ch/chat-history-append-streaming-text! ch "thinking")
      (ch/chat-history-set-info-msg! ch {:label "test" :content "info"})
      (ch/chat-history-clear! ch)
      (is (= [] (ch/chat-history-get-messages ch)))
      (is (= "" (ch/chat-history-get-streaming-text ch))))))

(deftest test-tool-expanded-toggle
  (testing "toggle tool expanded state"
    (let [ch (ch/make-chat-history)]
      (ch/chat-history-add-message! ch {:role :tool :name "ls" :content "files"})
      (is (false? (ch/chat-history-get-tool-expanded ch)))
      (ch/chat-history-toggle-tool-expanded! ch)
      (is (true? (ch/chat-history-get-tool-expanded ch)))
      (ch/chat-history-toggle-tool-expanded! ch)
      (is (false? (ch/chat-history-get-tool-expanded ch))))))

(deftest test-thinking-hidden-toggle
  (testing "toggle thinking hidden state"
    (let [ch (ch/make-chat-history)]
      (ch/chat-history-add-message! ch {:role :assistant :content "hello" :thinking "thinking..."})
      (is (false? (ch/chat-history-get-thinking-hidden ch)))
      (ch/chat-history-toggle-thinking-hidden! ch)
      (is (true? (ch/chat-history-get-thinking-hidden ch)))
      (ch/chat-history-toggle-thinking-hidden! ch)
      (is (false? (ch/chat-history-get-thinking-hidden ch))))))

(deftest test-render-multiple
  (testing "multiple messages render"
    (let [ch (ch/make-chat-history)]
      (ch/chat-history-add-message! ch {:role :user :content "line1"})
      (ch/chat-history-add-message! ch {:role :user :content "line2"})
      (let [lines (core/render ch 40)]
        (is (pos? (count lines)))))))

(deftest test-multiple-messages
  (testing "multiple messages render in order"
    (let [ch (ch/make-chat-history)]
      (ch/chat-history-add-message! ch {:role :user :content "First"})
      (ch/chat-history-add-message! ch {:role :assistant :content "Second"})
      (ch/chat-history-add-message! ch {:role :user :content "Third"})
      (let [lines (plain-lines ch 40)]
        (is (some #(re-find #"First" %) lines))
        (is (some #(re-find #"Second" %) lines))
        (is (some #(re-find #"Third" %) lines))
        (let [first-idx (first (keep-indexed #(when (re-find #"First" %2) %1) lines))
              third-idx (first (keep-indexed #(when (re-find #"Third" %2) %1) lines))]
          (is (< first-idx third-idx) "Messages should maintain order"))))))

(deftest test-cache-invalidation
  (testing "cache invalidates on new message"
    (let [ch (ch/make-chat-history)]
      (ch/chat-history-add-message! ch {:role :user :content "original"})
      (let [lines1 (plain-lines ch 40)]
        (is (some #(re-find #"original" %) lines1)))
      (ch/chat-history-add-message! ch {:role :user :content "new"})
      (let [lines2 (plain-lines ch 40)]
        (is (some #(re-find #"new" %) lines2))))))

(deftest test-remove-last
  (testing "remove last message"
    (let [ch (ch/make-chat-history)]
      (ch/chat-history-add-message! ch {:role :user :content "first"})
      (ch/chat-history-add-message! ch {:role :user :content "second"})
      (is (= 2 (count (ch/chat-history-get-messages ch))))
      (ch/chat-history-remove-last! ch)
      (is (= 1 (count (ch/chat-history-get-messages ch))))
      (is (= "first" (:content (first (ch/chat-history-get-messages ch))))))))


