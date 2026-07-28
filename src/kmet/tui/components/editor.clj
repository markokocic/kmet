(ns kmet.tui.components.editor
  "Multi-line text editor with word-wrap, vertical scrolling, and cursor.
   Port of @earendil-works/pi-tui Editor.
   Phase 2b.1 — Core Editor: multi-line editing, word-wrap, cursor movement,
   vertical scrolling, border, basic editing (typing, backspace, delete,
   enter newline, submit)."
  (:require [kmet.tui.core :as core]
            [kmet.tui.keys :as keys]
            [kmet.tui.utils :as u]))

;; ─── Cursor marker ─────────────────────────────────────────────────────────
(def ^:const CURSOR-MARKER "\u001b_pi:c\u0007")

;; ─── Grapheme helpers (Java BreakIterator) ─────────────────────────────────

(defn- grapheme-left
  "Move cursor one grapheme cluster left."
  [s pos]
  (if (<= pos 0) 0
      (let [bi (java.text.BreakIterator/getCharacterInstance)]
        (.setText bi s)
        (let [prev (.preceding bi pos)]
          (if (== prev java.text.BreakIterator/DONE) 0 prev)))))

(defn- grapheme-right
  "Move cursor one grapheme cluster right."
  [s pos]
  (if (>= pos (count s)) (count s)
      (let [bi (java.text.BreakIterator/getCharacterInstance)]
        (.setText bi s)
        (let [nxt (.following bi pos)]
          (if (== nxt java.text.BreakIterator/DONE) (count s) nxt)))))

(defn- grapheme-at
  "Return the grapheme cluster at cursor position (or empty string if at end)."
  [s pos]
  (if (>= pos (count s)) ""
      (let [bi (java.text.BreakIterator/getCharacterInstance)]
        (.setText bi s)
        (let [nxt (.following bi pos)]
          (subs s pos (if (== nxt java.text.BreakIterator/DONE) (count s) nxt))))))

(defn- grapheme-segments
  "Return a vector of {:text str :start idx} for each grapheme cluster in s."
  [s]
  (if (empty? s) []
      (let [bi (java.text.BreakIterator/getCharacterInstance)]
        (.setText bi s)
        (loop [seg [] pos (.first bi)]
          (if (== pos java.text.BreakIterator/DONE) seg
              (let [nxt (.next bi)]
                (if (== nxt java.text.BreakIterator/DONE)
                  (conj seg {:text (subs s pos) :start pos})
                  (recur (conj seg {:text (subs s pos nxt) :start pos}) nxt))))))))

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
        (let [segments (grapheme-segments line)
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

;; ─── Public helpers (needed before internal helpers reference them) ────────

(defn editor-get-text [editor]
  (clojure.string/join "\n" (:lines @(:state-atom editor))))

;; ─── Internal helpers (defined before defrecord) ───────────────────────────

(defn- insert-character [editor char]
  (let [state @(:state-atom editor)
        lines (:lines state)
        cl (:cursor-line state)
        cc (:cursor-col state)
        line (or (nth lines cl) "")]
    (swap! (:state-atom editor) assoc
      :lines (assoc lines cl (str (subs line 0 cc) char (subs line cc)))
      :cursor-col (+ cc (count char)))
    (when-let [cb @(:on-change editor)] (cb (editor-get-text editor)))))

(defn- handle-backspace [editor]
  (let [state @(:state-atom editor)
        lines (:lines state)
        cl (:cursor-line state)
        cc (:cursor-col state)]
    (if (> cc 0)
      (let [line (or (nth lines cl) "")
            glen (grapheme-left line cc)]
        (swap! (:state-atom editor) assoc
          :lines (assoc lines cl (str (subs line 0 glen) (subs line cc)))
          :cursor-col glen))
      (when (> cl 0)
        (let [prev-line (or (nth lines (dec cl)) "")
              cur-line (or (nth lines cl) "")]
          (swap! (:state-atom editor) assoc
            :lines (vec (concat (subvec lines 0 (dec cl))
                                [(str prev-line cur-line)]
                                (subvec lines (inc cl))))
            :cursor-line (dec cl)
            :cursor-col (count prev-line)))))
    (when-let [cb @(:on-change editor)] (cb (editor-get-text editor)))))

(defn- handle-forward-delete [editor]
  (let [state @(:state-atom editor)
        lines (:lines state)
        cl (:cursor-line state)
        cc (:cursor-col state)
        line (or (nth lines cl) "")]
    (if (< cc (count line))
      (let [nxt (grapheme-right line cc)]
        (swap! (:state-atom editor) assoc
          :lines (assoc lines cl (str (subs line 0 cc) (subs line nxt)))))
      (when (< cl (dec (count lines)))
        (let [next-line (or (nth lines (inc cl)) "")]
          (swap! (:state-atom editor) assoc
            :lines (vec (concat (subvec lines 0 (inc cl))
                                [(str line next-line)]
                                (subvec lines (+ cl 2))))
            :cursor-col cc))))
    (when-let [cb @(:on-change editor)] (cb (editor-get-text editor)))))

(defn- add-new-line [editor]
  (let [state @(:state-atom editor)
        lines (:lines state)
        cl (:cursor-line state)
        cc (:cursor-col state)
        line (or (nth lines cl) "")]
    (swap! (:state-atom editor) assoc
      :lines (vec (concat (subvec lines 0 cl)
                          [(subs line 0 cc)]
                          [(subs line cc)]
                          (subvec lines (inc cl))))
      :cursor-line (inc cl)
      :cursor-col 0)
    (reset! (:preferred-col-atom editor) nil)
    (when-let [cb @(:on-change editor)] (cb (editor-get-text editor)))))

(defn- move-cursor-horizontal [editor dir]
  (let [state @(:state-atom editor)
        lines (:lines state)
        cl (:cursor-line state)
        cc (:cursor-col state)
        line (or (nth lines cl) "")]
    (reset! (:preferred-col-atom editor) nil)
    (if (neg? dir)
      (if (> cc 0)
        (swap! (:state-atom editor) assoc :cursor-col (grapheme-left line cc))
        (when (> cl 0)
          (let [prev-line (or (nth lines (dec cl)) "")]
            (swap! (:state-atom editor) assoc
              :cursor-line (dec cl)
              :cursor-col (count prev-line)))))
      (if (< cc (count line))
        (swap! (:state-atom editor) assoc :cursor-col (grapheme-right line cc))
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
        terminal-rows 24
        max-visible (max 5 (quot terminal-rows 10))
        page-size (max 1 (dec max-visible))]
    (dotimes [_ page-size]
      (move-cursor-vertical editor dir))))

;; ─── Editor component ──────────────────────────────────────────────────────

(defrecord Editor [state-atom scroll-offset-atom preferred-col-atom
                   last-width-atom focused? on-submit on-change
                   disable-submit padding-x border-fn]
  core/IComponent

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
          terminal-rows 24
          max-visible (max 5 (quot terminal-rows 10))
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
        (doseq [vl visible]
          (let [vl-has-cursor (and (= (:logical-line vl) cursor-line)
                                   (>= cursor-col (:start-col vl))
                                   (< cursor-col (+ (:start-col vl) (:length vl))))
                vl-cursor-col (- cursor-col (:start-col vl))
                display-text (:text vl)
                line-width (u/visible-width display-text)
                cur-fn (fn [t]
                         (str left-pad t
                              (apply str (repeat (max 0 (- content-width line-width)) \space))
                              right-pad))]
            (if (and vl-has-cursor (>= vl-cursor-col 0) (<= vl-cursor-col (count display-text)))
              (let [before (subs display-text 0 vl-cursor-col)
                    at-cursor (grapheme-at display-text vl-cursor-col)
                    after (subs display-text
                                (min (count display-text) (+ vl-cursor-col (count at-cursor))))]
                (vswap! result conj
                  (cur-fn (str before
                               (when @focused? CURSOR-MARKER)
                               "\u001b[7m" (if (empty? at-cursor) " " at-cursor) "\u001b[0m"
                               after))))
              (vswap! result conj (cur-fn display-text)))))
        ;; Bottom border
        (let [remaining (- (count visual-lines) (+ scroll-offset (count visible)))]
          (if (pos? remaining)
            (vswap! result conj (str "─── ↓ " remaining " more "
                                      (apply str (repeat (max 0 (- width 12)) "─"))))
            (vswap! result conj (apply str (repeat width bdr)))))
        @result)))

  (handle-input [this data]
    (let [state @state-atom
          lines (:lines state)
          cursor-line (:cursor-line state)
          cursor-col (:cursor-col state)]
      (cond
        (keys/matches-key? data "enter")
        (do (when-let [cb @on-submit] (cb (clojure.string/join "\n" lines))) nil)
        (or (keys/matches-key? data (keys/shift "enter"))
            (keys/matches-key? data (keys/ctrl "enter"))
            (keys/matches-key? data (keys/alt "enter"))
            (keys/matches-key? data (keys/ctrl "j")))
        (do (add-new-line this) nil)
        (or (keys/matches-key? data "backspace")
            (keys/matches-key? data (keys/ctrl "h")))
        (do (handle-backspace this) nil)
        (or (keys/matches-key? data "delete")
            (keys/matches-key? data (keys/ctrl "d")))
        (do (handle-forward-delete this) nil)
        (keys/matches-key? data "up")
        (do (move-cursor-vertical this -1) nil)
        (keys/matches-key? data "down")
        (do (move-cursor-vertical this 1) nil)
        (or (keys/matches-key? data "left")
            (keys/matches-key? data (keys/ctrl "b")))
        (do (move-cursor-horizontal this -1) nil)
        (or (keys/matches-key? data "right")
            (keys/matches-key? data (keys/ctrl "f")))
        (do (move-cursor-horizontal this 1) nil)
        (or (keys/matches-key? data "home")
            (keys/matches-key? data (keys/ctrl "a")))
        (do (swap! state-atom assoc :cursor-col 0)
            (reset! preferred-col-atom nil) nil)
        (or (keys/matches-key? data "end")
            (keys/matches-key? data (keys/ctrl "e")))
        (do (let [line (nth (:lines @state-atom) (:cursor-line @state-atom) "")]
              (swap! state-atom assoc :cursor-col (count line))
              (reset! preferred-col-atom nil))
            nil)
        (keys/matches-key? data "pageUp")
        (do (page-scroll this -1) nil)
        (keys/matches-key? data "pageDown")
        (do (page-scroll this 1) nil)
        :else
        (let [has-ctrl? (some #(let [c (int %)]
                                 (or (< c 32) (== c 127)
                                     (and (>= c 128) (<= c 159))))
                              data)]
          (when-not has-ctrl? (insert-character this data))))))

  (invalidate [_this] nil))

;; ─── Construction ──────────────────────────────────────────────────────────

(defn make-editor
  "Create a new Editor component."
  []
  (map->Editor {:state-atom (atom (make-editor-state))
                :scroll-offset-atom (atom 0)
                :preferred-col-atom (atom nil)
                :last-width-atom (atom 80)
                :focused? (atom false)
                :on-submit (atom nil)
                :on-change (atom nil)
                :disable-submit (atom false)
                :padding-x (atom 0)
                :border-fn (atom nil)}))

(defn editor-set-text! [editor text]
  (reset! (:state-atom editor) (make-editor-state text))
  (reset! (:scroll-offset-atom editor) 0)
  (reset! (:preferred-col-atom editor) nil))

(defn editor-set-on-submit! [editor f]
  (reset! (:on-submit editor) f))

(defn editor-set-on-change! [editor f]
  (reset! (:on-change editor) f))

;; ─── IFocusable ─────────────────────────────────────────────────────────────

(extend-type Editor
  core/IFocusable
  (focused [this] @(:focused? this))
  (set-focused! [this val] (reset! (:focused? this) val)))
