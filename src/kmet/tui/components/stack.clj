(ns kmet.tui.components.stack
  "Minimal vertical stack layout for the TUI render loop.
   Port of pi's VStack allocation, simplified: the first IScrollView entry
   (kmet.tui.components.scroll-view/IScrollView) grows to fill the height
   left over after the fixed components render at their natural heights.
   The ScrollView windows its child's content, so the total rendered output
   never exceeds the screen height (unless the fixed parts alone overflow)."
  (:require [kmet.tui.protocols :as protocols]
            [kmet.tui.components.scroll-view :as sv]))

(defn render-stack
  "Render a vertical stack of COMPONENTS at WIDTH x HEIGHT.
   The single IScrollView component (if any) gets all remaining height and
   renders its child at the scroll view's content width; every other
   component renders at its natural height. Returns a flat vector of lines."
  [components width height request-render]
  (let [sv-idx (first (keep-indexed (fn [i c] (when (satisfies? sv/IScrollView c) i))
                                    components))]
    (if (nil? sv-idx)
      (vec (mapcat #(protocols/render % width) components))
      (let [sv (nth components sv-idx)
            content-width (sv/get-content-width sv width)
            rendered (mapv (fn [i c]
                             (if (= i sv-idx)
                               (protocols/render c content-width)
                               (protocols/render c width)))
                           (range) components)
            fixed (apply + (keep-indexed (fn [i lines] (when (not= i sv-idx) (count lines)))
                                         rendered))
            viewport (max 0 (- height fixed))]
        (sv/update-layout! sv (count (nth rendered sv-idx)) viewport request-render)
        (vec (apply concat (map-indexed
                            (fn [i lines] (if (= i sv-idx) (sv/render-window sv lines) lines))
                            rendered)))))))
