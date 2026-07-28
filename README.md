# kmet

A minimal coding agent built in Clojure, featuring a terminal user interface (TUI) with differential rendering.

## Overview

kmet is an experimental terminal-based coding agent. It uses JLine3 for terminal handling and implements a TUI framework with:
- **Differential rendering** — only changed lines are redrawn, reducing flicker
- **Component model** — composable UI components (text, spacer, etc.)
- **Overlay support** — modal overlays for prompts and dialogs
- **Raw-mode input** — keyboard-driven interaction

## Usage

### Prerequisites

- [Babashka](https://babashka.org/) ≥ 1.12.215
- Java 11+ (JLine3 dependency)

### Run the demo

```sh
bb run
```

### Tasks

| Task   | Description       |
| ------ | ----------------- |
| `run`  | Run the TUI demo  |
| `help` | Show task help    |

## Project Structure

```
src/kmet/
├── demo.clj              — Demo entry point
├── tui/
│   ├── core.clj          — TUI framework (components, overlays, rendering)
│   ├── terminal.clj      — JLine3 terminal wrapper
│   ├── keys.clj          — Keyboard input handling
│   ├── utils.clj         — Utility functions
│   ├── index.clj         — Public API re-exports
│   └── components/
│       ├── spacer.clj    — Vertical spacer component
│       └── text.clj      — Static text component
```

## Development

Start a REPL:

```sh
clj -M -m kmet.demo
```

Run tests (if present):

```sh
clj -M:dev -m kmet.demo
```

## License

Copyright © 2026 – present Marko Kocic <marko@euptera.com>

Licensed under the Eclipse Public License 2.0 (EPL-2.0).

You may not use this file except in compliance with the License.
You may obtain a copy of the License at

    https://www.eclipse.org/legal/epl-2.0/

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
