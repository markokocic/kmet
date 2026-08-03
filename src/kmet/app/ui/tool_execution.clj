(ns kmet.app.ui.tool-execution
  "ToolExecutionComponent component — Pi's ToolExecutionComponent.
   Uses a Box (with status background) wrapping a Container that holds
   the call-render and result-render children.
   Matching Pi architecture: Box handles padding/background/caching.
   Timing is managed internally (started-at on first content, ended-at on error/finalize)."
  (:require [kmet.tui.protocols :as protocols]
            [kmet.tui.theme :as theme]
            [kmet.tui.utils :as utils]
            [kmet.tui.components.text :as text]
            [kmet.tui.components.box :as box]
            [kmet.tui.components.container :as container]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [babashka.fs :as fs]
            [kmet.app.keybindings :as app-kb]
            [kmet.app.bash-executor :as bash-exec]
            [kmet.app.tools.edit-diff :as edit-diff]
            [kmet.tui.components.spacer :as spacer]
            [kmet.tui.components.image :as ic]
            [kmet.libs.terminal-image :as timg]
            [kmet.tui.macros :refer [defsetter defgetter defcomponent]]
            [cheshire.core :as json]))

;; ─── Shared render helpers (pi: render-utils.ts) ────────────────────────────

(defn- tool-path-str
  "Pi: str() — string passes through, missing → \"\", non-string → null."
  [raw-path]
  (if (string? raw-path) raw-path (if (nil? raw-path) "" nil)))

(defn- replace-tabs
  "Pi: replaceTabs — tabs render as 3 spaces."
  [text]
  (str/replace text "\t" "   "))

(defn- normalize-display-text
  "Pi: normalizeDisplayText — strip carriage returns."
  [text]
  (str/replace text "\r" ""))

(defn- sanitize-display-text
  "Pi: getTextOutput — strip ANSI codes and control characters
   (except \t \n \r) before display."
  [text]
  (-> text
      (utils/strip-ansi-codes)
      (str/replace #"\r\n" "\n")
      (str/replace #"\r" "\n")
      (str/replace #"[^\t\n\r\u0020-\uFFF8\uFFFC-\uFFFF]" "")))

(defn- shorten-path
  "Pi: shortenPath — replace the home directory prefix with ~."
  [path]
  (let [home (str (fs/home))]
    (if (and (pos? (count home))
             (or (= path home) (str/starts-with? path (str home "/"))))
      (str "~" (subs path (count home)))
      path)))

(defn- url-encode
  "Percent-encode a path for use in a file:// URL (pi: pathToFileURL)."
  [s]
  (let [safe #{\- \_ \. \~ \/ \:}
        bytes (.getBytes ^String s "UTF-8")]
    (apply str
           (for [b bytes
                 :let [c (bit-and (int b) 0xFF)]]
             (if (or (and (>= c 0x41) (<= c 0x5A))
                     (and (>= c 0x61) (<= c 0x7A))
                     (and (>= c 0x30) (<= c 0x39))
                     (contains? safe (char c)))
               (char c)
               (format "%%%02X" c))))))

(defn- file-url
  "Pi: pathToFileURL(...).href — file:// + percent-encoded absolute path."
  [path]
  (str "file://" (url-encode (if (str/starts-with? path "/") path (str "/" path)))))

(defn- resolve-path
  "Pi: resolvePath — absolute path for raw-path relative to cwd."
  [raw-path cwd]
  (if (fs/absolute? raw-path)
    (str (fs/absolutize raw-path))
    (str (fs/normalize (fs/path cwd raw-path)))))

(defn- link-path
  "Pi: linkPath — wrap styled text in an OSC 8 hyperlink when the terminal
   supports hyperlinks and the path is non-empty."
  [styled raw-path cwd]
  (if (and (:hyperlinks (timg/get-capabilities))
           (string? raw-path)
           (pos? (count raw-path)))
    (str "\u001b]8;;" (file-url (resolve-path raw-path cwd)) "\u001b\\" styled "\u001b]8;;\u001b\\")
    styled))

(defn- render-tool-path
  "Pi: renderToolPath — accent path (shortened + hyperlinked when supported);
   '...' toolOutput when empty; '[invalid arg]' error when the arg is not a string."
  [raw-path theme cwd]
  (let [s (tool-path-str raw-path)]
    (if (nil? s)
      (theme/fg theme :error "[invalid arg]")
      (if (empty? s)
        (theme/fg theme :tool-output "...")
        (link-path (theme/fg theme :accent (shorten-path s)) s cwd)))))

(defn- trim-trailing-empty-lines
  "Pi: trimTrailingEmptyLines — drop empty lines at the end of a vector."
  [lines]
  (let [n (count lines)]
    (loop [end n]
      (if (and (pos? end) (= "" (nth lines (dec end))))
        (recur (dec end))
        (subvec lines 0 end)))))

;; ─── Compact read classification (pi: read.ts getCompactReadClassification) ─

(def ^:private compact-resource-file-names
  #{"AGENTS.md" "AGENTS.MD" "CLAUDE.md" "CLAUDE.MD"})

(defn- path-relative-to-cwd-or-absolute
  "Pi: formatPathRelativeToCwdOrAbsolute — path relative to cwd when inside
   it, absolute otherwise."
  [file-path cwd]
  (let [abs (resolve-path file-path cwd)
        cwd-abs (str (fs/absolutize cwd))]
    (if (= abs cwd-abs)
      "."
      (if (str/starts-with? abs (str cwd-abs "/"))
        (subs abs (count (str cwd-abs "/")))
        abs))))

(defn- compact-read-classification
  "Pi: getCompactReadClassification — SKILL.md and AGENTS.md/CLAUDE.md files
   render as compact labels instead of paths. Returns
   {:kind :skill|:resource :label str} or nil."
  [raw-path cwd]
  (when (and (string? raw-path) (pos? (count raw-path)))
    (let [file-name (fs/file-name raw-path)]
      (cond
        (= file-name "SKILL.md")
        {:kind :skill
         :label (or (some-> (fs/parent raw-path) fs/file-name str) file-name)}
        (contains? compact-resource-file-names file-name)
        {:kind :resource
         :label (path-relative-to-cwd-or-absolute raw-path cwd)}
        :else nil))))

(defn- read-line-range
  "Pi: formatReadLineRange — ':start-end' warning suffix when offset/limit given."
  [args theme]
  (let [offset (:offset args)
        limit (:limit args)]
    (when (or offset limit)
      (let [start-line (or offset 1)
            end-line (when limit (+ start-line limit -1))]
        (theme/fg theme :warning
                  (str ":" start-line (when end-line (str "-" end-line))))))))

(defn- expand-key-text
  "Pi: keyText('app.tools.expand') — all resolved key chords joined with '/'."
  []
  (app-kb/key-text "app.tools.expand"))

(defn- format-compact-read-call
  "Pi: formatCompactReadCall — skill/resources render as labeled read calls
   with an expand hint instead of a full path."
  [classification _args theme range-str]
  (let [expand-hint (theme/fg theme :dim
                              (str " (" (expand-key-text) " to expand)"))]
    (if (= :skill (:kind classification))
      (str (theme/fg theme :custom-message-label (theme/bold "[skill] "))
           (theme/fg theme :custom-message-text (:label classification))
           range-str expand-hint)
      (str (theme/fg theme :tool-title (theme/bold (str "read " (name (:kind classification)))))
           " " (theme/fg theme :accent (:label classification))
           range-str expand-hint))))

;; ─── Edit diff preview (pi: computeEditsDiff + renderDiff) ─────────────────
;; Applies edits in memory and produces pi-format line-numbered diff lines:
;;   " 123 content" context, "-123 content" removed, "+123 content" added,
;;   " ..." skip markers. Single-line -/+ pairs get word-level intra-line
;;   inverse highlighting (pi: diffWords + renderIntraLineDiff).

(defn- compute-edit-preview
  "Try to apply edits in memory and return a pi-format diff.
   Args: path, edits — vector of {:old-text str :new-text str}
   Uses the same BOM/line-ending normalization and exact-then-fuzzy matching
   as the edit tool (kmet.app.tools.edit-diff) so preview and result are
   byte-comparable and error messages match.
   Returns {:success? bool :diff str :diff-lines [\"+123 content\" ...] :error str?}"
  [path edits]
  (try
    (let [f (io/file path)]
      (if-not (fs/exists? f)
        {:success? false :error (str "File not found: " path)}
        (let [content (slurp f)
              {:keys [text]} (edit-diff/strip-bom content)
              normalized (edit-diff/normalize-to-lf text)
              {:keys [base-content new-content]}
              (edit-diff/apply-edits-to-normalized-content normalized edits path)
              {:keys [diff]} (edit-diff/format-diff-lines
                              (str/split-lines base-content)
                              (str/split-lines new-content))]
          {:success? true
           :diff diff
           :diff-lines (vec (str/split-lines diff))})))
    (catch Exception e
      ;; Pi: preview and tool surface the same error so the render-result can
      ;; suppress an execution error already shown by the preview
      {:success? false
       :error (if (= :edit-error (:type (ex-data e)))
                (ex-message e)
                (str "Error editing " path ": " (ex-message e)))})))

(defn- edit-preview
  "Compute the edit preview, failing on missing/empty edits (pi: validateEditInput)."
  [path edits]
  (if (or (nil? edits) (empty? edits))
    {:success? false :error "Edit tool input is invalid. edits must contain at least one replacement."}
    (compute-edit-preview path edits)))

(defn- normalize-edit-args
  "Pi: prepareEditArguments — normalize edit tool args into a vector of
   {:old-text :new-text}. Handles: edits as a JSON string (array), camelCase
   oldText/newText keys, and the legacy top-level old-text/new-text pair
   (appended, matching the tool's normalize-edits)."
  [args]
  (let [parsed (cond
                 (string? (:edits args)) (try (let [p (json/parse-string (:edits args) true)]
                                                (when (sequential? p) p))
                                              (catch Exception _ nil))
                 (sequential? (:edits args)) (:edits args)
                 :else nil)
        kebab (mapv (fn [e] {:old-text (or (:old-text e) (:oldText e))
                             :new-text (or (:new-text e) (:newText e))})
                    parsed)
        legacy (when (and (or (string? (:old-text args)) (string? (:oldText args)))
                          (or (string? (:new-text args)) (string? (:newText args))))
                 {:old-text (or (:old-text args) (:oldText args))
                  :new-text (or (:new-text args) (:newText args))})]
    (cond-> (seq kebab)
      legacy (conj legacy))))
(defn- word-diff
  "Pi: renderIntraLineDiff — word-level diff of two strings.
   Common leading/trailing tokens stay plain; the changed middle gets
   inverse styling. The first changed part's leading whitespace stays
   unstyled. O(n) prefix/suffix trimming (pi uses diffWords LCS, which is
   quadratic and too slow for very long lines). Returns
   {:removed-line :added-line}."
  [old-s new-s]
  (let [old-tokens (vec (re-seq #"\s+|\S+" old-s))
        new-tokens (vec (re-seq #"\s+|\S+" new-s))
        n (count old-tokens) m (count new-tokens)
        p (loop [i 0]
            (if (and (< i n) (< i m) (= (nth old-tokens i) (nth new-tokens i)))
              (recur (inc i)) i))
        s (loop [k 0]
            (if (and (>= (- n 1 k) p) (>= (- m 1 k) p)
                     (= (nth old-tokens (- n 1 k)) (nth new-tokens (- m 1 k))))
              (recur (inc k)) k))
        old-mid (subvec old-tokens p (- n s))
        new-mid (subvec new-tokens p (- m s))]
    (if (and (empty? old-mid) (empty? new-mid))
      {:removed-line old-s :added-line new-s}
      (let [removed (StringBuilder.)
            added (StringBuilder.)
            old-ws (when (seq old-mid) (re-find #"^\s" (first old-mid)))
            new-ws (when (seq new-mid) (re-find #"^\s" (first new-mid)))
            emit (fn [sb tokens]
                   (doseq [t tokens] (.append sb (theme/inverse t))))]
        (doseq [t (subvec old-tokens 0 p)] (.append removed t))
        (doseq [t (subvec new-tokens 0 p)] (.append added t))
        (when old-ws (.append removed (first old-mid)))
        (when new-ws (.append added (first new-mid)))
        (emit removed (if old-ws (subvec old-mid 1) old-mid))
        (emit added (if new-ws (subvec new-mid 1) new-mid))
        (doseq [t (subvec old-tokens (- n s))] (.append removed t))
        (doseq [t (subvec new-tokens (- m s))] (.append added t))
        {:removed-line (str removed) :added-line (str added)}))))
(defn- style-change-pair
  "Style a consecutive -/+ run, applying word-level intra-line diff to
   single pairs (pi: renderDiff). Returns a vector of styled strings."
  [removed added tabs theme]
  (if (and (= 1 (count removed)) (= 1 (count added)))
    (let [{:keys [removed-line added-line]}
          (word-diff (tabs (:content (first removed)))
                     (tabs (:content (first added))))]
      [(theme/fg theme :tool-diff-removed
                 (str "-" (:line-num (first removed)) " " removed-line))
       (theme/fg theme :tool-diff-added
                 (str "+" (:line-num (first added)) " " added-line))])
    (into []
          (concat
           (mapv #(theme/fg theme :tool-diff-removed
                            (str "-" (:line-num %) " " (tabs (:content %)))) removed)
           (mapv #(theme/fg theme :tool-diff-added
                            (str "+" (:line-num %) " " (tabs (:content %)))) added)))))

(defn- render-diff-lines
  "Style pi-format diff lines; single -/+ pairs get intra-line inverse
   highlighting. Returns a vector of styled strings (pi: renderDiff)."
  [diff-lines theme]
  (let [n (count diff-lines)
        parse (fn [line]
                (when-let [m (re-find #"^([ +-])(\s*\d*)\s(.*)$" line)]
                  {:prefix (nth m 1) :line-num (nth m 2) :content (nth m 3)}))
        tabs (fn [s] (str/replace s "\t" "   "))]
    (loop [i 0 acc []]
      (if (>= i n)
        acc
        (let [p (parse (nth diff-lines i))]
          (if (and p (= "-" (:prefix p)))
            (let [removed (loop [j i acc []]
                            (if-let [q (and (< j n) (parse (nth diff-lines j)))]
                              (if (= "-" (:prefix q))
                                (recur (inc j) (conj acc q))
                                acc)
                              acc))
                  rn (count removed)
                  added (loop [j (+ i rn) acc []]
                          (if-let [q (and (< j n) (parse (nth diff-lines j)))]
                            (if (= "+" (:prefix q))
                              (recur (inc j) (conj acc q))
                              acc)
                            acc))
                  next-i (+ i rn (count added))
                  styled (style-change-pair removed added tabs theme)]
              (recur next-i (into acc styled)))
            (recur (inc i)
                   (conj acc
                         (theme/fg theme :tool-diff-context
                                   (if p
                                     (str " " (:line-num p) " " (tabs (:content p)))
                                     (nth diff-lines i)))))))))))

;; ─── Built-in tool renderers ──────────────────────────────────────────────
;; Each render-call takes (name args theme width context) → IComponent or nil.
;; Each render-result takes (content is-error theme width expanded? started-at ended-at truncation context) → IComponent or nil.
;; context is a ToolRenderContext map (see tool-execution-context). Built-in renderers ignore it.

(defn- renderable-edit-input
  "Pi: getRenderablePreviewInput — path must be a non-empty string and edits
   a non-empty vector of {:old-text str :new-text str}."
  [raw-path edits]
  (and (string? raw-path)
       (pos? (count raw-path))
       (seq edits)
       (every? (fn [e] (and (string? (:old-text e)) (string? (:new-text e)))) edits)))

(defn- build-edit-box
  "Pi: buildEditCallComponent — Box whose bg reflects the preview state
   (pending/success/error) with the title and diff/error body."
  [preview raw-path theme cwd]
  (let [box (box/make-box 1 1
                          (cond
                            (nil? preview)       #(theme/bg theme :tool-pending-bg %)
                            (:success? preview)  #(theme/bg theme :tool-success-bg %)
                            :else                #(theme/bg theme :tool-error-bg %)))]
    (box/box-add-child box
                       (text/make-text
                        (str (theme/fg theme :tool-title (theme/bold "edit "))
                             (render-tool-path raw-path theme cwd))
                        0 0))
    (when preview
      (box/box-add-child box (spacer/make-spacer 1))
      (box/box-add-child box
                         (text/make-text
                          (if (:success? preview)
                            (str/join "\n" (render-diff-lines (:diff-lines preview) theme))
                            (theme/fg theme :error (:error preview)))
                          0 0)))
    box))

(def ^:private builtin-renderers
  {"read"  {:render-call (fn [_name args theme _width context]
                           (let [raw-path (:file_path args (:path args))
                                 range-str (read-line-range args theme)
                                 classification (when-not (:expanded context)
                                                  (compact-read-classification raw-path (:cwd context)))]
                             (if classification
                               (text/make-text
                                (format-compact-read-call classification args theme range-str)
                                0 0)
                               (text/make-text
                                (str (theme/fg theme :tool-title (theme/bold "read "))
                                     (render-tool-path raw-path theme (:cwd context))
                                     range-str)
                                0 0))))
            :render-result (fn [content is-error theme _width expanded? _started-at _ended-at truncation _context]
                             (if (and (not expanded?) (not is-error))
                               nil
                               (let [c (container/make-container)
                                     lines (trim-trailing-empty-lines
                                            (str/split-lines (sanitize-display-text (or content ""))))
                                     n (count lines)
                                     max-lines (if expanded? n 10)
                                     show (take max-lines lines)
                                     more (- n max-lines)]
                                 ;; Pi: result always starts with a blank line
                                 (container/container-add-child c (spacer/make-spacer 1))
                                 (when (seq lines)
                                   (doseq [line show]
                                     (container/container-add-child c
                                                                    (text/make-text
                                                                     (theme/fg theme :tool-output (replace-tabs line))
                                                                     0 0)))
                                   (when (pos? more)
                                     (container/container-add-child c
                                                                    (text/make-text
                                                                     (str (theme/fg theme :muted (str "... (" more " more lines,"))
                                                                          " "
                                                                          (app-kb/key-hint "app.tools.expand" "to expand")
                                                                          (theme/fg theme :muted ")"))
                                                                     0 0)))
                                   ;; Pi: truncation warnings (first line / lines / bytes)
                                   (when truncation
                                     (let [{:keys [first-line-exceeds-limit truncated-by output-lines total-lines max-lines max-bytes]} truncation
                                           warn (cond
                                                  first-line-exceeds-limit
                                                  (str "[First line exceeds " (bash-exec/format-size (or max-bytes bash-exec/DEFAULT-MAX-BYTES)) " limit]")
                                                  (= truncated-by :lines)
                                                  (str "[Truncated: showing " output-lines " of " total-lines " lines ("
                                                       (or max-lines bash-exec/DEFAULT-MAX-LINES) " line limit)]")
                                                  (= truncated-by :bytes)
                                                  (str "[Truncated: " output-lines " lines shown ("
                                                       (bash-exec/format-size (or max-bytes bash-exec/DEFAULT-MAX-BYTES)) " limit)]")
                                                  :else nil)]
                                       (when warn
                                         (container/container-add-child c (spacer/make-spacer 1))
                                         (container/container-add-child c
                                                                        (text/make-text (theme/fg theme :warning warn) 0 0))))))
                                 c)))}
   "write" {:render-call (fn [_name args theme _width context]
                           (let [raw-path (:file_path args (:path args))
                                 content (:content args)
                                 c (container/make-container)]
                             (container/container-add-child c
                                                            (text/make-text
                                                             (str (theme/fg theme :tool-title (theme/bold "write "))
                                                                  (render-tool-path raw-path theme (:cwd context)))
                                                             0 0))
                             (if (nil? (tool-path-str content))
                               ;; Pi: invalid content arg
                               (container/container-add-child c
                                                              (text/make-text
                                                               (str "\n\n" (theme/fg theme :error "[invalid content arg - expected string]"))
                                                               0 0))
                               (when (seq content)
                                 (let [lines (trim-trailing-empty-lines
                                              (str/split-lines (normalize-display-text content)))
                                       total (count lines)
                                       max-lines (if (:expanded context) total 10)
                                       show (take max-lines lines)
                                       remaining (- total max-lines)]
                                   (container/container-add-child c (spacer/make-spacer 1))
                                   (container/container-add-child c (spacer/make-spacer 1))
                                   (doseq [line show]
                                     (container/container-add-child c
                                                                    (text/make-text (theme/fg theme :tool-output (replace-tabs line)) 0 0)))
                                   (when (pos? remaining)
                                     (container/container-add-child c
                                                                    (text/make-text
                                                                     (str (theme/fg theme :muted
                                                                                    (str "... (" remaining " more lines, " total " total,"))
                                                                          " "
                                                                          (app-kb/key-hint "app.tools.expand" "to expand")
                                                                          (theme/fg theme :muted ")"))
                                                                     0 0))))))
                             c))
            :render-result (fn [content is-error theme _width _expanded? & _]
                             (when is-error
                               (text/make-text (str "\n" (theme/fg theme :error content)) 0 0)))}
   "edit"  {:render-shell :self
            :render-call (fn [_name args theme _width context]
                           (let [raw-path (:file_path args (:path args))
                                 edits (normalize-edit-args args)
                                 args-key (str raw-path "|" (pr-str edits))
                                 state (:state context)
                                 set-state! (:set-state! context)
                                 args-changed? (not= args-key (:edit-args-key state))
                                 state (if args-changed?
                                         ;; Pi: new args invalidate the cached preview
                                         ;; (incl. one set by a previous result)
                                         (let [s' (-> state
                                                      (assoc :edit-args-key args-key)
                                                      (dissoc :edit-preview))]
                                           (when set-state! (set-state! s'))
                                           s')
                                         state)
                                 preview (cond
                                           ;; Pi: keep a result-provided preview as-is
                                           (contains? state :edit-preview) (:edit-preview state)
                                           ;; Pi: compute only when args complete and renderable
                                           (and (:args-complete context)
                                                (renderable-edit-input raw-path edits))
                                           (let [p (edit-preview raw-path edits)]
                                             (when set-state!
                                               (set-state! (assoc state :edit-preview p)))
                                             p)
                                           :else nil)]
                             ;; Pi: edit call is a Box whose bg reflects preview state;
                             ;; the preview is cached per args so re-renders after a
                             ;; successful edit keep showing the original diff.
                             (build-edit-box preview raw-path theme (:cwd context))))
            :render-result (fn [content is-error theme _width _expanded? _started-at _ended-at _truncation context]
                             (let [state (:state context)
                                   set-state! (:set-state! context)]
                               (if is-error
                                 ;; Pi: suppress execution errors already shown by the preview
                                 (let [preview-error (:error (:edit-preview state))]
                                   (if (= content preview-error)
                                     nil
                                     (let [c (container/make-container)]
                                       (container/container-add-child c (spacer/make-spacer 1))
                                       (container/container-add-child c
                                                                      (text/make-text (theme/fg theme :error content) 1 0))
                                       c)))
                                 ;; Pi: when the actually-applied diff differs from the
                                 ;; previewed one (file changed between preview and apply,
                                 ;; or no preview was computed), correct the cached preview
                                 ;; so the call box shows the real diff.
                                 (let [result-diff (get-in context [:details :diff])
                                       preview (:edit-preview state)
                                       preview-diff (when (and preview (:success? preview))
                                                      (:diff preview))]
                                   (when (and result-diff (not= result-diff preview-diff))
                                     (when set-state!
                                       (set-state! (assoc state :edit-preview
                                                          {:success? true
                                                           :diff result-diff
                                                           :diff-lines (vec (str/split-lines result-diff))})))
                                     (when-let [invalidate (:invalidate context)]
                                       (invalidate)))
                                   nil))))}
   "bash"  {:render-call (fn [_name args theme _width _context]
                           (let [cmd (:command args)
                                 timeout (:timeout args)
                                 cmd-str (if (string? cmd)
                                           cmd
                                           (if (nil? cmd) "" nil))
                                 cmd-display (cond
                                               (nil? cmd-str)
                                               (theme/fg theme :error "[invalid arg]")
                                               (empty? cmd-str)
                                               (theme/fg theme :tool-output "...")
                                               :else cmd-str)
                                 timeout-suffix (if (and (number? timeout) (pos? timeout))
                                                  (theme/fg theme :muted (str " (timeout " timeout "s)"))
                                                  "")]
                             (text/make-text
                              (str (theme/fg theme :tool-title (theme/bold (str "$ " cmd-display)))
                                   timeout-suffix)
                              0 0)))
            :render-result (fn [content _is-error theme width expanded? started-at ended-at truncation _context]
                             (let [c (container/make-container)
                                   BASH-PREVIEW-LINES 5
                                   full-output-path (:full-output-path truncation)
                                   output (let [trimmed (str/trim (or content ""))]
                                            (if (and truncation
                                                     full-output-path
                                                     (some? ended-at)
                                                     (str/ends-with? trimmed "]"))
                                              (let [footer-start (str/last-index-of trimmed "\n\n[")]
                                                (if (and footer-start
                                                         (str/includes? (subs trimmed footer-start)
                                                                        full-output-path))
                                                  (str/trimr (subs trimmed 0 footer-start))
                                                  trimmed))
                                              trimmed))]
                               (when (seq output)
                                 (let [styled (->> (str/split-lines output)
                                                   (mapv #(theme/fg theme :tool-output %))
                                                   (str/join "\n"))]
                                   (if expanded?
                                     (do
                                       (container/container-add-child c (spacer/make-spacer 1))
                                       (doseq [line (str/split-lines styled)]
                                         (container/container-add-child c (text/make-text line 0 0))))
                                     (let [{:keys [visual-lines skipped-count]}
                                           (utils/truncate-to-visual-lines styled BASH-PREVIEW-LINES width)]
                                       (container/container-add-child c (spacer/make-spacer 1))
                                       (when (pos? skipped-count)
                                         (container/container-add-child c
                                                                        (text/make-text
                                                                         (utils/truncate-to-width
                                                                          (str (theme/fg theme :muted
                                                                                         (str "... (" skipped-count " earlier lines,"))
                                                                               " "
                                                                               (app-kb/key-hint "app.tools.expand" "to expand")
                                                                               (theme/fg theme :muted ")"))
                                                                          width "...")
                                                                         0 0)))
                                       (doseq [line visual-lines]
                                         (container/container-add-child c (text/make-text line 0 0)))))))
                               (when truncation
                                 (let [{:keys [total-lines shown-lines truncated-by max-bytes]} truncation
                                       size-str (when (= truncated-by :bytes)
                                                  (bash-exec/format-size
                                                   (or max-bytes bash-exec/DEFAULT-MAX-BYTES)))
                                       truncated-part (if (= truncated-by :bytes)
                                                        (str "Truncated: " shown-lines " lines shown (" size-str " limit)")
                                                        (str "Truncated: showing " shown-lines " of " total-lines " lines"))
                                       warn (str "[" (str/join ". "
                                                               (cond-> []
                                                                 full-output-path (conj (str "Full output: " full-output-path))
                                                                 :always (conj truncated-part)))
                                                 "]")]
                                   (container/container-add-child c (spacer/make-spacer 1))
                                   (container/container-add-child c
                                                                  (text/make-text (theme/fg theme :warning warn) 0 0))))
                               (when started-at
                                 (let [now (or ended-at (System/currentTimeMillis))
                                       elapsed-ms (- now started-at)
                                       label (if ended-at "Took" "Elapsed")]
                                   (container/container-add-child c (spacer/make-spacer 1))
                                   (container/container-add-child c
                                                                  (text/make-text
                                                                   (theme/fg theme :muted (str label " " (format "%.1f" (float (/ elapsed-ms 1000))) "s"))
                                                                   0 0))))
                               c))}})

;; ─── Default renderers (fallback when no custom or built-in) ──────────────

(defn- default-render-call
  "Default render-call: show tool name bolded in tool-title color."
  [name _args theme _width & [_context]]
  (text/make-text (theme/fg theme :tool-title (theme/bold name)) 0 0))

(defn- default-render-result
  "Default render-result: show raw content in tool-output color.
   Accepts extra args for compatibility."
  [content _is-error theme _width _expanded? & _]
  (text/make-text (theme/fg theme :tool-output content) 0 0))

;; ─── Render context helper ─────────────────────────────────────────────────

(defn- tool-execution-context
  "Build a ToolRenderContext map for the given component and last-component."
  [comp last-comp]
  {:args @(:args-atom comp)
   :tool-call-id @(:tool-call-id-atom comp)
   :invalidate (fn []
                 (protocols/invalidate comp)
                 (when-let [cb @(:request-render-fn-atom comp)]
                   (cb)))
   :last-component last-comp
   :state @(:renderer-state-atom comp)
   :set-state! (fn [new-state]
                 (reset! (:renderer-state-atom comp) new-state))
   :cwd @(:cwd-atom comp)
   :execution-started (some? @(:started-at-atom comp))
   :args-complete @(:args-complete-atom comp)
   :details @(:details-atom comp)
   :is-partial (nil? @(:ended-at-atom comp))
   :expanded @(:expanded-atom comp)
   :show-images true
   :is-error @(:is-error-atom comp)})

;; ─── Record ────────────────────────────────────────────────────────────────
;; Pi matching: ToolExecutionComponent manages its own timing.
;; started-at is set on first set-content! call (execution start).
;; ended-at is set on set-error! or on final full-content set-content!.

(defcomponent ToolExecutionComponent :tool
              [name-atom args-atom content-atom is-error-atom
               theme-atom output-pad-atom expanded-atom
               custom-render-call-atom custom-render-result-atom
               started-at-atom ended-at-atom timer-active-atom
               truncation-atom tool-call-id-atom
               details-atom        ;; result :details map (pi: result.details), e.g. edit diff
               args-complete-atom
               image-data-atom       ;; vector of {:data str :mime-type str}
               last-call-component-atom   ;; component from previous render-call
               last-result-component-atom ;; component from previous render-result
               renderer-state-atom        ;; persistent state for custom renderers
               request-render-fn-atom  ;; nil or (fn) to trigger TUI re-render
               cwd-atom                ;; current working directory
               box             ;; outer Box (padding + bg)
               inner-container] ;; Container for call/result children
  (render [this width]
    (let [theme @theme-atom
          is-error @is-error-atom
          output-pad @output-pad-atom
          name @name-atom
          args @args-atom
          content @content-atom
          expanded? @expanded-atom
          started-at @started-at-atom
          ended-at @ended-at-atom
      ;; Re-check empty — only when no call component rendered and no result
          builtin (get builtin-renderers name)
          render-call-fn (or @custom-render-call-atom
                             (:render-call builtin)
                             default-render-call)
          render-result-fn (or @custom-render-result-atom
                               (:render-result builtin)
                               default-render-result)
          render-shell (or (:render-shell builtin) :default)
          container @inner-container
          content-width (max 1 (- width (* 2 output-pad)))
          call-context (tool-execution-context this @last-call-component-atom)
          call-comp (render-call-fn name args theme content-width call-context)
          _ (reset! last-call-component-atom call-comp)
          truncation @truncation-atom
          result-context (tool-execution-context this @last-result-component-atom)
          result-comp (render-result-fn content is-error theme content-width expanded? started-at ended-at truncation result-context)
          _ (reset! last-result-component-atom result-comp)
          image-data @image-data-atom]
      ;; Pi: hide component when no call/render content and no images
      (if (and (nil? call-comp) (nil? result-comp) (not (seq image-data)))
        []
        (do
          ;; Schedule periodic re-render while tool is running (Pi: setInterval equivalent)
          (when (and started-at (not ended-at) (compare-and-set! timer-active-atom false true))
            (future
              (Thread/sleep 1000)
              (reset! timer-active-atom false)
              (protocols/invalidate this)))
          ;; Build inner container
          (container/container-clear container)
          (container/container-add-child container call-comp)
          (when result-comp
            (container/container-add-child container result-comp))
          ;; Build image components from raw data (Pi: spacer + ImageComponent)
          (doseq [img image-data]
            (container/container-add-child container (spacer/make-spacer 1))
            (container/container-add-child container
                                           (ic/make-image (:data img) (:mime-type img)
                                                          {:fallback-color (fn [s] (theme/fg theme :tool-output s))}
                                                          :max-width-cells 60)))
          ;; Pi: render-shell :self skips outer Box (tool renders its own framing)
          (if (= :self render-shell)
            (let [content-lines (protocols/render container width)]
              (if (seq content-lines)
                (into [""] content-lines)
                []))
            (let [bg-key (cond
                           ;; Pi: isPartial=true until result arrives; ended-at=nil = pending
                           (nil? ended-at) :tool-pending-bg
                           is-error :tool-error-bg
                           :else :tool-success-bg)
                  _ (box/box-set-bg-fn @box #(theme/bg theme bg-key %))
                  box-lines (protocols/render @box width)]
              (if (seq box-lines)
                (into [""] box-lines)
                [])))))))
  (invalidate [_this]
    (protocols/invalidate @box)
    ;; Pi: invalidate also triggers TUI re-render
    (when-let [cb @request-render-fn-atom]
      (cb))))

;; ─── Construction ──────────────────────────────────────────────────────────
;; Pi: component manages timing internally — no started-at/ended-at passed in.

(defn make-tool-execution
  [& {:keys [name args content is-error theme output-pad expanded? render-call-fn render-result-fn truncation details cwd]
      :or {name "" args {} content "" is-error false theme theme/dark-theme
           output-pad 1 expanded? false truncation nil details nil
           cwd (or (System/getProperty "user.dir") ".")}}]
  (let [inner-container (container/make-container)
        bg-key (if is-error :tool-error-bg :tool-success-bg)
        b (box/make-box output-pad 1 #(theme/bg theme bg-key %))]
    (box/box-add-child b inner-container)
    (map->ToolExecutionComponent {:name-atom (atom name)
                                  :args-atom (atom args)
                                  :content-atom (atom content)
                                  :is-error-atom (atom is-error)
                                  :theme-atom (atom theme)
                                  :output-pad-atom (atom output-pad)
                                  :expanded-atom (atom expanded?)
                                  :started-at-atom (atom nil)
                                  :ended-at-atom (atom nil)
                                  :timer-active-atom (atom false)
                                  :truncation-atom (atom truncation)
                                  :tool-call-id-atom (atom nil)
                                  :details-atom (atom details)
                                  :args-complete-atom (atom false)
                                  :custom-render-call-atom (atom render-call-fn)
                                  :custom-render-result-atom (atom render-result-fn)
                                  :image-data-atom (atom [])
                                  :last-call-component-atom (atom nil)
                                  :last-result-component-atom (atom nil)
                                  :renderer-state-atom (atom {})
                                  :request-render-fn-atom (atom nil)
                                  :cwd-atom (atom cwd)
                                  :box (atom b)
                                  :inner-container (atom inner-container)})))

;; ─── Public API ────────────────────────────────────────────────────────────
;; Pi: set-content! and set-error! manage timing internally.

(defsetter tool-execution-set-name! :name-atom comp name
  (protocols/invalidate comp))

(defsetter tool-execution-set-content! :content-atom comp content
  ;; Pi: first content delivery marks execution started
  (when (nil? @(:started-at-atom comp))
    (reset! (:started-at-atom comp) (System/currentTimeMillis)))
  (protocols/invalidate comp))

(defsetter tool-execution-set-error! :is-error-atom comp is-error
  ;; Pi: error marks execution ended
  (when (nil? @(:ended-at-atom comp))
    (reset! (:ended-at-atom comp) (System/currentTimeMillis)))
  (protocols/invalidate comp))

(defsetter tool-execution-set-expanded! :expanded-atom comp expanded?
  (protocols/invalidate comp))
(defsetter tool-execution-set-theme! :theme-atom comp theme
  (protocols/invalidate comp))
(defsetter tool-execution-set-output-pad! :output-pad-atom comp n
  ;; Rebuild the box with the new horizontal padding (render sets the bg-fn)
  (let [b (box/make-box n 1 nil)
        inner @(:inner-container comp)]
    (box/box-add-child b inner)
    (reset! (:box comp) b))
  (protocols/invalidate comp))

(defsetter tool-execution-set-truncation! :truncation-atom comp truncation
  (protocols/invalidate comp))

(defsetter tool-execution-set-details! :details-atom comp details
  (protocols/invalidate comp))

(defsetter tool-execution-set-tool-call-id! :tool-call-id-atom comp id)

(defn tool-execution-mark-execution-started!
  "Mark that tool execution has started (Pi: markExecutionStarted()).
   Sets started-at timestamp so pending background and timer activate
   from tool start rather than waiting for first content delivery."
  [comp]
  (when (nil? @(:started-at-atom comp))
    (reset! (:started-at-atom comp) (System/currentTimeMillis)))
  (protocols/invalidate comp))

(defgetter tool-execution-get-tool-call-id :tool-call-id-atom comp)

(defn tool-execution-set-args-complete!
  "Mark that all tool arguments have been received.
   Pi: setArgsComplete() — affects render context :args-complete."
  [comp]
  (reset! (:args-complete-atom comp) true))

(defn tool-execution-set-images!
  "Set image content blocks for this tool execution.
   images — vector of {:data str :mime-type str}
   Stores raw image data; ImageComponents are built at render time."
  [comp images]
  (let [image-data (mapv (fn [img] {:data (:data img) :mime-type (:mime-type img)}) images)]
    (reset! (:image-data-atom comp) image-data)
    (protocols/invalidate comp)))

(defsetter tool-execution-set-request-render-fn! :request-render-fn-atom comp f)
(defsetter tool-execution-set-render-call-fn! :custom-render-call-atom comp f
  (protocols/invalidate comp))
(defsetter tool-execution-set-render-result-fn! :custom-render-result-atom comp f
  (protocols/invalidate comp))
