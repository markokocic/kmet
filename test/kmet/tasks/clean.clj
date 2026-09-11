(ns kmet.tasks.clean
  "Dev-loop helper backing the `clean` task — `bb clean` and `jolt clean` are
   the same bb.edn task on both hosts: remove what the toolchain and the test
   suite leave in the working tree, so a checkout comes back to its committed
   state without re-cloning. The accumulation is real: the test suite writes
   its scratch under target/, which is also where the uberjar, the babashka
   build cache, jolt's AOT scratch and clj-kondo's reader-view mirrors live.

   Two things are deliberately kept. Source and other tracked files: a
   candidate containing a tracked path is skipped, `git ls-files` being the
   guard — no pattern can delete committed work, and clean needs no
   repository to run. And state a human owns: the project's .kmet/ (settings,
   skills, themes) and an editor's .lsp/; clean removes artifacts, not user
   configuration.

   See `artifacts` for the list and `-main` for the CLI."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.string :as str]))

;; ─── what clean removes ───────────────────────────────────────────────────

(def ^:private artifacts
  "The removal list: root-relative literals and globs, grouped for the report.
   Globs run with :hidden — the dot-directories are exactly a few of these
   targets — and a pattern must stay inside the project (no absolute path, no
   `..`, asserted by kmet.tasks.test-clean): the tracked-file guard only reasons
   about paths below the root.

   `target` swallows the test scratch too, so the suite's leftovers (thousands
   of scratch dirs, the lint mirrors) go with the build output. `.lsp/` is
   absent on purpose: a clojure-lsp settings.edn can live there."
  [{:label "build output" :paths ["target" "dist" "extensions/*/target"]}
   {:label "caches"
    :paths [".cpcache" ".jolt" ".clj-kondo/.cache" ".clj-kondo/imports"]}
   {:label "logs" :paths ["*.log"]}
   {:label "dev state" :paths [".kmet-changed-baseline"]}])

(defn- hr-size
  "Human-readable size for BYTES: whole bytes, one decimal above that."
  [bytes]
  (let [units ["B" "KiB" "MiB" "GiB" "TiB"]]
    (loop [n (double bytes), u 0]
      (if (and (>= n 1024) (< u (dec (count units))))
        (recur (/ n 1024) (inc u))
        (str (if (zero? u)
               (long n)
               (double (/ (Math/round (* n 10.0)) 10.0)))
             " " (nth units u))))))

(defn- tree-size
  "Bytes in PATH: a file's own size, or every regular file below a directory.
   Symlinks are not followed — their target may lie outside the project — and
   an unreadable entry counts 0 rather than aborting the removal."
  [path]
  (try
    (let [path (fs/path path)]
      (cond
        (fs/sym-link? path) 0
        (fs/directory? path) (reduce + 0 (map tree-size (fs/list-dir path)))
        :else (fs/size path)))
    (catch Exception _ 0)))

;; ─── finding them ─────────────────────────────────────────────────────────

(defn- expand
  "ROOT-relative paths matching PATTERN: the path itself for a literal, every
   glob hit otherwise."
  [root pattern]
  (if (str/includes? pattern "*")
    (mapv str (fs/glob (str root) pattern {:hidden true}))
    (let [path (fs/path root pattern)]
      (if (fs/exists? path) [(str path)] []))))

(defn- candidates
  "The existing artifact paths under ROOT, deepest first: a child is removed
   before a parent that would swallow it, and a parent the guard skips leaves
   its children's removal (they are artifacts either way) intact."
  [root]
  (->> artifacts
       (mapcat (fn [{:keys [paths]}]
                 (mapcat #(expand root %) paths)))
       (sort-by count #(compare %2 %1))))

(defn- tracked
  "The paths git tracks in ROOT, repo-relative, or nil when ROOT is not a git
   work tree — nothing to guard then."
  [root]
  (when (fs/exists? (fs/path root ".git"))
    (let [{:keys [exit out]} (p/shell {:continue true :out :string :err :string}
                                      "git" "-C" (str root) "ls-files" "-z")]
      (when (zero? exit)
        (set (remove str/blank? (str/split (str out) #"\u0000")))))))

(defn- guard-rel
  "PATH as a repo-relative string with / separators, the spelling `tracked`
   reports — the two are compared literally."
  [root path]
  (-> (str (fs/normalize (fs/relativize (fs/absolutize (str root))
                                        (fs/absolutize (str path)))))
      (str/replace "\\" "/")))

(defn- protected?
  "True when git tracks REL or anything below it — a file clean must not
   touch, or (for a directory) the reason to leave its whole tree alone."
  [tracked-set rel]
  (boolean (and tracked-set
                (some #(or (= % rel) (str/starts-with? % (str rel "/"))) tracked-set))))

;; ─── the task ─────────────────────────────────────────────────────────────

(defn clean!
  "Remove the project's artifacts under ROOT (default: the cwd), printing one
   line per path and a summary. With :dry-run? nothing is deleted — the report
   then says what would go. Returns {:removed [...rels] :kept [...rels]
   :bytes n :dry-run? bool}, rels being root-relative so the caller can assert
   on them."
  [opts]
  (let [root (str (or (:root opts) (fs/cwd)))
        dry-run? (boolean (:dry-run? opts))
        tracked-set (tracked root)
        results (reduce (fn [acc path]
                          (let [rel (guard-rel root path)
                                size (tree-size path)]
                            (cond
                              (protected? tracked-set rel)
                              (update acc :kept conj rel)

                              :else
                              (do (println (str "clean: " (if dry-run? "would remove " "removed ")
                                                rel " (" (hr-size size) ")"))
                                  (when-not dry-run?
                                    (if (fs/directory? path)
                                      (fs/delete-tree path)
                                      (fs/delete path)))
                                  (-> acc
                                      (update :removed conj rel)
                                      (update :bytes + size))))))
                        {:removed [] :kept [] :bytes 0}
                        (candidates root))]
    (doseq [rel (:kept results)]
      (println (str "clean: kept " rel " (tracked by git)")))
    (cond
      (and (empty? (:removed results)) (empty? (:kept results)))
      (println "clean: nothing to remove")

      (empty? (:removed results))
      (println (str "clean: nothing to remove (" (count (:kept results))
                    " path(s) kept)"))

      dry-run?
      (println (str "clean: dry run — " (count (:removed results)) " path(s), "
                    (hr-size (:bytes results)) " reclaimable"
                    (when (seq (:kept results)) (str ", " (count (:kept results)) " kept)"))))

      :else
      (println (str "clean: " (count (:removed results)) " path(s) removed, "
                    (hr-size (:bytes results)) " freed"
                    (when (seq (:kept results))
                      (str ", " (count (:kept results)) " kept)")))))
    (assoc results :dry-run? dry-run?)))

(def ^:private usage
  "clean [--dry-run|-n]

   Remove what this project's toolchain and test suite leave behind:
     build output   target/  dist/  extensions/*/target/
     caches         .cpcache/  .jolt/  .clj-kondo/{.cache,imports}/
     logs           *.log
     dev state      .kmet-changed-baseline
   Source and anything else git tracks is never touched, nor is .kmet/ or
   .lsp/ (user configuration). --dry-run lists without removing.")

(defn- parse-args
  [args]
  (reduce (fn [opts arg]
            (case arg
              ("-n" "--dry-run") (assoc opts :dry-run? true)
              ("-h" "--help") (assoc opts :help? true)
              (throw (ex-info (str "unknown option: " arg)
                              {:type ::usage :argument arg}))))
          {:dry-run? false :help? false}
          args))

(defn -main
  "The `clean` task entry point: run clean! over the cwd. `--dry-run` reports
   without deleting, `--help` prints the usage."
  [& args]
  (let [opts (try (parse-args args)
                  (catch Exception e
                    (println (str "clean: " (ex-message e)))
                    (println usage)
                    (System/exit 2)))]
    (if (:help? opts)
      (println usage)
      (clean! opts))))
