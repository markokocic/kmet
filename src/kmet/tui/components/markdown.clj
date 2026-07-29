(ns kmet.tui.components.markdown
  "Markdown to ANSI-styled terminal output.
   Port of @earendil-works/pi-tui Markdown.
   Minimal CommonMark renderer for terminal display."
  (:require [kmet.tui.protocols :as protocols]
            [kmet.tui.utils :as u]))

;; ─── Theme ──────────────────────────────────────────────────────────────────
;; Matches pi's MarkdownTheme interface.

(defrecord MarkdownTheme [heading link link-url code code-block
                          code-block-border quote quote-border hr
                          list-bullet bold italic underline strikethrough])

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
     :link (fn [s] (str ul-ansi "\u001b[36m" s rst))
     :link-url (fn [s] (str " " dim-ansi "(" s ")" rst))
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
     :strikethrough (fn [s] (str strike-ansi s "\u001b[29m"))}))

;; ─── Minimal inline parser ─────────────────────────────────────────────────

(defn- parse-inlines
  "Parse inline formatting from a line text, returning ANSI-styled output."
  [line theme]
  (if (or (empty? line) (nil? line))
    ""
    (loop [i 0, n (count line), result []]
      (if (>= i n)
        (apply str result)
        (let [c (subs line i (inc i))
              remaining (subs line i)]
          (cond
            ;; Code span: `code`
            (= c "`")
            (let [end (or (clojure.string/index-of remaining "`" 1) -1)]
              (if (>= end 0)
                (let [code ((:code theme) (subs line (inc i) (+ i end)))]
                  (recur (+ i end 1) n (conj result code)))
                (recur (inc i) n (conj result c))))
            ;; Bold: **text**
            (and (= c "*") (< (inc i) n) (= (nth line (inc i)) \*))
            (let [end (or (clojure.string/index-of remaining "**" 2) -1)]
              (if (>= end 0)
                (let [inner ((:bold theme) (parse-inlines (subs line (+ i 2) (+ i end)) theme))]
                  (recur (+ i end 2) n (conj result inner)))
                (recur (inc i) n (conj result c))))
            ;; Italic: *text*
            (= c "*")
            (let [end (or (clojure.string/index-of remaining "*" 1) -1)]
              (if (>= end 0)
                (let [inner ((:italic theme) (parse-inlines (subs line (inc i) (+ i end)) theme))]
                  (recur (+ i end 1) n (conj result inner)))
                (recur (inc i) n (conj result c))))
            ;; Strikethrough: ~~text~~ (GitHub flavored)
            (and (= c "~") (>= n (+ i 2)) (= (subs line (inc i) (+ i 2)) "~"))
            (let [end (or (clojure.string/index-of remaining "~~" 2) -1)]
              (if (>= end 0)
                (let [inner (str "\u001b[9m" (parse-inlines (subs line (+ i 2) (+ i end)) theme) "\u001b[29m")]
                  (recur (+ i end 2) n (conj result inner)))
                (recur (inc i) n (conj result c))))
            ;; Link: [text](url)
            (= c "[")
            (let [close-b (or (clojure.string/index-of remaining "]") -1)]
              (if (>= close-b 0)
                (let [paren (or (clojure.string/index-of remaining "(" close-b) -1)]
                  (if (and (>= paren 0) (= paren (inc close-b)))
                    (let [close-p (or (clojure.string/index-of remaining ")" paren) -1)]
                      (if (>= close-p 0)
                        (let [text (subs line (inc i) (+ i close-b))
                              url (subs line (+ i paren 1) (+ i close-p))
                              link-text ((:link theme) (parse-inlines text theme))
                              url-text ((:link-url theme) url)]
                          (recur (+ i close-p 1) n (conj result link-text url-text)))
                        (recur (inc i) n (conj result c))))
                    (recur (inc i) n (conj result c))))
                (recur (inc i) n (conj result c))))
            :else
            (recur (inc i) n (conj result c))))))))

;; ─── Line type detection ───────────────────────────────────────────────────

(def ^:private heading-re #"^(#{1,6})\s+(.*)$")
(def ^:private code-fence-re #"^```(\w*)$")
(def ^:private quote-re #"^>\s?(.*)$")
(def ^:private ul-re #"^[\s]*[-*+]\s+(.*)$")
(def ^:private ol-re #"^[\s]*\d+\.\s+(.*)$")
(def ^:private hr-re #"^[-*_]{3,}$")
(def ^:private empty-re #"^\s*$")

;; ─── Markdown component ───────────────────────────────────────────────────

(defrecord Markdown [text-atom theme-atom padding-x-atom
                     cache-atom width-atom]
  protocols/IComponent

  (render [this width]
    (let [text @text-atom
          theme @theme-atom
          padding-x @padding-x-atom
          cached @cache-atom]
      (if (and cached (= (:width cached) width) (= (:text cached) text))
        (:lines cached)
        (let [content-width (max 1 (- width (* 2 padding-x)))
              left-pad (apply str (repeat padding-x \space))
              lines (clojure.string/split-lines text)
              result (volatile! [])
              in-code-block (volatile! false)
              code-lang (volatile! "")]
          (doseq [line lines]
            (let [trimmed (clojure.string/trim line)]
              (cond
                ;; Code block fences
                (and (not @in-code-block) (re-matches code-fence-re trimmed))
                (do (vswap! result conj "")
                    (vswap! in-code-block not)
                    (let [lang (second (re-find code-fence-re trimmed))]
                      (vreset! code-lang (or lang ""))))

                @in-code-block
                (if (re-matches code-fence-re trimmed)
                  (do (vswap! result conj "")
                      (vswap! in-code-block not)
                      (vreset! code-lang ""))
                  (let [styled ((:code-block theme) (str "  " line))
                        padded (str left-pad styled
                                    (apply str (repeat (max 0 (- content-width (u/visible-width styled))) \space)))]
                    (vswap! result conj padded)))

                ;; Horizontal rule
                (re-matches hr-re trimmed)
                (let [hr ((:hr theme) (apply str (repeat content-width "─")))
                      padded (str left-pad hr)]
                  (vswap! result conj padded))

                ;; Heading
                (re-matches heading-re trimmed)
                (let [[_ level-str content] (re-find heading-re trimmed)
                      level (count level-str)
                      styled ((:heading theme) (parse-inlines content theme))
                      line-width (u/visible-width styled)
                      padded (str left-pad styled
                                  (apply str (repeat (max 0 (- content-width line-width)) \space)))]
                  (vswap! result conj padded)
                  ;; Add underline for H1/H2
                  (when (<= level 2)
                    (let [underline ((:hr theme) (apply str (repeat content-width (if (= level 1) "═" "─"))))
                          upadded (str left-pad underline)]
                      (vswap! result conj upadded))))

                ;; Blockquote
                (re-matches quote-re trimmed)
                (let [[_ content] (re-find quote-re trimmed)
                      border ((:quote-border theme) "▎")
                      styled ((:quote theme) (parse-inlines content theme))
                      line-width (u/visible-width styled)
                      padded (str left-pad border styled
                                  (apply str (repeat (max 0 (- content-width (inc line-width))) \space)))]
                  (vswap! result conj padded))

                ;; Unordered list
                (re-matches ul-re trimmed)
                (let [[_ content] (re-find ul-re trimmed)
                      bullet ((:list-bullet theme) "• ")
                      styled (parse-inlines content theme)
                      line-width (u/visible-width (str bullet styled))
                      padded (str left-pad bullet styled
                                  (apply str (repeat (max 0 (- content-width line-width)) \space)))]
                  (vswap! result conj padded))

                ;; Ordered list
                (re-matches ol-re trimmed)
                (let [[_ content] (re-find ol-re trimmed)
                      bullet ((:list-bullet theme) "1. ")
                      styled (parse-inlines content theme)
                      line-width (u/visible-width (str bullet styled))
                      padded (str left-pad bullet styled
                                  (apply str (repeat (max 0 (- content-width line-width)) \space)))]
                  (vswap! result conj padded))

                ;; Empty line
                (re-matches empty-re line)
                (vswap! result conj (str left-pad
                                         (apply str (repeat content-width \space))))

                ;; Regular paragraph
                :else
                (let [styled (parse-inlines line theme)
                      line-width (u/visible-width styled)]
                  (if (<= line-width content-width)
                    (let [padded (str left-pad styled
                                      (apply str (repeat (max 0 (- content-width line-width)) \space)))]
                      (vswap! result conj padded))
                    ;; Word-wrap long lines
                    (let [wrapped (u/wrap-text-with-ansi styled content-width)]
                      (doseq [wl wrapped]
                        (let [wl-width (u/visible-width wl)
                              padded (str left-pad wl
                                          (apply str (repeat (max 0 (- content-width wl-width)) \space)))]
                          (vswap! result conj padded)))))))))
          (let [result-lines @result]
            (reset! cache-atom {:width width :text text :lines result-lines})
            result-lines)))))

  (handle-input [this data] nil)

  (invalidate [this]
    (reset! (:cache-atom this) nil)))

;; ─── Construction ──────────────────────────────────────────────────────────

(defn make-markdown
  "Create a Markdown display component.
   Options: :theme (default-theme), :padding-x (default 1)"
  [text & {:keys [theme padding-x] :or {padding-x 1}
           :as opts}]
  (let [t (or theme default-theme)]
    (map->Markdown {:text-atom (atom text)
                    :theme-atom (atom t)
                    :padding-x-atom (atom padding-x)
                    :cache-atom (atom nil)
                    :width-atom (atom 80)})))

(defn markdown-set-text! [md text]
  (reset! (:text-atom md) text)
  (protocols/invalidate md))

(defn markdown-append! [md text]
  (let [current @(:text-atom md)]
    (reset! (:text-atom md) (str current "\n" text))
    (protocols/invalidate md)))

(defn markdown-get-text [md]
  @(:text-atom md))
