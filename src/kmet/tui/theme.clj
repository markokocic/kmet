(ns kmet.tui.theme
  "Theme system for kmet.
   Defines a Theme record with ANSI color codes, and provides loading
   from EDN files in ~/.config/kmet/themes/."
  (:require [clojure.java.io :as io]
            [clojure.edn :as edn]))

;; ─── Theme record ──────────────────────────────────────────────────────────

(defrecord Theme [name
                  text accent muted dim
                  success error warning
                  border border-accent border-muted
                  user-bg assistant-bg
                  tool-title tool-output
                  md-heading md-link md-code md-block
                  selected-bg
                  thinking-levels])

;; ─── ANSI helpers ──────────────────────────────────────────────────────────

(def ^:private rst "\u001b[0m")

(defn- ansi-16 [n]
  (str "\u001b[" n "m"))

(defn- ansi-256 [n]
  (str "\u001b[38;5;" n "m"))

(defn- ansi-bg-256 [n]
  (str "\u001b[48;5;" n "m"))

(defn- ansi-truecolor [r g b]
  (str "\u001b[38;2;" r ";" g ";" b "m"))

(defn- ansi-bg-truecolor [r g b]
  (str "\u001b[48;2;" r ";" g ";" b "m"))

(defn- resolve-color
  "Resolve a color value from EDN to an ANSI escape code."
  [color]
  (cond
    (nil? color) rst
    (number? color) (ansi-256 color)
    (string? color) (if (.startsWith color "#")
                      (let [h (subs color 1)
                            r (Integer/parseInt (subs h 0 2) 16)
                            g (Integer/parseInt (subs h 2 4) 16)
                            b (Integer/parseInt (subs h 4 6) 16)]
                        (ansi-truecolor r g b))
                      (case color
                        "black" (ansi-16 30)
                        "red" (ansi-16 31)
                        "green" (ansi-16 32)
                        "yellow" (ansi-16 33)
                        "blue" (ansi-16 34)
                        "magenta" (ansi-16 35)
                        "cyan" (ansi-16 36)
                        "white" (ansi-16 37)
                        "bright-black" (ansi-16 90)
                        "bright-red" (ansi-16 91)
                        "bright-green" (ansi-16 92)
                        "bright-yellow" (ansi-16 93)
                        "bright-blue" (ansi-16 94)
                        "bright-magenta" (ansi-16 95)
                        "bright-cyan" (ansi-16 96)
                        "bright-white" (ansi-16 97)
                        "dim" (ansi-16 2)
                        "bold" (ansi-16 1)
                        rst))
    (vector? color) (apply ansi-truecolor color)
    :else rst))

(defn- resolve-bg-color [color]
  (cond
    (nil? color) rst
    (number? color) (ansi-bg-256 color)
    (string? color) (if (.startsWith color "#")
                      (let [h (subs color 1)
                            r (Integer/parseInt (subs h 0 2) 16)
                            g (Integer/parseInt (subs h 2 4) 16)
                            b (Integer/parseInt (subs h 4 6) 16)]
                        (ansi-bg-truecolor r g b))
                      (case color
                        "black" (ansi-16 40)
                        "red" (ansi-16 41)
                        "green" (ansi-16 42)
                        "yellow" (ansi-16 43)
                        "blue" (ansi-16 44)
                        "magenta" (ansi-16 45)
                        "cyan" (ansi-16 46)
                        "white" (ansi-16 47)
                        rst))
    (vector? color) (apply ansi-bg-truecolor color)
    :else rst))

(defn- resolve-thinking-level [level]
  (cond
    (nil? level) rst
    (number? level) (ansi-256 level)
    (string? level) (resolve-color level)
    (vector? level) (apply ansi-truecolor level)
    :else rst))

;; ─── Theme construction ────────────────────────────────────────────────────

(defn make-theme
  "Create a Theme record from a map of color specifications.
   Keys are keyword color names, values can be:
     - nil (use reset)
     - number (256-color index)
     - string (named ANSI color or hex #RRGGBB)
     - vector [r g b] (truecolor)"
  [name color-map]
  (let [resolve (fn [k default]
                  (resolve-color (get color-map k default)))
        resolve-bg (fn [k default]
                     (resolve-bg-color (get color-map k default)))
        resolve-tl (fn [levels]
                     (mapv resolve-thinking-level levels))]
    (map->Theme {:name (name name)
                 :text (resolve :text nil)
                 :accent (resolve :accent "cyan")
                 :muted (resolve :muted "bright-black")
                 :dim (resolve :dim "dim")
                 :success (resolve :success "green")
                 :error (resolve :error "red")
                 :warning (resolve :warning "yellow")
                 :border (resolve :border "bright-black")
                 :border-accent (resolve :border-accent "cyan")
                 :border-muted (resolve :border-muted "black")
                 :user-bg (resolve-bg :user-bg nil)
                 :assistant-bg (resolve-bg :assistant-bg nil)
                 :tool-title (resolve :tool-title "yellow")
                 :tool-output (resolve :tool-output nil)
                 :md-heading (resolve :md-heading "bold")
                 :md-link (resolve :md-link "cyan")
                 :md-code (resolve :md-code "green")
                 :md-block (resolve :md-block "bright-black")
                 :selected-bg (resolve-bg :selected-bg "bright-black")
                 :thinking-levels (resolve-tl (get color-map :thinking-levels []))})))

;; ─── Default dark theme ────────────────────────────────────────────────────

(def dark-theme
  (make-theme "dark"
    {:text nil
     :accent "cyan"
     :muted "bright-black"
     :dim "dim"
     :success "green"
     :error "red"
     :warning "yellow"
     :border "bright-black"
     :border-accent "cyan"
     :border-muted "black"
     :user-bg nil
     :assistant-bg nil
     :tool-title "yellow"
     :tool-output nil
     :md-heading "bold"
     :md-link "cyan"
     :md-code "green"
     :md-block "bright-black"
     :selected-bg "bright-black"
     :thinking-levels [240 245 250 255]}))

(def light-theme
  (make-theme "light"
    {:text nil
     :accent "blue"
     :muted "bright-black"
     :dim "dim"
     :success "green"
     :error "red"
     :warning "yellow"
     :border "bright-black"
     :border-accent "blue"
     :border-muted "bright-white"
     :user-bg nil
     :assistant-bg nil
     :tool-title "magenta"
     :tool-output nil
     :md-heading "bold"
     :md-link "blue"
     :md-code "green"
     :md-block "bright-black"
     :selected-bg "bright-white"
     :thinking-levels [250 245 240 235]}))

;; ─── Theme registry ─────────────────────────────────────────────────────────

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

;; ─── Theme file loading ────────────────────────────────────────────────────

(defn load-themes-from-dir
  "Load all .edn theme files from a directory."
  [dir]
  (let [d (io/file dir)]
    (when (.isDirectory d)
      (doseq [f (.listFiles d (fn [_ name] (.endsWith name ".edn")))]
        (try
          (let [data (edn/read-string (slurp f))
                name (.getName f)
                theme-name (clojure.string/replace name #"\.edn$" "")
                theme (make-theme theme-name data)]
            (register-theme! theme))
          (catch Exception e
            (binding [*out* *err*]
              (println "Warning: Failed to load theme" (.getName f) ":" (.getMessage e)))))))))
