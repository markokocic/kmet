(ns kmet.tui.components.test-chat-history
  (:require [clojure.test :as t :refer [deftest is testing]]
            [kmet.tui.core :as core]
            [kmet.tui.components.chat-history :as ch]))

(defn- strip-ansi [s]
  (clojure.string/replace s #"\u001b\[[0-9;]*[a-zA-Z]" ""))

(defn- plain-lines [component width]
  "Render component and strip ANSI codes for easier testing."
  (mapv strip-ansi (core/render component width)))

(deftest test-create
  (testing "create chat history component"
    (let [ch (ch/make-chat-history :max-lines 20)]
      (is (some? ch))
      (is (= [] (ch/chat-history-get-messages ch)))
      (is (= "" (ch/chat-history-get-streaming-text ch))))))

(deftest test-add-message
  (testing "add a user message"
    (let [ch (ch/make-chat-history :max-lines 20)]
      (ch/chat-history-add-message! ch {:role :user :content "hello"})
      (is (= 1 (count (ch/chat-history-get-messages ch))))
      (is (= :user (:role (first (ch/chat-history-get-messages ch)))))
      (is (= "hello" (:content (first (ch/chat-history-get-messages ch))))))))

(deftest test-render-user
  (testing "render a user message"
    (let [ch (ch/make-chat-history :max-lines 20)]
      (ch/chat-history-add-message! ch {:role :user :content "Hello world"})
      (let [lines (plain-lines ch 40)]
        (is (pos? (count lines)))
        (is (some #(re-find #"─── You" %) lines))
        (is (some #(re-find #"Hello world" %) lines))))))

(deftest test-render-assistant
  (testing "render an assistant message"
    (let [ch (ch/make-chat-history :max-lines 20)]
      (ch/chat-history-add-message! ch {:role :assistant :content "Hi there!"})
      (let [lines (plain-lines ch 40)]
        (is (pos? (count lines)))
        (is (some #(re-find #"─── Assistant" %) lines))
        (is (some #(re-find #"Hi there!" %) lines))))))

(deftest test-render-tool
  (testing "render a tool message"
    (let [ch (ch/make-chat-history :max-lines 20)]
      (ch/chat-history-add-message! ch
        {:role :tool :name "read" :content "file contents" :is-error false})
      (let [lines (plain-lines ch 40)]
        (is (pos? (count lines)))
        (is (some #(re-find #"─── read" %) lines))
        (is (some #(re-find #"file contents" %) lines))))))

(deftest test-render-tool-error
  (testing "render a tool error message"
    (let [ch (ch/make-chat-history :max-lines 20)]
      (ch/chat-history-add-message! ch
        {:role :tool :name "bash" :content "command not found" :is-error true})
      (let [lines (core/render ch 40)
            plain (plain-lines ch 40)]
        (is (pos? (count lines)))
        (is (some #(re-find #"─── bash" %) plain))
        (is (some #(re-find #"command not found" %) plain))))))

(deftest test-streaming
  (testing "streaming text"
    (let [ch (ch/make-chat-history :max-lines 20)]
      (ch/chat-history-set-streaming-text! ch "Thinking...")
      (is (= "Thinking..." (ch/chat-history-get-streaming-text ch)))
      (let [lines (plain-lines ch 40)]
        (is (some #(re-find #"Thinking" %) lines))
        (is (some #(re-find #"Assistant" %) lines))))))

(deftest test-append-streaming
  (testing "append streaming text"
    (let [ch (ch/make-chat-history :max-lines 20)]
      (ch/chat-history-append-streaming-text! ch "Hello ")
      (ch/chat-history-append-streaming-text! ch "world")
      (is (= "Hello world" (ch/chat-history-get-streaming-text ch))))))

(deftest test-finalize-streaming
  (testing "finalize streaming text into a message"
    (let [ch (ch/make-chat-history :max-lines 20)]
      (ch/chat-history-append-streaming-text! ch "Final message")
      (let [msg (ch/chat-history-finalize-streaming! ch)]
        (is (some? msg))
        (is (= :assistant (:role msg)))
        (is (= "Final message" (:content msg)))
        (is (= "" (ch/chat-history-get-streaming-text ch)))
        (is (= 1 (count (ch/chat-history-get-messages ch))))))))

(deftest test-finalize-empty
  (testing "finalize empty streaming returns nil"
    (let [ch (ch/make-chat-history :max-lines 20)]
      (is (nil? (ch/chat-history-finalize-streaming! ch)))
      (is (= [] (ch/chat-history-get-messages ch))))))

(deftest test-clear
  (testing "clear all messages"
    (let [ch (ch/make-chat-history :max-lines 20)]
      (ch/chat-history-add-message! ch {:role :user :content "hello"})
      (ch/chat-history-set-streaming-text! ch "thinking")
      (ch/chat-history-clear! ch)
      (is (= [] (ch/chat-history-get-messages ch)))
      (is (= "" (ch/chat-history-get-streaming-text ch))))))

(deftest test-max-lines
  (testing "max-lines limits visible output"
    (let [ch (ch/make-chat-history :max-lines 3)]
      (ch/chat-history-add-message! ch {:role :user :content "line1"})
      (ch/chat-history-add-message! ch {:role :user :content "line2"})
      (let [lines (core/render ch 40)]
        ;; Each message has header + content lines, with max-lines=3 should be limited
        (is (<= (count lines) 5) (str "Got " (count lines) " lines"))))))  ;; header + wrap + some padding

(deftest test-set-max-lines
  (testing "set max-lines dynamically"
    (let [ch (ch/make-chat-history :max-lines 5)]
      (ch/chat-history-set-max-lines! ch 10)
      ;; Can't easily test the effect without rendering, but should not crash
      (ch/chat-history-add-message! ch {:role :user :content "test"})
      (let [lines (core/render ch 40)]
        (is (pos? (count lines)))))))

(deftest test-multiple-messages
  (testing "multiple messages render in order"
    (let [ch (ch/make-chat-history :max-lines 20)]
      (ch/chat-history-add-message! ch {:role :user :content "First"})
      (ch/chat-history-add-message! ch {:role :assistant :content "Second"})
      (ch/chat-history-add-message! ch {:role :user :content "Third"})
      (let [lines (plain-lines ch 40)]
        (is (some #(re-find #"First" %) lines))
        (is (some #(re-find #"Second" %) lines))
        (is (some #(re-find #"Third" %) lines))
        ;; Check order — find first and third positions
        (let [first-idx (first (keep-indexed #(when (re-find #"First" %2) %1) lines))
              third-idx (first (keep-indexed #(when (re-find #"Third" %2) %1) lines))]
          (is (< first-idx third-idx) "Messages should maintain order"))))))

(deftest test-cache-invalidation
  (testing "cache invalidates on new message"
    (let [ch (ch/make-chat-history :max-lines 20)]
      (ch/chat-history-add-message! ch {:role :user :content "original"})
      (let [lines1 (plain-lines ch 40)]
        (is (some #(re-find #"original" %) lines1)))
      (ch/chat-history-add-message! ch {:role :user :content "new"})
      (let [lines2 (plain-lines ch 40)]
        (is (some #(re-find #"new" %) lines2))))))
