(ns kmet.app.ui.dock
  "Editor-dock mounting for selectors (pi: interactive-mode showSelector).
   All full-panel selectors mount the way pi's do: the component replaces
   the editor in the editor dock and takes focus; the returned done fn
   restores the previously active editor and focus (pi: done())."
  (:require [kmet.tui.components.container :as container]
            [kmet.tui.core :as tui]))

(def ^:private dock-generation
  "pi: activeSelectorToken — only the most recently mounted selector may
  restore the editor, so a stale done() from a replaced selector is inert
  instead of yanking the newer one out of the dock."
  (atom 0))

(defn mount!
  "Swap COMPONENT into CS's editor dock. FOCUS-TARGET (pi: showSelector's
  `focus`) is the component that receives keys — the interactive child of
  the panel when COMPONENT itself is inert chrome (pi:
  `focus: selector.getMessageList()`); defaults to COMPONENT. Returns DONE
  — a zero-arg fn restoring the active editor (current-editor-atom, so
  custom editors survive) and focus; re-adding the same instance is
  idempotent, so an accidental double call is harmless."
  ([cs component]
   (mount! cs component nil))
  ([cs component focus-target]
   (let [gen (swap! dock-generation inc)
         tui* (:tui cs)
         editor-container (:editor-container cs)
         done (fn []
                ;; stale done() (a newer selector was mounted meanwhile)
                ;; must not restore the editor over it (pi:
                ;; activeSelectorToken check in done())
                (when (= gen @dock-generation)
                  (container/container-clear editor-container)
                  (container/container-add-child editor-container
                                                 @(:current-editor-atom cs))
                  (tui/tui-set-focus tui* @(:current-editor-atom cs))
                  (tui/tui-request-render tui*)))]
     (container/container-clear editor-container)
     (container/container-add-child editor-container component)
     (tui/tui-set-focus tui* (or focus-target component))
     (tui/tui-request-render tui*)
     done)))
