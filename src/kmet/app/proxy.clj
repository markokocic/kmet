(ns kmet.app.proxy
  "Proxy support for LLM HTTP calls. Reads the standard proxy environment
   variables (HTTPS_PROXY / HTTP_PROXY / ALL_PROXY / NO_PROXY, upper- and
   lowercase) and routes requests accordingly, with curl's no_proxy semantics
   (subdomains, host:port entries, CIDR blocks).
   java.net.http — the engine under babashka.http-client — supports only plain
   HTTP proxies, so SOCKS (socks4/4a/5/5h) and https-scheme (TLS-speaking)
   proxies are transported via curl."
  (:require [babashka.http-client :as http]
            [babashka.process :as proc]
            [cheshire.core :as json]
            [clojure.string :as str]
            [kmet.libs.process :as process]))

;; ─── Proxy parsing ────────────────────────────────────────────────────────

(defn- parse-proxy-url
  "Parse a proxy URL string into {:scheme :host :port :url :user :pass} or
   nil when unparsable. A missing scheme defaults to http; a missing port to
   the scheme default (1080 for SOCKS)."
  [s]
  (try
    (let [s (str/trim s)
          s (if (re-find #"^[a-zA-Z][a-zA-Z0-9+.-]*://" s) s (str "http://" s))
          uri (java.net.URI. s)
          scheme (or (.getScheme uri) "http")
          scheme (if (= scheme "socks") "socks5" scheme)
          host (.getHost uri)
          port (let [p (.getPort uri)]
                 (or (when (pos? p) p)
                     (case scheme
                       "http" 80
                       "https" 443
                       ("socks4" "socks4a" "socks5" "socks5h") 1080
                       8080)))
          userinfo (.getUserInfo uri)
          [user pass] (when (and userinfo (seq userinfo))
                        (str/split userinfo #":" 2))
          host-str (if (str/includes? host ":") (str "[" host "]") host)
          url (str scheme "://"
                   (when user (str user (when pass (str ":" pass)) "@"))
                   host-str ":" port)]
      (when (seq host)
        {:scheme scheme :host host :port port :user user :pass pass :url url}))
    (catch Exception _ nil)))

(defn- env-first
  "First non-blank value of the given env var names (upper- then lowercase,
   like curl)."
  [env & names]
  (some (fn [n] (let [v (get env n)] (when (seq v) v))) names))

(defn- proxy-config
  [env]
  {:https (some-> (env-first env "HTTPS_PROXY" "https_proxy") parse-proxy-url)
   :http (some-> (env-first env "HTTP_PROXY" "http_proxy") parse-proxy-url)
   :all (some-> (env-first env "ALL_PROXY" "all_proxy") parse-proxy-url)
   :no-proxy (when-let [v (env-first env "NO_PROXY" "no_proxy")]
               (mapv str/trim (str/split v #",")))})

;; ─── no_proxy matching (curl semantics) ───────────────────────────────────

(defn- host-match?
  "curl-style hostname matching: `*` matches everything; an entry matches the
   host and its subdomains; a leading dot is ignored (.example.com matches
   example.com and www.example.com)."
  [entry host]
  (let [entry (str/lower-case (if (str/starts-with? entry ".")
                                (subs entry 1)
                                entry))
        host (str/lower-case host)]
    (or (= entry "*")
        (= host entry)
        (str/ends-with? host (str "." entry)))))

(defn- ipv4->int
  "IPv4 dotted string to 32-bit integer."
  [ip]
  (reduce (fn [acc o] (+ (* acc 256) o)) 0
          (map #(Integer/parseInt %) (str/split ip #"\."))))

(defn- cidr-match?
  "True when ip is inside the CIDR network (e.g. 172.30.208.0/20)."
  [ip cidr]
  (try
    (let [[net prefix] (str/split cidr #"/" 2)
          prefix (Integer/parseInt prefix)
          mask (if (>= prefix 32) -1 (bit-shift-left -1 (- 32 prefix)))]
      (and (<= prefix 32)
           (== (bit-and (ipv4->int ip) mask)
               (bit-and (ipv4->int net) mask))))
    (catch Exception _ false)))

(defn- entry-matches?
  "True when a NO_PROXY entry matches host:port. An entry with a port matches
   only that port; CIDR entries match by IP; IPv6 hosts may be bracketed."
  [entry host port]
  (let [[host-part port-part] (str/split entry #":" 2)
        host-part (str/replace host-part #"^\[|\]$" "")]
    (and (if (str/includes? host-part "/")
           (cidr-match? host host-part)
           (host-match? host-part host))
         (or (nil? port-part) (= (str port) port-part)))))

(defn no-proxy-match?
  "True when host:port is exempted from proxying by NO_PROXY entries."
  [entries host port]
  (boolean (some #(entry-matches? % host port) entries)))

;; ─── Proxy selection ──────────────────────────────────────────────────────

(defn proxy-for-url
  "The proxy to use for a URL, from the environment (curl semantics):
   HTTPS_PROXY / HTTP_PROXY, falling back to ALL_PROXY, honoring NO_PROXY.
   Returns a parsed proxy map {:scheme :host :port :url :user :pass} or nil.
   env — an env map (defaults to System/getenv; pass a map in tests)."
  ([url] (proxy-for-url url (System/getenv)))
  ([url env]
   (let [cfg (proxy-config env)
         uri (java.net.URI. url)
         scheme (.getScheme uri)
         host (.getHost uri)
         port (let [p (.getPort uri)]
                (if (pos? p) p (if (= "https" scheme) 443 80)))]
     (when (and host (not (no-proxy-match? (:no-proxy cfg) host port)))
       (if (= "https" scheme)
         (or (:https cfg) (:all cfg))
         (or (:http cfg) (:all cfg)))))))

(defn curl-proxy?
  "True when the proxy needs curl: java.net.http supports only plain HTTP
   proxies, so SOCKS (any socks* scheme) and https-scheme (TLS-speaking)
   proxies are transported via curl."
  [p]
  (not= "http" (:scheme p)))

;; ─── Transport ────────────────────────────────────────────────────────────

(def curl-timeout-seconds 120)

(def curl-available?
  "Resolved once: true when curl is on PATH (the SOCKS transport needs it)."
  (delay
    (try
      (let [r @(proc/process ["sh" "-c" "command -v curl"]
                             {:out :discard :err :discard})]
        (zero? (:exit r)))
      (catch Exception _ false))))

(defn java-client
  "babashka.http-client client routing through an HTTP proxy (used for
   http/https proxies; SOCKS goes through curl instead)."
  [p]
  (http/client (cond-> {:proxy {:type :http :host (:host p) :port (:port p)}}
                 (:user p) (assoc :authenticator
                                  (cond-> {:user (:user p)}
                                    (:pass p) (assoc :pass (:pass p)))))))

(defn abort-stream!
  "Kill the underlying transport of a stream response (curl-backed only) so
   a concurrent read releases — interrupts don't unblock process pipes.
   No-op for java.net.http responses."
  [response]
  (when-let [pid (:pid response)]
    (process/kill-process-tree! pid)))

(defn watch-cancel!
  "Kill a process tree within ~200ms of the cancel signal firing mid-stream
   (the bash tool's signal-poller pattern — the sse read loop itself only
   stops *processing* lines on signal and keeps reading until EOF). Exits
   without killing when the process has already finished."
  [{:keys [proc pid]} signal]
  (when (and signal pid)
    (let [alive? #(-> proc :proc .isAlive)]
      (future
        (loop []
          (when (and (not @signal) (alive?))
            (Thread/sleep 200)
            (recur)))
        (when (and @signal (alive?))
          (process/kill-process-tree! pid))))))

(defn- curl-argv
  "Full curl argv for the request. Prefixed with setsid when available so the
   process is its own group leader — kill-process-tree!'s group kill then
   works reliably (same pattern as the bash tool; without it, a kill(1) that
   reports success without signaling a non-leader leaves curl alive).
   --max-time follows the request's :timeout (ms → s) when set; a nil request
   timeout (disabled) omits it; callers without a :timeout get the
   curl-timeout-seconds default."
  [url opts p]
  (let [max-time (if (contains? opts :timeout)
                   (when-let [t (:timeout opts)]
                     (max 1 (quot t 1000)))
                   curl-timeout-seconds)
        get? (= :get (:method opts))
        args (into (cond-> ["curl" "-sS" "-N" "--fail-with-body"
                            "--noproxy" ""
                            "--proxy" (:url p)]
                     max-time (conj "--max-time" (str max-time)))
                   (concat
                    (mapcat (fn [[k v]] ["-H" (str k ": " v)]) (:headers opts))
                    (if get?
                      [url]
                      ["--data-binary" "@-" url])))]
    (if-let [setsid @process/setsid-path]
      (into [setsid] args)
      args)))

(defn curl-post
  "POST via curl (proxies java.net.http can't handle: SOCKS and https-scheme
   proxies). Returns {:body InputStream :proc process-map
   :pid long} — read :body like a babashka.http-client :as :stream response,
   then call finish-curl!. Non-2xx responses make curl exit non-zero
   (--fail-with-body) with the error on stderr, mirroring
   babashka.http-client's throw-on-error. --noproxy \"\" pins proxy selection
   to our env parsing (overrides curl's own NO_PROXY handling).
   signal — cancel atom; when set mid-stream, the process tree is killed and
   the read ends (finish-curl! then skips error reporting). Throws ex-info
   when curl is missing from PATH."
  [url opts p signal]
  (when-not @curl-available?
    (throw (ex-info "curl not found on PATH — required for SOCKS proxies (install curl or use an HTTP proxy)"
                    {:type :curl-not-found})))
  (let [cmd (curl-argv url opts p)
        proc (proc/process cmd {:in (:body opts) :out :stream :err :string})
        pid (try (-> proc :proc .pid) (catch Exception _ nil))]
    (when pid (process/track-pid! pid))
    (watch-cancel! {:proc proc :pid pid} signal)
    {:body (:out proc) :proc proc :pid pid}))

(defn finish-curl!
  "Cleanup after the stream was read to EOF (or cancelled): kills the process
   tree on cancellation; otherwise reports a non-zero exit (connection
   failure, timeout, HTTP >= 400) via on-error. No-op for non-curl responses."
  [{:keys [proc pid]} signal on-error]
  (when proc
    (when pid (process/untrack-pid! pid))
    (if (and signal @signal)
      (when pid (process/kill-process-tree! pid))
      (let [result @proc]
        (when-not (zero? (:exit result))
          (when on-error
            (on-error (str "Proxy request failed: "
                           (str/trim (or (:err result) ""))))))))))

(defn post-stream
  "POST url with babashka.http-client opts, routing through the proxy selected
   from env vars (proxy-for-url). Returns a map with :body as an input stream,
   plus :proc/:pid for curl-backed (SOCKS) responses — call finish-curl! after
   the stream is read. signal — cancel atom: for curl-backed requests it kills
   the process tree when set mid-stream."
  [url opts signal]
  (if-let [p (proxy-for-url url)]
    (if (curl-proxy? p)
      (curl-post url opts p signal)
      (http/post url (assoc opts :client (java-client p))))
    (http/post url opts)))

(defn request-json
  "HTTP request expecting a JSON response (pi fetchJson — the OAuth flows).
   OPTS are babashka.http-client opts (:method defaults to :post, :headers,
   :body — a string, or a map that is JSON-encoded; :timeout ms). Routes
   through the proxy selected from env vars like post-stream; signal — cancel
   atom (curl-backed requests only). Returns {:status n :body parsed-map-or-
   nil}: non-2xx responses throw ex-info '<status>: <body>' with the raw
   response body in the message (curl path reports the error body/exit)."
  [url opts signal]
  (let [opts (cond-> (assoc opts :url url :throw false)
               (map? (:body opts)) (update :body json/generate-string))]
    (if-let [p (proxy-for-url url)]
      (if (curl-proxy? p)
        (let [response (curl-post url opts p signal)
              result @(:proc response)
              pid (:pid response)
              text (slurp (:body response))]
          ;; curl-post tracks the pid for tree-kill cleanup — a one-shot JSON
          ;; request must untrack it (finish-curl! does this for streams).
          (when pid (process/untrack-pid! pid))
          (if (zero? (:exit result))
            {:status 200
             :body (when (seq text) (json/parse-string text true))}
            (throw (ex-info (str "OAuth request failed: "
                                 (str/trim (or (:err result) text)))
                            {:type :oauth-http :status (:exit result)}))))
        (let [response (http/request (assoc opts :client (java-client p)))
              status (:status response)
              body (:body response)]
          (when-not (<= 200 status 299)
            (throw (ex-info (str status ": " body)
                            {:type :oauth-http :status status})))
          {:status status
           :body (when (seq body) (json/parse-string body true))}))
      (let [response (http/request opts)
            status (:status response)
            body (:body response)]
        (when-not (<= 200 status 299)
          (throw (ex-info (str status ": " body)
                          {:type :oauth-http :status status})))
        {:status status
         :body (when (seq body) (json/parse-string body true))}))))
