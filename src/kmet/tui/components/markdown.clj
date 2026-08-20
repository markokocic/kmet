(ns kmet.tui.components.markdown
  "Markdown to ANSI-styled terminal output.
   Port of @earendil-works/pi-tui Markdown.
   The tokenizer is kmet.libs.markdown/parse (pure data, no ANSI); this
   component walks the token AST and applies theme, padding, and word-wrap."
  (:require [clojure.string :as str]
            [kmet.libs.terminal-image :as timg]
            [kmet.tui.utils :as u]
            [kmet.tui.macros :refer [track! defcomponent]]
            [kmet.libs.markdown :as md]))

;; ─── Theme ──────────────────────────────────────────────────────────────────
;; Matches pi's MarkdownTheme interface.

(defrecord MarkdownTheme [heading link link-url code code-block
                          code-block-border quote quote-border hr
                          list-bullet bold italic underline strikethrough
                          table-border code-block-indent highlight-code])

(def ^:private rst "\u001b[0m")
(def ^:private bold-ansi "\u001b[1m")
(def ^:private dim-ansi "\u001b[2m")
(def ^:private italic-ansi "\u001b[3m")
(def ^:private ul-ansi "\u001b[4m")
(def ^:private strike-ansi "\u001b[9m")

(def default-theme
  (map->MarkdownTheme
   {:heading (fn [s]
               (str bold-ansi "\u001b[33m" s rst))
    :link (fn [s] (str "\u001b[36m" s rst))
    :link-url (fn [s] (str dim-ansi s rst))
    :code (fn [s] (str "\u001b[33m" s rst))
    :code-block (fn [s] (str "\u001b[33m" s rst))
    :code-block-border identity
    :quote (fn [s] (str dim-ansi "\u001b[37m" s rst))
    :quote-border (fn [s] (str dim-ansi "\u001b[90m" s rst))
    :hr (fn [s] (str dim-ansi s rst))
    :list-bullet (fn [s] (str "\u001b[36m" s rst))
    :bold (fn [s] (str bold-ansi s "\u001b[22m"))
    :italic (fn [s] (str italic-ansi s "\u001b[23m"))
    :underline (fn [s] (str ul-ansi s "\u001b[24m"))
    :strikethrough (fn [s] (str strike-ansi s "\u001b[29m"))
    :table-border (fn [s] (str dim-ansi s rst))
    :code-block-indent "  "
    :highlight-code nil}))

;; ─── AST rendering ──────────────────────────────────────────────────────────

(defn- pad-right
  "Append spaces so the visible width of S reaches CW."
  [cw s]
  (str s (apply str (repeat (max 0 (- cw (u/visible-width s))) \space))))

(declare render-inlines)

(defn- style-prefix
  "Return the opening style sequence produced by STYLE-FN before its text."
  [style-fn]
  (if style-fn
    (let [sentinel "\u0000"
          styled (style-fn sentinel)
          index (str/index-of styled sentinel)]
      (if (some? index)
        (subs styled 0 index)
        ""))
    ""))

(defn- make-style-context
  "Build the inline styling context used to preserve a default style across
   Markdown formatting resets, matching pi's Markdown renderer."
  [style-fn]
  (when style-fn
    {:apply-text style-fn
     :style-prefix (style-prefix style-fn)}))

(defn- apply-style-context
  [style-context text]
  (if-let [style-fn (:apply-text style-context)]
    (style-fn text)
    text))

(defn- append-style-prefix
  [text style-context]
  (str text (:style-prefix style-context "")))

(defn- hyperlink
  "Wrap TEXT in an OSC 8 hyperlink targeting URL, matching pi's terminal
   hyperlink helper."
  [text url]
  (str "\u001b]8;;" url "\u001b\\" text "\u001b]8;;\u001b\\"))

(defn- raw-inline-source
  "Reconstruct the raw Markdown source represented by inline TOKENS."
  [tokens]
  (apply str
         (map (fn [tok]
                (case (:type tok)
                  :text (:s tok)
                  :code (str "`" (:s tok) "`")
                  :strong (str "**" (raw-inline-source (:content tok)) "**")
                  :em (str "*" (raw-inline-source (:content tok)) "*")
                  :del (str "~~" (raw-inline-source (:content tok)) "~~")
                  :link (str "[" (raw-inline-source (:text tok)) "](" (:url tok) ")")
                  ""))
              tokens)))

(defn- link-label-matches-url?
  [label url]
  (or (= label url)
      (= label (if (str/starts-with? url "mailto:")
                 (subs url (count "mailto:"))
                 url))))

(defn- trim-style-prefix
  [text style-context]
  (let [prefix (:style-prefix style-context "")]
    (if (seq prefix)
      (loop [text text]
        (if (str/ends-with? text prefix)
          (recur (subs text 0 (- (count text) (count prefix))))
          text))
      text)))

(defn- render-inline
  "Render one inline token to a styled string while preserving the default
   text style after Markdown-specific style resets."
  [tok theme style-context]
  (case (:type tok)
    :text (apply-style-context style-context (:s tok))
    :strong (append-style-prefix
             ((:bold theme) (render-inlines (:content tok) theme style-context))
             style-context)
    :em (append-style-prefix
         ((:italic theme) (render-inlines (:content tok) theme style-context))
         style-context)
    :del (append-style-prefix
          ((:strikethrough theme) (render-inlines (:content tok) theme style-context))
          style-context)
    :code (append-style-prefix ((:code theme) (:s tok)) style-context)
    :link (let [label-tokens (:text tok)
                label (or (:raw-label (meta tok))
                          (raw-inline-source label-tokens))
                link-text ((:link theme)
                           ((:underline theme) (render-inlines label-tokens theme style-context)))
                url (:url tok)
                rendered (if (:hyperlinks (timg/get-capabilities))
                           (hyperlink link-text url)
                           (if (link-label-matches-url? label url)
                             link-text
                             (str link-text ((:link-url theme) (str " (" url ")")))))]
            (append-style-prefix rendered style-context))
    ""))

(defn- render-inlines
  "Render a vector of inline tokens to one styled string."
  ([tokens theme]
   (render-inlines tokens theme nil))
  ([tokens theme style-context]
   (trim-style-prefix
    (apply str (map #(render-inline % theme style-context) tokens))
    style-context)))

(defn- render-code
  "Render a :code token: styled fence lines (with lang), interior lines with
   the code-block theme and indent (or a :highlight-code hook when set).
   EXTRA-INDENT prefixes code nested inside list items. Trailing interior
   blank lines are preserved. Like pi, long lines are wrapped via
   wrap-text-with-ansi to CONTENT-WIDTH, not truncated with ellipsis."
  [result t theme content-width left-pad extra-indent]
  (let [indent (or (:code-block-indent theme) "  ")
        prefix (str left-pad extra-indent)
        lang (:lang t "")
        text (:text t)
        interior (if (str/blank? text) [] (str/split text #"\n" -1))
        push-wrapped (fn [styled]
                       (doseq [wl (u/wrap-text-with-ansi styled content-width)]
                         (vswap! result conj (pad-right content-width wl))))]
    (push-wrapped (str prefix ((:code-block-border theme) (str "```" lang))))
    (if-let [hl (:highlight-code theme)]
      (doseq [l (hl text lang)]
        (push-wrapped (str prefix indent l)))
      (doseq [line interior]
        (push-wrapped (str prefix indent ((:code-block theme) line)))))
    (push-wrapped (str prefix ((:code-block-border theme) "```")))))

(defn- longest-word-width
  "Visible width of TEXT's longest word, capped at LIMIT (0 when empty)."
  [text limit]
  (min limit (reduce max 0 (map u/visible-width (filter seq (str/split text #"\s+"))))))

(defn- partial-fence-line?
  "A line of 1-2 backticks: a fragment of a closing fence mid-stream."
  [line]
  (let [n (count line)]
    (and (pos? n) (<= n 2) (every? #(= \` %) line))))

(defn- trim-code-fence
  "Streaming fix (pi #5825): strip trailing partial closing-fence lines from
   a :code token's text so fence fragments never show while the closing fence
   is being typed. Recurses into the last list item's last :blocks entry."
  [token]
  (case (:type token)
    :code (loop [text (:text token)]
            (let [lines (str/split text #"\n" -1)]
              (if (and (seq lines) (partial-fence-line? (peek lines)))
                (recur (str/join "\n" (pop lines)))
                (assoc token :text text))))
    (:ul :ol)
    (let [items (:items token)]
      (loop [i (dec (count items))]
        (cond
          (neg? i) token
          (= :blank (:type (nth items i))) (recur (dec i))
          :else (update-in token [:items i :blocks]
                           (fn [blocks]
                             (if (seq blocks)
                               (update blocks (dec (count blocks)) trim-code-fence)
                               blocks))))))
    token))

(defn- emit-table-row!
  "Push the visual lines of one table ROW onto RESULT: each cell wrapped to
   its COLUMN-WIDTHS entry, padded, joined with themed │ separators. Header
   cells render bold when BOLD?."
  [result row column-widths theme border-fn bold? left-pad]
  (let [wrapped (mapv (fn [c w] (u/wrap-text-with-ansi c (max 1 w))) row column-widths)
        height (reduce max 1 (map count wrapped))]
    (dotimes [i height]
      (let [cells (mapv (fn [wl w]
                          (let [s (nth wl i "")
                                padded (str s (apply str (repeat (max 0 (- w (u/visible-width s))) \space)))]
                            (if bold? ((:bold theme) padded) padded)))
                        wrapped column-widths)]
        (vswap! result conj (str left-pad (border-fn "│ ")
                                 (str/join (str " " (border-fn "│") " ") cells)
                                 " " (border-fn "│")))))))

(defn- render-table
  "Render a :table token: box-drawn borders, bold header, width-aware columns
   with cell wrapping (pi's renderTable port). Falls back to the raw markdown
   when the table cannot fit."
  [result t theme content-width left-pad default-style]
  (let [num-cols (count (:header t))
        border-fn (or (:table-border theme) identity)
        border-overhead (inc (* 3 num-cols))
        available-for-cells (- content-width border-overhead)
        style-context (make-style-context default-style)
        cell-style (fn [c]
                     (render-inlines c theme style-context))]
    (if (< available-for-cells num-cols)
      ;; Too narrow — fall back to the raw markdown, wrapped
      (doseq [line (u/wrap-text-with-ansi (:raw t) content-width)]
        (vswap! result conj (pad-right content-width (str left-pad line))))
      (let [header-styled (mapv cell-style (:header t))
            row-styled (mapv #(mapv cell-style %) (:rows t))
            max-unbroken 30
            natural (reduce (fn [ws row]
                              (mapv (fn [w c] (max w (u/visible-width c))) ws row))
                            (mapv u/visible-width header-styled)
                            row-styled)
            min-word (reduce (fn [ws row]
                               (mapv (fn [w c] (max w (longest-word-width c max-unbroken))) ws row))
                             (mapv #(max 1 (longest-word-width % max-unbroken)) header-styled)
                             row-styled)
            min-cols (if (> (reduce + min-word) available-for-cells)
                       (let [remaining (- available-for-cells num-cols)
                             total-weight (reduce + (map #(max 0 (dec %)) min-word))
                             growth (mapv (fn [w] (if (pos? total-weight)
                                                    (Math/floorDiv (* (max 0 (dec w)) remaining) total-weight)
                                                    0))
                                          min-word)
                             base (mapv inc growth)
                             allocated (reduce + growth)]
                         (reduce (fn [cs i] (update cs i inc))
                                 base (range (min (- remaining allocated) num-cols))))
                       min-word)
            total-natural (+ (reduce + natural) border-overhead)
            column-widths (if (<= total-natural content-width)
                            (mapv max natural min-cols)
                            (let [total-grow (reduce + (map (fn [n m] (max 0 (- n m))) natural min-cols))
                                  extra (max 0 (- available-for-cells (reduce + min-cols)))
                                  grown (mapv (fn [n m] (+ m (if (pos? total-grow)
                                                               (Math/floorDiv (* (max 0 (- n m)) extra) total-grow)
                                                               0)))
                                              natural min-cols)
                                  remaining (- available-for-cells (reduce + grown))]
                              (loop [ws grown, rem remaining]
                                (if (pos? rem)
                                  (let [[ws' grew? rem'] (loop [ws' ws, i 0, grew? false, r rem]
                                                           (if (or (= i (count ws')) (zero? r))
                                                             [ws' grew? r]
                                                             (if (< (nth ws' i) (nth natural i))
                                                               (recur (update ws' i inc) (inc i) true (dec r))
                                                               (recur ws' (inc i) grew? r))))]
                                    (if grew?
                                      (recur ws' rem')
                                      ws'))
                                  ws))))
            top (str "┌─" (str/join "─┬─" (map #(apply str (repeat % "─")) column-widths)) "─┐")
            sep (str "├─" (str/join "─┼─" (map #(apply str (repeat % "─")) column-widths)) "─┤")
            bot (str "└─" (str/join "─┴─" (map #(apply str (repeat % "─")) column-widths)) "─┘")]
        (vswap! result conj (str left-pad (border-fn top)))
        (emit-table-row! result header-styled column-widths theme border-fn true left-pad)
        (vswap! result conj (str left-pad (border-fn sep)))
        (doseq [row row-styled]
          (emit-table-row! result row column-widths theme border-fn false left-pad))
        (vswap! result conj (str left-pad (border-fn bot)))))))

(defn- render-list
  "Render a :ul/:ol token at nesting DEPTH (0 = root), pushing lines onto
   RESULT. Nested lists indent 4 columns per depth (pi's convention); items
   render their :content lines (first gets the bullet, rest are continuations
   aligned to the marker column), wrap long lines to the item width, then
   render any :blocks (:ul/:ol at depth+1, :code at depth+1) and :blank
   pseudo-items as empty lines. Ordered items number from 1 across real items.
   DEFAULT-STYLE tints item text only."
  [result t theme depth content-width left-pad default-style]
  (let [indent (apply str (repeat (* 4 depth) \space))
        indent-w (count indent)
        num (volatile! 0)]
    (doseq [item (:items t)]
      (if (= :blank (:type item))
        (vswap! result conj (str left-pad (apply str (repeat content-width \space))))
        (let [n (vswap! num inc)
              style-context (make-style-context default-style)
              marker (if (= :ul (:type t)) "• " (str n ". "))
              marker-w (u/visible-width marker)
              bullet ((:list-bullet theme) marker)
              item-width (max 1 (- content-width indent-w marker-w))
              first-prefix (str left-pad indent bullet)
              cont-prefix (str left-pad indent (apply str (repeat marker-w \space)))
              first-line? (volatile! true)]
          (doseq [line (:content item)]
            (let [styled (render-inlines line theme style-context)
                  segs (if (<= (u/visible-width styled) item-width)
                         [styled]
                         (u/wrap-text-with-ansi styled item-width))]
              (doseq [wl segs]
                (let [prefix (if @first-line? first-prefix cont-prefix)]
                  (vswap! result conj
                          (str prefix wl
                               (apply str (repeat (max 0 (- content-width indent-w marker-w
                                                            (u/visible-width wl))) \space))))
                  (vreset! first-line? false)))))
          (doseq [b (:blocks item)]
            (case (:type b)
              :ul (render-list result b theme (inc depth) content-width left-pad default-style)
              :ol (render-list result b theme (inc depth) content-width left-pad default-style)
              :code (render-code result b theme content-width left-pad
                                 (apply str (repeat (* 4 (inc depth)) \space)))
              nil)))))))

(defn- render-block
  "Render one block token into RESULT (a volatile vector of lines).
   Each line is left-padded and right-padded to the content width, matching
   the original line-oriented renderer's output exactly. DEFAULT-STYLE tints
   text content (paragraphs, list items, table cells) but not code blocks,
   headings, quotes, or hr — mirroring pi's defaultTextStyle."
  [result t theme content-width left-pad default-style]
  (case (:type t)
    :blank
    (vswap! result conj (str left-pad (apply str (repeat content-width \space))))

    :hr
    (vswap! result conj (str left-pad ((:hr theme) (apply str (repeat content-width "─")))))

    :heading
    (let [level (:level t)
          heading-style-fn (if (= level 1)
                             (fn [s] ((:heading theme) ((:bold theme) ((:underline theme) s))))
                             (fn [s] ((:heading theme) ((:bold theme) s))))
          heading-ctx (make-style-context heading-style-fn)
          content (render-inlines (:content t) theme heading-ctx)
          ;; pi: H3+ deliberately keeps the "### " prefix (visible depth) and wraps (never truncates)
          styled (if (>= level 3)
                   (str (heading-style-fn (str (apply str (repeat level "#")) " ")) content)
                   content)]
      (doseq [wl (u/wrap-text-with-ansi styled content-width)]
        (vswap! result conj (pad-right content-width (str left-pad wl))))
      ;; Add underline for H1/H2
      (when (<= level 2)
        (vswap! result conj (str left-pad ((:hr theme)
                                           (apply str (repeat content-width (if (= level 1) "═" "─"))))))))

    :code
    (render-code result t theme content-width left-pad "")

    :table
    (render-table result t theme content-width left-pad default-style)

    :quote
    (let [border ((:quote-border theme) "▎")
          border-w (u/visible-width border)
          content-w (max 1 (- content-width border-w))
          styled ((:quote theme) (render-inlines (:content t) theme))
          wrapped (u/wrap-text-with-ansi styled content-w)]
      (doseq [wl wrapped]
        (vswap! result conj
                (str left-pad border wl
                     (apply str (repeat (max 0 (- content-width border-w (u/visible-width wl))) \space))))))

    :ul
    (render-list result t theme 0 content-width left-pad default-style)

    :ol
    (render-list result t theme 0 content-width left-pad default-style)

    :paragraph
    (let [styled (render-inlines (:content t) theme (make-style-context default-style))
          line-width (u/visible-width styled)]
      (if (<= line-width content-width)
        (vswap! result conj (pad-right content-width (str left-pad styled)))
        ;; Word-wrap long lines
        (doseq [wl (u/wrap-text-with-ansi styled content-width)]
          (vswap! result conj (pad-right content-width (str left-pad wl))))))

    nil))

;; ─── Markdown component ───────────────────────────────────────────────────

(defcomponent Markdown nil [text-atom theme-atom padding-x-atom
                            default-style-atom transform-atom
                            cache-atom]
  (render [this width]
    (track! this width
      (let [padding-x @padding-x-atom
            content-width (max 1 (- width (* 2 padding-x)))
            ;; Extension markdown transformers (pi: Markdown's transform fn —
            ;; applied per render with the available width; a throwing
            ;; transformer is skipped). Nil when no transformers.
            text (if-let [tf @transform-atom]
                   (tf @text-atom {:available-width content-width})
                   @text-atom)
            theme @theme-atom
            default-style @default-style-atom
            left-pad (apply str (repeat padding-x \space))
            result (volatile! [])]
        ;; Trim partial closing-fence fragments from the LAST block (streaming)
        (let [tokens (md/parse text)
              tokens (if (seq tokens)
                       (update tokens (dec (count tokens)) trim-code-fence)
                       tokens)]
          (doseq [t tokens]
            (render-block result t theme content-width left-pad default-style)))
        @result))))

;; ─── Construction ──────────────────────────────────────────────────────────

(defn make-markdown
  "Create a Markdown display component.
   Options: :theme (default-theme), :padding-x (default 1),
   :default-style — per-line base style fn applied to text content
   (paragraphs, list items, table cells) but NOT block-level styled elements
   (code blocks, headings, quotes, hr), mirroring pi's defaultTextStyle,
   :transform — extension markdown transformer fn (fn [text {:keys
   [available-width]}]) applied per render before parsing (pi: Markdown's
   transform)."
  [text & {:keys [theme padding-x default-style transform] :or {padding-x 1}}]
  (let [t (or theme default-theme)]
    (map->Markdown {:text-atom (atom text)
                    :theme-atom (atom t)
                    :padding-x-atom (atom padding-x)
                    :default-style-atom (atom default-style)
                    :transform-atom (atom transform)
                    :cache-atom (atom nil)})))

(defn markdown-set-text! [md text]
  (reset! (:text-atom md) text))

(defn markdown-append! [md text]
  (swap! (:text-atom md) #(str % "\n" text)))

(defn markdown-set-theme! [md theme]
  (reset! (:theme-atom md) theme))

(defn markdown-set-default-style! [md f]
  (reset! (:default-style-atom md) f))

(defn markdown-set-transform! [md f]
  (reset! (:transform-atom md) f))

(defn markdown-set-padding-x! [md n]
  (reset! (:padding-x-atom md) n))

(defn markdown-get-text [md]
  @(:text-atom md))
