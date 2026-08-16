(ns kmet.libs.hooks
  "Generic single-fn slot registry. Each slot is an atom holding a fn (or nil);
   install replaces the fn, apply invokes it.

   Used by kmet.ai.hooks (provider-event hooks) and kmet.ai.auth
   (config-key-source, oauth-source) — the pattern is identical, so the
   shared lib avoids the boilerplate duplication.")

(defn make-slot
  "Create an empty hook slot (atom holding nil)."
  []
  (atom nil))

(defn set-slot!
  "Install F as the slot's handler. Returns nil."
  [slot f]
  (reset! slot f)
  nil)

(defn apply-slot
  "Apply SLOT to args, returning (apply f args) when installed, or nil."
  [slot & args]
  (when-let [f @slot]
    (apply f args)))
