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

(require '[babashka.classes :as bc]
         '[kmet.libs.jsonrpc :as jrpc]
         '[babashka.fs :as fs]
         '[clojure.java.io :as io]
         '[clojure.string :as str]
         '[sci.core :as sci]
         '[extensions.lsp-adapter.detect :as detect]
         '[extensions.lsp-adapter :as entry]
         '[extensions.lsp-adapter.lsp :as lsp]
         '[extensions.lsp-adapter.runtime :as runtime]
         '[extensions.lsp-adapter.tools :as tools]
         '[extensions.lsp-adapter.panel :as panel]
         '[kmet.tui.protocols :as protocols])

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

;; -- uri building ----------------------------------------------------------
;; path->uri must survive two hostile environments: kmet evaluates
;; extension source inside an isolated sci context whose class registry
;; lacks sun.nio.fs.UnixPath (instance-method interop throws "not
;; allowed"), and bb's native image does not reflect Path methods even
;; where the class is registered. The checks below simulate the exact
;; context construction kmet uses.

(let [paths (map #(str (fs/absolutize %))
                 [(str ext-dir "/scripts/validate.bb")
                  (str ext-dir "/a b.clj")
                  (str ext-dir "/hash#tag.clj")
                  (str ext-dir "/quest?.clj")
                  (str ext-dir "/100%.clj")
                  (str ext-dir "/ünï cödé.clj")
                  (str ext-dir "/back\\slash.clj")
                  (str ext-dir "/nested/dir/file.bb")
                  "relative-sample.txt"])]
  (check "path->uri matches Path#toUri on every sample"
         (every? #(= (str (.toUri (fs/path %))) (lsp/path->uri %)) paths))
  (check "uri->path inverts path->uri"
         (every? #(= % (lsp/uri->path (lsp/path->uri %))) paths)))

(let [src (slurp (str ext-dir "/src/extensions/lsp_adapter/lsp.clj"))
      forms (remove #(and (seq? %) (= 'ns (first %)))
                    (read-string (str "[\n" src "\n]")))
      ctx (sci/init
           {:classes (into {}
                           (map (fn [^Class c] [(symbol (.getName c)) {:class c}])
                                (remove #(str/starts-with? (.getName ^Class %) "[")
                                        (bc/all-classes))))
            ;; mirror the kmet bb-imports entries lsp.clj needs
            :imports '{StringBuilder java.lang.StringBuilder
                       Thread java.lang.Thread}
            :namespaces {'babashka.fs (ns-publics 'babashka.fs)
                         'kmet.libs.jsonrpc (ns-publics 'kmet.libs.jsonrpc)}})]
  ;; Establish the aliases inside the ctx's default user ns — do NOT eval
  ;; an (ns ...) form here: nested sci contexts share the global
  ;; current-ns, and switching it derails bb's evaluation of the very
  ;; script we are running.
  (sci/eval-form ctx '(require '[clojure.string :as str]
                               '[babashka.fs :as fs]
                               '[kmet.libs.jsonrpc :as jrpc]))
  (doseq [f forms]
    (sci/eval-form ctx f))
  (check "path->uri survives the extension sci sandbox"
         (let [m (try (sci/eval-form
                       ctx '(let [inputs [(str (fs/cwd) "/plain.clj")
                                          (str (fs/cwd) "/with space.clj")
                                          (str (fs/cwd) "/ünï cödé.clj")]]
                              (zipmap inputs (map path->uri inputs))))
                      (catch Exception _ nil))
               spaced (str ext-dir "/with space.clj")]
           (and (map? m)
                (= (get m spaced) (lsp/path->uri spaced))
                (str/ends-with? (get m spaced "") "/with%20space.clj")
                (str/ends-with? (get m (str ext-dir "/ünï cödé.clj") "")
                                "/%C3%BCn%C3%AF%20c%C3%B6d%C3%A9.clj")))))

(check "handshake failure carries the server's stderr"
       (try
         (lsp/start! {:command ["bb" "-e"
                                "(do (binding [*out* *err*] (println \"boom: no /tmp here\")) (System/exit 1))"]}
                     ext-dir nil 5000)
         false
         (catch Exception e
           (let [data (ex-data e)]
             (and (str/includes? (ex-message e) "boom: no /tmp here")
                  (vector? (:server-stderr data)))))))

(check "connect-stdio does not leak opts into the child's argv"
       (let [c (jrpc/connect-stdio
                {:command ["bb" "-e" "(println (count *command-line-args*))"]
                 :cwd ext-dir})
             line (try (let [rdr (io/reader (:in c))] (.readLine rdr))
                       (catch Exception _ ""))]
         (try (jrpc/close! c) (catch Exception _ nil))
         (= "0" (str/trim (str line)))))

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
      st (runtime/new-state nil cfg)
      changes (atom 0)]
  (runtime/set-on-change! st (fn [] (swap! changes inc)))
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
    (check "on-change hook fired during connects/broken marks"
           (pos? @changes))
    (finally
      (runtime/shutdown-all! st)
      (fs/delete-tree dir))))

(println "\n-- panel --")

(let [proj (tmp-dir)
      _ (spit (str proj "/root-marker.txt") "")
      cfg {:servers {"fake" {:command ["bb" fake-server]
                             :extensions ["txt"]
                             :root-markers ["root-marker.txt"]}}}
      st (runtime/new-state nil cfg)]
  (try
    (runtime/mark-broken! st "fake" ":not installed")
    (let [closed (atom false)
          restarted (atom nil)
          refreshed (atom false)
          comp (panel/make-panel st (fn [_] (reset! closed true))
                                 {:restart-fn (fn [n]
                                                (reset! restarted n)
                                                (runtime/clear-broken! st n))
                                  :refresh-fn (fn [] (reset! refreshed true))})
          rendered (protocols/render comp 70)]
      (check "panel renders rows, icons and hints"
             (and (str/includes? rendered "fake")
                  (str/includes? rendered "\u2717")   ;; broken icon
                  (str/includes? rendered "esc close")))
      ;; selection wraps inside the full registry row count
      (protocols/handle-input comp "\u001b[B")
      (check "selection stays in range after down"
             (and (>= @(:sel comp) 0)
                  (< @(:sel comp) 10)))
      ;; pin selection to the fake row, then restart it
      (let [eff (detect/effective-servers (:servers cfg))
            fake-i (some (fn [[i r]] (when (= "fake" (:id r)) i))
                         (map-indexed vector eff))]
        (reset! (:sel comp) fake-i))
      (protocols/handle-input comp "r")
      (check "restart action fires for the selected server"
             (= "fake" @restarted))
      (check "restart cleared the broken mark"
             (nil? (runtime/broken st "fake")))
      (protocols/handle-input comp "f")
      (check "refresh action fires"
             (true? @refreshed))
      (protocols/handle-input comp "\u001b")
      (check "esc closes the panel"
             (true? @closed)))
    (finally
      (runtime/shutdown-all! st)
      (fs/delete-tree proj))))
(check "shutdown-all disconnects everything"
       (let [{:keys [dir cfg sample]} (temp-project)
             st (runtime/new-state nil cfg)]
         (try
           (tools/execute st nil {:operation "hover"
                                  :filePath sample :line 1 :character 1})
           (runtime/shutdown-all! st)
           (empty? @(:conns st))
           (finally (fs/delete-tree dir)))))

(println "\n-- footer --")

(let [cfg {:servers {"fake" {:command ["bb" fake-server]
                             :extensions ["txt"]
                             :root-markers ["root-marker.txt"]}}}
      st (runtime/new-state nil cfg)
      f #'entry/status-text]
  (check "idle fleet stays out of the footer"
         (nil? (f st)))
  (swap! (:conns st) assoc ["fake" (str (tmp-dir))]
         {:client nil :name "fake" :root (tmp-dir)
          :docs (atom {}) :diags (atom {})})
  (check "connected shows connected/total"
         (= "LSP 1/1" (f st)))
  (check "footer clears when nothing is configured"
         (nil? (do (runtime/set-config! st {})
                   (f st)))))

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
