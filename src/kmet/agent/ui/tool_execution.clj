(ns kmet.agent.ui.tool-execution
  "ToolExecutionComponent component — Pi's ToolExecutionComponent.
   Supports optional custom render-call and render-result functions,
   plus built-in renderers for standard tools (read, write, edit, bash).
   When no custom render function is provided and no built-in exists,
   falls back to showing raw content in a colored box."
  (:require [kmet.tui.protocols :as protocols]
            [kmet.tui.utils :as u]
            [kmet.tui.theme :as theme]
            [kmet.tui.macros :refer [with-cache]]
            [clojure.string :as str]))

(def ^:private BLD "\u001b[1m")
(def ^:private RST "\u001b[0m")

;; ─── Built-in tool renderers ──────────────────────────────────────────────
;; Each render-call takes (name theme width) → vector of lines.
;; Each render-result takes (content is-error theme width expanded?) → vector of lines.

(def ^:private builtin-renderers
  {"read"  {:render-call (fn [name theme width]
                           [(str "  " (theme/fg theme :tool-title (str BLD name RST)))])
            :render-result (fn [content is-error theme width expanded?]
                             (let [lines (str/split-lines content)
                                   n (count lines)
                                   summary (if is-error
                                             (theme/fg theme :error (first lines))
                                             (str (theme/fg theme :success (str n " lines"))))]
                               (if expanded?
                                 (let [show (take 15 lines)
                                       more (- n 15)]
                                   (vec (concat [summary]
                                                (mapv #(theme/fg theme :dim %) show)
                                                (when (pos? more) [(theme/fg theme :muted (str "... " more " more lines"))]))))
                                 [summary])))}
   "write" {:render-call (fn [name theme width]
                           [(str "  " (theme/fg theme :tool-title (str BLD name RST)))])
            :render-result (fn [content is-error theme width expanded?]
                             [(theme/fg theme (if is-error :error :success) content)])}
   "edit"  {:render-call (fn [name theme width]
                           [(str "  " (theme/fg theme :tool-title (str BLD name RST)))])
            :render-result (fn [content is-error theme width expanded?]
                             (let [m (str/split-lines content)]
                               [(theme/fg theme (if is-error :error :success) (first m))]))}
   "bash"  {:render-call (fn [name theme width]
                           [(str "  " (theme/fg theme :tool-title (str BLD name RST)))])
            :render-result (fn [content is-error theme width expanded?]
                             (let [lines (str/split-lines content)
                                   n (count (filter #(not (str/blank? %)) lines))
                                   summary (if is-error
                                             (theme/fg theme :error (str "exit "
                                               (or (some #(when (re-find #"exit code:" %) %) lines) "non-zero")))
                                             (theme/fg theme :success (str "done  (" n " lines)")))]
                               (if expanded?
                                 (let [show (take 20 lines)
                                       more (- n 20)]
                                   (vec (concat [summary]
                                                (mapv #(theme/fg theme :dim %) show)
                                                (when (pos? more) [(theme/fg theme :muted (str "... " more " more lines"))]))))
                                 [summary])))}})

;; ─── Default renderers (fallback when no custom or built-in) ──────────────

(defn- default-render-call
  "Default render-call: show tool name bolded."
  [name _theme _width]
  [(str BLD name RST)])

(defn- default-render-result
  "Default render-result: show raw content in a box with background color."
  [content is-error theme width _expanded?]
  (let [pad-x 1 pad-y 1
        cw (max 1 (- width (* 2 pad-x)))
        left-pad (apply str (repeat pad-x \space))
        bg-key (if is-error :tool-error-bg :tool-success-bg)
        bg (fn [line] (theme/bg theme bg-key
                        (str line (apply str (repeat (max 0 (- width (u/visible-width line))) \space)))))
        empty (apply str (repeat width \space))
        content-indented (when (seq content)
                           (let [wrapped (u/wrap-text-with-ansi content cw)
                                 colored (mapv #(theme/fg theme :tool-output %) wrapped)]
                             (mapv #(str left-pad %) colored)))
        top-pad (repeat pad-y (bg empty))
        bottom-pad (repeat pad-y (bg empty))]
    (vec (concat top-pad (when content-indented (map bg content-indented)) bottom-pad))))

;; ─── Record ────────────────────────────────────────────────────────────────

(defrecord ToolExecutionComponent [name-atom content-atom is-error-atom theme-atom
                          output-pad-atom expanded-atom
                          custom-render-call-atom custom-render-result-atom
                          cache-atom]
  protocols/IComponent
  (render [this width]
    (let [name @name-atom
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
                cw (max 1 (- width (* 2 pad-x)))
                left-pad (apply str (repeat pad-x \space))
                bg-key (if is-error :tool-error-bg :tool-success-bg)
                bg (fn [line] (theme/bg theme bg-key
                        (str line (apply str (repeat (max 0 (- width (u/visible-width line))) \space)))))
                empty (apply str (repeat width \space))
                ;; Render-call: show tool name/args line
                call-lines (render-call-fn name theme width)
                call-indented (mapv #(str left-pad %) call-lines)
                ;; Render-result: show tool output/error
                result-lines (render-result-fn content is-error theme width expanded?)
                result-indented (mapv #(str left-pad %) result-lines)]
            (vec (concat
                   (repeat pad-y (bg empty))
                   (map bg call-indented)
                   (map bg (cons (str left-pad (apply str (repeat (max 0 (- cw (count name))) "─"))) []))
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
  [& {:keys [name content is-error theme output-pad expanded? render-call-fn render-result-fn]
      :or {name "" content "" is-error false theme theme/dark-theme
           output-pad 1 expanded? false}}]
  (map->ToolExecutionComponent {:name-atom (atom name)
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
