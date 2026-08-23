(ns kmet.tui.reagent
  "Reactive engine for the TUI DSL: a Babashka port of reagent.ratom's
   semantics — auto-dependency-discovering reactions, cursors, batching —
   with plain clojure.lang.Atoms as first-class inputs (dsl.md §2.8,
   §2.8-bb).

   There is no custom atom type: Babashka seals IWatchable/IReset/IRef away
   from pure-source implementations, so a drop-in RAtom cannot exist.
   Dependency capture rides the existing deref funnel instead —
   kmet.tui.macros/tracked-deref, through which every component render body
   already routes its @reads via track! — extended to feed *ratom-context*.
   Reactions and cursors are reify'd IDeref refs carrying their own watcher
   registries; they record themselves into an enclosing reaction from their
   own deref, and are watched/disposed through watch-ref/unwatch-ref (core
   add-watch cannot take them — they are not IRefs).

   Coverage contract: tracked reads are component render bodies (automatic),
   explicit tracked-deref calls, and nested reaction/cursor derefs
   (automatic). A bare @plain-atom inside a hand-written body is untracked —
   correct under the batched fallback, just not narrow.

   Scheduling: a reaction whose deps change (by =) is marked dirty and
   ENQUEUED; flush! drains the queue and runs each dirty reaction exactly
   once per pass. Deref OUTSIDE any reaction settles the queue first and
   always answers with the CURRENT value (Reagent's non-reactive deref:
   flush, then inline-run when dirty) — the queued path exists purely for
   dep-change re-runs between frames. kmet.tui.core calls flush! from the
   render loop's ~16ms tick — Reagent's animation-frame batching.

   Examined against reagent.ratom (2.0.1) and rum's derived-atom/cursor
   (battle-tested references, ~/src/cvstree/). Adopted from them: sticky
   caught errors (a failed body's exception is rethrown by derefs without
   re-execution; run! retries; the next dep change clears it), callback
   scheduling (:auto-run? fn → ComponentFn's invalidate hook), manual-track
   value caching, last-watcher auto-dispose for manual reactions, and run!
   flushing the queue first. One deliberate deviation from current Reagent:
   plain reactions re-run QUEUED at the frame tick, not synchronously in the
   watch handler — coalescing matters at streaming write rates, and
   Reagent's own component path (the one Stage 3 mirrors) is callback-based
   either way."
  (:require [kmet.tui.macros :as macros :refer [-add-watch -remove-watch
                                                -cell -dispose -force-run]])
  (:refer-clojure :exclude [run!]))

;; ═══════════════════════════════════════════════════════════════════════════
;; Context
;; ═══════════════════════════════════════════════════════════════════════════

(defn in-context?
  "True when called inside a running reaction body (derefs here become
   tracked dependencies of that reaction)."
  []
  (some? macros/*ratom-context*))

(defn tracked-deref
  "Deref REF with dependency capture — the entry point for reactive code
   outside component render bodies (which get it via the track! rewrite).
   Delegates to kmet.tui.macros/tracked-deref, which records into both the
   track! render scope and any running reaction."
  [ref]
  (macros/tracked-deref ref))

;; ═══════════════════════════════════════════════════════════════════════════
;; Universal watching — plain atoms take the core path, library refs the
;; internal registry (SCI exposes no IWatchable implementation to hook)
;; ═══════════════════════════════════════════════════════════════════════════

;; The internal ref surface (RXRef) lives in kmet.tui.macros — beside the
;; track! machinery that watches these refs, so record-component renders
;; can subscribe to computes without a require cycle. One name kept here:
(def RXRef macros/RXRef)

(defn reactive-ref?
  "True for the library's own refs (reactions, cursors)."
  [ref]
  (satisfies? RXRef ref))

(defn add-on-dispose!
  "Register F to run when R is disposed; F receives R (Reagent's
   add-on-dispose!). Runs after R is inert — deps unwatched, queue purged.
   This is how compute slices under with-let will unwind their watches
   (Stage 5)."
  [r f]
  (when (reactive-ref? r)
    (swap! (-cell r) update :on-dispose (fnil conj []) f))
  r)

(defn watch-ref
  "add-watch for any reactive ref: plain atoms go through
   clojure.core/add-watch, library refs (reactions, cursors) through their
   internal registry. F receives (key ref old-val new-val) either way."
  [ref key f]
  (if (reactive-ref? ref)
    (-add-watch ref key f)
    (add-watch ref key f)))

(defn unwatch-ref
  "remove-watch counterpart of watch-ref."
  [ref key]
  (if (reactive-ref? ref)
    (-remove-watch ref key)
    (remove-watch ref key)))

;; ═══════════════════════════════════════════════════════════════════════════
;; Batch queue — drained by flush! from the render-loop tick
;; ═══════════════════════════════════════════════════════════════════════════

(def ^:private max-rounds 100)

(defonce ^:private queue (atom []))

(defonce ^:private flushing? (atom false))

(defn- enqueue!
  "Add R to the batch queue unless already present (N invalidations between
   frames collapse into one run)."
  [r]
  (let [add (fn [q]
              (if (some (fn [x] (identical? x r)) q)
                q
                (conj q r)))]
    (swap! queue add))
  nil)

(defn- take-batch!
  "Atomically remove and return everything currently queued."
  ;; swap! returns the NEW value, so capture the old one inside the swap fn.
  []
  (let [captured (volatile! nil)]
    (swap! queue (fn [q]
                   (vreset! captured q)
                   []))
    @captured))

(defn queued-count
  "Number of reactions waiting in the batch queue (tests, debug counters)."
  []
  (count @queue))

(defn flush!
  "Drain the batch queue: bring every queued reaction current, repeating
   until empty (one reaction's run may dirty another — chained updates
   settle within one flush). Throws when the queue does not settle within
   max-rounds passes (cyclic reactions). A throwing reaction surfaces its
   error AFTER the batch finished draining — the rest of the batch still
   runs, so no reaction is left dirty but dequeued. Reactions dirtied by
   batch entries running later stay queued and settle at the next flush.
   Reentrant calls are no-ops; the outer drain wins."
  []
  (when (compare-and-set! flushing? false true)
    (try
      (loop [round 0]
        (let [batch (take-batch!)]
          (when (seq batch)
            ;; Run every entry even if one throws: the batch was already
            ;; dequeued, so bailing early would strand the rest dirty with
            ;; nothing to re-enqueue them. Reagent's _queued-run skip-clean
            ;; check lives here: entries whose state is already current
            ;; (settled synchronously earlier in this drain) don't re-run.
            (let [error (volatile! nil)]
              (doseq [r batch]
                (try
                  (when-not (#{:idle :failed} (:state @(-cell r)))
                    (-force-run r))
                  (catch Throwable e
                    (when-not @error
                      (vreset! error e)))))
              (when-some [e @error]
                (throw e)))
            (when (> round max-rounds)
              (throw (ex-info "kmet.tui.reagent: reaction queue did not settle (cyclic reactions?)"
                              {:rounds round})))
            (recur (inc round)))))
      (finally
        (reset! flushing? false)))))

;; ═══════════════════════════════════════════════════════════════════════════
;; Reaction
;; ═══════════════════════════════════════════════════════════════════════════

;; Cell states: :unrun (created, never executed) → :idle (cached value
;; valid) → :dirty (a dep changed; queued) → :busy (body running right now)
;; → :failed (body threw; :caught holds the ex, sticky until next change or
;; explicit run!). :disposed is terminal. Watches on the reaction itself live in the cell;
;; so does the dependency set (:watching).

;; ═══════════════════════════════════════════════════════════════════
;; Identity plumbing
;; ═══════════════════════════════════════════════════════════════════

;; Babashka seals hashCode/equals on SCI-generated classes: `=` over our
;; reify'd reactions is UNRELIABLE (false negatives even against the object
;; itself via certain dispatch paths). Two consequences, handled here:
;; — reactions must never sit in hash-based collections: :watching is a
;;   VECTOR scanned with identical?, the batch queue likewise;
;; — watch KEYS installed on plain atoms go through a record wrapper
;;   (structural hash/equals on a unique uid): an atom's internal watch map
;;   upgrades array-map→hash-map past 8 watchers, and hashing a raw reify
;;   would detonate right at transcript scale (per-message subscriptions).
(defrecord RxKey [uid])

(defn- identical-member?
  "True when COLL contains REF by identity."
  [coll ref]
  (boolean (some #(identical? % ref) coll)))

(defn changed?
  "Reagent-style change gate: identical? fast path, structural = otherwise.
   Public because tests and future engine extensions need the same gate."
  [a b]
  (not (or (identical? a b) (= a b))))

(defn make-reaction
  "Wrap F in a reaction: running it captures every tracked deref as a
   dependency; when a dependency changes by =, F is marked dirty and brought
   current at the next flush!. Watchers registered through watch-ref fire
   only when F's OUTPUT changes by =.
   Options:
     :auto-run? controls scheduling (Reagent's :auto-run):
       true  (default) — dep changes enqueue F for the next flush!;
               derefs outside a reaction always answer CURRENT (they
               settle the queue and sync-run unrun/dirty bodies)
       false — manual track: also enqueued on change (like Reagent), but
               caches its value between runs, runs lazily on first deref,
               and disposes itself when its last watcher is removed
       ifn   — callback scheduling (reagent.impl.component's shape): the fn
               receives the reaction instead of any queueing — the ComponentFn
               hook, where the callback invalidates the component and requests
               a frame
     :rerun-without-deps? (default false) — when a run collects NO tracked
       dependencies beyond :implicit-deps, leave the reaction :unrun so the
       next deref re-runs it (batched-mode fallback). This keeps bodies that
       read only UNTRACKED values (bare @plain-atom in a hand-written fn,
       static trees) never stale: they re-derive every pass exactly as
       pre-reaction code did, instead of caching once forever. A body with
       at least one real tracked dep is cached normally — mixed bodies must
       read their reactive inputs through tracked-deref/cursors/slices
       (coverage contract, dsl.md §2.8-bb).
     :implicit-deps — collection of refs to ignore in the emptiness check
       (the framework's own seeded reads, e.g. ComponentFn's props/ctree)"
  ([f] (make-reaction f nil))
  ([f {:keys [auto-run? rerun-without-deps? implicit-deps]
       :or {auto-run? true} :as _opts}]
   ;; The closures below need the reaction VALUE they collectively
   ;; constitute, but r is only complete once the reify form evaluates;
   ;; every closure runs strictly later (watch callbacks, body runs), so
   ;; they read the finished value through this holder.
   (let [cell (atom {:f f :state :unrun :value nil :watching []
                     :watches {} :caught nil
                     :on-dispose (if-some [od (:on-dispose _opts)]
                                   [od]
                                   [])})
         ;; set of the framework's own reads to ignore; (set nil) = #{}
         exempt? (set implicit-deps)
         self (atom nil)
         watch-key (RxKey. (gensym "rx"))
         dep-handler
         (fn [_key _ref old new]
           ;; Reagent's _handle-change gate: identical fast path, structural
           ;; =, skip if already dirty or dead. A disposed reaction stays
           ;; dead: watches can still fire once after dispose (watch-fire
           ;; order vs the unwatch), and must not resurrect it. The body is
           ;; isolated — it runs on the MUTATOR's thread (any background
           ;; thread swapping app state), so a failure here must never
           ;; propagate into whoever wrote the dep.
           (try
             (when (and (changed? old new)
                        (not= :dirty (:state @cell))
                        (not= :disposed (:state @cell)))
               (swap! cell assoc :state :dirty :caught nil)
               (if (fn? auto-run?)
                 (auto-run? @self)
                 (enqueue! @self)))
             (catch Throwable e
               (binding [*out* *err*]
                 (println "kmet.tui.reagent dep-handler error:" (.getMessage e))))))
         update-watching!
         (fn [collected]
           ;; Set-diff (Reagent's _update-watching), identity-flavored:
           ;; watch newly-captured refs, unwatch refs this run stopped
           ;; reading. VECTOR + identical? — see Identity plumbing above.
           (let [{:keys [watching]} @cell
                 added (remove #(identical-member? watching %) collected)
                 dropped (filter #(and (identical-member? watching %)
                                       (not (identical-member? collected %)))
                                 watching)]
             (doseq [dep added]
               (watch-ref dep watch-key dep-handler))
             (doseq [dep dropped]
               (unwatch-ref dep watch-key))
             (swap! cell assoc :watching (vec collected))))
         run-sync!
         (fn []
           (loop [guard 0]
             (let [{:keys [state value] :as snap} @cell]
               (cond
                 (= :disposed state) nil
                 ;; Reentrant deref of R inside R's own body: hand back the
                 ;; value being replaced rather than recursing forever.
                 (= :busy state) value
                 (> guard max-rounds)
                 (throw (ex-info "kmet.tui.reagent: reaction did not converge after repeated mid-run invalidation"
                                 {:rounds guard}))
                 :else
                 ;; Fresh re-check: dispose may have landed between the
                 ;; loop-top read and here — never execute a dead body.
                 (if (= :disposed (:state @cell))
                   nil
                   (let [_ (swap! cell assoc :state :busy)
                         ;; Mark BUSY before the body runs: a :dirty seen AFTER
                         ;; the pass can then only mean a dep was written while
                         ;; the body executed (mid-run invalidation), never the
                         ;; stale flag this run was started for.
                         pending (atom #{})
                         result (try
                                  (binding [macros/*ratom-context* {:pending pending :self @self}]
                                    ((:f snap)))
                                  (catch Throwable e
                                    ;; Body threw: sticky failure (Reagent's
                                    ;; caught) — derefs rethrow it without
                                    ;; re-executing the body; an explicit
                                    ;; run! retries; the next dep change
                                    ;; clears it.
                                    (swap! cell assoc :state :failed :caught e)
                                    (throw e)))
                         collected @pending]
                     ;; Disposed while the body ran: keep it dead — no watch
                     ;; re-registration, no value write, nothing observable.
                     (if (= :disposed (:state @cell))
                       nil
                       (do (update-watching! collected)
                           (swap! cell assoc :value result :caught nil
                                  ;; A dep written while the body ran left :dirty
                                  ;; behind — loop to re-run against fresh state.
                                  :state (cond
                                           (= :dirty (:state @cell)) :dirty
                                           ;; Batched fallback: nothing tracked was
                                           ;; read beyond the framework's own seeded
                                           ;; deps, so this cached value could never
                                           ;; be invalidated — re-run on next deref
                                           ;; instead of caching stale output.
                                           (and rerun-without-deps?
                                                (empty? (remove exempt? collected))) :unrun
                                           :else :idle))
                           (when (changed? result value)
                             ;; Watcher isolation: a throwing watcher must
                             ;; not abort its siblings nor propagate into
                             ;; whatever thread triggered the change.
                             (doseq [[key fl] (:watches @cell)]
                               (try
                                 (fl key @self value result)
                                 (catch Throwable e
                                   (binding [*out* *err*]
                                     (println "kmet.tui.reagent watcher error:"
                                              (.getMessage e)))))))
                           (if (= :dirty (:state @cell))
                             (recur (inc guard))
                             result)))))))))
         deref-fn
         (fn []
           (let [{:keys [state value caught]} @cell]
             (cond
               (= :disposed state) nil
               ;; Sticky failure: rethrow the captured error without
               ;; re-executing the body (Reagent's caught) — a read must
               ;; never re-run side effects. run! is the explicit retry.
               (some? caught) (throw caught)
               (= :busy state) value
               :else (do
                       ;; Non-reactive reads settle the queue FIRST (Reagent
                       ;; flushes on every non-reactive deref): upstream
                       ;; reactions dirtied since the last frame must be
                       ;; current before this ref answers, or chained reads
                       ;; see a stale slice. The flush may run THIS reaction
                       ;; too (it can sit queued), so re-read after.
                       (when (nil? macros/*ratom-context*)
                         (flush!))
                       (let [{:keys [state value caught]} @cell]
                         (cond
                           (= :disposed state) nil
                           (some? caught) (throw caught)
                           (= :busy state) value
                           ;; Cached-valid — plain reactions AND manual tracks
                           ;; hand back without running (Reagent's Track caches
                           ;; its inner reaction); an in-context parent reading
                           ;; a clean child must not force a redundant run.
                           (= :idle state) value
                           ;; Everything else runs synchronously: first reads
                           ;; anywhere (unrun — Reagent births reactions dirty),
                           ;; manual tracks' first read, dirty reactions. An
                           ;; in-context parent captured SELF just before (reify
                           ;; deref → capture-deref!), so it tracks this ref.
                           :else (run-sync!)))))))
         r (reify
             RXRef
             (-add-watch [_ key fl] (swap! cell assoc-in [:watches key] fl) _)
             (-remove-watch [_ key]
               ;; Reagent's GC hygiene: when a MANUAL reaction's last watcher
               ;; leaves, it disposes itself — its dep watches would otherwise
               ;; pin it (and them) forever. Plain/callback reactions keep
               ;; running; they are disposed explicitly. (Unlike Reagent,
               ;; kmet's dispose is TERMINAL — a dead reaction never
               ;; resurrects on deref — so anything driven by derefs or the
               ;; auto-run callback must not self-dispose here.)
               (let [was (:watches @cell)]
                 (swap! cell update :watches dissoc key)
                 (when (and (seq was)
                            (empty? (:watches @cell))
                            (false? auto-run?))
                   (-dispose @self)))
               _)
             (-cell [_] cell)
             (-dispose [_]
               (doseq [dep (:watching @cell)]
                 (unwatch-ref dep watch-key))
               ;; Purge from the batch queue too: a disposed reaction must be
               ;; fully inert immediately, not skipped-later (watch-fire order
               ;; relative to dispose is arbitrary — without the purge its own
               ;; dep-handler can enqueue it after it died).
               (swap! queue (fn [q]
                              (filterv (fn [x] (not (identical? x @self))) q)))
               (swap! cell assoc :state :disposed :watching #{}
                      :watches {} :value nil :caught nil)
               ;; Disposers fire last, once the reaction is fully inert;
               ;; each receives the reaction (Reagent contract).
               (doseq [f (:on-dispose @cell)]
                 (f @self))
               nil)
             (-force-run [_]
               ;; Explicit recompute: retries through sticky failures, always
               ;; runs unless disposed. The queue's clean-skip lives in
               ;; flush!, not here.
               (let [{:keys [state]} @cell]
                 (when-not (= :disposed state)
                   (run-sync!))))
             clojure.lang.IDeref
             (deref [_]
               ;; Record R as a dependency of the ENCLOSING reaction, if one
               ;; is running, then produce the value under our own frame.
               (macros/capture-deref! @self)
               (deref-fn)))]
     (reset! self r)
     r)))

(defn reaction?
  "True when X is a reaction or cursor made by this namespace."
  [x]
  (reactive-ref? x))

(defn reaction-state
  "Debugging introspection: {:state :value :watching} of R."
  [r]
  (let [{:keys [state value watching]} @(-cell r)]
    {:state state :value value :watching watching}))

(defn run!
  "Flush the batch queue (so upstream reactions are current), then bring R
   current synchronously — Reagent's run. Retries through sticky failures;
   manual tracks always recompute here (deref is the cached path)."
  [r]
  (flush!)
  (-force-run r))

(defn invalidate!
  "Force R's next deref to re-run its body even though no dependency changed
   (normal caching hands back the value while every dep is unchanged).
   For inputs that shape output without being tracked deps — ComponentFn
   calls this when the render-pass width changes, because *width* is a
   dynamic var, not an atom. Marks R :dirty, so the next deref (or a flush,
   should one land in between) brings it current. Disposed refs are ignored."
  [r]
  (when (reactive-ref? r)
    (swap! (-cell r)
           (fn [{:keys [state] :as c}]
             (if (= :disposed state)
               c
               (assoc c :state :dirty :caught nil)))))
  nil)

(defn dispose!
  "Kill R: unwatch every dependency, drop watchers, leave the queue.
   Later derefs return nil. Idempotent."
  [r]
  (when (reactive-ref? r)
    (-dispose r))
  nil)

;; ═══════════════════════════════════════════════════════════════════════════
;; Public sugar
;; ═══════════════════════════════════════════════════════════════════════════

(defn ratom
  "Create a reactive input atom — a plain clojure.lang.Atom (see the ns
   docstring: bb cannot ship a drop-in RAtom, and none is needed; plain
   atoms ARE the tracked inputs). Provided so (r/atom x) call sites port
   from Reagent unchanged."
  [x]
  (atom x))

(defmacro reaction
  "Form-1 reactive body: (reaction body...) ≡ (make-reaction (fn [] body...)).
   Deps are discovered at deref time — read atoms via tracked-deref (or
   deref other reactions/cursors) inside."
  [& body]
  `(make-reaction (fn [] ~@body)))

(defmacro track
  "Like reaction, but manual (auto-run? false): caches its value between dep
   changes, runs lazily on first deref, and self-disposes when its last
   watcher is removed (Reagent's Track). (track f a b) wraps (f a b)."
  [f & args]
  `(make-reaction (fn [] (~f ~@args)) {:auto-run? false}))

(defn cursor
  "A derived view over SOURCE at PATH: (get-in @source path) with the source
   read captured, so reactions reading the cursor track the source. PATH is
   a seq, or varargs keys. Implemented as an auto-running reaction (the
   Reagent ≥0.10 model)."
  ([source path]
   ;; Accept a seq/vector or a bare key (Reagent-style (cur src k1 k2)):
   ;; normalize so get-in always gets a sequential path.
   (let [path (if (sequential? path) path [path])]
     (make-reaction #(get-in (tracked-deref source) path))))
  ([source k & ks]
   (cursor source (cons k ks))))
