(ns kmet.tui.components.test-truncated-text
  (:require [clojure.string :as str]
            [clojure.test :as t]
            [kmet.tui.core :as core]
            [kmet.tui.utils :as u]
            [kmet.tui.components.truncated-text :as tt]))

(defn- plain [lines]
  (mapv #(u/strip-ansi-codes %) lines))

(t/deftest test-create
  (let [c (tt/make-truncated-text "hello")]
    (t/is (satisfies? core/IComponent c))
    (t/is (some? (:text-atom c)))))

(t/deftest test-render-single-line
  (let [lines (plain (core/render (tt/make-truncated-text "hello") 20))]
    (t/is (= 1 (count lines)))
    (t/is (= "hello" (str/trim (first lines))))))

(t/deftest test-render-padding
  (let [c (tt/make-truncated-text "hi" :padding-x 2 :padding-y 1)
        lines (plain (core/render c 10))]
    (t/is (= 3 (count lines)) "1 padding-y above + content + 1 below")
    (t/is (= 10 (count (first lines))))
    (t/is (re-find #"^  hi" (second lines)) "content padded by padding-x")
    (t/is (= 10 (u/visible-width (second lines))))
    (t/is (= 10 (count (nth lines 2))))))

(t/deftest test-truncates-to-width
  (let [c (tt/make-truncated-text "hello world foo" :padding-x 0)
        lines (plain (core/render c 8))]
    (t/is (<= (u/visible-width (first lines)) 8))
    (t/is (re-find #"\.\.\." (first lines)) "pi default ellipsis \"...\"")))

(t/deftest test-first-line-only
  (let [lines (plain (core/render (tt/make-truncated-text "first\nsecond") 20))]
    (t/is (= 1 (count lines)))
    (t/is (re-find #"first" (first lines)))
    (t/is (not-any? #(re-find #"second" %) lines))))

(t/deftest test-set-text!
  (let [c (tt/make-truncated-text "a")]
    (tt/truncated-text-set-text! c "b")
    (let [lines (plain (core/render c 5))]
      (t/is (re-find #"b" (first lines))))))

(t/deftest test-ansi-preserved
  (let [styled (str (core/sgr 31) "red" (core/sgr 39))
        c (tt/make-truncated-text styled :padding-x 0)
        lines (core/render c 20)]
    (t/is (re-find #"\u001b\[31m" (first lines)) "fg code kept on the shown prefix")))

(t/deftest test-empty
  ;; pi's TruncatedText always renders the padded line, even for empty text
  (let [lines (core/render (tt/make-truncated-text "") 10)]
    (t/is (= 1 (count lines)))
    (t/is (= 10 (u/visible-width (first lines)))
          "empty text renders a full-width blank line")))
