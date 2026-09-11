;; kmet.tasks.generate-models — the `bb generate-models` / `bb check-model-data`
;; task entry over kmet.ai.model-gen (the generator implementation, which lives
;; in src so the packaged binary can run `kmet --generate-models` targeting the
;; user-level cache instead of the committed catalogs). Task-only code: nothing
;; in the app requires it and uberjar* keeps kmet/tasks/* out of the jar.
;;
;; Run via: bb generate-models   (network, regenerates src/kmet/ai/model_data)
;; Check via: bb check-model-data (offline)

(ns kmet.tasks.generate-models
  (:require [kmet.ai.model-gen :as gen]))

(def data-dir gen/data-dir)
(def validate-committed! gen/validate-committed!)
(def -main gen/-main)
