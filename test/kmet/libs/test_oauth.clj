(ns kmet.libs.test-oauth
  "kmet.libs.oauth — the generic OAuth machinery shared with the mcp-adapter
   extension: device-code poll state machine (the kmet.ai.test-oauth suite
   covers the same flow through the kmet.ai.oauth wrapper), PKCE, the
   loopback callback server, and the RFC 8414 discovery / RFC 7591 DCR /
   token exchange / refresh / RFC 8628 device-start helpers (HTTP mocked
   via with-redefs on fetch-json — no real network)."
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :as t :refer [deftest is]]
            [kmet.libs.crypto :as crypto]
            [kmet.libs.oauth :as oauth]))

;; ─── Poll state machine (directly on the lib) ─────────────────────────────

(deftest test-poll-completes-immediately
  (is (= "token"
         (oauth/poll-oauth-device-code-flow
          {:poll (fn [] {:status :complete :value "token"})
           :signal (atom false)}))))

(deftest test-poll-uses-provided-sleep
  (let [sleeps (atom [])
        calls (atom 0)
        result (oauth/poll-oauth-device-code-flow
                {:interval-seconds 1
                 :sleep (fn [ms _] (swap! sleeps conj ms))
                 :poll (fn [] (swap! calls inc)
                         (if (= 1 @calls)
                           {:status :pending}
                           {:status :complete :value "ok"}))
                 :signal (atom false)})]
    (is (= "ok" result))
    (is (= [1000] @sleeps))))

(deftest test-poll-cancel
  (is (thrown-with-msg? Exception #"Login cancelled"
                        (oauth/poll-oauth-device-code-flow
                         {:poll (fn [] {:status :pending})
                          :signal (atom true)}))))

(deftest test-poll-timeout
  (is (thrown-with-msg? Exception #"Device flow timed out"
                        (oauth/poll-oauth-device-code-flow
                         {:poll (fn [] {:status :pending})
                          :expires-in-seconds 0
                          :signal (atom false)}))))

;; ─── PKCE ─────────────────────────────────────────────────────────────────

(deftest test-pkce
  (let [{:keys [verifier challenge]} (oauth/generate-pkce)]
    (is (string? verifier))
    (is (string? challenge))
    (is (not= verifier challenge)))
  (is (not= (:verifier (oauth/generate-pkce))
            (:verifier (oauth/generate-pkce)))))

(deftest test-random-hex
  (is (= 32 (count (oauth/random-hex 16))))
  (is (re-matches #"[0-9a-f]+" (oauth/random-hex 8))))

;; ─── Loopback callback server (local socket, no network) ──────────────────

(deftest test-callback-server
  (let [code-p (promise)
        {:keys [port close]} (oauth/start-callback-server
                              0
                              (fn [{:keys [path query-params]}]
                                (when (= path "/cb")
                                  (deliver code-p (:code query-params))
                                  {:status 200 :body "ok"})))
        _ (future
            (try
              (with-open [s (java.net.Socket. "127.0.0.1" port)]
                (.write (.getOutputStream s)
                        (.getBytes "GET /cb?code=abc HTTP/1.1\r\nHost: x\r\n\r\n" "UTF-8"))
                (.flush (.getOutputStream s))
                (Thread/sleep 50))
              (catch Exception _ nil)))
        result (deref code-p 5000 ::timeout)]
    (is (= "abc" result))
    (close)))

(deftest test-parse-authorization-input
  (is (= {:code "c1" :state "s1"}
         (oauth/parse-authorization-input
          "http://127.0.0.1:1234/callback?code=c1&state=s1")))
  (is (= {:code "c1" :state "s1"} (oauth/parse-authorization-input "c1#s1")))
  (is (= {:code "c1"} (oauth/parse-authorization-input "code=c1")))
  (is (= {:code "bare"} (oauth/parse-authorization-input "bare"))))

;; ─── RFC 8414 discovery ───────────────────────────────────────────────────

(defn- form-decode
  "Decode a form-urlencoded body string into a map (the token/device
   endpoints send form bodies)."
  [s]
  (into {} (for [pair (str/split s #"&")]
             (let [[k v] (str/split pair #"=" 2)]
               [(java.net.URLDecoder/decode k "UTF-8")
                (java.net.URLDecoder/decode (or v "") "UTF-8")]))))

(defn- mock-fetch
  "with-redefs helper: FETCH is (fn [url method] → body-map-or-nil); nil
   means the request throws (transport error / non-2xx)."
  [fetch]
  (fn [url opts]
    (if-let [body (fetch url (or (:method opts) :post))]
      {:status 200 :body body}
      (throw (ex-info (str "404: " url) {:type :oauth-http :status 404})))))

(deftest test-discovery-primary-endpoint
  (with-redefs [oauth/fetch-json
                (mock-fetch (fn [url _]
                              (when (str/ends-with? url
                                                    "/mcp/.well-known/oauth-authorization-server")
                                {:authorization_endpoint "https://as.example/authorize"
                                 :token_endpoint "https://as.example/token"
                                 :registration_endpoint "https://as.example/register"
                                 :device_authorization_endpoint "https://as.example/device"
                                 :issuer "https://as.example"})))]
    (let [meta (oauth/discover-authorization-server "https://mcp.example.com/mcp")]
      (is (= "https://as.example/authorize" (:authorization_endpoint meta)))
      (is (= "https://as.example/device" (:device_authorization_endpoint meta))))))

(deftest test-discovery-falls-back-to-protected-resource
  (with-redefs [oauth/fetch-json
                (mock-fetch (fn [url _]
                              (when (str/ends-with? url
                                                    "/mcp/.well-known/oauth-protected-resource")
                                {:authorization_endpoint "https://as.example/authorize"})))]
    (let [meta (oauth/discover-authorization-server "https://mcp.example.com/mcp")]
      (is (= "https://as.example/authorize" (:authorization_endpoint meta))))))

(deftest test-discovery-returns-nil-when-unavailable
  (with-redefs [oauth/fetch-json (mock-fetch (fn [_ _] nil))]
    (is (nil? (oauth/discover-authorization-server "https://mcp.example.com/mcp")))))

;; ─── RFC 7591 dynamic client registration ─────────────────────────────────

(deftest test-register-client
  (let [captured (atom nil)]
    (with-redefs [oauth/fetch-json
                  (fn [url opts]
                    (reset! captured {:url url :body (:body opts)})
                    {:status 201
                     :body {:client_id "client-123"
                            :client_id_issued_at 1700000000
                            :redirect_uris ["http://127.0.0.1:45678/callback"]}})]
      (let [client (oauth/register-client
                    "https://as.example/register"
                    {:redirect-uris ["http://127.0.0.1:45678/callback"]
                     :client-name "kmet"})]
        (is (= "client-123" (:client_id client)))
        (is (= "https://as.example/register" (:url @captured)))
        (let [body (:body @captured)]
          (is (= ["http://127.0.0.1:45678/callback"] (:redirect_uris body)))
          (is (= "kmet" (:client_name body)))
          (is (= "none" (:token_endpoint_auth_method body)))
          (is (= ["authorization_code" "refresh_token"] (:grant_types body)))
          (is (= ["code"] (:response_types body))))))))

(deftest test-register-client-requires-redirect-uris
  (is (thrown-with-msg? Exception #"redirect URIs"
                        (oauth/register-client "https://as.example/register" {}))))

;; ─── Token exchange / refresh ─────────────────────────────────────────────

(deftest test-exchange-authorization-code
  (let [captured (atom nil)]
    (with-redefs [oauth/fetch-json
                  (fn [url opts]
                    (reset! captured {:url url :body (:body opts)
                                      :content-type (get-in opts [:headers "Content-Type"])})
                    {:status 200
                     :body {:access_token "at-1"
                            :refresh_token "rt-1"
                            :expires_in 3600
                            :scope "read"}})]
      (let [tokens (oauth/exchange-authorization-code
                    "https://as.example/token"
                    {:client-id "client-123"
                     :code "code-1"
                     :code-verifier "verifier-1"
                     :redirect-uri "http://127.0.0.1:45678/callback"})]
        (is (= "at-1" (:access tokens)))
        (is (= "rt-1" (:refresh tokens)))
        (is (= 3600 (:expires-in tokens)))
        (is (= "read" (:scope tokens)))
        (let [body (form-decode (:body @captured))]
          (is (= "authorization_code" (get body "grant_type")))
          (is (= "code-1" (get body "code")))
          (is (= "verifier-1" (get body "code_verifier")))
          (is (= "client-123" (get body "client_id"))))))))

(deftest test-exchange-requires-access-token
  (with-redefs [oauth/fetch-json
                (fn [_ _] {:status 200 :body {:expires_in 3600}})]
    (is (thrown-with-msg? Exception #"missing fields"
                          (oauth/exchange-authorization-code
                           "https://as.example/token"
                           {:client-id "c" :code "x"})))))

(deftest test-refresh-access-token
  (let [captured (atom nil)]
    (with-redefs [oauth/fetch-json
                  (fn [url opts]
                    (reset! captured {:url url :body (:body opts)})
                    {:status 200
                     :body {:access_token "at-2"
                            :refresh_token "rt-2"
                            :expires_in 7200}})]
      (let [tokens (oauth/refresh-access-token
                    "https://as.example/token"
                    {:client-id "client-123" :refresh-token "rt-1"})]
        (is (= "at-2" (:access tokens)))
        (is (= "rt-2" (:refresh tokens)))
        (is (= 7200 (:expires-in tokens)))
        (is (= "refresh_token" (get (form-decode (:body @captured)) "grant_type")))))))

(deftest test-refresh-requires-refresh-token
  (is (thrown-with-msg? Exception #"refresh token"
                        (oauth/refresh-access-token "https://as.example/token" {}))))

;; ─── RFC 8628 device-authorization start ──────────────────────────────────

(deftest test-start-device-authorization
  (let [captured (atom nil)]
    (with-redefs [oauth/fetch-json
                  (fn [url opts]
                    (reset! captured {:url url :body (:body opts)})
                    {:status 200
                     :body {:device_code "dc-1"
                            :user_code "ABCD-EFGH"
                            :verification_uri "https://example.com/device"
                            :interval 5
                            :expires_in 600}})]
      (let [device (oauth/start-device-authorization
                    "https://as.example/device"
                    {:client-id "client-123" :scope "read"})]
        (is (= "dc-1" (:device-code device)))
        (is (= "ABCD-EFGH" (:user-code device)))
        (is (= 5 (:interval device)))
        (is (= 600 (:expires-in device)))
        (let [body (form-decode (:body @captured))]
          (is (= "client-123" (get body "client_id")))
          (is (= "read" (get body "scope"))))))))

(deftest test-start-device-authorization-rejects-untrusted-uri
  (with-redefs [oauth/fetch-json
                (fn [_ _]
                  {:status 200
                   :body {:device_code "dc"
                          :user_code "uc"
                          :verification_uri "file:///etc/passwd"
                          :expires_in 600}})]
    (is (thrown-with-msg? Exception #"Untrusted verification_uri"
                          (oauth/start-device-authorization
                           "https://as.example/device"
                           {:client-id "c"})))))

;; ─── client-credentials grant (RFC 6749 §4.4) ─────────────────────────────

(deftest test-client-credentials
  (let [captured (atom nil)]
    (with-redefs [oauth/fetch-json
                  (fn [url opts]
                    (reset! captured {:url url :headers (:headers opts) :body (:body opts)})
                    {:status 200
                     :body {:access_token "cc-access" :expires_in 3600
                            :token_type "Bearer" :scope "read"}})]
      (let [tokens (oauth/client-credentials-token
                    "https://as.example/token"
                    {:client-id "cc-client" :client-secret "s3cret" :scope "read"})]
        (is (= "cc-access" (:access tokens)))
        (is (= 3600 (:expires-in tokens)))
        (is (= "read" (:scope tokens)))
        (is (= "https://as.example/token" (:url @captured)))
        (is (= (str "Basic " (.encodeToString (java.util.Base64/getEncoder)
                                              (.getBytes "cc-client:s3cret" "UTF-8")))
               (get-in @captured [:headers "Authorization"])))
        (is (str/includes? (get-in @captured [:body]) "grant_type=client_credentials"))
        (is (str/includes? (get-in @captured [:body]) "client_id=cc-client"))
        (is (str/includes? (get-in @captured [:body]) "scope=read"))))))

(deftest test-client-credentials-secret-post
  (let [captured (atom nil)]
    (with-redefs [oauth/fetch-json
                  (fn [_ opts]
                    (reset! captured {:headers (:headers opts) :body (:body opts)})
                    {:status 200 :body {:access_token "a" :expires_in 60}})]
      (oauth/client-credentials-token
       "https://as.example/token"
       {:client-id "c" :client-secret "s"
        :token-endpoint-auth-method :client-secret-post})
      (is (str/includes? (get-in @captured [:body]) "client_secret=s"))
      (is (nil? (get-in @captured [:headers "Authorization"]))))))

(deftest test-client-credentials-public-client
  (let [captured (atom nil)]
    (with-redefs [oauth/fetch-json
                  (fn [_ opts]
                    (reset! captured {:headers (:headers opts) :body (:body opts)})
                    {:status 200 :body {:access_token "a" :expires_in 60}})]
      (oauth/client-credentials-token
       "https://as.example/token" {:client-id "public-client"})
      (is (nil? (get-in @captured [:headers "Authorization"])))
      (is (str/includes? (get-in @captured [:body]) "client_id=public-client")))))

(deftest test-client-credentials-requires-client-id
  (is (thrown-with-msg? Exception #"client id"
                        (oauth/client-credentials-token "https://as.example/token" {}))))

;; ─── JWT-bearer grant (RFC 7523; signing lives in kmet.libs.crypto) ──────

(defn- pem-of
  "PEM armor for DER bytes (runtime-generated keys)."
  [label der]
  (let [body (.encodeToString (java.util.Base64/getEncoder) der)]
    (str "-----BEGIN " label "-----\n"
         (str/join "\n" (map #(apply str %) (partition-all 64 body)))
         "\n-----END " label "-----\n")))

(defn- jwt-parts
  [jwt]
  (let [[h p s] (str/split jwt #"\." 3)]
    {:header (json/parse-string (String. (crypto/base64url-decode h) "UTF-8") true)
     :payload (json/parse-string (String. (crypto/base64url-decode p) "UTF-8") true)
     :signature s}))

(deftest test-jwt-bearer-token
  (let [kg (java.security.KeyPairGenerator/getInstance "RSA")]
    (.initialize kg 2048)
    (let [pem (pem-of "PRIVATE KEY" (.getEncoded (.getPrivate (.generateKeyPair kg))))
          captured (atom nil)]
      (with-redefs [oauth/fetch-json
                    (fn [url opts]
                      (reset! captured {:url url :body (:body opts)})
                      {:status 200 :body {:access_token "jb-access" :expires_in 300}})]
        (let [tokens (oauth/jwt-bearer-token
                      "https://as.example/token"
                      {:private-key pem
                       :issuer "svc@example.com"
                       :scope "read"})]
          (is (= "jb-access" (:access tokens)))
          (is (= 300 (:expires-in tokens)))
          (is (= "https://as.example/token" (:url @captured)))
          (let [body (get-in @captured [:body])
                grant (some-> (re-find #"grant_type=([^&]+)" body) second
                              (java.net.URLDecoder/decode "UTF-8"))
                assertion (some-> (re-find #"assertion=([^&]+)" body) second
                                  (java.net.URLDecoder/decode "UTF-8"))]
            (is (= "urn:ietf:params:oauth:grant-type:jwt-bearer" grant))
            (is (str/includes? body "scope=read"))
            (let [{:keys [payload]} (jwt-parts assertion)]
              (is (= "svc@example.com" (:iss payload)))
              (is (= "svc@example.com" (:sub payload)))
              ;; the audience defaults to the token endpoint
              (is (= "https://as.example/token" (:aud payload)))
              (is (seq (:jti payload))))))))))

(deftest test-jwt-bearer-requires-issuer
  (is (thrown-with-msg? Exception #"issuer"
                        (oauth/jwt-bearer-token "https://as.example/token"
                                                {:private-key "x"}))))

(deftest test-start-device-authorization-requires-client-id
  (is (thrown-with-msg? Exception #"client id"
                        (oauth/start-device-authorization "https://as.example/device" {}))))
