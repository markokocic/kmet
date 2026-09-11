# jolt/ — kmet's RFC 0014 provider lib

Clojure/JDK shims for the functionality the jolt ecosystem does not supply
that kmet's crypto/oauth/ADC paths need (jolt-port.md §8 cause 1). This
directory is a self-contained library: `jolt/deps.edn` declares its source
root and its `:jolt/provides` (RFC 0014); kmet's root `deps.edn` pulls it in
as `{:local/root "jolt"}`. It is **inert on bb/JVM** — nothing on the bb
classpath ever requires a `jolt.kmet.*` namespace, and the class registrations
below are jolt-only hooks.

**Scope (2026-09-10):** RSA moved upstream — jolt.crypto now provides
`Signature` / `KeyPairGenerator` / `KeyFactory` for RSA (and EC) and claims
those classes itself. What remains here is what the jolt runtime lacks and
crypto does not cover: `java.util.Base64/getMimeDecoder` and the
`java.net.http.HttpTimeoutException` ctor.

## No `jolt.crypto` require (dropped 2026-09-10)

`jolt.kmet.providers` requires nothing but `jolt.host`. The first-form
`jolt.crypto` require was structural only while this lib re-registered
crypto's asymmetric classes (a last-wins merge that made load order matter);
since RSA moved to jolt.crypto the re-registration is gone and so is the
reason for the require. Dropping it also removed a JOLT_DEBUG false positive:
jolt marks the namespace whose install is loading, and a nested load of
another provider's install namespace keeps the OUTER provider's mark — with
the require in place, crypto's registrations were reported as
`jolt.kmet.providers registers MessageDigest/Signature/… without declaring it`
under `JOLT_DEBUG` (the general nested-provider case, **jolt#926** — fixed
upstream in PR #930, merge `899a2204`, `v0.8.6-42`+; the require stays
dropped for the structural reason above, not for the diagnostics).
jolt#914 (PR #924, `f85adb51`, `v0.8.6-29`+) is what makes
this safe: a declared provider resolves its class whatever loaded first, and
jolt.crypto's own `:jolt/provides` claims resolve its classes on the first
reference.

That the require is gone changes nothing about the crypto classes' load
order requirements:

- jolt.crypto's `deps.edn` declares `:jolt/native` — libcrypto/libssl load
  with crypto itself; jolt dedupes natives by `:name`, so an app that also
  pulls http-client shares the one copy.
- jolt.crypto's `install!` registers the crypto `java.*` classes as its
  install namespace loads, and RFC 0014 makes what a declared provider
  implements its own (jolt#914's registration guard: replacing one of its
  members is dropped).
- jolt.crypto's own `:jolt/provides` claims `Signature` / `KeyPairGenerator`
  / `KeyFactory` (EC and RSA) and the spec classes — a dependent referencing
  one of those autoloads crypto directly, with no kmet involvement.

The one mechanism left is the **guarded require** in the kmet namespaces
that reference the classes directly (`kmet.libs.crypto`,
`kmet.ai.google-adc`) — a top-level
`(when (find-var 'clojure.core/*jolt-version*) (require 'jolt.kmet.providers))`
as the first form after the `ns`. It exists for `java.util.Base64` only: a
claim on it is refused because the runtime implements the class, so the guard
is the only install path (verified: without it, `No matching field or method:
java.util.Base64/getMimeDecoder`). The class kmet claims
(`HttpTimeoutException`) resolves through its own `:jolt/provides` — verified:
a bare `(java.net.http.HttpTimeoutException. "x")` autoloads the provider
with no guard at all.

## What is provided

| class/member | notes |
|---|---|
| `java.util.Base64/getMimeDecoder` | PEM bodies carry newlines; jolt's basic decoder rejects them. MIME rules: discard every non-alphabet char, decode 4→3. Returns the same `[B` type core's decoder returns. |
| `java.net.http.HttpTimeoutException` ctor | class modelled, no ctor registered. `jolt.host/throwable` builds a host throwable answering `(class e)`/`ex-message` like the JDK's — all kmet's transport-error classifier reads. |

## Done upstream / in kmet (2026-09-10)

- **Load-order dependence (jolt#914)** — fixed upstream in jolt PR #924
  (merge `f85adb51`, `v0.8.6-29`+): a declared provider resolves its class
  whatever loaded first. kmet's guarded requires are now needed only for
  `java.util.Base64`; the `HttpTimeoutException` claim and the crypto
  classes resolve through their own `:jolt/provides`. The nested-require
  `JOLT_DEBUG` false positives this lib used to show are gone with the
  `jolt.crypto` require itself (see the section above); the unactionable
  note that used to fire for `java.util.Base64` went with **jolt#926**
  (PR #930, merge `899a2204`, `v0.8.6-42`+): the note no longer advises a
  `:jolt/provides` declaration jolt refuses for a class the runtime
  implements, and a provider reached from another provider's install
  namespace is attributed to itself. The guard remains Base64's only
  install path.
- **RSA** — now provided by jolt.crypto (merged upstream: jolt-lang/crypto#8,
  merge commit `79ecb3d` — the previous pin was the same tree from the fork):
  RSA keygen via
  `RSA_new`/`RSA_generate_key_ex` + `EVP_PKEY_set1_RSA`, `SHA*withRSA` in
  `Signature`, and RSA in `KeyFactory` — all reusing the EVP seam the EC
  code already had. Both kmet RSA tests and the production Google-ADC /
  oauth RS256 paths run green on jolt. The claims for those classes are
  gone from `jolt/deps.edn`: jolt allows a class only one provider, and
  crypto is it now.
- **JWK bigint→DER bytes** — closed in `kmet.libs.crypto` (`8eff434`): the
  DER builders use the portable `bigint->bytes` (a quot/rem digit loop, no
  host `.toByteArray`), so bigints jolt models as `Long`/`BigInt` work on
  both hosts.

## Verification

```sh
# loads standalone: jolt.host only, no jolt.crypto require
jolt -e "(require 'jolt.kmet.providers)"
# shims live:
jolt -e "(println (String. (.decode (java.util.Base64/getMimeDecoder) \"aGVs\nbG8=\") \"UTF-8\"))"   ; hello
jolt -e "(println (class (java.net.http.HttpTimeoutException. \"x\")))"
# kmet guards (bb: no-op):
bb -e "(require 'kmet.libs.crypto) (println :ok)"
jolt -e "(require 'kmet.libs.crypto) (println :ok)"
```
