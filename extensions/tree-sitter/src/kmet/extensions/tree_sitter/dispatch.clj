(ns kmet.extensions.tree-sitter.dispatch
  "Route a file extension to the validator that owns it:

     :tree-sitter  grammar-backed languages (python, typescript, tsx) —
                   full parse validation
     :delimiter    clojure-family files when the clojure extension is NOT
                   enabled — comment/string-aware bracket balance only
                   (the clojure grammar is intentionally permissive, so
                   gating on its ERROR nodes would be all noise)
     :defer        clojure-family files when the clojure extension IS
                   enabled — its paren-repair hooks own them
     nil           everything else — never validated

   Whether the clojure extension is enabled is checked lazily per hook
   invocation through the api captured by set-api!. Tests can override the
   decision with the :clojure-enabled? opt."
  (:require [clojure.string :as str]
            [kmet.extensions.tree-sitter.grammars :as grammars]))

(def ^:private api-atom (atom nil))

(defn set-api!
  "Capture the extension api so clojure-extension presence can be checked
   lazily at hook time. Called once from core/init."
  [api-map]
  (reset! api-atom api-map))

(def ^:private clojure-family-exts
  #{"clj" "cljs" "cljc" "cljd" "bb" "edn" "lpy"})

(def ^:private clojure-extension-tools
  #{"clojure_edit" "clojure_edit_replace_sexp" "clojure_paren_repair"})

(defn clojure-extension-enabled?
  []
  (when-some [get-all (:get-all-tools @api-atom)]
    (boolean (some #(contains? clojure-extension-tools
                               (some-> (:name %) name))
                   (get-all)))))

(defn route
  ":tree-sitter | :delimiter | :defer | nil for a file EXTENSION (without
   dot, case-insensitive). Opts: {:clojure-enabled? bool :langs table}."
  ([ext] (route ext nil))
  ([ext {:keys [langs clojure-enabled?]}]
   (let [ext (some-> ext str/lower-case)
         family? (contains? clojure-family-exts ext)
         lang (when ext (grammars/resolve-lang ext {:langs langs}))
         enabled? (if (nil? clojure-enabled?)
                    (clojure-extension-enabled?)
                    (boolean clojure-enabled?))]
     (cond
       (and family? enabled?) :defer
       family? :delimiter
       lang :tree-sitter
       :else nil))))
