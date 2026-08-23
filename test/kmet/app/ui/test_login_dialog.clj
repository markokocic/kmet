(ns kmet.app.ui.test-login-dialog
  "Login dialog tests — the content tree conversion (dsl.md stage 4, item
   12): show-* mutations are pure row swaps re-derived by the mounted root,
   exactly one input row exists at a time, a resolved prompt becomes a
   `> answer` transcript line, and dispose unwinds the tree's reaction."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [kmet.app.ui.login-dialog :as ld]
            [kmet.tui.protocols :as protocols]
            [kmet.tui.reagent :as r]))

(defn- strip-ansi [s]
  (str/replace s #"\u001b\[[0-9;]*[a-zA-Z]" ""))

(defn- render-plain [comp width]
  (->> (protocols/render comp width) (mapv strip-ansi)))

(defn- make-dialog []
  (ld/make-login-dialog nil "TestProv" (fn [_ _] nil)))

(deftest test-initial-frame-and-input-row
  (testing "the dialog renders chrome + title + the initial bare input"
    (let [d (make-dialog)]
      (try
        (let [lines (render-plain d 50)]
          (is (some #(re-find #"Login to TestProv" %) lines) "title shown")
          (is (= 2 (count (filter #(re-find #"─+" %) lines))) "two borders")
          (is (= [{:row :input}] @(:rows-atom d)) "initial rows"))
        (finally (protocols/dispose d))))))

(deftest test-show-auth-replaces-content
  (testing "show-auth! swaps the rows atomically; the input row stays"
    (let [d (make-dialog)]
      (try
        (with-redefs [ld/open-browser (fn [_] nil)]
          (ld/login-dialog-show-auth! d "https://example.com/auth" "Click it"))
        (let [lines (render-plain d 60)]
          (is (some #(re-find #"example\.com/auth" %) lines) "url shown")
          (is (some #(re-find #"Click it" %) lines) "instructions shown"))
        (is (= :input (:row (last @(:rows-atom d)))) "input row last")
        (finally (protocols/dispose d))))))

(deftest test-prompt-transcript-accumulates
  (testing "typed input resolves through handle-input; the answered prompt
            becomes `> answer` and the next prompt appends a fresh input row"
    (let [d (make-dialog)]
      (try
        (let [p (ld/login-dialog-show-prompt! d "First question" nil)]
          (doseq [c "alpha"] (protocols/handle-input d (str c)))
          (protocols/handle-input d "\r")
          (is (= "alpha" @p) "promise delivers the typed value"))
        (ld/login-dialog-show-prompt! d "Second question" nil)
        (let [lines (render-plain d 60)]
          (is (some #(re-find #"> alpha" %) lines) "submitted line shown")
          (is (some #(re-find #"First question" %) lines) "history kept")
          (is (some #(re-find #"Second question" %) lines) "new prompt shown"))
        (is (= 1 (count (filter #(= :input (:row %)) @(:rows-atom d))))
            "exactly one live input row")
        (finally (protocols/dispose d))))))

(deftest test-manual-input-moves-single-input-row
  (testing "show-manual-input! moves (not duplicates) the bare initial input
            row — pi Container.addChild moves semantics"
    (let [d (make-dialog)]
      (try
        (ld/login-dialog-show-manual-input! d "Paste the code")
        (let [rows @(:rows-atom d)]
          (is (= 1 (count (filter #(= :input (:row %)) rows)))
              "single input row")
          (is (= :input (:row (peek (pop rows)))) "input second to last")
          (is (= :text (:row (peek rows))) "hint line last"))
        (finally (protocols/dispose d))))))

(deftest test-appends-stay-above-the-input
  (testing "info/waiting/progress appends land ABOVE the live input row —
            pi's content area sits above the input (regression: conj put them
            below it)"
    (let [d (make-dialog)]
      (try
        (ld/login-dialog-show-device-code! d "https://example.dev" "ABC-123")
        (ld/login-dialog-show-waiting! d "Waiting for authentication...")
        (ld/login-dialog-show-progress! d "Polling...")
        (ld/login-dialog-show-info! d "Provider says hi")
        (let [rows @(:rows-atom d)
              texts (mapv :text rows)
              input-pos (first (keep-indexed (fn [i r] (when (= :input (:row r)) i)) rows))
              waiting-pos (first (keep-indexed (fn [i t] (when (str/includes? (str t) "Waiting") i)) texts))
              polling-pos (first (keep-indexed (fn [i t] (when (str/includes? (str t) "Polling") i)) texts))
              info-pos (first (keep-indexed (fn [i t] (when (str/includes? (str t) "says hi") i)) texts))]
          (is (= 1 (count (filter #(= :input (:row %)) rows))) "single input row")
          (is (< waiting-pos input-pos) "waiting line above input")
          (is (< polling-pos input-pos) "progress line above input")
          (is (< info-pos input-pos) "info line above input"))
        (finally (protocols/dispose d))))))

(deftest test-cancel-settles-pending-prompt
  (testing "escape settles the pending promise with the cancellation ex-info
            and fires on-complete (pi cancel)"
    (let [completed (atom nil)
          d (ld/make-login-dialog nil "TestProv"
                                  (fn [ok msg] (reset! completed [ok msg])))]
      (try
        ;; show-prompt! creates the pending promise; escape settles it
        (let [p (ld/login-dialog-show-prompt! d "Enter token" nil)]
          (protocols/handle-input d "\u001b")
          (is (instance? Exception @p) "pending prompt settled with ex-info")
          (is (= [false "Login cancelled"] @completed) "on-complete fired"))
        (finally (protocols/dispose d))))))

(deftest test-dispose-unwinds-reaction
  (testing "dispose disposes the root: its reaction dies and later row swaps
            no longer re-derive (the watcher is gone)"
    (let [d (make-dialog)]
      ;; first render births the wrapper's reaction
      (render-plain d 50)
      (protocols/dispose d)
      (is (= :disposed (:state (r/reaction-state @(:rx (:root d)))))
          "content reaction disposed with the dialog"))))
