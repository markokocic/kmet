(ns kmet.libs.test-reakt
  "Pure engine tests for kmet.libs.reakt — no TUI, no components: dependency
   discovery across branches, =-gated watch notification, cursors, manual
   tracks, disposal semantics, and the changed? gate. (The engine's
   integration with component render bodies is covered in
   kmet.tui.test-reakt-integration / test-compute / test-track.)"
  (:require [clojure.test :as t :refer [deftest is testing]]
            [kmet.libs.reakt :as r]))

(deftest test-changed-gate
  (testing "identical? fast path + structural = fallback"
    (let [a (atom [1 2])]
      (is (r/changed? a (atom [1 2])) "different identities are 'changed'")
      (reset! a [1 2])
      (is (not (r/changed? @a [1 2])) "equal values by = are not")
      (is (r/changed? @a [1 3])))))

(deftest test-derive-dep-discovery-and-gating
  (testing "derive re-runs only on real dep changes; equal resets notify nobody"
    (let [a (atom 1)
          runs (atom 0)
          fired (atom 0)
          d (r/derive [a] (fn [v] (swap! runs inc) (* 10 v)))]
      (is (= 10 @d) "first deref derives lazily")
      (r/watch-ref d :t (fn [& _] (swap! fired inc)))
      (is (= 1 @runs))
      (swap! a inc)
      (is (= 20 @d) "dep change re-derives")
      (is (= 2 @runs))
      (is (= 1 @fired) "one notification for the real output change")
      (reset! a 2)
      (is (= 2 @runs) "equal-value reset does not re-run")
      (is (= 1 @fired) "equal-value reset adds no notification")))
  (testing "extra deps read through tracked-deref join the set"
    (let [a (atom 1)
          b (atom 100)
          d (r/derive [a] (fn [_] (+ @a (r/tracked-deref b))))]
      (is (= 101 @d))
      (swap! b inc)
      (is (= 102 @d) "discovered dep re-derives"))))

(deftest test-cursor
  (testing "cursor tracks its source path"
    (let [src (atom {:a {:b 1}})
          cur (r/cursor src [:a :b])]
      (is (= 1 @cur))
      (swap! src assoc-in [:a :b] 5)
      (is (= 5 @cur))
      (is (= 5 @(r/cursor src :a :b)) "varargs keys normalize"))))

(deftest test-manual-track-lazy-and-disposed
  (testing "manual track caches and disposes with its last watcher"
    (let [a (atom 1)
          runs (atom 0)
          v0 @a
          tr (r/make-reaction (fn [] (swap! runs inc) v0)
                              {:auto-run? false})]
      (is (zero? @runs) "no eager run")
      (is (= 1 @tr))
      (is (= 1 @runs))
      (is (= 1 @tr) "cached hand-back")
      (is (= 1 @runs))
      (r/dispose! tr)
      (is (nil? @tr) "disposed refs deref nil"))))

(deftest test-flush-and-queued-count
  (testing "batch queue drains and settles chained dirt"
    (let [a (atom 1)
          b (r/make-reaction (fn [] (inc (r/tracked-deref a))))]
      (is (= 2 @b))
      (is (zero? (r/queued-count)) "clean deref leaves nothing queued")
      (swap! a inc)
      (is (pos? (r/queued-count)) "dirty dep enqueues downstream")
      (r/flush!)
      (is (zero? (r/queued-count)))
      (is (= 3 @b))))
  (testing "sticky failure: deref rethrows without re-running until change"
    (let [fail? (atom true)
          runs (atom 0)
          rx (r/make-reaction
              (fn [] (swap! runs inc)
                (when @fail? (throw (ex-info "boom" {})))
                :ok))]
      (is (thrown? Exception @rx))
      (is (thrown? Exception @rx) "second deref rethrows captured error")
      (is (= 1 @runs) "body did not re-execute")
      (reset! fail? false)
      (r/force-run! rx)
      (is (= :ok @rx) "run! retries through sticky failure"))))
