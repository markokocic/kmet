;; scripts/generate_models.clj — bb-task entry over kmet.ai.model-gen (the
;; generator implementation lives in src so the packaged binary can run
;; `kmet --generate-models`, which targets the user-level cache instead of
;; the committed catalogs). Kept as the documented workflow entry; resolved
;; by bb.edn's generate-models / check-model-data tasks and loaded by
;; test/kmet/ai/test_model_data.clj.
;;
;; Run via: bb generate-models   (network, regenerates src/kmet/ai/model_data)
;; Check via: bb check-model-data (offline)

(ns generate-models
  (:require [kmet.ai.model-gen :as gen]))

(def data-dir gen/data-dir)
(def validate-committed! gen/validate-committed!)
(def -main gen/-main)
