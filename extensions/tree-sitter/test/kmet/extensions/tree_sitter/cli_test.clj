(ns kmet.extensions.tree-sitter.cli-test
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [kmet.extensions.tree-sitter.cli :as cli]
            [kmet.extensions.tree-sitter.paths :as paths]
            [kmet.extensions.tree-sitter.test-util :as tu]))

(deftest process!-capture-test
  (testing "captures exit code and stdout"
    (let [res (cli/process! ["sh" "-c" "printf hi"] nil)]
      (is (= 0 (:exit res)))
      (is (= "hi" (str/trim (:out res))))))
  (testing "propagates non-zero exits without throwing"
    (let [res (cli/process! ["sh" "-c" "echo boom >&2; exit 3"] nil)]
      (is (= 3 (:exit res)))
      (is (str/includes? (str (:err res)) "boom")))))

(deftest process!-timeout-test
  (let [res (cli/process! ["sleep" "30"] {:timeout-ms 150})]
    (is (= {:error :timeout} (select-keys res [:error])))))

(deftest exec!-spawn-failure-test
  ;; base dir holds no binary -> resolution points at a nonexistent path
  (let [base (tu/temp-dir! "ts-spawn")
        res (cli/exec! ["--version"] {:base base})]
    (is (= {:error :spawn-failure} (select-keys res [:error])))
    (is (string? (:reason res)))))

(deftest process!-extra-env-test
  (let [res (cli/process! ["sh" "-c" "printf $TS_PROBE_VAR"]
                          {:env {"TS_PROBE_VAR" "42"}})]
    (is (= "42" (str/trim (:out res))))))

(deftest launcher-text-test
  (let [text (cli/launcher-text {:bin-name "tree-sitter" :linker "ld-linux-aarch64.so.1"})]
    (is (str/starts-with? text "#!/data/data/com.termux/files/usr/bin/sh"))
    (is (re-find #"glibc/lib/ld-linux-aarch64\.so\.1" text))
    (is (re-find #"\"\$DIR/tree-sitter\"" text))
    (is (re-find #"unset LD_PRELOAD" text))))

(deftest emit-launcher!-test
  (let [base (tu/temp-dir! "ts-launcher")]
    (paths/ensure-dirs! base)
    (let [w (cli/emit-launcher! base)]
      (is (fs/exists? w))
      (is (str/includes? (slurp (str w)) "ld-linux"))
      (when-not (fs/windows?)
        (is (= "rwxr-xr-x" (fs/posix->str (fs/posix-file-permissions w))))))))

(deftest resolve-invocation-test
  (let [base (tu/temp-dir! "ts-resolve")]
    (paths/ensure-dirs! base)
    (let [bin (paths/bin-path base)
          launcher (paths/launcher-path base)]
      (spit (str bin) "binary")
      (testing "falls back to the binary when no launcher exists"
        (is (= bin (cli/resolve-invocation base))))
      (spit (str launcher) "#!/bin/sh\n")
      (testing "prefers the launcher once present"
        (is (= launcher (cli/resolve-invocation base)))))))
