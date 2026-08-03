(ns kmet.app.ui.test-loaded-resources
  (:require [clojure.string :as str]
            [clojure.test :as t :refer [deftest is testing]]
            [kmet.tui.core :as core]
            [kmet.app.ui.loaded-resources :as lr]))

(defn- strip-ansi [s]
  (str/replace s #"\u001b\[[0-9;]*[a-zA-Z]" ""))

(defn- render-plain [c width]
  (mapv strip-ansi (core/render c width)))

(deftest test-empty-renders-nothing
  (testing "no sections renders nothing"
    (let [c (lr/make-loaded-resources)]
      (is (= [] (core/render c 60))))))

(deftest test-section-collapsed
  (testing "collapsed sections show heading + compact comma list"
    (let [c (lr/make-loaded-resources)
          _ (lr/loaded-resources-set-sections!
             c [{:name "Skills" :items ["/a" "/b"] :expanded-items ["  /path/a" "  /path/b"]}])
          plain (render-plain c 60)]
      (is (some #(.contains % "[Skills]") plain))
      (is (some #(.contains % "/a, /b") plain))
      (is (not-any? #(.contains % "/path/a") plain)))))

(deftest test-section-expanded
  (testing "expanded sections show the full path list"
    (let [c (lr/make-loaded-resources :expanded? true)
          _ (lr/loaded-resources-set-sections!
             c [{:name "Skills" :items ["/a"] :expanded-items ["  /path/a"]}])
          plain (render-plain c 60)]
      (is (some #(.contains % "/path/a") plain)))))

(deftest test-set-expanded-toggles
  (testing "set-expanded! switches between compact and full bodies"
    (let [c (lr/make-loaded-resources)
          _ (lr/loaded-resources-set-sections!
             c [{:name "Context" :items ["~/.kmet/agent/AGENTS.md"]
                 :expanded-items ["  ~/.kmet/agent/AGENTS.md"]}])]
      (lr/loaded-resources-set-expanded! c true)
      (is (some #(.contains % "~/.kmet/agent/AGENTS.md") (render-plain c 60))))))

(deftest test-trailing-blank
  (testing "each section is followed by a blank spacer line"
    (let [c (lr/make-loaded-resources)
          _ (lr/loaded-resources-set-sections!
             c [{:name "A" :items ["x"] :expanded-items ["  x"]}
                {:name "B" :items ["y"] :expanded-items ["  y"]}])
          plain (render-plain c 60)]
      (is (some #(= "" %) plain) "sections separated by blank lines"))))
