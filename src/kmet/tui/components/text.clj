(ns kmet.tui.components.text
  "Text component - displays multi-line text with word wrapping."
  (:require [kmet.tui.protocols :as protocols]
            [kmet.tui.utils :as u]
            [kmet.tui.macros :refer [track!]]))

(defrecord Text [text-atom padding-x padding-y bg-fn cache]
  protocols/IComponent
  (render [this width]
    (track! this width
      (let [text @text-atom
            normalized (clojure.string/replace text "\t" "   ")
            cw (max 1 (- width (* 2 padding-x)))
            wrapped (if (clojure.string/blank? text)
                      []
                      (u/wrap-text-with-ansi normalized cw))
            left (apply str (repeat padding-x \space))
            right (apply str (repeat padding-x \space))
            empty-line (apply str (repeat width \space))
            pad-lines (vec (repeat padding-y empty-line))
            content-lines (mapv (fn [line]
                                  (let [padded (str left line right)
                                        vis (u/visible-width padded)
                                        need (max 0 (- width vis))
                                        filled (str padded (apply str (repeat need \space)))]
                                    (if bg-fn (bg-fn filled) filled)))
                                wrapped)
            result (if (clojure.string/blank? text)
                     []
                     (into [] (concat pad-lines content-lines pad-lines)))]
        result)))
  (handle-input [this data] nil)
  (invalidate [this] (reset! cache nil)))

(defn make-text
  ([text] (make-text text 1 1 nil))
  ([text padding-x padding-y] (make-text text padding-x padding-y nil))
  ([text padding-x padding-y bg-fn]
   (map->Text {:text-atom (atom text)
               :padding-x padding-x
               :padding-y padding-y
               :bg-fn bg-fn
               :cache (atom nil)})))

(defn text-set! [text new-text]
  (reset! (:text-atom text) new-text))
