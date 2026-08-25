(ns extensions.lsp-adapter.lsp
  "LSP protocol layer over kmet.libs.jsonrpc: initialize handshake,
   conservative client capabilities, the server→client request/notification
   policy, and document-sync message builders. Pure protocol — connection
   ownership lives in runtime."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [kmet.libs.jsonrpc :as jrpc]))

(defn path->uri
  "file:// URI for PATH with correct percent-encoding (spaces, unicode) —
   fs/path.toUri interop, no java.nio imports."
  [path]
  (str (.toUri (fs/path (str path)))))

(defn uri->path
  "FILE-URI back to a filesystem path (percent-decoded)."
  [uri]
  (let [bare (cond
               (str/starts-with? uri "file://") (subs uri 7)
               :else uri)]
    (java.net.URLDecoder/decode bare "UTF-8")))

;; ─── Handshake ────────────────────────────────────────────────────────────

(def ^:private client-capabilities
  "Conservative and static: full-text sync assumed, push diagnostics
   wanted, workspace configuration answered. Servers needing more degrade
   gracefully."
  {:textDocument {:synchronization {:dynamicRegistration false}
                  :publishDiagnostics {:relatedInformation false
                                       :versionSupport true}}
   :workspace {:configuration true
               :workspaceFolders true
               :didChangeConfiguration {:dynamicRegistration false}}})

(defn initialize-params
  "initialize request payload. PID ties the server to our process (many
   servers exit when we die); sent as both rootUri and workspaceFolders —
   rootUri is deprecated but every shipping server still reads it."
  [root pid init-options]
  (let [root-uri (path->uri root)]
    {:processId pid
     :rootUri root-uri
     :rootPath (str root)
     :capabilities client-capabilities
     :initializationOptions (or init-options {})
     :workspaceFolders [{:uri root-uri :name (str (fs/file-name root))}]}))

(defn start!
  "Connects + handshakes a stdio LSP server: jsonrpc connect → initialize
   (INITIALIZE-TIMEOUT-MS) → initialized notification. Returns the lib
   conn; throws on any failure (runtime records it)."
  [{:keys [command env cwd on-notification on-request]}
   root init-options timeout-ms]
  (let [conn (jrpc/connect-stdio
              {:command command :env env :cwd cwd :framing :content-length
               :on-notification on-notification :on-request on-request})
        result (try
                 (jrpc/request! conn "initialize"
                                (initialize-params root
                                                   (:pid conn)
                                                   init-options)
                                {:timeout-ms timeout-ms})
                 (catch Exception e
                   (jrpc/close! conn)
                   (throw e)))]
    (jrpc/notify! conn "initialized" {})
    ;; server-info rides on the lib conn itself - callers pass the conn
    ;; around as one opaque value
    (assoc conn :server-info (:serverInfo result))))

(def shutdown-dance
  "The polite LSP close sequence passed to jrpc/close!'s :graceful."
  {:request "shutdown" :notification "exit"})

;; ─── Server→client policy ────────────────────────────────────────────────

(defn make-on-request
  "Answers the requests servers legally send during a session.
   workspace/applyEdit is refused by policy: servers must never write
   through us — edits flow exclusively through kmet's own edit tools.
   Unknown methods return nil ⇒ the lib replies -32601."
  []
  (fn [method _params]
    (case method
      "workspace/configuration" []                       ; defaults per item
      "client/registerCapability" {}                     ; accept, ignore
      "window/workDoneProgress/create" {}
      "workspace/applyEdit" {:applied false
                             :failureReason "kmet edits files itself"}
      nil)))

;; ─── Document-sync builders (full text always) ───────────────────────────

(defn did-open
  "textDocument/didOpen — VERSION starts at 1."
  [path language-id text]
  {:textDocument {:uri (path->uri path)
                  :languageId language-id
                  :version 1
                  :text text}})

(defn did-change-full
  "Full-text didChange; re-reading disk per touch picks up kmet's own
   edits without watching the filesystem."
  [path version text]
  {:textDocument {:uri (path->uri path) :version version}
   :contentChanges [{:text text}]})

(defn did-close [path]
  {:textDocument {:uri (path->uri path)}})

(defn text-document-position
  "Shared {textDocument, position} params; LINE/CHARACTER arrive 1-based
   from the tool boundary and convert to the wire format exactly here."
  ([path line character]
   {:textDocument {:uri (path->uri path)}
    :position {:line (dec line) :character (dec character)}}))
