(ns kmet.app.ui.model-cost
  "Shared model cost display for the /model and /scoped-models overlays —
   a compact $/M rate line using the footer's token-direction marks
   (↑ input, ↓ output, C↑ cache read, C↓ cache write)."
  (:require [clojure.string :as str]))

(defn- fmt-usd-rate
  "$/M rate as a compact string (0.44 → \"$0.44\", 15 → \"$15\"); the
   per-million-tokens unit is implied by the context. nil when absent
   or zero."
  [v]
  (when (and (number? v) (pos? v))
    (let [s (-> (format "%.4g" (double v))
                (str/replace #"(\.\d*?)0+$" "$1")
                (str/replace #"\.$" ""))]
      (str "$" s))))

(defn model-cost-str
  "Compact cost summary line for a Model record's :cost — input/output
   rates marked ↑/↓, plus cache-read/cache-write rates as C↑/C↓ when the
   model prices them (pi doesn't show cost in the selectors; kmet adds it
   opportunistically). nil when the model carries no cost data or all
   rates are zero."
  [model]
  (when-let [c (:cost model)]
    (let [in-rate (fmt-usd-rate (:input c))
          out-rate (fmt-usd-rate (:output c))
          cr-rate (fmt-usd-rate (:cache-read c))
          cw-rate (fmt-usd-rate (:cache-write c))
          parts (cond-> []
                  in-rate (conj (str "↑" in-rate))
                  out-rate (conj (str "↓" out-rate))
                  cr-rate (conj (str "C↑" cr-rate))
                  cw-rate (conj (str "C↓" cw-rate)))]
      (when (seq parts)
        (str "  Cost: " (str/join " · " parts))))))