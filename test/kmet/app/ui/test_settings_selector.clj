(ns kmet.app.ui.test-settings-selector
  (:require [clojure.test :as t :refer [deftest is testing]]
            [kmet.app.ui.settings-selector :as ss]
            [kmet.app.ui.subs :as subs]
            [kmet.libs.terminal-image :as timg]))

(defn- with-image-env
  "Run F with the shared image settings and terminal capabilities bound
   (both are process-global); restore afterwards."
  [settings caps f]
  (let [prev-settings @subs/image-settings-atom
        prev-caps (timg/get-capabilities)]
    (try
      (reset! subs/image-settings-atom settings)
      (timg/set-capabilities! caps)
      (f)
      (finally
        (reset! subs/image-settings-atom prev-settings)
        (timg/set-capabilities! prev-caps)))))

(deftest image-rows-gated-on-terminal-support
  (testing "no image rows on a terminal without image support (pi: supportsImages)"
    (with-image-env
      {:show-images true :image-width-cells 60}
      {:images nil :true-color true :hyperlinks true}
      (fn []
        (is (nil? (#'ss/image-rows))))))
  (testing "rows appear when the terminal supports images"
    (with-image-env
      {:show-images true :image-width-cells 60}
      {:images :kitty :true-color true :hyperlinks true}
      (fn []
        (is (= 2 (count (#'ss/image-rows))))))))

(deftest image-rows-reflect-live-settings
  (testing "the rows carry the live settings values and pi's width choices"
    (with-image-env
      {:show-images false :image-width-cells 80}
      {:images :kitty :true-color true :hyperlinks true}
      (fn []
        (is (= [{:id :show-images
                 :label "Show images"
                 :value "false"
                 :values ["true" "false"]}
                {:id :image-width-cells
                 :label "Image width"
                 :value "80"
                 :values ["60" "80" "120"]}]
               (#'ss/image-rows)))))))
