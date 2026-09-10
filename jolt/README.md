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

## Load order — jolt.crypto first (non-negotiable)

`jolt.kmet.providers` requires `jolt.crypto` as its **first form**, and every
registration happens after that require. That is structural, not incidental:

- jolt.crypto's `deps.edn` declares `:jolt/native` — libcrypto/libssl load
  before its namespace; jolt dedupes natives by `:name`, so an app that also
  pulls http-client shares the one copy.
- jolt.crypto's `install!` registers the crypto `java.*` classes.
  `__register-class-statics!` **merges** into a class's shared member table
  with last-wins per member, so registrations extend cleanly.
- jolt.crypto's own `:jolt/provides` claims `Signature` / `KeyPairGenerator`
  / `KeyFactory` (EC and RSA) and the spec classes — a dependent referencing
  one of those autoloads crypto directly, with no kmet involvement.

Two mechanisms carry kmet's remaining shims:

1. **`jolt/deps.edn` `:jolt/provides`** claims `HttpTimeoutException`,
   mapping to `jolt.kmet.providers`. The first reference to it autoloads us —
   and we load jolt.crypto first.
2. **Guarded requires** in the kmet namespaces that reference the classes
   directly (`kmet.libs.crypto`, `kmet.ai.google-adc`) — a top-level
   `(when (find-var 'clojure.core/*jolt-version*) (require 'jolt.kmet.providers))`
   as the first form after the `ns`. This covers `java.util.Base64`, which
   cannot be claimed because the runtime implements the class.

`java.util.Base64` is deliberately **not** claimed (jolt refuses claims on
classes it implements); its missing members are added at install instead.

## What is provided

| class/member | notes |
|---|---|
| `java.util.Base64/getMimeDecoder` | PEM bodies carry newlines; jolt's basic decoder rejects them. MIME rules: discard every non-alphabet char, decode 4→3. Returns the same `[B` type core's decoder returns. |
| `java.net.http.HttpTimeoutException` ctor | class modelled, no ctor registered. `jolt.host/throwable` builds a host throwable answering `(class e)`/`ex-message` like the JDK's — all kmet's transport-error classifier reads. |

## Done upstream / in kmet (2026-09-10)

- **RSA** — now provided by jolt.crypto (`feat/rsa`): RSA keygen via
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
# load order: jolt.crypto before jolt.kmet.providers
jolt -e "(require 'jolt.kmet.providers)"
# shims live:
jolt -e "(println (String. (.decode (java.util.Base64/getMimeDecoder) \"aGVs\nbG8=\") \"UTF-8\"))"   ; hello
jolt -e "(println (class (java.net.http.HttpTimeoutException. \"x\")))"
# kmet guards (bb: no-op):
bb -e "(require 'kmet.libs.crypto) (println :ok)"
jolt -e "(require 'kmet.libs.crypto) (println :ok)"
```
