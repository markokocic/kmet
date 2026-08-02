(ns kmet.tui.components.test-cancellable-loader
  (:require [clojure.test :as t]
            [kmet.tui.core :as core]
            [kmet.tui.components.spinner :as spinner]
            [kmet.tui.components.cancellable-loader :as cl]))

(defn- make-loader [& {:keys [on-abort]}]
  (cl/make-cancellable-loader
   :spinner (spinner/make-spinner :text "Working..." :active true)
   :on-abort on-abort))

(t/deftest test-create
  (let [loader (make-loader)]
    (t/is (satisfies? core/IComponent loader))
    (t/is (not (cl/cancellable-loader-aborted? loader)))))

(t/deftest test-renders-like-spinner
  (let [loader (make-loader)]
    ;; pi Loader shape: leading blank line + animated line
    (t/is (= 2 (count (core/render loader 20))))
    (t/is (= "" (first (core/render loader 20))) "leading blank line")
    (t/is (re-find #"Working" (second (core/render loader 20))))))

(t/deftest test-escape-aborts
  (let [loader (make-loader)]
    (core/handle-input loader "\u001b")  ;; raw ESC
    (t/is (cl/cancellable-loader-aborted? loader))))

(t/deftest test-other-keys-do-not-abort
  (let [loader (make-loader)]
    (core/handle-input loader "a")
    (core/handle-input loader "up")
    (t/is (not (cl/cancellable-loader-aborted? loader)))))

(t/deftest test-on-abort-callback
  (let [called (atom 0)
        loader (make-loader :on-abort (fn [] (swap! called inc)))]
    (core/handle-input loader "\u001b")
    (t/is (= 1 @called))
    (cl/cancellable-loader-set-on-abort! loader (fn [] (swap! called + 10)))
    (t/is (= 1 @called) "setter does not fire the callback")))

(t/deftest test-signal-atom
  (let [loader (make-loader)
        sig (cl/cancellable-loader-signal loader)]
    (t/is (instance? clojure.lang.Atom sig))
    (core/handle-input loader "\u001b")
    (t/is (true? @sig))))

(t/deftest test-dispose-stops-spinner
  (let [loader (make-loader)]
    (t/is (spinner/spinner-active? (:spinner loader)))
    (cl/cancellable-loader-dispose! loader)
    (t/is (not (spinner/spinner-active? (:spinner loader))))))
