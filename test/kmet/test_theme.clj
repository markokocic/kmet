(ns kmet.test-theme
  (:require [clojure.test :as t]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [kmet.tui.theme :as theme]))

;; ─── Theme record ──────────────────────────────────────────────────────────

(t/deftest test-theme-record
  (let [t theme/dark-theme]
    (t/is (instance? kmet.tui.theme.Theme t))
    (t/is (= "dark" (:name t)))
    (t/is (string? (:text t)))
    (t/is (string? (:accent t)))
    (t/is (string? (:border t)))
    (t/is (string? (:error t)))
    (t/is (string? (:success t)))
    (t/is (string? (:warning t)))))

(t/deftest test-light-theme
  (let [t theme/light-theme]
    (t/is (instance? kmet.tui.theme.Theme t))
    (t/is (= "light" (:name t)))
    (t/is (string? (:accent t)))))

;; ─── Make theme ────────────────────────────────────────────────────────────

(t/deftest test-make-theme
  (let [t (theme/make-theme "test"
            {:text "red"
             :accent "blue"
             :success "green"
             :error "red"
             :border "bright-black"})]
    (t/is (= "test" (:name t)))
    (t/is (.contains (:text t) "31m"))   ;; red
    (t/is (.contains (:accent t) "34m")) ;; blue
    (t/is (.contains (:success t) "32m")) ;; green
    (t/is (.contains (:error t) "31m")))) ;; red

(t/deftest test-make-theme-hex
  (let [t (theme/make-theme "hex"
            {:text "#ff0000"
             :accent "#00ff00"
             :border "#0000ff"})]
    (t/is (.contains (:text t) "38;2;255;0;0"))
    (t/is (.contains (:accent t) "38;2;0;255;0"))
    (t/is (.contains (:border t) "38;2;0;0;255"))))

(t/deftest test-make-theme-256
  (let [t (theme/make-theme "256"
            {:text 196
             :accent 46
             :border 21})]
    (t/is (.contains (:text t) "38;5;196"))
    (t/is (.contains (:accent t) "38;5;46"))
    (t/is (.contains (:border t) "38;5;21"))))

(t/deftest test-make-theme-truecolor-vector
  (let [t (theme/make-theme "truecolor"
            {:text [255 0 0]
             :accent [0 255 0]
             :border [0 0 255]})]
    (t/is (.contains (:text t) "38;2;255;0;0"))
    (t/is (.contains (:accent t) "38;2;0;255;0"))
    (t/is (.contains (:border t) "38;2;0;0;255"))))

(t/deftest test-make-theme-nil-color
  (let [t (theme/make-theme "nil-test"
            {:text nil
             :accent nil})]
    (t/is (= "\u001b[0m" (:text t)))
    (t/is (= "\u001b[0m" (:accent t)))))

(t/deftest test-make-theme-with-bg
  (let [t (theme/make-theme "bg-test"
            {:user-bg "bright-black"
             :assistant-bg nil
             :selected-bg "cyan"})]
    (t/is (.contains (:user-bg t) "100m"))   ;; bright-black bg = ANSI 100
    (t/is (= "\u001b[0m" (:assistant-bg t))) ;; nil bg = reset
    (t/is (.contains (:selected-bg t) "46m")))) ;; cyan bg = ANSI 46

(t/deftest test-thinking-levels
  (let [t (theme/make-theme "think-test"
            {:thinking-levels [240 245 250 255]})]
    (t/is (vector? (:thinking-levels t)))
    (t/is (= 4 (count (:thinking-levels t))))
    (doseq [level (:thinking-levels t)]
      (t/is (.contains level "38;5")))))

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
  (let [t (theme/make-theme "custom" {:accent "magenta"})]
    (theme/register-theme! t)
    (let [loaded (theme/get-theme "custom")]
      (t/is (some? loaded))
      (t/is (= "custom" (:name loaded)))
      (t/is (.contains (:accent loaded) "35m")))))

;; ─── Theme file loading ───────────────────────────────────────────────────

(t/deftest test-load-themes-from-dir-non-existent
  (t/testing "Loading from non-existent dir should not throw"
    (t/is (nil? (theme/load-themes-from-dir "/nonexistent/themes")))))

(t/deftest test-load-themes-from-dir
  (let [tmp-dir (str "target/test-themes-" (System/currentTimeMillis))]
    (io/make-parents (str tmp-dir "/test.edn"))
    ;; Write a valid theme file
    (spit (str tmp-dir "/test.edn")
      "{:text \"green\" :accent \"cyan\" :border \"bright-black\"}")
    ;; Write a non-edn file (should be ignored)
    (spit (str tmp-dir "/not-a-theme.txt") "hello")
    (theme/load-themes-from-dir tmp-dir)
    (let [loaded (theme/get-theme "test")]
      (t/is (some? loaded))
      (t/is (= "test" (:name loaded)))))
  ;; Cleanup
  (let [d (io/file "target")]
    (doseq [f (file-seq d)]
      (when (.endsWith (.getName f) ".edn")
        (.delete f)))))
