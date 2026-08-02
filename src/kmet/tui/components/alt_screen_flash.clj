(ns kmet.tui.components.alt-screen-flash
  "Stub for pi's AltScreenFlashContainer (components/alt-screen-flash.ts).

   NOT IMPLEMENTED — implement upon first use. Transient messages (e.g.
   status feedback) composited by the alternate-screen renderer; kmet has
   no alternate-screen renderer yet. Until then every API call throws with
   a clear message instead of silently rendering nothing."
  (:require [kmet.tui.protocols :as protocols]))

(defn- not-implemented []
  (throw (ex-info "AltScreenFlash is not implemented yet; implement upon first use."
                  {:component :alt-screen-flash})))

(defrecord AltScreenFlashContainer [request-render-fn entries-atom next-id-atom]
  protocols/IComponent
  (render [_this _width] (not-implemented))
  (handle-input [_this _data] nil)
  (invalidate [_this] nil))

(defn make-alt-screen-flash
  "Stub — see ns docstring."
  [request-render]
  (map->AltScreenFlashContainer {:request-render-fn request-render
                                 :entries-atom (atom [])
                                 :next-id-atom (atom 0)}))

(defn alt-screen-flash!
  "Stub — show a transient message for DURATION-MS (pi: flash)."
  [_this _message & _opts]
  (not-implemented))

(defn alt-screen-flash-dispose!
  "Stub — clear all pending flashes (pi: dispose)."
  [_this]
  (not-implemented))
