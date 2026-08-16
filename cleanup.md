# Moving app/ai modules into kmet.libs — analysis

> Status: Tier 1 step 1 done — `kmet.ai.config-value` moved to
> `kmet.libs.dynamic-value` (2026-08-16). Nothing else moved yet.
> Layer rule enforced by `test/kmet/libs/test-self-contained.clj` (libs must not
> require anything outside `kmet.libs.*`) and `test/kmet/ai/test-self-contained.clj`.

## Motivation

Extensions may only require `kmet.extension`, `kmet.tui.*`, `kmet.libs.*` —
everything in `app/`, `ai/`, and `config` is off-limits (see `kmet.extension`
ns docstring). The mcp-adapter extension already proves the need: it
re-implements three things that belong in libs because the kmet versions live
outside it:

| Re-implemented in mcp-adapter | kmet original |
|---|---|
| EDN config merge (`config.clj`) | `kmet.config` deep-merge + scope paths |
| Credential file storage (`auth.clj` → `mcp-oauth.edn`) | `kmet.ai.auth` auth.edn store |
| HTTP client (`proxy.clj`) | `kmet.ai.proxy` transport |

It *does* share `kmet.libs.oauth` — the seam that already works.

## Tier 1 — zero-dependency moves (move as-is, tests move with them)

| Module | Size | deps | Notes |
|---|---|---|---|
| `kmet.ai.config-value` | 260 | stdlib only | `$VAR` / `!command` config-value resolution. **Highest value**: the mcp-adapter's `:bearer-token-env` is exactly this pattern; extensions resolving secrets from env/commands is a core need. |
| `kmet.ai.aws-sigv4` | ~206 | stdlib + JVM crypto | Textbook generic library (SigV4 signing is a published spec; tests pin AWS's official test suite). Only consumers: `auth`, `bedrock_converse_stream`. |
| `kmet.app.context` | 44 | `babashka.fs` only | AGENTS.md/CLAUDE.md discovery walking up from cwd. Generic file discovery; extensions (e.g. skills that read project context) would use it. |
| `kmet.ai.hooks` | 80 | none | The single-fn slot registry pattern is generic — and already duplicated in `kmet.ai.auth` (`config-key-source`, `oauth-source` atoms). Extract `kmet.libs.hooks` (install/apply), have both use it. |
| `kmet.ai.usage` | 58 | none | Borderline. Token accounting is generic; the key shapes are provider-flavored. Moves cleanly but the only consumers are `api.shared` + `session` + footer. Low urgency. |

## Tier 2 — detach a small dep, then move

### `kmet.ai.proxy` (314 lines)

Only kmet dep is `kmet.ai.hooks`, used solely in `post-stream` (hook
application around the HTTP call).

**Split**: `kmet.libs.proxy` gets `proxy-for-url`, `no-proxy-match?`,
`java-client`, curl transport (`curl-post`, `finish-curl!`, `abort-stream!`,
`request-json`); a thin `ai.proxy` keeps `post-stream` (libs + hooks). Then
`ai.oauth`, `ai.google-adc`, `ai.image-models`, and all 8 `api/*` files depend
on the lib.

**Value**: extensions get env-proxy HTTP routing (mcp-adapter's `proxy.clj`
does plain http with no proxy support today).

### `kmet.ai.auth` (386 lines)

Provider→env-var table and `resolve-provider-auth` are ai-shaped, but the
*credential store* is generic: auth.edn read/write under `file-lock`,
`valid-credential?`, atom, set/remove.

**Extract**: `kmet.libs.credential-store` (or fold into a `libs.edn-store`).
Directly mirrors mcp-adapter's `mcp-oauth.edn` store (temp+rename, 0600 — a
different write strategy, so the lib should offer both lock and atomic-rename).

After Tier 1 moves, `auth`'s remaining deps (`aws-sigv4`, `config-value`,
`google-adc`→proxy) all live in libs, so the whole file could eventually move
too — but the env-var table is LLM-provider knowledge, so `ai.auth` as the
thin layer (table + hooks) over `libs.credential-store` is the right boundary.

### `kmet.config` (407 lines) — settings layer

The generic core: `deep-merge`, `expand-path`, scope-relative path resolution,
lenient EDN load, and the settings-persistence layer (`pretty-edn`,
`update-setting-text` line surgery, `save-setting!` under lock).

**This is the biggest duplication cluster**:

- `pretty-edn` (config) and `pretty-auth` (auth) are the same function
- lenient `edn/read-string` parse appears ~5× (config, auth, skills, prompts, session)
- global+project EDN merge with per-field precedence is re-implemented in
  mcp-adapter's `merge-configs` / `merge-server-maps`

**Extract**: `kmet.libs.edn-settings` (or `kmet.libs.config`): pretty-EDN,
text-surgery save, deep-merge, path expansion. `kmet.config` keeps the
app-shaped bits: defaults, env overrides, `get-theme`, `get-api-key`,
`resource-dirs`, prompt-file discovery. The mcp-adapter's
`set-server-disabled!` / `write-direct-tools!` (raw `pr-str` writes today)
gain a shared writer.

## Tier 3 — keep where they are

- **`kmet.app.event-bus`** — the *vocabulary* is the app's contract with
  extensions (already reachable via `kmet.extension` api wrappers); the
  listener registry is generic but ~40 lines. Not worth extracting.
- **`kmet.ai.oauth` (1011)** — provider flows (Copilot device-code, codex,
  anthropic, openrouter) on top of the already-shared `libs.oauth` machinery.
  The generic half is already in libs; the rest is provider-specific
  (client-ids, scopes, endpoints, login pages).
- **`kmet.ai.models` / `model-config` / `provider-composer` / `image-models`** —
  catalog and registry logic, ai-shaped.
- **`kmet.app.session` / `skills` / `prompts` / `bash-executor` / `loop` /
  `compaction`** — app-shaped (frontmatter parsing already lives in libs).
- **`kmet.ai.google-adc`** — generic Google auth in principle, but only
  `ai.proxy` + ai consumers; after the proxy split it *could* move, but
  there's no second consumer. Leave.

## Bottom line — best ROI, in order

1. **`kmet.libs.config-value`** (from `ai.config-value`) — extensions need it,
   zero deps.
2. **`kmet.libs.edn-settings`** (from `kmet.config` + auth) — kills the
   `pretty-edn` duplicate, gives mcp-adapter a shared writer, and unblocks a
   `libs.credential-store` on top.
3. **`kmet.libs.proxy`** split (from `ai.proxy`) — detaches the last `hooks`
   dep, then the whole ai transport stack depends on libs only.
4. **`kmet.libs.aws-sigv4`, `kmet.libs.context`, `kmet.libs.hooks`** —
   trivially clean, smaller wins.
5. **`kmet.libs.credential-store`** (from `ai.auth`) — medium value; do it
   after #2 since it builds on the same EDN-store primitives.

## Mechanics

- Both self-contained guard tests already enforce the boundaries.
- Moved tests go to `test/kmet/libs/` and must be re-registered in
  `kmet.runner/all-namespaces`.
- Renames are mechanical but broad: 9 namespaces depend on `config`, 10 on
  `auth`, 15 on `proxy` — the real cost is churn, not design.
