(ns kmet.libs.clipboard
  "System clipboard copy via platform tools (pi: utils/clipboard.ts
   copyToClipboard — /copy). Termux, Wayland, X11, macOS, Windows; the
   calling layer may add an OSC52 fallback for environments with no tool."
  (:require [babashka.process :as proc]
            [clojure.string :as str]))

(def ^:private tool-timeout-ms 5000)

(defn- run-tool!
  "Run CMD with TEXT piped to stdin. Returns true when the tool exited 0
   before the timeout; false on non-zero exit, spawn failure, or timeout.
   babashka's :timeout opt is ignored by proc/process, so the deadline is a
   deref timeout; a hung tool is destroyed (tree) so it can't linger."
  [cmd text]
  (try
    (let [p (proc/process cmd {:out :string :err :discard :in text})
          result (deref p tool-timeout-ms :timed-out)]
      (if (= result :timed-out)
        (do (proc/destroy-tree p)
            false)
        (zero? (:exit result))))
    (catch Exception _ false)))

(defn copy-text!
  "Copy TEXT to the system clipboard. Tries, in order: Termux
   (termux-clipboard-set), Wayland (wl-copy), X11 (xclip, then xsel),
   macOS (pbcopy), Windows (clip). Returns true when a tool succeeded,
   false when none is available on this platform."
  [text]
  (let [os (str/lower-case (System/getProperty "os.name" ""))
        termux? (seq (System/getenv "TERMUX_VERSION"))
        candidates (cond-> []
                     termux? (conj ["termux-clipboard-set"])
                     (and (not termux?) (str/includes? (str/lower-case os) "linux"))
                     (into [["wl-copy"] ["xclip" "-selection" "clipboard"]
                            ["xsel" "--clipboard" "--input"]])
                     (and (not termux?) (str/includes? (str/lower-case os) "mac"))
                     (conj ["pbcopy"])
                     (and (not termux?) (str/includes? (str/lower-case os) "win"))
                     (conj ["clip"]))]
    (boolean (some #(run-tool! % text) candidates))))
