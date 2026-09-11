# jolt/ — kmet's RFC 0014 provider lib

Clojure/JDK shims for the functionality the jolt ecosystem does not supply
that kmet's crypto/oauth/ADC paths need (jolt-port.md §9). This
directory is a self-contained library: `jolt/deps.edn` declares its source
root and its `:jolt/provides` (RFC 0014); kmet's root `deps.edn` pulls it in
as `{:local/root "jolt"}`. It is **inert on bb/JVM** — nothing on the bb
classpath ever requires a `jolt.kmet.*` namespace, and the class registrations
below are jolt-only hooks.

**Scope:** what the jolt runtime lacks and jolt.crypto does not cover:
`java.util.Base64/getMimeDecoder` and the `java.net.http.HttpTimeoutException`
ctor. RSA is jolt.crypto's — it provides `Signature` / `KeyPairGenerator` /
`KeyFactory` for RSA (and EC) and claims those classes itself. **Re-verified
2026-09-11 on
upstream main (`v0.8.6-72-g0f7d1a11`, locally built):** both are still
missing without this provider — a bare `(java.util.Base64/getMimeDecoder)`
answers `No matching field or method: java.util.Base64/getMimeDecoder` and
a bare `(java.net.http.HttpTimeoutException. "x")` answers
`No matching ctor found for class java.net.http.HttpTimeoutException` — so
the lib stays as is (open upstream gaps: `jolt-bugs.md`).

## No `jolt.crypto` require

`jolt.kmet.providers` requires nothing but `jolt.host`. crypto's classes
resolve through crypto's own `:jolt/provides` claims on the first reference:
a declared provider resolves its class whatever loaded first, owns the
members it registers, and is attributed to itself — so no require is needed
to pin an order.

That the require is gone changes nothing about the crypto classes' load
order requirements:

- jolt.crypto's `deps.edn` declares `:jolt/native` — libcrypto/libssl load
  with crypto itself; jolt dedupes natives by `:name`, so an app that also
  pulls http-client shares the one copy.
- jolt.crypto's `install!` registers the crypto `java.*` classes as its
  install namespace loads, and RFC 0014 makes what a declared provider
  implements its own (a registration that would replace one of its
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
| `java.net.http.HttpTimeoutException` ctor | class modelled, no ctor registered (open upstream gap — `jolt-bugs.md`). `jolt.host/throwable` builds a host throwable answering `(class e)`/`ex-message` like the JDK's — all kmet's transport-error classifier reads. |

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
