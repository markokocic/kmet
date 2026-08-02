(ns kmet.libs.markdown
  "Pure Markdown tokenizer: text -> AST.

   Self-contained (stdlib only, no kmet.* deps, no ANSI, no width). The TUI
   renderer (kmet.tui.components.markdown) walks the AST and applies theme,
   padding, and word-wrap. Port of the parsing half of @earendil-works/pi-tui
   Markdown (which uses `marked`); this is a line-oriented CommonMark subset
   with GFM tables and fenced code blocks (including inside list items)."
  (:require [clojure.string :as str]))

;; ─── Line type detection ───────────────────────────────────────────────────

(def ^:private heading-re #"^(#{1,6})\s+(.*)$")
(def ^:private code-fence-re #"^```\s*(\S*)$")
(def ^:private quote-re #"^>\s?(.*)$")
(def ^:private ul-re #"^[\s]*[-*+]\s+(.*)$")
(def ^:private ol-re #"^[\s]*\d+\.\s+(.*)$")
(def ^:private hr-re #"^([-*_])(?:[ \t]*\1){2,}$")
(def ^:private empty-re #"^\s*$")
(def ^:private sep-cell-re #":?-+:?")
(def ^:private escaped-pipe-re #"\\\|")

;; ─── Inline tokenizer ──────────────────────────────────────────────────────

(defn- append-token
  "Conj TOK onto RESULT, merging adjacent :text tokens for a cleaner AST."
  [result tok]
  (if (and (= (:type tok) :text)
           (seq result)
           (= (:type (peek result)) :text))
    (conj (pop result) {:type :text :s (str (:s (peek result)) (:s tok))})
    (conj result tok)))

(defn parse-inline
  "Parse inline formatting in LINE into a vector of inline tokens.
   Token types: :text (with :s), :strong/:em/:del (with nested :content),
   :code (with :s), :link (with :url and :text inline tokens)."
  [line]
  (if (or (empty? line) (nil? line))
    []
    (loop [i 0, n (count line), result []]
      (if (>= i n)
        result
        (let [c (subs line i (inc i))
              remaining (subs line i)]
          (cond
            ;; Code span: `code`
            (= c "`")
            (let [end (or (str/index-of remaining "`" 1) -1)]
              (if (>= end 0)
                (recur (+ i end 1) n
                       (append-token result {:type :code :s (subs line (inc i) (+ i end))}))
                (recur (inc i) n (append-token result {:type :text :s c}))))

            ;; Bold: **text** (end > 2: the closing ** must not start at the
            ;; content position — degenerate runs like **** stay literal)
            (and (= c "*") (< (inc i) n) (= (nth line (inc i)) \*))
            (let [end (or (str/index-of remaining "**" 2) -1)]
              (if (> end 2)
                (recur (+ i end 2) n
                       (append-token result {:type :strong
                                             :content (parse-inline (subs line (+ i 2) (+ i end)))}))
                (recur (inc i) n (append-token result {:type :text :s c}))))

            ;; Italic: *text* (end > 1: needs content between the markers)
            (= c "*")
            (let [end (or (str/index-of remaining "*" 1) -1)]
              (if (> end 1)
                (recur (+ i end 1) n
                       (append-token result {:type :em
                                             :content (parse-inline (subs line (inc i) (+ i end)))}))
                (recur (inc i) n (append-token result {:type :text :s c}))))

            ;; Strikethrough: ~~text~~ (GitHub flavored; end > 2: needs content)
            (and (= c "~") (>= n (+ i 2)) (= (subs line (inc i) (+ i 2)) "~"))
            (let [end (or (str/index-of remaining "~~" 2) -1)]
              (if (> end 2)
                (recur (+ i end 2) n
                       (append-token result {:type :del
                                             :content (parse-inline (subs line (+ i 2) (+ i end)))}))
                (recur (inc i) n (append-token result {:type :text :s c}))))

            ;; Link: [text](url)
            (= c "[")
            (let [close-b (or (str/index-of remaining "]") -1)]
              (if (>= close-b 0)
                (let [paren (or (str/index-of remaining "(" close-b) -1)]
                  (if (and (>= paren 0) (= paren (inc close-b)))
                    (let [close-p (or (str/index-of remaining ")" paren) -1)]
                      (if (>= close-p 0)
                        (let [text (subs line (inc i) (+ i close-b))
                              url (subs line (+ i paren 1) (+ i close-p))]
                          (recur (+ i close-p 1) n
                                 (append-token result {:type :link :url url :text (parse-inline text)})))
                        (recur (inc i) n (append-token result {:type :text :s c}))))
                    (recur (inc i) n (append-token result {:type :text :s c}))))
                (recur (inc i) n (append-token result {:type :text :s c}))))

            :else
            (recur (inc i) n (append-token result {:type :text :s c}))))))))

;; ─── List nesting state ──────────────────────────────────────────────────────
;; Lists are built on an indent-based stack: each entry is {:indent n
;; :list {:type :ul/:ol :items [...]}}. Deeper indentation opens a nested list
;; under the innermost list's last item; equal indentation appends a sibling
;; item; shallower indentation pops the stack. Blank lines become :blank
;; pseudo-items (loose lists stay one token); indented non-list lines become
;; additional :content lines of the last real item (continuation); indented
;; code fences become :blocks of the last real item.

(defn- leading-indent
  "Width of LINE's leading whitespace (tab = 4 columns, CommonMark-ish)."
  [line]
  (loop [i 0, w 0]
    (if (>= i (count line))
      w
      (case (nth line i)
        \space (recur (inc i) (inc w))
        \tab (recur (inc i) (+ w 4))
        w))))

(defn- strip-indent
  "Remove up to INDENT columns of leading whitespace from LINE (tab = 4)."
  [indent line]
  (loop [i 0, w 0]
    (if (or (>= i (count line)) (>= w indent))
      (subs line i)
      (case (nth line i)
        \space (recur (inc i) (inc w))
        \tab (recur (inc i) (+ w 4))
        (subs line i)))))

(defn- close-list!
  "Pop the innermost list from STACK: attach it to the parent list's last
   item (as a :blocks entry) when a parent exists, else conj it onto RESULT."
  [stack result]
  (let [list-tok (:list (peek @stack))]
    (vswap! stack pop)
    (if (seq @stack)
      (let [parent (peek @stack)
            parent-list (:list parent)
            idx (dec (count (:items parent-list)))
            parent-pos (dec (count @stack))]
        (vswap! stack update-in [parent-pos :list :items idx :blocks]
                (fnil conj []) list-tok))
      (vswap! result conj list-tok))))

(defn- close-lists-above!
  "Close lists whose indent is strictly greater than INDENT."
  [stack result indent]
  (loop []
    (when (and (seq @stack) (> (:indent (peek @stack)) indent))
      (close-list! stack result)
      (recur))))

(defn- close-all-lists!
  "Close every open list onto RESULT (non-list content or end of input)."
  [stack result]
  (loop []
    (when (seq @stack)
      (close-list! stack result)
      (recur))))

(defn- list-type
  ":ul or :ol for a TRIMmed list line."
  [trimmed]
  (if (re-matches ul-re trimmed) :ul :ol))

(defn- make-item
  "A list item holding one content line's inline tokens."
  [inline-tokens]
  {:content [inline-tokens]})

(defn- innermost-list
  "The list token being built on top of the STACK."
  [stack]
  (:list (peek @stack)))

(defn- last-real-item-idx
  "Index of the last non-:blank item in the innermost list, or nil."
  [stack]
  (let [items (:items (innermost-list stack))]
    (loop [i (dec (count items))]
      (cond
        (neg? i) nil
        (= :blank (:type (nth items i))) (recur (dec i))
        :else i))))

(defn- attach-block!
  "Attach BLOCK (a nested list or :code token) to the innermost list's last
   real item's :blocks."
  [stack block]
  (when-let [idx (last-real-item-idx stack)]
    (vswap! stack update-in [(dec (count @stack)) :list :items idx :blocks]
            (fnil conj []) block)))

(defn- add-list-line!
  "Fold one list source line into the STACK: sibling item, nested list, or a
   new root list depending on INDENT. RESULT receives closed root lists.
   A marker-type change at the current indent closes the list first, so the
   new list starts fresh (CommonMark: marker change starts a new list)."
  [stack result indent trimmed]
  (close-lists-above! stack result indent)
  (let [type (list-type trimmed)
        [_ content] (re-find (if (= type :ul) ul-re ol-re) trimmed)
        item (make-item (parse-inline content))
        top-indent (:indent (peek @stack))
        top-type (:type (innermost-list stack))]
    (cond
      (empty? @stack)
      (vswap! stack conj {:indent indent :list {:type type :items [item]}})

      (= top-indent indent)
      (if (= type top-type)
        ;; same level, same type: append a sibling item
        (vswap! stack update-in [(dec (count @stack)) :list :items] conj item)
        ;; same level, marker-type change: close this list, start a new one
        (do (close-list! stack result)
            (vswap! stack conj {:indent indent :list {:type type :items [item]}})))

      :else
      ;; deeper: open a nested list under the innermost list's last real item
      (vswap! stack conj {:indent indent :list {:type type :items [item]}}))))

(defn- add-blank-line!
  "A blank line inside an open list becomes a :blank pseudo-item; with no
   open list it is a root :blank block token."
  [stack result]
  (if (seq @stack)
    (vswap! stack update-in [(dec (count @stack)) :list :items]
            conj {:type :blank})
    (vswap! result conj {:type :blank})))

(defn- add-continuation!
  "An indented non-list line continues the innermost list's last real item:
   its inline tokens (leading whitespace stripped — the renderer supplies
   alignment) are appended to the item's :content as a new line."
  [stack line]
  (when-let [idx (last-real-item-idx stack)]
    (vswap! stack update-in [(dec (count @stack)) :list :items idx :content]
            conj (parse-inline (str/triml line)))))

;; ─── Tables ─────────────────────────────────────────────────────────────────

(defn- split-table-row
  "Split a GFM table row into raw cell strings: unescaped pipes separate
   cells, outer pipes are dropped, cells trimmed, \\| unescaped."
  [line]
  (let [cells (mapv str/trim (str/split line #"(?<!\\)\|" -1))]
    (-> cells
        (cond-> (str/starts-with? line "|") rest
                (str/ends-with? line "|") butlast)
        (->> (mapv #(str/replace % escaped-pipe-re "|"))))))

(defn- table-row?
  "A GFM table data row: contains a pipe after trimming."
  [line]
  (str/includes? (str/trim line) "|"))

(defn- table-separator?
  "A GFM table separator row: every cell is dashes with optional colons."
  [cells]
  (and (seq cells) (every? #(re-matches sep-cell-re %) cells)))

(defn- cell-align
  "Alignment from a separator cell: :left/:right/:center, or nil."
  [s]
  (let [t (str/trim s)]
    (cond
      (and (str/starts-with? t ":") (str/ends-with? t ":")) :center
      (str/starts-with? t ":") :left
      (str/ends-with? t ":") :right
      :else nil)))

(defn- parse-table!
  "Parse the GFM table whose header is LINES[I] and separator LINES[I+1].
   Appends the :table token to RESULT; returns the number of lines consumed.
   Columns are padded to the header count; short rows get empty cells."
  [lines i result]
  (let [header-cells (split-table-row (nth lines i))
        sep-cells (split-table-row (nth lines (inc i)))
        n (count header-cells)
        j (loop [j (+ i 2)]
            (if (and (< j (count lines)) (table-row? (nth lines j)))
              (recur (inc j))
              j))
        cell-tokens (fn [line]
                      (mapv parse-inline
                            (take n (concat (split-table-row line) (repeat "")))))]
    (vswap! result conj
            {:type :table
             :header (cell-tokens (nth lines i))
             :rows (mapv cell-tokens (subvec lines (+ i 2) j))
             :aligns (mapv cell-align (take n (concat sep-cells (repeat ""))))
             :raw (str/join "\n" (subvec lines i j))})
    (- j i)))

;; ─── Block tokenizer ────────────────────────────────────────────────────────

(defn parse
  "Parse Markdown TEXT into a vector of block tokens (the AST).

   Block tokens:
     {:type :blank}                          whitespace-only line
     {:type :hr}                             --- / ___ / ***
     {:type :heading :level 1..6 :content [inline]}
     {:type :paragraph :content [inline]}
     {:type :code :lang \"\" :text \"raw\"}   fenced code (interior raw lines, \\n-joined)
     {:type :table :header [cell ...] :rows [[cell ...] ...] :aligns [...] :raw \"...\"}
                                             GFM table; cells are inline-token
                                             vectors; :aligns holds :left/:right/
                                             :center/nil per column; :raw is the
                                             source lines (narrow-width fallback)
     {:type :ul :items [item ...]}           one item per source line;
     {:type :ol :items [item ...]}             consecutive same-indent lines group
     {:type :quote :content [inline]}

   List items are maps: {:content [[inline] ...] :blocks [<block> ...]}.
   :content holds the item's lines (first line gets the bullet, the rest are
   continuations); :blocks holds nested :ul/:ol/:code tokens (indent-deeper
   content). A :blank pseudo-item renders an empty line inside the list.

   Inline tokens are produced by parse-inline: :text/:strong/:em/:del/:code/:link.
   All tokens are plain data — no ANSI styling, no terminal-width dependence."
  [text]
  (let [lines (str/split-lines (or text ""))
        result (volatile! [])
        code-state (volatile! nil)
        stack (volatile! [])]
    (loop [i 0]
      (when (< i (count lines))
        (let [line (nth lines i)
              trimmed (str/trim line)
              indent (leading-indent line)
              next-i (cond
                       ;; Inside a code block: accumulate raw lines (leading
                       ;; whitespace up to the fence indent stripped) until the
                       ;; closing fence, then emit (or attach to a list item).
                       @code-state
                       (if (re-matches code-fence-re trimmed)
                         (let [code-tok {:type :code
                                         :lang (:lang @code-state)
                                         :text (:text @code-state)}]
                           (if (:in-item? @code-state)
                             (attach-block! stack code-tok)
                             (vswap! result conj code-tok))
                           (vreset! code-state nil)
                           (inc i))
                         (let [l (strip-indent (:indent @code-state) line)]
                           (if (:started? @code-state)
                             (vswap! code-state update :text str (str "\n" l))
                             (vswap! code-state assoc :text l :started? true))
                           (inc i)))

                       ;; Code fence open: inside a list item when indented
                       ;; deeper than the innermost list
                       (re-matches code-fence-re trimmed)
                       (do (if (and (seq @stack) (> indent (:indent (peek @stack))))
                             (vreset! code-state {:lang (or (second (re-find code-fence-re trimmed)) "")
                                                  :text "" :started? false :indent indent :in-item? true})
                             (do (close-all-lists! stack result)
                                 (vreset! code-state {:lang (or (second (re-find code-fence-re trimmed)) "")
                                                      :text "" :started? false :indent indent})))
                           (inc i))

                       (re-matches hr-re trimmed)
                       (do (close-all-lists! stack result)
                           (vswap! result conj {:type :hr})
                           (inc i))

                       (re-matches heading-re trimmed)
                       (do (close-all-lists! stack result)
                           (let [[_ level-str content] (re-find heading-re trimmed)]
                             (vswap! result conj {:type :heading :level (count level-str)
                                                  :content (parse-inline content)}))
                           (inc i))

                       (re-matches quote-re trimmed)
                       (do (close-all-lists! stack result)
                           (let [[_ content] (re-find quote-re trimmed)]
                             (vswap! result conj {:type :quote :content (parse-inline content)}))
                           (inc i))

                       ;; List line (before tables: a pipe in list content must
                       ;; not turn the item into a table header)
                       (or (re-matches ul-re trimmed) (re-matches ol-re trimmed))
                       (do (add-list-line! stack result indent trimmed)
                           (inc i))

                       ;; GFM table: a pipe row immediately followed by a
                       ;; separator row
                       (and (table-row? line)
                            (seq (split-table-row line))
                            (table-separator? (split-table-row (nth lines (inc i) ""))))
                       (do (close-all-lists! stack result)
                           (+ i (parse-table! lines i result)))

                       (re-matches empty-re line)
                       (do (add-blank-line! stack result)
                           (inc i))

                       ;; Indented continuation of the innermost list item
                       (and (seq @stack) (> indent (:indent (peek @stack))))
                       (do (add-continuation! stack line)
                           (inc i))

                       :else
                       (do (close-all-lists! stack result)
                           (vswap! result conj {:type :paragraph :content (parse-inline line)})
                           (inc i)))]
          (recur next-i))))
    ;; Flush an unclosed code block at end of input (attach when inside a list)
    (when-let [cs @code-state]
      (let [code-tok {:type :code :lang (:lang cs) :text (:text cs)}]
        (if (:in-item? cs)
          (attach-block! stack code-tok)
          (vswap! result conj code-tok))))
    (close-all-lists! stack result)
    @result))
