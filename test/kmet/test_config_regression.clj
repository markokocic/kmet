(ns kmet.test-config-regression
  "Regression tests for fixes in kmet.config."
  (:require [clojure.test :as t]
            [clojure.java.io :as io]
            [kmet.config :as cfg]))

;; ─── Malformed config file handling ──────────────────────────────────────

(t/deftest test-load-edn-file-nonexistent
  ;; load-edn-file should return nil for missing files
  (let [result (cfg/load-edn-file "target/test-nonexistent-config.edn")]
    (t/is (nil? result) "Nonexistent file should return nil")))

(t/deftest test-load-edn-file-malformed
  ;; load-edn-file should return nil for malformed files (not crash)
  (spit "target/test-malformed.edn" "{:bad \"config\" :broken true")
  (let [result (cfg/load-edn-file "target/test-malformed.edn")]
    (t/is (nil? result) "Malformed file should return nil without crashing"))
  (.delete (io/file "target/test-malformed.edn")))

(t/deftest test-load-edn-file-valid
  ;; load-edn-file should return parsed map for valid files
  (spit "target/test-valid.edn" "{:theme \"light\" :provider :anthropic}")
  (let [result (cfg/load-edn-file "target/test-valid.edn")]
    (t/is (map? result))
    (t/is (= "light" (:theme result)))
    (t/is (= :anthropic (:provider result))))
  (.delete (io/file "target/test-valid.edn")))

;; ─── Config merging with malformed project config ───────────────────────

(t/deftest test-load-config-with-malformed-project
  ;; load-config should gracefully handle a malformed .kmet/settings.edn
  (.mkdirs (io/file "target/.kmet"))
  (spit "target/.kmet/settings.edn" "{:provider :anthropic")  ;; unclosed map
  (let [c (cfg/load-config :no-env? true)]
    (t/is (map? c))
    ;; Config from current dir shouldn't affect this
    (t/is (= :opencode-go (:provider c))))
  (.delete (io/file "target/.kmet/settings.edn"))
  (.delete (io/file "target/.kmet")))

;; ─── Expand path ─────────────────────────────────────────────────────────

(t/deftest test-expand-path-home
  (let [home (System/getProperty "user.home")
        result (cfg/expand-path "~/test")]
    (t/is (.startsWith result home))
    (t/is (.endsWith result "/test"))))

(t/deftest test-expand-path-absolute
  (t/is (= "/tmp/foo" (cfg/expand-path "/tmp/foo"))))
