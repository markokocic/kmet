(ns kmet.tui.components.test-select-list
  (:require [clojure.string :as str]
            [clojure.test :as t]
            [kmet.tui.core :as core]
            [kmet.tui.utils :as u]
            [kmet.tui.components.select-list :as sl]))

(def ^:const K-DOWN "\u001b[B")
(def ^:const K-UP "\u001b[A")
(def ^:const K-HOME "\u001b[H")
(def ^:const K-END "\u001b[F")
(def ^:const K-PGDN "\u001b[6$")
(def ^:const K-PGUP "\u001b[5$")
(def ^:const K-BS "\u007f")
(def ^:const K-ENTER "\r")
(def ^:const K-ESC "\u001b")

(def sample-items
  [{:label "apple" :value :apple :description "A fruit"}
   {:label "banana" :value :banana :description "Yellow fruit"}
   {:label "cherry" :value :cherry :description "Red fruit"}
   {:label "date" :value :date :description "Dried fruit"}])

;; ─── Construction ───────────────────────────────────────────────────────────

(t/deftest test-select-list-create
  (let [s (sl/make-select-list sample-items)]
    (t/is (satisfies? core/IComponent s))
    (t/is (satisfies? core/IFocusable s))
    (t/is (not (core/focused s)))))

(t/deftest test-select-list-create-empty
  (let [s (sl/make-select-list [])]
    (t/is (satisfies? core/IComponent s))))

(t/deftest test-select-list-focus
  (let [s (sl/make-select-list sample-items)]
    (core/set-focused! s true)
    (t/is (core/focused s))
    (core/set-focused! s false)
    (t/is (not (core/focused s)))))

;; ─── Selection ─────────────────────────────────────────────────────────────

(t/deftest test-select-list-initial-selection
  (let [s (sl/make-select-list sample-items)]
    (t/is (zero? @(:selected-idx-atom s)))))

(t/deftest test-select-list-navigate-down
  (let [s (sl/make-select-list sample-items)]
    (core/handle-input s K-DOWN)
    (t/is (= 1 @(:selected-idx-atom s)))
    (core/handle-input s K-DOWN)
    (t/is (= 2 @(:selected-idx-atom s)))))

(t/deftest test-select-list-navigate-up
  (let [s (sl/make-select-list sample-items)]
    (core/handle-input s K-DOWN)
    (core/handle-input s K-DOWN)
    (core/handle-input s K-UP)
    (t/is (= 1 @(:selected-idx-atom s)))))

(t/deftest test-select-list-navigate-past-end
  ;; pi: down at the bottom wraps to the top
  (let [s (sl/make-select-list sample-items)]
    (dotimes [_ 10] (core/handle-input s K-DOWN))
    (t/is (= 2 @(:selected-idx-atom s)))))

(t/deftest test-select-list-navigate-before-start
  ;; pi: up at the top wraps to the bottom
  (let [s (sl/make-select-list sample-items)]
    (core/handle-input s K-UP)
    (t/is (= 3 @(:selected-idx-atom s)))))

(t/deftest test-select-list-wrap-around
  (let [s (sl/make-select-list sample-items)]
    (core/handle-input s K-DOWN)  ;; 1
    (core/handle-input s K-DOWN)  ;; 2
    (core/handle-input s K-DOWN)  ;; 3
    (core/handle-input s K-DOWN)  ;; wraps to 0
    (t/is (zero? @(:selected-idx-atom s)))
    (core/handle-input s K-UP)    ;; wraps to 3
    (t/is (= 3 @(:selected-idx-atom s)))))

(t/deftest test-select-list-ctrl-n-wraps
  (let [s (sl/make-select-list sample-items)]
    (dotimes [_ 4] (core/handle-input s (str (char 14))))  ;; ctrl+n x4
    (t/is (zero? @(:selected-idx-atom s)) "ctrl+n wraps at the bottom")))

(t/deftest test-select-list-ctrl-p-wraps
  (let [s (sl/make-select-list sample-items)]
    (core/handle-input s (str (char 16)))  ;; ctrl+p at the top
    (t/is (= 3 @(:selected-idx-atom s)) "ctrl+p wraps at the top")))

(t/deftest test-select-list-wrap-single-item
  (let [s (sl/make-select-list [{:label "only" :value :only}])]
    (core/handle-input s K-DOWN)
    (t/is (zero? @(:selected-idx-atom s)))
    (core/handle-input s K-UP)
    (t/is (zero? @(:selected-idx-atom s)))
    (core/handle-input s K-UP)
    (t/is (zero? @(:selected-idx-atom s)))))

(t/deftest test-select-list-wrap-empty-list-no-op
  (let [s (sl/make-select-list [])]
    (core/handle-input s K-UP)
    (t/is (zero? @(:selected-idx-atom s)))
    (core/handle-input s K-DOWN)
    (t/is (zero? @(:selected-idx-atom s)))))

(t/deftest test-select-list-home
  (let [s (sl/make-select-list sample-items)]
    (core/handle-input s K-DOWN)
    (core/handle-input s K-HOME)
    (t/is (zero? @(:selected-idx-atom s)))))

(t/deftest test-select-list-end
  (let [s (sl/make-select-list sample-items)]
    (core/handle-input s K-END)
    (t/is (= 3 @(:selected-idx-atom s)))))

;; ─── Selection via ctrl+n / ctrl+p ────────────────────────────────────────

(t/deftest test-select-list-ctrl-n
  (let [s (sl/make-select-list sample-items)]
    (core/handle-input s (str (char 14)))  ;; ctrl+n
    (t/is (= 1 @(:selected-idx-atom s)))))

(t/deftest test-select-list-ctrl-p
  (let [s (sl/make-select-list sample-items)]
    (core/handle-input s K-DOWN)
    (core/handle-input s (str (char 16)))  ;; ctrl+p
    (t/is (zero? @(:selected-idx-atom s)))))

;; ─── Filtering ────────────────────────────────────────────────────────────

(t/deftest test-select-list-filter
  (let [s (sl/make-select-list sample-items)]
    (core/handle-input s "a")
    (let [filtered (->> @(:items-atom s)
                        (filter #(clojure.string/includes? (:label %) "a")))]
      (t/is (pos? (count filtered))))))

(t/deftest test-select-list-filter-backspace
  (let [s (sl/make-select-list sample-items)]
    (core/handle-input s "a")
    (core/handle-input s K-BS)
    (t/is (= "" @(:filter-atom s)))))

(t/deftest test-select-list-filter-no-match
  (let [s (sl/make-select-list sample-items)]
    (core/handle-input s "zzzz")
    (let [lines (core/render s 40)]
      (t/is (some #(.contains % "No matching commands") lines)))))

;; ─── Select ───────────────────────────────────────────────────────────────

(t/deftest test-select-list-select
  (let [s (sl/make-select-list sample-items)
        selected (atom nil)]
    (reset! (:on-select s) (fn [item] (reset! selected item)))
    (core/handle-input s K-ENTER)
    (t/is (= :apple (:value @selected)))))

(t/deftest test-select-list-select-second-item
  (let [s (sl/make-select-list sample-items)
        selected (atom nil)]
    (reset! (:on-select s) (fn [item] (reset! selected item)))
    (core/handle-input s K-DOWN)
    (core/handle-input s K-ENTER)
    (t/is (= :banana (:value @selected)))))

;; ─── Escape ───────────────────────────────────────────────────────────────

(t/deftest test-select-list-escape
  (let [s (sl/make-select-list sample-items)
        escaped (atom false)]
    (reset! (:on-escape s) (fn [] (reset! escaped true)))
    (core/handle-input s K-ESC)
    (t/is @escaped)))

;; ─── get-selected ──────────────────────────────────────────────────────────

(t/deftest test-select-list-get-selected
  (let [s (sl/make-select-list sample-items)]
    (t/is (= :apple (:value (sl/select-list-get-selected s))))
    (core/handle-input s K-DOWN)
    (t/is (= :banana (:value (sl/select-list-get-selected s))))))

;; ─── set-items ─────────────────────────────────────────────────────────────

(t/deftest test-select-list-set-items
  (let [s (sl/make-select-list sample-items)]
    (sl/select-list-set-items! s [{:label "x" :value :x}])
    (t/is (= 1 (count @(:items-atom s))))))

(t/deftest test-select-list-set-items-resets-state
  (let [s (sl/make-select-list sample-items)]
    (core/handle-input s K-DOWN)
    (core/handle-input s "a")
    (sl/select-list-set-items! s [{:label "x" :value :x}])
    (t/is (zero? @(:selected-idx-atom s)))
    (t/is (= "" @(:filter-atom s)))))

;; ─── Theme ─────────────────────────────────────────────────────────────────

(t/deftest test-select-list-default-theme
  (t/is (some? sl/default-theme)))

(t/deftest test-select-list-set-theme
  (let [s (sl/make-select-list sample-items)]
    (sl/select-list-set-theme! s (sl/map->SelectListTheme
                                  {:selected-prefix "→ "
                                   :selected-text identity
                                   :description (fn [d] (str "(" d ")"))
                                   :scroll-info identity
                                   :no-match identity}))
    (t/is (some? @(:theme-atom s)))))

;; ─── truncatePrimary callback ────────────────────────────────────────────────

(t/deftest test-select-list-truncate-primary-default
  ;; Without a callback the value is truncated to the column width (pi
  ;; default: truncateToWidth). A long label must not overflow the width.
  (let [s (sl/make-select-list [{:label (apply str (repeat 60 "x"))
                                 :value :long}]
                               :min-primary-column-width 10
                               :max-primary-column-width 10)
        line (first (core/render s 40))]
    (t/is (not (nil? line)))
    (t/is (<= (u/visible-width line) 40))))

(t/deftest test-select-list-truncate-primary-callback
  (let [calls (atom [])
        s (sl/make-select-list [{:label "apple" :value :apple}
                                {:label "banana" :value :banana}]
                               :truncate-primary
                               (fn [ctx]
                                 (swap! calls conj ctx)
                                 (str "[" (:text ctx) "]")))
        lines (core/render s 40)]
    (t/is (some? (first lines)))
    ;; callback received the context map
    (t/is (seq @calls))
    (let [ctx (first @calls)]
      (t/is (= "apple" (:text ctx)))
      (t/is (integer? (:max-width ctx)))
      (t/is (integer? (:column-width ctx)))
      (t/is (= :apple (:value (:item ctx))))
      (t/is (boolean? (:is-selected ctx))))
    ;; the callback result was used in the render
    (t/is (some #(clojure.string/includes? % "[apple]") lines))))

(t/deftest test-select-list-truncate-primary-reclamped
  ;; pi re-truncates the callback output to max-width.
  (let [s (sl/make-select-list [{:label "short" :value :s}]
                               :min-primary-column-width 6
                               :max-primary-column-width 6
                               :truncate-primary
                               (fn [ctx] (str (:text ctx) "-padding-padding")))
        line (first (core/render s 40))]
    (t/is (not (nil? line)))
    (t/is (<= (count line) 40))))

(t/deftest test-select-list-set-truncate-primary
  (let [s (sl/make-select-list sample-items)]
    (sl/select-list-set-truncate-primary! s (fn [ctx] (:text ctx)))
    (t/is (fn? @(:truncate-primary-atom s)))))

;; ─── Render ───────────────────────────────────────────────────────────────

(t/deftest test-select-list-render
  (let [s (sl/make-select-list sample-items)
        lines (core/render s 40)]
    (t/is (pos? (count lines)))
    (t/is (some #(.contains % "apple") lines))))

(t/deftest test-select-list-render-cache
  (let [s (sl/make-select-list sample-items)]
    (core/render s 40)
    (t/is (some? @(:cache-atom s)))))

;; ─── Page up / page down ──────────────────────────────────────────────────

(t/deftest test-select-list-page-down
  (let [s (sl/make-select-list sample-items :height 2)]
    (core/handle-input s K-PGDN)
    (t/is (pos? @(:selected-idx-atom s)))))

(t/deftest test-select-list-page-up
  (let [s (sl/make-select-list sample-items :height 2)]
    (core/handle-input s K-PGDN)
    (core/handle-input s K-PGUP)
    (t/is (zero? @(:selected-idx-atom s)))))

(t/deftest test-select-list-column-bounds
  ;; pi: getPrimaryColumnBounds — a single bound applies to both sides
  (let [s-min (sl/make-select-list sample-items :min-primary-column-width 10)]
    (t/is (= 10 @(:min-primary-column-atom s-min)))
    (t/is (= 10 @(:max-primary-column-atom s-min)))
    (let [s-max (sl/make-select-list sample-items :max-primary-column-width 40)]
      (t/is (= 40 @(:min-primary-column-atom s-max)))
      (t/is (= 40 @(:max-primary-column-atom s-max))))
    (let [s-both (sl/make-select-list sample-items
                                      :min-primary-column-width 12
                                      :max-primary-column-width 32)]
      (t/is (= 12 @(:min-primary-column-atom s-both)))
      (t/is (= 32 @(:max-primary-column-atom s-both))))
    (let [s-default (sl/make-select-list sample-items)]
      (t/is (= 32 @(:min-primary-column-atom s-default)))
      (t/is (= 32 @(:max-primary-column-atom s-default))))))

;; ─── onSelectionChange callback ──────────────────────────────────────────────

(t/deftest test-select-list-on-selection-change
  (let [changes (atom [])
        s (sl/make-select-list sample-items
                               :on-selection-change
                               (fn [item] (swap! changes conj (:value item))))]
    (core/handle-input s K-DOWN)
    (core/handle-input s K-DOWN)
    (t/is (= [:banana :cherry] @changes) "fires with the newly selected item")
    (core/handle-input s K-UP)
    (t/is (= [:banana :cherry :banana] @changes))
    (core/handle-input s K-UP)
    (t/is (= [:banana :cherry :banana :apple] @changes) "wrap fires with the bottom item")))

(t/deftest test-select-list-set-on-selection-change
  (let [s (sl/make-select-list sample-items)
        seen (atom nil)]
    (sl/select-list-set-on-selection-change! s (fn [item] (reset! seen (:value item))))
    (core/handle-input s K-DOWN)
    (t/is (= :banana @seen))))

;; ─── Centered viewport (pi: startIndex = selected - floor(height/2)) ────────

(t/deftest test-select-list-centered-viewport
  (let [s (sl/make-select-list sample-items :height 2)]
    (dotimes [_ 3] (core/handle-input s K-DOWN))  ;; select "date" (idx 3)
    (let [lines (core/render s 40)]
      ;; viewport shows items 2..3 (start = 3 - 1)
      (t/is (some #(.contains % "cherry") lines))
      (t/is (some #(.contains % "date") lines))
      (t/is (not-any? #(.contains % "apple") lines)))))

(t/deftest test-select-list-viewport-at-top
  (let [s (sl/make-select-list sample-items :height 2)
        lines (core/render s 40)]
    (t/is (some #(.contains % "apple") lines))
    (t/is (some #(.contains % "banana") lines))
    (t/is (not-any? #(.contains % "date") lines))))

(t/deftest test-select-list-scroll-info-shown-when-clipped
  ;; pi: scroll info shows when the viewport is clipped at either end
  (let [s (sl/make-select-list sample-items :height 2)]
    (dotimes [_ 3] (core/handle-input s K-DOWN))
    (let [lines (core/render s 40)]
      (t/is (some #(.contains % "(4/4)") lines))))
  (let [s2 (sl/make-select-list sample-items :height 5)
        lines (core/render s2 40)]
    (t/is (not-any? #(.contains % "(1/4)") lines)
          "no scroll info when everything fits")))
