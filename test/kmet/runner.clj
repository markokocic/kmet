(ns kmet.runner
  "Test runner with per-test slow isolation.

   Slow tests are marked with ^:slow on the deftest (tests that wait real
   wall-clock time: sleeps, terminal-query timeouts; real network calls; and
   subprocess spawns — bash tool, shell commands, git).
   `bb test` runs all tests except ^:slow ones; `bb test-ext` runs only the
   ^:slow tests. Both are selected at the individual test level — no whole
   namespaces are excluded."
  (:require [clojure.string :as str]
            [clojure.test :as t]
            [kmet.test-utils]
            [kmet.test-keys]
            [kmet.app.test-session]
            [kmet.app.test-session-export]
            [kmet.app.test-compaction]
            [kmet.app.test-tools]
            [kmet.app.test-llm]
            [kmet.app.test-attribution]
            [kmet.app.test-models]
            [kmet.app.test-model-resolver]
            [kmet.app.test-config-value]
            [kmet.app.test-model-config]
            [kmet.app.test-provider-composer]
            [kmet.app.test-auth]
            [kmet.app.test-oauth]
            [kmet.app.test-model-data]
            [kmet.app.test-proxy]
            [kmet.app.test-loop]
            [kmet.test-theme]
            [kmet.test-config]
            [kmet.app.test-skills]
            [kmet.app.test-context]
            [kmet.app.test-prompts]
            [kmet.app.test-extensions]
            [kmet.app.test-extensions-ui]
            [kmet.app.test-interactive-ui]
            [kmet.app.test-event-bus]
            [kmet.app.test-theme-controller]
            [kmet.app.test-commands]
            [kmet.app.test-keybindings]
            [kmet.test-editing]
            [kmet.tui.test-fuzzy]
            [kmet.tui.test-autocomplete]
            [kmet.tui.test-core]
            [kmet.tui.components.test-text]
            [kmet.tui.components.test-spacer]
            [kmet.tui.components.test-container]
            [kmet.tui.components.test-box]
            [kmet.tui.components.test-input]
            [kmet.tui.components.test-editor]
            [kmet.tui.components.test-select-list]
            [kmet.tui.components.test-settings-list]
            [kmet.tui.components.test-markdown]
            [kmet.tui.components.test-track]
            [kmet.tui.components.test-caching-conventions]
            [kmet.tui.components.test-scroll-view]
            [kmet.tui.components.test-stack]
            [kmet.tui.components.test-v-stack]
            [kmet.tui.components.test-h-stack]
            [kmet.tui.components.test-truncated-text]
            [kmet.tui.components.test-alt-screen-flash]
            [kmet.tui.components.test-cancellable-loader]
            [kmet.tui.components.test-dynamic-border]
            [kmet.tui.components.test-spinner]
            [kmet.tui.components.test-expandable-text]
            [kmet.tui.test-overlay]
            [kmet.tui.test-negotiation]
            [kmet.tui.test-terminal-response]
            [kmet.tui.test-render-loop]
            [kmet.libs.test-self-contained]
            [kmet.libs.test-sse]
            [kmet.libs.test-terminal-image]
            [kmet.libs.test-yaml-lite]
            [kmet.libs.test-markdown]
            [kmet.libs.test-highlight]
            [kmet.app.ui.test-chat-history]
            [kmet.app.ui.test-user-message]
            [kmet.app.ui.test-assistant-message]
            [kmet.app.ui.test-tool-execution]
            [kmet.app.ui.test-custom-message]
            [kmet.app.ui.test-bash-execution]
            [kmet.app.ui.test-extension-dialogs]
            [kmet.app.ui.test-footer]
            [kmet.app.ui.test-footer-data-provider]
            [kmet.app.ui.test-pending-messages]
            [kmet.app.ui.test-loaded-resources]
            [kmet.app.ui.test-scoped-models-selector]
            [kmet.test-core]))

(def all-namespaces
  "Every test namespace. The slow/fast split happens per test var via ^:slow
   metadata, so no namespace is excluded from either run."
  '[kmet.test-utils kmet.test-keys
    kmet.app.test-session
    kmet.app.test-compaction
    kmet.app.test-tools
    kmet.app.test-llm
    kmet.app.test-attribution
    kmet.app.test-models
    kmet.app.test-model-resolver
    kmet.app.test-config-value
    kmet.app.test-model-config
    kmet.app.test-provider-composer
    kmet.app.test-auth
    kmet.app.test-oauth
    kmet.app.test-model-data
    kmet.app.test-proxy
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
    kmet.test-core])

(defn- selected-vars
  "All test vars whose :slow metadata matches the requested selection."
  [slow?]
  (for [ns-sym all-namespaces
        v (vals (ns-interns ns-sym))
        :when (and (:test (meta v))
                   (if slow?
                     (:slow (meta v))
                     (not (:slow (meta v)))))]
    v))

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

(defn -main
  "Run the test suites.
   slow? selects ^:slow vs non-slow vars; nil means filter by var name only.
   Remaining args are test var filters (plain name or ns/var): when given,
   only matching vars run, regardless of :slow (e.g. `bb test test-tool-bash`
   or `bb test-ext kmet.app.test-loop/test-loop-parallel-tool-execution`)."
  [slow? & filters]
  (let [vars (if (seq filters)
               (filter (fn [v]
                         (let [vn (name (:name (meta v)))
                               ns-full (str (:ns (meta v)))]
                           (some #(let [f (str %)]
                                    (or (= f vn)
                                        (= f (str ns-full "/" vn))))
                                 filters)))
                       (concat (selected-vars true) (selected-vars false)))
               (selected-vars slow?))
        start-ms (System/currentTimeMillis)
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
    (System/exit (if (pos? (+ fails errs)) 1 0))))
