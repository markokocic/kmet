(ns kmet.tui.test-negotiation
  "Kitty keyboard protocol negotiation tests — response parsing, prefix
   detection, protocol enable/disable, and the input-reader interception
   (pi: terminal.ts negotiation + setupStdinBuffer)."
  (:require [clojure.test :as t :refer [deftest testing]]
            [kmet.tui.core :as core]
            [kmet.tui.keys :as keys]
            [kmet.tui.terminal :as term]))

(defn- recording-terminal
  "ITerminal stub recording written output."
  []
  (let [writes (atom [])]
    {:term (reify term/ITerminal
             (start! [_ _ _] nil)
             (stop! [_] nil)
             (write-output [_ s] (swap! writes conj s))
             (columns [_] 80)
             (rows [_] 24)
             (hide-cursor! [_] nil)
             (show-cursor! [_] nil)
             (clear-line! [_] nil)
             (clear-screen! [_] nil)
             (set-title! [_ _] nil))
     :writes writes}))

(defn- reset-protocol-state!
  "Reset the module-level protocol flags between tests."
  []
  (keys/set-kitty-active! false)
  (term/query-kitty-protocol! (:term (recording-terminal))))

(t/use-fixtures :each (fn [f] (reset-protocol-state!) (f)))

;; ─── Response parsing (pi: parseKeyboardProtocolNegotiationSequence) ──────

(deftest test-parse-negotiation-sequence
  (testing "kitty flags, device attributes, and garbage"
    (t/is (= {:type :kitty-flags :flags 7} (term/parse-negotiation-sequence "\u001b[?7u")))
    (t/is (= {:type :kitty-flags :flags 0} (term/parse-negotiation-sequence "\u001b[?0u")))
    (t/is (= {:type :device-attributes} (term/parse-negotiation-sequence "\u001b[?1;2c")))
    (t/is (= {:type :device-attributes} (term/parse-negotiation-sequence "\u001b[?c")))
    (t/is (nil? (term/parse-negotiation-sequence "a")))
    (t/is (nil? (term/parse-negotiation-sequence "\u001b[A")) "arrow keys are not negotiation responses")
    (t/is (nil? (term/parse-negotiation-sequence "\u001b[?7u\u001b[?1;2c"))
          "multi-sequence chunks never parse (split by the char reader)")))

(deftest test-negotiation-prefix
  (testing "fragments that could still become a response"
    (t/is (true? (term/negotiation-prefix? "\u001b[")))
    (t/is (true? (term/negotiation-prefix? "\u001b[?")))
    (t/is (true? (term/negotiation-prefix? "\u001b[?7")))
    (t/is (true? (term/negotiation-prefix? "\u001b[?1;2")))
    (t/is (false? (term/negotiation-prefix? "\u001b[A")) "arrow prefix is not negotiation")
    (t/is (false? (term/negotiation-prefix? "\u001b[?7u")) "complete responses are not prefixes")))

;; ─── Protocol handling (pi: handleKeyboardProtocolNegotiationSequence) ─────

(deftest test-handle-kitty-flags-enables-kitty
  (testing "non-zero flags enable the Kitty protocol"
    (try
      (keys/set-kitty-active! false)
      (let [{:keys [term writes]} (recording-terminal)]
        ;; fallback enabled first, then kitty wins → fallback disabled
        (term/handle-negotiation-sequence! term {:type :device-attributes})
        (term/handle-negotiation-sequence! term {:type :kitty-flags :flags 7})
        (t/is (true? (keys/kitty-active?)))
        (t/is (= ["\u001b[>4;2m" "\u001b[>4;0m"] @writes)
              "modifyOtherKeys enabled then disabled when kitty wins"))
      (finally (keys/set-kitty-active! false)))))

(deftest test-handle-zero-flags-falls-back
  (testing "zero flags enable the modifyOtherKeys fallback"
    (let [{:keys [term writes]} (recording-terminal)]
      (term/handle-negotiation-sequence! term {:type :kitty-flags :flags 0})
      (t/is (false? (keys/kitty-active?)))
      (t/is (= ["\u001b[>4;2m"] @writes) "modifyOtherKeys fallback enabled"))))

(deftest test-handle-device-attributes-fallback
  (testing "a device-attributes report (DA sentinel) enables the fallback
            when kitty is not active"
    (let [{:keys [term writes]} (recording-terminal)]
      (term/handle-negotiation-sequence! term {:type :device-attributes})
      (t/is (= ["\u001b[>4;2m"] @writes) "DA sentinel enables the fallback"))))

(deftest test-disable-kitty-protocol
  (testing "disable writes the disable sequence and clears state"
    (try
      (keys/set-kitty-active! true)
      (let [{:keys [term writes]} (recording-terminal)]
        (term/disable-kitty-protocol! term)
        (t/is (= ["\u001b[<u"] @writes)
              "modifyOtherKeys was never enabled here, so only the kitty disable is written")
        (t/is (false? (keys/kitty-active?))))
      (finally (keys/set-kitty-active! false)))))

;; ─── Input-reader interception (pi: setupStdinBuffer) ──────────────────────

(defn- intercept [tui buf]
  ((var core/intercept-keyboard-negotiation!) tui nil buf))

(deftest test-intercept-consumes-response
  (testing "a complete response is consumed, never dispatched as input"
    (keys/set-kitty-active! false)
    (try
      (let [tui (core/create-tui (:term (recording-terminal)))
            buf (atom "\u001b[?7u")]
        (reset! (:keyboard-protocol-pushed? tui) true)
        (t/is (= :consumed (intercept tui buf)))
        (t/is (= "" @buf) "response removed from the input buffer")
        (t/is (true? (keys/kitty-active?)) "kitty enabled from the reader path"))
      (finally (keys/set-kitty-active! false)))))

(deftest test-intercept-holds-fragments
  (testing "split responses are held across reads, then consumed"
    (let [tui (core/create-tui (:term (recording-terminal)))
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
    (let [tui (core/create-tui (:term (recording-terminal)))
          buf (atom "\u001b[?")]
      (reset! (:keyboard-protocol-pushed? tui) true)
      (t/is (= :pending (intercept tui buf)))
      (reset! buf "A")  ;; user pressed up while the response was pending
      (t/is (nil? (intercept tui buf)) "not negotiation input")
      (t/is (= "\u001b[?A" @buf) "held fragment + new input restored"))))

(deftest test-intercept-inactive-when-not-pushed
  (testing "no interception before the query is sent"
    (let [tui (core/create-tui (:term (recording-terminal)))
          buf (atom "\u001b[?7u")]
      (t/is (nil? (intercept tui buf)))
      (t/is (= "\u001b[?7u" @buf)))))
