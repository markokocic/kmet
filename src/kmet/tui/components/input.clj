(ns kmet.tui.components.input
  "Single-line text input with horizontal scrolling and cursor.
   Port of @earendil-works/pi-tui Input."
  (:require [kmet.tui.protocols :as protocols]
            [kmet.tui.keys :as keys]
            [kmet.tui.utils :as u]))

;; ─── Cursor marker ─────────────────────────────────────────────────────────
;; Zero-width APC sequence emitted at cursor position for IME positioning.
;; TUI finds this marker and positions the hardware cursor there.
(def ^:const CURSOR-MARKER "\u001b_pi:c\u0007")

;; ─── Grapheme helpers (Java BreakIterator) ─────────────────────────────────

(defn- grapheme-left
  "Move cursor one grapheme cluster left."
  [s pos]
  (if (<= pos 0)
    0
    (let [bi (java.text.BreakIterator/getCharacterInstance)]
      (.setText bi s)
      (let [prev (.preceding bi pos)]
        (if (== prev java.text.BreakIterator/DONE) 0 prev)))))

(defn- grapheme-right
  "Move cursor one grapheme cluster right."
  [s pos]
  (if (>= pos (count s))
    (count s)
    (let [bi (java.text.BreakIterator/getCharacterInstance)]
      (.setText bi s)
      (let [nxt (.following bi pos)]
        (if (== nxt java.text.BreakIterator/DONE)
          (count s)
          nxt)))))

(defn- grapheme-at
  "Return the grapheme cluster at cursor position (or space if at end)."
  [s pos]
  (if (>= pos (count s))
    " "
    (let [bi (java.text.BreakIterator/getCharacterInstance)]
      (.setText bi s)
      (let [nxt (.following bi pos)]
        (subs s pos (if (== nxt java.text.BreakIterator/DONE) (count s) nxt))))))

;; ─── Kill ring ──────────────────────────────────────────────────────────────

(defrecord KillRing [entries])

(defn make-kill-ring []
  (map->KillRing {:entries (atom [])}))

(defn- kill-ring-push [kr text & {:keys [prepend accumulate]}]
  (when (seq text)
    (swap! (:entries kr)
      (fn [es]
        (if (and accumulate (seq es))
          (let [last (peek es)]
            (conj (vec (butlast es))
                  (if prepend (str text last) (str last text))))
          (conj (vec es) text))))))

(defn- kill-ring-peek [kr]
  (peek @(:entries kr)))

(defn- kill-ring-rotate [kr]
  (swap! (:entries kr)
    (fn [es]
      (if (> (count es) 1)
        (into [(peek es)] (vec (butlast es)))
        es))))

(defn- kill-ring-length [kr]
  (count @(:entries kr)))

;; ─── Undo stack ─────────────────────────────────────────────────────────────

(defrecord UndoStack [stack])

(defn make-undo-stack []
  (map->UndoStack {:stack (atom [])}))

(defn- undo-push [us value]
  (swap! (:stack us) conj value))

(defn- undo-pop [us]
  (let [s @(:stack us)]
    (when (seq s)
      (let [snapshot (peek s)]
        (swap! (:stack us) pop)
        snapshot))))

;; ─── Word navigation helpers ────────────────────────────────────────────────

(defn- word-boundary-left [text pos]
  "Find cursor position after moving one word backward.
   Skips whitespace, then skips either a word or a punctuation run.
   After skipping punctuation, continues past whitespace to land at
   the start of the preceding word."
  (let [n (count text)
        pos (min pos n)]
    (if (<= pos 0)
      0
      (let [before (subs text 0 pos)
            no-trail (clojure.string/replace before #"\s+$" "")
            trimmed (count no-trail)]
        (if (zero? trimmed)
          0
          (let [last-char (subs no-trail (dec trimmed))
                word-char? (boolean (re-find #"^\w" last-char))]
            (loop [i (dec trimmed)]
              (if (<= i 0)
                0
                (let [c (subs text i (inc i))
                      is-word (re-find #"^\w" c)
                      is-space (re-find #"^\s" c)]
                  (cond
                    is-space (if word-char?
                               ;; Word: stop at start of space
                               (inc i)
                               ;; Punctuation: skip past space too
                               (recur (dec i)))
                    word-char? (if is-word
                                 (recur (dec i))
                                 (inc i))
                    ;; Not word char, not space (punctuation)
                    :else (if is-word
                            (inc i)
                            (recur (dec i)))))))))))))

(defn- word-boundary-right [text pos]
  "Find cursor position after moving one word forward."
  (let [n (count text)]
    (if (>= pos n)
      n
      (let [bi (java.text.BreakIterator/getWordInstance)]
        (.setText bi text)
        (loop [p pos]
          (let [nxt (.following bi p)]
            (if (== nxt java.text.BreakIterator/DONE)
              n
              (let [c (subs text p (min (inc p) n))]
                (if (re-find #"^\s" c)
                  (recur nxt)
                  nxt)))))))))

;; ─── Input action helpers ───────────────────────────────────────────────────
;; Defined before defrecord so method bodies can reference them.

(defn- insert-character [input char]
  (let [value @(:value-atom input)
        cursor @(:cursor-atom input)]
    (when (or (re-find #"^\s" char)
              (not= @(:last-action input) :type-word))
      (undo-push (:undo-stack input) {:value value :cursor cursor}))
    (reset! (:last-action input) :type-word)
    (let [new-val (str (subs value 0 cursor) char (subs value cursor))
          new-cursor (+ cursor (count char))]
      (reset! (:value-atom input) new-val)
      (reset! (:cursor-atom input) new-cursor))))

(defn- handle-backspace [input]
  (let [value @(:value-atom input)
        cursor @(:cursor-atom input)]
    (reset! (:last-action input) nil)
    (when (> cursor 0)
      (undo-push (:undo-stack input) {:value value :cursor cursor})
      (let [glen (grapheme-left value cursor)]
        (reset! (:value-atom input)
          (str (subs value 0 glen) (subs value cursor)))
        (reset! (:cursor-atom input) glen)))))

(defn- handle-forward-delete [input]
  (let [value @(:value-atom input)
        cursor @(:cursor-atom input)]
    (reset! (:last-action input) nil)
    (when (< cursor (count value))
      (undo-push (:undo-stack input) {:value value :cursor cursor})
      (let [nxt (grapheme-right value cursor)]
        (reset! (:value-atom input)
          (str (subs value 0 cursor) (subs value nxt)))))))

(defn- delete-to-line-start [input]
  (let [value @(:value-atom input)
        cursor @(:cursor-atom input)]
    (when (pos? cursor)
      (undo-push (:undo-stack input) {:value value :cursor cursor})
      (let [deleted (subs value 0 cursor)]
        (kill-ring-push (:kill-ring input) deleted :prepend true
                        :accumulate (= @(:last-action input) :kill))
        (reset! (:last-action input) :kill)
        (reset! (:value-atom input) (subs value cursor))
        (reset! (:cursor-atom input) 0)))))

(defn- delete-to-line-end [input]
  (let [value @(:value-atom input)
        cursor @(:cursor-atom input)]
    (when (< cursor (count value))
      (undo-push (:undo-stack input) {:value value :cursor cursor})
      (let [deleted (subs value cursor)]
        (kill-ring-push (:kill-ring input) deleted :prepend false
                        :accumulate (= @(:last-action input) :kill))
        (reset! (:last-action input) :kill)
        (reset! (:value-atom input) (subs value 0 cursor))))))

(defn- delete-word-backwards [input]
  (let [value @(:value-atom input)
        cursor @(:cursor-atom input)]
    (when (pos? cursor)
      (let [was-kill (= @(:last-action input) :kill)]
        (undo-push (:undo-stack input) {:value value :cursor cursor})
        (let [old-cursor cursor
              new-cursor (word-boundary-left value cursor)]
          (let [deleted (subs value new-cursor old-cursor)]
            (kill-ring-push (:kill-ring input) deleted :prepend true
                            :accumulate was-kill)
            (reset! (:last-action input) :kill)
            (reset! (:value-atom input)
              (str (subs value 0 new-cursor) (subs value old-cursor)))
            (reset! (:cursor-atom input) new-cursor)))))))

(defn- delete-word-forward [input]
  (let [value @(:value-atom input)
        cursor @(:cursor-atom input)]
    (when (< cursor (count value))
      (let [was-kill (= @(:last-action input) :kill)]
        (undo-push (:undo-stack input) {:value value :cursor cursor})
        (let [old-cursor cursor
              new-cursor (word-boundary-right value cursor)]
          (let [deleted (subs value old-cursor new-cursor)]
            (kill-ring-push (:kill-ring input) deleted :prepend false
                            :accumulate was-kill)
            (reset! (:last-action input) :kill)
            (reset! (:value-atom input)
              (str (subs value 0 old-cursor) (subs value new-cursor)))))))))

(defn- yank-action [input]
  (let [value @(:value-atom input)
        cursor @(:cursor-atom input)
        text (kill-ring-peek (:kill-ring input))]
    (when text
      (undo-push (:undo-stack input) {:value value :cursor cursor})
      (let [new-val (str (subs value 0 cursor) text (subs value cursor))]
        (reset! (:value-atom input) new-val)
        (reset! (:cursor-atom input) (+ cursor (count text)))
        (reset! (:last-action input) :yank)))))

(defn- yank-pop-action [input]
  (let [value @(:value-atom input)
        cursor @(:cursor-atom input)
        kr (:kill-ring input)]
    (when (and (= @(:last-action input) :yank)
               (> (kill-ring-length kr) 1))
      (undo-push (:undo-stack input) {:value value :cursor cursor})
      (let [prev-text (or (kill-ring-peek kr) "")]
        (let [new-val (str (subs value 0 (- cursor (count prev-text)))
                           (subs value cursor))]
          (reset! (:value-atom input) new-val)
          (reset! (:cursor-atom input) (- cursor (count prev-text))))
        (kill-ring-rotate kr)
        (let [text (or (kill-ring-peek kr) "")]
          (let [new-cursor @(:cursor-atom input)]
            (let [new-val (str (subs @(:value-atom input) 0 new-cursor)
                               text
                               (subs @(:value-atom input) new-cursor))]
              (reset! (:value-atom input) new-val)
              (reset! (:cursor-atom input) (+ new-cursor (count text)))
              (reset! (:last-action input) :yank))))))))

;; ─── Render helper ──────────────────────────────────────────────────────────

(defn- render-line
  "Render a single line of input with cursor marker at cursor position.
   Returns the line string WITHOUT the prompt."
  [value cursor focused? scrolled?]
  (let [at-end? (>= cursor (count value))
        at-char (if at-end? " " (grapheme-at value cursor))
        char-len (count at-char)
        before (subs value 0 cursor)
        after (subs value (min (count value) (+ cursor char-len)))]
    (str before
         (when focused? CURSOR-MARKER)
         "\u001b[7m" at-char "\u001b[27m"
         after)))

;; ─── Input component ────────────────────────────────────────────────────────

(defrecord Input [value-atom cursor-atom on-submit on-escape focused?
                  paste-buffer paste-state kill-ring last-action undo-stack]
  protocols/IComponent

  (render [_this width]
    (let [prompt "> "
          prompt-len (count prompt)
          available (- width prompt-len)]
      (if (<= available 0)
        [prompt]
        (let [value @value-atom
              cursor @cursor-atom
              total-width (u/visible-width value)]
          (if (< total-width available)
            ;; Everything fits
            (let [line (render-line value cursor @focused? false)
                  padding (apply str (repeat (max 0 (- available (u/visible-width line))) \space))]
              [(str prompt line padding)])
            ;; Need horizontal scrolling
            (let [scroll-width (if (== cursor (count value))
                                 (max 1 (dec available))
                                 available)
                  cursor-col (u/visible-width (subs value 0 cursor))
                  total (u/visible-width value)]
              (if (<= scroll-width 0)
                [(str prompt (apply str (repeat available \space)))]
                (let [half (quot scroll-width 2)
                      start-col (cond
                                  (< cursor-col half) 0
                                  (> cursor-col (- total half))
                                  (max 0 (- total scroll-width))
                                  :else (max 0 (- cursor-col half)))
                      visible-text (u/slice-by-column value start-col scroll-width true)
                      before-txt (subs (u/slice-by-column value start-col (max 0 (- cursor-col start-col)) true) 0)
                      cursor-display (u/visible-width before-txt)
                      line (render-line visible-text cursor-display @focused? true)
                      padding (apply str (repeat (max 0 (- available (u/visible-width line))) \space))]
                  [(str prompt line padding)]))))))))

  (handle-input [this data]
    (let [value @value-atom
          cursor @cursor-atom]
      (cond
        ;; Paste start marker
        (.contains data "\u001b[200~")
        (do (reset! paste-state :buffering)
            (reset! paste-buffer "")
            (let [remaining (.replace data "\u001b[200~" "")]
              (when (seq remaining)
                (protocols/handle-input this remaining)))
            nil)

        ;; Inside paste buffer
        (= @paste-state :buffering)
        (do (swap! paste-buffer str data)
            (let [buf @paste-buffer
                  end-idx (.indexOf buf "\u001b[201~")]
              (when (>= end-idx 0)
                (let [paste-text (subs buf 0 end-idx)
                      clean (clojure.string/replace paste-text #"\r\n|\r|\n" " ")
                      clean (clojure.string/replace clean "\t" "    ")]
                  (undo-push undo-stack {:value value :cursor cursor})
                  (reset! last-action nil)
                  (reset! value-atom (str (subs value 0 cursor) clean (subs value cursor)))
                  (reset! cursor-atom (+ cursor (count clean)))))
              (reset! paste-state :idle)
              (reset! paste-buffer ""))
            nil)

        ;; Escape / Cancel
        (or (keys/matches-key? data "escape")
            (keys/matches-key? data (keys/ctrl "c")))
        (do (when-let [cb @on-escape] (cb)) nil)

        ;; Undo
        (keys/matches-key? data (keys/ctrl "-"))
        (do (when-let [snapshot (undo-pop undo-stack)]
              (reset! value-atom (:value snapshot))
              (reset! cursor-atom (:cursor snapshot))
              (reset! last-action nil))
            nil)

        ;; Submit
        (keys/matches-key? data "enter")
        (do (when-let [cb @on-submit] (cb @value-atom)) nil)

        ;; Backspace
        (or (keys/matches-key? data "backspace")
            (keys/matches-key? data (keys/ctrl "h")))
        (do (handle-backspace this) nil)

        ;; Forward delete
        (or (keys/matches-key? data "delete")
            (keys/matches-key? data (keys/ctrl "d")))
        (do (handle-forward-delete this) nil)

        ;; Delete word backward
        (or (keys/matches-key? data (keys/ctrl "w"))
            (keys/matches-key? data (keys/alt "backspace")))
        (do (delete-word-backwards this) nil)

        ;; Delete word forward
        (or (keys/matches-key? data (keys/alt "d"))
            (keys/matches-key? data (keys/alt "delete")))
        (do (delete-word-forward this) nil)

        ;; Delete to line start
        (keys/matches-key? data (keys/ctrl "u"))
        (do (delete-to-line-start this) nil)

        ;; Delete to line end
        (keys/matches-key? data (keys/ctrl "k"))
        (do (delete-to-line-end this) nil)

        ;; Yank
        (keys/matches-key? data (keys/ctrl "y"))
        (do (yank-action this) nil)

        ;; Yank pop
        (keys/matches-key? data (keys/alt "y"))
        (do (yank-pop-action this) nil)

        ;; Cursor left
        (or (keys/matches-key? data "left")
            (keys/matches-key? data (keys/ctrl "b")))
        (do (reset! last-action nil)
            (reset! cursor-atom (grapheme-left value cursor))
            nil)

        ;; Cursor right
        (or (keys/matches-key? data "right")
            (keys/matches-key? data (keys/ctrl "f")))
        (do (reset! last-action nil)
            (reset! cursor-atom (grapheme-right value cursor))
            nil)

        ;; Cursor line start
        (or (keys/matches-key? data "home")
            (keys/matches-key? data (keys/ctrl "a")))
        (do (reset! last-action nil)
            (reset! cursor-atom 0)
            nil)

        ;; Cursor line end
        (or (keys/matches-key? data "end")
            (keys/matches-key? data (keys/ctrl "e")))
        (do (reset! last-action nil)
            (reset! cursor-atom (count @value-atom))
            nil)

        ;; Cursor word left
        (or (keys/matches-key? data (keys/alt "left"))
            (keys/matches-key? data (keys/ctrl "left"))
            (keys/matches-key? data (keys/alt "b")))
        (do (reset! last-action nil)
            (reset! cursor-atom (word-boundary-left @value-atom @cursor-atom))
            nil)

        ;; Cursor word right
        (or (keys/matches-key? data (keys/alt "right"))
            (keys/matches-key? data (keys/ctrl "right"))
            (keys/matches-key? data (keys/alt "f")))
        (do (reset! last-action nil)
            (reset! cursor-atom (word-boundary-right @value-atom @cursor-atom))
            nil)

        ;; Regular character input (reject control chars)
        :else
        (let [has-ctrl? (some #(let [c (int %)]
                                 (or (< c 32) (== c 127)
                                     (and (>= c 128) (<= c 159))))
                              data)]
          (when-not has-ctrl?
            (insert-character this data))))))

  (invalidate [_this] nil))

;; ─── Construction ──────────────────────────────────────────────────────────

(defn make-input
  "Create a new Input component."
  []
  (map->Input {:value-atom (atom "")
               :cursor-atom (atom 0)
               :on-submit (atom nil)
               :on-escape (atom nil)
               :focused? (atom false)
               :paste-buffer (atom "")
               :paste-state (atom :idle)
               :kill-ring (make-kill-ring)
               :last-action (atom nil)
               :undo-stack (make-undo-stack)}))

(defn input-set-value! [input value]
  (reset! (:value-atom input) value)
  (reset! (:cursor-atom input) (min (count value) @(:cursor-atom input))))

(defn input-get-value [input]
  @(:value-atom input))

(defn input-set-on-submit! [input f]
  (reset! (:on-submit input) f))

(defn input-set-on-escape! [input f]
  (reset! (:on-escape input) f))

;; ─── IFocusable ─────────────────────────────────────────────────────────────

(extend-type Input
  protocols/IFocusable
  (focused [this] @(:focused? this))
  (set-focused! [this val] (reset! (:focused? this) val)))
