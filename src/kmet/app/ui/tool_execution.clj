(ns kmet.app.ui.tool-execution
  "ToolExecutionComponent component — Pi's ToolExecutionComponent.
   Uses a Box (with status background) wrapping a Container that holds
   the call-render and result-render children.
   Matching Pi architecture: Box handles padding/background/caching.
   Timing is managed internally (started-at on first content, ended-at on error/finalize)."
  (:require [kmet.tui.protocols :as protocols]
            [kmet.tui.theme :as theme]
            [kmet.tui.utils :as utils]
            [kmet.tui.components.text :as text]
            [kmet.tui.components.box :as box]
            [kmet.tui.components.container :as container]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [babashka.fs :as fs]
            [kmet.app.keybindings :as app-kb]
            [kmet.app.bash-executor :as bash-exec]
            [kmet.tui.components.spacer :as spacer]
            [kmet.tui.components.image :as ic]
            [kmet.tui.terminal-image :as timg]
            [kmet.tui.macros :refer [defsetter defgetter defcomponent]]))

;; ─── Edit diff preview ─────────────────────────────────────────────────────
;; Compute a preview of an edit without actually modifying the file.
;; Reads the file, tries the replacement, and returns a diff-like structure.

;; Number of context lines to show before/after the edit region.
(def preview-context-lines 3)

(defn- compute-edit-preview
  "Try to apply an edit in memory and return diff information.
   Args: path, old-text, new-text
   Returns: {:success? bool :diff-lines [[:context|:remove|:add str] ...] :error str?}
   When old-text is not found, returns {:success? false :error ...}."
  [path old-text new-text]
  (try
    (let [f (io/file path)]
      (if-not (fs/exists? f)
        {:success? false :error (str "File not found: " path)}
        (let [content (slurp f)
              idx (str/index-of content old-text)]
          (if (nil? idx)
            {:success? false :error (str "Could not find old-text in " path)}
            (let [content-lines (str/split-lines content)
                  ;; Find which line old-text starts on
                  before-old (subs content 0 idx)
                  start-line (count (str/split-lines before-old))
                  old-lines (str/split-lines old-text)
                  new-lines (str/split-lines new-text)
                  num-old (count old-lines)
                  num-new (count new-lines)
                  end-line (+ start-line num-old -1)
                  ;; Context window
                  ctx-start (max 0 (- start-line preview-context-lines))
                  ctx-end (min (count content-lines) (+ end-line preview-context-lines 1))
                  ;; Build diff lines
                  diff-lines (atom [])]
              ;; Lines before the edit (context)
              (doseq [i (range ctx-start start-line)]
                (swap! diff-lines conj [:context (nth content-lines i)]))
              ;; Removed lines
              (doseq [line old-lines]
                (swap! diff-lines conj [:remove line]))
              ;; Added lines
              (doseq [line new-lines]
                (swap! diff-lines conj [:add line]))
              ;; Lines after the edit (context)
              (when (< (dec end-line) (dec ctx-end))
                (doseq [i (range (inc end-line) ctx-end)]
                  (swap! diff-lines conj [:context (nth content-lines i)])))
              {:success? true
               :diff-lines @diff-lines
               :num-additions num-new
               :num-removals num-old})))))
    (catch Exception e
      {:success? false :error (str "Error previewing edit: " (ex-message e))})))

;; ─── Built-in tool renderers ──────────────────────────────────────────────
;; Each render-call takes (name args theme width context) → IComponent or nil.
;; Each render-result takes (content is-error theme width expanded? started-at ended-at truncation context) → IComponent or nil.
;; context is a ToolRenderContext map (see tool-execution-context). Built-in renderers ignore it.

(def ^:private builtin-renderers
  {"read"  {:render-call (fn [name args theme width _context]
                           (let [path (:path args)
                                 offset (:offset args)
                                 limit (:limit args)
                                 range-str (when (or offset limit)
                                             (let [start-line (or offset 1)
                                                   end-line (when limit (+ start-line limit -1))]
                                               (theme/fg theme :warning
                                                         (str ":" start-line (when end-line (str "-" end-line))))))]
                             (text/make-text
                               (str (theme/fg theme :tool-title (theme/bold "read "))
                                    (theme/fg theme :accent path)
                                    range-str)
                               0 0)))
            :render-result (fn [content is-error theme width expanded? & _]
                             (if (and (not expanded?) (not is-error))
                               nil
                               (let [lines (str/split-lines content)
                                     n (count lines)
                                     max-lines (if expanded? n 10)
                                     show (take max-lines lines)
                                     more (- n max-lines)
                                     c (container/make-container)]
                                 (doseq [line show]
                                   (container/container-add-child c
                                     (text/make-text
                                       (if is-error
                                         (theme/fg theme :error line)
                                         (theme/fg theme :tool-output line))
                                       0 0)))
                                 (when (pos? more)
                                   (container/container-add-child c
                                     (text/make-text
                                       (theme/fg theme :muted (str "... " more " more lines"))
                                       0 0)))
                                 c)))}
   "write" {:render-call (fn [name args theme width _context]
                           (let [path (:path args)
                                 content (:content args)
                                 lines (str/split-lines (or content ""))
                                 line-count (count lines)
                                 preview-lines (take 10 lines)
                                 preview-count (count preview-lines)
                                 c (container/make-container)]
                             ;; Header
                             (container/container-add-child c
                               (text/make-text
                                 (str (theme/fg theme :tool-title (theme/bold "write "))
                                      (theme/fg theme :accent path)
                                      (theme/fg theme :dim (str " (" line-count " lines)")))
                                 0 0))
                             ;; Content preview in a muted Box (Pi: syntax-highlighted preview)
                             (let [preview-box (box/make-box 1 0
                                                 #(theme/bg theme :border-muted %))]
                               (doseq [line preview-lines]
                                 (box/box-add-child preview-box
                                   (text/make-text
                                     (str "  " (theme/fg theme :tool-output line))
                                     0 0)))
                               (when (< preview-count line-count)
                                 (box/box-add-child preview-box
                                   (text/make-text
                                     (theme/fg theme :muted (str "  ... " (- line-count preview-count) " more lines"))
                                     0 0)))
                               (container/container-add-child c preview-box))
                             c))
            :render-result (fn [content is-error theme width expanded? & _]
                             (when is-error
                               (let [lines (str/split-lines content)] (text/make-text (theme/fg theme :error (str/join "\n" lines)) 0 0))))}
   "edit"  {:render-shell :self
            :render-call (fn [name args theme width _context]
                           (let [path (:path args)
                                 old-text (:old-text args)
                                 new-text (:new-text args)
                                 preview (compute-edit-preview path old-text new-text)
                                 c (container/make-container)]
                             (if (:success? preview)
                               (let [{:keys [diff-lines num-additions num-removals]} preview]
                                 ;; Header
                                 (container/container-add-child c
                                   (text/make-text
                                     (str (theme/fg theme :tool-title (theme/bold "edit "))
                                          (theme/fg theme :accent path))
                                     0 0))
                                 ;; Diff preview in a Box with success background
                                 (let [preview-box (box/make-box 1 0
                                                     #(theme/bg theme :tool-success-bg %))]
                                   (box/box-add-child preview-box
                                     (text/make-text
                                       (str (theme/fg theme :tool-diff-context "Preview: ")
                                            (theme/fg theme :tool-diff-added (str "+" num-additions " "))
                                            (theme/fg theme :dim "/ ")
                                            (theme/fg theme :tool-diff-removed (str "-" num-removals)))
                                       0 0))
                                   (doseq [[kind line] diff-lines]
                                     (let [style-prefix (case kind
                                                          :add (str (theme/fg theme :tool-diff-added) "+")
                                                          :remove (str (theme/fg theme :tool-diff-removed) "-")
                                                          :context (str (theme/fg theme :tool-diff-context) " "))]
                                       (box/box-add-child preview-box
                                         (text/make-text
                                           (str style-prefix line)
                                           0 0))))
                                   (container/container-add-child c preview-box))
                                 c)
                               ;; Preview failed -- show header + error in error-colored Box
                               (do
                                 (container/container-add-child c
                                   (text/make-text
                                     (str (theme/fg theme :tool-title (theme/bold "edit "))
                                          (theme/fg theme :accent path))
                                     0 0))
                                 (let [error-box (box/make-box 1 0
                                                    #(theme/bg theme :tool-error-bg %))]
                                   (box/box-add-child error-box
                                     (text/make-text
                                       (theme/fg theme :error (:error preview))
                                       0 0))
                                   (container/container-add-child c error-box))
                                 c))))
            :render-result (fn [content is-error theme width expanded? & _]
                             (when is-error
                               ;; Only show errors from actual execution (not preview)
                               (let [lines (str/split-lines content)]
                                 (text/make-text
                                   (theme/fg theme :error (str/join "\n" lines))
                                   0 0))))}

   "bash"  {:render-call (fn [name args theme width _context]
                           (let [cmd (:command args)
                                 timeout (:timeout args)
                                 cmd-str (if (string? cmd)
                                           cmd
                                           (if (nil? cmd) "" nil))
                                 cmd-display (cond
                                               (nil? cmd-str)
                                               (theme/fg theme :error "[invalid arg]")
                                               (empty? cmd-str)
                                               (theme/fg theme :tool-output "...")
                                               :else cmd-str)
                                 timeout-suffix (if (and (number? timeout) (pos? timeout))
                                                  (theme/fg theme :muted (str " (timeout " timeout "s)"))
                                                  "")]
                             (text/make-text
                               (str (theme/fg theme :tool-title (theme/bold (str "$ " cmd-display)))
                                    timeout-suffix)
                               0 0)))
            :render-result (fn [content is-error theme width expanded? started-at ended-at truncation _context]
                             (let [c (container/make-container)
                                   BASH-PREVIEW-LINES 5
                                   full-output-path (:full-output-path truncation)
                                   output (let [trimmed (str/trim (or content ""))]
                                            (if (and truncation
                                                     full-output-path
                                                     (some? ended-at)
                                                     (str/ends-with? trimmed "]"))
                                              (let [footer-start (str/last-index-of trimmed "\n\n[")]
                                                (if (and footer-start
                                                         (str/includes? (subs trimmed footer-start)
                                                                   full-output-path))
                                                  (str/trimr (subs trimmed 0 footer-start))
                                                  trimmed))
                                              trimmed))]
                               (when (seq output)
                                 (let [styled (->> (str/split-lines output)
                                                    (mapv #(theme/fg theme :tool-output %))
                                                    (str/join "\n"))]
                                   (if expanded?
                                     (do
                                       (container/container-add-child c (text/make-text "" 0 0))
                                       (doseq [line (str/split-lines styled)]
                                         (container/container-add-child c (text/make-text line 0 0))))
                                     (let [{:keys [visual-lines skipped-count]}
                                           (utils/truncate-to-visual-lines styled BASH-PREVIEW-LINES width)]
                                       (container/container-add-child c (text/make-text "" 0 0))
                                       (when (pos? skipped-count)
                                         (container/container-add-child c
                                           (text/make-text
                                             (utils/truncate-to-width
                                               (str (theme/fg theme :muted
                                                    (str "... (" skipped-count " earlier lines,"))
                                                    " "
                                                    (app-kb/key-hint "app.tools.expand" "to expand")
                                                    (theme/fg theme :muted ")"))
                                               width "...")
                                             0 0)))
                                       (doseq [line visual-lines]
                                         (container/container-add-child c (text/make-text line 0 0)))))))
                               (when truncation
                                 (let [{:keys [total-lines shown-lines truncated-by max-bytes]} truncation
                                       size-str (when (= truncated-by :bytes)
                                                  (bash-exec/format-size
                                                    (or max-bytes bash-exec/DEFAULT-MAX-BYTES)))
                                       truncated-part (if (= truncated-by :bytes)
                                                        (str "Truncated: " shown-lines " lines shown (" size-str " limit)")
                                                        (str "Truncated: showing " shown-lines " of " total-lines " lines"))
                                       warn (str "[" (str/join ". "
                                                     (cond-> []
                                                       full-output-path (conj (str "Full output: " full-output-path))
                                                       :always (conj truncated-part)))
                                                  "]")]
                                   (container/container-add-child c (text/make-text "" 0 0))
                                   (container/container-add-child c
                                     (text/make-text (theme/fg theme :warning warn) 0 0))))
                               (when started-at
                                 (let [now (or ended-at (System/currentTimeMillis))
                                       elapsed-ms (- now started-at)
                                       label (if ended-at "Took" "Elapsed")]
                                   (container/container-add-child c (text/make-text "" 0 0))
                                   (container/container-add-child c
                                     (text/make-text
                                       (theme/fg theme :muted (str label " " (format "%.1f" (float (/ elapsed-ms 1000))) "s"))
                                       0 0))))
                               c))}})

;; ─── Default renderers (fallback when no custom or built-in) ──────────────

(defn- default-render-call
  "Default render-call: show tool name bolded in tool-title color."
  [name _args theme _width & [_context]]
  (text/make-text (theme/fg theme :tool-title (theme/bold name)) 0 0))

(defn- default-render-result
  "Default render-result: show raw content in tool-output color.
   Accepts extra args for compatibility."
  [content _is-error theme _width _expanded? & _]
  (text/make-text (theme/fg theme :tool-output content) 0 0))

;; ─── Render context helper ─────────────────────────────────────────────────

(defn- tool-execution-context
  "Build a ToolRenderContext map for the given component and last-component."
  [comp last-comp]
  {:args @(:args-atom comp)
   :tool-call-id @(:tool-call-id-atom comp)
   :invalidate (fn []
                 (protocols/invalidate comp)
                 (when-let [cb @(:request-render-fn-atom comp)]
                   (cb)))
   :last-component last-comp
   :state @(:renderer-state-atom comp)
   :cwd @(:cwd-atom comp)
   :execution-started (some? @(:started-at-atom comp))
   :args-complete @(:args-complete-atom comp)
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
   started-at-atom ended-at-atom timer-active-atom
   truncation-atom tool-call-id-atom
   args-complete-atom
   image-data-atom       ;; vector of {:data str :mime-type str}
   converted-images-atom ;; atom of {idx -> {:base64 str :mime-type "image/png" :width-px int :height-px int}}
   last-call-component-atom   ;; component from previous render-call
   last-result-component-atom ;; component from previous render-result
   renderer-state-atom        ;; persistent state for custom renderers
   request-render-fn-atom  ;; nil or (fn) to trigger TUI re-render
   cwd-atom                ;; current working directory
   box             ;; outer Box (padding + bg)
   inner-container] ;; Container for call/result children
  (render [this width]
    (let [theme @theme-atom
          is-error @is-error-atom
          output-pad @output-pad-atom
          name @name-atom
          args @args-atom
          content @content-atom
          expanded? @expanded-atom
          started-at @started-at-atom
          ended-at @ended-at-atom]
      ;; Re-check empty — only when no call component rendered and no result
      (let [builtin (get builtin-renderers name)
              render-call-fn (or @custom-render-call-atom
                                 (:render-call builtin)
                                 default-render-call)
              render-result-fn (or @custom-render-result-atom
                                   (:render-result builtin)
                                   default-render-result)
              render-shell (or (:render-shell builtin) :default)
              container @inner-container
              content-width (max 1 (- width (* 2 output-pad)))
              call-context (tool-execution-context this @last-call-component-atom)
              call-comp (render-call-fn name args theme content-width call-context)
              _ (reset! last-call-component-atom call-comp)
              truncation @truncation-atom
              result-context (tool-execution-context this @last-result-component-atom)
              result-comp (render-result-fn content is-error theme content-width expanded? started-at ended-at truncation result-context)
              _ (reset! last-result-component-atom result-comp)
              image-data @image-data-atom
              converted @converted-images-atom]
      ;; Pi: hide component when no call/render content and no images
      (if (and (nil? call-comp) (nil? result-comp) (not (seq image-data)))
        []
        (do
          ;; Schedule periodic re-render while tool is running (Pi: setInterval equivalent)
          (when (and started-at (not ended-at) (compare-and-set! timer-active-atom false true))
            (future
              (Thread/sleep 1000)
              (reset! timer-active-atom false)
              (protocols/invalidate this)))
          ;; Build inner container
          (container/container-clear container)
          (container/container-add-child container call-comp)
          (when result-comp
            (container/container-add-child container result-comp))
          ;; Build image components from raw data + conversions (Pi: spacer + ImageComponent)
          (doseq [[i img] (map-indexed vector image-data)]
            (let [converted (get converted i)
                  img-data (if converted (:base64 converted) (:data img))
                  img-mime (if converted (:mime-type converted) (:mime-type img))]
              (container/container-add-child container (spacer/make-spacer 1))
              (container/container-add-child container
                (ic/make-image img-data img-mime
                  {:fallback-color (fn [s] (theme/fg theme :tool-output s))}
                  :max-width-cells 60))))
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
  (invalidate [this]
    (protocols/invalidate @box)
    ;; Pi: invalidate also triggers TUI re-render
    (when-let [cb @request-render-fn-atom]
      (cb))))

;; ─── Construction ──────────────────────────────────────────────────────────
;; Pi: component manages timing internally — no started-at/ended-at passed in.

(defn make-tool-execution
  [& {:keys [name args content is-error theme output-pad expanded? render-call-fn render-result-fn truncation cwd]
      :or {name "" args {} content "" is-error false theme theme/dark-theme
           output-pad 1 expanded? false truncation nil
           cwd (or (System/getProperty "user.dir") ".")}}]
  (let [inner-container (container/make-container)
        bg-key (if is-error :tool-error-bg :tool-success-bg)
        b (box/make-box output-pad 1 #(theme/bg theme bg-key %))]
    (box/box-add-child b inner-container)
    (map->ToolExecutionComponent {:name-atom (atom name)
                         :args-atom (atom args)
                         :content-atom (atom content)
                         :is-error-atom (atom is-error)
                         :theme-atom (atom theme)
                         :output-pad-atom (atom output-pad)
                         :expanded-atom (atom expanded?)
                         :started-at-atom (atom nil)
                         :ended-at-atom (atom nil)
                         :timer-active-atom (atom false)
                         :truncation-atom (atom truncation)
                         :tool-call-id-atom (atom nil)
                         :args-complete-atom (atom false)
                         :custom-render-call-atom (atom render-call-fn)
                         :custom-render-result-atom (atom render-result-fn)
                         :image-data-atom (atom [])
                         :converted-images-atom (atom {})
                         :last-call-component-atom (atom nil)
                         :last-result-component-atom (atom nil)
                         :renderer-state-atom (atom {})
                         :request-render-fn-atom (atom nil)
                         :cwd-atom (atom cwd)
                         :box (atom b)
                         :inner-container (atom inner-container)})))

;; ─── Public API ────────────────────────────────────────────────────────────
;; Pi: set-content! and set-error! manage timing internally.

(defsetter tool-execution-set-name! :name-atom comp name
  (protocols/invalidate comp))

(defsetter tool-execution-set-content! :content-atom comp content
  ;; Pi: first content delivery marks execution started
  (when (nil? @(:started-at-atom comp))
    (reset! (:started-at-atom comp) (System/currentTimeMillis)))
  (protocols/invalidate comp))

(defsetter tool-execution-set-error! :is-error-atom comp is-error
  ;; Pi: error marks execution ended
  (when (nil? @(:ended-at-atom comp))
    (reset! (:ended-at-atom comp) (System/currentTimeMillis)))
  (protocols/invalidate comp))

(defsetter tool-execution-set-expanded! :expanded-atom comp expanded?
  (protocols/invalidate comp))
(defsetter tool-execution-set-theme! :theme-atom comp theme
  (protocols/invalidate comp))
(defsetter tool-execution-set-output-pad! :output-pad-atom comp n
  (protocols/invalidate comp))

(defsetter tool-execution-set-truncation! :truncation-atom comp truncation
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
   Stores raw image data; ImageComponents are built at render time.
   For non-PNG images in kitty-capable terminals, triggers async conversion to PNG."
  [comp images]
  (let [image-data (mapv (fn [img] {:data (:data img) :mime-type (:mime-type img)}) images)]
    (reset! (:image-data-atom comp) image-data)
    (reset! (:converted-images-atom comp) {})
    ;; Async conversion for non-PNG images in kitty terminals
    (let [caps (timg/get-capabilities)]
      (when (= :kitty (:images caps))
        (doseq [[i img] (map-indexed vector images)]
          (when (and (:data img) (:mime-type img) (not= (:mime-type img) "image/png"))
            (future
              (when-let [converted (timg/convert-to-png (:data img) (:mime-type img))]
                (let [converted' @(:converted-images-atom comp)]
                  (reset! (:converted-images-atom comp)
                    (assoc converted' i
                      {:base64 (:base64 converted)
                       :mime-type "image/png"
                       :width-px (:width-px converted)
                       :height-px (:height-px converted)})))
                (protocols/invalidate comp)))))))
    (protocols/invalidate comp)))

(defsetter tool-execution-set-request-render-fn! :request-render-fn-atom comp f)
(defsetter tool-execution-set-render-call-fn! :custom-render-call-atom comp f
  (protocols/invalidate comp))
(defsetter tool-execution-set-render-result-fn! :custom-render-result-atom comp f
  (protocols/invalidate comp))
