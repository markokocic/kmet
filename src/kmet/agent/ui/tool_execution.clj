(ns kmet.agent.ui.tool-execution
  "ToolExecutionComponent component — Pi's ToolExecutionComponent.
   Supports optional custom render-call and render-result functions
   that return IComponent instances (like Text, Box), plus built-in
   renderers for standard tools (read, write, edit, bash).
   When no render function is provided and no built-in exists,
   falls back to showing raw content in a colored box."
  (:require [kmet.tui.protocols :as protocols]
            [kmet.tui.utils :as u]
            [kmet.tui.theme :as theme]
            [kmet.tui.components.text :as text]
            [kmet.tui.components.container :as container]
            [kmet.tui.macros :refer [with-cache]]
            [clojure.string :as str]))

;; ─── Built-in tool renderers ──────────────────────────────────────────────
;; Each render-call takes (name args theme width) → IComponent or nil.
;; Each render-result takes (content is-error theme width expanded?) → IComponent or nil.
;; nil means use default rendering.

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
                                 truncated (if (> (count cmd) 77) (str (subs cmd 0 74) "...") cmd)
                                 timeout (:timeout args)]
                             (text/make-text
                               (str (theme/fg theme :tool-title (theme/bold "$ "))
                                    (theme/fg theme :accent (or truncated cmd))
                                    (when timeout
                                      (theme/fg theme :dim (str " (timeout: " timeout "s)"))))
                               0 0)))
            :render-result (fn [content is-error theme width expanded?]
                             (let [lines (str/split-lines content)
                                   n (count (filter #(not (str/blank? %)) lines))
                                   exit-code (some #(when (re-find #"exit code: (\d+)" %)
                                                      (last (re-find #"exit code: (\d+)" %)))
                                                   lines)]
                               (if expanded?
                                 (let [show (take 20 lines)
                                       more (- n 20)
                                       c (container/make-container)]
                                   (container/container-add-child c
                                     (text/make-text
                                       (str (if (and (not is-error)
                                                    (or (nil? exit-code) (= exit-code "0")))
                                              (theme/fg theme :success "done")
                                              (theme/fg theme :error (str "exit " (or exit-code "non-zero"))))
                                            (theme/fg theme :dim (str " (" n " lines)")))
                                       0 0))
                                   (doseq [line show]
                                     (container/container-add-child c
                                       (text/make-text (theme/fg theme :dim line) 0 0)))
                                   (when (pos? more)
                                     (container/container-add-child c
                                       (text/make-text (theme/fg theme :muted (str "... " more " more output")) 0 0)))
                                   c)
                                 (text/make-text
                                   (str (if (and (not is-error)
                                                (or (nil? exit-code) (= exit-code "0")))
                                          (theme/fg theme :success "done")
                                          (theme/fg theme :error (str "exit " (or exit-code "non-zero"))))
                                        (theme/fg theme :dim (str " (" n " lines)")))
                                   0 0))))}})

;; ─── Default renderers (fallback when no custom or built-in) ──────────────

(defn- default-render-call
  "Default render-call: show tool name bolded in tool-title color (matching pi)."
  [name _args theme _width]
  (text/make-text (theme/fg theme :tool-title (theme/bold name)) 0 0))

(defn- default-render-result
  "Default render-result: show raw content in tool-output color (matching pi)."
  [content _is-error theme _width _expanded?]
  (text/make-text (theme/fg theme :tool-output content) 0 0))

;; ─── Record ────────────────────────────────────────────────────────────────

(defrecord ToolExecutionComponent [name-atom args-atom content-atom is-error-atom theme-atom
                          output-pad-atom expanded-atom
                          custom-render-call-atom custom-render-result-atom
                          cache-atom]
  protocols/IComponent
  (render [this width]
    (let [name @name-atom
          args @args-atom
          content @content-atom
          is-error @is-error-atom
          theme @theme-atom
          expanded? @expanded-atom
          output-pad @output-pad-atom]
      (with-cache this width
        {:name name :content content :is-error is-error
         :theme theme :output-pad output-pad :expanded? expanded?}
        (fn []
          (let [builtin (get builtin-renderers name)
                render-call-fn (or @custom-render-call-atom
                                   (:render-call builtin)
                                   default-render-call)
                render-result-fn (or @custom-render-result-atom
                                     (:render-result builtin)
                                     default-render-result)
                pad-x output-pad pad-y 1
                content-width (max 1 (- width (* 2 pad-x)))
                left-pad (apply str (repeat pad-x \space))
                bg-key (if is-error :tool-error-bg :tool-success-bg)
                bg (fn [line] (theme/bg theme bg-key
                        (str line (apply str (repeat (max 0 (- width (u/visible-width line))) \space)))))
                empty (apply str (repeat width \space))
                ;; Render-call component: get lines from its render
                call-comp (render-call-fn name args theme content-width)
                call-lines (protocols/render call-comp content-width)
                call-indented (mapv #(str left-pad %) call-lines)
                ;; Render-result component
                result-comp (render-result-fn content is-error theme content-width expanded?)
                result-lines (protocols/render result-comp content-width)
                result-indented (mapv #(str left-pad %) result-lines)]
            (vec (concat
                   (repeat pad-y (bg empty))
                   (map bg call-indented)
                   (when (seq call-lines)
                     (map bg [(str left-pad (apply str (repeat (max 0 (- content-width 1)) "─")))]))
                   (map bg result-indented)
                   (repeat pad-y (bg empty)))))))))
  (handle-input [_this _data] nil)
  (invalidate [this]
    (reset! (:cache-atom this) nil)))

;; ─── IComponentKind ─────────────────────────────────────────────────────────

(extend-type ToolExecutionComponent
  protocols/IComponentKind
  (component-kind [_] :tool))

;; ─── Construction ──────────────────────────────────────────────────────────

(defn make-tool-execution
  [& {:keys [name args content is-error theme output-pad expanded? render-call-fn render-result-fn]
      :or {name "" args {} content "" is-error false theme theme/dark-theme
           output-pad 1 expanded? false}}]
  (map->ToolExecutionComponent {:name-atom (atom name)
                       :args-atom (atom args)
                       :content-atom (atom content)
                       :is-error-atom (atom is-error)
                       :theme-atom (atom theme)
                       :output-pad-atom (atom output-pad)
                       :expanded-atom (atom expanded?)
                       :custom-render-call-atom (atom render-call-fn)
                       :custom-render-result-atom (atom render-result-fn)
                       :cache-atom (atom nil)}))

;; ─── Public API ────────────────────────────────────────────────────────────

(defn tool-execution-set-name! [comp name]
  (reset! (:name-atom comp) name) (protocols/invalidate comp))
(defn tool-execution-set-content! [comp content]
  (reset! (:content-atom comp) content) (protocols/invalidate comp))
(defn tool-execution-set-error! [comp is-error]
  (reset! (:is-error-atom comp) is-error) (protocols/invalidate comp))
(defn tool-execution-set-expanded! [comp expanded?]
  (reset! (:expanded-atom comp) expanded?) (protocols/invalidate comp))
(defn tool-execution-set-theme! [comp theme]
  (reset! (:theme-atom comp) theme) (protocols/invalidate comp))
(defn tool-execution-set-output-pad! [comp n]
  (reset! (:output-pad-atom comp) n) (protocols/invalidate comp))
(defn tool-execution-set-render-call-fn! [comp f]
  (reset! (:custom-render-call-atom comp) f) (protocols/invalidate comp))
(defn tool-execution-set-render-result-fn! [comp f]
  (reset! (:custom-render-result-atom comp) f) (protocols/invalidate comp))
