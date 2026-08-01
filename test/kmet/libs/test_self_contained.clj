(ns kmet.libs.test-self-contained
  "Guard: every kmet.libs.* namespace must be self-contained — no requires
   on any other kmet.* namespace (app, tui, modes, or sibling libs). A lib
   should be extractable as a third-party library on its own."
  (:require [clojure.test :as t :refer [deftest is]]
            [clojure.string :as str]
            [babashka.fs :as fs]))

(defn- lib-files []
  (->> (fs/list-dir "src/kmet/libs")
       (filter #(str/ends-with? (str %) ".clj"))
       (map str)))

(defn- kmet-requires [path]
  (let [content (slurp path)
        ns-block (re-find #"\(ns\s+[\w.-]+(?:\s+.*?)?\)" content)]
    (when ns-block
      (->> (re-seq #"\[([\w.-]+)(?:\s+:as\s+\w+)?\]" ns-block)
           (map second)
           (filter #(str/starts-with? % "kmet."))
           (remove #(= % "kmet.libs"))))))

(deftest libs-are-self-contained
  (doseq [f (lib-files)]
    (let [deps (kmet-requires f)]
      (is (empty? deps)
          (str f " must not require kmet.* namespaces, found: " deps)))))
