(ns kmet.tui.components.box
  "Box component - a container that applies padding and background to all children.
   Port of @earendil-works/pi-tui Box."
  (:require [kmet.tui.core :as core]
            [kmet.tui.utils :as u]))

;; ─── Internal helpers (defined before record to be visible in method bodies) ─

(defn- apply-bg [{:keys [bg-fn]} line width]
  (let [vis (u/visible-width line)
        pad (max 0 (- width vis))
        padded (str line (apply str (repeat pad \space)))]
    (if bg-fn (bg-fn padded) padded)))

;; ─── Box record ─────────────────────────────────────────────────────────────

(defrecord Box [children padding-x padding-y bg-fn cache]
  core/IComponent
  (render [this width]
    (if (empty? @children)
      []
      (let [content-width (max 1 (- width (* 2 padding-x)))
            left-pad (apply str (repeat padding-x \space))
            child-lines (mapcat (fn [c]
                                  (map #(str left-pad %) (core/render c content-width)))
                                @children)
            bg-sample (when bg-fn (bg-fn "test"))
            cached @cache]
        (if (and cached
                 (= (:width cached) width)
                 (= (:bg-sample cached) bg-sample)
                 (= (:child-lines cached) child-lines))
          (:lines cached)
          (let [result (into []
                         (concat
                           (repeat padding-y (apply-bg this "" width))
                           (map #(apply-bg this % width) child-lines)
                           (repeat padding-y (apply-bg this "" width))))]
            (reset! cache {:width width :bg-sample bg-sample
                           :child-lines child-lines :lines result})
            result)))))
  (handle-input [_this data]
    (some #(core/handle-input % data) @children))
  (invalidate [this]
    (reset! (:cache this) nil)
    (doseq [c @children] (core/invalidate c))))

;; ─── Constructors & helpers ─────────────────────────────────────────────────

(defn make-box
  ([] (map->Box {:children (atom [])
                 :padding-x 1 :padding-y 1
                 :bg-fn nil :cache (atom nil)}))
  ([padding-x padding-y] (make-box padding-x padding-y nil))
  ([padding-x padding-y bg-fn]
   (map->Box {:children (atom [])
              :padding-x padding-x :padding-y padding-y
              :bg-fn bg-fn :cache (atom nil)})))

(defn box-add-child [box child]
  (swap! (:children box) conj child)
  (reset! (:cache box) nil))

(defn box-remove-child [box child]
  (swap! (:children box) (fn [v] (vec (remove #(identical? % child) v))))
  (reset! (:cache box) nil))

(defn box-clear [box]
  (reset! (:children box) [])
  (reset! (:cache box) nil))

(defn box-set-bg-fn [box bg-fn]
  (reset! (:bg-fn box) bg-fn))
