(ns kmet.tui.test-negotiation
  "Kitty keyboard protocol negotiation tests — response parsing, prefix
   detection, protocol enable/disable, and the input-reader interception
   (pi: terminal.ts negotiation + setupStdinBuffer)."
  (:require [clojure.test :as t :refer [deftest testing]]
            [kmet.tui.core :as core]
            [kmet.tui.keys :as keys]
            [kmet.libs.terminal :as lib]
            [kmet.tui.terminal :as term]))

(defn- recording-writer
  "Returns a write-fn recording written output plus the writes atom."
  []
  (let [writes (atom [])]
    {:write-fn #(swap! writes conj %)
     :writes writes}))

(defn- recording-terminal
  "ITerminal stub for the reader-interception tests."
  []
  (reify term/ITerminal
    (start! [_ _ _] nil)
    (stop! [_] nil)
    (write-output [_ _] nil)
    (columns [_] 80)
    (rows [_] 24)
    (hide-cursor! [_] nil)
    (show-cursor! [_] nil)
    (clear-line! [_] nil)
    (clear-screen! [_] nil)
    (set-title! [_ _] nil)
    (move-by! [_ _] nil)
    (clear-from-cursor! [_] nil)
    (set-progress! [_ _] nil)))

(defn- reset-protocol-state!
  "Reset the module-level protocol flags between tests."
  []
  (keys/set-kitty-active! false)
  (lib/query-kitty-protocol! (fn [_])))

(t/use-fixtures :each (fn [f] (reset-protocol-state!) (f)))

;; ─── Response parsing (pi: parseKeyboardProtocolNegotiationSequence) ──────

(deftest test-parse-negotiation-sequence
  (testing "kitty flags, device attributes, and garbage"
    (t/is (= {:type :kitty-flags :flags 7} (lib/parse-negotiation-sequence "\u001b[?7u")))
    (t/is (= {:type :kitty-flags :flags 0} (lib/parse-negotiation-sequence "\u001b[?0u")))
    (t/is (= {:type :device-attributes} (lib/parse-negotiation-sequence "\u001b[?1;2c")))
    (t/is (= {:type :device-attributes} (lib/parse-negotiation-sequence "\u001b[?c")))
    (t/is (nil? (lib/parse-negotiation-sequence "a")))
    (t/is (nil? (lib/parse-negotiation-sequence "\u001b[A")) "arrow keys are not negotiation responses")
    (t/is (nil? (lib/parse-negotiation-sequence "\u001b[?7u\u001b[?1;2c"))
          "multi-sequence chunks never parse (split by the char reader)")))

(deftest test-negotiation-prefix
  (testing "fragments that could still become a response"
    (t/is (true? (lib/negotiation-prefix? "\u001b[")))
    (t/is (true? (lib/negotiation-prefix? "\u001b[?")))
    (t/is (true? (lib/negotiation-prefix? "\u001b[?7")))
    (t/is (true? (lib/negotiation-prefix? "\u001b[?1;2")))
    (t/is (false? (lib/negotiation-prefix? "\u001b[A")) "arrow prefix is not negotiation")
    (t/is (false? (lib/negotiation-prefix? "\u001b[?7u")) "complete responses are not prefixes")))

;; ─── Protocol handling (pi: handleKeyboardProtocolNegotiationSequence) ─────

(deftest test-handle-kitty-flags-enables-kitty
  (testing "non-zero flags enable the Kitty protocol"
    (try
      (keys/set-kitty-active! false)
      (let [{:keys [write-fn writes]} (recording-writer)]
        ;; fallback enabled first, then kitty wins → fallback disabled
        (lib/handle-negotiation-sequence! write-fn {:type :device-attributes})
        (lib/handle-negotiation-sequence! write-fn {:type :kitty-flags :flags 7})
        (t/is (true? (keys/kitty-active?)))
        (t/is (= ["\u001b[>4;2m" "\u001b[>4;0m"] @writes)
              "modifyOtherKeys enabled then disabled when kitty wins"))
      (finally (keys/set-kitty-active! false)))))

(deftest test-handle-zero-flags-falls-back
  (testing "zero flags enable the modifyOtherKeys fallback"
    (let [{:keys [write-fn writes]} (recording-writer)]
      (lib/handle-negotiation-sequence! write-fn {:type :kitty-flags :flags 0})
      (t/is (false? (keys/kitty-active?)))
      (t/is (= ["\u001b[>4;2m"] @writes) "modifyOtherKeys fallback enabled"))))

(deftest test-handle-device-attributes-fallback
  (testing "a device-attributes report (DA sentinel) enables the fallback
            when kitty is not active"
    (let [{:keys [write-fn writes]} (recording-writer)]
      (lib/handle-negotiation-sequence! write-fn {:type :device-attributes})
      (t/is (= ["\u001b[>4;2m"] @writes) "DA sentinel enables the fallback"))))

(deftest test-disable-kitty-protocol
  (testing "disable writes the disable sequence and clears state"
    (try
      (keys/set-kitty-active! true)
      (let [{:keys [write-fn writes]} (recording-writer)]
        (lib/disable-kitty-protocol! write-fn)
        (t/is (= ["\u001b[<u"] @writes)
              "modifyOtherKeys was never enabled here, so only the kitty disable is written")
        (t/is (false? (keys/kitty-active?))))
      (finally (keys/set-kitty-active! false)))))

;; ─── Input-reader interception (pi: setupStdinBuffer) ──────────────────────

(defn- stub-read-fn
  "A read-fn that never yields more input (immediate timeout)."
  [_timeout-ms]
  -2)

(defn- intercept [tui buf]
  ((var core/intercept-keyboard-negotiation!) tui stub-read-fn buf))

(deftest test-intercept-consumes-response
  (testing "a complete response is consumed, never dispatched as input"
    (keys/set-kitty-active! false)
    (try
      (let [tui (core/create-tui (recording-terminal))
            buf (atom "\u001b[?7u")]
        (reset! (:keyboard-protocol-pushed? tui) true)
        (t/is (= :consumed (intercept tui buf)))
        (t/is (= "" @buf) "response removed from the input buffer")
        (t/is (true? (keys/kitty-active?)) "kitty enabled from the reader path"))
      (finally (keys/set-kitty-active! false)))))

(deftest test-intercept-holds-fragments
  (testing "split responses are held across reads, then consumed"
    (let [tui (core/create-tui (recording-terminal))
          buf (atom "\u001b[?")]
      (reset! (:keyboard-protocol-pushed? tui) true)
      (t/is (= :pending (intercept tui buf)))
      (t/is (= "" @buf) "fragment moved to the negotiation buffer")
      (reset! buf "7u")
      (t/is (= :consumed (intercept tui buf)))
      (t/is (= "" @buf)))))

(deftest test-intercept-flushes-non-negotiation
  (testing "held fragments flush back into the buffer when input stops
            matching the response shape"
    (let [tui (core/create-tui (recording-terminal))
          buf (atom "\u001b[?")]
      (reset! (:keyboard-protocol-pushed? tui) true)
      (t/is (= :pending (intercept tui buf)))
      (reset! buf "A")  ;; user pressed up while the response was pending
      (t/is (nil? (intercept tui buf)) "not negotiation input")
      (t/is (= "\u001b[?A" @buf) "held fragment + new input restored"))))

(deftest test-intercept-inactive-when-not-pushed
  (testing "no interception before the query is sent"
    (let [tui (core/create-tui (recording-terminal))
          buf (atom "\u001b[?7u")]
      (t/is (nil? (intercept tui buf)))
      (t/is (= "\u001b[?7u" @buf)))))

;; ─── ESC-wait loop interception (regression: DA response leaking into the
;;      editor as "?1;2;4c") ────────────────────────────────────────────────

(defn- process-chars
  "Drive process-input-buffer! the way the app reader loop does: one char per
   read (the next char of CHARS available immediately to the ESC-wait loop's
   timed reads), then assert the final state."
  [tui chars]
  (let [pending (atom (seq chars))
        read-fn (fn [_timeout-ms]
                  (if-let [c (first @pending)]
                    (do (swap! pending rest) (int c))
                    -2))
        dispatched (atom [])
        buf (atom "")]
    (swap! (:input-listeners tui)
           conj (fn [data] (swap! dispatched conj data) nil))
    (reset! (:keyboard-protocol-pushed? tui) true)
    (doseq [_ (range (count chars))]
      ;; the ESC-wait loop may have consumed some chars already, so reads
      ;; can time out (-2) before the loop finishes
      (let [ch (read-fn 0)]
        (when (>= ch 0)
          (swap! buf str (char ch))
          ((var core/process-input-buffer!) tui read-fn buf))))
    {:dispatched @dispatched :buf @buf}))

(deftest test-esc-loop-consumes-split-da-response
  (testing "a device-attributes response arriving char-by-char through the
            ESC-wait loop is held and consumed — never dispatched as keys
            (regression: the loop parsed \"\u001b[\" as alt+[ and leaked the
            remaining \"?1;2;4c\" into the focused editor)"
    (let [{:keys [dispatched buf]} (process-chars (core/create-tui (recording-terminal))
                                                  "\u001b[?1;2;4c")]
      (t/is (= [] dispatched) "no response char reaches the input path")
      (t/is (= "" buf) "response fully consumed")
      (t/is (false? (keys/kitty-active?)) "DA fallback: kitty stays off")))
  (testing "a complete kitty-flags response is consumed the same way"
    (let [{:keys [dispatched buf]} (process-chars (core/create-tui (recording-terminal))
                                                  "\u001b[?7u")]
      (t/is (= [] dispatched))
      (t/is (= "" buf))
      (t/is (true? (keys/kitty-active?)) "kitty enabled from the ESC path")))
  (testing "an arrow key is NOT held by the interception — it dispatches"
    (let [{:keys [dispatched buf]} (process-chars (core/create-tui (recording-terminal))
                                                  "\u001b[A")]
      (t/is (= ["\u001b[A"] dispatched) "arrow key reaches the input path")
      (t/is (= "" buf)))))
