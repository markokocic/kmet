# mcp-adapter

MCP server access for kmet, ported from
[pi-mcp-adapter](https://github.com/nicobailon/pi-mcp-adapter)
(design contract: [`../../mcp-adapter.md`](../../mcp-adapter.md) — see
`extensions/README.md` for the extension system overview).

The point is context economy: instead of hundreds of tool definitions
burning your context window, you get one `mcp` proxy tool (~200 tokens).
The agent discovers what it needs on demand with `search`/`describe`, and
servers connect lazily — only when a tool is actually called.

**Status: planned (Phase 1) — implementation pending.** The behavior below
is the approved design; see `../../mcp-adapter.md` for the full plan.

## Enabling

Extensions load from `~/.kmet/agent/extensions/` (global) and
`.kmet/extensions/` (project-local) at startup and on `/reload`. Enable by
symlinking or copying this directory:

```bash
mkdir -p ~/.kmet/agent/extensions
ln -s "$PWD/extensions/mcp-adapter" ~/.kmet/agent/extensions/mcp-adapter
```

Restart kmet or run `/reload` to pick it up. On first load the extension
creates `~/.kmet/agent/mcp.edn` with a commented starter template when it
doesn't exist yet.

## Configuration (EDN only)

Exactly two files:

| File | Scope | Precedence |
|---|---|---|
| `~/.kmet/agent/mcp.edn` | global | lower |
| `.kmet/mcp.edn` (project) | per-project | higher — the only file the extension writes |

Per-field server merge, project wins. Keys are read in kebab or camel form,
so copying content from a pi-style JSON config is a light edit.

```clojure
{:settings {:direct-tools false           ;; global default for direct tools
            :tool-prefix :server          ;; :server | :none | :short | :mcp
            :disable-proxy-tool false}
 :mcp-servers
 {"chrome-devtools" {:command "npx"
                     :args ["-y" "chrome-devtools-mcp@1.6.0"]
                     :lifecycle :lazy     ;; :lazy | :eager | :keep-alive
                     :direct-tools false  ;; bool | [tool-name ...]
                     :disabled false
                     :env {"KEY" "value"}
                     :cwd "/path"}
  "remote" {:url "https://mcp.example.com/mcp"
            :auth :bearer                  ;; bearer only (Phase 1)
            :bearer-token-env "MY_TOKEN"   ;; or :bearer-token, or :headers
            :http-transport :streamable-http ;; :streamable-http | :sse
            :lifecycle :lazy}}}
```

Server keys: `:command`/`:args` (stdio), `:url` (HTTP), `:env`, `:cwd`,
`:headers`, `:auth` (`:bearer`), `:bearer-token`/`:bearer-token-env`,
`:http-transport`, `:lifecycle`, `:direct-tools`, `:tool-prefix`,
`:request-timeout-ms`, `:disabled` (only literal `true` disables).

## The `mcp` tool

```
mcp({ search: "screenshot" })
mcp({ describe: "chrome_devtools_take_screenshot" })
mcp({ tool: "chrome_devtools_take_screenshot", args: { format: "png" } })
mcp({ server: "chrome-devtools" })   → list that server's tools
mcp({ connect: "filesystem" })       → connect now
mcp({ disconnect: "filesystem" })
mcp({})                              → status
```

Two calls instead of N tools cluttering the context. Servers are lazy by
default — they don't connect until you call one of their tools.

## Direct tools (opt-in)

Enable per server (`:direct-tools true` in `mcp.edn`), per server with a
name list (`:direct-tools ["tool-a" "tool-b"]`), globally via
`settings.direct-tools`, or via the `MCP_DIRECT_TOOLS` env var
(`MCP_DIRECT_TOOLS=server1,server2`, `__none__` disables all). Each tool is
then registered as `server_toolname` (or bare name with
`:tool-prefix :none`/`:short`/`:mcp`), registered from cached metadata so
no server spawns at startup.

## Commands

| Command | Description |
|---|---|
| `/mcp` | status: servers, lifecycle, state, tool counts |
| `/mcp search <q>` | search tools (substring; `regex` param for the tool form) |
| `/mcp list [server]` | servers, or one server's tools |
| `/mcp connect <server>` | connect now (+ metadata refresh) |
| `/mcp disconnect <server>` | stop the server process |
| `/mcp enable\|disable <server>` | write `:disabled` into `.kmet/mcp.edn`; `/reload` to apply |
| `/mcp refresh` | reload `mcp.edn`, resync tools |

## Security

MCP config is **trusted code execution**: stdio servers run whatever
`command` you configure, and HTTP bearer tokens are sent to the `:url` you
configure. Only add servers you trust.

## Phase-2 roadmap

OAuth, prompts → slash commands, resources → read tool, mcpScript batching,
`/mcp serve` (expose kmet as an MCP server), setup wizard, include/exclude
globs, output guards, streaming progress.
