(ns kmet.test-http-boundary
  "Strict guard for the outbound-HTTP boundary (http.md §7, phase 2):
   every production/script/extension/test namespace must route outbound
   HTTP through kmet.libs.http. Only kmet.libs.http itself may require
   babashka.http-client or spawn curl; the retired kmet.libs.proxy /
   kmet.ai.proxy namespaces are deleted, so any require of them (or of
   babashka.http-client) fails the build — preventing the abstraction
   from eroding later."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [babashka.fs :as fs]))

(defn- source-files
  "Every .clj file under the given dirs (src, scripts, extensions, test)."
  []
  (->> ["src" "scripts" "extensions" "test"]
       (mapcat #(when (fs/directory? %) (fs/glob % "**/*.clj")))
       (map str)))

(defn- ns-sym [path]
  (try
    (with-open [rdr (java.io.PushbackReader. (java.io.InputStreamReader.
                                              (java.io.FileInputStream. path)))]
      (let [form (read rdr)]
        (when (and (list? form) (= 'ns (first form)))
          (second form))))
    (catch Exception _ nil)))

(defn- required-libs
  "The library symbols in a file's ns require clauses."
  [path]
  (let [content (slurp path)
        ns-block (re-find #"(?s)\(ns\s+[\w.-]+(?:\s+.*?)?\)" content)]
    (when ns-block
      (->> (re-seq #"\[([\w.-]+)(?:\s+:as\s+\w+)?\]" ns-block)
           (map second)
           (map symbol)))))

(defn- offending-requires
  "The forbidden libs required by a file: babashka.http-client (only
   kmet.libs.http may use it) and the deleted kmet.*.proxy namespaces."
  [path]
  (let [n (ns-sym path)]
    (when-not (= n 'kmet.libs.http)
      (->> (required-libs path)
           (filter #(or (= % 'babashka.http-client)
                        (= % 'kmet.libs.proxy)
                        (= % 'kmet.ai.proxy)))))))

(defn- spawns-curl?
  "True when the file invokes curl directly (outside kmet.libs.http)."
  [path]
  (and (not= (ns-sym path) 'kmet.libs.http)
       (str/includes? (slurp path) "\"curl\"")))

(deftest http-boundary-strict
  (doseq [path (source-files)]
    (let [bad (offending-requires path)]
      (is (empty? bad)
          (str path " requires " bad
               " — outbound HTTP must go through kmet.libs.http")))
    (is (not (spawns-curl? path))
        (str path " invokes curl directly — only kmet.libs.http may spawn curl"))))
