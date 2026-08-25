(ns kmet.extensions.tree-sitter.fetch-test
  (:require [babashka.fs :as fs]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [kmet.extensions.tree-sitter.fetch :as fetch]
            [kmet.extensions.tree-sitter.test-util :as tu])
  (:import [java.io ByteArrayInputStream]
           [java.util.zip ZipEntry ZipOutputStream]))

(def ^:private abc-sha
  "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")

(defn- make-zip!
  "Write a zip with {entry-name content-string} entries; returns its path."
  [path entries]
  (with-open [zos (ZipOutputStream. (io/output-stream (fs/file path)))]
    (doseq [[name content] entries]
      (.putNextEntry zos (ZipEntry. name))
      (.write zos (.getBytes content "UTF-8"))
      (.closeEntry zos)))
  path)

(deftest sha256-test
  (let [tmp (tu/temp-dir! "ts-fetch")]
    (spit (str (fs/path tmp "abc")) "abc")
    (is (= abc-sha (fetch/sha256 (fs/path tmp "abc"))))
    ;; sanity: a different content hashes differently
    (spit (str (fs/path tmp "abx")) "abx")
    (is (not= abc-sha (fetch/sha256 (fs/path tmp "abx"))))))

(deftest store-and-verify!-test
  (testing "happy path: stream lands atomically at dest"
    (let [tmp (tu/temp-dir! "ts-store")
          dest (fs/path tmp "blob")
          content "hello world"]
      (spit (str (fs/path tmp "ref")) content)
      (let [expected (fetch/sha256 (fs/path tmp "ref"))]
        (is (= dest (fetch/store-and-verify!
                     (ByteArrayInputStream. (.getBytes content "UTF-8"))
                     dest expected)))
        (is (= content (slurp (str dest))))))
    (testing "no temp litter left behind"
      (let [tmp (tu/temp-dir! "ts-store2")
            dest (fs/path tmp "blob")]
        (spit (str (fs/path tmp "ref")) "x")
        (fetch/store-and-verify! (ByteArrayInputStream. (.getBytes "x" "UTF-8"))
                                 dest (fetch/sha256 (fs/path tmp "ref")))
        (is (empty? (filter #(str/includes? (str %) ".part-")
                            (fs/list-dir tmp)))))))
  (testing "sha mismatch: throws, dest untouched, temp cleaned"
    (let [tmp (tu/temp-dir! "ts-mismatch")
          dest (fs/path tmp "blob")
          ex (try (fetch/store-and-verify! (ByteArrayInputStream. (.getBytes "tampered" "UTF-8"))
                                           dest abc-sha)
                  nil
                  (catch Exception e e))]
      (is (some? ex))
      (is (= ::fetch/sha-mismatch (:type (ex-data ex))))
      (is (not (fs/exists? dest)))
      (is (empty? (filter #(str/includes? (str %) ".part-")
                          (fs/list-dir tmp)))))))

(deftest extract-zip!-test
  (let [tmp (tu/temp-dir! "ts-zip")
        zip (make-zip! (fs/path tmp "a.zip")
                       {"tree-sitter" "#!/bin/sh\necho hi\n"
                        "nested/dir/readme.txt" "contents"})
        out (fs/path tmp "out")]
    (testing "extracts all file entries, creating nested dirs"
      (let [extracted (fetch/extract-zip! zip out)]
        (is (= 2 (count extracted)))
        (is (= "#!/bin/sh\necho hi\n" (slurp (str (fs/path out "tree-sitter")))))
        (is (= "contents" (slurp (str (fs/path out "nested/dir/readme.txt")))))))))

(deftest extract-zip!-zip-slip-test
  (doseq [name ["../evil.txt" "/abs/evil.txt"]]
    (let [tmp (tu/temp-dir! "ts-slip")
          zip (make-zip! (fs/path tmp "evil.zip") {name "nope"})
          ex (try (fetch/extract-zip! zip (fs/path tmp "out"))
                  nil
                  (catch Exception e e))]
      (is (some? ex) (str "expected rejection for entry " name))
      (is (= ::fetch/unsafe-zip-entry (:type (ex-data ex))))
      (is (not (fs/exists? (fs/path tmp "evil.txt")))))))

(deftest binary-release-test
  (let [{:keys [version targets]} (fetch/binary-release)]
    (testing "one pinned version, six targets, well-formed entries"
      (is (re-matches #"\d+\.\d+\.\d+" version))
      (is (= #{"linux-x64" "linux-arm64" "macos-x64" "macos-arm64"
               "windows-x64" "windows-arm64"}
             (set (keys targets))))
      (doseq [[target {:keys [url sha256 binary-sha256]}] targets]
        (is (re-find #"github\.com/tree-sitter/tree-sitter/releases/download/" url)
            target)
        (is (re-matches #"[0-9a-f]{64}" sha256) target)
        (is (re-matches #"[0-9a-f]{64}" binary-sha256) target)
        (is (not= sha256 binary-sha256) target)))
    (testing "host target is covered by the manifest"
      (is (contains? targets (fetch/host-target))))))
