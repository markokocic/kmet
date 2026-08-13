# kmet

A minimal coding agent built in Clojure/Babashka, featuring a terminal user interface (TUI)
with differential rendering, LLM integration, and tool execution.

Inspired by [pi](https://pi.dev) — a terminal-based AI coding agent.

## Overview

kmet provides an interactive terminal UI where you can chat with an LLM (OpenAI or Anthropic),
with the agent having access to filesystem tools (read, write, edit, bash, grep, find, ls).

### Features

- **TUI Framework** — differential rendering, component model, overlays, raw-mode input
- **Multi-line Editor** — word-wrap, undo/redo, kill-ring, history, paste markers
- **Chat History** — user/assistant/tool message display with scrolling
- **Agent Loop** — orchestrates LLM calls and tool execution with streaming
- **Config System** — `~/.kmet/agent/settings.edn` and `.kmet/settings.edn` overrides
- **Theme System** — customizable ANSI color themes from EDN files
- **Session Persistence** — EDNL files with branching support
- **Skills & Extensions** — markdown skills and Clojure extensions

## Prerequisites

- [Babashka](https://babashka.org/) ≥ 1.12.215 (bundles JLine 4.3.1)
- API keys: `OPENCODE_API_KEY` (opencode-go/opencode), `DEEPSEEK_API_KEY`,
  `OPENAI_API_KEY` (openai), `XAI_API_KEY` (xai), `AZURE_OPENAI_API_KEY`
  (azure-openai-responses; base URL/deployment from `AZURE_OPENAI_BASE_URL` /
  `AZURE_OPENAI_RESOURCE_NAME` / `AZURE_OPENAI_DEPLOYMENT_NAME_MAP`),
  `COPILOT_GITHUB_TOKEN` (or `/login` inside kmet to store `auth.edn`
  credentials; `/logout` removes them). GitHub Copilot and OpenAI Codex
  support an OAuth device-code login (`/login github-copilot` →
  "Sign in with an account"; `/login openai-codex` → ChatGPT device flow).

## Usage

```sh
# Interactive TUI
bb run

# Or via the entry script
./kmet

# With options
bb run --model deepseek-v4-flash --provider opencode-go

# Non-interactive mode
bb run --print "list files in current directory"
```

### Command-line options

```
  -p, --print           Print response and exit (non-interactive)
  -c, --continue        Continue most recent session
  -r, --resume          Browse sessions
  --model <id>          Model to use (pattern: provider/model[:thinking])
  --provider <name>     Provider (opencode-go, opencode, deepseek, github-copilot,
                        openai, xai, openai-codex, azure-openai-responses)
  --models <patterns>   Comma-separated model patterns for Ctrl+P cycling
  --list-models [search] List available models (with optional fuzzy search)
  -t, --thinking <level> Thinking level (off, minimal, low, medium, high, xhigh, max)
  -h, --help            Show this help
```

### In-TUI commands

| Command | Description |
|---------|-------------|
| `/quit` | Exit kmet |
| `/help` | Show help |
| `/model <provider:model[:thinking]>` | Switch model (Ctrl+L opens a selector; refresh-on-miss falls through to the selector with the search term) |
| `/scoped-models` | Enable/disable/reorder the models Ctrl+P cycles through (Ctrl+S saves to settings) |
| `/settings` | Settings menu — thinking level, hide-thinking, retry (enabled / max retries / base delay) |
| `/login [provider]` | Configure provider auth (API key or GitHub Copilot OAuth) |
| `/logout [provider]` | Remove stored provider credentials |
| `/new` | Start new session |
| `/resume` | Browse past sessions |
| `/tree` | Browse session entry tree |
| `/theme <name>` | Switch color theme |

### Keyboard shortcuts

| Key | Action |
|-----|--------|
| `Enter` | Submit message |
| `Escape` | Cancel current turn |
| `Ctrl+D` | Exit when editor is empty |
| `Ctrl+C` | Clear editor (twice to quit) |
| `Ctrl+L` | Select model |
| `Ctrl+P` / `Shift+Ctrl+P` | Cycle scoped models (`--models` / `/scoped-models`) |
| `Ctrl+Up/Down` | Scroll chat viewport |

## Project Structure

```
src/kmet/
├── core.clj              — CLI entry, main layout, commands, session integration
├── config.clj            — Configuration loading (settings.edn, env vars)
├── skills.clj            — Skills & extension loading
├── demo.clj              — Standalone editor demo
├── tui/
│   ├── core.clj          — TUI framework (components, overlays, rendering)
│   ├── terminal.clj      — JLine 4.x terminal wrapper
│   ├── keys.clj          — Keyboard input handling
│   ├── utils.clj         — Text width, wrapping, ANSI helpers
│   ├── protocols.clj     — IComponent, IFocusable protocols
│   ├── theme.clj         — Theme record, color resolution
│   └── components/
│       ├── text.clj, spacer.clj, box.clj, container.clj
│       ├── input.clj, editor.clj
│       ├── chat_history.clj, markdown.clj
│       ├── select_list.clj, settings_list.clj
└── agent/
    ├── llm.clj           — LLM API client (OpenAI + Anthropic, SSE streaming)
    ├── tools.clj         — 7 built-in tools (read/write/edit/bash/grep/find/ls)
    ├── loop.clj          — Agent turn loop with tool cycling, compaction
    └── session.clj       — EDNL session storage with branching, tree support
```

## Configuration

Settings are loaded from:

1. `~/.kmet/agent/settings.edn` — user-wide settings
2. `.kmet/settings.edn` — project-local overrides
3. Environment variables: `KMET_PROVIDER`, `KMET_MODEL`

Example `~/.kmet/agent/settings.edn`:

```clojure
{:provider :opencode-go
 :model "deepseek-v4-flash"
 :theme "dark"
 :thinking :off
 :session-dir "~/.kmet/sessions"
 :http-idle-timeout-ms 300000   ; LLM stream idle + total deadline in ms; 0 disables
 :system-prompt "You are a helpful assistant."   ; replaces the default system prompt
 :append-system-prompt "Follow the project conventions." ; appended after it}
```

`kmet` reads its provider catalog from `src/kmet/app/model_data/*.edn`
(opencode-go, opencode, deepseek, github-copilot, openai, xai, openai-codex,
azure-openai-responses). Models,
base URLs and defaults are registry data — see `models.md` for the
subsystem plan.

The system prompt (pi-compatible) is built from: the default (or `:system-prompt`)
base, the active tools with one-line snippets, guidelines, `:append-system-prompt`,
the project context (`AGENTS.md`/`CLAUDE.md` from `~/.kmet/agent` and the cwd's
ancestors), the `<available_skills>` block, and the current working directory.

Like pi, prompt files are discovered when the config keys are unset:

- `.kmet/SYSTEM.md` or `~/.kmet/agent/SYSTEM.md` — replaces the system prompt
- `.kmet/APPEND_SYSTEM.md` or `~/.kmet/agent/APPEND_SYSTEM.md` — appended to it

Config values win over files; a config value naming an existing file is read as
content. CLI flags `--system-prompt <txt>` and `--append-system-prompt <txt>`
(repeatable) override everything.

`:http-idle-timeout-ms` (default 300000, pi: `httpIdleTimeoutMs`) is the LLM
stream deadline: a stream that receives no bytes for this long errors retryably
(undici bodyTimeout semantics), and the same value bounds the total request
(SDK `timeoutMs`). `0` disables both.

`/reload` re-reads settings, reloads extensions/skills/prompts/themes, re-discovers
context files, and rebuilds the system prompt (pi: `session.reload`). It refuses
while a response is streaming.

Compaction (pi-compatible): `:compact-threshold` (entry count, default 400) and
`:compact-token-threshold` (estimated tokens, default off) trigger proactive
compaction before a run; context-overflow errors compact once then retry. The
pre-cut conversation is summarized via the LLM (structured Goal/Progress/Next-
Steps checkpoint, updated on subsequent compactions) and replaced with a summary
entry; `:keep-recent-tokens` (default 20000) sets how many recent tokens to keep.
`/compact [instructions]` triggers it manually.

## Themes

Create EDN theme files in `~/.kmet/agent/themes/`. See `examples/themes/` for format.

## Skills & Extensions

- **Skills**: Place `name/SKILL.md` directories (or flat `.md` files) with YAML frontmatter (`name`, `description`) in `~/.kmet/agent/skills/` or `.kmet/skills/` — listed in the system prompt as `<available_skills>`; `/skill:name` loads one on demand (Agent Skills standard, pi-compatible)
- **Prompt Templates**: Place `.md` files in `~/.kmet/agent/prompts/` or `.kmet/prompts/` — `/name args` expands to the template body with `$1`, `$@`, `${1:-default}`, `${@:N}` placeholders; unknown `/cmd` falls through to the agent (pi-compatible)
- **Extensions**: Place `.clj` files in `~/.kmet/agent/extensions/` — loaded at startup

## Development

```sh
bb demo    # Standalone editor demo
bb test    # Run fast test suites (excludes ^:slow tests)
bb test-ext # Run only the slow (^:slow) test suites
bb help    # Show task help
```

## Status

- ✅ Phase 1 — TUI Foundation
- ✅ Phase 2 — Interactive Components
- ✅ Phase 3 — Agent Core
- ✅ Phase 4 — Chat History, Layout, Commands
- ✅ Phase 5 — Themes, Config, Polish

## License

Copyright © 2026 — present Marko Kocic <marko@euptera.com>

Licensed under the Eclipse Public License 2.0 (EPL-2.0).
