(ns kmet.app.test-packages
  "Package manager tests (pi: core/package-manager.ts local-source subset):
   source parsing, path identity, settings persistence, filter patterns,
   per-package resolution and the package resource loaders."
  (:require [clojure.test :as t]
            [clojure.string :as str]
            [clojure.edn :as edn]
            [babashka.fs :as fs]
            [kmet.app.packages :as pkgs]
            [kmet.app.prompts :as prompts]
            [kmet.app.skills :as skills]
            [kmet.config :as cfg]
            [kmet.tui.theme :as theme]))

(defn- tmp-dir []
  (str (fs/create-temp-dir {:dir (System/getenv "TMPDIR")})))

(defn- make-package
  "A conventional kmet package directory: extensions/, skills/ (a SKILL.md
   root plus a flat .md), prompts/, themes/."
  [root]
  (fs/create-dirs (str root "/extensions"))
  (fs/create-dirs (str root "/skills/root"))
  (fs/create-dirs (str root "/prompts"))
  (fs/create-dirs (str root "/themes"))
  (spit (str root "/extensions/one.clj") "(ns pkg-one)\n")
  (spit (str root "/extensions/two.clj") "(ns pkg-two)\n")
  (spit (str root "/extensions/three.clj") "(ns pkg-three)\n")
  (spit (str root "/skills/root/SKILL.md")
        "---\nname: skill-a\ndescription: skill a\n---\nbody\n")
  (spit (str root "/skills/flat.md")
        "---\nname: flat-skill\ndescription: flat skill\n---\nbody\n")
  (spit (str root "/prompts/x.md") "# x\n")
  (spit (str root "/prompts/y.md") "# y\n")
  (spit (str root "/themes/t.edn")
        (pr-str {:name "package-theme"
                 :colors (into {} (map (fn [k] [(name k) "#000000"]))
                               (concat theme/FG-TOKENS theme/BG-TOKENS))}))
  root)

;; ─── Source parsing ────────────────────────────────────────────────────────

(t/deftest test-source-parsing
  (t/testing "local sources"
    (t/is (pkgs/local-source? "./rel"))
    (t/is (pkgs/local-source? "/abs"))
    (t/is (pkgs/local-source? "~/home"))
    (t/is (pkgs/local-source? "bare-name"))
    (t/is (= :local (:kind (pkgs/parse-source "  ./x  "))))
    (t/is (= "./x" (:path (pkgs/parse-source "  ./x  ")))))
  (t/testing "remote sources (npm/git — not installable by kmet)"
    (t/is (not (pkgs/local-source? "npm:@foo/bar")))
    (t/is (not (pkgs/local-source? "git:github.com/user/repo")))
    (t/is (not (pkgs/local-source? "git://host/repo")))
    (t/is (not (pkgs/local-source? "https://host/repo")))
    (t/is (not (pkgs/local-source? "ssh://git@host/repo")))
    (t/is (= :remote (:kind (pkgs/parse-source "npm:foo@1.0.0"))))
    (t/is (= :remote (:kind (pkgs/parse-source "git://host/x"))))))

;; ─── Resolution over a package directory ───────────────────────────────────

(t/deftest test-resolve-conventional-package
  (let [dir (make-package (tmp-dir))
        res (pkgs/resolve-package-items {:packages [dir]} nil)]
    (t/is (= #{:extensions :skills :prompts :themes} (set (keys res))))
    (t/is (= 3 (count (:extensions res))))
    (t/is (= 2 (count (:skills res))))
    (t/is (= 2 (count (:prompts res))))
    (t/is (= 1 (count (:themes res))))
    (t/is (every? :enabled (mapcat res (keys res))))
    (t/is (= dir (get-in (first (:extensions res)) [:metadata :base-dir])))
    (t/is (= dir (get-in (first (:skills res)) [:metadata :base-dir])))))

(t/deftest test-resolve-single-file-package
  (let [dir (tmp-dir)
        f (str dir "/ext.clj")]
    (spit f "(ns ext)\n")
    (let [res (pkgs/resolve-package-items {:packages [f]} nil)]
      (t/is (= [f] (mapv :path (:extensions res))))
      (t/is (empty? (:skills res)))
      (t/is (= dir (get-in (first (:extensions res)) [:metadata :base-dir]))))))

(t/deftest test-resolve-extension-dir-package
  (let [dir (tmp-dir)]
    (spit (str dir "/extension.edn") "{:name \"pkg-ext\" :entry pkg.ext}\n")
    (spit (str dir "/pkg_ext.clj") "(ns pkg.ext)\n")
    (let [res (pkgs/resolve-package-items {:packages [dir]} nil)]
      (t/is (= [dir] (mapv :path (:extensions res))))
      (t/is (empty? (:skills res))))))

(t/deftest test-resolve-bare-dir-is-extension-container
  (let [dir (tmp-dir)]
    (spit (str dir "/a.clj") "(ns bare-a)\n")
    (spit (str dir "/b.clj") "(ns bare-b)\n")
    (let [res (pkgs/resolve-package-items {:packages [dir]} nil)]
      (t/is (= #{(str dir "/a.clj") (str dir "/b.clj")}
               (set (map :path (:extensions res))))))))

(t/deftest test-missing-package-skipped
  (let [res (pkgs/resolve-package-items {:packages [(str (tmp-dir) "/nope")]} nil)]
    (t/is (empty? (:extensions res)))))

(t/deftest test-remote-package-warns-and-skips
  (let [out (java.io.StringWriter.)]
    (binding [*err* out]
      (let [res (pkgs/resolve-package-items {:packages ["npm:@foo/bar"]} nil)]
        (t/is (empty? (:extensions res)))
        (t/is (str/includes? (str out) "remote source"))))))

;; ─── Filters (object entries) ──────────────────────────────────────────────

(t/deftest test-filter-patterns
  (let [dir (make-package (tmp-dir))]
    (t/testing "glob include + exclude"
      (let [res (pkgs/resolve-package-items
                 {:packages [{:source dir :extensions ["extensions/*.clj" "!extensions/two.clj"]}]} nil)]
        (t/is (= #{"one.clj" "three.clj"}
                 (set (map (comp fs/file-name :path)
                           (filter :enabled (:extensions res))))))))
    (t/testing "empty array disables that type (items stay, unchecked)"
      (let [res (pkgs/resolve-package-items {:packages [{:source dir :skills []}]} nil)]
        (t/is (= 2 (count (:skills res))))
        (t/is (every? (complement :enabled) (:skills res)))
        (t/is (= 3 (count (:extensions res))))
        (t/is (every? :enabled (:extensions res)))
        (t/is (= 2 (count (:prompts res))))))
    (t/testing "-path force-excludes exactly"
      (let [res (pkgs/resolve-package-items {:packages [{:source dir :extensions ["-extensions/two.clj"]}]} nil)]
        (t/is (= 3 (count (:extensions res))))
        (t/is (= #{"one.clj" "three.clj"}
                 (set (map (comp fs/file-name :path)
                           (filter :enabled (:extensions res))))))))
    (t/testing "bare name include matches any file of that name"
      (let [res (pkgs/resolve-package-items {:packages [{:source dir :extensions ["two.clj"]}]} nil)]
        (t/is (= 1 (count (filter :enabled (:extensions res)))))))
    (t/testing "skill patterns may name the skill root"
      (let [res (pkgs/resolve-package-items {:packages [{:source dir :skills ["skills/root"]}]} nil)]
        (t/is (= 1 (count (filter :enabled (:skills res)))))))
    (t/testing "prompt include"
      (let [res (pkgs/resolve-package-items {:packages [{:source dir :prompts ["prompts/x.md"]}]} nil)]
        (t/is (= 1 (count (filter :enabled (:prompts res)))))))))

(t/deftest test-filter-on-single-artifact-ignored
  ;; files and extension.edn dirs are single extensions — filters don't
  ;; apply (pi: file sources bypass package filters)
  (let [dir (tmp-dir)
        f (str dir "/ext.clj")]
    (spit f "(ns ext)\n")
    (let [res (pkgs/resolve-package-items {:packages [{:source f :extensions []}]} nil)]
      (t/is (= 1 (count (:extensions res)))))))

;; ─── Scope precedence and deltas ───────────────────────────────────────────

(t/deftest test-project-entry-wins
  (let [dir (make-package (tmp-dir))
        res (pkgs/resolve-package-items {:packages [{:source dir :extensions []}]}
                                        {:packages [dir]})]
    (t/is (= 3 (count (:extensions res))))
    (t/is (= :project (get-in (first (:extensions res)) [:metadata :scope])))))

(t/deftest test-autoload-false-delta-over-user
  (let [dir (make-package (tmp-dir))
        res (pkgs/resolve-package-items
             {:packages [{:source dir :extensions ["-extensions/two.clj"]}]}
             {:packages [{:source dir :autoload false
                          :extensions ["+extensions/two.clj"]}]})]
      ;; user entry: one.clj + three.clj; delta re-enables two.clj
    (t/is (= #{"one.clj" "two.clj" "three.clj"}
             (set (map (comp fs/file-name :path) (:extensions res)))))
    (t/is (every? :enabled (:extensions res)))))

(t/deftest test-autoload-false-empty-is-no-op
  (let [dir (make-package (tmp-dir))
        res (pkgs/resolve-package-items {:packages [dir]}
                                        {:packages [{:source dir :autoload false :extensions []}]})]
    (t/is (= 3 (count (:extensions res))))))
(t/deftest test-canonical-dedupe-across-packages
  ;; the same file listed by two packages resolves once (first wins)
  (let [root (make-package (tmp-dir))
        res (pkgs/resolve-package-items {:packages [root]} {:packages [root]})]
    (t/is (= 2 (count (:skills res))))))

(t/deftest test-resolve-preserves-package-order
  ;; >8 items of one type must keep the resolution order (package order,
  ;; discovery order within each) — a state map would reorder them
  (let [p1 (str (fs/path (tmp-dir) "p1"))
        p2 (str (fs/path (tmp-dir) "p2"))]
    (doseq [root [p1 p2]]
      (fs/create-dirs (str root "/extensions"))
      (doseq [i (range 10)]
        (spit (str root "/extensions/e" i ".clj") "(ns x)\n")))
    (let [res (pkgs/resolve-package-items {:packages [p1 p2]} nil)]
      (t/is (= (into (mapv #(str "e" % ".clj") (range 10))
                     (mapv #(str "e" % ".clj") (range 10)))
               (mapv (comp fs/file-name :path) (:extensions res)))))))

;; ─── Settings persistence (add / remove / list) ───────────────────────────

(defn- with-isolated-settings
  "Run F with the global settings file, agent dir, project settings file
   and project dir pointed at temp dirs (pi user/project scope)."
  [f]
  (let [global-dir (tmp-dir)
        project-dir (str (fs/path (tmp-dir) ".kmet"))]
    (with-redefs [cfg/global-settings-path (fn [] (str (fs/path global-dir "settings.edn")))
                  cfg/get-agent-dir (fn [] global-dir)
                  cfg/project-settings-path (fn [] (str (fs/path project-dir "settings.edn")))
                  cfg/project-dir (fn [] project-dir)]
      (f {:global-dir global-dir :project-dir project-dir}))))

(t/deftest test-add-and-remove-user-package
  (with-isolated-settings
    (fn [{:keys [global-dir]}]
      (let [pkg-dir (str (fs/path global-dir "my-pkg"))
            _ (fs/create-dirs pkg-dir)
            pkg-file (str pkg-dir "/ext.clj")]
        (spit pkg-file "(ns x)\n")
        (t/testing "add stores the path relative to the agent dir"
          (t/is (true? (pkgs/add-package-to-settings! pkg-file)))
          (let [settings (edn/read-string (slurp (str (fs/path global-dir "settings.edn"))))]
            (t/is (= ["my-pkg/ext.clj"] (:packages settings)))))
        (t/testing "re-adding the same package reports no change"
          (t/is (false? (pkgs/add-package-to-settings! pkg-file))))
        (t/testing "adding a sibling spelling matches and reports no change"
          (t/is (false? (pkgs/add-package-to-settings! (str pkg-dir "/sub/../ext.clj")))))
        (t/testing "list shows the configured package with its installed path"
          (let [listed (pkgs/list-configured-packages)]
            (t/is (= 1 (count listed)))
            (t/is (= "my-pkg/ext.clj" (:source (first listed))))
            (t/is (= :user (:scope (first listed))))
            (t/is (= pkg-file (:installed-path (first listed))))))
        (t/testing "remove deletes the entry"
          (t/is (true? (pkgs/remove-package-from-settings! pkg-file)))
          (t/is (empty? (:packages (edn/read-string (slurp (str (fs/path global-dir "settings.edn")))))))
          (t/is (false? (pkgs/remove-package-from-settings! pkg-file))))))))

(t/deftest test-add-project-package
  (with-isolated-settings
    (fn [{:keys [project-dir]}]
      (let [pkg-dir (str (fs/normalize (fs/path project-dir ".." "pkgs")))
            _ (fs/create-dirs pkg-dir)
            pkg-file (str pkg-dir "/ext.clj")]
        (spit pkg-file "(ns x)\n")
        (t/is (true? (pkgs/add-package-to-settings! pkg-file {:local true})))
        (let [settings (edn/read-string (slurp (str (fs/path project-dir "settings.edn"))))]
          (t/is (= ["../pkgs/ext.clj"] (:packages settings))))
        (let [listed (pkgs/list-configured-packages)]
          (t/is (= 1 (count listed)))
          (t/is (= :project (:scope (first listed)))))))))

(t/deftest test-list-empty
  (with-isolated-settings
    (fn [_]
      (t/is (empty? (pkgs/list-configured-packages))))))

(t/deftest test-remove-missing-package
  (with-isolated-settings
    (fn [_]
      (t/is (false? (pkgs/remove-package-from-settings! "/does/not/exist"))))))

(t/deftest test-remove-by-sibling-spelling
  (with-isolated-settings
    (fn [{:keys [global-dir]}]
      (let [pkg-dir (str (fs/path global-dir "pkgs"))
            _ (fs/create-dirs pkg-dir)]
        (pkgs/add-package-to-settings! pkg-dir)
        (t/is (true? (pkgs/remove-package-from-settings!
                      (str pkg-dir "/../pkgs"))))))))

(t/deftest test-object-entry-list-and-remove
  (with-isolated-settings
    (fn [{:keys [global-dir]}]
      (let [pkg-dir (str (fs/path global-dir "pkgs"))
            _ (fs/create-dirs pkg-dir)]
        (cfg/save-setting! [:packages] [{:source (pkgs/normalize-source-for-settings pkg-dir :user)
                                         :extensions []}])
        (let [listed (pkgs/list-configured-packages)]
          (t/is (= 1 (count listed)))
          (t/is (true? (:filtered (first listed)))))
        (t/is (true? (pkgs/remove-package-from-settings! pkg-dir)))))))

(t/deftest test-normalize-relative-storage
  (with-isolated-settings
    (fn [{:keys [global-dir]}]
      (t/is (= "sub/x.clj"
               (pkgs/normalize-source-for-settings (str (fs/path global-dir "sub" "x.clj")) :user))))))

(t/deftest test-normalize-source-at-base-dir
  (with-isolated-settings
    (fn [{:keys [global-dir project-dir]}]
      (t/testing "a source that is the scope base dir stores as \".\" (not blank)"
        (t/is (= "." (pkgs/normalize-source-for-settings global-dir :user)))
        (t/is (= "." (pkgs/normalize-source-for-settings project-dir :project)))))))

;; ─── The package resource loaders ─────────────────────────────────────────

(defn- with-empty-registries
  [f]
  (skills/clear-skills!)
  (prompts/clear-prompt-templates!)
  (try (f)
       (finally
         (skills/clear-skills!)
         (prompts/clear-prompt-templates!))))

(defn- with-settings
  "Run F with user settings pointing at PACKAGES (absolute temp paths, so
   no scope-base resolution is involved)."
  [f & packages]
  (with-redefs [pkgs/user-settings-map (fn [] {:packages (vec packages)})
                pkgs/project-settings-map (fn [] {})]
    (f)))

(t/deftest test-load-package-skills-and-prompts
  (with-empty-registries
    (fn []
      (let [dir (make-package (tmp-dir))]
        (with-settings (fn []
                         (pkgs/load-package-skills!)
                         (pkgs/load-package-prompts!)
                         (t/is (= #{"skill-a" "flat-skill"}
                                  (set (map :name (skills/get-skills)))))
                         (t/is (= #{"x" "y"}
                                  (set (map :name (prompts/get-prompt-templates))))))
          dir)))))

(t/deftest test-package-loaders-respect-filters
  (with-empty-registries
    (fn []
      (let [dir (make-package (tmp-dir))]
        (with-settings (fn []
                         (pkgs/load-package-skills!)
                         (t/is (= #{"skill-a"}
                                  (set (map :name (skills/get-skills))))))
          {:source dir :skills ["-skills/flat.md"]})))))

(t/deftest test-package-themes-load
  (let [dir (make-package (tmp-dir))]
    (try
      (with-settings (fn []
                       (pkgs/load-package-themes!)
                       (t/is (some? (theme/get-theme-by-name "package-theme"))))
        {:source dir})
      (finally
        (theme/unregister-theme! "package-theme")))))

;; ─── Pattern engine ────────────────────────────────────────────────────────

(t/deftest test-apply-patterns
  (let [base (str (fs/path "/pkg"))
        paths ["/pkg/extensions/a.clj" "/pkg/extensions/b.clj" "/pkg/extensions/c.clj"]
        rel (fn [p] (str/replace p (str base "/") ""))]
    (t/is (= #{"extensions/a.clj" "extensions/b.clj" "extensions/c.clj"}
             (set (map rel (pkgs/apply-patterns paths ["extensions/*.clj"] base)))))
    (t/testing "exclude glob"
      (t/is (= #{"extensions/a.clj" "extensions/c.clj"}
               (set (map rel (pkgs/apply-patterns paths
                                                  ["extensions/*.clj" "!extensions/b.clj"] base))))))
    (t/testing "force-exclude exact"
      (t/is (= #{"extensions/a.clj" "extensions/c.clj"}
               (set (map rel (pkgs/apply-patterns paths ["-extensions/b.clj"] base))))))
    (t/testing "force-include adds back over an exclude"
      (t/is (= #{"extensions/b.clj"}
               (set (map rel (pkgs/apply-patterns paths
                                                  ["!extensions/*" "+extensions/b.clj"] base))))))
    (t/testing "plain name pattern"
      (t/is (= #{"extensions/a.clj"}
               (set (map rel (pkgs/apply-patterns paths ["a.clj"] base))))))
    (t/testing "no includes means everything"
      (t/is (= 3 (count (pkgs/apply-patterns paths [] base)))))
    (t/testing "absolute-path glob"
      (t/is (= 1 (count (pkgs/apply-patterns paths [(str base "/extensions/b.clj")] base))))))
  (t/testing "double-star crosses directories"
    (let [paths ["/pkg/a/b/c.md" "/pkg/x.md"]]
      (t/is (= 2 (count (pkgs/apply-patterns paths ["**/*.md"] "/pkg"))))
      (t/is (= 1 (count (pkgs/apply-patterns paths ["a/**/*.md"] "/pkg"))))))
  (t/testing "trailing double-star matches any depth"
    (t/is (= 2 (count (pkgs/apply-patterns ["/pkg/skills/a.md" "/pkg/skills/x/y.md"]
                                           ["skills/**"] "/pkg"))))
    (t/is (= 2 (count (pkgs/apply-patterns ["/pkg/a/b/c.md" "/pkg/x.md"]
                                           ["**"] "/pkg")))))
  (t/testing "wildcards need an explicit dot for hidden segments"
    (t/is (= 0 (count (pkgs/apply-patterns ["/pkg/.hidden.md"] ["*.md"] "/pkg"))))
    (t/is (= 1 (count (pkgs/apply-patterns ["/pkg/.hidden.md"] [".*.md"] "/pkg"))))))

(t/deftest test-glob-classes
  ;; minimatch character-class parity: malformed classes never throw, `[.]`
  ;; writes the dot itself, a class containing `/` is literal text
  (let [m (fn [pat paths] (vec (sort (pkgs/apply-patterns paths [pat] "/pkg"))))]
    (t/testing "[.] is a literal dot, so it matches a hidden segment"
      (t/is (= ["/pkg/.hidden"] (m "[.]hidden" ["/pkg/.hidden" "/pkg/x"]))))
    (t/testing "a class that can match other characters keeps the hidden guard"
      (t/is (= ["/pkg/ax"] (m "[a.]x" ["/pkg/.x" "/pkg/ax"])))
      (t/is (= ["/pkg/bx"] (m "[!a]x" ["/pkg/.x" "/pkg/bx" "/pkg/ax"]))))
    (t/testing "malformed classes are literals or never-match (no regex error)"
      (t/is (= ["/pkg/[]x"] (m "[]x" ["/pkg/[]x" "/pkg/x"])))
      (t/is (= ["/pkg/[!]"] (m "[!]" ["/pkg/[!]" "/pkg/x"])))
      (t/is (= ["/pkg/[a"] (m "[a" ["/pkg/[a" "/pkg/a"])))
      (t/is (empty? (m "[z-a]" ["/pkg/z" "/pkg/a"]))))
    (t/testing "a reversed range inside a wider class is dropped"
      (t/is (= ["/pkg/x"] (m "[z-ax]" ["/pkg/x" "/pkg/z" "/pkg/a"]))))
    (t/testing "a class containing / is literal (minimatch splits on /)"
      (t/is (= ["/pkg/[a/b].clj"] (m "[a/b].clj" ["/pkg/[a/b].clj" "/pkg/ab.clj"]))))
    (t/testing "ranges"
      (t/is (= ["/pkg/a" "/pkg/b" "/pkg/z"]
               (m "[a-cz]" ["/pkg/a" "/pkg/b" "/pkg/z" "/pkg/d"]))))))

(t/deftest test-delta-entry-without-user-entry-resolves-in-project-scope
  ;; pi findAutoloadDeltaBase: with no user entry of the same identity the
  ;; delta entry resolves against its own scope — it used to fall back to
  ;; the user base dir
  (with-isolated-settings
    (fn [{:keys [global-dir project-dir]}]
      (let [user-pkg (str (fs/path global-dir "pkg"))
            proj-pkg (str (fs/path project-dir "pkg"))]
        (doseq [[root file] [[user-pkg "user-ext.clj"] [proj-pkg "proj-ext.clj"]]]
          (fs/create-dirs (str root "/extensions"))
          (spit (str root "/extensions/" file) "(ns x)\n"))
        (let [res (pkgs/resolve-package-items
                   nil
                   {:packages [{:source "./pkg" :autoload false
                                :extensions ["+extensions/proj-ext.clj"]}]})]
          (t/is (= ["proj-ext.clj"] (mapv (comp fs/file-name :path) (:extensions res))))
          (t/is (str/starts-with? (:path (first (:extensions res))) proj-pkg))
          (t/is (every? :enabled (:extensions res))))))))

(t/deftest test-delta-patterns
  (let [paths ["/pkg/extensions/a.clj" "/pkg/extensions/b.clj"]]
    (t/is (= {"/pkg/extensions/a.clj" true}
             (pkgs/apply-autoload-disabled-patterns paths ["+extensions/a.clj"] "/pkg")))
    (t/is (= {"/pkg/extensions/b.clj" false}
             (pkgs/apply-autoload-disabled-patterns paths ["-extensions/b.clj"] "/pkg")))))

;; ─── Skill file discovery helpers ─────────────────────────────────────────

(t/deftest test-discover-skill-files
  (let [dir (make-package (tmp-dir))]
    (t/is (= #{(str dir "/skills/root/SKILL.md") (str dir "/skills/flat.md")}
             (set (skills/discover-skill-files (str dir "/skills")))))))

(t/deftest test-single-extension-items-are-not-toggleable
  (with-isolated-settings
    (fn [{:keys [global-dir]}]
      (let [file (str (fs/path global-dir "ext.clj"))]
        (spit file "(ns x)\n")
        (t/testing "a file source resolves to a single always-enabled extension"
          (let [item (first (:extensions (pkgs/resolve-package-items {:packages [file]} nil)))]
            (t/is (pkgs/single-extension-item? item))
            (t/is (true? (:enabled item)))
            (t/testing "toggling it writes nothing"
              (t/is (false? (pkgs/apply-global-toggle! item false)))
              (t/is (false? (pkgs/apply-project-override! item :unload)))
              (t/is (empty? (:packages (pkgs/user-settings-map)))))))
        (t/testing "an extension.edn directory too"
          (let [dir (tmp-dir)]
            (spit (str dir "/extension.edn") "{:name \"pkg-ext\" :entry pkg.ext}\n")
            (let [item (first (:extensions (pkgs/resolve-package-items {:packages [dir]} nil)))]
              (t/is (pkgs/single-extension-item? item))
              (t/is (false? (pkgs/apply-global-toggle! item false))))))
        (t/testing "conventional package resources stay toggleable"
          (let [pkg (make-package (tmp-dir))]
            (t/is (not (pkgs/single-extension-item? (first (:extensions
                                                            (pkgs/resolve-package-items {:packages [pkg]} nil))))))))))))

