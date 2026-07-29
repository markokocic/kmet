(ns kmet.tui.macros
  "Cache helper for TUI components.
   Eliminates the repetitive ~12-line cache pattern in every component.
   Uses a runtime function (with-cache) instead of a macro to avoid SCI
   macro expansion issues.")

(defn with-cache
  "Cache wrapper for component render methods.
   
   Usage:
     (render [this width]
       (with-cache this width {:text text :theme theme :output-pad output-pad}
         (fn []
           ;; Pure rendering logic — no cache boilerplate
           (vec ...))))

   Compares CACHED state map against current state-vals. On match,
   returns cached lines. Otherwise calls RENDER-FN, caches result, returns it.
   Automatically uses :cache-atom field on the component record."
  [component width state-vals render-fn]
  (let [cache @(:cache-atom component)
        state (zipmap (cons :width (keys state-vals)) (cons width (vals state-vals)))]
    (if (and cache (= (:state cache) state))
      (:lines cache)
      (let [result (render-fn)]
        (reset! (:cache-atom component) {:state state :lines result})
        result))))

(defn invalidate-cache
  "Invalidate a component's cache. Call from your invalidate method.
   Equivalent to (reset! (:cache-atom component) nil)."
  [component]
  (reset! (:cache-atom component) nil))
