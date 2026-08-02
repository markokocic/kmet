(ns kmet.tui.components.truncated-text
  "Stub for pi's TruncatedText (components/truncated-text.ts).

   NOT IMPLEMENTED — implement upon first use. A single-line Text that
   truncates to the viewport width (ANSI-aware) with horizontal/vertical
   padding. kmet tui.components.text may cover most uses; implement this
   when a truncating one-liner is actually needed."
  (:require [kmet.tui.protocols :as protocols]))

(defn- not-implemented []
  (throw (ex-info "TruncatedText is not implemented yet; implement upon first use."
                  {:component :truncated-text})))

(defrecord TruncatedText [text-atom padding-x-atom padding-y-atom]
  protocols/IComponent
  (render [_this _width] (not-implemented))
  (handle-input [_this _data] nil)
  (invalidate [_this] nil))

(defn make-truncated-text
  "Stub — see ns docstring."
  [text & {:keys [padding-x padding-y] :or {padding-x 0 padding-y 0}}]
  (map->TruncatedText {:text-atom (atom text)
                       :padding-x-atom (atom padding-x)
                       :padding-y-atom (atom padding-y)}))
