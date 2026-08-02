(ns kmet.app.tools.read
  "Read tool implementation — read file contents with offset/limit.
   Also handles image files by returning base64-encoded data in :images.
   Pi: read.ts — offset is 1-indexed; truncated output carries a
   continuation footer and :truncation metadata for the TUI warning."
  (:require [clojure.string :as str]
            [clojure.java.io :as io]
            [babashka.fs :as fs]
            [kmet.app.bash-executor :as bash-exec])
  (:import [java.util Base64]))

;; ─── Image helpers ──────────────────────────────────────────────────────────

(def ^:private image-extensions
  {"png" "image/png"
   "jpg" "image/jpeg"
   "jpeg" "image/jpeg"
   "gif" "image/gif"
   "webp" "image/webp"
   "bmp" "image/bmp"})

(defn- detect-image-mime-type
  "Detect image MIME type from file path by extension."
  [path]
  (when-let [ext (fs/extension path)]
    (get image-extensions (str/lower-case (str/replace ext "." "")))))

(defn- read-file-as-base64
  "Read a file and return its contents as a base64-encoded string."
  [path]
  (.encodeToString (Base64/getEncoder)
                   (fs/read-all-bytes (io/file path))))

;; ─── Tool implementation ───────────────────────────────────────────────────

(defn execute
  "Read file contents with optional offset/limit (1-indexed, pi: read.ts).
   For image files, returns the image data in :images and a text
   description in :content. Truncated text reads return :truncation."
  [{:keys [path offset limit]}]
  (try
    (let [f (io/file path)]
      (if-not (fs/exists? f)
        {:content (str "File not found: " path) :is-error true}
        (if-let [mime-type (detect-image-mime-type path)]
          ;; Image file — read as base64 and return image block
          (let [base64-data (read-file-as-base64 path)
                img-dim (str (fs/size f) " bytes")]
            {:content (str "Read image file [" mime-type "] (" img-dim ")")
             :images [{:data base64-data :mime-type mime-type}]})
          ;; Text file — read with offset/limit (pi: 1-indexed)
          (let [content (slurp f)
                all-lines (str/split-lines content)
                total-file-lines (count all-lines)
                start-line (if offset (max 0 (dec offset)) 0)
                start-line-display (inc start-line)]
            (if (>= start-line total-file-lines)
              {:content (str "Offset " offset " is beyond end of file (" total-file-lines " lines total)")
               :is-error true}
              (let [selected (if (some? limit)
                               (let [end (min (+ start-line limit) total-file-lines)]
                                 (subvec all-lines start-line end))
                               (subvec all-lines start-line))
                    user-limited-lines (when (some? limit) (count selected))
                    selected-content (str/join "\n" selected)
                    truncation (bash-exec/truncate-head selected-content)
                    output-text (cond
                                  (:first-line-exceeds-limit truncation)
                                  (let [first-line-size (bash-exec/format-size
                                                         (count (nth all-lines start-line "")))]
                                    (str "[Line " start-line-display " is " first-line-size ", exceeds "
                                         (bash-exec/format-size bash-exec/DEFAULT-MAX-BYTES)
                                         " limit. Use bash: sed -n '" start-line-display "p' " path
                                         " | head -c " bash-exec/DEFAULT-MAX-BYTES "]"))
                                  (:truncated truncation)
                                  (let [end-line-display (+ start-line-display (:output-lines truncation) -1)
                                        next-offset (inc end-line-display)]
                                    (str (:content truncation)
                                         (if (= (:truncated-by truncation) :lines)
                                           (str "\n\n[Showing lines " start-line-display "-" end-line-display " of "
                                                total-file-lines ". Use offset=" next-offset " to continue.]")
                                           (str "\n\n[Showing lines " start-line-display "-" end-line-display " of "
                                                total-file-lines " (" (bash-exec/format-size bash-exec/DEFAULT-MAX-BYTES)
                                                " limit). Use offset=" next-offset " to continue.]"))))
                                  (and user-limited-lines (< (+ start-line user-limited-lines) total-file-lines))
                                  (let [remaining (- total-file-lines (+ start-line user-limited-lines))
                                        next-offset (+ start-line user-limited-lines 1)]
                                    (str (:content truncation) "\n\n[" remaining " more lines in file. Use offset="
                                         next-offset " to continue.]"))
                                  :else (:content truncation))]
                (cond-> {:content output-text}
                  (:truncated truncation)
                  (assoc :truncation {:total-lines (:total-lines truncation)
                                      :total-bytes (:total-bytes truncation)
                                      :output-lines (:output-lines truncation)
                                      :output-bytes (:output-bytes truncation)
                                      :truncated-by (:truncated-by truncation)
                                      :max-lines (:max-lines truncation)
                                      :max-bytes (:max-bytes truncation)
                                      :first-line-exceeds-limit (:first-line-exceeds-limit truncation)}))))))))
    (catch Exception e
      {:content (str "Error reading " path ": " (ex-message e)) :is-error true})))
