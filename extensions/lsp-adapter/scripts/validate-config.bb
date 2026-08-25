#!/usr/bin/env bb
;; Config validation for the lsp-adapter extension (plan §15.4 — config
;; pieces): precedence merge (global → project), camel/kebab normalization,
;; defaults, template creation, enable/disable write round-trip (incl. the
;; lower-source-disabled case), empty-entry pruning, and malformed-file
;; leniency.
;;
;; Config paths are redirected to temp files via with-redefs — the real
;; ~/.kmet/agent/lsp.edn is never touched.
;;
;; Run from the extension directory:
;;   bb -cp ../../src:src scripts/validate-config.bb

(require '[clojure.java.io :as io]
         '[kmet.libs.edn-store :as edn-store]
         '[extensions.lsp-adapter.config :as config])

(def failures (atom 0))

(defn check [label ok]
  (println (if ok "PASS" "FAIL") label)
  (when-not ok (swap! failures inc)))

(defn temp-file [name]
  (str (System/getProperty "user.dir") "/.lsp-" name "-" (System/nanoTime) ".edn"))

(defn with-redirected-config
  "Run F with the global + project config paths redirected to temp files
   (deleted afterwards)."
  [f]
  (let [global (temp-file "global")
        project (temp-file "project")]
    (with-redefs [config/global-config-path (delay global)
                  config/project-config-path (fn [& _] project)]
      (try
        (f global project)
        (finally
          (doseq [x [global project]]
            (doseq [p [x (str x ".lock")]]
              (let [fl (io/file p)] (when (.exists fl) (.delete fl))))))))))

(defn spit-edn [path m] (spit path (pr-str m)))

;; ─── Normalization ────────────────────────────────────────────────────────

(let [entry (get-in (config/normalize-config
                     {:servers {"s" {:initializationOptions {:a 1}
                                     :rootMarkers ["x"] :rootDir "/w"
                                     :extendExtensions nil
                                     :unknown-key 42}}})
                    [:servers "s"])]
  (check "camel keys fold to kebab inside server entries"
         (= {:initialization-options {:a 1} :root-markers ["x"] :root-dir "/w"}
            (select-keys entry [:initialization-options :root-markers :root-dir])))
  (check "nil values dropped"
         (not (contains? entry :extend-extensions)))
  (check "unknown keys pass through"
         (= 42 (:unknown-key entry))))

(let [diag (get-in (config/normalize-config
                    {:settings {:diagnostics {:afterEdit true :waitMs 4000
                                              :maxPerFile 8 :floodThreshold 40}}})
                   [:settings :diagnostics])]
  (check "nested :diagnostics aliases fold"
         (= {:after-edit true :wait-ms 4000 :max-per-file 8 :flood-threshold 40}
            diag)))

(check "unknown top-level keys preserved"
       (= {:custom-section {:x 1}}
          (select-keys (config/normalize-config {:custom-section {:x 1}})
                       [:custom-section])))

(check "settings camel keys fold"
       (= {:request-timeout-ms 5000}
          (:settings (config/normalize-config
                      {:settings {:requestTimeoutMs 5000}}))))

(check ":lifecycle accepts a string"
       (= :eager (get-in (config/normalize-config
                          {:servers {"s" {:lifecycle "eager"}}})
                         [:servers "s" :lifecycle])))

(check "non-map server entries pass through untouched"
       (= "oops" (get-in (config/normalize-config {:servers {"s" "oops"}})
                         [:servers "s"])))

(check ":diagnostics false survives normalization"
       (= false (get-in (config/normalize-config {:settings {:diagnostics false}})
                        [:settings :diagnostics])))

(check "type-malformed :servers degrades to empty"
       (= {} (:servers (config/normalize-config {:servers "oops"}))))

(check "type-malformed :settings degrades to empty"
       (= {} (:settings (config/normalize-config {:settings [1 2]}))))

(check "non-map entries still pass through inside a valid map"
       (= {"s" "oops"} (select-keys (:servers (config/normalize-config
                                               {:servers {"s" "oops"}}))
                                    ["s"])))

;; ─── Merge, defaults, leniency, template, write-back ──────────────────────

(with-redirected-config
  (fn [global project]
    (check "defaults apply when no files exist"
           (= {:request-timeout-ms 30000 :initialize-timeout-ms 60000 :idle-timeout 15}
              (:settings (config/load-config))))

    (spit-edn global {:settings {:request-timeout-ms 9000}
                      :servers {"clojure-lsp" {:request-timeout-ms 10000}}})
    (check "global alone merges over defaults"
           (and (= 9000 (get-in (config/load-config) [:settings :request-timeout-ms]))
                (= 60000 (get-in (config/load-config) [:settings :initialize-timeout-ms]))
                (= 10000 (get-in (config/load-config) [:servers "clojure-lsp" :request-timeout-ms]))))

    (spit-edn project {:settings {:idle-timeout 5}
                       :servers {"clojure-lsp" {:disabled true}}})
    (let [cfg (config/load-config)]
      (check "project wins per-key; other keys survive the deep merge"
             (and (= 9000 (get-in cfg [:settings :request-timeout-ms]))
                  (= 5 (get-in cfg [:settings :idle-timeout]))
                  (= 10000 (get-in cfg [:servers "clojure-lsp" :request-timeout-ms]))
                  (true? (get-in cfg [:servers "clojure-lsp" :disabled])))))

    (spit project "{:broken")
    (check "malformed project file degrades to empty (global still wins)"
           (= 9000 (get-in (config/load-config) [:settings :request-timeout-ms])))
    (spit-edn project {:settings {:idle-timeout 5}
                       :servers {"clojure-lsp" {:disabled true}}})

    ;; template creation
    (.delete (io/file global))
    (let [written (config/ensure-global-template!)]
      (check "template written when global missing"
             (and (some? written) (.exists (io/file global))))
      (check "template parses and carries documented defaults"
             (= 30000 (get-in (config/read-config-file global)
                              [:settings :request-timeout-ms])))
      (check "ensure-global-template! is idempotent"
             (nil? (config/ensure-global-template!))))

    ;; enable/disable round-trip — start from a project without the entry,
    ;; so the first disable actually changes something
    (spit-edn project {})
    (config/set-server-disabled! "never-configured" false)
    (check "pruning never materializes :servers in a file without it"
           (nil? (get-in (edn-store/read-edn-map project) [:servers])))
    (check "disable writes :disabled true"
           (:changed (config/set-server-disabled! "clojure-lsp" true)))
    (check "disable visible in merged config"
           (true? (get-in (config/load-config) [:servers "clojure-lsp" :disabled])))
    (check "re-disable is a no-op"
           (false? (:changed (config/set-server-disabled! "clojure-lsp" true))))

    ;; global does NOT disable clojure-lsp → enable prunes the empty entry
    (check "enable reports changed"
           (:changed (config/set-server-disabled! "clojure-lsp" false)))
    (check "enabled entry pruned when empty"
           (nil? (get-in (edn-store/read-edn-map project) [:servers "clojure-lsp"])))

    ;; globally-disabled builtin: project enable must write explicit false
    (spit-edn global (assoc-in (edn-store/read-edn-map global)
                               [:servers "rust-analyzer" :disabled] true))
    (config/set-server-disabled! "rust-analyzer" true)
    (config/set-server-disabled! "rust-analyzer" false)
    (let [entry (get-in (edn-store/read-edn-map project) [:servers "rust-analyzer"])]
      (check "enable under globally-disabled writes explicit :disabled false"
             (false? (:disabled entry)))
      (check "explicit project false overrides global true (force-enable)"
             (false? (get-in (config/load-config) [:servers "rust-analyzer" :disabled]))))))

(with-redirected-config
  (fn [_global project]
    (spit-edn project {:servers {"weird" "not-a-map"}})
    (let [r (try (config/set-server-disabled! "weird" true) ::no-throw
                 (catch Exception e e))]
      (check "non-map entry rejected with ex-info" (instance? Exception r)))))

(println)
(if (pos? @failures)
  (do (println @failures "FAILURES") (System/exit 1))
  (println "All config validations passed."))
