(ns kmet.extensions.tree-sitter.paths
  "User-level cache layout for the tree-sitter extension (SPEC.md):
   ~/.kmet/agent/tree-sitter/{bin,libs,grammars}/ plus generated config.json.
   Every public fn takes an optional base-dir override so tests and callers
   can work against isolated roots; no env override in v1."
  (:require [babashka.fs :as fs]))

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
