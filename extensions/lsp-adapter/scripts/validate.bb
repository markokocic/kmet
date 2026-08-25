#!/usr/bin/env bb
;; Validation for the lsp-adapter extension (plan §15, Rev 2 - one script):
;;
;;   detect     language invariant (every claimed extension has a
;;              languageId), effective-registry reshaping, root walk-up
;;              fixtures, deno hand-off, outside-cwd attachment
;;   e2e        real subprocess against fake-lsp-server.bb: handshake,
;;              definition/hover/documentSymbol/references/call-hierarchy,
;;              diagnostics push collection, server-request auto-reply
;;              (marker file), broken-stickiness, graceful close
;;
;; Run from the extension directory:
;;   bb -cp ../../src:src scripts/validate.bb

(require '[babashka.fs :as fs]
         '[clojure.java.io :as io]
         '[clojure.string :as str]
         '[extensions.lsp-adapter.detect :as detect]
         '[extensions.lsp-adapter.runtime :as runtime]
         '[extensions.lsp-adapter.tools :as tools])

(def failures (atom 0))

(defn check [label ok]
  (println (if ok "PASS" "FAIL") label)
  (when-not ok (swap! failures inc)))

(def ext-dir (str (fs/cwd)))

(defn tmp-dir
  "Fresh temp dir under cwd (/tmp does not exist on Termux)."
  []
  (let [d (str (fs/path (fs/cwd) (str ".lsp-val-" (System/nanoTime))))]
    (fs/create-dirs d)
    d))
(def fake-server (str ext-dir "/scripts/fake-lsp-server.bb"))

;; -- detect: invariants ---------------------------------------------------

(check "every builtin-claimed extension has a languageId entry"
       (let [claimed (mapcat :extensions detect/builtin-descriptors)]
         (every? #(contains? detect/language-ids %) claimed)))

(check "filename markers resolve"
       (= "cmake" (detect/language-id "/x/CMakeLists.txt")))
(check "plaintext fallback"
       (= "plaintext" (detect/language-id "/x/nope.unknownext")))
(check "clojure family maps to clojure"
       (every? #(= "clojure" (detect/language-id (str "/p/f." %)))
               ["clj" "cljs" "cljc" "edn" "bb"]))

;; -- detect: effective registry -------------------------------------------

(check "no config means all builtins in registry order"
       (= ["clojure-lsp" "typescript-language-server" "pyright-langserver"
           "rust-analyzer" "gopls" "clangd" "ruby-lsp" "bash-language-server"
           "jdtls"]
          (mapv :id (detect/effective-servers {}))))

(let [eff (detect/effective-servers
           {"jdtls" {:disabled true}
            "rust-analyzer" {:request-timeout-ms 60000}
            "clojure-lsp" {:extend-extensions ["janet"]}
            "fennel" {:command ["fennel-ls"] :extensions ["fnl"]}})
      cl (some #(when (= "clojure-lsp" (:id %)) %) eff)
      fx (some #(when (= "fennel" (:id %)) %) eff)]
  (check ":disabled removes a builtin"
         (not-any? #(= "jdtls" %) (mapv :id eff)))
  (check "same-id override keeps builtin argv, folds extend-extensions"
         (and (= ["clojure-lsp"] (:command cl))
              (contains? (:extensions cl) "janet")
              (contains? (:extensions cl) "clj")))
  (check "unknown id with :command appends a custom server last"
         (and (= ["fennel-ls"] (:command fx))
              (= "fennel" (:id (last eff))))))

;; -- detect: roots ---------------------------------------------------------

(let [tmp (tmp-dir)
      proj (str tmp "/proj")
      _ (fs/create-dirs (str proj "/nested/deep"))
      _ (spit (str proj "/deps.edn") "{}")
      _ (spit (str proj "/nested/deep/f.clj") "(+ 1 1)")
      eff (detect/effective-servers {})
      claim (first (detect/claiming eff (str proj "/nested/deep/f.clj") proj))]
  (check "marker walk-up finds the project root"
         (= (fs/canonicalize proj) (:root claim))))

(let [tmp (tmp-dir)
      _ (fs/create-dirs (str tmp "/js"))
      _ (spit (str tmp "/deno.json") "{}")
      _ (spit (str tmp "/js/a.ts") "let x = 1")
      claims (detect/claiming (detect/effective-servers {})
                              (str tmp "/js/a.ts") (str tmp))]
  (check "exclude marker hands the tree off (tsserver not claimed)"
         (empty? claims)))

(let [tmp (tmp-dir)
      outside (str tmp "/outside")
      _ (fs/create-dirs outside)
      _ (spit (str outside "/a.rb") "1")
      claim (first (detect/claiming (detect/effective-servers {})
                                    (str outside "/a.rb") "/definitely/not/cwd"))]
  (check "file outside cwd attaches at its own directory"
         (= (fs/canonicalize outside) (:root claim))))

;; -- e2e against the fake server -------------------------------------------

(defn temp-project []
  (let [dir (tmp-dir)]
    (spit (str dir "/root-marker.txt") "")
    (spit (str dir "/sample.txt") "line zero\nline one\n")
    {:dir dir
     :cfg {:servers {"fake" {:command ["bb" fake-server]
                             :extensions ["txt"]
                             :root-markers ["root-marker.txt"]}}}
     :sample (str dir "/sample.txt")}))

(println "\n-- e2e against fake-lsp-server --")

(let [{:keys [dir cfg sample]} (temp-project)
      marker (str dir "/config-marker")
      cfg (assoc-in cfg [:servers "fake" :command] ["bb" fake-server marker])
      st (runtime/new-state nil cfg)]
  (try
    (check "definition returns a shaped location"
           (let [out (tools/execute st nil
                                    {:operation "definition"
                                     :filePath sample :line 1 :character 1})]
             (and (str/includes? out "\u2500\u2500 fake (")
                  (str/includes? out "sample.txt:5:3"))))
    (check "hover shapes contents"
           (str/includes? (tools/execute st nil {:operation "hover"
                                                 :filePath sample :line 1
                                                 :character 1})
                          "hover docs for fake"))
    (check "documentSymbol flattens with kind names"
           (let [out (tools/execute st nil {:operation "documentSymbol"
                                            :filePath sample})]
             (and (str/includes? out "alpha fn")
                  (str/includes? out "beta var"))))
    (check "references lists two sites"
           (let [out (tools/execute st nil {:operation "references"
                                            :filePath sample :line 1
                                            :character 1})]
             (and (str/includes? out "sample.txt:10:1")
                  (str/includes? out "sample.txt:12:2"))))
    (check "incoming calls compose prepare+incoming"
           (let [out (tools/execute st nil {:operation "incomingCalls"
                                            :filePath sample :line 1
                                            :character 1})]
             (and (str/includes? out "caller fn")
                  (str/includes? out "(1 site)"))))
    (check "server->client configuration probe was auto-answered"
           (let [deadline (+ (System/currentTimeMillis) 5000)]
             (loop []
               (or (.exists (io/file marker))
                   (and (< (System/currentTimeMillis) deadline)
                        (do (Thread/sleep 100) (recur)))))))
    (check "diagnostics push is collected and rendered"
           (let [deadline (+ (System/currentTimeMillis) 5000)]
             (loop []
               (let [out (tools/execute st nil {:operation "diagnostics"
                                                :filePath sample})]
                 (or (str/includes? out "ERROR [1:1] fake diagnostic")
                     (and (< (System/currentTimeMillis) deadline)
                          (do (Thread/sleep 100) (recur))))))))
    (check "broken server fails fast and sticks"
           (let [_ (runtime/set-config!
                    st (assoc-in cfg [:servers "missing"]
                                 {:command ["definitely-not-a-real-bin-xyz"]
                                  :extensions ["zzz"]}))
                 _ (spit (str dir "/m.zzz") "x")
                 attempt (fn []
                           (let [t0 (System/currentTimeMillis)
                                 out (try (tools/execute st nil
                                                         {:operation "definition"
                                                          :filePath (str dir "/m.zzz")
                                                          :line 1 :character 1})
                                          (catch Exception e (ex-message e)))]
                             [out (- (System/currentTimeMillis) t0)]))
                 [out1 ms1] (attempt)
                 [out2 ms2] (attempt)]
             (and (string? out1) (string? out2)
                  (str/includes? out1 "not installed")
                  (str/includes? out2 "not installed")
                  (< ms2 500))))
    (finally
      (runtime/shutdown-all! st)
      (fs/delete-tree dir))))

(check "shutdown-all disconnects everything"
       (let [{:keys [dir cfg sample]} (temp-project)
             st (runtime/new-state nil cfg)]
         (try
           (tools/execute st nil {:operation "hover"
                                  :filePath sample :line 1 :character 1})
           (runtime/shutdown-all! st)
           (empty? @(:conns st))
           (finally (fs/delete-tree dir)))))

(println)
(if (pos? @failures)
  (do (println @failures "FAILURES") (System/exit 1))
  (println "All lsp-adapter validations passed."))
