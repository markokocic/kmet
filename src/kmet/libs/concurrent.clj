(ns kmet.libs.concurrent
  "Daemon-thread spawn helper for extension SCI contexts.

   Extensions run in isolated SCI contexts where `future`/`pmap`/`pcalls`
   are not available — SCI is a pure interpreter with no bundled
   Executor, while babashka injects `future` only into its host context
   (see `kmet.app.extensions/build-context-namespaces`). `kmet` therefore
   exposes this tiny helper instead of whitelisting `future`:

   - host: `babashka` embeds `sci` + injects `future` via `:namespaces`
   - extensions: second `sci` context built by `kmet.app.extensions` —
     only `slurp`/`spit`/`file-seq` from `clojure.core` plus the shared
     `kmet.tui.*` / `kmet.libs.*` layers are injected; `future` is
     deliberately omitted.

   Whitelisting `future` would pull in an implicit `Executor`, make
   extension work survive `unload-extension!`/`reload-extensions!`
   (non-daemon futures keep kmet alive), enable unbounded submission
   with no backpressure, and require the rest of the `future` family
   (`future-call`, `future-cancel`, …) plus `pmap`/`pcalls` for
   consistency. Keeping extensions on an explicit daemon `Thread` makes
   the lifecycle obvious and keeps unload/reload clean. If a nicer
   primitive is needed later, expose exactly this `spawn` — not `future`.")

(defn spawn
  "Start a daemon thread running `(f)` — the extension-SCI replacement
   for `future` (which is not available in extension contexts).

   Exceptions in `f` are swallowed (extension background work must never
   kill the host). Returns the `Thread` (already started, daemon=true)
   so callers that need to `.interrupt` / `.join` it can."
  [f]
  (let [t (Thread. (fn [] (try (f) (catch Throwable _ nil))))]
    (.setDaemon t true)
    (.start t)
    t))
