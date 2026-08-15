(ns kmet.changed
  "Dev-loop helper backing the `bb changed` / `bb *-changed` tasks: finds
   changed files and computes, via the require graph, which namespaces are
   affected. Test namespaces map 1:1 to source namespaces (test/kmet/x/test_y.clj
   ↔ src/kmet/x/y.clj), so a source change must also re-run the tests that
   transitively require it.

   extensions/ is first-class: its .clj files (source and any tests they
   carry) are part of the lint/format gates and the changed-file scan, and
   join the require graph so contract changes pull them into the lint
   closure. Extension tests are separate projects though — they run from
   inside their own directory against their own deps, never via the root
   runner — so extension namespaces are excluded from root test selection.

   Change detection: git diff vs HEAD + untracked files when the project is a
   git repo; otherwise a mtime comparison against a baseline file written by
   the test gates (`bb test` / `bb test-ext`, when green and unfiltered)
   — everything newer than the last full test run counts as changed."
  (:require [babashka.fs :as fs]
            [babashka.process :as proc]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def baseline-file
  ".kmet-changed-baseline")

(defn- git-repo?
  []
  (fs/exists? ".git"))

(defn- shell-out
  [& args]
  (:out (apply proc/shell {:out :string :err :string :continue true} args)))

(defn- git-changed-files
  "Tracked changes vs HEAD (staged + unstaged) plus untracked files, minus
   deleted ones. With no commits yet, `git diff HEAD` yields nothing and every
   file shows up as untracked, which is the correct bootstrap."
  []
  (->> (str/split-lines (str (shell-out "git" "diff" "--name-only" "HEAD")
                             "\n"
                             (shell-out "git" "ls-files" "--others" "--exclude-standard")))
       (remove str/blank?)
       (filter fs/exists?)
       distinct
       sort))

(defn- dir-clj-files
  "Every .clj file under DIR — top level and nested. java.nio's glob
   `**/*.clj` requires at least one directory level, so top-level files
   (e.g. extensions/tools.clj) need the `*.clj` pattern too."
  [dir]
  (concat (fs/glob dir "*.clj") (fs/glob dir "**/*.clj")))

(defn- mtime-changed-files
  "src/test/extensions .clj files modified after the baseline timestamp
   (mtime fallback without git; a missing baseline means everything changed)."
  []
  (let [base (try (Long/parseLong (str/trim (slurp baseline-file)))
                  (catch Exception _ 0))]
    (->> (mapcat dir-clj-files ["src" "test" "extensions"])
         (filter #(> (.toMillis (fs/last-modified-time %)) base))
         (map str)
         sort)))

(defn changed-files
  "All changed, still-existing files (git mode: everything vs HEAD; fallback:
   src/test .clj modified since the last full validation)."
  []
  (if (git-repo?)
    (git-changed-files)
    (mtime-changed-files)))

(defn changed-clj-files
  "Changed .clj files under src/, test/ and extensions/."
  []
  (filter #(re-matches #"(?:src|test|extensions)/.*\.clj" %) (changed-files)))

(defn mark-validated!
  "Record 'all gates green as of now' for the mtime fallback. No-op with git."
  []
  (when-not (git-repo?)
    (spit baseline-file (str (System/currentTimeMillis)))))

(defn config-changed?
  "True when clj-kondo config or hook files changed — those force a full lint."
  []
  (boolean (some #(str/starts-with? % ".clj-kondo/") (changed-files))))

(defn path->ns
  "Source/test file path to its namespace symbol
   (src/kmet/app/ui/model_selector.clj → kmet.app.ui.model-selector)."
  [path]
  (symbol
   (-> path
       (str/replace #"\.clj$" "")
       (str/replace "_" "-")
       (str/replace "/" ".")
       (str/replace #"^src\.|^test\." ""))))

(defn- read-ns-form
  "First form of PATH (the ns form), or nil when unreadable."
  [path]
  (try
    (with-open [r (java.io.PushbackReader. (io/reader path))]
      (read {:eof ::eof :read-cond :allow} r))
    (catch Exception _ nil)))

(defn ns-requires
  "The kmet.* namespace symbols NS-FORM requires. Handles vector entries,
   prefix-list entries (`(kmet.libs [a :as x] b)`) and bare symbols.
   Returns #{} for a valid ns form without a :require clause (the namespace
   still joins the graph), nil for non-ns forms."
  [ns-form]
  (when (and (seq? ns-form) (= 'ns (first ns-form)))
    (let [clause (some #(when (and (seq? %) (= :require (first %))) (rest %))
                       ns-form)]
      (if clause
        (letfn [(libs [form prefix]
                  (cond
                    (vector? form) (when (symbol? (first form))
                                     [(symbol (str prefix (when prefix ".") (first form)))])
                    (list? form) (mapcat #(libs % (first form)) (rest form))
                    (symbol? form) [(symbol (str prefix (when prefix ".") form))]
                    :else []))]
          (->> (mapcat #(libs % nil) clause)
               (filter #(str/starts-with? (str %) "kmet."))
               distinct
               set))
        #{}))))

(defn- scan-graph
  "The require graph (ns → set of required kmet.* nss) and ns → file path,
   from every src/test/extensions .clj file. Extension namespaces join the
   graph so changes to the extension contract (kmet.extension, kmet.tui.*,
   kmet.libs.*) pull dependent extension files into the lint closure."
  []
  (reduce (fn [acc f]
            (let [path (str f)
                  ns-sym (path->ns path)
                  form (read-ns-form path)
                  reqs (and form (ns-requires form))]
              (if reqs
                (-> acc
                    (update :graph assoc ns-sym reqs)
                    (update :paths assoc ns-sym path))
                acc)))
          {:graph {} :paths {}}
          (mapcat dir-clj-files ["src" "test" "extensions"])))

(defn- reverse-graph
  "ns → set of namespaces that require it."
  [graph]
  (reduce-kv (fn [acc ns-sym reqs]
               (reduce (fn [a r] (update a r (fnil conj #{}) ns-sym)) acc reqs))
             {} graph))

(defn- closure
  "ROOTS plus every namespace transitively depending on them."
  [roots rev]
  (loop [frontier (seq roots) seen (set roots)]
    (if-let [n (first frontier)]
      (let [deps (rev n #{})]
        (recur (concat (rest frontier) (remove seen deps))
               (into seen deps)))
      seen)))

(defn- test-ns?
  [ns-sym]
  (str/starts-with? (last (str/split (str ns-sym) #"\.")) "test-"))

(defn affected-test-nss-by
  "Test namespaces affected by CHANGED-NSS: the changed ones plus every test
   namespace that transitively requires them. Extension namespaces never
   enter the selection — their tests are separate projects (own deps and
   classpath) that run from inside the extension directory, so the root
   runner must not try to load them."
  [changed-nss]
  (let [{:keys [graph paths]} (scan-graph)
        rev (reverse-graph graph)
        root? (fn [ns-sym]
                (and (graph ns-sym)
                     (not (str/starts-with? (paths ns-sym) "extensions/"))))
        roots (filter root? changed-nss)
        tests (set (filter (fn [ns-sym]
                             (and (test-ns? ns-sym)
                                  (not (str/starts-with? (paths ns-sym) "extensions/"))))
                           (keys paths)))]
    (->> (closure roots rev) (filter tests) sort)))

(defn affected-test-nss
  "Test namespaces affected by the currently changed files."
  []
  (affected-test-nss-by (map path->ns (changed-clj-files))))

(defn affected-lint-files
  "Files to lint: the changed src/test .clj files plus every affected
   dependent (a changed signature is only flagged at the call site)."
  []
  (let [{:keys [graph paths]} (scan-graph)
        rev (reverse-graph graph)
        roots (filter graph (map path->ns (changed-clj-files)))
        closure-nss (closure roots rev)]
    (->> (concat (changed-clj-files) (map paths closure-nss))
         distinct
         sort)))

(defn all-clj-files
  "Every src/test/extensions .clj file (full lint when clj-kondo config
   changed)."
  []
  (->> (mapcat dir-clj-files ["src" "test" "extensions"])
       (map str)
       sort))

(defn extension-changed-files
  "Changed .clj files under extensions/. Their tests run from inside the
   extension directory, never from the root runner — the `bb *-changed`
   test tasks print a hint instead of silently skipping them."
  []
  (filter #(str/starts-with? % "extensions/") (changed-clj-files)))
