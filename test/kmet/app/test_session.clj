(ns kmet.app.test-session
  (:require [clojure.test :as t]
            [clojure.string :as str]
            [clojure.edn :as edn]
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

(defn- silent-stderr
  "Run f with stderr redirected — suppresses the expected warning
   output from deliberately corrupt test session files."
  [f]
  (binding [*err* (java.io.StringWriter.)]
    (f)))

(t/deftest test-session-create
  (let [session (s/create-session test-dir)]
    (t/is (string? (:id session)))
    (t/is (string? (:file session)))
    (t/is (instance? clojure.lang.Atom (:entries session)))
    (t/is (instance? clojure.lang.Atom (:leaf-id session)))
    (t/is (empty? @(:entries session)))
    (t/is (nil? @(:leaf-id session)))
    (t/is (not (.exists (io/file (:file session))))
          "lazy creation: no file until the first assistant message (G4)")))

(t/deftest test-session-header
  ;; G1: header carries type/version/id/created-at/cwd/parent-session
  (let [session (s/create-session test-dir
                                  {:cwd "/home/user/proj"
                                   :parent-session "/home/user/proj/parent.ednl"})]
    (t/is (= :session (:type (:header session))))
    (t/is (= 1 (:version (:header session))))
    (t/is (= (:id session) (:id (:header session))))
    (t/is (= "/home/user/proj" (:cwd (:header session))))
    (t/is (= "/home/user/proj/parent.ednl" (:parent-session (:header session))))
    (t/is (string? (:created-at (:header session)))))
  (t/is (nil? (:parent-session (:header (s/create-session test-dir))))
        "parent-session omitted for a root session")
  (t/is (= (str (fs/cwd)) (:cwd (:header (s/create-session test-dir))))
        "cwd defaults to the process cwd"))

(t/deftest test-session-lazy-creation
  ;; G4: no file until the first assistant message — user/bash entries
  ;; accumulate in memory; the assistant entry persists header + everything
  ;; in order (pi: _persist)
  (let [dir (str "target/test-sess-lazy-" (System/currentTimeMillis))
        sess (s/create-session dir)]
    (try
      (t/is (not (fs/exists? (:file sess))) "no file after create")
      (s/append-entry sess {:role :user :content "q"})
      (s/append-entry sess {:role :bash :command "ls" :output "" :exit-code 0})
      (t/is (not (fs/exists? (:file sess)))
            "no file with only user/bash entries")
      (s/append-entry sess {:role :assistant :content "a"})
      (t/is (fs/exists? (:file sess))
            "file appears on the first assistant message")
      (let [lines (str/split-lines (slurp (:file sess)))]
        (t/is (= :session (:type (edn/read-string (first lines))))
              "header is the first line")
        (t/is (= 4 (count lines)) "header + 3 entries persisted in order"))
      (let [loaded (s/load-session (:file sess))]
        (t/is (= 3 (count @(:entries loaded))))
        (t/is (= (:id sess) (:id loaded)) "id round-trips via the header")
        (t/is (= (:header sess) (:header loaded)) "header round-trips"))
      (finally (fs/delete-tree dir)))))

(t/deftest test-session-legacy-file-loads
  ;; Files without a header (pre-G1 format) still load: entries-only, :header
  ;; nil, :id derived from the filename
  (let [dir (str "target/test-sess-legacy-" (System/currentTimeMillis))
        sess (s/create-session dir)
        file (:file sess)]
    (try
      (spit file "{:id \"1\" :role :user :content \"a\"}\n")
      (spit file "{:id \"2\" :role :assistant :content \"b\"}\n" :append true)
      (let [loaded (s/load-session file)]
        (t/is (nil? (:header loaded)))
        (t/is (= 2 (count @(:entries loaded))))
        (t/is (= (str/replace (fs/file-name file) #"\.ednl$" "") (:id loaded))
              "legacy id derives from the filename"))
      (finally (fs/delete-tree dir)))))

(t/deftest test-session-append-entry
  (let [session (s/create-session test-dir)
        entry (s/append-entry session {:role :user :content [{:type :text :text "hello"}]})]
    (t/is (string? (:id entry)))
    (t/is (nil? (:parent-id entry)))
    (t/is (= :user (:role entry)))
    (t/is (= 1 (count @(:entries session))))
    (t/is (string? @(:leaf-id session)))))

(t/deftest test-session-concurrent-appends
  ;; Concurrent appends (a ! bash result on its future thread + a submitted
  ;; message on the agent thread) must not orphan sibling entries from the
  ;; branch — pi serializes all mutations through storage.enqueue; kmet locks
  ;; the session. Regression: this used to lose ~38% of entries on reload.
  (let [session (s/create-session test-dir)]
    (s/append-entry session {:role :user :content [{:type :text :text "seed"}]})
    ;; An assistant entry persists the file first, so the concurrent appends
    ;; below exercise the serialized append-to-file path (lazy creation G4
    ;; would otherwise hold everything in memory).
    (s/append-entry session {:role :assistant :content [{:type :text :text "boot"}]})
    (let [futures (doall
                   (for [_ (range 200)]
                     [(future (s/append-entry session {:role :bash :command "ls"}))
                      (future (s/append-entry session {:role :user :content [{:type :text :text "followup"}]}))]))]
      (doseq [[f1 f2] futures] @f1 @f2))
    (let [loaded (s/load-session (:file session))
          n-branch (count (s/get-branch loaded))
          n-total (count @(:entries loaded))]
      (t/is (= n-branch n-total)))))

(t/deftest test-session-append-multiple
  (let [session (s/create-session test-dir)]
    (s/append-entry session {:role :user :content [{:type :text :text "q1"}]})
    (s/append-entry session {:role :assistant :content [{:type :text :text "a1"}]})
    (s/append-entry session {:role :user :content [{:type :text :text "q2"}]})
    (t/is (= 3 (count @(:entries session))))))

(t/deftest test-session-parent-id-chain
  (let [session (s/create-session test-dir)
        e1 (s/append-entry session {:role :user :content [{:type :text :text "q"}]})
        e2 (s/append-entry session {:role :assistant :content [{:type :text :text "a"}]})]
    (t/is (= (:id e1) (:parent-id e2)))))

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
        (t/is (= (:header session) (:header loaded)) "header round-trips")
        (t/is (= 2 (count @(:entries loaded))))
        (t/is (= :user (:role (first @(:entries loaded)))))
        (t/is (= :assistant (:role (second @(:entries loaded)))))))))

(t/deftest test-session-load-nonexistent
  (t/is (thrown? Exception (s/load-session "nonexistent.ednl"))))

(t/deftest test-session-list-sessions
  (let [s1 (s/create-session test-dir)
        _ (s/append-entry s1 {:role :user :content "q"})
        _ (s/append-entry s1 {:role :assistant :content "a"})
        s2 (s/create-session test-dir)
        _ (s/append-entry s2 {:role :user :content "q"})
        _ (s/append-entry s2 {:role :assistant :content "a"})
        files (s/list-sessions test-dir)]
    (t/is (sequential? files))
    (t/is (>= (count files) 2))
    (doseq [f files]
      (t/is (str/ends-with? f ".ednl")))))

(t/deftest test-encode-cwd
  ;; G2: pi getDefaultSessionDirPath — `--` + cwd with leading slash stripped
  ;; and / \ : replaced by -
  (t/is (= "--home-user-proj--" (s/encode-cwd "/home/user/proj")))
  (t/is (= "--home-user-proj--" (s/encode-cwd "\\home\\user\\proj")))
  (t/is (= "--C--Users-dev--" (s/encode-cwd "C:\\Users\\dev")))
  (t/is (= "--a-b--" (s/encode-cwd "a/b"))))

(t/deftest test-session-list-cwd-dirs
  ;; G2: sessions live in <base>/<--cwd-->/; list-sessions on the base walks
  ;; the cwd subdirs (pi: listAll), a cwd dir lists flat, and legacy flat
  ;; files in the base stay visible (no regression on upgrade)
  (let [dir (str "target/test-sess-cwd-" (System/currentTimeMillis))
        cwd-a (s/session-dir-for-cwd dir "/home/user/proj-a")
        cwd-b (s/session-dir-for-cwd dir "/home/user/proj-b")
        sa (s/create-session cwd-a)
        _ (s/append-entry sa {:role :assistant :content "a1"})
        sb (s/create-session cwd-b)
        _ (s/append-entry sb {:role :assistant :content "b1"})
        ;; a legacy pre-G2 session file placed flat in the base dir
        legacy (s/create-session dir)
        _ (s/append-entry legacy {:role :assistant :content "legacy"})]
    (try
      (t/is (= 3 (count (s/list-sessions dir)))
            "base listing walks cwd subdirs + legacy flat files")
      (t/is (= 1 (count (s/list-sessions cwd-a))) "cwd dir lists flat")
      (finally (fs/delete-tree dir)))))

(t/deftest test-find-most-recent-session
  ;; G23/continue: pi findMostRecentSession — header-based discovery in the
  ;; cwd dir, scoped by header :cwd; legacy headerless files and other-cwd
  ;; sessions excluded, no fallback
  (let [dir (str "target/test-sess-mrs-" (System/currentTimeMillis))
        cwd-a (s/session-dir-for-cwd dir "/home/user/proj-a")
        cwd-b (s/session-dir-for-cwd dir "/home/user/proj-b")
        sa1 (s/create-session cwd-a {:cwd "/home/user/proj-a"})
        _ (s/append-entry sa1 {:role :assistant :content "a1"})
        _ (Thread/sleep 5)
        sa2 (s/create-session cwd-a {:cwd "/home/user/proj-a"})
        _ (s/append-entry sa2 {:role :assistant :content "a2"})
        sb (s/create-session cwd-b {:cwd "/home/user/proj-b"})
        _ (s/append-entry sb {:role :assistant :content "b1"})
        ;; a legacy headerless file in the cwd dir — must be excluded
        legacy (s/create-session cwd-a {:cwd "/home/user/proj-a"})
        _ (spit (:file legacy) "{:id \"1\" :role :assistant :content \"old\"}\n")]
    (try
      (t/is (= (:file sa2) (s/find-most-recent-session cwd-a "/home/user/proj-a"))
            "newest matching session in the cwd dir wins")
      (t/is (nil? (s/find-most-recent-session cwd-a "/home/user/proj-b"))
            "header :cwd mismatch → excluded")
      (t/is (nil? (s/find-most-recent-session cwd-b "/home/user/proj-a"))
            "dir scoping: proj-b has no proj-a session")
      (t/is (nil? (s/find-most-recent-session dir "/home/user/proj-a"))
            "only the cwd dir is scanned, not the base")
      (t/is (nil? (s/find-most-recent-session (str dir "/missing") "/home/user/proj-a"))
            "missing dir → nil")
      (finally (fs/delete-tree dir)))))

(t/deftest test-session-list-sessions-nonexistent-dir
  (let [files (s/list-sessions "nonexistent-dir")]
    (t/is (nil? files))))

(t/deftest test-session-first-message
  ;; pi: buildSessionInfo firstMessage — the first user message text
  (let [session (s/create-session test-dir)]
    (t/is (= "(no messages)" (s/get-first-message session)) "empty session")
    (s/append-entry session {:role :user :content [{:type :text :text "hello"}]})
    (s/append-entry session {:role :assistant :content [{:type :text :text "hi"}]})
    (s/append-entry session {:role :user :content [{:type :text :text "steered"}]})
    (t/is (= "hello" (s/get-first-message session)) "first user message wins")))

(t/deftest test-session-message-count
  ;; pi: buildSessionInfo messageCount — message entries, session_info excluded
  (let [session (s/create-session test-dir)]
    (s/append-entry session {:role :user :content [{:type :text :text "a"}]})
    (s/append-entry session {:role :assistant :content [{:type :text :text "b"}]})
    (s/append-entry session {:role :session_info :name "t"})
    (s/append-entry session {:role :bash :command "ls" :output "" :exit-code 0})
    (t/is (= 3 (s/get-message-count session)))))

(t/deftest test-session-last-activity
  ;; pi: modified — the last entry's timestamp, falling back to file mtime
  (let [session (s/create-session test-dir)
        now (System/currentTimeMillis)]
    (s/append-entry session {:role :user :content [{:type :text :text "a"}]})
    (let [ms (s/get-last-activity-ms session)]
      (t/is (number? ms))
      (t/is (>= ms (- now 5000)) "last activity is recent"))))

(t/deftest test-session-last-activity-empty
  ;; Unsaved (lazy) sessions have no file — last activity falls back to the
  ;; header created-at (G4), so the resume dialog can still format an age.
  (let [session (s/create-session test-dir)]
    (t/is (number? (s/get-last-activity-ms session)))
    (t/is (pos? (s/get-last-activity-ms session)))))

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
    (let [result (s/compact! session 6)
          branch (s/get-branch session)]
      (t/is (some? result))
      (t/is (= :compaction (:role result)))
      (t/is (= 21 (count @(:entries session)))
            "append-only: all entries stay, compaction entry added")
      (t/is (= (:id result) (:id (last branch)))
            "compaction is the new leaf")))
  (let [session (s/create-session test-dir)]
    (s/append-entry session {:role :user :content "hi"})
    (t/is (nil? (s/compact! session 6))
          "nothing to compact below the threshold")))

(t/deftest test-session-delete
  (let [session (s/create-session test-dir)
        f (:file session)]
    (s/append-entry session {:role :assistant :content "hi"}) ;; persist (lazy G4)
    (t/is (.exists (io/file f)))
    (s/delete-session! session)
    (t/is (not (.exists (io/file f))))))

;; ─── Regression: corrupted entries ────────────────────────────────────────

(t/deftest test-session-load-with-corrupt-entry
  (let [session (s/create-session test-dir)
        file (:file session)]
    ;; Write: valid, broken (unclosed map), valid
    (spit file "{:id \"1\" :role :user :content \"hello\"}\n")
    (spit file "{:bad \"entry\"\n" :append true)  ;; unclosed map → parse error
    (spit file "{:id \"3\" :role :assistant :content \"world\"}\n" :append true)
    (let [loaded (silent-stderr #(s/load-session file))
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
    (let [loaded (silent-stderr #(s/load-session file))
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
    (let [loaded (silent-stderr #(s/load-session file))
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

(t/deftest test-compact-with-summary
  (let [dir (str "target/test-sess-cws-" (System/currentTimeMillis))
        sess (s/create-session dir)]
    (try
      (doseq [i (range 6)]
        (s/append-entry sess {:role :user :content [{:type :text :text (str "msg " i)}]}))
      (let [entries (s/get-branch sess)
            first-kept-id (:id (nth entries 3))
            summary-entry (s/compact-with-summary! sess "SUMMARY" first-kept-id)
            branch (s/get-branch sess)
            context (s/build-context sess)]
        (t/is (some? summary-entry))
        (t/is (= :compaction (:role summary-entry)))
        (t/is (= "SUMMARY" (:summary summary-entry)))
        (t/is (= first-kept-id (:first-kept-id summary-entry)))
        (t/is (= 7 (count branch)) "append-only: 6 entries + compaction")
        (t/is (= (:id (last entries)) (:parent-id summary-entry))
              "compaction is a child of the previous leaf")
        (t/is (= 4 (count context)) "context = compaction + 3 kept entries")
        (t/is (= :compaction (:role (first context))))
        (t/is (= first-kept-id (:id (second context))) "first kept entry follows")
        (t/is (= (:id (first entries)) (:id (first branch)))
              "summarized history stays in the branch")
        ;; persist the file (lazy G4: needs an assistant message) so the
        ;; reload checks below have something to read
        (s/append-entry sess {:role :assistant :content "done"})
        ;; file reloadable, context survives a reload
        (let [loaded (s/load-session (:file sess))]
          (t/is (= 8 (count @(:entries loaded)))
                "6 user + compaction + the done entry persist")
          (t/is (= 5 (count (s/build-context loaded)))
                "context = compaction + 3 kept + done")))
      (finally (fs/delete-tree dir)))))

;; ─── Append-only compaction: context build (pi: buildContextEntries) ─────

(t/deftest test-build-context-no-compaction
  (let [session (s/create-session test-dir)]
    (s/append-entry session {:role :user :content "a"})
    (s/append-entry session {:role :assistant :content "b"})
    (t/is (= 2 (count (s/build-context session)))
          "no compaction on the path → full branch")))

(t/deftest test-build-context-empty
  (let [session (s/create-session test-dir)]
    (t/is (empty? (s/build-context session)))))

(t/deftest test-build-context-latest-compaction-wins
  (let [session (s/create-session test-dir)]
    (dotimes [i 6]
      (s/append-entry session {:role :user :content (str "m" i)}))
    (let [first-kept-1 (:id (nth (s/get-branch session) 2))]
      (s/compact-with-summary! session "FIRST" first-kept-1)
      (s/append-entry session {:role :user :content "m6"})
      (s/append-entry session {:role :user :content "m7"})
      (let [e2 (s/get-branch session)
            first-kept-2 (:id (nth e2 4))]
        (s/compact-with-summary! session "SECOND" first-kept-2)
        (let [context (s/build-context session)]
          (t/is (= "SECOND" (:summary (first context)))
                "latest compaction wins")
          (t/is (= first-kept-2 (:id (second context)))
                "its first-kept-id starts the tail"))))))

(t/deftest test-build-context-messages
  (let [session (s/create-session test-dir)]
    (s/append-entry session {:role :user :content "q"})
    (s/append-entry session {:role :assistant :content "a"})
    (s/append-session-info! session "t")
    (s/append-entry session {:role :info :label "note" :content "x"})
    (s/compact-with-summary! session "SUM" (:id (first (s/get-branch session))))
    (let [msgs (mapcat s/context-messages (s/build-context session))]
      (t/is (= 3 (count msgs)))
      (t/is (= "SUM" (-> msgs first :content first :text))
            "compaction projects to a :user summary message")
      (t/is (= "q" (-> msgs second :content)))
      (t/is (= "a" (-> msgs last :content)))
      (t/is (not-any? #(contains? #{:info :session_info} (:role %)) msgs)
            ":info/:session_info are metadata, excluded from context"))))

(t/deftest test-compaction-retains-old-entries
  (let [session (s/create-session test-dir)]
    (dotimes [i 4]
      (s/append-entry session {:role :user :content (str "q" i)}))
    (let [branch (s/get-branch session)
          first-kept-id (:id (nth branch 2))]
      (s/compact-with-summary! session "SUM" first-kept-id)
      (s/append-entry session {:role :assistant :content "after"}))
    (let [loaded (s/load-session (:file session))]
      (t/is (= 6 (count @(:entries loaded))) "all entries persist on reload")
      (t/is (some #(= "q0" (:content %)) @(:entries loaded))
            "summarized history stays reachable (tree/fork)"))))

;; ─── Atomic rewrite (pi: temp-file publication) ──────────────────────────

(t/deftest test-replace-entries
  (let [session (s/create-session test-dir)]
    (s/append-entry session {:role :user :content "old"})
    (s/replace-entries! session [{:role :user :content "new1"}
                                 {:role :assistant :content "new2"}])
    (let [branch (s/get-branch session)]
      (t/is (= 2 (count branch)))
      (t/is (= "new1" (-> branch first :content)))
      (t/is (= "new2" (-> branch second :content)))
      (t/is (= (:id (first branch)) (:parent-id (second branch)))
            "linear chain rebuilt")
      (let [loaded (s/load-session (:file session))]
        (t/is (= 2 (count @(:entries loaded))))))))

;; ─── Session display name (pi: /name command) ────────────────────────────

(t/deftest test-sanitize-session-name
  (t/is (= "my session" (s/sanitize-session-name " my\nsession\r\n "))
        "newlines collapse to spaces, then trim")
  (t/is (= "" (s/sanitize-session-name "  \n "))
        "whitespace-only names sanitize to empty"))

(t/deftest test-append-session-info
  (let [session (s/create-session test-dir)
        entry (s/append-session-info! session "my session")]
    (t/is (= :session_info (:role entry)))
    (t/is (= "my session" (:name entry)))
    (t/is (= 1 (count @(:entries session))))
    (t/is (= 1 (count (s/get-branch session)))
          "session_info participates in the branch tree")))

(t/deftest test-get-session-name
  (let [session (s/create-session test-dir)]
    (t/is (nil? (s/get-session-name session)))
    (s/append-entry session {:role :user :content "hi"})
    (s/append-session-info! session "alpha")
    (t/is (= "alpha" (s/get-session-name session)))
    (s/append-session-info! session "beta")
    (t/is (= "beta" (s/get-session-name session))
          "latest session_info entry wins")))

(t/deftest test-get-session-name-empty-clears
  (let [session (s/create-session test-dir)]
    (s/append-session-info! session "alpha")
    (s/append-session-info! session "   ")
    (t/is (nil? (s/get-session-name session))
          "empty name explicitly clears the title")))

(t/deftest test-session-name-persists
  (let [session (s/create-session test-dir)
        file (:file session)]
    (s/append-entry session {:role :user :content "hi"})
    (s/append-entry session {:role :assistant :content "hello"}) ;; persist (lazy G4)
    (s/append-session-info! session "my session")
    (let [loaded (s/load-session file)]
      (t/is (= "my session" (s/get-session-name loaded))))))

;; ─── Usage tracking ────────────────────────────────────────────────────────

;; ─── Bash result recording ─────────────────────────────────────────────────

(t/deftest test-make-bash-entry
  (let [entry (s/make-bash-entry "echo hi" {:output "hi\n" :exit-code 0} false)]
    (t/is (= :bash (:role entry)))
    (t/is (= "echo hi" (:command entry)))
    (t/is (= "hi\n" (:output entry)))
    (t/is (= 0 (:exit-code entry)))
    (t/is (false? (:cancelled entry)))
    (t/is (false? (:exclude-from-context? entry)))
    (t/is (false? (:truncated entry)))
    (t/is (nil? (:full-output-path entry))))
  (t/is (= true (:exclude-from-context?
                 (s/make-bash-entry "ls" {:output "" :exit-code 1} true))))
  (t/is (= "" (:output (s/make-bash-entry "ls" {} false))))
  (t/is (= true (:cancelled (s/make-bash-entry "ls" {:cancelled true} false)))))

(t/deftest test-record-bash-result
  (let [session-dir (io/file test-dir "bash")
        sess (s/create-session (str session-dir))
        result {:output "clean\n" :exit-code 0}
        entry (s/record-bash-result! sess "git st" result false)]
    (t/is (= :bash (:role entry)))
    (t/is (= "git st" (:command entry)))
    (t/is (= 1 (count @(:entries sess))))
    (t/is (= (:id entry) (:id (first @(:entries sess)))))))

(t/deftest test-entry-usage
  (t/testing "OpenAI usage shape — input excludes cached tokens (pi normalizeUsage)"
    (t/is (= {:input 70 :output 20 :cache-read 30 :cache-write 0 :cost 0.0}
             (s/entry-usage {:prompt_tokens 100 :completion_tokens 20
                             :prompt_tokens_details {:cached_tokens 30}}))))
  (t/testing "OpenAI cache-write tokens are split out and subtracted (OpenRouter-compatible)"
    (t/is (= {:input 65 :output 20 :cache-read 30 :cache-write 5 :cost 0.0}
             (s/entry-usage {:prompt_tokens 100 :completion_tokens 20
                             :prompt_tokens_details {:cached_tokens 30
                                                     :cache_write_tokens 5}}))))
  (t/testing "Anthropic usage shape — input_tokens already excludes cache"
    (t/is (= {:input 100 :output 20 :cache-read 30 :cache-write 10 :cost 0.0}
             (s/entry-usage {:input_tokens 100 :output_tokens 20
                             :cache_read_input_tokens 30
                             :cache_creation_input_tokens 10}))))
  (t/testing "Google's already-normalized shape (sse/google-usage)"
    (t/is (= {:input 10 :output 20 :cache-read 5 :cache-write 0 :cost 0.0}
             (s/entry-usage {:input 10 :output 20 :cache-read 5 :cache-write 0}))))
  (t/testing "fully-cached prompt → zero input, cache tokens still reported"
    (t/is (= {:input 0 :output 1 :cache-read 100 :cache-write 0 :cost 0.0}
             (s/entry-usage {:prompt_tokens 100 :completion_tokens 1
                             :prompt_tokens_details {:cached_tokens 100}}))))
  (t/testing "cost is carried through from the breakdown attached by llm"
    (t/is (= 0.0042 (:cost (s/entry-usage {:prompt_tokens 100 :completion_tokens 20
                                           :cost {:input 0.0014 :output 0.0028
                                                  :cache-read 0.0 :cache-write 0.0
                                                  :total 0.0042}})))))
  (t/testing "unknown shape returns nil"
    (t/is (nil? (s/entry-usage {:foo 1})))
    (t/is (nil? (s/entry-usage nil)))))

(t/deftest test-usage-totals
  (let [dir (str "target/test-sess-usage-" (System/currentTimeMillis))
        sess (s/create-session dir)]
    (try
      (s/append-entry sess {:role :user :content "hi"})
      (s/append-entry sess {:role :assistant :content "a"
                            :usage {:prompt_tokens 10 :completion_tokens 2}})
      (s/append-entry sess {:role :assistant :content "b"
                            :usage {:prompt_tokens 20 :completion_tokens 4
                                    :prompt_tokens_details {:cached_tokens 6}}})
      (s/append-entry sess {:role :assistant :content "c"
                            :usage {:prompt_tokens 100 :completion_tokens 0
                                    :cost {:input 0.001 :output 0.0
                                           :cache-read 0.0 :cache-write 0.0
                                           :total 0.001}}})
      (t/is (= {:input 124 :output 6 :cache-read 6 :cache-write 0 :cost 0.001}
               (s/usage-totals sess)))
      (finally (fs/delete-tree dir)))))

;; ─── Branching (G17): branch!/reset-leaf!/branch-with-summary! ─────────────

(t/deftest test-branch-moves-leaf
  (let [session (s/create-session test-dir)]
    (s/append-entry session {:role :user :content "q1"})
    (s/append-entry session {:role :assistant :content "a1"})
    (let [u2 (s/append-entry session {:role :user :content "q2"})
          _ (s/append-entry session {:role :assistant :content "a2"})]
      ;; branch back to the second user message: next append is its child
      (s/branch! session (:id u2))
      (t/is (= (:id u2) @(:leaf-id session)))
      (t/is (= [:user :assistant :user] (map :role (s/get-branch session))))
      (let [a3 (s/append-entry session {:role :assistant :content "a3"})]
        (t/is (= (:id u2) (:parent-id a3))
              "append after branch becomes a child of the new leaf")
        (t/is (= :assistant (:role (last (s/get-branch session)))))
        (t/is (= 5 (count @(:entries session)))
              "old branch entries remain in the file")))))

(t/deftest test-branch-unknown-entry-throws
  (let [session (s/create-session test-dir)]
    (s/append-entry session {:role :user :content "q1"})
    (t/is (thrown? clojure.lang.ExceptionInfo (s/branch! session "nope")))))

(t/deftest test-reset-leaf
  (let [session (s/create-session test-dir)]
    (s/append-entry session {:role :user :content "q1"})
    (s/reset-leaf! session)
    (t/is (nil? @(:leaf-id session)))
    (let [u2 (s/append-entry session {:role :user :content "q2"})]
      (t/is (nil? (:parent-id u2))
            "append after reset becomes a new root")
      (t/is (= [u2] (s/get-branch session))))))

(t/deftest test-branch-with-summary
  (let [session (s/create-session test-dir)]
    (s/append-entry session {:role :user :content "q1"})
    (let [u2 (s/append-entry session {:role :user :content "q2"})
          summary (s/branch-with-summary! session (:id u2)
                                          "explored option B" {:details {:x 1}})]
      (t/is (= :branch-summary (:role summary)))
      (t/is (= (:id u2) (:from-id summary)))
      (t/is (= (:id u2) (:parent-id summary))
            "summary entry is a child of the new leaf position")
      (t/is (= "explored option B" (:summary summary)))
      (t/is (= {:x 1} (:details summary)))
      (t/is (= (:id summary) @(:leaf-id session))))
    ;; nil branch-from-id → root, from-id "root"
    (s/reset-leaf! session)
    (let [summary (s/branch-with-summary! session nil "back to root")]
      (t/is (= "root" (:from-id summary)))
      (t/is (nil? (:parent-id summary))))
    (t/is (thrown? clojure.lang.ExceptionInfo
                   (s/branch-with-summary! session "nope" "x")))))

(t/deftest test-branch-summary-projects-to-context
  (let [session (s/create-session test-dir)]
    (s/append-entry session {:role :user :content "q1"})
    (let [u2 (s/append-entry session {:role :user :content "q2"})
          _ (s/branch-with-summary! session (:id u2) "abandoned path")
          msgs (mapcat s/context-messages (s/build-context session))]
      (t/is (= :user (:role (last msgs))))
      (t/is (= "abandoned path" (-> msgs last :content first :text))
            "branch_summary projects as a user summary message"))))

(t/deftest test-branch-summary-entries
  ;; branch: root → u1 → a1 → u2 → a2; navigate from a2 back to u1
  (let [session (s/create-session test-dir)]
    (s/append-entry session {:role :user :content "q1"})
    (let [a1 (s/append-entry session {:role :assistant :content "a1"})
          u2 (s/append-entry session {:role :user :content "q2"})
          a2 (s/append-entry session {:role :assistant :content "a2"})]
      (t/is (= [a2] (s/branch-summary-entries session (:id a2) (:id u2)))
            "abandoned tail from the old leaf down to (excluding) the common ancestor")
      (t/is (= [u2 a2] (s/branch-summary-entries session (:id a2) (:id a1)))
            "chronological order (rootward first), common ancestor (a1) excluded"))))

;; ─── Labels (G11): set-label!/get-label ────────────────────────────────────

(t/deftest test-labels
  (let [session (s/create-session test-dir)]
    (s/append-entry session {:role :user :content "q1"})
    (let [a1 (s/append-entry session {:role :assistant :content "a1"})]
      (t/is (nil? (s/get-label session (:id a1))))
      (s/set-label! session (:id a1) "fix-123")
      (t/is (= "fix-123" (s/get-label session (:id a1))))
      ;; latest-wins
      (s/set-label! session (:id a1) "fix-456")
      (t/is (= "fix-456" (s/get-label session (:id a1))))
      ;; clearing
      (s/set-label! session (:id a1) nil)
      (t/is (nil? (s/get-label session (:id a1))))
      (t/is (thrown? clojure.lang.ExceptionInfo (s/set-label! session "nope" "x"))))))

;; ─── Fork (G18): createBranchedSession semantics ───────────────────────────

(t/deftest test-fork-keeps-ids-and-parent-chain
  (let [session (s/create-session test-dir)
        q1 (s/append-entry session {:role :user :content "q1"})
        a1 (s/append-entry session {:role :assistant :content "a1"})
        u2 (s/append-entry session {:role :user :content "q2"})]
    (s/branch! session (:id u2))
    (let [fork (s/fork-session session (:id u2))]
      (t/is (some? fork))
      (t/is (= [(:id q1) (:id a1) (:id u2)] (map :id (s/get-branch fork)))
            "fork branch = root→entry")
      (t/is (= (:id u2) (:id (last (s/get-branch fork))))
            "entry ids are preserved")
      (t/is (= (:file session) (get-in fork [:header :parent-session]))
            "fork header links the source file")
      (t/is (= (get-in session [:header :cwd]) (get-in fork [:header :cwd]))
            "fork keeps the source cwd")
      (t/is (= (:id u2) (:parent-id (s/append-entry fork {:role :assistant :content "a3"})))
            "fork's next append children off the preserved leaf id"))))

(t/deftest test-fork-rechains-around-labels
  (let [session (s/create-session test-dir)
        q1 (s/append-entry session {:role :user :content "q1"})
        a1 (s/append-entry session {:role :assistant :content "a1"})
        ;; label entry chains off a1, then the next message chains off the label
        lab (s/set-label! session (:id a1) "keep")
        u2 (s/append-entry session {:role :user :content "q2"})
        a2 (s/append-entry session {:role :assistant :content "a2"})]
    (t/is (= (:id lab) (:parent-id u2))
          "later entries are children of the label entry")
    (let [fork (s/fork-session session (:id a2))
          fork-u2 (s/get-entry fork (:id u2))
          fork-a2 (s/get-entry fork (:id a2))
          fork-leaf (last (s/get-branch fork))]
      (t/is (= [(:id q1) (:id a1) (:id u2) (:id a2)]
               (mapv :id (butlast (s/get-branch fork))))
            "fork path skips labels and re-chains parents")
      (t/is (= :label (:role fork-leaf))
            "the recreated label chains off the last retained entry (the new leaf)")
      (t/is (= (:id a1) (:target-id fork-leaf)))
      (t/is (= (:id a1) (:parent-id fork-u2))
            "re-chained: u2's parent is a1, not the removed label")
      (t/is (= (:id u2) (:parent-id fork-a2))
            "a2's parent chain is intact")
      (t/is (= "keep" (s/get-label fork (:id a1)))
            "label recreated in the fork (original target, latest label)")
      (t/is (= (:timestamp lab) (:timestamp fork-leaf))
            "recreated label entry keeps its original timestamp")
      (t/is (= 5 (count @(:entries fork)))
            "fork holds the four retained entries + the recreated label"))))

(t/deftest test-clone-session
  (let [session (s/create-session test-dir)]
    (t/is (nil? (s/clone-session session))
          "nothing to clone before the first entry")
    (s/append-entry session {:role :user :content "q1"})
    (s/append-entry session {:role :assistant :content "a1"})
    (let [clone (s/clone-session session)]
      (t/is (some? clone))
      (t/is (= (map :id (s/get-branch session))
               (map :id (s/get-branch clone)))
            "clone copies the full active branch")
      (t/is (= (:file session) (get-in clone [:header :parent-session]))))))

(t/deftest test-fork-from
  (let [source (s/create-session test-dir)]
    (s/append-entry source {:role :user :content "q1"})
    (s/append-entry source {:role :assistant :content "a1"})
    (let [path (:file source)
          ;; force persistence (lazy G4 writes on first assistant message)
          _ (t/is (fs/exists? path))
          target-cwd (str (fs/path (fs/temp-dir) "other-proj"))
          fork (s/fork-from path target-cwd test-dir)]
      (t/is (some? fork))
      (t/is (= (map :id (s/get-branch source))
               (map :id (s/get-branch fork)))
            "fork-from copies all entries verbatim (ids preserved)")
      (t/is (= target-cwd (get-in fork [:header :cwd]))
            "fork-from header carries the target cwd")
      (t/is (= path (get-in fork [:header :parent-session])))
      (t/is (fs/exists? (:file fork))
            "fork-from writes the fork file immediately")))
  (let [empty-file (str test-dir "/empty.ednl")]
    (spit empty-file "")
    (t/is (thrown? clojure.lang.ExceptionInfo
                   (s/fork-from empty-file (str (fs/temp-dir)) test-dir)))
    (fs/delete empty-file)))

(t/deftest test-common-ancestor-id
  (let [session (s/create-session test-dir)
        q1 (s/append-entry session {:role :user :content "q1"})
        _ (s/append-entry session {:role :assistant :content "a1"})
        u2 (s/append-entry session {:role :user :content "q2"})
        a2 (s/append-entry session {:role :assistant :content "a2"})]
    (t/is (= (:id q1) (s/common-ancestor-id session (:id a2) (:id q1))))
    (t/is (= (:id u2) (s/common-ancestor-id session (:id a2) (:id u2))))
    (t/is (= (:id a2) (s/common-ancestor-id session (:id a2) (:id a2)))
          "same entry is its own common ancestor")
    (t/is (nil? (s/common-ancestor-id session nil (:id a2)))
          "no old leaf → nil")))
