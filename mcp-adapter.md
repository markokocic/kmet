# kmet mcp-adapter extension — implementation plan (Phase 1)

Status: approved plan — not yet implemented. Rev 2: OAuth (RFC 8414/7591,
PKCE loopback + RFC 8628 device flow) added to Phase 1 scope on request.
This document is the design contract; deviations during implementation must
be reflected back here.

## 1. Goal

Give kmet the pi-mcp-adapter capability: access to MCP servers **without
burning context** on tool definitions. One lazy `mcp` proxy tool (~200
tokens) plus opt-in direct tools, over stdio **and** HTTP MCP servers,
configured from kmet's own EDN files.

**Design source: [pi-mcp-adapter](https://github.com/nicobailon/pi-mcp-adapter)** — local checkout
`~/src/cvstree/pi-mcp-adapter/` is the authoritative reference for this port
(read the .ts modules named in §4 before implementing each counterpart).
Everything below is a faithful port of its architecture and behavior — the
proxy tool surface (`proxy-modes.ts`), direct-tool registration
(`direct-tools.ts`), server lifecycle (`server-manager.ts`), metadata cache
(`metadata-cache.ts`), and command surface (`commands.ts`) — adapted to
kmet's extension contract and babashka (sync, no async/await, EDN config,
no third-party MCP SDK: JSON-RPC is spoken directly). Deviations are
explicit and justified by the Phase-1 scope (§3) or babashka limits (§14),
never by convenience.

## 2. Locked decisions

| Decision | Choice |
|---|---|
| Design source | pi-mcp-adapter — behavioral parity wherever feasible in the Phase-1 subset (§1) |
| Scope | Phase 1 only (see §3 for in/out) |
| Tool name | `mcp` |
| Direct tools | Fully match pi-mcp-adapter semantics (§10.5) |
| Config format | EDN only — JSON never read at runtime |
| Config sources | exactly two: global `~/.kmet/agent/mcp.edn`, project `.kmet/mcp.edn` |
| Porting | none — no pi `mcp.json` reading, no host-config discovery |
| Template | create global `mcp.edn` at init when missing; project file on first enable/disable write |
| HTTP | in Phase 1: streamable HTTP + legacy SSE; auth: static bearer/headers + OAuth (RFC 8414 discovery, RFC 7591 DCR, PKCE loopback + device flow) |
| OAuth machinery | new generic lib `kmet.libs.oauth`, extracted from `kmet.ai.oauth` (device-code poll, PKCE, callback server) + RFC 8414/7591 additions; extension `auth.clj` is a thin adapter; `kmet.ai.oauth` refactored onto the lib — one implementation, no duplication |
| `/mcp serve` | out of scope |
| Entry ns | `extensions.mcp-adapter` (a `kmet.*` prefix is rejected by the loader) |

## 3. Scope

### In scope (Phase 1)

- EDN config loading/merging/template/write-back (§6)
- MCP client: stdio JSON-RPC, streamable HTTP, legacy SSE (§7)
- OAuth client auth for HTTP servers: RFC 8414 metadata discovery, RFC 7591
  dynamic client registration, PKCE loopback + RFC 8628 device flows, token
  refresh, plaintext token store with 0600 perms (§7.8; machinery in the
  new generic lib `kmet.libs.oauth`)
- Proxy tool `mcp` with status/search/describe/call/connect/disconnect (§9)
- Direct tools, pi-parity (§10.5)
- Metadata cache for offline search/describe + startup direct-tool registration (§8)
- Server lifecycle: lazy/eager/keep-alive, reconnect-on-use, cleanup on
  session shutdown / extension unload
- `/mcp` command: status, search, list, connect, disconnect, enable, disable,
  refresh, auth, logout (+ argument completions)
- Usage skill contributed via `:resources-discover`, README

### Out of scope (Phase 2+)

- OAuth client-credentials / JWT-bearer grants, OS-keyring token storage
- Prompts → slash commands, resources → read tool
- `mcpScript` batching tool (node)
- `/mcp serve` (expose kmet as an MCP server, dirge-style)
- Setup wizard / host-config adoption
- `includeTools` / `excludeTools` / `searchKeywords` globs, idle-timeout
  reaping, output guards, streaming tool-call progress (client-side)

## 4. Port mapping (pi-mcp-adapter → kmet)

| pi-mcp-adapter module | kmet file | Ported behavior |
|---|---|---|
| `index.ts` (installMcpAdapter) | `mcp_adapter.clj` | init/shutdown, state, tool registration, events, lifecycle generations simplified to per-server locks |
| `config.ts` (loadMcpConfig, writeProjectServerDisabledOverride) | `config.clj` | precedence merge, disabled override — EDN sources instead of mcp.json, no imports/host discovery (Phase 1) |
| `server-manager.ts` | `client.clj` | server lifecycle, lazy connect, reconnect, stdio+streamable-http+sse transports (no sampling/elicitation handlers) |
| `mcp-auth.ts`, `mcp-oauth-provider.ts`, `oauth-handler.ts`, `mcp-auth-flow.ts`, `mcp-callback-server.ts` | `kmet.libs.oauth` + `auth.clj` | generic machinery → `kmet.libs.oauth` (extracted from `kmet.ai.oauth`, extended with RFC 8414 discovery + RFC 7591 DCR + token exchange/refresh); `auth.clj` is a thin adapter (config → lib calls, token-store file, browser open, status). Extensions cannot require `kmet.ai.*` — the lib is the shared seam; plaintext store instead of OS keyring |
| `proxy-modes.ts` (executeSearch/Describe/Call/Connect/Status) | `proxy.clj` | proxy tool dispatch, search ranking, describe, status text |
| `direct-tools.ts` (resolveDirectTools, createDirectToolExecutor) | `mcp_adapter.clj` (§10.5) | per-server direct-tools opt-in, MCP_DIRECT_TOOLS env, tool-prefix naming, cache-backed registration |
| `metadata-cache.ts` (loadMetadataCache, isServerCacheValid) | `metadata.clj` | persistent cache, config fingerprint, 7-day freshness |
| `commands.ts` (`/mcp` subcommands) | `mcp_adapter.clj` (§10.6) | status/search/list/connect/disconnect/enable/disable/auth/logout (+ refresh); no setup panels (Phase 1) |
| `init.ts` (updateStatusBar, lazyConnect) | `mcp_adapter.clj` | status text, lazy/eager/keep-alive connect, description rebuild |
| `types.ts` (ServerEntry, McpSettings, ToolPrefix) | `config.clj` (§6.2) | same field semantics, kebab-case EDN + tolerant camel reading |
| `skills/` (mcp-scripting) | `skills/mcp/SKILL.md` | usage skill, contributed via `:resources-discover` |

Deliberately not ported in Phase 1 (see §3): `mcp-panel.ts`, `mcp-setup-panel.ts`, `mcp-code.ts` (mcpScript), `tool-approval.ts`, `mcp-output-guard.ts`, `ui-*.ts`, `mcp-script-worker.mjs`, `prompts.ts`, `resource-tools.ts`, `search-ranking.ts` ranking depth (phase 1 keeps name-over-description ranking), `tool-result-renderer.ts` (default rendering; custom render hooks are optional polish, §10.4).

## 5. File layout

```
extensions/mcp-adapter/
├── extension.edn                 {:name "mcp-adapter" :entry "src/extensions/mcp_adapter.clj"}
├── mcp-adapter.md                this plan
├── README.md                     usage, config reference, /mcp reference, security note
├── skills/mcp/SKILL.md           usage skill (contributed via :resources-discover)
├── scripts/
│   ├── fake-mcp-server.bb        fake stdio MCP server (validation)
│   ├── fake-http-mcp-server.bb   fake streamable-HTTP MCP server (validation)
│   └── fake-oauth-server.bb      fake OAuth AS: discovery/DCR/token/authorize/device (validation)
└── src/extensions/mcp_adapter.clj          entry: init/shutdown, state, registration, /mcp
    src/extensions/mcp_adapter/config.clj   EDN config
    src/extensions/mcp_adapter/client.clj   transports + JSON-RPC
    src/extensions/mcp_adapter/auth.clj     OAuth: discovery, DCR, PKCE/device flows, token store
    src/extensions/mcp_adapter/metadata.clj cache
    src/extensions/mcp_adapter/proxy.clj    proxy tool executor
```

Plus one new core file (outside the extension dir):
`src/kmet/libs/oauth.clj` — generic OAuth machinery extracted from
`kmet.ai.oauth` (device-code poll, PKCE, loopback callback server), extended
with RFC 8414 discovery + RFC 7591 DCR + token exchange/refresh;
transport-agnostic (plain `babashka.http-client`, no `kmet.ai.proxy`); the
caller supplies the token store and interaction fns. Unlike the extension
files it sits inside the `bb lint` / `bb test` gates and must satisfy
`kmet.libs.test-self-contained`.

Dependencies allowed: `kmet.extension`, `kmet.tui.*`, `kmet.libs.*`,
`clojure.*`, `babashka.*`, bundled `cheshire.core` + `clojure.core.async` +
`babashka.http-client`. **No `deps.edn` needed** (all libs are bb-bundled and
resolved from the bb classpath).

## 6. Config — `config.clj`

### 6.1 Sources & precedence

- Global: `~/.kmet/agent/mcp.edn`
- Project: `<cwd>/.kmet/mcp.edn` — highest precedence, per-field server
  merge + per-key settings merge (later wins). This is the only file the
  extension writes.
- No other sources. No `--mcp-config` flag.

### 6.2 EDN format

```clojure
{:settings {:direct-tools false           ;; global default for direct tools
            :tool-prefix :server          ;; :server | :none | :short | :mcp
            :disable-proxy-tool false}
 :mcp-servers
 {"chrome-devtools" {:command "npx"
                     :args ["-y" "chrome-devtools-mcp@1.6.0"]
                     :lifecycle :lazy     ;; :lazy | :eager | :keep-alive
                     :direct-tools false  ;; bool | [tool-name ...]
                     :tool-prefix :server
                     :request-timeout-ms 120000
                     :disabled false
                     :env {"KEY" "value"}
                     :cwd "/path"}
  "remote" {:url "https://mcp.example.com/mcp"
            :headers {"Authorization" "Bearer ..."}   ;; or :auth :bearer + :bearer-token
            :auth :bearer
            :bearer-token "sk-..."                    ;; or :bearer-token-env "ENV_VAR"
            :http-transport :streamable-http          ;; :streamable-http | :sse
            :lifecycle :lazy}
  "notion" {:url "https://mcp.notion.com/mcp"
            :auth :oauth                              ;; triggers the §7.8 OAuth flow
            :oauth {:flow :auto                       ;; :auto | :pkce | :device
                    :scopes ["read"]}}}}              ;; :client-id optional → DCR
```

Server key meaning:

| Key | Type | Notes |
|---|---|---|
| `:command` | string or vector | stdio; vector = full argv (merged with `:args`) |
| `:args` | vector of strings | stdio |
| `:env` | map | merged over `System/getenv` (bb `:env` replaces — must merge manually) |
| `:cwd` | string | spawn dir, default cwd |
| `:url` | string | HTTP server (streamable-http or sse) |
| `:headers` | map | static HTTP headers |
| `:auth` | `:bearer` \| `:oauth` \| `false` (default) | auth mode; `:oauth` runs the §7.8 flow |
| `:bearer-token` / `:bearer-token-env` | string | token or env var name; sets `Authorization: Bearer …` when `:auth :bearer` and no explicit header |
| `:oauth` | map \| `false` | `:client-id`/`:client-secret` (omit → RFC 7591 DCR), `:scopes` (string or vector), `:flow` (`:auto` default \| `:pkce` \| `:device`), `:redirect-uri`, `:authorization-server-url`, `:skip-issuer-metadata-validation`; explicit `false` disables |
| `:http-transport` | `:streamable-http` (default) \| `:sse` | forced transport |
| `:lifecycle` | `:lazy` (default) \| `:eager` \| `:keep-alive` | see §10.3 |
| `:direct-tools` | bool \| `[name ...]` | opt-in surface; list = only those tools |
| `:tool-prefix` | `:server` \| `:none` \| `:short` \| `:mcp` | overrides settings |
| `:request-timeout-ms` | number | overrides the 120s default |
| `:disabled` | bool | only literal `true` disables |

A server with neither `:command` nor `:url` is skipped at registration and
surfaces in status as `misconfigured`.

### 6.3 Key normalization

Known keys accepted in camel or kebab form so a file copied from a pi-style
JSON config works with light edits: `:mcpServers`/`:mcp-servers`,
`:directTools`/`:direct-tools`, `:toolPrefix`/`:tool-prefix`,
`:requestTimeoutMs`/`:request-timeout-ms`, `:bearerToken`/`:bearer-token`,
`:bearerTokenEnv`/`:bearer-token-env`, `:httpTransport`/`:http-transport`,
`:disableProxyTool`/`:disable-proxy-tool`, plus `:settings`; nested under
`:oauth`: `:clientId`/`:client-id`, `:clientSecret`/`:client-secret`,
`:redirectUri`/`:redirect-uri`, `:authorizationServerUrl`/`:authorization-server-url`,
`:skipIssuerMetadataValidation`/`:skip-issuer-metadata-validation`. `:lifecycle` /
`:tool-prefix` values accept string or keyword. Unknown keys pass through
unmodified.

### 6.4 Template creation

At init: if `~/.kmet/agent/mcp.edn` does not exist, write it with a
schema-commented starter (both stdio and url examples, settings block).
Project `.kmet/mcp.edn` is never auto-created by init — only by the first
`enable`/`disable` write (§6.5).

### 6.5 enable/disable write

Port of pi's `writeProjectServerDisabledOverride` semantics, onto EDN:
- Read existing `.kmet/mcp.edn` (EDN; parse error → throw with path).
- Disable: set `:disabled true` on the server entry (create entry if absent).
- Enable: remove the `:disabled` key; if the merged *lower* config (global
  file) has the server disabled, write `:disabled false` instead; if the
  entry is now empty, remove the server key.
- Write pretty EDN (`pr-str`, trailing newline); return `{:path :changed}`.
- The command then tells the user to run `/reload` to apply (config is
  loaded at init; a live `refresh` subcommand is the no-reload alternative).

## 7. Client — `client.clj`

### 7.1 Transport abstraction

Every connection is a map with `:transport` (`:stdio` | `:streamable-http` |
`:sse`), `:id-counter`, and transport-specific fields. Public protocol:

```
request!   (conn method params & [{:keys [timeout-ms]}]) → :result map
notify!    (conn method params)                            → nil
close!     (conn)                                          → nil
alive?     (conn)                                          → bool
```

`request!` throws `ex-info` on JSON-RPC error, timeout, or transport death.
Notifications received mid-request are consumed/dropped; stale responses
(non-matching `:id`) are dropped and the loop continues.

### 7.2 stdio transport

- Spawn `(apply proc/process (into argv args) {:in :stream :out :stream
  :err :stream :dir cwd :env (merge (System/getenv) env)})`.
- pid via `(.pid (:proc p))`; `alive?` via `proc/alive?`.
- Reader thread: buffered stdout lines → `cheshire` parse → push to a
  core.async channel (capacity 128); `::eof` marker + close on EOF.
- Stderr drained into a bounded tail (last 20 lines) for diagnostics.
- `request!`: write newline-delimited JSON with `:id`, `alts!!` until the
  matching id or deadline; distinguish timeout (`alts!!` returns the timeout
  channel) from process exit (channel closed / `::eof`).

### 7.3 streamable-http transport

- Per-request POST via `babashka.http-client`:
  `{:headers {"Content-Type" "application/json" "Accept" "application/json, text/event-stream"
              (+ "Mcp-Session-Id" after initialize) (+ auth headers)}
    :body (json/generate-string msg) :as :stream}`.
- `initialize` response: capture `Mcp-Session-Id` (and `Mcp-Protocol-Version`
  if present); remember for subsequent requests.
- Response handling by content type:
  - `application/json`: parse body → it is the response.
  - `text/event-stream`: read lines, collect `data:` payloads, parse each as
    JSON, keep the one matching our `:id` (notifications/other events
    dropped); the server closes the stream after the result.
- Blocking read happens inside a `future`; deref with deadline. On timeout:
  close the HTTP client, throw. **Documented limitation**: the server may
  still complete the call; the result is lost (no abort transport in bb).
- `close!`: close the underlying client.

### 7.4 legacy SSE transport

- GET the `:url` with auth headers; background reader thread parses SSE
  events (`event:` / `data:` lines, reusing `kmet.libs.sse` if its API fits,
  else a ~30-line local parser):
  - `endpoint` event → the POST URL for requests (assume the data is a bare
    path unless it parses as JSON; append relative to the GET url).
  - `message` event → JSON-RPC message → push to the channel (same design as
    stdio).
- `request!`/`notify!`: POST to the endpoint URL (with `Mcp-Session-Id`
  after initialize); responses arrive on the stream channel; id-matched
  waits as in §7.2.
- Session-id and reconnect on stream drop: on channel EOF, next `request!`
  re-GETs the stream and re-runs `initialize` (bounded retry, then throw).

### 7.5 JSON-RPC protocol (all transports)

- Handshake: `initialize` `{:protocolVersion "2025-06-18" :capabilities {}
  :clientInfo {:name "kmet-mcp-adapter" :version "0.1.0"}}` (60s timeout) →
  `notifications/initialized`; record negotiated `protocolVersion` +
  `serverInfo`.
- `tools/list` with cursor pagination (`nextCursor` loop, 30s per page).
- `tools/call` `{:name … :arguments …}` with `:request-timeout-ms` (default
  120s).

### 7.6 Result formatting

Flatten content blocks: `text`/`error` blocks joined with newlines; `image`
→ `[image: <mimeType>, <n> bytes — not rendered]`; empty content with
`structuredContent` → pretty JSON of it; fallback `(no text content)`.
`isError: true` surfaces as `:is-error true` in the tool result.

### 7.7 Errors & diagnostics

Every transport failure includes the stderr tail / HTTP status in the
`ex-info` data. Message pattern:
`MCP connect failed: <cause> (stderr: …)` / `MCP error <code>: <message>` /
`MCP request timed out after Nms: <method>`. / `MCP auth failed: <cause>`
(§7.8).

### 7.8 OAuth (HTTP servers) — `auth.clj` over `kmet.libs.oauth`

Trigger: server config `:auth :oauth` (or an `:oauth` map). Faithful port of
pi's `mcp-auth.ts` / `mcp-oauth-provider.ts` / `oauth-handler.ts` /
`mcp-auth-flow.ts` / `mcp-callback-server.ts`. The extension cannot require
`kmet.ai.*`, so the generic machinery lives in a new shared lib,
`kmet.libs.oauth` — extracted from `kmet.ai.oauth` (device-code poll, PKCE,
loopback callback server, existing raw `java.net.ServerSocket` design) and
extended with RFC 8414 discovery, RFC 7591 DCR and token
exchange/refresh; transport-agnostic, plain `babashka.http-client`.
`kmet.ai.oauth` is refactored onto the same lib (single implementation).
The extension's `auth.clj` is a thin adapter: server config → lib calls,
token-store file wiring, browser open, status text.

1. **Token lookup** (pi `oauth-handler.ts`): per-server entry in
   `~/.kmet/agent/mcp-oauth.edn` — `{:tokens {:access … :refresh … :expires
   ms :scope …} :client-info {:client-id … :redirect-uris […]}}`. Expired →
   refresh (§5); missing/refresh-failed → full flow. Plaintext file, 0600
   perms (bb has no OS keyring — documented tradeoff; pi uses the keyring);
   fixed path, no `MCP_OAUTH_DIR` override in Phase 1.
2. **Discovery** (RFC 8414): GET
   `{url}/.well-known/oauth-authorization-server` →
   `authorization_endpoint` / `token_endpoint` / `registration_endpoint` /
   `device_authorization_endpoint`; fallback to
   `/.well-known/oauth-protected-resource`. Config
   `:authorization-server-url` (or explicit endpoints) skips discovery.
3. **Client registration** (RFC 7591): `:oauth {:client-id …}` in config →
   use it, no DCR (pi `configPreRegistered` path). Otherwise POST
   `registration_endpoint` `{:redirect_uris [loopback-uri]
   :token_endpoint_auth_method "none" :grant_types ["authorization_code"
   "refresh_token"] :response_types ["code"] :client_name "kmet"}` →
   `client_id`; persist with the entry, register once.
4. **Authorization**: default `:flow :auto` — PKCE (S256) with a loopback
   redirect (`http://127.0.0.1:<os-assigned-port>/callback`, port 0 → OS
   assigns; pi `ensureCallbackServer` strictPort=false) when the metadata
   supports it; RFC 8628 device flow (port of `kmet.ai.oauth` device-code
   polling incl. `slow_down`/`interval` handling) when the metadata exposes
   `device_authorization_endpoint` but loopback is unusable (remote/headless
   hosts), or when forced via `:oauth {:flow :device}`. Browser opened via
   the platform helper (`xdg-open` / `open` / `termux-open-url`); device
   flow falls back to printing the URL + polling in place. Manual
   paste-the-redirect-URL path is Phase 2 (pi `auth-complete`).
5. **Tokens**: `grant_type=authorization_code` + `code_verifier` (or device
   grant) → store access/refresh/expires/scope. Requests attach
   `Authorization: Bearer <access>` (§7.3/§7.4); on 401 with a stored
   refresh token, refresh once and retry the request once; on expiry,
   refresh silently before sending. Refresh failure → re-run the flow.
6. **Surfaces**: `/mcp auth <server>` forces a fresh flow; `/mcp logout
   <server>` clears the stored entry (§10.6). Status shows
   logged-in/expired/none (§9.5).

## 8. Metadata cache — `metadata.clj`

- Path: `~/.kmet/agent/mcp-cache.edn`.
- Shape: `{:version 1 :servers {name {:config-fingerprint str
  :fetched-at ms :tools [{:name :description :inputSchema}]}}}`.
- Freshness: 7 days (`fetched-at`). `server-entry` returns nil when stale or
  fingerprint mismatch → callers fall back to a live connect.
- Fingerprint: `pr-str` of sorted server names + `:command/:args/:url/
  :disabled/:direct-tools/:tool-prefix` + relevant settings. A config change
  invalidates cached metadata.
- API: `load-cache`, `save-cache!`, `server-entry`, `update-entry!`,
  `all-tools` (fresh + non-disabled, across servers, `{:server :tool}`).
- Writes via temp-file + rename (atomic-ish), mirroring `kmet.libs.file-lock`
  conventions if a lock is needed (single process — plain write is fine).

## 9. Proxy tool — `proxy.clj`

### 9.1 Tool parameters (JSON schema)

```
tool      (string, optional)  — MCP tool name to call
args      (object, optional)  — arguments as a JSON object
server    (string, optional)  — scope to / disambiguate a server
search    (string, optional)  — search tools by name/description
regex     (boolean, optional)— treat search as regex (default false)
includeSchemas (boolean, optional) — schemas in search output (default true)
describe  (string, optional)  — show a tool's parameters
connect   (string, optional)  — connect a server now (+ refresh)
disconnect(string, optional)  — disconnect a server
list      (string, optional)  — list a server's tools
limit     (number, optional)  — search limit (default 12)
offset    (number, optional)  — search offset (default 0)
```

No params → status. `args` is declared `object`; the registry's
`normalize-args` also accepts a JSON string defensively.

### 9.2 Dispatch

Precedence: `search` → `describe` → `tool` → `connect` → `disconnect` →
`list` → `server` (list that server's tools) → status. Search/describe read
the cache only (no spawn); call/connect ensure a live connection.

### 9.3 Search

Substring (case-insensitive) or regex (`regex: true`, invalid regex →
message). Name matches rank above description matches; both sorted by name;
server-then-name for ties. `limit`/`offset`. Output lines per hit:

```
server_name: tool_name — one-line description
  format (string, default: png)
  fullPage (boolean)
```

(omit the param block when `includeSchemas: false`).

### 9.4 Describe

Full listing: server, tool name, description, each param with type,
required/optional, description, enum/default when present. Ambiguous
(same tool name on multiple servers) → instruct to add `server`.

### 9.5 Status text

Per server: name, lifecycle, state (`idle`/`connecting`/`connected`/
`failed`/`disabled`/`misconfigured`/`unsupported-transport`), auth state
(`bearer` | `oauth` logged-in/expired/none) when configured, tool count
(from cache or live), error tail when failed, cache age. Plus global
settings line (direct-tools default, tool-prefix) and cache file age.

## 10. Entry — `mcp_adapter.clj`

### 10.1 State

```clojure
{:api api
 :config {:mcp-servers {} :settings {}}
 :cache <cache-map-or-nil>
 :servers {name {:definition entry-map
                 :conn (atom nil)      ;; client conn or nil
                 :error (atom nil)     ;; last failure message
                 :lock (Object.)}}     ;; connect mutex
 :registered-direct (atom #{})        ;; registered direct tool names
 :ensure-connected-fn (fn [name])     ;; set at init; used by proxy/direct executors
 :disconnect-fn (fn [name])}
```

### 10.2 init / shutdown

`init` (in order):
1. Load config (`config/load-config`), ensure global `mcp.edn` exists
   (§6.4), build `:servers` from `:mcp-servers`.
2. Register proxy tool (§10.4).
3. Sync direct tools from cache (§10.5).
4. Register `/mcp` command + argument completions (§10.6).
5. Register events: `:session-start`, `:session-shutdown`,
   `:resources-discover` (§10.7).

`shutdown`: close all connections, kill process trees. Idempotent.

### 10.3 Connection lifecycle

- `ensure-connected!` (locked, idempotent): connected + `alive?` → return;
  dead → clear, retry; else connect (dispatch stdio/http by definition),
  handshake, `tools/list`; on success: record `:conn`, clear `:error`,
  refresh cache + save, resync direct tools, rebuild proxy description
  (re-register tool). On failure: record `:error`, throw.
- `disconnect-server!`: `close!`, clear `:conn`; used by `/mcp disconnect`
  and shutdown.
- `:session-start` → background `future` connects of `:eager`/`:keep-alive`
  servers (never blocks session start).
- `:session-shutdown` and extension `shutdown` → disconnect all.
- All servers reconnect-on-use after failure regardless of lifecycle;
  `:eager`/`:keep-alive` differ only in starting at session start.

### 10.4 Proxy tool registration

Plain tool map (runtime consumes by keyword access):

```clojure
{:name "mcp"
 :label "MCP"
 :description (build-proxy-description)   ;; dynamic, see below
 :prompt-snippet "MCP gateway — status, search, describe, and single MCP tool calls"
 :parameters {…§9.1…}
 :execute (fn [params] (proxy/execute @state params))}
```

`build-proxy-description` from config + cache:
`MCP gateway: status, search, describe, call. Servers: chrome-devtools (26
tools), filesystem (5 tools). Servers connect lazily on first use.
search: "screenshot" · tool: "server_tool" args: {...}.`
Re-registered (replaces by name) after every connect/refresh so the system
prompt sees current availability.

### 10.5 Direct tools — pi parity

Opt-in resolution (pi: `MCP_DIRECT_TOOLS` env / per-server / settings):
1. `MCP_DIRECT_TOOLS` env set (not `__none__`) → only listed servers, config
   ignored.
2. `MCP_DIRECT_TOOLS=__none__` → none.
3. Else server `:direct-tools` (bool | name list; list = only those tools),
   else settings `:direct-tools`, else `false`.

Naming (pi `getServerPrefix`): `:server` default → `server_toolname`;
`:mcp` → `mcp_toolname`; `:none`/`:short` → bare toolname with collision
fallback to server prefix. Sanitization: lowercase, `[^a-z0-9_]` → `_`.

Registration:
- From the metadata cache only (never spawns); skip disabled servers and
  url-less/misconfigured ones.
- Tool map: `{:name prefixed :label "MCP: <orig>" :description (or desc
  "(no description)") :prompt-snippet (truncate desc 100) :parameters
  normalized-input-schema :execute (fn [args] (proxy/call-mcp-tool @state
  server orig-tool args opts))}`.
- Schema normalization: ensure `{:type "object" :properties {}}` base;
  pass through properties/required as-is.
- Diff-based resync (`sync-direct-tools!`): register new/updated, unregister
  removed; track names in `:registered-direct`. Runs at init and after every
  connect/refresh.

### 10.6 `/mcp` command

Subcommands (empty = status):

| Subcommand | Behavior |
|---|---|
| `status` | §9.5 text |
| `search <q> [regex]` | §9.3 |
| `list [server]` | all servers, or one server's tools (cache; live fallback on `connect` only) |
| `connect <server>` | ensure-connected (+ cache refresh + tool resync) |
| `disconnect <server>` | close + kill |
| `enable\|disable <server>` | §6.5 write, notify "run /reload to apply" |
| `refresh` | reload EDN config, add/remove servers (disconnecting dropped ones), resync tools, rebuild description |
| `auth <server>` | run the OAuth flow now — fresh login, replaces stored tokens (§7.8) |
| `logout <server>` | clear stored OAuth tokens + client info |

Output: `ui-notify` when `(:has-ui ctx)`, `println` headless. Multi-line
notify behavior verified during implementation; fallback is a
`ui-custom` dialog. Completions: subcommands, then server names for
connect/disconnect/enable/disable/auth/logout.

### 10.7 Events

- `:session-start` → background eager/keep-alive connects (§10.3).
- `:session-shutdown` → disconnect all.
- `:resources-discover` → `{:skill-paths [(str (:extension-dir api)
  "/skills/mcp")]}`.
- No tool-call/result hooks needed (tools return `{:content … :is-error}`).

## 11. Skill + README

- `skills/mcp/SKILL.md`: frontmatter (`name`, `description`), body teaching
  proxy usage (search → describe → call), direct-tool naming, lazy-connect
  note, config reference pointer.
- `README.md`: enabling (symlink `extensions/mcp-adapter` into
  `~/.kmet/agent/extensions/`), first-run template note, EDN config
  reference, `/mcp` reference, direct-tools + env var, HTTP/bearer/OAuth
  notes (PKCE/device flows; plaintext token store at
  `~/.kmet/agent/mcp-oauth.edn`), security warning ("MCP config is trusted
  code execution"), Phase-2
  roadmap.

## 12. Validation plan

1. **Fake stdio server** (`scripts/fake-mcp-server.bb`): bb script
   implementing initialize (negotiation), notifications/initialized echo,
   tools/list (with a cursor page), tools/call (echo + error path), a
   notification mid-request, and clean exit.
2. **Fake HTTP server** (`scripts/fake-http-mcp-server.bb`): minimal
   streamable-http endpoint (POST JSON → JSON or SSE response; session-id;
   a slow call to exercise the timeout path). Also a legacy-SSE variant.
3. **Client tests** (bb script, no test framework needed): stdio +
   streamable-http + sse connect/handshake/pagination/call; timeout;
   process-exit error; disconnect kills the process tree.
4. **Config tests**: precedence merge (global → project), camel/kebab
   normalization, template creation, enable/disable write round-trip
   (including the lower-source-disabled case).
5. **OAuth tests**: `fake-oauth-server.bb` (or extend `fake-http-mcp-server.bb`)
   with `/.well-known/oauth-authorization-server`, registration, token,
   authorize (loopback) and device endpoints — DCR → PKCE loopback → token →
   authenticated request → 401-refresh → device flow → logout. The lib's
   unit tests are registered in `kmet.runner/all-namespaces` (inside the
   normal gates, unlike extension files); the existing `kmet.ai` OAuth tests
   must stay green unchanged, proving the extraction is behavior-neutral.
6. **Extension load**: load the entry against
   `kmet.extension/create-nullable-api` — assert proxy tool, direct tools,
   `/mcp` command, event registrations; then a live smoke in `bb run` with a
   real `mcp.edn` pointing at the fake servers (TUI: status/search/describe/
   call/connect/disconnect/enable/disable/refresh/auth/logout).
7. **Quality**: clj-kondo over the extension files (manual — `bb lint` only
   covers src/test), cljfmt check, plan/README consistency.

## 13. Implementation order

1. `kmet.libs.oauth` — extract generic machinery from `kmet.ai.oauth`
   (device-code poll, PKCE, callback server), add RFC 8414 discovery + RFC
   7591 DCR + token exchange/refresh; refactor `kmet.ai.oauth` onto it.
   Validated by the existing `kmet.ai` OAuth tests staying green — the
   shared foundation lands first, de-risked before any extension work.
2. `config.clj` (+ template + tests)
3. `client.clj` stdio transport (+ fake stdio server validation)
4. `client.clj` http transports (+ fake http server validation)
5. `auth.clj` adapter (+ fake OAuth server validation)
6. `metadata.clj`
7. `proxy.clj`
8. entry `mcp_adapter.clj` (state, tools, direct tools, `/mcp`, events)
9. skill + README, full validation pass (§12)

## 14. Risks & notes

- bb `:env` replaces the environment — merge with `System/getenv` manually.
- `:pid` is absent on bb process records — use `(.pid (:proc p))`.
- HTTP abort is not possible in bb — timeout abandons the read; the server
  may complete the call (documented; tool output lost).
- OAuth tokens are stored plaintext (no OS keyring in bb) — `mcp-oauth.edn`
  written with 0600 perms; `logout` clears. Loopback callback binds an
  OS-assigned local port and is unusable on remote/headless hosts —
  `:flow :device` fallback (auto-selected when the metadata exposes a
  device endpoint but loopback is unavailable). Some servers pre-register
  clients / block DCR — `:oauth {:client-id …}` skips registration. 401s
  without `WWW-Authenticate` are handled by pre-emptive token refresh when
  `:auth :oauth` is configured.
- `kmet.libs.oauth` sits inside the `bb lint` / `bb test` gates and must
  pass `kmet.libs.test-self-contained` (no kmet.* requires) — unlike the
  extension files. The extraction must be behavior-neutral for
  `kmet.ai.oauth`'s provider flows (they keep `kmet.ai.proxy` transport;
  the lib is transport-agnostic).
- Some servers negotiate an older protocol version — accept the server's
  response version; never send `notifications/initialized` before
  `initialize` succeeds.
- Parallel tool calls (kmet default `:execution-mode :parallel`) race on
  connect — the per-server lock serializes; requests after connect are
  id-matched and safe to pipeline.
- Extension dir layout: `extensions/` is outside the `bb lint` gate — lint
  the files manually; keep them clj-kondo-clean anyway.
