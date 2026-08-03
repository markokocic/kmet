(ns kmet.tui.test-core
  (:require [clojure.test :as t :refer [testing]]
            [kmet.tui.core :as core]
            [kmet.tui.keys :as keys]))

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
