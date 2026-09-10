(ns jolt.kmet.providers
  "kmet's RFC 0014 provider (jolt-port.md §8 cause 1, jolt/README.md): the
   JDK classes the jolt ecosystem does not supply that kmet's
   crypto/oauth/ADC paths need.

   Currently provided:
     java.util.Base64/getMimeDecoder  — PEM armor carries newlines, which
       jolt's basic decoder rejects; the MIME decoder discards every
       non-alphabet char (JDK semantics).
     java.net.http.HttpTimeoutException ctor — jolt models the class but
       registers no constructor; kmet's transport-error classifier keys off
       the simple name.

   RSA used to be planned here; jolt.crypto provides it now (EC and RSA
   keygen/Signature/KeyFactory, 2026-09), so the claims for those classes
   moved to jolt.crypto's own :jolt/provides — jolt allows a class a single
   provider. The JWK bigint->DER conversion jolt's Long-backed small bigints
   broke (.toByteArray) is handled in kmet.libs.crypto/bigint->bytes, which
   works on both hosts.

   LOAD ORDER — why jolt.crypto still loads first. jolt.crypto MUST load
   before any registration below runs: its deps.edn :jolt/native loads
   libcrypto/libssl, and its install! registers the crypto java.* classes
   (__register-class-statics! merges into the class's shared table; a
   re-registered member is last-wins). The require is the first form in
   the file, so every load path — jolt's provider autoload (see
   jolt/deps.edn :jolt/provides) or kmet's guarded requires in
   kmet.libs.crypto / kmet.ai.google-adc — gets jolt.crypto fully installed
   before anything here runs."
  (:require [jolt.crypto]))

;; ─── java.util.Base64/getMimeDecoder ──────────────────────────────────────
;; jolt core registers getEncoder/getDecoder/getUrlEncoder/getUrlDecoder
;; (host-static-classes.ss) but not the MIME decoder. The MIME decoder is
;; the basic decoder over the input with every char outside the standard
;; alphabet discarded (JDK semantics — whitespace, stray padding, anything
;; else); the basic decoder would throw on the newlines every PEM body
;; carries.

(def ^:private b64-alphabet
  "RFC 4648 standard base64 alphabet, index -> char."
  "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/")

(def ^:private b64-val
  "Char -> 6-bit value for the standard alphabet."
  (into {} (map-indexed (fn [i c] [c i]) b64-alphabet)))

(defn- b64-decode-mime
  "Decode base64 S (a string, or a byte array read as UTF-8): discard every
   char outside the standard alphabet, then decode 4 chars -> 3 bytes with
   the tail (2 or 3 chars after the stripped padding) yielding 1 or 2
   bytes. Returns a jolt byte-array (the same [B type core's decoder
   returns)."
  [s]
  (let [s (if (string? s) s (String. s "UTF-8"))
        vs (vec (keep b64-val s))
        n (count vs)
        n-full-groups (quot n 4)
        tail (rem n 4)]
    (when (= tail 1)
      (throw (IllegalArgumentException. "Base64: input length mod 4 == 1")))
    (let [out (loop [i 0, acc (transient [])]
                (if (< i (* n-full-groups 4))
                  (let [a (vs i) b (vs (inc i)) c (vs (+ i 2)) d (vs (+ i 3))
                        v (bit-or (bit-shift-left a 18) (bit-shift-left b 12)
                                  (bit-shift-left c 6) d)]
                    (recur (+ i 4)
                           (-> acc
                               (conj! (unchecked-byte (bit-and (bit-shift-right v 16) 255)))
                               (conj! (unchecked-byte (bit-and (bit-shift-right v 8) 255)))
                               (conj! (unchecked-byte (bit-and v 255))))))
                  (let [base (* n-full-groups 4)]
                    (case tail
                      0 acc
                      2 (let [a (vs base) b (vs (inc base))
                              v (bit-or (bit-shift-left a 18) (bit-shift-left b 12))]
                          (conj! acc (unchecked-byte (bit-and (bit-shift-right v 16) 255))))
                      3 (let [a (vs base) b (vs (inc base)) c (vs (+ base 2))
                              v (bit-or (bit-shift-left a 18) (bit-shift-left b 12)
                                        (bit-shift-left c 6))]
                          (-> acc
                              (conj! (unchecked-byte (bit-and (bit-shift-right v 16) 255)))
                              (conj! (unchecked-byte (bit-and (bit-shift-right v 8) 255)))))))))]
      (byte-array (persistent! out)))))

;; ─── install! ─────────────────────────────────────────────────────────────

(defn install!
  "Register everything this lib provides. Idempotent (registrations are
   table merges; members replaced last-wins). Called once at load — the
   final form of this namespace."
  []
  ;; getMimeDecoder: a tagged-table decoder object whose .decode dispatches
  ;; through the methods registered below (jolt.crypto's object model).
  (clojure.core/__register-class-statics!
   "java.util.Base64"
   {"getMimeDecoder" (fn [] (jolt.host/tagged-table :jolt.kmet/b64-mime-decoder))})
  (clojure.core/__register-class-methods!
   :jolt.kmet/b64-mime-decoder
   {"decode" (fn [_self s] (b64-decode-mime s))})

  ;; HttpTimeoutException: the class is modelled but has no constructor.
  ;; jolt.host/throwable builds a real host throwable that answers
  ;; (class e) / ex-message / toString like the JDK's, which is all kmet's
  ;; transport classifier reads.
  (doseq [nm ["HttpTimeoutException" "java.net.http.HttpTimeoutException"]]
    (clojure.core/__register-class-ctor!
     nm
     (fn [msg] (jolt.host/throwable "java.net.http.HttpTimeoutException" (str msg)))))
  ;; JDK hierarchy edge: public class HttpTimeoutException extends
  ;; IOException (jch-closure is transitive, so Exception/Throwable follow).
  ;; Without the row, instance?/catch on the supertypes miss — jolt's class
  ;; graph is open exactly for this (jolt.host/register-class-supers!, merge
  ;; semantics, idempotent).
  (jolt.host/register-class-supers! "java.net.http.HttpTimeoutException"
                                    ["java.io.IOException"])
  nil)

(install!)
