(ns kmet.tui.components.spacer
  "Spacer component - renders empty lines."
  (:require [kmet.tui.core :as core]))

(defrecord Spacer [lines-atom]
  core/IComponent
  (render [this _width]
    (vec (repeat @lines-atom "")))
  (handle-input [this _data] nil)
  (invalidate [this] nil))

(defn make-spacer
  ([] (map->Spacer {:lines-atom (atom 1)}))
  ([n] (map->Spacer {:lines-atom (atom n)})))
