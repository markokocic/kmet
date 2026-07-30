(ns kmet.app.bash-executor
  "Bash command execution with streaming, truncation, and temp file spill.
   Port of pi's bash-executor.ts + OutputAccumulator.

   Design:
   - Rolling tail buffer for real-time display (bounded memory)
   - Temp file for full output when buffer exceeds threshold
   - Streams decoded + sanitized chunks via callback
   - Session env injection (PI_SESSION_ID, PI_PROVIDER, PI_MODEL, etc.)
   - Spawn hook for command/env customization
   - Process group kill for clean cancellation
   - Detached child PID tracking for shutdown cleanup"
  (:require [clojure.string :as str]
            [babashka.fs :as fs]
            [babashka.process :as proc]
            [kmet.debug :as debug]))

;; ─── Constants ─────────────────────────────────────────────────────────────

(def DEFAULT-MAX-LINES 2000)
(def DEFAULT-MAX-BYTES (* 50 1024))
(def MAX-ROLLING-BYTES (* DEFAULT-MAX-BYTES 2))
(def TEMP-FILE-PREFIX "kmet-bash-")
(def TEMP-FILE-SUFFIX ".log")

;; ─── Process tree kill (pi: killProcessTree) ───────────────────────────────

(defn kill-process-tree!
  "Kill a process and all its children using SIGKILL on the process group.
   Pi: killProcessTree() — uses process.kill(-pid, 'SIGKILL') on Unix.
   Falls back to killing just the child if the group kill fails."
  [pid]
  (try
    ;; Kill the full process group (negative PID) via `kill` command
    @(proc/process ["kill" "-9" (str "-" pid)] {:out :inherit :err :inherit})
    (catch Exception _e
      ;; Fallback to killing just the child
      (try
        @(proc/process ["kill" "-9" (str pid)] {:out :inherit :err :inherit})
        (catch Exception _e2
          ;; Process already dead
          nil)))))

;; ─── Detached child PID tracking (pi: Set + killTrackedDetachedChildren) ────

(defonce ^:private tracked-pids (atom #{}))

(defn track-pid!
  "Register a PID for cleanup on shutdown."
  [pid]
  (swap! tracked-pids conj pid))

(defn untrack-pid!
  "Unregister a PID (done when process finishes normally)."
  [pid]
  (swap! tracked-pids disj pid))

(defn kill-tracked-children!
  "Kill all tracked child processes. Called on shutdown to clean up orphans."
  []
  (doseq [pid @tracked-pids]
    (try (kill-process-tree! pid) (catch Exception _ nil)))
  (reset! tracked-pids #{}))

;; ─── Output sanitization ──────────────────────────────────────────────────

(def ^:private ANSI-PATTERN #"\u001b\[[0-9;]*[a-zA-Z]")

(defn- strip-ansi [s]
  (str/replace s ANSI-PATTERN ""))

(defn- sanitize-output
  "Sanitize binary output for display.
   Strips ANSI, normalizes line endings, removes control chars except \\t \\n \\r.
   Pi: sanitizeBinaryOutput() + stripAnsi()"
  [s]
  (-> s
      strip-ansi
      (str/replace #"\r\n" "\n")
      (str/replace #"\r" "\n")
      (str/replace #"[^\t\n\r\u0020-\uFFFF]" "")))

;; ─── Truncation ────────────────────────────────────────────────────────────

(defn truncate-tail
  "Keep the last N lines of content (matching pi's truncateTail).
   Returns {:content str :truncated bool :truncated-by (keyword or nil)
            :total-lines int :total-bytes int
            :output-lines int :output-bytes int
            :max-lines int :max-bytes int}"
  [content & {:keys [max-lines max-bytes]
              :or {max-lines DEFAULT-MAX-LINES
                   max-bytes DEFAULT-MAX-BYTES}}]
  (let [total-bytes (count content)
        lines (str/split-lines content)
        total-lines (count lines)]
    (if (and (<= total-lines max-lines) (<= total-bytes max-bytes))
      {:content content
       :truncated false
       :truncated-by nil
       :total-lines total-lines
       :total-bytes total-bytes
       :output-lines total-lines
       :output-bytes total-bytes
       :max-lines max-lines
       :max-bytes max-bytes}
      (let [tail-lines (take-last max-lines lines)
            tail-content (str/join "\n" tail-lines)
            tail-bytes (count tail-content)]
        (if (<= tail-bytes max-bytes)
          ;; Truncated by lines
          {:content tail-content
           :truncated true
           :truncated-by :lines
           :total-lines total-lines
           :total-bytes total-bytes
           :output-lines (count tail-lines)
           :output-bytes tail-bytes
           :max-lines max-lines
           :max-bytes max-bytes}
          ;; Truncated by bytes
          (let [truncated-content (subs tail-content (max 0 (- (count tail-content) max-bytes)))
                truncated-lines (str/split-lines truncated-content)]
            {:content (str/join "\n" truncated-lines)
             :truncated true
             :truncated-by :bytes
             :total-lines total-lines
             :total-bytes total-bytes
             :output-lines (count truncated-lines)
             :output-bytes (count truncated-content)
             :max-lines max-lines
             :max-bytes max-bytes}))))))

;; ─── Temp file ─────────────────────────────────────────────────────────────

(defn- create-temp-file []
  (let [tmp (java.io.File/createTempFile TEMP-FILE-PREFIX TEMP-FILE-SUFFIX)]
    (.deleteOnExit tmp)
    (str (fs/canonicalize tmp))))

;; ─── Shell resolution (pi: getShellConfig) ────────────────────────────────

(defn- resolve-shell []
  (or (when (fs/exists? "/bin/bash") "/bin/bash")
      (when (fs/exists? "/usr/bin/bash") "/usr/bin/bash")
      (try
        (let [p (proc/process ["sh" "-c" "command -v bash"] {:out :pipe :err :inherit})
              _ @p
              output (str/trim (slurp (:out p)))]
          (when (seq output) output))
        (catch Exception _ nil))
      "sh"))

;; ─── Core execution ───────────────────────────────────────────────────────

(defn execute-bash
  "Execute a bash command with streaming output.

   Parameters (map):
     :command    — shell command string (required)
     :cwd        — working directory (default: current dir)
     :env        — extra environment variables map {str str} (merged into process env)
     :on-chunk   — (fn [text]) called for each decoded chunk during execution
     :signal     — atom; set to true to cancel execution
     :timeout    — timeout in seconds (optional)
     :spawn-hook — (fn [{:keys [command cwd env]}] → {:keys [command cwd env]})
                   lets extensions rewrite command/cwd/env before execution
     :max-lines  — max lines before truncation (default: 2000)
     :max-bytes  — max bytes before truncation (default: 50KB)

   Returns map:
     :output          — string (possibly truncated)
     :exit-code       — int or nil (nil if cancelled)
     :cancelled       — boolean
     :truncated       — boolean
     :full-output-path — string or nil (path to temp file with full output)"
  [{:keys [command cwd env on-chunk signal timeout spawn-hook max-lines max-bytes]
    :or {cwd (System/getProperty "user.dir")
         max-lines DEFAULT-MAX-LINES
         max-bytes DEFAULT-MAX-BYTES}}]
  (let [;; Pi's OutputAccumulator keeps raw chunks until temp file is created
        raw-chunks (atom [])   ;; raw byte arrays before temp file (pi: rawChunks)
        tail-buf (atom [])
        tail-bytes (atom 0)
        total-decoded-bytes (atom 0)
        tail-starts-at-line-boundary (atom true)  ;; pi: tailStartsAtLineBoundary
        temp-file-path (atom nil)
        ;; Pi: temp file uses raw OutputStream (writes raw bytes, not decoded text)
        temp-file-stream (atom nil)  ;; raw OutputStream for temp file
        process-pid (atom nil)
        signal-watcher (atom nil)

        ;; ─── Handle decoded text for display pipeline ────────────────────
        ;; Pi: separate path: raw bytes → temp file, decoded text → display
        handle-text
        (fn [text]
          (let [bytes (count text)]
            (swap! total-decoded-bytes + bytes)
            (swap! tail-buf conj text)
            (swap! tail-bytes + bytes)
            ;; Trim rolling tail (pi: keeps maxOutputBytes = DEFAULT_MAX_BYTES * 2)
            (when (> @tail-bytes MAX-ROLLING-BYTES)
              (loop []
                (when (and (> (count @tail-buf) 1)
                           (> @tail-bytes MAX-ROLLING-BYTES))
                  (let [removed (first @tail-buf)]
                    (swap! tail-buf subvec 1)
                    (swap! tail-bytes - (count removed))
                    ;; Pi: track whether we're at a line boundary after removal
                    (when (not (str/ends-with? removed "\n"))
                      (reset! tail-starts-at-line-boundary false)))
                  (recur))))
            ;; Stream sanitized text to callback (display pipeline)
            (when on-chunk
              (try (on-chunk text)
                   (catch Exception e
                     (debug/log "bash chunk callback: " e))))))

        ;; ─── Handle raw bytes — write to temp file, decode for display ──
        ;; Pi: onData receives raw Buffer, writes to temp file, decodes for display
        handle-raw-bytes
        (fn [raw-bytes offset len]
          ;; Pi: write raw bytes to temp file (OutputStream, not Writer)
          (if (or @temp-file-stream @temp-file-path
                  (> @total-decoded-bytes max-bytes))
            (do
              ;; Pi: ensureTempFile — creates temp file, writes all accumulated raw chunks
              (when (nil? @temp-file-path)
                (let [path (create-temp-file)
                      os (java.io.FileOutputStream. path)]
                  (reset! temp-file-path path)
                  (reset! temp-file-stream os)
                  ;; Pi: write all accumulated raw byte arrays to temp file
                  (doseq [[data start end] @raw-chunks]
                    (.write os data start (- end start)))
                  ;; Write current raw bytes
                  (.write os raw-bytes offset len)
                  (.flush os)))
              ;; Write current raw bytes to temp file (pi: writes raw Buffer)
              (when @temp-file-stream
                (.write @temp-file-stream raw-bytes offset len)
                (.flush @temp-file-stream)))
            ;; Pi: keep raw bytes in memory until threshold is reached
            ;; Must copy the byte array since the buffer is reused (pi: rawChunks stores Buffer copies)
            (let [copy (java.util.Arrays/copyOfRange raw-bytes offset (+ offset len))]
              (swap! raw-chunks conj [copy 0 (alength copy)])))
          ;; Decode bytes to string for display pipeline
          (let [text (String. raw-bytes offset len "UTF-8")
                clean (sanitize-output text)]
            (when (seq clean)
              (handle-text clean))))

        ;; ─── Read a stream into the raw byte pipeline ────────────────────
        read-stream
        (fn [stream]
          (let [buf (byte-array 8192)]
            (loop []
              (let [n (.read stream buf)]
                (when (pos? n)
                  (handle-raw-bytes buf 0 n)
                  (recur))))))

        ;; ─── Finalize and build result ──────────────────────────────────
        finalize
        (fn []
          ;; Cancel signal watcher if still running (process finished normally)
          (when-let [w @signal-watcher]
            (future-cancel w)
            (reset! signal-watcher nil))
          (when @temp-file-stream
            (try (.close @temp-file-stream) (catch Exception _ nil))
            (reset! temp-file-stream nil))
          (let [;; Pi: if tail doesn't start at a line boundary, skip the partial first line
                raw-output (apply str @tail-buf)
                clean-output (if @tail-starts-at-line-boundary
                               raw-output
                               (let [first-nl (str/index-of raw-output "\n")]
                                 (if first-nl
                                   (subs raw-output (inc first-nl))
                                   raw-output)))
                {:keys [content truncated]} (truncate-tail clean-output
                                               :max-lines max-lines
                                               :max-bytes max-bytes)]
            ;; Pi: if no temp file was opened during streaming but output is truncated
            (when (and truncated (nil? @temp-file-path))
              (let [path (create-temp-file)]
                (reset! temp-file-path path)
                (spit path clean-output)))
            {:output content
             :exit-code nil
             :cancelled false
             :truncated truncated
             :full-output-path @temp-file-path}))]

    ;; ─── Start execution ─────────────────────────────────────────────────
    (try
      (let [shell (resolve-shell)
            _ (when-not (fs/exists? cwd)
                (throw (ex-info (str "Working directory does not exist: " cwd)
                         {:cwd cwd})))

            ;; ─── Build env (pi: resolveSpawnContext) ─────────────────────
            base-env (into {} (System/getenv))
            merged-env (if env (merge base-env env) base-env)

            ;; ─── Spawn hook (pi: BashSpawnHook) ─────────────────────────
            {:keys [command final-cwd final-env]}
            (if spawn-hook
              (spawn-hook {:command command :cwd cwd :env merged-env})
              {:command command :cwd cwd :env merged-env})

            shell-args [shell "-c" command]
            proc-opts {:dir final-cwd
                       :err :pipe
                       :out :pipe
                       :env final-env}
            proc-opts (if timeout
                        (assoc proc-opts :timeout (* timeout 1000))
                        proc-opts)
            p (proc/process shell-args proc-opts)

            ;; ─── Track PID for cleanup ──────────────────────────────────
            pid (try (-> p :proc .pid) (catch Exception _ nil))
            _ (when pid (track-pid! pid) (reset! process-pid pid))

            ;; Pi: read raw bytes from stdout InputStream
            out-stream (:out p)
            stdout-future
            (future
              (try
                (read-stream out-stream)
                (catch Exception e
                  (debug/log "bash stdout stream: " e))))

            ;; Pi: read stderr in a separate future (same raw byte pipeline)
            stderr-future
            (when-let [err-stream (:err p)]
              (future
                (try
                  (read-stream err-stream)
                  (catch Exception e
                    (debug/log "bash stderr stream: " e)))))]

        ;; Watch for cancellation signal — kill process group when triggered
        ;; Pi: AbortSignal listener kills process tree
        (when signal
          (let [w (future
                    (loop []
                      (when-not @signal
                        (Thread/sleep 200)
                        (recur)))
                    (when @process-pid
                      (debug/log "bash: cancelling process group " @process-pid)
                      (kill-process-tree! @process-pid)))]
            (reset! signal-watcher w)))

        ;; Wait for process completion
        (let [result (try
                       (deref p)
                       (catch Exception e
                         (debug/log "bash process wait: " e)
                         nil))
              exit-code (:exit result)]
          ;; Pi: waitForChildProcess — grace timer after exit, re-armed on each data chunk
          (let [grace-ms 100]
            (loop [last-bytes @tail-bytes
                   deadline (+ (System/currentTimeMillis) grace-ms)]
              (let [stdout-done (future-done? stdout-future)
                    stderr-done (or (nil? stderr-future) (future-done? stderr-future))]
                (when (and (not (and stdout-done stderr-done))
                           (< (System/currentTimeMillis) deadline))
                  ;; Re-arm grace timer if new data arrived (pi: onData re-arms timer)
                  (let [current-bytes @tail-bytes]
                    (recur (max last-bytes current-bytes)
                           (if (> current-bytes last-bytes)
                             (+ (System/currentTimeMillis) grace-ms)
                             deadline)))))))
          ;; Untrack PID
          (when pid (untrack-pid! pid))
          (let [finalized (finalize)]
            (assoc finalized :exit-code exit-code))))

      (catch Exception e
        (debug/log "bash execute error: " e)
        ;; Cancel signal watcher if process exited before signal
        (when-let [w @signal-watcher] (future-cancel w))
        ;; Untrack PID if we got one
        (when-let [pid @process-pid] (untrack-pid! pid))
        (let [raw-output (apply str @tail-buf)
              ;; Pi: apply tail-starts-at-line-boundary fix (same as finalize)
              full-output (if @tail-starts-at-line-boundary
                            raw-output
                            (let [first-nl (str/index-of raw-output "\n")]
                              (if first-nl
                                (subs raw-output (inc first-nl))
                                raw-output)))
              _ (when (and (nil? @temp-file-path)
                           (> (count full-output) max-bytes))
                  (let [path (create-temp-file)]
                    (reset! temp-file-path path)
                    (spit path full-output)))
              {:keys [content truncated]} (truncate-tail full-output
                                             :max-lines max-lines
                                             :max-bytes max-bytes)]
          (cond
            (and signal @signal)
            {:output content :exit-code nil :cancelled true
             :truncated truncated :full-output-path @temp-file-path}

            (str/includes? (str (.getMessage e)) "timeout")
            {:output (str content "\n\nCommand timed out after " (or timeout "?") "s")
             :exit-code nil :cancelled false
             :truncated truncated :full-output-path @temp-file-path}

            :else
            {:output (str content "\n\nError: " (.getMessage e))
             :exit-code nil :cancelled false
             :truncated truncated :full-output-path @temp-file-path}))))))
