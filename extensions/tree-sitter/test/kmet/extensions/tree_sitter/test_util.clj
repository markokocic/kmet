(ns kmet.extensions.tree-sitter.test-util
  "Helpers shared by tree-sitter extension tests."
  (:require [babashka.fs :as fs]))

(def ^:private created-dirs (atom []))

(.addShutdownHook (Runtime/getRuntime)
                  (Thread. (fn []
                             (doseq [d @created-dirs]
                               (try (fs/delete-tree d) (catch Exception _))))))

(defn temp-dir!
  "Fresh temp dir under $TMPDIR (Termux ships without a usable /tmp),
   falling back to java.io.tmpdir, then to .ts-test-tmp in the cwd.
   Registered for deletion at JVM shutdown — tests never need to clean up."
  [prefix]
  (let [base (or (some-> (System/getenv "TMPDIR") fs/path)
                 (some-> (System/getProperty "java.io.tmpdir") fs/path)
                 (fs/path (fs/cwd) ".ts-test-tmp"))]
    (fs/create-dirs base)
    (let [d (fs/create-temp-dir {:dir base :prefix prefix})]
      (swap! created-dirs conj d)
      d)))
