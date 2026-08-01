(ns kmet.tui.components.image
  "Image component — renders images via Kitty terminal protocol.
   Port of @earendil-works/pi-tui Image component.
   Falls back to text representation when image protocol is unavailable."
  (:require [kmet.tui.protocols :as protocols]
            [kmet.libs.terminal-image :as img]))

(defrecord ImageComponent [base64-data mime-type dimensions
                           theme-atom options image-id-atom
                           cache-atom last-width-atom]
  protocols/IComponent
  (render [this width]
    (let [cached @cache-atom
          last-width @last-width-atom]
      (if (and cached (= last-width width))
        cached
        (let [caps (img/get-capabilities)
              max-width (max 1 (min (- width 2)
                                   (or (:max-width-cells (:options this)) 60)))
              cell-dims (img/get-cell-dimensions)
              default-max-height (max 1 (Math/ceil
                                         (/ (* max-width (:width-px cell-dims))
                                            (:height-px cell-dims))))
              max-height (or (:max-height-cells (:options this)) default-max-height)
              lines (if (:images caps)
                      (let [image-id (or @image-id-atom
                                         (let [id (img/allocate-image-id)]
                                           (reset! image-id-atom id)
                                           id))
                            result (img/render-image base64-data dimensions
                                      :mime-type mime-type
                                      :max-width-cells max-width
                                      :max-height-cells max-height
                                      :image-id image-id
                                      :move-cursor false)]
                        (if result
                          ;; Kitty: sequence on first line, blank padding lines
                          ;; so TUI accounts for image height
                          (let [rows (:rows result)]
                            (into [(:sequence result)]
                                  (repeat (dec rows) "")))
                          ;; Render failed — fallback
                          [(img/image-fallback mime-type
                             :dimensions dimensions
                             :filename (:filename options))]))
                      ;; No image protocol — text fallback
                      [(img/image-fallback mime-type
                         :dimensions dimensions
                         :filename (:filename options))])]
          (reset! cache-atom lines)
          (reset! last-width-atom width)
          lines))))
  (handle-input [_this _data] nil)
  (invalidate [this]
    (reset! cache-atom nil)))

;; ─── Construction ──────────────────────────────────────────────────────────

(defn make-image
  "Create an Image component.
   base64-data — base64-encoded image data
   mime-type — e.g. \"image/png\"
   theme — {:fallback-color (fn [str] -> str)}
   opts — :max-width-cells, :max-height-cells, :filename, :image-id"
  [base64-data mime-type theme & {:keys [max-width-cells max-height-cells filename image-id]
                                   :or {max-width-cells 60}}]
  (let [dimensions (or (img/get-image-dimensions base64-data mime-type)
                       {:width-px 800 :height-px 600})]
    (map->ImageComponent
      {:base64-data base64-data
       :mime-type mime-type
       :dimensions dimensions
       :theme-atom (atom theme)
       :options {:max-width-cells max-width-cells
                 :max-height-cells max-height-cells
                 :filename filename}
       :image-id-atom (atom image-id)
       :cache-atom (atom nil)
       :last-width-atom (atom nil)})))

;; ─── Public API ─────────────────────────────────────────────────────────

(defn image-get-id [comp]
  @(:image-id-atom comp))

(defn image-set-theme! [comp theme]
  (reset! (:theme-atom comp) theme)
  (protocols/invalidate comp))
