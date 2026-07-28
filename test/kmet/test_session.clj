(ns kmet.test-session
  (:require [clojure.test :as t]
            [clojure.java.io :as io]
            [kmet.agent.session :as s]))

(def test-dir "target/test-sessions")

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
