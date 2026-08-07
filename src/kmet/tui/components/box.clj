(ns kmet.tui.components.box
  "Box component - a container that applies padding and background to all children.
   Port of @earendil-works/pi-tui Box. Like pi, a Box does not receive input:
   the TUI dispatches keys to the focused leaf component only."
  (:require [kmet.tui.protocols :as protocols]
            [kmet.tui.macros :refer [defcomponent]]
            [kmet.tui.utils :as u]))

;; ─── Internal helpers (defined before record to be visible in method bodies) ─

(defn- apply-bg [{:keys [bg-fn]} line width]
  (let [vis (u/visible-width line)
        pad (max 0 (- width vis))
        padded (str line (apply str (repeat pad \space)))]
    (if-let [f @bg-fn] (f padded) padded)))

;; ─── Box record ─────────────────────────────────────────────────────────────

(defcomponent Box nil [children padding-x padding-y bg-fn cache]
  (render [this width]
    (if (empty? @children)
      []
      (let [content-width (max 1 (- width (* 2 padding-x)))
            left-pad (apply str (repeat padding-x \space))
            child-lines (mapcat (fn [c]
                                  (map #(str left-pad %) (protocols/render c content-width)))
                                @children)
            bg-fn-val (when-let [f @bg-fn] f)
            bg-sample (when bg-fn-val (bg-fn-val "test"))
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
  (handle-input [_this _data] nil)
  (invalidate [this]
    (reset! (:cache this) nil)
    (doseq [c @children] (protocols/invalidate c))))

;; ─── Constructors & helpers ─────────────────────────────────────────────────

(defn make-box
  ([] (map->Box {:children (atom [])
                 :padding-x 1 :padding-y 1
                 :bg-fn (atom nil) :cache (atom nil)}))
  ([padding-x padding-y] (make-box padding-x padding-y nil))
  ([padding-x padding-y bg-fn]
   (map->Box {:children (atom [])
              :padding-x padding-x :padding-y padding-y
              :bg-fn (atom bg-fn) :cache (atom nil)})))

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
  (reset! (:bg-fn box) bg-fn)
  (reset! (:cache box) nil))
