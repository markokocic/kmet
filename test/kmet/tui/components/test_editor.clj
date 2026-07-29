(ns kmet.tui.components.test-editor
  (:require [clojure.test :as t]
            [kmet.tui.core :as core]
            [kmet.tui.keys :as keys]
            [kmet.tui.components.editor :as editor]))

;; Raw key sequences matching what parse-key expects
(def ^:const K-LEFT "\u001b[D")
(def ^:const K-RIGHT "\u001b[C")
(def ^:const K-UP "\u001b[A")
(def ^:const K-DOWN "\u001b[B")
(def ^:const K-HOME "\u001b[H")
(def ^:const K-END "\u001b[F")
(def ^:const K-DEL "\u001b[3~")
(def ^:const K-BS "\u007f")
(def ^:const K-ENTER "\r")
(def ^:const K-ESC "\u001b")
(def ^:const K-TAB "\t")
(def ^:const K-PGUP "\u001b[5~")
(def ^:const K-PGDN "\u001b[6~")
(def ^:const K-ALT-LEFT "\u001bb")
(def ^:const K-ALT-RIGHT "\u001bf")
;; Control characters
(def ctrl (comp str char))

;; ─── Construction ───────────────────────────────────────────────────────────

(t/deftest test-editor-create
  (let [e (editor/make-editor)]
    (t/is (satisfies? core/IComponent e))
    (t/is (satisfies? core/IFocusable e))
    (t/is (= "" (editor/editor-get-text e)))
    (t/is (not (core/focused e)))))

(t/deftest test-editor-create-with-height
  (let [e (editor/make-editor :height 5)]
    (t/is (= 5 @(:height-atom e)))))

(t/deftest test-editor-focus
  (let [e (editor/make-editor)]
    (core/set-focused! e true)
    (t/is (core/focused e))
    (core/set-focused! e false)
    (t/is (not (core/focused e)))))

;; ─── Text operations ──────────────────────────────────────────────────────

(t/deftest test-editor-typing
  (let [e (editor/make-editor)]
    (doseq [c "hello"] (core/handle-input e (str c)))
    (t/is (= "hello" (editor/editor-get-text e)))))

(t/deftest test-editor-typing-multi-line
  (let [e (editor/make-editor)]
    (doseq [c "hello"] (core/handle-input e (str c)))
    (core/handle-input e (ctrl 10))  ;; ctrl+j = newline
    (doseq [c "world"] (core/handle-input e (str c)))
    (t/is (= "hello\nworld" (editor/editor-get-text e)))))

(t/deftest test-editor-backspace
  (let [e (editor/make-editor)]
    (doseq [c "hello"] (core/handle-input e (str c)))
    (core/handle-input e K-BS)
    (t/is (= "hell" (editor/editor-get-text e)))
    (core/handle-input e K-BS)
    (t/is (= "hel" (editor/editor-get-text e)))))

(t/deftest test-editor-delete
  (let [e (editor/make-editor)]
    (doseq [c "hello"] (core/handle-input e (str c)))
    (core/handle-input e (ctrl 1))  ;; ctrl+a = home
    (core/handle-input e K-DEL)
    (t/is (= "ello" (editor/editor-get-text e)))))

(t/deftest test-editor-newline
  (let [e (editor/make-editor)]
    (doseq [c "ab"] (core/handle-input e (str c)))
    (core/handle-input e (ctrl 10))  ;; ctrl+j = newline
    (doseq [c "cd"] (core/handle-input e (str c)))
    (t/is (= "ab\ncd" (editor/editor-get-text e)))))

;; ─── Cursor movement ──────────────────────────────────────────────────────

(t/deftest test-editor-cursor-left-right
  (let [e (editor/make-editor)]
    (doseq [c "abcd"] (core/handle-input e (str c)))
    (core/handle-input e (ctrl 2))  ;; ctrl+b = left
    (core/handle-input e (ctrl 2))  ;; ctrl+b = left
    (core/handle-input e "X")
    (t/is (= "abXcd" (editor/editor-get-text e)))))

(t/deftest test-editor-cursor-home-end
  (let [e (editor/make-editor)]
    (doseq [c "hello"] (core/handle-input e (str c)))
    (core/handle-input e (ctrl 1))  ;; ctrl+a = home
    (core/handle-input e "X")
    (t/is (= "Xhello" (editor/editor-get-text e)))
    (core/handle-input e (ctrl 5))  ;; ctrl+e = end
    (core/handle-input e "Z")
    (t/is (= "XhelloZ" (editor/editor-get-text e)))))

;; ─── Undo / Redo ──────────────────────────────────────────────────────────

(t/deftest test-editor-undo-typing
  (let [e (editor/make-editor)]
    (doseq [c "hello"] (core/handle-input e (str c)))
    (core/handle-input e (ctrl 31))  ;; ctrl+- = undo
    (t/is (= "" (editor/editor-get-text e)))))

(t/deftest test-editor-undo-redo
  (let [e (editor/make-editor)]
    (doseq [c "hello"] (core/handle-input e (str c)))
    (core/handle-input e (ctrl 31))  ;; undo
    (t/is (= "" (editor/editor-get-text e)))
    (core/handle-input e (ctrl 26))  ;; ctrl+z = redo
    (t/is (= "hello" (editor/editor-get-text e)))))

;; ─── Line editing ─────────────────────────────────────────────────────────

(t/deftest test-editor-ctrl-u
  (let [e (editor/make-editor)]
    (doseq [c "hello world"] (core/handle-input e (str c)))
    (dotimes [_ 5] (core/handle-input e K-LEFT))
    (core/handle-input e (ctrl 21))  ;; ctrl+u = kill to line start
    (t/is (= "world" (editor/editor-get-text e)))))

(t/deftest test-editor-ctrl-k
  (let [e (editor/make-editor)]
    (doseq [c "hello world"] (core/handle-input e (str c)))
    (dotimes [_ 6] (core/handle-input e K-LEFT))
    (core/handle-input e (ctrl 11))  ;; ctrl+k = kill to line end
    (t/is (= "hello" (editor/editor-get-text e)))))

(t/deftest test-editor-ctrl-w
  (let [e (editor/make-editor)]
    (doseq [c "hello world"] (core/handle-input e (str c)))
    (core/handle-input e (ctrl 23))  ;; ctrl+w = kill line
    (t/is (= "" (editor/editor-get-text e)))))

;; ─── Yank ─────────────────────────────────────────────────────────────────

(t/deftest test-editor-yank
  (let [e (editor/make-editor)]
    (doseq [c "hello"] (core/handle-input e (str c)))
    (core/handle-input e (ctrl 1))   ;; home
    (core/handle-input e (ctrl 11))  ;; ctrl+k = kill to end
    (t/is (= "" (editor/editor-get-text e)))
    (core/handle-input e (ctrl 25))  ;; ctrl+y = yank
    (t/is (= "hello" (editor/editor-get-text e)))))



;; ─── Submit ───────────────────────────────────────────────────────────────

(t/deftest test-editor-submit
  (let [e (editor/make-editor)
        submitted (atom nil)]
    (editor/editor-set-on-submit! e (fn [v] (reset! submitted v)))
    (doseq [c "hello"] (core/handle-input e (str c)))
    (core/handle-input e K-ENTER)
    (t/is (= "hello" @submitted))))

(t/deftest test-editor-submit-empty
  (let [e (editor/make-editor)
        submitted (atom nil)]
    (editor/editor-set-on-submit! e (fn [v] (reset! submitted v)))
    (core/handle-input e K-ENTER)
    (t/is (= "" @submitted))))

(t/deftest test-editor-escape
  (let [e (editor/make-editor)
        cancelled (atom true)]
    (editor/editor-set-on-submit! e (fn [v] (reset! cancelled (nil? v))))
    (core/handle-input e K-ESC)
    (t/is @cancelled)))

;; ─── set-text / get-text ──────────────────────────────────────────────────

(t/deftest test-editor-set-text
  (let [e (editor/make-editor)]
    (editor/editor-set-text! e "hello\nworld")
    (t/is (= "hello\nworld" (editor/editor-get-text e)))))

(t/deftest test-editor-set-text-empty
  (let [e (editor/make-editor)]
    (editor/editor-set-text! e "")
    (t/is (= "" (editor/editor-get-text e)))))

;; ─── History ──────────────────────────────────────────────────────────────

(t/deftest test-editor-history
  (let [e (editor/make-editor)]
    (editor/editor-push-history! e "hello")
    (editor/editor-push-history! e "world")
    (t/is (= ["hello" "world"] (editor/editor-get-history e)))))

(t/deftest test-editor-history-no-duplicates
  (let [e (editor/make-editor)]
    (editor/editor-push-history! e "hello")
    (editor/editor-push-history! e "hello")
    (t/is (= ["hello"] (editor/editor-get-history e)))))

(t/deftest test-editor-set-history
  (let [e (editor/make-editor)]
    (editor/editor-set-history! e ["a" "b" "c"])
    (t/is (= ["a" "b" "c"] (editor/editor-get-history e)))))

;; ─── Height ───────────────────────────────────────────────────────────────

(t/deftest test-editor-set-height
  (let [e (editor/make-editor :height 10)]
    (t/is (= 10 @(:height-atom e)))
    (editor/editor-set-height! e 20)
    (t/is (= 20 @(:height-atom e)))))

;; ─── On-change callback ───────────────────────────────────────────────────

(t/deftest test-editor-on-change
  (let [e (editor/make-editor)
        changes (atom [])]
    (editor/editor-set-on-change! e (fn [t] (swap! changes conj t)))
    (doseq [c "ab"] (core/handle-input e (str c)))
    (t/is (= 2 (count @changes)))
    (t/is (= "a" (first @changes)))
    (t/is (= "ab" (second @changes)))))

;; ─── Render ───────────────────────────────────────────────────────────────

(t/deftest test-editor-render
  (let [e (editor/make-editor :height 5 :padding-x 1)]
    (doseq [c "hello"] (core/handle-input e (str c)))
    (let [lines (core/render e 20)]
      (t/is (pos? (count lines)))
      (t/is (.startsWith (first lines) "───"))
      (t/is (.endsWith (last lines) "───")))))

(t/deftest test-editor-render-empty
  (let [e (editor/make-editor :height 3)]
    (let [lines (core/render e 20)]
      (t/is (pos? (count lines)))
      (t/is (> (count lines) 1)))))

;; ─── Text length ──────────────────────────────────────────────────────────

(t/deftest test-editor-text-length
  (let [e (editor/make-editor)]
    (t/is (zero? (editor/editor-get-text-length e)))
    (doseq [c "hello"] (core/handle-input e (str c)))
    (t/is (= 5 (editor/editor-get-text-length e)))))

;; ─── Editor with border function ──────────────────────────────────────────

(t/deftest test-editor-border-fn
  (let [e (editor/make-editor :height 3 :border-fn (fn [_] "="))]
    (doseq [c "hi"] (core/handle-input e (str c)))
    (let [lines (core/render e 10)]
      (t/is (.startsWith (first lines) "=="))
      (t/is (.endsWith (last lines) "==")))))

;; ─── Edge cases ──────────────────────────────────────────────────────────

(t/deftest test-editor-backspace-at-start
  (let [e (editor/make-editor)]
    (core/handle-input e K-BS)
    (t/is (= "" (editor/editor-get-text e)))))

(t/deftest test-editor-delete-at-end
  (let [e (editor/make-editor)]
    (doseq [c "hi"] (core/handle-input e (str c)))
    (core/handle-input e K-DEL)
    (t/is (= "hi" (editor/editor-get-text e)))))

(t/deftest test-editor-undo-empty
  (let [e (editor/make-editor)]
    (core/handle-input e (ctrl 31))  ;; ctrl+-
    (t/is (= "" (editor/editor-get-text e)))))

(t/deftest test-editor-ctrl-u-at-start
  (let [e (editor/make-editor)]
    (core/handle-input e (ctrl 21))
    (t/is (= "" (editor/editor-get-text e)))))

(t/deftest test-editor-ctrl-k-at-end
  (let [e (editor/make-editor)]
    (doseq [c "hello"] (core/handle-input e (str c)))
    (core/handle-input e (ctrl 11))
    (t/is (= "hello" (editor/editor-get-text e)))))



;; ─── Regression: grapheme operations with cached BreakIterator ───────────

(t/deftest test-editor-grapheme-cursor-movement
  (let [e (editor/make-editor)]
    ;; Type text
    (doseq [c "cafe"] (core/handle-input e (str c)))
    (t/is (= "cafe" (editor/editor-get-text e)))
    ;; LEFT x3: cursor at index 1 (after 'c', before 'a')
    (dotimes [_ 3] (core/handle-input e K-LEFT))
    ;; Insert 'X' at cursor → "cXafe"
    (core/handle-input e "X")
    (t/is (= "cXafe" (editor/editor-get-text e)) "Insert at cursor position")
    ;; HOME → move cursor to start
    (core/handle-input e (ctrl 1))  ;; home
    (core/handle-input e "X")
    (t/is (= "XcXafe" (editor/editor-get-text e)) "Home then insert works")
    ;; END → move to end, BACKSPACE deletes last char
    (core/handle-input e (ctrl 5))  ;; end
    (core/handle-input e K-BS)
    (t/is (= "XcXaf" (editor/editor-get-text e)) "Backspace removes last char")))

(t/deftest test-editor-kill-and-yank-with-graphemes
  (let [e (editor/make-editor)]
    (doseq [c "hello"] (core/handle-input e (str c)))
    (core/handle-input e (ctrl 1))   ;; home
    (core/handle-input e (ctrl 11))  ;; ctrl+k = kill to end
    (t/is (= "" (editor/editor-get-text e)) "Kill line removes all text")
    (core/handle-input e (ctrl 25))  ;; ctrl+y = yank
    (t/is (= "hello" (editor/editor-get-text e)) "Yank restores killed text")))
