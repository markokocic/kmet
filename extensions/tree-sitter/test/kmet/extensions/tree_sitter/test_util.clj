(ns kmet.extensions.tree-sitter.test-util
  "Helpers shared by tree-sitter extension tests."
  (:require [babashka.fs :as fs]))

(defn temp-dir!
  "Fresh temp dir under $TMPDIR (Termux ships without a usable /tmp),
   falling back to java.io.tmpdir, then to .ts-test-tmp in the cwd."
  [prefix]
  (let [base (or (some-> (System/getenv "TMPDIR") fs/path)
                 (some-> (System/getProperty "java.io.tmpdir") fs/path)
                 (fs/path (fs/cwd) ".ts-test-tmp"))]
    (fs/create-dirs base)
    (fs/create-temp-dir {:dir base :prefix prefix})))
