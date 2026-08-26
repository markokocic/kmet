(ns kmet.app.ui.test-tool-renderers
  "Headless tests for the hiccup-compiled built-in tool renderers: the
   element-tree conversions must produce the same visible output the
   imperative builders did (line caps, expand hints, truncation warns,
   error text)."
  (:require [clojure.string :as str]
            [clojure.test :as t :refer [deftest is testing]]
            [kmet.tui.core :as core]
            [kmet.app.ui.tool-renderers :as r]
            [kmet.tui.theme :as theme]))

(def ^:private th theme/dark-theme)

(defn- plain
  "Render a renderer result headlessly, ANSI-stripped."
  [comp width]
  (when comp
    (mapv #(clojure.string/replace % #"\u001b\[[0-9;]*[a-zA-Z]" "")
          (core/render comp width))))

(deftest test-read-call
  (testing "full call shows name + path"
    (let [lines (plain (r/render-read-call "read" {:file_path "a/b.txt"} th 40 {}) 40)]
      (is (= 1 (count lines)))
      (is (str/includes? (first lines) "read a/b.txt"))))
  (testing "compact classification when not expanded"
    (let [comp (r/render-read-call "read" {:file_path "/home/u/proj/SKILL.md"} th 40
                                   {:cwd "/home/u" :expanded false})
          line (first (plain comp 40))]
      (is (str/includes? line "proj") "compact skill label shows parent dir")
      (is (not (str/includes? line "read ")) "no full call form"))))

(deftest test-read-result-line-cap-and-hint
  (testing "collapsed without error renders nothing (call line is the summary)"
    (is (nil? (r/render-read-result "x" false th 60 false nil nil nil {}))))
  (testing "expanded shows every line, no hint"
    (let [content (str/join "\n" (mapv #(str "line-" %) (range 30)))
          lines (plain (r/render-read-result content false th 60 true nil nil nil {}) 60)]
      (is (= 31 (count lines)) "spacer + 30 lines")
      (is (str/starts-with? (second lines) "line-0"))
      (is (not-any? #(str/includes? % "more lines") lines))))
  (testing "error + collapsed caps at 10 lines with hint"
    (let [content (str/join "\n" (mapv #(str "line-" %) (range 30)))
          lines (plain (r/render-read-result content true th 60 false nil nil nil {}) 60)]
      (is (= 12 (count lines)) "spacer + 10 lines + more-hint")
      (is (str/starts-with? (second lines) "line-0"))
      (is (str/includes? (peek lines) "... (20 more lines,"))))
  (testing "truncation warn renders on the visible path"
    (let [content (str/join "\n" (mapv #(str "l" %) (range 5)))
          truncation {:truncated-by :lines :output-lines 5 :total-lines 99 :max-lines 5}
          lines (plain (r/render-read-result content true th 60 false nil nil truncation {}) 60)]
      (is (some #(str/includes? % "[Truncated: showing 5 of 99") lines)))))

(deftest test-write-call-and-result
  (testing "invalid content arg surfaces error text"
    (let [lines (plain (r/render-write-call "write" {:file_path "f" :content :bad} th 40 {}) 40)]
      (is (some #(str/includes? % "[invalid content arg") lines))))
  (testing "content preview capped at 10 + hint"
    (let [content (str/join "\n" (mapv #(str "w-" %) (range 25)))
          lines (plain (r/render-write-call "write" {:file_path "f" :content content} th 60 {}) 60)]
      (is (= 14 (count lines)) "title + 2 spacers + 10 lines + hint")
      (is (str/includes? (peek lines) "... (15 more lines,"))))
  (testing "error result renders content"
    (let [lines (plain (r/render-write-result "disk full" true th 40 false) 40)]
      (is (some #(str/includes? % "disk full") lines)))
    (is (nil? (r/render-write-result "ok" false th 40 false))))
  (testing "known-language paths highlight; unknown use toolOutput color"
    (let [content "defn foo [x]\n  (println \"hi\")"
          render (fn [p] (-> (r/render-write-call "write" {:file_path p :content content} th 60 {:expanded true})
                             (core/render 60)
                             ;; drop title + 2 spacers, keep content lines only
                             (subvec 3)))
          clj-lines (render "a.clj")
          txt-lines (render "a.txt")
          ;; tool-output gray = 38;2;128;128;128; syntax colors differ
          gray-esc "\u001b[38;2;128;128;128m"]
      (is (some #(str/includes? % gray-esc) txt-lines) ".txt content is toolOutput gray")
      (is (not-any? #(str/includes? % gray-esc) clj-lines) ".clj content uses syntax colors, not toolOutput"))))

(deftest test-default-renderers
  (testing "default call joins args, truncated to width"
    (let [lines (plain (r/render-default-call "grep" {:pattern "x" :path "y"} th 30 {}) 30)]
      (is (= 1 (count lines)))
      (is (str/includes? (first lines) "grep"))))
  (testing "default result previews 5 lines with expand hint"
    (let [content (str/join "\n" (mapv #(str "d-" %) (range 9)))
          lines (plain (r/render-default-result content false th 60 false) 60)]
      (is (= 7 (count lines)) "spacer + 5 lines + hint")
      (is (str/includes? (peek lines) "... (4 more lines,")))
    (testing "expanded shows everything"
      (let [content (str/join "\n" (mapv #(str "d-" %) (range 9)))
            lines (plain (r/render-default-result content false th 60 true) 60)]
        (is (= 10 (count lines)))))))

(deftest test-edit-box-bg-states
  ;; build-edit-box is private; exercise via render-edit-call with a
  ;; complete-args context that skips preview (unrenderable path → pending bg)
  (let [comp (r/render-edit-call "edit" {"file_path" "f.txt"} th 60
                                 {:cwd "." :args-complete false :state {}
                                  :set-state! (fn [_])})
        raw (core/render comp 60)
        ansi (first raw)]
    (testing "pending bg while nothing rendered yet"
      (is (str/includes? (or ansi "") "48;2;") "box paints an RGB background"))))
