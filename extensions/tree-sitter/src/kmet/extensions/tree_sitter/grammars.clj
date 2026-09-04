(ns kmet.extensions.tree-sitter.grammars
  "Grammar acquisition and scaffolding (SPEC.md): the CLI loads grammar
   .wasm files as data from TREE_SITTER_LIBDIR, but discovery needs a
   scaffold dir per language under parser-directories — tree-sitter.json +
   src/grammar.json + a stub src/parser.c older than the wasm. Fresh
   downloads get a load-check: the wasm must actually parse a known-clean
   probe snippet before it is kept; any failure deletes blob + scaffold
   (pi-tree-sitter corruption policy). Unknown language -> nil everywhere."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [kmet.extensions.tree-sitter.cli :as cli]
            [kmet.extensions.tree-sitter.fetch :as fetch]
            [kmet.extensions.tree-sitter.paths :as paths]))

(defn languages
  "Pinned language table: {lang {:source :id/:url :version :sha256
   :file-types [...] :probe string}}."
  []
  (let [r (paths/bundled-resource "kmet/extensions/tree_sitter/libs_manifest.edn")]
    (edn/read-string (slurp r))))

(defn- normalize-ext
  [ext]
  (let [s (-> (str ext) str/trim str/lower-case)]
    (if (str/starts-with? s ".") (subs s 1) s)))

(defn resolve-lang
  "Language whose grammar handles this file extension (\"py\", \".PY\",
   \"tsx\" ...), or nil when unknown."
  ([] nil)
  ([ext] (resolve-lang ext {}))
  ([ext {:keys [langs]}]
   (when ext
     (let [langs (or langs (languages))]
       (reduce-kv (fn [_ lang {:keys [file-types]}]
                    (when (some #(= (normalize-ext ext) (normalize-ext %)) file-types)
                      (reduced lang)))
                  nil langs)))))

(defn wasm-path
  "Where a language's grammar blob lives (libs-dir IS TREE_SITTER_LIBDIR)."
  ([lang] (wasm-path lang nil))
  ([lang base] (fs/path (paths/libs-dir base) (str lang ".wasm"))))

(defn scaffold-dir
  ([lang] (scaffold-dir lang nil))
  ([lang base] (fs/path (paths/grammars-dir base) (str "tree-sitter-" lang))))

(defn ensure-config!
  "(Re)generate config.json pointing parser-directories at grammars-dir."
  ([] (ensure-config! nil))
  ([base]
   (spit (str (paths/config-path base))
         (format "{\"parser-directories\": [\"%s\"]}"
                 (str/replace (str (paths/grammars-dir base)) "\\" "\\\\")))))

(def ^:private scaffold-mtime-ms
  "Stub parser.c timestamp; anything in the past makes cached wasms newer,
   which is what makes the CLI load them without compiling (SPEC fact 2)."
  1000000000000)

(defn scaffold!
  "Write the proven scaffold layout for a language:
   grammars/tree-sitter-<lang>/{tree-sitter.json, src/grammar.json,
   src/parser.c(stub, mtime set old)}. Idempotent; also refreshes
   config.json."
  ([lang] (scaffold! lang nil))
  ([lang {:keys [base langs]}]
   (let [entry (get (or langs (languages)) lang)
         {:keys [file-types]} entry
         dir (scaffold-dir lang base)
         src (fs/path dir "src")]
     (fs/create-dirs src)
     (spit (str (fs/path dir "tree-sitter.json"))
           (format "{\"metadata\": {\"version\": %s, \"license\": \"MIT\",
 \"description\": %s, \"links\": {\"repository\": \"https://github.com/kmet/kmet\"}},
 \"grammars\": [{\"name\": %s, \"file-types\": [%s], \"scope\": %s}]}"
                   (pr-str (or (:version entry) "0.1.0"))
                   (pr-str (str lang " grammar scaffold"))
                   (pr-str lang)
                   (->> (map #(str "\"" (normalize-ext %) "\"") file-types)
                        (str/join ", "))
                   (pr-str (str "source." lang))))
     (spit (str (fs/path src "grammar.json")) (format "{\"name\": %s}" (pr-str lang)))
     (spit (str (fs/path src "parser.c"))
           "/* stub: forces load-only from TREE_SITTER_LIBDIR */\n")
     (fs/set-last-modified-time (fs/path src "parser.c") scaffold-mtime-ms)
     (ensure-config! base)
     dir)))

(defn- zed-download-url
  [{:keys [id version]}]
  (format "https://api.zed.dev/extensions/%s/%s/download" id version))

(defn- find-tar-member
  "Locate grammars/<lang>.wasm inside an extracted zed tarball: check the
   conventional root-relative location first, then walk the whole tree
   (tarball nesting has varied across registry versions)."
  [extract lang]
  (let [want (str lang ".wasm")
        direct (fs/path extract "grammars" want)]
    (if (fs/exists? direct)
      direct
      (->> (tree-seq fs/directory? (fn [d] (try (fs/list-dir d) (catch Exception _))) extract)
           (filter #(and (= want (str (fs/file-name %)))
                         (= "grammars" (str (fs/file-name (fs/parent %))))))
           first))))

(defn- acquire!
  "Fetch the wasm for lang into place, sha-verifying the final bytes.
   :direct downloads the wasm itself; :zed downloads the extension tarball
   and extracts its grammars/<lang>.wasm member."
  [lang {:keys [source] :as entry} base]
  (let [wasm (wasm-path lang base)
        sha256 (:sha256 entry)]
    (if (= :direct source)
      (fetch/download+verify! (:url entry) wasm sha256)
      (let [stamp (System/currentTimeMillis)
            work (fs/path (paths/libs-dir base)
                          (str ".acquire-" lang "-" stamp))
            blob (fs/path work (str lang "-" stamp ".tar.gz"))
            extract (fs/path work "extract")]
        (try
          (fetch/download+verify! (zed-download-url entry) blob nil)
          (fetch/extract-tarball! blob extract)
          (let [member (find-tar-member extract lang)]
            (when-not member
              (throw (ex-info (str "grammars/" lang
                                   ".wasm member missing from zed tarball")
                              {:type ::missing-member :lang lang})))
            (fs/create-dirs (fs/parent wasm))
            (fs/move member wasm {:replace-existing true}))
          (finally
            (fs/delete-tree work)))))
    ;; final integrity gate regardless of source
    (when-not (= (fetch/sha256 wasm) sha256)
      (fs/delete-if-exists wasm)
      (throw (ex-info (str "grammar blob failed sha256 verification for " lang)
                      {:type ::sha-mismatch :lang lang})))
    wasm))

(defn load-check!
  "Prove a freshly downloaded grammar actually loads: parse a known-clean
   probe snippet (written as a temp file with one of the language's
   extensions) through the CLI and require a clean tree. Throws
   ::load-check-failed otherwise. parse-runner is injectable for tests.
   Opts: {:base dir :langs table}."
  ([lang opts] (load-check! lang opts cli/exec!))
  ([lang {:keys [base langs]} parse-runner]
   (let [entry (get (or langs (languages)) lang)
         {:keys [probe file-types]} entry
         probe-file (fs/path (paths/root base)
                             (str "load-check-" lang "."
                                  (normalize-ext (first file-types))))
         _ (spit (str probe-file) probe)
         _ (ensure-config! base)
         res (parse-runner ["parse" "--wasm" "--config-path"
                            (str (paths/config-path base))
                            (str probe-file)]
                           {:base base
                            :env {"TREE_SITTER_LIBDIR"
                                  (str (paths/libs-dir base))}})]
     (fs/delete-if-exists probe-file)
     (let [out (str (:out res))]
       (when-not (and (= 0 (or (:exit res) 1))
                      (not (str/includes? out "ERROR"))
                      (not (str/includes? out "MISSING")))
         (throw (ex-info (str "grammar load-check failed for " lang)
                         {:type ::load-check-failed :lang lang
                          :result res})))))))

(defn ensure-grammar!
  "Cache-hit path: wasm present + sha-ok -> ensure scaffold, status :cached.
   Otherwise acquire from the pinned source, scaffold, and load-check;
   any failure deletes blob + scaffold and rethrows. Returns
   {:lang lang :status :cached|:installed}, or nil for unknown langs.
   Lang may be a keyword or string; opts: {:base dir :langs table
   :parse-runner fn}. Provisions the CLI binary first (load-check needs it;
   cheap no-op when already cached+verified)."
  ([] (ensure-grammar! nil))
  ([lang] (ensure-grammar! lang nil))
  ([lang {:keys [base langs] :as opts}]
   (let [lang (if (keyword? lang) (name lang) lang)
         entry (get (or langs (languages)) lang)]
     (when entry
       (cli/ensure-binary! {:base base})
       (paths/ensure-dirs! base)
       (let [wasm (wasm-path lang base)
             cached? (and (fs/exists? wasm)
                          (= (fetch/sha256 wasm) (:sha256 entry)))]
         (when-not cached?
           (acquire! lang entry base))
         (scaffold! lang opts)
         (when-not cached?
           (try
             (load-check! lang opts (or (:parse-runner opts) cli/exec!))
             (catch Exception e
               (fs/delete-if-exists wasm)
               (fs/delete-tree (scaffold-dir lang base))
               (throw e))))
         {:lang lang :status (if cached? :cached :installed)})))))
