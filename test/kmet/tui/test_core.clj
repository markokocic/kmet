(ns kmet.tui.test-core
  (:require [clojure.test :as t :refer [testing]]
            [kmet.tui.core :as core]
            [kmet.tui.keys :as keys]
            [kmet.tui.components.editor :as editor]))

(defn- leaf
  "A focusable leaf component with a focused?-atom (like the editor)."
  []
  (let [focused? (atom false)]
    {:comp (reify core/IComponent
             core/IFocusable
             (render [_ _] [""])
             (handle-input [_ _] nil)
             (invalidate [_])
             (focused [_] @focused?)
             (set-focused! [_ v] (reset! focused? v)))
     :focused? focused?}))

(t/deftest test-overlay-focus-restores-previous
  (testing "hiding an overlay restores the component focused before it was shown"
    (let [tui (core/create-tui nil)
          a (leaf)
          b (leaf)
          c (leaf)]
      (core/tui-add-child tui (:comp a))
      (core/tui-add-child tui (:comp b))
      (core/tui-set-focus tui (:comp b))
      (core/tui-show-overlay tui (:comp c) :width 10 :height 5)
      (t/is (identical? (:comp c) @(:focused-component tui)))
      (t/is (true? @(:focused? c)))
      (t/is (false? @(:focused? b)) "previous focus loses the flag")
      (core/tui-hide-overlay tui)
      (t/is (identical? (:comp b) @(:focused-component tui))
            "focus returns to the pre-overlay component")
      (t/is (true? @(:focused? b)) "focus flag restored"))))

(t/deftest test-overlay-stacked-focus
  (testing "hiding the top overlay focuses the overlay below, not the base"
    (let [tui (core/create-tui nil)
          a (leaf)
          b (leaf)
          c (leaf)]
      (core/tui-add-child tui (:comp a))
      (core/tui-set-focus tui (:comp a))
      (core/tui-show-overlay tui (:comp b) :width 10 :height 5)
      (core/tui-show-overlay tui (:comp c) :width 10 :height 5)
      (core/tui-hide-overlay tui)
      (t/is (identical? (:comp b) @(:focused-component tui))
            "lower overlay gets focus when the top one closes"))))

(t/deftest test-overlay-focus-fallback
  (testing "no previous focus → falls back to the last component"
    (let [tui (core/create-tui nil)
          a (leaf)
          o (leaf)]
      (core/tui-add-child tui (:comp a))
      (core/tui-show-overlay tui (:comp o) :width 10 :height 5)
      (core/tui-hide-overlay tui)
      (t/is (identical? (:comp a) @(:focused-component tui))
            "falls back to the last top-level component"))))

(t/deftest test-flash-api
  (testing "tui-flash! shows a flash and tui-flash-dispose! clears it"
    (let [tui (core/create-tui nil)]
      (core/tui-flash! tui "Copied!" :duration-ms 60000)
      (t/is (= 1 (count (core/render @(:flashes tui) 20))))
      (core/tui-flash-dispose! tui)
      (t/is (= [] (core/render @(:flashes tui) 20))))))

(t/deftest test-tui-stop-disposes-flashes
  (testing "stopping the TUI clears pending flashes (pi: dispose on close)"
    (let [tui (core/create-tui nil)]
      (core/tui-flash! tui "x" :duration-ms 60000)
      (t/is (= 1 (count (core/render @(:flashes tui) 20))))
      (core/tui-stop tui)
      (t/is (= [] (core/render @(:flashes tui) 20))))))

(t/deftest test-overlay-stale-previous-focus
  (testing "a removed previous-focus falls back to the last remaining component"
    (let [tui (core/create-tui nil)
          a (leaf)
          b (leaf)
          o (leaf)]
      (core/tui-add-child tui (:comp a))
      (core/tui-add-child tui (:comp b))
      (core/tui-set-focus tui (:comp b))
      (core/tui-show-overlay tui (:comp o) :width 10 :height 5)
      (core/tui-remove-child tui (:comp b))
      (core/tui-hide-overlay tui)
      (t/is (identical? (:comp a) @(:focused-component tui))
            "falls back to the last live component"))))

(defn- dispatch!
  "Call the private input dispatcher (pi: TUI input routing)."
  [tui data]
  ((var kmet.tui.core/dispatch-input!) tui data))

(t/deftest test-dispatch-no-focus-drops-input
  (testing "input with no focused component is dropped (pi: no fallback)"
    (let [tui (core/create-tui nil)
          got (atom [])
          c (reify core/IComponent
              (render [_ _] [""])
              (handle-input [_ data] (swap! got conj data))
              (invalidate [_]))]
      (core/tui-add-child tui c)
      (dispatch! tui "a")
      (t/is (empty? @got) "nothing delivered without a focused component"))))

(t/deftest test-dispatch-filters-key-releases
  (testing "key release events are filtered unless the component opts in"
    (keys/set-kitty-active! true)
    (try
      (let [tui (core/create-tui nil)
            got (atom [])
            c (reify core/IComponent
                (render [_ _] [""])
                (handle-input [_ data] (swap! got conj data))
                (invalidate [_]))]
        (core/tui-add-child tui c)
        (core/tui-set-focus tui c)
        (dispatch! tui "a")
        (dispatch! tui "\u001b[97;1:3u")  ;; kitty release event
        (t/is (= ["a"] @got) "release events are filtered by default"))
      (finally (keys/set-kitty-active! false)))))

(defrecord WantsReleases [wants-key-release? log]
  core/IComponent
  (render [_ _] [""])
  (handle-input [_ data] (swap! log conj data))
  (invalidate [_]))

(t/deftest test-dispatch-wants-key-release-opt-in
  (testing "a component with :wants-key-release? true receives releases (pi: wantsKeyRelease)"
    (keys/set-kitty-active! true)
    (try
      (let [tui (core/create-tui nil)
            log (atom [])
            opt-in (map->WantsReleases {:wants-key-release? true :log log})]
        (core/tui-add-child tui opt-in)
        (core/tui-set-focus tui opt-in)
        (dispatch! tui "\u001b[97;1:3u")
        (t/is (= ["\u001b[97;1:3u"] @log)
              "opt-in component receives the release event"))
      (finally (keys/set-kitty-active! false)))))

(t/deftest test-dispatch-listener-chain
  (testing "input listeners chain: :data transforms feed later listeners,
            :consume stops dispatch (pi: InputListener chain)"
    (let [tui (core/create-tui nil)
          got (atom [])
          c (reify core/IComponent
              (render [_ _] [""])
              (handle-input [_ data] (swap! got conj data))
              (invalidate [_]))]
      (core/tui-add-child tui c)
      (core/tui-set-focus tui c)
      (core/tui-add-input-listener tui (fn [data] {:data (str data "!")}))
      (core/tui-add-input-listener tui (fn [data] (swap! got conj [:l2 data])))
      (dispatch! tui "x")
      (t/is (= [:l2 "x!"] (first @got))
            "second listener sees the transformed data")
      (t/is (= "x!" (second @got))
            "focused component receives the final transformed data")
      ;; consume stops later listeners AND focus delivery (pi semantics:
      ;; earlier listeners already ran)
      (reset! got [])
      (core/tui-add-input-listener tui (fn [_] {:consume true}))
      (dispatch! tui "y")
      (t/is (= [[:l2 "y!"]] @got)
            "consume drops the event for later listeners and focus"))))

;; ─── Unbracketed paste detection (paste-like bursts) ──────────────────────
;; Terminals/IMEs without bracketed-paste support deliver paste content as
;; ordinary key events, so a paste line ending arrives as a lone CR and the
;; editor would submit it (executing pasted /cmd or !cmd without Enter). The
;; reader rewrites a CR that ends a paste-like burst to \n; only an isolated
;; CR (a real Enter press) submits.

(defn- cr-in-paste-burst?
  "Test helper for the private predicate."
  [recent now]
  ((var kmet.tui.core/cr-in-paste-burst?) recent now))

(defn- paste-input-decision
  "Test helper for the private decision fn."
  [c now recent swallow-lf]
  ((var kmet.tui.core/paste-input-decision) c now recent swallow-lf))

(defn- burst-chars
  "N chars arriving BURST-APART ms apart, ending with LAST, all within the
   paste-burst window ending at NOW. Entries are [timestamp char] pairs like
   the reader's recent-chars tracking."
  [n burst-apart last now]
  (conj (mapv (fn [i]
                [(- now (* (- n i) burst-apart))
                 (char (+ (int \a) i))])
              (range n))
        [now last]))

(t/deftest test-cr-in-paste-burst
  (testing "a CR ends a paste-like burst"
    (t/is (true? (cr-in-paste-burst? (burst-chars 3 5 \return 1000) 1000))
          "4 chars (incl CR) within 100ms")
    (t/is (true? (cr-in-paste-burst? (burst-chars 8 5 \return 1000) 1000))
          "any paste-sized burst"))
  (testing "slow input is typing, not a paste"
    (t/is (false? (cr-in-paste-burst? (burst-chars 2 5 \return 1000) 1000))
          "fewer than 4 chars")
    (t/is (false? (cr-in-paste-burst? (burst-chars 3 200 \return 1000) 1000))
          "chars arrive slower than the burst window"))
  (testing "an Enter key repeat stream (all CRs) keeps submitting"
    (t/is (false? (cr-in-paste-burst? (mapv (fn [i] [(- 1000 (* i 30)) \return])
                                            (range 4))
                                      1000)))))

(t/deftest test-paste-input-decision
  (let [recent (burst-chars 3 5 \return 1000)
        now 1000]
    (testing "CR ending a burst becomes a newline and arms the LF swallow"
      (t/is (= {:append "\n" :new-swallow-lf 1000}
               (paste-input-decision \return now recent nil))))
    (testing "the LF half of a rewritten CRLF is dropped"
      (t/is (= {:drop true :new-swallow-lf nil}
               (paste-input-decision \newline 1005 recent 1000)))
      (t/is (= {:append "\n" :new-swallow-lf nil}
               (paste-input-decision \newline 1100 recent 1000))
            "a late LF is a real newline"))
    (testing "ordinary chars pass through"
      (t/is (= {:append "x" :new-swallow-lf nil}
               (paste-input-decision \x now recent 1000)))
      (t/is (= {:append "\r" :new-swallow-lf nil}
               (paste-input-decision \return now [] nil))
            "an isolated CR (real Enter) is untouched"))))

(defn- reader-feed!
  "Simulate the app reader loop (start-input-reader) for CHARS: each entry is
   [char ts] with TS a monotonic timestamp; applies the paste-burst decision
   and drives process-input-buffer! exactly like the real loop."
  [tui chars]
  (let [read-fn (fn [_timeout-ms] -2)
        buf (atom "")
        recent-chars (atom [])
        swallow-lf (atom nil)]
    (doseq [[c ts] chars]
      (let [now ts]
        (swap! recent-chars
               (fn [rc]
                 (-> (conj rc [now c])
                     (->> (filter (fn [[t _]] (>= t (- now 100)))))
                     vec)))
        (let [{:keys [append drop new-swallow-lf]}
              (paste-input-decision c now @recent-chars @swallow-lf)]
          (reset! swallow-lf new-swallow-lf)
          (swap! (:input-generation tui) inc)
          (when-not drop
            (swap! buf str append)
            ((var kmet.tui.core/process-input-buffer!) tui read-fn buf)))))
    {:buf @buf}))

(defn- pasted-editor
  "TUI with a focused editor; feeds CHARS through the simulated reader loop
   and returns {:editor ed :submitted submitted}."
  [chars]
  (let [tui (core/create-tui nil)
        ed (editor/make-editor)
        submitted (atom [])]
    (editor/editor-set-on-submit! ed (fn [t] (swap! submitted conj t)))
    (core/tui-add-child tui ed)
    (core/tui-set-focus tui ed)
    (reader-feed! tui chars)
    {:editor ed :submitted submitted}))

(defn- paste-chars
  "CHARS arriving BURST-APART ms apart starting at TS."
  [chars burst-apart ts]
  (mapv (fn [i c] [c (+ ts (* i burst-apart))]) (range) chars))

(t/deftest test-unbracketed-paste-does-not-submit
  (testing "an unbracketed paste with CR line endings inserts text, no submit"
    (let [{:keys [editor submitted]} (pasted-editor (paste-chars "abc\rdef\r" 5 1000))]
      (t/is (= [] @submitted) "paste CRs never submit")
      (t/is (= "abc\ndef\n" (editor/editor-get-text editor)) "CRs became newlines")))
  (testing "CRLF line endings collapse to a single newline each"
    (let [{:keys [editor submitted]} (pasted-editor (paste-chars "abc\r\ndef\r" 5 1000))]
      (t/is (= [] @submitted))
      (t/is (= "abc\ndef\n" (editor/editor-get-text editor)))))
  (testing "a pasted slash command is text, not a command"
    (let [{:keys [editor submitted]} (pasted-editor (paste-chars "/model DGG hhh\r" 5 1000))]
      (t/is (= [] @submitted))
      (t/is (= "/model DGG hhh\n" (editor/editor-get-text editor))))))

(t/deftest test-isolated-enter-still-submits
  (testing "a lone CR (real Enter press) submits"
    (let [{:keys [submitted]} (pasted-editor (paste-chars "abc\r" 150 1000))]
      (t/is (= ["abc"] @submitted) "slow typing then Enter submits")))
  (testing "an Enter key repeat stream (all CRs) keeps submitting"
    (let [{:keys [submitted]} (pasted-editor (paste-chars "\r\r\r\r" 30 1000))]
      (t/is (= 4 (count @submitted)) "repeated Enters are not rewritten"))))

(t/deftest test-bracketed-paste-unaffected
  (testing "bracketed paste still buffers and normalizes via handle-paste"
    (let [{:keys [editor submitted]}
          (pasted-editor (paste-chars "\u001b[200~ab\r\ncd\r\u001b[201~" 5 1000))]
      (t/is (= [] @submitted))
      (t/is (= "ab\ncd" (editor/editor-get-text editor))))))

;; ─── Paste-marker buffering (regression: text sharing a buffer pass with
;;      a marker was dropped) ────────────────────────────────────────────────

(defn- feed-buf!
  "Run one process-input-buffer! pass over BUF (as the reader/flush timers
   do), collecting dispatched data through an input listener."
  [tui buf]
  (let [dispatched (atom [])]
    (swap! (:input-listeners tui) conj (fn [data] (swap! dispatched conj data) nil))
    ((var kmet.tui.core/process-input-buffer!) tui (fn [_] -2) buf)
    @dispatched))

(t/deftest test-paste-marker-preserves-surrounding-text
  ;; Text can share a buffer pass with a paste marker when a held interceptor
  ;; fragment flushes back into the buffer ahead of fresh input. Everything
  ;; around the marker must stay buffered in arrival order — previously any
  ;; content AFTER the marker was silently discarded.
  (testing "content after the start marker stays buffered"
    (let [tui (core/create-tui nil)
          buf (atom "\u001b[200~abc")]
      (t/is (= ["\u001b[200~"] (feed-buf! tui buf)) "marker dispatched")
      (t/is (= "abc" @buf) "trailing text preserved")))
  (testing "text before the marker stays buffered ahead of post-marker text"
    (let [tui (core/create-tui nil)
          buf (atom "q\u001b[200~hi")]
      (t/is (= ["\u001b[200~"] (feed-buf! tui buf)))
      (t/is (= "qhi" @buf) "arrival order kept")))
  (testing "the end marker completes the paste of everything between"
    (let [tui (core/create-tui nil)
          ed (editor/make-editor)
          _ (do (core/tui-add-child tui ed)
                (core/tui-set-focus tui ed))
          buf (atom "\u001b[200~hello")]
      (feed-buf! tui buf)                 ; marker; "hello" stays buffered
      ;; deliver the preserved text as one printable run (the reader now
      ;; drains whole bursts), then the end marker
      ((var kmet.tui.core/process-input-buffer!) tui (fn [_] -2) buf)
      (reset! buf "\u001b[201~")
      (feed-buf! tui buf)
      (t/is (= "hello" (editor/editor-get-text ed))))))

(t/deftest test-incomplete-sequence-flush-timeouts
  ;; pi parity: a lone ESC fires as Escape quickly (10ms), but a partial CSI
  ;; sequence waits 50ms — the flat 10ms fired MID-SEQUENCE under ordinary
  ;; reader stalls (observed at 11-15ms on Android), flushing a phantom
  ;; Escape and leaking the rest of a bracketed paste as literal text.
  (testing "a lone ESC dispatches as Escape shortly after"
    (let [tui (core/create-tui nil)
          buf (atom "")
          dispatched (atom [])]
      (swap! (:input-listeners tui) conj (fn [data] (swap! dispatched conj data) nil))
      (swap! buf str "\u001b")
      ((var kmet.tui.core/process-input-buffer!) tui (fn [_] -2) buf)
      (let [deadline (+ (System/currentTimeMillis) 600)]
        (while (and (empty? @dispatched) (< (System/currentTimeMillis) deadline))
          (Thread/sleep 5)))
      (t/is (= ["\u001b"] @dispatched) "Escape dispatched")
      (t/is (= "" @buf) "buffer cleared")))
  (testing "a partial CSI prefix is never flushed as keys"
    (let [tui (core/create-tui nil)
          buf (atom "\u001b[2")
          dispatched (atom [])]
      (swap! (:input-listeners tui) conj (fn [data] (swap! dispatched conj data) nil))
      ((var kmet.tui.core/process-input-buffer!) tui (fn [_] -2) buf)
      (Thread/sleep 80)
      (t/is (= [] @dispatched) "incomplete prefix never dispatches")
      (t/is (= "\u001b[2" @buf) "still buffered, waiting for the remainder")))
  (testing "a flush-timer fire does not break later sequence completion"
    ;; The timer claims \u001b[200 after SEQUENCE-FLUSH-MS, dispatch declines
    ;; (incomplete), and the fragment must go BACK into the buffer — a late ~
    ;; still completes the paste marker instead of leaking as literal text.
    (let [tui (core/create-tui nil)
          buf (atom "\u001b[200")
          dispatched (atom [])]
      (swap! (:input-listeners tui) conj (fn [data] (swap! dispatched conj data) nil))
      ((var kmet.tui.core/process-input-buffer!) tui (fn [_] -2) buf)
      (Thread/sleep 80)               ; timer fires, claims, declines, restores
      (swap! buf str "~")
      ((var kmet.tui.core/process-input-buffer!) tui (fn [_] -2) buf)
      (t/is (= ["\u001b[200~"] @dispatched) "marker completed from restored fragment")
      (t/is (= "" @buf))))
  (testing "a flush claim that loses the race leaves the buffer untouched"
    ;; The claim fn must return cur UNCHANGED on mismatch: returning nil used
    ;; to install nil as the buffer value, wiping whatever the reader had
    ;; appended in the read->claim window.
    (let [buf (atom "\u001b[")]
      (t/is (false? ((var kmet.tui.core/claim-flush-content!) buf "\u001b"))
            "mismatched content is not claimed")
      (t/is (= "\u001b[" @buf) "failed claim leaves the buffer intact"))
    (let [buf (atom "\u001b")]
      (t/is (true? ((var kmet.tui.core/claim-flush-content!) buf "\u001b"))
            "matching content is claimed")
      (t/is (= "" @buf) "claimed content is consumed"))))
