Agreed. `kmet.libs.http` should be the only outbound HTTP boundary. Neither `babashka.http-client`, `kmet.libs.proxy`, nor direct `curl` invocation should appear in consumers.

Build in three phases:

- **Phase 0** — create `src/kmet/libs/http.clj` (ns `kmet.libs.http`): the
  unified request API + folded-in proxy/curl transport, fully implemented
  and tested (native/curl parity). No caller changes; `kmet.libs.proxy` /
  `kmet.ai.proxy` keep working unchanged.
- **Phase 1** — migrate every internal caller (app, ai, libs, scripts,
  tests, shipped extensions) to `kmet.libs.http`. The old namespaces still
  exist during the migration but end the phase with zero consumers.
- **Phase 2** — delete `src/kmet/libs/proxy.clj`, `src/kmet/ai/proxy.clj`,
  and every remaining `babashka.http-client` require; the boundary guard
  then flips from advisory to enforced.

## Current status (phase 0 — library created, tests green)

Done (committed):

- `src/kmet/libs/http.clj` — the unified transport:
  - `request` / `get` / `post` / `request-json` with `:as` (:string/:bytes/
    :stream), `:timeout-ms` (babashka's `:timeout` key, ms), `:throw?`,
    `:follow-redirects`, `:signal`, `:proxy` (:env/:none/explicit map)
  - responses always `{:status n :headers {...} :body ...}` (lowercased
    headers), both transports
  - errors: `{:type :http-error :status :headers :body}` for HTTP >= 400;
    `{:type :transport-error :cause}` wrapping raw JVM exceptions;
    `transport-error-message` gives the retry classifier's stable
    "network error" token (ConnectException/UnknownHost/timeout/RST)
  - proxy parsing ported from kmet.libs.proxy (HTTPS/HTTP/ALL/SOCKS_PROXY,
    NO_PROXY host/subdomain/port/CIDR/IPv4/IPv6) + fixed bracketed-IPv6
    entries ([::1] / [::1]:8080)
  - plain http proxies → java.net.http via a per-proxy cached client;
    SOCKS/https proxies → curl with status/header parity (--dump-header
    temp file, --fail-with-body, -L, --compressed)
  - curl path: credentials/headers in a protected temp config file, never
    argv; `abort!` kills the tree; `close!` reaps the process, deletes
    temp files, reports mid-stream transport failures
  - `:timeout` maps to babashka's `:timeout` (ms) — NOT `:request-timeout`
    (which is ineffective; verified empirically)
- `test/kmet/libs/test_http.clj` — 19 tests / 49 assertions, all green:
  proxy parsing (incl. lowercase env, socks5h default, NO_PROXY variants),
  native transport (get/headers/method+body/json-encode/request-json/
  throw-true/throw-false/bytes/stream/transport-error/timeout-ms),
  curl transport via a real in-test SOCKS5 proxy (RFC 1928 server:
  handshake + bidirectional pump) — stream, throw-false, missing-curl
- `kmet.libs.proxy` / `kmet.ai.proxy` untouched and still passing their
  tests (phase 0 is additive; no caller changed)
- the self-contained libs guard passes (http.clj only requires
  kmet.libs.process)

Known remaining issues before phase 1:

- `request` passes `:proxy` to `native-request` and `curl-request` but the
  curl path's `:proxy` handling and the explicit-proxy-map branch are
  exercised only through the test redefs; a direct `(http/request {:proxy
  {...}})` integration test is still missing
- `curl-request`'s `:throw? false` on HTTP >= 400 returns
  `{:status :headers :body}` but the `:as :bytes` curl branch is untested
- `http/abort!` (kill the tree) has no test yet — only `close!` is covered
- `test_http.clj` is NOT yet in `kmet.runner/all-namespaces` (so `bb test`
  full runs skip it); add it when the suite is stable
- `bb lint` (clj-kondo) was not run (clj-kondo not installed here)

## 1. Define the public HTTP API

Create `src/kmet/libs/http.clj` with a transport-neutral API (the
ns docstring marks it the single outbound-HTTP boundary; the API stays
close to babashka.http-client's so migration is mostly mechanical):

```clojure
(http/request {:url ...
               :method :get
               :headers {}
               :body ...
               :as :string       ; :string | :bytes | :stream
               :timeout-ms 30000
               :throw? true
               :follow-redirects :normal
               :signal cancel-atom
               :proxy :env})     ; :env | :none | explicit proxy

(http/get url opts)
(http/post url opts)
(http/request-json opts)

(http/abort! response)
(http/close! response)
```

Responses should consistently expose:

```clojure
{:status 200
 :headers {...}
 :body ...}
```

Transport details such as curl processes, PIDs, and Java clients remain
private. The wrapper caches/reuses one `babashka.http-client` client per
proxy configuration (direct, and per HTTP-proxy host/port/credentials)
inside an atom keyed by config — it must not expose `babashka.http-client/client`
or accept `:client` in opts. `:proxy` accepts `:env` (default) | `:none` | an
explicit parsed-proxy map. One deliberate spec deviation: `:timeout` is
accepted as ms but `:timeout-ms` is the canonical key (see §3).

## 2. Fold proxy handling into `kmet.libs.http`

Move the useful logic from `src/kmet/libs/proxy.clj` into private implementation functions in `kmet.libs.http`:

- `HTTPS_PROXY`, `HTTP_PROXY`, `ALL_PROXY`
- upper/lower-case variants
- `NO_PROXY` host, subdomain, port, CIDR, IPv4/IPv6 handling
- proxy credentials
- direct/plain HTTP proxy ΓåÆ `babashka.http-client`
- SOCKS and TLS-speaking HTTPS proxy ΓåÆ curl

Use `kmet.libs.process` for:

- portable PID lookup
- tracked child cleanup
- `setsid`
- process-tree cancellation
- Windows `taskkill`

Then delete `kmet.libs.proxy`; do not retain `proxy-for-url`, `curl-post`, `finish-curl!`, or `java-client` as consumer-facing APIs.

## 3. Make curl behavior equivalent to the native transport

This needs more than moving the current implementation. Today the curl path
loses headers/status and `request-json` hardcodes status 200, which would
break MCP authentication and content-type handling. The curl adapter is
implemented inside `kmet.libs.http` and must match the native path's
contract: `--fail-with-body` + `--dump-header` to a temp file (read back
for `:headers`; status parsed from the header block — curl uses `-w
%{http_code}` as a fallback seam), `-L` when `:follow-redirects` is set,
`--compressed` for transparent gzip, and `-w %{http_code}` (or header
inspection) so a non-2xx with `:throw? false` still returns
`{:status n :headers ... :body ...}` instead of throwing.

The curl adapter should:

- support all required methods, not only GET/POST
- preserve response status and headers
- handle redirects
- support string, bytes, and streaming bodies
- honor millisecond timeouts
- distinguish HTTP errors from curl/transport failures
- return non-2xx responses when `:throw? false`
- include `:status`, `:headers`, and `:body` in structured exceptions
- expose a managed stream whose EOF/close reaps and untracks curl
- make `http/abort!` terminate the process tree
- propagate curl failures while reading, so callers see an error instead of a false clean EOF
- avoid putting authorization headers or proxy credentials in process argv; use a protected temporary curl config/header file and clean it up

This should eliminate the transport-specific `finish-curl!` protocol entirely.

## 4. Preserve AI provider hooks without preserving `ai.proxy`

Replace `src/kmet/ai/proxy.clj` with a thin `src/kmet/ai/http.clj` decorator
(provider hooks stay in `kmet.ai.hooks`, which must stop depending on
`kmet.ai.proxy` — its current re-exports collapse once `post-stream` moves):

1. apply `before-provider-headers`
2. delegate to `kmet.libs.http/request`
3. emit `after-provider-response`

It contains no networking itself. Provider implementations then use `kmet.ai.http`, while OAuth, model generation, and other ordinary calls use `kmet.libs.http` directly.

Migrate these provider files (all 9 currently call `proxy/post-stream`;
each switches to `kmet.ai.http/request` with `:as :stream` and drops the
now-unneeded `:client` handling):

- `src/kmet/ai/api/anthropic_messages.clj`
- `azure_openai_responses.clj`
- `bedrock_converse_stream.clj`
- `google_generative_ai.clj`
- `google_vertex.clj`
- `mistral_conversations.clj`
- `openai_codex_responses.clj`
- `openai_completions.clj`
- `openai_responses.clj`

Replace `abort-stream!` with `http/abort!`; remove `finish-curl!` and checks such as `(:proc response)`. The `network-exception-classes` / `http2-stream-reset-regex` / `http-error-message` / `transport-error-message` classification in `kmet.ai.api.shared` (currently read off babashka exceptions) moves into `kmet.libs.http` so every caller gets stable retryable error tokens regardless of transport — the shared ns re-exports them.

## 5. Migrate every other outbound HTTP caller

### Core/AI/library code

- `src/kmet/libs/oauth.clj`
  - use `kmet.libs.http` (its `fetch-json` keeps OAuth-specific parsing/error mapping, delegating the transport)
  - extensions reach proxy support transitively through this — today the lib is deliberately transport-agnostic, so this is a behavior change for the mcp-adapter's OAuth (see §6)
- `src/kmet/ai/oauth.clj` — `proxy/request-json` → `http/request-json` (the new lib)
- `src/kmet/ai/google_adc.clj` — same
- `src/kmet/ai/image_models.clj` — same
- `src/kmet/ai/model_gen.clj` — `http/get` with `:throw false` → `http/get` on the new lib; error mapping stays in the callers
- `src/kmet/libs/aws_sigv4.clj` — only signs; no transport change
- `scripts/generate_image_models.clj` — `kmet.ai.model-gen` covers the generation path
- `src/kmet/build.clj`
  - replace its direct curl helper with streamed `http/get` (follows redirects by default)
  - preserve GitHub redirect and atomic-download behavior
  - keep the release-binary download path; the `-o` temp-file + atomic move stays

### Shipped extensions

- `extensions/mcp-adapter/.../client.clj`
  - replace direct `babashka.http-client`
  - remove raw client objects from connection maps
  - store/abort the active legacy SSE response on disconnect
  - route POST, SSE GET, 401 retry, and OAuth through the wrapper
- `extensions/mcp-adapter/.../auth.clj`
  - gains proxy support transitively through `kmet.libs.oauth`
- `extensions/tree-sitter/.../fetch.clj`
  - use streamed `http/get`
  - replace the currently ineffective `:request-timeout` option with the wrapperΓÇÖs `:timeout-ms`

Also migrate test-only `babashka.http-client` calls (`test/kmet/ai/test_llm.clj` error-message test, `test/kmet/ai/test_oauth.clj`) so the boundary can be enforced repository-wide.

## 6. Expose the wrapper to extensions

Update `src/kmet/app/extensions.clj`:

- add `kmet.libs.http` explicitly to `libs-library-namespaces`
- ensure it is loaded before SCI contexts are built
- allow extensions to require `[kmet.libs.http :as http]`
- reject direct `[babashka.http-client ...]` requires with an actionable message
- validate every internal extension namespace, not only the entry namespace

Direct curl execution cannot be a security restriction because extensions can spawn arbitrary commands, but shipped extensions and documented extension code should follow the boundary.

## 7. Add enforcement

Add an architecture test that fails when:

- any production/script/extension namespace except `kmet.libs.http` requires `babashka.http-client`
- anything requires `kmet.libs.proxy` or `kmet.ai.proxy`
- source outside `kmet.libs.http` directly invokes `"curl"`

Land the strict guard in phase 2, once every internal caller is migrated —
during phase 1 the not-yet-migrated `kmet.libs.proxy`, `kmet.ai.proxy`,
`kmet.build` and the two extensions still legitimately use the raw
transport. To prevent drift during the migration, phase 1 adds an
inventory test that lists the remaining `babashka.http-client`/
`kmet.*.proxy` users and fails when a *new* one appears.

This prevents the abstraction from eroding later.

## 8. Tests

Create `test/kmet/libs/test_http.clj` (the proxy-focused
`test/kmet/libs/test_proxy.clj` stays until phase 2; the new suite is a
superset). Cover:

- proxy env precedence and lowercase variants
- `NO_PROXY` exact host, subdomain, port, CIDR, wildcard, IPv6
- native versus curl transport selection
- methods, headers, body, redirects, compression
- string/bytes/stream response parity
- status and response headers on the curl path
- `:throw? false`
- structured HTTP and transport errors — `ex-data` carries `:status`/`:headers`/`:body` for HTTP errors; curl failures are distinguishable from HTTP errors (see §3)
- timeout conversion (`:timeout` ms → curl `--max-time` seconds, native `:request-timeout` ms)
- cancellation and PID cleanup (watch-cancel pattern, `abort!` kills the tree)
- closing a stream early (reaps + untracks the curl pid)
- missing curl
- no credentials in argv (auth header/proxy creds via a protected temp curl config/header file, removed on cleanup)
- env-map injection seam (proxy parsing must stay testable without touching the process env)

Add local-server integration tests for both ordinary and streaming responses, plus an extension-context test proving `kmet.libs.http` works from SCI and direct `babashka.http-client` is rejected (the SCI-context part lands with the phase-1 `extensions.clj` exposure, §6).

## 9. Documentation

Update (phase 2):

- `AGENTS.md`: outbound HTTP must go through `kmet.libs.http`
- `README.md`: replace the `ai/proxy.clj` layout entry
- `extensions/extensions.md`: document the extension HTTP API and automatic proxy behavior
- `extensions/README.md`
- MCP and tree-sitter READMEs/comments
- `extensions/tree-sitter/deps.edn` comment

`http.md` itself becomes the design contract for `kmet.libs.http` and is updated as the design changes.

## Suggested implementation order

### Phase 0 (library lands; no callers change)

1. Add `src/kmet/libs/http.clj` + `test/kmet/libs/test_http.clj` (unit +
   local-server integration tests), porting `kmet.libs.proxy`'s logic
   behind the private boundary.
2. Curl-adapter parity (§3): status/headers/redirects/bytes/streams/
   timeouts/structured errors; client caching + env-map seam; no
   credentials in argv.
3. Run `bb lint-changed` / `bb format-changed` / `bb test-changed` on the
   new namespace. Everything else keeps using the old transport — a green
   full suite proves the library is additive.

### Phase 1 (migration; old namespaces kept)

4. Add `src/kmet/ai/http.clj` decorator (provider hooks) and move the
   error-classification helpers out of `kmet.ai.api.shared`.
5. Migrate AI callers: the 9 wire APIs (`post-stream` →
   `ai.http/request :as :stream`), `ai/oauth.clj`, `ai/google_adc.clj`,
   `ai/image_models.clj`, `ai/model_gen.clj`.
6. Migrate library callers: `libs/oauth.clj`, `test_llm.clj`'s error test,
   `test_oauth.clj`.
7. Migrate extensions: `mcp-adapter/client.clj` (+ `auth.clj`
   transitively), `tree-sitter/fetch.clj`; expose `kmet.libs.http` in
   `extensions.clj`.
8. Migrate `src/kmet/build.clj` curl helper.
9. Add the phase-1 inventory guard (§7) — fails when a *new*
   `babashka.http-client` / `kmet.*.proxy` user appears.
10. Run changed-file gates (`bb test-changed`, `bb lint-changed`,
    `bb format-changed`) + tree-sitter extension tests + MCP validation
    scripts.

### Phase 2 (old boundary removed)

11. Delete `src/kmet/libs/proxy.clj`, `src/kmet/ai/proxy.clj`, and
    `test/kmet/libs/test_proxy.clj` (its coverage moved into
    `test_http.clj`); delete the `post-stream`/`finish-curl!`/
    `abort-stream!` machinery everywhere.
12. Flip the architecture guard from inventory to strict (any require of
    `babashka.http-client`, `kmet.libs.proxy`, `kmet.ai.proxy`, or a
    direct `"curl"` invocation outside `kmet.libs.http` fails the build).
13. Update docs (AGENTS.md, README.md, extensions docs).
14. Full gates: `bb lint`, `bb format-check`, `bb test`, `bb test-ext`.

Scope-wise, this covers outbound HTTP(S) initiated by kmet and shipped extensions. Inbound OAuth callback sockets, Maven traffic internal to `borkdude.deps`, and arbitrary user-launched subprocesses are separate boundaries.
