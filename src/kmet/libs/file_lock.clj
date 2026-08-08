(ns kmet.libs.file-lock
  "Atomic single-writer file lock via atomic directory creation
   (pi: proper-lockfile.lockSync — brief retry; locks older than 10s left by
   crashed writers are broken). Self-contained: only babashka.fs."
  (:require [babashka.fs :as fs]))

(def ^:private lock-stale-ms 10000)
(def ^:private lock-acquire-attempts 10)
(def ^:private lock-acquire-delay-ms 20)

(defn- acquire-lock!
  [lock-path]
  (loop [attempt 1]
    (cond
      (try (fs/create-dir lock-path) true (catch Exception _ false)) :ok
      (try (> (- (System/currentTimeMillis) (.toMillis (fs/last-modified-time lock-path)))
              lock-stale-ms)
           (catch Exception _ false))
      (do (fs/delete-tree lock-path) (Thread/sleep lock-acquire-delay-ms) (recur attempt))
      (< attempt lock-acquire-attempts)
      (do (Thread/sleep lock-acquire-delay-ms) (recur (inc attempt)))
      :else (throw (ex-info "Timed out acquiring file lock"
                            {:type :file-lock-timeout :path lock-path})))))

(defn with-file-lock
  "Run F with the lock at LOCK-PATH held (directory lock; stale locks older
   than 10s are broken)."
  [lock-path f]
  (acquire-lock! lock-path)
  (try (f)
       (finally (fs/delete-tree lock-path))))
