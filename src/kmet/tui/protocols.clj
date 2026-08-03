(ns kmet.tui.protocols
  "Protocols for the TUI component system.
   Extracted from core.clj to avoid circular dependencies.")

(defprotocol IComponent
  "Component interface (pi: Component). Implement render/handle-input/
   invalidate. Records may optionally carry a :wants-key-release? field
   (pi: Component.wantsKeyRelease) — when true, Kitty protocol key release
   events are delivered to handle-input (filtered otherwise)."
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

(defprotocol IEditorComponent
  "Custom editor contract (pi: EditorComponent in editor-component.ts).
   Extends IComponent with text access and app-integration callbacks so
   extensions can provide alternative editor implementations (vim/emacs
   modes etc.). Callbacks and appearance that pi exposes as properties
   (onSubmit/onChange/borderColor) remain duck-typed record fields."
  (editor-get-text [this] "Current text content (paste markers unexpanded)")
  (editor-set-text! [this text] "Replace the full text")
  (editor-get-expanded-text [this] "Text with paste markers expanded; falls back to plain text")
  (editor-add-to-history! [this text] "Add text to the history stack")
  (editor-insert-text-at-cursor! [this text] "Insert text at the current cursor")
  (editor-set-autocomplete-provider! [this provider] "Set the autocomplete provider")
  (editor-set-autocomplete-max-visible! [this n] "Set the autocomplete dropdown height")
  (editor-set-padding-x! [this n] "Set horizontal padding")
  (editor-set-on-submit! [this f] "Set the submit callback")
  (editor-set-on-change! [this f] "Set the change callback"))
