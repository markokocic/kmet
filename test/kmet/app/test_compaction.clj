(ns kmet.app.test-compaction
  (:require [clojure.test :as t]
            [clojure.string :as str]
            [kmet.app.compaction :as compaction]))

;; ─── Token estimation (pi: estimateTokens) ─────────────────────────────────

(t/deftest test-estimate-tokens
  (t/testing "string content ~ chars/4"
    (t/is (= 3 (compaction/estimate-tokens {:role :user :content "0123456789ab"}))))
  (t/testing "text blocks count, image blocks count fixed chars"
    (t/is (pos? (compaction/estimate-tokens {:role :user :content [{:type :text :text "hello"}]})))
    (t/is (pos? (compaction/estimate-tokens {:role :user :content [{:type :image :data "x"}]}))))
  (t/testing "tool results count their text"
    (t/is (pos? (compaction/estimate-tokens {:role :tool :content [{:type :tool_result :content "big output here"}]}))))
  (t/testing "assistant thinking and tool calls count"
    (t/is (pos? (compaction/estimate-tokens {:role :assistant
                                             :content [{:type :thinking :thinking "hmm..."}]
                                             :tool-calls [{:name "read" :arguments {:path "/x"}}]}))))
  (t/testing "info and excluded bash contribute 0"
    (t/is (zero? (compaction/estimate-tokens {:role :info :content "x"})))
    (t/is (zero? (compaction/estimate-tokens {:role :bash :command "ls" :output "out" :exclude-from-context? true}))))
  (t/testing "bash without exclusion counts command + output"
    (t/is (pos? (compaction/estimate-tokens {:role :bash :command "ls" :output "out"})))))

;; ─── Cut point (pi: findCutPoint) ──────────────────────────────────────────

(t/deftest test-find-cut-point
  (t/testing "empty entries"
    (t/is (= {:first-kept-index 0 :split-turn? false}
             (compaction/find-cut-point [] 100))))
  (t/testing "never cuts at tool results"
    (let [entries [{:role :user :content "aaaa"}          ;; 1 token
                   {:role :tool :content [{:type :tool_result :content "bbbbbbbb"}]} ;; 2 tokens
                   {:role :user :content "cccc"}]         ;; 1 token
          cut (compaction/find-cut-point entries 1)]
      (t/is (not= 1 (:first-kept-index cut))
            "tool result index is not a valid cut point")))
  (t/testing "budget not reached → keep from first valid"
    (let [entries [{:role :user :content "a"}
                   {:role :assistant :content "b"}]]
      (t/is (= 0 (:first-kept-index (compaction/find-cut-point entries 1000))))))
  (t/testing "split-turn detection"
    (let [entries [{:role :user :content "q"}
                   {:role :assistant :content "aaaaaaaaaaaaaaaaaaaa"}  ;; 5 tokens
                   {:role :tool :content [{:type :tool_result :content "bbbbbbbbbbbbbbbbbbbb"}]}
                   {:role :assistant :content "cccccccccccccccccccc"}] ;; 5 tokens
          cut (compaction/find-cut-point entries 8)]
      (t/is (true? (:split-turn? cut)) "cut lands mid-turn (not a turn start)"))))

;; ─── Preparation (pi: prepareCompaction) ───────────────────────────────────

(t/deftest test-prepare
  (t/testing "nothing to summarize → nil"
    (t/is (nil? (compaction/prepare [] 100)))
    (t/is (nil? (compaction/prepare [{:role :user :content "tiny"}] 100))))
  (t/testing "cut point selected; context-visible messages collected"
    (let [entries (vec (for [i (range 20)]
                         {:id (str "e" i)
                          :role :user
                          :content (apply str (repeat 100 (str i)))}))  ;; ~25 tokens each
          prep (compaction/prepare entries 100)]
      (t/is (some? prep))
      (t/is (string? (:first-kept-id prep)))
      (t/is (pos? (count (:messages prep))))
      (t/is (every? #(= :user (:role %)) (:messages prep)))))
  (t/testing "previous summary found and reported"
    (let [entries (into [{:id "s1" :role :system :summary "OLD SUMMARY"
                          :content [{:type :text :text "OLD SUMMARY"}]}]
                        (for [i (range 20)]
                          {:id (str "e" i)
                           :role :user
                           :content (apply str (repeat 100 (str i)))}))
          prep (compaction/prepare entries 100)]
      (t/is (= "OLD SUMMARY" (:previous-summary prep)))
      (t/is (not-any? #(= "s1" (:id %)) (:messages prep))
            "previous summary entry is not summarized again")))
  (t/testing "tokens-before is the total estimate"
    (let [entries [{:id "a" :role :user :content (apply str (repeat 16 "x"))}
                   {:id "b" :role :user :content (apply str (repeat 16 "y"))}
                   {:id "c" :role :user :content (apply str (repeat 16 "z"))}]
          prep (compaction/prepare entries 8)]
      (t/is (= 12 (:tokens-before prep))))))

;; ─── Serialization (pi: serializeConversation) ─────────────────────────────

(t/deftest test-serialize-conversation
  (t/testing "user/assistant/tool roles serialized"
    (let [entries [{:role :user :content "hello"}
                   {:role :assistant :content [{:type :text :text "hi there"}]
                    :tool-calls [{:name "read" :arguments {:path "/x"}}]}
                   {:role :tool :content [{:type :tool_result :content "result text"}]}]
          text (compaction/serialize-conversation entries)]
      (t/is (str/includes? text "[User]: hello"))
      (t/is (str/includes? text "[Assistant]: hi there"))
      (t/is (str/includes? text "read("))
      (t/is (str/includes? text "[Tool result]: result text"))))
  (t/testing "info and excluded bash are skipped"
    (let [text (compaction/serialize-conversation
                 [{:role :info :content "ignored"}
                  {:role :bash :command "ls" :output "out" :exclude-from-context? true}])]
      (t/is (empty? text)))))

;; ─── Summarization request (pi: generateSummaryWithUsage) ─────────────────

(t/deftest test-summarization-messages
  (t/testing "initial prompt without previous summary"
    (let [msgs (compaction/summarization-messages [{:role :user :content "x"}] nil nil)]
      (t/is (= :system (:role (first msgs))))
      (let [text (-> msgs second :content first :text)]
        (t/is (str/includes? text "<conversation>"))
        (t/is (str/includes? text "## Goal"))
        (t/is (not (str/includes? text "<previous-summary>"))))))
  (t/testing "update prompt with previous summary"
    (let [msgs (compaction/summarization-messages [{:role :user :content "x"}] "OLD" nil)]
      (let [text (-> msgs second :content first :text)]
        (t/is (str/includes? text "<previous-summary>\nOLD\n</previous-summary>"))
        (t/is (str/includes? text "PRESERVE all existing information")))))
  (t/testing "custom instructions appended"
    (let [msgs (compaction/summarization-messages [{:role :user :content "x"}] nil "focus on tests")]
      (t/is (str/includes? (-> msgs second :content first :text)
                           "Additional focus: focus on tests")))))
