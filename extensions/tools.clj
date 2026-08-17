(ns kmet.extensions.tools
  "Interactive /tools selector — port of pi's example tools extension
   (packages/coding-agent/examples/extensions/tools.ts).

   Provides a /tools command that opens an interactive selector to enable
   and disable tools. The selection persists across session reloads as a
   tools-config custom entry along the active branch and is restored on
   session start and branch navigation.

   Loading this extension replaces the builtin /tools listing command —
   builtins never clobber commands an extension registered (pi has no
   builtin /tools; the example extension owns the name there).

   The dialog is built from the generic TUI layer (kmet.tui.* — the port
   of pi's @earendil-works/pi-tui), which extension contexts share by
   reference: the SettingsList component handles navigation, value cycling
   and key parsing itself; the theme helpers handle styling. ui-custom
   accepts either an IComponent or a duck-typed {:render :handle-input
   :invalidate} map (pi: custom() accepts both a Component and a
   duck-typed object).

   Usage: symlink or copy this file into ~/.kmet/agent/extensions/ (global)
   or .kmet/extensions/ (project-local), then restart kmet or run /reload."
  (:require [kmet.extension :as ext]
            [kmet.tui.protocols :as protocols]
            [kmet.tui.theme :as theme]
            [kmet.tui.components.container :as container]
            [kmet.tui.components.settings-list :as settings-list]
            [kmet.tui.components.text :as text]
            [kmet.tui.components.spacer :as spacer]))

;; ─── Extension state ───────────────────────────────────────────────────────

(def ^:private tools-config-type "tools-config")

(defn- persist-state!
  "Append the current selection as a tools-config custom entry (pi:
   persistState). Entries ride the branch, so restore reads the last one on
   the active path."
  [api state]
  ((:append-entry! (ext/session api))
   tools-config-type {:enabled-tools (vec (sort (:enabled-tools @state)))}))

(defn- apply-tools!
  "Apply the current selection to the agent (pi: applyTools)."
  [api state]
  (ext/set-active-tools api (vec (:enabled-tools @state))))

(defn- restore-from-branch!
  "Sync the selection with the session (pi: restoreFromBranch): the last
   tools-config entry on the active branch wins, filtered to tools that
   still exist; without one, mirror the currently active tools."
  [api state]
  (let [sess (ext/session api)
        all-tools (ext/get-all-tools api)
        all-names (mapv :name all-tools)
        saved (last (keep (fn [e] (some-> (:data e) :enabled-tools))
                          ((:get-entries sess) tools-config-type)))]
    (swap! state assoc :all-tools all-tools)
    (if saved
      (do (swap! state assoc :enabled-tools
                 (set (filter (fn [t] (some #(= t %) all-names)) saved)))
          (apply-tools! api state))
      (swap! state assoc :enabled-tools
             (set (or (ext/get-active-tools api) all-names))))))

;; ─── The /tools dialog (pi: the ctx.ui.custom factory) ─────────────────────

(defn- tools-dialog
  "Build the /tools selector component. TUI/TH/KB/CLOSE are what ui-custom
   passes to factories (pi: (tui, theme, kb, done)); the render loop
   re-renders after every input, so no explicit requestRender is needed.
   The SettingsList handles navigation, value cycling and escape itself."
  [api state _tui th _kb close]
  (let [items (mapv (fn [t]
                      {:id (:name t)
                       :label (:name t)
                       :value (if (contains? (:enabled-tools @state)
                                             (:name t))
                                "enabled" "disabled")
                       :values ["enabled" "disabled"]})
                    (sort-by :name (:all-tools @state)))
        settings (settings-list/make-settings-list
                  items
                  :theme (theme/get-settings-list-theme th)
                  :max-visible (min (+ (count items) 2) 15)
                  :on-change (fn [id new-value]
                               (swap! state update :enabled-tools
                                      (if (= new-value "enabled") conj disj) id)
                               (apply-tools! api state)
                               (persist-state! api state)))
        _ (settings-list/settings-list-set-on-escape! settings
                                                      (fn [] (close nil)))
        c (container/make-container)
        _ (container/container-add-child
           c (text/make-text
              (theme/fg th :accent (theme/bold "Tool Configuration")) 0 0))
        _ (container/container-add-child c (spacer/make-spacer 1))
        _ (container/container-add-child c settings)]
    ;; duck-typed component: input goes to the settings list, render and
    ;; invalidate delegate to the container (pi: the wrapper object
    ;; delegating handleInput to the settingsList)
    {:render (fn [width] (protocols/render c width))
     :handle-input (fn [data] (protocols/handle-input settings data))
     :invalidate (fn [] (protocols/invalidate c))}))

(defn- open-selector!
  "Open the /tools selector. Headless/print mode has no UI registry
   (ctx.mode !== 'tui' in pi) — notify instead of showing a dialog."
  [api state ctx]
  (swap! state assoc :all-tools (ext/get-all-tools api))
  (when (or (not= :interactive (:mode ctx))
            (not (ext/ui-custom api (fn [tui th kb close]
                                      (tools-dialog api state tui th kb close)))))
    (ext/ui-notify api "/tools requires TUI mode" :error)))

(defn init
  "Register the /tools command and restore the selection on session start
   and branch navigation (pi: toolsExtension)."
  [api]
  (let [state (atom {:all-tools [] :enabled-tools #{}})]
    (ext/register-command! api
                           {:name "tools"
                            :description "Enable/disable tools interactively"
                            :handler (fn [ctx _args] (open-selector! api state ctx))})
    ;; handlers receive (event ctx) — pi parity (pi: on('session_start',
    ;; (_event, ctx) => ...)); ctx is unused here but required arity
    (ext/on-event api :session-start
                  (fn [_ev _ctx] (restore-from-branch! api state)))
    (ext/on-event api :session-tree
                  (fn [_ev _ctx] (restore-from-branch! api state)))))
