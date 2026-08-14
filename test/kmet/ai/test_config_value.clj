(ns kmet.ai.test-config-value
  "Phase 6: config-value resolution — env interpolation, $$/$! escapes,
   !command (cached, timeout), missing-env → nil, is-config-value-configured?,
   header resolution (pi resolve-config-value.ts)."
  (:require [clojure.test :as t]
            [kmet.ai.config-value :as cv]))

;; ─── Parsing (pi parseConfigValueTemplate) ─────────────────────────────────

(t/deftest test-parse-config-value
  (t/testing "literal without $"
    (t/is (= {:type :template
              :parts [{:type :literal :value "sk-abc"}]}
             (cv/parse-config-value "sk-abc"))))
  (t/testing "command"
    (t/is (= {:type :command :command "!echo hi"}
             (cv/parse-config-value "!echo hi"))))
  (t/testing "$NAME env part"
    (t/is (= {:type :template :parts [{:type :env :name "FOO"}]}
             (cv/parse-config-value "$FOO"))))
  (t/testing "${NAME} env part"
    (t/is (= {:type :template :parts [{:type :env :name "FOO_BAR"}]}
             (cv/parse-config-value "${FOO_BAR}"))))
  (t/testing "$$ escapes a literal $; $! a literal !"
    (t/is (= {:type :template :parts [{:type :literal :value "$"}]}
             (cv/parse-config-value "$$")))
    (t/is (= {:type :template :parts [{:type :literal :value "!"}]}
             (cv/parse-config-value "$!"))))
  (t/testing "interpolation inside larger literals (pi: ${KEY}_BAR)"
    (t/is (= {:type :template
              :parts [{:type :env :name "KEY_PREFIX"}
                      {:type :literal :value "_BAR"}]}
             (cv/parse-config-value "${KEY_PREFIX}_BAR"))))
  (t/testing "invalid ${name} and bare $ stay literal"
    (t/is (= {:type :template :parts [{:type :literal :value "${1BAD}"}]}
             (cv/parse-config-value "${1BAD}")))
    (t/is (= {:type :template :parts [{:type :literal :value "$"}]}
             (cv/parse-config-value "$")))))

;; ─── Env var name introspection ────────────────────────────────────────────

(t/deftest test-env-var-names
  (t/testing "single reference → name"
    (t/is (= "FOO" (cv/get-config-value-env-var-name "$FOO")))
    (t/is (nil? (cv/get-config-value-env-var-name "${FOO}_BAR")))
    (t/is (nil? (cv/get-config-value-env-var-name "literal")))
    (t/is (nil? (cv/get-config-value-env-var-name "!cmd"))))
  (t/testing "all names, deduped, in order"
    (t/is (= ["A" "B"] (cv/get-config-value-env-var-names "${A}_${B}${A}")))
    (t/is (= [] (cv/get-config-value-env-var-names "literal")))
    (t/is (= [] (cv/get-config-value-env-var-names "!cmd"))))
  (t/testing "missing names"
    (with-redefs [cv/getenv (fn [k] (when (= k "A") "a"))]
      (t/is (= ["B"] (cv/get-missing-config-value-env-var-names "${A}_${B}" nil)))
      (t/is (= [] (cv/get-missing-config-value-env-var-names "${A}" nil))))
    (t/testing "explicit env map takes precedence over process env"
      (with-redefs [cv/getenv (fn [_] nil)]
        (t/is (= [] (cv/get-missing-config-value-env-var-names "${B}" {"B" "b"})))))))

;; ─── Classification (pi isCommandConfigValue / isConfigValueConfigured) ────

(t/deftest test-classification
  (t/is (true? (cv/is-command-config-value? "!op read")))
  (t/is (false? (cv/is-command-config-value? "$FOO")))
  (t/testing "is-config-value-configured?: literals and commands always"
    (with-redefs [cv/getenv (fn [_] nil)]
      (t/is (true? (cv/is-config-value-configured? "sk-literal")))
      (t/is (true? (cv/is-config-value-configured? "!cmd")))))
  (t/testing "is-config-value-configured?: $ENV needs the var"
    (with-redefs [cv/getenv (fn [k] (when (= k "FOO") "v"))]
      (t/is (true? (cv/is-config-value-configured? "$FOO")))
      (t/is (false? (cv/is-config-value-configured? "${MISSING}")))
      (t/is (false? (cv/is-config-value-configured? "${MISSING}_x"))))))

;; ─── Resolution (pi resolveConfigValue) ────────────────────────────────────

(t/deftest ^:slow test-resolve-config-value
  (t/testing "literal"
    (t/is (= "sk-abc" (cv/resolve-config-value "sk-abc" nil))))
  (t/testing "$NAME / ${NAME} interpolation (env map first, then process env)"
    (with-redefs [cv/getenv (fn [k] (when (= k "FOO") "proc"))]
      (t/is (= "proc" (cv/resolve-config-value "$FOO" nil)))
      (t/is (= "explicit" (cv/resolve-config-value "$FOO" {"FOO" "explicit"})))
      (t/is (= "pre_SFX" (cv/resolve-config-value "${KEY_PREFIX}_SFX" {"KEY_PREFIX" "pre"})))))
  (t/testing "missing env var → nil"
    (with-redefs [cv/getenv (fn [_] nil)]
      (t/is (nil? (cv/resolve-config-value "$MISSING" nil)))
      (t/is (nil? (cv/resolve-config-value "pre_${MISSING}_post" nil)))))
  (t/testing "$$ / $! escapes"
    (t/is (= "$" (cv/resolve-config-value "$$" nil)))
    (t/is (= "!" (cv/resolve-config-value "$!" nil)))
    (t/is (= "a$b" (cv/resolve-config-value "a$$b" nil))))
  (t/testing "!command executes, stdout trimmed"
    (cv/clear-config-value-cache!)
    (t/is (= "hi" (cv/resolve-config-value "!echo hi" nil)))))

(t/deftest ^:slow test-resolve-config-value-command-failures
  (cv/clear-config-value-cache!)
  (t/testing "non-zero exit → nil"
    (t/is (nil? (cv/resolve-config-value "!false" nil))))
  (t/testing "empty stdout → nil"
    (t/is (nil? (cv/resolve-config-value "!true" nil)))))

(t/deftest test-command-cache
  (cv/clear-config-value-cache!)
  (let [calls (atom 0)]
    (with-redefs [cv/execute-command-uncached
                  (fn [_] (swap! calls inc) "cached-result")]
      (t/is (= "cached-result" (cv/resolve-config-value "!cmd" nil)))
      (t/is (= "cached-result" (cv/resolve-config-value "!cmd" nil)))
      (t/is (= 1 @calls) "second resolution hits the cache")
      (t/testing "clear-config-value-cache! forces re-execution"
        (cv/clear-config-value-cache!)
        (t/is (= "cached-result" (cv/resolve-config-value "!cmd" nil)))
        (t/is (= 2 @calls))))))

(t/deftest ^:slow test-resolve-config-value-or-throw
  (with-redefs [cv/getenv (fn [_] nil)]
    (t/testing "resolvable → value"
      (t/is (= "sk-1" (cv/resolve-config-value-or-throw "sk-1" "API key for provider \"x\"" nil))))
    (t/testing "missing single env var names it"
      (let [e (try (cv/resolve-config-value-or-throw "$MISSING" "API key for provider \"x\"" nil)
                   (catch Exception e e))]
        (t/is (= "Failed to resolve API key for provider \"x\" from environment variable: MISSING"
                 (ex-message e)))))
    (t/testing "missing multiple env vars listed"
      (let [e (try (cv/resolve-config-value-or-throw "${A}_${B}" "key" nil)
                   (catch Exception e e))]
        (t/is (= "Failed to resolve key from environment variables: A, B"
                 (ex-message e)))))
    (t/testing "failed command names it"
      (cv/clear-config-value-cache!)
      (let [e (try (cv/resolve-config-value-or-throw "!false" "key" nil)
                   (catch Exception e e))]
        (t/is (= "Failed to resolve key from shell command: false"
                 (ex-message e)))))))

;; ─── Header resolution (pi resolveHeaders / resolveHeadersOrThrow) ─────────

(t/deftest test-resolve-headers
  (with-redefs [cv/getenv (fn [k] (when (= k "K") "v"))]
    (t/testing "values resolve as config values; unresolvable ones drop"
      (t/is (= {"X-Literal" "a" "X-Env" "v"}
               (cv/resolve-headers {"X-Literal" "a" "X-Env" "$K" "X-Drop" "$MISSING"} nil))))
    (t/testing "nil when no headers or all drop"
      (t/is (nil? (cv/resolve-headers nil nil)))
      (t/is (nil? (cv/resolve-headers {"X" "$MISSING"} nil)))
      (t/is (nil? (cv/resolve-headers {} nil))))))

(t/deftest test-resolve-headers-or-throw
  (with-redefs [cv/getenv (fn [_] nil)]
    (t/testing "throws on an unresolvable header, naming it"
      (let [e (try (cv/resolve-headers-or-throw {"X" "$MISSING"} "model \"p/m\"" nil)
                   (catch Exception e e))]
        (t/is (= "Failed to resolve model \"p/m\" header \"X\" from environment variable: MISSING"
                 (ex-message e)))))
    (t/testing "resolves clean maps"
      (t/is (= {"X" "a"} (cv/resolve-headers-or-throw {"X" "a"} "p" nil))))))
