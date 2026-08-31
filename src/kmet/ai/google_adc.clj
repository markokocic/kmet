(ns kmet.ai.google-adc
  "Google Application Default Credentials token acquisition (the ADC half of
   pi's google-vertex provider — pi delegates to the @google/genai SDK; kmet
   fetches the OAuth2 token itself).

   Supports service-account keys (self-signed RS256 JWT grant) and
   authorized_user credentials (refresh-token grant) from
   GOOGLE_APPLICATION_CREDENTIALS or the default gcloud path
   (~/.config/gcloud/application_default_credentials.json). Tokens are
   cached per credentials file with a 5-minute expiry window (pi's SDK
   caches similarly)."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.string :as str]
            [kmet.libs.http :as http]))

(def ^:private token-url "https://oauth2.googleapis.com/token")
(def ^:private cloud-platform-scope "https://www.googleapis.com/auth/cloud-platform")

(defonce ^:private token-cache (atom {}))

(defn credentials-path
  "The ADC credentials file: GOOGLE_APPLICATION_CREDENTIALS (when it exists)
   or the default gcloud path. nil when neither exists."
  []
  (or (let [p (System/getenv "GOOGLE_APPLICATION_CREDENTIALS")]
        (when (and (seq p) (fs/exists? p)) p))
      (let [p (str (fs/path (System/getProperty "user.home")
                            ".config" "gcloud" "application_default_credentials.json"))]
        (when (fs/exists? p) p))))

(defn configured?
  "True when ADC credentials are available (pi hasVertexAdcCredentials —
   feeds auth/configured? for :google-vertex; the request additionally needs
   GOOGLE_CLOUD_PROJECT/GCLOUD_PROJECT + GOOGLE_CLOUD_LOCATION)."
  []
  (some? (credentials-path)))

(defn- b64url
  "Base64url encoding without padding."
  [bytes]
  (.encodeToString (.withoutPadding (java.util.Base64/getUrlEncoder)) bytes))

(defn- jwt-sign
  "RS256 signature over signing-input with a PEM PKCS8 private key."
  [pem signing-input]
  (let [pem (str/replace pem #"-----BEGIN [^-]+-----|-----END [^-]+-----|\s" "")
        der (.decode (java.util.Base64/getMimeDecoder) pem)
        key (.generatePrivate (java.security.KeyFactory/getInstance "RSA")
                              (java.security.spec.PKCS8EncodedKeySpec. der))
        sig (java.security.Signature/getInstance "SHA256withRSA")]
    (.initSign sig key)
    (.update sig (.getBytes signing-input "UTF-8"))
    (.sign sig)))

(defn- parse-token-response
  "POST a form-encoded body to the token endpoint; returns
   {:token str :expires-ms long} or nil when the response carries no token
   (the request reports the standard no-auth error)."
  [form]
  (try
    (let [resp (http/request-json token-url
                                  {:method :post
                                   :headers {"Content-Type" "application/x-www-form-urlencoded"}
                                   :body form})]
      (when-let [token (:access_token (:body resp))]
        {:token token
         :expires-ms (+ (System/currentTimeMillis)
                        (* 1000 (- (long (or (:expires_in (:body resp)) 3600)) 300)))}))
    (catch Exception _ nil)))

(defn- service-account-token
  "Self-signed JWT → OAuth2 token (the google-auth-library service-account
   flow: grant type urn:ietf:params:oauth:grant-type:jwt-bearer with a
   cloud-platform-scoped assertion)."
  [cred]
  (let [now (quot (System/currentTimeMillis) 1000)
        header (b64url (.getBytes (json/generate-string {:alg "RS256" :typ "JWT"}) "UTF-8"))
        claims (b64url (.getBytes (json/generate-string {:iss (:client_email cred)
                                                         :scope cloud-platform-scope
                                                         :aud token-url
                                                         :iat now
                                                         :exp (+ now 3600)}) "UTF-8"))
        signing-input (str header "." claims)
        assertion (str signing-input "." (b64url (jwt-sign (:private_key cred) signing-input)))
        form (str "grant_type=urn:ietf:params:oauth:grant-type:jwt-bearer&assertion=" assertion)]
    (parse-token-response form)))

(defn- authorized-user-token
  "Refresh-token grant for gcloud's authorized_user ADC credentials."
  [cred]
  (let [form (str "grant_type=refresh_token"
                  "&client_id=" (:client_id cred)
                  "&client_secret=" (:client_secret cred)
                  "&refresh_token=" (:refresh_token cred))]
    (parse-token-response form)))

(defn access-token!
  "An OAuth2 access token from ADC credentials, cached until the 5-minute
   expiry window (the cache is keyed on the credentials file path + modified
   time so a rotated file invalidates it). nil when no credentials exist or
   the token acquisition failed."
  []
  (when-let [path (credentials-path)]
    (let [key [path (str (fs/last-modified-time path))]]
      (or (when-let [cached (get @token-cache key)]
            (when (> (:expires-ms cached) (System/currentTimeMillis))
              (:token cached)))
          (let [cred (json/parse-string (slurp path) true)
                token (case (:type cred)
                        "service_account" (service-account-token cred)
                        "authorized_user" (authorized-user-token cred)
                        nil)]
            (when token
              (swap! token-cache assoc key token))
            (:token token))))))

(defn clear-token-cache!
  "Drop the cached token (test seam)."
  []
  (reset! token-cache {}))
