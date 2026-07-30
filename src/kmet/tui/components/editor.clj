(ns kmet.tui.components.editor
  "Multi-line text editor with word-wrap, vertical scrolling, and cursor.
   Port of @earendil-works/pi-tui Editor.
   Phase 2b.1 — Core Editor: multi-line editing, word-wrap, cursor movement,
   vertical scrolling, border, basic editing (typing, backspace, delete,
   enter newline, submit)."
  (:require [kmet.tui.protocols :as protocols]
            [kmet.tui.keys :as keys]
            [kmet.tui.utils :as u]
            [kmet.tui.components.editing :as edit]))



;; ─── Grapheme helpers ─────────────────────────────────────────────────────
;; Imported from kmet.tui.components.editing (grapheme-left, grapheme-right,
;; grapheme-at, grapheme-segments, KillRing, etc.)

;; ─── Word-wrapped line layout ──────────────────────────────────────────────

(defrecord TextChunk [text start-index end-index])

(defn- word-wrap-line
  "Split a single logical line into word-wrapped visual chunks."
  [line max-width]
  (if (or (nil? line) (<= max-width 0))
    [(map->TextChunk {:text (or line "") :start-index 0 :end-index (count (or line ""))})]
    (let [line-width (u/visible-width line)]
      (if (<= line-width max-width)
        [(map->TextChunk {:text line :start-index 0 :end-index (count line)})]
        (let [segments (edit/grapheme-segments line)
              n (count segments)
              result (volatile! [])
              cw (volatile! 0)    ;; current line width
              cs (volatile! 0)    ;; current chunk start index
              woi (volatile! -1)  ;; wrap opportunity segment index
              wow (volatile! 0)]  ;; wrap opportunity width
          (loop [i 0]
            (when (< i n)
              (let [seg (nth segments i)
                    gwidth (u/visible-width (:text seg))
                    char-idx (:start seg)
                    is-ws (boolean (re-find #"^\s" (:text seg)))]
                (if (> gwidth max-width)
                  ;; Atomic segment wider than max-width: recurse
                  (let [sub-chunks (word-wrap-line (:text seg) max-width)]
                    (doseq [sc (butlast sub-chunks)]
                      (vswap! result conj
                        (map->TextChunk {:text (:text sc)
                                         :start-index (+ char-idx (:start-index sc))
                                         :end-index (+ char-idx (:end-index sc))})))
                    (let [last-sc (last sub-chunks)]
                      (vreset! cs (+ char-idx (:start-index last-sc)))
                      (vreset! cw (u/visible-width (:text last-sc)))
                      (vreset! woi -1) (vreset! wow 0)))
                  ;; Normal-width grapheme
                  (do
                    ;; Overflow check
                    (when (> (+ @cw gwidth) max-width)
                      (if (and (>= @woi 0)
                               (<= (+ (- @cw @wow) gwidth) max-width))
                        (let [opp-seg (nth segments @woi)]
                          (vswap! result conj
                            (map->TextChunk {:text (subs line @cs (:start opp-seg))
                                             :start-index @cs
                                             :end-index (:start opp-seg)}))
                          (vreset! cs (:start opp-seg))
                          (vreset! cw @wow))
                        (when (< @cs char-idx)
                          (vswap! result conj
                            (map->TextChunk {:text (subs line @cs char-idx)
                                             :start-index @cs
                                             :end-index char-idx}))
                          (vreset! cs char-idx)
                          (vreset! cw 0)))
                      (vreset! woi -1) (vreset! wow 0))
                    ;; Advance
                    (vswap! cw + gwidth)
                    ;; Record wrap opportunity (ws followed by non-ws)
                    (let [nxt (when (< (inc i) n) (nth segments (inc i)))]
                      (when (and is-ws nxt (not (re-find #"^\s" (:text nxt))))
                        (vreset! woi i)
                        (vreset! wow @cw))))))
              (recur (inc i))))
          (vswap! result conj
            (map->TextChunk {:text (subs line @cs)
                             :start-index @cs
                             :end-index (count line)}))
          @result)))))

;; ─── Visual line map ───────────────────────────────────────────────────────

(defrecord VisualLineInfo [logical-line start-col length text])

(defn- build-visual-line-map
  "Build a vector of VisualLineInfo from logical lines and content width."
  [lines width]
  (if (or (empty? lines) (and (= (count lines) 1) (empty? (first lines))))
    [(map->VisualLineInfo {:logical-line 0 :start-col 0 :length 0 :text ""})]
    (vec (mapcat
      (fn [i]
        (let [line (or (nth lines i) "")
              lw (u/visible-width line)]
          (if (<= lw width)
            [(map->VisualLineInfo {:logical-line i :start-col 0
                                   :length (count line) :text line})]
            (map #(map->VisualLineInfo {:logical-line i
                                        :start-col (:start-index %)
                                        :length (count (:text %))
                                        :text (:text %)})
                 (word-wrap-line line width)))))
      (range (count lines))))))

(defn- find-visual-line-at
  "Find visual line index containing the given logical line + column."
  [visual-lines logical-line col]
  (loop [i 0]
    (if (>= i (count visual-lines))
      (max 0 (dec (count visual-lines)))
      (let [vl (nth visual-lines i)]
        (if (and (= (:logical-line vl) logical-line)
                 (>= col (:start-col vl))
                 (<= col (+ (:start-col vl) (:length vl))))
          i
          (recur (inc i)))))))

(defn- find-current-visual-line
  "Find the visual line index for the current cursor position."
  [visual-lines cursor-line cursor-col]
  (find-visual-line-at visual-lines cursor-line cursor-col))

;; ─── Editor state ─────────────────────────────────────────────────────────

(defrecord EditorState [lines cursor-line cursor-col])

(defn make-editor-state
  ([] (map->EditorState {:lines [""] :cursor-line 0 :cursor-col 0}))
  ([text]
   (let [lines (if (empty? text) [""] (clojure.string/split-lines text))
         last-idx (count lines)]
     (map->EditorState {:lines lines
                        :cursor-line (max 0 (dec last-idx))
                        :cursor-col (count (last lines))}))))

;; ─── Public helpers ────────

(defn editor-get-text [editor]
  (clojure.string/join "\n" (:lines @(:state-atom editor))))



;; ─── Kill ring
;; Imported from kmet.tui.components.editing

;; ─── Undo/redo stacks

(defn- snapshot-state [state-atom]
  (let [s @state-atom]
    {:lines (:lines s) :cursor-line (:cursor-line s) :cursor-col (:cursor-col s)}))

(defn- undo-push [stack-atom snapshot]
  (swap! stack-atom conj snapshot))

(defn- undo-pop [stack-atom]
  (let [s @stack-atom]
    (when (seq s)
      (let [snapshot (peek s)]
        (swap! stack-atom pop)
        snapshot))))

(defn- push-undo-state [editor]
  (undo-push (:undo-stack editor) (snapshot-state (:state-atom editor))))

(defn- handle-undo [editor]
  (when-let [snapshot (undo-pop (:undo-stack editor))]
    (undo-push (:redo-stack editor) (snapshot-state (:state-atom editor)))
    (reset! (:state-atom editor)
      (map->EditorState {:lines (:lines snapshot)
                         :cursor-line (:cursor-line snapshot)
                         :cursor-col (:cursor-col snapshot)}))
    (reset! (:preferred-col-atom editor) nil)
    (reset! (:last-action editor) nil)
    (when-let [cb @(:on-change editor)] (cb (editor-get-text editor)))))

(defn- handle-redo [editor]
  (when-let [snapshot (undo-pop (:redo-stack editor))]
    (undo-push (:undo-stack editor) (snapshot-state (:state-atom editor)))
    (reset! (:state-atom editor)
      (map->EditorState {:lines (:lines snapshot)
                         :cursor-line (:cursor-line snapshot)
                         :cursor-col (:cursor-col snapshot)}))
    (reset! (:preferred-col-atom editor) nil)
    (reset! (:last-action editor) nil)
    (when-let [cb @(:on-change editor)] (cb (editor-get-text editor)))))

;; ─── Word navigation

(defn- word-boundary-left [editor-lines cursor-line cursor-col]
  (let [cl (max 0 cursor-line) cc (max 0 cursor-col)]
    (if (and (zero? cl) (zero? cc))
      [0 0]
      (let [line (get editor-lines cl "") n (count line)]
        (if (> cc 0)
          (let [before (subs line 0 cc)
                no-trail (clojure.string/replace before #"\s+$" "")
                trimmed (count no-trail)]
            (if (zero? trimmed) [cl 0]
              (let [last-char (subs no-trail (dec trimmed))
                    word-char? (boolean (re-find #"^\w" last-char))]
                (loop [i (dec trimmed)]
                  (if (<= i 0) [cl 0]
                    (let [c (subs line i (inc i))
                          is-word (re-find #"^\w" c)
                          is-space (re-find #"^\s" c)]
                      (cond
                        is-space (if word-char? (inc i) (recur (dec i)))
                        word-char? (if is-word (recur (dec i)) (inc i))
                        :else (if is-word (inc i) (recur (dec i))))))))))
          (if (> cl 0)
            (let [prev-line (get editor-lines (dec cl) "")]
              (if (zero? (count prev-line))
                (word-boundary-left editor-lines (dec cl) 0)
                (let [no-trail (clojure.string/replace prev-line #"\s+$" "")
                      trimmed (count no-trail)]
                  (if (zero? trimmed) [(dec cl) 0]
                    (let [last-char (subs no-trail (dec trimmed))
                          word-char? (boolean (re-find #"^\w" last-char))]
                      (loop [i (dec trimmed)]
                        (if (<= i 0) [(dec cl) 0]
                          (let [c (subs prev-line i (inc i))
                                is-word (re-find #"^\w" c)
                                is-space (re-find #"^\s" c)]
                            (cond
                              is-space (if word-char? (inc i) (recur (dec i)))
                              word-char? (if is-word (recur (dec i)) (inc i))
                              :else (if is-word (inc i) (recur (dec i))))))))))))
            [0 0])))))

(defn- word-boundary-right [editor-lines cursor-line cursor-col]
  (let [cl (max 0 cursor-line) cc (max 0 cursor-col) total (count editor-lines)]
    (if (and (>= cl (dec total)) (>= cc (count (get editor-lines cl "")))) [cl cc]
      (let [line (get editor-lines cl "") n (count line)]
        (if (< cc n)
          ;; Pure Clojure word boundary detection
          (let [after (subs line cc)
                skip-ws (count (take-while #(re-find #"^\s" (str %)) after))
                start (+ cc skip-ws)]
            (if (>= start n)
              (if (< cl (dec total)) [(inc cl) 0] [cl n])
              (let [rest-str (subs line start)
                    word-len (count (take-while #(re-find #"^\w" (str %)) rest-str))]
                (if (pos? word-len)
                  [cl (+ start word-len)]
                  (let [non-ws (count (take-while #(not (re-find #"^\s" (str %))) rest-str))]
                    [cl (+ start (max 1 non-ws))])))))
          (if (< cl (dec total)) [(inc cl) 0] [cl cc])))))))

;; ─── Line editing actions

(defn- insert-character [editor char]
  (let [state @(:state-atom editor) lines (:lines state) cl (:cursor-line state)
        cc (:cursor-col state) line (or (nth lines cl) "")]
    (when (or (re-find #"^\s" char) (not= @(:last-action editor) :type-word))
      (push-undo-state editor))
    (reset! (:last-action editor) :type-word)
    (reset! (:redo-stack editor) [])
    (swap! (:state-atom editor) assoc
      :lines (assoc lines cl (str (subs line 0 cc) char (subs line cc)))
      :cursor-col (+ cc (count char)))
    (when-let [cb @(:on-change editor)] (cb (editor-get-text editor)))))

(defn- handle-backspace [editor]
  (let [state @(:state-atom editor) lines (:lines state) cl (:cursor-line state) cc (:cursor-col state)]
    (push-undo-state editor)
    (reset! (:last-action editor) nil)
    (reset! (:redo-stack editor) [])
    (if (> cc 0)
      (let [line (or (nth lines cl) "") glen (edit/grapheme-left line cc)]
        (swap! (:state-atom editor) assoc
          :lines (assoc lines cl (str (subs line 0 glen) (subs line cc)))
          :cursor-col glen))
      (when (> cl 0)
        (let [prev-line (or (nth lines (dec cl)) "") cur-line (or (nth lines cl) "")]
          (swap! (:state-atom editor) assoc
            :lines (vec (concat (subvec lines 0 (dec cl))
                                [(str prev-line cur-line)] (subvec lines (inc cl))))
            :cursor-line (dec cl) :cursor-col (count prev-line)))))
    (when-let [cb @(:on-change editor)] (cb (editor-get-text editor)))))

(defn- handle-forward-delete [editor]
  (let [state @(:state-atom editor) lines (:lines state) cl (:cursor-line state)
        cc (:cursor-col state) line (or (nth lines cl) "")]
    (push-undo-state editor)
    (reset! (:last-action editor) nil)
    (reset! (:redo-stack editor) [])
    (if (< cc (count line))
      (let [nxt (edit/grapheme-right line cc)]
        (swap! (:state-atom editor) assoc
          :lines (assoc lines cl (str (subs line 0 cc) (subs line nxt)))))
      (when (< cl (dec (count lines)))
        (let [next-line (or (nth lines (inc cl)) "")]
          (swap! (:state-atom editor) assoc
            :lines (vec (concat (subvec lines 0 (inc cl))
                                [(str line next-line)] (subvec lines (+ cl 2))))))))
    (when-let [cb @(:on-change editor)] (cb (editor-get-text editor)))))

(defn- add-new-line [editor]
  (let [state @(:state-atom editor) lines (:lines state) cl (:cursor-line state)
        cc (:cursor-col state) line (or (nth lines cl) "")]
    (push-undo-state editor)
    (reset! (:last-action editor) nil)
    (reset! (:redo-stack editor) [])
    (swap! (:state-atom editor) assoc
      :lines (vec (concat (subvec lines 0 cl) [(subs line 0 cc)] [(subs line cc)]
                          (subvec lines (inc cl))))
      :cursor-line (inc cl) :cursor-col 0)
    (reset! (:preferred-col-atom editor) nil)
    (when-let [cb @(:on-change editor)] (cb (editor-get-text editor)))))

(defn- handle-kill-to-line-start [editor]
  (let [state @(:state-atom editor) lines (:lines state) cl (:cursor-line state)
        cc (:cursor-col state) line (or (nth lines cl) "")]
    (when (pos? cc)
      (push-undo-state editor)
      (let [deleted (subs line 0 cc)]
        (edit/kill-ring-push (:kill-ring editor) deleted :prepend true
                        :accumulate (= @(:last-action editor) :kill))
        (reset! (:last-action editor) :kill)
        (reset! (:redo-stack editor) [])
        (swap! (:state-atom editor) assoc
          :lines (assoc lines cl (subs line cc)) :cursor-col 0))
      (when-let [cb @(:on-change editor)] (cb (editor-get-text editor))))))

(defn- handle-kill-to-line-end [editor]
  (let [state @(:state-atom editor) lines (:lines state) cl (:cursor-line state)
        cc (:cursor-col state) line (or (nth lines cl) "")]
    (when (< cc (count line))
      (push-undo-state editor)
      (let [deleted (subs line cc)]
        (edit/kill-ring-push (:kill-ring editor) deleted :prepend false
                        :accumulate (= @(:last-action editor) :kill))
        (reset! (:last-action editor) :kill)
        (reset! (:redo-stack editor) [])
        (swap! (:state-atom editor) assoc
          :lines (assoc lines cl (subs line 0 cc))))
      (when-let [cb @(:on-change editor)] (cb (editor-get-text editor))))))

(defn- handle-delete-word-backward [editor]
  (let [state @(:state-atom editor) lines (:lines state) cl (:cursor-line state) cc (:cursor-col state)]
    (when (or (pos? cc) (pos? cl))
      (push-undo-state editor)
      (let [[new-line new-col] (word-boundary-left lines cl cc)
            deleted (if (= new-line cl)
                      (subs (nth lines cl) new-col cc)
                      (str (subs (nth lines new-line) new-col) "\n"
                           (clojure.string/join "\n" (subvec lines (inc new-line) cl)) "\n"
                           (subs (nth lines cl) 0 cc)))]
        (edit/kill-ring-push (:kill-ring editor) deleted :prepend true
                        :accumulate (= @(:last-action editor) :kill))
        (reset! (:last-action editor) :kill)
        (reset! (:redo-stack editor) [])
        (let [new-lines (if (= new-line cl)
                          (assoc lines cl (str (subs (nth lines cl) 0 new-col) (subs (nth lines cl) cc)))
                          (vec (concat (subvec lines 0 new-line)
                                       [(str (subs (nth lines new-line) 0 new-col) (subs (nth lines cl) cc))]
                                       (subvec lines (inc cl)))))]
          (swap! (:state-atom editor) assoc :lines new-lines :cursor-line new-line :cursor-col new-col)))
      (when-let [cb @(:on-change editor)] (cb (editor-get-text editor))))))

(defn- handle-delete-word-forward [editor]
  (let [state @(:state-atom editor) lines (:lines state) cl (:cursor-line state) cc (:cursor-col state)]
    (let [[tline tcol] (word-boundary-right lines cl cc)]
      (when (or (not= tline cl) (not= tcol cc))
        (push-undo-state editor)
        (let [deleted (if (= tline cl)
                        (subs (nth lines cl) cc tcol)
                        (str (subs (nth lines cl) cc) "\n"
                             (clojure.string/join "\n" (subvec lines (inc cl) tline)) "\n"
                             (subs (nth lines tline) 0 tcol)))]
          (edit/kill-ring-push (:kill-ring editor) deleted :prepend false
                          :accumulate (= @(:last-action editor) :kill))
          (reset! (:last-action editor) :kill)
          (reset! (:redo-stack editor) [])
          (let [new-lines (if (= tline cl)
                            (assoc lines cl (str (subs (nth lines cl) 0 cc) (subs (nth lines cl) tcol)))
                            (vec (concat (subvec lines 0 cl)
                                         [(str (subs (nth lines cl) 0 cc) (subs (nth lines tline) tcol))]
                                         (subvec lines (inc tline)))))]
            (swap! (:state-atom editor) assoc :lines new-lines)))
        (when-let [cb @(:on-change editor)] (cb (editor-get-text editor)))))))

(defn- handle-kill-line [editor]
  (let [state @(:state-atom editor) lines (:lines state) cl (:cursor-line state)]
    (when (seq lines)
      (push-undo-state editor)
      (let [deleted (nth lines cl "")
            new-lines (if (= 1 (count lines)) [""]
                          (vec (concat (subvec lines 0 cl) (subvec lines (inc cl)))))
            new-cl (min cl (dec (count new-lines)))]
        (edit/kill-ring-push (:kill-ring editor) (str deleted "\n") :prepend false
                        :accumulate (= @(:last-action editor) :kill))
        (reset! (:last-action editor) :kill)
        (reset! (:redo-stack editor) [])
        (swap! (:state-atom editor) assoc :lines new-lines :cursor-line new-cl :cursor-col 0))
      (when-let [cb @(:on-change editor)] (cb (editor-get-text editor))))))

;; ─── Yank helpers

(defn- handle-yank [editor]
  (let [state @(:state-atom editor) lines (:lines state) cl (:cursor-line state)
        cc (:cursor-col state) text (edit/kill-ring-peek (:kill-ring editor))]
    (when text
      (push-undo-state editor)
      (reset! (:redo-stack editor) [])
      (if (clojure.string/includes? text "\n")
        (let [parts (clojure.string/split text #"\n" -1)
              first-part (first parts) rest-parts (rest parts)
              line (nth lines cl "")
              new-line (str (subs line 0 cc) first-part)
              remaining (subs line cc)
              new-lines (vec (concat (subvec lines 0 cl) [new-line] rest-parts
                                     (when (seq remaining) [remaining])
                                     (subvec lines (inc cl))))]
          (swap! (:state-atom editor) assoc
            :lines new-lines :cursor-line (+ cl (count rest-parts))
            :cursor-col (count (or (last rest-parts) ""))))
        (let [line (nth lines cl "")
              new-val (str (subs line 0 cc) text (subs line cc))]
          (swap! (:state-atom editor) assoc
            :lines (assoc lines cl new-val) :cursor-col (+ cc (count text)))))
      (reset! (:last-action editor) :yank)
      (when-let [cb @(:on-change editor)] (cb (editor-get-text editor))))))

(defn- handle-yank-pop [editor]
  (let [state @(:state-atom editor) lines (:lines state) cl (:cursor-line state)
        cc (:cursor-col state) kr (:kill-ring editor)]
    (when (and (= @(:last-action editor) :yank) (> (edit/kill-ring-length kr) 1))
      (push-undo-state editor)
      (reset! (:redo-stack editor) [])
      (let [_ (edit/kill-ring-rotate kr)
            new-text (or (edit/kill-ring-peek kr) "")]
        (if (clojure.string/includes? new-text "\n")
          (let [parts (clojure.string/split new-text #"\n" -1)
                first-part (first parts) rest-parts (rest parts)
                line (nth lines cl "")
                new-line (str (subs line 0 cc) first-part)
                remaining (subs line cc)
                new-lines (vec (concat (subvec lines 0 cl) [new-line] rest-parts
                                       (when (seq remaining) [remaining])
                                       (subvec lines (inc cl))))]
            (swap! (:state-atom editor) assoc
              :lines new-lines :cursor-line (+ cl (count rest-parts))
              :cursor-col (count (or (last rest-parts) ""))))
          (let [line (nth lines cl "")
                new-val (str (subs line 0 cc) new-text (subs line cc))]
            (swap! (:state-atom editor) assoc
              :lines (assoc lines cl new-val) :cursor-col (+ cc (count new-text)))))
        (reset! (:last-action editor) :yank)
        (when-let [cb @(:on-change editor)] (cb (editor-get-text editor)))))))

;; ─── Paste markers

(defn- handle-paste [editor text]
  (let [state @(:state-atom editor) lines (:lines state) cl (:cursor-line state)
        cc (:cursor-col state) paste-lines (clojure.string/split-lines text)
        line-count (count paste-lines)]
    (push-undo-state editor)
    (reset! (:last-action editor) nil)
    (reset! (:redo-stack editor) [])
    (if (<= line-count 10)
      (let [first-line (first paste-lines) rest-lines (rest paste-lines)
            cur-line (nth lines cl "")
            new-cur-line (str (subs cur-line 0 cc) first-line (subs cur-line cc))
            new-lines (if (empty? rest-lines)
                        (assoc lines cl new-cur-line)
                        (vec (concat (subvec lines 0 cl)
                                     [(str (subs cur-line 0 cc) first-line)]
                                     (mapv (fn [l] l) rest-lines)
                                     [(subs cur-line cc)]
                                     (subvec lines (inc cl)))))]
        (swap! (:state-atom editor) assoc
          :lines new-lines :cursor-line (+ cl (count rest-lines))
          :cursor-col (count (or (last rest-lines) ""))))
      (let [n (swap! (:paste-counter editor) inc)
            marker (str "[paste #" n " +" line-count " lines — ctrl+o to expand]")
            cur-line (nth lines cl "")
            new-cur-line (str (subs cur-line 0 cc) marker (subs cur-line cc))]
        (swap! (:paste-store editor) assoc n text)
        (swap! (:state-atom editor) assoc
          :lines (assoc lines cl new-cur-line) :cursor-col (+ cc (count marker)))))
    (when-let [cb @(:on-change editor)] (cb (editor-get-text editor)))))

;; ─── Character jump mode

(defn- enter-jump-mode [editor dir]
  (reset! (:jump-mode editor) {:dir dir :char nil}))

(defn- handle-jump-character [editor char]
  (let [jm @(:jump-mode editor) dir (:dir jm)
        state @(:state-atom editor) lines (:lines state)
        cl (:cursor-line state) cc (:cursor-col state)]
    (reset! (:jump-mode editor) nil)
    (when (and dir char)
      (let [result (if (= dir :forward)
                     (let [line (nth lines cl "") idx (clojure.string/index-of line (str char) cc)]
                       (if (>= idx 0) [cl idx]
                         (loop [i (inc cl)]
                           (when (< i (count lines))
                             (let [li (nth lines i "") idx (clojure.string/index-of li (str char))]
                               (if (>= idx 0) [i idx] (recur (inc i))))))))
                     (let [line (nth lines cl "")
                           idx (if (<= cc 0) -1 (clojure.string/last-index-of line (str char) (dec cc)))]
                       (if (>= idx 0) [cl idx]
                         (loop [i (dec cl)]
                           (when (>= i 0)
                             (let [li (nth lines i "") idx (clojure.string/last-index-of li (str char))]
                               (if (>= idx 0) [i idx] (recur (dec i)))))))))]
        (when result
          (swap! (:state-atom editor) assoc
            :cursor-line (first result) :cursor-col (second result))
          (reset! (:preferred-col-atom editor) nil))))))

;; ─── Cursor movement

(defn- move-cursor-word-left [editor]
  (let [s @(:state-atom editor)
        [nl nc] (word-boundary-left (:lines s) (:cursor-line s) (:cursor-col s))]
    (swap! (:state-atom editor) assoc :cursor-line nl :cursor-col nc)
    (reset! (:preferred-col-atom editor) nil)
    (reset! (:last-action editor) nil)))

(defn- move-cursor-word-right [editor]
  (let [s @(:state-atom editor)
        [nl nc] (word-boundary-right (:lines s) (:cursor-line s) (:cursor-col s))]
    (swap! (:state-atom editor) assoc :cursor-line nl :cursor-col nc)
    (reset! (:preferred-col-atom editor) nil)
    (reset! (:last-action editor) nil)))

;; ─── History navigation

(defn- history-set-state! [editor text]
  (reset! (:state-atom editor) (make-editor-state text))
  (reset! (:scroll-offset-atom editor) 0)
  (reset! (:preferred-col-atom editor) nil)
  (reset! (:undo-stack editor) [])
  (reset! (:redo-stack editor) [])
  (reset! (:last-action editor) nil)
  (reset! (:jump-mode editor) nil))

(defn- history-backward [editor]
  (let [h @(:history editor)
        idx @(:history-idx editor)]
    (if (or (neg? idx) (empty? h))
      nil
      (let [next-idx (max -1 (dec idx))
            entry (nth h idx)]
        (reset! (:history-idx editor) next-idx)
        (history-set-state! editor entry)
        ;; Move cursor to end
        (let [lines (:lines @(:state-atom editor))]
          (swap! (:state-atom editor) assoc
            :cursor-line (max 0 (dec (count lines)))
            :cursor-col (count (last lines))))
        (when-let [cb @(:on-change editor)] (cb (editor-get-text editor)))))))

(defn- history-forward [editor]
  (let [h @(:history editor)
        idx @(:history-idx editor)
        n (count h)]
    (if (or (neg? idx) (zero? n))
      nil
      (let [next-idx (inc idx)]
        (if (< next-idx n)
          (let [entry (nth h next-idx)]
            (reset! (:history-idx editor) next-idx)
            (history-set-state! editor entry)
            (let [lines (:lines @(:state-atom editor))]
              (swap! (:state-atom editor) assoc
                :cursor-line (max 0 (dec (count lines)))
                :cursor-col (count (last lines))))
            (when-let [cb @(:on-change editor)] (cb (editor-get-text editor))))
          (do
            (reset! (:history-idx editor) -1)
            (history-set-state! editor "")
            (when-let [cb @(:on-change editor)] (cb (editor-get-text editor)))))))))

(defn editor-push-history! [editor text]
  (when (and (seq text) (not= text (peek @(:history editor))))
    (swap! (:history editor) conj text)
    (reset! (:history-idx editor) -1)))

;; ─── Autocomplete

(defn- handle-tab [editor]
  (if-let [ap @(:autocomplete-provider editor)]
    (let [state @(:state-atom editor)
          lines (:lines state)
          cl (:cursor-line state) cc (:cursor-col state)
          line (or (nth lines cl) "")
          before-cursor (subs line 0 cc)
          word-start (or (last (keep-indexed #(when (re-find #"[\s/]" (str %2)) %1) before-cursor))
                         -1)
          partial (subs before-cursor (inc word-start))
          result (ap partial (editor-get-text editor))]
      (when result
        (insert-character editor result)))
    ;; Default tab: insert 4 spaces
    (insert-character editor "    ")))

;; ─── Height helper

(defn- get-editor-height [editor]
  (or @(:height-atom editor) 12))

;; ─── Internal helpers (cursor movement)
(defn- move-cursor-horizontal [editor dir]
  (let [state @(:state-atom editor)
        lines (:lines state)
        cl (:cursor-line state)
        cc (:cursor-col state)
        line (or (nth lines cl) "")]
    (reset! (:preferred-col-atom editor) nil)
    (if (neg? dir)
      (if (> cc 0)
        (swap! (:state-atom editor) assoc :cursor-col (edit/grapheme-left line cc))
        (when (> cl 0)
          (let [prev-line (or (nth lines (dec cl)) "")]
            (swap! (:state-atom editor) assoc
              :cursor-line (dec cl)
              :cursor-col (count prev-line)))))
      (if (< cc (count line))
        (swap! (:state-atom editor) assoc :cursor-col (edit/grapheme-right line cc))
        (when (< cl (dec (count lines)))
          (swap! (:state-atom editor) assoc
            :cursor-line (inc cl)
            :cursor-col 0))))))

(defn- move-cursor-vertical [editor dir]
  (let [state @(:state-atom editor)
        lines (:lines state)
        cl (:cursor-line state)
        cc (:cursor-col state)
        width @(:last-width-atom editor)
        visual-lines (build-visual-line-map lines width)
        current-idx (find-current-visual-line visual-lines cl cc)
        target-idx (+ current-idx dir)]
    (when (and (>= target-idx 0) (< target-idx (count visual-lines)))
      (let [target-vl (nth visual-lines target-idx)
            preferred @(:preferred-col-atom editor)
            target-col (if preferred
                         (min preferred (:length target-vl))
                         (do (when (pos? dir)
                               (reset! (:preferred-col-atom editor) cc))
                             (min cc (:length target-vl))))]
        (swap! (:state-atom editor) assoc
          :cursor-line (:logical-line target-vl)
          :cursor-col (+ (:start-col target-vl) target-col))))))

(defn- page-scroll [editor dir]
  (let [width @(:last-width-atom editor)
        lines (:lines @(:state-atom editor))
        visual-lines (build-visual-line-map lines width)
        page-size (max 1 (dec (get-editor-height editor)))]
    (dotimes [_ page-size]
      (move-cursor-vertical editor dir))))

;; ─── Editor component ──────────────────────────────────────────────────────

(defrecord Editor [state-atom scroll-offset-atom preferred-col-atom
                   last-width-atom focused? on-submit on-change
                   disable-submit padding-x border-fn height-atom
                   undo-stack redo-stack kill-ring last-action
                   paste-buffer paste-state paste-store paste-counter
                   jump-mode
                   history history-idx
                   autocomplete-provider]
  protocols/IComponent

  (render [this width]
    (let [state @state-atom
          padding-x @padding-x
          max-padding (max 0 (quot (dec width) 2))
          padding-x (min padding-x max-padding)
          content-width (max 1 (- width (* padding-x 2)))
          layout-width (max 1 (- content-width (if (zero? padding-x) 1 0)))
          _ (reset! last-width-atom layout-width)
          lines (:lines state)
          cursor-line (:cursor-line state)
          cursor-col (:cursor-col state)
          visual-lines (build-visual-line-map lines layout-width)
          max-visible (get-editor-height this)
          cursor-visual-idx (find-current-visual-line visual-lines cursor-line cursor-col)
          scroll-offset @scroll-offset-atom
          new-offset (cond
                       (< cursor-visual-idx scroll-offset) cursor-visual-idx
                       (>= cursor-visual-idx (+ scroll-offset max-visible))
                       (- cursor-visual-idx max-visible -1)
                       :else scroll-offset)
          max-offset (max 0 (- (count visual-lines) max-visible))
          scroll-offset (max 0 (min new-offset max-offset))]
      (reset! scroll-offset-atom scroll-offset)
      (let [visible (subvec visual-lines scroll-offset
                            (min (+ scroll-offset max-visible) (count visual-lines)))
            bdr (if @border-fn (@border-fn "─") "─")
            left-pad (apply str (repeat padding-x \space))
            right-pad left-pad
            result (volatile! [])]
        ;; Top border
        (if (pos? scroll-offset)
          (vswap! result conj (str "─── ↑ " scroll-offset " more "
                                    (apply str (repeat (max 0 (- width 12)) "─"))))
          (vswap! result conj (apply str (repeat width bdr))))
        ;; Render visible lines
        (doseq [[vi vl] (map-indexed vector visible)]
          (let [vl-has-cursor (= (+ vi scroll-offset) cursor-visual-idx)
                vl-cursor-col (- cursor-col (:start-col vl))
                display-text (:text vl)
                line-width (u/visible-width display-text)]
            (if (and vl-has-cursor (>= vl-cursor-col 0) (<= vl-cursor-col (count display-text)))
              (let [before (subs display-text 0 vl-cursor-col)
                    at-cursor (edit/grapheme-at display-text vl-cursor-col)
                    after (subs display-text
                                (min (count display-text) (+ vl-cursor-col (count at-cursor))))
                    cursor-at-end? (empty? after)
                    display-with-cursor (str before
                                             (when @focused? u/CURSOR-MARKER)
                                             "\u001b[7m" (if cursor-at-end? " " at-cursor) "\u001b[0m"
                                             after)
                    effective-width (if cursor-at-end?
                                     (+ line-width 1)
                                     line-width)
                    cursor-in-padding? (and cursor-at-end?
                                           (> effective-width content-width)
                                           (pos? padding-x))
                    p (apply str (repeat (max 0 (- content-width effective-width)) \space))
                    rp (if cursor-in-padding? (subs right-pad 1) right-pad)]
                (vswap! result conj (str left-pad display-with-cursor p rp)))
              (let [p (apply str (repeat (max 0 (- content-width line-width)) \space))]
                (vswap! result conj (str left-pad display-text p right-pad))))))
        ;; Bottom border
        (let [remaining (- (count visual-lines) (+ scroll-offset (count visible)))]
          (if (pos? remaining)
            (vswap! result conj (str "─── ↓ " remaining " more "
                                      (apply str (repeat (max 0 (- width 12)) "─"))))
            (vswap! result conj (apply str (repeat width bdr)))))
        @result)))

  (handle-input [this data]
    (if (and @jump-mode (:dir @jump-mode) (nil? (:char @jump-mode)))
      (let [char (first data)]
        (when (and char (not= (int char) 27))
          (handle-jump-character this (str char)))
        nil)
      (let [state @state-atom
            lines (:lines state)]
        (cond
          (and (keys/matches-key? data "enter") (not @disable-submit))
          (do (when-let [cb @on-submit] (cb (clojure.string/join "\n" lines))) nil)

          (or (keys/matches-key? data (keys/shift "enter"))
              (keys/matches-key? data (keys/ctrl "enter"))
              (keys/matches-key? data (keys/alt "enter"))
              (keys/matches-key? data (keys/ctrl "j")))
          (do (add-new-line this) nil)

          (keys/matches-key? data "escape")
          (do (reset! jump-mode nil)
              (when-let [cb @on-submit] (cb nil))
              nil)

          (or (keys/matches-key? data "backspace")
              (keys/matches-key? data (keys/ctrl "h")))
          (do (handle-backspace this) nil)

          (or (keys/matches-key? data "delete")
              (keys/matches-key? data (keys/ctrl "d")))
          (do (handle-forward-delete this) nil)

          (keys/matches-key? data (keys/ctrl "-"))
          (do (handle-undo this) nil)

          (keys/matches-key? data (keys/ctrl "z"))
          (do (handle-redo this) nil)

          (or (keys/matches-key? data "tab")
              (keys/matches-key? data (keys/ctrl "i")))
          (do (handle-tab this) nil)

          (keys/matches-key? data (keys/ctrl "]"))
          (do (enter-jump-mode this :forward) nil)

          (keys/matches-key? data (keys/ctrl-shift "]"))
          (do (enter-jump-mode this :backward) nil)

          (keys/matches-key? data (keys/ctrl "u"))
          (do (handle-kill-to-line-start this) nil)

          (keys/matches-key? data (keys/ctrl "k"))
          (do (handle-kill-to-line-end this) nil)

          (keys/matches-key? data (keys/ctrl "w"))
          (do (handle-kill-line this) nil)

          (or (keys/matches-key? data (keys/alt "backspace"))
              (keys/matches-key? data (keys/alt "h")))
          (do (handle-delete-word-backward this) nil)

          (or (keys/matches-key? data (keys/alt "d"))
              (keys/matches-key? data (keys/alt "delete")))
          (do (handle-delete-word-forward this) nil)

          (keys/matches-key? data (keys/ctrl "y"))
          (do (handle-yank this) nil)

          (keys/matches-key? data (keys/alt "y"))
          (do (handle-yank-pop this) nil)

          (keys/matches-key? data "up")
          (do (let [lines (:lines @state-atom)
                    cl (:cursor-line @state-atom)
                    cc (:cursor-col @state-atom)]
                (if (and (zero? cl) (zero? cc)
                         (or (empty? lines) (= (first lines) "")))
                  (history-backward this)
                  (move-cursor-vertical this -1)))
              nil)

          (keys/matches-key? data "down")
          (do (let [lines (:lines @state-atom)
                    cl (:cursor-line @state-atom)
                    cc (:cursor-col @state-atom)
                    last-idx (dec (count lines))
                    line (nth lines cl "")]
                (if (and (= cl last-idx) (>= cc (count line)))
                  (history-forward this)
                  (move-cursor-vertical this 1)))
              nil)

          (keys/matches-key? data (keys/ctrl "p"))
          (do (history-backward this) nil)

          (keys/matches-key? data (keys/ctrl "n"))
          (do (history-forward this) nil)

          (or (keys/matches-key? data "left")
              (keys/matches-key? data (keys/ctrl "b")))
          (do (move-cursor-horizontal this -1) nil)

          (or (keys/matches-key? data "right")
              (keys/matches-key? data (keys/ctrl "f")))
          (do (move-cursor-horizontal this 1) nil)

          (or (keys/matches-key? data "home")
              (keys/matches-key? data (keys/ctrl "a")))
          (do (swap! state-atom assoc :cursor-col 0)
              (reset! preferred-col-atom nil)
              (reset! last-action nil) nil)

          (or (keys/matches-key? data "end")
              (keys/matches-key? data (keys/ctrl "e")))
          (do (let [line (nth (:lines @state-atom) (:cursor-line @state-atom) "")]
                (swap! state-atom assoc :cursor-col (count line))
                (reset! preferred-col-atom nil)
                (reset! last-action nil))
              nil)

          (or (keys/matches-key? data (keys/alt "left"))
              (keys/matches-key? data (keys/ctrl "left"))
              (keys/matches-key? data (keys/alt "b")))
          (do (move-cursor-word-left this) nil)

          (or (keys/matches-key? data (keys/alt "right"))
              (keys/matches-key? data (keys/ctrl "right"))
              (keys/matches-key? data (keys/alt "f")))
          (do (move-cursor-word-right this) nil)

          (keys/matches-key? data "pageUp")
          (do (page-scroll this -1) nil)

          (keys/matches-key? data "pageDown")
          (do (page-scroll this 1) nil)

          (clojure.string/includes? data "\u001b[200~")
          (do (reset! paste-state :buffering)
              (reset! paste-buffer "")
              (let [remaining (clojure.string/replace data "\u001b[200~" "")]
                (when (seq remaining)
                  (protocols/handle-input this remaining)))
              nil)

          (= @paste-state :buffering)
          (do (swap! paste-buffer str data)
              (let [buf @paste-buffer
                    end-idx (clojure.string/index-of buf "\u001b[201~")]
                (when (>= end-idx 0)
                  (let [paste-text (subs buf 0 end-idx)]
                    (handle-paste this paste-text)))
                (reset! paste-state :idle)
                (reset! paste-buffer ""))
              nil)

          :else
          (let [has-ctrl? (some #(let [c (int %)]
                                   (or (< c 32) (== c 127)
                                       (and (>= c 128) (<= c 159))))
                                data)]
            (when-not has-ctrl?
              (insert-character this data)))))))

  (invalidate [_this] nil))

;; ─── Construction ──────────────────────────────────────────────────────────

(defn make-editor
  "Create a new Editor component.
   Options key-value pairs:
     :height  — number of visible lines (default 12)
     :padding-x — horizontal padding (default 0)
     :border-fn — function to style border chars"
  [& {:keys [height padding-x border-fn] :or {height 12 padding-x 0}}]
  (map->Editor {:state-atom (atom (make-editor-state))
                :scroll-offset-atom (atom 0)
                :preferred-col-atom (atom nil)
                :last-width-atom (atom 80)
                :focused? (atom false)
                :on-submit (atom nil)
                :on-change (atom nil)
                :disable-submit (atom false)
                :padding-x (atom padding-x)
                :border-fn (atom border-fn)
                :height-atom (atom height)
                :undo-stack (atom [])
                :redo-stack (atom [])
                :kill-ring (edit/make-kill-ring)
                :last-action (atom nil)
                :paste-buffer (atom "")
                :paste-state (atom :idle)
                :paste-store (atom {})
                :paste-counter (atom 0)
                :jump-mode (atom nil)
                :history (atom [])
                :history-idx (atom -1)
                :autocomplete-provider (atom nil)}))

(defn editor-set-text! [editor text]
  (reset! (:state-atom editor) (make-editor-state text))
  (reset! (:scroll-offset-atom editor) 0)
  (reset! (:preferred-col-atom editor) nil)
  (reset! (:undo-stack editor) [])
  (reset! (:redo-stack editor) [])
  (reset! (:last-action editor) nil)
  (reset! (:jump-mode editor) nil)
  (reset! (:history-idx editor) -1))

(defn editor-set-on-submit! [editor f]
  (reset! (:on-submit editor) f))

(defn editor-set-on-change! [editor f]
  (reset! (:on-change editor) f))

(defn editor-set-on-tab! [editor f]
  (reset! (:autocomplete-provider editor) f))

(defn editor-get-history [editor]
  @(:history editor))

(defn editor-set-history! [editor history]
  (reset! (:history editor) (vec history))
  (reset! (:history-idx editor) -1))

(defn editor-get-paste [editor id]
  (get @(:paste-store editor) id))

(defn editor-set-height! [editor h]
  (reset! (:height-atom editor) h))

(defn editor-get-text-length [editor]
  (count (editor-get-text editor)))

;; ─── IFocusable ─────────────────────────────────────────────────────────────

(extend-type Editor
  protocols/IFocusable
  (focused [this] @(:focused? this))
  (set-focused! [this val] (reset! (:focused? this) val)))
