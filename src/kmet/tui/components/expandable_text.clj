(ns kmet.tui.components.expandable-text
  "ExpandableText — a Text whose content switches between collapsed and
   expanded variants (pi: interactive-mode ExpandableText). Content is
   produced by (fn [] string) so a rebuild (theme change) can re-run the
   fns and regenerate pre-baked colors; set-expanded! switches the variant."
  (:require [kmet.tui.protocols :as protocols]
            [kmet.tui.components.text :as text]))

(defrecord ExpandableText [collapsed-fn expanded-fn expanded?-atom text-comp]
  protocols/IComponent
  (render [_this width] (protocols/render text-comp width))
  (handle-input [_this _data] nil)
  (invalidate [_this] (protocols/invalidate text-comp)))

(defn make-expandable-text
  "Create an ExpandableText.
   collapsed-fn / expanded-fn — (fn [] string) producing the two variants.
   Options:
     :expanded? — initial expansion state (default false)
     :padding-x / :padding-y — passed to the inner Text (default 0)"
  [collapsed-fn expanded-fn & {:keys [expanded? padding-x padding-y]
                               :or {expanded? false padding-x 0 padding-y 0}}]
  (map->ExpandableText
   {:collapsed-fn collapsed-fn
    :expanded-fn expanded-fn
    :expanded?-atom (atom (boolean expanded?))
    :text-comp (text/make-text ((if expanded? expanded-fn collapsed-fn))
                               padding-x padding-y)}))

(defn expandable-text-set-expanded!
  "Set the expansion state, switching the displayed content (pi: setExpanded)."
  [comp expanded?]
  (when (not= (boolean expanded?) @(:expanded?-atom comp))
    (reset! (:expanded?-atom comp) (boolean expanded?))
    (text/text-set! (:text-comp comp)
                    ((if expanded? (:expanded-fn comp) (:collapsed-fn comp)))))
  nil)

(defn expandable-text-get-expanded
  "Current expansion state."
  [comp]
  @(:expanded?-atom comp))

(defn expandable-text-rebuild!
  "Re-run the content fns for the current expansion state. Call when the
   strings produced by the fns may have changed (theme changes: colors are
   pre-baked into the content)."
  [comp]
  (text/text-set! (:text-comp comp)
                  ((if @(:expanded?-atom comp)
                     (:expanded-fn comp)
                     (:collapsed-fn comp))))
  nil)
