(ns kmet.tui.components.tool-execution
  "ToolExecution component — Pi's ToolExecutionComponent.
   Renders tool calls/results in a Box with appropriate background
   (tool-pending-bg / tool-success-bg / tool-error-bg)."
  (:require [kmet.tui.protocols :as protocols]
            [kmet.tui.utils :as u]
            [kmet.tui.theme :as theme]))

(def ^:private BLD "\u001b[1m")
(def ^:private RST "\u001b[0m")

(defrecord ToolExecution [name-atom        ;; string: tool name
                          content-atom     ;; string: output content
                          is-error-atom    ;; bool
                          theme-atom
                          output-pad-atom
                          expanded-atom    ;; bool: show content?
                          cache-atom]
  protocols/IComponent

  (render [this width]
    (let [name @name-atom
          content @content-atom
          is-error @is-error-atom
          theme @theme-atom
          output-pad @output-pad-atom
          expanded? @expanded-atom
          cached @cache-atom]
      (if (and cached
               (= (:width cached) width)
               (= (:name cached) name)
               (= (:content cached) content)
               (= (:error cached) is-error)
               (= (:theme cached) theme)
               (= (:output-pad cached) output-pad)
               (= (:expanded cached) expanded?))
        (:lines cached)
        (let [pad-x output-pad
              pad-y 1
              cw (max 1 (- width (* 2 pad-x)))
              left-pad (apply str (repeat pad-x \space))
              bg-key (if is-error :tool-error-bg :tool-success-bg)
              bg (fn [line] (theme/bg theme bg-key
                              (str line (apply str (repeat (max 0 (- width (u/visible-width line))) \space)))))
              empty (apply str (repeat width \space))
              ;; Tool name line — bold + tool-title color
              name-str (str BLD (theme/fg theme :tool-title name) RST)
              name-wrapped (u/wrap-text-with-ansi name-str cw)
              name-indented (mapv #(str left-pad %) name-wrapped)
              ;; Content lines (show by default; can be hidden by setting expanded=false and hiding)
              show-content true
              content-indented (when (and (seq content) show-content)
                                 (let [wrapped (u/wrap-text-with-ansi content cw)
                                       colored (mapv #(theme/fg theme :tool-output %) wrapped)]
                                   (mapv #(str left-pad %) colored)))
              top-pad (repeat pad-y (bg empty))
              bottom-pad (repeat pad-y (bg empty))
              result (vec (concat top-pad
                                  (map bg name-indented)
                                  (when content-indented (map bg content-indented))
                                  bottom-pad))]
          (reset! cache-atom {:width width :name name :content content
                              :error is-error :theme theme
                              :output-pad output-pad :expanded expanded?
                              :lines result})
          result))))

  (handle-input [_this _data] nil)

  (invalidate [this]
    (reset! (:cache-atom this) nil)))

;; ─── Construction ──────────────────────────────────────────────────────────

(defn make-tool-execution
  "Create a ToolExecution component.
   Options:
     :name       — tool name (default \"\")
     :content    — tool output (default \"\")
     :is-error   — is this an error? (default false)
     :theme      — Theme record
     :output-pad — horizontal padding (default 1)
     :expanded?  — show content? (default false)"
  [& {:keys [name content is-error theme output-pad expanded?]
      :or {name "" content "" is-error false theme theme/dark-theme
           output-pad 1 expanded? false}}]
  (map->ToolExecution {:name-atom (atom name)
                       :content-atom (atom content)
                       :is-error-atom (atom is-error)
                       :theme-atom (atom theme)
                       :output-pad-atom (atom output-pad)
                       :expanded-atom (atom expanded?)
                       :cache-atom (atom nil)}))

;; ─── Public API ────────────────────────────────────────────────────────────

(defn tool-execution-set-name!
  "Set the tool name."
  [comp name]
  (reset! (:name-atom comp) name)
  (protocols/invalidate comp))

(defn tool-execution-set-content!
  "Set the tool output content."
  [comp content]
  (reset! (:content-atom comp) content)
  (protocols/invalidate comp))

(defn tool-execution-set-error!
  "Set the error flag."
  [comp is-error]
  (reset! (:is-error-atom comp) is-error)
  (protocols/invalidate comp))

(defn tool-execution-set-expanded!
  "Set whether tool content is expanded."
  [comp expanded?]
  (reset! (:expanded-atom comp) expanded?)
  (protocols/invalidate comp))

(defn tool-execution-set-theme!
  "Set the theme."
  [comp theme]
  (reset! (:theme-atom comp) theme)
  (protocols/invalidate comp))

(defn tool-execution-set-output-pad!
  "Set horizontal padding."
  [comp n]
  (reset! (:output-pad-atom comp) n)
  (protocols/invalidate comp))
