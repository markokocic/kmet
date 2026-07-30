(ns kmet.app.bash-executor
  "Bash command execution with streaming, truncation, and temp file spill.
   Port of pi's bash-executor.ts + OutputAccumulator."
  (:require [clojure.string :as str]
            [babashka.fs :as fs]
            [babashka.process :as proc]
            [kmet.debug :as debug]))

(def DEFAULT-MAX-LINES 2000)
(def DEFAULT-MAX-BYTES (* 50 1024))
(def MAX-ROLLING-BYTES (* DEFAULT-MAX-BYTES 2))
(def TEMP-FILE-PREFIX "kmet-bash-")
(def TEMP-FILE-SUFFIX ".log")

(defn kill-process-tree! [pid]
  (try
    @(proc/process ["kill" "-9" (str "-" pid)] {:out :inherit :err :inherit})
    (catch Exception _e
      (try
        @(proc/process ["kill" "-9" (str pid)] {:out :inherit :err :inherit})
        (catch Exception _e2 nil)))))

(defonce ^:private tracked-pids (atom #{}))
(defn track-pid! [pid] (swap! tracked-pids conj pid))
(defn untrack-pid! [pid] (swap! tracked-pids disj pid))
(defn kill-tracked-children! []
  (doseq [pid @tracked-pids]
    (try (kill-process-tree! pid) (catch Exception _ nil)))
  (reset! tracked-pids #{}))

(def ^:private ANSI-PATTERN #"\u001b\[[0-9;]*[a-zA-Z]")
(defn- strip-ansi [s] (str/replace s ANSI-PATTERN ""))
(defn- sanitize-output [s]
  (-> s strip-ansi
      (str/replace #"\r\n" "\n")
      (str/replace #"\r" "\n")
      (str/replace #"[^\t\n\r\u0020-\uFFFF]" "")))

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
  (let [tmp (java.io.File/createTempFile TEMP-FILE-PREFIX TEMP-FILE-SUFFIX)]
    (.deleteOnExit tmp) (str (fs/canonicalize tmp))))

(defn- resolve-shell []
  (or (when (fs/exists? "/bin/bash") "/bin/bash")
      (when (fs/exists? "/usr/bin/bash") "/usr/bin/bash")
      (try (let [p (proc/process ["sh" "-c" "command -v bash"] {:out :pipe :err :inherit})
                 _ @p output (str/trim (slurp (:out p)))]
             (when (seq output) output))
           (catch Exception _ nil))
      "sh"))

(defn create-default-ops [& {:keys [shell-path]}]
  (let [shell (or shell-path (resolve-shell))]
    (fn [{:keys [command cwd on-data signal timeout env]}]
      (let [_ (when-not (fs/exists? cwd)
                (throw (ex-info (str "Working dir not found: " cwd) {:cwd cwd})))
            shell-args [shell "-c" command]
            proc-opts {:dir cwd :err :pipe :out :pipe :env env}
            proc-opts (if timeout (assoc proc-opts :timeout (* timeout 1000)) proc-opts)
            p (proc/process shell-args proc-opts)
            pid (try (-> p :proc .pid) (catch Exception _ nil))
            _ (when pid (track-pid! pid))
            read-stream (fn [stream]
                          (let [buf (byte-array 8192)]
                            (loop [] (let [n (.read stream buf)]
                                       (when (pos? n) (on-data buf 0 n) (recur))))))]
        (let [out-future (future (try (read-stream (:out p))
                                     (catch Exception e (debug/log "ops stdout: " e))))
              _ (when-let [err-stream (:err p)]
                  (future (try (read-stream err-stream)
                              (catch Exception e (debug/log "ops stderr: " e)))))]
          (when signal
            (future (loop [] (when-not @signal (Thread/sleep 200) (recur)))
                    (when pid (kill-process-tree! pid))))
          (let [result (try (deref p) (catch Exception _ nil)) exit-code (:exit result)]
            (try (deref out-future 5000 nil) (catch Exception _ nil))
            (when pid (untrack-pid! pid))
            {:exit-code exit-code}))))))

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
          (let [decoded (String. raw-bytes offset len "UTF-8")
                clean (sanitize-output decoded)]
            (when (seq clean)
              (handle-text clean))))

        finalize
        (fn []
          (when-let [watcher @signal-watcher]
            (future-cancel watcher)
            (reset! signal-watcher nil))
          (when @temp-file-stream
            (try (.close @temp-file-stream) (catch Exception _ nil))
            (reset! temp-file-stream nil))
          (let [raw-output (apply str @tail-buf)
                clean-output (if @tail-starts-at-line-boundary
                               raw-output
                               (let [first-newline (str/index-of raw-output "\n")]
                                 (if first-newline
                                   (subs raw-output (inc first-newline))
                                   raw-output)))
                {:keys [content truncated]} (truncate-tail clean-output
                                               :max-lines max-lines
                                               :max-bytes max-bytes)]
            (when (and truncated (nil? @temp-file-path))
              (let [path (create-temp-file)]
                (reset! temp-file-path path)
                (spit path clean-output)))
            {:output content
             :exit-code nil
             :cancelled false
             :truncated truncated
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
              _ (when (and (nil? @temp-file-path)
                           (> (count clean-output) max-bytes))
                  (let [path (create-temp-file)]
                    (reset! temp-file-path path)
                    (spit path clean-output)))
              {:keys [content truncated]} (truncate-tail clean-output
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
