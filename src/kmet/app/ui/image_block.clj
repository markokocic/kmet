(ns kmet.app.ui.image-block
  "Inline image rendering for chat content — one block per image (pi:
   ToolExecutionComponent's Image child + getTextOutput's imageFallback).

   A block renders the terminal image when display is enabled (the
   :show-images setting AND terminal protocol support) and the styled
   imageFallback text indicator otherwise. It subscribes to the shared
   ui.subs/image-settings-sub, so a /settings change re-renders every
   mounted block — tool results and message images alike."
  (:require [kmet.app.ui.subs :as s]
            [kmet.libs.terminal-image :as timg]
            [kmet.tui.components.image :as ic]
            [kmet.tui.macros :refer [track! defcomponent]]
            [kmet.tui.protocols :as protocols]
            [kmet.tui.utils :as utils]))

;; ─── Content helper ────────────────────────────────────────────────────────

(defn content-images
  "Image blocks of a message content value (a string, or a vector of content
   blocks — pi: user message attachments, image tool results) as
   [{:data base64 :mime-type str} …], [] when there are none. Block :type
   may be the keyword :image or the string \"image\" (session data mixes
   both); malformed blocks are dropped."
  [content]
  (if (or (nil? content) (string? content))
    []
    (into []
          (comp (filter #(contains? #{:image "image"} (:type %)))
                (keep (fn [b]
                        (when (and (:data b) (:mime-type b))
                          {:data (:data b) :mime-type (:mime-type b)}))))
          content)))

(defn images-enabled?
  "Whether SETTINGS render images as terminal images: the :show-images
   setting AND terminal protocol support (pi: caps.images && showImages —
   otherwise the imageFallback text indicator is drawn instead)."
  [settings]
  (and (:show-images settings)
       (boolean (:images (timg/get-capabilities)))))

;; ─── Record ────────────────────────────────────────────────────────────────

(defcomponent ImageBlock nil
              [data mime-type filename dimensions fallback-style cache-atom]
  (render [this width]
    (track! this width
      (let [settings (deref s/image-settings-sub)
            theme (deref s/theme-sub)
            style (or fallback-style (fn [_ s] s))]
        (if (images-enabled? settings)
          (protocols/render
           (ic/make-image data mime-type
                          {:fallback-color #(style theme %)}
                          :max-width-cells (:image-width-cells settings)
                          :filename filename)
           width)
          [(utils/truncate-to-width
            (style theme (timg/image-fallback mime-type
                                              :dimensions dimensions
                                              :filename filename))
            width)])))))

;; ─── Construction ──────────────────────────────────────────────────────────

(defn make-image-block
  "Create an inline image block. OPTS:
     :filename       — name shown in the text fallback
     :fallback-style — (fn [theme text] styled) applied to the fallback
                       text and to the image component's render-failure
                       fallback (pi: fallbackColor)"
  [data mime-type & {:keys [filename fallback-style]}]
  (map->ImageBlock {:data data
                    :mime-type mime-type
                    :filename filename
                    :dimensions (or (timg/get-image-dimensions data mime-type)
                                    {:width-px 800 :height-px 600})
                    :fallback-style fallback-style
                    :cache-atom (atom nil)}))
