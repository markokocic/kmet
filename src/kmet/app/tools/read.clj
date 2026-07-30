(ns kmet.app.tools.read
  "Read tool implementation — read file contents with offset/limit.
   Also handles image files by returning base64-encoded data in :images."
  (:require [clojure.string :as str]
            [clojure.java.io :as io]
            [babashka.fs :as fs])
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
  "Read file contents with optional offset/limit.
   For image files, returns the image data in :images and
   a text description in :content."
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
          ;; Text file — read with offset/limit
          (let [content (slurp f)
                lines (str/split-lines content)
                offset (or offset 0)
                limit (or limit (count lines))
                selected (->> lines (drop offset) (take limit))
                total (count lines)
                result (str/join "\n" selected)]
            {:content (str result
                           (when (> total (+ offset limit))
                             (str "\n\n[..." (- total (+ offset limit)) " more lines]"))
                           (when (pos? offset)
                             (str "\n\n[showing " (count selected) " of " total " lines]")))}))))
    (catch Exception e
      {:content (str "Error reading " path ": " (.getMessage e)) :is-error true})))
