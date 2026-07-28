(ns kmet.tui.components.spacer
  "Spacer component - renders empty lines."
  (:require [kmet.tui.protocols :as protocols]))

(defrecord Spacer [lines-atom]
  protocols/IComponent
  (render [this _width]
    (vec (repeat @lines-atom "")))
  (handle-input [this _data] nil)
  (invalidate [this] nil))

(defn make-spacer
  ([] (map->Spacer {:lines-atom (atom 1)}))
  ([n] (map->Spacer {:lines-atom (atom n)})))
