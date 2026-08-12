(ns kmet.tui.components.test-alt-screen-flash
  (:require [clojure.test :as t]
            [kmet.tui.core :as core]
            [kmet.tui.utils :as u]
            [kmet.tui.components.alt-screen-flash :as asf]))

(defn- plain [lines]
  (mapv #(u/strip-ansi-codes %) lines))

(t/deftest test-create
  (let [c (asf/make-alt-screen-flash (fn []))]
    (t/is (satisfies? core/IComponent c))
    (t/is (= [] (core/render c 10)) "no flashes initially")))

(t/deftest ^:slow test-flash-renders-inverse-line
  (let [c (asf/make-alt-screen-flash (fn []))]
    (asf/alt-screen-flash! c "Copied!")
    (let [lines (core/render c 20)]
      (t/is (= 1 (count lines)))
      (t/is (re-find #"\u001b\[7m" (first lines)) "inverse video")
      (t/is (re-find #"Copied!" (first lines)))))
  ;; flashes expire after their duration
  (let [c (asf/make-alt-screen-flash (fn []))]
    (asf/alt-screen-flash! c "x" :duration-ms 20)
    (t/is (= 1 (count (core/render c 20))))
    (Thread/sleep 60)
    (t/is (= [] (core/render c 20)) "expired after duration")))

(t/deftest test-multiple-flashes-stack
  (let [c (asf/make-alt-screen-flash (fn []))]
    (asf/alt-screen-flash! c "one" :duration-ms 60000)
    (asf/alt-screen-flash! c "two" :duration-ms 60000)
    (let [lines (core/render c 20)]
      (t/is (= 2 (count lines)))
      (t/is (re-find #"one" (first lines)))
      (t/is (re-find #"two" (second lines))))))

(t/deftest test-flash-truncates
  (let [c (asf/make-alt-screen-flash (fn []))]
    (asf/alt-screen-flash! c "a very long message that cannot fit" :duration-ms 60000)
    (let [lines (plain (core/render c 12))]
      (t/is (<= (u/visible-width (first lines)) 12)))))

(t/deftest ^:slow test-dispose
  (let [c (asf/make-alt-screen-flash (fn []))]
    (asf/alt-screen-flash! c "x" :duration-ms 60000)
    (asf/alt-screen-flash-dispose! c)
    (t/is (= [] (core/render c 20)) "dispose clears pending flashes")
    ;; no crash after the pending timer fires
    (Thread/sleep 30)))

(t/deftest ^:slow test-request-render-called
  (let [renders (atom 0)
        c (asf/make-alt-screen-flash #(swap! renders inc))]
    (asf/alt-screen-flash! c "x" :duration-ms 20)
    (t/is (pos? @renders) "flash triggers a render request")
    (Thread/sleep 60)
    (t/is (>= @renders 2) "expiry triggers another render request")))
