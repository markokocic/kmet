(ns kmet.app.test-extensions
  (:require [clojure.test :as t]
            [clojure.java.io :as io]
            [kmet.app.extensions :as extensions]))

;; ─── Extension loading ────────────────────────────────────────────────────

(t/deftest test-load-extensions-from-dir-non-existent
  (t/testing "Loading from non-existent dir should not throw"
    (t/is (nil? (extensions/load-extensions-from-dir "/nonexistent/extensions")))))

(t/deftest test-load-extensions-from-dir
  (let [tmp-dir (str "target/test-ext-" (System/currentTimeMillis))
        f (str tmp-dir "/ext.clj")]
    (io/make-parents f)
    (spit f "(println \"extension loaded\")")
    (t/testing "Loading should not throw"
      ;; The fixture extension prints "extension loaded" to stdout.
      (t/is (nil? (binding [*out* (java.io.StringWriter.)]
                    (extensions/load-extensions-from-dir tmp-dir)))))))

;; ─── Extension input / before-agent-start hooks ───────────────────────────

(t/deftest test-input-hook-pass
  (extensions/clear-input-hooks!)
  (let [r (extensions/apply-input-hooks "hello" :interactive)]
    (t/is (= :pass (:action r)))
    (t/is (= "hello" (:text r)))))

(t/deftest test-input-hook-transform
  (extensions/clear-input-hooks!)
  (extensions/register-input-hook!
   (fn [{:keys [text]}] {:action :transform :text (str ">> " text)}))
  (let [r (extensions/apply-input-hooks "hello" :interactive)]
    (t/is (= :transform (:action r)))
    (t/is (= ">> hello" (:text r)))))

(t/deftest test-input-hook-transform-chains
  (extensions/clear-input-hooks!)
  (extensions/register-input-hook!
   (fn [{:keys [text]}] {:action :transform :text (str text "!")}))
  (extensions/register-input-hook!
   (fn [{:keys [text]}] {:action :transform :text (str "[" text "]")}))
  (let [r (extensions/apply-input-hooks "a" :interactive)]
    (t/is (= "[a!]" (:text r)) "later hooks see earlier transforms")))

(t/deftest test-input-hook-transform-empty
  (extensions/clear-input-hooks!)
  (extensions/register-input-hook! (fn [_] {:action :transform :text ""}))
  (let [r (extensions/apply-input-hooks "hello" :interactive)]
    (t/is (= "" (:text r)) "empty transform text is honored")))

(t/deftest test-input-hook-handled-stops
  (extensions/clear-input-hooks!)
  (let [later-called (atom false)]
    (extensions/register-input-hook! (fn [_] {:action :handled}))
    (extensions/register-input-hook! (fn [_] (reset! later-called true)))
    (let [r (extensions/apply-input-hooks "x" :interactive)]
      (t/is (= :handled (:action r)))
      (t/is (false? @later-called) "later hooks must not run after :handled"))))

(t/deftest test-input-hook-error-safe
  (extensions/clear-input-hooks!)
  (extensions/register-input-hook! (fn [_] (throw (Exception. "boom"))))
  ;; The throwing hook prints a warning to stderr — suppress it.
  (let [r (binding [*err* (java.io.StringWriter.)]
            (extensions/apply-input-hooks "x" :interactive))]
    (t/is (= :pass (:action r)))
    (t/is (= "x" (:text r)))))

(t/deftest test-input-hook-source-in-ctx
  (extensions/clear-input-hooks!)
  (let [seen (atom nil)]
    (extensions/register-input-hook! (fn [{:keys [source]}] (reset! seen source)))
    (extensions/apply-input-hooks "x" :interactive)
    (t/is (= :interactive @seen))))

(t/deftest test-input-hook-streaming-behavior-in-ctx
  (extensions/clear-input-hooks!)
  (let [seen (atom nil)]
    (extensions/register-input-hook! (fn [{:keys [streaming-behavior]}]
                                       (reset! seen streaming-behavior)))
    (extensions/apply-input-hooks "x" :interactive {:streaming-behavior :steer})
    (t/is (= :steer @seen))
    (extensions/apply-input-hooks "x" :interactive)
    (t/is (nil? @seen) "streaming-behavior is nil when idle")))

(t/deftest test-input-hook-images-in-ctx
  (extensions/clear-input-hooks!)
  (let [seen (atom nil)]
    (extensions/register-input-hook! (fn [{:keys [images]}] (reset! seen images)))
    (extensions/apply-input-hooks "x" :interactive
                                  {:images [{:type :image :data "AA" :mime-type "image/png"}]})
    (t/is (= [{:type :image :data "AA" :mime-type "image/png"}] @seen))))

(t/deftest test-input-hook-images-transform
  (extensions/clear-input-hooks!)
  (extensions/register-input-hook!
   (fn [{:keys [images]}]
     {:action :transform :text "t"
      :images (conj images {:type :image :data "BB" :mime-type "image/jpeg"})}))
  (let [r (extensions/apply-input-hooks "x" :interactive)]
    (t/is (= :transform (:action r)))
    (t/is (= [{:type :image :data "BB" :mime-type "image/jpeg"}] (:images r)))))

(t/deftest test-input-hook-images-pass-through
  (extensions/clear-input-hooks!)
  (let [r (extensions/apply-input-hooks "x" :interactive
                                        {:images [{:type :image :data "AA" :mime-type "image/png"}]})]
    (t/is (= :pass (:action r)))
    (t/is (= [{:type :image :data "AA" :mime-type "image/png"}] (:images r))
          "pass result carries the images")))

(t/deftest test-before-agent-start-hooks-empty
  (extensions/clear-before-agent-start-hooks!)
  (let [r (extensions/apply-before-agent-start-hooks "hello" "base")]
    (t/is (nil? (:system-prompt r)))
    (t/is (empty? (:messages r)))))

(t/deftest test-before-agent-start-system-prompt
  (extensions/clear-before-agent-start-hooks!)
  (extensions/register-before-agent-start-hook!
   (fn [{:keys [system-prompt]}]
     {:system-prompt (str system-prompt "\nEXTRA")}))
  (let [r (extensions/apply-before-agent-start-hooks "hi" "base")]
    (t/is (= "base\nEXTRA" (:system-prompt r)))))

(t/deftest test-before-agent-start-messages
  (extensions/clear-before-agent-start-hooks!)
  (extensions/register-before-agent-start-hook!
   (fn [_] {:message {:role :user :content [{:type :text :text "note"}]}}))
  (let [r (extensions/apply-before-agent-start-hooks "hi" "base")]
    (t/is (= 1 (count (:messages r))))
    (t/is (= :user (:role (first (:messages r)))))))

(t/deftest test-before-agent-start-chains
  (extensions/clear-before-agent-start-hooks!)
  (extensions/register-before-agent-start-hook!
   (fn [{:keys [system-prompt]}] {:system-prompt (str system-prompt "+")}))
  (extensions/register-before-agent-start-hook!
   (fn [{:keys [system-prompt]}]
     {:system-prompt (str system-prompt "+")
      :message {:role :info :label "ext" :content "note"}}))
  (let [r (extensions/apply-before-agent-start-hooks "hi" "base")]
    (t/is (= "base++" (:system-prompt r)) "later hooks see earlier overrides")
    (t/is (= 1 (count (:messages r))))))

(t/deftest test-before-agent-start-error-safe
  (extensions/clear-before-agent-start-hooks!)
  (extensions/register-before-agent-start-hook! (fn [_] (throw (Exception. "boom"))))
  ;; The throwing hook prints a warning to stderr — suppress it.
  (let [r (binding [*err* (java.io.StringWriter.)]
            (extensions/apply-before-agent-start-hooks "hi" "base"))]
    (t/is (nil? (:system-prompt r)))
    (t/is (empty? (:messages r)))))

(t/deftest test-clear-extensions
  (t/testing "clear-extensions! removes registered hooks"
    (extensions/register-input-hook! (fn [ctx] (assoc ctx :action :transform :text (str (:text ctx) "!"))))
    (extensions/register-before-agent-start-hook! (fn [ctx] ctx))
    (extensions/clear-extensions!)
    (let [r (extensions/apply-input-hooks "hello" :interactive)]
      (t/is (= :pass (:action r)))
      (t/is (= "hello" (:text r))))))
