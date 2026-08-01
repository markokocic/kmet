(ns kmet.app.frontmatter
  "YAML frontmatter parsing shared by skills and prompt templates
   (pi: utils/frontmatter.js). Extracts the --- delimited block and delegates
   to kmet.libs.yaml-lite (a minimal YAML subset parser)."
  (:require [clojure.string :as str]
            [kmet.libs.yaml-lite :as yaml]))

(defn parse-frontmatter
  "Parse YAML frontmatter (--- delimited) from markdown content.
   Returns {:frontmatter map :body string}. Content without frontmatter yields
   an empty frontmatter map and the full content as body (pi: extractFrontmatter)."
  [content]
  (let [normalized (str/replace content #"\r\n|\r" "\n")]
    (if-not (str/starts-with? normalized "---")
      {:frontmatter {} :body normalized}
      (let [end-idx (str/index-of normalized "\n---" 3)]
        (if (nil? end-idx)
          {:frontmatter {} :body normalized}
          ;; max clamps for empty frontmatter (---\n---): pi's slice returns ""
          {:frontmatter (or (yaml/parse (subs normalized 4 (max 4 end-idx))) {})
           :body (str/trim (subs normalized (+ end-idx 4)))})))))
