(ns kmet.agent.ui.tool-execution
  "ToolExecutionComponent component — Pi's ToolExecutionComponent.
   Uses a Box (with status background) wrapping a Container that holds
   the call-render and result-render children.
   Matching Pi architecture: Box handles padding/background/caching."
  (:require [kmet.tui.protocols :as protocols]
            [kmet.tui.utils :as u]
            [kmet.tui.theme :as theme]
            [kmet.tui.components.text :as text]
            [kmet.tui.components.box :as box]
            [kmet.tui.components.container :as container]
            [kmet.tui.macros :refer [with-cache]]
            [clojure.string :as str]))

;; ─── Built-in tool renderers ──────────────────────────────────────────────
;; Each render-call takes (name args theme width) → IComponent or nil.
;; Each render-result takes (content is-error theme width expanded?) → IComponent or nil.

(def ^:private builtin-renderers
  {"read"  {:render-call nil
            :render-result (fn [content is-error theme width expanded?]
                             (let [lines (str/split-lines content)
                                   n (count lines)]
                               (if expanded?
                                 (let [show (take 15 lines)
                                       more (- n 15)
                                       c (container/make-container)]
                                   (container/container-add-child c
                                     (text/make-text
                                       (if is-error
                                         (theme/fg theme :error (first lines))
                                         (str (theme/fg theme :success (str n " lines"))))
                                       0 0))
                                   (doseq [line show]
                                     (container/container-add-child c
                                       (text/make-text (theme/fg theme :dim line) 0 0)))
                                   (when (pos? more)
                                     (container/container-add-child c
                                       (text/make-text (theme/fg theme :muted (str "... " more " more lines")) 0 0)))
                                   c)
                                 (text/make-text
                                   (if is-error
                                     (theme/fg theme :error (first lines))
                                     (str (theme/fg theme :success (str n " lines"))))
                                   0 0))))}
   "write" {:render-call nil
            :render-result (fn [content is-error theme width expanded?]
                             (text/make-text
                               (theme/fg theme (if is-error :error :success) content)
                               0 0))}
   "edit"  {:render-call nil
            :render-result (fn [content is-error theme width expanded?]
                             (let [m (str/split-lines content)]
                               (text/make-text
                                 (theme/fg theme (if is-error :error :success) (first m))
                                 0 0)))}
   "bash"  {:render-call (fn [name args theme width]
                           (let [cmd (:command args)
                                 timeout (:timeout args)
                                 cmd-display (if (nil? cmd)
                                              (theme/fg theme :error "[invalid arg]")
                                              (if (empty? cmd)
                                                (theme/fg theme :tool-output "...")
                                                cmd))
                                 timeout-suffix (when timeout
                                                  (theme/fg theme :muted (str " (timeout: " timeout "s)")))]
                             (text/make-text
                               (str (theme/fg theme :tool-title (theme/bold "$ "))
                                    (theme/fg theme :accent cmd-display)
                                    timeout-suffix)
                               0 0)))
            :render-result (fn [content is-error theme width expanded?]
                             (let [lines (str/split-lines content)
                                   footer-re #"^\[Showing.*Full output:.*\]$"
                                   footer-line (last (filter #(re-find footer-re %) lines))
                                   output-lines (if footer-line
                                                  (vec (butlast lines))
                                                  lines)
                                   n (count (filter #(not (str/blank? %)) output-lines))
                                   exit-code (some #(when (re-find #"exit code: (\d+)" %)
                                                      (last (re-find #"exit code: (\d+)" %)))
                                                   lines)]
                               (let [c (container/make-container)
                                     status-str (if is-error
                                                  (str (theme/fg theme :error (str "exit " (or exit-code "non-zero")))
                                                       (theme/fg theme :dim (str " (" n " lines)")))
                                                  (str (theme/fg theme :success "done")
                                                       (theme/fg theme :dim (str " (" n " lines)"))))]
                                 (container/container-add-child c
                                   (text/make-text status-str 0 0))
                                 (when (seq output-lines)
                                   (let [styled (mapv #(theme/fg theme :tool-output %) output-lines)
                                         BASH-PREVIEW-LINES 5]
                                     (if expanded?
                                       (doseq [sline styled]
                                         (container/container-add-child c
                                           (text/make-text sline 0 0)))
                                       (let [total (count styled)
                                             show-lines (take-last BASH-PREVIEW-LINES styled)
                                             skipped (- total BASH-PREVIEW-LINES)]
                                         (when (pos? skipped)
                                           (container/container-add-child c
                                             (text/make-text
                                               (theme/fg theme :muted
                                                 (str "... (" skipped " earlier lines, to expand)"))
                                               0 0)))
                                         (doseq [sline show-lines]
                                           (container/container-add-child c
                                             (text/make-text sline 0 0)))))))
                                 (when (and (not is-error) footer-line)
                                   (container/container-add-child c
                                     (text/make-text
                                       (theme/fg theme :warning (str " [" footer-line "]"))
                                       0 0)))
                                 c)))}})

;; ─── Default renderers (fallback when no custom or built-in) ──────────────

(defn- default-render-call
  "Default render-call: show tool name bolded in tool-title color."
  [name _args theme _width]
  (text/make-text (theme/fg theme :tool-title (theme/bold name)) 0 0))

(defn- default-render-result
  "Default render-result: show raw content in tool-output color."
  [content _is-error theme _width _expanded?]
  (text/make-text (theme/fg theme :tool-output content) 0 0))

;; ─── Separator line helper ─────────────────────────────────────────────────

(defn- separator-component
  "Return a Text component rendering a horizontal separator line."
  [theme content-width]
  (text/make-text
    (theme/fg theme :dim (apply str (repeat (max 0 (- content-width 1)) "─")))
    0 0))

;; ─── Record ────────────────────────────────────────────────────────────────

(defrecord ToolExecutionComponent [name-atom args-atom content-atom is-error-atom
                                   theme-atom output-pad-atom expanded-atom
                                   custom-render-call-atom custom-render-result-atom
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
          ;; Update bg-fn based on current is-error
          bg-key (if is-error :tool-error-bg :tool-success-bg)
          _ (box/box-set-bg-fn @box #(theme/bg theme bg-key %))
          ;; Build children
          container @inner-container
          content-width (max 1 (- width (* 2 output-pad)))
          call-comp (render-call-fn name args theme content-width)
          result-comp (render-result-fn content is-error theme content-width expanded?)
          sep (separator-component theme content-width)]
      ;; Rebuild inner container
      (container/container-clear container)
      (container/container-add-child container call-comp)
      (container/container-add-child container sep)
      (container/container-add-child container result-comp)
      ;; Render through Box
      (protocols/render @box width)))
  (handle-input [_this _data] nil)
  (invalidate [this]
    (protocols/invalidate @box)))

;; ─── IComponentKind ─────────────────────────────────────────────────────────

(extend-type ToolExecutionComponent
  protocols/IComponentKind
  (component-kind [_] :tool))

;; ─── Construction ──────────────────────────────────────────────────────────

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
                         :custom-render-call-atom (atom render-call-fn)
                         :custom-render-result-atom (atom render-result-fn)
                         :box (atom b)
                         :inner-container (atom inner-container)})))

;; ─── Public API ────────────────────────────────────────────────────────────

(defn tool-execution-set-name! [comp name]
  (reset! (:name-atom comp) name)
  (protocols/invalidate comp))
(defn tool-execution-set-content! [comp content]
  (reset! (:content-atom comp) content)
  (protocols/invalidate comp))
(defn tool-execution-set-error! [comp is-error]
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
