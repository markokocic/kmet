(ns kmet.tui.components.cancellable-loader
  "Stub for pi's CancellableLoader (components/cancellable-loader.ts).

   NOT IMPLEMENTED — implement upon first use. A Loader (kmet
   tui.components.spinner/Spinner) that can be cancelled with Escape,
   exposing an abort signal so async operations can stop early. Until then
   every API call throws with a clear message instead of silently doing
   nothing."
  (:require [kmet.tui.protocols :as protocols]))

(defn- not-implemented []
  (throw (ex-info "CancellableLoader is not implemented yet; implement upon first use."
                  {:component :cancellable-loader})))

(defrecord CancellableLoader [spinner abort-signal-atom on-abort-fn-atom]
  protocols/IComponent
  (render [_this _width] (not-implemented))
  (handle-input [_this _data] nil)
  (invalidate [_this] nil))

(defn make-cancellable-loader
  "Stub — see ns docstring."
  [& {:keys [spinner on-abort]}]
  (map->CancellableLoader {:spinner spinner
                           :abort-signal-atom (atom nil)
                           :on-abort-fn-atom (atom on-abort)}))

(defn cancellable-loader-aborted?
  "Stub — true once the loader was cancelled (pi: aborted)."
  [_this]
  (not-implemented))

(defn cancellable-loader-dispose!
  "Stub — stop the loader (pi: dispose)."
  [_this]
  (not-implemented))
