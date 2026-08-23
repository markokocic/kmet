(ns kmet.tui.components.alt-screen-flash
  "AltScreenFlashContainer — transient messages composited by the host
   renderer (pi: components/alt-screen-flash.ts). Each flash renders as a
   single inverse-video line (message padded with one space each side,
   truncated ANSI-aware to the viewport width). Entries auto-expire after
   their duration and the host re-renders. The render loop composites flash
   lines over the screen window (right-aligned at the bottom, pi:
   compositeFlashes); the container itself just holds and expires entries."
  (:require
   [kmet.tui.macros :refer [defcomponent]]
   [kmet.tui.utils :as u]
   [kmet.tui.theme :as theme]))

(def ^:private DEFAULT-DURATION-MS 1000)

(declare alt-screen-flash-dispose!)

(defcomponent AltScreenFlashContainer nil [request-render-fn entries-atom next-id-atom]
  (render [_this width]
    (mapv (fn [entry]
            (let [message (u/truncate-to-width (str " " (:message entry) " ") width "")]
              (theme/inverse message)))
          @entries-atom))
  (dispose [this] (alt-screen-flash-dispose! this)))

(defn- request-render!
  "Call the host's re-render callback when present."
  [this]
  (when-let [f (:request-render-fn this)]
    (f)))

;; ─── Construction & API ────────────────────────────────────────────────────

(defn make-alt-screen-flash
  "Create an AltScreenFlashContainer. REQUEST-RENDER is called whenever a
   flash appears or expires so the host can repaint."
  [request-render]
  (map->AltScreenFlashContainer {:request-render-fn request-render
                                 :entries-atom (atom [])
                                 :next-id-atom (atom 0)}))

(defn alt-screen-flash!
  "Show a transient inverse-video message for DURATION-MS (default 1000),
   then remove it and request a re-render (pi: flash)."
  [this message & {:keys [duration-ms]}]
  (let [duration-ms (or duration-ms DEFAULT-DURATION-MS)
        id (swap! (:next-id-atom this) inc)
        disposed? (atom false)
        timer (future
                (try
                  (Thread/sleep (max 0 duration-ms))
                  (when-not @disposed?
                    (swap! (:entries-atom this)
                           (fn [es] (vec (remove #(= id (:id %)) es))))
                    (request-render! this))
                  (catch InterruptedException _)))]
    (swap! (:entries-atom this) conj {:id id :message message :timer timer})
    (request-render! this)
    nil))

(defn alt-screen-flash-dispose!
  "Clear all pending flashes immediately (pi: dispose)."
  [this]
  (doseq [{:keys [timer]} @(:entries-atom this)]
    (future-cancel timer))
  (reset! (:entries-atom this) [])
  (request-render! this)
  nil)
