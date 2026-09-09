(ns kmet.app.packages
  "Package manager (pi: core/package-manager.ts + the package part of
   core/resource-loader.ts — the local directory/file source subset; npm and
   git installs are deliberately out of scope, see alignment.md).

   A package is a `:packages` entry in the global settings.edn
   (~/.kmet/agent/settings.edn, scope :user) or the project settings.edn
   (.kmet/settings.edn, scope :project). Entries are source strings or maps

     {:source \"../ext\"          ;; source (pi: PackageSource)
      :autoload false            ;; project delta over the user entry
      :extensions [\"+x.clj\"]     ;; per-type filter patterns}

   Sources are local files or directories only. A file loads as a single
   extension (pi: \"If the path is a file, it loads as a single extension\").
   A directory loads with pi's package rules: a directory with an
   extension.edn manifest is one kmet extension; otherwise resources come
   from the conventional extensions/ skills/ prompts/ themes/ subdirectories
   (each a container loaded with that resource type's own discovery rules);
   a directory with none of those is an extension container itself.

   Filter patterns (pi packages.md \"Package Filtering\"): plain patterns
   include matching resources, `!glob` excludes, `+path`/`-path` force
   include/exclude exact paths relative to the package root, an empty array
   disables all resources of that type, an absent key loads all. A project
   entry with :autoload false is a delta over the global entry of the same
   identity: only the resources its patterns mention are affected.

   Item identity (pi \"Scope and Deduplication\"): local packages are
   identified by their resolved absolute path; when the same package appears
   in both settings files the project entry wins (unless it is a
   :autoload false delta, which applies on top of the user entry). Resources
   with the same canonical path are loaded once (first wins)."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [babashka.fs :as fs]
            [kmet.app.extensions :as extensions]
            [kmet.app.prompts :as prompts]
            [kmet.app.skills :as skills]
            [kmet.config :as cfg]
            [kmet.tui.theme :as theme]))

(def ^:private resource-types
  "The four package resource types (pi: RESOURCE_TYPES)."
  [:extensions :skills :prompts :themes])

(def ^:private resource-type-set (set resource-types))

;; ─── Source parsing (pi: isLocalPath / parseSource) ───────────────────────

(def ^:private remote-prefixes
  "Protocol/source prefixes that make a source non-local (pi: isLocalPath)."
  ["npm:" "git:" "github:" "http:" "https:" "ssh:"])

(defn local-source?
  "True when SOURCE is a local path (pi: isLocalPath — anything that does
   not start with npm:/git:/github:/http:/https:/ssh:; `git://` URLs are
   treated as local paths exactly like pi)."
  [source]
  (let [s (str/trim (str source))]
    (not (some #(str/starts-with? s %) remote-prefixes))))

(defn parse-source
  "Parse a source string (pi: parseSource). Returns
   {:kind :local :path PATH} or {:kind :remote :prefix P :spec S}."
  [source]
  (let [s (str/trim (str source))]
    (if-let [p (some #(when (str/starts-with? s %) %) remote-prefixes)]
      {:kind :remote :prefix p :spec s}
      {:kind :local :path s})))

;; ─── Path helpers (pi: resolvePath / normalize / relative) ────────────────

(defn- expand-home
  "Expand a leading ~ (pi: normalizePath's expandTilde)."
  [s]
  (let [home (System/getProperty "user.home")]
    (cond
      (= s "~") home
      (str/starts-with? s "~/") (str home (subs s 1))
      :else s)))

(defn- file-url?
  "True when S is a file: URL (pi treats file: URLs as local paths)."
  [s]
  (str/starts-with? s "file://"))

(defn- local-path
  "pi normalizePath for local sources: trim, ~ expansion, file: URL decode."
  [s]
  (let [trimmed (str/trim (str s))
        expanded (expand-home (if (file-url? trimmed)
                                (subs trimmed (count "file://"))
                                trimmed))]
    expanded))

(defn resolve-path
  "Resolve a local source PATH against BASE-DIR (lexical: ~, absolute paths
   and `..` segments normalize without touching the filesystem — pi
   resolvePath). Returns the absolute string."
  [path base-dir]
  (let [s (local-path path)]
    (if (fs/absolute? s)
      (str (fs/normalize s))
      (str (fs/normalize (fs/path (str base-dir) s))))))

(defn normalize-abs
  "Lexically normalize an absolute path for identity comparisons (pi:
   resolvePath without filesystem access)."
  [p]
  (str (fs/normalize (str p))))

(defn canonicalize
  "pi canonicalizePath — resolve symlinks; unresolvable paths pass through
   unchanged."
  [p]
  (try (str (fs/canonicalize (str p)))
       (catch Exception _ (normalize-abs p))))

(defn- posix
  "Normalize path separators to / for pattern storage and matching
   (pi: toPosixPath)."
  [p]
  (str/replace (str p) "\\" "/"))

(defn base-dir-for-scope
  "The directory local sources of SCOPE resolve against (pi:
   getBaseDirForScope): the agent dir for user packages, the .kmet project
   dir for project packages."
  [scope]
  (if (= scope :project)
    (cfg/project-dir)
    (cfg/get-agent-dir)))

(defn resolve-source-path
  "Resolve SOURCE (a local source string) against the base dir of SCOPE."
  [source scope]
  (resolve-path (local-path source) (base-dir-for-scope scope)))

;; ─── Settings entries (pi: PackageSource read/write) ──────────────────────

(defn source-of
  "The source string of a settings entry (pi: getPackageSourceString)."
  [entry]
  (if (map? entry) (str (:source entry)) (str entry)))

(defn filter-keys-of
  "Per-type filter keys present on an object entry (pi: PackageFilter)."
  [entry]
  (when (map? entry)
    (set (filter #(contains? resource-types %) (keys entry)))))

(defn autoload-disabled?
  "True when ENTRY is an object entry with :autoload false (pi: a project
   delta over the global entry of the same identity)."
  [entry]
  (and (map? entry) (false? (:autoload entry))))

(defn package-identity
  "The identity of a package for dedupe (pi: getPackageIdentity): local
   packages are their resolved absolute path, remote ones their spec."
  [source scope]
  (let [parsed (parse-source source)]
    (if (= :local (:kind parsed))
      (str "local:" (resolve-path (:path parsed) (base-dir-for-scope scope)))
      (str "remote:" (:spec parsed)))))

(defn normalize-source-for-settings
  "The form a local SOURCE takes in the settings file of SCOPE: the path
   relative to the scope base dir when possible, otherwise a relative
   `../..` path, never the input verbatim (pi:
   normalizePackageSourceForSettings — relative(baseDir, resolve(source)))."
  [source scope]
  (let [parsed (parse-source source)]
    (if (= :remote (:kind parsed))
      (str source)
      (let [resolved (resolve-path (:path parsed) (str (fs/cwd)))
            rel (fs/relativize (fs/path (base-dir-for-scope scope))
                               (fs/path resolved))]
        (if (or (= rel resolved) (nil? rel))
          "."
          (posix rel))))))

(defn- packages-of
  "The :packages entries of a settings map (pi: settings.packages ?? [])."
  [settings]
  (vec (or (:packages settings) [])))

(defn user-settings-map
  "The parsed global settings map, or {} (pi: getGlobalSettings)."
  []
  (or (cfg/read-global-settings-map) {}))

(defn project-settings-map
  "The parsed project settings map, or {} (pi: getProjectSettings)."
  []
  (or (cfg/read-project-settings-map) {}))

(defn user-packages
  "Configured user-scope packages from the global settings file."
  []
  (packages-of (user-settings-map)))

(defn project-packages
  "Configured project-scope packages from the project settings file."
  []
  (packages-of (project-settings-map)))

(defn- packages-source-match?
  "True when EXISTING-SOURCE (a configured entry in EXISTING-SCOPE) and
   INPUT-SOURCE name the same package (pi: packageSourcesMatch — the input
   side resolves against the current working directory, the configured side
   against its own scope base)."
  [existing-source existing-scope input-source]
  (let [parsed (parse-source input-source)]
    (if (= :remote (:kind parsed))
      (= (str existing-source) (:spec parsed))
      (= (resolve-source-path existing-source existing-scope)
         (resolve-path (:path parsed) (str (fs/cwd)))))))

(defn add-package-to-settings!
  "Add SOURCE to the :packages of the global (default) or project settings
   file (pi: addSourceToSettings). The stored form is normalized relative to
   the scope base dir; a matching entry updates in place (object entries
   keep their filters). Returns true when the settings file changed."
  [source & [{:keys [local]}]]
  (let [scope (if local :project :user)
        save! (if local cfg/save-project-setting! cfg/save-setting!)
        current (if local (project-packages) (user-packages))
        normalized (normalize-source-for-settings source scope)
        idx (first (keep-indexed (fn [i entry]
                                   (when (packages-source-match? (source-of entry) scope
                                                                 source)
                                     i))
                                 current))]
    (if idx
      (if (= (source-of (nth current idx)) normalized)
        false
        (let [existing (nth current idx)
              next (assoc current idx
                          (if (map? existing)
                            (assoc existing :source normalized)
                            normalized))]
          (save! [:packages] next)
          true))
      (do (save! [:packages] (conj current normalized))
          true))))

(defn remove-package-from-settings!
  "Remove every package matching SOURCE from the :packages of the global
   (default) or project settings file (pi: removeSourceFromSettings).
   Returns true when something was removed, false otherwise."
  [source & [{:keys [local]}]]
  (let [scope (if local :project :user)
        save! (if local cfg/save-project-setting! cfg/save-setting!)
        current (if local (project-packages) (user-packages))
        next (vec (remove #(packages-source-match? (source-of %) scope source)
                          current))]
    (if (not= (count next) (count current))
      (do (save! [:packages] next)
          true)
      false)))

(defn get-installed-path
  "The resolved path of SOURCE in SCOPE when it exists on disk, else nil
   (pi: getInstalledPath)."
  [source scope]
  (let [parsed (parse-source source)]
    (when (= :local (:kind parsed))
      (let [p (resolve-source-path (:path parsed) scope)]
        (when (fs/exists? p) p)))))

(defn list-configured-packages
  "All configured packages from both settings files with their scope and
   installed path (pi: listConfiguredPackages). Order: user entries first,
   then project entries."
  []
  (concat
   (for [entry (user-packages)]
     {:source (source-of entry)
      :scope :user
      :filtered (map? entry)
      :installed-path (get-installed-path (source-of entry) :user)})
   (for [entry (project-packages)]
     {:source (source-of entry)
      :scope :project
      :filtered (map? entry)
      :installed-path (get-installed-path (source-of entry) :project)})))

;; ─── Pattern matching (pi: applyPatterns & friends) ───────────────────────
;; Pattern kinds (pi packages.md): plain glob = include, `!glob` = exclude,
;; `+path`/`-path` = force include/exclude by exact path. Glob support is
;; minimatch's common subset: `*` (no /), `?`, `**` (any depth) and
;; `[...]` character classes; `*`/`?`/`**` do not match a leading dot
;; segment unless the pattern segment starts with a dot.

(defn- glob->regex
  "Compile a glob PATTERN to a regex string (segment-aware: `**` may cross
   separators, `*`/`?` may not, hidden segments need explicit dots)."
  [pattern]
  (let [sb (StringBuilder. "^")]
    (loop [i 0]
      (if (>= i (count pattern))
        (str sb "$")
        (let [c (nth pattern i)
              next? (fn [n] (when (< (+ i n) (count pattern)) (nth pattern (+ i n))))]
          (cond
            (and (= c \*) (= \* (next? 1)))
            (do (.append sb "(?:[^/]*/)*")
                ;; minimatch: `**/` swallows its trailing slash
                (recur (+ i 2 (if (= \/ (next? 2)) 1 0))))

            (= c \*)
            (do (.append sb "[^/]*")
                (recur (inc i)))

            (= c \?)
            (do (.append sb "[^/]")
                (recur (inc i)))

            (= c \[)
            ;; character class [...] copied verbatim (validated loosely)
            (let [end (str/index-of pattern "]" i)]
              (if end
                (let [cls (subs pattern (inc i) end)]
                  (.append sb "[")
                  (when (str/starts-with? cls "^") (.append sb "^"))
                  (when (str/starts-with? cls "!") (.append sb "^"))
                  (.append sb (str/replace cls #"^[!^]" ""))
                  (.append sb "]")
                  (recur (inc end)))
                (do (.append sb (java.util.regex.Pattern/quote (str c)))
                    (recur (inc i)))))

            :else
            (do (.append sb (java.util.regex.Pattern/quote (str c)))
                (recur (inc i)))))))))

(defn- glob-matcher
  "A fn of one path string returning true when the path matches PATTERN
   (path separators normalized to / before matching)."
  [pattern]
  (let [re (re-pattern (glob->regex (posix pattern)))]
    (fn [path] (boolean (re-matches re (posix path))))))

(defn- match-forms
  "The path forms a pattern is matched against (pi: matchesAnyPattern):
   the path relative to BASE-DIR, its file name, and the absolute path —
   plus the parent-dir forms when the file is a SKILL.md (patterns may name
   the skill root directory)."
  [path base-dir]
  (let [rel (posix (fs/relativize (fs/path (str base-dir)) (fs/path (str path))))
        name (fs/file-name (str path))
        abs (posix path)
        is-skill? (= name "SKILL.md")]
    (cond-> [rel name abs]
      is-skill? (into [(posix (fs/relativize (fs/path (str base-dir))
                                             (fs/path (str (fs/parent path)))))
                       (fs/file-name (fs/parent (str path)))
                       (posix (str (fs/parent path)))]))))

(defn- matches-any-pattern?
  "True when any PATTERN (a glob) matches PATH relative to BASE-DIR (pi:
   matchesAnyPattern)."
  [patterns path base-dir]
  (let [forms (match-forms path base-dir)]
    (boolean (some (fn [p] (some (glob-matcher p) forms)) patterns))))

(defn- exact-form
  "The canonical exact form of a pattern/path for +/- matching (pi:
   normalizeExactPattern — strips a ./ prefix, posix separators)."
  [s]
  (let [s (str s)]
    (if (or (str/starts-with? s "./") (str/starts-with? s ".\\"))
      (posix (subs s 2))
      (posix s))))

(defn- matches-any-exact-pattern?
  "True when any PATTERN exactly names PATH (pi: matchesAnyExactPattern)."
  [patterns path base-dir]
  (let [forms (match-forms path base-dir)
        exacts (map exact-form patterns)]
    (boolean (some #(some (fn [f] (= % f)) forms) exacts))))

(defn apply-patterns
  "pi applyPatterns — apply filter patterns to PATHS (relative to BASE-DIR)
   and return the set of enabled paths. Steps: plain includes narrow (or
   everything when none); `!` globs exclude; `+` exact paths force-include;
   `-` exact paths force-exclude."
  [paths patterns base-dir]
  (let [patterns (mapv str patterns)
        override? #(or (str/starts-with? % "+") (str/starts-with? % "-") (str/starts-with? % "!"))
        includes (filterv #(not (override? %)) patterns)
        excludes (mapv #(subs % 1) (filterv #(str/starts-with? % "!") patterns))
        force-includes (mapv #(subs % 1) (filterv #(str/starts-with? % "+") patterns))
        force-excludes (mapv #(subs % 1) (filterv #(str/starts-with? % "-") patterns))
        step1 (if (seq includes)
                (filterv #(matches-any-pattern? includes % base-dir) paths)
                (vec paths))
        step2 (if (seq excludes)
                (filterv #(not (matches-any-pattern? excludes % base-dir)) step1)
                step1)
        step3 (if (seq force-includes)
                (into step2 (filter #(matches-any-exact-pattern? force-includes % base-dir)
                                    paths))
                step2)
        step4 (if (seq force-excludes)
                (filterv #(not (matches-any-exact-pattern? force-excludes % base-dir))
                         step3)
                step3)]
    (set step4)))

(defn apply-autoload-disabled-patterns
  "pi applyAutoloadDisabledPatterns — the delta view of a :autoload false
   entry: returns {path enabled} for exactly the resources its PATTERNS
   mention (+/plain patterns enable, -/! patterns disable)."
  [paths patterns base-dir]
  (reduce (fn [acc pattern]
            (let [target (if (str/starts-with? pattern "+")
                           (subs pattern 1)
                           (if (str/starts-with? pattern "-")
                             (subs pattern 1)
                             (if (str/starts-with? pattern "!")
                               (subs pattern 1)
                               pattern)))
                  enabled? (not (or (str/starts-with? pattern "-")
                                    (str/starts-with? pattern "!")))
                  exact? (or (str/starts-with? pattern "+")
                             (str/starts-with? pattern "-"))]
              (reduce (fn [acc path]
                        (if (if exact?
                              (matches-any-exact-pattern? [target] path base-dir)
                              (matches-any-pattern? [target] path base-dir))
                          (assoc acc path enabled?)
                          acc))
                      acc paths)))
          {}
          patterns))

;; ─── Item discovery (pi: collectResourceFiles per type) ───────────────────
;; Kmet resource units inside a container dir: extensions = extension
;; artifacts (top-level .clj/.jar/.zip files and extension.edn dirs), skills
;; = skill files (SKILL.md roots and flat .md, recursive), prompts = .md
;; files (top level), themes = .edn files (top level). The walkers are the
;; same ones the directory loaders use.

(defn- discover-items-in-dir
  "All item paths of TYPE inside container dir D."
  [type d]
  (case type
    :extensions (extensions/extension-artifact-paths d)
    :skills (skills/discover-skill-files d)
    :prompts (prompts/prompt-template-files-in-dir d)
    :themes (vec (or (theme/theme-files-in-dir d) []))))

(defn- discover-package-dir
  "pi collectPackageResources — resource items of a local package
   directory. Returns {type [absolute item paths]}: the conventional
   extensions/ skills/ prompts/ themes/ subdirectories when any exist,
   otherwise the directory itself as an extension container. A directory
   with an extension.edn manifest is one extension and is handled by the
   caller."
  [root]
  (let [subdirs (into {}
                      (for [t resource-types
                            :let [d (str (io/file root (name t)))]
                            :when (fs/directory? d)]
                        [t d]))]
    (if (seq subdirs)
      (into {} (for [[t d] subdirs] [t (discover-items-in-dir t d)]))
      {:extensions (extensions/extension-artifact-paths root)})))

(defn- enabled-paths
  "pi applyPackageFilter/collectDefaultResources — PATTERNS nil = every
   PATH; an empty array disables all of the type; otherwise pi's 4-step
   pattern application relative to BASE-DIR (the package root). Returns the
   enabled path set."
  [paths patterns base-dir]
  (cond
    (nil? patterns) (set paths)
    (empty? patterns) #{}
    :else (apply-patterns paths patterns base-dir)))

;; ─── Resolution (pi: resolvePackageSources + toResolvedPaths) ─────────────

(defrecord PackageItem [path enabled resource-type metadata])

(defn- entry-filter
  "The per-type filter map of an object entry, nil for strings."
  [entry]
  (when (map? entry)
    (into {} (filter (fn [[k]] (contains? resource-type-set k))) entry)))

(defn- dedupe-entries
  "pi dedupePackages — project entries first; a same-identity pair keeps
   the project entry (it wins), unless the project entry is a
   :autoload false delta, in which case the user entry is kept too (delta
   first, applied on top of the user entry at resolve time)."
  [entries]
  (let [ordered (sort-by (fn [{:keys [scope]}] (if (= scope :project) 0 1)) entries)]
    (loop [remaining ordered
           result []
           seen {}]
      (if-let [{:keys [entry scope] :as item} (first remaining)]
        (let [id (package-identity (source-of entry) scope)
              idx (get seen id)]
          (if (nil? idx)
            (recur (rest remaining)
                   (conj result item)
                   (assoc seen id (count result)))
            (let [existing (nth result idx)]
              (cond
                (and (= :project (:scope existing)) (= :user scope)
                     (autoload-disabled? (:entry existing)))
                ;; project delta over the user entry: keep both
                (recur (rest remaining) (conj result item) seen)

                (= :project scope)
                ;; project entry replaces the user entry of the same identity
                (recur (rest remaining) (assoc result idx item) seen)

                :else
                ;; duplicate within the same scope: first wins
                (recur (rest remaining) result seen)))))
        result))))

(defn- add-resource!
  "Accumulate one resolved resource (pi: addResource — first add wins per
   canonical path)."
  [acc type item]
  (let [by-type (get @acc type {})
        key (canonicalize (:path item))]
    (when-not (contains? by-type key)
      (swap! acc update type assoc key item))))

(defn- resolve-local-entry
  "Resolve one local package entry into items (pi:
   resolveLocalExtensionSource + collectPackageResources). BASE is the
   resolved package path; missing paths are skipped."
  [acc base entry scope source-string]
  (let [base (str base)
        root-file? (fs/regular-file? base)
        ext-dir? (fs/exists? (io/file base "extension.edn"))
        metadata {:source source-string
                  :scope scope
                  :origin :package
                  :base-dir (if root-file? (str (fs/parent base)) base)}
        filter (entry-filter entry)
        delta? (autoload-disabled? entry)]
    (cond
      (or root-file? ext-dir?)
      ;; a file (or an extension.edn directory) is a single extension —
      ;; filters don't apply (pi: file sources bypass package filters; kmet
      ;; extension dirs are the single-extension unit)
      (add-resource! acc :extensions
                     (->PackageItem base true :extensions metadata))

      :else
      (let [discovered (discover-package-dir base)]
        (doseq [[type paths] discovered]
          (let [patterns (get filter type)
                ;; pi: filtered-out items stay in the resolution with
                ;; :enabled false (the config list shows them unchecked)
                pairs (if delta?
                        (apply-autoload-disabled-patterns paths patterns base)
                        (let [enabled (enabled-paths paths patterns base)]
                          (into {} (map (fn [p] [p (contains? enabled p)])) paths)))]
            (doseq [[path enabled] pairs]
              (add-resource! acc type
                             (->PackageItem path enabled type metadata)))))))))

(defn resolve-package-items
  "Resolve the configured packages of USER-SETTINGS and PROJECT-SETTINGS
   (settings maps or nil) into per-type PackageItem vectors (pi: resolve —
   the packages part; kmet's auto resource dirs load separately and are not
   part of this model). Order: project packages first, then user packages;
   first-wins per canonical path."
  [user-settings project-settings]
  (let [entries (concat (for [entry (packages-of (or project-settings {}))]
                          {:entry entry :scope :project})
                        (for [entry (packages-of (or user-settings {}))]
                          {:entry entry :scope :user}))
        acc (atom {:extensions {} :skills {} :prompts {} :themes {}})]
    (doseq [{:keys [entry scope]} (dedupe-entries entries)
            :let [source (source-of entry)
                  parsed (parse-source source)]]
      (if (= :remote (:kind parsed))
        (binding [*out* *err*]
          (println "Warning: package" source "is a remote source; kmet supports"
                   "local directories and files only — skipping"))
        (let [delta? (and (= scope :project) (autoload-disabled? entry))
              ;; pi findAutoloadDeltaBase: a delta entry resolves its path
              ;; from the user entry of the same identity
              base-source (if delta?
                            (or (some (fn [{:keys [entry scope]}]
                                        (when (and (= scope :user)
                                                   (= (package-identity (source-of entry) :user)
                                                      (package-identity source :project)))
                                          (source-of entry)))
                                      entries)
                                source)
                            source)
              resolved (resolve-source-path base-source (if delta? :user scope))]
          (when (fs/exists? resolved)
            (resolve-local-entry acc resolved entry scope source)))))
    (into {} (for [[type items] @acc]
               [type (vec (vals items))]))))

(defn resolve-configured-packages
  "Resolve the configured packages read from both settings files (the
   merged user + project view, pi: the project-trusted resolve)."
  []
  (resolve-package-items (user-settings-map) (project-settings-map)))

(defn resolve-user-packages
  "Resolve only the user-scope packages from the global settings file (pi:
   resolve with global-only settings — the pi config command's global
   view)."
  []
  (resolve-package-items (user-settings-map) nil))

;; ─── Loading (pi: resource-loader package resources) ──────────────────────

(defn- enabled-item-paths
  "Enabled item paths of TYPE from a resolved view, minus items whose
   canonical path is already loaded from the auto resource dirs (pi:
   toResolvedPaths dedupes canonical paths and auto resources outrank
   packages — kmet's auto dirs load first, so those must not double-load)."
  [resolved type already-loaded]
  (->> (get resolved type)
       (filter :enabled)
       (remove (fn [item] (contains? already-loaded (canonicalize (:path item)))))
       (mapv :path)))

(defn load-package-extensions!
  "Load the enabled package extension items that are not already loaded
   from the resource dirs. Returns the per-extension results of the new
   loads ({:extension name :error} maps, failures also warn — same shape as
   load-extensions-from-dir)."
  []
  (let [loaded (set (map (comp canonicalize :path) (extensions/registered-extensions)))]
    (extensions/load-extension-paths!
     (enabled-item-paths (resolve-configured-packages) :extensions loaded))))

(defn load-package-skills!
  "Load the enabled package skill items that are not already loaded from
   the resource dirs (dedupe by file path — dir-loaded skills register
   their file path)."
  []
  (let [loaded (into #{} (keep :file-path) (skills/get-skills))
        paths (enabled-item-paths (resolve-configured-packages) :skills loaded)]
    (skills/load-skills-from-files! paths)))

(defn load-package-prompts!
  "Load the enabled package prompt items that are not already loaded from
   the resource dirs (dedupe by file path)."
  []
  (let [loaded (into #{} (keep :file-path) (prompts/get-prompt-templates))
        paths (enabled-item-paths (resolve-configured-packages) :prompts loaded)]
    (prompts/load-prompt-template-files! paths)))

(defn load-package-themes!
  "Register the enabled package theme items that are not already loaded
   from the theme dir (dedupe by source path)."
  []
  (let [loaded (into #{} (keep :source-path) (vals (theme/get-all-themes)))
        paths (enabled-item-paths (resolve-configured-packages) :themes loaded)]
    (theme/load-theme-paths! paths)))

;; ─── Config model (pi: config-selector.ts — package resources) ────────────
;; The resource-config TUI (kmet config) toggles package resources. kmet
;; lists package-origin resources only — the auto resource dirs are managed
;; as files and top-level settings resource entries are not ported (see
;; alignment.md). Toggle writes follow pi exactly: per-type +/- patterns on
;; the package's settings entry, object-entry conversion, and — in project
;; scope — :autoload false delta entries for inherited user packages with
;; an inherit/load/unload tri-state cycle.

(def resource-type-labels
  "pi RESOURCE_TYPE_LABELS."
  {:extensions "Extensions" :skills "Skills" :prompts "Prompts" :themes "Themes"})

(defn item-key
  "pi getResourceItemKey — resourceType + canonical path."
  [item]
  (str (name (:resource-type item)) ":" (canonicalize (:path item))))

(defn pattern-target
  "pi getPatternEntryTarget — strip the !/+/− prefix."
  [entry]
  (let [s (str entry)]
    (if (str/starts-with? s "!")
      (subs s 1)
      (if (str/starts-with? s "+")
        (subs s 1)
        (if (str/starts-with? s "-")
          (subs s 1)
          s)))))

(defn item-pattern
  "pi getPackageResourcePattern — the item's path relative to its package
   base dir (the string the toggle writes as a +/- pattern)."
  [item]
  (let [base (get-in item [:metadata :base-dir])]
    (if base
      (posix (fs/relativize (fs/path base) (fs/path (:path item))))
      (posix (:path item)))))

(defn next-override-state
  "pi getNextOverrideState — the tri-state cycle (inherit → load/unload →
   inherit...) driven by the inherited (global) enabled state."
  [state inherited-enabled]
  (cond
    (= state :inherit) (if inherited-enabled :unload :load)
    (= state :unload) (if inherited-enabled :load :inherit)
    :else (if inherited-enabled :inherit :unload)))

(defn type-array-of
  "The filter array of TYPE on a package ENTRY (pi: pkg[type] ?? [])."
  [entry type]
  (if (map? entry) (vec (or (get entry type) [])) []))

(defn override-state-from-array
  "pi getOverrideStateFromEntries for one package: :inherit when no entry
   targets PATTERN, :load for +/plain entries, :unload for −/! entries; an
   empty array reads as :unload (the whole type is disabled)."
  [type-array pattern empty-array-unload?]
  (if (and (empty? type-array) empty-array-unload?)
    :unload
    (reduce (fn [state entry]
              (if (= (pattern-target entry) pattern)
                (if (or (str/starts-with? (str entry) "-")
                        (str/starts-with? (str entry) "!"))
                  :unload
                  :load)
                state))
            :inherit
            type-array)))

(defn- source-matches-scope
  "pi packageSourceStringMatches — string equality, or two local sources
   whose resolved paths (at their own scope bases) are equal."
  [a a-scope b b-scope]
  (or (= (str a) (str b))
      (and (local-source? a) (local-source? b)
           (= (resolve-source-path a a-scope)
              (resolve-source-path b b-scope)))))

(defn override-state-of
  "pi getProjectOverrideState — the project-scope override state of ITEM
   given the project PACKAGES entries."
  [item packages]
  (let [entry (some (fn [e]
                      (when (source-matches-scope (source-of e) :project
                                                  (get-in item [:metadata :source])
                                                  (get-in item [:metadata :scope]))
                        e))
                    packages)]
    (if-not (map? entry)
      :inherit
      (let [array (type-array-of entry (:resource-type item))]
        (override-state-from-array array (item-pattern item)
                                   (not (false? (:autoload entry))))))))

(defn- set-type-array!
  "pi per-type filter surgery: PACKAGES with entry at IDX gets PATTERN
   added/removed for TYPE. STATE :inherit removes the item's pattern
   entries; :load appends +pattern; :unload appends −pattern. A type array
   emptied by the surgery drops its key; an entry with no type keys left
   returns to its plain source string — unless it is an :autoload false
   delta, which is dropped entirely (pi: setProjectPackageOverride cleanup).
   Returns the next packages vector."
  [packages idx type pattern state]
  (let [entry (nth packages idx)
        entry (if (map? entry) entry {:source entry})
        current (vec (or (get entry type) []))
        target (pattern-target pattern)
        filtered (vec (remove #(= (pattern-target %) target) current))
        with-sign (cond-> filtered
                    (not= state :inherit)
                    (conj (str (if (= state :load) "+" "-") pattern)))
        entry (cond-> entry
                (seq with-sign) (assoc type with-sign)
                (empty? with-sign) (dissoc entry type))
        type-keys (filter #(contains? resource-type-set %) (keys entry))]
    (cond
      (seq type-keys)
      (assoc packages idx entry)

      (and (map? (nth packages idx)) (false? (:autoload entry)))
      (vec (concat (subvec packages 0 idx) (subvec packages (inc idx))))

      :else
      (assoc packages idx (:source entry)))))

(defn apply-global-toggle!
  "pi togglePackageResource on the user settings file: flip ITEM's enabled
   state by writing +/−PATTERN into the matching package entry (string
   entries become object entries; entries with no remaining filters return
   to strings). No-op when the package is not found."
  [item enabled]
  (let [current (user-packages)
        source (get-in item [:metadata :source])
        idx (first (keep-indexed (fn [i e]
                                   (when (= (source-of e) source) i))
                                 current))]
    (when idx
      (cfg/save-setting!
       [:packages]
       (set-type-array! current idx (:resource-type item) (item-pattern item)
                        (if enabled :load :unload)))
      true)))

(defn- override-source-entry
  "pi createPackageOverrideSource — a project-scope delta entry over the
   user package ITEM belongs to: the same local source normalized against
   the project base dir, marked :autoload false."
  [item]
  (let [source (get-in item [:metadata :source])
        item-scope (get-in item [:metadata :scope] :user)
        resolved (resolve-source-path source item-scope)
        rel (fs/relativize (fs/path (base-dir-for-scope :project))
                           (fs/path resolved))]
    {:source (if (or (= rel resolved) (nil? rel)) "." (posix rel))
     :autoload false}))

(defn apply-project-override!
  "pi setProjectPackageOverride — set ITEM's project override STATE
   (:inherit/:load/:unload) in the project settings file. Inherited user
   packages without a project entry get a fresh :autoload false delta
   entry; cycling back to :inherit removes the override again."
  [item state]
  (when (not= state (override-state-of item (project-packages)))
    (let [current (project-packages)
          item-scope (get-in item [:metadata :scope] :user)
          idx (first (keep-indexed (fn [i e]
                                     (when (source-matches-scope (source-of e) :project
                                                                 (get-in item [:metadata :source])
                                                                 item-scope)
                                       i))
                                   current))]
      (if (and (nil? idx) (= state :inherit))
        false
        (let [packages (if idx
                         current
                         (conj current (override-source-entry item)))
              idx (or idx (dec (count packages)))]
          (cfg/save-project-setting! [:packages]
                                     (set-type-array! packages idx
                                                      (:resource-type item)
                                                      (item-pattern item)
                                                      state))
          true)))))
