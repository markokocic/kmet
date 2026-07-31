(ns kmet.app.commands
  "Slash command registry. Commands are maps:
     {:name string
      :description string
      :argument-hint optional string
      :get-argument-completions optional (fn [arg-prefix] -> items)
      :handler (fn [cs args] ...)}
   The registry is global so builtins, skills, and extensions can all
   register commands; the editor's autocomplete provider reads it live.")

(defonce ^:private registry (atom []))

(defn register-command!
  "Register (or replace by :name) a slash command. Returns nil."
  [cmd]
  (swap! registry (fn [cmds] (conj (remove #(= (:name %) (:name cmd)) cmds) cmd)))
  nil)

(defn unregister-command!
  "Remove a slash command by :name. Returns nil."
  [name]
  (swap! registry (fn [cmds] (remove #(= (:name %) name) cmds)))
  nil)

(defn clear-commands!
  "Remove all registered commands (mainly for tests). Returns nil."
  []
  (reset! registry [])
  nil)

(defn get-commands
  "All registered slash commands."
  []
  @registry)

(defn find-command
  "Look up a slash command by :name, or nil."
  [name]
  (first (filter #(= (:name %) name) @registry)))
