#!/usr/bin/env bb
;; Fake OAuth authorization server for validating the mcp-adapter OAuth
;; adapter (extensions/mcp-adapter/scripts/fake-oauth-server.bb — §12.5).
;; A plain java.net.ServerSocket HTTP loop. Serves:
;;
;;   GET  /.well-known/oauth-authorization-server — RFC 8414 metadata
;;   POST /register     — RFC 7591 DCR (returns a fixed client id)
;;   GET  /authorize    — loopback authorize: redirects to the callback
;;                        with ?code=...&state=<echoed> when code_verifier
;;                        is echoed via the state (validates challenge)
;;   POST /token        — authorization_code / refresh_token / device_code
;;                        grants (device: fixed code + pending/slow_down
;;                        sequence)
;;   POST /device       — RFC 8628 device authorization
;;   GET  /resource     — echo endpoint used by the fake MCP server
;;
;; The authorize endpoint simply redirects to the client's redirect_uri
;; with code=CODE and the same state — the validation script drives the
;; callback directly instead of a real browser.
;;
;; Usage: bb fake-oauth-server.bb [port]
;; Prints "PORT <n>" on stdout.
(require '[cheshire.core :as json]
         '[clojure.string :as str]
         '[clojure.java.io :as io])

(def port (Long/parseLong (or (first *command-line-args*) "0")))

(def state (atom {:device-polls 0
                  :issued-tokens 0}))

(defn- http-response
  ([status body] (http-response status body {"Content-Type" "application/json"}))
  ([status body headers]
   (let [body (str body)
         head (str "HTTP/1.1 " status " "
                   ({200 "OK" 302 "Found" 400 "Bad Request" 401 "Unauthorized"} status "OK")
                   "\r\n"
                   (str/join "" (map (fn [[k v]] (str k ": " v "\r\n")) headers))
                   "Content-Length: " (count (.getBytes body "UTF-8")) "\r\n"
                   "Connection: close\r\n\r\n")]
     (str head body))))

(defn- form-decode [s]
  (into {} (for [pair (str/split (or s "") #"&")]
             (let [[k v] (str/split pair #"=" 2)]
               [(java.net.URLDecoder/decode k "UTF-8")
                (java.net.URLDecoder/decode (or v "") "UTF-8")]))))

(defn- issue-tokens [& [extra]]
  (let [n (swap! state update :issued-tokens inc)]
    (merge {"access_token" (str "access-" (:issued-tokens n))
            "refresh_token" (str "refresh-" (:issued-tokens n))
            "expires_in" 3600
            "token_type" "Bearer"}
           extra)))

(defn- read-request [in]
  (let [reader (io/reader in)
        line (.readLine reader)]
    (when line
      (let [headers (loop [headers {}]
                      (let [h (.readLine reader)]
                        (if (and h (seq h))
                          (let [[k v] (str/split h #":" 2)]
                            (recur (assoc headers (str/lower-case (or k ""))
                                          (str/trim (or v "")))))
                          headers)))
            [method target] (str/split line #"\s+" 3)
            [path query] (str/split (or target "") #"\?" 2)
            content-length (Long/parseLong (or (get headers "content-length") "0"))
            body (when (pos? content-length)
                   (let [buf (char-array content-length)]
                     (.read reader buf 0 content-length)
                     (String. buf)))]
        {:method method :path path :query query :headers headers :body body}))))

(defn- handle [req]
  (let [path (:path req)
        method (:method req)]
    (cond
      (and (= method "GET")
           (str/ends-with? path "/.well-known/oauth-authorization-server"))
      (http-response 200
                     (json/generate-string
                      {:issuer (str "http://127.0.0.1:" (:port @state))
                       :authorization_endpoint (str "http://127.0.0.1:" (:port @state) "/authorize")
                       :token_endpoint (str "http://127.0.0.1:" (:port @state) "/token")
                       :registration_endpoint (str "http://127.0.0.1:" (:port @state) "/register")
                       :device_authorization_endpoint (str "http://127.0.0.1:" (:port @state) "/device")
                       :response_types_supported ["code"]
                       :grant_types_supported ["authorization_code" "refresh_token"
                                               "urn:ietf:params:oauth:grant-type:device_code"]
                       :token_endpoint_auth_methods_supported ["none"]}))

      (and (= method "POST") (= path "/register"))
      (http-response 201
                     (json/generate-string
                      {:client_id "dcr-client-1"
                       :client_id_issued_at 1700000000
                       :redirect_uris [(get-in (json/parse-string (:body req) true)
                                               [:redirect_uris 0])]}))

      (and (= method "GET") (= path "/authorize"))
      ;; Validate the PKCE challenge is present, then redirect to the
      ;; callback with code + the same state (the validation script hits
      ;; the callback URL directly).
      (let [params (form-decode (:query req))]
        (if (seq (:code_challenge params))
          (http-response 302 ""
                         {"Location" (str (:redirect_uri params)
                                          "?code=fake-code&state=" (:state params))})
          (http-response 400 "missing code_challenge")))

      (and (= method "POST") (= path "/token"))
      (let [form (form-decode (:body req))
            grant (get form "grant_type")]
        (case grant
          "authorization_code"
          (if (= "fake-code" (get form "code"))
            (http-response 200 (json/generate-string (issue-tokens
                                                      {"scope" (get form "scope")})))
            (http-response 400 (json/generate-string
                                {:error "invalid_grant"})))

          "refresh_token"
          (if (str/starts-with? (get form "refresh_token") "refresh-")
            (http-response 200 (json/generate-string (issue-tokens)))
            (http-response 400 (json/generate-string {:error "invalid_grant"})))

          "urn:ietf:params:oauth:grant-type:device_code"
          (let [polls (swap! state update :device-polls inc)]
            (case polls
              1 (http-response 400 (json/generate-string {:error "authorization_pending"}))
              2 (http-response 400 (json/generate-string {:error "slow_down" :interval 1}))
              (http-response 200 (json/generate-string (issue-tokens)))))

          (http-response 400 (json/generate-string {:error "unsupported_grant_type"}))))

      (and (= method "POST") (= path "/device"))
      (http-response 200
                     (json/generate-string
                      {:device_code "device-code-1"
                       :user_code "ABCD-EFGH"
                       :verification_uri "http://127.0.0.1:PORT/device-verify"
                       :interval 1
                       :expires_in 60}))

      :else (http-response 404 "not found"))))

(defn -main [& _]
  (let [server (java.net.ServerSocket. port 10
                                       (java.net.InetAddress/getByName "127.0.0.1"))]
    (reset! state (assoc @state :port (.getLocalPort server)))
    (println "PORT" (.getLocalPort server))
    (flush)
    (loop []
      (try
        (let [socket (.accept server)]
          (future
            (try
              (with-open [in (.getInputStream socket)
                          out (.getOutputStream socket)]
                (when-let [req (read-request in)]
                  (let [response (handle req)]
                    (.write out (.getBytes response "UTF-8"))
                    (.flush out))))
              (catch Exception _ nil))))
        (catch Exception _ nil))
      (recur))))

(-main)
