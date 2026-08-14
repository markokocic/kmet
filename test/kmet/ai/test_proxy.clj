(ns kmet.ai.test-proxy
  (:require [clojure.test :as t]
            [babashka.process :as proc]
            [kmet.ai.proxy :as proxy]
            [kmet.libs.process :as process]))

(t/deftest test-proxy-module-loaded
  (t/is (fn? proxy/proxy-for-url))
  (t/is (fn? proxy/curl-post))
  (t/is (fn? proxy/finish-curl!)))

(t/deftest test-proxy-selection
  (let [env {"HTTPS_PROXY" "http://proxy.corp:8080"
             "ALL_PROXY" "socks5://localhost:2080"
             "NO_PROXY" "localhost,127.0.0.1,.trelleborg.com,172.30.208.0/20"}]
    ;; HTTPS via HTTPS_PROXY
    (t/is (= "http://proxy.corp:8080"
             (:url (proxy/proxy-for-url "https://api.openai.com" env))))
    ;; HTTP falls back to ALL_PROXY
    (t/is (= "socks5://localhost:2080"
             (:url (proxy/proxy-for-url "http://example.com" env))))
    ;; NO_PROXY: exact host, subdomain (leading dot), CIDR block
    (t/is (nil? (proxy/proxy-for-url "https://localhost:9999" env)))
    (t/is (nil? (proxy/proxy-for-url "https://intra.trelleborg.com" env)))
    (t/is (nil? (proxy/proxy-for-url "https://172.30.210.5" env)))
    ;; NO_PROXY host without port bypasses any port
    (t/is (nil? (proxy/proxy-for-url "https://trelleborg.com:8443" env)))))

(t/deftest test-proxy-lowercase-env
  (let [env {"https_proxy" "http://proxy.corp:8080"
             "all_proxy" "socks5://localhost:2080"}]
    (t/is (= "http://proxy.corp:8080"
             (:url (proxy/proxy-for-url "https://api.openai.com" env))))
    (t/is (= "socks5://localhost:2080"
             (:url (proxy/proxy-for-url "http://example.com" env))))))

(t/deftest test-proxy-parse
  (t/is (= "socks5" (:scheme (proxy/proxy-for-url "https://x.com"
                                                  {"ALL_PROXY" "socks5://localhost:2080"}))))
  (t/is (= 2080 (:port (proxy/proxy-for-url "https://x.com"
                                            {"ALL_PROXY" "socks5://localhost:2080"}))))
  ;; scheme-less proxy defaults to http
  (t/is (= "http" (:scheme (proxy/proxy-for-url "https://x.com"
                                                {"HTTPS_PROXY" "proxy.corp:8080"}))))
  ;; bare "socks" scheme normalized to socks5
  (t/is (= "socks5" (:scheme (proxy/proxy-for-url "https://x.com"
                                                  {"ALL_PROXY" "socks://localhost:2080"}))))
  ;; credentials are parsed
  (let [p (proxy/proxy-for-url "https://x.com"
                               {"ALL_PROXY" "socks5://u:pw@localhost:2080"})]
    (t/is (= "u" (:user p)))
    (t/is (= "pw" (:pass p))))
  ;; missing port defaults per scheme (URI.getPort is -1, not nil)
  (t/is (= 1080 (:port (proxy/proxy-for-url "https://x.com"
                                            {"ALL_PROXY" "socks5://localhost"}))))
  (t/is (= "socks5://localhost:1080"
           (:url (proxy/proxy-for-url "https://x.com"
                                      {"ALL_PROXY" "socks5://localhost"}))))
  (t/is (= 80 (:port (proxy/proxy-for-url "https://x.com"
                                          {"HTTPS_PROXY" "proxy.corp"})))))

(t/deftest test-proxy-no-proxy-default-port
  ;; a URL without an explicit port still matches a host:port NO_PROXY entry
  (t/is (nil? (proxy/proxy-for-url "https://intra"
                                   {"HTTPS_PROXY" "http://p:1"
                                    "NO_PROXY" "intra:443"})))
  (t/is (nil? (proxy/proxy-for-url "http://intra"
                                   {"HTTP_PROXY" "http://p:1"
                                    "NO_PROXY" "intra:80"}))))

(t/deftest test-proxy-no-proxy-port
  ;; host:port entries only bypass that port
  (t/is (nil? (proxy/proxy-for-url "http://intra:8080"
                                   {"HTTP_PROXY" "http://p:1"
                                    "NO_PROXY" "intra:8080"})))
  (t/is (= "http://p:1"
           (:url (proxy/proxy-for-url "http://intra:9090"
                                      {"HTTP_PROXY" "http://p:1"
                                       "NO_PROXY" "intra:8080"})))))

(t/deftest test-proxy-no-proxy-star
  (t/is (nil? (proxy/proxy-for-url "https://anything.example"
                                   {"HTTPS_PROXY" "http://p:1"
                                    "NO_PROXY" "*"}))))

(t/deftest test-proxy-curl-route
  (t/is (proxy/curl-proxy? {:scheme "socks5"}))
  (t/is (proxy/curl-proxy? {:scheme "socks5h"}))
  (t/is (proxy/curl-proxy? {:scheme "https"}))
  (t/is (not (proxy/curl-proxy? {:scheme "http"}))))

(t/deftest test-proxy-java-client
  (t/is (some? (proxy/java-client {:scheme "http" :host "localhost" :port 2080}))))

;; ─── Cancellation / process lifecycle (spawns real processes) ─────────────

(t/deftest ^:slow test-proxy-cancel-kills-process
  ;; setsid prefix like curl-post uses: the process becomes a group leader so
  ;; kill-process-tree! can group-kill it (some kill(1) builds report success
  ;; without signaling non-leaders).
  (let [pre @process/setsid-path
        argv (into (if pre [pre] []) ["sleep" "30"])
        p (proc/process argv {:out :discard :err :discard})
        resp {:proc p :pid (-> p :proc .pid)}
        sig (atom true)
        errs (atom [])]
    ;; signal already set: watcher kills within ~200ms, no error reported
    (proxy/watch-cancel! resp sig)
    (Thread/sleep 700)
    (t/is (not (-> p :proc .isAlive)))
    (proxy/finish-curl! resp sig (fn [e] (swap! errs conj e)))
    (t/is (empty? @errs))))

;; ─── curl --max-time follows the request :timeout (pi: timeoutMs) ─────────

(t/deftest test-proxy-curl-argv-max-time
  (let [p {:scheme "socks5" :host "127.0.0.1" :port 1080
           :url "socks5://127.0.0.1:1080"}
        ;; argv may be prefixed with setsid when available — ignore it
        strip-setsid (fn [argv] (if (= (first argv) "setsid") (rest argv) argv))]
    (t/is (some #{"--max-time" "120"}
                (strip-setsid (@#'proxy/curl-argv "https://api.example.com/"
                                                  {:headers {"Authorization" "Bearer x"}}
                                                  p)))
          "no :timeout in opts → curl-timeout-seconds default")
    (t/is (some #{"--max-time" "300"}
                (strip-setsid (@#'proxy/curl-argv "https://api.example.com/"
                                                  {:headers {}
                                                   :timeout 300000}
                                                  p)))
          "request :timeout (ms) drives --max-time (s)")
    (t/is (not-any? #{"--max-time"}
                    (strip-setsid (@#'proxy/curl-argv "https://api.example.com/"
                                                      {:headers {}
                                                       :timeout nil}
                                                      p)))
          "nil request :timeout (disabled) omits --max-time")))
