(ns kmet.app.ui.subs
  "Shared derived-state subscriptions for the UI (dsl.md §3.1/§3.2): plain
   top-level computes def'd once — the def IS the registry. Components deref
   these instead of receiving the values as constructor arguments, so a
   change invalidates exactly the subscribed subtrees and no re-theming
   walk is needed. kmet.tui.hiccup/compute is generic; the app-owned atoms
   live in kmet.tui.theme."
  (:require [kmet.tui.hiccup :as h]
            [kmet.tui.theme :as theme]))

(def theme-sub
  "The active theme (pi: the global theme getter) as a reactive ref.
   Deref inside render bodies — the tracked read subscribes the component,
   and a theme switch (settings, /theme, custom-file reload) re-derives
   exactly the subscribed caches on the next frame."
  (h/compute [theme/theme-atom] identity))
