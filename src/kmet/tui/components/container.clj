(ns kmet.tui.components.container
  "Container component - groups child components vertically.
   Port of @earendil-works/pi-tui Container. Like pi, a Container does not
   receive input: the TUI dispatches keys to the focused leaf component
   only (pi: focusedComponent?.handleInput)."
  (:require [kmet.tui.protocols :as protocols]
            [kmet.tui.macros :refer [defcomponent]]))

(defcomponent Container nil [children]
  (render [_this width] (into [] (mapcat #(protocols/render % width)) @children))
  ;; pi: no handleInput on containers — input routes via TUI focus
  (invalidate [_this] (doseq [c @children] (protocols/invalidate c))))

(defn make-container
  ([] (map->Container {:children (atom [])}))
  ([children] (map->Container {:children (atom (vec children))})))

(defn container-add-child [c child] (swap! (:children c) conj child))
(defn container-remove-child [c child]
  (swap! (:children c) (fn [v] (vec (remove #(identical? % child) v)))))
(defn container-set-children!
  "Replace all children at once (used for ordered insertion)."
  [c children]
  (reset! (:children c) (vec children)))
(defn container-clear [c] (reset! (:children c) []))
