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
