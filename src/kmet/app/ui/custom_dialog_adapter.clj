(ns kmet.app.ui.custom-dialog-adapter
  "Adapter mounting foreign dialog implementations (pi: ctx.ui.custom's
   Component / duck-typed object) into kmet's TUI surfaces.

   Extension factories may return a plain {:render :handle-input
   :invalidate :dispose} map or a hiccup element tree; ui-custom wraps
   either in a CustomDialogAdapter so the dock/widget strips hold a
   defcomponent RECORD. Records splice into hiccup trees by record? —
   no protocol-identity checks involved. That matters because bb's
   satisfies? can return false for reified components created in other
   evaluation contexts even though method dispatch works on them (the
   documented SCI gotcha), so a reify reaching reconcile as a bare tree
   node crashes the render loop instead of splicing.

   The adapter is a transparent delegate over closures it does not own:
   nothing to cache (the wrapped component manages its own state), and
   its lifetime stays with ui-custom's dialog bookkeeping — trees never
   dispose it (foreign splice contract).

   dispose-component! here is THE disposal entry point for values of any
   shape (records, reifies, duck-typed maps) — shared by the dialog
   bookkeeping and the tool-execution renderer children."
  (:require [kmet.tui.macros :refer [defcomponent]]
            [kmet.tui.protocols :as protocols]))

(defn dispose-component!
  "Dispose a component of ANY shape — a defcomponent record, a reify, or a
   duck-typed {:render … :dispose …} map. A :dispose key wins (duck-typed
   maps and records carrying one); otherwise the protocol's multimethod,
   which dispatches correctly under SCI even where satisfies? lies (bb reify
   limitation). Exceptions are swallowed — disposal is cleanup, and a broken
   foreign component must not take the frame down. Idempotent by convention;
   nil is a no-op."
  [component]
  (when (some? component)
    (if-let [dispose (:dispose component)]
      (try (dispose) (catch Exception _))
      (try (protocols/dispose component) (catch Exception _)))))

(defcomponent CustomDialogAdapter nil [render-fn handle-input-fn
                                       invalidate-fn dispose-fn]
  (render [_this width]
    (when render-fn (render-fn width)))
  (handle-input [_this data]
    (when handle-input-fn (handle-input-fn data)))
  (invalidate [_this]
    (when invalidate-fn (invalidate-fn)))
  (dispose [_this]
    ;; idempotent by convention: ui-custom clears its reference after
    ;; disposing, and double-close guards live in the close closure
    (when dispose-fn (dispose-fn))))
