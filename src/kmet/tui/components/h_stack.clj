(ns kmet.tui.components.h-stack
  "Stub for pi's HStack (components/h-stack.ts).

   NOT IMPLEMENTED — implement upon first use. A horizontal stack layout
   component distributing width among children (flexbox grow/shrink via
   stack/allocate-stack-sizes) and compositing their lines at x-offsets.
   kmet's tui.components.stack only does the vertical render-loop layout;
   implement HStack when a horizontal row of components is needed."
  (:require [kmet.tui.protocols :as protocols]))

(defn- not-implemented []
  (throw (ex-info "HStack is not implemented yet; implement upon first use."
                  {:component :h-stack})))

(defrecord HStack [entries-atom gap-atom align-atom]
  protocols/IComponent
  (render [_this _width] (not-implemented))
  (handle-input [_this _data] nil)
  (invalidate [_this] nil))

(defn make-h-stack
  "Stub — see ns docstring."
  [children & {:keys [gap align] :or {gap 0 align :stretch}}]
  (map->HStack {:entries-atom (atom (vec children))
                :gap-atom (atom gap)
                :align-atom (atom align)}))
