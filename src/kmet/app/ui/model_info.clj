(ns kmet.app.ui.model-info
  "Shared model info display for the /model and /scoped-models overlays —
   the selected model's name and cost lines, the cost rates marked with
   the footer's token-direction marks (↑ input, ↓ output, C↑ cache read,
   C↓ cache write)."
  (:require [clojure.string :as str]
            [kmet.tui.theme :as theme]))

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

(defn- cost-line
  "The cost line for a (non-nil) model: the priced rates marked ↑/↓ and
   C↑/C↓, or \"Cost: free\" when it carries no priced rates (pi doesn't
   show cost in the selectors; kmet adds it opportunistically)."
  [model]
  (let [c (:cost model)
        in-rate (fmt-usd-rate (:input c))
        out-rate (fmt-usd-rate (:output c))
        cr-rate (fmt-usd-rate (:cache-read c))
        cw-rate (fmt-usd-rate (:cache-write c))
        parts (cond-> []
                in-rate (conj (str "↑" in-rate))
                out-rate (conj (str "↓" out-rate))
                cr-rate (conj (str "C↑" cr-rate))
                cw-rate (conj (str "C↓" cw-rate)))]
    (if (seq parts)
      (str "  Cost: " (str/join " · " parts))
      "  Cost: free")))

(defn model-info-lines
  "The muted-styled info lines under the selectors' model rows for the
   selected model: the name line plus the cost line — or just
   \"Model unavailable\" for a nil model (an enabled id with no catalog
   entry in the scoped selector). Each string renders as one text row."
  [model]
  (let [th (theme/get-current-theme)]
    (if model
      [(theme/fg th :muted (str "  Model Name: " (:name model)))
       (theme/fg th :muted (cost-line model))]
      [(theme/fg th :muted "  Model unavailable")])))