(ns kmet.tui.components.select-list
  "Interactive selection list with keyboard navigation and fuzzy filtering.
   Port of @earendil-works/pi-tui SelectList — same rendering: no header,
   two-column item layout with aligned descriptions, `→ ` selected prefix,
   `  (N/M)` scroll info, `  No matching commands` empty state."
  (:require [kmet.tui.protocols :as protocols]
            [kmet.tui.keys :as keys]
            [kmet.tui.utils :as u]
            [kmet.tui.macros :refer [track!]]))

;; ─── Default theme ──────────────────────────────────────────────────────────
;; Matches pi's SelectListTheme interface.

(defrecord SelectListTheme [selected-prefix selected-text description
                            scroll-info no-match])

(def default-theme
  (map->SelectListTheme
    {:selected-prefix (fn [s] (str "\u001b[36m" s "\u001b[39m"))  ;; accent cyan
     :selected-text (fn [s] (str "\u001b[36m" s "\u001b[39m"))   ;; accent cyan
     :description (fn [s] (str "\u001b[2m" s "\u001b[22m"))
     :scroll-info (fn [s] (str "\u001b[2m" s "\u001b[22m"))
     :no-match (fn [s] (str "\u001b[31m" s "\u001b[0m"))}))

;; ─── Layout constants (pi: select-list.ts) ─────────────────────────────────

(def ^:private DEFAULT-PRIMARY-COLUMN-WIDTH 32)
(def ^:private PRIMARY-COLUMN-GAP 2)
(def ^:private MIN-DESCRIPTION-WIDTH 10)

;; ─── Fuzzy matching ─────────────────────────────────────────────────────────

(defn- fuzzy-match?
  "Check if pattern fuzzy-matches within text."
  [pattern text]
  (let [pl (count pattern) tl (count text)]
    (if (zero? pl) true
        (loop [pi 0 ti 0]
          (if (>= pi pl) true
              (if (>= ti tl) false
                  (if (= (nth pattern pi) (nth text ti))
                    (recur (inc pi) (inc ti))
                    (recur pi (inc ti)))))))))

(defn- score-match
  "Simple score: prefer matches at word boundaries and start of string."
  [pattern text]
  (if (empty? pattern) 0
      (let [n (count text)
            idx (clojure.string/index-of (clojure.string/lower-case text) (clojure.string/lower-case pattern))]
        (if (and idx (>= idx 0))
          (- n idx)  ;; Prefer matches earlier in string
          ;; Fuzzy: count contiguous/recent matches
          (let [pl (count pattern)]
            (loop [pi 0 ti 0 score 0]
              (if (or (>= pi pl) (>= ti n)) score
                  (if (= (nth pattern pi) (nth text ti))
                    (recur (inc pi) (inc ti) (+ score (- n ti)))
                    (recur pi (inc ti) score)))))))))

(defn- display-value [item]
  (or (:label item) (:value item) ""))

(defn- normalize-single-line [text]
  (clojure.string/trim (clojure.string/replace text #"[\r\n]+" " ")))

(defn- primary-column-width
  "Pi: getPrimaryColumnWidth — widest primary value + gap, clamped to the
   configured bounds (default 32)."
  [filtered min-col max-col]
  (let [widest (reduce (fn [w item] (max w (u/visible-width (display-value item))))
                       0 filtered)
        target (+ widest PRIMARY-COLUMN-GAP)
        lo (max 1 (min min-col max-col))
        hi (max 1 (max min-col max-col))]
    (max lo (min target hi))))

(defn- render-item
  "Pi: renderItem — `→ ` prefix for the selection, primary value truncated to
   the column width, description aligned in a second column when it fits."
  [item selected? width desc theme primary-width]
  (let [prefix (if selected? "→ " "  ")
        value (display-value item)
        primary-width (min primary-width (max 1 (- width 2 4)))]
    (if (and desc (> width 40))
      (let [max-primary (max 1 (- primary-width PRIMARY-COLUMN-GAP))
            truncated-value (u/truncate-to-width value max-primary)
            vw (u/visible-width truncated-value)
            spacing (apply str (repeat (max 1 (- primary-width vw)) \space))
            desc-start (+ 2 vw (count spacing))
            remaining (- width desc-start 2)]
        (if (> remaining MIN-DESCRIPTION-WIDTH)
          (let [truncated-desc (u/truncate-to-width desc remaining)]
            (if selected?
              ((:selected-text theme) (str prefix truncated-value spacing truncated-desc))
              (str prefix truncated-value
                   ((:description theme) (str spacing truncated-desc)))))
          (if selected?
            ((:selected-text theme) (str prefix truncated-value))
            (str prefix truncated-value))))
      (let [max-width (- width 2 2)
            truncated-value (u/truncate-to-width value max-width)]
        (if selected?
          ((:selected-text theme) (str prefix truncated-value))
          (str prefix truncated-value))))))

;; ─── SelectList component ───────────────────────────────────────────────────

(defrecord SelectList [items-atom selected-idx-atom filter-atom
                       on-select on-escape
                       focused? theme-atom height-atom cache-atom
                       scroll-offset-atom header-atom
                       min-primary-column-atom max-primary-column-atom]
  protocols/IComponent

  (render [this width]
    (track! this width
      (let [items @items-atom
            filter-str @filter-atom
            theme @theme-atom
            height @height-atom
            header @header-atom
            min-col @min-primary-column-atom
            max-col @max-primary-column-atom
            filtered (if (empty? filter-str)
                       items
                       (vec (->> items
                                 (clojure.core/filter #(fuzzy-match? filter-str (:label %)))
                                 (sort-by #(- (score-match filter-str (:label %)))))))
            n (count filtered)
            selected (min @selected-idx-atom (max 0 (dec n)))
            _ (reset! selected-idx-atom selected)
            ;; Adjust scroll offset
            scroll-offset @scroll-offset-atom
            new-offset (cond
                         (< selected scroll-offset) selected
                         (>= selected (+ scroll-offset height))
                         (- selected height -1)
                         :else scroll-offset)
            _ (reset! scroll-offset-atom (max 0 (min new-offset (max 0 (- n height)))))
            visible (subvec filtered scroll-offset
                            (min (+ scroll-offset height) n))
            lines (volatile! [])]
        ;; Optional header (kmet extension — pi's SelectList renders items only)
        (when (seq header)
          (vswap! lines conj (str " " (u/truncate-to-width header (- width 2)))))
        ;; Items
        (if (empty? filtered)
          (vswap! lines conj ((:no-match theme) "  No matching commands"))
          (let [col-width (primary-column-width filtered min-col max-col)]
            (doseq [[idx item] (map-indexed vector visible)]
              (let [global-idx (+ idx scroll-offset)
                    desc (when (:description item)
                           (normalize-single-line (:description item)))]
                (vswap! lines conj
                  (render-item item (= global-idx selected) width desc theme col-width))))))
        ;; Scroll info — pi: `  (N/M)` only when items overflow the visible area
        (when (and (> n (+ scroll-offset height)) (pos? n))
          (vswap! lines conj ((:scroll-info theme) (str "  (" (inc selected) "/" n ")"))))
        @lines)))

  (handle-input [this data]
    (let [items @items-atom
          filter-str @filter-atom
          filtered (if (empty? filter-str)
                     items
                     (vec (clojure.core/filter #(fuzzy-match? filter-str (:label %)) items)))
          n (count filtered)
          selected @selected-idx-atom]
      (cond
        ;; Enter — select
        (and (keys/matches-key? data "enter") (pos? n))
        (do (when-let [cb @on-select]
              (cb (nth filtered selected)))
            nil)

        ;; Escape — cancel
        (keys/matches-key? data "escape")
        (do (when-let [cb @on-escape] (cb))
            nil)

        ;; Down
        (or (keys/matches-key? data "down")
            (keys/matches-key? data (keys/ctrl "n")))
        (do (when (pos? n)
              (swap! selected-idx-atom #(min (inc %) (dec n))))
            nil)

        ;; Up
        (or (keys/matches-key? data "up")
            (keys/matches-key? data (keys/ctrl "p")))
        (do (swap! selected-idx-atom #(max 0 (dec %)))
            nil)

        ;; Page down
        (keys/matches-key? data "pageDown")
        (do (swap! selected-idx-atom #(min (+ % @height-atom) (max 0 (dec n))))
            nil)

        ;; Page up
        (keys/matches-key? data "pageUp")
        (do (swap! selected-idx-atom #(max 0 (- % @height-atom)))
            nil)

        ;; Home
        (keys/matches-key? data "home")
        (do (reset! selected-idx-atom 0) nil)

        ;; End
        (keys/matches-key? data "end")
        (do (reset! selected-idx-atom (max 0 (dec n))) nil)

        ;; Backspace — remove last filter char
        (or (keys/matches-key? data "backspace")
            (keys/matches-key? data (keys/ctrl "h")))
        (do (swap! filter-atom #(subs % 0 (max 0 (dec (count %)))))
            (reset! selected-idx-atom 0)
            nil)

        ;; Regular character — add to filter
        :else
        (let [has-ctrl? (some #(let [c (int %)]
                                 (or (< c 32) (== c 127)
                                     (and (>= c 128) (<= c 159))))
                              data)]
          (when-not has-ctrl?
            (swap! filter-atom str data)
            (reset! selected-idx-atom 0)
            nil)))))

  (invalidate [this]
    (reset! (:cache-atom this) nil)))

;; ─── Construction ──────────────────────────────────────────────────────────

(defn make-select-list
  "Create a new SelectList component.
   Items are maps with :label, optional :value and :description.
   Options:
     :height                  — max visible items (default 10)
     :theme                   — SelectListTheme map (default default-theme)
     :header                  — optional title line above the items
     :min-primary-column-width / :max-primary-column-width — description
                               column bounds (pi defaults: 32)"
  [items & {:keys [height theme header
                   min-primary-column-width max-primary-column-width
                   on-select on-escape]
            :or {height 10 theme default-theme
                 min-primary-column-width DEFAULT-PRIMARY-COLUMN-WIDTH
                 max-primary-column-width DEFAULT-PRIMARY-COLUMN-WIDTH}}]
  (map->SelectList {:items-atom (atom items)
                    :selected-idx-atom (atom 0)
                    :filter-atom (atom "")
                    :on-select (atom on-select)
                    :on-escape (atom on-escape)
                    :focused? (atom false)
                    :theme-atom (atom theme)
                    :height-atom (atom height)
                    :cache-atom (atom nil)
                    :scroll-offset-atom (atom 0)
                    :header-atom (atom header)
                    :min-primary-column-atom (atom min-primary-column-width)
                    :max-primary-column-atom (atom max-primary-column-width)}))

;; ─── Public helpers ─────────────────────────────────────────────────────────

(defn select-list-set-items! [sl items]
  (reset! (:items-atom sl) items)
  (reset! (:selected-idx-atom sl) 0)
  (reset! (:filter-atom sl) "")
  (reset! (:scroll-offset-atom sl) 0))

(defn select-list-get-selected [sl]
  (let [items @(:items-atom sl)
        filter-str @(:filter-atom sl)
        filtered (if (empty? filter-str)
                   items
                   (vec (clojure.core/filter #(fuzzy-match? filter-str (:label %)) items)))
        idx @(:selected-idx-atom sl)]
    (when (and (seq filtered) (>= idx 0) (< idx (count filtered)))
      (nth filtered idx))))

(defn select-list-set-theme! [sl theme]
  (reset! (:theme-atom sl) theme))

;; ─── IFocusable ─────────────────────────────────────────────────────────────

(extend-type SelectList
  protocols/IFocusable
  (focused [this] @(:focused? this))
  (set-focused! [this val] (reset! (:focused? this) val)))
