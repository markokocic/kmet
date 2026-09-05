(ns kmet.libs.test-crypto
  "kmet.libs.crypto — base64url, DER-encoded private-key parsing (PEM
   PKCS#8/PKCS#1, JWK RSA/EC) and JWT signing (RS256/ES256). The PKCS#8
   path is exercised with runtime-generated keys; the PKCS#1/JWK paths
   use embedded throwaway key material (babashka's class registry cannot
   extract key components at runtime — no RSAPrivateCrtKeySpec /
   .getModulus interop)."
  (:require [kmet.libs.json :as json]
            [clojure.string :as str]
            [clojure.test :as t :refer [deftest is]]
            [kmet.libs.crypto :as crypto]))

;; ─── Test key fixtures (throwaway RSA-2048 + P-256 keypair, generated
;;    offline and embedded as literals) ────────────────────────────────────

(def ^:private rsa-pkcs1-pem
  "-----BEGIN RSA PRIVATE KEY-----
MIIEowIBAAKCAQEAqXQMXwj3XgLAosclGolI3XSu1OEs9DerliNAJ9tyVgpWpgkY
BGYfnDZaq9L5EU25igj6nramCudgmdt4iVSRB7A2LxOqXc/bNE90POOdj2E83f1g
v8oWpADDmOPzsKol30c7IJlcWM4XcOyixBa/841kuWpNtOopVl5yRrAwOfz5sVOh
NSezOOzaR8mNK3+jp01C0kerG5YmjB+kSIKqNCWj5/A0TiuJLJE9cFCfa07V1tqZ
FE+zNIEU74oJSu7F9M6zUMsVHUAH0lWYmXcJtTge6wgFaAMvGtQlnhelmhsT6r0j
VfxnfKIITccox+peoTna4Lr0zEajTlgsnRKDJwIDAQABAoIBAEZfZHffUGPhfsDb
NYSEuRcHS0grT7EIoaDTeORUlXI4Du4bXrcM9lm4lQVAs6FFByXVPsfFEMX8ezjK
VY+q6CQqQQZiA2G8Xcump55V5OxTtKR4gsKDmc14Z8LKAD1BHENS4LJUHm7fAAss
qxgGXqc19Duwcx8MFCjDRnAm4ZBi2wiQZBcyTeXh1kyimmMUJBl8NY7iey4eTZjj
MkotOSxOaj+ObogqfgwFR7kvf6firDx48oKlia4QKlEoy8iswPZYcvHoDglXB6r8
d+D724j0ARVvre4cVHs6rA09eMwtMI2nNOYRbzW3+v/V5A5Ujm2K6AvFRTNky022
N8GwfLkCgYEA0yJ+aKX31Z23wjL5kw3+FVI95jsDc2m8GrY9L0eG37QkLdMwvVuq
N+H7mhN2TvqwkUGDGAszm6icUXoUYCScQjucUpJhURh+gZ01Az8Vnf34GnA5ImB8
7F7rd+W5A6ZrodzRJ7GI0YM+LxeWprn1FjXtrzGsq1hPIxgJZZebT3MCgYEAzXYh
CR6ywc9kzIwu9RrbqaaFf8vV6gPp3p6eF+kIBlg90rERPROff8BoswAUmDZuKkFC
yHVS8aw2tDpG1rnnr5dvaPOo7UARn+xOPahgazp+pqR4E5M8rqlLD7/JNdp1eF4B
esnf2E/8/QWIq6Bfsaa4hVpoxZXsDoS4PcCyaH0CgYEAu7G9eKCauw9zrbONRRq6
Vw/+sS6jObW0oHaD3AUVNMe7JfXKLXxQzU8bUfSdR0b7MpZvyS8kGOwC0zfY50OE
mijOJmW1F9fTlrw/xXwOZp7BMhez4wit5Z/YaoURPdpzcriQQ15DSCYJYOnyZpOH
+s/EVeuuGcDu0T7sE6F8U1sCgYAviWRNUt/y/YQJ22lF5mfqUY/TqJqeoTcr/bEA
QTdNGH99TuB5LCAcE61ltOAO85D7j8vey15ccgbaHh2jsrGLK9NoCfAMrGUnhin4
FAvy4z7IWYc6qDdDBgJK64mnPxsstTtMaIa06pTTCcO8Sce6N6O7ntZc2LocBdMG
3p2olQKBgGR/99C/bzpqLMY9/0UjVB8laZ/18tRQR/pLM4gJlik8NnaGqTH8yzcF
NxfwKRzaK9ua8nar7NZAJ1i8fSxuKajU5yHkmhtaNnwCaKU4znzEo7lwRr+Rq1G2
GarvMn/oLkaqYB5qx5M8Zjoq6aqWB0FnYgCps7ejH2DsdtkOdBap
-----END RSA PRIVATE KEY-----")

(def ^:private rsa-pkcs8-pem
  "-----BEGIN PRIVATE KEY-----
MIIEvQIBADANBgkqhkiG9w0BAQEFAASCBKcwggSjAgEAAoIBAQCpdAxfCPdeAsCi
xyUaiUjddK7U4Sz0N6uWI0An23JWClamCRgEZh+cNlqr0vkRTbmKCPqetqYK52CZ
23iJVJEHsDYvE6pdz9s0T3Q8452PYTzd/WC/yhakAMOY4/OwqiXfRzsgmVxYzhdw
7KLEFr/zjWS5ak206ilWXnJGsDA5/PmxU6E1J7M47NpHyY0rf6OnTULSR6sbliaM
H6RIgqo0JaPn8DROK4kskT1wUJ9rTtXW2pkUT7M0gRTviglK7sX0zrNQyxUdQAfS
VZiZdwm1OB7rCAVoAy8a1CWeF6WaGxPqvSNV/Gd8oghNxyjH6l6hOdrguvTMRqNO
WCydEoMnAgMBAAECggEARl9kd99QY+F+wNs1hIS5FwdLSCtPsQihoNN45FSVcjgO
7htetwz2WbiVBUCzoUUHJdU+x8UQxfx7OMpVj6roJCpBBmIDYbxdy6annlXk7FO0
pHiCwoOZzXhnwsoAPUEcQ1LgslQebt8ACyyrGAZepzX0O7BzHwwUKMNGcCbhkGLb
CJBkFzJN5eHWTKKaYxQkGXw1juJ7Lh5NmOMySi05LE5qP45uiCp+DAVHuS9/p+Ks
PHjygqWJrhAqUSjLyKzA9lhy8egOCVcHqvx34PvbiPQBFW+t7hxUezqsDT14zC0w
jac05hFvNbf6/9XkDlSObYroC8VFM2TLTbY3wbB8uQKBgQDTIn5opffVnbfCMvmT
Df4VUj3mOwNzabwatj0vR4bftCQt0zC9W6o34fuaE3ZO+rCRQYMYCzObqJxRehRg
JJxCO5xSkmFRGH6BnTUDPxWd/fgacDkiYHzsXut35bkDpmuh3NEnsYjRgz4vF5am
ufUWNe2vMayrWE8jGAlll5tPcwKBgQDNdiEJHrLBz2TMjC71GtuppoV/y9XqA+ne
np4X6QgGWD3SsRE9E59/wGizABSYNm4qQULIdVLxrDa0OkbWueevl29o86jtQBGf
7E49qGBrOn6mpHgTkzyuqUsPv8k12nV4XgF6yd/YT/z9BYiroF+xpriFWmjFlewO
hLg9wLJofQKBgQC7sb14oJq7D3Ots41FGrpXD/6xLqM5tbSgdoPcBRU0x7sl9cot
fFDNTxtR9J1HRvsylm/JLyQY7ALTN9jnQ4SaKM4mZbUX19OWvD/FfA5mnsEyF7Pj
CK3ln9hqhRE92nNyuJBDXkNIJglg6fJmk4f6z8RV664ZwO7RPuwToXxTWwKBgC+J
ZE1S3/L9hAnbaUXmZ+pRj9Oomp6hNyv9sQBBN00Yf31O4HksIBwTrWW04A7zkPuP
y97LXlxyBtoeHaOysYsr02gJ8AysZSeGKfgUC/LjPshZhzqoN0MGAkrriac/Gyy1
O0xohrTqlNMJw7xJx7o3o7ue1lzYuhwF0wbenaiVAoGAZH/30L9vOmosxj3/RSNU
HyVpn/Xy1FBH+ksziAmWKTw2doapMfzLNwU3F/ApHNor25rydqvs1kAnWLx9LG4p
qNTnIeSaG1o2fAJopTjOfMSjuXBGv5GrUbYZqu8yf+guRqpgHmrHkzxmOirpqpYH
QWdiAKmzt6MfYOx22Q50Fqk=
-----END PRIVATE KEY-----")

(def ^:private rsa-spki-pem
  "-----BEGIN PUBLIC KEY-----
MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAqXQMXwj3XgLAosclGolI
3XSu1OEs9DerliNAJ9tyVgpWpgkYBGYfnDZaq9L5EU25igj6nramCudgmdt4iVSR
B7A2LxOqXc/bNE90POOdj2E83f1gv8oWpADDmOPzsKol30c7IJlcWM4XcOyixBa/
841kuWpNtOopVl5yRrAwOfz5sVOhNSezOOzaR8mNK3+jp01C0kerG5YmjB+kSIKq
NCWj5/A0TiuJLJE9cFCfa07V1tqZFE+zNIEU74oJSu7F9M6zUMsVHUAH0lWYmXcJ
tTge6wgFaAMvGtQlnhelmhsT6r0jVfxnfKIITccox+peoTna4Lr0zEajTlgsnRKD
JwIDAQAB
-----END PUBLIC KEY-----")

(def ^:private ec-pkcs8-pem
  "-----BEGIN PRIVATE KEY-----
MEECAQAwEwYHKoZIzj0CAQYIKoZIzj0DAQcEJzAlAgEBBCBGeVcRGnop0Wixv6a9
YnpB+wpx1Ph/o7UpCl8wBbb1gA==
-----END PRIVATE KEY-----")

(def ^:private ec-spki-pem
  "-----BEGIN PUBLIC KEY-----
MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE+PcdLFHrfpNpmO3tNgnu++XJQsDg
w753I5QsmUWyeA4oShI/eiyxOZ9MxKU9wd70tHhZVWPC/rEA0PfuypCBOA==
-----END PUBLIC KEY-----")

(def ^:private rsa-jwk
  {:kty "RSA"
   :n "AKl0DF8I914CwKLHJRqJSN10rtThLPQ3q5YjQCfbclYKVqYJGARmH5w2WqvS-RFNuYoI-p62pgrnYJnbeIlUkQewNi8Tql3P2zRPdDzjnY9hPN39YL_KFqQAw5jj87CqJd9HOyCZXFjOF3DsosQWv_ONZLlqTbTqKVZeckawMDn8-bFToTUnszjs2kfJjSt_o6dNQtJHqxuWJowfpEiCqjQlo-fwNE4riSyRPXBQn2tO1dbamRRPszSBFO-KCUruxfTOs1DLFR1AB9JVmJl3CbU4HusIBWgDLxrUJZ4XpZobE-q9I1X8Z3yiCE3HKMfqXqE52uC69MxGo05YLJ0Sgyc"
   :e "AQAB"
   :d "Rl9kd99QY-F-wNs1hIS5FwdLSCtPsQihoNN45FSVcjgO7htetwz2WbiVBUCzoUUHJdU-x8UQxfx7OMpVj6roJCpBBmIDYbxdy6annlXk7FO0pHiCwoOZzXhnwsoAPUEcQ1LgslQebt8ACyyrGAZepzX0O7BzHwwUKMNGcCbhkGLbCJBkFzJN5eHWTKKaYxQkGXw1juJ7Lh5NmOMySi05LE5qP45uiCp-DAVHuS9_p-KsPHjygqWJrhAqUSjLyKzA9lhy8egOCVcHqvx34PvbiPQBFW-t7hxUezqsDT14zC0wjac05hFvNbf6_9XkDlSObYroC8VFM2TLTbY3wbB8uQ"
   :p "ANMifmil99Wdt8Iy-ZMN_hVSPeY7A3NpvBq2PS9Hht-0JC3TML1bqjfh-5oTdk76sJFBgxgLM5uonFF6FGAknEI7nFKSYVEYfoGdNQM_FZ39-BpwOSJgfOxe63fluQOma6Hc0SexiNGDPi8Xlqa59RY17a8xrKtYTyMYCWWXm09z"
   :q "AM12IQkessHPZMyMLvUa26mmhX_L1eoD6d6enhfpCAZYPdKxET0Tn3_AaLMAFJg2bipBQsh1UvGsNrQ6Rta556-Xb2jzqO1AEZ_sTj2oYGs6fqakeBOTPK6pSw-_yTXadXheAXrJ39hP_P0FiKugX7GmuIVaaMWV7A6EuD3Asmh9"
   :dp "ALuxvXigmrsPc62zjUUaulcP_rEuozm1tKB2g9wFFTTHuyX1yi18UM1PG1H0nUdG-zKWb8kvJBjsAtM32OdDhJooziZltRfX05a8P8V8DmaewTIXs-MIreWf2GqFET3ac3K4kENeQ0gmCWDp8maTh_rPxFXrrhnA7tE-7BOhfFNb"
   :dq "L4lkTVLf8v2ECdtpReZn6lGP06ianqE3K_2xAEE3TRh_fU7geSwgHBOtZbTgDvOQ-4_L3steXHIG2h4do7KxiyvTaAnwDKxlJ4Yp-BQL8uM-yFmHOqg3QwYCSuuJpz8bLLU7TGiGtOqU0wnDvEnHujeju57WXNi6HAXTBt6dqJU"
   :qi "ZH_30L9vOmosxj3_RSNUHyVpn_Xy1FBH-ksziAmWKTw2doapMfzLNwU3F_ApHNor25rydqvs1kAnWLx9LG4pqNTnIeSaG1o2fAJopTjOfMSjuXBGv5GrUbYZqu8yf-guRqpgHmrHkzxmOirpqpYHQWdiAKmzt6MfYOx22Q50Fqk"})

(def ^:private ec-jwk
  {:kty "EC" :crv "P-256" :d "RnlXERp6KdFosb-mvWJ6QfsKcdT4f6O1KQpfMAW29YA"})

;; ─── Helpers ──────────────────────────────────────────────────────────────

(defn- pem-of
  "PEM armor for DER bytes."
  [label der]
  (let [body (.encodeToString (java.util.Base64/getEncoder) der)]
    (str "-----BEGIN " label "-----\n"
         (str/join "\n" (map #(apply str %) (partition-all 64 body)))
         "\n-----END " label "-----\n")))

(defn- pem->der
  [pem]
  (.decode (java.util.Base64/getMimeDecoder)
           (str/join "" (remove #(str/includes? % "-----") (str/split-lines pem)))))

(defn- public-key
  "Public key from an SPKI PEM."
  [spki algorithm]
  (-> (java.security.KeyFactory/getInstance algorithm)
      (.generatePublic (java.security.spec.X509EncodedKeySpec. (pem->der spki)))))

(defn- jwt-parts
  [jwt]
  (let [[h p s] (str/split jwt #"\." 3)]
    {:header (json/parse-string (String. (crypto/base64url-decode h) "UTF-8") true)
     :payload (json/parse-string (String. (crypto/base64url-decode p) "UTF-8") true)
     :signature s}))

(defn- signing-input
  [jwt]
  (str/join "." (butlast (str/split jwt #"\."))))

(defn- verify-signature
  "Verify a JWT's signature against a public key. ECDSA signatures come
   back in JWT's raw r||s form and are re-encoded as DER for java's
   verifier."
  [algorithm public-key jwt]
  (let [raw (crypto/base64url-decode (get (jwt-parts jwt) :signature))
        s (java.security.Signature/getInstance algorithm)]
    (.initVerify s public-key)
    (.update s (.getBytes (signing-input jwt) "UTF-8"))
    (if (= algorithm "SHA256withECDSA")
      (let [half (quot (alength raw) 2)
            bigint-of (fn [off]
                        (let [bs (java.util.Arrays/copyOfRange raw off (+ off half))
                              bs (if (and (> (alength bs) 1) (zero? (aget bs 0)))
                                   (java.util.Arrays/copyOfRange bs 1 (alength bs))
                                   bs)]
                          (BigInteger. 1 bs)))
            der-int (fn [n]
                      (let [bs (.toByteArray n)
                            content (if (zero? (bit-and (aget bs 0) 0x80))
                                      bs
                                      (byte-array (concat [(byte 0)] bs)))]
                        (byte-array (concat [(byte 0x02) (byte (alength content))]
                                            content))))
            r-int (der-int (bigint-of 0))
            s-int (der-int (bigint-of half))]
        (.verify s (byte-array (concat [(byte 0x30)
                                        (byte (+ (alength r-int) (alength s-int)))]
                                       r-int s-int))))
      (.verify s raw))))

;; ─── Base64url ────────────────────────────────────────────────────────────

(deftest test-base64url-round-trip
  (let [bytes (.getBytes "hello world" "UTF-8")]
    (is (= "aGVsbG8gd29ybGQ" (crypto/base64url bytes)))
    (is (java.util.Arrays/equals bytes (crypto/base64url-decode "aGVsbG8gd29ybGQ")))))

;; ─── JWT signing (RFC 7515/7519; RS256 + ES256) ───────────────────────────

(deftest test-sign-jwt-rs256
  (let [kg (java.security.KeyPairGenerator/getInstance "RSA")]
    (.initialize kg 2048)
    (let [kp (.generateKeyPair kg)
          pem (pem-of "PRIVATE KEY" (.getEncoded (.getPrivate kp)))
          jwt (crypto/sign-jwt {:key pem
                                :claims {"iss" "svc" "sub" "svc"
                                         "aud" "https://as.example/token"}})
          {:keys [header payload]} (jwt-parts jwt)]
      (is (= "RS256" (:alg header)))
      (is (= "JWT" (:typ header)))
      (is (= "svc" (:iss payload)))
      (is (= "https://as.example/token" (:aud payload)))
      (is (number? (:iat payload)))
      (is (= (+ (:iat payload) 3600) (:exp payload)))
      (is (verify-signature "SHA256withRSA" (.getPublic kp) jwt)))))

(deftest test-sign-jwt-es256
  (let [kg (java.security.KeyPairGenerator/getInstance "EC")]
    (.initialize kg (java.security.spec.ECGenParameterSpec. "secp256r1"))
    (let [kp (.generateKeyPair kg)
          jwt (crypto/sign-jwt {:algorithm :ES256
                                :key (pem-of "PRIVATE KEY" (.getEncoded (.getPrivate kp)))
                                :claims {"iss" "svc"}})
          {:keys [header signature]} (jwt-parts jwt)]
      (is (= "ES256" (:alg header)))
      (is (= 64 (alength (crypto/base64url-decode signature))))
      (is (verify-signature "SHA256withECDSA" (.getPublic kp) jwt)))))

(deftest test-sign-jwt-rejects-unknown-algorithm
  (is (thrown-with-msg? Exception #"algorithm"
                        (crypto/sign-jwt {:algorithm :HS256 :key "x" :claims {}}))))

;; ─── Key formats: PKCS#1 / PKCS#8 PEM + JWK (RSA and EC) ──────────────────
;; The PKCS#1 and JWK paths exercise the DER-wrap into PKCS#8; the SPKI
;; PEMs provide the matching public keys for verification.

(deftest test-pkcs1-rsa-private-key
  (let [jwt (crypto/sign-jwt {:key rsa-pkcs1-pem :claims {"iss" "svc"}})]
    (is (verify-signature "SHA256withRSA" (public-key rsa-spki-pem "RSA") jwt))))

(deftest test-pkcs8-rsa-private-key
  (let [jwt (crypto/sign-jwt {:key rsa-pkcs8-pem :claims {"iss" "svc"}})]
    (is (verify-signature "SHA256withRSA" (public-key rsa-spki-pem "RSA") jwt))))

(deftest test-pkcs8-ec-private-key
  (let [jwt (crypto/sign-jwt {:algorithm :ES256 :key ec-pkcs8-pem
                              :claims {"iss" "svc"}})]
    (is (verify-signature "SHA256withECDSA" (public-key ec-spki-pem "EC") jwt))))

(deftest test-jwk-rsa-private-key
  (let [jwt (crypto/sign-jwt {:key rsa-jwk :claims {"iss" "svc"}})]
    (is (verify-signature "SHA256withRSA" (public-key rsa-spki-pem "RSA") jwt))))

(deftest test-jwk-ec-private-key
  (let [jwt (crypto/sign-jwt {:algorithm :ES256 :key ec-jwk :claims {"iss" "svc"}})]
    (is (verify-signature "SHA256withECDSA" (public-key ec-spki-pem "EC") jwt))))

(deftest test-parse-private-key-rejects-garbage
  (is (thrown-with-msg? Exception #"PEM"
                        (crypto/parse-private-key "not a key at all")))
  (is (thrown-with-msg? Exception #"key type"
                        (crypto/parse-private-key {:kty "OKP" :d "x"})))
  (is (thrown-with-msg? Exception #"private key"
                        (crypto/parse-private-key nil))))
