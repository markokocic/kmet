(ns kmet.libs.test-http
  "Unit + integration tests for kmet.libs.http — the single outbound HTTP
   boundary (proxy parsing, transport selection, native/curl parity,
   structured errors, cancellation)."
  (:require [clojure.string :as str]
            [clojure.test :as t]
            [kmet.libs.http :as http]))

;; ─── Local test server (java.net.ServerSocket, no external deps) ──────────

(defn- respond
  "Write an HTTP/1.1 response to socket S."
  [s status body hdrs]
  (let [b (.getBytes body)
        h (apply str (map (fn [[k v]] (str k ": " v "\r\n")) hdrs))
        head (str "HTTP/1.1 " status " X\r\n" h
                  "Content-Length: " (count b) "\r\n\r\n")]
    (.write (.getOutputStream s) (.getBytes head))
    (.write (.getOutputStream s) b)
    (.flush (.getOutputStream s))))

(defn- read-request
  "Read [req-line headers-map body-reader] off socket S: the request line,
   the headers, and the SAME BufferedReader that consumed the header block
   (a fresh reader on the raw stream would lose buffered body bytes)."
  [s]
  (let [rdr (java.io.BufferedReader.
             (java.io.InputStreamReader. (.getInputStream s)))
        req-line (.readLine rdr)]
    (loop [m {}]
      (let [l (.readLine rdr)]
        (if (seq l)
          (recur (if-let [[_ k v] (re-matches #"^([^:]+):\s*(.*)" l)]
                   (assoc m (str/lower-case k) v)
                   m))
          [req-line m rdr])))))

(defn- start-server
  "A one-shot test server: each connection is handled by HANDLER (fn [s
   req-line headers rdr]) and closed — RDR is the BufferedReader that
   consumed the header block (read the body from it). Returns
   [base-url close-fn]."
  [handler]
  (let [ss (java.net.ServerSocket. 0)
        port (.getLocalPort ss)
        _ (doto (Thread.
                 (fn []
                   (try
                     (loop []
                       (let [s (.accept ss)
                             [req-line headers rdr] (read-request s)]
                         (handler s req-line headers rdr)
                         (try (.close s) (catch Exception _ nil)))
                       (when-not (.isClosed ss) (recur)))
                     (catch Exception _ nil))))
            (.setDaemon true)
            (.start))]
    [(str "http://127.0.0.1:" port)
     (fn [] (try (.close ss) (catch Exception _ nil)))]))

;; ─── Proxy parsing (ported from test_proxy.clj) ────────────────────────────

(t/deftest test-proxy-module-loaded
  (t/is (fn? http/proxy-for-url))
  (t/is (fn? http/curl-proxy?)))

(t/deftest test-proxy-selection
  (let [env {"HTTPS_PROXY" "http://proxy.corp:8080"
             "ALL_PROXY" "socks5://localhost:2080"
             "NO_PROXY" "localhost,127.0.0.1,.trelleborg.com,172.30.208.0/20"}]
    (t/is (= "http://proxy.corp:8080"
             (:url (http/proxy-for-url "https://api.openai.com" env))))
    (t/is (= "socks5://localhost:2080"
             (:url (http/proxy-for-url "http://example.com" env))))
    (t/is (nil? (http/proxy-for-url "https://localhost:9999" env)))
    (t/is (nil? (http/proxy-for-url "https://intra.trelleborg.com" env)))
    (t/is (nil? (http/proxy-for-url "https://172.30.210.5" env)))
    (t/is (nil? (http/proxy-for-url "https://trelleborg.com:8443" env)))))

(t/deftest test-proxy-lowercase-env
  (let [env {"https_proxy" "http://proxy.corp:8080"
             "all_proxy" "socks5://localhost:2080"}]
    (t/is (= "http://proxy.corp:8080"
             (:url (http/proxy-for-url "https://api.openai.com" env))))
    (t/is (= "socks5://localhost:2080"
             (:url (http/proxy-for-url "http://example.com" env))))))

(t/deftest test-proxy-socks-env
  (let [env {"socks_proxy" "localhost:2080"}]
    (t/is (= "socks5h" (:scheme (http/proxy-for-url "https://api.deepseek.com" env))))
    (t/is (= 2080 (:port (http/proxy-for-url "https://api.deepseek.com" env))))
    (t/is (= "socks5h://localhost:2080"
             (:url (http/proxy-for-url "https://api.deepseek.com" env))))
    (t/is (= "socks5h://localhost:2080"
             (:url (http/proxy-for-url "http://example.com" env)))))
  (t/is (= "socks5" (:scheme (http/proxy-for-url "https://x.com"
                                                 {"SOCKS_PROXY" "socks5://localhost:2080"})))))

(t/deftest test-no-proxy
  (t/is (http/no-proxy-match? ["localhost"] "localhost" 80))
  (t/is (http/no-proxy-match? [".example.com"] "www.example.com" 443))
  (t/is (http/no-proxy-match? ["example.com:8080"] "example.com" 8080))
  (t/is (not (http/no-proxy-match? ["example.com:8080"] "example.com" 9090)))
  (t/is (http/no-proxy-match? ["172.30.208.0/20"] "172.30.210.5" 80))
  (t/is (http/no-proxy-match? ["*"] "anything.example" 80))
  (t/is (http/no-proxy-match? ["[::1]"] "::1" 80)))

;; ─── Native transport (java.net.http) ─────────────────────────────────────

(t/deftest test-native-get
  (let [[base close] (start-server
                      (fn [s _ _ _] (respond s "200 OK" "hello" {"X-Custom" "abc"})))]
    (try
      (let [r (http/get (str base "/") {})]
        (t/is (= 200 (:status r)))
        (t/is (= "hello" (:body r)))
        (t/is (= "abc" (get (:headers r) "x-custom"))))
      (finally (close)))))

(t/deftest test-native-headers-lowercased
  (let [[base close] (start-server
                      (fn [s _ _ _] (respond s "200 OK" "ok" {"X-Custom" "abc"})))]
    (try
      (t/is (= {"content-length" "2" "x-custom" "abc"}
               (:headers (http/get (str base "/") {}))))
      (finally (close)))))

(t/deftest test-native-method-and-body
  (let [[base close] (start-server
                      (fn [s req-line headers _]
                        (respond s "200 OK"
                                 (str req-line "|" (get headers "content-type" ""))
                                 {})))]
    (try
      (let [r (http/post (str base "/x")
                         {:headers {"Content-Type" "application/json"}
                          :body "{}"})]
        (t/is (str/starts-with? (:body r) "POST /x"))
        (t/is (str/ends-with? (:body r) "|application/json")))
      (finally (close)))))

(t/deftest test-native-json-body-encoding
  (let [[base close] (start-server
                      (fn [s _ headers rdr]
                        (let [len (Long/parseLong (get headers "content-length" "0"))
                              buf (char-array len)]
                          (.read rdr buf)
                          (respond s "200 OK" (String. buf) {}))))]
    (try
      (let [r (http/post (str base "/x") {:body {"a" 1}})]
        (t/is (= "{\"a\":1}" (:body r))))
      (finally (close)))))

(t/deftest test-native-request-json
  (let [[base close] (start-server
                      (fn [s _ _ _] (respond s "200 OK" "{\"a\":1}" {})))]
    (try
      (t/is (= {:a 1} (:body (http/request-json (str base "/x")))))
      (finally (close)))))

(t/deftest test-native-throw-true
  (let [[base close] (start-server
                      (fn [s _ _ _] (respond s "400 Bad Request" "oops" {"X-Custom" "abc"})))]
    (try
      (let [e (try (http/get (str base "/") {}) (catch Exception e e))]
        (t/is (= :http-error (:type (ex-data e))))
        (t/is (= 400 (:status (ex-data e))))
        (t/is (= "oops" (:body (ex-data e))))
        (t/is (= "abc" (get (:headers (ex-data e)) "x-custom"))))
      (finally (close)))))

(t/deftest test-native-throw-false
  (let [[base close] (start-server
                      (fn [s _ _ _] (respond s "400 Bad Request" "oops" {})))]
    (try
      (let [r (http/get (str base "/") {:throw? false})]
        (t/is (= 400 (:status r)))
        (t/is (= "oops" (:body r))))
      (finally (close)))))

(t/deftest test-native-bytes
  (let [[base close] (start-server
                      (fn [s _ _ _] (respond s "200 OK" "ABCDEFGHIJKLMNOP" {})))]
    (try
      (let [r (http/get (str base "/") {:as :bytes})
            expected (.getBytes "ABCDEFGHIJKLMNOP" "UTF-8")]
        (t/is (= 16 (alength (:body r))))
        (t/is (java.util.Arrays/equals expected (:body r))))
      (finally (close)))))

(t/deftest test-native-stream
  (let [[base close] (start-server
                      (fn [s _ _ _] (respond s "200 OK" "streamed" {})))]
    (try
      (let [r (http/get (str base "/") {:as :stream})]
        (t/is (= 200 (:status r)))
        (t/is (= "streamed" (slurp (:body r))))
        (http/close! r))
      (finally (close)))))

(t/deftest test-native-transport-error
  (let [e (try (http/get "http://127.0.0.1:1" {}) (catch Exception e e))]
    (t/is (= :transport-error (:type (ex-data e))))
    (t/is (str/includes? (ex-message e) "network error"))))

(t/deftest test-native-timeout-ms
  (let [[base close] (start-server
                      (fn [s _ _ _]
                        (Thread/sleep 5000)
                        (respond s "200 OK" "late" {})))]
    (try
      (let [e (try (http/get (str base "/") {:timeout 200}) (catch Exception e e))]
        (t/is (= :transport-error (:type (ex-data e))))
        (t/is (str/includes? (ex-message e) "network error")))
      (finally (close)))))

(t/deftest test-native-follow-redirects
  ;; one server: /start → 302 Location: /final; /final → 200 with the
  ;; request line echoed. A followed redirect issues both requests.
  (let [[base close] (start-server
                      (fn [s req-line _ _]
                        (if (str/includes? req-line "/start")
                          (let [b (.getBytes "moved")
                                head (str "HTTP/1.1 302 Found\r\n"
                                          "Location: /final\r\n"
                                          "Content-Length: " (count b) "\r\n\r\n")]
                            (.write (.getOutputStream s) (.getBytes head))
                            (.write (.getOutputStream s) b)
                            (.flush (.getOutputStream s)))
                          (respond s "200 OK" req-line {}))))]
    (try
      (let [r (http/get (str base "/start") {:follow-redirects :normal})]
        (t/is (= 200 (:status r)))
        (t/is (str/includes? (:body r) "/final")))
      (finally (close)))))

;; ─── Curl transport (SOCKS/https proxies) — needs curl on PATH ─────────────

;; ─── Local SOCKS5 proxy (minimal RFC 1928 server) ─────────────────────────
;; A real SOCKS5 proxy so the curl transport (SOCKS/https-scheme proxies
;; route through curl) can actually reach the target through it. The proxy
;; accepts a single connection: reads the client greeting (no-auth),
;; replies with the chosen method, reads the connect request, connects to
;; the target, and then bidirectional-pumps bytes until both sides close.

(defn- pump
  "Copy bytes between IN and OUT until EOF, then half-close the other
   direction (sockets are full-duplex; the proxy must keep pumping the
   reverse direction after one side finishes)."
  [in out]
  (try
    (let [buf (byte-array 8192)]
      (loop []
        (let [n (.read in buf)]
          (when (pos? n)
            (.write out buf 0 n)
            (.flush out)
            (recur)))))
    (catch Exception _ nil))
  (try (.shutdownOutput out) (catch Exception _ nil)))

(defn- read-addr
  "Read a SOCKS5 address of ATYP from DIS; returns the host string."
  [dis atyp]
  (case atyp
    1 (let [b (byte-array 4)] (.readFully dis b)
           (str/join "." (map #(bit-and % 0xff) b)))
    3 (let [len (.read dis)
            b (byte-array len)] (.readFully dis b)
           (String. b "UTF-8"))
    4 (let [b (byte-array 16)
            words (map (fn [i]
                         (+ (* (bit-and (nth b (* 2 i)) 0xff) 256)
                            (bit-and (nth b (inc (* 2 i))) 0xff)))
                       (range 8))]
        (.readFully dis b)
        (str/join ":" (map #(format "%02x" %) words)))
    (throw (Exception. "bad atyp"))))

(defn- socks5-handshake
  "Serve SOCKS5 connect requests on CLIENT: performs the no-auth handshake,
   connects to the requested target, replies success, and pumps bytes both
   ways until both sides close. Loops for the next request on the same
   connection (curl reuses the proxy connection across redirect hops).
   Returns nil on protocol failure."
  [client]
  (let [dis (java.io.DataInputStream. (.getInputStream client))
        out (.getOutputStream client)]
    (try
      (loop []
        (let [v (.read dis)]
          (when-not (or (neg? v) (not= 5 v))
            (let [nmethods (.read dis)]
              (dotimes [_ nmethods] (.read dis))
              (.write out (byte-array [5 0]))
              (.flush out)
              (let [v (.read dis)
                    _ (when (and (not (neg? v)) (not= 5 v))
                        (throw (Exception. "bad version")))
                    cmd (.read dis)
                    _ (.read dis) ;; reserved
                    atyp (.read dis)
                    host (read-addr dis atyp)
                    port (let [hi (.read dis) lo (.read dis)]
                           (+ (* hi 256) lo))]
                (when (not= 1 cmd) (throw (Exception. "not a connect")))
                (let [target (java.net.Socket. host port)
                      t-in (.getInputStream target)
                      t-out (.getOutputStream target)
                      c-in (.getInputStream client)]
                  (.write out (byte-array [5 0 0 1 127 0 0 1 0 0]))
                  (.flush out)
                  (let [p1 (doto (Thread. #(pump c-in t-out)) (.setDaemon true))
                        p2 (doto (Thread. #(pump t-in out)) (.setDaemon true))]
                    (.start p1)
                    (.start p2)
                    ;; wait only for the target→client direction: when the
                    ;; target closes (response done), kill the client side
                    ;; too — curl sees the tunnel die and opens a fresh
                    ;; proxy connection for the next hop (redirect), which
                    ;; the accept loop serves. Joining p1 would block
                    ;; forever: curl keeps the connection open for reuse.
                    (.join p2)
                    (try (.close client) (catch Exception _ nil))
                    (try (.close target) (catch Exception _ nil)))
                  (recur)))))))
      (catch Exception _ nil)
      (finally (try (.close client) (catch Exception _ nil))))))

(defn- start-socks-proxy
  "Start a SOCKS5 proxy on an ephemeral port; returns [port stop-fn]. Each
   connection is handed off to a daemon thread that runs the handshake and
   pumps bytes (so the test server can serve multiple requests)."
  []
  (let [ss (java.net.ServerSocket. 0)
        port (.getLocalPort ss)
        _ (doto (Thread.
                 (fn []
                   (try
                     (loop []
                       (let [s (.accept ss)]
                         (doto (Thread. (fn [] (socks5-handshake s)))
                           (.setDaemon true)
                           (.start)))
                       (when-not (.isClosed ss) (recur)))
                     (catch Exception _ nil))))
            (.setDaemon true)
            (.start))]
    [port (fn [] (try (.close ss) (catch Exception _ nil)))]))

(defn- with-socks-proxy
  "Run F with a SOCKS5 proxy env var set (so requests route through curl).
   Starts a real local SOCKS5 proxy; requests through it prove the curl
   transport works end-to-end."
  [f]
  (let [[port stop] (start-socks-proxy)]
    (try
      (with-redefs [http/proxy-for-url
                    (fn [_] {:scheme "socks5h" :host "127.0.0.1" :port port
                             :url (str "socks5h://127.0.0.1:" port)})]
        (f))
      (finally (stop)))))

(t/deftest test-curl-missing
  (with-redefs [http/curl-available? (delay false)]
    (let [e (try (http/get "http://x" {:proxy {:scheme "socks5h" :host "h" :port 1
                                               :url "socks5h://h:1"}})
                 (catch Exception e e))]
      (t/is (= :curl-not-found (:type (ex-data e)))))))

(t/deftest test-curl-stream
  (let [[base close] (start-server
                      (fn [s _ _ _] (respond s "200 OK" "streamed" {})))]
    (try
      (with-socks-proxy
        (fn []
          (let [r (http/get (str base "/") {:as :stream})]
            (t/is (= 200 (:status r)))
            (t/is (= "streamed" (slurp (:body r))))
            (http/close! r))))
      (finally (close)))))

(t/deftest test-curl-throw-false
  (let [[base close] (start-server
                      (fn [s _ _ _] (respond s "400 Bad Request" "oops" {})))]
    (try
      (with-socks-proxy
        (fn []
          (let [r (http/get (str base "/") {:throw? false})]
            (t/is (= 400 (:status r)))
            (t/is (= "oops" (:body r))))))
      (finally (close)))))

(t/deftest test-curl-direct-proxy-map
  ;; an explicit parsed-proxy map routes through curl directly (no env)
  (let [[base close] (start-server
                      (fn [s _ _ _] (respond s "200 OK" "via-proxy" {})))
        [port stop] (start-socks-proxy)]
    (try
      (let [r (http/get (str base "/")
                        {:proxy {:scheme "socks5h" :host "127.0.0.1" :port port
                                 :url (str "socks5h://127.0.0.1:" port)}})]
        (t/is (= 200 (:status r)))
        (t/is (= "via-proxy" (:body r))))
      (finally (stop) (close)))))

(t/deftest test-curl-bytes
  (let [[base close] (start-server
                      (fn [s _ _ _] (respond s "200 OK" "ABCDEFGHIJKLMNOP" {})))]
    (try
      (with-socks-proxy
        (fn []
          (let [r (http/get (str base "/") {:as :bytes})
                expected (.getBytes "ABCDEFGHIJKLMNOP" "UTF-8")]
            (t/is (= 200 (:status r)))
            (t/is (java.util.Arrays/equals expected (:body r))))))
      (finally (close)))))

(t/deftest test-curl-follow-redirects
  (let [[base close] (start-server
                      (fn [s req-line _ _]
                        (if (str/includes? req-line "/start")
                          (let [b (.getBytes "moved")
                                head (str "HTTP/1.1 302 Found\r\n"
                                          "Location: /final\r\n"
                                          "Content-Length: " (count b) "\r\n\r\n")]
                            (.write (.getOutputStream s) (.getBytes head))
                            (.write (.getOutputStream s) b)
                            (.flush (.getOutputStream s)))
                          (respond s "200 OK" req-line {}))))]
    (try
      (with-socks-proxy
        (fn []
          (let [r (http/get (str base "/start") {:follow-redirects :normal})]
            (t/is (= 200 (:status r)))
            (t/is (str/includes? (:body r) "/final")))))
      (finally (close)))))

(t/deftest test-native-follow-redirects-default
  ;; Absent :follow-redirects follows (the documented default).
  (let [[base close] (start-server
                      (fn [s req-line _ _]
                        (if (str/includes? req-line "/start")
                          (let [b (.getBytes "moved")
                                head (str "HTTP/1.1 302 Found\r\n"
                                          "Location: /final\r\n"
                                          "Content-Length: " (count b) "\r\n\r\n")]
                            (.write (.getOutputStream s) (.getBytes head))
                            (.write (.getOutputStream s) b)
                            (.flush (.getOutputStream s)))
                          (respond s "200 OK" req-line {}))))]
    (try
      (let [r (http/get (str base "/start") {})]
        (t/is (= 200 (:status r)))
        (t/is (str/includes? (:body r) "/final")))
      (finally (close)))))

(t/deftest test-native-no-follow
  ;; Explicit disable is honored on the native path (per-mode clients).
  (let [[base close] (start-server
                      (fn [s req-line _ _]
                        (if (str/includes? req-line "/start")
                          (let [b (.getBytes "moved")
                                head (str "HTTP/1.1 302 Found\r\n"
                                          "Location: /final\r\n"
                                          "Content-Length: " (count b) "\r\n\r\n")]
                            (.write (.getOutputStream s) (.getBytes head))
                            (.write (.getOutputStream s) b)
                            (.flush (.getOutputStream s)))
                          (respond s "200 OK" req-line {}))))]
    (try
      (doseq [fr [:never false]]
        (let [r (http/get (str base "/start") {:throw? false :follow-redirects fr})]
          (t/is (= 302 (:status r)) (str "expected no follow for " (pr-str fr)))))
      (finally (close)))))

(t/deftest test-curl-follow-redirects-default
  ;; Absent :follow-redirects must follow, per the documented default
  ;; (regression: curl-argv only sent -L when the key was present, so proxied
  ;; downloads of redirecting URLs failed with the 302 status).
  (let [[base close] (start-server
                      (fn [s req-line _ _]
                        (if (str/includes? req-line "/start")
                          (let [b (.getBytes "moved")
                                head (str "HTTP/1.1 302 Found\r\n"
                                          "Location: /final\r\n"
                                          "Content-Length: " (count b) "\r\n\r\n")]
                            (.write (.getOutputStream s) (.getBytes head))
                            (.write (.getOutputStream s) b)
                            (.flush (.getOutputStream s)))
                          (respond s "200 OK" req-line {}))))]
    (try
      (with-socks-proxy
        (fn []
          (let [r (http/get (str base "/start") {})]
            (t/is (= 200 (:status r)))
            (t/is (str/includes? (:body r) "/final")))))
      (finally (close)))))

(t/deftest test-curl-redirect-slow-second-hop
  ;; Hop 1's 302 sits alone in the dump-header file while a slow hop 2 is
  ;; still in flight (GitHub releases behind a proxy behave exactly like
  ;; this): the reported status must be the FINAL hop's. Without the
  ;; wait-for-final-headers loop this returns 302.
  (let [[base close] (start-server
                      (fn [s req-line _ _]
                        (if (str/includes? req-line "/start")
                          (let [b (.getBytes "moved")
                                head (str "HTTP/1.1 302 Found\r\n"
                                          "Location: /final\r\n"
                                          "Content-Length: " (count b) "\r\n\r\n")]
                            (.write (.getOutputStream s) (.getBytes head))
                            (.write (.getOutputStream s) b)
                            (.flush (.getOutputStream s)))
                          (do (Thread/sleep 2000)
                              (respond s "200 OK" "final-body" {})))))]
    (try
      (with-socks-proxy
        (fn []
          (let [r (http/get (str base "/start") {})]
            (t/is (= 200 (:status r)))
            (t/is (str/includes? (:body r) "final-body")))))
      (finally (close)))))

(t/deftest test-curl-no-follow
  ;; Explicit disable is honored on the curl path (no -L).
  (let [[base close] (start-server
                      (fn [s req-line _ _]
                        (if (str/includes? req-line "/start")
                          (let [b (.getBytes "moved")
                                head (str "HTTP/1.1 302 Found\r\n"
                                          "Location: /final\r\n"
                                          "Content-Length: " (count b) "\r\n\r\n")]
                            (.write (.getOutputStream s) (.getBytes head))
                            (.write (.getOutputStream s) b)
                            (.flush (.getOutputStream s)))
                          (respond s "200 OK" req-line {}))))]
    (try
      (with-socks-proxy
        (fn []
          (doseq [fr [:never false]]
            (let [r (http/get (str base "/start") {:throw? false :follow-redirects fr})]
              (t/is (= 302 (:status r)) (str "expected no follow for " (pr-str fr)))))))
      (finally (close)))))

(t/deftest test-curl-timeout-ms
  (let [[base close] (start-server
                      (fn [s _ _ _]
                        (Thread/sleep 5000)
                        (respond s "200 OK" "late" {})))]
    (try
      (with-socks-proxy
        (fn []
          (let [e (try (http/get (str base "/") {:timeout 300}) (catch Exception e e))]
            (t/is (= :transport-error (:type (ex-data e)))))))
      (finally (close)))))

(t/deftest test-curl-compression
  ;; --compressed: a gzip Content-Encoding response arrives decompressed
  (let [[base close] (start-server
                      (fn [s _ _ _]
                        (let [body "hello gzip world"
                              bos (java.io.ByteArrayOutputStream.)]
                          (with-open [gz (java.util.zip.GZIPOutputStream. bos)]
                            (.write gz (.getBytes body "UTF-8")))
                          (let [b (.toByteArray bos)
                                head (str "HTTP/1.1 200 OK\r\n"
                                          "Content-Encoding: gzip\r\n"
                                          "Content-Length: " (count b) "\r\n\r\n")]
                            (.write (.getOutputStream s) (.getBytes head "ISO-8859-1"))
                            (.write (.getOutputStream s) b)
                            (.flush (.getOutputStream s))))))]
    (try
      (with-socks-proxy
        (fn []
          (let [r (http/get (str base "/") {})]
            (t/is (= "hello gzip world" (:body r))))))
      (finally (close)))))

(t/deftest test-curl-abort
  ;; abort! must kill the curl process tree (the sse read loop's cancel
  ;; path); close! then reaps/untracks. With the cancel signal fired,
  ;; close! skips the mid-stream transport-error report.
  (let [[base close] (start-server
                      (fn [s _ _ _]
                        (Thread/sleep 10000)
                        (respond s "200 OK" "never" {})))]
    (try
      (with-socks-proxy
        (fn []
          (let [signal (atom false)
                r (http/get (str base "/") {:as :stream :signal signal})]
            (t/is (= 200 (:status r)))
            (reset! signal true)
            (http/abort! r)
            (http/close! r) ;; must not throw (signal fired)
            (t/is (nil? (http/close! r)) "close! returns nil"))))
      (finally (close)))))

(t/deftest test-curl-close-early
  ;; a stream whose body is truncated mid-transfer must surface as a
  ;; transport failure from close! (not a false clean EOF): the server
  ;; sends Content-Length: 100 but only 10 bytes, then keeps the socket
  ;; open (never completes the body). The client closes the stream early;
  ;; curl's stdout write fails → exit 23 → close! reports it. The server
  ;; thread stays blocked but is a daemon.
  (let [[base close] (start-server
                      (fn [s _ _ _]
                        (let [b (.getBytes "streamed")
                              head (str "HTTP/1.1 200 OK\r\n"
                                        "Content-Length: 100\r\n\r\n")]
                          (.write (.getOutputStream s) (.getBytes head))
                          (.write (.getOutputStream s) b)
                          (.flush (.getOutputStream s))
                          (Thread/sleep 60000))))]
    (try
      (with-socks-proxy
        (fn []
          (let [r (http/get (str base "/") {:as :stream})]
            (t/is (= 200 (:status r)))
            (t/is (thrown-with-msg? Exception #"Proxy request failed"
                                    (http/close! r))
                  "truncated body reported as transport failure"))))
      (finally (close)))))

(t/deftest test-curl-no-credentials-in-argv
  ;; Authorization and proxy credentials must live in the temp config
  ;; file, never in curl's argv (visible via ps). Capture the argv via
  ;; the private curl-argv builder and assert no secret appears.
  (let [captured (atom nil)
        orig-curl-argv @#'http/curl-argv
        [base close] (start-server
                      (fn [s _ _ _] (respond s "200 OK" "ok" {})))
        [port stop] (start-socks-proxy)]
    (try
      (with-redefs [http/curl-argv (fn [url opts config-file header-file]
                                     (reset! captured
                                             (orig-curl-argv url opts config-file header-file))
                                     @captured)]
        (http/get (str base "/")
                  {:proxy {:scheme "socks5h" :host "127.0.0.1" :port port
                           :url (str "socks5h://user:secret@127.0.0.1:" port)}
                   :headers {"Authorization" "Bearer topsecret"}}))
      (let [argv @captured]
        (t/is (some? argv) "curl-argv was called")
        (t/is (not-any? #(str/includes? (str %) "topsecret") argv)
              "Authorization header not in argv")
        (t/is (not-any? #(str/includes? (str %) "secret") argv)
              "proxy credentials not in argv"))
      (finally (stop) (close)))))
