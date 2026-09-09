# jolt/ — kmet's RFC 0014 provider lib

Clojure/JDK shims for the functionality the jolt ecosystem does not supply
that kmet's crypto/oauth/ADC paths need (jolt-port.md §8 cause 1). This
directory is a self-contained library: `jolt/deps.edn` declares its source
root and its `:jolt/provides` (RFC 0014); kmet's root `deps.edn` pulls it in
as `{:local/root "jolt"}`. It is **inert on bb/JVM** — nothing on the bb
classpath ever requires a `jolt.kmet.*` namespace, and the class registrations
below are jolt-only hooks.

## Load order — jolt.crypto first (non-negotiable)

`jolt.kmet.providers` requires `jolt.crypto` as its **first form**, and every
registration happens after that require. That is structural, not incidental:

- jolt.crypto's `deps.edn` declares `:jolt/native` — libcrypto/libssl load
  before its namespace, and our FFI bindings (RSA, next) resolve against the
  same loaded copy (jolt dedupes natives by `:name`).
- jolt.crypto's `install!` registers the EC/symmetric `java.*` classes.
  `__register-class-statics!` **merges** into a class's shared member table
  with last-wins per member, so our registrations extend/replace cleanly.

Why the require alone isn't enough — and what makes it deterministic:

- jolt autoloads a provider only when a referenced class is **unregistered**.
- jolt.crypto registers EC-only `Signature` / `KeyPairGenerator` /
  `KeyFactory` at install time as a side effect of its own autoload (any
  `javax.crypto.Mac`/`Cipher` reference pulls it). Once registered, those
  classes never trigger a provider lookup again — so without this setup, a
  namespace that compiled after jolt.crypto loaded would bind the EC-only
  registrations and RSA would be unreachable.

Two complementary mechanisms close that gap:

1. **`jolt/deps.edn` `:jolt/provides`** claims the asymmetric classes +
   `HttpTimeoutException`, mapping to `jolt.kmet.providers`. The first
   reference to any of them autoloads us — and we load jolt.crypto first.
2. **Guarded requires** in the kmet namespaces that reference these classes
   directly (`kmet.libs.crypto`, `kmet.ai.google-adc`) — a top-level
   `(when (find-var 'clojure.core/*jolt-version*) (require 'jolt.kmet.providers))`
   as the first form after the `ns`. This covers the poisoned case above
   (jolt.crypto already loaded via `Mac`/`Cipher`) and any class that cannot
   be claimed because the runtime implements it (`java.util.Base64`).

`java.util.Base64` is deliberately **not** claimed (jolt refuses claims on
classes it implements); its missing members are added at install instead.

## What is provided

| class/member | notes |
|---|---|
| `java.util.Base64/getMimeDecoder` | PEM bodies carry newlines; jolt's basic decoder rejects them. MIME rules: discard every non-alphabet char, decode 4→3. Returns the same `[B` type core's decoder returns. |
| `java.net.http.HttpTimeoutException` ctor | class modelled, no ctor registered. `jolt.host/throwable` builds a host throwable answering `(class e)`/`ex-message` like the JDK's — all kmet's transport-error classifier reads. |

## Planned (next)

- **RSA** `KeyPairGenerator` / `KeyFactory` / `Signature` — re-register the
  statics jolt.crypto installs with an RSA+EC dispatcher (EC delegates by
  rebuilding jolt.crypto's tagged tables; RSA via libcrypto `EVP`, the same
  FFI seam). Unblocks kmet production Google-ADC RS256 login + the RS256 JWT
  paths and the RSA test fixtures.
- **JWK bigint→DER bytes** — kmet's DER building calls `.toByteArray` on
  values jolt models as `Long` when small (the JWK tests' `No matching field
  found: toByteArray for class java.lang.Long`).

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
