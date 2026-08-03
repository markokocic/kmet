(ns kmet.tui.components.dynamic-border
  "DynamicBorder — a border line that adjusts to viewport width.
   Port of pi modes/interactive/components/dynamic-border.ts. Renders a
   single horizontal rule (─) spanning the full render width, colored via
   the provided color function. Used to frame dialogs (pi: preset.ts pick
   dialog, BorderedLoader)."
  (:require [kmet.tui.protocols :as protocols]
            [kmet.tui.theme :as theme]))

(defrecord DynamicBorder [color-fn]
  protocols/IComponent
  (render [_this width]
    [(color-fn (apply str (repeat (max 1 width) "─")))])
  (handle-input [_this _data] nil)
  (invalidate [_this] nil))

(defn make-dynamic-border
  "Create a DynamicBorder. COLOR-FN receives the border string and returns
   it styled (pi: DynamicBorder constructor). Default colors with the dark
   theme's :border token — pass an explicit color function (e.g. themed from
   the config theme) when the component is used in extension UIs."
  ([] (map->DynamicBorder {:color-fn #(theme/fg theme/dark-theme :border %)}))
  ([color-fn] (map->DynamicBorder {:color-fn color-fn})))
