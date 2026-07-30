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
    :or {cwd (System/getProperty "user.dir") max-lines 2000 max-bytes (* 50 1024)}}]
  (let [resolved-cmd (if command-prefix (str command-prefix "\n" command) command)
        ops (or operations (create-default-ops :shell-path shell-path))
        raw-chunks (atom []) tail-buf (atom []) tail-bytes (atom 0)
        total-decoded-bytes (atom 0) tail-line-boundary (atom true)
        tmp-path (atom nil) tmp-stream (atom nil) sig-watcher (atom nil)
        handle-text (fn [text]
                      (let [b (count text)]
                        (swap! total-decoded-bytes + b) (swap! tail-buf conj text)
                        (swap! tail-bytes + b)
                        (when (> @tail-bytes MAX-ROLLING-BYTES)
                          (loop [] (when (and (> (count @tail-buf) 1) (> @tail-bytes MAX-ROLLING-BYTES))
                                     (let [r (first @tail-buf)]
                                       (swap! tail-buf subvec 1) (swap! tail-bytes - (count r))
                                       (when (not (str/ends-with? r "\n"))
                                         (reset! tail-line-boundary false)))
                                     (recur))))
                        (when on-chunk (try (on-chunk text) (catch Exception e (debug/log "cb: " e))))))
        handle-bytes (fn [buf off len]
                       (if (or @tmp-stream @tmp-path (> @total-decoded-bytes max-bytes))
                         (do (when (nil? @tmp-path)
                               (let [p (create-temp-file) os (java.io.FileOutputStream. p)]
                                 (reset! tmp-path p) (reset! tmp-stream os)
                                 (doseq [[d s e] @raw-chunks] (.write os d s (- e s)))
                                 (.write os buf off len) (.flush os)))
                             (when @tmp-stream (.write @tmp-stream buf off len) (.flush @tmp-stream)))
                         (let [c (java.util.Arrays/copyOfRange buf off (+ off len))]
                           (swap! raw-chunks conj [c 0 (alength c)])))
                       (let [t (String. buf off len "UTF-8") s (sanitize-output t)]
                         (when (seq s) (handle-text s))))
        finalize (fn []
                   (when-let [w @sig-watcher] (future-cancel w) (reset! sig-watcher nil))
                   (when @tmp-stream (try (.close @tmp-stream) (catch Exception _ nil))
                     (reset! tmp-stream nil))
                   (let [raw (apply str @tail-buf)
                         clean (if @tail-line-boundary raw
                                 (let [nl (str/index-of raw "\n")]
                                   (if nl (subs raw (inc nl)) raw)))
                         {:keys [content truncated]} (truncate-tail clean :max-lines max-lines :max-bytes max-bytes)]
                     (when (and truncated (nil? @tmp-path))
                       (let [p (create-temp-file)] (reset! tmp-path p) (spit p clean)))
                     {:output content :exit-code nil :cancelled false
                      :truncated truncated :full-output-path @tmp-path}))]
    (try
      (let [base (into {} (System/getenv)) merged (if env (merge base env) base)
            {:keys [command cwd env]}
            (if spawn-hook (spawn-hook {:command resolved-cmd :cwd cwd :env merged})
                {:command resolved-cmd :cwd cwd :env merged})
            ops-result (ops {:command command :cwd cwd :on-data handle-bytes
                             :signal signal :timeout timeout :env env})
            exit-code (:exit-code ops-result)]
        (loop [lb @tail-bytes dl (+ (System/currentTimeMillis) 100)]
          (when (< (System/currentTimeMillis) dl)
            (let [cb @tail-bytes]
              (if (> cb lb) (recur cb (+ (System/currentTimeMillis) 100))
                  (do (Thread/sleep 10) (recur lb dl))))))
        (assoc (finalize) :exit-code exit-code))
      (catch Exception e
        (debug/log "bash err: " e) (when-let [w @sig-watcher] (future-cancel w))
        (let [raw (apply str @tail-buf)
              clean (if @tail-line-boundary raw
                      (let [nl (str/index-of raw "\n")] (if nl (subs raw (inc nl)) raw)))
              _ (when (and (nil? @tmp-path) (> (count clean) max-bytes))
                  (let [p (create-temp-file)] (reset! tmp-path p) (spit p clean)))
              {:keys [content truncated]} (truncate-tail clean :max-lines max-lines :max-bytes max-bytes)]
          (cond (and signal @signal)
                {:output content :exit-code nil :cancelled true :truncated truncated :full-output-path @tmp-path}
                (str/includes? (str (.getMessage e)) "timeout")
                {:output (str content "\n\nTimed out after " (or timeout "?") "s")
                 :exit-code nil :cancelled false :truncated truncated :full-output-path @tmp-path}
                :else
                {:output (str content "\n\nError: " (.getMessage e))
                 :exit-code nil :cancelled false :truncated truncated :full-output-path @tmp-path}))))))
