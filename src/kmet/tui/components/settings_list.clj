(ns kmet.tui.components.settings-list
  "Toggle/cycle through setting values.
   Port of @earendil-works/pi-tui SettingsList."
  (:require [kmet.tui.protocols :as protocols]
            [kmet.tui.keys :as keys]
            [kmet.tui.utils :as u]))

;; ─── Theme ──────────────────────────────────────────────────────────────────

(defrecord SettingsListTheme [label value current-value cursor])

(def default-theme
  (map->SettingsListTheme
    {:label identity
     :value (fn [v] (str "\u001b[36m" v "\u001b[0m"))
     :current-value (fn [v] (str "\u001b[1m\u001b[33m" v "\u001b[0m\u001b[22m"))
     :cursor (fn [s] (str "\u001b[7m" s "\u001b[27m"))}))

;; ─── SettingsList component ─────────────────────────────────────────────────

(defrecord SettingsList [items-atom selected-idx-atom filter-atom
                         focused? theme-atom cache-atom]
  protocols/IComponent

  (render [this width]
    (let [items @items-atom
          filter @filter-atom
          selected @selected-idx-atom
          theme @theme-atom
          cache @cache-atom]
      (if (and cache (= (:width cache) width) (= (:items cache) items)
               (= (:filter cache) filter) (= (:theme cache) theme))
        (:lines cache)
        (let [filtered (if (empty? filter)
                         items
                         (->> items
                              (filter #(let [label (:label %)]
                                        (clojure.string/includes?
                                          (clojure.string/lower-case label)
                                          (clojure.string/lower-case filter))))))
              n (count filtered)
              selected (min selected (max 0 (dec n)))
              _ (reset! selected-idx-atom selected)
              lines (volatile! [])]
          ;; Header
          (vswap! lines conj
            (str " \u001b[1mSettings"
                 (if (empty? filter) "" (str " (filter: " filter ")"))
                 "\u001b[0m"))
          ;; Items
          (doseq [[idx item] (map-indexed vector filtered)]
            (let [is-selected (= idx selected)
                  label ((:label theme) (:label item))
                  current (or (:value item) "")
                  possible (:values item)
                  is-cycling (and is-selected possible (> (count possible) 1))
                  value-str (cond
                              is-cycling
                              (str " < "
                                   (clojure.string/join " | " (map #(if (= % current)
                                                                      ((:current-value theme) (pr-str %))
                                                                      ((:value theme) (pr-str %)))
                                                                    possible))
                                   " > ")
                              :else (str "  " ((:value theme) (pr-str current))))
                  cursor (if (and is-selected @focused?) "▸ " "  ")
                  line-str (str cursor label value-str)
                  truncated (u/truncate-to-width line-str (- width 1))
                  padded (str truncated
                             (apply str (repeat (max 0 (- width (u/visible-width truncated))) \space)))]
              (vswap! lines conj padded)))
          (when (zero? n)
            (vswap! lines conj
              (str "  \u001b[2mNo matching settings\u001b[0m"
                   (apply str (repeat (max 0 (- width 35)) \space)))))
          (let [result @lines]
            (reset! cache-atom {:width width :items items :filter filter
                                :theme theme :lines result})
            result)))))

  (handle-input [this data]
    (let [items @items-atom
          filter @filter-atom
          filtered (if (empty? filter)
                     items
                     (->> items
                          (filter #(clojure.string/includes?
                                     (clojure.string/lower-case (:label %))
                                     (clojure.string/lower-case filter)))))
          n (count filtered)
          selected @selected-idx-atom]
      (cond
        ;; Escape — close
        (keys/matches-key? data "escape")
        (do (when-let [cb @(:on-escape (meta this))] (cb))
            nil)

        ;; Down / Ctrl+n
        (or (keys/matches-key? data "down")
            (keys/matches-key? data (keys/ctrl "n")))
        (do (when (pos? n) (swap! selected-idx-atom #(min (inc %) (dec n))))
            nil)

        ;; Up / Ctrl+p
        (or (keys/matches-key? data "up")
            (keys/matches-key? data (keys/ctrl "p")))
        (do (swap! selected-idx-atom #(max 0 (dec %))) nil)

        ;; Next value (right / space)
        (or (keys/matches-key? data "right")
            (keys/matches-key? data "space"))
        (do (when (and (pos? n) (< selected n))
              (let [item (nth filtered selected)
                    possible (:values item)]
                (when (and possible (seq possible))
                  (let [current (or (:value item) "")
                        cur-idx (.indexOf possible current)
                        next-idx (mod (inc (if (neg? cur-idx) -1 cur-idx))
                                      (count possible))
                        new-val (nth possible next-idx)]
                    (when-let [cb @(:on-change (meta this))]
                      (cb (:id item) new-val))
                    (swap! items-atom update-in
                      [(.indexOf items item) :value] (constantly new-val))
                    (reset! cache-atom nil)))))
            nil)

        ;; Previous value (left)
        (keys/matches-key? data "left")
        (do (when (and (pos? n) (< selected n))
              (let [item (nth filtered selected)
                    possible (:values item)]
                (when (and possible (seq possible))
                  (let [current (or (:value item) "")
                        cur-idx (.indexOf possible current)
                        prev-idx (mod (dec (if (neg? cur-idx) 0 cur-idx))
                                      (count possible))
                        new-val (nth possible prev-idx)]
                    (when-let [cb @(:on-change (meta this))]
                      (cb (:id item) new-val))
                    (swap! items-atom update-in
                      [(.indexOf items item) :value] (constantly new-val))
                    (reset! cache-atom nil)))))
            nil)

        ;; Backspace — remove filter char
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

(defn make-settings-list
  "Create a new SettingsList component.
   Each item: {:id :key :label \"Name\" :value current :values [possible ...]}
   Options: :theme (default-theme), :on-change callback"
  [items & {:keys [theme on-change]
            :or {theme default-theme}}]
  (let [sl (map->SettingsList {:items-atom (atom items)
                               :selected-idx-atom (atom 0)
                               :filter-atom (atom "")
                               :focused? (atom false)
                               :theme-atom (atom theme)
                               :cache-atom (atom nil)})]
    ;; Store callbacks in metadata
    (with-meta sl {:on-change (atom on-change)
                   :on-escape (atom nil)})))

(defn settings-list-set-on-escape! [sl f]
  (alter-meta! sl assoc :on-escape (atom f)))

;; ─── Public helpers ─────────────────────────────────────────────────────────

(defn settings-list-get-item [sl id]
  (some #(when (= (:id %) id) %) @(:items-atom sl)))

(defn settings-list-set-value! [sl id value]
  (swap! (:items-atom sl)
    (fn [items]
      (mapv (fn [item]
              (if (= (:id item) id)
                (assoc item :value value)
                item))
            items)))
  (protocols/invalidate sl))

;; ─── IFocusable ─────────────────────────────────────────────────────────────

(extend-type SettingsList
  protocols/IFocusable
  (focused [this] @(:focused? this))
  (set-focused! [this val] (reset! (:focused? this) val)))
