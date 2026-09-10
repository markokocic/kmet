(ns kmet.tui.test-border
  "Tests for kmet.tui.border — the box-drawing glyph sets (tui.md §2.8):
   set data, resolve semantics (loud on typos, :none → nil, map merges over
   :normal), and the assembled frame lines."
  (:require [clojure.test :as t]
            [kmet.tui.border :as border]))

(t/deftest every-set-is-complete
  ;; A frame part or junction missing from a set would render as a space
  ;; mid-frame — the drift this namespace exists to prevent.
  (t/testing "all sets carry the 8 frame parts and 5 junctions"
    (doseq [[k b] border/styles]
      (doseq [part [:top :bottom :left :right
                    :top-left :top-right :bottom-left :bottom-right
                    :tee-down :tee-up :tee-left :tee-right :cross]]
        (t/is (string? (get b part))
              (str k " is missing " part)))))
  (t/testing "no set (except :hidden) draws a blank"
    (doseq [[k b] (dissoc border/styles :hidden)]
      (doseq [[part ch] b]
        (t/is (not= " " ch) (str k " has a blank " part))))))

(t/deftest hidden-is-spaces-not-absent
  ;; The point of :hidden: same cells, no ink, so a row of frames stays aligned.
  (t/is (= 13 (count border/hidden)))
  (t/is (every? #(= " " %) (vals border/hidden)))
  (t/is (= "        " (border/rule-line border/hidden 8))))

(t/deftest ascii-shells-out
  (t/is (= "-" (border/rule border/ascii)))
  (t/is (= "+" (:top-left border/ascii)))
  (t/is (= ["+--+" "|  |" "+--+"]
           [(border/top-line border/ascii 4)
            (border/mid-line border/ascii "  ")
            (border/bottom-line border/ascii 4)])))

(t/deftest resolves-keywords-and-defaults
  (t/is (= border/normal (border/resolve nil)) "nil means :normal")
  (t/is (= border/rounded (border/resolve :rounded)))
  (t/is (nil? (border/resolve :none)) ":none means no border at all")
  (t/testing "a map merges over :normal, keeping unmentioned parts"
    (let [b (border/resolve {:top "-" :bottom "-"})]
      (t/is (= "-" (:top b)))
      (t/is (= "┌" (:top-left b)) "corners stay :normal")
      (t/is (= "│" (:left b)) "sides stay :normal"))))

(t/deftest unknown-style-throws-with-did-you-mean
  (let [e (try (border/resolve :round) nil (catch Exception e e))]
    (t/is (some? e))
    (t/is (re-find #"unknown border style" (ex-message e)))
    (t/is (re-find #"Did you mean :rounded\?" (ex-message e))))
  (t/testing "a non-keyword, non-map border is rejected too"
    (t/is (thrown? Exception (border/resolve "thick")))))

(t/deftest assembled-frame-lines
  (t/is (= "┌────┐" (border/top-line border/normal 6)))
  (t/is (= "└────┘" (border/bottom-line border/normal 6)))
  (t/is (= "│ ab │" (border/mid-line border/normal " ab ")))
  (t/testing "mid-line's edge fn styles the sides only, never the content"
    (t/is (= "<│> ab <│>" (border/mid-line border/normal " ab " #(str "<" % ">")))))
  (t/testing "a one-cell frame degrades to the edge glyph"
    (t/is (= "─" (border/top-line border/normal 1)))
    (t/is (= "─" (border/bottom-line border/normal 1))))
  (t/testing "zero width draws nothing"
    (t/is (nil? (border/top-line border/normal 0)))
    (t/is (nil? (border/bottom-line border/normal 0)))
    (t/is (= "" (border/rule-line border/normal 0)))))

(t/deftest rule-line-spans-the-width
  (t/is (= "─────" (border/rule-line border/normal 5)))
  (t/is (= "─────" (border/rule-line border/rounded 5)) "corners do not affect rules")
  (t/is (= "━━━━━" (border/rule-line border/thick 5)) "a set's own glyph is used")
  (t/is (= (apply str (repeat 5 (border/rule border/double-line)))
           (border/rule-line border/double-line 5))
        "the line is the rule glyph repeated"))

(t/deftest table-junctions
  ;; A table separator row is assembled from the junctions; the names say
  ;; which way the branch leaves the run.
  (t/testing "normal light set"
    (t/is (= ["┌─┬─┐" "├─┼─┤" "└─┴─┘"]
             [(str (:top-left border/normal) (:top border/normal)
                   (:tee-down border/normal) (:top border/normal) (:top-right border/normal))
              (str (:tee-right border/normal) (:top border/normal)
                   (:cross border/normal) (:top border/normal) (:tee-left border/normal))
              (str (:bottom-left border/normal) (:bottom border/normal)
                   (:tee-up border/normal) (:bottom border/normal) (:bottom-right border/normal))])))
  (t/testing "ascii degrades every junction to +"
    (t/is (= "+-+-+" (str (:top-left border/ascii) (:top border/ascii)
                          (:tee-down border/ascii) (:top border/ascii)
                          (:top-right border/ascii))))))
