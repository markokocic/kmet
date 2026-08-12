(ns kmet.app.test-extensions
  (:require [clojure.test :as t]
            [clojure.java.io :as io]
            [kmet.app.extensions :as extensions]
            [kmet.app.models :as models]
            [kmet.app.session :as session]))

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

;; ─── Extension session API (G9/G10/G11: custom, custom_message, labels) ──

(defn- with-session
  "Register SESS as the live session, run F, then unregister (tests isolate
   the session registry atom)."
  [sess f]
  (try
    (extensions/set-session! sess)
    (f)
    (finally
      (extensions/set-session! nil))))

(t/deftest test-session-api-append-custom-entry
  ;; G9: extensions persist state as custom entries (never in LLM context)
  (let [sess (session/create-session "target/test-ext-session")]
    (with-session sess
      (fn []
        (let [id (extensions/append-custom-entry! :my-state {:count 1})
              e (session/get-entry sess id)]
          (t/is (string? id))
          (t/is (= :custom (:role e)))
          (t/is (= :my-state (:custom-type e)))
          (t/is (= {:count 1} (:data e)))
          (t/is (= [e] (extensions/get-custom-entries :my-state)))
          (t/is (empty? (extensions/get-custom-entries :other)))
          (t/is (empty? (session/context-messages e)) "never in context"))))))

(t/deftest test-session-api-append-custom-message
  ;; G10: append-custom-message! persists the entry AND injects the message
  ;; into the live agent context via the installed sink
  (let [sess (session/create-session "target/test-ext-session")
        received (atom nil)]
    (extensions/set-context-sink! (fn [m] (reset! received m)))
    (with-session sess
      (fn []
        (let [id (extensions/append-custom-message! :note "hello" true {:x 1})
              e (session/get-entry sess id)]
          (t/is (= :custom-message (:role e)))
          (t/is (= :note (:custom-type e)))
          (t/is (= true (:display e)))
          (t/is (= {:x 1} (:details e)))
          (t/is (= :custom (:role @received)) "sink sees the projection")
          (t/is (= "hello" (:content @received)))
          (t/is (= :note (:custom-type @received)))
          (t/is (= true (:display @received))))))
    (extensions/set-context-sink! nil)))

(t/deftest test-session-api-labels
  ;; G11: extensions set/read labels on entries (pi: ctx.session.setLabel)
  (let [sess (session/create-session "target/test-ext-session")
        e (session/append-entry sess {:role :assistant :content "a"})]
    (with-session sess
      (fn []
        (t/is (nil? (extensions/get-label (:id e))))
        (extensions/set-label! (:id e) "done")
        (t/is (= "done" (extensions/get-label (:id e))))
        (t/is (= "done" (session/get-label sess (:id e))) "entry-level API agrees")
        (extensions/set-label! (:id e) nil)
        (t/is (nil? (extensions/get-label (:id e))) "cleared")))))

(t/deftest test-session-api-no-session
  ;; wrappers are no-ops (nil/empty) without a live session
  (extensions/set-session! nil)
  (extensions/set-context-sink! nil)
  (t/is (nil? (extensions/get-session)))
  (t/is (nil? (extensions/append-custom-entry! :x {:v 1})))
  (t/is (nil? (extensions/append-custom-message! :x "hi" true)))
  (t/is (empty? (extensions/get-custom-entries :x)))
  (t/is (nil? (extensions/get-label "nope")))
  (t/is (nil? (extensions/set-label! "nope" "l"))))

;; ─── Extension provider registry (pi: ctx.registerProvider / ctx.models) ──

(t/deftest test-register-provider-delegation
  (models/clear-extension-providers!)
  (try
    (t/testing "register-provider! with id + config delegates to the registry"
      (extensions/register-provider! :ext-prov
                                     {:base-url "https://ext.example/v1" :api :openai-completions
                                      :api-key "sk-ext" :models [{:id "ext-1"}]})
      (t/is (some? (models/get-provider :ext-prov)))
      (t/is (= ["ext-1"] (mapv :id (models/get-models :ext-prov))))
      (t/is (= {:base-url "https://ext.example/v1" :api :openai-completions
                :api-key "sk-ext" :models [{:id "ext-1"}]}
               (extensions/get-registered-provider-config :ext-prov))))
    (t/testing "broken config throws and leaves no registration"
      (t/is (thrown? Exception
                     (extensions/register-provider! :broken {:models [{:id "x"}]})))
      (t/is (nil? (models/get-provider :broken))))
    (t/testing "unregister-provider! removes it"
      (extensions/unregister-provider! :ext-prov)
      (t/is (nil? (models/get-provider :ext-prov))))
    (t/testing "read facade mirrors the registry"
      (extensions/register-provider! :facade
                                     {:base-url "https://f/v1" :api :openai-completions
                                      :api-key "sk" :models [{:id "f1"}]})
      (let [model (extensions/find-model :facade "f1")]
        (t/is (some? model))
        (t/is (some #(= "f1" (:id %)) (extensions/get-all-models)))
        (t/is (true? (extensions/has-configured-auth model)))
        (t/is (= {:configured true :source :fallback}
                 (extensions/get-provider-auth-status :facade)))
        (t/is (true? (:ok (extensions/get-api-key-and-headers model))))
        (t/is (= [:facade] (extensions/get-registered-provider-ids)))))
    (finally
      (models/clear-extension-providers!)
      (models/load-catalogs!))))
