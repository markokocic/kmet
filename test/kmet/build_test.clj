(ns kmet.build-test
  (:require [clojure.test :refer [deftest is testing]]
            [kmet.build :as build]))

(deftest slug-for-maps-os-arch-to-release-assets
  (is (= "linux-aarch64-static" (build/slug-for "linux" "aarch64")))
  (is (= "linux-aarch64-static" (build/slug-for "Linux" "arm64")))
  (is (= "linux-amd64-static" (build/slug-for "Linux" "amd64")))
  (is (= "macos-aarch64" (build/slug-for "Mac OS X" "aarch64")))
  (is (= "macos-amd64" (build/slug-for "Darwin" "x86_64")))
  (is (= "windows-amd64" (build/slug-for "Windows 11" "amd64")))
  (testing "unsupported combos have no slug"
    (is (nil? (build/slug-for "SunOS" "sparc")))
    (is (nil? (build/slug-for "Linux" "riscv64")))))

(deftest normalize-slug-expands-shorthands
  ;; "linux-amd64" is its own table entry (glibc variant); hosts default to
  ;; the static variant via slug-for
  (is (= "linux-amd64" (build/normalize-slug "linux-amd64")))
  (is (= "linux-aarch64-static" (build/normalize-slug "linux-aarch64")))
  ;; exact table entries pass through
  (is (= "windows-amd64" (build/normalize-slug "windows-amd64")))
  (is (nil? (build/normalize-slug "atari-2600"))))

(deftest parse-args-collects-targets-and-flags
  (is (= {:targets [] :all? false :force? false :no-smoke? false :help? false}
         (build/parse-args [])))
  (is (= {:targets ["linux-aarch64-static"] :all? true :force? true :no-smoke? false :help? false}
         (build/parse-args ["linux-aarch64" "--all" "--force"])))
  (is (= {:targets ["macos-aarch64" "windows-amd64"]
          :all? false :force? false :no-smoke? false :help? true}
         (build/parse-args ["macos-aarch64" "--help" "windows-amd64"]))))

(deftest parse-args-rejects-bad-input
  (is (thrown-with-msg? Exception #"unknown target"
                        (build/parse-args ["plan9"])))
  (is (thrown-with-msg? Exception #"unknown option"
                        (build/parse-args ["--wat"]))))
