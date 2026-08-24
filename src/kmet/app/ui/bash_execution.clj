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
            [kmet.app.ui.subs :as s]
            [kmet.tui.macros :refer [track! defcomponent]]
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
               tools-expanded-atom ;; chat-history-wide toggle atom, or nil (unlinked)
               content-container  ;; Container for command/output/status
               spinner-comp      ;; Spinner component (animated loader)
               truncation-atom   ;; bash-exec truncation result or nil
               full-output-path-atom ;; string or nil
               started-at-atom   ;; long (System/currentTimeMillis)
               ended-at-atom     ;; long or nil
               cache-atom        ;; render cache
               exclude-from-context-atom ;; boolean (!! vs !)
               ticker-atom]      ;; 1s elapsed-tick future while running
  (render [this width]
    (track! this width
      (let [command @command-atom
            raw-output-lines @output-lines-atom
            status @status-atom
            exit-code @exit-code-atom
            expanded? (or @expanded-atom
                          ;; chat-history-wide toggle — read lexically so
                          ;; track! records it (tui.md §4 track-deps rule)
                          (when tools-expanded-atom @tools-expanded-atom))
            exclude? @exclude-from-context-atom
            truncation @truncation-atom
            full-output-path @full-output-path-atom
            started-at @started-at-atom
            ended-at @ended-at-atom
            ;; tracked read of the shared palette sub: a theme switch
            ;; re-derives this cache exactly once (Stage 5, dsl.md §3.2);
            ;; the spinner color fns re-apply below on every cache miss,
            ;; so the loader never keeps a stale palette while running
            t (deref s/theme-sub)
            color-key (if exclude? :dim :bash-mode)
            _ (let [sp @spinner-comp]
                (spinner/spinner-set-spinner-color-fn! sp #(theme/fg t color-key %))
                (spinner/spinner-set-message-color-fn! sp #(theme/fg t :muted %)))
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
                                             (map->BashPreview {:kind nil
                                                                :preview-text preview-text
                                                                :pad content-pad})))))

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
  "Create a BashExecutionComponent with animated spinner. THEME is no
   longer taken: styling subscribes to ui.subs/theme-sub and follows
   palette changes live (Stage 5).
   Options:
     :command                — the shell command string
     :exclude-from-context?  — boolean (!! vs !)"
  [& {:keys [command exclude-from-context? tools-expanded-atom]
      :or {command "" exclude-from-context? false}}]
  (let [content-container (container/make-container)
        t0 (theme/get-current-theme)
        color-key (if exclude-from-context? :dim :bash-mode)
        cancel-key (or (tui-kb/key-text (tui-kb/get-global-keybindings) "app.interrupt") "Esc")
        ;; Create animated spinner (pi: Loader constructor); colors are
        ;; refreshed from theme-sub on every render pass of the component
        sp (spinner/make-spinner
            :text (str "Running... (" cancel-key " to cancel)")
            :active true
            :prefix ""
            :spinner-color-fn (fn [x] (theme/fg t0 color-key x))
            :message-color-fn (fn [x] (theme/fg t0 :muted x)))
        comp (map->BashExecutionComponent
              {:kind :bash
               :command-atom (atom command)
               :output-lines-atom (atom [])
               :status-atom (atom :running)
               :exit-code-atom (atom nil)
               :expanded-atom (atom false)
               :tools-expanded-atom tools-expanded-atom
               :content-container content-container
               :spinner-comp (atom sp)
               :truncation-atom (atom nil)
               :full-output-path-atom (atom nil)
               :started-at-atom (atom (System/currentTimeMillis))
               :ended-at-atom (atom nil)
               :cache-atom (atom nil)
               :exclude-from-context-atom (atom exclude-from-context?)
               :ticker-atom (atom nil)})]
    ;; Pi: the loader's setInterval drives re-renders while running —
    ;; kmet's spinner is passive, so a 1s ticker invalidates the component
    ;; while :running (the invalidation schedules the render itself, §3.4),
    ;; keeping the Elapsed counter (and the spinner frame) updating even
    ;; when no output chunks arrive. Self-exits on completion;
    ;; set-complete! cancels it promptly.
    (reset! (:ticker-atom comp)
            (future
              (try
                (loop []
                  (Thread/sleep 1000)
                  (if (= :running @(:status-atom comp))
                    (do
                      ;; one bad invalidate/render-request must not kill the
                      ;; ticker (pi's setInterval survives callback throws)
                      (try
                        (protocols/invalidate comp)
                        (catch Exception _))
                      (recur))
                    nil))
                (catch InterruptedException _))))
    comp))

;; ─── Public API ────────────────────────────────────────────────────────────

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
  ;; Stop the spinner and the 1s elapsed ticker
  (when-let [sp @(:spinner-comp comp)]
    (spinner/spinner-stop! sp))
  (when-let [t @(:ticker-atom comp)]
    (future-cancel t)
    (reset! (:ticker-atom comp) nil)))

;; nil if still running or cancelled
