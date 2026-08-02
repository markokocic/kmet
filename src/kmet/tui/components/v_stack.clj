(ns kmet.tui.components.v-stack
  "Stub for pi's VStack (components/v-stack.ts).

   NOT IMPLEMENTED — implement upon first use. A vertical stack layout
   component rendering children top-to-bottom with an optional gap. kmet's
   tui.components.stack covers the render-loop layout for the app's fixed
   header/dock; implement VStack when a reusable stacked component is
   needed."
  (:require [kmet.tui.protocols :as protocols]))

(defn- not-implemented []
  (throw (ex-info "VStack is not implemented yet; implement upon first use."
                  {:component :v-stack})))

(defrecord VStack [entries-atom gap-atom]
  protocols/IComponent
  (render [_this _width] (not-implemented))
  (handle-input [_this _data] nil)
  (invalidate [_this] nil))

(defn make-v-stack
  "Stub — see ns docstring."
  [children & {:keys [gap] :or {gap 0}}]
  (map->VStack {:entries-atom (atom (vec children))
                :gap-atom (atom gap)}))
