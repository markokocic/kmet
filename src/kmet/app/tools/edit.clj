(ns kmet.app.tools.edit
  "Edit tool implementation — precise text replacement in files.
   Accepts :edits (vector of maps, pi's oldText/newText or kebab-case),
   a JSON-string edits arg, or the legacy top-level old-text/new-text pair.
   Pi: edit.ts — prepareEditArguments + validateEditInput."
  (:require [clojure.string :as str]
            [clojure.java.io :as io]
            [babashka.fs :as fs]
            [cheshire.core :as json]))

(defn- normalize-edits
  "Pi: prepareEditArguments — normalize the args into {:edits [...]}.
   Handles: edits as a JSON string, camelCase oldText/newText keys,
   and the legacy top-level old-text/new-text pair (appended)."
  [{:keys [edits old-text new-text oldText newText] :as args}]
  (let [parsed (cond
                 (string? edits) (try (let [p (json/parse-string edits true)]
                                        (when (sequential? p) p))
                                      (catch Exception _ nil))
                 (sequential? edits) edits
                 :else nil)
        kebab (mapv (fn [e]
                      {:old-text (or (:old-text e) (:oldText e))
                       :new-text (or (:new-text e) (:newText e))})
                    parsed)
        legacy (when (and (or old-text oldText) (or new-text newText))
                 {:old-text (or old-text oldText) :new-text (or new-text newText)})]
    (cond-> (assoc args :edits (seq kebab))
      legacy (update :edits (fnil conj []) legacy))))

(defn execute
  "Precise text replacement in a file.
   Each edit's old-text is matched against the current content."
  [{:keys [path] :as args}]
  (let [{:keys [edits]} (normalize-edits args)]
    (if (or (nil? edits) (empty? edits))
      ;; Pi: validateEditInput — at least one replacement required
      {:content "Edit tool input is invalid. edits must contain at least one replacement."
       :is-error true}
      (try
        (let [f (io/file path)]
          (if-not (fs/exists? f)
            {:content (str "File not found: " path) :is-error true}
            (let [content (slurp f)
                  applied (loop [current content
                                 remaining edits]
                            (if (empty? remaining)
                              {:ok? true :content current}
                              (let [{:keys [old-text new-text]} (first remaining)]
                                (if (str/includes? current old-text)
                                  (recur (str/replace-first current old-text new-text) (rest remaining))
                                  {:ok? false :error (str "Could not find old-text in " path)}))))]
              (if-not (:ok? applied)
                {:content (:error applied) :is-error true}
                (let [result (:content applied)]
                  (spit f result)
                  {:content (str "Successfully replaced " (count edits) " block(s) in " path ".")})))))
        (catch Exception e
          {:content (str "Error editing " path ": " (ex-message e)) :is-error true})))))
