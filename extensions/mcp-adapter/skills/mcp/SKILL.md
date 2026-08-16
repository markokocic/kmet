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

## Commands

- `/mcp` — status; `/mcp search <q>`; `/mcp list [server]`
- `/mcp connect|disconnect <server>`
- `/mcp enable|disable <server>` — writes `.kmet/mcp.edn`; `/reload` to apply
- `/mcp refresh` — reload the EDN config without restarting
- `/mcp auth <server>` / `/mcp logout <server>` — OAuth login/logout for
  HTTP servers (PKCE loopback or device flow)

## Notes

- Search/describe are cache-only — no server is spawned for them.
- After a connect failure, the next use reconnects automatically.
- OAuth tokens are stored plaintext at `~/.kmet/agent/mcp-oauth.edn`
  (0600 perms) — there is no OS keyring in Babashka.
- MCP config is **trusted code execution**: stdio servers run whatever
  `:command` you configure.
