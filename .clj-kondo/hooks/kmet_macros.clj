(ns hooks.kmet-macros
  "clj-kondo analysis hooks for kmet.tui.macros custom macros."
  (:require [clj-kondo.hooks-api :as api]))

(defn defcomponent
  "Teach clj-kondo that (defcomponent Name kind [fields] method...)
   is a defrecord. The protocol is omitted: kmet.tui.protocols is not
   required by every component file, and method bodies/fields are
   analyzed identically without it."
  [{:keys [node]}]
  (let [[_ name _ fields & methods] (:children node)]
    {:node (api/list-node
            (list* (api/token-node 'defrecord)
                   name
                   fields
                   methods))}))

(defn defsetter
  "Teach clj-kondo that (defsetter name field comp value & body)
   defines (defn name [comp value] (reset! (field comp) value) body...)
   so comp and value count as used."
  [{:keys [node]}]
  (let [[_ name field comp value & body] (:children node)]
    {:node (api/list-node
            (list* (api/token-node 'defn)
                   name
                   (api/vector-node [comp value])
                   (api/list-node (list (api/token-node 'reset!)
                                        (api/list-node (list field comp))
                                        value))
                   body))}))

(defn defgetter
  "Teach clj-kondo that (defgetter name field comp) defines
   (defn name [comp] @(field comp)) so comp counts as used."
  [{:keys [node]}]
  (let [[_ name field comp] (:children node)]
    {:node (api/list-node
            (list (api/token-node 'defn)
                  name
                  (api/vector-node [comp])
                  (api/list-node (list (api/token-node 'deref)
                                       (api/list-node (list field comp))))))}))
