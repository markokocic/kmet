(ns kmet.tui.components.spacer
  "Spacer component - renders empty lines."
  (:require [kmet.tui.macros :refer [track! defcomponent]]))

(defcomponent Spacer nil [lines-atom cache-atom]
  (render [this width]
    (track! this width
      (vec (repeat @lines-atom "")))))

(defn make-spacer
  ([] (map->Spacer {:lines-atom (atom 1) :cache-atom (atom nil)}))
  ([n] (map->Spacer {:lines-atom (atom n) :cache-atom (atom nil)})))
