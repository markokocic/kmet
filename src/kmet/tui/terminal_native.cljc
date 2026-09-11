(ns kmet.tui.terminal-native
  "Jolt terminal backend: raw mode, bounded reads and live size over jolt.ffi.

   Unix (Linux/macOS/Android): termios raw mode (tcgetattr → cfmakeraw →
   tcsetattr, restored on stop and on a registered shutdown hook), poll(2) +
   read(2) for reads bounded by a timeout, and ioctl(TIOCGWINSZ) for the
   live size. No subprocesses, no libraries beyond libc. Multi-byte UTF-8
   split across read buffers is reassembled by kmet.libs.terminal/utf8-decode
   with a per-terminal tail (a read boundary is not a character boundary).

   Windows is not implemented yet: create-terminal throws with the design
   note, so the Windows port has one landing spot — kernel32
   GetConsoleMode/SetConsoleMode with ENABLE_VIRTUAL_TERMINAL_INPUT for raw
   mode, WaitForSingleObject + ReadFile for bounded reads, and
   GetConsoleScreenBufferInfo for the size (jolt-tui.md §6; the same three
   calls pi's win32-platform.c makes). The jolt FFI surface is also thinner
   on some Windows machine types (process.ss), which is why the POSIX half
   lands first.

   Loaded at runtime by kmet.tui.terminal/create-terminal, and only on Jolt:
   the whole implementation sits in the file's single #?(:jolt ...) branch,
   so bb/JVM loads this namespace empty and never touches jolt.ffi."
  #?(:jolt
     (:require [clojure.string :as str]
               [kmet.libs.terminal :as lib]
               [kmet.tui.terminal :as term]
               [jolt.ffi :as ffi])))

#?(:jolt
   (do
     ;; ─── libc bindings (POSIX) ─────────────────────────────────────────────
     ;; Symbols resolve per call (a missing one throws on call, not at load),
     ;; so a Windows load reaches the clear error in create-terminal first.
     (ffi/defcfn ^:private c-tcgetattr "tcgetattr" [:int :pointer] :int)
     (ffi/defcfn ^:private c-tcsetattr "tcsetattr" [:int :int :pointer] :int)
     (ffi/defcfn ^:private c-cfmakeraw "cfmakeraw" [:pointer] :void)
     (ffi/defcfn ^:private c-poll "poll" [:pointer :ulong :int] :int :blocking)
     (ffi/defcfn ^:private c-read "read" [:int :pointer :size_t] :ssize_t :blocking)
     (ffi/defcfn ^:private c-write "write" [:int :pointer :size_t] :ssize_t :blocking)
     (ffi/defcfn ^:private c-strlen "strlen" [:pointer] :size_t)
     (ffi/defcfn ^:private c-ioctl "ioctl" [:int :ulong :&] :int)

     ;; ─── Constants ─────────────────────────────────────────────────────────
     (def ^:private stdin-fd 0)
     (def ^:private stdout-fd 1)
     (def ^:private tcsa-now 0)
     (def ^:private eintr 4)
     ;; Big enough for every termios flavour (Linux 60B, macOS ~72B).
     (def ^:private termios-size 256)
     (def ^:private read-buf-size 1024)
     (def ^:private pollin 1)
     ;; POLLERR|POLLHUP|POLLNVAL — the pty is gone; reading would return EOF
     ;; or an error forever.
     (def ^:private pollerr 0x38)

     (def ^:private pollfd-layout
       "struct pollfd { int fd; short events; short revents; } — 8 bytes on
        Linux and macOS."
       (ffi/layout [:struct [[:fd :int] [:events :short] [:revents :short]]]))

     (def ^:private tiocgwinsz
       "ioctl request for struct winsize {ws_row, ws_col, ...}."
       (if (str/includes? (str (System/getProperty "os.name")) "Mac")
         0x40087468
         0x5413))

     (defn- windows? []
       (str/includes? (str/lower-case (str (System/getProperty "os.name"))) "win"))

     ;; ─── Writes ────────────────────────────────────────────────────────────

     (defn- write-all!
       "write(2) the N bytes at P to FD, retrying EINTR and short writes."
       [fd p n]
       (loop [off 0]
         (let [w (c-write fd (+ p off) (- n off))]
           (cond
             (pos? w) (when (< (+ off w) n) (recur (+ off w)))
             (zero? w) nil
             (= eintr (ffi/errno)) (recur off)
             :else (throw (ex-info "kmet.tui.terminal-native: write failed"
                                   {:type :terminal-native-write
                                    :errno (ffi/errno)}))))))

     (defn- write-string! [fd s]
       (when s
         (ffi/with-c-string [p s]
           (let [n (c-strlen p)]
             (when (pos? n)
               (write-all! fd p n))))))

     ;; ─── Raw mode (termios) ────────────────────────────────────────────────

     (defn- claim-restore!
       "Atomically take the saved-termios pointer, so stop! and the shutdown
        hook can never both restore/free it."
       [term]
       (let [ref (:restore-ref term)
             saved @ref]
         (when (and saved (compare-and-set! ref saved nil))
           saved)))

     (defn- restore-cooked! [term]
       (when-let [saved (claim-restore! term)]
         (c-tcsetattr (:in-fd term) tcsa-now saved)
         (ffi/free saved)))

     (defn- enable-raw! [term]
       (when-not @(:running-ref term)
         (let [saved (ffi/alloc termios-size)]
           (when-not (zero? (c-tcgetattr (:in-fd term) saved))
             (let [e (ffi/errno)]
               (ffi/free saved)
               (throw (ex-info (str "kmet.tui.terminal-native: tcgetattr failed — "
                                    "stdin is not a tty")
                               {:type :terminal-native-not-a-tty
                                :errno e}))))
           (ffi/with-alloc [raw termios-size]
             (ffi/copy saved raw termios-size)
             (c-cfmakeraw raw)
             (when-not (zero? (c-tcsetattr (:in-fd term) tcsa-now raw))
               (let [e (ffi/errno)]
                 (ffi/free saved)
                 (throw (ex-info "kmet.tui.terminal-native: tcsetattr(raw) failed"
                                 {:type :terminal-native-raw-mode
                                  :errno e})))))
           (reset! (:restore-ref term) saved)
           (reset! (:running-ref term) true)
           ;; Bracketed paste, as the JLine backend does in start! — pastes then
           ;; arrive wrapped in 200~/201~ instead of as raw bursts the paste
           ;; heuristics have to guess at.
           (write-string! (:out-fd term) lib/BRACKETED-PASTE-ON)))
       term)

     (defn- shutdown!
       "stop! body: bracketed paste off, raw mode off, restore the cooked
        termios, drop buffered input. Runs under the record's monitor so a
        read already inside read-chars! finishes BEFORE the restore — the
        reverse order would let it consume input typed after the terminal
        went back to the shell. Idempotent and silent when never started
        (the shutdown hook runs on every exit)."
       [term]
       (locking term
         (let [was-running? @(:running-ref term)]
           (reset! (:running-ref term) false)
           (when was-running?
             (write-string! (:out-fd term) lib/BRACKETED-PASTE-OFF))
           (restore-cooked! term)
           (reset! (:pending term) [])
           (reset! (:carry term) [])))
       term)

     ;; ─── Reads (poll + read + UTF-8) ───────────────────────────────────────

     (defn- poll-status
       "poll(2) IN-FD for TIMEOUT-MS: :data when readable, :hangup when the
        pty is gone (POLLHUP/POLLERR/POLLNVAL — a busy loop otherwise, since
        poll returns immediately forever), :timeout otherwise."
       [fd timeout-ms]
       (ffi/with-alloc [pfd (ffi/sizeof pollfd-layout)]
         (ffi/write-field pfd pollfd-layout [:fd] fd)
         (ffi/write-field pfd pollfd-layout [:events] pollin)
         (let [rc (c-poll pfd 1 timeout-ms)]
           (if (pos? rc)
             (let [revents (ffi/read-field pfd pollfd-layout [:revents])]
               (cond
                 (pos? (bit-and revents pollin)) :data
                 (pos? (bit-and revents pollerr)) :hangup
                 :else :timeout))
             :timeout))))

     (defn- pop-pending!
       "Take the next buffered code point, or nil. Atomic: the reader thread
        and the drain-on-exit path can both call read-input while shutdown
        winds down."
       [term]
       (let [[old _] (swap-vals! (:pending term)
                                 (fn [p] (if (seq p) (subvec p 1) p)))]
         (when (seq old)
           (nth old 0))))

     (defn- read-chars!
       "Poll IN-FD for at most TIMEOUT-MS, read up to one bufferful, decode
        UTF-8 (a partial trailing sequence is kept in :carry). Answers the
        code points; nil when nothing arrived (a dead pty backs off instead
        of spinning). Serialized by the record's monitor: the reader thread
        and drain-on-exit can both call read-input, and unserialized they
        could BOTH poll the same byte ready — the loser's blocking read(2)
        would then wait for a keypress that never comes, hanging stop!
        before it restores the terminal."
       [term timeout-ms]
       (locking term
         (if-not @(:running-ref term)
           ;; A stop! that landed while the caller was on its way here: never
           ;; read cooked-mode input bound for the shell.
           nil
           (case (poll-status (:in-fd term) timeout-ms)
             :data
             (ffi/with-alloc [buf read-buf-size]
               (let [n (c-read (:in-fd term) buf read-buf-size)]
                 (if (pos? n)
                   (let [[cps tail] (lib/utf8-decode (into @(:carry term)
                                                           (ffi/read-array buf n)))]
                     (reset! (:carry term) tail)
                     cps)
                   ;; EOF on the tty: don't spin on a permanent POLLHUP.
                   (do (Thread/sleep 20) nil))))

             :hangup
             (do (Thread/sleep 20) nil)

             :timeout
             nil))))

     (defn- read-char!
       [term timeout-ms]
       (or (pop-pending! term)
           (if-not @(:running-ref term)
             ;; Before start! (the reader thread begins first) reading would
             ;; consume cooked-mode input — wait out the timeout instead.
             (do (Thread/sleep (max 1 (min 100 timeout-ms))) -1)
             (let [cps (read-chars! term timeout-ms)]
               (if (seq cps)
                 (do (reset! (:pending term) (subvec cps 1))
                     (nth cps 0))
                 -1)))))

     ;; ─── Size ──────────────────────────────────────────────────────────────

     (defn- terminal-size
       "Live {rows cols} from TIOCGWINSZ, or nil when the ioctl fails."
       [fd]
       (ffi/with-alloc [ws 8]
         (when (zero? (c-ioctl fd tiocgwinsz ws))
           (let [rows (ffi/read ws :uint16)
                 cols (ffi/read ws :uint16 2)]
             (when (and (pos? rows) (pos? cols))
               {:rows rows :cols cols})))))

     (defn- env-size
       "COLUMNS/LINES fallback (pi: process.stdout.columns || env || 80/24)."
       [name fallback]
       (let [v (parse-long (str (System/getenv name)))]
         (if (and v (pos? v)) v fallback)))

     ;; ─── Backend ───────────────────────────────────────────────────────────

     (defrecord NativeTerminal [in-fd out-fd running-ref restore-ref pending carry
                                progress-interval-atom]
       term/ITerminal
       (start! [this _ _] (enable-raw! this))
       (stop! [this] (shutdown! this))
       (started? [this] (boolean @(:running-ref this)))
       (write-output [this s]
         (write-string! (:out-fd this) s)
         (lib/write-log! s))
       (read-input [this timeout-ms] (read-char! this timeout-ms))
       (columns [this]
         (or (:cols (terminal-size (:in-fd this)))
             (env-size "COLUMNS" 80)))
       (rows [this]
         (or (:rows (terminal-size (:in-fd this)))
             (env-size "LINES" 24)))
       (set-progress! [this active]
         (term/apply-progress! this (:progress-interval-atom this) active)))

     (defn create-terminal
       "A NativeTerminal over the process's stdin/stdout. Raw mode is entered
        by start!, so creation has no terminal side effects."
       []
       (when (windows?)
         (throw (ex-info (str "kmet.tui.terminal-native: no Windows implementation yet — "
                              "bind kernel32 GetConsoleMode/SetConsoleMode + "
                              "ENABLE_VIRTUAL_TERMINAL_INPUT, WaitForSingleObject + "
                              "ReadFile and GetConsoleScreenBufferInfo (jolt-tui.md §6)")
                         {:type :terminal-native-windows-unsupported})))
       (let [term (map->NativeTerminal {:in-fd stdin-fd
                                        :out-fd stdout-fd
                                        :running-ref (atom false)
                                        :restore-ref (atom nil)
                                        :pending (atom [])
                                        :carry (atom [])
                                        :progress-interval-atom (atom nil)})]
         ;; Classic failure mode: a crash in raw mode leaves the user's shell
         ;; echo-less. The hook restores on process exit; stop! is the normal
         ;; path and makes the hook a no-op (claim-restore! is atomic).
         (try
           (.addShutdownHook (Runtime/getRuntime)
                             (Thread. (fn [] (try (shutdown! term) (catch Throwable _ nil)))))
           (catch Throwable _ nil))
         term))))
