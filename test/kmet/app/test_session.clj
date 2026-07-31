(ns kmet.app.test-session
  (:require [clojure.test :as t]
            [clojure.java.io :as io]
            [babashka.fs :as fs]
            [kmet.app.session :as s]))

(def test-dir "target/test-sessions")

;; The list-sessions test asserts per file, so a stale dir would make the
;; assertion count grow across runs — start each run from a clean dir.
(t/use-fixtures :once
  (fn [f]
    (fs/delete-tree test-dir)
    (f)))

(t/deftest test-session-create
  (let [session (s/create-session test-dir)]
    (t/is (string? (:id session)))
    (t/is (string? (:file session)))
    (t/is (instance? clojure.lang.Atom (:entries session)))
    (t/is (instance? clojure.lang.Atom (:leaf-id session)))
    (t/is (empty? @(:entries session)))
    (t/is (nil? @(:leaf-id session)))))

(t/deftest test-session-append-entry
  (let [session (s/create-session test-dir)]
    (let [entry (s/append-entry session {:role :user :content [{:type :text :text "hello"}]})]
      (t/is (string? (:id entry)))
      (t/is (nil? (:parent-id entry)))
      (t/is (= :user (:role entry)))
      (t/is (= 1 (count @(:entries session))))
      (t/is (string? @(:leaf-id session))))))

(t/deftest test-session-append-multiple
  (let [session (s/create-session test-dir)]
    (s/append-entry session {:role :user :content [{:type :text :text "q1"}]})
    (s/append-entry session {:role :assistant :content [{:type :text :text "a1"}]})
    (s/append-entry session {:role :user :content [{:type :text :text "q2"}]})
    (t/is (= 3 (count @(:entries session))))))

(t/deftest test-session-parent-id-chain
  (let [session (s/create-session test-dir)]
    (let [e1 (s/append-entry session {:role :user :content [{:type :text :text "q"}]})
          e2 (s/append-entry session {:role :assistant :content [{:type :text :text "a"}]})]
      (t/is (= (:id e1) (:parent-id e2))))))

(t/deftest test-session-get-branch
  (let [session (s/create-session test-dir)]
    (s/append-entry session {:role :user :content [{:type :text :text "q1"}]})
    (s/append-entry session {:role :assistant :content [{:type :text :text "a1"}]})
    (s/append-entry session {:role :user :content [{:type :text :text "q2"}]})
    (let [branch (s/get-branch session)]
      (t/is (= 3 (count branch)))
      (t/is (= :user (:role (first branch))))
      (t/is (= :user (:role (last branch)))))))

(t/deftest test-session-get-branch-empty
  (let [session (s/create-session test-dir)]
    (t/is (empty? (s/get-branch session)))))

(t/deftest test-session-save-and-load
  (let [session (s/create-session test-dir)]
    (s/append-entry session {:role :user :content [{:type :text :text "ping"}]})
    (s/append-entry session {:role :assistant :content [{:type :text :text "pong"}]})
    (let [file (:file session)]
      (t/is (.exists (io/file file)))
      (let [loaded (s/load-session file)]
        (t/is (= (:id session) (:id loaded)))
        (t/is (= 2 (count @(:entries loaded))))
        (t/is (= :user (:role (first @(:entries loaded)))))
        (t/is (= :assistant (:role (second @(:entries loaded)))))))))

(t/deftest test-session-load-nonexistent
  (t/is (thrown? Exception (s/load-session "nonexistent.ednl"))))

(t/deftest test-session-list-sessions
  (let [_ (s/create-session test-dir)
        _ (s/create-session test-dir)
        files (s/list-sessions test-dir)]
    (t/is (sequential? files))
    (t/is (>= (count files) 2))
    (doseq [f files]
      (t/is (.endsWith f ".ednl")))))

(t/deftest test-session-list-sessions-nonexistent-dir
  (let [files (s/list-sessions "nonexistent-dir")]
    (t/is (nil? files))))

(t/deftest test-session-fork
  (let [session (s/create-session test-dir)]
    (s/append-entry session {:role :user :content [{:type :text :text "q"}]})
    (let [e2 (s/append-entry session {:role :assistant :content [{:type :text :text "a"}]})
          fork (s/fork-session session (:id e2))]
      (t/is (some? fork))
      (t/is (not= (:id session) (:id fork)))
      (t/is (>= (count @(:entries fork)) 1)))))

(t/deftest test-session-fork-nonexistent
  (let [session (s/create-session test-dir)]
    (t/is (nil? (s/fork-session session "nonexistent")))))

(t/deftest test-session-get-tree
  (let [session (s/create-session test-dir)]
    (s/append-entry session {:role :user :content [{:type :text :text "q1"}]})
    (s/append-entry session {:role :assistant :content [{:type :text :text "a1"}]})
    (s/append-entry session {:role :user :content [{:type :text :text "q2"}]})
    (let [tree (s/get-tree session)]
      (t/is (vector? tree))
      (t/is (pos? (count tree)))
      (t/is (= :user (:role (first tree)))
        "Root is the first entry (user)")
      (t/is (= 1 (count (:children (first tree))))
        "Root has one child")
      (t/is (= :assistant (:role (first (:children (first tree)))))
        "First child is assistant"))))

(t/deftest test-session-compact
  (let [session (s/create-session test-dir)]
    (dotimes [i 10]
      (s/append-entry session {:role :user :content [{:type :text :text (str "q" i)}]})
      (s/append-entry session {:role :assistant :content [{:type :text :text (str "a" i)}]}))
    (let [result (s/compact! session 6)]
      (t/is (string? result))
      (t/is (<= (count @(:entries session)) 6)))))

(t/deftest test-session-delete
  (let [session (s/create-session test-dir)]
    (let [f (:file session)]
      (t/is (.exists (io/file f)))
      (s/delete-session! session)
      (t/is (not (.exists (io/file f)))))))

;; ─── Regression: corrupted entries ────────────────────────────────────────

(t/deftest test-session-load-with-corrupt-entry
  (let [session (s/create-session test-dir)
        file (:file session)]
    ;; Write: valid, broken (unclosed map), valid
    (spit file "{:id \"1\" :role :user :content \"hello\"}\n")
    (spit file "{:bad \"entry\"\n" :append true)  ;; unclosed map → parse error
    (spit file "{:id \"3\" :role :assistant :content \"world\"}\n" :append true)
    (let [loaded (s/load-session file)
          entries @(:entries loaded)]
      (t/is (= 2 (count entries)) "Should skip corrupt entry, keep both valid ones")
      (t/is (= "1" (:id (first entries))))
      (t/is (= "3" (:id (second entries))))
      (t/is (= :user (:role (first entries))))
      (t/is (= :assistant (:role (second entries)))))))

(t/deftest test-session-load-with-multiple-corrupt-entries
  (let [session (s/create-session test-dir)
        file (:file session)]
    ;; Write: valid, broken, valid, broken, valid
    (spit file "{:id \"1\" :role :user :content \"a\"}\n")
    (spit file "garbage!@\n" :append true)         ;; invalid characters
    (spit file "{:id \"3\" :role :user :content \"b\"}\n" :append true)
    (spit file "{{:id \"4\"}\n" :append true)     ;; double brace → parse error
    (spit file "{:id \"5\" :role :assistant :content \"c\"}\n" :append true)
    (let [loaded (s/load-session file)
          entries @(:entries loaded)]
      (t/is (= 3 (count entries)) "Should skip all corrupt entries, keep all valid ones")
      (t/is (= "1" (:id (nth entries 0))))
      (t/is (= "3" (:id (nth entries 1))))
      (t/is (= "5" (:id (nth entries 2)))))))

(t/deftest test-session-load-all-corrupt
  (let [session (s/create-session test-dir)
        file (:file session)]
    (spit file "{{{{{{\n")
    (spit file "[invalid}^&*\n" :append true)
    (let [loaded (s/load-session file)
          entries @(:entries loaded)]
      (t/is (empty? entries) "Should return empty entries when all lines are corrupt"))))

(t/deftest test-session-load-empty-file
  (let [session (s/create-session test-dir)
        file (:file session)]
    (spit file "")
    (let [loaded (s/load-session file)
          entries @(:entries loaded)]
      (t/is (empty? entries) "Empty file should produce empty entries"))))

(t/deftest test-session-load-with-blank-lines
  (let [session (s/create-session test-dir)
        file (:file session)]
    (spit file "{:id \"1\" :role :user :content \"hi\"}\n")
    (spit file "\n" :append true)  ;; blank line
    (spit file "  \n" :append true)  ;; whitespace line
    (spit file "{:id \"4\" :role :assistant :content \"bye\"}\n" :append true)
    (let [loaded (s/load-session file)
          entries @(:entries loaded)]
      (t/is (= 2 (count entries)) "Should skip blank lines")
      (t/is (= "1" (:id (first entries))))
      (t/is (= "4" (:id (second entries)))))))
