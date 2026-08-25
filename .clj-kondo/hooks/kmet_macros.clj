(ns hooks.kmet-macros
  "clj-kondo analysis hooks for kmet.tui.macros custom macros."
  (:require [clj-kondo.hooks-api :as api]))

(defn defcomponent
  "Teach clj-kondo that (defcomponent Name kind [fields] method...)
   is a defrecord whose FIRST field is the stamped kind (kind-as-data).
   The field is the SYMBOL `kind` — the value at the call site (nil or a
   keyword) must not leak into the binding vector. The protocol is
   omitted: kmet.tui.protocols is not required by every component file,
   and method bodies/fields are analyzed identically without it."
  [{:keys [node]}]
  (let [[_ name _kind fields & methods] (:children node)
        fields-with-kind (api/vector-node
                         (cons (api/token-node 'kind) (:children fields)))]
    {:node (api/list-node
            (list* (api/token-node 'defrecord)
                   name
                   fields-with-kind
                   methods))}))

(defn with-let
  "Teach clj-kondo that (with-let [x init ...] body... (finally cleanup))
   binds the names like let (init/cleanup run under the store at runtime)."
  [{:keys [node]}]
  (let [[_ bindings & body] (:children node)
        body' (remove (fn [f] (and (api/list-node? f)
                                   (= 'finally (api/sexpr (first (:children f))))))
                      body)]
    {:node (api/list-node
            (list* (api/token-node 'let) bindings body'))}))
