(ns extensions.lsp-adapter.config
  "EDN LSP config loading/merging for the lsp-adapter extension (plan §6,
   lsp-adapter.plan at the repo root — mcp-adapter's §6 mechanics adapted to
   LSP keys, built on kmet.libs.edn-store primitives instead of hand-rolled
   persistence).

   Sources & precedence:
     - global  ~/.kmet/agent/lsp.edn   (lower)
     - project <cwd>/.kmet/lsp.edn     (higher — the only file this
                                        extension writes)

   Project wins via deep-merge: per-field server merge + per-key settings
   merge. Keys are read in camel or kebab form so a config copied from an
   opencode/pi-style JSON file works with light edits; unknown keys pass
   through unmodified. Enum values (:lifecycle) accept string or keyword.

   The extension cannot require kmet.config — paths derive from fs/home and
   fs/cwd directly. Both path bindings are derefable/fn so validation
   scripts can redirect them with with-redefs."
  (:require [babashka.fs :as fs]
            [kmet.libs.edn-store :as edn-store]))

(def ^:private default-settings
  {:request-timeout-ms 30000
   :initialize-timeout-ms 60000
   :idle-timeout 15})

;; ─── Paths ────────────────────────────────────────────────────────────────

(def global-config-path
  "Derefable delay — the global lsp.edn path. Redefable in validation
   scripts (mcp-adapter convention)."
  (delay (str (fs/path (fs/home) ".kmet" "agent" "lsp.edn"))))

(defn project-config-path
  "The project-local lsp.edn path (the only file this extension writes).
   A fn for the same redef reason."
  [& _]
  (str (fs/path (str (fs/cwd)) ".kmet" "lsp.edn")))

;; ─── Key normalization (§6.3) ─────────────────────────────────────────────

(def ^:private key-aliases
  "Camel → kebab aliases for known keys. Unknown keys pass through
   unmodified."
  {:requestTimeoutMs :request-timeout-ms
   :initializeTimeoutMs :initialize-timeout-ms
   :idleTimeout :idle-timeout
   :initializationOptions :initialization-options
   :rootMarkers :root-markers
   :excludeRootMarkers :exclude-root-markers
   :rootDir :root-dir
   :extendExtensions :extend-extensions})

(defn- normalize-key
  ([k] (normalize-key k key-aliases))
  ([k aliases]
   (if (keyword? k)
     (or (aliases k) k)
     k)))

(def ^:private nested-normalizing-keys
  "Maps whose own known aliases fold too (:diagnostics knobs)."
  #{:diagnostics})

(def ^:private diagnostics-aliases
  {:afterEdit :after-edit
   :waitMs :wait-ms
   :maxPerFile :max-per-file
   :floodThreshold :flood-threshold
   :maxProjectFiles :max-project-files})

(def ^:private keyword-valued-keys #{:lifecycle})

(defn- coerce-keyword-value
  "Enum-valued options accept string or keyword; keywords win."
  [v]
  (cond
    (keyword? v) v
    (string? v) (keyword v)
    :else v))

(defn- normalize-map-value
  ([m] (normalize-map-value m key-aliases))
  ([m aliases]
   (into {}
         (keep (fn [[k v]]
                 (let [k (normalize-key k aliases)]
                   (when (some? v)
                     [k (cond
                          (and (contains? nested-normalizing-keys k) (map? v))
                          (normalize-map-value v diagnostics-aliases)

                          (contains? keyword-valued-keys k)
                          (coerce-keyword-value v)

                          :else v)]))))
         m)))

(defn- normalize-config
  "Normalized {:settings … :servers …} from a raw parsed file map: alias
   folding on settings and every server entry, nils dropped. Entries that
   are not maps are passed through untouched — set-server-disabled!
   rejects those with a clear error only when it must touch them."
  [raw]
  (let [base (normalize-map-value raw)
        settings (:settings raw)
        servers (:servers raw)]
    ;; non-map :settings/:servers (type-malformed) degrade to empty — same
    ;; leniency as syntax-malformed files; startup never crashes on config
    (assoc base
           :settings (if (map? settings) (normalize-map-value settings) {})
           :servers (if (map? servers)
                      (into {}
                            (map (fn [[name entry]]
                                   [name (if (map? entry)
                                           (normalize-map-value entry)
                                           entry)]))
                            servers)
                      {}))))

;; ─── Reading & merging (§6.1) ─────────────────────────────────────────────

(defn read-config-file
  "Parsed + normalized config file map, {} when missing/malformed/empty.
   Malformed files degrade to empty rather than failing startup — the
   lenient-read convention (a broken project file then loses to global
   instead of killing the session)."
  [path]
  (normalize-config (or (edn-store/read-edn-map path) {})))

(defn load-config
  "The merged effective config: documented defaults, deep-merged under
   global, under project (project wins per-key)."
  []
  (edn-store/deep-merge {:settings default-settings :servers {}}
                        (read-config-file @global-config-path)
                        (read-config-file (project-config-path))))

;; ─── Template creation (§6.4) ─────────────────────────────────────────────

(def ^:private template-edn
  ";; kmet lsp-adapter — language server configuration (global file).
;; A project .kmet/lsp.edn overrides this file per-key (project wins).
;;
;; Built-in servers need no configuration — install the binary and touch a
;; claimed file: clojure-lsp (clj cljs cljc edn bb), typescript-language-
;; server (ts js tsx jsx), pyright-langserver (py), rust-analyzer (rs),
;; gopls (go), clangd (c/cpp), ruby-lsp (rb), bash-language-server (sh),
;; jdtls (java).
;;
;; Entries here override builtins or add custom servers:
{:settings {:request-timeout-ms 30000  ;; per-LSP-request bound (ms)
            :initialize-timeout-ms 60000
            :idle-timeout 15           ;; minutes of idleness before a
                                       ;; server is disconnected; 0 = never
            ;; :diagnostics {:after-edit true}  ;; Phase 2 hook
            }
 :servers {\"jdtls\" {:disabled true}          ;; remove a builtin entirely
           \"rust-analyzer\" {:request-timeout-ms 60000}
           ;; \"fennel\" {:command \"fennel-ls\"      ;; out-of-tree server
           ;;            :extensions [\"fnl\"]
           ;;            :root-markers [\"flxproject.ni\"]}
           }}
")

(defn ensure-global-template!
  "Write the schema-commented starter template to the global lsp.edn when
   it does not exist yet (§6.4). Returns the path when written, nil when
   the file already exists."
  []
  (let [path @global-config-path]
    (when-not (fs/exists? path)
      (fs/create-dirs (fs/parent path))
      (spit path template-edn)
      path)))

;; ─── enable/disable write-back (§6.5) ─────────────────────────────────────

(defn set-server-disabled!
  "Persist a :disabled override for SERVER-NAME into the project config
   (§6.5):

     Disable: set :disabled true on the server entry (created if absent).
     Enable:  remove :disabled — unless the lower-precedence global file
              still disables the builtin, in which case write explicit
              :disabled false; prune the entry when it ends up empty.

   Writes canonical pretty EDN via update-edn-map! (file-locked). Returns
   {:path str :changed bool}; :changed false when the write would be a
   no-op. The command tells the user to /reload (or /lsp refresh)."
  [server-name disabled]
  (let [path (project-config-path)
        lower-disabled? (boolean
                         (get-in (read-config-file @global-config-path)
                                 [:servers server-name :disabled]))
        changed? (atom false)
        _ (fs/create-dirs (fs/parent path))
        result
        (edn-store/update-edn-map! path
                                   (fn [raw]
                                     (let [servers (get raw :servers {})
                                           existing (get servers server-name)]
                                       (when (and (some? existing) (not (map? existing)))
                                         (throw (ex-info (str "Failed to update project LSP override at "
                                                              path ": server \"" server-name
                                                              "\" must be a map")
                                                         {:type :lsp-config-error :path path}))))
                                     (let [existing (get-in raw [:servers server-name])
                                           next (if disabled
                                                  (assoc (or existing {}) :disabled true)
                                                  (let [cleaned (dissoc (or existing {}) :disabled)]
                                                    (if lower-disabled?
                                                      (assoc cleaned :disabled false)
                                                      cleaned)))]
                                       (reset! changed? (not= (or existing {}) next))
                                       (cond
                                         (and (seq next) (not= next {}))
                                         (assoc-in raw [:servers server-name] next)

                                         ;; pruning must not materialize an
                                         ;; empty :servers key in a file that
                                         ;; never had one
                                         (contains? raw :servers)
                                         (update raw :servers dissoc server-name)

                                         :else raw))))]
    {:path path :changed @changed? :config result}))
