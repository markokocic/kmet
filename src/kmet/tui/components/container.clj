(ns kmet.tui.components.container
  "Container component - groups child components vertically.
   Port of @earendil-works/pi-tui Container."
  (:require [kmet.tui.protocols :as protocols]))

(defrecord Container [children]
  protocols/IComponent
  (render [_this width] (into [] (mapcat #(protocols/render % width)) @children))
  (handle-input [_this data] (some #(protocols/handle-input % data) @children))
  (invalidate [_this] (doseq [c @children] (protocols/invalidate c))))

(defn make-container
  ([] (map->Container {:children (atom [])}))
  ([children] (map->Container {:children (atom (vec children))})))

(defn container-add-child [c child] (swap! (:children c) conj child))
(defn container-remove-child [c child]
  (swap! (:children c) (fn [v] (vec (remove #(identical? % child) v)))))
(defn container-clear [c] (reset! (:children c) []))
