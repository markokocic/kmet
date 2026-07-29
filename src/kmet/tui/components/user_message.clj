(ns kmet.tui.components.user-message
  "UserMessage component — Pi's UserMessageComponent.
   Renders user messages in a Box with user-message-bg background
   and user-message-text foreground color."
  (:require [kmet.tui.protocols :as protocols]
            [kmet.tui.utils :as u]
            [kmet.tui.theme :as theme]))

(defrecord UserMessage [text-atom     ;; string
                        theme-atom
                        output-pad-atom
                        cache-atom]
  protocols/IComponent

  (render [this width]
    (let [text @text-atom
          theme @theme-atom
          output-pad @output-pad-atom
          cached @cache-atom]
      (if (and cached (= (:width cached) width) (= (:text cached) text))
        (:lines cached)
        (let [pad-x output-pad
              pad-y 1
              cw (max 1 (- width (* 2 pad-x)))
              left-pad (apply str (repeat pad-x \space))
              wrapped (u/wrap-text-with-ansi text cw)
              colored (mapv #(theme/fg theme :user-message-text %) wrapped)
              indented (mapv #(str left-pad %) colored)
              bg (fn [line] (theme/bg theme :user-message-bg
                              (str line (apply str (repeat (max 0 (- width (u/visible-width line))) \space)))))
              empty (apply str (repeat width \space))
              top-pad (repeat pad-y (bg empty))
              bottom-pad (repeat pad-y (bg empty))
              result (vec (concat top-pad (map bg indented) bottom-pad))]
          (reset! cache-atom {:width width :text text :lines result})
          result))))

  (handle-input [_this _data] nil)

  (invalidate [this]
    (reset! (:cache-atom this) nil)))

(defn make-user-message
  "Create a UserMessage component.
   Options:
     :text       — user message content (default \"\")
     :theme      — Theme record (default dark-theme)
     :output-pad — horizontal padding (default 1)"
  [& {:keys [text theme output-pad]
      :or {text "" theme theme/dark-theme output-pad 1}}]
  (map->UserMessage {:text-atom (atom text)
                     :theme-atom (atom theme)
                     :output-pad-atom (atom output-pad)
                     :cache-atom (atom nil)}))

(defn user-message-set-text!
  "Update the user message text."
  [comp text]
  (reset! (:text-atom comp) text)
  (protocols/invalidate comp))

(defn user-message-set-theme!
  "Set the theme used for rendering."
  [comp theme]
  (reset! (:theme-atom comp) theme)
  (protocols/invalidate comp))

(defn user-message-set-output-pad!
  "Set horizontal padding."
  [comp n]
  (reset! (:output-pad-atom comp) n)
  (protocols/invalidate comp))
