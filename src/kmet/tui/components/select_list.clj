(ns kmet.tui.components.select-list
  "Interactive selection list with keyboard navigation and fuzzy filtering.
   Port of @earendil-works/pi-tui SelectList."
  (:require [kmet.tui.protocols :as protocols]
            [kmet.tui.keys :as keys]
            [kmet.tui.utils :as u]))

;; ─── Default theme ──────────────────────────────────────────────────────────

(defrecord SelectListTheme [selected-prefix selected-text description
                            scroll-info no-match])

(def default-theme
  (map->SelectListTheme
    {:selected-prefix "▸ "
     :selected-text (fn [s] (str "\u001b[1m" s "\u001b[22m"))
     :description (fn [s] (str "\u001b[2m" s "\u001b[22m"))
     :scroll-info (fn [s] (str "\u001b[2m" s "\u001b[22m"))
     :no-match (fn [s] (str "\u001b[31m" s "\u001b[0m"))}))

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
        (if (>= idx 0)
          (- n idx)  ;; Prefer matches earlier in string
          ;; Fuzzy: count contiguous/recent matches
          (let [pl (count pattern)]
            (loop [pi 0 ti 0 score 0]
              (if (or (>= pi pl) (>= ti n)) score
                  (if (= (nth pattern pi) (nth text ti))
                    (recur (inc pi) (inc ti) (+ score (- n ti)))
                    (recur pi (inc ti) score)))))))))

;; ─── SelectList component ───────────────────────────────────────────────────

(defrecord SelectList [items-atom selected-idx-atom filter-atom
                       on-select on-escape
                       focused? theme-atom height-atom cache-atom
                       scroll-offset-atom]
  protocols/IComponent

  (render [this width]
    (let [items @items-atom
          filter-str @filter-atom
          theme @theme-atom
          height @height-atom
          cache @cache-atom]
      (if (and cache (= (:width cache) width) (= (:items cache) items)
               (= (:filter cache) filter-str) (= (:theme cache) theme))
        (:lines cache)
        (let [filtered (if (empty? filter-str)
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
          ;; Header
          (vswap! lines conj
            (str " \u001b[1m"
                 (if (empty? filter-str) "Select:" (str "Filter: " filter-str))
                 "\u001b[0m"))
          ;; Items
          (if (empty? filtered)
            (vswap! lines conj
              (str "  " ((:no-match theme) (str "No matches for \"" filter "\""))))
            (doseq [[idx item] (map-indexed vector visible)]
              (let [global-idx (+ idx scroll-offset)
                    prefix (if (= global-idx selected)
                             (:selected-prefix theme) "  ")
                    label ((:selected-text theme) (:label item))
                    desc (when (:description item)
                           (let [d ((:description theme) (:description item))]
                             (str "  " d)))
                    line-width (- width 2)
                    truncated (u/truncate-to-width
                                (str prefix (if (= global-idx selected)
                                              (str "\u001b[7m" label "\u001b[27m")
                                              label)
                                     (or desc ""))
                                line-width)
                    padded (str truncated
                               (apply str (repeat (max 0 (- width (u/visible-width truncated))) \space)))]
                (vswap! lines conj padded))))
          ;; Scroll indicator
          (when (< n (+ scroll-offset height))
            (when (pos? n)
              (let [info (str "Showing " (count visible) " of " n " items")
                    scroll-line ((:scroll-info theme) info)]
                (vswap! lines conj
                  (str scroll-line
                       (apply str (repeat (max 0 (- width (u/visible-width scroll-line))) \space)))))))
          (let [result @lines]
            (reset! cache-atom {:width width :items items :filter filter
                                :theme theme :lines result})
            result)))))

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
   Options: :height (default 10), :theme (default-theme)"
  [items & {:keys [height theme on-select on-escape]
            :or {height 10 theme default-theme}}]
  (map->SelectList {:items-atom (atom items)
                    :selected-idx-atom (atom 0)
                    :filter-atom (atom "")
                    :on-select (atom on-select)
                    :on-escape (atom on-escape)
                    :focused? (atom false)
                    :theme-atom (atom theme)
                    :height-atom (atom height)
                    :cache-atom (atom nil)
                    :scroll-offset-atom (atom 0)}))

;; ─── Public helpers ─────────────────────────────────────────────────────────

(defn select-list-set-items! [sl items]
  (reset! (:items-atom sl) items)
  (reset! (:selected-idx-atom sl) 0)
  (reset! (:filter-atom sl) "")
  (reset! (:scroll-offset-atom sl) 0)
  (protocols/invalidate sl))

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
  (reset! (:theme-atom sl) theme)
  (protocols/invalidate sl))

;; ─── IFocusable ─────────────────────────────────────────────────────────────

(extend-type SelectList
  protocols/IFocusable
  (focused [this] @(:focused? this))
  (set-focused! [this val] (reset! (:focused? this) val)))
