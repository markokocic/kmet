(ns kmet.ai.test-self-contained
  "Guard: every kmet.ai.* namespace must be self-contained — no requires on
   app, tui, modes, or sibling ai namespaces beyond kmet.libs.*. This is the
   structural boundary of the provider/auth subsystem (pi: packages/ai is a
   standalone library the agent depends on; the agent never feeds back into
   it)."
  (:require [clojure.test :as t :refer [deftest is]]
            [clojure.string :as str]
            [babashka.fs :as fs]))

(defn- ai-files []
  (->> (fs/list-dir "src/kmet/ai")
       (filter #(str/ends-with? (str %) ".clj"))
       (map str)))

(defn- kmet-requires [path]
  (let [content (slurp path)
        ns-block (re-find #"(?s)\(ns\s+[\w.-]+(?:\s+.*?)?\)" content)]
    (when ns-block
      (->> (re-seq #"\[([\w.-]+)(?:\s+:as\s+\w+)?\]" ns-block)
           (map second)
           (filter #(str/starts-with? % "kmet."))
           (remove #(or (= % "kmet.ai")
                        (str/starts-with? % "kmet.ai.")
                        (str/starts-with? % "kmet.libs.")))))))

(deftest ai-is-self-contained
  (doseq [f (ai-files)]
    (let [deps (kmet-requires f)]
      (is (empty? deps)
          (str f " must only require kmet.libs.*, found: " deps)))))
