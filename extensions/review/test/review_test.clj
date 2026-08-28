(ns review-test
  "Tests for the review extension's init/shutdown lifecycle, command
   registration, and arg parsing. The dialogs and command handlers
   exercise the live kmet extension context (ui-custom, ctx :mode,
   etc.) and are hard to unit-test without the host runtime — those
   flows are validated by manual /review and /end-review invocations
   in the TUI."
  (:require [clojure.test :refer [deftest is testing]]
            [kmet.extension :as ext]
            [kmet.extensions.review.core :as review]
            [kmet.extensions.review.dialogs :as dlg]))

;; -- init / shutdown -----------------------------------------------------

(deftest init-registers-commands-test
  (let [{:keys [api state]} (ext/create-nullable-api)]
    (review/init api)
    (let [cmds (set (keys (:commands @state)))]
      (is (contains? cmds "review"))
      (is (contains? cmds "end-review")))))

(deftest init-registers-handlers-test
  (let [{:keys [api state]} (ext/create-nullable-api)]
    (review/init api)
    (let [handlers (set (keys (:handlers @state)))]
      (is (contains? handlers :session-start))
      (is (contains? handlers :session-tree)))))

;; -- Arg tokenization ---------------------------------------------------

;; The tokenize-args and parse-args functions are private. We test
;; the user-visible surface: empty / invalid / unrecognized inputs
;; return :target nil (the caller shows the selector) and well-formed
;; inputs return the expected :target map.

;; -- parse-args (via #') -----------------------------------------------

(defn- parse [args]
  (try (#'review/parse-args args)
       (catch Exception e {:error (ex-message e)})))

(deftest parse-args-empty-test
  (is (= {:target nil} (parse "")))
  (is (= {:target nil} (parse nil))))

(deftest parse-args-uncommitted-test
  (is (= {:target {:type :uncommitted} :extra-instruction nil}
         (parse "uncommitted"))))

(deftest parse-args-base-branch-test
  (is (= {:target {:type :base-branch :branch "main"}
          :extra-instruction nil}
         (parse "branch main"))))

(deftest parse-args-base-branch-missing-test
  ;; No branch arg: target is nil (caller shows selector)
  (is (= {:target nil :extra-instruction nil}
         (parse "branch"))))

(deftest parse-args-commit-test
  (is (= {:target {:type :commit :sha "abc1234" :title nil}
          :extra-instruction nil}
         (parse "commit abc1234"))))

(deftest parse-args-commit-with-title-test
  (is (= {:target {:type :commit :sha "abc1234" :title "WIP flarble"}
          :extra-instruction nil}
         (parse "commit abc1234 WIP flarble"))))

(deftest parse-args-folder-test
  (is (= {:target {:type :folder :paths ["src" "docs"]}
          :extra-instruction nil}
         (parse "folder src docs"))))

(deftest parse-args-folder-missing-test
  (is (= {:target nil :extra-instruction nil}
         (parse "folder"))))

(deftest parse-args-extra-flag-test
  (testing "--extra with following arg"
    (is (= {:target {:type :uncommitted}
            :extra-instruction "focus on performance"}
           (parse "uncommitted --extra \"focus on performance\""))))
  (testing "--extra=value"
    (is (= {:target {:type :uncommitted}
            :extra-instruction "focus on errors"}
           (parse "uncommitted --extra=focus on errors"))))
  (testing "--extra without value"
    (let [r (parse "uncommitted --extra")]
      (is (contains? r :error)))))

(deftest parse-args-quoted-paths-test
  ;; pi semantics: the folder case joins all trailing args with a
  ;; space and re-splits on whitespace (parse-paths). Quoted paths
  ;; are kept as one token by the tokenizer, but parse-paths splits
  ;; on whitespace again, so multi-word paths are not supported
  ;; through the simple CLI path. This is the same behavior as
  ;; pi-review.
  (is (= {:target {:type :folder :paths ["src/My" "File.clj" "docs"]}
          :extra-instruction nil}
         (parse "folder \"src/My File.clj\" docs"))))

(deftest parse-args-unknown-subcommand-test
  ;; unknown subcommand -> target nil, caller shows selector
  (is (= {:target nil} (parse "garbage"))))

;; -- preset-state + smart-default ---------------------------------------

(deftest smart-default-test
  ;; The smart default index matches the presets order:
  ;; 0 uncommitted, 1 base-branch, 2 commit
  (is (= 0 (dlg/smart-default true false)))
  (is (= 1 (dlg/smart-default false true)))
  (is (= 2 (dlg/smart-default false false))))
