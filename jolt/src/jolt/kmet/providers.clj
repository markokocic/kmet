(ns jolt.kmet.providers
  "kmet's RFC 0014 provider (jolt-port.md §9, jolt/README.md): the
   JDK classes the jolt ecosystem does not supply that kmet's
   crypto/oauth/ADC paths need.

   Currently provided:
     java.net.http.HttpTimeoutException ctor — jolt models the class but
       registers no constructor; kmet's transport-error classifier keys off
       the simple name.

   RSA is not provided here: jolt.crypto supplies it (EC and RSA
   keygen/Signature/KeyFactory) and claims those classes itself — jolt
   allows a class a single provider.

   java.util.Base64 is not provided here either: the runtime implements the
   class, including the MIME pair, and a class the runtime implements can be
   claimed by nobody.

   NO jolt.crypto REQUIRE: this ns needs only jolt.host and clojure.core.
   crypto's classes resolve through crypto's own :jolt/provides claims on
   the first reference, and a declared provider owns the members it
   registers, so no require is needed to pin an order.")

;; ─── install! ─────────────────────────────────────────────────────────────

(defn install!
  "Register everything this lib provides. Idempotent (registrations are
   table merges; members replaced last-wins). Called once at load — the
   final form of this namespace."
  []
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
