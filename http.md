Agreed. `kmet.libs.http` should be the only outbound HTTP boundary. Neither `babashka.http-client`, `kmet.libs.proxy`, nor direct `curl` invocation should appear in consumers.

## 1. Define the public HTTP API

Create `src/kmet/libs/http.clj` with a transport-neutral API:

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

Transport details such as curl processes, PIDs, and Java clients remain private. The wrapper should cache/reuse Java clients internally rather than expose `babashka.http-client/client`.

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

This needs more than moving the current implementation. Today the curl path loses headers/status and `request-json` hardcodes status 200, which would break MCP authentication and content-type handling.

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

Replace `src/kmet/ai/proxy.clj` with a thin `src/kmet/ai/http.clj` decorator:

1. apply `before-provider-headers`
2. delegate to `kmet.libs.http/request`
3. emit `after-provider-response`

It contains no networking itself. Provider implementations then use `kmet.ai.http`, while OAuth, model generation, and other ordinary calls use `kmet.libs.http` directly.

Migrate these provider files:

- `src/kmet/ai/api/anthropic_messages.clj`
- `azure_openai_responses.clj`
- `bedrock_converse_stream.clj`
- `google_generative_ai.clj`
- `google_vertex.clj`
- `mistral_conversations.clj`
- `openai_codex_responses.clj`
- `openai_completions.clj`
- `openai_responses.clj`

Replace `abort-stream!` with `http/abort!`; remove `finish-curl!` and checks such as `(:proc response)`.

## 5. Migrate every other outbound HTTP caller

### Core/AI/library code

- `src/kmet/libs/oauth.clj`
  - use `kmet.libs.http`
  - retain OAuth-specific parsing/error mapping here, not in the generic HTTP layer
- `src/kmet/ai/oauth.clj`
- `src/kmet/ai/google_adc.clj`
- `src/kmet/ai/image_models.clj`
- `src/kmet/ai/model_gen.clj`
- `scripts/generate_image_models.clj`
- `src/kmet/build.clj`
  - replace its direct curl helper with streamed `http/get`
  - preserve GitHub redirect and atomic-download behavior

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

Also migrate test-only `babashka.http-client` calls so the boundary can be enforced repository-wide.

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

This prevents the abstraction from eroding later.

## 8. Tests

Create `test/kmet/libs/test_http.clj`, replacing the proxy-focused suite. Cover:

- proxy env precedence and lowercase variants
- `NO_PROXY` exact host, subdomain, port, CIDR, wildcard, IPv6
- native versus curl transport selection
- methods, headers, body, redirects, compression
- string/bytes/stream response parity
- status and response headers on the curl path
- `:throw? false`
- structured HTTP and transport errors
- timeout conversion
- cancellation and PID cleanup
- closing a stream early
- missing curl
- no credentials in argv

Add local-server integration tests for both ordinary and streaming responses, plus an extension-context test proving `kmet.libs.http` works from SCI and direct `babashka.http-client` is rejected.

## 9. Documentation

Update:

- `AGENTS.md`: outbound HTTP must go through `kmet.libs.http`
- `README.md`: replace the `ai/proxy.clj` layout entry
- `extensions/extensions.md`: document the extension HTTP API and automatic proxy behavior
- `extensions/README.md`
- MCP and tree-sitter READMEs/comments
- `extensions/tree-sitter/deps.edn` comment

## Suggested implementation order

1. Add `kmet.libs.http` and its unit/integration tests.
2. Add the AI hook decorator.
3. Migrate AI and library callers.
4. Migrate MCP and tree-sitter extensions.
5. Migrate build/generator scripts.
6. Delete both proxy namespaces.
7. Add the architectural guard and update docs.
8. Run changed-file gates plus the tree-sitter tests and MCP validation scripts.

Scope-wise, this covers outbound HTTP(S) initiated by kmet and shipped extensions. Inbound OAuth callback sockets, Maven traffic internal to `borkdude.deps`, and arbitrary user-launched subprocesses are separate boundaries.
