(ns kmet.runner
  "Test runner with per-test slow isolation.

   Slow tests are marked with ^:slow on the deftest (tests that wait real
   wall-clock time: sleeps, terminal-query timeouts; real network calls; and
   subprocess spawns — bash tool, shell commands, git).
   `bb test` runs all tests except ^:slow ones; `bb test-ext` runs only the
   ^:slow tests. Both are selected at the individual test level — no whole
   namespaces are excluded.

   Test namespaces load lazily: the full run requires all of them; a
   filtered run (`bb test test-llm-loaded` or
   `bb test-ext kmet.app.test-loop/test-…`) loads only the namespaces it
   needs, so single-test runs skip most of the require phase."
  (:require [clojure.string :as str]
            [clojure.test :as t]))

(def all-namespaces
  "Every test namespace. The slow/fast split happens per test var via ^:slow
   metadata, so no namespace is excluded from either run."
  '[kmet.test-utils kmet.test-keys
    kmet.app.test-session
    kmet.app.test-compaction
    kmet.app.test-tools
    kmet.ai.test-llm
    kmet.ai.test-attribution
    kmet.ai.test-models
    kmet.app.test-model-resolver
    kmet.ai.test-config-value
    kmet.ai.test-model-config
    kmet.ai.test-provider-composer
    kmet.ai.test-auth
    kmet.ai.test-aws-sigv4
    kmet.ai.test-google-adc
    kmet.ai.test-oauth
    kmet.ai.test-image-models
    kmet.ai.test-model-data
    kmet.ai.test-proxy
    kmet.ai.test-self-contained
    kmet.ai.test-constrained-sampling
    kmet.ai.test-api-tools
    kmet.app.test-loop
    kmet.test-theme kmet.test-config
    kmet.app.test-skills
    kmet.app.test-context
    kmet.app.test-prompts
    kmet.app.test-extensions
    kmet.app.test-extensions-ui
    kmet.app.test-interactive-ui
    kmet.app.test-event-bus
    kmet.app.test-theme-controller
    kmet.app.test-commands
    kmet.app.test-keybindings
    kmet.modes.test-print
    kmet.modes.test-interactive
    kmet.test-editing
    kmet.tui.test-fuzzy
    kmet.tui.test-autocomplete
    kmet.tui.test-core
    kmet.tui.components.test-text
    kmet.tui.components.test-spacer
    kmet.tui.components.test-container
    kmet.tui.components.test-box
    kmet.tui.components.test-input
    kmet.tui.components.test-editor
    kmet.tui.components.test-select-list
    kmet.tui.components.test-settings-list
    kmet.tui.components.test-markdown
    kmet.tui.components.test-track
    kmet.tui.components.test-caching-conventions
    kmet.tui.components.test-scroll-view
    kmet.tui.components.test-stack
    kmet.tui.components.test-v-stack
    kmet.tui.components.test-h-stack
    kmet.tui.components.test-truncated-text
    kmet.tui.components.test-alt-screen-flash
    kmet.tui.components.test-cancellable-loader
    kmet.tui.components.test-dynamic-border
    kmet.tui.components.test-spinner
    kmet.tui.components.test-expandable-text
    kmet.tui.test-overlay
    kmet.tui.test-negotiation
    kmet.tui.test-terminal-response
    kmet.tui.test-render-loop
    kmet.libs.test-self-contained
    kmet.libs.test-frontmatter
    kmet.libs.test-sse
    kmet.libs.test-terminal-image
    kmet.libs.test-yaml-lite
    kmet.libs.test-markdown
    kmet.libs.test-highlight
    kmet.app.ui.test-chat-history
    kmet.app.ui.test-user-message
    kmet.app.ui.test-assistant-message
    kmet.app.ui.test-tool-execution
    kmet.app.ui.test-custom-message
    kmet.app.ui.test-bash-execution
    kmet.app.ui.test-extension-dialogs
    kmet.app.ui.test-footer
    kmet.app.ui.test-footer-data-provider
    kmet.app.ui.test-pending-messages
    kmet.app.ui.test-loaded-resources
    kmet.app.ui.test-scoped-models-selector
    kmet.app.ui.test-model-selector
    kmet.test-core
    kmet.test-changed])

(defn- ns-vars
  "Require NS-SYM (lazily) and return all its interned test vars."
  [ns-sym]
  (require ns-sym)
  (vals (ns-interns ns-sym)))

(defn- selected-vars
  "All test vars whose :slow metadata matches the requested selection."
  [slow?]
  (for [ns-sym all-namespaces
        v (ns-vars ns-sym)
        :when (and (:test (meta v))
                   (if slow?
                     (:slow (meta v))
                     (not (:slow (meta v)))))]
    v))

(defn- var-matches-filter?
  "True when a test var matches any filter (plain name or ns/var)."
  [v filters]
  (let [vn (name (:name (meta v)))
        ns-full (str (:ns (meta v)))]
    (some #(or (= % vn)
               (= % (str ns-full "/" vn)))
          filters)))

(defn- filtered-vars
  "The test vars matching FILTERS, loading namespaces lazily. Plain-name
   filters scan all-namespaces in order and stop once every plain filter
   has matched at least one var — so `bb test test-llm-loaded` requires
   only the namespaces up to the first match, not all 76. ns/var filters
   load only their own namespace."
  [filters]
  (let [filters (map str filters)
        plain (remove #(str/includes? % "/") filters)]
    (if (seq plain)
      (loop [nss all-namespaces
             remaining (set plain)
             acc []]
        (if-let [ns-sym (first nss)]
          (let [vars (ns-vars ns-sym)
                acc (into acc (filter #(var-matches-filter? % filters)) vars)
                remaining (apply disj remaining
                                 (keep #(when (some (fn [f] (= f (name (:name (meta %))))) plain)
                                          (name (:name (meta %))))
                                       vars))]
            (if (seq remaining)
              (recur (rest nss) remaining acc)
              (distinct acc)))
          (distinct acc)))
      ;; all filters are ns/var — load exactly those namespaces
      (->> filters
           (map #(let [slash (str/index-of % "/")]
                   [(symbol (subs % 0 slash)) (subs % (inc slash))]))
           (mapcat (fn [[ns-sym var-name]]
                     (filter #(= var-name (name (:name (meta %))))
                             (ns-vars ns-sym))))
           distinct))))

(defn- join-fixtures*
  "Compose fixture fns. bb's join-fixtures only works with >= 2 fixtures:
   with 0 it throws an arity error, with exactly 1 it tries to reduce over
   the fn as a collection. Handle those cases directly."
  [fixtures]
  (let [fxs (or fixtures [])]
    (case (count fxs)
      0 (fn [f] (f))
      1 (first fxs)
      (apply t/join-fixtures fxs))))

(defn- plural
  "N + label, singular for 1."
  [n singular plural]
  (str n " " (if (= 1 n) singular plural)))

(defn- fmt-summary
  "One-line test summary with zero counts omitted: tests always, then
   assertions/failures/errors only when nonzero."
  [n-test n-pass n-fail n-error]
  (str/join ", "
            (cond-> [(plural n-test "test" "tests")]
              (pos? (+ n-pass n-fail n-error))
              (conj (plural (+ n-pass n-fail n-error) "assertion" "assertions"))
              (pos? n-fail) (conj (plural n-fail "failure" "failures"))
              (pos? n-error) (conj (plural n-error "error" "errors")))))

(defn- fmt-duration
  "Format elapsed milliseconds as a compact duration (ms or s)."
  [ms]
  (if (< ms 1000)
    (str (long ms) " ms")
    (format "%.1f s" (/ ms 1000.0))))

(defn- run-ns-vars
  "Run the selected vars of a namespace, applying its fixtures like
   clojure.test/test-ns: :once fixtures wrap the namespace run, :each
   fixtures wrap every test var. Prints a summary line with elapsed time
   for the namespace. (bb's clojure.test/test-vars is broken — it silently
   drops vars — so we drive test-var directly.)"
  [ns-sym vars]
  (let [ns-obj (find-ns ns-sym)
        before @t/*report-counters*
        start-ms (System/currentTimeMillis)
        once-fx (join-fixtures* (:clojure.test/once-fixtures (meta ns-obj)))
        each-fx (join-fixtures* (:clojure.test/each-fixtures (meta ns-obj)))]
    (println "\nTesting" (ns-name ns-obj))
    (binding [*ns* ns-obj]
      (once-fx
       (fn []
         (doseq [v vars]
           (each-fx (fn [] (t/test-var v)))))))
    (let [after @t/*report-counters*
          n-test (- (:test after) (:test before))
          n-pass (- (:pass after) (:pass before))
          n-fail (- (:fail after) (:fail before))
          n-error (- (:error after) (:error before))
          elapsed-ms (- (System/currentTimeMillis) start-ms)]
      (println (str "  " (fmt-summary n-test n-pass n-fail n-error)
                    " (" (fmt-duration elapsed-ms) ")")))))

(defn- run-selected
  "Run selected test vars, grouped by namespace so fixtures apply per ns."
  [vars]
  (doseq [[ns-sym ns-vars] (sort-by key (group-by (comp ns-name :ns meta) vars))]
    (run-ns-vars ns-sym ns-vars)))

(defn- run-and-summarize
  "Run VARS, print the summary, exit with status 0/1. When MARK-VALIDATED?
   and everything passed, records the changed-files baseline (kmet.changed)."
  [vars mark-validated?]
  (let [start-ms (System/currentTimeMillis)
        results (binding [t/*report-counters* (ref t/*initial-report-counters*)]
                  (run-selected vars)
                  @t/*report-counters*)
        n-tests (:test results)
        n-assertions (+ (:pass results) (:fail results) (:error results))
        total-ms (- (System/currentTimeMillis) start-ms)
        fails (:fail results)
        errs (:error results)]
    (println (str "\nRan " n-tests " tests containing " n-assertions " assertions in "
                  (fmt-duration total-ms) "."))
    (when (pos? (+ fails errs))
      (println (str (str/join ", " [(plural fails "failure" "failures")
                                    (plural errs "error" "errors")]) ".")))
    (println "Results:" (str/join ", "
                                  (cond-> [(str (:pass results) " passed")]
                                    (pos? fails) (conj (str fails " failed"))
                                    (pos? errs) (conj (plural errs "error" "errors")))))
    (when (and mark-validated? (zero? (+ fails errs)))
      (try ((requiring-resolve 'kmet.changed/mark-validated!))
           (catch Throwable e
             (.println System/err
                       (str "warning: could not update changed-files baseline: "
                            (.getMessage e))))))
    (System/exit (if (pos? (+ fails errs)) 1 0))))

(defn -main
  "Run the test suites.
   slow? selects ^:slow vs non-slow vars; nil means filter by var name only.
   Remaining args are test var filters (plain name or ns/var): when given,
   only matching vars run, regardless of :slow (e.g. `bb test test-tool-bash`
   or `bb test-ext kmet.app.test-loop/test-loop-parallel-tool-execution`).
   A full run without filters (either suite) records the changed-files
   baseline after a green result, so `bb test-changed` sees a clean slate."
  [slow? & filters]
  (run-and-summarize (if (seq filters)
                       (filtered-vars filters)
                       (selected-vars slow?))
                     (empty? filters)))

(defn run-ns-syms
  "Run the test vars of NS-SYMS matching SLOW? (true = ^:slow only, false =
   non-slow only), for `bb test-ext-changed` / `bb test-changed`."
  [ns-syms slow?]
  (run-and-summarize
   (for [ns-sym ns-syms
         v (ns-vars ns-sym)
         :when (and (:test (meta v)) (= slow? (boolean (:slow (meta v)))))]
     v)
   false))
