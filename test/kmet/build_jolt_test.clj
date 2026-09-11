(ns kmet.build-jolt-test
  ;; The jolt packager's pure surface (kmet.build-jolt): slug/naming rules and
  ;; the CLI parser. The compile itself is jolt's CLI in a subprocess and is
  ;; not unit-tested here; only the host's own artifact can smoke-test, which
  ;; the packager does as part of the build.
  (:require [clojure.test :refer [deftest is testing]]
            [kmet.build-jolt :as jbuild]))

(deftest slug-for-names-os-and-arch
  (is (= "linux-amd64" (jbuild/slug-for "Linux" "amd64")))
  (is (= "linux-amd64" (jbuild/slug-for "Linux" "x86_64")))
  (is (= "linux-aarch64" (jbuild/slug-for "Linux" "aarch64")))
  (is (= "linux-aarch64" (jbuild/slug-for "Linux" "arm64")))
  (is (= "macos-aarch64" (jbuild/slug-for "Mac OS X" "arm64")))
  (is (= "macos-amd64" (jbuild/slug-for "Darwin" "x86_64")))
  (is (= "windows-amd64" (jbuild/slug-for "Windows 11" "amd64")))
  (testing "no -static variants: a jolt binary carries its own runtime"
    (is (= "linux-amd64" (jbuild/slug-for "linux" "amd64"))))
  (testing "unsupported pairs have no slug"
    (is (nil? (jbuild/slug-for "SunOS" "sparc")))
    (is (nil? (jbuild/slug-for "Linux" "riscv64")))))

(deftest target-slug-maps-chez-machines
  (testing "the host when no cross target is given"
    (is (= (jbuild/host-slug) (jbuild/target-slug nil)))
    (is (= (jbuild/host-slug) (jbuild/target-slug ""))))
  (testing "Chez machine strings"
    (is (= "linux-amd64" (jbuild/target-slug "ta6le")))
    (is (= "linux-aarch64" (jbuild/target-slug "tarm64le")))
    (is (= "macos-amd64" (jbuild/target-slug "ta6osx")))
    (is (= "macos-aarch64" (jbuild/target-slug "tarm64osx")))
    (is (= "windows-amd64" (jbuild/target-slug "ta6nt"))))
  (testing "an unmapped machine keeps its own name"
    (is (= "tb3le" (jbuild/target-slug "tb3le")))))

(deftest artifact-base-mirrors-the-babashka-naming
  (is (= "kmet-1.2.3-jolt0.8.6-linux-amd64"
         (jbuild/artifact-base "1.2.3" "0.8.6" "linux-amd64" {})))
  (is (= "kmet-20260911-abc1234-jolt0.8.6-86-g234f460b-windows-amd64"
         (jbuild/artifact-base "20260911-abc1234" "0.8.6-86-g234f460b"
                               "windows-amd64" {})))
  (testing "a dev build cannot be mistaken for a release artifact"
    (is (= "kmet-1.2.3-jolt0.8.6-linux-amd64-dev"
           (jbuild/artifact-base "1.2.3" "0.8.6" "linux-amd64" {:dev? true}))))
  (testing "no jolt version (babashka, where the var is absent) still names"
    (is (= "kmet-1.2.3-joltdev-linux-amd64"
           (jbuild/artifact-base "1.2.3" nil "linux-amd64" {})))))

(deftest parse-args-defaults-to-a-release-host-build
  (is (= {:mode "release" :flags [] :boot nil :target nil :target-pack nil
          :out nil :jolt nil :force? false :no-smoke? false :help? false}
         (jbuild/parse-args []))))

(deftest parse-args-reads-modes-and-passthrough-flags
  (is (= "dev" (:mode (jbuild/parse-args ["--dev"]))))
  (is (= "optimized" (:mode (jbuild/parse-args ["--opt"]))))
  (is (= ["--closed-world" "--dynamic"] (:flags
                                         (jbuild/parse-args ["--closed-world" "--dynamic"]))))
  (is (= ["--tree-shake"] (:flags (jbuild/parse-args ["--tree-shake"]))))
  (is (= "small" (:boot (jbuild/parse-args ["--boot" "small"]))))
  (testing "an option's value is not read as a positional argument"
    (is (= "small" (:boot (jbuild/parse-args ["--boot" "small" "--no-smoke"])))))
  (is (true? (:force? (jbuild/parse-args ["--force"]))))
  (is (true? (:no-smoke? (jbuild/parse-args ["--no-smoke"]))))
  (is (true? (:help? (jbuild/parse-args ["-h"]))))
  (is (= "/tmp/kmet" (:out (jbuild/parse-args ["-o" "/tmp/kmet"]))))
  (is (= "/tmp/kmet" (:out (jbuild/parse-args ["--out" "/tmp/kmet"]))))
  (is (= "/opt/jolt" (:jolt (jbuild/parse-args ["--jolt" "/opt/jolt"])))))

(deftest parse-args-reads-cross-builds
  (let [opts (jbuild/parse-args ["--target" "tarm64le" "--target-pack" "/tmp/pack"])]
    (is (= "tarm64le" (:target opts)))
    (is (= "/tmp/pack" (:target-pack opts)))))

(deftest parse-args-rejects-bad-input
  (is (thrown-with-msg? Exception #"unknown option"
                        (jbuild/parse-args ["--optt"])))
  (testing "no positional targets: a jolt cross build needs a pack, not a download"
    (is (thrown-with-msg? Exception #"unexpected argument"
                          (jbuild/parse-args ["linux-amd64"]))))
  (is (thrown-with-msg? Exception #"--boot needs"
                        (jbuild/parse-args ["--boot" "medium"])))
  (is (thrown-with-msg? Exception #"--boot needs"
                        (jbuild/parse-args ["--boot"])))
  (is (thrown-with-msg? Exception #"--target needs"
                        (jbuild/parse-args ["--target"])))
  (is (thrown-with-msg? Exception #"--target-pack needs"
                        (jbuild/parse-args ["--target-pack"]))))

(deftest build-argv-pins-the-entry-and-output
  (let [argv #'jbuild/build-argv]
    (is (= ["build" "-m" "kmet.core" "-o" "/tmp/out/kmet"]
           (argv {:mode "release" :flags [] :out "/tmp/out/kmet"})))
    (testing "mode, boot and passthrough flags follow the output"
      (is (= ["build" "-m" "kmet.core" "-o" "/o" "--dev" "--closed-world"]
             (argv {:mode "dev" :flags ["--closed-world"] :out "/o"})))
      (is (= ["build" "-m" "kmet.core" "-o" "/o" "--opt" "--boot" "small"]
             (argv {:mode "optimized" :boot "small" :flags [] :out "/o"}))))
    (testing "cross builds carry the target and its pack"
      (is (= ["build" "-m" "kmet.core" "-o" "/o" "--target" "tarm64le" "--target-pack" "/p"]
             (argv {:mode "release" :flags [] :out "/o"
                    :target "tarm64le" :target-pack "/p"}))))))
