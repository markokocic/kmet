---
name: mcp
description: Access MCP (Model Context Protocol) servers through kmet's mcp gateway tool. Use when the user asks to work with MCP servers, MCP tools, or when you need tools from servers like filesystem, chrome-devtools, notion, or any other configured MCP server.
---

# MCP access via the `mcp` proxy tool

kmet talks to MCP servers through one lazy `mcp` proxy tool (~200 tokens)
instead of registering every server tool in your context. Servers are
configured in `~/.kmet/agent/mcp.edn` (global) and `.kmet/mcp.edn`
(project) — see the mcp-adapter README for the full config reference.

## Workflow: search → describe → call

Never guess tool names. Discover first (reads the local cache, no server
spawn):

1. **Status** — `mcp({})` shows servers, states, tool counts.
2. **Search** — `mcp({ search: "screenshot" })` finds tools by name or
   description (name matches rank first). `regex: true` for regex search;
   `limit`/`offset` paginate; `includeSchemas: false` drops the parameter
   blocks.
3. **Describe** — `mcp({ describe: "chrome_devtools_take_screenshot" })`
   shows the full parameter list (types, required/optional, defaults,
   enums). If the name is ambiguous across servers, add `server: "..."`.
4. **Call** — `mcp({ tool: "chrome_devtools_take_screenshot", args: { format: "png" } })`.
   Servers connect lazily on first use — the first call may take a moment
   to spawn the server process. `args` is a JSON object; a JSON string is
   also accepted.

Also: `mcp({ server: "name" })` lists one server's tools,
`mcp({ connect: "name" })` connects now (+ refreshes metadata),
`mcp({ disconnect: "name" })` stops a server process.

## Direct tools (opt-in)

Servers with `:direct-tools true` (or a tool name list) in `mcp.edn`
register their tools directly as `server_toolname` — call them like normal
tools without the proxy. They register from cached metadata, so no server
spawns at startup. The `MCP_DIRECT_TOOLS` env var (`server1,server2`, or
`__none__` to disable all) overrides the config for the session.

`includeTools`/`excludeTools` globs on a server (`["server_*"]`) filter
which of its tools register as direct tools; `exposeResources: false`
disables the `read_<resource>` direct tools that are registered for the
server's MCP resources by default. Server `searchKeywords`
(`{"server_*" ["screenshot" "capture"]}`) boost proxy search ranking.

## mcpScript — batch multiple MCP calls

The `mcpScript` tool runs trusted **Clojure** that makes several MCP calls
in one request — loop, filter, chain, or fan out. It runs in a sandboxed
`bb` subprocess with no host access; only the tools bridge:

```clojure
;; discover first (structured, not an {ok data} envelope):
(emit (tools/search {:query "screenshot"}))      ;; {:items [{:path :name :server :description :score}] :total ...}
(emit (tools/describe {:path "chrome_devtools_take_screenshot"}))

;; then call — {:ok true :data ...} or {:ok false :error {:code :message}}:
(let [r (tools/call "chrome_devtools_take_screenshot" {:format "png"})]
  (when (:ok r) (emit (:data r))))

;; loop/filter/fan out; emit() for user-visible output, console/log etc.
(emit (str "processed " (count results) " files"))
```

- `timeoutMs` param (default 30000) bounds the whole script; on timeout
  the worker is killed and in-flight calls appear in the result details
  as incomplete.
- Progress notifications stream into the tool output while calls run.
- Call paths are the prefixed names (`server_toolname`); search/describe
  resolve from cached metadata — connect a server once (`mcp({connect:
  "name"})` or a prior session's cache) before scripting it.
- The flat `tools.<name>(args)` shorthand of pi's JavaScript mcpScript is
  not available in Clojure — always use `(tools/call "name" args)`.

## Prompts → slash commands

Every prompt a server advertises becomes a `/mcp__<server>__<prompt>`
command (from cached metadata, so they exist before any connect).
Arguments map positionally or by name, bash-style quoting supported:

    /mcp__demo__brief day=today "important tasks"

Missing required arguments produce a usage message; the prompt result is
sent into the conversation as a user message. `/mcp prompts` lists all
prompt commands.

## Commands

- `/mcp` — status; `/mcp search <q>`; `/mcp list [server]`; `/mcp prompts`
- `/mcp connect|disconnect <server>`
- `/mcp enable|disable <server>` — writes `.kmet/mcp.edn`; `/reload` to apply
- `/mcp refresh` — reload the EDN config without restarting
- `/mcp auth <server>` / `/mcp logout <server>` — OAuth login/logout for
  HTTP servers (PKCE loopback or device flow)
- `/mcp setup` — interactive panel: add a known server, add a custom
  server, import host configs, scaffold the project config
- `/mcp import` — headless host-config adoption (Cursor/Claude/Codex/
  opencode/windsurf/vscode mcp.json into `.kmet/mcp.edn`)

## Notes

- Search/describe are cache-only — no server is spawned for them.
- After a connect failure, the next use reconnects automatically.
- Servers idle past `:idle-timeout` minutes (settings, default 10; 0
  disables; `:keep-alive` servers never reap) are disconnected by a
  background reaper.
- Large tool results are truncated at 50 KiB / 2000 lines by default and
  the full text is spilled to a temp file; tune with settings
  `:output-guard {:max-bytes .. :max-lines ..}` or disable with
  `:output-guard false` (env kill switch `MCP_OUTPUT_GUARD=0`).
- OAuth tokens are stored in the OS keyring when available (macOS
  `security`, Linux `secret-tool`, Windows Credential Manager), else
  plaintext at `~/.kmet/agent/mcp-oauth.edn` (0600). Settings
  `:token-storage :keyring | :file | :auto` (default `:auto`); env
  `MCP_TOKEN_STORAGE` overrides.
- MCP config is **trusted code execution**: stdio servers run whatever
  `:command` you configure.
