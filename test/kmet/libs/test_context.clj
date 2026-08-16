(ns kmet.libs.test-context
  (:require [clojure.test :as t]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [babashka.fs :as fs]
            [kmet.libs.context :as context]))

(defn- tmp-dir
  "Writable temp dir under target/ (ancestors may contain uncontrolled
   AGENTS.md files, e.g. this repo's own — assertions account for that)."
  [suffix]
  (str (fs/absolutize (fs/file "target" (str "test-ctx-" suffix "-" (System/currentTimeMillis))))))

(defn- under-tmp?
  "True when a path string is inside the temp tree."
  [tmp p]
  (str/starts-with? (str p) (str tmp "/")))

(t/deftest test-load-project-context-files
  (t/testing "agent dir file first, then nearest ancestor first (pi order)"
    (let [tmp (tmp-dir "order")
          agent-dir (str tmp "/agent")
          dir-a (str tmp "/a")
          dir-b (str tmp "/a/b")
          agent-file (str agent-dir "/AGENTS.md")
          a-file (str dir-a "/AGENTS.md")
          b-file (str dir-b "/CLAUDE.md")]
      (try
        (io/make-parents agent-file)
        (io/make-parents a-file)
        (io/make-parents b-file)
        (spit agent-file "# agent rules")
        (spit a-file "# a rules")
        (spit b-file "# b rules")
        (let [files (context/load-project-context-files agent-dir dir-b)]
          ;; take 3: ancestors above the temp tree may add more files
          (t/is (= [agent-file b-file a-file]
                   (take 3 (map :path files)))))
        (finally (fs/delete-tree tmp)))))
  (t/testing "missing context files contribute nothing (pi)"
    (let [tmp (tmp-dir "empty")
          x-dir (str tmp "/x")]
      (try
        (io/make-parents (str x-dir "/.keep"))
        (let [files (context/load-project-context-files (str tmp "/agent") x-dir)]
          (t/is (not-any? #(under-tmp? tmp %) (map :path files))))
        (finally (fs/delete-tree tmp)))))
  (t/testing "same file deduped when agent dir is inside the cwd chain (pi)"
    (let [tmp (tmp-dir "dedupe")
          agent-dir (str tmp "/agent")
          f (str agent-dir "/AGENTS.md")]
      (try
        (io/make-parents f)
        (spit f "# rules")
        (let [files (context/load-project-context-files agent-dir agent-dir)]
          (t/is (= f (first (map :path files))))
          (t/is (= 1 (count (filter #(under-tmp? tmp %) (map :path files))))))
        (finally (fs/delete-tree tmp))))))
