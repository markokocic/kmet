(ns kmet.app.compaction
  "LLM-based context compaction aligned with pi (pi: core/compaction/compaction.js).
   Pure logic here: token estimation, cut-point selection, conversation
   serialization, and the summarization prompts. Session replacement happens in
   kmet.app.session (compact-with-summary!), orchestration in kmet.app.loop.

   Deviations from pi: no file-operation tracking (kmet does not record
   read/modified files); a split turn is summarized in a single call (pi uses a
   dedicated turn-prefix prompt and merges two summaries)."
  (:require [clojure.string :as str]
            [clojure.edn :as edn]))

(def ^:private image-chars 4800)
(def ^:private tool-result-max-chars 2000)

;; ─── Token estimation (pi: estimateTokens) ─────────────────────────────────

(defn- content-text
  "Plain text of a content string or block vector (tool_result blocks carry
   their text in :content)."
  [content]
  (if (string? content)
    content
    (str/join (for [b content
                    :when (or (= :text (:type b))
                              (= :tool_result (:type b)))]
                (or (:text b) (:content b) "")))))

(defn- text-chars
  "Char count of a content string or block vector (images count fixed chars,
   pi: estimateTextAndImageContentChars)."
  [content]
  (if (string? content)
    (count content)
    (reduce + 0
      (for [b content]
        (case (:type b)
          :image image-chars
          :text (count (or (:text b) ""))
          :thinking (count (or (:thinking b) ""))
          :tool_result (count (or (:content b) ""))
          0)))))

(defn estimate-tokens
  "Rough token estimate for a session entry (pi: estimateTokens, chars/4).
   :info and excluded-from-context :bash entries contribute 0 — they never
   reach the LLM."
  [entry]
  (let [chars (case (:role entry)
                :info 0
                :bash (if (:exclude-from-context? entry)
                        0
                        (+ (count (or (:command entry) ""))
                           (count (or (:output entry) ""))))
                (+ (text-chars (:content entry))
                   (reduce + 0
                     (for [tc (:tool-calls entry)]
                       (+ (count (or (:name tc) ""))
                          (count (str (or (:arguments tc) ""))))))))]
    (quot (+ chars 3) 4))) ;; ceil(chars / 4)

;; ─── Cut-point selection (pi: findCutPoint) ────────────────────────────────

(defn- context-visible?
  "True when an entry contributes to the LLM context. Tool results and
   display-only entries are never valid cut points (pi: isCutPointMessage)."
  [entry]
  (case (:role entry)
    :user true
    :assistant true
    :system true
    :bash (not (:exclude-from-context? entry))
    false))

(defn- turn-start?
  "True when an entry begins a turn (pi: isTurnStartEntry)."
  [entry]
  (contains? #{:user :bash} (:role entry)))

(defn find-cut-point
  "Find the entry index to start keeping from (pi: findCutPoint). Walks
   backwards from the newest entry accumulating estimated tokens; cuts at the
   closest valid cut point at/after the position where the budget
   keep-recent-tokens is reached. Never cuts at a tool result.
   Returns {:first-kept-index i :split-turn? bool}."
  [entries keep-recent-tokens]
  (let [n (count entries)
        valid (filterv (fn [i] (context-visible? (nth entries i))) (range n))]
    (if (empty? valid)
      {:first-kept-index 0 :split-turn? false}
      (let [acc (volatile! 0)
            cut (volatile! (first valid))
            stop (volatile! false)]
        (loop [i (dec n)]
          (when (and (>= i 0) (not @stop))
            (let [tokens (estimate-tokens (nth entries i))]
              (when (pos? tokens)
                (vswap! acc + tokens))
              (when (>= @acc keep-recent-tokens)
                (when-let [c (first (filter #(>= % i) valid))]
                  (vreset! cut c))
                (vreset! stop true)))
            (recur (dec i))))
        (let [cut-idx @cut]
          {:first-kept-index cut-idx
           :split-turn? (not (turn-start? (nth entries cut-idx)))})))))

;; ─── Preparation (pi: prepareCompaction) ───────────────────────────────────

(defn prepare
  "Compute what to compact (pi: prepareCompaction). Walks from the last
   compaction summary (physical removal in kmet means everything after it is
   new), finds the cut point, and collects the context-visible entries before
   it. Returns {:first-kept-id str :messages [entries] :previous-summary
   str-or-nil :tokens-before int} or nil when there is nothing to summarize."
  [entries keep-recent-tokens]
  (when (seq entries)
    (let [prev-idx (last (keep-indexed (fn [i e] (when (:summary e) i)) entries))
          previous-summary (when prev-idx (:summary (nth entries prev-idx)))
          boundary-start (if prev-idx (inc prev-idx) 0)
          cut (find-cut-point (subvec entries boundary-start) keep-recent-tokens)
          cut-idx (+ boundary-start (:first-kept-index cut))
          first-kept-id (:id (nth entries cut-idx))
          msgs (vec (filter context-visible? (subvec entries boundary-start cut-idx)))]
      (when (and first-kept-id (seq msgs))
        {:first-kept-id first-kept-id
         :messages msgs
         :previous-summary previous-summary
         :tokens-before (reduce + 0 (map estimate-tokens entries))}))))

;; ─── Conversation serialization (pi: serializeConversation) ────────────────

(defn- truncate-for-summary
  "Truncate text to tool-result-max-chars, keeping the beginning with a
   truncation marker (pi: truncateForSummary)."
  [text]
  (if (<= (count text) tool-result-max-chars)
    text
    (str (subs text 0 tool-result-max-chars)
         "\n\n[... " (- (count text) tool-result-max-chars)
         " more characters truncated]")))

(defn- tool-call-str
  "Render a tool call as name(k=v, ...) (pi: serializeConversation). Args
   may be a parsed map or a string to parse."
  [tc]
  (let [args (or (:arguments tc) {})
        args (if (map? args) args
                 (try (edn/read-string (str args)) (catch Exception _ {})))
        args-str (str/join ", "
                   (for [[k v] args]
                     (str (name k) "=" (pr-str v))))]
    (str (:name tc) "(" args-str ")")))

(defn serialize-conversation
  "Serialize context-visible entries to text for summarization (pi:
   serializeConversation). Tool results are truncated to 2000 chars."
  [entries]
  (str/join "\n\n"
    (keep (fn [e]
            (case (:role e)
              :user (let [t (content-text (:content e))]
                      (when (seq t) (str "[User]: " t)))
              :assistant
              (let [thinking (str/join "\n"
                               (for [b (:content e) :when (= :thinking (:type b))]
                                 (:thinking b)))
                    text (content-text (:content e))
                    tcs (:tool-calls e)
                    parts (cond-> []
                            (seq thinking) (conj (str "[Assistant thinking]: " thinking))
                            (seq text) (conj (str "[Assistant]: " text))
                            (seq tcs) (conj (str "[Assistant tool calls]: "
                                                 (str/join "; " (map tool-call-str tcs)))))]
                (when (seq parts) (str/join "\n" parts)))
              :tool (let [t (content-text (:content e))]
                      (when (seq t)
                        (str "[Tool result]: " (truncate-for-summary t))))
              nil))
          entries)))

;; ─── Summarization prompts (pi: SUMMARIZATION_PROMPT / UPDATE_SUMMARIZATION_PROMPT) ──

(def ^:private summarization-system-prompt
  "You are a context summarization assistant. Your task is to read a conversation between a user and an AI assistant, then produce a structured summary following the exact format specified.

Do NOT continue the conversation. Do NOT respond to any questions in the conversation. ONLY output the structured summary.")

(def ^:private summarization-prompt
  "The messages above are a conversation to summarize. Create a structured context checkpoint summary that another LLM will use to continue the work.

Use this EXACT format:

## Goal
[What is the user trying to accomplish? Can be multiple items if the session covers different tasks.]

## Constraints & Preferences
- [Any constraints, preferences, or requirements mentioned by user]
- [Or \"(none)\" if none were mentioned]

## Progress
### Done
- [x] [Completed tasks/changes]

### In Progress
- [ ] [Current work]

### Blocked
- [Issues preventing progress, if any]

## Key Decisions
- **[Decision]**: [Brief rationale]

## Next Steps
1. [Ordered list of what should happen next]

## Critical Context
- [Any data, examples, or references needed to continue]
- [Or \"(none)\" if not applicable]

Keep each section concise. Preserve exact file paths, function names, and error messages.")

(def ^:private update-summarization-prompt
  "The messages above are NEW conversation messages to incorporate into the existing summary provided in <previous-summary> tags.

Update the existing structured summary with new information. RULES:
- PRESERVE all existing information from the previous summary
- ADD new progress, decisions, and context from the new messages
- UPDATE the Progress section: move items from \"In Progress\" to \"Done\" when completed
- UPDATE \"Next Steps\" based on what was accomplished
- PRESERVE exact file paths, function names, and error messages
- If something is no longer relevant, you may remove it

Use this EXACT format:

## Goal
[Preserve existing goals, add new ones if the task expanded]

## Constraints & Preferences
- [Preserve existing, add new ones discovered]

## Progress
### Done
- [x] [Include previously done items AND newly completed items]

### In Progress
- [ ] [Current work - update based on progress]

### Blocked
- [Current blockers - remove if resolved]

## Key Decisions
- **[Decision]**: [Brief rationale] (preserve all previous, add new)

## Next Steps
1. [Update based on current state]

## Critical Context
- [Preserve important context, add new if needed]

Keep each section concise. Preserve exact file paths, function names, and error messages.")

(defn summarization-request
  "Build the single user message for a summarization call (pi:
   generateSummaryWithUsage): <conversation> tags, optional <previous-summary>
   tags, then the (initial or update) prompt with optional custom instructions."
  [messages previous-summary custom-instructions]
  (let [prompt (if previous-summary
                 update-summarization-prompt
                 summarization-prompt)
        prompt (if (seq custom-instructions)
                 (str prompt "\n\nAdditional focus: " custom-instructions)
                 prompt)]
    {:role :user
     :content [{:type :text
                :text (str "<conversation>\n"
                           (serialize-conversation messages)
                           "\n</conversation>\n\n"
                           (when previous-summary
                             (str "<previous-summary>\n" previous-summary "\n</previous-summary>\n\n"))
                           prompt)}]}))

(defn summarization-messages
  "The full message list for a summarization call: system prompt + request."
  [messages previous-summary custom-instructions]
  [{:role :system :content [{:type :text :text summarization-system-prompt}]}
   (summarization-request messages previous-summary custom-instructions)])
