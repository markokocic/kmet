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
            [kmet.tui.components.spacer :as spacer]
            [kmet.tui.components.image :as ic]
            [kmet.tui.terminal-image :as timg]
            [kmet.tui.macros :refer [defsetter defgetter defcomponent]]
            [cheshire.core :as json]))

;; ─── Shared render helpers (pi: render-utils.ts) ────────────────────────────

(defn- tool-path-str
  "Pi: str() — string passes through, missing → \"\", non-string → null."
  [raw-path]
  (if (string? raw-path) raw-path (if (nil? raw-path) "" nil)))

(defn- render-tool-path
  "Pi: renderToolPath — accent path; '...' toolOutput when empty;
   '[invalid arg]' error when the arg is not a string."
  [raw-path theme]
  (let [s (tool-path-str raw-path)]
    (if (nil? s)
      (theme/fg theme :error "[invalid arg]")
      (if (empty? s)
        (theme/fg theme :tool-output "...")
        (theme/fg theme :accent s)))))

(defn- trim-trailing-empty-lines
  "Pi: trimTrailingEmptyLines — drop empty lines at the end of a vector."
  [lines]
  (let [n (count lines)]
    (loop [end n]
      (if (and (pos? end) (= "" (nth lines (dec end))))
        (recur (dec end))
        (subvec lines 0 end)))))

;; ─── Edit diff preview (pi: computeEditsDiff + renderDiff) ─────────────────
;; Applies edits in memory and produces pi-format line-numbered diff lines:
;;   " 123 content" context, "-123 content" removed, "+123 content" added,
;;   " ..." skip markers. Single-line -/+ pairs get word-level intra-line
;;   inverse highlighting (pi: diffWords + renderIntraLineDiff).

(def diff-context-lines 4)  ;; pi: contextLines = 4

(defn- diff-region
  "Trim the common prefix/suffix from old/new line vectors.
   Returns {:start int :old-changed [...] :new-changed [...]} (0-indexed start)."
  [old-lines new-lines]
  (let [n (count old-lines) m (count new-lines)
        p (loop [i 0]
            (if (and (< i n) (< i m) (= (nth old-lines i) (nth new-lines i)))
              (recur (inc i)) i))
        s (loop [k 0]
            (if (and (>= (- n 1 k) p) (>= (- m 1 k) p)
                     (= (nth old-lines (- n 1 k)) (nth new-lines (- m 1 k))))
              (recur (inc k)) k))]
    {:start p
     :old-changed (subvec old-lines p (- n s))
     :new-changed (subvec new-lines p (- m s))}))

(defn- format-diff-lines
  "Generate pi-style numbered diff lines from full old/new line vectors.
   Returns {:diff-lines [str] :num-additions int :num-removals int}."
  [old-lines new-lines]
  (let [n (count old-lines) m (count new-lines)
        {:keys [start old-changed new-changed]} (diff-region old-lines new-lines)
        old-n (count old-changed) new-n (count new-changed)
        width (count (str (max n m)))
        pad (fn [num] (let [s (str num)] (str (apply str (repeat (- width (count s)) \space)) s)))
        skip-marker (str " " (apply str (repeat width \space)) " ...")
        out (atom [])
        ctx-start (max 0 (- start diff-context-lines))
        before-lines (subvec old-lines ctx-start start)]
    ;; Pi: skip marker only when leading context was clipped
    (when (pos? ctx-start)
      (swap! out conj skip-marker))
    (doseq [[i line] (map-indexed vector before-lines)]
      (swap! out conj (str " " (pad (+ ctx-start i 1)) " " line)))
    (doseq [[i line] (map-indexed vector old-changed)]
      (swap! out conj (str "-" (pad (+ start i 1)) " " line)))
    (doseq [[i line] (map-indexed vector new-changed)]
      (swap! out conj (str "+" (pad (+ start i 1)) " " line)))
    (let [after-start (+ start old-n)
          after-end (min n (+ after-start diff-context-lines))]
      (doseq [[i line] (map-indexed vector (subvec old-lines after-start after-end))]
        (swap! out conj (str " " (pad (+ after-start i 1)) " " line)))
      (when (< after-end n)
        (swap! out conj skip-marker)))
    {:diff-lines @out
     :num-additions new-n
     :num-removals old-n}))

(defn- compute-edit-preview
  "Try to apply edits in memory and return a pi-format diff.
   Args: path, edits — vector of {:old-text str :new-text str}
   Returns {:success? bool :diff-lines [\"+123 content\" ...]
            :num-additions int :num-removals int :error str?}"
  [path edits]
  (try
    (let [f (io/file path)]
      (if-not (fs/exists? f)
        {:success? false :error (str "File not found: " path)}
        (let [content (slurp f)
              applied (loop [current content
                             remaining edits]
                        (if (empty? remaining)
                          {:ok? true :content current}
                          (let [{:keys [old-text new-text]} (first remaining)]
                            (if (str/includes? current old-text)
                              (recur (str/replace-first current old-text new-text) (rest remaining))
                              {:ok? false :error (str "Could not find old-text in " path)}))))]
          (if-not (:ok? applied)
            {:success? false :error (:error applied)}
            (let [{:keys [diff-lines num-additions num-removals]}
                  (format-diff-lines (str/split-lines content)
                                     (str/split-lines (:content applied)))]
              {:success? true
               :diff-lines diff-lines
               :num-additions num-additions
               :num-removals num-removals})))))
    (catch Exception e
      {:success? false :error (str "Error previewing edit: " (ex-message e))})))

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

(def ^:private builtin-renderers
  {"read"  {:render-call (fn [name args theme width _context]
                           (let [raw-path (:file-path args (:path args))
                                 offset (:offset args)
                                 limit (:limit args)
                                 range-str (when (or offset limit)
                                             (let [start-line (or offset 1)
                                                   end-line (when limit (+ start-line limit -1))]
                                               (theme/fg theme :warning
                                                         (str ":" start-line (when end-line (str "-" end-line))))))]
                             (text/make-text
                               (str (theme/fg theme :tool-title (theme/bold "read "))
                                    (render-tool-path raw-path theme)
                                    range-str)
                               0 0)))
            :render-result (fn [content is-error theme width expanded? _started-at _ended-at truncation _context]
                             (if (and (not expanded?) (not is-error))
                               nil
                               (let [c (container/make-container)
                                     lines (trim-trailing-empty-lines (str/split-lines content))
                                     n (count lines)
                                     max-lines (if expanded? n 10)
                                     show (take max-lines lines)
                                     more (- n max-lines)]
                                 (when (seq lines)
                                   (container/container-add-child c (text/make-text "" 0 0))
                                   (doseq [line show]
                                     (container/container-add-child c
                                       (text/make-text
                                         (if is-error
                                           (theme/fg theme :error line)
                                           (theme/fg theme :tool-output line))
                                         0 0)))
                                   (when (pos? more)
                                     (container/container-add-child c
                                       (text/make-text
                                         (utils/truncate-to-width
                                           (str (theme/fg theme :muted (str "... (" more " more lines,"))
                                                " "
                                                (app-kb/key-hint "app.tools.expand" "to expand")
                                                (theme/fg theme :muted ")"))
                                           width "...")
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
                                         (container/container-add-child c (text/make-text "" 0 0))
                                         (container/container-add-child c
                                           (text/make-text (theme/fg theme :warning warn) 0 0))))))
                                 c)))}
   "write" {:render-call (fn [name args theme width _context]
                           (let [raw-path (:file-path args (:path args))
                                 content (:content args)
                                 c (container/make-container)]
                             (container/container-add-child c
                               (text/make-text
                                 (str (theme/fg theme :tool-title (theme/bold "write "))
                                      (render-tool-path raw-path theme))
                                 0 0))
                             (if (nil? (tool-path-str content))
                               ;; Pi: invalid content arg
                               (container/container-add-child c
                                 (text/make-text
                                   (str "\n\n" (theme/fg theme :error "[invalid content arg - expected string]"))
                                   0 0))
                               (when (seq content)
                                 (let [lines (trim-trailing-empty-lines (str/split-lines content))
                                       total (count lines)
                                       show (take 10 lines)
                                       remaining (- total 10)]
                                   (container/container-add-child c (text/make-text "" 0 0))
                                   (container/container-add-child c (text/make-text "" 0 0))
                                   (doseq [line show]
                                     (container/container-add-child c
                                       (text/make-text (theme/fg theme :tool-output line) 0 0)))
                                   (when (pos? remaining)
                                     (container/container-add-child c
                                       (text/make-text
                                         (utils/truncate-to-width
                                           (str (theme/fg theme :muted
                                                (str "... (" remaining " more lines, " total " total,"))
                                                " "
                                                (app-kb/key-hint "app.tools.expand" "to expand")
                                                (theme/fg theme :muted ")"))
                                           width "...")
                                         0 0))))))
                             c))
            :render-result (fn [content is-error theme width expanded? & _]
                             (when is-error
                               (text/make-text (str "\n" (theme/fg theme :error content)) 0 0)))}
   "edit"  {:render-shell :self
            :render-call (fn [name args theme width context]
                           (let [raw-path (:file-path args (:path args))
                                 edits (normalize-edit-args args)
                                 preview (edit-preview raw-path edits)
                                 bg-fn (if (:success? preview)
                                         #(theme/bg theme :tool-success-bg %)
                                         #(theme/bg theme :tool-error-bg %))
                                 box (box/make-box 1 1 bg-fn)]
                             ;; Pi: edit call is a Box whose bg reflects preview state
                             (box/box-add-child box
                               (text/make-text
                                 (str (theme/fg theme :tool-title (theme/bold "edit "))
                                      (render-tool-path raw-path theme))
                                 0 0))
                             (if (:success? preview)
                               (let [{:keys [diff-lines]} preview]
                                 (box/box-add-child box (spacer/make-spacer 1))
                                 (doseq [line (render-diff-lines diff-lines theme)]
                                   (box/box-add-child box (text/make-text line 0 0))))
                               (box/box-add-child box
                                 (text/make-text (theme/fg theme :error (:error preview)) 0 0)))
                             box))
            :render-result (fn [content is-error theme width expanded? _started-at _ended-at _truncation context]
                             (if is-error
                               ;; Pi: suppress execution errors already shown by the preview
                               (let [raw-path (:file-path (:args context) (:path (:args context)))
                                     edits (normalize-edit-args (:args context))
                                     preview-error (:error (edit-preview raw-path edits))]
                                 (if (= content preview-error)
                                   nil
                                   (text/make-text (theme/fg theme :error content) 0 0)))
                               nil))}
   "bash"  {:render-call (fn [name args theme width _context]
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
            :render-result (fn [content is-error theme width expanded? started-at ended-at truncation _context]
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
                                       (container/container-add-child c (text/make-text "" 0 0))
                                       (doseq [line (str/split-lines styled)]
                                         (container/container-add-child c (text/make-text line 0 0))))
                                     (let [{:keys [visual-lines skipped-count]}
                                           (utils/truncate-to-visual-lines styled BASH-PREVIEW-LINES width)]
                                       (container/container-add-child c (text/make-text "" 0 0))
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
                                   (container/container-add-child c (text/make-text "" 0 0))
                                   (container/container-add-child c
                                     (text/make-text (theme/fg theme :warning warn) 0 0))))
                               (when started-at
                                 (let [now (or ended-at (System/currentTimeMillis))
                                       elapsed-ms (- now started-at)
                                       label (if ended-at "Took" "Elapsed")]
                                   (container/container-add-child c (text/make-text "" 0 0))
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
   :cwd @(:cwd-atom comp)
   :execution-started (some? @(:started-at-atom comp))
   :args-complete @(:args-complete-atom comp)
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
   args-complete-atom
   image-data-atom       ;; vector of {:data str :mime-type str}
   converted-images-atom ;; atom of {idx -> {:base64 str :mime-type "image/png" :width-px int :height-px int}}
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
          ended-at @ended-at-atom]
      ;; Re-check empty — only when no call component rendered and no result
      (let [builtin (get builtin-renderers name)
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
              image-data @image-data-atom
              converted @converted-images-atom]
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
          ;; Build image components from raw data + conversions (Pi: spacer + ImageComponent)
          (doseq [[i img] (map-indexed vector image-data)]
            (let [converted (get converted i)
                  img-data (if converted (:base64 converted) (:data img))
                  img-mime (if converted (:mime-type converted) (:mime-type img))]
              (container/container-add-child container (spacer/make-spacer 1))
              (container/container-add-child container
                (ic/make-image img-data img-mime
                  {:fallback-color (fn [s] (theme/fg theme :tool-output s))}
                  :max-width-cells 60))))
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
                []))))))))
  (invalidate [this]
    (protocols/invalidate @box)
    ;; Pi: invalidate also triggers TUI re-render
    (when-let [cb @request-render-fn-atom]
      (cb))))

;; ─── Construction ──────────────────────────────────────────────────────────
;; Pi: component manages timing internally — no started-at/ended-at passed in.

(defn make-tool-execution
  [& {:keys [name args content is-error theme output-pad expanded? render-call-fn render-result-fn truncation cwd]
      :or {name "" args {} content "" is-error false theme theme/dark-theme
           output-pad 1 expanded? false truncation nil
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
                         :args-complete-atom (atom false)
                         :custom-render-call-atom (atom render-call-fn)
                         :custom-render-result-atom (atom render-result-fn)
                         :image-data-atom (atom [])
                         :converted-images-atom (atom {})
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
  (protocols/invalidate comp))

(defsetter tool-execution-set-truncation! :truncation-atom comp truncation
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
   Stores raw image data; ImageComponents are built at render time.
   For non-PNG images in kitty-capable terminals, triggers async conversion to PNG."
  [comp images]
  (let [image-data (mapv (fn [img] {:data (:data img) :mime-type (:mime-type img)}) images)]
    (reset! (:image-data-atom comp) image-data)
    (reset! (:converted-images-atom comp) {})
    ;; Async conversion for non-PNG images in kitty terminals
    (let [caps (timg/get-capabilities)]
      (when (= :kitty (:images caps))
        (doseq [[i img] (map-indexed vector images)]
          (when (and (:data img) (:mime-type img) (not= (:mime-type img) "image/png"))
            (future
              (when-let [converted (timg/convert-to-png (:data img) (:mime-type img))]
                (let [converted' @(:converted-images-atom comp)]
                  (reset! (:converted-images-atom comp)
                    (assoc converted' i
                      {:base64 (:base64 converted)
                       :mime-type "image/png"
                       :width-px (:width-px converted)
                       :height-px (:height-px converted)})))
                (protocols/invalidate comp)))))))
    (protocols/invalidate comp)))

(defsetter tool-execution-set-request-render-fn! :request-render-fn-atom comp f)
(defsetter tool-execution-set-render-call-fn! :custom-render-call-atom comp f
  (protocols/invalidate comp))
(defsetter tool-execution-set-render-result-fn! :custom-render-result-atom comp f
  (protocols/invalidate comp))
