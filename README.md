# kmet

A minimal coding agent built in Clojure/Babashka, featuring a terminal user interface (TUI)
with differential rendering, LLM integration, and tool execution.

Inspired by [pi](https://pi.dev) — a terminal-based AI coding agent.

## Overview

kmet provides an interactive terminal UI where you can chat with any of 39
cataloged LLM providers (opencode-go, deepseek, anthropic, google, openai,
openrouter, mistral, bedrock, ...) — the model registry, catalogs, auth and
wire APIs are a port of pi's provider subsystem (see `models.md`). The agent
has filesystem tools (read, write, edit, bash, grep, find, ls) plus skills,
extensions and prompt templates.

### Features

- **TUI Framework** — differential rendering, component model, overlays, raw-mode input
- **Multi-line Editor** — word-wrap, undo/redo, kill-ring, history, paste markers
- **Chat History** — user/assistant/tool message display with scrolling
- **Agent Loop** — orchestrates LLM calls and tool execution with streaming
- **Config System** — `~/.kmet/agent/settings.edn` and `.kmet/settings.edn` overrides
- **Theme System** — customizable ANSI color themes from EDN files
- **Session Persistence** — EDNL files with branching support
- **Skills & Extensions** — markdown skills and Clojure extensions
- **Provider Subsystem** — 39 generated provider catalogs (pi-faithful
  `models.md` port), `--list-models`, model resolution/cycling, cost display,
  custom providers via `models.edn`, OAuth logins (Copilot, Codex, Anthropic,
  OpenRouter), and an image-model registry (`kmet.app.image-models`)

## Prerequisites

- [Babashka](https://babashka.org/) ≥ 1.12.215 (bundles JLine 4.3.1)
- API keys: `OPENCODE_API_KEY` (opencode-go/opencode), `DEEPSEEK_API_KEY`,
  `OPENAI_API_KEY` (openai), `XAI_API_KEY` (xai), `AZURE_OPENAI_API_KEY`
  (azure-openai-responses; base URL/deployment from `AZURE_OPENAI_BASE_URL` /
  `AZURE_OPENAI_RESOURCE_NAME` / `AZURE_OPENAI_DEPLOYMENT_NAME_MAP`),
  `COPILOT_GITHUB_TOKEN`,
  `ANTHROPIC_AUTH_TOKEN`/`ANTHROPIC_OAUTH_TOKEN`/`ANTHROPIC_API_KEY`
  (anthropic), `GEMINI_API_KEY` (google), `GROQ_API_KEY` (groq),
  `CEREBRAS_API_KEY` (cerebras), `HF_TOKEN` (huggingface),
  `MOONSHOT_API_KEY` (moonshotai), `XIAOMI_API_KEY` + `XIAOMI_TOKEN_PLAN_*_API_KEY`
  (xiaomi token plans), `QWEN_TOKEN_PLAN_API_KEY` (+ `_CN_`), `MINIMAX_API_KEY` /
  `MINIMAX_CN_API_KEY`, `NVIDIA_API_KEY` (nvidia), `OPENROUTER_API_KEY`
  (openrouter), `FIREWORKS_API_KEY` (fireworks), `AI_GATEWAY_API_KEY`
  (vercel-ai-gateway), `ZAI_API_KEY`/`ZAI_CODING_CN_API_KEY` (zai),
  `TOGETHER_API_KEY` (together), `BASETEN_API_KEY` (baseten),
  `ANT_LING_API_KEY` (ant-ling), `KIMI_API_KEY` (kimi-coding),
  `CLOUDFLARE_API_KEY` + `CLOUDFLARE_ACCOUNT_ID`/`CLOUDFLARE_GATEWAY_ID`
  (cloudflare workers-ai / ai-gateway), `MISTRAL_API_KEY` (mistral),
  `GOOGLE_CLOUD_API_KEY` (google-vertex; or Application Default
  Credentials — `GOOGLE_APPLICATION_CREDENTIALS` + project + location) —
  or `/login` inside kmet to store `auth.edn` credentials; `/logout`
  removes them. Amazon Bedrock authenticates via the ambient AWS
  credential sources (`AWS_ACCESS_KEY_ID`/`AWS_SECRET_ACCESS_KEY`,
  `AWS_PROFILE`, `AWS_BEARER_TOKEN_BEDROCK`, ECS/IRSA vars). OAuth
  subscription logins (`/login <provider>`): GitHub Copilot device-code
  ("Sign in with an account"), OpenAI Codex (browser PKCE loopback or
  device code), Anthropic Claude Pro/Max (browser PKCE loopback), and
  OpenRouter (browser PKCE loopback, permanent key).

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
                        openai, xai, openai-codex, azure-openai-responses,
                        anthropic, google, groq, cerebras, huggingface,
                        moonshotai, xiaomi, qwen-token-plan, minimax, nvidia,
                        openrouter, fireworks, vercel-ai-gateway, ...)
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
| `/model <provider:model[:thinking]>` | Switch model (Ctrl+L opens a selector; an unmatched term opens the selector pre-filled with it) |
| `/scoped-models` | Enable/disable/reorder the models Ctrl+P cycles through (Ctrl+S saves to settings) |
| `/settings` | Settings menu — thinking level, hide-thinking, retry (enabled / max retries / base delay), theme |
| `/login [provider]` | Configure provider auth — API key, or OAuth: Copilot device-code, Codex browser/device, Anthropic & OpenRouter browser PKCE |
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
├── core.clj            — CLI entry, arg parsing, mode dispatch
├── config.clj          — Configuration loading (settings.edn, env vars)
├── debug.clj           — Debug/error logging
├── libs/               — Generic, self-contained helpers (diff, process tree,
│                         SSE parsing, terminal protocol, yaml frontmatter,
│                         terminal images, file locks, hashing, highlighting)
├── modes/              — Entry modes: interactive TUI + print mode
├── ai/                 — Provider/auth subsystem (pi: packages/ai — a standalone
│   │                     library the agent depends on; self-contained, see AGENTS.md)
│   ├── models.clj      — Provider/model registry + committed EDN catalogs
│   │                     (model_data/), cost, catalog loading
│   ├── model_config.clj / provider_composer.clj / config_value.clj —
│   │                     models.edn custom providers, config resolution
│   ├── auth.clj        — env-var table, auth.edn, credential resolution
│   ├── oauth.clj       — OAuthAuth record, device-code + PKCE loopback flows
│   ├── llm.clj + api/  — LLM dispatcher + per-wire API builders
│   │                     (openai/anthropic/google/responses/codex/azure/
│   │                     bedrock/vertex/mistral)
│   ├── image_models.clj — image-generation registry + :openrouter-images
│   │                     wire (image_model_data/ catalog)
│   ├── aws_sigv4.clj / google_adc.clj — bedrock SigV4 + vertex ADC auth
│   └── proxy.clj       — proxy env vars + curl transport
├── app/                — App business logic (pi: dist/core/)
│   ├── model_resolver.clj — model pattern/CLI resolution
│   ├── tools/          — read/write/edit/bash tools (grep/find/ls disabled)
│   └── ui/             — app TUI components (chat history, footer, ...)
├── tui/                — Generic TUI library (pi: @earendil-works/pi-tui)
│   └── components/     — text, input, editor, markdown, select/settings
│                         lists, spinner, image, stack layouts, ...
```

The full annotated layout (every file) lives in `AGENTS.md`.

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

`kmet` reads its provider catalog from `src/kmet/ai/model_data/*.edn`
(39 providers: opencode-go, deepseek, anthropic, google, groq, cerebras,
openrouter, nvidia, moonshotai, qwen-token-plan, minimax, fireworks,
vercel-ai-gateway, zai, together, baseten, kimi-coding, cloudflare, mistral,
google-vertex, amazon-bedrock, ...; generated from models.dev + live
catalogs — `bb generate-models`). The image-model catalog lives in
`src/kmet/ai/image_model_data/image-models.edn` (`bb generate-image-models`).
Models, base URLs and defaults are registry data; custom providers, API keys
and model overrides go in `~/.kmet/agent/models.edn`. OpenAI-completions
wire compatibility (thinking format, max-tokens field, reasoning
round-trip) auto-detects from the provider id and base URL; explicit
`:compat` entries in models.edn override the detected defaults.

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

Compaction (pi-compatible): proactive compaction triggers before a run when
the measured context usage (latest response's reported usage + a chars/4
estimate of newer entries) comes within `:compact-reserve-tokens` (default
16384) of the model's context window. An explicit `:compact-token-threshold`
(estimated tokens) can override it. Context-overflow errors compact once then
retry. The pre-cut conversation is summarized via the LLM (structured
Goal/Progress/Next-Steps checkpoint, updated on subsequent compactions) and
replaced with a summary entry; `:keep-recent-tokens` (default 20000) sets how
many recent tokens to keep. `/compact [instructions]` triggers it manually.

## Themes

Create EDN theme files in `~/.kmet/agent/themes/`. See `examples/themes/` for format.

## Skills & Extensions

- **Skills**: Place `name/SKILL.md` directories (or flat `.md` files) with YAML frontmatter (`name`, `description`) in `~/.kmet/agent/skills/` or `.kmet/skills/` — listed in the system prompt as `<available_skills>`; `/skill:name` loads one on demand (Agent Skills standard, pi-compatible)
- **Prompt Templates**: Place `.md` files in `~/.kmet/agent/prompts/` or `.kmet/prompts/` — `/name args` expands to the template body with `$1`, `$@`, `${1:-default}`, `${@:N}` placeholders; unknown `/cmd` falls through to the agent (pi-compatible)
- **Extensions**: Place `.clj` files (or directories with `extension.edn`) in
  `~/.kmet/agent/extensions/` or `.kmet/extensions/`. An extension is a Clojure
  namespace defining `(defn init [api])` (and optionally `(defn shutdown [api])`),
  depending only on `kmet.extension`. A manifest dir declares `{:name :entry
  :files}` — its own source deps. Loaded at startup, reloadable via `/reload`, and
  unloadable at runtime; unload runs shutdown + deregisters everything

## Development

```sh
bb run             # Interactive TUI
bb test            # Run fast test suites (excludes ^:slow tests)
bb test-ext        # Run only the slow (^:slow) test suites
bb lint            # clj-kondo over src/test
bb format          # cljfmt (fix) / bb format-check (verify)
bb generate-models # Regenerate provider catalogs (network)
bb generate-image-models # Regenerate the image model catalog (network)
bb check-model-data      # Offline catalog validation
bb help            # Show task help
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
