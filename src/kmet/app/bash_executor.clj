(ns kmet.app.bash-executor
  "Bash command execution with streaming, truncation, and temp file spill.
   Port of pi's bash-executor.ts + OutputAccumulator."
  (:require [clojure.string :as str]
            [babashka.fs :as fs]
            [babashka.process :as proc]
            [kmet.libs.process :as process]
            [kmet.debug :as debug]))

(def DEFAULT-MAX-LINES 2000)
(def DEFAULT-MAX-BYTES (* 50 1024))
(def MAX-ROLLING-BYTES (* DEFAULT-MAX-BYTES 2))
(def TEMP-FILE-PREFIX "kmet-bash-")
(def TEMP-FILE-SUFFIX ".log")

;; ─── Format utilities (pi: formatSize) ──────────────────────────────────────

(defn format-size
  "Format bytes as human-readable size (pi: formatSize).
   Returns strings like '50B', '12.5KB', '1.5MB'."
  [bytes]
  (let [b (int bytes)]
    (cond
      (< b 1024) (str b "B")
      (< b (* 1024 1024)) (str (format "%.1f" (float (/ b 1024))) "KB")
      :else (str (format "%.1f" (float (/ b 1024 1024))) "MB"))))

(def ^:private ANSI-PATTERN #"\u001b\[[0-9;]*[a-zA-Z]")
(defn- strip-ansi [s] (str/replace s ANSI-PATTERN ""))
(defn- sanitize-output
  "Pi: sanitizeBinaryOutput() — strips control chars (except \\t \\n \\r)
   and Unicode format characters (U+FFF9-U+FFFB)."
  [s]
  (-> s strip-ansi
      (str/replace #"\r\n" "\n")
      (str/replace #"\r" "\n")
      ;; Pi: filter out control chars + Unicode format characters
      (str/replace #"[^\t\n\r\u0020-\uFFF8\uFFFC-\uFFFF]" "")))

(defn truncate-head [content & {:keys [max-lines max-bytes]
                                :or {max-lines 2000 max-bytes (* 50 1024)}}]
  (let [total-bytes (count content) lines (str/split-lines content) total-lines (count lines)]
    (if (and (<= total-lines max-lines) (<= total-bytes max-bytes))
      {:content content :truncated false :truncated-by nil :total-lines total-lines
       :total-bytes total-bytes :output-lines total-lines :output-bytes total-bytes
       :first-line-exceeds-limit false :max-lines max-lines :max-bytes max-bytes}
      (let [head-lines (take max-lines lines) head-content (str/join "\n" head-lines)
            head-bytes (count head-content)]
        (if (<= head-bytes max-bytes)
          {:content head-content :truncated true :truncated-by :lines
           :total-lines total-lines :total-bytes total-bytes
           :output-lines (count head-lines) :output-bytes head-bytes
           :first-line-exceeds-limit false :max-lines max-lines :max-bytes max-bytes}
          (let [reduced (loop [n (min max-lines (count lines))]
                          (let [c (str/join "\n" (take n lines))]
                            (if (or (<= (count c) max-bytes) (<= n 1)) c (recur (dec n)))))]
            (if (zero? (count (str/split-lines reduced)))
              {:content "" :truncated true :truncated-by :bytes :total-lines total-lines
               :total-bytes total-bytes :output-lines 0 :output-bytes 0
               :first-line-exceeds-limit true :max-lines max-lines :max-bytes max-bytes}
              {:content reduced :truncated true :truncated-by :bytes
               :total-lines total-lines :total-bytes total-bytes
               :output-lines (count (str/split-lines reduced))
               :output-bytes (count reduced)
               :first-line-exceeds-limit false :max-lines max-lines :max-bytes max-bytes})))))))

(defn truncate-tail [content & {:keys [max-lines max-bytes]
                                :or {max-lines 2000 max-bytes (* 50 1024)}}]
  (let [total-bytes (count content) lines (str/split-lines content) total-lines (count lines)]
    (if (and (<= total-lines max-lines) (<= total-bytes max-bytes))
      {:content content :truncated false :truncated-by nil
       :total-lines total-lines :total-bytes total-bytes
       :output-lines total-lines :output-bytes total-bytes
       :max-lines max-lines :max-bytes max-bytes}
      (let [tail-lines (take-last max-lines lines) tail-content (str/join "\n" tail-lines)
            tail-bytes (count tail-content)]
        (if (<= tail-bytes max-bytes)
          {:content tail-content :truncated true :truncated-by :lines
           :total-lines total-lines :total-bytes total-bytes
           :output-lines (count tail-lines) :output-bytes tail-bytes
           :max-lines max-lines :max-bytes max-bytes}
          (let [truncated-content (subs tail-content (max 0 (- (count tail-content) max-bytes)))
                truncated-lines (str/split-lines truncated-content)]
            {:content (str/join "\n" truncated-lines) :truncated true :truncated-by :bytes
             :total-lines total-lines :total-bytes total-bytes
             :output-lines (count truncated-lines) :output-bytes (count truncated-content)
             :max-lines max-lines :max-bytes max-bytes}))))))

(defn- create-temp-file []
  (let [tmp-dir (or (System/getenv "TMPDIR")
                    (System/getProperty "java.io.tmpdir"))
        tmp (fs/create-temp-file {:prefix TEMP-FILE-PREFIX
                                  :suffix TEMP-FILE-SUFFIX
                                  :dir tmp-dir})]
    (fs/delete-on-exit tmp)
    (str (fs/canonicalize tmp))))

(defn- find-bash-on-path []
  (try
    (let [cmd (if process/windows-os?
                ["cmd.exe" "/c" "where bash.exe"]
                ["sh" "-c" "command -v bash"])
          p (proc/process cmd {:out :pipe :err :inherit})
          _ @p
          output (str/trim (slurp (:out p)))
          ;; Pi: where can return non-existent paths on Windows — verify the file exists
          first-match (first (str/split-lines output))]
      (when (and first-match
                 (or (not process/windows-os?) (fs/exists? first-match))
                 (seq (str/trim first-match)))
        (str/trim first-match)))
    (catch Exception _ nil)))

(defn- resolve-shell []
  (or (when (fs/exists? "/bin/bash") "/bin/bash")
      (when (fs/exists? "/usr/bin/bash") "/usr/bin/bash")
      (find-bash-on-path)
      (when process/windows-os?
        (some (fn [env-var]
                (when-let [pf (System/getenv env-var)]
                  (let [path (str pf "\\Git\\bin\\bash.exe")]
                    (when (fs/exists? path) path))))
              ["ProgramFiles" "ProgramFiles(x86)"]))
      (if process/windows-os? "cmd.exe" "sh")))

(defn create-default-ops [& {:keys [shell-path]}]
  ;; Pi: throw early if custom shellPath is specified but not found
  (when (and shell-path (not (fs/exists? shell-path)))
    (throw (ex-info (str "Custom shell path not found: " shell-path)
                    {:shell-path shell-path})))
  (let [shell (or shell-path (resolve-shell))]
    (fn [{:keys [command cwd on-data signal timeout env]}]
      (let [_ (when-not (fs/exists? cwd)
                (throw (ex-info (str "Working directory does not exist: " cwd) {:cwd cwd})))
            ;; Pi: getShellConfig resolves shell + args per platform
            use-stdin? (and process/windows-os?
                            (re-find #"(?i)windows\\system32\\bash\.exe"
                                     (str/replace shell "/" "\\")))
            shell-args (cond
                         (str/includes? shell "cmd") [shell "/c" command]
                         use-stdin? [shell "-s"]
                         ;; setsid makes the sh its own process-group leader so
                         ;; kill-process-tree! can group-kill it — catches bg
                         ;; jobs mksh reparents outside the ppid tree.
                         :else (if-let [setsid @process/setsid-path]
                                 [setsid shell "-c" command]
                                 [shell "-c" command]))
            proc-opts {:dir cwd :err :pipe :out :pipe :env env
                       ;; Pi: stdin pipe when using -s transport
                       :in (if use-stdin? :pipe :inherit)}
            p (proc/process shell-args proc-opts)
            ;; Pi: write command to stdin for WSL -s transport
            _ (when (and use-stdin? (:in p))
                (try (spit (:in p) command) (catch Exception _ nil))
                (try (.close (:in p)) (catch Exception _ nil)))
            pid (try (-> p :proc .pid) (catch Exception _ nil))
            _ (when pid (process/track-pid! pid))
            ;; Pi: timeout handled manually (setTimeout + killProcessTree) —
            ;; babashka.process's :timeout reports exit 0 on kill, so a
            ;; timed-out command wouldn't be distinguishable from success.
            timed-out (atom false)
            timeout-watcher (when (and (number? timeout) (pos? timeout) pid)
                              (future
                                (Thread/sleep (* timeout 1000))
                                (reset! timed-out true)
                                (process/kill-process-tree! pid)))
            read-stream (fn [stream]
                          (let [buf (byte-array 8192)]
                            (loop [] (let [n (.read stream buf)]
                                       (when (pos? n) (on-data buf 0 n) (recur))))))
            out-future (future (try (read-stream (:out p))
                                    (catch Exception e (debug/log "ops stdout: " e))))
            _ (when-let [err-stream (:err p)]
                (future (try (read-stream err-stream)
                             (catch Exception e (debug/log "ops stderr: " e)))))]
        (when signal
          (future (loop [] (when-not @signal (Thread/sleep 200) (recur)))
                  (when pid (process/kill-process-tree! pid))))
        (let [result (try (deref p) (catch Exception _ nil)) exit-code (:exit result)]
          (when timeout-watcher (future-cancel timeout-watcher))
          (try (deref out-future 5000 nil) (catch Exception _ nil))
          (when pid (process/untrack-pid! pid))
          (cond
              ;; Pi: after the process exits, re-check abort/timeout — the kill
              ;; itself returns normally (SIGKILL exit code), so cancelled/timeout
              ;; must be inferred from the signal/flag, not the exit code.
            @timed-out (throw (ex-info (str "timeout:" timeout) {}))
            (and signal @signal) (throw (ex-info "aborted" {}))
            :else {:exit-code exit-code}))))))

(defn execute-bash
  [{:keys [command cwd env on-chunk signal timeout spawn-hook
           operations shell-path command-prefix max-lines max-bytes]
    :or {cwd (System/getProperty "user.dir")
         max-lines DEFAULT-MAX-LINES
         max-bytes DEFAULT-MAX-BYTES}}]
  (let [;; Resolve command with prefix (pi: commandPrefix)
        resolved-command (if command-prefix
                           (str command-prefix "\n" command)
                           command)
        ;; Use provided operations or default (pi: BashOperations)
        exec-ops (or operations (create-default-ops :shell-path shell-path))

        raw-chunks (atom [])
        tail-buf (atom [])
        tail-bytes (atom 0)
        total-decoded-bytes (atom 0)
        tail-starts-at-line-boundary (atom true)
        temp-file-path (atom nil)
        temp-file-stream (atom nil)
        signal-watcher (atom nil)

        handle-text
        (fn [text]
          (let [bytes (count text)]
            (swap! total-decoded-bytes + bytes)
            (swap! tail-buf conj text)
            (swap! tail-bytes + bytes)
            (when (> @tail-bytes MAX-ROLLING-BYTES)
              (loop []
                (when (and (> (count @tail-buf) 1)
                           (> @tail-bytes MAX-ROLLING-BYTES))
                  (let [removed (first @tail-buf)]
                    (swap! tail-buf subvec 1)
                    (swap! tail-bytes - (count removed))
                    (when (not (str/ends-with? removed "\n"))
                      (reset! tail-starts-at-line-boundary false)))
                  (recur))))
            (when on-chunk
              (try (on-chunk text)
                   (catch Exception e
                     (debug/log "bash chunk callback: " e))))))

        ;; Pi: streaming UTF-8 decoder (TextDecoder with {stream: true})
        utf8-decoder
        (let [cs (java.nio.charset.Charset/forName "UTF-8")
              d (.newDecoder cs)]
          (.onMalformedInput d java.nio.charset.CodingErrorAction/REPLACE)
          (.onUnmappableCharacter d java.nio.charset.CodingErrorAction/REPLACE)
          d)

        decode-chunk
        (fn [raw-bytes offset len end-of-input?]
          (let [in-buf (java.nio.ByteBuffer/wrap raw-bytes offset len)
                ;; Allocate generous output buffer (UTF-8 max 4 bytes/char → len * 2 is safe)
                out-buf (java.nio.CharBuffer/allocate (max 64 (* len 2)))
                _ (.decode utf8-decoder in-buf out-buf end-of-input?)
                pos (.position out-buf)]
            (when (pos? pos)
              (String. (.array out-buf) 0 pos))))

        finish-utf8
        (fn []
          (let [out-buf (java.nio.CharBuffer/allocate 64)
                _ (.decode utf8-decoder
                           (java.nio.ByteBuffer/wrap (byte-array 0)) out-buf true)
                _ (.flush utf8-decoder out-buf)
                pos (.position out-buf)]
            (when (pos? pos)
              (String. (.array out-buf) 0 pos))))

        handle-raw-bytes
        (fn [raw-bytes offset len]
          (if (or @temp-file-stream @temp-file-path
                  (> @total-decoded-bytes max-bytes))
            (do
              (when (nil? @temp-file-path)
                (let [path (create-temp-file)
                      output-stream (java.io.FileOutputStream. path)]
                  (reset! temp-file-path path)
                  (reset! temp-file-stream output-stream)
                  (doseq [[data start end] @raw-chunks]
                    (.write output-stream data start (- end start)))
                  (.write output-stream raw-bytes offset len)
                  (.flush output-stream)))
              (when @temp-file-stream
                (.write @temp-file-stream raw-bytes offset len)
                (.flush @temp-file-stream)))
            (let [copy (java.util.Arrays/copyOfRange raw-bytes offset (+ offset len))]
              (swap! raw-chunks conj [copy 0 (alength copy)])))
          ;; Pi: streaming decode — handles multi-byte sequences split across chunks
          (when-let [decoded (decode-chunk raw-bytes offset len false)]
            (let [clean (sanitize-output decoded)]
              (when (seq clean)
                (handle-text clean)))))

        finalize
        (fn []
          (when-let [watcher @signal-watcher]
            (future-cancel watcher)
            (reset! signal-watcher nil))
          (when @temp-file-stream
            (try (.close @temp-file-stream) (catch Exception _ nil))
            (reset! temp-file-stream nil))
          ;; Pi: flush remaining bytes from streaming decoder (end-of-input)
          (when-let [flushed (finish-utf8)]
            (let [clean (sanitize-output flushed)]
              (when (seq clean)
                (handle-text clean))))
          (let [raw-output (apply str @tail-buf)
                clean-output (if @tail-starts-at-line-boundary
                               raw-output
                               (let [first-newline (str/index-of raw-output "\n")]
                                 (if first-newline
                                   (subs raw-output (inc first-newline))
                                   raw-output)))
                truncation (truncate-tail clean-output
                                          :max-lines max-lines
                                          :max-bytes max-bytes)
                truncated (:truncated truncation)
                content (:content truncation)]
            (when (and truncated (nil? @temp-file-path))
              (let [path (create-temp-file)]
                (reset! temp-file-path path)
                (spit path clean-output)))
            {:output content
             :exit-code nil
             :cancelled false
             :truncated truncated
             :truncation truncation
             :full-output-path @temp-file-path}))]

    (try
      (let [base-env (into {} (System/getenv))
            merged-env (if env (merge base-env env) base-env)
            {:keys [command cwd env]}
            (if spawn-hook
              (spawn-hook {:command resolved-command :cwd cwd :env merged-env})
              {:command resolved-command :cwd cwd :env merged-env})
            ops-result (exec-ops
                        {:command command
                         :cwd cwd
                         :on-data handle-raw-bytes
                         :signal signal
                         :timeout timeout
                         :env env})
            exit-code (:exit-code ops-result)]
        ;; Grace polling for pending stream data after operations complete
        (loop [last-bytes @tail-bytes
               deadline (+ (System/currentTimeMillis) 100)]
          (when (< (System/currentTimeMillis) deadline)
            (let [current-bytes @tail-bytes]
              (if (> current-bytes last-bytes)
                (recur current-bytes (+ (System/currentTimeMillis) 100))
                (do (Thread/sleep 10)
                    (recur last-bytes deadline))))))
        (assoc (finalize) :exit-code exit-code))

      (catch Exception e
        (debug/log "bash execute error: " e)
        (when-let [watcher @signal-watcher]
          (future-cancel watcher))
        (let [raw-output (apply str @tail-buf)
              clean-output (if @tail-starts-at-line-boundary
                             raw-output
                             (let [first-newline (str/index-of raw-output "\n")]
                               (if first-newline
                                 (subs raw-output (inc first-newline))
                                 raw-output)))
              truncation (truncate-tail clean-output
                                        :max-lines max-lines
                                        :max-bytes max-bytes)
              content (:content truncation)
              truncated (:truncated truncation)
              ;; Pi: persistIfTruncated — save the full output whenever truncated
              _ (when (and truncated (nil? @temp-file-path))
                  (let [path (create-temp-file)]
                    (reset! temp-file-path path)
                    (spit path clean-output)))]
          (cond
            (and signal @signal)
            {:output content :exit-code nil :cancelled true
             :truncated truncated :truncation truncation
             :full-output-path @temp-file-path}
            (str/includes? (str (ex-message e)) "timeout")
            {:output content :exit-code nil :cancelled false :timed-out true
             :truncated truncated :truncation truncation
             :full-output-path @temp-file-path}
            :else
            {:output (str content "\n\nError: " (ex-message e))
             :exit-code nil :cancelled false
             :truncated truncated :truncation truncation
             :full-output-path @temp-file-path}))))))
