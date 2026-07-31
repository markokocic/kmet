(ns kmet.test-theme
  (:require [clojure.test :as t]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [kmet.tui.theme :as theme]))

;; ─── Theme record ──────────────────────────────────────────────────────────

(t/deftest test-theme-record
  (let [t theme/dark-theme]
    (t/is (instance? kmet.tui.theme.Theme t))
    (t/is (= "dark" (:name t)))
    (t/is (string? (get-in t [:fg-colors :text])))
    (t/is (string? (get-in t [:fg-colors :accent])))
    (t/is (string? (get-in t [:fg-colors :border])))
    (t/is (string? (get-in t [:fg-colors :error])))
    (t/is (string? (get-in t [:fg-colors :success])))
    (t/is (string? (get-in t [:fg-colors :warning])))))

(t/deftest test-light-theme
  (let [t theme/light-theme]
    (t/is (instance? kmet.tui.theme.Theme t))
    (t/is (= "light" (:name t)))
    (t/is (string? (get-in t [:fg-colors :accent])))))

;; ─── Make theme ────────────────────────────────────────────────────────────

(t/deftest test-make-theme
  (let [t (theme/make-theme
            {:name "test"
             :text "#ff0000"
             :accent "#0000ff"
             :success "#00ff00"
             :error "#ff0000"
             :border "#505050"})]
    (t/is (= "test" (:name t)))
    (t/is (.contains (get-in t [:fg-colors :text]) "38;2;255;0;0"))
    (t/is (.contains (get-in t [:fg-colors :accent]) "38;2;0;0;255"))
    (t/is (.contains (get-in t [:fg-colors :success]) "38;2;0;255;0"))
    (t/is (.contains (get-in t [:fg-colors :error]) "38;2;255;0;0"))))

(t/deftest test-make-theme-hex
  (let [t (theme/make-theme
            {:name "hex"
             :text "#ff0000"
             :accent "#00ff00"
             :border "#0000ff"})]
    (t/is (.contains (get-in t [:fg-colors :text]) "38;2;255;0;0"))
    (t/is (.contains (get-in t [:fg-colors :accent]) "38;2;0;255;0"))
    (t/is (.contains (get-in t [:fg-colors :border]) "38;2;0;0;255"))))

(t/deftest test-make-theme-256
  (let [t (theme/make-theme
            {:name "256"
             :text 196
             :accent 46
             :border 21})]
    (t/is (.contains (get-in t [:fg-colors :text]) "38;5;196"))
    (t/is (.contains (get-in t [:fg-colors :accent]) "38;5;46"))
    (t/is (.contains (get-in t [:fg-colors :border]) "38;5;21"))))

(t/deftest test-make-theme-truecolor-vector
  (let [t (theme/make-theme
            {:name "truecolor"
             :text "#ff0000"
             :accent "#00ff00"
             :border "#0000ff"})]
    (t/is (.contains (get-in t [:fg-colors :text]) "38;2;255;0;0"))
    (t/is (.contains (get-in t [:fg-colors :accent]) "38;2;0;255;0"))
    (t/is (.contains (get-in t [:fg-colors :border]) "38;2;0;0;255"))))

(t/deftest test-make-theme-nil-color
  (let [t (theme/make-theme
            {:name "nil-test"
             :text nil
             :accent nil})]
    ;; nil means "use default" — falls back to dark theme defaults
    (t/is (= "\u001b[38;2;212;212;212m" (get-in t [:fg-colors :text])))
    (t/is (= "\u001b[38;2;138;190;183m" (get-in t [:fg-colors :accent])))))

(t/deftest test-make-theme-with-bg
  (let [t (theme/make-theme
            {:name "bg-test"
             :user-message-bg "#505050"
             :custom-message-bg nil
             :selected-bg "#00ffff"})]
    (t/is (.contains (get-in t [:bg-colors :user-message-bg]) "48;2;80;80;80"))
    ;; nil bg means "use default" — falls back to dark theme default
    (t/is (= "\u001b[48;2;45;40;56m" (get-in t [:bg-colors :custom-message-bg])))
    (t/is (.contains (get-in t [:bg-colors :selected-bg]) "48;2;0;255;255"))))

(t/deftest test-thinking-levels
  (let [t (theme/make-theme
            {:name "think-test"
             :thinking-low 240
             :thinking-medium 245
             :thinking-high 250
             :thinking-xhigh 255})]
    (t/is (.contains (get-in t [:fg-colors :thinking-low]) "38;5;240"))
    (t/is (.contains (get-in t [:fg-colors :thinking-medium]) "38;5;245"))
    (t/is (.contains (get-in t [:fg-colors :thinking-high]) "38;5;250"))
    (t/is (.contains (get-in t [:fg-colors :thinking-xhigh]) "38;5;255"))))

;; ─── Theme registry ────────────────────────────────────────────────────────

(t/deftest test-get-theme-default
  (let [t (theme/get-theme "dark")]
    (t/is (some? t))
    (t/is (= "dark" (:name t)))))

(t/deftest test-get-theme-light
  (let [t (theme/get-theme "light")]
    (t/is (some? t))
    (t/is (= "light" (:name t)))))

(t/deftest test-get-theme-unknown-fallback
  (let [t (theme/get-theme "nonexistent")]
    (t/is (some? t))
    (t/is (= "dark" (:name t)))))

(t/deftest test-register-theme
  (let [t (theme/make-theme {:name "custom" :accent "#ff00ff"})]
    (theme/register-theme! t)
    (let [loaded (theme/get-theme "custom")]
      (t/is (some? loaded))
      (t/is (= "custom" (:name loaded)))
      (t/is (.contains (get-in loaded [:fg-colors :accent]) "38;2;255;0;255")))))

;; ─── Theme file loading ───────────────────────────────────────────────────

(t/deftest test-load-themes-from-dir-non-existent
  (t/testing "Loading from non-existent dir should not throw"
    (t/is (nil? (theme/load-themes-from-dir "/nonexistent/themes")))))

(t/deftest test-load-themes-from-dir
  (let [tmp-dir (str "target/test-themes-" (System/currentTimeMillis))]
    (io/make-parents (str tmp-dir "/test.edn"))
    ;; Write a valid theme file
    (spit (str tmp-dir "/test.edn")
      "{:name \"test\" :text \"#00ff00\" :accent \"#00ffff\" :border \"#505050\"}")
    ;; Write a non-edn file (should be ignored)
    (spit (str tmp-dir "/not-a-theme.txt") "hello")
    (theme/load-themes-from-dir tmp-dir)
    (let [loaded (theme/get-theme "test")]
      (t/is (some? loaded))
      (t/is (= "test" (:name loaded)))))
  ;; Cleanup
  (let [d (io/file "target")]
    (doseq [f (file-seq d)]
      (when (str/ends-with? (.getName f) ".edn")
        (.delete f)))))
