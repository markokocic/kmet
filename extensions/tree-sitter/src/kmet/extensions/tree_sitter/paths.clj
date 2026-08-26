(ns kmet.extensions.tree-sitter.paths
  "User-level cache layout for the tree-sitter extension (SPEC.md):
   ~/.kmet/agent/tree-sitter/{bin,libs,grammars}/ plus generated config.json.
   Every public fn takes an optional base-dir override so tests and callers
   can work against isolated roots; no env override in v1."
  (:require [babashka.fs :as fs]
            [clojure.java.io :as io]))

(def ^:private default-root
  (fs/path (fs/home) ".kmet" "agent" "tree-sitter"))

(defn bin-name
  "Executable file name inside bin/ for the host OS."
  []
  (if (fs/windows?) "tree-sitter.exe" "tree-sitter"))

(defn root
  ([] default-root)
  ([base] (fs/path (or base default-root))))

(defn bin-dir
  ([] (bin-dir nil))
  ([base] (fs/path (root base) "bin")))

(defn libs-dir
  ([] (libs-dir nil))
  ([base] (fs/path (root base) "libs")))

(defn grammars-dir
  ([] (grammars-dir nil))
  ([base] (fs/path (root base) "grammars")))

(defn bin-path
  ([] (bin-path nil))
  ([base] (fs/path (bin-dir base) (bin-name))))

(defn launcher-path
  "Termux-only glibc launcher script emitted next to the binary."
  ([] (launcher-path nil))
  ([base] (fs/path (bin-dir base) "tree-sitter.sh")))

(defn config-path
  "Generated CLI config.json (parser-directories -> grammars-dir)."
  ([] (config-path nil))
  ([base] (fs/path (root base) "config.json")))

(defn manifest-copy-path
  "Where a copy of the shipped bin manifest is materialized next to the
   downloaded binary (transparency/debugging; the resource stays authoritative)."
  ([] (manifest-copy-path nil))
  ([base] (fs/path (bin-dir base) "manifest.edn")))

(defn ensure-dirs!
  ([] (ensure-dirs! nil))
  ([base]
   (let [r (root base)]
     (doseq [d [(bin-dir base) (libs-dir base) (grammars-dir base)]]
       (fs/create-dirs d))
     r)))

;; ─── Bundled resources ────

(defonce ^:private extension-dir (atom nil))

(defn set-extension-dir!
  "Point bundled-resource at the installed extension's own directory.
   Called from core/init with the api's :extension-dir; the classpath
   fallback keeps dev/test working (they run with resources/ on the
   classpath, unlike the extension sandbox, which serves only .clj
   files)."
  [dir]
  (when dir (reset! extension-dir (str dir))))

(defn bundled-resource
  "Absolute path string of a resource shipped under the extension's
   resources/ directory (REL is relative to that directory), or nil when
   neither the configured extension dir nor the classpath provides it."
  [rel]
  (or (when-let [d @extension-dir]
        (let [p (fs/path d "resources" "kmet" "extensions" "tree_sitter" rel)]
          (when (fs/exists? p) (str p))))
      (some-> (io/resource (str "kmet/extensions/tree_sitter/" rel)) str)))
