(ns kmet.tui.protocols
  "Protocols for the TUI component system.
   Extracted from core.clj to avoid circular dependencies.")

(defprotocol IComponent
  (render [this width] "Render component to lines (seq of strings)")
  (handle-input [this data] "Handle keyboard input")
  (invalidate [this] "Clear cached render state"))

(defprotocol IFocusable
  (focused [this])
  (set-focused! [this val]))

(defprotocol IComponentKind
  "Reliable type dispatch for message components.
   Safer than key-based duck typing (contains? :name-atom) which
   silently breaks when record fields are renamed."
  (component-kind [this] "Returns :user, :assistant, :tool, :custom, or nil"))
