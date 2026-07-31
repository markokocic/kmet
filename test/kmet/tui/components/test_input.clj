(ns kmet.tui.components.test-input
  (:require [clojure.test :as t]
            [clojure.string :as str]
            [kmet.tui.core :as core]
            [kmet.tui.components.input :as input]))

;; ─── Construction ───────────────────────────────────────────────────────────

(t/deftest test-input-create
  (let [inp (input/make-input)]
    (t/is (satisfies? core/IComponent inp))
    (t/is (satisfies? core/IFocusable inp))
    (t/is (= "" (input/input-get-value inp)))
    (t/is (not (core/focused inp)))))

(t/deftest test-input-set-value
  (let [inp (input/make-input)]
    (input/input-set-value! inp "hello")
    (t/is (= "hello" (input/input-get-value inp)))))

(t/deftest test-input-focus
  (let [inp (input/make-input)]
    (core/set-focused! inp true)
    (t/is (core/focused inp))
    (core/set-focused! inp false)
    (t/is (not (core/focused inp)))))

;; ─── Basic typing ──────────────────────────────────────────────────────────

(t/deftest test-input-typing
  (let [inp (input/make-input)]
    (doseq [c "hello"] (core/handle-input inp (str c)))
    (t/is (= "hello" (input/input-get-value inp)))))

(t/deftest test-input-typing-multi-char
  (let [inp (input/make-input)]
    (doseq [c "hello world"] (core/handle-input inp (str c)))
    (t/is (= "hello world" (input/input-get-value inp)))))

;; ─── Cursor movement ───────────────────────────────────────────────────────

(t/deftest test-input-cursor-left-right
  (let [inp (input/make-input)]
    (doseq [c "abcd"] (core/handle-input inp (str c)))
    ;; left 2, insert X
    (core/handle-input inp "\u001b[D")
    (core/handle-input inp "\u001b[D")
    (core/handle-input inp "X")
    (t/is (= "abXcd" (input/input-get-value inp)))))

(t/deftest test-input-cursor-home-end
  (let [inp (input/make-input)]
    (doseq [c "hello"] (core/handle-input inp (str c)))
    (core/handle-input inp "\u001b[H")  ;; home
    (core/handle-input inp "X")
    (t/is (= "Xhello" (input/input-get-value inp)))
    (core/handle-input inp "\u001b[F")  ;; end
    (core/handle-input inp "Z")
    (t/is (= "XhelloZ" (input/input-get-value inp)))))

;; ─── Backspace & Delete ────────────────────────────────────────────────────

(t/deftest test-input-backspace
  (let [inp (input/make-input)]
    (doseq [c "hello"] (core/handle-input inp (str c)))
    (core/handle-input inp "\u007f")  ;; backspace
    (t/is (= "hell" (input/input-get-value inp)))
    (core/handle-input inp "\u007f")  ;; backspace
    (t/is (= "hel" (input/input-get-value inp)))))

(t/deftest test-input-delete
  (let [inp (input/make-input)]
    (doseq [c "hello"] (core/handle-input inp (str c)))
    (core/handle-input inp "\u001b[H")  ;; home
    (core/handle-input inp "\u001b[3~")  ;; delete
    (t/is (= "ello" (input/input-get-value inp)))))

;; ─── Line editing ─────────────────────────────────────────────────────────

(t/deftest test-input-ctrl-u
  (let [inp (input/make-input)]
    (doseq [c "hello world"] (core/handle-input inp (str c)))
    ;; Move to after the space (position 6), then ctrl+u deletes "hello "
    (dotimes [_ 5] (core/handle-input inp "\u001b[D"))
    (core/handle-input inp (str (char 21)))  ;; ctrl+u
    (t/is (= "world" (input/input-get-value inp)))))

(t/deftest test-input-ctrl-k
  (let [inp (input/make-input)]
    (doseq [c "hello world"] (core/handle-input inp (str c)))
    ;; Move to position 5 (the space), ctrl+k deletes " world"
    (dotimes [_ 6] (core/handle-input inp "\u001b[D"))
    (core/handle-input inp (str (char 11)))  ;; ctrl+k
    (t/is (= "hello" (input/input-get-value inp)))))

(t/deftest test-input-ctrl-w
  (let [inp (input/make-input)]
    (doseq [c "hello world"] (core/handle-input inp (str c)))
    (core/handle-input inp (str (char 23)))  ;; ctrl+w
    (t/is (= "hello " (input/input-get-value inp)))))

;; ─── Undo ─────────────────────────────────────────────────────────────────

(t/deftest test-input-undo-typing
  (let [inp (input/make-input)]
    (doseq [c "hello"] (core/handle-input inp (str c)))
    (core/handle-input inp (str (char 31)))  ;; ctrl+-
    (t/is (= "" (input/input-get-value inp)))))

(t/deftest test-input-undo-after-edit
  (let [inp (input/make-input)]
    (doseq [c "hello world"] (core/handle-input inp (str c)))
    (dotimes [_ 6] (core/handle-input inp "\u001b[D"))
    (core/handle-input inp (str (char 11)))  ;; ctrl+k
    (t/is (= "hello" (input/input-get-value inp)))
    (core/handle-input inp (str (char 31)))  ;; ctrl+-
    (t/is (= "hello world" (input/input-get-value inp)))))

;; ─── Yank ─────────────────────────────────────────────────────────────────

(t/deftest test-input-yank
  (let [inp (input/make-input)]
    (doseq [c "hello"] (core/handle-input inp (str c)))
    (core/handle-input inp "\u001b[H")  ;; home
    (core/handle-input inp (str (char 11)))  ;; ctrl+k (kill to end)
    (t/is (= "" (input/input-get-value inp)))
    (core/handle-input inp (str (char 25)))  ;; ctrl+y
    (t/is (= "hello" (input/input-get-value inp)))))

;; ─── Word navigation ──────────────────────────────────────────────────────

(t/deftest test-input-word-left
  (let [inp (input/make-input)]
    (doseq [c "hello world foo"] (core/handle-input inp (str c)))
    (core/handle-input inp "\u001bb")  ;; alt+left
    (core/handle-input inp "|")
    (t/is (= "hello world |foo" (input/input-get-value inp)))
    (core/handle-input inp "\u001bb")
    (core/handle-input inp "|")
    (t/is (= "hello world| |foo" (input/input-get-value inp)))))

(t/deftest test-input-word-right
  (let [inp (input/make-input)]
    (doseq [c "hello world foo"] (core/handle-input inp (str c)))
    (core/handle-input inp "\u001b[H")  ;; home
    (core/handle-input inp "\u001bf")  ;; alt+right
    (core/handle-input inp "|")
    (t/is (= "hello| world foo" (input/input-get-value inp)))))

;; ─── Submit & Escape ──────────────────────────────────────────────────────

(t/deftest test-input-submit
  (let [inp (input/make-input)
        submitted (atom nil)]
    (input/input-set-on-submit! inp (fn [v] (reset! submitted v)))
    (doseq [c "hello"] (core/handle-input inp (str c)))
    (core/handle-input inp "\r")
    (t/is (= "hello" @submitted))))

(t/deftest test-input-escape
  (let [inp (input/make-input)
        escaped (atom false)]
    (input/input-set-on-escape! inp (fn [] (reset! escaped true)))
    (core/handle-input inp "\u001b")
    (t/is @escaped)))

;; ─── Render ───────────────────────────────────────────────────────────────

(t/deftest test-input-render-empty
  (let [inp (input/make-input)]
    (let [lines (core/render inp 10)]
      (t/is (= 1 (count lines)))
      (t/is (str/starts-with? (first lines) "> ")))))

(t/deftest test-input-render-with-text
  (let [inp (input/make-input)]
    (doseq [c "hello"] (core/handle-input inp (str c)))
    (let [lines (core/render inp 15)]
      (t/is (= 1 (count lines)))
      (t/is (.contains (first lines) "hello")))))

;; ─── Edge cases ───────────────────────────────────────────────────────────

(t/deftest test-input-home-when-empty
  (let [inp (input/make-input)]
    (core/handle-input inp "\u001b[H")
    (core/handle-input inp "a")
    (t/is (= "a" (input/input-get-value inp)))))

(t/deftest test-input-backspace-when-empty
  (let [inp (input/make-input)]
    (core/handle-input inp "\u007f")
    (t/is (= "" (input/input-get-value inp)))))

(t/deftest test-input-delete-when-empty
  (let [inp (input/make-input)]
    (core/handle-input inp "\u001b[3~")
    (t/is (= "" (input/input-get-value inp)))))

(t/deftest test-input-ctrl-u-at-start
  (let [inp (input/make-input)]
    (core/handle-input inp (str (char 21)))
    (t/is (= "" (input/input-get-value inp)))))

(t/deftest test-input-ctrl-k-at-end
  (let [inp (input/make-input)]
    (doseq [c "hello"] (core/handle-input inp (str c)))
    (core/handle-input inp (str (char 11)))
    (t/is (= "hello" (input/input-get-value inp)))))
