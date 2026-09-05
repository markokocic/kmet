(ns kmet.libs.json
  "JSON encode/decode boundary for kmet and its shipped extensions (pi:
   the data.json npm package + the JSON.stringify/parse global). All JSON
   touches funnel through this namespace so the JSON engine is swappable
   (cheshire, the JSON library babashka bundles, today — jolt-port.md M1
   notes cheshire is not in the Jolt stdlib, so the swap point is this
   file alone).

   The vars below ARE cheshire.core's own fns (def alias — no wrapper
   layer, no behavior drift): cheshire's lazy parse-string, exact option
   handling and encoding rules all apply unchanged. Call sites use
   `json/parse-string` and `json/generate-string` with the same arities
   cheshire exposes; the alias keeps one require target for the whole
   codebase and lets the engine change behind it."
  (:require [cheshire.core :as cheshire]))

(def parse-string
  "Parse the JSON string S into Clojure data. KEYWORDS? true converts
   object keys to keywords; false (default) keeps strings. Malformed
   JSON throws (cheshire's JsonParseException). Array results are lazy
   seqs — realize inside a guard when consuming (see kmet.app.tools.edit)."
  cheshire/parse-string)

(def generate-string
  "Encode X as JSON text. OPTS passes through to cheshire: {:pretty bool
   :escape-non-ascii bool} and the rest of cheshire's option map."
  cheshire/generate-string)
