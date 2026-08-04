(ns kmet.app.test-keybindings
  (:require [clojure.test :as t]
            [babashka.fs :as fs]
            [kmet.app.keybindings :as kb]
            [kmet.tui.keybindings :as tui-kb]))

(defn- temp-agent-dir
  "Create a throwaway agent dir under target/ (with optional keybindings.edn
   content) and return its path."
  ([]
   (temp-agent-dir nil))
  ([content]
   (let [dir (str (fs/absolutize (fs/file "target" (str "test-kb-" (System/currentTimeMillis)))))]
     (fs/create-dirs dir)
     (when content
       (spit (str dir "/keybindings.edn") content))
     dir)))

(defn- cleanup
  [agent-dir]
  (fs/delete-tree agent-dir))

(defn- kb-file
  [agent-dir]
  (str agent-dir "/keybindings.edn"))

(t/deftest test-defaults-when-no-file
  (let [dir (temp-agent-dir)
        kmgr (kb/create-agent-keybindings-manager dir)]
    (try
      (t/is (= ["ctrl+o"] (tui-kb/get-keys kmgr "app.tools.expand")))
      (t/is (= ["ctrl+t"] (tui-kb/get-keys kmgr "app.thinking.toggle")))
      (finally (cleanup dir)))))

(t/deftest test-persisted-overrides
  (let [dir (temp-agent-dir "{\"app.tools.expand\" \"ctrl+e\"\n \"app.thinking.toggle\" [\"ctrl+t\" \"alt+t\"]}")
        kmgr (kb/create-agent-keybindings-manager dir)]
    (try
      (t/is (= ["ctrl+e"] (tui-kb/get-keys kmgr "app.tools.expand")))
      (t/is (= ["ctrl+t" "alt+t"] (tui-kb/get-keys kmgr "app.thinking.toggle")))
      (t/testing "unset ids keep defaults"
        (t/is (= ["ctrl+d"] (tui-kb/get-keys kmgr "app.exit"))))
      (finally (cleanup dir)))))

(t/deftest test-legacy-name-migration
  (t/testing "legacy camelCase ids migrate to namespaced ids (pi KEYBINDING_NAME_MIGRATIONS)"
    (let [dir (temp-agent-dir "{\"expandTools\" \"ctrl+e\"\n \"toggleThinking\" \"ctrl+y\"}")
          kmgr (kb/create-agent-keybindings-manager dir)]
      (try
        (t/is (= ["ctrl+e"] (tui-kb/get-keys kmgr "app.tools.expand")))
        (t/is (= ["ctrl+y"] (tui-kb/get-keys kmgr "app.thinking.toggle")))
        (finally (cleanup dir)))))
  (t/testing "namespaced id wins when both present"
    (let [dir (temp-agent-dir "{\"expandTools\" \"ctrl+e\"\n \"app.tools.expand\" \"ctrl+x\"}")
          kmgr (kb/create-agent-keybindings-manager dir)]
      (try
        (t/is (= ["ctrl+x"] (tui-kb/get-keys kmgr "app.tools.expand")))
        (finally (cleanup dir))))))

(t/deftest test-invalid-values-dropped
  (let [dir (temp-agent-dir "{\"app.clear\" 42\n \"app.exit\" [\"ctrl+d\" 7]\n \"app.tools.expand\" \"ctrl+e\"}")
        kmgr (kb/create-agent-keybindings-manager dir)]
    (try
      (t/is (= ["ctrl+e"] (tui-kb/get-keys kmgr "app.tools.expand")))
      (t/testing "non-string / non-vector-of-strings values fall back to defaults"
        (t/is (= ["ctrl+c"] (tui-kb/get-keys kmgr "app.clear")))
        (t/is (= ["ctrl+d"] (tui-kb/get-keys kmgr "app.exit"))))
      (finally (cleanup dir)))))

(t/deftest test-missing-or-malformed-file
  (t/testing "missing file yields empty bindings"
    (let [dir (temp-agent-dir)]
      (try
        (t/is (= {} (kb/load-user-bindings (kb-file dir))))
        (finally (cleanup dir)))))
  (t/testing "malformed EDN yields empty bindings, not an exception"
    (let [dir (temp-agent-dir "{not valid edn")]
      (try
        (t/is (= {} (binding [*err* (java.io.StringWriter.)]
                      (kb/load-user-bindings (kb-file dir)))))
        (finally (cleanup dir))))))

(t/deftest test-migrate-keybindings-config-file!
  (t/testing "legacy ids rewritten in place (pi: migrateKeybindingsConfigFile)"
    (let [dir (temp-agent-dir "{\"expandTools\" \"ctrl+e\"}")]
      (try
        (kb/migrate-keybindings-config-file! (kb-file dir))
        (t/is (= "{\"app.tools.expand\" \"ctrl+e\"}\n"
                 (slurp (kb-file dir))))
        (t/is (= ["ctrl+e"] (tui-kb/get-keys (kb/create-agent-keybindings-manager dir)
                                             "app.tools.expand")))
        (finally (cleanup dir)))))
  (t/testing "already-namespaced file is left untouched"
    (let [dir (temp-agent-dir "{\"app.tools.expand\" \"ctrl+e\"}")]
      (try
        (kb/migrate-keybindings-config-file! (kb-file dir))
        (t/is (= "{\"app.tools.expand\" \"ctrl+e\"}" (slurp (kb-file dir))))
        (finally (cleanup dir)))))
  (t/testing "missing file is a no-op"
    (let [dir (temp-agent-dir)]
      (try
        (kb/migrate-keybindings-config-file! (kb-file dir))
        (t/is (not (fs/exists? (kb-file dir))))
        (finally (cleanup dir))))))

(t/deftest test-reload-re-reads-file
  (let [dir (temp-agent-dir "{\"app.tools.expand\" \"ctrl+e\"}")
        prev-global (tui-kb/get-global-keybindings)]
    (try
      (tui-kb/set-global-keybindings! (kb/create-agent-keybindings-manager dir))
      (t/is (= ["ctrl+e"] (tui-kb/get-keys (tui-kb/get-global-keybindings) "app.tools.expand")))
      (spit (kb-file dir) "{\"app.tools.expand\" \"ctrl+shift+e\"}")
      (kb/reload-agent-keybindings! dir)
      (t/is (= ["ctrl+shift+e"] (tui-kb/get-keys (tui-kb/get-global-keybindings) "app.tools.expand")))
      (finally
        (cleanup dir)
        (tui-kb/set-global-keybindings! prev-global)))))
