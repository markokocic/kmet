(ns kmet.app.ui.tool-execution
  "ToolExecutionComponent component — Pi's ToolExecutionComponent.
   Uses a Box (with status background) wrapping a Container that holds
   the call-render and result-render children.
   Matching Pi architecture: Box handles padding/background/caching.
   Timing is managed internally (started-at on first content, ended-at on error/finalize)."
  (:require [kmet.app.ui.subs :as s]
            [kmet.tui.protocols :as protocols]
            [kmet.tui.theme :as theme]
            [kmet.tui.timers :as timers]
            [kmet.tui.components.box :as box]
            [kmet.tui.components.container :as container]
            [kmet.app.ui.tool-renderers :as renderers]
            [kmet.app.ui.custom-dialog-adapter :as cda]
            [kmet.app.ui.image-block :as image-block]
            [kmet.tui.components.spacer :as spacer]
            [kmet.tui.macros :refer [track! defcomponent]]))

;; ─── Renderer dispatch ─────────────────────────────────────────────────────
;; Built-in renderer functions live in kmet.app.ui.tool-renderers so supported
;; extensions can reuse them directly. One data table keyed by tool name holds
;; every built-in per-tool fact (:call / :result renderers, :shell mode); the
;; render method resolves custom override → builtin entry → default through a
;; single path instead of per-field case ladders.

(def ^:private builtin-renderers
  {"read"  {:call renderers/render-read-call
            :result renderers/render-read-result}
   "write" {:call renderers/render-write-call
            :result renderers/render-write-result}
   "edit"  {:call renderers/render-edit-call
            :result renderers/render-edit-result
            ;; pi: edit renders its own diff framing, no outer Box
            :shell :self}
   "bash"  {:call renderers/render-bash-call
            :result renderers/render-bash-result}})

;; ─── Render context helper ─────────────────────────────────────────────────

(defn- last-call-component
  "Read the previous render-call component WITHOUT tracking (the render body
   resets this atom on every cache miss; a tracked read would self-invalidate
   the track! render cache and re-render every frame)."
  [comp]
  @(:last-call-component-atom comp))

(defn- last-result-component
  "Read the previous render-result component WITHOUT tracking (see
   last-call-component)."
  [comp]
  @(:last-result-component-atom comp))

(defn- last-image-children
  "Read the previous pass's image children WITHOUT tracking (see
   last-call-component): the render body replaces this atom on every cache
   miss, so a tracked read could never equal the stored value — the cache
   would miss every frame and the image children would be rebuilt (new kitty
   image ids, new allocations) on every render."
  [comp]
  @(:image-children-atom comp))

(defn- tool-execution-context
  "Build a ToolRenderContext map for the given component and last-component.
   SHOW-IMAGES is whether images render (the :show-images setting AND
   terminal support — pi: ToolRenderContext showImages, made effective).

   :last-component is the previous pass's renderer output. A renderer that
   keeps its own instance returns it back to REUSE it (the identical
   instance is left alone); anything else the renderer returns replaces the
   old output, which is then disposed — the same drop-disposes contract as
   every other component in the tree (tui.md §5.1) — so a renderer holding
   a dropped component across passes must not expect it to stay live."
  [comp last-comp show-images]
  {:args @(:args-atom comp)
   :tool-call-id @(:tool-call-id-atom comp)
   ;; invalidation schedules the frame itself (§3.4 hook) — extension
                 ;; renderers need no injected render callback
   :invalidate (fn [] (protocols/invalidate comp))
   :last-component last-comp
   :state @(:renderer-state-atom comp)
   :set-state! (fn [new-state]
                 (reset! (:renderer-state-atom comp) new-state))
   :cwd @(:cwd-atom comp)
   :execution-started (some? @(:started-at-atom comp))
   :args-complete @(:args-complete-atom comp)
   :details @(:details-atom comp)
   :is-partial (nil? @(:ended-at-atom comp))
   :expanded (or @(:expanded-atom comp)
                 (when-some [shared (:tools-expanded-atom comp)] @shared))
   :show-images show-images
   :is-error @(:is-error-atom comp)})

;; ─── Record ────────────────────────────────────────────────────────────────
;; Pi matching: ToolExecutionComponent manages its own timing.
;; started-at is set on first set-content! call (execution start).
;; ended-at is set on set-error! or on final full-content set-content!.

(defcomponent ToolExecutionComponent :tool
              [name-atom args-atom content-atom is-error-atom
               output-pad-atom expanded-atom
               tools-expanded-atom ;; chat-history-wide toggle atom, or nil (unlinked)
               custom-render-call-atom custom-render-result-atom
               started-at-atom ended-at-atom
               truncation-atom tool-call-id-atom
               details-atom        ;; result :details map (pi: result.details), e.g. edit diff
               args-complete-atom
               render-shell-atom   ;; pi: ToolDefinition.renderShell — :self renders without the outer Box
               image-data-atom       ;; vector of {:data str :mime-type str}
               image-children-atom   ;; the previous pass's image children (disposed on rebuild)
               last-call-component-atom   ;; component from previous render-call
               last-result-component-atom ;; component from previous render-result
               renderer-state-atom        ;; persistent state for custom renderers
               cwd-atom                ;; current working directory
               box             ;; outer Box (padding + bg)
               inner-container ;; Container for call/result children
               cache-atom]     ;; render cache (track!)
  (render [this width]
    (track! this width
      (let [;; tracked read of the shared palette sub: a theme switch
            ;; re-derives this cache exactly once (Stage 5, dsl.md §3.2)
            theme (deref s/theme-sub)
            ;; tracked read of the shared image settings: a /settings change
            ;; (show-images / image-width-cells) re-renders every tool box
            image-settings (deref s/image-settings-sub)
            show-images? (image-block/images-enabled? image-settings)
            is-error @is-error-atom
            output-pad @output-pad-atom
            name @name-atom
            args @args-atom
            content @content-atom
            expanded? (or @expanded-atom
                          ;; chat-history-wide toggle — read lexically so
                          ;; track! records it (tui.md §4 track-deps rule)
                          (when tools-expanded-atom @tools-expanded-atom))
            started-at @started-at-atom
            ended-at @ended-at-atom
      ;; Re-check empty — only when no call component rendered and no result
            builtin (get builtin-renderers name)
            render-call-fn (or @custom-render-call-atom
                               (:call builtin)
                               renderers/render-default-call)
            render-result-fn (or @custom-render-result-atom
                                 (:result builtin)
                                 renderers/render-default-result)
            render-shell (or @render-shell-atom (:shell builtin) :default)
            container @inner-container
            content-width (max 1 (- width (* 2 output-pad)))
            ;; Previous pass's renderer outputs: passed to the renderers as
            ;; :last-component so an extension renderer may reuse its own
            ;; instance, and disposed below unless the renderer returned the
            ;; same one back (renderers may return IComponent or nil).
            prev-call (last-call-component this)
            call-context (tool-execution-context this prev-call show-images?)
            call-comp (render-call-fn name args theme content-width call-context)
            _ (reset! last-call-component-atom call-comp)
            truncation @truncation-atom
            prev-result (last-result-component this)
            result-context (tool-execution-context this prev-result show-images?)
            result-comp (render-result-fn content is-error theme content-width expanded? started-at ended-at truncation result-context)
            _ (reset! last-result-component-atom result-comp)
            image-data @image-data-atom
            prev-image-children (last-image-children this)
            obsolete (into []
                           (remove #(or (identical? % call-comp)
                                        (identical? % result-comp)))
                           [prev-call prev-result])]
      ;; Pi: hide component when no call/render content and no images
        (if (and (nil? call-comp) (nil? result-comp) (not (seq image-data)))
          (do
            ;; nothing renders — drop the dropped children (a stale child
            ;; would keep its track! watches alive; the renderers may return
            ;; duck-typed maps, so disposal goes through dispose-component!)
            (doseq [c obsolete]
              (cda/dispose-component! c))
            (doseq [c prev-image-children]
              (cda/dispose-component! c))
            (reset! image-children-atom [])
            (container/container-clear container)
            [])
          (do
          ;; Build inner container
            (container/container-clear container)
            (doseq [c obsolete]
              (cda/dispose-component! c))
            (when call-comp
              (container/container-add-child container call-comp))
            (when result-comp
              (container/container-add-child container result-comp))
          ;; Build image components from raw data (Pi: spacer + ImageComponent).
          ;; image-block renders the terminal image or, when display is off /
          ;; unsupported, the styled text indicator (pi: getTextOutput).
          ;; The previous pass's image children are disposed with the other
          ;; dropped children: an ImageBlock subscribes to the
          ;; image-settings/theme subs, so a dropped instance would keep its
          ;; track! watches alive forever (zombie watchers, tui.md §5.1).
            (let [children (into []
                                 (mapcat (fn [img]
                                           [(spacer/make-spacer 1)
                                            (image-block/make-image-block
                                             (:data img) (:mime-type img)
                                             :fallback-style (fn [thm s]
                                                               (theme/fg thm :tool-output s)))]))
                                 image-data)]
              (doseq [c prev-image-children]
                (cda/dispose-component! c))
              (reset! image-children-atom children)
              (doseq [c children]
                (container/container-add-child container c)))
          ;; Pi: render-shell :self skips outer Box (tool renders its own framing)
            (if (= :self render-shell)
              (let [content-lines (protocols/render container width)]
                (if (seq content-lines)
                  (into [""] content-lines)
                  []))
              (let [bg-key (cond
                           ;; Pi: isPartial=true until result arrives; ended-at=nil = pending
                             (nil? ended-at) :tool-pending-bg
                             is-error :tool-error-bg
                             :else :tool-success-bg)
                    _ (box/box-set-bg-fn @box #(theme/bg theme bg-key %))
                    box-lines (protocols/render @box width)]
                (if (seq box-lines)
                  (into [""] box-lines)
                  []))))))))
  (invalidate [_this]
    (protocols/invalidate @box))
  (dispose [_this]
    ;; Idempotent: cancel the running-tool repaint timer (§6.1) — a
    ;; component dropped from the chat (e.g. /new while a tool runs) must
    ;; not keep a zombie tick invalidating forever. swap-vals! makes the
    ;; read+remove atomic: a render racing dispose cannot re-arm a timer
    ;; between the deref and the dissoc. timers/cancel! is idempotent.
    (let [[state] (swap-vals! (:renderer-state-atom _this) dissoc :timer-id)]
      (when-let [id (:timer-id state)]
        (timers/cancel! id)))
    (protocols/dispose @box)
    ;; image children still outside the container (the hide path cleared it)
    ;; — disposal is idempotent, so the container-owned ones are harmless to
    ;; touch again
    (doseq [c @(:image-children-atom _this)]
      (cda/dispose-component! c))
    (reset! (:image-children-atom _this) [])))

;; ─── Construction ──────────────────────────────────────────────────────────
;; Pi: component manages timing internally — no started-at/ended-at passed in.

(defn make-tool-execution
  "THEME is no longer taken: the box background subscribes to
   ui.subs/theme-sub and follows palette changes live (Stage 5)."
  [& {:keys [name args content is-error output-pad expanded? tools-expanded-atom render-call-fn render-result-fn truncation details cwd render-shell]
      :or {name "" args {} content "" is-error false
           output-pad 1 expanded? false truncation nil details nil
           cwd (or (System/getProperty "user.dir") ".")}}]
  (let [inner-container (container/make-container)
        bg-key (if is-error :tool-error-bg :tool-success-bg)
        b (box/make-box output-pad 1 #(theme/bg (theme/get-current-theme) bg-key %))]
    (box/box-add-child b inner-container)
    (map->ToolExecutionComponent {:kind :tool
                                  :name-atom (atom name)
                                  :args-atom (atom args)
                                  :content-atom (atom content)
                                  :is-error-atom (atom is-error)
                                  :output-pad-atom (atom output-pad)
                                  :expanded-atom (atom expanded?)
                                  :tools-expanded-atom tools-expanded-atom
                                  :started-at-atom (atom nil)
                                  :ended-at-atom (atom nil)
                                  :truncation-atom (atom truncation)
                                  :tool-call-id-atom (atom nil)
                                  :details-atom (atom details)
                                  :args-complete-atom (atom false)
                                  :render-shell-atom (atom render-shell)
                                  :custom-render-call-atom (atom render-call-fn)
                                  :custom-render-result-atom (atom render-result-fn)
                                  :image-data-atom (atom [])
                                  :image-children-atom (atom [])
                                  :last-call-component-atom (atom nil)
                                  :last-result-component-atom (atom nil)
                                  :renderer-state-atom (atom {})
                                  :cwd-atom (atom cwd)
                                  :box (atom b)
                                  :inner-container (atom inner-container)
                                  :cache-atom (atom nil)})))

;; ─── Public API ────────────────────────────────────────────────────────────

(defn tool-execution-set-error!
  "Mark errored; pi: error marks execution ended (stamps ended-at once) and
   clears the elapsed ticker on completion — a component dropped from the
   chat (e.g. /new while a tool runs) must not keep a zombie interval
   invalidating forever."
  [comp is-error]
  (reset! (:is-error-atom comp) is-error)
  (when (nil? @(:ended-at-atom comp))
    (reset! (:ended-at-atom comp) (System/currentTimeMillis)))
  (let [state @(:renderer-state-atom comp)]
    (when-let [id (:timer-id state)]
      (timers/cancel! id))
    (when (contains? state :timer-id)
      (reset! (:renderer-state-atom comp) (dissoc state :timer-id)))))

(defn tool-execution-set-output-pad!
  "Rebuild the box with the new horizontal padding (render sets the bg-fn)."
  [comp n]
  (reset! (:output-pad-atom comp) n)
  (let [b (box/make-box n 1 nil)
        inner @(:inner-container comp)]
    (box/box-add-child b inner)
    (reset! (:box comp) b)))

(defn tool-execution-mark-execution-started!
  "Mark that tool execution has started (Pi: markExecutionStarted()).
   Sets started-at so pending background and timer activate from tool start
   rather than waiting for first content delivery. This is the ONLY thing
   that may stamp started-at: content updates are plain resets on the
   :content-atom — if they stamped too, replayed results (restore / -c)
   would show a fabricated \"Took 0.0s\" (pi renders replayed tools without
   a duration: startedAt stays undefined, updateResult never touches it)."
  [comp]
  (when (nil? @(:started-at-atom comp))
    (reset! (:started-at-atom comp) (System/currentTimeMillis))))

(defn tool-execution-set-args-complete!
  "Mark that all tool arguments have been received.
   Pi: setArgsComplete() — affects render context :args-complete."
  [comp]
  (reset! (:args-complete-atom comp) true))

(defn tool-execution-set-images!
  "Set image content blocks for this tool execution.
   images — vector of {:data str :mime-type str}
   Stores raw image data; ImageComponents are built at render time."
  [comp images]
  (let [image-data (mapv (fn [img] {:data (:data img) :mime-type (:mime-type img)}) images)]
    (reset! (:image-data-atom comp) image-data)))

