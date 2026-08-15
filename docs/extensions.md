# kmet extensions

An extension customises kmet with slash commands, tools, event handlers, hooks,
CLI flags, custom message/entry renderers, UI contributions, and more. Extensions
are **Clojure namespaces** with an explicit contract — they depend on exactly one
namespace, `kmet.extension`, and are loaded, reloaded and unloaded at runtime.

## The contract

An extension is a Clojure namespace defining an `init` function. The loader
calls `(init api)` with a map of runtime-bound capabilities; register everything
inside `init`. Optionally define `(defn shutdown [api])` for teardown — it runs
on unload/reload, and **everything the extension registered is automatically
unregistered** afterwards.

```clojure
(ns my.hello-ext
  (:require [kmet.extension :as ext]))

(defn init [api]
  (ext/register-command! api
    {:name        "hello"
     :description "Say hello"
     :handler     (fn [_cs args] (str "Hello, " args "!"))}))

(defn shutdown [api]
  ;; optional teardown — deregistration is automatic
  nil)
```

`shutdown` is optional. Every `ext/...` wrapper takes `api` as its first
argument and dispatches to the runtime; extensions never require kmet internals.

## The shared TUI library

Extensions that contribute interactive components (`ui/custom`) build them with
`kmet.tui.*` — the same pi-tui-derived component library the kmet UI itself is
built on. The namespaces are shared **by reference**: extension components are
the same `IComponent` records the host renders, so keys, focus, theming and
caching behave identically, and there is nothing to reimplement.

```clojure
(ns my.dialog
  (:require [kmet.tui.protocols :as protocols]
            [kmet.tui.components.container :as container]
            [kmet.tui.components.text :as text]
            [kmet.tui.theme :as theme]
            [kmet.tui.macros :refer [defcomponent track!]]))

(defcomponent MyDialog [this width]
  (protocols/render [_ w]
    (track! this w)
    (container/->Container
     {:child (text/->Text "Hello from an extension")})))
```

What is available: the core protocol records (`container`, `box`, `text`,
`spacer`, `markdown`, `input`, `editor`, `editing`, `select-list`,
`settings-list`, `stack`, `v-stack`, `h-stack`, `expandable-text`, `image`,
`spinner`, ...), `kmet.tui.theme` (styling — including
`theme/get-settings-list-theme` for the selector look), `kmet.tui.macros`
(`track!`/`track-deps` reactive caching), `kmet.tui.keybindings`, `kmet.tui.keys`,
`kmet.tui.fuzzy` and `kmet.tui.autocomplete`. Extension components must follow
the duck-typed `{:render :handle-input :invalidate}` contract accepted by
`ui/custom` (returning a real record is also fine). A `kmet.tui.*` require that
is not part of the shared set (or a `kmet.app.*`/`kmet.modes.*`/`kmet.libs.*`
require) fails the load with an explicit error.

## Where extensions live

Extensions load from the `:extensions-dir` (default `~/.kmet/agent/extensions`,
plus `.kmet/extensions` project-local) at startup and on `/reload`.

### Built-in extensions (`extensions/`)

The repo ships a set of opt-in extensions in `extensions/` — nothing there is
loaded by default. Enable one by symlinking (or copying) it into the global
or project extensions dir:

```bash
ln -s "$PWD/extensions/tools.clj" ~/.kmet/agent/extensions/tools.clj
```

Single-file extensions ship without tests (they must stay small and
self-contained); directory-based extensions are separate projects with
their own tests. See `extensions/README.md`.

### Single-file extensions

A `.clj` file in the extensions directory:

```
~/.kmet/agent/extensions/
└── hello_ext.clj        ; namespace must start with (ns ...) and define init
```

### Directory extensions (multi-file, `extension.edn`)

A directory containing an `extension.edn` manifest supports extensions with
their own source files:

```
~/.kmet/agent/extensions/
└── my-ext/
    ├── extension.edn
    ├── deps.edn          # optional: external library dependencies
    └── src/
        ├── main.clj
        └── helper.clj
```

```clojure
;; extension.edn
{:name  "my-ext"
 :entry "src/main.clj"}
```

```clojure
;; deps.edn (optional)
{:deps {cheshire/cheshire {:mvn/version "11.5.3"}}}
```

The manifest lists only the initial namespace — `:entry`, whose namespace must
define `init`. Everything else is required from there: internal namespaces
resolve to `.clj` files under the extension directory (indexed by their
`(ns ...)` form, loaded in dependency order), and declared library
dependencies resolve to the jars in `deps.edn` (see
[External library dependencies](#external-library-dependencies-depsedn)).
Internal dependencies are declared in each file's `ns` form:

```clojure
;; src/helper.clj
(ns my-ext.helper)

(defn tool-name [] "my-ext-tool")
```

```clojure
;; src/main.clj
(ns my-ext.main
  (:require [my-ext.helper :as helper]   ; internal dependency
            [kmet.extension :as ext]))   ; kmet core, resolved normally

(defn init [api]
  (ext/register-tool! api {:name (helper/tool-name)
                           :description "..."}))
```

Requires that don't resolve to a file under the extension directory (kmet core
namespaces, built-ins, declared libraries) are left to the normal classpath.
The extension name defaults to the directory name (`:name` in the manifest
overrides it).

### External library dependencies (`deps.edn`)

An extension directory may declare its own library dependencies in a
`deps.edn`; the extension's `ns` form can then require them normally
(`[cheshire.core :as json]`). kmet resolves the declared dependencies — the
complete transitive closure — and serves them **only to that extension's
evaluation context**. Every extension runs in its own isolated context:

- **Different extensions can use different versions of the same library** —
  jars are served per extension, so there is no first-wins shadowing.
- **Unloading an extension releases everything it pulled in** — its
  namespaces, closures and jars become unreachable; nothing global is
  touched.
- **Version changes take effect on `/reload`** — a fresh context is built
  and the deps re-resolve.
- kmet core and `clojure.*` / `babashka.*` namespaces are shared references
  (never re-evaluated), so extension registrations always reach the real
  kmet registries. Extensions may depend only on `kmet.extension` plus the
  shared `kmet.tui.*` library; other kmet internals are not resolvable from
  an extension context (the load fails with an explicit error).

Dep resolution happens **in-process** (via `borkdude.deps`, the tools.deps
port kmet depends on) — no subprocess, and nothing is written outside the
normal Maven/Git caches (`~/.m2`, `~/.gitlibs`). A library an extension
requires without declaring it in `deps.edn` fails with a clear error unless
it is babashka-bundled.

#### Limits (inherent to Babashka)

- Babashka only runs libraries it supports: pure-Clojure code using classes
  it exposes. Libraries needing `definterface`, `deftype` with non-protocol
  interfaces, or unexposed Java classes fail to load — in plain bb too.
- Libraries babashka ships adapted (`cheshire`, `core.async`, `data.json`,
  `tools.reader`, ...) usually cannot be replaced by their raw Maven
  versions; kmet warns when an extension pins one. Omit them from `deps.edn`
  to use the bundled copy.
- Single-file extensions (plain `.clj` files, no directory) cannot carry a
  `deps.edn`.

## Runtime lifecycle

Extensions load at startup and via `/reload`. They can also be managed at
runtime (e.g. from another extension or a REPL):

| Operation | Function | Effect |
|---|---|---|
| Load | `kmet.app.extensions/load-extension!` (path) | load one file or manifest dir; returns `{:extension name :error nil}` or `{:extension nil :error msg}` |
| Unload | `kmet.app.extensions/unload-extension!` (record) | run `shutdown`, deregister everything, remove namespaces |
| Reload | `kmet.app.extensions/reload-extensions!` (dirs) | unload all, reload from the given container dirs |
| List | `kmet.app.extensions/get-loaded-extensions` | `[{:name .. :path ..}]` |

A failing `init` is rolled back: the loader unloads whatever was registered
before the error and reports `{:extension nil :error msg}` — no partial state
lingers.

## The API surface

`api` carries identity plus the capability maps. The `ext/...` wrappers call
into it; the `(:ui api)`, `(:models api)` and `(:session api)` maps are used via
the wrappers below or directly.

### Identity

```clojure
(:extension-name api)   ; "hello_ext.clj" or the manifest :name
(:extension-path api)   ; absolute path to the extension file/dir
(:extension-dir api)    ; directory containing it
```

### Commands and tools

```clojure
(ext/register-command! api
  {:name "cmd" :description "..." :argument-hint "<arg>"
   :get-argument-completions (fn [prefix] [{:value "a" :label "A"}])
   :handler (fn [cs args] ...)})          ; args is the trimmed argument string
(ext/unregister-command! api "cmd")
(ext/get-commands api)

(ext/register-tool! api
  {:name "my-tool" :description "..."
   :params {:path {:type :string :description "..."}}   ; or :parameters (raw JSON schema)
   :execute (fn [args] {:content "..." :is-error false})})
(ext/unregister-tool! api "my-tool")
(ext/get-all-tools api)
(ext/get-active-tools api)      ; nil = all; else set of enabled names
(ext/set-active-tools api ["read" "write"])
```

### Events

```clojure
;; returns a deregister fn — call it to stop listening
(ext/on-event api :session-start (fn [ev] ...))
(ext/emit-event! api {:type :my-event :data 1})
```

Event types: `:agent-start` `:agent-end` `:agent-settled` `:turn-start`
`:turn-end` `:message-start` `:message-update` `:message-end`
`:tool-execution-start` `:tool-execution-update` `:tool-execution-end`
`:status` `:error` `:session-start` `:session-shutdown`
`:session-info-changed` `:user-bash`
`:session-before-tree` `:session-tree` `:queue-update` `:model-select`
`:thinking-level-select` `:context-replaced` `:auto-retry-start`
`:auto-retry-end` `:compaction-start` `:compaction-end`.

### Hooks

```clojure
;; intercept/rewrite user input before the agent runs
(ext/on-input api (fn [{:keys [text source streaming-behavior images]}]
                    ;; return nil (pass), {:action :handled}, or
                    ;; {:action :transform :text new-text :images new-images}
                    nil))

;; override the system prompt / inject context per run
(ext/on-before-agent-start api
  (fn [{:keys [prompt system-prompt]}]
    ;; return nil, or {:system-prompt ...} / {:message msg-map}
    nil))

;; transform tool calls/results (pi: tool_call / tool_result)
(ext/on-tool-call api
  (fn [{:keys [tool-name args]}]
    ;; nil (pass), {:block true :reason "..."}, or {:args transformed}
    ;; a blocked call may add :terminate true — when EVERY call in the
    ;; batch is blocked with :terminate the run stops after the batch
    ;; (no follow-up LLM call; the follow-up queue still drains)
    nil))
(ext/on-tool-result api
  (fn [{:keys [tool-name result is-error]}]
    ;; nil, or {:content ...} / {:is-error ...} overrides
    nil))
```

### CLI flags

```clojure
(ext/register-flag! api "my-flag" {:type :boolean :default false})
;; or {:type :string :default "..."}
(ext/get-flag api "my-flag")
```

Values come from `--my-flag`, `--my-flag value` or `--my-flag=value` on the
command line (string flags consume the following non-dash arg; bare flags are
boolean true).

### Custom renderers

```clojure
;; custom ENTRY (extension state, never in LLM context) — hidden unless a
;; renderer is registered; renderer returns a chat message map (or bare
;; component)
(ext/register-entry-renderer! api "my-state"
  (fn [entry] {:role :info :content (pr-str (:data entry)) :label "My state"}))

;; custom MESSAGE (participates in LLM context) — overrides the default
;; labeled info box
(ext/register-message-renderer! api "my-message"
  (fn [msg] {:role :info :content "rendered" :label "My message"}))
```

### Agent control

```clojure
(ext/set-model api model)                 ; a Model record from (:models api)
(ext/get-thinking-level api)
(ext/set-thinking-level api :high)
(ext/send-user-message api "text" {:deliver-as :steer})  ; :steer | :follow-up
```

`send-user-message` always triggers a turn when the agent is idle; while
streaming, `:deliver-as` controls whether the message is injected mid-run
(`:steer`) or queued until the run settles (`:follow-up`, the default).

### UI

The `:ui` capability map dispatches through the runtime registry — calls are
inert before the interactive layout exists and in headless/print mode.

```clojure
(ext/ui-set-status api "my-ext" "loaded")   ; footer status; nil clears
(ext/ui-notify api "Done" :info)            ; :info | :warning | :error
(ext/ui-set-widget api "my-widget" ["line 1" "line 2"] {:placement :above-editor})

;; dialogs return a promise — deref on a worker thread
(ext/ui-select api "Pick" [{:value "a" :label "A"}])
(ext/ui-confirm api "Sure?" "Proceed?")
(ext/ui-input api "Name" "placeholder")

(ext/ui-set-theme api "light")
(ext/ui-get-theme api)
(ext/ui-get-all-themes api)
(ext/ui-set-editor-text api "text")
(ext/ui-get-editor-text api)
(ext/ui-paste-to-editor api "text")
(ext/ui-set-footer api (fn [tui theme footer-data] comp-or-nil))
(ext/ui-set-header api (fn [tui theme] comp-or-nil))
(ext/ui-set-title api "kmet")
(ext/ui-set-working-indicator api {:frames ["⠋" "⠙"] :interval-ms 80})
(ext/ui-set-working-message api "Working...")
(ext/ui-set-working-visible api true)
(ext/ui-set-hidden-thinking-label api "…")
(ext/ui-set-editor-component api (fn [tui theme keybindings] comp))
(ext/ui-add-autocomplete-provider api (fn [base-provider] wrapped-or-nil))
(ext/ui-on-terminal-input api (fn [data] nil-or-{:consume true :data d}))
(ext/ui-set-tools-expanded api true)
(ext/ui-get-tools-expanded api)
(ext/ui-editor api "Title" "prefill")   ; modal editor dialog → promise of text, nil when dismissed (nil headless)
(ext/ui-get-theme-by-name api "dark")     ; real Theme record, nil for unknown names (pi: getTheme)
```

### Models

```clojure
(def models (ext/models api))
(models/get-all api)                      ; all registered models
(models/get-available api)                ; models with configured auth
(models/find api provider-id model-id)
(models/has-configured-auth api model)
(models/get-provider-auth-status api provider-id)
(models/get-api-key-and-headers api model)
(models/get-registered-provider-config api provider-id)
(models/get-registered-provider-ids api)
(models/register-provider! api :my-provider
                           {:base-url "https://..." :api :openai-completions
                            :api-key "sk-..." :models [{:id "my-model"}]})
(models/unregister-provider! api :my-provider)
```

### Session

```clojure
(def sess (ext/session api))
(sess/append-entry! "my-state" {:n 1})    ; durable extension state (not in LLM context)
(sess/append-message! "my-msg" "content" true {:details ...})  ; in LLM context; :display controls rendering
(sess/get-entries "my-state")
(sess/set-label! entry-id "bookmark")
(sess/get-label entry-id)
(sess/set-name! "my session")
(sess/get-name)
```

### Shell

```clojure
(ext/exec api "sh" ["-c" "echo hi"] {:dir "/tmp" :timeout-ms 5000})
;; => {:exit 0 :out "hi\n" :err ""}
```

## Testing extensions

Extensions are testable in isolation with the **nullable API** — a test fixture
that captures every registration into a state atom, with no kmet runtime
involved. Load the extension's `init` against it and assert what was
registered:

```clojure
(ns my.hello-ext-test
  (:require [clojure.test :refer [deftest is]]
            [kmet.extension :as ext]
            [my.hello-ext :as sut]))

(deftest init-registers-hello-command
  (let [{:keys [api state]} (ext/create-nullable-api)]
    (sut/init api)
    (is (contains? (:commands @state) "hello"))
    ;; deregister fns remove registrations (unload replay)
    (is (= 1 (count (get-in @state [:handlers :session-start]))))))
```

State shape: `:commands` `:tools` `:handlers` `:flags` `:entry-renderers`
`:message-renderers` `:tool-call-hooks` `:tool-result-hooks` `:input-hooks`
`:before-agent-start-hooks` `:ui-calls` `:emitted` `:model-calls`. Every
registration function returns a deregister fn; the nullable api's deregister
fns remove the corresponding registration.

For runtime integration tests, `load-extension!` + `unload-extension!` against
the real registries (see `test/kmet/app/test_extensions.clj`).

## Example

A complete extension showing the common pieces:

```clojure
(ns my.ext
  "Example: command + tool + event handler + status."
  (:require [clojure.string :as str]
            [kmet.extension :as ext]))

(defn init [api]
  (ext/ui-set-status api "my-ext" "loaded")

  (ext/register-command! api
    {:name "uppercase"
     :description "Uppercase a string"
     :handler (fn [_cs args] (str/upper-case args))})

  (ext/register-tool! api
    {:name "my-echo"
     :description "Echo the text argument"
     :params {:text {:type :string :description "Text to echo"}}
     :execute (fn [args] {:content (str "echo: " (:text args))})})

  (ext/on-event api :agent-end
    (fn [ev] (ext/ui-notify api "Turn finished" :info)))

  (ext/on-input api
    (fn [{:keys [text]}]
      (when (str/starts-with? text "!magic")
        {:action :transform :text (str "The magic word is " text)}))))

(defn shutdown [api]
  (ext/ui-set-status api "my-ext" nil))
```
