(ns kmet.tui.test-dispose
  "Dispose plumbing tests (dsl.md §5, stage 2): defcomponent synthesizes a
   no-op dispose, containers delegate to their children, the TUI disposes
   removed/cleared children, and dispose is idempotent."
  (:require [clojure.test :as t]
            [kmet.tui.components.box :as box]
            [kmet.tui.components.container :as container]
            [kmet.tui.components.h-stack :as h-stack]
            [kmet.tui.components.scroll-view :as scroll-view]
            [kmet.tui.components.text :as text]
            [kmet.tui.components.v-stack :as v-stack]
            [kmet.tui.core :as core]
            [kmet.tui.macros :refer [defcomponent]]
            [kmet.tui.protocols :as protocols]))

(defcomponent DisposalProbe nil [flag-atom]
  (render [_this _width] [])
  (dispose [this] (swap! (:flag-atom this) conj :probe)))

(t/deftest defcomponent-synthesizes-no-op-dispose
  ;; a component WITHOUT a custom dispose still satisfies IComponent and
  ;; can be disposed safely (the synthesized no-op)
  (let [c (text/make-text "x" 0 0)]
    (t/is (satisfies? protocols/IComponent c))
    (t/is (nil? (protocols/dispose c)) "no throw, returns nil")
    (t/is (nil? (protocols/dispose c)) "idempotent")))

(t/deftest custom-dispose-fires
  (let [flag (atom [])
        p (map->DisposalProbe {:kind nil :flag-atom flag})]
    (protocols/dispose p)
    (t/is (= [:probe] @flag))
    (protocols/dispose p)
    (t/is (= [:probe :probe] @flag) "custom dispose runs each call — keep it idempotent internally")))

(t/deftest containers-delegate-to-children
  (let [flag (atom [])
        child (map->DisposalProbe {:kind nil :flag-atom flag})
        b (box/make-box 0 0 nil)]
    (box/box-add-child b child)
    (protocols/dispose b)
    (t/is (= [:probe] @flag) "box delegates"))
  (let [flag (atom [])
        child (map->DisposalProbe {:kind nil :flag-atom flag})
        c (container/make-container [child])]
    (protocols/dispose c)
    (t/is (= [:probe] @flag) "container delegates"))
  (let [flag (atom [])
        child (map->DisposalProbe {:kind nil :flag-atom flag})
        vs (v-stack/make-v-stack [child])]
    (protocols/dispose vs)
    (t/is (= [:probe] @flag) "v-stack delegates through entry maps"))
  (let [flag (atom [])
        child (map->DisposalProbe {:kind nil :flag-atom flag})
        hs (h-stack/make-h-stack [{:component child}])]
    (protocols/dispose hs)
    (t/is (= [:probe] @flag) "h-stack delegates through entry maps"))
  (let [flag (atom [])
        child (map->DisposalProbe {:kind nil :flag-atom flag})
        sv (scroll-view/make-scroll-view child)]
    (protocols/dispose sv)
    (t/is (= [:probe] @flag) "scroll-view delegates to its single child"))
  ;; stack entry maps resolve via stack/entry-component
  (let [flag (atom [])
        child (map->DisposalProbe {:kind nil :flag-atom flag})
        vs (v-stack/make-v-stack [{:component child}])]
    (protocols/dispose vs)
    (t/is (= [:probe] @flag))))

(t/deftest tui-disposes-removed-and-cleared-children
  (let [tui (core/create-tui nil)
        flag (atom [])
        probe (fn [] (map->DisposalProbe {:kind nil :flag-atom flag}))
        a (probe) b (probe)
        _ (core/tui-add-child tui a)
        _ (core/tui-add-child tui b)]
    (core/tui-remove-child tui a)
    (t/is (= [:probe] @flag))
    (core/tui-clear tui)
    (t/is (= [:probe :probe] @flag))
    (t/is (empty? @(:components tui)))))
