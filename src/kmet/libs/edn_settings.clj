(ns kmet.libs.edn-settings
  "Generic EDN settings primitives: pretty-printing, deep merge, path
   expansion, text-surgery persistence, and lenient parsing.

   This is the shared foundation for kmet.config (settings.edn) and
   kmet.ai.auth (auth.edn) — and any extension that needs to read/write
   EDN files under a file lock (e.g. mcp-adapter's mcp-oauth.edn)."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [babashka.fs :as fs]
            [kmet.libs.file-lock :as file-lock]))

;; ─── Path expansion ────────────────────────────────────────────────────────

(defn expand-path
  "Expand a ~ prefix to user.home. Absolute and relative paths pass through."
  [path]
  (let [s (str path)]
    (if (str/starts-with? s "~")
      (str (System/getProperty "user.home") (subs s 1))
      s)))

;; ─── Deep merge ────────────────────────────────────────────────────────────

(defn deep-merge
  "Recursively merge maps: nested maps merge key-by-key, non-map values from
   later maps win. Vectors/lists are replaced, not merged (pi: 'Nested
   objects are merged' — only objects merge)."
  [& maps]
  (reduce (fn [acc m]
            (if (map? m)
              (merge-with (fn [a b] (if (and (map? a) (map? b)) (deep-merge a b) b)) acc m)
              acc))
          {} maps))

;; ─── Pretty EDN ────────────────────────────────────────────────────────────

(defn pretty-edn
  "Format a map as EDN with each top-level entry on its own line and
   the closing brace on its own line (pi: JSON.stringify(settings, null, 2))."
  [m]
  (str "{" (str/join "\n " (for [[k v] m] (str (pr-str k) " " (pr-str v)))) "\n}\n"))

;; ─── Lenient parsing ───────────────────────────────────────────────────────

(defn safe-parse-edn-map
  "Parse text as an EDN map, nil when malformed or not a map."
  [text]
  (when-let [parsed (try (edn/read-string text) (catch Exception _ nil))]
    (when (map? parsed) parsed)))

;; ─── Text-surgery persistence ──────────────────────────────────────────────

(defn- setting-line
  "EDN text for one pretty entry, e.g. \" :hide-thinking-block true\"."
  [key value]
  (str " " (pr-str key) " " (pr-str value)))

(defn- top-level-key-line?
  "True when LINE is a top-level KEY entry of an EDN map (key at
   line start, possibly indented)."
  [key line]
  (boolean (re-matches (re-pattern (str "^\\s*" (java.util.regex.Pattern/quote (pr-str key)) "\\s+.*"))
                       line)))

(defn update-setting-text
  "Return EDN text with the top-level KEY entry set to VALUE, preserving
   unrelated lines (hand-written comments): the key's line is replaced when
   present, otherwise a new line is inserted with the closing brace on its own
   line (canonical pretty format), so later updates stay in-place."
  [text key value]
  (let [line (setting-line key value)
        lines (str/split-lines text)
        idx (first (keep-indexed (fn [i l] (when (top-level-key-line? key l) i)) lines))
        trailing-nl? (str/ends-with? text "\n")]
    (if idx
      (str (str/join "\n" (assoc (vec lines) idx line))
           (when trailing-nl? "\n"))
      (when-let [i (str/last-index-of text "}")]
        (if (= \newline (get text (dec i)))
          (str (subs text 0 (dec i)) "\n" line "\n}" (subs text (inc i)))
          (str (subs text 0 i) "\n" line "\n}" (subs text (inc i))))))))

(defn save-edn-setting!
  "Persist a single top-level setting to FILE-PATH. PATH is a key path
   (e.g. [:hide-thinking-block]); only that leaf is merged, so unrelated
   keys survive. Hand-written comments are preserved via line surgery; the
   file is rewritten in a canonical pretty format when surgery is unsafe
   (malformed or one-line files, nested paths). File is locked during write."
  [file-path path value]
  (let [path (if (vector? path) path [path])
        file (io/file file-path)]
    (fs/create-dirs (fs/parent file-path))
    (file-lock/with-file-lock (str file-path ".lock")
      (fn []
        (if-not (fs/exists? file)
          (spit file-path (pretty-edn (assoc-in {} path value)))
          (let [text (slurp file-path)
                base (or (safe-parse-edn-map text) {})
                edited (when (= 1 (count path))
                         (update-setting-text text (peek path) value))
                edited-ok? (and edited
                                (let [parsed (safe-parse-edn-map edited)]
                                  (and parsed
                                       (= parsed (assoc-in base path value)))))]
            (if edited-ok?
              (spit file-path edited)
              (spit file-path
                    (pretty-edn (assoc-in base path value))))))))))
