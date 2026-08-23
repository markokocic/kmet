(ns kmet.app.ui.tool-execution
  "ToolExecutionComponent component — Pi's ToolExecutionComponent.
   Uses a Box (with status background) wrapping a Container that holds
   the call-render and result-render children.
   Matching Pi architecture: Box handles padding/background/caching.
   Timing is managed internally (started-at on first content, ended-at on error/finalize)."
  (:require [kmet.tui.protocols :as protocols]
            [kmet.tui.theme :as theme]
            [kmet.tui.components.box :as box]
            [kmet.tui.components.container :as container]
            [kmet.app.ui.tool-renderers :as renderers]
            [kmet.tui.components.spacer :as spacer]
            [kmet.tui.components.image :as ic]
            [kmet.tui.macros :refer [track! defsetter defgetter defcomponent]]))

;; ─── Renderer dispatch ─────────────────────────────────────────────────────
;; Built-in renderer functions live in kmet.app.ui.tool-renderers so supported
;; extensions can reuse them directly.

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

(defn- tool-execution-context
  "Build a ToolRenderContext map for the given component and last-component."
  [comp last-comp]
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
   :expanded @(:expanded-atom comp)
   :show-images true
   :is-error @(:is-error-atom comp)})

;; ─── Record ────────────────────────────────────────────────────────────────
;; Pi matching: ToolExecutionComponent manages its own timing.
;; started-at is set on first set-content! call (execution start).
;; ended-at is set on set-error! or on final full-content set-content!.

(defcomponent ToolExecutionComponent :tool
              [name-atom args-atom content-atom is-error-atom
               theme-atom output-pad-atom expanded-atom
               custom-render-call-atom custom-render-result-atom
               started-at-atom ended-at-atom
               truncation-atom tool-call-id-atom
               details-atom        ;; result :details map (pi: result.details), e.g. edit diff
               args-complete-atom
               render-shell-atom   ;; pi: ToolDefinition.renderShell — :self renders without the outer Box
               image-data-atom       ;; vector of {:data str :mime-type str}
               last-call-component-atom   ;; component from previous render-call
               last-result-component-atom ;; component from previous render-result
               renderer-state-atom        ;; persistent state for custom renderers
               cwd-atom                ;; current working directory
               box             ;; outer Box (padding + bg)
               inner-container ;; Container for call/result children
               cache-atom]     ;; render cache (track!)
  (render [this width]
    (track! this width
      (let [theme @theme-atom
            is-error @is-error-atom
            output-pad @output-pad-atom
            name @name-atom
            args @args-atom
            content @content-atom
            expanded? @expanded-atom
            started-at @started-at-atom
            ended-at @ended-at-atom
      ;; Re-check empty — only when no call component rendered and no result
            builtin-call-fn (case name
                              "read" renderers/render-read-call
                              "write" renderers/render-write-call
                              "edit" renderers/render-edit-call
                              "bash" renderers/render-bash-call
                              nil)
            builtin-result-fn (case name
                                "read" renderers/render-read-result
                                "write" renderers/render-write-result
                                "edit" renderers/render-edit-result
                                "bash" renderers/render-bash-result
                                nil)
            builtin-shell (when (= name "edit") :self)
            render-call-fn (or @custom-render-call-atom
                               builtin-call-fn
                               renderers/render-default-call)
            render-result-fn (or @custom-render-result-atom
                                 builtin-result-fn
                                 renderers/render-default-result)
            render-shell (or @render-shell-atom builtin-shell :default)
            container @inner-container
            content-width (max 1 (- width (* 2 output-pad)))
            call-context (tool-execution-context this (last-call-component this))
            call-comp (render-call-fn name args theme content-width call-context)
            _ (reset! last-call-component-atom call-comp)
            truncation @truncation-atom
            result-context (tool-execution-context this (last-result-component this))
            result-comp (render-result-fn content is-error theme content-width expanded? started-at ended-at truncation result-context)
            _ (reset! last-result-component-atom result-comp)
            image-data @image-data-atom]
      ;; Pi: hide component when no call/render content and no images
        (if (and (nil? call-comp) (nil? result-comp) (not (seq image-data)))
          []
          (do
          ;; Build inner container
            (container/container-clear container)
            (container/container-add-child container call-comp)
            (when result-comp
              (container/container-add-child container result-comp))
          ;; Build image components from raw data (Pi: spacer + ImageComponent)
            (doseq [img image-data]
              (container/container-add-child container (spacer/make-spacer 1))
              (container/container-add-child container
                                             (ic/make-image (:data img) (:mime-type img)
                                                            {:fallback-color (fn [s] (theme/fg theme :tool-output s))}
                                                            :max-width-cells 60)))
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
    (protocols/invalidate @box)))

;; ─── Construction ──────────────────────────────────────────────────────────
;; Pi: component manages timing internally — no started-at/ended-at passed in.

(defn make-tool-execution
  [& {:keys [name args content is-error theme output-pad expanded? render-call-fn render-result-fn truncation details cwd render-shell]
      :or {name "" args {} content "" is-error false theme theme/dark-theme
           output-pad 1 expanded? false truncation nil details nil
           cwd (or (System/getProperty "user.dir") ".")}}]
  (let [inner-container (container/make-container)
        bg-key (if is-error :tool-error-bg :tool-success-bg)
        b (box/make-box output-pad 1 #(theme/bg theme bg-key %))]
    (box/box-add-child b inner-container)
    (map->ToolExecutionComponent {:kind :tool
                                  :name-atom (atom name)
                                  :args-atom (atom args)
                                  :content-atom (atom content)
                                  :is-error-atom (atom is-error)
                                  :theme-atom (atom theme)
                                  :output-pad-atom (atom output-pad)
                                  :expanded-atom (atom expanded?)
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
                                  :last-call-component-atom (atom nil)
                                  :last-result-component-atom (atom nil)
                                  :renderer-state-atom (atom {})
                                  :cwd-atom (atom cwd)
                                  :box (atom b)
                                  :inner-container (atom inner-container)
                                  :cache-atom (atom nil)})))

;; ─── Public API ────────────────────────────────────────────────────────────
;; Pi: set-content! and set-error! manage timing internally.

(defsetter tool-execution-set-name! :name-atom comp name
  (protocols/invalidate comp))

(defsetter tool-execution-set-content! :content-atom comp content
  ;; Pi: timing is driven exclusively by markExecutionStarted (the
  ;; :tool-execution-start lifecycle event) — set-content! must NOT mark
  ;; execution started, or replayed results (restore / -c) would show a
  ;; fabricated "Took 0.0s". Pi renders replayed tools without a duration
  ;; (startedAt stays undefined: updateResult never touches it).
  (protocols/invalidate comp))

(defsetter tool-execution-set-error! :is-error-atom comp is-error
  ;; Pi: error marks execution ended
  (when (nil? @(:ended-at-atom comp))
    (reset! (:ended-at-atom comp) (System/currentTimeMillis)))
  ;; Pi: renderResult clears the elapsed ticker on completion — do it here
  ;; too, so a component dropped from the chat (e.g. /new while a tool runs)
  ;; doesn't keep a zombie interval invalidating forever.
  (let [state @(:renderer-state-atom comp)]
    (when-let [interval (:interval state)]
      (future-cancel interval))
    (when (contains? state :interval)
      (reset! (:renderer-state-atom comp) (dissoc state :interval))))
  (protocols/invalidate comp))

(defsetter tool-execution-set-expanded! :expanded-atom comp expanded?
  (protocols/invalidate comp))
(defsetter tool-execution-set-theme! :theme-atom comp theme
  (protocols/invalidate comp))
(defsetter tool-execution-set-output-pad! :output-pad-atom comp n
  ;; Rebuild the box with the new horizontal padding (render sets the bg-fn)
  (let [b (box/make-box n 1 nil)
        inner @(:inner-container comp)]
    (box/box-add-child b inner)
    (reset! (:box comp) b))
  (protocols/invalidate comp))

(defsetter tool-execution-set-truncation! :truncation-atom comp truncation
  (protocols/invalidate comp))

(defsetter tool-execution-set-details! :details-atom comp details
  (protocols/invalidate comp))

(defsetter tool-execution-set-tool-call-id! :tool-call-id-atom comp id)

(defn tool-execution-mark-execution-started!
  "Mark that tool execution has started (Pi: markExecutionStarted()).
   Sets started-at timestamp so pending background and timer activate
   from tool start rather than waiting for first content delivery."
  [comp]
  (when (nil? @(:started-at-atom comp))
    (reset! (:started-at-atom comp) (System/currentTimeMillis)))
  (protocols/invalidate comp))

(defgetter tool-execution-get-tool-call-id :tool-call-id-atom comp)

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
    (reset! (:image-data-atom comp) image-data)
    (protocols/invalidate comp)))

(defsetter tool-execution-set-render-call-fn! :custom-render-call-atom comp f
  (protocols/invalidate comp))
(defsetter tool-execution-set-render-result-fn! :custom-render-result-atom comp f
  (protocols/invalidate comp))
