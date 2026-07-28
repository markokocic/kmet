(ns kmet.tui.components.container
  "Container component - groups child components vertically.
   Port of @earendil-works/pi-tui Container."
  (:require [kmet.tui.core :as core]))

(defrecord Container [children]
  core/IComponent
  (render [_this width] (mapcat #(core/render % width) @children))
  (handle-input [_this data] (some #(core/handle-input % data) @children))
  (invalidate [_this] (doseq [c @children] (core/invalidate c))))

(defn make-container
  ([] (map->Container {:children (atom [])}))
  ([children] (map->Container {:children (atom (vec children))})))

(defn container-add-child [c child] (swap! (:children c) conj child))
(defn container-remove-child [c child]
  (swap! (:children c) (fn [v] (vec (remove #(identical? % child) v)))))
(defn container-clear [c] (reset! (:children c) []))
