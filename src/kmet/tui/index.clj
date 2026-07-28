(ns kmet.tui.index
  "Convenience namespace that re-exports all TUI components."
  (:refer-clojure :exclude [render])
  (:require [kmet.tui.core :as core]
            [kmet.tui.keys :as keys]
            [kmet.tui.terminal :as terminal]
            [kmet.tui.utils :as utils]
            [kmet.tui.components.text :as text]
            [kmet.tui.components.container :as container]
            [kmet.tui.components.spacer :as spacer]
            [kmet.tui.components.box :as box]
            [kmet.tui.components.input :as input]))

;; Re-exports from core
(def IComponent core/IComponent)
(def IFocusable core/IFocusable)
(def render core/render)
(def handle-input core/handle-input)
(def invalidate core/invalidate)
(def focused core/focused)
(def set-focused! core/set-focused!)
(def create-tui core/create-tui)
(def tui-add-child core/tui-add-child)
(def tui-remove-child core/tui-remove-child)
(def tui-set-focus core/tui-set-focus)
(def tui-add-input-listener core/tui-add-input-listener)
(def tui-start core/tui-start)
(def tui-stop core/tui-stop)
(def tui-request-render core/tui-request-render)
(def tui-show-overlay core/tui-show-overlay)
(def tui-hide-overlay core/tui-hide-overlay)
(def tui-has-overlay? core/tui-has-overlay?)

;; Re-exports from terminal
(def ITerminal terminal/ITerminal)
(def create-terminal terminal/create-terminal)

;; Re-exports from keys
(def matches-key? keys/matches-key?)
(def parse-key keys/parse-key)
(def is-key-release? keys/is-key-release?)
(def is-key-repeat? keys/is-key-repeat?)
(def set-kitty-active! keys/set-kitty-active!)
(def kitty-active? keys/kitty-active?)

;; Key constants
(def KEY-UP keys/KEY-UP)
(def KEY-DOWN keys/KEY-DOWN)
(def KEY-LEFT keys/KEY-LEFT)
(def KEY-RIGHT keys/KEY-RIGHT)
(def KEY-ENTER keys/KEY-ENTER)
(def KEY-ESC keys/KEY-ESC)
(def KEY-TAB keys/KEY-TAB)
(def KEY-BACKSPACE keys/KEY-BACKSPACE)
(def KEY-DELETE keys/KEY-DELETE)
(def KEY-HOME keys/KEY-HOME)
(def KEY-END keys/KEY-END)
(def KEY-PAGE-UP keys/KEY-PAGE-UP)
(def KEY-PAGE-DOWN keys/KEY-PAGE-DOWN)
(def KEY-SPACE keys/KEY-SPACE)
(def KEY-INSERT keys/KEY-INSERT)
(def ctrl keys/ctrl)
(def shift keys/shift)
(def alt keys/alt)

;; Re-exports from utils
(def visible-width utils/visible-width)
(def truncate-to-width utils/truncate-to-width)
(def wrap-text-with-ansi utils/wrap-text-with-ansi)
(def apply-background-to-line utils/apply-background-to-line)
(def strip-ansi-codes utils/strip-ansi-codes)
(def sgr utils/sgr)
(def slice-by-column utils/slice-by-column)

;; Re-exports from components
(def make-text text/make-text)
(def text-set! text/text-set!)
(def make-spacer spacer/make-spacer)
(def make-container container/make-container)
(def container-add-child container/container-add-child)
(def container-remove-child container/container-remove-child)
(def container-clear container/container-clear)
(def make-box box/make-box)
(def box-add-child box/box-add-child)
(def box-remove-child box/box-remove-child)
(def box-clear box/box-clear)
(def box-set-bg-fn box/box-set-bg-fn)
(def make-input input/make-input)
(def input-set-value! input/input-set-value!)
(def input-get-value input/input-get-value)
(def input-set-on-submit! input/input-set-on-submit!)
(def input-set-on-escape! input/input-set-on-escape!)
