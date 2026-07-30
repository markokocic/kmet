(ns kmet.agent.ui.tool-execution
  "ToolExecutionComponent component — Pi's ToolExecutionComponent.
   Uses a Box (with status background) wrapping a Container that holds
   the call-render and result-render children.
   Matching Pi architecture: Box handles padding/background/caching.
   Timing is managed internally (started-at on first content, ended-at on error/finalize)."
  (:require [kmet.tui.protocols :as protocols]
            [kmet.tui.theme :as theme]
            [kmet.tui.components.text :as text]
            [kmet.tui.components.box :as box]
            [kmet.tui.components.container :as container]
            [clojure.string :as str]))

;; ─── Built-in tool renderers ──────────────────────────────────────────────
;; Each render-call takes (name args theme width) → IComponent or nil.
;; Each render-result takes (content is-error theme width expanded? started-at ended-at) → IComponent or nil.
;; Returning nil from render-result means "show nothing" (no separator, no result).

(def ^:private builtin-renderers
  {"read"  {:render-call (fn [name args theme width]
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
   "write" {:render-call (fn [name args theme width]
                           (let [path (:path args)
                                 content (:content args)
                                 line-count (count (str/split-lines (or content "")))]
                             (text/make-text
                               (str (theme/fg theme :tool-title (theme/bold "write "))
                                    (theme/fg theme :accent path)
                                    (theme/fg theme :dim (str " (" line-count " lines)")))
                               0 0)))
            :render-result (fn [content is-error theme width expanded? & _]
                             (when is-error
                               (text/make-text (theme/fg theme :error (first (str/split-lines content))) 0 0)))}
   "edit"  {:render-shell :self
            :render-call (fn [name args theme width]
                           (text/make-text
                             (str (theme/fg theme :tool-title (theme/bold "edit "))
                                  (theme/fg theme :accent (:path args)))
                             0 0))
            :render-result (fn [content is-error theme width expanded? & _]
                             (if is-error
                               (text/make-text (theme/fg theme :error (first (str/split-lines content))) 0 0)
                               (let [lines (str/split-lines content)
                                     additions (count (filter #(and (.startsWith % "+")
                                                                     (not (.startsWith % "+++")))
                                                              lines))
                                     removals (count (filter #(and (.startsWith % "-")
                                                                     (not (.startsWith % "---")))
                                                              lines))
                                     has-diff? (or (pos? additions) (pos? removals))]
                                 (if has-diff?
                                   (let [stats-text (text/make-text
                                                      (str (theme/fg theme :success (str "+" additions))
                                                           (theme/fg theme :dim " / ")
                                                           (theme/fg theme :error (str "-" removals)))
                                                      0 0)]
                                     (if expanded?
                                       (let [c (container/make-container)]
                                         (container/container-add-child c stats-text)
                                         (doseq [line lines]
                                           (let [styled (cond
                                                          (and (.startsWith line "+")
                                                               (not (.startsWith line "+++")))
                                                            (theme/fg theme :success line)
                                                          (and (.startsWith line "-")
                                                               (not (.startsWith line "---")))
                                                            (theme/fg theme :error line)
                                                          :else (theme/fg theme :dim line))]
                                             (container/container-add-child c (text/make-text styled 0 0))))
                                         c)
                                       stats-text))
                                   (text/make-text (theme/fg theme :success "Applied") 0 0)))))}
   "bash"  {:render-call (fn [name args theme width]
                           (let [cmd (:command args)
                                 timeout (:timeout args)
                                 cmd-str (if (nil? cmd)
                                           "[invalid arg]"
                                           (if (empty? cmd)
                                             "..."
                                             cmd))
                                 timeout-suffix (when timeout
                                                  (theme/fg theme :muted (str " (timeout: " timeout "s)")))]
                             (text/make-text
                               (str (theme/fg theme :tool-title (theme/bold (str "$ " cmd-str)))
                                    timeout-suffix)
                               0 0)))
            :render-result (fn [content is-error theme width expanded? started-at ended-at]
                             (let [lines (str/split-lines content)
                                   footer-re #"^\[Showing.*Full output:.*\]$"
                                   footer-line (last (filter #(re-find footer-re %) lines))
                                   output-lines (if footer-line
                                                  (vec (butlast lines))
                                                  lines)
                                   c (container/make-container)]
                               (when (seq output-lines)
                                 (let [styled (mapv #(theme/fg theme :tool-output %) output-lines)
                                       BASH-PREVIEW-LINES 5]
                                   (if expanded?
                                     (do
                                       ;; Pi: leading blank line before expanded output
                                       (container/container-add-child c (text/make-text "" 0 0))
                                       (doseq [sline styled]
                                         (container/container-add-child c (text/make-text sline 0 0))))
                                     (let [total (count styled)
                                           show-lines (take-last BASH-PREVIEW-LINES styled)
                                           skipped (- total BASH-PREVIEW-LINES)]
                                       ;; Pi: leading blank line before collapsed output
                                       (container/container-add-child c (text/make-text "" 0 0))
                                       (when (pos? skipped)
                                         (container/container-add-child c
                                           (text/make-text
                                             (theme/fg theme :muted
                                               (str "... (" skipped " earlier lines, to expand)"))
                                             0 0)))
                                       (doseq [sline show-lines]
                                         (container/container-add-child c (text/make-text sline 0 0)))))))
                               (when (and (not is-error) footer-line)
                                 (container/container-add-child c
                                   (text/make-text
                                     (theme/fg theme :warning (str " [" footer-line "]"))
                                     0 0)))
                               ;; Pi: duration managed internally by component, with leading blank line
                               (when started-at
                                 (let [now (or ended-at (System/currentTimeMillis))
                                       elapsed-ms (- now started-at)
                                       elapsed-sec (float (/ elapsed-ms 1000))
                                       label (if ended-at "Took" "Elapsed")]
                                   (container/container-add-child c (text/make-text "" 0 0))
                                   (container/container-add-child c
                                     (text/make-text
                                       (theme/fg theme :muted (str label " " (format "%.1f" elapsed-sec) "s"))
                                       0 0))))
                               c))}})

;; ─── Default renderers (fallback when no custom or built-in) ──────────────

(defn- default-render-call
  "Default render-call: show tool name bolded in tool-title color."
  [name _args theme _width]
  (text/make-text (theme/fg theme :tool-title (theme/bold name)) 0 0))

(defn- default-render-result
  "Default render-result: show raw content in tool-output color.
   Accepts extra timing args for compatibility."
  [content _is-error theme _width _expanded? & _]
  (text/make-text (theme/fg theme :tool-output content) 0 0))

;; ─── Record ────────────────────────────────────────────────────────────────
;; Pi matching: ToolExecutionComponent manages its own timing.
;; started-at is set on first set-content! call (execution start).
;; ended-at is set on set-error! or on final full-content set-content!.

(defrecord ToolExecutionComponent [name-atom args-atom content-atom is-error-atom
                                   theme-atom output-pad-atom expanded-atom
                                   custom-render-call-atom custom-render-result-atom
                                   started-at-atom ended-at-atom timer-active-atom
                                   box             ;; outer Box (padding + bg)
                                   inner-container] ;; Container for call/result children
  protocols/IComponent
  (render [this width]
    (let [theme @theme-atom
          is-error @is-error-atom
          output-pad @output-pad-atom
          name @name-atom
          args @args-atom
          content @content-atom
          expanded? @expanded-atom
          builtin (get builtin-renderers name)
          render-call-fn (or @custom-render-call-atom
                             (:render-call builtin)
                             default-render-call)
          render-result-fn (or @custom-render-result-atom
                               (:render-result builtin)
                               default-render-result)
          render-shell (or (:render-shell builtin) :default)
          ;; Read timing atoms (needed early for bg-key)
          started-at @started-at-atom
          ended-at @ended-at-atom
          container @inner-container
          content-width (max 1 (- width (* 2 output-pad)))
          call-comp (render-call-fn name args theme content-width)
          result-comp (render-result-fn content is-error theme content-width expanded? started-at ended-at)]
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
      ;; Pi: render-shell :self skips outer Box (tool renders its own framing)
      (if (= :self render-shell)
        (let [content-lines (protocols/render container width)]
          (if (seq content-lines)
            (into [""] content-lines)
            []))
        (let [bg-key (cond
                       (and started-at (not ended-at)) :tool-pending-bg
                       is-error :tool-error-bg
                       :else :tool-success-bg)
              _ (box/box-set-bg-fn @box #(theme/bg theme bg-key %))
              box-lines (protocols/render @box width)]
          (if (seq box-lines)
            (into [""] box-lines)
            [])))))
  (handle-input [_this _data] nil)
  (invalidate [this]
    (protocols/invalidate @box)))

;; ─── IComponentKind ─────────────────────────────────────────────────────────

(extend-type ToolExecutionComponent
  protocols/IComponentKind
  (component-kind [_] :tool))

;; ─── Construction ──────────────────────────────────────────────────────────
;; Pi: component manages timing internally — no started-at/ended-at passed in.

(defn make-tool-execution
  [& {:keys [name args content is-error theme output-pad expanded? render-call-fn render-result-fn]
      :or {name "" args {} content "" is-error false theme theme/dark-theme
           output-pad 1 expanded? false}}]
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
                         :custom-render-call-atom (atom render-call-fn)
                         :custom-render-result-atom (atom render-result-fn)
                         :box (atom b)
                         :inner-container (atom inner-container)})))

;; ─── Public API ────────────────────────────────────────────────────────────
;; Pi: set-content! and set-error! manage timing internally.

(defn tool-execution-set-name! [comp name]
  (reset! (:name-atom comp) name)
  (protocols/invalidate comp))

(defn tool-execution-set-content! [comp content]
  ;; Pi: first content delivery marks execution started
  (when (nil? @(:started-at-atom comp))
    (reset! (:started-at-atom comp) (System/currentTimeMillis)))
  (reset! (:content-atom comp) content)
  (protocols/invalidate comp))

(defn tool-execution-set-error! [comp is-error]
  ;; Pi: error marks execution ended
  (when (nil? @(:ended-at-atom comp))
    (reset! (:ended-at-atom comp) (System/currentTimeMillis)))
  (reset! (:is-error-atom comp) is-error)
  (protocols/invalidate comp))

(defn tool-execution-set-expanded! [comp expanded?]
  (reset! (:expanded-atom comp) expanded?)
  (protocols/invalidate comp))
(defn tool-execution-set-theme! [comp theme]
  (reset! (:theme-atom comp) theme)
  (protocols/invalidate comp))
(defn tool-execution-set-output-pad! [comp n]
  (reset! (:output-pad-atom comp) n)
  (protocols/invalidate comp))
(defn tool-execution-set-render-call-fn! [comp f]
  (reset! (:custom-render-call-atom comp) f)
  (protocols/invalidate comp))
(defn tool-execution-set-render-result-fn! [comp f]
  (reset! (:custom-render-result-atom comp) f)
  (protocols/invalidate comp))
