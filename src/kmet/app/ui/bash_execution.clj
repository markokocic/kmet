(ns kmet.app.ui.bash-execution
  "BashExecutionComponent — TUI component for displaying !/!! bash command execution
   with streaming output, expand/collapse, animated loader, duration, and status info.
   Port of pi's BashExecutionComponent."
  (:require [kmet.tui.protocols :as protocols]
            [kmet.tui.theme :as theme]
            [kmet.tui.utils :as u]
            [kmet.tui.components.text :as text]
            [kmet.tui.components.container :as container]
            [kmet.tui.components.spinner :as spinner]
            [kmet.app.bash-executor :as bash-exec]
            [kmet.app.keybindings :as app-kb]
            [kmet.tui.keybindings :as tui-kb]
            [kmet.tui.macros :refer [track! defsetter defgetter defcomponent]]
            [clojure.string :as str]))

;; ─── Preview line limit ────────────────────────────────────────────────────
(def ^:private PREVIEW-LINES 20)

;; ─── Collapsed preview child ───────────────────────────────────────────────
;; Renders the last PREVIEW-LINES output lines, each truncated to the content
;; width, plus a "… N more lines (ctrl+o to expand)" hint.

(defcomponent BashPreview nil [preview-text pad]
  (render [_this w]
    (let [{:keys [visual-lines]} (u/truncate-to-visual-lines preview-text PREVIEW-LINES w)]
      (mapv #(str (apply str (repeat pad " ")) %) visual-lines))))

;; ─── Record ────────────────────────────────────────────────────────────────

(defcomponent BashExecutionComponent :bash
              [command-atom      ;; string
               output-lines-atom ;; vec of strings
               status-atom       ;; :running :complete :cancelled :error
               exit-code-atom    ;; int or nil
               expanded-atom     ;; boolean
               content-container  ;; Container for command/output/status
               spinner-comp      ;; Spinner component (animated loader)
               truncation-atom   ;; bash-exec truncation result or nil
               full-output-path-atom ;; string or nil
               started-at-atom   ;; long (System/currentTimeMillis)
               ended-at-atom     ;; long or nil
               cache-atom        ;; render cache
               exclude-from-context-atom ;; boolean (!! vs !)
               theme-atom]       ;; Theme record (default dark-theme)
  (render [this width]
    (track! this width
      (let [command @command-atom
            raw-output-lines @output-lines-atom
            status @status-atom
            exit-code @exit-code-atom
            expanded? @expanded-atom
            exclude? @exclude-from-context-atom
            truncation @truncation-atom
            full-output-path @full-output-path-atom
            started-at @started-at-atom
            ended-at @ended-at-atom
            t @theme-atom
            color-key (if exclude? :dim :bash-mode)
            border-color (fn [s] (theme/fg t color-key s))
            content-pad 1
            ;; ── Context truncation (pi: truncateTail before display) ──
            full-output (str/join "\n" raw-output-lines)
            {:keys [content truncated]}
            (bash-exec/truncate-tail full-output)
            display-lines (if (seq content) (str/split-lines content) [])
            preview-logical-lines (take-last PREVIEW-LINES display-lines)
            hidden-line-count (- (count display-lines) (count preview-logical-lines))]

            ;; Rebuild content container
        (container/container-clear content-container)

            ;; ── Command header ─────────────────────────────────────────
        (let [header-text (theme/fg t color-key
                                    (theme/bold (str "$ " command)))
              header (text/make-text header-text 1 0)]
          (container/container-add-child content-container header))

            ;; ── Output ─────────────────────────────────────────────────
        (when (seq display-lines)
          (if expanded?
                ;; Full output
            (let [styled (mapv #(theme/fg t :tool-output %) display-lines)
                  output-text (text/make-text (str "\n" (str/join "\n" styled)) 1 0)]
              (container/container-add-child content-container output-text))
                ;; Collapsed preview: last N lines with visual line truncation
            (let [styled-preview (mapv #(theme/fg t :tool-output %) preview-logical-lines)
                  preview-text (str "\n" (str/join "\n" styled-preview))]
              (container/container-add-child content-container
                                             (->BashPreview preview-text content-pad)))))

            ;; ── Loader or status ───────────────────────────────────────
        (if (= status :running)
              ;; Spinner text and colors are set once in make-bash-execution
          (container/container-add-child content-container @spinner-comp)
            ;; Status line: hidden lines hint + exit status + truncation + duration
          (let [status-parts (atom [])]

                  ;; Hidden lines hint
            (when (pos? hidden-line-count)
              (if expanded?
                (swap! status-parts conj
                       (str (theme/fg t :muted "(")
                            (app-kb/key-hint "app.tools.expand" "to collapse")
                            (theme/fg t :muted ")")))
                (swap! status-parts conj
                       (str (theme/fg t :muted
                                      (str "... " hidden-line-count " more lines ("))
                            (app-kb/key-hint "app.tools.expand" "to expand")
                            (theme/fg t :muted ")")))))

                  ;; Exit status
            (case status
              :cancelled (swap! status-parts conj (theme/fg t :warning "(cancelled)"))
              :error (swap! status-parts conj (theme/fg t :error (str "(exit " exit-code ")")))
              nil)

                  ;; Duration (pi: Elapsed X.Xs during, Took X.Xs after)
            (let [now (or ended-at (System/currentTimeMillis))
                  elapsed-ms (- now started-at)]
              (swap! status-parts conj
                     (theme/fg t :muted
                               (str (if ended-at "Took" "Elapsed")
                                    " " (format "%.1f" (float (/ elapsed-ms 1000))) "s"))))

                  ;; Truncation warning (pi: combined check — context OR server-side truncation)
            (let [was-truncated (or truncated (:truncated truncation))]
              (when (and was-truncated full-output-path)
                (swap! status-parts conj
                       (theme/fg t :warning
                                 (str "Output truncated. Full output: " full-output-path)))))

            (when (seq @status-parts)
              (container/container-add-child content-container
                                             (text/make-text (str "\n" (str/join "\n" @status-parts)) 1 0)))))

            ;; ── Return bordered display ────────────────────────────────
            ;; Pad every content line to the content width so the right border
            ;; stays flush (BashPreview/spacer lines are bare strings, unlike
            ;; Text children which self-pad).
        (let [cw (- width 2)
              top-border (str (border-color "┌") (apply str (repeat (- width 2) "─")) (border-color "┐"))
              bottom-border (str (border-color "└") (apply str (repeat (- width 2) "─")) (border-color "┘"))
              content-lines (protocols/render content-container cw)
              pad-line (fn [line]
                         (let [vis (u/visible-width line)]
                           (if (>= vis cw)
                             line
                             (str line (apply str (repeat (- cw vis) \space))))))
              result (conj (into [top-border]
                                 (map #(str (border-color "│") (pad-line %) (border-color "│")))
                                 content-lines)
                           bottom-border)]
          result)))))

;; ─── Construction ─────────────────────────────────────────────────────────

(defn make-bash-execution
  "Create a BashExecutionComponent with animated spinner.
   Options:
     :command                — the shell command string
     :exclude-from-context?  — boolean (!! vs !)
     :theme                  — Theme record (default dark-theme)"
  [& {:keys [command exclude-from-context? theme]
      :or {command "" exclude-from-context? false theme theme/dark-theme}}]
  (let [content-container (container/make-container)
        color-key (if exclude-from-context? :dim :bash-mode)
        cancel-key (or (tui-kb/key-text (tui-kb/get-global-keybindings) "app.interrupt") "Esc")
        ;; Create animated spinner with text and colors set once (pi: Loader constructor)
        sp (spinner/make-spinner
            :text (str "Running... (" cancel-key " to cancel)")
            :active true
            :prefix ""
            :spinner-color-fn (fn [s] (theme/fg theme color-key s))
            :message-color-fn (fn [s] (theme/fg theme :muted s)))]
    (map->BashExecutionComponent
     {:command-atom (atom command)
      :output-lines-atom (atom [])
      :status-atom (atom :running)
      :exit-code-atom (atom nil)
      :expanded-atom (atom false)
      :content-container content-container
      :spinner-comp (atom sp)
      :truncation-atom (atom nil)
      :full-output-path-atom (atom nil)
      :started-at-atom (atom (System/currentTimeMillis))
      :ended-at-atom (atom nil)
      :cache-atom (atom nil)
      :exclude-from-context-atom (atom exclude-from-context?)
      :theme-atom (atom theme)})))

(defn bash-execution-set-theme!
  "Set the theme on the border/output colors and the spinner."
  [comp theme]
  (reset! (:theme-atom comp) theme)
  (let [color-key (if @(:exclude-from-context-atom comp) :dim :bash-mode)]
    (spinner/spinner-set-spinner-color-fn! @(:spinner-comp comp)
                                           #(theme/fg theme color-key %))
    (spinner/spinner-set-message-color-fn! @(:spinner-comp comp)
                                           #(theme/fg theme :muted %)))
  (protocols/invalidate comp))

;; ─── Public API ────────────────────────────────────────────────────────────

(defsetter bash-execution-set-expanded! :expanded-atom comp expanded?)

(defn bash-execution-append-output!
  "Append a chunk of output text. Handles incomplete line continuation
   when a chunk doesn't end with a newline (matching pi's appendOutput)."
  [comp chunk]
  (let [clean (-> chunk
                  (str/replace #"\r\n" "\n")
                  (str/replace #"\r" "\n"))
        ;; Pi: split("\n") keeps a trailing "" for newline-terminated chunks;
        ;; split-lines drops it, which breaks line-continuation detection.
        new-lines (str/split clean #"\n" -1)
        current @(:output-lines-atom comp)]
    (if (and (seq current) (seq new-lines))
      ;; Pi: append first chunk to the last line (incomplete line continuation)
      ;; Pi: outputLines[last] += newLines[0]; outputLines.push(...newLines.slice(1))
      (let [base (vec (butlast current))
            merged (str (last current) (first new-lines))
            updated (into (conj base merged) (rest new-lines))]
        (reset! (:output-lines-atom comp) updated))
      (reset! (:output-lines-atom comp) (into current new-lines)))))

(defn bash-execution-set-complete!
  "Mark the bash command as complete.
   Stops the spinner, records exit code and duration.
   
   exit-code     — int or nil (nil if cancelled)
   cancelled?    — boolean
   :truncation   — {:total-lines int :shown-lines int ...} or nil
   :full-output-path — string or nil"
  [comp exit-code cancelled? & {:keys [truncation full-output-path]}]
  (reset! (:ended-at-atom comp) (System/currentTimeMillis))
  (reset! (:exit-code-atom comp) exit-code)
  (reset! (:status-atom comp)
          (cond
            cancelled? :cancelled
            (and exit-code (not= exit-code 0)) :error
            :else :complete))
  (when truncation
    (reset! (:truncation-atom comp) truncation))
  (when full-output-path
    (reset! (:full-output-path-atom comp) full-output-path))
  ;; Stop the spinner
  (when-let [sp @(:spinner-comp comp)]
    (spinner/spinner-stop! sp)))

(defn bash-execution-get-output
  "Get the raw accumulated output string."
  [comp]
  (str/join "\n" @(:output-lines-atom comp)))

(defgetter bash-execution-get-command :command-atom comp)

(defn bash-execution-is-running?
  "Returns true if the bash command is still running."
  [comp]
  (= :running @(:status-atom comp)))

;; nil if still running or cancelled
(defgetter bash-execution-get-exit-code :exit-code-atom comp)
