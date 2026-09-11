# jolt/ — kmet's RFC 0014 provider lib

Clojure/JDK shims for the functionality the jolt ecosystem does not supply
that kmet's transport-error classifier needs (jolt-port.md §9). This
directory is a self-contained library: `jolt/deps.edn` declares its source
root and its `:jolt/provides` (RFC 0014); kmet's root `deps.edn` pulls it in
as `{:local/root "jolt"}`. It is **inert on bb/JVM** — nothing on the bb
classpath ever requires a `jolt.kmet.*` namespace, and the class registrations
below are jolt-only hooks.

**Scope:** what the jolt runtime lacks and jolt.crypto does not cover:
the `java.net.http.HttpTimeoutException` ctor. RSA is jolt.crypto's — it
provides `Signature` / `KeyPairGenerator` / `KeyFactory` for RSA (and EC)
and claims those classes itself. `java.util.Base64` is runtime surface
(the MIME pair is registered with the other statics), so nothing here.
**Re-verified 2026-09-11 on `v0.8.6-86-g234f460b` (locally built):** a bare
`(java.net.http.HttpTimeoutException. "x")` answers
`No matching ctor found for class java.net.http.HttpTimeoutException` — so
the lib stays for that one gap (open upstream: `jolt-bugs.md`).

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

No guarded require is needed either: the class kmet claims
(`HttpTimeoutException`) resolves through its own `:jolt/provides` —
verified: a bare `(java.net.http.HttpTimeoutException. "x")` autoloads the
provider with no guard at all.

## What is provided

| class/member | notes |
|---|---|
| `java.net.http.HttpTimeoutException` ctor | class modelled, no ctor registered (open upstream gap — `jolt-bugs.md`). `jolt.host/throwable` builds a host throwable answering `(class e)`/`ex-message` like the JDK's — all kmet's transport-error classifier reads. |

## Verification

```sh
# loads standalone: jolt.host only, no jolt.crypto require
jolt -e "(require 'jolt.kmet.providers)"
# shim live:
jolt -e "(println (class (java.net.http.HttpTimeoutException. \"x\")))"
# kmet loads (bb: no-op):
bb -e "(require 'kmet.libs.crypto) (println :ok)"
jolt -e "(require 'kmet.libs.crypto) (println :ok)"
```
