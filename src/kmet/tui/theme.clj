(ns kmet.tui.theme
  "Theme system — identical structure to pi's theme.ts.
   EDN-only loading with same :vars/:colors schema as pi's JSON."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [babashka.fs :as fs]))

;; ═══════════════════════════════════════════════════════════════════════════
;; Color tokens — matching pi's ThemeColor / ThemeBg exactly
;; ═══════════════════════════════════════════════════════════════════════════

(def ^:const FG-TOKENS
  [:accent :border :border-accent :border-muted
   :success :error :warning :muted :dim :text :thinking-text
   :user-message-text :custom-message-text :custom-message-label
   :tool-title :tool-output
   :md-heading :md-link :md-link-url :md-code
   :md-code-block :md-code-block-border :md-quote :md-quote-border
   :md-hr :md-list-bullet :md-table-border
   :tool-diff-added :tool-diff-removed :tool-diff-context
   :syntax-comment :syntax-keyword :syntax-function
   :syntax-variable :syntax-string :syntax-number
   :syntax-type :syntax-operator :syntax-punctuation
   :thinking-off :thinking-minimal :thinking-low
   :thinking-medium :thinking-high :thinking-xhigh :thinking-max
   :bash-mode])

(def ^:const BG-TOKENS
  [:selected-bg :user-message-bg :custom-message-bg
   :tool-pending-bg :tool-success-bg :tool-error-bg])

;; ═══════════════════════════════════════════════════════════════════════════
;; Theme record — wraps fg/bg ANSI maps with API matching pi's Theme class
;; ═══════════════════════════════════════════════════════════════════════════

(defrecord Theme [name
                  fg-colors    ;; {keyword → ANSI escape string}
                  bg-colors    ;; {keyword → ANSI escape string}
                  color-mode   ;; :truecolor or :256color
                  source-path] ;; optional path for file-watching

  Object
  (toString [this] (str "#Theme{:name " (:name this) "}")))

;; ═══════════════════════════════════════════════════════════════════════════
;; Color helpers — matching pi's hexToRgb, rgbTo256, fgAnsi, bgAnsi
;; ═══════════════════════════════════════════════════════════════════════════

(def ^:private FG-RST "\u001b[39m")
(def ^:private BG-RST "\u001b[49m")

(defn hex->rgb [hex]
  (let [h (subs hex 1)
        r (Integer/parseInt (subs h 0 2) 16)
        g (Integer/parseInt (subs h 2 4) 16)
        b (Integer/parseInt (subs h 4 6) 16)]
    {:r r :g g :b b}))

(defn- rgb->256 [r g b]
  (let [cube [0 95 135 175 215 255]
        closest #(first (sort-by (fn [x] (Math/abs (- % x))) cube))
        ri (.indexOf cube (closest r))
        gi (.indexOf cube (closest g))
        bi (.indexOf cube (closest b))
        cube-idx (+ 16 (* 36 ri) (* 6 gi) bi)
        ;; grayscale check
        maxc (max r g b)
        minc (min r g b)
        spread (- maxc minc)
        gray (int (+ (* 0.299 r) (* 0.587 g) (* 0.114 b)))]
    (if (< spread 10)
      (let [gray-idx (int (Math/round (/ (- gray 8) 10)))]
        (+ 232 (min 23 (max 0 gray-idx))))
      cube-idx)))

(defn- fg-ansi [value color-mode]
  (cond
    (nil? value) FG-RST
    (= value "") FG-RST
    (number? value) (str "\u001b[38;5;" value "m")
    (string? value)
    (if (str/starts-with? value "#")
      (let [{:keys [r g b]} (hex->rgb value)]
        (if (= color-mode :truecolor)
          (str "\u001b[38;2;" r ";" g ";" b "m")
          (str "\u001b[38;5;" (rgb->256 r g b) "m")))
      FG-RST)
    :else FG-RST))

(defn- bg-ansi [value color-mode]
  (cond
    (nil? value) BG-RST
    (= value "") BG-RST
    (number? value) (str "\u001b[48;5;" value "m")
    (string? value)
    (if (str/starts-with? value "#")
      (let [{:keys [r g b]} (hex->rgb value)]
        (if (= color-mode :truecolor)
          (str "\u001b[48;2;" r ";" g ";" b "m")
          (str "\u001b[48;5;" (rgb->256 r g b) "m")))
      BG-RST)
    :else BG-RST))

;; ═══════════════════════════════════════════════════════════════════════════
;; Theme API — matching pi's theme.fg() / theme.bg() / bold() etc.
;; ═══════════════════════════════════════════════════════════════════════════

(defn fg
  "pi-equivalent: theme.fg(color, text) — wrap text in fg color, reset fg only."
  [theme color-key text]
  (str (get (:fg-colors theme) color-key FG-RST) text FG-RST))

(defn bg
  "pi-equivalent: theme.bg(color, text) — wrap text in bg color, reset bg only."
  [theme color-key text]
  (str (get (:bg-colors theme) color-key BG-RST) text BG-RST))

(defn bold [text] (str "\u001b[1m" text "\u001b[22m"))
(defn dim [text] (str "\u001b[2m" text "\u001b[22m"))
(defn italic [text] (str "\u001b[3m" text "\u001b[23m"))
(defn underline [text] (str "\u001b[4m" text "\u001b[24m"))
(defn inverse [text] (str "\u001b[7m" text "\u001b[27m"))
(defn strikethrough [text] (str "\u001b[9m" text "\u001b[29m"))

;; ═══════════════════════════════════════════════════════════════════════════
;; Sub-theme factories — matching pi's getMarkdownTheme() etc.
;; ═══════════════════════════════════════════════════════════════════════════

(defn get-markdown-theme
  "pi-equivalent: getMarkdownTheme() — returns MarkdownTheme fn map."
  [t]
  {:heading (fn [s] (fg t :md-heading s))
   :link (fn [s] (fg t :md-link s))
   :link-url (fn [s] (fg t :md-link-url s))
   :code (fn [s] (fg t :md-code s))
   :code-block (fn [s] (fg t :md-code-block s))
   :code-block-border (fn [s] (fg t :md-code-block-border s))
   :quote (fn [s] (fg t :md-quote s))
   :quote-border (fn [s] (fg t :md-quote-border s))
   :hr (fn [s] (fg t :md-hr s))
   :list-bullet (fn [s] (fg t :md-list-bullet s))
   :table-border (fn [s] (fg t :md-table-border s))
   :bold bold
   :italic italic
   :underline underline
   :strikethrough strikethrough})

(defn get-select-list-theme
  "pi-equivalent: getSelectListTheme() — returns SelectListTheme fn map."
  [t]
  {:selected-prefix (fn [s] (fg t :accent s))
   :selected-text (fn [s] (fg t :accent s))
   :description (fn [s] (fg t :muted s))
   :scroll-info (fn [s] (fg t :muted s))
   :no-match (fn [s] (fg t :muted s))})

(defn get-editor-theme
  "pi-equivalent: getEditorTheme() — returns EditorTheme map."
  [t]
  {:border-color (fn [s] (fg t :border-muted s))
   :select-list (get-select-list-theme t)})

(defn get-settings-list-theme
  "pi-equivalent: getSettingsListTheme() — returns SettingsListTheme fn map."
  [t]
  {:label (fn [s sel] (if sel (fg t :accent s) s))
   :value (fn [s sel] (if sel (fg t :accent s) (fg t :muted s)))
   :description (fn [s] (fg t :dim s))
   :cursor (fg t :accent "→ ")
   :hint (fn [s] (fg t :dim s))})

;; ═══════════════════════════════════════════════════════════════════════════
;; Theme construction — from pi-identical EDN schema
;; ═══════════════════════════════════════════════════════════════════════════
;;
;; EDN file format (identical structure to pi's JSON):
;;
;;   {:name "dark"
;;    :vars {"cyan" "#00d7ff" "blue" "#5f87ff" ...}
;;    :colors {:accent "accent" :border "blue" ...}}
;;
;; or flat format (backward compat):
;;
;;   {:accent "#8abeb7" :border "#5f87ff" ...}
;;
;; ═══════════════════════════════════════════════════════════════════════════

(defn- camel->kebab
  "Convert camelCase string to kebab-case keyword."
  [s]
  (keyword
   (str/lower-case
    (str/replace s #"([a-z])([A-Z])" "$1-$2"))))

;; Mapping from pi's camelCase color keys to kebab-case keywords
(def token-map
  {"accent" :accent "border" :border
   "borderAccent" :border-accent "borderMuted" :border-muted
   "success" :success "error" :error "warning" :warning
   "muted" :muted "dim" :dim "text" :text
   "thinkingText" :thinking-text
   "selectedBg" :selected-bg
   "userMessageBg" :user-message-bg "userMessageText" :user-message-text
   "customMessageBg" :custom-message-bg "customMessageText" :custom-message-text
   "customMessageLabel" :custom-message-label
   "toolPendingBg" :tool-pending-bg "toolSuccessBg" :tool-success-bg
   "toolErrorBg" :tool-error-bg
   "toolTitle" :tool-title "toolOutput" :tool-output
   "mdHeading" :md-heading "mdLink" :md-link "mdLinkUrl" :md-link-url
   "mdCode" :md-code "mdCodeBlock" :md-code-block
   "mdCodeBlockBorder" :md-code-block-border
   "mdQuote" :md-quote "mdQuoteBorder" :md-quote-border
   "mdHr" :md-hr "mdListBullet" :md-list-bullet
   "mdTableBorder" :md-table-border
   "toolDiffAdded" :tool-diff-added "toolDiffRemoved" :tool-diff-removed
   "toolDiffContext" :tool-diff-context
   "syntaxComment" :syntax-comment "syntaxKeyword" :syntax-keyword
   "syntaxFunction" :syntax-function "syntaxVariable" :syntax-variable
   "syntaxString" :syntax-string "syntaxNumber" :syntax-number
   "syntaxType" :syntax-type "syntaxOperator" :syntax-operator
   "syntaxPunctuation" :syntax-punctuation
   "thinkingOff" :thinking-off "thinkingMinimal" :thinking-minimal
   "thinkingLow" :thinking-low "thinkingMedium" :thinking-medium
   "thinkingHigh" :thinking-high "thinkingXhigh" :thinking-xhigh
   "thinkingMax" :thinking-max
   "bashMode" :bash-mode})

(defn- is-pi-schema?
  "Check if data uses pi's {:name :vars :colors} schema."
  [data]
  (contains? data :colors))

(defn- resolve-colors-from-pi-schema
  "Resolve colors from pi-style {:name :vars :colors} data.
   :colors keys can be camelCase strings (pi JSON style) or kebab-case keywords."
  [data color-mode]
  (let [vars (get data :vars {})
        raw (:colors data {})
        ;; Accept both camelCase string keys and kebab-case keywords
        raw-map (into {} (map (fn [[k v]]
                                (let [kw (if (keyword? k) k (camel->kebab k))]
                                  [kw v]))
                              raw))
        resolve (fn [v]
                  (cond
                    (number? v) v
                    (string? v)
                    (if (str/starts-with? v "#")
                      v
                      (let [var-val (get vars v)]
                        (if var-val var-val v)))
                    :else nil))
        get-color (fn [k] (when-let [v (get raw-map k)] (resolve v)))
        fg-map (into {} (keep (fn [k] (when-let [v (get-color k)] [k (fg-ansi v color-mode)])) FG-TOKENS))
        bg-map (into {} (keep (fn [k] (when-let [v (get-color k)] [k (bg-ansi v color-mode)])) BG-TOKENS))]
    {:fg-map fg-map :bg-map bg-map}))

(defn- resolve-colors-from-flat-map
  "Resolve colors from a flat kebab-case keyword → value map (legacy format)."
  [data color-mode]
  (let [fg-map (into {} (keep (fn [k] (when-let [v (get data k)] [k (fg-ansi v color-mode)])) FG-TOKENS))
        bg-map (into {} (keep (fn [k] (when-let [v (get data k)] [k (bg-ansi v color-mode)])) BG-TOKENS))]
    {:fg-map fg-map :bg-map bg-map}))

(defn make-theme
  "Create a Theme from an EDN color map.
   Accepts pi-schema {:name \"...\" :vars {...} :colors {...}}
   or flat schema {:accent \"...\" :border \"...\" ...}.
   Falls back to dark theme keys for missing colors."
  ([data] (make-theme data nil))
  ([data source-path]
   (let [color-mode :truecolor  ;; always truecolor for modern terminals
         name (or (:name data) "unnamed")
         {:keys [fg-map bg-map]} (if (is-pi-schema? data)
                                   (resolve-colors-from-pi-schema data color-mode)
                                   (resolve-colors-from-flat-map data color-mode))
         ;; Fill missing tokens with dark theme defaults
         dark (resolve-colors-from-flat-map
               {:accent "#8abeb7" :border "#5f87ff" :border-accent "#00d7ff"
                :border-muted "#505050" :success "#b5bd68" :error "#cc6666"
                :warning "#ffff00" :muted "#808080" :dim "#666666"
                :text "#d4d4d4" :thinking-text "#808080"
                :selected-bg "#3a3a4a" :user-message-bg "#343541"
                :user-message-text "#d4d4d4" :custom-message-bg "#2d2838"
                :custom-message-text "#d4d4d4" :custom-message-label "#9575cd"
                :tool-pending-bg "#282832" :tool-success-bg "#283228"
                :tool-error-bg "#3c2828" :tool-title "#d4d4d4"
                :tool-output "#808080"
                :md-heading "#f0c674" :md-link "#81a2be" :md-link-url "#666666"
                :md-code "#8abeb7" :md-code-block "#b5bd68"
                :md-code-block-border "#808080" :md-quote "#808080"
                :md-quote-border "#808080" :md-hr "#808080"
                :md-list-bullet "#8abeb7"
                :md-table-border "#808080"
                :tool-diff-added "#b5bd68" :tool-diff-removed "#cc6666"
                :tool-diff-context "#808080"
                :syntax-comment "#6A9955" :syntax-keyword "#569CD6"
                :syntax-function "#DCDCAA" :syntax-variable "#9CDCFE"
                :syntax-string "#CE9178" :syntax-number "#B5CEA8"
                :syntax-type "#4EC9B0" :syntax-operator "#D4D4D4"
                :syntax-punctuation "#D4D4D4"
                :thinking-off "#505050" :thinking-minimal "#6e6e6e"
                :thinking-low "#5f87af" :thinking-medium "#81a2be"
                :thinking-high "#b294bb" :thinking-xhigh "#d183e8"
                :thinking-max "#ff5fff"
                :bash-mode "#b5bd68"}
               color-mode)
         complete-fg (merge (:fg-map dark) fg-map)
         complete-bg (merge (:bg-map dark) bg-map)]
     (map->Theme
      {:name name
       :fg-colors complete-fg
       :bg-colors complete-bg
       :color-mode color-mode
       :source-path source-path}))))

;; ═══════════════════════════════════════════════════════════════════════════
;; Built-in themes — same color values as pi's dark.json / light.json
;; ═══════════════════════════════════════════════════════════════════════════

(def dark-theme
  (make-theme
   {:name "dark"
    :vars {"cyan" "#00d7ff" "blue" "#5f87ff" "green" "#b5bd68"
           "red" "#cc6666" "yellow" "#ffff00"
           "text" "#d4d4d4" "gray" "#808080" "dimGray" "#666666"
           "darkGray" "#505050" "accent" "#8abeb7"
           "selectedBg" "#3a3a4a" "userMsgBg" "#343541"
           "toolPendingBg" "#282832" "toolSuccessBg" "#283228"
           "toolErrorBg" "#3c2828" "customMsgBg" "#2d2838"}
    :colors {"accent" "accent" "border" "blue"
             "borderAccent" "cyan" "borderMuted" "darkGray"
             "success" "green" "error" "red" "warning" "yellow"
             "muted" "gray" "dim" "dimGray" "text" "text"
             "thinkingText" "gray"
             "selectedBg" "selectedBg" "userMessageBg" "userMsgBg"
             "userMessageText" "text"
             "customMessageBg" "customMsgBg" "customMessageText" "text"
             "customMessageLabel" "#9575cd"
             "toolPendingBg" "toolPendingBg" "toolSuccessBg" "toolSuccessBg"
             "toolErrorBg" "toolErrorBg" "toolTitle" "text"
             "toolOutput" "gray"
             "mdHeading" "#f0c674" "mdLink" "#81a2be"
             "mdLinkUrl" "dimGray" "mdCode" "accent"
             "mdCodeBlock" "green" "mdCodeBlockBorder" "gray"
             "mdQuote" "gray" "mdQuoteBorder" "gray" "mdHr" "gray"
             "mdListBullet" "accent"
             "toolDiffAdded" "green" "toolDiffRemoved" "red"
             "toolDiffContext" "gray"
             "syntaxComment" "#6A9955" "syntaxKeyword" "#569CD6"
             "syntaxFunction" "#DCDCAA" "syntaxVariable" "#9CDCFE"
             "syntaxString" "#CE9178" "syntaxNumber" "#B5CEA8"
             "syntaxType" "#4EC9B0" "syntaxOperator" "#D4D4D4"
             "syntaxPunctuation" "#D4D4D4"
             "thinkingOff" "darkGray" "thinkingMinimal" "#6e6e6e"
             "thinkingLow" "#5f87af" "thinkingMedium" "#81a2be"
             "thinkingHigh" "#b294bb" "thinkingXhigh" "#d183e8"
             "thinkingMax" "#ff5fff"
             "bashMode" "green"}}))

(def light-theme
  (make-theme
   {:name "light"
    :vars {"cyan" "#00afd7" "blue" "#005f87" "green" "#4e9a06"
           "red" "#cc0000" "yellow" "#c4a000"
           "text" "#2e3436" "gray" "#888a85" "dimGray" "#babdb6"
           "darkGray" "#d3d7cf" "accent" "#4e9a06"
           "selectedBg" "#d3d7cf" "userMsgBg" "#eeeeec"
           "toolPendingBg" "#f5f5f0" "toolSuccessBg" "#f0fff0"
           "toolErrorBg" "#fff0f0" "customMsgBg" "#f0ecf5"}
    :colors {"accent" "accent" "border" "blue"
             "borderAccent" "cyan" "borderMuted" "darkGray"
             "success" "green" "error" "red" "warning" "yellow"
             "muted" "gray" "dim" "dimGray" "text" "text"
             "thinkingText" "gray"
             "selectedBg" "selectedBg" "userMessageBg" "userMsgBg"
             "userMessageText" "text"
             "customMessageBg" "customMsgBg" "customMessageText" "text"
             "customMessageLabel" "#5e3a87"
             "toolPendingBg" "toolPendingBg" "toolSuccessBg" "toolSuccessBg"
             "toolErrorBg" "toolErrorBg" "toolTitle" "text"
             "toolOutput" "gray"
             "mdHeading" "#c4a000" "mdLink" "#005f87"
             "mdLinkUrl" "dimGray" "mdCode" "accent"
             "mdCodeBlock" "green" "mdCodeBlockBorder" "gray"
             "mdQuote" "gray" "mdQuoteBorder" "gray" "mdHr" "gray"
             "mdListBullet" "accent"
             "toolDiffAdded" "green" "toolDiffRemoved" "red"
             "toolDiffContext" "gray"
             "syntaxComment" "#6A9955" "syntaxKeyword" "#0000ff"
             "syntaxFunction" "#795e26" "syntaxVariable" "#001080"
             "syntaxString" "#a31515" "syntaxNumber" "#098658"
             "syntaxType" "#267f99" "syntaxOperator" "#2e3436"
             "syntaxPunctuation" "#2e3436"
             "thinkingOff" "darkGray" "thinkingMinimal" "#c0c0c0"
             "thinkingLow" "#5f87af" "thinkingMedium" "#005f87"
             "thinkingHigh" "#800080" "thinkingXhigh" "#a020f0"
             "thinkingMax" "#ff00ff"
             "bashMode" "green"}}))

;; ═══════════════════════════════════════════════════════════════════════════
;; Registry & Loading — EDN only, same schema as pi's JSON
;; ═══════════════════════════════════════════════════════════════════════════

(defonce ^:private themes (atom {"dark" dark-theme "light" light-theme}))

(defn register-theme!
  "Register a theme by name."
  [theme]
  (swap! themes assoc (:name theme) theme))

(defn get-theme
  "Get a theme by name. Falls back to 'dark'."
  [name]
  (or (get @themes (str name))
      (get @themes "dark")
      dark-theme))

(defn- load-edn-file [path]
  (let [basename (str/replace (fs/file-name path) #"\.edn$" "")]
    (try
      (let [data (edn/read-string (slurp path))
            ;; If data uses pi schema with :name, use it; otherwise derive from filename
            theme (if (string? (:name data))
                    (make-theme data path)
                    (make-theme (assoc data :name basename) path))]
        (register-theme! theme))
      (catch Exception e
        (binding [*out* *err*]
          (println "Warning: Failed to load theme" (fs/file-name path) ":" (ex-message e)))))))

(defn load-themes-from-dir
  "Load all .edn theme files from a directory."
  [dir]
  (when (fs/directory? dir)
    (doseq [f (fs/list-dir dir)]
      (when (and (fs/regular-file? f)
                 (str/ends-with? (fs/file-name f) ".edn"))
        (load-edn-file (str f))))))
