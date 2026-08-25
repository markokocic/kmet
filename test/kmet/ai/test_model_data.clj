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
  "File name -> last-modified string for DIR's top level (stable ordering)."
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
      (let [before (dir-mtimes dir)
            bumped (update-in catalogs [:gen-alpha 0 :context-window] inc)
            n (mg/write-catalogs! dir bumped)]
        (t/is (= 2 n) "only the changed catalog + manifest")
        (let [after (dir-mtimes dir)]
          (t/is (not= (get before "gen-alpha.edn") (get after "gen-alpha.edn")))
          (t/is (= (get before "gen-beta.edn") (get after "gen-beta.edn"))
                "unchanged provider keeps its bytes/mtime")
          (t/is (not= (get before "manifest.edn") (get after "manifest.edn"))))
        ;; the result still passes the committed-data gate
        (t/is (empty? (mg/validate-committed! dir))))
      (finally
        (fs/delete-tree dir)))))
