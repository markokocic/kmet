(ns kmet.tui.test-reagent
  "Headless tests for kmet.tui.reagent — the reactive engine (dsl.md Stage 1).
   No terminal, no sleeps: dependency discovery, watch set-diff, =-gated
   notification, queue draining, plain-atom interop, component-body capture
   (a Text render inside a reaction tracks text-set!), tracks, cursors,
   disposal, and the with-let store primitives."
  (:require [clojure.string :as str]
            [clojure.test :as t]
            [kmet.tui.core :as core]
            [kmet.tui.components.text :as text]
            [kmet.tui.macros :as macros]
            [kmet.tui.reagent :as r]))

(defn- rd
  "Tracked deref for reaction bodies under test."
  [ref]
  (r/tracked-deref ref))

(defn- counting-rx
  "Reaction over REF reading through tracked-deref, counting body runs."
  [ref]
  (let [runs (atom 0)
        rx (r/make-reaction (fn []
                              (swap! runs inc)
                              (rd ref)))]
    {:rx rx :runs runs :ref ref}))

;; ═══════════════════════════════════════════════════════════════════════════
;; Dependency discovery
;; ═══════════════════════════════════════════════════════════════════════════

(t/deftest test-dep-discovery-across-branches
  ;; Only the atoms actually read on the last pass are dependencies:
  ;; writes to dropped branches are silent after the branch changes.
  (let [a (atom 1)
        b (atom 10)
        switch (atom :a)
        runs (atom 0)
        rx (r/make-reaction (fn []
                              (swap! runs inc)
                              (case (rd switch)
                                :a (rd a)
                                :b (rd b))))]
    (t/is (= 1 (r/run! rx)))
    (t/is (= #{a switch} (set (:watching (r/reaction-state rx)))))
    (reset! b 99)
    (r/flush!)
    (t/is (= 1 @runs) "unread dep write must not re-run")
    (t/is (zero? (r/queued-count)))
    (reset! switch :b)
    (t/is (= 1 (r/queued-count)) "branch switch queues exactly once")
    (r/flush!)
    (t/is (= 99 @rx))
    (t/is (= 2 @runs))
    (t/is (= #{b switch} (set (:watching (r/reaction-state rx)))) "watch set-diff followed the branch")
    (reset! a 555)
    (r/flush!)
    (t/is (= 2 @runs) "write to dropped dep is silent")
    (t/is (zero? (r/queued-count)))))

(t/deftest test-equal-value-dep-writes-are-silent
  ;; = -gated notification end to end: identical? and structural-equal
  ;; dep writes produce no dirty flag, no queue entry, no re-run.
  (let [{:keys [rx runs ref]} (counting-rx (atom {:n 1}))]
    (r/run! rx)
    (swap! ref identity)
    (r/flush!)
    (t/is (= 1 @runs))
    (reset! ref {:n 1})
    (r/flush!)
    (t/is (= 1 @runs) "structural-equal fresh object stays silent")
    (t/is (zero? (r/queued-count)))))

(t/deftest test-output-watch-fires-only-on-change
  ;; Watchers of the reaction itself fire only when its OUTPUT changed by =.
  (let [a (atom {:n 1})
        notes (atom [])
        rx (r/make-reaction #(rd a))]
    (r/run! rx)
    (r/watch-ref rx :w (fn [_ _ o n] (swap! notes conj [o n])))
    (reset! a {:n 2})
    (r/flush!)
    (t/is (= [[{:n 1} {:n 2}]] @notes))
    (reset! a {:n 2})
    (r/flush!)
    (t/is (= [[{:n 1} {:n 2}]] @notes) "equal output → no notification")))

(t/deftest test-mid-run-invalidation-converges
  ;; A dep written while the body executes must not cache a stale result:
  ;; the pass re-runs against the fresh state. (First-ever runs can't see
  ;; mid-writes of deps whose watches aren't registered yet — prime once.)
  (let [x (atom :a)
        gate (atom false)
        runs (atom 0)
        rx (r/make-reaction (fn []
                              (swap! runs inc)
                              (let [v (rd x)]
                                (when @gate
                                  (reset! x :b))
                                v)))]
    (r/run! rx)                       ; prime: registers the watch on x
    (t/is (= 1 @runs))
    (reset! gate true)
    (reset! x :a2)                    ; dirty the reaction so the drain recomputes
    (r/flush!)                        ; convergence loop: pass 2 writes x mid-run,
    (t/is (= :b @rx) "converged value reflects the mid-run write")
    (t/is (= 3 @runs) "pass 3 converged against the fresh state")
    (t/is (= :b @rx))
    (t/is (= 3 @runs) "equal-value self-write stays silent; reads are cached")))

;; ═══════════════════════════════════════════════════════════════════════════
;; Queue / scheduling
;; ═══════════════════════════════════════════════════════════════════════════

(t/deftest test-async-first-deref-and-exactly-once-drain
  ;; Reagent's classic shape: first deref outside a context queues the run
  ;; and returns nil; one flush runs the body exactly once; further flushes
  ;; are no-ops.
  (let [runs (atom 0)
        rx (r/make-reaction (fn []
                              (swap! runs inc)
                              :v))]
    (t/is (nil? @rx))
    (t/is (= 1 (r/queued-count)))
    (t/is (zero? @runs))
    (r/flush!)
    (t/is (= :v @rx))
    (t/is (= 1 @runs))
    (r/flush!)
    (t/is (= 1 @runs) "clean reaction is skipped by later flushes")))

(t/deftest test-chained-reactions-settle-within-one-flush
  ;; out ← mid ← src: one write fans out through both levels inside a single
  ;; flush!, each body running exactly once.
  (let [src (atom 1)
        mid-runs (atom 0)
        out-runs (atom 0)
        mid (r/make-reaction (fn [] (swap! mid-runs inc) (inc (rd src))))
        out (r/make-reaction (fn [] (swap! out-runs inc) (inc (rd mid))))]
    (r/run! mid)
    (r/run! out)
    (reset! src 10)
    (r/flush!)
    (t/is (= 11 @mid))
    (t/is (= 12 @out))
    (t/is (= 2 @mid-runs) "initial + one flush run")
    (t/is (= 2 @out-runs))))

(t/deftest test-fan-out-one-write-two-reactions
  ;; One dep, two subscribers: a single write re-runs each exactly once.
  (let [src (atom 0)
        a-runs (atom 0)
        b-runs (atom 0)
        ra (r/make-reaction (fn [] (swap! a-runs inc) (* 2 (rd src))))
        rb (r/make-reaction (fn [] (swap! b-runs inc) (+ 100 (rd src))))]
    (r/run! ra)
    (r/run! rb)
    (reset! src 1)
    (r/flush!)
    (t/is (= 2 @ra))
    (t/is (= 101 @rb))
    (t/is (= 2 @a-runs))
    (t/is (= 2 @b-runs))))

(t/deftest test-n-invalidations-collapse-to-one-run
  (let [{:keys [rx runs ref]} (counting-rx (atom 0))]
    (r/run! rx)
    (reset! ref 1)
    (reset! ref 2)
    (reset! ref 3)
    (t/is (= 1 (r/queued-count)) "membership dedupe")
    (r/flush!)
    (t/is (= 3 @rx))
    (t/is (= 2 @runs) "three writes between frames, one body run")))

(t/deftest test-dispose-while-queued-is-silent
  ;; Dirty + enqueued + disposed: the drain must skip it without error and
  ;; leave nothing stuck in the queue.
  (let [{:keys [rx runs ref]} (counting-rx (atom 1))]
    (r/run! rx)
    (reset! ref 2)                 ; dirty + queued
    (r/dispose! rx)
    (r/flush!)                     ; must not run the body, must not throw
    (t/is (zero? (r/queued-count)))
    (t/is (= 1 @runs))))

(t/deftest test-throwing-reaction-does-not-strand-siblings
  ;; A throwing entry surfaces its error AFTER the batch drained — its
  ;; siblings still ran. The thrower lands in :failed: sticky, NOT retried
  ;; by later flushes — recovery needs a new dep change or an explicit
  ;; run!.
  (let [flag (atom true)
        shared (atom 0)
        boom-runs (atom 0)
        boom (r/make-reaction (fn []
                                (swap! boom-runs inc)
                                (if @flag
                                  (throw (ex-info "boom" {}))
                                  (rd shared))))
        ok-runs (atom 0)
        ok (r/make-reaction (fn [] (swap! ok-runs inc) (inc (rd shared))))]
    ;; prime both with flag false so watches register
    (reset! flag false)
    (r/run! boom)
    (r/run! ok)
    (t/is (zero? @boom))
    (reset! flag true)
    (reset! shared 1)              ; queues both, boom first (registered first)
    (t/is (thrown? Exception (r/flush!)) "the error still surfaces")
    (t/is (= 2 @ok-runs) "sibling ran despite the thrower")
    (t/is (zero? (r/queued-count)) "queue fully drained")
    (t/is (= 2 @boom-runs) "thrower ran once, failed sticky")
    ;; Sticky: a later flush with no NEW change does not retry it...
    (r/flush!)
    (t/is (= 2 @boom-runs))
    ;; ...an explicit run! does:
    (reset! flag false)
    (t/is (= 1 (r/run! boom)))
    (t/is (= 3 @boom-runs))))

(t/deftest test-flush-is-reentrancy-safe
  ;; A reaction body calling flush! while the queue drains must not recurse
  ;; the drain (CAS guard); everything still settles exactly once.
  (let [a (atom 1)
        inner-runs (atom 0)
        inner (r/make-reaction (fn []
                                 (swap! inner-runs inc)
                                 (rd a)))
        _ (r/run! inner)
        trigger (r/make-reaction (fn []
                                   (rd a)
                                   (r/flush!) ; no-op: the outer drain holds the lock
                                   :done))]
    (r/run! trigger)
    (reset! a 2) ; queues inner + trigger
    (r/flush!)
    (t/is (= 2 @inner-runs) "initial run + one flush run — nested flush added none")
    (t/is (= 2 (r/run! inner)))))

;; ═══════════════════════════════════════════════════════════════════════════
;; Plain-atom interop (the pivot's core claim)
;; ═══════════════════════════════════════════════════════════════════════════

(t/deftest test-ratom-returns-a-plain-atom
  (let [ra (r/ratom 5)]
    (t/is (instance? clojure.lang.Atom ra))
    (t/is (= 5 @ra))
    (swap! ra inc)
    (t/is (= 6 @ra))))

(t/deftest test-existing-setter-fns-work-on-tracked-inputs
  ;; The interchangeability story, post-pivot: deps ARE existing atoms, so
  ;; setter fns written against plain atoms drive reactions unchanged.
  (let [record {:text (atom "x")}
        set-text! #(reset! (:text %1) %2)
        runs (atom 0)
        rx (r/make-reaction (fn [] (swap! runs inc) (rd (:text record))))]
    (r/run! rx)
    (set-text! record "y")
    (r/flush!)
    (t/is (= "y" @rx))
    (t/is (= 2 @runs))))

;; ═══════════════════════════════════════════════════════════════════════════
;; Component-body capture — the money test
;; ═══════════════════════════════════════════════════════════════════════════

(t/deftest test-component-render-body-is-reactive
  ;; A real Text component rendered inside a reaction: track! routes its
  ;; @reads through tracked-deref, which feeds *ratom-context*, so the
  ;; plain text-set! call marks the reaction dirty — zero migration.
  (let [c (text/make-text "a" 0 0)
        runs (atom 0)
        rx (r/make-reaction (fn []
                              (swap! runs inc)
                              (core/render c 20)))]
    (r/run! rx)
    (t/is (= 1 @runs))
    (text/text-set! c "b")
    (t/is (pos? (r/queued-count)) "component setter dirtied the enclosing reaction")
    (r/flush!)
    (t/is (= 2 @runs))
    (t/is (.contains (first (core/render c 20)) "b"))
    (text/text-set! c "b")
    (t/is (zero? (r/queued-count)) "equal-value setter stays silent")))

(t/deftest test-bare-deref-inside-body-is-untracked-but-correct
  ;; Documented coverage contract: a bare @plain-atom read is NOT captured
  ;; (no dirty, no re-run) — correct under the batched fallback, just not
  ;; narrow. Reads go through tracked-deref to be tracked.
  (let [tracked-ref (atom 1)
        bare-ref (atom 100)
        runs (atom 0)
        rx (r/make-reaction (fn []
                              (swap! runs inc)
                              [(rd tracked-ref) @bare-ref]))]
    (r/run! rx)
    (reset! bare-ref 200)
    (r/flush!)
    (t/is (= 1 @runs) "bare read captured nothing")
    (reset! tracked-ref 2)
    (r/flush!)
    (t/is (= 2 @runs))
    (t/is (= [2 200] (r/run! rx)) "untracked read still yields the current value")))

;; ═══════════════════════════════════════════════════════════════════════════
;; Tracks, cursors, nesting, disposal
;; ═══════════════════════════════════════════════════════════════════════════

(t/deftest test-track-is-lazy-and-caches-until-dirty
  ;; track runs lazily on first deref, caches between changes — and like
  ;; Reagent's manual reactions it JOINS THE QUEUE on dep change, so an
  ;; unread track still refreshes at the next flush.
  (let [src (atom 1)
        runs (atom 0)
        tr (r/track (fn [] (swap! runs inc) (inc (rd src))))]
    (t/is (zero? @runs) "creation runs nothing")
    (t/is (zero? (r/queued-count)))
    (t/is (= 2 @tr))
    (t/is (= 2 @tr) "clean cached deref does not re-run")
    (t/is (= 1 @runs))
    (reset! src 10)
    (t/is (= 1 @runs) "change alone: nothing yet")
    (t/is (= 1 (r/queued-count)) "manual tracks enqueue like Reagent's")
    (r/flush!)
    (t/is (= 11 @tr) "flush refreshed the unread track")
    (t/is (= 2 @runs))))

(t/deftest test-cursor-follows-source-and-composes
  (let [state (atom {:profile {:name "ada"}})
        name-cur (r/cursor state [:profile :name])
        shout (r/make-reaction (fn [] (str/upper-case (rd name-cur))))
        pre-cur (do (r/run! name-cur) (r/run! name-cur))
        pre-shout (do (r/run! shout) (r/run! shout))]
    (t/is (= "ada" pre-cur))
    (t/is (= "ADA" pre-shout))
    (reset! state {:profile {:name "grace"}})
    (r/flush!)
    (t/is (= "grace" (r/run! name-cur)) "cursor followed the source")
    (t/is (= "GRACE" (r/run! shout)) "cursor-as-dep propagated through the reaction"))
  (let [state (atom {:a {:b 7}})
        cur (r/cursor state :a)]
    (t/is (= {:b 7} (r/run! cur)) "bare-key path form")))

(t/deftest test-reset-in-reaction-converges
  ;; Ported from reagent's reset-in-reaction: a body writing back to its own
  ;; source atom must converge, not loop.
  (let [state (atom {})
        c1 (r/make-reaction #(get-in (rd state) [:data :a]))
        c2 (r/make-reaction #(get-in (rd state) [:data :b]))
        runs (atom 0)
        rxn (r/make-reaction (fn []
                               (swap! runs inc)
                               ;; (or cc 0): Reagent's original relies on JS
                               ;; coercing null to 0 — JVM needs the guard.
                               (let [cc1 (or (rd c1) 0)
                                     cc2 (or (rd c2) 0)]
                                 (swap! state assoc :derived (+ cc1 cc2))
                                 nil)))]
    (r/run! rxn)
    (t/is (= (:derived @state) 0))
    (swap! state assoc :data {:a 1 :b 2})
    (r/flush!)
    (t/is (= (:derived @state) 3))
    (swap! state assoc :data {:a 11 :b 22})
    (r/flush!)
    (t/is (= (:derived @state) 33))
    (r/dispose! rxn)
    (t/is (< @runs 10) "converged without runaway")))

(t/deftest test-exception-recover-indirect
  ;; Ported from reagent's exception-recover-indirect. Our contract differs
  ;; where the sync/queued models diverge: a FAILED upstream produces no new
  ;; output, so downstream reactions don't re-run until its output actually
  ;; changes again — failure does not propagate as a value.
  (let [state (atom 1)
        ref-runs (atom 0)
        ref (r/make-reaction (fn []
                               (swap! ref-runs inc)
                               (when (= (rd state) 2)
                                 (throw (ex-info "err" {})))))
        runner-runs (atom 0)
        rnr (r/make-reaction (fn []
                               (swap! runner-runs inc)
                               (rd ref)))]
    (r/run! rnr)
    (t/is (= 1 @runner-runs))
    (swap! state inc)                    ; → 2: upstream will throw
    (t/is (thrown? Exception (r/flush!)))
    (t/is (= 2 @ref-runs) "upstream ran and failed")
    (t/is (= :failed (:state (r/reaction-state ref))))
    (t/is (thrown? Exception @ref) "sticky on deref")
    (t/is (= 1 @runner-runs) "no output change → downstream untouched")
    (swap! state inc)                    ; → 3: recovery
    (r/flush!)
    (t/is (= 3 @ref-runs))
    (t/is (nil? (rd ref)))
    ;; ref's output went nil → nil: still no CHANGE for the runner
    (t/is (= 1 @runner-runs))
    (t/is (nil? @rnr))))

(t/deftest test-nested-reactions-self-record
  ;; Derefing a reaction inside another body records it as a dependency via
  ;; its own deref implementation.
  (let [src (atom 1)
        inner (r/make-reaction #(rd src))
        outer-runs (atom 0)
        outer (r/make-reaction (fn [] (swap! outer-runs inc) (* 2 (rd inner))))]
    (r/run! inner)
    (r/run! outer)
    (reset! src 21)
    (r/flush!)
    (t/is (= 42 @outer))
    (t/is (= 2 @outer-runs))))

(t/deftest test-dispose-stops-tracking-and-idempotent
  (let [{:keys [rx runs ref]} (counting-rx (atom 1))]
    (r/run! rx)
    (r/dispose! rx)
    (r/dispose! rx)
    (reset! ref 2)
    (r/flush!)
    (t/is (zero? (r/queued-count)))
    (t/is (= 1 @runs))
    (t/is (nil? @rx) "disposed deref returns nil")))

(t/deftest test-dispose-during-run-stays-dead
  ;; A dep watcher disposing the reaction mid-run (watch-fire order can put
  ;; it before the reaction's own handler; a background thread can land it
  ;; inside the body): no resurrection, no watch re-registration, no value
  ;; write, no further body execution, derefs stay nil.
  (let [x (atom 1)
        runs (atom 0)
        rx (r/make-reaction (fn []
                              (swap! runs inc)
                              (* 10 (rd x))))]
    (r/run! rx)
    (add-watch x :killer (fn [_ _ _ _] (r/dispose! rx)))
    (try
      (reset! x 2)                   ; killer fires, then rx's own handler
      (finally (remove-watch x :killer)))
    (t/is (= :disposed (:state (r/reaction-state rx))) "handler must not resurrect")
    (t/is (= 1 @runs))
    (t/is (zero? (r/queued-count)))
    (t/is (nil? (r/run! rx)) "force-run on disposed is a no-op")
    (t/is (= 1 @runs) "still no body execution")
    ;; A later write must not re-enqueue or re-run anything.
    (reset! x 3)
    (t/is (zero? (r/queued-count)))
    (t/is (nil? @rx))
    (t/is (= 1 @runs))))

(t/deftest test-in-context-flag
  (t/is (false? (r/in-context?)))
  (let [seen (atom nil)
        rx (r/make-reaction (fn [] (reset! seen (r/in-context?)) :ok))]
    (r/run! rx)
    (t/is (true? @seen))
    (t/is (false? (r/in-context?)) "context unwound after the run")))

(t/deftest test-body-exception-propagates-and-reaction-stays-retryable
  (let [fail? (atom true)
        runs (atom 0)
        rx (r/make-reaction (fn []
                              (swap! runs inc)
                              (if @fail? (throw (ex-info "boom" {})) :ok)))]
    (t/is (thrown? Exception (r/run! rx)))
    (t/is (thrown? Exception (r/run! rx)) "explicit run! retries")
    (reset! fail? false)
    (t/is (= :ok (r/run! rx)))
    (t/is (= 3 @runs))))

(t/deftest test-caught-error-sticky-until-change
  ;; Reagent's caught: a failure captured during a queued run is rethrown by
  ;; every deref WITHOUT re-executing the body; the next dep change clears
  ;; it. A read must never re-run side effects.
  (let [src (atom :good)
        runs (atom 0)
        rx (r/make-reaction (fn []
                              (swap! runs inc)
                              (if (= :bad (rd src))
                                (throw (ex-info "bad state" {}))
                                (str "ok:" (rd src)))))]
    (r/run! rx)
    (reset! src :bad)
    (t/is (thrown? Exception (r/flush!)) "the error surfaces from the drain")
    (let [after-fail @runs]          ; the failing run itself counts
      (t/is (thrown? Exception @rx) "deref rethrows")
      (t/is (thrown? Exception @rx) "and again")
      (t/is (= after-fail @runs) "body never re-ran for a mere read")
      (reset! src :good)
      (r/flush!)
      (t/is (= "ok::good" @rx) "keyword prints its colon")
      (t/is (= (+ after-fail 1) @runs) "recovered on the next change"))))

(t/deftest test-auto-run-callback-scheduling
  ;; :auto-run? fn = ComponentFn's future hook: dep changes invoke the
  ;; callback with the reaction instead of queueing — the callback owns
  ;; scheduling (invalidate + request frame).
  (let [src (atom 1)
        fired (atom [])
        body-runs (atom 0)
        rx (r/make-reaction (fn []
                              (swap! body-runs inc)
                              (rd src))
                            {:auto-run? (fn [reaction]
                                          (swap! fired conj reaction))})]
    (r/run! rx)
    (reset! src 2)
    (t/is (= 1 (count @fired)) "callback invoked once per change")
    (t/is (identical? rx (first @fired)) "with the reaction itself")
    (t/is (zero? (r/queued-count)) "nothing queued — callback owns scheduling")
    (t/is (= 1 @body-runs) "engine did not re-run the body")
    (t/is (= 2 (r/run! rx)) "explicit run! still brings it current")
    (t/is (= 2 @body-runs))))

(t/deftest test-on-dispose-and-branch-auto-dispose
  ;; Ported from reagent's test-dispose/test-add-dispose: on-dispose
  ;; callbacks fire when a reaction dies — including the branch-switch case,
  ;; where dropping the last reader auto-disposes an intermediate manual
  ;; reaction.
  (let [a (atom 0)
        b-disposed (atom false)
        c-disposed (atom false)
        b-runs (atom 0)
        b (r/make-reaction (fn []
                             (swap! b-runs inc)
                             (inc (rd a)))
                           {:auto-run? false
                            :on-dispose (fn [_] (reset! b-disposed true))})
        c (r/make-reaction (fn []
                             (if (< (rd a) 1)
                               (rd b)
                               (dec (rd a))))
                           {:auto-run? false
                            :on-dispose (fn [_] (reset! c-disposed true))})
        res (atom nil)
        cns (r/make-reaction (fn [] (reset! res (rd c))))]
    (r/run! cns)
    (t/is (= @res 1))
    (t/is (= 1 @b-runs))
    (reset! a -1)
    (r/flush!)
    (t/is (= @res 0))
    (t/is (= false @b-disposed) "b still read on this branch")
    (reset! a 2)
    (r/flush!)
    (t/is (= @res 1))
    (t/is (= true @b-disposed) "branch dropped b → last watcher gone → auto-disposed")
    (t/is (= false @c-disposed) "c still alive")
    (r/dispose! cns)
    (t/is (= true @c-disposed)))
  ;; add-on-dispose!: registered callbacks fire with the reaction, after it
  ;; is inert.
  (let [a (atom 0)
        seen (atom [])
        rx (r/make-reaction #(rd a))]
    (r/run! rx)
    (r/add-on-dispose! rx (fn [reaction] (swap! seen conj [:first reaction])))
    (r/add-on-dispose! rx (fn [_] (swap! seen conj :second)))
    (r/dispose! rx)
    ;; NOTE: structural = over vectors containing reactions is unreliable in
    ;; bb (sealed hashCode on SCI classes) — assert by count and identity.
    (t/is (= 2 (count @seen)))
    (t/is (identical? rx (second (first @seen))))
    (t/is (= :first (first (first @seen))))
    (t/is (= :second (last @seen)))
    (t/is (= :disposed (:state (r/reaction-state rx))))
    (t/is (nil? @rx))))

(t/deftest test-many-watchers-survive-hash-transition
  ;; Transcript-scale guard: an atom's internal watch map upgrades
  ;; array-map→hash-map past 8 entries, and hashing our reify'd reactions is
  ;; sealed in bb. Watch keys therefore go through RxKey records — 20
  ;; reactions on ONE atom must all fire, exactly once each.
  (let [src (atom 0)
        runs (vec (repeatedly 20 #(atom 0)))
        rxs (mapv (fn [counter]
                    (r/make-reaction (fn []
                                       (swap! counter inc)
                                       (rd src))))
                  runs)]
    (doseq [rx rxs] (r/run! rx))
    (reset! src 1)
    (r/flush!)
    (doseq [i (range 20)]
      (t/is (= 2 @(nth runs i)) (str "prime + one flush run, watcher #" i)))
    (doseq [rx rxs]
      (t/is (= 1 (rd rx))))))

(t/deftest test-watch-ref-handles-both-kinds
  (let [a (atom 0)
        notes (atom [])
        rx (r/make-reaction #(rd a))]
    (r/run! rx)
    (r/watch-ref a :plain (fn [_ _ o n] (swap! notes conj [:atom o n])))
    (r/watch-ref rx :rx (fn [_ _ o n] (swap! notes conj [:reaction o n])))
    (reset! a 1)
    (r/flush!)
    (t/is (= [[:atom 0 1] [:reaction 0 1]] @notes))
    (r/unwatch-ref a :plain)
    (r/unwatch-ref rx :rx)
    (reset! a 2)
    (r/flush!)
    (t/is (= [[:atom 0 1] [:reaction 0 1]] @notes) "watches removed")))

;; ═══════════════════════════════════════════════════════════════════════════
;; with-let store primitives (macros.clj)
;; ═══════════════════════════════════════════════════════════════════════════

(t/deftest test-store-fetch-initializes-once-per-site
  (let [store (macros/new-store)
        inits (atom 0)
        init #(do (swap! inits inc) :value)
        site 'site-1]
    (macros/with-store store
      (t/is (= :value (macros/fetch-local site init)))
      (t/is (= :value (macros/fetch-local site init)))
      (t/is (= :value (macros/fetch-local site init)))
      (t/is (= 1 @inits) "body re-runs, init does not")
      ;; a different site gets its own slot
      (t/is (= :other (macros/fetch-local 'site-2 (constantly :other)))))))

(t/deftest test-stores-are-isolated
  (let [s1 (macros/new-store)
        s2 (macros/new-store)]
    (macros/with-store s1
      (macros/fetch-local 'k (constantly :one)))
    (macros/with-store s2
      (t/is (= :two (macros/fetch-local 'k (constantly :two)))))))

(t/deftest test-cleanup-registers-once-runs-lifo
  (let [store (macros/new-store)
        log (atom [])]
    (macros/with-store store
      (dotimes [_ 50] ; body reruns must not grow the cleanup list
        (macros/register-cleanup! 'conn #(swap! log conj :close-conn)))
      (macros/register-cleanup! 'timer #(swap! log conj :stop-timer)))
    (t/is (empty? @log) "cleanups fire only at destroy")
    (macros/destroy-store! store)
    (t/is (= [:stop-timer :close-conn] @log) "LIFO, registered once despite 50 passes")
    (macros/destroy-store! store)
    (t/is (= [:stop-timer :close-conn] @log) "destroy is idempotent")))

(t/deftest test-store-outside-binding-throws
  (t/is (thrown? Exception (macros/fetch-local 'k (constantly 1))))
  (t/is (thrown? Exception (macros/register-cleanup! 'k (fn [])))))

(t/deftest test-destroy-store-survives-throwing-cleanup
  (let [store (macros/new-store)
        log (atom [])]
    (macros/with-store store
      (macros/register-cleanup! 'good #(swap! log conj :good))
      (macros/register-cleanup! 'bad #(throw (ex-info "cleanup boom" {}))))
    (macros/destroy-store! store)
    (t/is (= [:good] @log) "throwing cleanup doesn't block the rest")))
