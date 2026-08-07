(ns kmet.tui.components.h-stack
  "HStack — horizontal stack layout component (pi: components/h-stack.ts).
   Renders children in a row, distributing the viewport width via the
   flexbox-style grow/shrink allocation (stack/allocate-stack-sizes) and
   compositing their lines at x-offsets (utils/composite-line, ANSI-aware).
   Children may be bare components or stack entry maps
   {:component c :basis n :grow n :shrink n :min-size n :max-size n
   :visible (fn [viewport] bool)} — e.g. a right-aligned row is
   [left, {:component spacer :grow 1}, right]. :align controls the vertical
   placement of short children within the row height (:stretch/:start/
   :center/:end). Like pi, an HStack does not receive input — the TUI
   dispatches keys to the focused leaf component only."
  (:require [kmet.tui.protocols :as protocols]
            [kmet.tui.macros :refer [defcomponent]]
            [kmet.tui.utils :as u]
            [kmet.tui.components.stack :as stack]))

(defcomponent HStack nil [entries-atom gap-atom align-atom]
  (render [_this width]
    (let [safe-width (max 1 width)
          viewport {:width safe-width :height Long/MAX_VALUE}
          entries (stack/visible-stack-entries @entries-atom viewport)]
      (if (empty? entries)
        []
        (let [intrinsic (mapv (fn [e]
                                (let [lines (protocols/render (stack/entry-component e) safe-width)]
                                  (reduce (fn [mx line] (max mx (u/visible-width line))) 0 lines)))
                              entries)
              widths (stack/allocate-stack-sizes entries intrinsic safe-width @gap-atom)
              rendered (mapv (fn [e w]
                               (if (zero? w) [] (protocols/render (stack/entry-component e) w)))
                             entries widths)
              height (reduce (fn [mx lines] (max mx (count lines))) 0 rendered)
              align @align-atom]
          (loop [i 0, x 0, acc (vec (repeat height ""))]
            (if (>= i (count rendered))
              acc
              (let [lines (nth rendered i)
                    child-width (nth widths i)
                    offset (case align
                             :center (long (Math/floor (/ (- height (count lines)) 2)))
                             :end (- height (count lines))
                             0)]
                (recur (inc i) (+ x child-width @gap-atom)
                       (loop [row 0, acc acc]
                         (if (>= row (count lines))
                           acc
                           (let [target (+ row offset)]
                             (if (or (< target 0) (>= target height))
                               (recur (inc row) acc)
                               (recur (inc row)
                                      (assoc acc target
                                             (u/composite-line (nth acc target)
                                                               (nth lines row)
                                                               x child-width safe-width)))))))))))))))
  (handle-input [_this _data] nil)
  (invalidate [_this]
    (doseq [e @entries-atom] (protocols/invalidate (stack/entry-component e)))))

;; ─── Construction & API ────────────────────────────────────────────────────

(defn make-h-stack
  "Create an HStack. CHILDREN: components or stack entry maps. Options:
     :gap   — columns between children (default 0, clamped to ≥ 0 like
              pi's normalizeSize)
     :align — :stretch (default) | :start | :center | :end — vertical
              placement of short children within the row height"
  [children & {:keys [gap align] :or {gap 0 align :stretch}}]
  (map->HStack {:entries-atom (atom (vec children))
                :gap-atom (atom (max 0 (long (Math/floor (double (or gap 0))))))
                :align-atom (atom align)}))

(defn h-stack-add-child! [hs child]
  (swap! (:entries-atom hs) conj child))

(defn h-stack-remove-child! [hs child]
  (swap! (:entries-atom hs)
         (fn [v] (vec (remove #(identical? (stack/entry-component %) child) v)))))

(defn h-stack-clear! [hs]
  (reset! (:entries-atom hs) []))

(defn h-stack-set-gap! [hs gap]
  (reset! (:gap-atom hs) (max 0 (long (Math/floor (double (or gap 0)))))))

(defn h-stack-set-align! [hs align]
  (reset! (:align-atom hs) align))
