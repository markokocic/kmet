(ns kmet.tui.components.spacer
  "Spacer component - renders empty lines."
  (:require [kmet.tui.macros :refer [defcomponent]]))

(defcomponent Spacer nil [lines-atom]
  (render [this _width]
    (vec (repeat @lines-atom ""))))

(defn make-spacer
  ([] (map->Spacer {:lines-atom (atom 1)}))
  ([n] (map->Spacer {:lines-atom (atom n)})))
