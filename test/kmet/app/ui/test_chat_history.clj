(ns kmet.app.ui.test-chat-history
  (:require [clojure.string :as str]
            [clojure.test :as t :refer [deftest is testing]]
            [kmet.tui.core :as core]
            [kmet.app.ui :as ui]
            [kmet.app.ui.chat-history :as ch]))

(defn- strip-ansi [s]
  (clojure.string/replace s #"\u001b\[[0-9;]*[a-zA-Z]" ""))

(defn- plain-lines
  "Render component and strip ANSI codes for easier testing."
  [component width]
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

(deftest test-remove-streaming-placeholder
  (testing "placeholder is the last entry — removed, streaming state cleared"
    (let [ch (ch/make-chat-history)]
      (ch/chat-history-add-message! ch {:role :user :content "initial"})
      (ch/chat-history-start-streaming! ch)
      (is (true? (ch/chat-history-remove-streaming-placeholder! ch)))
      (is (nil? @(:streaming-atom ch)))
      (is (= ["initial"] (map :content (ch/chat-history-get-messages ch))))))

  (testing "consumed steering message sits after the placeholder — it survives"
    ;; on-agent-error / handle-cancel remove the empty placeholder when a
    ;; run dies before the next assistant message-start. A drained
    ;; steering/follow-up user message (or a tool execution) is appended
    ;; AFTER the placeholder, so position-based removal would delete the
    ;; user's message instead (pi: agent_end removes the streamingComponent
    ;; by reference).
    (let [ch (ch/make-chat-history)]
      (ch/chat-history-add-message! ch {:role :user :content "initial"})
      (ch/chat-history-start-streaming! ch)
      (ch/chat-history-add-message! ch {:role :user :content "STEERED"})
      (ch/chat-history-add-message! ch {:role :user :content "TOOL"})
      (is (true? (ch/chat-history-remove-streaming-placeholder! ch)))
      (is (nil? @(:streaming-atom ch)))
      (is (= ["initial" "STEERED" "TOOL"]
             (map :content (ch/chat-history-get-messages ch))))))

  (testing "trailing status lines are dropped with the placeholder"
    (let [ch (ch/make-chat-history)]
      (ch/chat-history-start-streaming! ch)
      (ch/chat-history-show-status! ch "some status")
      (is (true? (ch/chat-history-remove-streaming-placeholder! ch)))
      (is (empty? (ch/chat-history-get-messages ch)))))

  (testing "no streaming in progress — no-op"
    (let [ch (ch/make-chat-history)]
      (ch/chat-history-add-message! ch {:role :user :content "initial"})
      (is (nil? (ch/chat-history-remove-streaming-placeholder! ch)))
      (is (= 1 (count (ch/chat-history-get-messages ch)))))))

(deftest test-rebuild
  (testing "rebuild replaces all messages and preserves the info banner"
    (let [ch (ch/make-chat-history)
          _ (ch/chat-history-set-info-msg! ch {:label "banner" :content "info text"})]
      (ch/chat-history-add-message! ch {:role :user :content "old 1"})
      (ch/chat-history-add-message! ch {:role :assistant :content "old 2"})
      (ch/chat-history-rebuild! ch [{:role :user :content "new 1"}
                                    {:role :assistant :content "new 2"}])
      ;; old messages gone, new ones present
      (let [lines (plain-lines ch 40)]
        (is (some #(re-find #"new 1" %) lines) "new user message rendered")
        (is (some #(re-find #"new 2" %) lines) "new assistant message rendered")
        (is (not-any? #(re-find #"old 1|old 2" %) lines) "old messages removed")
        (is (some #(re-find #"info text" %) lines) "info banner preserved"))))

  (testing "rebuild with no info banner"
    (let [ch (ch/make-chat-history)]
      (ch/chat-history-add-message! ch {:role :user :content "old"})
      (ch/chat-history-rebuild! ch [{:role :user :content "only"}])
      (is (= [{:role :user :content "only"}]
             (ch/chat-history-get-messages ch))))))

(deftest test-insert-before-streaming
  (testing "injected :info message lands above the streaming placeholder"
    (let [ch (ch/make-chat-history)]
      (ch/chat-history-add-message! ch {:role :user :content "hi"})
      (ch/chat-history-start-streaming! ch)
      (ch/chat-history-append-streaming-text! ch "response")
      (ch/chat-history-insert-before-streaming! ch
                                                {:role :info :label "ext" :content "injected note"})
      (let [msgs (ch/chat-history-get-messages ch)]
        (is (= 3 (count msgs)))
        (is (= :user (:role (nth msgs 0))))
        (is (= :info (:role (nth msgs 1))) "info box sits above the response")
        (is (= :assistant (:role (nth msgs 2))))))))

(deftest test-insert-before-streaming-appends-when-no-streaming
  (testing "falls back to appending when no streaming placeholder exists"
    (let [ch (ch/make-chat-history)]
      (ch/chat-history-add-message! ch {:role :user :content "hi"})
      (ch/chat-history-insert-before-streaming! ch
                                                {:role :info :label "ext" :content "note"})
      (let [msgs (ch/chat-history-get-messages ch)]
        (is (= 2 (count msgs)))
        (is (= :info (:role (nth msgs 1))))))))

(deftest test-user-message-block-content
  (testing "block-vector user content renders as plain text, not a literal vector"
    (let [ch (ch/make-chat-history)]
      (ch/chat-history-add-message! ch
                                    {:role :user :content [{:type :text :text "hi there"}]})
      (let [lines (plain-lines ch 40)]
        (is (some #(re-find #"hi there" %) lines))
        (is (not-any? #(re-find #"\{:type" %) lines)
            "raw block vector must not render")))))

(deftest test-user-message-image-placeholder
  (testing "image blocks render as [image mime-type] placeholders"
    (let [ch (ch/make-chat-history)]
      (ch/chat-history-add-message! ch
                                    {:role :user
                                     :content [{:type :text :text "see:"}
                                               {:type :image :data "AA" :mime-type "image/png"}]})
      (let [lines (plain-lines ch 40)]
        (is (some #(re-find #"see:" %) lines))
        (is (some #(re-find #"\[image image/png\]" %) lines))))))

(deftest test-show-status
  (testing "status line renders and is not persisted as a message"
    (let [ch (ch/make-chat-history)]
      (ch/chat-history-add-message! ch {:role :user :content "hello"})
      (ch/chat-history-show-status! ch "Tool output: expanded")
      (let [lines (plain-lines ch 40)]
        (is (some #(re-find #"Tool output: expanded" %) lines)
            "status text should render"))
      (is (= [:user] (mapv :role (ch/chat-history-get-messages ch)))
          "status must not appear in persisted messages")
      (is (= ["hello"] (mapv :content (ch/chat-history-get-messages ch)))))))

(deftest test-show-status-updates-in-place
  (testing "repeated status updates replace the line instead of appending"
    (let [ch (ch/make-chat-history)
          _ (ch/chat-history-add-message! ch {:role :user :content "hi"})]
      (ch/chat-history-show-status! ch "Tool output: expanded")
      (ch/chat-history-show-status! ch "Tool output: collapsed")
      (let [lines (plain-lines ch 40)]
        (is (some #(re-find #"Tool output: collapsed" %) lines))
        (is (not-any? #(re-find #"Tool output: expanded" %) lines)
            "old status text should be replaced, not accumulated")))))

(deftest test-info-collapsible
  (testing "collapsible info banner toggles with the tool-expand action"
    (let [ch (ch/make-chat-history)]
      (ch/chat-history-set-info-msg! ch
                                     {:label "kmet" :content "plain"
                                      :collapsed-content "Press ctrl+o to expand"
                                      :expanded-content "Full help here"})
      (let [lines (plain-lines ch 40)]
        (is (some #(re-find #"Press ctrl\+o to expand" %) lines)
            "collapsed content shown by default")
        (is (not-any? #(re-find #"Full help here" %) lines)))
      (ch/chat-history-toggle-tool-expanded! ch)
      (let [lines (plain-lines ch 40)]
        (is (some #(re-find #"Full help here" %) lines)
            "expanded content after toggle")
        (is (not-any? #(re-find #"Press ctrl\+o to expand" %) lines))))))

(deftest test-info-non-collapsible-untouched
  (testing "non-collapsible info banner is left untouched by the toggle"
    (let [ch (ch/make-chat-history)]
      (ch/chat-history-set-info-msg! ch {:label "kmet" :content "static text"})
      (ch/chat-history-toggle-tool-expanded! ch)
      (let [lines (plain-lines ch 40)]
        (is (some #(re-find #"static text" %) lines))))))

(deftest test-rebuild-preserves-collapsible-info
  (testing "rebuild keeps collapsible variants and expanded state"
    (let [ch (ch/make-chat-history)]
      (ch/chat-history-set-info-msg! ch
                                     {:label "kmet" :content "plain"
                                      :collapsed-content "Collapsed banner"
                                      :expanded-content "Expanded banner"})
      (ch/chat-history-toggle-tool-expanded! ch)  ;; expand
      (ch/chat-history-rebuild! ch [{:role :user :content "new"}])
      (let [lines (plain-lines ch 40)]
        (is (some #(re-find #"Expanded banner" %) lines)
            "expanded state preserved across rebuild"))
      (ch/chat-history-toggle-tool-expanded! ch)  ;; collapse
      (let [lines (plain-lines ch 40)]
        (is (some #(re-find #"Collapsed banner" %) lines)
            "collapsible variants preserved across rebuild")))))

(deftest test-flag-toggle-no-content
  (testing "toggles flip the tracked flags even with no matching components"
    (let [ch (ch/make-chat-history)]
      (is (false? (ch/chat-history-get-tool-expanded ch)))
      (ch/chat-history-toggle-tool-expanded! ch)
      (is (true? (ch/chat-history-get-tool-expanded ch)) "flag flips without tools")
      (ch/chat-history-toggle-tool-expanded! ch)
      (is (false? (ch/chat-history-get-tool-expanded ch)))
      (ch/chat-history-toggle-thinking-hidden! ch)
      (is (true? (ch/chat-history-get-thinking-hidden ch)) "flag flips without messages"))))

(deftest test-new-messages-inherit-flags
  (testing "new tool components inherit the expansion flag"
    (let [ch (ch/make-chat-history)]
      (ch/chat-history-add-message! ch {:role :tool :name "ls" :content "files"})
      (is (false? (ch/chat-history-get-tool-expanded ch)))
      (ch/chat-history-toggle-tool-expanded! ch)
      ;; a NEW tool added after the toggle is expanded too
      (ch/chat-history-add-message! ch {:role :tool :name "ls" :content "more"})
      (let [tools (keep (fn [m] (when (contains? (:component m) :expanded-atom)
                                  (:component m)))
                        @(:messages-atom ch))]
        (is (= 2 (count tools)))
        (is (every? #(true? @(:expanded-atom %)) tools)
            "all tools — old and new — are expanded"))))
  (testing "new assistant messages inherit the thinking-hidden flag"
    (let [ch (ch/make-chat-history)]
      (ch/chat-history-add-message! ch
                                    {:role :assistant :content "a" :thinking "t"})
      (ch/chat-history-toggle-thinking-hidden! ch)
      (ch/chat-history-add-message! ch
                                    {:role :assistant :content "b" :thinking "t2"})
      (let [assistants (keep (fn [m] (when (contains? (:component m) :hide-thinking-atom)
                                       (:component m)))
                             @(:messages-atom ch))]
        (is (= 2 (count assistants)))
        (is (every? #(true? @(:hide-thinking-atom %)) assistants)
            "all assistant messages — old and new — have thinking hidden")))))

(deftest test-remove-streaming-placeholder-skips-status
  (testing "placeholder removal drops trailing status lines with it"
    (let [ch (ch/make-chat-history)]
      (ch/chat-history-start-streaming! ch)
      (ch/chat-history-show-status! ch "Tool output: expanded")
      (is (true? (ch/chat-history-remove-streaming-placeholder! ch)))
      (is (= [] (ch/chat-history-get-messages ch))
          "the status line and the placeholder are both removed"))))

(deftest test-show-status-appends-then-scrolls-away
  (testing "a status is a trailing entry — the next message renders below it,
            pushing the status up out of the pinned-bottom position"
    (let [ch (ch/make-chat-history)
          _ (ch/chat-history-add-message! ch {:role :user :content "hello"})
          _ (ch/chat-history-show-status! ch "Tool output: expanded")
          _ (ch/chat-history-add-message! ch {:role :assistant :content "response"})
          lines (plain-lines ch 40)
          status-idx (first (keep-indexed #(when (re-find #"Tool output" %2) %1) lines))
          resp-idx (first (keep-indexed #(when (re-find #"response" %2) %1) lines))]
      (is status-idx)
      (is resp-idx)
      (is (< status-idx resp-idx)
          "the new message appears after the status — the status is no longer
           pinned to the bottom of the chat"))))

(deftest test-info-banner-in-children
  (testing "the info banner is a chat message: themed, persisted as :info, survives placeholder removal"
    (let [ch (ch/make-chat-history)]
      (ch/chat-history-set-info-msg! ch {:label "kmet" :content "banner"})
      (is (some? @(:info-comp-atom ch)) "banner component exists")
      ;; output-pad must reach the banner without errors (theme needs no
      ;; walk — components subscribe to ui.subs/theme-sub, Stage 5)
      (ch/chat-history-set-output-pad! ch 2)
      (ch/chat-history-add-message! ch {:role :user :content "hi"})
      (is (= [:info :user] (mapv :role (ch/chat-history-get-messages ch))))
      (ch/chat-history-start-streaming! ch)
      (is (true? (ch/chat-history-remove-streaming-placeholder! ch)))
      (is (= [:info :user] (mapv :role (ch/chat-history-get-messages ch)))
          "placeholder removal removes the placeholder, not the banner or the message"))))

(deftest test-show-error-warning
  (testing "show-error! / show-warning! render plain spacer + colored text (pi: showError/showWarning)"
    (let [ch (ch/make-chat-history)
          _ (ui/show-warning! ch "bash already running")
          _ (ui/show-error! ch "command failed")
          lines (plain-lines ch 40)]
      (is (some #(re-find #"Warning: bash already running" %) lines))
      (is (some #(re-find #"Error: command failed" %) lines))
      (is (not-any? #(re-find #"custom-message" %) lines)
          "no background box — plain text like pi"))))

(deftest test-info-banner-first-user-spacing
  (testing "first user message after the info banner gets the same separator as later messages (visible gap between the two boxes)"
    (let [ch (ch/make-chat-history)
          _ (ch/chat-history-set-info-msg! ch {:label "kmet" :content "banner"})
          _ (ch/chat-history-add-message! ch {:role :user :content "hello"})
          lines (plain-lines ch 20)
          banner-idx (first (keep-indexed #(when (re-find #"banner" %2) %1) lines))
          hello-idx (first (keep-indexed #(when (re-find #"hello" %2) %1) lines))]
      (is banner-idx)
      (is hello-idx)
      (is (= hello-idx (+ banner-idx 4))
          "banner content → 3 blank lines (box bottom pad + separator + user box top pad) → user text"))))

(deftest test-unknown-system-role-renders
  (testing "roles without a dedicated component (:system, :unknown) render as plain text"
    (let [ch (ch/make-chat-history)]
      (ch/chat-history-add-message! ch {:role :system :content "compaction summary"})
      (ch/chat-history-add-message! ch {:role :unknown :content "mystery content"})
      (let [lines (plain-lines ch 40)]
        (is (some #(re-find #"compaction summary" %) lines))
        (is (some #(re-find #"mystery content" %) lines))
        (is (= 2 (count (ch/chat-history-get-messages ch)))
            "messages are kept, not silently dropped")))))

(deftest test-tool-block-content
  (testing "tool messages with block-vector content (agent-loop :context-replaced) render"
    (let [ch (ch/make-chat-history)]
      ;; :context-replaced rebuild feeds agent messages whose tool content is
      ;; [{:type :tool_result :content "..."}] — must not crash or print the raw vector
      (ch/chat-history-add-message! ch
                                    {:role :tool :name "bash"
                                     :content [{:type :tool_result :tool_use_id "x" :content "file text"}]
                                     :is-error false})
      (let [lines (plain-lines ch 40)]
        (is (some #(re-find #"file text" %) lines)
            "tool_result content extracted from the block")
        (is (not-any? #(re-find #"tool_result|PersistentVector" %) lines)
            "raw block vector must not render")))))

(deftest test-hidden-thinking-label
  (testing "chat-history-set-hidden-thinking-label! applies to existing and
            new assistant messages (pi: setHiddenThinkingLabel)"
    (let [ch (ch/make-chat-history)
          _ (ch/chat-history-add-message! ch {:role :assistant
                                              :content "resp"
                                              :thinking "secret"})
          _ (ch/chat-history-toggle-thinking-hidden! ch)
          _ (ch/chat-history-set-hidden-thinking-label! ch "Thoughts hidden")
          lines (plain-lines ch 40)]
      (is (some #(re-find #"Thoughts hidden" %) lines)
          "existing hidden message shows the custom label")
      (is (not-any? #(re-find #"secret" %) lines)
          "thinking content stays hidden")
      ;; nil restores the default
      (ch/chat-history-set-hidden-thinking-label! ch nil)
      (let [lines (plain-lines ch 40)]
        (is (some #(re-find #"Thinking\.\.\." %) lines)
            "nil restores the default label")))))

(deftest test-render-prebuilt-component
  (testing "a message carrying a pre-built :component renders it as-is"
    (let [ch (ch/make-chat-history)
          comp (core/make-text "prebuilt!")]
      (ch/chat-history-add-message! ch {:component comp})
      (let [lines (plain-lines ch 40)]
        (is (some #(re-find #"prebuilt!" %) lines)
            "the pre-built component's text should be visible, not empty")))))
