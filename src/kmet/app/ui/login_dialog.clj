(ns kmet.app.ui.login-dialog
  "Login flow dialog (pi: modes/interactive/components/login-dialog.ts
   LoginDialogComponent): dock-mounted dialog that replaces the editor
   during an OAuth/API-key login — bold accent \"Login to <provider>\" title,
   a dynamic content area (auth URL, device code, progress, waiting), and a
   one-line input the flows prompt through. Escape aborts the flow."
  (:require [babashka.process :as p]
            [clojure.string :as str]
            [kmet.app.keybindings :as app-kb]
            [kmet.tui.components.container :as container]
            [kmet.tui.components.dynamic-border :as db]
            [kmet.tui.components.input :as input]
            [kmet.tui.components.spacer :as spacer]
            [kmet.tui.components.text :as text]
            [kmet.tui.core :as tui]
            [kmet.tui.keybindings :as kb]
            [kmet.tui.macros :refer [defcomponent]]
            [kmet.tui.protocols :as protocols]
            [kmet.tui.theme :as theme]
            [kmet.tui.utils :as u]))

;; ─── Browser (pi utils/open-browser.ts) ────────────────────────────────────

(defn open-browser
  "Open a URL/file with the platform default handler (pi openBrowser):
   macOS `open`, Windows rundll32 handler, else xdg-open. Best-effort —
   launcher failures are swallowed (the caller shows the target anyway)."
  [target]
  (let [os (str/lower-case (str (System/getProperty "os.name")))
        cmd (cond
              (str/includes? os "mac") ["open" target]
              (str/includes? os "win") ["rundll32" "url.dll,FileProtocolHandler" target]
              :else ["xdg-open" target])]
    (try
      ;; :continue — a missing launcher must not throw (pi swallows the
      ;; spawn error event)
      (p/shell {:out :ignore :err :ignore :continue true} cmd)
      (catch Exception _ nil))))

;; ─── Content helpers ───────────────────────────────────────────────────────

(defn- add-content!
  "Append CHILD to the dialog's content area."
  [d child]
  (container/container-add-child (:content-container d) child))

(defn- repaint!
  "Request a TUI render after mutating the dialog off the input thread (pi:
   every LoginDialogComponent.show* ends with this.tui.requestRender())."
  [d]
  (when-let [tui (:tui d)]
    (tui/tui-request-render tui))
  nil)

(defn- linked-url-line
  "Accent OSC 8-hyperlinked URL (pi showAuth/showDeviceCode first line)."
  [th url]
  (text/make-text (theme/fg th :accent (u/osc-hyperlink url url)) 1 0))

(defn- click-hint-line
  "Dim hyperlink carrying the platform click hint (pi clickHint)."
  [th url]
  (text/make-text
   (theme/fg th :dim (u/osc-hyperlink url "Ctrl+click to open")) 1 0))

(defn- cancel-hint-line
  "(escape to cancel) hint line, pi keyHint styling."
  []
  (text/make-text
   (str "(" (app-kb/key-hint "tui.select.cancel" "to cancel") ")") 1 0))

(defn- submit-hint-line
  "(escape to cancel, enter to submit) hint line."
  []
  (text/make-text
   (str "(" (app-kb/key-hint "tui.select.cancel" "to cancel,") " "
        (app-kb/key-hint "tui.select.confirm" "to submit") ")") 1 0))

;; ─── Flow states (pi showAuth / showDeviceCode / showPrompt / ...) ─────────

(defn login-dialog-show-auth!
  "Show an OAuth authorize URL (+ optional instructions) and open it in the
   platform browser (pi showAuth)."
  [d url instructions]
  (container/container-clear (:content-container d))
  (let [th (theme/get-current-theme)]
    (add-content! d (spacer/make-spacer 1))
    (add-content! d (linked-url-line th url))
    (add-content! d (click-hint-line th url))
    (when instructions
      (add-content! d (spacer/make-spacer 1))
      (add-content! d (text/make-text (theme/fg th :warning instructions) 1 0))))
  (open-browser url)
  (repaint! d))

(defn login-dialog-show-device-code!
  "Show the device-flow verification URI + user code (pi showDeviceCode)."
  [d verification-uri user-code]
  (container/container-clear (:content-container d))
  (let [th (theme/get-current-theme)]
    (add-content! d (spacer/make-spacer 1))
    (add-content! d (linked-url-line th verification-uri))
    (add-content! d (click-hint-line th verification-uri))
    (add-content! d (spacer/make-spacer 1))
    (add-content! d (text/make-text (theme/fg th :warning (str "Enter code: " user-code)) 1 0)))
  (repaint! d))

(defn login-dialog-show-info!
  "Append provider-owned informational text (pi showInfo)."
  [d message]
  (let [th (theme/get-current-theme)]
    (add-content! d (spacer/make-spacer 1))
    (add-content! d (text/make-text (theme/fg th :text message) 1 0)))
  (repaint! d))

(defn login-dialog-show-waiting!
  "Append a dim waiting message + cancel hint (pi showWaiting — polling
   flows like GitHub Copilot)."
  [d message]
  (let [th (theme/get-current-theme)]
    (add-content! d (spacer/make-spacer 1))
    (add-content! d (text/make-text (theme/fg th :dim message) 1 0))
    (add-content! d (cancel-hint-line)))
  (repaint! d))

(defn login-dialog-show-progress!
  "Append a dim progress line (pi showProgress)."
  [d message]
  (add-content! d (text/make-text (theme/fg (theme/get-current-theme) :dim message) 1 0))
  (repaint! d))

(defn- replace-input-with-submitted-text!
  "Swap the input row for a `> value` transcript line after submission (pi
   replaceInputWithSubmittedText)."
  [d value]
  (let [cc (:content-container d)
        input-comp (:input-comp d)
        submitted (text/make-text (str "> " value) 0 0)]
    (container/container-set-children!
     cc (mapv #(if (identical? % input-comp) submitted %) @(:children cc)))))

(defn- resolve-input!
  "Deliver VALUE to the pending prompt promise, if any (pi input.onSubmit)."
  [d value]
  (when-let [resolve @(:input-resolver-atom d)]
    (reset! (:input-resolver-atom d) nil)
    (replace-input-with-submitted-text! d value)
    (resolve value)))

(defn login-dialog-cancel!
  "Abort the login flow: settle any pending prompt as cancelled and fire
   on-complete (pi cancel)."
  [d]
  (when-let [reject @(:input-rejecter-atom d)]
    (reset! (:input-resolver-atom d) nil)
    (reset! (:input-rejecter-atom d) nil)
    (reject (ex-info "Login cancelled" {:type :login-cancelled})))
  (when-let [cb @(:on-complete-atom d)]
    (cb false "Login cancelled"))
  nil)

(defn- detach-input!
  "pi Container.addChild MOVES a component out of its previous parent;
   kmet containers allow one child in several parents, so remove the input
   from the top-level children explicitly before appending it to the
   content area."
  [d]
  (let [c (:container d)
        inp (:input-comp d)]
    (when (some #(identical? inp %) @(:children c))
      (container/container-set-children!
       c (vec (remove #(identical? inp %) @(:children c)))))))

(defn- show-input-prompt!
  "Common tail of show-prompt!/show-manual-input!: append MESSAGE rows + the
   input + hint, clear the input, return a promise delivering the submitted
   string (or the cancellation ex-info)."
  [d rows hint-line]
  (detach-input! d)
  (doseq [r rows] (add-content! d r))
  (add-content! d (:input-comp d))
  (add-content! d hint-line)
  (input/input-set-value! (:input-comp d) "")
  (reset! (:cursor-atom (:input-comp d)) 0)
  (let [p (promise)]
    (reset! (:input-resolver-atom d) #(deliver p %))
    (reset! (:input-rejecter-atom d) #(deliver p %))
    (repaint! d)
    p))

(defn await-prompt!
  "Deref a show-prompt!/show-manual-input! promise; rethrows the
   \"Login cancelled\" ex-info when the dialog was cancelled instead of
   submitted."
  [p]
  (let [v @p]
    (if (instance? Exception v)
      (throw v)
      v)))

(defn login-dialog-show-prompt!
  "Prompt for text inside the dialog (pi showPrompt): message, optional dim
   `e.g., placeholder` line, the input, and the cancel/submit hint. Returns
   a promise of the entered string. Appends below existing content so an
   auth URL shown earlier stays visible."
  [d message placeholder]
  (let [th (theme/get-current-theme)
        rows (cond-> [(spacer/make-spacer 1)
                      (text/make-text (theme/fg th :text message) 1 0)]
               (seq placeholder) (conj (text/make-text (theme/fg th :dim (str "e.g., " placeholder)) 1 0)))]
    (show-input-prompt! d rows (submit-hint-line))))

(defn login-dialog-show-manual-input!
  "Manual code/URL entry for callback-server providers (pi showManualInput):
   dim prompt text + input + cancel hint; returns a promise of the string."
  [d prompt]
  (let [th (theme/get-current-theme)]
    (show-input-prompt! d
                        [(spacer/make-spacer 1)
                         (text/make-text (theme/fg th :dim prompt) 1 0)]
                        (cancel-hint-line))))

;; ─── Component ─────────────────────────────────────────────────────────────

(defcomponent LoginDialog nil
              [container content-container input-comp tui input-resolver-atom
               input-rejecter-atom on-complete-atom focused? cache-atom]

  (render [this width] (protocols/render (:container this) width))

  (handle-input [this data]
    (if (kb/matches-key (kb/get-global-keybindings) data "tui.select.cancel")
      (login-dialog-cancel! this)
      (protocols/handle-input (:input-comp this) data))))

(extend-type LoginDialog
  protocols/IFocusable
  (focused [this] @(:focused? this))
  (set-focused! [this val]
    (reset! (:focused? this) val)
    (protocols/set-focused! (:input-comp this) val)))

(defn make-login-dialog
  "Create the login dialog for PROVIDER-NAME (pi LoginDialogComponent).
   ON-COMPLETE fires (fn [success message]) when the user escapes. TUI is
   the tui instance (request-render after prompt resolution)."
  [tui provider-name on-complete]
  (let [th (theme/get-current-theme)
        c (container/make-container)
        cc (container/make-container)
        inp (input/make-input)
        add (fn [child] (container/container-add-child c child))
        d (map->LoginDialog
           {:container c
            :content-container cc
            :input-comp inp
            :tui tui
            :input-resolver-atom (atom nil)
            :input-rejecter-atom (atom nil)
            :on-complete-atom (atom on-complete)
            :focused? (atom false)
            :cache-atom (atom nil)})]
    (add (db/make-dynamic-border #(theme/fg th :accent %)))
    (add (text/make-text (theme/fg th :accent (theme/bold (str "Login to " provider-name))) 1 0))
    (add cc)
    (add inp)
    (add (db/make-dynamic-border #(theme/fg th :accent %)))
    (input/input-set-on-submit! inp #(resolve-input! d %))
    (input/input-set-on-escape! inp #(login-dialog-cancel! d))
    d))
