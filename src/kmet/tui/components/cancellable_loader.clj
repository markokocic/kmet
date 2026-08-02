(ns kmet.tui.components.cancellable-loader
  "CancellableLoader — a Loader (kmet tui.components.spinner/Spinner) that
   can be cancelled with Escape, exposing an abort flag so async operations
   can stop early (pi: components/cancellable-loader.ts). Renders exactly
   like the wrapped spinner — a leading blank line above the animated line,
   matching pi Loader's leading-blank-line shape (pi: CancellableLoader
   extends Loader). handle-input aborts when the input matches the global
   'tui.select.cancel' keybinding (default Escape/Ctrl+C) and invokes the
   on-abort callback. Async code polls cancellable-loader-aborted? or
   watches the signal atom."
  (:require [kmet.tui.protocols :as protocols]
            [kmet.tui.keybindings :as kb]
            [kmet.tui.components.spinner :as spinner]))

(defrecord CancellableLoader [spinner abort-signal-atom on-abort-fn-atom]
  protocols/IComponent
  (render [this width]
    (protocols/render (:spinner this) width))
  (handle-input [this data]
    (when (kb/matches-key (kb/get-global-keybindings) data "tui.select.cancel")
      (reset! (:abort-signal-atom this) true)
      (when-let [f @(:on-abort-fn-atom this)]
        (f)))
    nil)
  (invalidate [this]
    (protocols/invalidate (:spinner this))))

;; ─── Construction & API ────────────────────────────────────────────────────

(defn make-cancellable-loader
  "Create a CancellableLoader wrapping a Spinner component.
   Options:
     :spinner  — the kmet.tui.components.spinner/Spinner to display
     :on-abort — callback invoked when the user cancels (default nil)"
  [& {:keys [spinner on-abort]}]
  (map->CancellableLoader {:spinner spinner
                           :abort-signal-atom (atom false)
                           :on-abort-fn-atom (atom on-abort)}))

(defn cancellable-loader-aborted?
  "True once the loader was cancelled by the user (pi: aborted)."
  [this]
  @(:abort-signal-atom this))

(defn cancellable-loader-signal
  "The abort signal atom — async code watches it (add-watch) or polls it."
  [this]
  (:abort-signal-atom this))

(defn cancellable-loader-set-on-abort!
  "Set the callback invoked when the user cancels (pi: loader.onAbort = fn)."
  [this f]
  (reset! (:on-abort-fn-atom this) f))

(defn cancellable-loader-dispose!
  "Stop the underlying spinner (pi: dispose → Loader.stop)."
  [this]
  (spinner/spinner-stop! (:spinner this)))
