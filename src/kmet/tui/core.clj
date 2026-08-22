(ns kmet.tui.core
  "TUI - Main class for managing terminal UI with differential rendering.
   Primary entry point for kmet.tui — all public symbols are re-exported
   from this namespace for convenience."
  (:require [clojure.string :as str]
            [clojure.java.io :as io]
            [kmet.tui.protocols :as protocols]
            [kmet.tui.terminal :as terminal]
            [kmet.tui.keys :as keys]
            [kmet.tui.utils :as utils]
            [kmet.libs.terminal-image :as img]
            [kmet.tui.components.text :as text]
            [kmet.tui.components.spacer :as spacer]
            [kmet.tui.components.container :as container]
            [kmet.tui.components.box :as box]
            [kmet.tui.components.input :as input]
            [kmet.tui.components.editor :as editor]
            [kmet.tui.components.select-list :as select-list]
            [kmet.tui.components.settings-list :as settings-list]
            [kmet.tui.components.markdown :as markdown]
            [kmet.tui.components.spinner :as spinner]
            [kmet.tui.components.scroll-view :as scroll-view]
            [kmet.tui.components.stack :as stack]
            ;; pi-parity components (previously stubs — now implemented)
            [kmet.tui.components.alt-screen-flash :as alt-screen-flash]
            [kmet.tui.components.cancellable-loader :as cancellable-loader]
            [kmet.tui.components.truncated-text :as truncated-text]
            [kmet.tui.components.h-stack :as h-stack]
            [kmet.tui.components.v-stack :as v-stack]
            [kmet.tui.components.dynamic-border :as dynamic-border]))

;; ═══════════════════════════════════════════════════════════════════════════
;; Protocol re-exports
;; ═══════════════════════════════════════════════════════════════════════════

(def IComponent protocols/IComponent)
(def IFocusable protocols/IFocusable)
(def IEditorComponent protocols/IEditorComponent)
(def render protocols/render)
(def handle-input protocols/handle-input)
(def invalidate protocols/invalidate)
(def focused protocols/focused)
(def set-focused! protocols/set-focused!)

;; ═══════════════════════════════════════════════════════════════════════════
;; Cursor
;; ═══════════════════════════════════════════════════════════════════════════

(defn- extract-cursor-position
  "Find CURSOR-MARKER in rendered lines (viewport only), strip it from output,
   and return {:lines cleared-lines :cursor {:row r :col c}}.
   Returns {:lines original-lines :cursor nil} when no marker found.
   Only scans the bottom `height` lines (visible viewport), matching Pi's approach."
  [lines height]
  (let [viewport-top (max 0 (- (count lines) height))]
    (loop [i (dec (count lines))]
      (if (>= i viewport-top)
        (let [line (nth lines i)]
          (if-let [marker-idx (clojure.string/index-of line utils/CURSOR-MARKER)]
            (let [before (subs line 0 marker-idx)
                  after (subs line (+ marker-idx (count utils/CURSOR-MARKER)))
                  col (utils/visible-width before)
                  new-line (str before after)
                  new-lines (assoc lines i new-line)]
              {:lines new-lines :cursor {:row i :col col}})
            (recur (dec i))))
        {:lines lines :cursor nil}))))

;; ═══════════════════════════════════════════════════════════════════════════
;; CSI 2026 sync
;; ═══════════════════════════════════════════════════════════════════════════

(def CSI-2026-H terminal/CSI-2026-SYNC-ON)
(def CSI-2026-L terminal/CSI-2026-SYNC-OFF)

;; ═══════════════════════════════════════════════════════════════════════════
;; TUI
;; ═══════════════════════════════════════════════════════════════════════════

(defrecord TUI [terminal components focused-component
                input-listeners previous-lines
                previous-width render-requested?
                running? stopped? overlays
                render-loop input-reader current-reader
                flashes overlay-focus-restore focus-order-counter
                show-hardware-cursor? keyboard-protocol-pushed?
                negotiation-buffer negotiation-timer
                previous-height max-lines-rendered clear-on-shrink?
                full-redraw-count previous-kitty-image-ids
                pending-osc-11? osc-11-queries
                color-scheme-listeners terminal-response-buffer
                terminal-response-timer color-scheme-notifications-enabled?
                debug-redraw? tui-debug?
                input-generation incomplete-flush-timer])

(declare tui-request-render tui-stop set-focus-internal
         overlay-visible? overlay-handle process-input-buffer! tui-invalidate)

(defn create-tui [terminal]
  (let [tui (map->TUI {:terminal (atom terminal)
                       :components (atom [])
                       :focused-component (atom nil)
                       :input-listeners (atom [])
                       :previous-lines (atom [])
                       :previous-width (atom 0)
                       :render-requested? (atom false)
                       :running? (atom false)
                       :stopped? (atom false)
                       :overlays (atom [])
                       :render-loop (atom nil)
                       :input-reader (atom nil)
                       :current-reader (atom nil)
                       :flashes (atom nil)
                       :overlay-focus-restore (atom {:status :inactive})
                       :focus-order-counter (atom 0)
                       :show-hardware-cursor? (atom (= (System/getenv "KMET_HARDWARE_CURSOR") "1"))
                       :keyboard-protocol-pushed? (atom false)
                       :negotiation-buffer (atom "")
                       :negotiation-timer (atom nil)
                       :previous-height (atom 0)
                       :max-lines-rendered (atom 0)
                       :clear-on-shrink? (atom (= (System/getenv "KMET_CLEAR_ON_SHRINK") "1"))
                       :full-redraw-count (atom 0)
                       :previous-kitty-image-ids (atom #{})
                       :pending-osc-11? (atom false)
                       :osc-11-queries (atom [])
                       :color-scheme-listeners (atom #{})
                       :terminal-response-buffer (atom "")
                       :terminal-response-timer (atom nil)
                       :color-scheme-notifications-enabled? (atom false)
                       :debug-redraw? (atom (= (System/getenv "KMET_DEBUG_REDRAW") "1"))
                       :tui-debug? (atom (= (System/getenv "KMET_TUI_DEBUG") "1"))
                       :input-generation (atom 0)
                       :incomplete-flush-timer (atom nil)})]
    ;; AltScreenFlashContainer owned by the TUI (pi: TuiAltScreen owns its
    ;; flash container) — the render loop composites flash lines over the
    ;; screen window; tui-flash! / tui-flash-dispose! are the public API.
    (reset! (:flashes tui)
            (alt-screen-flash/make-alt-screen-flash #(tui-request-render tui)))
    tui))

(defn tui-add-child [tui c] (swap! (:components tui) conj c))
(defn tui-remove-child [tui c]
  (swap! (:components tui) (fn [v] (vec (remove #(identical? % c) v)))))
(defn tui-clear [tui] (reset! (:components tui) []))

(defn tui-set-focus [tui component]
  ;; pi: public setFocus — clears any pending overlay restore state
  (set-focus-internal tui component :clear))

(defn tui-get-show-hardware-cursor
  "Whether the hardware terminal cursor is visible (pi: getShowHardwareCursor)."
  [tui]
  @(:show-hardware-cursor? tui))

(defn tui-set-show-hardware-cursor!
  "Enable/disable the visible hardware cursor (pi: setShowHardwareCursor).
   Disabling hides the cursor immediately; both paths request a re-render."
  [tui enabled?]
  (when (not= enabled? @(:show-hardware-cursor? tui))
    (reset! (:show-hardware-cursor? tui) enabled?)
    (when-not enabled?
      (when-let [term @(:terminal tui)] (terminal/hide-cursor! term)))
    (tui-request-render tui)))

(defn tui-add-input-listener [tui f]
  (swap! (:input-listeners tui) conj f))
(defn tui-remove-input-listener [tui f]
  (swap! (:input-listeners tui) (fn [v] (vec (remove #(= % f) v)))))

;; ─── Flashes (pi: TuiAltScreen.flash — transient messages) ─────────────────

(defn tui-flash!
  "Show a transient inverse-video message over the bottom of the screen for
   DURATION-MS (default 1000), then remove it (pi: flash)."
  [tui message & {:keys [duration-ms]}]
  (alt-screen-flash/alt-screen-flash! @(:flashes tui) message :duration-ms duration-ms))

(defn tui-flash-dispose!
  "Clear all pending flashes immediately."
  [tui]
  (alt-screen-flash/alt-screen-flash-dispose! @(:flashes tui)))

;; ═══════════════════════════════════════════════════════════════════════════
;; Overlays
;; ═══════════════════════════════════════════════════════════════════════════

;; Overlay stack entry. Mutable fields hold atoms so the handle keeps
;; identity across set-hidden!/focus/retarget mutations (kmet convention:
;; atoms for mutable state).
(defrecord Overlay [component options pre-focus hidden? focus-order])

;; ─── Layout resolution (pi: resolveOverlayLayout + anchors) ────────────────

(defn- parse-size-value
  "Parse a size value: absolute number or percentage string (\"50%\") of
   REFERENCE-SIZE (pi: parseSizeValue). Unparseable values → nil."
  [v reference-size]
  (cond
    (nil? v) nil
    (number? v) v
    (string? v) (if-let [[_ pct] (re-matches #"(\d+(?:\.\d+)?)%" v)]
                  (int (Math/floor (* reference-size (/ (Double/parseDouble pct) 100.0))))
                  nil)
    :else nil))

(defn- resolve-anchor-row
  "Anchor → top row offset (pi: resolveAnchorRow)."
  [anchor height avail-height margin-top]
  (case anchor
    (:top-left :top-center :top-right) margin-top
    (:bottom-left :bottom-center :bottom-right) (+ margin-top (- avail-height height))
    ;; :left-center / :center / :right-center
    (+ margin-top (int (Math/floor (/ (- avail-height height) 2))))))

(defn- resolve-anchor-col
  "Anchor → left column offset (pi: resolveAnchorCol)."
  [anchor width avail-width margin-left]
  (case anchor
    (:top-left :left-center :bottom-left) margin-left
    (:top-right :right-center :bottom-right) (+ margin-left (- avail-width width))
    ;; :top-center / :center / :bottom-center
    (+ margin-left (int (Math/floor (/ (- avail-width width) 2))))))

(defn- resolve-overlay-layout
  "Resolve overlay sizing and position from OPTIONS (pi: resolveOverlayLayout).
   Returns {:width w :row r :col c :max-height mh-or-nil}."
  [options overlay-height term-width term-height]
  (let [opt (or options {})
        margin (if (number? (:margin opt))
                 {:top (:margin opt) :right (:margin opt)
                  :bottom (:margin opt) :left (:margin opt)}
                 (:margin opt))
        margin-top (max 0 (:top margin 0))
        margin-right (max 0 (:right margin 0))
        margin-bottom (max 0 (:bottom margin 0))
        margin-left (max 0 (:left margin 0))
        avail-width (max 1 (- term-width margin-left margin-right))
        avail-height (max 1 (- term-height margin-top margin-bottom))
        width (or (parse-size-value (:width opt) term-width) (min 80 avail-width))
        width (if (some? (:min-width opt)) (max width (:min-width opt)) width)
        width (max 1 (min width avail-width))
        max-height (parse-size-value (:max-height opt) term-height)
        max-height (when (some? max-height) (max 1 (min max-height avail-height)))
        effective-height (if (some? max-height) (min overlay-height max-height) overlay-height)
        row (if (some? (:row opt))
              (if (string? (:row opt))
                (if-let [[_ pct] (re-matches #"(\d+(?:\.\d+)?)%" (:row opt))]
                  (let [max-row (max 0 (- avail-height effective-height))]
                    (+ margin-top (int (Math/floor (* max-row (/ (Double/parseDouble pct) 100.0))))))
                  (resolve-anchor-row :center effective-height avail-height margin-top))
                (:row opt))
              (resolve-anchor-row (:anchor opt :center) effective-height avail-height margin-top))
        col (if (some? (:col opt))
              (if (string? (:col opt))
                (if-let [[_ pct] (re-matches #"(\d+(?:\.\d+)?)%" (:col opt))]
                  (let [max-col (max 0 (- avail-width width))]
                    (+ margin-left (int (Math/floor (* max-col (/ (Double/parseDouble pct) 100.0))))))
                  (resolve-anchor-col :center width avail-width margin-left))
                (:col opt))
              (resolve-anchor-col (:anchor opt :center) width avail-width margin-left))
        row (+ row (or (:offset-y opt) 0))
        col (+ col (or (:offset-x opt) 0))
        row (max margin-top (min row (- term-height margin-bottom effective-height)))
        col (max margin-left (min col (- term-width margin-right width)))]
    {:width width :row row :col col :max-height max-height}))

(defn- normalize-overlay-options
  "Map legacy kmet option keys (:x :y :height) onto pi OverlayOptions
   (:col :row :max-height). Other keys pass through unchanged."
  [options]
  (cond-> (dissoc options :x :y :height)
    (contains? options :x) (assoc :col (:x options))
    (contains? options :y) (assoc :row (:y options))
    (contains? options :height) (assoc :max-height (:height options))))

(defn tui-show-overlay
  "Show an overlay component with configurable positioning and sizing
   (pi: TUI.showOverlay). OPTIONS (pi: OverlayOptions):
     :width / :min-width / :max-height — number or percentage string
       (e.g. \"50%\"); width defaults to min(80, available)
     :anchor — :center :top-left :top-center :top-right :left-center
       :right-center :bottom-left :bottom-center :bottom-right (default :center)
     :offset-x / :offset-y — offsets from the anchor position
     :row / :col — absolute number or percentage string (overrides :anchor)
     :margin — number (all sides) or {:top :right :bottom :left}
     :visible — (fn [term-width term-height]) responsive visibility
     :non-capturing — true to render without taking keyboard focus
   Legacy kmet keys :x / :y / :height map to :col / :row / :max-height.
   Returns an OverlayHandle map (pi: OverlayHandle): :hide, :set-hidden!,
   :is-hidden?, :focus, :unfocus (with {:target comp} or nil), :is-focused?."
  [tui component & {:as options}]
  (let [options (normalize-overlay-options options)
        entry (map->Overlay {:component component
                             :options options
                             :pre-focus (atom @(:focused-component tui))
                             :hidden? (atom false)
                             :focus-order (atom (swap! (:focus-order-counter tui) inc))})]
    (swap! (:overlays tui) conj entry)
    (when-not (:non-capturing options)
      (when (overlay-visible? tui entry)
        (tui-set-focus tui component)))
    (when-let [term @(:terminal tui)] (terminal/hide-cursor! term))
    (tui-request-render tui)
    (overlay-handle tui entry)))

;; ─── Visibility + focus restore state machine (pi) ─────────────────────────

(defn- overlay-visible?
  "True when an overlay entry is not hidden and its :visible callback (when
   provided) passes for the current terminal size (pi: isOverlayVisible)."
  [tui entry]
  (and (not @(:hidden? entry))
       (if-let [visible-fn (:visible (:options entry))]
         (if-let [term @(:terminal tui)]
           (visible-fn (terminal/columns term) (terminal/rows term))
           true)
         true)))

(defn- get-topmost-visible-overlay
  "Visual-frontmost visible capturing overlay by focus order
   (pi: getTopmostVisibleOverlay)."
  [tui]
  (reduce (fn [top o]
            (if (and (not (:non-capturing (:options o)))
                     (overlay-visible? tui o)
                     (or (nil? top) (> @(:focus-order o) @(:focus-order top))))
              o
              top))
          nil
          @(:overlays tui)))

(defn- get-visible-overlay-focus-restore
  "The overlay focus restore state, unless its overlay left the stack or
   became invisible (pi: getVisibleOverlayFocusRestore)."
  [tui]
  (let [state @(:overlay-focus-restore tui)]
    (if (and (not= :inactive (:status state))
             (some #(identical? (:overlay state) %) @(:overlays tui))
             (overlay-visible? tui (:overlay state)))
      state
      {:status :inactive})))

(defn- clear-overlay-focus-restore! [tui]
  (reset! (:overlay-focus-restore tui) {:status :inactive}))

(defn- clear-overlay-focus-restore-for!
  "Drop restore state belonging to OVERLAY (pi: clearOverlayFocusRestoreFor)."
  [tui overlay]
  (let [state @(:overlay-focus-restore tui)]
    (when (and (not= :inactive (:status state))
               (identical? (:overlay state) overlay))
      (reset! (:overlay-focus-restore tui) {:status :inactive}))))

(defn- resolve-blocked-overlay-focus-resume
  "Resolve a blocked restore: re-focus the overlay (restore-overlay) or the
   explicit target (focus-target, pi: resolveBlockedOverlayFocusResume)."
  [tui restore-state]
  (if (= :restore-overlay (:status (:resume restore-state)))
    (:component (:overlay restore-state))
    (do (clear-overlay-focus-restore! tui)
        (:target (:resume restore-state)))))

(defn- is-overlay-focus-ancestor?
  "True when COMPONENT is reachable via the preFocus chain of ENTRY
   (pi: isOverlayFocusAncestor)."
  [tui entry component]
  (loop [visited #{}
         current @(:pre-focus entry)]
    (cond
      (or (nil? current) (contains? visited current)) false
      (identical? current component) true
      :else (let [next-prev (some (fn [o]
                                    (when (identical? (:component o) current)
                                      @(:pre-focus o)))
                                  @(:overlays tui))]
              (recur (conj visited current) next-prev)))))

(defn- retarget-overlay-pre-focus!
  "When an overlay is removed, overlays that pointed at it as pre-focus are
   retargeted to its own pre-focus (pi: retargetOverlayPreFocus)."
  [tui removed]
  (doseq [o @(:overlays tui)]
    (when (and (not (identical? o removed))
               (identical? @(:pre-focus o) (:component removed)))
      (reset! (:pre-focus o) @(:pre-focus removed)))))

(defn- contains-component?
  [root target]
  (or (identical? root target)
      (when (instance? clojure.lang.IRef (:children root))
        (boolean (some #(contains-component? % target) @(:children root))))))

(defn- is-component-mounted?
  "True when COMPONENT is still reachable from the base layout
   (pi: isComponentMounted)."
  [tui component]
  (boolean (some #(contains-component? % component) @(:components tui))))

(defn- set-focus-internal
  "Port of pi's TUI.setFocusInternal — switches focus while maintaining the
   overlay focus restore state machine (eligible/blocked/inactive).
   OVERLAY-FOCUS-RESTORE-POLICY: :clear drops pending restore state on a
   null target (public setFocus), :preserve keeps it (input dispatch
   redirect of a no-longer-visible focused overlay)."
  [tui component overlay-focus-restore-policy]
  (let [previous-focus @(:focused-component tui)
        previous-focused-overlay (some #(when (and (identical? (:component %) previous-focus)
                                                   (overlay-visible? tui %))
                                          %)
                                       @(:overlays tui))
        next-focus-is-overlay? (boolean (some #(identical? (:component %) component)
                                              @(:overlays tui)))
        restore-state (get-visible-overlay-focus-restore tui)
        next-focus (atom component)]
    (cond
      (and (some? component) (not next-focus-is-overlay?))
      (if (and (= :blocked (:status restore-state))
               (identical? (:blocked-by restore-state) previous-focus))
        (if (or (= :focus-target (:status (:resume restore-state)))
                (not (is-component-mounted? tui (:blocked-by restore-state))))
          (reset! next-focus (resolve-blocked-overlay-focus-resume tui restore-state))
          (reset! (:overlay-focus-restore tui)
                  {:status :blocked :overlay (:overlay restore-state)
                   :blocked-by component :resume (:resume restore-state)}))
        (when (and previous-focused-overlay
                   (not= :inactive (:status restore-state))
                   (identical? (:overlay restore-state) previous-focused-overlay)
                   (not (is-overlay-focus-ancestor? tui previous-focused-overlay component)))
          (reset! (:overlay-focus-restore tui)
                  {:status :blocked :overlay previous-focused-overlay
                   :blocked-by component :resume {:status :restore-overlay}})))

      (nil? component)
      (if (and (= :blocked (:status restore-state))
               (identical? (:blocked-by restore-state) previous-focus))
        (reset! next-focus (resolve-blocked-overlay-focus-resume tui restore-state))
        (when (= :clear overlay-focus-restore-policy)
          (clear-overlay-focus-restore! tui)))

      ;; next focus is an overlay component — restore state unchanged
      :else nil)
    (when (satisfies? IFocusable previous-focus)
      (set-focused! previous-focus false))
    (reset! (:focused-component tui) @next-focus)
    (when (satisfies? IFocusable @next-focus)
      (set-focused! @next-focus true))
    (when-let [focused-overlay (some #(when (and (identical? (:component %) @next-focus)
                                                 (overlay-visible? tui %))
                                        %)
                                     @(:overlays tui))]
      (reset! (:overlay-focus-restore tui)
              {:status :eligible :overlay focused-overlay}))
    nil))

;; ─── OverlayHandle (pi: OverlayHandle) ─────────────────────────────────────

(defn- overlay-handle
  "Return the OverlayHandle map for ENTRY: {:hide :set-hidden! :is-hidden?
   :focus :unfocus :is-focused?} (pi: OverlayHandle)."
  [tui entry]
  {:hide (fn []
           (when (some #(identical? % entry) @(:overlays tui))
             (clear-overlay-focus-restore-for! tui entry)
             (retarget-overlay-pre-focus! tui entry)
             (swap! (:overlays tui) (fn [v] (vec (remove #(identical? % entry) v))))
             (when (identical? (:component entry) @(:focused-component tui))
               (if-let [top (get-topmost-visible-overlay tui)]
                 (tui-set-focus tui (:component top))
                 (tui-set-focus tui @(:pre-focus entry))))
             (when (empty? @(:overlays tui))
               (when-let [term @(:terminal tui)] (terminal/hide-cursor! term)))
             (tui-request-render tui)))
   :set-hidden! (fn [hidden?]
                  (when (not= hidden? @(:hidden? entry))
                    (reset! (:hidden? entry) hidden?)
                    (if hidden?
                      (do (clear-overlay-focus-restore-for! tui entry)
                          (when (identical? (:component entry) @(:focused-component tui))
                            (if-let [top (get-topmost-visible-overlay tui)]
                              (tui-set-focus tui (:component top))
                              (tui-set-focus tui @(:pre-focus entry)))))
                      (when (and (not (:non-capturing (:options entry)))
                                 (overlay-visible? tui entry))
                        (reset! (:focus-order entry) (swap! (:focus-order-counter tui) inc))
                        (tui-set-focus tui (:component entry))))
                    (tui-request-render tui)))
   :is-hidden? (fn [] @(:hidden? entry))
   :focus (fn []
            (when (and (some #(identical? % entry) @(:overlays tui))
                       (overlay-visible? tui entry))
              (reset! (:focus-order entry) (swap! (:focus-order-counter tui) inc))
              (tui-set-focus tui (:component entry))
              (tui-request-render tui)))
   :unfocus (fn [& [unfocus-options]]
              (let [is-focused? (identical? (:component entry) @(:focused-component tui))
                    restore-state @(:overlay-focus-restore tui)
                    has-pending-restore? (and (not= :inactive (:status restore-state))
                                              (identical? (:overlay restore-state) entry))]
                (when (or is-focused? has-pending-restore?)
                  (if (and (= :blocked (:status restore-state))
                           (identical? (:overlay restore-state) entry)
                           (identical? (:blocked-by restore-state) @(:focused-component tui)))
                    (do (if (some? unfocus-options)
                          (reset! (:overlay-focus-restore tui)
                                  {:status :blocked :overlay entry
                                   :blocked-by (:blocked-by restore-state)
                                   :resume {:status :focus-target :target (:target unfocus-options)}})
                          (clear-overlay-focus-restore! tui))
                        (tui-request-render tui))
                    (do (clear-overlay-focus-restore-for! tui entry)
                        (when (or is-focused? (some? unfocus-options))
                          (let [top (get-topmost-visible-overlay tui)
                                fallback (if (and top (not (identical? top entry)))
                                           (:component top)
                                           @(:pre-focus entry))]
                            (tui-set-focus tui (if (some? unfocus-options)
                                                 (:target unfocus-options)
                                                 fallback))))
                        (tui-request-render tui))))))
   :is-focused? (fn [] (identical? (:component entry) @(:focused-component tui)))})

(defn tui-hide-overlay
  "Hide the topmost overlay and restore focus — topmost visible overlay, or
   the pre-overlay focus when still mounted, else the last component
   (pi: TUI.hideOverlay)."
  [tui]
  (when-let [overlay (peek @(:overlays tui))]
    (clear-overlay-focus-restore-for! tui overlay)
    (retarget-overlay-pre-focus! tui overlay)
    (swap! (:overlays tui) pop)
    (when (identical? (:component overlay) @(:focused-component tui))
      (if-let [top (get-topmost-visible-overlay tui)]
        (tui-set-focus tui (:component top))
        (let [prev @(:pre-focus overlay)]
          (if (and prev (is-component-mounted? tui prev))
            (tui-set-focus tui prev)
            (when-let [last (last @(:components tui))]
              (tui-set-focus tui last))))))
    (when (empty? @(:overlays tui))
      (when-let [term @(:terminal tui)] (terminal/hide-cursor! term)))
    (tui-request-render tui)))

(defn tui-has-overlay?
  "True when any visible overlay is on the stack (pi: TUI.hasOverlay)."
  [tui]
  (boolean (some #(overlay-visible? tui %) @(:overlays tui))))

(defn- composite-overlays
  "Composite all visible overlays (sorted by focus order, later = on top)
   onto the rendered base LINES (pi: TUI.compositeOverlays). Each overlay is
   positioned by resolve-overlay-layout; content rows are padded so overlay
   rows land in the terminal viewport; overlay lines are truncated to their
   declared width before compositing."
  [tui lines term-width term-height]
  (let [visible-entries (->> @(:overlays tui)
                             (filter #(overlay-visible? tui %))
                             (sort-by #(deref (:focus-order %))))]
    (if (empty? visible-entries)
      lines
      (let [rendered (mapv (fn [entry]
                             (let [options (:options entry)
                                   layout0 (resolve-overlay-layout options 0 term-width term-height)
                                   width (:width layout0)
                                   max-height (:max-height layout0)
                                   overlay-lines (vec (render (:component entry) width))
                                   overlay-lines (if (and max-height (> (count overlay-lines) max-height))
                                                   (subvec overlay-lines 0 max-height)
                                                   overlay-lines)
                                   layout (resolve-overlay-layout options
                                                                  (count overlay-lines)
                                                                  term-width term-height)]
                               {:lines overlay-lines
                                :row (:row layout)
                                :col (:col layout)
                                :width width}))
                           visible-entries)
            min-lines-needed (reduce (fn [m {:keys [row lines]}]
                                       (max m (+ row (count lines))))
                                     (count lines) rendered)
            working-height (max (count lines) term-height min-lines-needed)
            result (into (vec lines) (repeat (max 0 (- working-height (count lines))) ""))
            viewport-start (max 0 (- working-height term-height))]
        (reduce (fn [acc {:keys [lines row col width]}]
                  (reduce (fn [acc2 [i overlay-line]]
                            (let [idx (+ viewport-start row i)]
                              (if (and (>= idx 0) (< idx (count acc2)))
                                (let [line (if (> (utils/visible-width overlay-line) width)
                                             (:text (utils/slice-with-width overlay-line 0 width :strict? true))
                                             overlay-line)]
                                  (assoc acc2 idx
                                         (utils/composite-line (nth acc2 idx) line col width term-width)))
                                acc2)))
                          acc
                          (map-indexed vector lines)))
                result
                rendered)))))

;; ═══════════════════════════════════════════════════════════════════════════
;; Diff
;; ═══════════════════════════════════════════════════════════════════════════

(defn- composite-flashes
  "Overlay transient flash lines (AltScreenFlashContainer) onto the screen
   window — the bottom HEIGHT content rows — right-aligned, one flash per
   row (pi: compositeFlashes). Takes the LAST HEIGHT flash lines so a flash
   flood stays on-screen (pi: .slice(-height)). Returns LINES unchanged
   when nothing flashes."
  [flashes lines width height]
  (let [flash-lines (protocols/render flashes width)
        flash-lines (if (> (count flash-lines) height)
                      (vec (take-last height flash-lines))
                      flash-lines)
        n (count flash-lines)
        top (max 0 (- (count lines) height))]
    (if (zero? n)
      lines
      (loop [row 0, lines lines]
        (if (>= row n)
          lines
          (let [line (nth flash-lines row)
                fw (utils/visible-width line)]
            (if (pos? fw)
              (let [idx (+ top row)
                    out (utils/composite-line (if (< idx (count lines)) (nth lines idx) "")
                                              line (- width fw) fw width)]
                (recur (inc row)
                       (if (< idx (count lines))
                         (assoc lines idx out)
                         (conj lines out))))
              (recur (inc row) lines))))))))

;; ═══════════════════════════════════════════════════════════════════════════
;; Kitty image diff support (pi: expandChangedRangeForKittyImages /
;; deleteChangedKittyImages / getKittyImageReservedRows)
;; ═══════════════════════════════════════════════════════════════════════════

(defn- kitty-image-reserved-rows
  "Rows the image at INDEX occupies: the declared rows, bounded by the
   consecutive blank rows after it and by MAX-INDEX (pi:
   getKittyImageReservedRows). Must be computed on the raw, unpadded lines —
   padded spaces would terminate the walk immediately."
  [lines index & [max-index]]
  (let [rows (img/extract-kitty-image-rows (or (nth lines index nil) ""))
        max-rows (min rows
                      (if max-index
                        (- max-index index -1)
                        (count lines))
                      (- (count lines) index))]
    (if (<= rows 1)
      1
      (loop [reserved 1]
        (if (>= reserved max-rows)
          reserved
          (let [line (or (nth lines (+ index reserved) nil) "")]
            (if (or (img/is-image-line line) (pos? (utils/visible-width line)))
              reserved
              (recur (inc reserved)))))))))

(defn- collect-kitty-image-ids
  "All Kitty image ids referenced by LINES (pi: collectKittyImageIds)."
  [lines]
  (reduce (fn [acc line] (reduce conj acc (img/extract-kitty-image-ids line)))
          #{} lines))

(defn- expand-changed-range-for-kitty-images
  "Port of pi's expandChangedRangeForKittyImages: widen the changed range so it
   covers every image block that touches it in either prev or new lines."
  [first-changed last-changed prev lines]
  (let [expanded (volatile! [first-changed last-changed])
        expand-for (fn [ls]
                     (doseq [i (range (count ls))]
                       (when (seq (img/extract-kitty-image-ids (nth ls i)))
                         (let [block-end (+ i (kitty-image-reserved-rows ls i) -1)]
                           (when (or (>= i first-changed)
                                     (and (<= i last-changed) (>= block-end first-changed)))
                             (vswap! expanded
                                     (fn [[f l]] [(min f i) (max l block-end)])))))))]
    (expand-for prev)
    (expand-for lines)
    @expanded))

(defn- delete-changed-kitty-images
  "Port of pi's deleteChangedKittyImages: the delete sequence for all image ids
   in PREV within the changed range."
  [first-changed last-changed prev]
  (let [ids (reduce (fn [acc i]
                      (reduce conj acc (img/extract-kitty-image-ids (nth prev i ""))))
                    #{}
                    (range first-changed (min (inc last-changed) (count prev))))]
    (apply str (map img/delete-kitty-image ids))))

;; ─── Crash + debug logs (pi: pi-crash.log / PI_DEBUG_REDRAW / PI_TUI_DEBUG)

(defn- log-path [filename]
  (str (io/file (System/getProperty "user.dir") filename)))

(defn- append-log!
  "Append TEXT to a file at PATH, ignoring errors (pi: appendFileSync)."
  [path text]
  (try
    (with-open [w (io/writer path :append true)]
      (.write w text))
    (catch Exception _)))

(defn- write-crash-log!
  "Write all rendered lines + overflow info to kmet-crash.log in the cwd
   (pi: pi-crash.log)."
  [lines w idx vw]
  (let [path (log-path "kmet-crash.log")
        data (str "Crash at " (java.time.LocalDateTime/now) "\n"
                  "Terminal width: " w "\n"
                  "Line " idx " visible width: " vw "\n"
                  "\n"
                  "=== All rendered lines ===\n"
                  (apply str (map-indexed (fn [i l]
                                            (str "[" i "] (w=" (utils/visible-width l) ") " l "\n"))
                                          lines))
                  "\n")]
    (try
      (spit path data)
      (catch Exception _))))

(defn- tui-debug-dump!
  "Write a render frame dump to /tmp/tui/ when KMET_TUI_DEBUG=1
   (pi: PI_TUI_DEBUG render dumps)."
  [prev lines buffer w h viewport-top hardware-cursor-row]
  (try
    (let [dir "/tmp/tui"]
      (.mkdirs (io/file dir))
      (let [path (str (io/file dir (str "render-" (System/nanoTime) "-"
                                        (rand-int 0x7fffffff) ".log")))]
        (spit path (str "viewportTop: " viewport-top "\n"
                        "hardwareCursorRow: " hardware-cursor-row "\n"
                        "height: " h "\n"
                        "width: " w "\n"
                        "newLines.length: " (count lines) "\n"
                        "previousLines.length: " (count prev) "\n"
                        "\n"
                        "=== newLines ===\n"
                        (apply str (map-indexed (fn [i l] (str "[" i "] " l "\n")) lines))
                        "\n"
                        "=== previousLines ===\n"
                        (apply str (map-indexed (fn [i l] (str "[" i "] " l "\n")) prev))
                        "\n"
                        "=== buffer ===\n" buffer "\n"))))
    (catch Exception _)))

;; ═══════════════════════════════════════════════════════════════════════════
;; Input reader
;; ═══════════════════════════════════════════════════════════════════════════

(def ^:private PASTE-START terminal/PASTE-START)
(def ^:private PASTE-END terminal/PASTE-END)

;; ─── Kitty protocol negotiation interception (pi: setupStdinBuffer) ────────

(defn- clear-negotiation-timer!
  "Cancel a pending negotiation flush timer."
  [tui]
  (when-let [t @(:negotiation-timer tui)]
    (future-cancel t)
    (reset! (:negotiation-timer tui) nil)))

(defn- schedule-negotiation-flush!
  "Port of pi's scheduleKeyboardProtocolNegotiationBufferFlush: after
   NEGOTIATION-FLUSH-TIMEOUT-MS, flush any still-held negotiation fragment
   back into the input buffer so it is not swallowed forever.
   Guarded by the input generation (like schedule-incomplete-flush!): a
   fragment is only flushed after true idleness — if a new char arrived
   while sleeping, the response is still coming and the flush is skipped
   (a stale flush would append the fragment after the fresh char and the
   re-process would dispatch the char as a key)."
  [tui read-fn buf]
  (when (and (nil? @(:negotiation-timer tui))
             (seq @(:negotiation-buffer tui)))
    (let [gen @(:input-generation tui)]
      (reset! (:negotiation-timer tui)
              (future
                (try
                  (Thread/sleep terminal/NEGOTIATION-FLUSH-TIMEOUT-MS)
                  (when (and (seq @(:negotiation-buffer tui))
                             (= gen @(:input-generation tui)))
                    (swap! buf str @(:negotiation-buffer tui))
                    (reset! (:negotiation-buffer tui) "")
                    (process-input-buffer! tui read-fn buf))
                  (catch Exception _))
                (reset! (:negotiation-timer tui) nil))))))

(defn- intercept-keyboard-negotiation!
  "Port of pi's setupStdinBuffer negotiation interception: while the Kitty
   protocol query is outstanding, hold response fragments in a separate
   buffer so the response is consumed (never dispatched as input). Returns
   :consumed / :pending / nil (not negotiation input — proceed normally)."
  [tui read-fn buf]
  (if-not @(:keyboard-protocol-pushed? tui)
    nil
    (let [held @(:negotiation-buffer tui)
          combined (str held @buf)
          parsed (terminal/parse-negotiation-sequence combined)]
      (cond
        parsed
        (do (clear-negotiation-timer! tui)
            (reset! (:negotiation-buffer tui) "")
            (reset! buf "")
            (terminal/handle-negotiation-sequence! @(:terminal tui) parsed)
            (tui-request-render tui)
            :consumed)

        (terminal/negotiation-prefix? combined)
        (do (reset! (:negotiation-buffer tui) combined)
            (reset! buf "")
            (schedule-negotiation-flush! tui read-fn buf)
            :pending)

        :else
        (do (when (seq held)
              (clear-negotiation-timer! tui)
              (reset! buf combined)
              (reset! (:negotiation-buffer tui) ""))
            nil)))))

;; ─── Terminal response interception (pi: consumeOsc11BackgroundResponse /
;;      consumeTerminalColorSchemeReport / consumeCellSizeResponse) ──────────

(defn- clear-terminal-response-timer!
  "Cancel a pending terminal-response flush timer."
  [tui]
  (when-let [t @(:terminal-response-timer tui)]
    (future-cancel t)
    (reset! (:terminal-response-timer tui) nil)))

(defn- schedule-terminal-response-flush!
  "Flush a held terminal-response fragment back into the input buffer after
   the flush timeout so it is never swallowed forever (mirrors the
   negotiation flush). Guarded by the input generation: only flush after
   true idleness, never while a response is still arriving."
  [tui read-fn buf]
  (when (and (nil? @(:terminal-response-timer tui))
             (seq @(:terminal-response-buffer tui)))
    (let [gen @(:input-generation tui)]
      (reset! (:terminal-response-timer tui)
              (future
                (try
                  (Thread/sleep terminal/NEGOTIATION-FLUSH-TIMEOUT-MS)
                  (when (and (seq @(:terminal-response-buffer tui))
                             (= gen @(:input-generation tui)))
                    (swap! buf str @(:terminal-response-buffer tui))
                    (reset! (:terminal-response-buffer tui) "")
                    (process-input-buffer! tui read-fn buf))
                  (catch Exception _))
                (reset! (:terminal-response-timer tui) nil))))))

(defn- settle-osc-11-query!
  "Settle the OSC 11 query identified by PROMISE with COLOR (nil on
   timeout). Drops already-settled queries; clears the pending flag before
   resolving so observers never see a stale flag after the promise settles."
  [tui color promise]
  (let [settled? (volatile! false)]
    (swap! (:osc-11-queries tui)
           (fn [qs]
             (if-let [q (first (filter #(identical? (:promise %) promise) qs))]
               (do (when (compare-and-set! (:settled? q) false true)
                     (future-cancel (:timer q))
                     (vreset! settled? true))
                   (vec (remove #(identical? (:promise %) promise) qs)))
               qs)))
    (when (empty? @(:osc-11-queries tui))
      (reset! (:pending-osc-11? tui) false))
    (when @settled?
      (deliver promise color))))

(defn- settle-osc-11-response!
  "Settle the oldest unsettled OSC 11 query with COLOR (pi: shift + resolve)."
  [tui color]
  (let [settled? (volatile! false)
        q (first (filter #(not @(:settled? %)) @(:osc-11-queries tui)))]
    (when q
      (when (compare-and-set! (:settled? q) false true)
        (future-cancel (:timer q))
        (vreset! settled? true))
      (swap! (:osc-11-queries tui)
             (fn [qs] (vec (remove #(identical? (:promise %) (:promise q)) qs)))))
    (when (empty? @(:osc-11-queries tui))
      (reset! (:pending-osc-11? tui) false))
    (when (and q @settled?)
      (deliver (:promise q) color))))

(defn- intercept-terminal-response!
  "Consume terminal query responses — cell size (\u001b[6;h;wt), OSC 11
   background color, color scheme report (\u001b[?997;Nn) — before key
   parsing; pi consumes them in handleInput before listeners. Cell size is
   ungated (pi: consumeCellSizeResponse has no pending query); OSC 11 is
   gated on an outstanding query. Returns :consumed (handled), :pending
   (fragment held), or nil (not a response)."
  [tui read-fn buf]
  (let [held @(:terminal-response-buffer tui)
        combined (str held @buf)
        cell-size (terminal/parse-cell-size-response combined)
        osc-11 (when @(:pending-osc-11? tui)
                 (terminal/parse-osc-11-background-response combined))
        scheme (terminal/parse-terminal-color-scheme-report combined)]
    (cond
      cell-size
      (do (clear-terminal-response-timer! tui)
          (reset! (:terminal-response-buffer tui) "")
          (reset! buf "")
          (img/set-cell-dimensions! cell-size)
          ;; Invalidate all components so images re-render at the new size
          ;; (pi: consumeCellSizeResponse — ungated, no pending query).
          (tui-invalidate tui)
          (tui-request-render tui)
          :consumed)

      osc-11
      (do (clear-terminal-response-timer! tui)
          (reset! (:terminal-response-buffer tui) "")
          (reset! buf "")
          (settle-osc-11-response! tui osc-11)
          :consumed)

      scheme
      (do (clear-terminal-response-timer! tui)
          (reset! (:terminal-response-buffer tui) "")
          (reset! buf "")
          (doseq [l @(:color-scheme-listeners tui)]
            (try (l scheme)
                 (catch Exception e
                   (binding [*out* *err*]
                     (println "color scheme listener:" (ex-message e))))))
          :consumed)

      (or (terminal/cell-size-response-prefix? combined)
          (and @(:pending-osc-11? tui) (terminal/osc-11-response-prefix? combined))
          (terminal/color-scheme-report-prefix? combined))
      (do (reset! (:terminal-response-buffer tui) combined)
          (reset! buf "")
          (schedule-terminal-response-flush! tui read-fn buf)
          :pending)

      :else
      (do (when (seq held)
            (clear-terminal-response-timer! tui)
            (reset! buf combined)
            (reset! (:terminal-response-buffer tui) ""))
          nil))))

(defn- dispatch-input!
  "Port of pi's TUI input routing: listeners first, then overlay focus
   maintenance (focused-overlay visibility redirect + focus-restore
   reclaim), then delivery to the focused component. Key release events
   are filtered unless the component opts in via a :wants-key-release?
   field (pi: Component.wantsKeyRelease)."
  [tui data]
  ;; pi: input listeners run as a chain — each may :consume (stop dispatch)
  ;; or return transformed :data for the later listeners and the focused
  ;; component (pi: handleInput listener loop).
  (let [{:keys [data] :as chained}
        (reduce (fn [{:keys [data] :as acc} l]
                  (if (:consumed acc)
                    acc
                    (let [result (try (l data)
                                      (catch Exception e
                                        (binding [*out* *err*]
                                          (println "input listener error:" (ex-message e)))
                                        nil))]
                      (cond
                        (:consume result) (assoc acc :consumed true)
                        (map? result) (assoc acc :data (:data result data))
                        :else acc))))
                {:data data :consumed false}
                @(:input-listeners tui))]
    ;; pi: a listener chain that transforms data to an empty string drops
    ;; the event entirely
    (when-not (or (:consumed chained) (empty? data))
      ;; If the focused component is an overlay that is no longer visible
      ;; (hidden via handle, or the :visible callback went false), redirect
      ;; focus to the topmost visible overlay or back to the pre-focus
      ;; (pi: handleInput overlay visibility check).
      (let [fc @(:focused-component tui)
            focused-overlay (some #(when (identical? (:component %) fc) %) @(:overlays tui))]
        (when (and focused-overlay (not (overlay-visible? tui focused-overlay)))
          (if-let [top (get-topmost-visible-overlay tui)]
            (tui-set-focus tui (:component top))
            (set-focus-internal tui @(:pre-focus focused-overlay) :preserve))))
      ;; Focus is not an overlay: reclaim input for the focused visible overlay
      ;; (pi: eligible → reclaim; blocked → resolve the resume, unless the
      ;; current focus is the blocker itself).
      (let [fc @(:focused-component tui)
            focus-is-overlay? (boolean (some #(identical? (:component %) fc) @(:overlays tui)))]
        (when-not focus-is-overlay?
          (let [rs (get-visible-overlay-focus-restore tui)]
            (cond
              (= :eligible (:status rs))
              (tui-set-focus tui (:component (:overlay rs)))

              (and (= :blocked (:status rs)) (not (identical? (:blocked-by rs) fc)))
              (if (= :restore-overlay (:status (:resume rs)))
                (tui-set-focus tui (:component (:overlay rs)))
                (do (clear-overlay-focus-restore! tui)
                    (tui-set-focus tui (:target (:resume rs)))))))))
      (when-let [fc @(:focused-component tui)]
        ;; pi: input goes only to the focused leaf; key release events are
        ;; filtered unless the component opts in via a :wants-key-release?
        ;; field (pi: Component.wantsKeyRelease)
        (when (or (not (keys/is-key-release? data))
                  (:wants-key-release? fc))
          (handle-input fc data)))
      (tui-request-render tui))))

;; ═══════════════════════════════════════════════════════════════════════════
;; Input buffer (pi: stdin-buffer.ts)
;; ═══════════════════════════════════════════════════════════════════════════

;; pi: DEFAULT_SEQUENCE_TIMEOUT_MS — a partial CSI/OSC/mouse sequence gets
;; 50ms for its remainder. The earlier flat 10ms fired mid-sequence under
;; ordinary reader stalls (observed at 11-15ms on Android): the paste-marker
;; ESC was flushed as a phantom Escape keypress and "[200~" leaked in as
;; literal text, splitting every bracketed paste whose bytes crossed a stall.
(def ^:private SEQUENCE-FLUSH-MS 50)

;; pi: DEFAULT_ESCAPE_TIMEOUT_MS — only a lone ESC waits this long before it
;; is dispatched as the Escape key.
(def ^:private ESCAPE-FLUSH-MS 10)

;; Serializes input dispatch. pi's StdinBuffer timeouts run on the Node main
;; thread, so a flush can never interleave with the next key's processing;
;; here the flush futures are separate threads, and without this lock two
;; threads could be inside dispatch-input!/handle-input at once (e.g. the
;; reader inserting a pasted char while a stale timer dispatches Escape —
;; both racing the editor's read-modify-swap on its state atom).
(def ^:private dispatch-lock (Object.))

(defn- clear-incomplete-flush!
  "Cancel a pending incomplete-buffer flush timer."
  [tui]
  (when-let [t @(:incomplete-flush-timer tui)]
    (future-cancel t)
    (reset! (:incomplete-flush-timer tui) nil)))

(defn- dispatch-buffer!
  "Dispatch the buffered CONTENT as a single input sequence. Only a lone ESC
   (the Escape key) or a complete dispatchable sequence is flushed — a partial
   CSI/mouse prefix stays buffered, waiting for the terminal's next character
   (dispatching it would leak the fragment as text into the focused editor).
   Returns true when dispatched (the caller clears the buffer), false when the
   fragment stays pending."
  [tui content]
  (when (seq content)
    (cond
      (= content "\u001b")
      (do (dispatch-input! tui content) true)

      (and (or (keys/parse-key content)
               (keys/mouse-sequence? content)
               (keys/focus-sequence? content))
           (keys/complete-sequence? content))
      (do (dispatch-input! tui content) true)

      ;; Complete but unrecognized ESC sequence — garbage. Returning true
      ;; makes the caller clear the buffer so it can never grow and swallow
      ;; subsequent input (see the ESC branch of process-input-buffer!).
      (and (not= content "\u001b")
           (keys/complete-sequence? content))
      true

      :else false)))

(defn- claim-flush-content!
  "Atomically claim BUF for a flush: when it still holds exactly CONTENT,
  empty it and return true; otherwise return false and leave BUF untouched.
  The swap fn MUST return cur unchanged on mismatch — returning nil would
  install nil as the buffer value and wipe whatever the reader had appended
  in the meantime."
  [buf content]
  (let [claimed? (volatile! false)]
    (swap! buf (fn [cur]
                 (if (= cur content)
                   (do (vreset! claimed? true) "")
                   cur)))
    @claimed?))

(defn- schedule-incomplete-flush!
  "Port of pi's StdinBuffer timeout: when the buffer holds an incomplete
   ESC-prefixed sequence (e.g. a lone ESC or a partial CSI prefix), wait for
   the rest — ESCAPE-FLUSH-MS for a lone ESC, SEQUENCE-FLUSH-MS otherwise
   (pi: escapeTimeout/timeout). On expiry a lone ESC is dispatched as the
   Escape key and a complete sequence is dispatched; a partial CSI/mouse
   fragment stays buffered (it must not leak as text).
   Re-armed on every accumulation. The future CLAIMS the buffer atomically
   via claim-flush-content!: a char arriving concurrently from the reader
   thread makes the claim a no-op, so neither thread can dispatch a stale
   fragment twice or wipe freshly appended input — unlike pi this timer runs
   on a separate thread, so it must never race the reader's own handling."
  [tui buf]
  (clear-incomplete-flush! tui)
  (when (seq @buf)
    (let [escape? (= @buf "\u001b")
          fut-box (atom nil)
          fut (future
                (try
                  (Thread/sleep (if escape? ESCAPE-FLUSH-MS SEQUENCE-FLUSH-MS))
                  (let [content @buf]
                    (when (seq content)
                      ;; Claim-then-dispatch: only when the buffer STILL holds
                      ;; exactly what was armed does this flush own the input;
                      ;; anything else means the reader appended in between and
                      ;; its own pass handles the buffer.
                      (when (claim-flush-content! buf content)
                        (let [dispatched?
                              (locking dispatch-lock
                                (dispatch-buffer! tui content))]
                          ;; A declined fragment (partial CSI/mouse prefix)
                          ;; stays buffered — put it back ahead of any input
                          ;; that arrived while dispatching (it is older;
                          ;; dropping it would break completion of the
                          ;; sequence by the remaining bytes).
                          (when-not dispatched?
                            (swap! buf (fn [cur]
                                         (if (empty? cur)
                                           content
                                           (str content cur)))))))))
                  (catch Exception _))
                (when (identical? @fut-box @(:incomplete-flush-timer tui))
                  (reset! (:incomplete-flush-timer tui) nil)))]
      (reset! fut-box fut)
      (reset! (:incomplete-flush-timer tui) fut))))

(defn- process-input-buffer!
  "Process buffered input. Dispatch is STRUCTURAL (pi: extractCompleteSequences):
   a sequence is dispatched only when complete — parse-key/mouse/focus matched
   AND structurally finished (complete-sequence?). Incomplete ESC-prefixed
   buffers stay in BUF and are flushed after SEQUENCE-FLUSH-MS / ESCAPE-FLUSH-MS
   if no further characters arrive, so a lone ESC still fires as the Escape key.
   READ-FN is accepted for the interception flush timers but never used for
   per-character waiting — the reader thread does all reading (JLine's timed
   reads race the NonBlockingReader pump and can lose characters).

   The whole pass runs under dispatch-lock: flush-timer futures re-enter this
   fn from their own threads (pi: single-threaded Node, no such interleaving),
   so without the lock a timer could process/dispatch a fragment concurrently
   with the reader's char — splitting sequences and racing the focused
   component's state updates."
  [tui read-fn buf]
  ;; Kitty protocol negotiation responses are intercepted first and never
  ;; reach the normal dispatch path (pi: setupStdinBuffer); terminal query
  ;; responses (cell size / OSC 11 / color scheme) are consumed next (pi:
  ;; handleInput consumes them before listeners).
  (locking dispatch-lock
    (when (nil? (intercept-keyboard-negotiation! tui read-fn buf))
      (when (nil? (intercept-terminal-response! tui read-fn buf))
        (let [s @buf]
          (cond
            (empty? s) nil

            ;; Paste markers — dispatch immediately. Text around the marker
            ;; stays buffered in arrival order (pi emits pre-marker sequences,
            ;; then enters paste mode with everything after the marker).
            (and (>= (count s) 6)
                 (or (clojure.string/includes? s PASTE-START)
                     (clojure.string/includes? s PASTE-END)))
            (let [marker (if (clojure.string/includes? s PASTE-START) PASTE-START PASTE-END)
                  idx (clojure.string/index-of s marker)
                  before (subs s 0 idx)
                  after (subs s (+ idx (count marker)))]
              (reset! buf (str before after))
              (dispatch-input! tui marker))

          ;; ESC-prefixed: dispatch only complete sequences (pi: a complete
          ;; CSI/SS3/OSC/mouse sequence, or a meta key). Incomplete prefixes
          ;; ("\u001b", "\u001b[", "\u001b[<...") wait for more characters;
          ;; the lone-ESC case is flushed after ESCAPE-FLUSH-MS, other partial
          ;; sequences after SEQUENCE-FLUSH-MS. A COMPLETE sequence that
          ;; nothing recognizes (e.g. a terminal response the interceptors
          ;; missed — Termux's \u001b[?64;...c DA or a kitty push response in an
          ;; unparsed format) is garbage: holding it in the buffer would append
          ;; every subsequent key to it and swallow all input forever ("kmet
          ;; frozen, keys dead, no crash log"). Drop it.
            (= (first s) \u001b)
            (if (and (or (keys/parse-key s)
                         (keys/mouse-sequence? s)
                         (keys/focus-sequence? s))
                     (not= s "\u001b")
                     (keys/complete-sequence? s))
              (do (reset! buf "")
                  (dispatch-input! tui s))
              (if (and (not= s "\u001b") (keys/complete-sequence? s))
                (reset! buf "")
                (schedule-incomplete-flush! tui buf)))

          ;; Non-ESC — dispatch printable runs in bulk.
            :else
            ;; Non-ESC — dispatch printable runs in bulk. A run is everything
            ;; up to the first control char (<= 31): the editor inserts the
            ;; whole run at once, so a large paste is ONE insertion instead of
            ;; n per-char insertions into a growing buffer (O(n^2) for 100K
            ;; chars — measured ~0.64s per 50K). Control chars (CR/LF/Tab)
            ;; dispatch ALONE — the editor needs per-char handling for them,
            ;; and a control left in the buffer would stall the run-finder
            ;; forever. The buffer keeps only what remains after this pass.
            (let [ctrl (loop [i 0]
                         (when (< i (count s))
                           (if (<= (int (.charAt ^String s i)) 31)
                             i
                             (recur (inc i)))))
                  run-len (or ctrl (count s))
                  run (subs s 0 run-len)]
              (when (seq run)
                (dispatch-input! tui run))
              ;; dispatch the control chars one at a time (each ≤ 31)
              (let [rest-s (subs s run-len)]
                (doseq [c rest-s]
                  (dispatch-input! tui (str c))))
              (reset! buf ""))))))))

;; ─── Unbracketed paste detection (paste-like bursts) ────────────────────────
;; Bracketed paste (mode 2004) delivers paste content verbatim, but input
;; paths that bypass bracketing — Android IME text injection, terminals
;; without bracketed-paste support, tmux send-keys — deliver the raw bytes as
;; ordinary key events. A paste line ending then arrives as a lone CR and the
;; editor submits, executing pasted "/cmd" or "!cmd" text without the user
;; pressing Enter. Human typing cannot reach paste speed, so a CR that ends a
;; paste-like burst (several chars within a few ms) is rewritten to \n; only
;; an isolated CR (a real Enter press) keeps submitting. The rewritten CR's
;; LF half (CRLF line endings) is then swallowed so a \r\n pair still
;; produces a single newline (matching normalize-paste-text).

(def ^:private paste-burst-ms 100)
(def ^:private paste-burst-chars 4)
(def ^:private paste-lf-swallow-ms 50)

(defn- cr-in-paste-burst?
  "True when the CR arriving at NOW ends an unbracketed paste: at least
   PASTE-BURST-CHARS chars (the CR included) arrived within the last
   PASTE-BURST-MS and at least one of them is not itself a CR — an Enter key
   repeat stream of CRs must keep submitting. RECENT is a vector of
   [timestamp char] pairs including the current char."
  [recent now]
  (let [in-window (filter (fn [[ts _]] (>= ts (- now paste-burst-ms))) recent)]
    (boolean (and (>= (count in-window) paste-burst-chars)
                  (some #(not= \return (second %)) in-window)))))

(defn- paste-input-decision
  "Decide how to process the input char C arriving at NOW. RECENT holds
   [timestamp char] pairs of previously read chars, SWALLOW-LF the timestamp
   of a recently rewritten paste CR (nil when none). Returns a map with
   :append (the char to buffer), :drop (true when the char is swallowed — the
   LF half of a rewritten CRLF), and :new-swallow-lf (the flag value to keep)."
  [c now recent swallow-lf]
  (cond
    (and (= c \return) (cr-in-paste-burst? recent now))
    {:append "\n" :new-swallow-lf now}

    (and (= c \newline) swallow-lf (<= (- now swallow-lf) paste-lf-swallow-ms))
    {:drop true :new-swallow-lf nil}

    :else
    {:append (str c) :new-swallow-lf nil}))

(defn- normalize-input-batch!
  "Apply the paste-burst decision to a whole drained BATCH at once: returns
   the string to append to the input buffer (CRs that end a paste-like burst
   rewritten to \n; the LF half of a rewritten CRLF dropped). RECENT-CHARS
   and SWALLOW-LF are updated as a side effect, mirroring the per-char loop
   they replaced. Doing this once per batch instead of per char keeps large
   pastes O(n) — the per-char path appended to a growing string (O(n^2))."
  [batch recent-chars swallow-lf]
  (let [out (StringBuilder.)
        now (System/currentTimeMillis)]
    (doseq [c batch]
      (let [{:keys [append drop new-swallow-lf]}
            (paste-input-decision c now @recent-chars @swallow-lf)]
        (reset! swallow-lf new-swallow-lf)
        (swap! recent-chars
               (fn [ts]
                 (-> (conj ts [now c])
                     (->> (filter (fn [[t _]] (>= t (- now paste-burst-ms)))))
                     vec)))
        (when-not drop
          (.append out append))))
    (str out)))

(defn- start-input-reader [tui]
  (let [jline (.terminal @(:terminal tui))
        reader (.reader jline)
        read-fn (fn [timeout-ms] (.read reader timeout-ms))]
    ;; Track the current reader so a stale reader (from a suspended TUI
    ;; session) exits as soon as a fresh reader is installed by resume.
    (reset! (:current-reader tui) reader)
    (let [f (future
              (let [buf (atom "")
                    ;; [timestamp char] pairs of recently read chars, pruned
                    ;; to the paste-burst window on every read so the vector
                    ;; stays bounded; swallow-lf remembers a rewritten paste
                    ;; CR whose LF half may still arrive.
                    recent-chars (atom [])
                    swallow-lf (atom nil)]
                (while (and @(:running? tui) (not @(:stopped? tui))
                            (identical? reader @(:current-reader tui)))
                  (try
                    ;; Bounded read: data arrives immediately, but the idle
                    ;; timeout lets the loop re-check the stop conditions and
                    ;; lets terminal close() proceed promptly. A blocking
                    ;; read here deadlocks JLine's FFM terminal close on
                    ;; aarch64 Linux (jline3 #1909 — close waits for the
                    ;; in-flight read; the FFM pump thread never wakes it).
                    (let [ch (.read reader 100)]
                      (when (>= ch 0)
                        ;; Drain everything ALREADY QUEUED behind the first
                        ;; char (pi: stdin 'data' events deliver chunks, not
                        ;; single bytes). Feeding one byte per pass exposed
                        ;; every multi-byte sequence to a scheduling stall at
                        ;; each byte boundary — a >10ms stall between ESC and
                        ;; "[" of a paste marker flushed a phantom Escape and
                        ;; leaked the rest as literal text. Draining keeps a
                        ;; burst (escape sequence, bracketed paste, fast
                        ;; typing) in ONE process pass; the 1ms drain read only
                        ;; picks up bytes that are already queued, so idle
                        ;; latency is unaffected. A multibyte UTF-8 char whose
                        ;; second half is still in flight yields -2 here and
                        ;; simply finishes on the next loop iteration.
                        (let [batch (loop [acc (doto (StringBuilder.)
                                                 (.append (char ch)))]
                                      (let [more (.read reader 1)]
                                        (if (>= more 0)
                                          (recur (.append acc (char more)))
                                          (str acc))))]
                          ;; Normalize the whole batch once (paste-burst CR
                          ;; rewriting / LF swallowing), then a SINGLE append
                          ;; + process pass. Per-char processing made large
                          ;; pastes O(n^2): n appends to a growing buffer, n
                          ;; process passes each copying the remainder. An
                          ;; exception during processing still loses the
                          ;; remaining chars of the batch (they were already
                          ;; consumed from the reader) — the per-char try
                          ;; guard from the drain fix is kept around the
                          ;; process call.
                          (try
                            (let [append (normalize-input-batch! batch
                                                                 recent-chars
                                                                 swallow-lf)]
                              (when (seq append)
                                (swap! (:input-generation tui) inc)
                                (swap! buf str append)
                                (process-input-buffer! tui read-fn buf)))
                            (catch Exception e
                              (binding [*out* *err*]
                                (println "input:" (ex-message e))))))))
                    (catch Exception e
                      (when (and @(:running? tui)
                                 (identical? reader @(:current-reader tui)))
                        (binding [*out* *err*]
                          (println "input:" (ex-message e)))))))))]
      (reset! (:input-reader tui) f))))
;; ═══════════════════════════════════════════════════════════════════════════
;; Start / Stop
;; ═══════════════════════════════════════════════════════════════════════════

(defn tui-request-render
  "Request a render on the next frame. With FORCE (pi: requestRender(force)),
   all previous frame state is cleared so the next frame is a clearing full
   redraw regardless of diffing."
  [tui & [force]]
  (when force
    (reset! (:previous-lines tui) [])
    (reset! (:previous-width tui) -1)
    (reset! (:previous-height tui) -1)
    (reset! (:max-lines-rendered tui) 0)
    (reset! (:previous-kitty-image-ids tui) #{}))
  (reset! (:render-requested? tui) true))

(defn tui-stop [tui]
  (reset! (:stopped? tui) true)
  (reset! (:running? tui) false)
  ;; pi: TuiAltScreen.dispose() clears pending flashes on close
  (tui-flash-dispose! tui))

(defn tui-invalidate
  "Invalidate all components and overlays (pi: TUI.invalidate — used after
   cell-size changes so images re-render at the new dimensions)."
  [tui]
  (doseq [c @(:components tui)] (protocols/invalidate c))
  (doseq [o @(:overlays tui)] (protocols/invalidate (:component o))))

(defn tui-get-clear-on-shrink [tui] @(:clear-on-shrink? tui))

(defn tui-set-clear-on-shrink!
  "Enable/disable clearing empty rows when content shrinks (pi:
   setClearOnShrink)."
  [tui enabled?]
  (reset! (:clear-on-shrink? tui) enabled?))

(defn tui-get-full-redraw-count
  "Number of full redraws performed (pi: getFullRedrawCount)."
  [tui]
  @(:full-redraw-count tui))

(defn tui-query-terminal-background-color
  "Query the terminal's default background color via OSC 11; returns a
   promise resolving to {:r :g :b} or nil on timeout (pi:
   queryTerminalBackgroundColor)."
  [tui & {:keys [timeout-ms] :or {timeout-ms 500}}]
  (let [p (promise)]
    (swap! (:osc-11-queries tui)
           conj {:settled? (atom false)
                 :promise p
                 :timer (future
                          (Thread/sleep timeout-ms)
                          (settle-osc-11-query! tui nil p))})
    (reset! (:pending-osc-11? tui) true)
    (when-let [term @(:terminal tui)]
      (terminal/query-osc-11-background! term))
    p))

(defn tui-on-terminal-color-scheme-change
  "Register a listener called with :dark/:light on terminal color scheme
   reports (CSI ? 997 n). Returns an unsubscribe fn (pi:
   onTerminalColorSchemeChange)."
  [tui f]
  (swap! (:color-scheme-listeners tui) conj f)
  (fn [] (swap! (:color-scheme-listeners tui) disj f)))

(defn tui-query-terminal-color-scheme
  "Query the terminal color scheme preference (CSI ? 996 n); returns a
   promise resolving to :dark / :light or nil on timeout (pi:
   queryTerminalColorScheme)."
  [tui & {:keys [timeout-ms] :or {timeout-ms 500}}]
  (let [p (promise)
        unsub (tui-on-terminal-color-scheme-change
               tui (fn [scheme]
                     (when-not (realized? p)
                       (deliver p scheme))))]
    (future
      (Thread/sleep timeout-ms)
      (deliver p nil)
      (unsub))
    (when-let [term @(:terminal tui)]
      (terminal/query-color-scheme! term))
    p))

(defn tui-set-terminal-color-scheme-notifications
  "Enable/disable unsolicited terminal color scheme reports (CSI ? 2031 h/l),
   written immediately when the TUI is running (pi:
   setTerminalColorSchemeNotifications)."
  [tui enabled?]
  (reset! (:color-scheme-notifications-enabled? tui) enabled?)
  (when @(:running? tui)
    (when-let [term @(:terminal tui)]
      (terminal/set-color-scheme-notifications! term enabled?))))

(defn- run-render-loop!
  "Start the terminal (raw mode) and run the render loop until the TUI is
   suspended (tui-suspend!) or stopped (tui-stop). Restores and closes the
   terminal on exit; tui-resume! creates a fresh one."
  [tui]
  ;; Activate the negotiation interception BEFORE the terminal starts: the
  ;; reader thread may process the terminal's response to JLine's own DA
  ;; query (written inside start!) before the query below is sent — the flag
  ;; must already be set or those bytes would be parsed as keys (pi sets
  ;; keyboardProtocolPushed before writing the query).
  (reset! (:keyboard-protocol-pushed? tui) true)
  (reset! (:negotiation-buffer tui) "")
  (clear-negotiation-timer! tui)
  (let [started (terminal/start! @(:terminal tui)
                                 (fn [_] nil)
                                 (fn [] (tui-request-render tui)))
        jline (.terminal started)
        hardware-cursor-row (atom 0)
        previous-viewport-top (atom 0)]
    ;; Make the started record (with the live writer) visible to the input
    ;; path: negotiation / OSC 11 / color-scheme handlers write through
    ;; @(:terminal tui), and the unstarted record's writer is nil — writes
    ;; would be silently dropped.
    (reset! (:terminal tui) started)
    (terminal/hide-cursor! started)
    ;; Pi: no clear-screen on start — preserves prior terminal output above the TUI
    (tui-request-render tui)
    ;; Kitty keyboard protocol negotiation (pi: queryAndEnableKittyProtocol) —
    ;; responses are intercepted by the input reader; modifyOtherKeys is the
    ;; fallback for terminals without Kitty support. The flag was set before
    ;; start! so even JLine's own DA response is intercepted.
    (terminal/query-kitty-protocol! started)
    (when @(:color-scheme-notifications-enabled? tui)
      (terminal/set-color-scheme-notifications! started true))
    ;; Query the cell size (CSI 16 t) when the terminal supports images — the
    ;; response is consumed ungated by the input path (pi: queryCellSize /
    ;; consumeCellSizeResponse).
    (when (:images (img/get-capabilities))
      (terminal/query-cell-size! started))
    (try
      (loop []
        (when @(:running? tui)
          (let [w (.getWidth jline)
                h (.getHeight jline)]
            ;; Terminal resize detection. JLine's native WINCH handling does
            ;; not work under babashka's GraalVM native image (no native
            ;; signal handlers are registered), so the on-resize callback is
            ;; never invoked and nothing re-renders on resize — the editor
            ;; keeps wrapping at the pre-resize width until the next input
            ;; event (pi: terminal.on("resize") → requestRender).
            ;; getWidth/getHeight are live terminal queries, so polling them
            ;; here (16ms cadence) catches the change reliably; the existing
            ;; width-changed/height-changed logic then does the full redraw.
            (when (and (pos? @(:previous-width tui)) (not= @(:previous-width tui) w))
              (tui-request-render tui))
            (when (and (pos? @(:previous-height tui)) (not= @(:previous-height tui) h))
              (tui-request-render tui))
            (when @(:render-requested? tui)
              (reset! (:render-requested? tui) false)
              ;; Base content: the whole UI is one flat document — the stack
              ;; layout renders every component at natural height, so the total
              ;; may exceed the screen and the render loop scrolls the overflow
              ;; into the native terminal scrollback (pi: main-screen model).
              ;; Visible overlays are then composited on top (pi: compositeOverlays).
              (let [base-lines (stack/render-stack @(:components tui) w)
                    raw-lines (composite-overlays tui base-lines w h)
                    cursor-result (extract-cursor-position raw-lines h)
                    cursor (:cursor cursor-result)
                    lines (:lines cursor-result)
                    ;; pi: normalizeTerminalOutput — Thai/Lao AM decomposition +
                    ;; tab expansion — runs before applyLineResets (pi
                    ;; normalizes in applyLineResets, after cursor extraction).
                    ;; Lines are written at their natural width like pi (no
                    ;; global padding): the diff's \x1b[2K clears each rewritten
                    ;; line before the write, so full-width padding is not
                    ;; needed for clean rewrites — and it actively broke
                    ;; kitty-image-reserved-rows, whose blank-row walk must run
                    ;; on raw unpadded lines (padded spaces terminated it
                    ;; immediately, collapsing every image block to one row).
                    lines (mapv utils/normalize-terminal-output lines)
                    lines (composite-flashes @(:flashes tui) lines w h)
                    ;; pi: applyLineResets — every non-image line ends with a
                    ;; full SGR + OSC 8 reset (SEGMENT_RESET) so a truncated
                    ;; line can never leave active attributes or an open
                    ;; hyperlink bleeding into the next line; the diff's
                    ;; partial rewrites rely on each line being self-cleaning.
                    lines (mapv (fn [line]
                                  (if (img/is-image-line line)
                                    line
                                    (str line utils/SEGMENT-RESET)))
                                lines)
                    prev @(:previous-lines tui)
                    prev-w @(:previous-width tui)
                    prev-h @(:previous-height tui)
                    prev-count (count prev)
                    new-count (count lines)
                    width-changed (and (not (zero? prev-w)) (not= prev-w w))
                    height-changed (and (not (zero? prev-h)) (not= prev-h h))
                    termux? (boolean (System/getenv "TERMUX_VERSION"))
                    first-render? (and (empty? prev)
                                       (not width-changed)
                                       (not height-changed))
                    ;; One write per frame: the whole frame's output (sync markers,
                    ;; cursor moves, line rewrites, cursor hide) accumulates into SB
                    ;; and is written+flushed once (pi: one write per render).
                    ;; Pi-state: the viewport top persists across frames
                    prev-buffer-length (if (pos? prev-h) (+ @previous-viewport-top prev-h) h)
                    prev-viewport-top (atom (if height-changed
                                              (max 0 (- prev-buffer-length h))
                                              @previous-viewport-top))
                    viewport-top (atom @prev-viewport-top)
                    ;; The frame buffer is an atom so a mid-diff full-redraw
                    ;; fallback can discard the partial diff output (pi: the
                    ;; diff buffer is a local that fullRender replaces).
                    sb (atom (StringBuilder.))
                    emit! (fn [s] (.append @sb s))
                    debug-redraw? @(:debug-redraw? tui)
                    log-redraw! (fn [reason]
                                  (when debug-redraw?
                                    (append-log!
                                     (log-path "kmet-debug-render.log")
                                     (str "[" (java.time.LocalDateTime/now) "] fullRender: "
                                          reason " (prev=" prev-count ", new=" new-count
                                          ", height=" h ")\n"))))
                    compute-line-diff (fn [target-row]
                                        (- (- target-row @viewport-top)
                                           (- @hardware-cursor-row @prev-viewport-top)))
                    position-hardware-cursor (fn [cursor-pos total-lines]
                                               (if (or (nil? cursor-pos) (<= total-lines 0))
                                                 (emit! "\u001b[?25l")
                                                 (let [target-row (max 0 (min (:row cursor-pos) (dec total-lines)))
                                                       target-col (max 0 (:col cursor-pos))
                                                       row-delta (- target-row @hardware-cursor-row)
                                                       buf (str (cond
                                                                  (pos? row-delta) (str "\u001b[" row-delta "B")
                                                                  (neg? row-delta) (str "\u001b[" (- row-delta) "A")
                                                                  :else "")
                                                                "\u001b[" (inc target-col) "G")]
                                                   (when (seq buf) (emit! buf))
                                                   (reset! hardware-cursor-row target-row)
                                                   (if @(:show-hardware-cursor? tui)
                                                     (emit! "\u001b[?25h")
                                                     (emit! "\u001b[?25l")))))
                    do-full-redraw (fn do-full-redraw [clear?]
                                     (swap! (:full-redraw-count tui) inc)
                                     (emit! CSI-2026-H)
                                     (when clear?
                                       (doseq [id @(:previous-kitty-image-ids tui)]
                                         (emit! (img/delete-kitty-image id)))
                                       ;; The full redraw re-emits the whole
                                       ;; transcript below, so the scrollback MUST
                                       ;; be cleared too: 2J alone leaves the old
                                       ;; transcript in the scrollback and the
                                       ;; re-emit appends after it, duplicating
                                       ;; every line (pi issue #6050: "without
                                       ;; clearing scrollback the whole history
                                       ;; duplicates"). Windows Terminal scrolls
                                       ;; to the top on 3J (microsoft/terminal
                                       ;; #20370, being fixed upstream) — pi
                                       ;; accepts that over duplicated output.
                                       (emit! "\u001b[2J\u001b[H\u001b[3J"))
                                     (loop [i 0]
                                       (when (< i new-count)
                                         (when (pos? i) (emit! "\r\n"))
                                         (let [rows (if (img/is-image-line (nth lines i))
                                                      (kitty-image-reserved-rows lines i)
                                                      1)]
                                           (if (and (> rows 1) (<= rows h))
                                             (do (dotimes [_ (dec rows)] (emit! "\r\n"))
                                                 (emit! (str "\u001b[" (dec rows) "A"))
                                                 (emit! (nth lines i))
                                                 (emit! (str "\u001b[" (dec rows) "B"))
                                                 (recur (+ i rows)))
                                             (do (emit! (nth lines i))
                                                 (recur (inc i)))))))
                                     (emit! CSI-2026-L)
                                     (reset! hardware-cursor-row (max 0 (dec new-count)))
                                     (reset! viewport-top (max 0 (- (max h new-count) h)))
                                     (if clear?
                                       (reset! (:max-lines-rendered tui) new-count)
                                       (swap! (:max-lines-rendered tui) max new-count))
                                     (position-hardware-cursor cursor new-count))
                    main-diff (fn main-diff []
                                (let [max-lines (max new-count prev-count)
                                      [first-changed last-changed]
                                      (loop [i 0, fc -1, lc -1]
                                        (if (< i max-lines)
                                          (let [old-line (if (< i prev-count) (nth prev i) "")
                                                new-line (if (< i new-count) (nth lines i) "")]
                                            (if (not= old-line new-line)
                                              (recur (inc i) (if (neg? fc) i fc) i)
                                              (recur (inc i) fc lc)))
                                          [fc lc]))
                                      appended? (> new-count prev-count)
                                      [first-changed last-changed]
                                      (if appended?
                                        [(if (neg? first-changed) prev-count first-changed)
                                         (dec new-count)]
                                        [first-changed last-changed])
                                      [first-changed last-changed]
                                      (if (not (neg? first-changed))
                                        (expand-changed-range-for-kitty-images
                                         first-changed last-changed prev lines)
                                        [first-changed last-changed])
                                      append-start? (and appended?
                                                         (= first-changed prev-count)
                                                         (pos? first-changed))
                                      mid-full-redraw! (fn [reason]
                                                         (log-redraw! reason)
                                                         (reset! sb (StringBuilder.))
                                                         (do-full-redraw true))]
                                  (cond
                                    (neg? first-changed)
                                    (do (position-hardware-cursor cursor new-count)
                                        (reset! viewport-top @prev-viewport-top))

                                    (>= first-changed new-count)
                                    (if (> prev-count new-count)
                                      (let [target-row (max 0 (dec new-count))]
                                        (if (< target-row @prev-viewport-top)
                                          (mid-full-redraw! (str "deleted lines moved viewport up ("
                                                                 target-row " < " @prev-viewport-top ")"))
                                          (let [line-diff (compute-line-diff target-row)
                                                extra-lines (- prev-count new-count)]
                                            (if (> extra-lines h)
                                              (mid-full-redraw! (str "extraLines > height ("
                                                                     extra-lines " > " h ")"))
                                              (let [clear-start-offset (if (zero? new-count) 0 1)
                                                    move-back (max 0 (- (+ extra-lines clear-start-offset) 1))]
                                                (emit! CSI-2026-H)
                                                (emit! (delete-changed-kitty-images
                                                        first-changed last-changed prev))
                                                (when (pos? line-diff)
                                                  (emit! (str "\u001b[" line-diff "B")))
                                                (when (neg? line-diff)
                                                  (emit! (str "\u001b[" (- line-diff) "A")))
                                                (emit! "\r")
                                                (when (and (pos? extra-lines) (pos? clear-start-offset))
                                                  (emit! (str "\u001b[" clear-start-offset "B")))
                                                (dotimes [i extra-lines]
                                                  (emit! "\r\u001b[2K")
                                                  (when (< i (dec extra-lines))
                                                    (emit! "\u001b[1B")))
                                                (when (pos? move-back)
                                                  (emit! (str "\u001b[" move-back "A")))
                                                (emit! CSI-2026-L)
                                                (reset! hardware-cursor-row target-row)
                                                (position-hardware-cursor cursor new-count)
                                                (reset! viewport-top @prev-viewport-top))))))
                                      (do (position-hardware-cursor cursor new-count)
                                          (reset! viewport-top @prev-viewport-top)))

                                    (< first-changed @prev-viewport-top)
                                    (mid-full-redraw! (str "firstChanged < viewportTop ("
                                                           first-changed " < " @prev-viewport-top ")"))

                                    :else
                                    (let [prev-viewport-bottom (+ @prev-viewport-top h -1)
                                          move-target-row (if append-start? (dec first-changed) first-changed)]
                                      (when (> move-target-row prev-viewport-bottom)
                                        (let [current-screen-row (max 0 (min (dec h)
                                                                             (- @hardware-cursor-row @prev-viewport-top)))
                                              move-to-bottom (- (dec h) current-screen-row)
                                              scroll (- move-target-row prev-viewport-bottom)]
                                          (when (pos? move-to-bottom)
                                            (emit! (str "\u001b[" move-to-bottom "B")))
                                          (emit! (apply str (repeat scroll "\r\n")))
                                          (swap! prev-viewport-top + scroll)
                                          (reset! viewport-top @prev-viewport-top)
                                          (reset! hardware-cursor-row move-target-row)))
                                      (let [line-diff (compute-line-diff move-target-row)
                                            render-end (min last-changed (dec new-count))
                                            final-cursor-row (volatile! render-end)]
                                        (emit! CSI-2026-H)
                                        (emit! (delete-changed-kitty-images
                                                first-changed last-changed prev))
                                        (when (pos? line-diff)
                                          (emit! (str "\u001b[" line-diff "B")))
                                        (when (neg? line-diff)
                                          (emit! (str "\u001b[" (- line-diff) "A")))
                                        (emit! (if append-start? "\r\n" "\r"))
                                        (loop [i first-changed]
                                          (when (<= i render-end)
                                            (when (> i first-changed) (emit! "\r\n"))
                                            (let [line (nth lines i)
                                                  is-image (img/is-image-line line)
                                                  rows (if is-image
                                                         (kitty-image-reserved-rows lines i render-end)
                                                         1)]
                                              (if (> rows 1)
                                                (let [image-start-screen-row (- i @viewport-top)]
                                                  (if (or (< image-start-screen-row 0)
                                                          (> (+ image-start-screen-row rows) h))
                                                    (mid-full-redraw! (str "kitty image pre-clear would scroll ("
                                                                           image-start-screen-row " + " rows " > " h ")"))
                                                    (do (emit! "\u001b[2K")
                                                        (dotimes [_ (dec rows)] (emit! "\r\n\u001b[2K"))
                                                        (emit! (str "\u001b[" (dec rows) "A"))
                                                        (emit! line)
                                                        (emit! (str "\u001b[" (dec rows) "B"))
                                                        (recur (+ i rows)))))
                                                (do (emit! "\u001b[2K")
                                                    ;; A line wider than the terminal must NEVER kill the app — that
                                                    ;; leaves a frozen frame on screen and a dead reader ("fully
                                                    ;; stuck, had to kill it from the OS"). Log it and truncate the
                                                    ;; line instead so the TUI keeps running; the ANSI-aware slice
                                                    ;; drops the pending SGR/OSC reset, so re-append it to keep the
                                                    ;; truncated line self-cleaning.
                                                    (let [line (if (and (not is-image)
                                                                        (> (utils/visible-width line) w))
                                                                 (do (write-crash-log! lines w i (utils/visible-width line))
                                                                     (str (:text (utils/slice-with-width line 0 w :strict? true))
                                                                          utils/SEGMENT-RESET))
                                                                 line)]
                                                      (emit! line)
                                                      (recur (inc i))))))))
                                        (when (> prev-count new-count)
                                          (when (< render-end (dec new-count))
                                            (emit! (str "\u001b[" (- (dec new-count) render-end) "B"))
                                            (vreset! final-cursor-row (dec new-count)))
                                          (let [extra-lines (- prev-count new-count)]
                                            (dotimes [_ extra-lines]
                                              (emit! "\r\n\u001b[2K"))
                                            (emit! (str "\u001b[" extra-lines "A"))))
                                        (emit! CSI-2026-L)
                                        (reset! hardware-cursor-row @final-cursor-row)
                                        (swap! (:max-lines-rendered tui) max new-count)
                                        (reset! viewport-top
                                                (max @prev-viewport-top
                                                     (- @final-cursor-row h -1)))
                                        (position-hardware-cursor cursor new-count))))))]
                (cond
                  first-render?
                  (do (log-redraw! "first render")
                      (do-full-redraw false))
                  width-changed
                  (do (log-redraw! (str "terminal width changed (" prev-w " -> " w ")"))
                      (do-full-redraw true))
                  (and height-changed (not termux?))
                  (do (log-redraw! (str "terminal height changed (" prev-h " -> " h ")"))
                      (do-full-redraw true))
                  (and @(:clear-on-shrink? tui)
                       (< new-count @(:max-lines-rendered tui))
                       (empty? @(:overlays tui)))
                  (do (log-redraw! (str "clearOnShrink (maxLinesRendered="
                                        @(:max-lines-rendered tui) ")"))
                      (do-full-redraw true))
                  :else
                  (main-diff))
                (reset! previous-viewport-top @viewport-top)
                (when @(:tui-debug? tui)
                  (tui-debug-dump! prev lines (str @sb) w h @viewport-top @hardware-cursor-row))
                (when (pos? (.length @sb))
                  (terminal/write-output started (str @sb)))
                (reset! (:previous-lines tui) lines)
                (reset! (:previous-width tui) w)
                (reset! (:previous-height tui) h)
                (reset! (:previous-kitty-image-ids tui)
                        (if (some img/is-image-line lines)
                          (collect-kitty-image-ids lines)
                          #{})))))

          (Thread/sleep 16)
          (recur)))
      (finally
        (reset! (:running? tui) false)
        ;; Disable the keyboard protocols: drain on final stop so late key
        ;; release sequences don't leak to the parent shell (pi: drainInput);
        ;; on suspend only disable so the external program inherits a clean
        ;; terminal (pi: stop disables, drain is quit-only).
        (reset! (:keyboard-protocol-pushed? tui) false)
        (reset! (:negotiation-buffer tui) "")
        (clear-negotiation-timer! tui)
        (clear-terminal-response-timer! tui)
        (reset! (:terminal-response-buffer tui) "")
        (reset! (:pending-osc-11? tui) false)
        (when @(:color-scheme-notifications-enabled? tui)
          (terminal/set-color-scheme-notifications! started false))
        (if @(:stopped? tui)
          (terminal/drain-input! started)
          (terminal/disable-kitty-protocol! started))
        ;; Pi: on final stop position the cursor at end of content so the
        ;; shell prompt appears below and the user can scroll up to review
        ;; the session. On suspend the position is left untouched for the
        ;; external program that takes over the terminal.
        (when @(:stopped? tui)
          (let [prev-lines @(:previous-lines tui)]
            (when (seq prev-lines)
              (let [target-row (count prev-lines)  ;; row past last content line
                    row-delta (- target-row @hardware-cursor-row)]
                (terminal/write-output started " ")
                (when (pos? row-delta)
                  (terminal/write-output started (str "\u001b[" row-delta "B")))
                (terminal/write-output started "\r\n")))))
        (terminal/show-cursor! started)
        (terminal/stop! started)))))
(defn tui-start
  "Start the TUI: enter raw mode, start the input reader and render loop.
   Blocks the calling thread until tui-stop is called."
  [tui]
  (reset! (:running? tui) true)
  (reset! (:stopped? tui) false)
  (start-input-reader tui)
  (reset! (:render-loop tui) (future
                               (try (run-render-loop! tui)
                                    (catch Throwable t
                                      ;; A render crash must not leave the app
                                      ;; hanging with a dead render loop and a
                                      ;; dead reader: log the stack, then stop
                                      ;; so the caller unwinds and -main reports
                                      ;; the error (pi: the render loop never
                                      ;; crashes silently).
                                      (try (with-open [w (java.io.FileWriter. "render-crash.log" true)]
                                             (.write w (str t "\n" (clojure.string/join "\n" (.getStackTrace t)) "\n")))
                                           (catch Exception _))
                                      (tui-stop tui)))))
  (tui-request-render tui)
  ;; Block until a final stop is requested
  (loop []
    (when-not @(:stopped? tui)
      (Thread/sleep 50)
      (recur)))
  ;; Final stop: join both loops (the render loop's finally restores the
  ;; terminal; closing it unblocks the input reader so it exits too)
  (reset! (:running? tui) false)
  (when-let [f @(:render-loop tui)]
    (deref f 3000 nil))
  (reset! (:render-loop tui) nil)
  (when-let [f @(:input-reader tui)]
    (deref f 3000 nil))
  (reset! (:input-reader tui) nil)
  nil)

(defn tui-suspend!
  "Suspend the TUI: stop the render loop and input reader, restore the
   terminal to its normal (non-raw) state, and close it so a spawned
   external program (e.g. $EDITOR) has exclusive access to the terminal.
   Call tui-resume! to restart. May be called from the input reader thread
   (the thread that drives the external program afterwards)."
  [tui]
  (reset! (:running? tui) false)
  ;; The render loop's finally restores and closes the terminal, which also
  ;; unblocks any blocked input read. Wait for it to finish.
  (when-let [f @(:render-loop tui)]
    (deref f 3000 nil))
  (reset! (:render-loop tui) nil)
  nil)

(defn tui-resume!
  "Restart the TUI after tui-suspend!: create a fresh terminal, re-enter raw
   mode, and restart the render loop and input reader. Forces a clearing full
   redraw: the external program that took over the terminal restores the
   pre-suspend frame on exit, so the re-emit must clear the stale screen and
   scrollback first or old and new content overlap (pi: requestRender(true)
   after ui.start() — prev-width -1 routes the first frame through the
   width-changed clearing path)."
  [tui]
  (reset! (:terminal tui) (terminal/create-terminal))
  (reset! (:running? tui) true)
  (reset! (:stopped? tui) false)
  ;; Drop any pending input flush from before the suspend (pi:
  ;; beforeTerminalStart resets selection state).
  (clear-incomplete-flush! tui)
  (start-input-reader tui)
  (reset! (:render-loop tui) (future (run-render-loop! tui)))
  (tui-request-render tui true)
  nil)

;; ═══════════════════════════════════════════════════════════════════════════
;; Re-exports — convenience aliases for all public symbols.
;; ═══════════════════════════════════════════════════════════════════════════

;; Terminal
(def ITerminal terminal/ITerminal)
(def create-terminal terminal/create-terminal)

;; Keys
(def matches-key? keys/matches-key?)
(def parse-key keys/parse-key)
(def is-key-release? keys/is-key-release?)
(def is-key-repeat? keys/is-key-repeat?)
(def set-kitty-active! keys/set-kitty-active!)
(def kitty-active? keys/kitty-active?)
(def KEY-UP keys/KEY-UP)
(def KEY-DOWN keys/KEY-DOWN)
(def KEY-LEFT keys/KEY-LEFT)
(def KEY-RIGHT keys/KEY-RIGHT)
(def KEY-ENTER keys/KEY-ENTER)
(def KEY-ESC keys/KEY-ESC)
(def KEY-TAB keys/KEY-TAB)
(def KEY-BACKSPACE keys/KEY-BACKSPACE)
(def KEY-DELETE keys/KEY-DELETE)
(def KEY-HOME keys/KEY-HOME)
(def KEY-END keys/KEY-END)
(def KEY-PAGE-UP keys/KEY-PAGE-UP)
(def KEY-PAGE-DOWN keys/KEY-PAGE-DOWN)
(def KEY-SPACE keys/KEY-SPACE)
(def KEY-INSERT keys/KEY-INSERT)
(def ctrl keys/ctrl)
(def shift keys/shift)
(def alt keys/alt)

;; Utils
(def visible-width utils/visible-width)
(def truncate-to-width utils/truncate-to-width)
(def wrap-text-with-ansi utils/wrap-text-with-ansi)
(def apply-background-to-line utils/apply-background-to-line)
(def strip-ansi-codes utils/strip-ansi-codes)
(def sgr utils/sgr)
(def slice-by-column utils/slice-by-column)

;; Components
(def make-text text/make-text)
(def text-set! text/text-set!)
(def make-spacer spacer/make-spacer)
(def make-container container/make-container)
(def container-add-child container/container-add-child)
(def container-remove-child container/container-remove-child)
(def container-clear container/container-clear)
(def make-box box/make-box)
(def box-add-child box/box-add-child)
(def box-remove-child box/box-remove-child)
(def box-clear box/box-clear)
(def box-set-bg-fn box/box-set-bg-fn)
(def make-input input/make-input)
(def input-set-value! input/input-set-value!)
(def input-get-value input/input-get-value)
(def input-set-on-submit! input/input-set-on-submit!)
(def input-set-on-escape! input/input-set-on-escape!)
(def make-editor editor/make-editor)
;; IEditorComponent members dispatch through the protocol when the target
;; implements it (custom editors from extensions), else the field-based fn
;; (Editor-shaped records). `ed` avoids shadowing the `editor` ns alias.
(defn editor-set-text! [ed text]
  (if (satisfies? protocols/IEditorComponent ed)
    (protocols/editor-set-text! ed text)
    (editor/editor-set-text! ed text)))
(defn editor-get-text [ed]
  (if (satisfies? protocols/IEditorComponent ed)
    (protocols/editor-get-text ed)
    (editor/editor-get-text ed)))
(defn editor-set-on-submit! [ed f]
  (if (satisfies? protocols/IEditorComponent ed)
    (protocols/editor-set-on-submit! ed f)
    (editor/editor-set-on-submit! ed f)))
(defn editor-set-on-change! [ed f]
  (if (satisfies? protocols/IEditorComponent ed)
    (protocols/editor-set-on-change! ed f)
    (editor/editor-set-on-change! ed f)))
(defn editor-get-expanded-text [ed]
  (if (satisfies? protocols/IEditorComponent ed)
    (protocols/editor-get-expanded-text ed)
    (editor/editor-get-expanded-text ed)))
(defn editor-set-autocomplete-provider! [ed provider]
  (if (satisfies? protocols/IEditorComponent ed)
    (protocols/editor-set-autocomplete-provider! ed provider)
    (editor/editor-set-autocomplete-provider! ed provider)))
(defn editor-set-autocomplete-max-visible! [ed n]
  (if (satisfies? protocols/IEditorComponent ed)
    (protocols/editor-set-autocomplete-max-visible! ed n)
    (editor/editor-set-autocomplete-max-visible! ed n)))
(defn editor-set-padding-x! [ed n]
  (if (satisfies? protocols/IEditorComponent ed)
    (protocols/editor-set-padding-x! ed n)
    (editor/editor-set-padding-x! ed n)))
(defn editor-insert-text-at-cursor! [ed text]
  (if (satisfies? protocols/IEditorComponent ed)
    (protocols/editor-insert-text-at-cursor! ed text)
    (editor/editor-insert-text-at-cursor! ed text)))
(defn editor-add-to-history! [ed text]
  (if (satisfies? protocols/IEditorComponent ed)
    (protocols/editor-add-to-history! ed text)
    (editor/editor-push-history! ed text)))
(def editor-set-on-action! editor/editor-set-on-action!)
(def editor-set-on-tab! editor/editor-set-on-tab!)
(def editor-set-autocomplete-theme! editor/editor-set-autocomplete-theme!)
(def editor-autocomplete-active? editor/editor-autocomplete-active?)
(def editor-push-history! editor/editor-push-history!)
(def editor-get-history editor/editor-get-history)
(def editor-set-history! editor/editor-set-history!)
(def editor-get-paste editor/editor-get-paste)
(def editor-set-height! editor/editor-set-height!)
(def editor-set-terminal-rows! editor/editor-set-terminal-rows!)
(def editor-get-text-length editor/editor-get-text-length)

;; SelectList
(def make-select-list select-list/make-select-list)
(def select-list-set-items! select-list/select-list-set-items!)
(def select-list-get-selected select-list/select-list-get-selected)
(def select-list-set-theme! select-list/select-list-set-theme!)
(def default-theme select-list/default-theme)

;; SettingsList
(def make-settings-list settings-list/make-settings-list)
(def settings-list-set-on-escape! settings-list/settings-list-set-on-escape!)
(def settings-list-get-item settings-list/settings-list-get-item)
(def settings-list-set-value! settings-list/settings-list-set-value!)

;; Markdown
(def make-markdown markdown/make-markdown)
(def markdown-set-text! markdown/markdown-set-text!)
(def markdown-append! markdown/markdown-append!)
(def markdown-set-theme! markdown/markdown-set-theme!)
(def markdown-set-padding-x! markdown/markdown-set-padding-x!)
(def markdown-get-text markdown/markdown-get-text)

;; Spinner
(def make-spinner spinner/make-spinner)
(def spinner-start! spinner/spinner-start!)
(def spinner-set-start! spinner/spinner-set-start!)
(def spinner-stop! spinner/spinner-stop!)
(def spinner-active? spinner/spinner-active?)
(def spinner-set-text! spinner/spinner-set-text!)
(def spinner-set-prefix! spinner/spinner-set-prefix!)
(def spinner-set-spinner-color-fn! spinner/spinner-set-spinner-color-fn!)
(def spinner-set-message-color-fn! spinner/spinner-set-message-color-fn!)

;; ScrollView + stack layout
(def IScrollView scroll-view/IScrollView)
(def make-scroll-view scroll-view/make-scroll-view)
(def scroll-view-update-layout! scroll-view/update-layout!)
(def scroll-view-render-window scroll-view/render-window)
(def scroll-view-scroll-by! scroll-view/scroll-by!)
(def scroll-view-scroll-to! scroll-view/scroll-to!)
(def scroll-view-scroll-to-start! scroll-view/scroll-to-start!)
(def scroll-view-scroll-to-end! scroll-view/scroll-to-end!)
(def scroll-view-scroll-top scroll-view/scroll-top)
(def scroll-view-follows-end? scroll-view/follows-end?)
(def render-stack stack/render-stack)

;; AltScreenFlash — transient messages composited over the screen bottom
(def make-alt-screen-flash alt-screen-flash/make-alt-screen-flash)
(def alt-screen-flash! alt-screen-flash/alt-screen-flash!)
(def alt-screen-flash-dispose! alt-screen-flash/alt-screen-flash-dispose!)

;; CancellableLoader — Loader cancellable with Escape (pi: BorderedLoader)
(def make-cancellable-loader cancellable-loader/make-cancellable-loader)
(def cancellable-loader-aborted? cancellable-loader/cancellable-loader-aborted?)
(def cancellable-loader-signal cancellable-loader/cancellable-loader-signal)
(def cancellable-loader-set-on-abort! cancellable-loader/cancellable-loader-set-on-abort!)
(def cancellable-loader-dispose! cancellable-loader/cancellable-loader-dispose!)

;; TruncatedText — single-line truncating text
(def make-truncated-text truncated-text/make-truncated-text)
(def truncated-text-set-text! truncated-text/truncated-text-set-text!)

;; DynamicBorder — theme-colored border line (pi: DynamicBorder)
(def make-dynamic-border dynamic-border/make-dynamic-border)

;; HStack / VStack — flexbox-style horizontal / vertical stacks
(def make-h-stack h-stack/make-h-stack)
(def h-stack-add-child! h-stack/h-stack-add-child!)
(def h-stack-remove-child! h-stack/h-stack-remove-child!)
(def h-stack-clear! h-stack/h-stack-clear!)
(def h-stack-set-gap! h-stack/h-stack-set-gap!)
(def h-stack-set-align! h-stack/h-stack-set-align!)
(def make-v-stack v-stack/make-v-stack)
(def v-stack-add-child! v-stack/v-stack-add-child!)
(def v-stack-remove-child! v-stack/v-stack-remove-child!)
(def v-stack-clear! v-stack/v-stack-clear!)
(def v-stack-set-gap! v-stack/v-stack-set-gap!)

;; Stack sizing (pi: allocateStackSizes) — shared by HStack/VStack
(def stack-entry? stack/stack-entry?)
(def entry-component stack/entry-component)
(def visible-stack-entries stack/visible-stack-entries)
(def allocate-stack-sizes stack/allocate-stack-sizes)

