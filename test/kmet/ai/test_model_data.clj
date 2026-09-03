(ns kmet.ai.test-model-data
  "Phase 1: offline strict validation of the committed provider catalogs +
   manifest hash match (pi check-model-data.ts / validateModelDataDirectory).

   The generator (kmet.ai.model-gen, entered via scripts/generate_models.clj)
   is the single source of truth for the strict per-model validation; this
   test loads it and runs its offline half over the committed files, so
   drift (uncommitted regenerations, hand-edits) is caught in CI without
   network."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :as t]
            [kmet.ai.model-gen :as mg]
            [kmet.ai.models :as m]))

(defn- validate-dir
  "Run the generator script's offline validation over DIR (defaults to the
   committed catalog dir). The script is loaded once and cached — its
   validate-committed! is a pure read of the catalog files."
  [& [dir]]
  (let [f (force
           (delay
             (load-file "scripts/generate_models.clj")
             (ns-resolve 'generate-models 'validate-committed!)))]
    (when-not f
      (throw (ex-info "scripts/generate_models.clj did not define validate-committed!"
                      {:type :script-invalid})))
    (if dir (f dir) (f))))

(t/deftest test-committed-catalogs-valid
  (let [errors (validate-dir)]
    (t/is (empty? errors)
          (str "committed catalogs failed strict validation:\n"
               (str/join "\n" errors)))))

(t/deftest test-manifest-covers-committed-catalogs
  (t/is (m/manifest-matches?)
        "manifest.edn covers the committed catalog files (regenerate the
         manifest when catalogs change)"))

;; ─── Negative paths (the offline gate must fail loudly on broken data) ─────

(defn- with-scratch-data
  "Write FILE-CONTENTS into an isolated temp dir, run F with the dir."
  [file-contents f]
  (let [dir (str (fs/create-temp-dir {:dir "target" :prefix "model-data-test-"}))]
    (doseq [[fname content] file-contents]
      (spit (str dir "/" fname) content))
    (try
      (f dir)
      (finally
        (fs/delete-tree dir)))))

(defn- gen-model
  "One valid generated-model map for PID/MID."
  [pid mid]
  {:id mid :name (str/upper-case mid) :provider pid
   :api :openai-completions :base-url "https://gen.example/v1"
   :reasoning false :input [:text]
   :cost {:input 0 :output 0 :cache-read 0 :cache-write 0}
   :context-window 1000 :max-tokens 100})

(defn- dir-mtimes
  "File name -> last-modified string for DIR's top level (stable ordering).
   Callers sleep past the filesystem mtime granularity before rewriting, so
   a rewritten file observably moves."
  [dir]
  (into (sorted-map)
        (for [f (fs/list-dir dir)]
          [(fs/file-name f) (str (fs/last-modified-time f))])))

(defn- valid-catalog
  []
  {:schema-version 1
   :generated-at "2026-08-08T00:00:00Z"
   :provider {:id :scratch :name "Scratch" :env-vars ["SCRATCH_KEY"]
              :default-model "m1"}
   :models {:openai-completions
            {"m1" {:id "m1" :name "M1" :provider :scratch
                   :api :openai-completions :base-url "https://scratch.example/v1"
                   :reasoning false :input [:text]
                   :cost {:input 0 :output 0 :cache-read 0 :cache-write 0}
                   :context-window 1000 :max-tokens 100}}}})

(t/deftest test-validate-committed-rejects-missing-manifest
  (with-scratch-data
    [["scratch.edn" (pr-str (valid-catalog))]]
    (fn [dir]
      (let [errors (validate-dir dir)]
        (t/is (some #(re-find #"manifest.edn is missing or incomplete" %) errors)
              (str "expected a manifest error, got: " errors))))))

(t/deftest test-validate-committed-rejects-broken-catalog
  (let [broken (assoc-in (valid-catalog) [:models :openai-completions "m1" :reasoning] "yes")]
    (with-scratch-data
      [["scratch.edn" (pr-str broken)]]
      (fn [dir]
        (let [errors (validate-dir dir)]
          (t/is (some #(re-find #"has no reasoning boolean" %) errors)
                (str "expected a reasoning error, got: " errors)))))))

(t/deftest test-commandcode-refs-transfer-capabilities
  "Regression: the canonical-ref lookup in process-commandcode (get-in
   grouped [provider model-id]) must receive the {provider -> {model-id ->
   model}} shape. When fed the bare group-by :provider vector shape it
   silently returns nil and every commandcode model falls back to
   conservative defaults (:reasoning false, :max-tokens 32768) — the
   deepseek-v4/claude/gpt entries lost thinking support. Drive the same
   path generate-models-data uses: provider-model-index over the canonical
   models, then process-commandcode."
  (let [canonical {:id "deepseek-v4-flash" :provider :deepseek
                   :api :openai-completions :base-url "https://api.deepseek.com"
                   :reasoning true :input [:text]
                   :cost {:input 0.14 :output 0.28 :cache-read 0.0028 :cache-write 0}
                   :context-window 1000000 :max-tokens 384000
                   :thinking-level-map {:high "high" :low nil :max "max"
                                        :medium nil :minimal nil}
                   :compat {:thinking-format :deepseek}}
        fetched [{"id" "deepseek/deepseek-v4-flash"
                  "name" "DeepSeek V4 Flash (latest)"
                  "context_length" 1000000}]
        grouped (#'mg/provider-model-index [canonical])
        out (first (#'mg/process-commandcode fetched grouped {}))]
    (t/is (= true (:reasoning out))
          "canonical :reasoning must transfer to the commandcode model")
    (t/is (= 384000 (:max-tokens out))
          "canonical :max-tokens must transfer (was clamped to 32768)")
    (t/is (= {:high "high" :low nil :max "max" :medium nil :minimal nil}
             (:thinking-level-map out))
          "canonical thinking-level-map must transfer")
    (t/is (= :deepseek (:thinking-format (:compat out)))
          "model-bound compat keys must transfer")
    (t/is (= "deepseek/deepseek-v4-flash" (:id out)))))

(t/deftest ^:slow test-commandcode-prices-timeout-proceeds
  "A stalled CommandCode price fetch must not block generation: the bounded
   fetch-commandcode-prices wrapper gives up after the cap and returns nil,
   so generation proceeds with zero rates. Drives the real wrapper with a
   stubbed inner fetch (fast path asserts no regression on the happy path)."
  (with-redefs [mg/fetch-commandcode-prices*
                (fn [] {"x" {:input 1 :output 2 :cache-read 0 :cache-write 0}})]
    (t/is (= {"x" {:input 1 :output 2 :cache-read 0 :cache-write 0}}
             (mg/fetch-commandcode-prices))
          "fast inner fetch passes through"))
  (with-redefs [mg/fetch-commandcode-prices* (fn [] (Thread/sleep 300000))
                mg/commandcode-prices-timeout-ms 500]
    (let [t0 (System/currentTimeMillis)
          res (mg/fetch-commandcode-prices)
          dt (- (System/currentTimeMillis) t0)]
      (t/is (nil? res) "stalled fetch yields nil (zero rates downstream)")
      (t/is (< dt 30000) (str "wrapper must give up at the cap, took " dt "ms")))))

(t/deftest test-write-catalogs-skips-unchanged
  (let [dir (str (fs/create-temp-dir {:dir "target" :prefix "model-gen-test-"}))
        catalogs {:gen-alpha [(gen-model :gen-alpha "a1")]
                  :gen-beta [(gen-model :gen-beta "b1")]}]
    (try
      ;; initial generation writes both catalogs + the manifest
      (t/is (= 3 (mg/write-catalogs! dir catalogs)))
      ;; rerun with identical data: nothing is written, no mtime moves
      (let [before (dir-mtimes dir)]
        (t/is (zero? (mg/write-catalogs! dir catalogs)))
        (t/is (= before (dir-mtimes dir))
              "an unchanged regeneration touches no file"))
      ;; changing ONE provider rewrites just its file + the manifest
      (let [before-bytes (into (sorted-map)
                               (for [f (fs/list-dir dir)]
                                 [(fs/file-name f) (slurp (str f))]))
            before-mtimes (dir-mtimes dir)
            bumped (update-in catalogs [:gen-alpha 0 :context-window] inc)
            n (mg/write-catalogs! dir bumped)]
        (t/is (= 2 n) "only the changed catalog + manifest")
        (let [after-bytes (into (sorted-map)
                                (for [f (fs/list-dir dir)]
                                  [(fs/file-name f) (slurp (str f))]))
              after-mtimes (dir-mtimes dir)]
          (t/is (not= (get before-bytes "gen-alpha.edn") (get after-bytes "gen-alpha.edn"))
                "changed provider rewrites")
          (t/is (= (get before-bytes "gen-beta.edn") (get after-bytes "gen-beta.edn"))
                "unchanged provider keeps its bytes")
          (t/is (= (get before-mtimes "gen-beta.edn") (get after-mtimes "gen-beta.edn"))
                "unchanged provider is not rewritten (mtime untouched)")
          (t/is (not= (get before-bytes "manifest.edn") (get after-bytes "manifest.edn"))
                "manifest follows the change"))
        ;; the result still passes the committed-data gate
        (t/is (empty? (mg/validate-committed! dir))))
      (finally
        (fs/delete-tree dir)))))

(t/deftest test-write-catalogs-keeps-per-file-timestamps
  (let [dir (str (fs/create-temp-dir {:dir "target" :prefix "model-gen-test-"}))
        catalogs {:gen-alpha [(gen-model :gen-alpha "a1")]
                  :gen-beta [(gen-model :gen-beta "b1")]}
        gen-at (atom "2026-08-08T00:00:01Z")]
    (try
      (with-redefs [mg/generated-at (fn [] @gen-at)]
        ;; initial generation writes both catalogs + the manifest
        (t/is (= 3 (mg/write-catalogs! dir catalogs)))
        ;; partial change at a later timestamp: only alpha + manifest rewrite
        (reset! gen-at "2026-08-08T00:00:02Z")
        (let [bumped (update-in catalogs [:gen-alpha 0 :context-window] inc)]
          (t/is (= 2 (mg/write-catalogs! dir bumped)))
          ;; identical rerun at a still-later timestamp: nothing is written —
          ;; beta keeps its original timestamp instead of being restamped
          ;; with a timestamp-only diff
          (reset! gen-at "2026-08-08T00:00:03Z")
          (let [before (dir-mtimes dir)]
            (t/is (zero? (mg/write-catalogs! dir bumped))
                  "unchanged files keep their own timestamps (no timestamp-only diffs)")
            (t/is (= before (dir-mtimes dir))
                  "an unchanged regeneration touches no file"))
          (t/is (str/includes? (slurp (str dir "/gen-beta.edn")) "2026-08-08T00:00:01Z")
                "unchanged provider keeps its original timestamp")
          (t/is (str/includes? (slurp (str dir "/gen-alpha.edn")) "2026-08-08T00:00:02Z")
                "changed provider carries the update timestamp")
          (t/is (empty? (mg/validate-committed! dir)))))
      (finally
        (fs/delete-tree dir)))))
