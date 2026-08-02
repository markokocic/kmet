(ns kmet.tui.components.truncated-text
  "TruncatedText — single-line text that truncates to the viewport width
   (pi: components/truncated-text.ts). Shows only the first line of TEXT
   (stopping at the first newline), truncated ANSI-aware to the available
   width (width minus 2×padding-x) with an ellipsis, and padded to exactly
   WIDTH columns. Empty lines from :padding-y vertical padding are added
   above and below. Empty text still renders the padded line (pi parity)."
  (:require [clojure.string :as str]
            [kmet.tui.utils :as u]
            [kmet.tui.macros :refer [defcomponent track!]]))

(defcomponent TruncatedText nil [text-atom padding-x-atom padding-y-atom cache]
  (render [this width]
    (track! this width
      (let [text @text-atom
            empty-line (apply str (repeat width \space))
            pad-y @padding-y-atom
            pad-x @padding-x-atom
            available (max 1 (- width (* 2 pad-x)))
            single-line (or (first (str/split-lines text)) "")
            ;; pi: truncateToWidth default ellipsis "..."
            display (u/truncate-to-width single-line available "...")
            left (apply str (repeat pad-x \space))
            right (apply str (repeat pad-x \space))
            line (str left display right)
            need (max 0 (- width (u/visible-width line)))
            line (str line (apply str (repeat need \space)))]
        (into [] (concat (repeat pad-y empty-line)
                         [line]
                         (repeat pad-y empty-line))))))
  (invalidate [this]
    (reset! (:cache this) nil)))

;; ─── Construction & API ────────────────────────────────────────────────────

(defn make-truncated-text
  "Create a TruncatedText.
   TEXT   — the text to display (only its first line is shown)
   Options:
     :padding-x — horizontal padding columns (default 0)
     :padding-y — vertical padding lines (default 0)"
  [text & {:keys [padding-x padding-y] :or {padding-x 0 padding-y 0}}]
  (map->TruncatedText {:text-atom (atom text)
                       :padding-x-atom (atom padding-x)
                       :padding-y-atom (atom padding-y)
                       :cache (atom nil)}))

(defn truncated-text-set-text! [tt text]
  (reset! (:text-atom tt) text))
