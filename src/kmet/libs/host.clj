(ns kmet.libs.host
  "Which runtime hosts this kmet process.

   kmet runs on babashka and on Jolt: Jolt defines the
   clojure.core/*jolt-version* var (jolt-port.md), babashka does not — the
   only supported non-Jolt host, so anything that isn't Jolt is babashka.
   UI surfaces that name or badge the host (the welcome header logo, the
   footer's К mark) share this one detection instead of repeating it.")

(defn jolt?
  "True on the Jolt host: jolt defines clojure.core/*jolt-version*;
   babashka does not."
  []
  (boolean (find-var 'clojure.core/*jolt-version*)))

(defn runtime-name
  "Name of the hosting runtime: \"jolt\" or \"babashka\"."
  []
  (if (jolt?) "jolt" "babashka"))

(defn mark
  "Single-letter runtime mark for compact UI badges (the footer's Кb/Кj):
   the runtime name's initial — \"b\" on babashka, \"j\" on Jolt."
  []
  (subs (runtime-name) 0 1))
