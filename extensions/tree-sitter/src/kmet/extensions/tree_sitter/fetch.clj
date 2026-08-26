(ns kmet.extensions.tree-sitter.fetch
  "Download + verify primitives for the tree-sitter extension.
   All remote artifacts are sha256-checked against the shipped manifest
   before they land at their destination (temp file -> atomic move).
   Release assets are zips; extraction is in-process via java.util.zip
   (available under babashka) so no external tar/unzip is needed on any
   target platform."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [babashka.http-client :as http]
            [kmet.extensions.tree-sitter.paths :as paths])
  (:import [java.security MessageDigest]
           [java.util.zip ZipFile]))

(defn binary-release
  "Pinned CLI release from the shipped manifest:
   {:version string :targets {target {:url string :sha256 string}}}."
  []
  (let [[version targets] (-> (paths/bundled-resource "bin_manifest.edn")
                              slurp
                              edn/read-string
                              first)]
    (when-not (and (string? version) (map? targets) (seq targets))
      (throw (ex-info "malformed bin manifest resource"
                      {:type ::malformed-manifest :resource "bin_manifest.edn"})))
    {:version version :targets targets}))

(defn manifest-text
  "Raw manifest EDN text, materialized next to the installed binary."
  []
  (slurp (paths/bundled-resource "bin_manifest.edn")))

(defn host-target
  "os-arch slug for the machine we run on (linux-x64, macos-arm64,
   windows-x64, ...), matching the manifest keys. Throws
   ::unsupported-platform when the OS/arch pair has no release asset."
  []
  (let [os (str/lower-case (str (System/getProperty "os.name")))
        arch (str/lower-case (str (System/getProperty "os.arch")))
        os (cond
             (str/includes? os "win") "windows"
             (or (str/includes? os "mac")
                 (str/includes? os "darwin")) "macos"
             (str/includes? os "linux") "linux")
        arch (cond
               (#{"amd64" "x86_64"} arch) "x64"
               (#{"aarch64" "arm64"} arch) "arm64")]
    (or (and os arch (str os "-" arch))
        (throw (ex-info (str "unsupported platform: " (System/getProperty "os.name")
                             "/" (System/getProperty "os.arch"))
                        {:type ::unsupported-platform
                         :os (System/getProperty "os.name")
                         :arch (System/getProperty "os.arch")})))))

(defn sha256
  "Lowercase hex SHA-256 of a file's contents (streamed)."
  [path]
  (let [md (MessageDigest/getInstance "SHA-256")
        buf (byte-array 65536)]
    (with-open [in (io/input-stream (fs/file path))]
      (loop []
        (let [n (.read in buf)]
          (when (pos? n)
            (.update md buf 0 n)
            (recur)))))
    (->> (.digest md)
         (map #(format "%02x" (bit-and 0xff %)))
         (apply str))))

(defn store-and-verify!
  "Stream `in` into a temp sibling of dest, verify its sha256 against
   expected-sha256, then atomically move it onto dest (replacing). Throws
   ::sha-mismatch and leaves dest untouched on mismatch; returns dest."
  [in dest expected-sha256]
  (fs/create-dirs (fs/parent dest))
  (let [tmp (fs/path (fs/parent dest)
                     (str (fs/file-name dest)
                          ".part-" (System/currentTimeMillis)
                          "-" (rand-int 1000000)))]
    (try
      (io/copy in (fs/file tmp))
      (let [actual (sha256 tmp)]
        (if (or (nil? expected-sha256) (= actual expected-sha256))
          (do (fs/move tmp dest {:replace-existing true}) dest)
          (throw (ex-info (str "sha256 mismatch for download of " dest)
                          {:type ::sha-mismatch
                           :dest (str dest)
                           :expected expected-sha256
                           :actual actual}))))
      (finally
        (fs/delete-if-exists tmp)))))

(defn extract-tarball!
  "Extract a .tar.gz under dest-dir via spawned tar (present on all target
   platforms; Windows ships bsdtar). Throws ::extract-failed on non-zero
   exit. Returns dest-dir."
  [tarball dest-dir]
  (fs/create-dirs dest-dir)
  (let [res (p/shell {:out :string :err :string :continue true}
                     "tar" "xzf" (str (fs/file tarball))
                     "-C" (str (fs/file dest-dir)))]
    (when-not (zero? (:exit res))
      (throw (ex-info (str "tar extraction failed for " tarball)
                      {:type ::extract-failed
                       :exit (:exit res)
                       :err (str/trim (str (:err res)))}))))
  dest-dir)

(defn download+verify!
  "GET url and persist it at dest only after sha256 verification.
   expected-sha256 may be nil to skip hashing (intermediate artifacts whose
   integrity is checked after a later extraction step). Throws
   ::download-failed on non-200 and ::sha-mismatch on hash mismatch;
   returns dest."
  [url dest expected-sha256]
  (let [resp (http/get url {:as :stream :request-timeout 120000})]
    (when (not= 200 (:status resp))
      (throw (ex-info (str "download failed with HTTP " (:status resp) " for " url)
                      {:type ::download-failed
                       :url url
                       :status (:status resp)})))
    (store-and-verify! (:body resp) dest expected-sha256)))
(defn extract-zip!
  "Extract every file entry of zip-path under dest-dir, creating nested dirs
   as needed. Rejects absolute or parent-traversing entry names (::unsafe-zip-
   entry) and re-checks each target stays inside dest-dir. Returns the seq of
   extracted paths (no permission preservation — callers chmod as needed)."
  [zip-path dest-dir]
  (let [dest (fs/absolutize dest-dir)]
    (fs/create-dirs dest)
    (with-open [zf (ZipFile. (str (fs/file zip-path)))]
      (doall
       (for [entry (enumeration-seq (.entries zf))
             :when (not (.isDirectory entry))
             :let [name (.getName entry)]]
         (do (when (or (str/starts-with? name "/")
                       (some #{".."} (str/split name #"/")))
               (throw (ex-info (str "unsafe zip entry: " name)
                               {:type ::unsafe-zip-entry :entry name})))
             (let [target (reduce fs/path dest (str/split name #"/"))]
               (when-not (fs/starts-with? (fs/absolutize target) dest)
                 (throw (ex-info (str "zip entry escapes destination: " name)
                                 {:type ::unsafe-zip-entry :entry name})))
               (fs/create-dirs (fs/parent target))
               (with-open [in (.getInputStream zf entry)]
                 (io/copy in (fs/file target)))
               target)))))))
