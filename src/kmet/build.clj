(ns kmet.build
  "Build self-contained kmet executables.
   A binary is the official babashka release binary with target/kmet.jar (an
   uberjar of src + runtime deps) appended — babashka detects the appended zip
   at startup and runs the uberjar's -main (babashka wiki: Self-contained
   executable). One artifact per babashka release asset slug; cross-builds work
   from any host because packaging is just download + concat.

   Termux/Android: the glibc bb binary must be exec'd through the termux glibc
   dynamic linker, which also breaks bb's own appended-jar detection
   (/proc/self/exe resolves to ld-linux). For a termux host we therefore emit a
   companion launcher script that unsets LD_PRELOAD, execs via
   $PREFIX/glibc/lib/ld-linux-*.so.1 and passes --jar <self> explicitly."
  (:require [babashka.classpath :as bcp]
            [babashka.fs :as fs]
            [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [kmet.libs.archive :as archive]
            [kmet.libs.http :as http]))

(def ^:private gh-api-url
  "https://api.github.com/repos/babashka/babashka/releases/latest")

(def ^:private cache-dir "target/build-cache")
(def ^:private dist-dir "dist")
(def ^:private jar-path "target/kmet.jar")
(def ^:private main-class "kmet.core")

;; ─── Target table ──────────────────────────────────────────────────────────
;;
;; Slugs mirror the babashka release asset names:
;;   babashka-<version>-<slug>.(tar.gz|zip) (+ .sha256 sibling)
;; :ext      archive kind to extract
;; :bin-name executable name inside the archive
;; :linker   glibc loader file name, for the termux launcher script

(def ^:private target-table
  {"linux-aarch64-static" {:ext :tar.gz :bin-name "bb" :linker "ld-linux-aarch64.so.1"}
   "linux-amd64"          {:ext :tar.gz :bin-name "bb" :linker "ld-linux-x86-64.so.2"}
   "linux-amd64-static"   {:ext :tar.gz :bin-name "bb" :linker "ld-linux-x86-64.so.2"}
   "macos-aarch64"        {:ext :tar.gz :bin-name "bb"}
   "macos-amd64"          {:ext :tar.gz :bin-name "bb"}
   "windows-amd64"        {:ext :zip :bin-name "bb.exe"}})

(defn slug-for
  "Release asset slug for an OS/arch pair (os.name/os.arch style values,
   case-insensitive), or nil when unsupported."
  [os arch]
  (let [os (str/lower-case (str os))
        arch (str/lower-case (str arch))
        os (cond (str/includes? os "linux") "linux"
                 (or (str/includes? os "mac")
                     (str/includes? os "darwin")) "macos"
                 (str/includes? os "windows") "windows"
                 :else nil)
        arch (cond (#{"aarch64" "arm64"} arch) "aarch64"
                   (#{"amd64" "x86_64"} arch) "amd64"
                   :else nil)]
    (when (and os arch)
      (get-in {"linux" {"aarch64" "linux-aarch64-static"
                        "amd64" "linux-amd64-static"}
               "macos" {"aarch64" "macos-aarch64"
                        "amd64" "macos-amd64"}
               "windows" {"amd64" "windows-amd64"}}
              [os arch]))))

(defn normalize-slug
  "Accept shorthand slugs (linux-aarch64 => linux-aarch64-static); nil when
   unknown."
  [slug]
  (cond
    (contains? target-table slug) slug
    (= "linux-aarch64" slug) "linux-aarch64-static"
    :else nil))

(defn host-slug
  "Release asset slug for the machine we're running on (best effort), or
   :unknown-platform when it can't be determined."
  []
  (if-some [slug (slug-for (System/getProperty "os.name") (System/getProperty "os.arch"))]
    slug
    :unknown-platform))

(defn termux?
  "True when running under Termux (Android)."
  []
  (str/includes? (str (System/getenv "PREFIX")) "/com.termux/"))

;; ─── Version ───────────────────────────────────────────────────────────────

(defn- git-out
  "Output of a git command, trimmed; nil when git is missing or fails."
  [& args]
  (try
    (let [{:keys [out exit]} (apply p/shell {:out :string :err :suppress} "git" args)]
      (when (zero? exit) (str/trim out)))
    (catch Exception _ nil)))

(defn version
  "Artifact version string: tag pointing at HEAD if any (`v` prefix
   stripped), else short commit hash, else \"dev\" outside a git repo."
  []
  (or (some-> (git-out "describe" "--tags" "--exact-match" "HEAD")
              (str/replace #"^v" ""))
      (git-out "rev-parse" "--short" "HEAD")
      "dev"))

;; ─── Downloading & extraction ──────────────────────────────────────────────

(defn- asset-url [version slug ext]
  (format "https://github.com/babashka/babashka/releases/download/v%s/babashka-%s-%s.%s"
          version version slug (name ext)))

(defn- latest-bb-version
  "Latest babashka release version from the GitHub API, e.g. \"1.13.219\"."
  []
  (let [out (:body (http/get gh-api-url {}))
        tag (:tag_name (json/parse-string out true))
        v (str/replace (str tag) #"^v" "")]
    (when-not (seq v)
      (throw (ex-info "GitHub API returned no tag_name" {:type ::bad-release})))
    v))

(defn- sha256
  "Hex sha256 digest of a file, streamed."
  [path]
  (let [md (java.security.MessageDigest/getInstance "SHA-256")
        buf (byte-array 65536)]
    (with-open [in (io/input-stream (fs/file path))]
      (loop [n (.read in buf)]
        (when (pos? n)
          (.update md buf 0 n)
          (recur (.read in buf)))))
    (->> (.digest md) (map #(format "%02x" %)) (str/join))))

(defn- download!
  "Download url to dest via streamed http/get (temp file, then atomic
   move — GitHub release assets are served through redirects, which the
   wrapper follows by default)."
  [url dest]
  (fs/create-dirs (fs/parent dest))
  (let [tmp (str dest ".part")
        resp (http/get url {:as :stream})]
    (try
      (with-open [in (:body resp)]
        (io/copy in (fs/file tmp)))
      (fs/move (fs/path tmp) (fs/path dest) {:replace-existing true})
      (finally
        (when (fs/exists? tmp) (fs/delete tmp))
        ;; reap the transport (curl: untrack pid + delete temp files) —
        ;; a mid-stream cut surfaces here as a transport error
        (http/close! resp)))))

(defn- extract-archive!
  "Extract a .tar.gz (tar CLI) or .zip (kmet.libs.archive, so windows
   targets unpack without unzip installed) into dir; returns path of the bb
   executable inside."
  [{:keys [ext bin-name]} archive dir]
  (fs/create-dirs dir)
  (case ext
    :tar.gz (p/shell "tar" "xzf" (str archive) "-C" (str dir))
    :zip (archive/extract-zip! archive dir))
  (let [bin (fs/path dir bin-name)]
    (when-not (fs/exists? bin)
      (throw (ex-info (str "archive did not contain " bin-name)
                      {:type ::bad-archive :archive (str archive)})))
    bin))

(defn- ensure-bb-binary!
  "Path of the extracted bb binary for [bb-version slug], downloading and
   sha256-verifying into the build cache when not already there."
  [bb-version slug {:keys [ext] :as target}]
  (let [asset (str "babashka-" bb-version "-" slug "." (name ext))
        archive (fs/path cache-dir bb-version asset)
        dir (fs/path cache-dir bb-version slug)
        bin (fs/path dir (get {:tar.gz "bb" :zip "bb.exe"} ext))]
    (if (fs/exists? bin)
      (println "cached:" asset)
      (do
        (print "downloading" asset "...") (flush)
        (download! (asset-url bb-version slug ext) archive)
        (download! (str (asset-url bb-version slug ext) ".sha256") (str archive ".sha256"))
        (let [actual (sha256 archive)
              expected (first (str/split (slurp (str archive ".sha256")) #"\s+"))]
          (when-not (= actual expected)
            (throw (ex-info (str "sha256 mismatch for " asset)
                            {:type ::checksum-mismatch :expected expected :actual actual}))))
        (extract-archive! target archive dir)
        (println "ok")))
    bin))

;; ─── Uberjar ───────────────────────────────────────────────────────────────

(defn uberjar*
  "Create target/kmet.jar in THIS process — no nested bb interpreter (a second
   ~200MB babashka under memory pressure is what gets the whole Termux app,
   tmux server included, killed by Android's low-memory killer). Layout:
   META-INF/MANIFEST.MF with Main-Class kmet.core, every src/ file (.clj,
   .cljc, .edn — catalogs are classpath resources), then the entries of each
   dependency jar already on the classpath (only borkdude/deps.clj isn't
   bb-builtin; keeping all jars is simpler than filtering). Dependency
   manifests and signatures are skipped so ours wins. Returns absolute path."
  []
  (fs/create-dirs (fs/parent jar-path))
  (let [tmp (str jar-path ".part")
        seen (volatile! #{})
        ;; path.separator is ";" on Windows and ":" on Unix — the old
        ;; #"::?" split only worked on Unix and glued all Windows
        ;; classpath entries into one string, so no dep jar ever landed in
        ;; the uberjar (borkdude/deps.clj etc. were missing)
        dep-jars (->> (str/split (bcp/get-classpath)
                                 (re-pattern (System/getProperty "path.separator")))
                      (filter #(and (str/ends-with? % ".jar")
                                    (not (fs/directory? %)))))]
    (with-open [zos (java.util.zip.ZipOutputStream. (io/output-stream tmp))]
      (.putNextEntry zos (java.util.zip.ZipEntry. "META-INF/MANIFEST.MF"))
      (io/copy (.getBytes (str "Manifest-Version: 1.0\r\n"
                               "Main-Class: " main-class "\r\n\r\n")) zos)
      (.closeEntry zos)
      (doseq [p (sort-by str (fs/glob "src" "**.{clj,cljc,edn}"))]
        ;; the builder itself isn't runtime code — keep it out of artifacts
        (let [rel (str (fs/relativize "src" p))
              ;; jar entries must use / separators — fs/relativize yields \ on
              ;; Windows, which breaks bb's classpath lookup (kmet/core.clj
              ;; would not resolve from the appended jar)
              entry (str/replace rel "\\" "/")]
          (when-not (= "kmet/build.clj" entry)
            (.putNextEntry zos (java.util.zip.ZipEntry. entry))
            (with-open [in (io/input-stream (fs/file p))]
              (io/copy in zos))
            (.closeEntry zos))))
      (doseq [j dep-jars]
        (with-open [zf (java.util.zip.ZipFile. (fs/file j))]
          (doseq [e (enumeration-seq (.entries zf))
                  :when (not (.isDirectory e))]
            (let [n (.getName e)]
              (when-not (or (str/starts-with? n "META-INF/")
                            (contains? @seen n))
                (vswap! seen conj n)
                (.putNextEntry zos (java.util.zip.ZipEntry. n))
                (with-open [in (.getInputStream zf e)]
                  (io/copy in zos))
                (.closeEntry zos)))))))
    (fs/move (fs/path tmp) (fs/path jar-path) {:replace-existing true})
    (fs/canonicalize jar-path)))

;; ─── Assembling artifacts ──────────────────────────────────────────────────

(defn- concat-files!
  "Binary-safe append of src files into dest — portable replacement for cat."
  [dest srcs]
  (fs/create-dirs (fs/parent dest))
  (with-open [out (io/output-stream (fs/file dest))]
    (doseq [src srcs]
      (io/copy (io/input-stream (fs/file src)) out))))

(defn- wrapper-script
  "Termux launcher script text: exec through the glibc linker and pass
   --jar <self> explicitly, because bb's appended-jar detection resolves
   /proc/self/exe to the dynamic linker under this invocation style."
  [bin-name linker]
  (format "#!/data/data/com.termux/files/usr/bin/sh
# kmet launcher (Termux): run the glibc babashka binary through the glibc
# dynamic linker; LD_PRELOAD (libtermux-exec) breaks non-bionic executables.
DIR=$(CDPATH= cd -- \"$(dirname -- \"$0\")\" && pwd)
BIN=\"$DIR/%s\"
LD=\"$PREFIX/glibc/lib/%s\"
[ -x \"$LD\" ] || { echo \"termux glibc package required: pkg install glibc-repo && pkg install glibc\" >&2; exit 1; }
unset LD_PRELOAD
export TMPDIR=\"${TMPDIR:-$PREFIX/tmp}\"
exec \"$LD\" --library-path \"$PREFIX/glibc/lib\" \"$BIN\" --jar \"$BIN\" \"$@\"
"
          bin-name linker))

(defn assemble-one!
  "Produce dist/kmet-<version>-<slug>[.exe]: the official babashka binary for
   slug with kmet.jar appended. On a termux host, slugs that need the glibc
   linker also get a matching .sh launcher script. Returns the artifact path."
  [ver slug {:keys [linker] :as target} bb-ver]
  (let [bb-bin (ensure-bb-binary! bb-ver slug target)
        base (str "kmet-" ver "-" slug)
        windows? (str/starts-with? slug "windows")
        artifact (fs/path dist-dir (cond-> base windows? (str ".exe")))]
    (println "building" (str artifact))
    (concat-files! artifact [bb-bin (fs/path jar-path)])
    (when-not windows?
      (fs/set-posix-file-permissions artifact "rwxr-xr-x"))
    (when (and (termux?) linker)
      (let [w (fs/path dist-dir (str base ".sh"))]
        (spit (str w) (wrapper-script (fs/file-name artifact) linker))
        (fs/set-posix-file-permissions w "rwxr-xr-x")
        (println "launcher:" (str w))))
    artifact))

(defn- smoke-test!
  "Run the freshly built current-host artifact with --list-models and require
   exit code 0. Skipped for cross-built platforms."
  [artifact slug]
  (when (= (host-slug) slug)
    (println "smoke test:" (str artifact) "--list-models")
    (let [launcher (fs/path (fs/parent artifact)
                            (str (fs/file-name artifact) ".sh"))
          cmd (if (and (termux?) (fs/exists? launcher)) launcher artifact)
          res (apply p/shell {:out :string :err :string :continue true}
                     (str cmd) "--list-models")]
      (if (zero? (:exit res))
        (println "smoke test passed:" (count (str/split-lines (:out res))) "models listed")
        (do (println (:err res))
            (throw (ex-info (str "smoke test failed for " artifact)
                            {:type ::smoke-failed :exit (:exit res)})))))))

;; ─── CLI ───────────────────────────────────────────────────────────────────

(defn parse-args
  "CLI args => {:targets [...] :all? :force? :no-smoke? :help?}. Unknown
   slugs/options throw ex-info with :type ::usage."
  [args]
  (loop [args args
         opts {:targets [] :all? false :force? false :no-smoke? false :help? false}]
    (if-some [arg (first args)]
      (cond
        (= "--all" arg) (recur (rest args) (assoc opts :all? true))
        (= "--force" arg) (recur (rest args) (assoc opts :force? true))
        (= "--no-smoke" arg) (recur (rest args) (assoc opts :no-smoke? true))
        (= "--help" arg) (recur (rest args) (assoc opts :help? true))
        (str/starts-with? arg "--") (throw (ex-info (str "unknown option: " arg)
                                                    {:type ::usage}))
        :else (if-some [slug (normalize-slug arg)]
                (recur (rest args) (update opts :targets conj slug))
                (throw (ex-info (str "unknown target: " arg)
                                {:type ::usage :known (vec (sort (keys target-table)))}))))
      opts)))

(defn -main
  "bb build [target ...|--all] [--force] [--no-smoke]

   Build self-contained kmet executable(s) in dist/: the official babashka
   release binary with the kmet uberjar appended. Targets are release asset
   slugs (linux-aarch64-static, linux-amd64[-static], macos-aarch64, macos-amd64,
   windows-amd64); they default to the current platform. --all builds every
   published platform, --force re-downloads cached babashka binaries, and
   --no-smoke skips running the current-host artifact after building (saves
   memory on constrained devices)."
  [& args]
  (let [{:keys [targets all? force? no-smoke? help?]} (parse-args args)]
    (when help?
      (println (:doc (meta #'-main)))
      (System/exit 0))
    (when force?
      (when (fs/exists? cache-dir)
        (fs/delete-tree cache-dir)))
    (let [ver (version)
          bb-ver (latest-bb-version)
          targets (cond
                    all? (sort (keys target-table))
                    (seq targets) targets
                    :else (let [h (host-slug)]
                            (when-not (string? h)
                              (throw (ex-info "cannot determine host platform; pass explicit targets"
                                              {:type ::usage :reason h})))
                            [h]))]
      (println (format "kmet %s | babashka %s | targets: %s" ver bb-ver (str/join ", " targets)))
      (doseq [slug targets]
        (let [artifact (assemble-one! ver slug (get target-table slug) bb-ver)]
          (when-not no-smoke?
            (smoke-test! artifact slug))))
      (println "done:" dist-dir))))
