(ns kmet.libs.crypto
  "Generic crypto helpers for Babashka (no bundled crypto library):
   base64url, a minimal DER reader/writer, private-key parsing (PEM
   PKCS#8/PKCS#1, JWK RSA/EC) and RFC 7515/7519 JWT signing (RS256/ES256).
   Used by kmet.libs.oauth (the RFC 7523 JWT-bearer grant) and shared with
   the mcp-adapter extension through the libs layer.

   Why the DER code exists: babashka's fixed class registry lacks
   RSAPrivateCrtKeySpec / ECPrivateKeySpec / ECNamedCurveSpec (Class/forName
   fails), and instance-method interop on key impl classes is unavailable
   (.getModulus throws NoSuchFieldException). Keys are therefore
   constructed ONLY via PKCS8EncodedKeySpec (which resolves): PKCS#1 and
   JWK keys are DER-wrapped into a PKCS#8 PrivateKeyInfo here — pure byte
   assembly, no spec classes needed."
  (:require [cheshire.core :as json]
            [clojure.string :as str]))

;; ─── Base64url ────────────────────────────────────────────────────────────

(defn base64url
  "Base64url without padding."
  [bytes]
  (.encodeToString (.withoutPadding (java.util.Base64/getUrlEncoder)) bytes))

(defn base64url-decode
  "Base64url-decode a string, restoring the padding JWT segments omit."
  [s]
  (let [s (str s)
        padded (str s (apply str (repeat (mod (- 4 (mod (count s) 4)) 4) "=")))]
    (.decode (java.util.Base64/getUrlDecoder) padded)))

;; ─── DER reading ──────────────────────────────────────────────────────────

(defn- bytes->bigint
  "Unsigned big-endian bytes → BigInteger (strips a leading sign byte)."
  [bs]
  (let [bs (if (and (> (alength bs) 1) (zero? (aget bs 0)))
             (java.util.Arrays/copyOfRange bs 1 (alength bs))
             bs)]
    (BigInteger. 1 bs)))

(defn- read-der-length
  "DER length at BYTES[i] → [length next-index] (short + long form)."
  [bytes i]
  (let [b (bit-and (aget bytes i) 0xff)]
    (if (zero? (bit-and b 0x80))
      [b (inc i)]
      (let [n (bit-and b 0x7f)]
        [(loop [j 0 acc 0]
           (if (< j n)
             (recur (inc j) (+ (* acc 256) (bit-and (aget bytes (+ i 1 j)) 0xff)))
             acc))
         (+ i 1 n)]))))

(defn- der->unsigned-integers
  "Minimal DER reader: a SEQUENCE (0x30) of INTEGER (0x02) values → vector
   of BigIntegers. Covers PKCS#1 RSAPrivateKey and DER ECDSA signatures
   (r, s); throws on any other structure."
  [der]
  (let [tag (bit-and (aget der 0) 0xff)]
    (when-not (= tag 0x30)
      (throw (ex-info "Invalid DER: expected SEQUENCE"
                      {:type :crypto-invalid-key})))
    (let [[seq-len next] (read-der-length der 1)
          end (+ next seq-len)]
      (loop [i next acc []]
        (if (< i end)
          (let [t (bit-and (aget der i) 0xff)]
            (when-not (= t 0x02)
              (throw (ex-info "Invalid DER: expected INTEGER"
                              {:type :crypto-invalid-key})))
            (let [[len j] (read-der-length der (inc i))]
              (recur (+ j len)
                     (conj acc (bytes->bigint
                                (java.util.Arrays/copyOfRange der j (+ j len)))))))
          acc)))))

;; ─── DER writing (PKCS#8 wrapping) ────────────────────────────────────────

(defn- der-length-bytes
  "DER length encoding (short + long form)."
  [n]
  (if (< n 128)
    [(unchecked-byte n)]
    (let [bs (loop [n n acc []]
               (if (zero? n)
                 acc
                 (recur (quot n 256) (cons (unchecked-byte (mod n 256)) acc))))]
      (into [(unchecked-byte (bit-or 0x80 (count bs)))] bs))))

(defn- der-tlv
  "DER tag + length + value bytes."
  [tag content]
  (byte-array (concat [(unchecked-byte tag)]
                      (der-length-bytes (alength content))
                      content)))

(defn- base128-encode
  "DER base-128 encoding of a non-negative long: big-endian groups of 7
   bits, high bit set on every group except the last."
  [n]
  (let [groups (loop [n n acc []]
                 (if (zero? n)
                   (if (seq acc) acc [0])
                   (recur (quot n 128) (cons (mod n 128) acc))))
        c (count groups)]
    (mapv (fn [i g] (unchecked-byte (if (= i (dec c)) g (bit-or g 0x80))))
          (range) groups)))

(defn- der-oid
  "DER OBJECT IDENTIFIER for an OID string like \"1.2.840.113549.1.1.1\"."
  [oid]
  (let [parts (mapv #(Long/parseLong %) (str/split oid #"\."))
        first-byte (unchecked-byte (+ (* 40 (parts 0)) (parts 1)))
        rest (mapcat base128-encode (subvec parts 2))]
    (der-tlv 0x06 (byte-array (concat [first-byte] rest)))))

(defn- der-integer
  "DER INTEGER for a non-negative BigInteger (prepends the sign byte when
   the high bit is set)."
  [n]
  (let [bs (.toByteArray n)]
    (der-tlv 0x02 (if (zero? (bit-and (aget bs 0) 0x80))
                    bs
                    (byte-array (concat [(unchecked-byte 0)] bs))))))

(defn- unsigned-bytes
  "Big-endian bytes of a BigInteger without a leading sign byte."
  [n]
  (let [bs (.toByteArray n)]
    (if (and (> (alength bs) 1) (zero? (aget bs 0)))
      (java.util.Arrays/copyOfRange bs 1 (alength bs))
      bs)))

(defn- der-null
  []
  (byte-array [(unchecked-byte 0x05) (unchecked-byte 0x00)]))

(defn- der-octet-string
  [content]
  (der-tlv 0x04 content))

(defn- der-sequence
  [contents]
  (der-tlv 0x30 (byte-array (mapcat seq contents))))

(defn- der-context0
  "DER context-specific tag [0]."
  [content]
  (der-tlv 0xa0 content))

(def ^:private rsa-encryption-oid "1.2.840.113549.1.1.1")
(def ^:private id-ec-public-key-oid "1.2.840.10045.2.1")
(def ^:private ec-curve-oids
  {"secp256r1" "1.2.840.10045.3.1.7"
   "secp384r1" "1.3.132.0.34"
   "secp521r1" "1.3.132.0.35"})

(defn- pkcs8-wrap
  "Wrap an algorithm-specific private key DER in a PKCS#8 PrivateKeyInfo:
   SEQUENCE { INTEGER 0, AlgorithmIdentifier, OCTET STRING private-der }."
  [algorithm-id private-der]
  (der-sequence [(der-integer (BigInteger. "0"))
                 algorithm-id
                 (der-octet-string private-der)]))

(defn- rsa-algorithm-id
  []
  (der-sequence [(der-oid rsa-encryption-oid) (der-null)]))

(defn- ec-algorithm-id
  [curve-name]
  (der-sequence [(der-oid id-ec-public-key-oid) (der-oid (ec-curve-oids curve-name))]))

;; ─── Private-key parsing (PEM / JWK → java.security.PrivateKey) ──────────

(defn- pem->der
  "Strip PEM armor (-----BEGIN/END lines) and base64-decode the body."
  [pem]
  (.decode (java.util.Base64/getMimeDecoder)
           (str/join "" (remove #(str/includes? % "-----")
                                (str/split-lines pem)))))

(defn- private-key-from-pkcs8
  "PKCS#8 DER → java.security key; the algorithm is encoded in the
   structure, so try RSA then EC."
  [der]
  (try
    (-> (java.security.KeyFactory/getInstance "RSA")
        (.generatePrivate (java.security.spec.PKCS8EncodedKeySpec. der)))
    (catch Exception _
      (-> (java.security.KeyFactory/getInstance "EC")
          (.generatePrivate (java.security.spec.PKCS8EncodedKeySpec. der))))))

(defn- private-key-from-pkcs1
  "PKCS#1 RSAPrivateKey DER (openssl genrsa) → java.security RSA key, via
   a PKCS#8 wrap. The DER carries its own version INTEGER (0) — drop it
   and re-add a fresh one."
  [der]
  (let [[_ & components] (der->unsigned-integers der)
        pkcs1 (der-sequence (cons (der-integer (BigInteger. "0"))
                                  (map der-integer components)))]
    (private-key-from-pkcs8 (pkcs8-wrap (rsa-algorithm-id) pkcs1))))

(defn- jwk->rsa-private-key
  [jwk]
  (let [components (map (comp bytes->bigint base64url-decode)
                        [(:n jwk) (:e jwk) (:d jwk) (:p jwk) (:q jwk)
                         (:dp jwk) (:dq jwk) (:qi jwk)])
        pkcs1 (der-sequence (cons (der-integer (BigInteger. "0"))
                                  (map der-integer components)))]
    (private-key-from-pkcs8 (pkcs8-wrap (rsa-algorithm-id) pkcs1))))

(defn- jwk->ec-private-key
  "JWK EC → RFC 5915 ECPrivateKey DER (version 1, d, [0] named curve) →
   PKCS#8 → java.security EC key."
  [jwk]
  (let [curve-name ({"P-256" "secp256r1"
                     "P-384" "secp384r1"
                     "P-521" "secp521r1"} (:crv jwk))]
    (when-not curve-name
      (throw (ex-info (str "Unsupported EC JWK curve " (:crv jwk)
                           " (expected P-256, P-384 or P-521)")
                      {:type :crypto-invalid-key})))
    (let [d (bytes->bigint (base64url-decode (:d jwk)))
          curve-oid (der-oid (ec-curve-oids curve-name))
          ec-der (der-sequence [(der-integer (BigInteger. "1"))
                                (der-octet-string (unsigned-bytes d))
                                (der-context0 curve-oid)])]
      (private-key-from-pkcs8 (pkcs8-wrap (ec-algorithm-id curve-name) ec-der)))))

(defn parse-private-key
  "Parse a private key into a java.security.PrivateKey. KEY is a PEM
   string (PKCS#8 \"BEGIN PRIVATE KEY\" — RSA or EC — or PKCS#1
   \"BEGIN RSA PRIVATE KEY\") or a JWK map ({:kty \"RSA\" ...} with
   n/e/d/p/q/dp/dq/qi, or {:kty \"EC\" :crv \"P-256\"|\"P-384\"|
   \"P-521\" :d ...})."
  [key]
  (cond
    (string? key)
    (let [label (first (filter #(str/includes? % "-----BEGIN")
                               (str/split-lines key)))
          der (pem->der key)]
      (cond
        (str/includes? (str label) "RSA PRIVATE KEY")
        (private-key-from-pkcs1 der)

        (str/includes? (str label) "PRIVATE KEY")
        (private-key-from-pkcs8 der)

        :else
        (throw (ex-info (str "Unsupported PEM private key (expected PKCS#8 "
                             "'BEGIN PRIVATE KEY' or PKCS#1 'BEGIN RSA "
                             "PRIVATE KEY')")
                        {:type :crypto-invalid-key}))))

    (map? key)
    (case (:kty key)
      "RSA" (jwk->rsa-private-key key)
      "EC" (jwk->ec-private-key key)
      (throw (ex-info (str "Unsupported JWK key type " (:kty key)
                           " (expected RSA or EC)")
                      {:type :crypto-invalid-key})))

    :else
    (throw (ex-info "A private key is required (PEM string or JWK map)"
                    {:type :crypto-invalid-key}))))

;; ─── JWT signing (RFC 7515/7519; RS256 + ES256) ───────────────────────────

(defn- ec-der->raw
  "DER-encoded ECDSA signature (r, s INTEGERs) → the fixed-width raw r||s
   form JWT requires (RFC 7515 §3.4); WIDTH bytes per component."
  [der width]
  (let [[r s] (der->unsigned-integers der)
        strip (fn [bs] (if (and (> (alength bs) 1) (zero? (aget bs 0)))
                         (java.util.Arrays/copyOfRange bs 1 (alength bs))
                         bs))
        pad (fn [bs]
              (let [out (byte-array width)]
                (System/arraycopy bs 0 out (- width (alength bs)) (alength bs))
                out))]
    (byte-array (concat (pad (strip (.toByteArray r)))
                        (pad (strip (.toByteArray s)))))))

(defn sign-jwt
  "RFC 7519 JWT signed with :RS256 (default) or :ES256. KEY is a PEM
   string or JWK map (parse-private-key). CLAIMS is a map of
   string/keyword → value; :iat/:exp default to now / now+3600 (epoch
   seconds; numbers pass through). Returns the compact JWT
   header.payload.signature."
  [{:keys [algorithm key claims]}]
  (let [algorithm (or algorithm :RS256)]
    (when-not (contains? #{:RS256 :ES256} algorithm)
      (throw (ex-info (str "Unsupported JWT algorithm " algorithm
                           " (expected :RS256 or :ES256)")
                      {:type :crypto-invalid-config})))
    (let [now (long (/ (System/currentTimeMillis) 1000))
          claims (merge {:iat now :exp (+ now 3600)} claims)
          header (json/generate-string {"alg" (name algorithm) "typ" "JWT"})
          payload (json/generate-string claims)
          signing-input (str (base64url (.getBytes header "UTF-8"))
                             "."
                             (base64url (.getBytes payload "UTF-8")))
          private-key (parse-private-key key)
          signature (case algorithm
                      :RS256 (let [s (java.security.Signature/getInstance "SHA256withRSA")]
                               (.initSign s private-key)
                               (.update s (.getBytes signing-input "UTF-8"))
                               (.sign s))
                      :ES256 (let [s (java.security.Signature/getInstance "SHA256withECDSA")]
                               (.initSign s private-key)
                               (.update s (.getBytes signing-input "UTF-8"))
                               (ec-der->raw (.sign s) 32)))]
      (str signing-input "." (base64url signature)))))
