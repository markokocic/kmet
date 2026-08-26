(ns kmet.extensions.tree-sitter.cli
  "Locating, installing and spawning the tree-sitter CLI binary.
   exec!/process! never throw for infrastructure reasons: spawn failures and
   timeouts come back as {:error ...} maps so callers (hooks!) can treat
   them as pass-through. ensure-binary! downloads the pinned release once,
   verifies its sha256 against the shipped manifest, emits the Termux glibc
   launcher next to the binary (same pattern as kmet.build) and smoke-runs
   --version before declaring the install good."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.string :as str]
            [kmet.extensions.tree-sitter.fetch :as fetch]
            [kmet.extensions.tree-sitter.paths :as paths]))

(def default-timeout-ms 30000)

(defn termux?
  "True when running under Termux (Android)."
  []
  (str/includes? (str (System/getenv "PREFIX")) "/com.termux/"))

(defn- linker-for
  "glibc dynamic loader file name for the host arch (Termux launcher)."
  []
  (let [arch (str/lower-case (str (System/getProperty "os.arch")))]
    (cond (#{"aarch64" "arm64"} arch) "ld-linux-aarch64.so.1"
          (#{"amd64" "x86_64"} arch) "ld-linux-x86-64.so.2"
          :else (throw (ex-info (str "unsupported termux arch: " arch)
                                {:type ::unsupported-platform :arch arch})))))

(defn launcher-text
  "Termux launcher script text: exec the downloaded glibc binary through the
   glibc dynamic linker; LD_PRELOAD (libtermux-exec) breaks non-bionic
   executables."
  [{:keys [bin-name linker]}]
  (format "#!/data/data/com.termux/files/usr/bin/sh
# tree-sitter launcher (Termux): run the glibc CLI binary through the glibc
# dynamic linker; LD_PRELOAD breaks non-bionic executables.
DIR=$(CDPATH= cd -- \"$(dirname -- \"$0\")\" && pwd)
LD=\"$PREFIX/glibc/lib/%s\"
[ -x \"$LD\" ] || { echo \"termux glibc package required: pkg install glibc-repo && pkg install glibc\" >&2; exit 1; }
unset LD_PRELOAD
export TMPDIR=\"${TMPDIR:-$PREFIX/tmp}\"
exec \"$LD\" --library-path \"$PREFIX/glibc/lib\" \"$DIR/%s\" \"$@\"
" linker bin-name))

(defn emit-launcher!
  "Write + chmod the Termux launcher next to the binary; returns its path."
  ([] (emit-launcher! nil))
  ([base]
   (let [w (paths/launcher-path base)]
     (spit (str w) (launcher-text {:bin-name (fs/file-name (paths/bin-path base))
                                   :linker (linker-for)}))
     (fs/set-posix-file-permissions w "rwxr-xr-x")
     w)))

(defn resolve-invocation
  "What to exec: the Termux launcher when present, else the raw binary path."
  ([] (resolve-invocation nil))
  ([base]
   (let [launcher (paths/launcher-path base)]
     (if (fs/exists? launcher) launcher (paths/bin-path base)))))

(defn- capture
  "Post-exit stream content as a string (process versions differ on whether
   :string buffers come back as streams or ready strings)."
  [v]
  (if (instance? java.io.InputStream v) (slurp v) (str v)))

(defn process!
  "Generic never-throw process runner: run cmd vector, capture out/err as
   strings. Opts: {:timeout-ms n :env {extra env vars}}. Returns
   {:exit n :out s :err s}, or {:error :timeout} / {:error :spawn-failure
   :reason s}."
  [cmd {:keys [timeout-ms env]}]
  (try
    (let [proc (p/process cmd
                          {:out :string
                           :err :string
                           :env (merge {} (System/getenv) env)})
          res (deref proc (or timeout-ms default-timeout-ms) ::timed-out)]
      (if (= res ::timed-out)
        (do (p/destroy-tree proc)
            ;; reap so no zombie lingers after the kill
            (try (deref proc 5000 nil) (catch Exception _))
            {:error :timeout})
        {:exit (:exit res)
         :out (capture (:out res))
         :err (capture (:err res))}))
    (catch Exception e
      {:error :spawn-failure
       :reason (str/trim (str (ex-message e)))})))

(defn exec!
  "Spawn the cached tree-sitter CLI with args. Opts: {:base dir :timeout-ms n
   :env {...}}. Same result contract / never-throw guarantee as process!."
  ([args] (exec! args nil))
  ([args {:keys [base] :as opts}]
   (process! (into [(str (resolve-invocation base))] args) opts)))

(defn- smoke-version!
  "Run --version and require output `tree-sitter <version>`; throws
   ::smoke-failed otherwise."
  [base version]
  (let [res (exec! ["--version"] {:base base})]
    (if (and (zero? (or (:exit res) 1))
             (re-find (re-pattern (str "^tree-sitter "
                                       (str/replace version "." "\\.")))
                      (str/trim (str (:out res)))))
      res
      (throw (ex-info (str "installed tree-sitter binary failed --version "
                           "smoke check")
                      {:type ::smoke-failed :version version :result res})))))

(def ^:private executable-perms "rwxr-xr-x")

(defn install-binary!
  "Download the pinned release zip for this platform into the cache,
   sha-verify it against the manifest, extract the executable, discard the
   zip, chmod, materialize the manifest copy, emit the Termux launcher.
   Returns bin path."
  ([] (install-binary! nil))
  ([base]
   (let [{:keys [version targets]} (fetch/binary-release)
         target-key (fetch/host-target)
         {:keys [url sha256 binary-sha256]} (or (get targets target-key)
                                                (throw (ex-info (str "no release asset for target: "
                                                                     target-key)
                                                                {:type ::no-asset-for-target
                                                                 :target target-key
                                                                 :version version})))
         bin-dir (paths/bin-dir base)
         bin (paths/bin-path base)
         zip (fs/path bin-dir "release.zip")]
     (fetch/download+verify! url zip sha256)
     (let [extracted (fetch/extract-zip! zip bin-dir)]
       (fs/delete zip)
       (when-not (some #{(str bin)} (map str extracted))
         (throw (ex-info (str "release archive did not contain expected entry "
                              (fs/file-name bin))
                         {:type ::unexpected-archive :entries extracted}))))
     (when-not (= (fetch/sha256 bin) binary-sha256)
       (fs/delete-if-exists bin)
       (throw (ex-info "extracted binary failed sha256 verification"
                       {:type ::sha-mismatch :path (str bin)})))
     (when-not (fs/windows?)
       (fs/set-posix-file-permissions bin executable-perms))
     (spit (str (paths/manifest-copy-path base)) (fetch/manifest-text))
     (when (termux?)
       (emit-launcher! base))
     bin)))

(def ^:private verified-installs
  "bin-path -> [size mtime-ms] of binaries already sha-verified this
   process; lets repeated tool calls skip the multi-MB re-hash. Any change
   to the file (corruption, replacement) changes the stamp and forces the
   full check again."
  (atom {}))

(defn- stamp
  "Identity of a file on disk cheap enough for hot-path checks."
  [path]
  [(fs/size path) (.toMillis (fs/last-modified-time path))])

(defn ensure-binary!
  "Install orchestration: cached+sha-verified binary -> reuse (no spawn);
   missing or corrupt -> download once (zip verified, then the extracted
   executable re-verified against its own pin) and smoke-run --version —
   a failed smoke deletes the blob so the next call retries cleanly. The
   smoke only guards fresh installs: a binary whose bytes match the pin
   has already proven it runs. Returns {:path bin-path :version}.
   Opts: {:base dir}."
  ([] (ensure-binary! nil))
  ([opts]
   (let [base (:base opts)
         _ (paths/ensure-dirs! base)
         bin (paths/bin-path base)
         {:keys [version targets]} (fetch/binary-release)
         target-key (fetch/host-target)
         {:keys [binary-sha256]} (or (get targets target-key)
                                     (throw (ex-info (str "manifest has no entry for target: "
                                                          target-key)
                                                     {:type ::no-asset-for-target
                                                      :target target-key
                                                      :version version})))]
     (let [sig (when (fs/exists? bin) (stamp bin))]
       (if (and sig (= (get @verified-installs (str bin)) sig))
         ;; proven earlier this process: skip the multi-MB hash + spawn;
         ;; auxiliary files are rewritten anyway (cheap, self-healing)
         (do (spit (str (paths/manifest-copy-path base)) (fetch/manifest-text))
             (when (termux?)
               (emit-launcher! base)))
         (do (if (and sig (= (fetch/sha256 bin) binary-sha256))
               ;; cached on disk: verify auxiliaries into place
               (do (spit (str (paths/manifest-copy-path base))
                         (fetch/manifest-text))
                   (when (termux?)
                     (emit-launcher! base)))
               ;; fresh install: download, then prove it actually runs
               (do (install-binary! base)
                   (try
                     (smoke-version! base version)
                     (catch Exception e
                       ;; failed smoke -> drop the blob so the next call retries
                       ;; cleanly
                       (fs/delete-if-exists bin)
                       (fs/delete-if-exists (paths/launcher-path base))
                       (throw e)))))
             (swap! verified-installs assoc (str bin)
                    (when (fs/exists? bin) (stamp bin))))))
     {:path bin :version version})))
