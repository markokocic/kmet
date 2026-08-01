(ns kmet.tui.components.test-select-list
  (:require [clojure.test :as t]
            [kmet.tui.core :as core]
            [kmet.tui.components.select-list :as sl]))

(def ^:const K-DOWN "\u001b[B")
(def ^:const K-UP "\u001b[A")
(def ^:const K-HOME "\u001b[H")
(def ^:const K-END "\u001b[F")
(def ^:const K-PGDN "\u001b[6~")
(def ^:const K-PGUP "\u001b[5~")
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
  (let [s (sl/make-select-list sample-items)]
    (dotimes [_ 10] (core/handle-input s K-DOWN))
    (t/is (= 3 @(:selected-idx-atom s)))))

(t/deftest test-select-list-navigate-before-start
  (let [s (sl/make-select-list sample-items)]
    (core/handle-input s K-UP)
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

;; ─── Render ───────────────────────────────────────────────────────────────

(t/deftest test-select-list-render
  (let [s (sl/make-select-list sample-items)]
    (let [lines (core/render s 40)]
      (t/is (pos? (count lines)))
      (t/is (some #(.contains % "apple") lines)))))

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
