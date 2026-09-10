(ns kmet.app.ui.test-image-block
  (:require [clojure.string :as str]
            [clojure.test :as t :refer [deftest is testing]]
            [kmet.app.ui.image-block :as ib]
            [kmet.app.ui.subs :as subs]
            [kmet.libs.terminal-image :as timg]
            [kmet.tui.core :as core]
            [kmet.tui.utils :as utils]))

(def ^:private png
  "A 1x1 PNG."
  "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==")

(defn- strip-ansi [s]
  (utils/strip-ansi-codes s))

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

;; ─── content-images ────────────────────────────────────────────────────────

(deftest content-images-extracts-blocks
  (testing "strings and text-only block vectors yield no images"
    (is (= [] (ib/content-images "plain text")))
    (is (= [] (ib/content-images [{:type :text :text "hi"}])))
    (is (= [] (ib/content-images nil))))
  (testing "image blocks become {:data :mime-type} maps"
    (is (= [{:data "AA" :mime-type "image/png"}]
           (ib/content-images [{:type :text :text "see"}
                               {:type :image :data "AA" :mime-type "image/png"}])))
    (is (= [{:data "AA" :mime-type "image/png"}]
           (ib/content-images [{:type "image" :data "AA" :mime-type "image/png"}]))
        "the string :type spelling (session data) is recognized"))
  (testing "malformed blocks are dropped"
    (is (= [] (ib/content-images [{:type :image :mime-type "image/png"}])))))

;; ─── render modes ──────────────────────────────────────────────────────────

(deftest renders-fallback-without-image-support
  (with-image-env
    {:show-images true :image-width-cells 60}
    {:images nil :true-color true :hyperlinks true}
    (fn []
      (let [lines (mapv strip-ansi (core/render (ib/make-image-block png "image/png") 60))]
        (is (some #(str/includes? % "[Image: [image/png] 1x1]") lines))))))

(deftest renders-fallback-when-show-images-off
  (with-image-env
    {:show-images false :image-width-cells 60}
    {:images :kitty :true-color true :hyperlinks true}
    (fn []
      (let [lines (core/render (ib/make-image-block png "image/png") 60)]
        (is (not-any? #(str/includes? % "\u001b_G") lines))
        (is (some #(str/includes? (strip-ansi %) "[Image: [image/png] 1x1]") lines))))))

(deftest renders-image-when-supported-and-enabled
  (with-image-env
    {:show-images true :image-width-cells 20}
    {:images :kitty :true-color true :hyperlinks true}
    (fn []
      (let [lines (core/render (ib/make-image-block png "image/png") 60)
            seq-line (first (filter #(str/includes? % "\u001b_G") lines))]
        (is (some? seq-line))
        (is (= "20" (second (re-find #"c=(\d+)" seq-line)))
            "the configured :image-width-cells caps the rendered width")
        (is (not-any? #(str/includes? % "[Image:") (mapv strip-ansi lines)))))))

(deftest resubscribes-to-settings-changes
  (testing "a settings change re-renders the block (shared sub subscription)"
    (with-image-env
      {:show-images false :image-width-cells 60}
      {:images :kitty :true-color true :hyperlinks true}
      (fn []
        (let [b (ib/make-image-block png "image/png")]
          (is (not-any? #(str/includes? % "\u001b_G") (core/render b 60)))
          (reset! subs/image-settings-atom {:show-images true :image-width-cells 60})
          (is (some #(str/includes? % "\u001b_G") (core/render b 60))))))))
