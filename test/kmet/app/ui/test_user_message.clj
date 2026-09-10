(ns kmet.app.ui.test-user-message
  (:require [clojure.string :as str]
            [clojure.test :as t :refer [deftest is testing]]
            [kmet.tui.theme :as theme]
            [kmet.tui.core :as core]
            [kmet.libs.terminal-image :as timg]
            [kmet.app.ui.subs :as subs]
            [kmet.app.ui.user-message :as um]))

(defn- strip-ansi [s]
  (clojure.string/replace s #"\u001b\[[0-9;]*[a-zA-Z]" ""))

(deftest test-create
  (testing "create user message component"
    (let [c (um/make-user-message :text "hello")]
      (is (some? c))
      (is (satisfies? core/IComponent c)))))

(deftest test-render-shows-content
  (testing "render shows the message text"
    (let [c (um/make-user-message :text "Hello world")
          lines (core/render c 40)]
      (is (pos? (count lines)))
      (is (some #(re-find #"Hello world" %) (mapv strip-ansi lines)))
      "User message content should be visible")))

(deftest test-render-no-old-header
  (testing "no old-style ─── You header"
    (let [c (um/make-user-message :text "test")]
      (is (not-any? #(re-find #"───" %) (mapv strip-ansi (core/render c 40)))))))

(deftest test-render-background
  (testing "renders with user-message-bg background"
    (let [c (um/make-user-message :text "test")
          lines (core/render c 40)]
      (is (every? #(re-find #"\u001b\[48" %) lines)
          "All lines should have background ANSI codes"))))

(deftest test-theme-sub-retheme
  (testing "swapping the shared theme atom re-themes the message on next render (Stage 5)"
    (let [c (um/make-user-message :text "hello")
          before (core/render c 40)]
      (reset! theme/theme-atom (theme/get-theme "light"))
      (try
        (let [after (core/render c 40)]
          (is (= (mapv strip-ansi before) (mapv strip-ansi after))
              "content unchanged across the palette switch")
          (is (not= before after) "styling changed with the theme"))
        (finally
          (reset! theme/theme-atom (theme/get-theme "dark")))))))

(deftest test-set-output-pad
  (testing "set-output-pad! changes padding"
    (let [c (um/make-user-message :text "hello" :output-pad 3)]
      (um/user-message-set-output-pad! c 5)
      (is (pos? (count (core/render c 40)))))))

(deftest test-empty-text
  (testing "empty text renders lines (box padding)"
    (let [c (um/make-user-message :text "")]
      (is (pos? (count (core/render c 40)))))))

(deftest test-long-text-wraps
  (testing "long text wraps to fit width"
    (let [c (um/make-user-message :text (apply str (repeat 200 "x")))
          lines (core/render c 40)]
      (is (> (count lines) 3) "Long text should wrap to multiple lines"))))

;; ─── Image attachments (P2: terminal.showImages) ───────────────────────────

(def ^:private png
  "A 1x1 PNG."
  "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==")

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

(deftest test-images-render-fallback
  (testing "attached images render as image elements inside the message (text indicator without protocol support)"
    (with-image-env
      {:show-images true :image-width-cells 60}
      {:images nil :true-color true :hyperlinks true}
      (fn []
        (let [c (um/make-user-message :text "see:"
                                      :images [{:data png :mime-type "image/png"}])
              plain (mapv strip-ansi (core/render c 60))]
          (is (some #(re-find #"see:" %) plain))
          (is (some #(re-find #"\[Image: \[image/png\] 1x1\]" %) plain)))))))

(deftest test-images-follow-settings-live
  (testing "a show-images change re-renders mounted message images"
    (with-image-env
      {:show-images true :image-width-cells 20}
      {:images :kitty :true-color true :hyperlinks true}
      (fn []
        (let [c (um/make-user-message :text "see:"
                                      :images [{:data png :mime-type "image/png"}])
              seq-line (fn [lines] (first (filter #(str/includes? % "\u001b_G") lines)))]
          (is (= "20" (second (re-find #"c=(\d+)" (seq-line (core/render c 60))))))
          (reset! subs/image-settings-atom {:show-images false :image-width-cells 20})
          (let [lines (core/render c 60)]
            (is (not-any? #(str/includes? % "\u001b_G") lines))
            (is (some #(re-find #"\[Image: \[image/png\] 1x1\]" %)
                      (mapv strip-ansi lines)))))))))

(deftest test-images-survive-output-pad-rebuild
  (testing "set-output-pad! rebuilds the box without dropping the image blocks"
    (with-image-env
      {:show-images true :image-width-cells 60}
      {:images nil :true-color true :hyperlinks true}
      (fn []
        (let [c (um/make-user-message :text "see:" :output-pad 1
                                      :images [{:data png :mime-type "image/png"}])]
          (um/user-message-set-output-pad! c 3)
          (let [plain (mapv strip-ansi (core/render c 60))]
            (is (some #(re-find #"see:" %) plain))
            (is (some #(re-find #"\[Image: \[image/png\] 1x1\]" %) plain))))))))
