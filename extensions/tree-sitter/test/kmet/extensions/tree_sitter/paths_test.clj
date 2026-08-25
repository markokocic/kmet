(ns kmet.extensions.tree-sitter.paths-test
  (:require [babashka.fs :as fs]
            [clojure.test :refer [deftest is testing]]
            [kmet.extensions.tree-sitter.paths :as paths]
            [kmet.extensions.tree-sitter.test-util :as tu]))

(deftest default-root-test
  (testing "default root lives at ~/.kmet/agent/tree-sitter"
    (is (= (fs/path (fs/home) ".kmet" "agent" "tree-sitter")
           (paths/root))))
  (testing "bin name follows the host OS"
    (is (= (if (fs/windows?) "tree-sitter.exe" "tree-sitter")
           (paths/bin-name)))))

(deftest base-override-test
  (let [base (tu/temp-dir! "ts-paths")]
    (testing "every path stays inside an overridden base"
      (doseq [p [(paths/root base)
                 (paths/bin-dir base)
                 (paths/libs-dir base)
                 (paths/grammars-dir base)
                 (paths/config-path base)
                 (paths/bin-path base)
                 (paths/launcher-path base)
                 (paths/manifest-copy-path base)]]
        (is (fs/starts-with? p base) (str p)))
      (testing "override does not disturb the default root"
        (is (not= (str (paths/bin-dir base)) (str (paths/bin-dir))))))))

(deftest ensure-dirs!-test
  (let [base (tu/temp-dir! "ts-dirs")]
    (testing "creates root plus the three subdirs"
      (paths/ensure-dirs! base)
      (doseq [d [(paths/root base)
                 (paths/bin-dir base)
                 (paths/libs-dir base)
                 (paths/grammars-dir base)]]
        (is (fs/directory? d) (str d))))
    (testing "idempotent"
      (is (fs/directory? (paths/ensure-dirs! base))))))
