(ns kmet.tui.core
  "TUI - Main class for managing terminal UI with differential rendering."
  (:require [kmet.tui.terminal :as t]))

(defprotocol IComponent
  (render [this width] "Render component to lines (seq of strings)")
  (handle-input [this data] "Handle keyboard input")
  (invalidate [this] "Clear cached render state"))

(defprotocol IFocusable
  (focused [this])
  (set-focused! [this val]))

;; ─── Container ──────────────────────────────────────────────────────────────

(defrecord Container [children]
  IComponent
  (render [this width] (mapcat #(render % width) @children))
  (handle-input [this data] (some #(handle-input % data) @children))
  (invalidate [this] (doseq [c @children] (invalidate c))))

(defn make-container
  ([] (map->Container {:children (atom [])}))
  ([children] (map->Container {:children (atom (vec children))})))

(defn container-add-child [c child] (swap! (:children c) conj child))
(defn container-remove-child [c child]
  (swap! (:children c) (fn [v] (vec (remove #(identical? % child) v)))))
(defn container-clear [c] (reset! (:children c) []))

;; ─── Overlay ────────────────────────────────────────────────────────────────

(defrecord Overlay [component x y width height focused?])

;; ─── CSI 2026 sync ─────────────────────────────────────────────────────────

(def CSI-2026-H "\u001b[?2026h")
(def CSI-2026-L "\u001b[?2026l")

;; ─── TUI ────────────────────────────────────────────────────────────────────

(defrecord TUI [terminal components focused-component
                input-listeners previous-lines
                previous-width render-requested?
                running? stopped? overlays])

(defn create-tui [terminal]
  (map->TUI {:terminal terminal
             :components (atom [])
             :focused-component (atom nil)
             :input-listeners (atom [])
             :previous-lines (atom [])
             :previous-width (atom 0)
             :render-requested? (atom false)
             :running? (atom false)
             :stopped? (atom false)
             :overlays (atom [])}))

(defn tui-add-child [tui c] (swap! (:components tui) conj c))
(defn tui-remove-child [tui c]
  (swap! (:components tui) (fn [v] (vec (remove #(identical? % c) v)))))
(defn tui-clear [tui] (reset! (:components tui) []))

(defn tui-set-focus [tui component]
  (when-let [prev @(:focused-component tui)]
    (when (satisfies? IFocusable prev) (set-focused! prev false)))
  (reset! (:focused-component tui) component)
  (when (satisfies? IFocusable component) (set-focused! component true))
  nil)

(defn tui-add-input-listener [tui f]
  (swap! (:input-listeners tui) conj f))
(defn tui-remove-input-listener [tui f]
  (swap! (:input-listeners tui) (fn [v] (vec (remove #(= % f) v)))))

;; ─── Overlays ───────────────────────────────────────────────────────────────

(defn tui-show-overlay [tui component & {:keys [x y width height]}]
  (let [o (map->Overlay {:component component :x x :y y
                          :width width :height height :focused? true})]
    (swap! (:overlays tui) conj o)
    (tui-set-focus tui component)
    o))

(defn tui-hide-overlay [tui]
  (when-let [o (peek @(:overlays tui))]
    (swap! (:overlays tui) pop)
    (when (:focused? o)
      (if-let [next-o (:component (peek @(:overlays tui)))]
        (tui-set-focus tui next-o)
        (when-let [last (last @(:components tui))]
          (tui-set-focus tui last))))))

(defn tui-has-overlay? [tui] (pos? (count @(:overlays tui))))

(declare tui-request-render tui-stop)

;; ─── Diff ──────────────────────────────────────────────────────────────────

(defn- diff-lines [prev next]
  (let [n (max (count prev) (count next))]
    (loop [i 0, r []]
      (if (>= i n) r
          (let [a (get prev i "") b (get next i "")]
            (if (= a b) (recur (inc i) r)
                (recur (inc i) (conj r (str "\u001b[" (inc i) "H\u001b[2K" b)))))))))

;; ─── Input reader ──────────────────────────────────────────────────────────

(defn- start-input-reader [tui]
  (let [jline (.terminal (:terminal tui))]
    (future
      (let [reader (.reader jline)]
        (while (and @(:running? tui) (not @(:stopped? tui)))
          (try (let [ch (.read reader)]
                 (when (>= ch 0)
                   (let [data (str (char ch))]
                     (doseq [l @(:input-listeners tui)] (l data))
                     (if-let [fc @(:focused-component tui)]
                       (handle-input fc data)
                       (when-let [c (first @(:components tui))]
                         (handle-input c data)))
                     (tui-request-render tui))))
               (catch Exception e
                 (when @(:running? tui)
                   (binding [*out* *err*] (println "input:" (.getMessage e)))))))))))

;; ─── Start / Stop ──────────────────────────────────────────────────────────

(defn tui-request-render [tui]
  (reset! (:render-requested? tui) true))

(defn tui-stop [tui]
  (reset! (:stopped? tui) true)
  (reset! (:running? tui) false))

(defn tui-start
  "Start TUI render loop. Blocks the calling thread."
  [tui]
  (let [term (:terminal tui)
        jline (.terminal term)]
    (t/start! term (fn [_] nil) (fn [] (tui-request-render tui)))
    (t/hide-cursor! term)
    (t/write-output term "\u001b[2J\u001b[H")
    (reset! (:running? tui) true)
    (reset! (:stopped? tui) false)
    (start-input-reader tui)
    (loop []
      (when @(:running? tui)
        (let [w (.getWidth jline)
              h (.getHeight jline)]
          (when @(:render-requested? tui)
            (reset! (:render-requested? tui) false)
            (let [lines (vec (mapcat #(render % w) @(:components tui)))
                  prev @(:previous-lines tui)]
              (when (not= prev lines)
                (let [d (diff-lines prev lines)]
                  (when (seq d)
                    (t/write-output term CSI-2026-H)
                    (doseq [x d] (t/write-output term x))
                    (t/write-output term CSI-2026-L)
                    (let [cr (min (count lines) (dec h))]
                      (t/write-output term (str "\u001b[" (max 1 (inc cr)) "H")))))
                (reset! (:previous-lines tui) lines)
                (reset! (:previous-width tui) w)))))
        (Thread/sleep 33)
        (recur)))
    (reset! (:running? tui) false)
    (t/show-cursor! term)
    (t/stop! term)))
