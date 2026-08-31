(ns kmet.test-http-boundary
  "Phase-1 inventory guard for the outbound-HTTP boundary (http.md §7):
   every production/script/extension/test namespace must route outbound
   HTTP through kmet.libs.http. During the migration the retired
   kmet.libs.proxy / kmet.ai.proxy and their tests are the only allowed
   legacy users; this test fails when a NEW namespace requires
   babashka.http-client or a kmet.*.proxy, or spawns curl directly —
   preventing drift until phase 2 deletes the old boundary and flips the
   guard to strict."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [babashka.fs :as fs]))

(def ^:private allowed-legacy
  "Namespaces still legitimately on the old transport during phase 1
   (deleted in phase 2, http.md)."
  #{'kmet.libs.proxy
    'kmet.ai.proxy
    'kmet.libs.test-proxy
    'kmet.ai.test-proxy})

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
  "The forbidden libs required by a file: babashka.http-client and the
   kmet.*.proxy legacy namespaces, unless the file's namespace is in the
   phase-1 allowlist (kmet.libs.http itself owns the transport, so its own
   babashka.http-client require is fine)."
  [path]
  (let [n (ns-sym path)]
    (when-not (contains? allowed-legacy n)
      (->> (required-libs path)
           (filter #(or (= % 'babashka.http-client)
                        (= % 'kmet.libs.proxy)
                        (= % 'kmet.ai.proxy)))
           (remove #(and (= n 'kmet.libs.http)
                         (= % 'babashka.http-client)))))))

(defn- spawns-curl?
  "True when the file invokes curl directly (outside kmet.libs.http and
   the phase-1 legacy namespaces)."
  [path]
  (let [n (ns-sym path)]
    (and (not (contains? allowed-legacy n))
         (not= n 'kmet.libs.http)
         (str/includes? (slurp path) "\"curl\""))))

(deftest http-boundary-inventory
  (doseq [path (source-files)]
    (let [bad (offending-requires path)]
      (is (empty? bad)
          (str path " requires " bad
               " — outbound HTTP must go through kmet.libs.http")))
    (is (not (spawns-curl? path))
        (str path " invokes curl directly — only kmet.libs.http may spawn curl"))))
