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
- API keys: `OPENAI_API_KEY` or `ANTHROPIC_API_KEY` environment variables

## Usage

```sh
# Interactive TUI
bb run

# Or via the entry script
./kmet

# With options
bb run --model gpt-4o --provider openai

# Non-interactive mode
bb run --print "list files in current directory"
```

### Command-line options

```
  -p, --print           Print response and exit (non-interactive)
  -c, --continue        Continue most recent session
  -r, --resume          Browse sessions
  --model <id>          Model to use
  --provider <name>     Provider (openai, anthropic)
  -t, --thinking <level> Thinking level (off, low, medium, high)
  -h, --help            Show this help
```

### In-TUI commands

| Command | Description |
|---------|-------------|
| `/quit` | Exit kmet |
| `/help` | Show help |
| `/model <provider:model>` | Switch model |
| `/new` | Start new session |
| `/resume` | Browse past sessions |
| `/tree` | Browse session entry tree |
| `/theme <name>` | Switch color theme |

### Keyboard shortcuts

| Key | Action |
|-----|--------|
| `Enter` | Submit message |
| `Escape` | Cancel current turn |
| `Ctrl+Z` | Quit |
| `Ctrl+C` | Cancel / clear editor |
| `Ctrl+L` | Clear terminal |
| `Up/Down` | Scroll chat history |

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
{:provider :openai
 :theme "dark"
 :thinking :off
 :session-dir "~/.kmet/sessions"
 :providers {:openai {:model "gpt-4o"}
             :anthropic {:model "claude-sonnet-4-20250514"}}}
```

## Themes

Create EDN theme files in `~/.kmet/agent/themes/`. See `examples/themes/` for format.

## Skills & Extensions

- **Skills**: Place `name/SKILL.md` directories (or flat `.md` files) with YAML frontmatter (`name`, `description`) in `~/.kmet/agent/skills/` — listed in the system prompt as `<available_skills>`; the full `SKILL.md` is read on demand (Agent Skills standard, pi-compatible)
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
