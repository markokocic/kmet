#!/usr/bin/env python3
"""Run a command in a pty, then type text character-by-character with human-ish
delays. Two ways to schedule input:
  --send-after S --text "hello\\n"       — start typing at S seconds
  --schedule "0:!sleep 1\\n;2600:hello"  — semicolon list of ms:text segments,
                                          each typed char-by-char (\\n = Enter)
Captures all output bytes to --out."""
import argparse
import fcntl
import os
import pty
import select
import signal
import struct
import termios
import time


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--cols", type=int, default=80)
    ap.add_argument("--rows", type=int, default=24)
    ap.add_argument("--send-after", type=float, default=2.0)
    ap.add_argument("--delay", type=float, default=45.0)
    ap.add_argument("--text", default="")
    ap.add_argument("--schedule", default="",
                    help="semicolon list of ms-after-start:text segments")
    ap.add_argument("--timeout", type=float, default=30.0)
    ap.add_argument("--out", required=True)
    ap.add_argument("cmd", nargs=argparse.REMAINDER)
    args = ap.parse_args()

    cmd = args.cmd
    pid, fd = pty.fork()
    if pid == 0:
        os.environ["TERM"] = "xterm-256color"
        os.environ["NO_COLOR"] = ""
        try:
            os.setsid()
        except Exception:
            pass
        try:
            os.execvp(cmd[0], cmd)
        except Exception as e:
            os.write(2, ("exec failed: %s\n" % e).encode())
            os._exit(127)

    fcntl.ioctl(fd, termios.TIOCSWINSZ, struct.pack("HHHH", args.rows, args.cols, 0, 0))
    # schedule entries: [absolute-seconds, chars, done?]
    sched = []
    if args.schedule:
        for seg in args.schedule.split(";"):
            ms, _, txt = seg.partition(":")
            sched.append([float(ms) / 1000.0, txt.replace("\\n", "\n"), False])
    main_text = [args.send_after, args.text.replace("\\n", "\n"), False]
    out = open(args.out, "wb")
    start = time.time()
    try:
        while time.time() - start < args.timeout:
            r, _, _ = select.select([fd], [], [], 0.02)
            if r:
                try:
                    data = os.read(fd, 65536)
                except OSError:
                    break
                if not data:
                    break
                out.write(data)
                out.flush()
            for entry in sched + [main_text]:
                if not entry[2] and entry[1] and time.time() - start >= entry[0]:
                    entry[2] = True
                    for ch in entry[1]:
                        os.write(fd, ch.encode())
                        time.sleep(args.delay / 1000.0)
    finally:
        try:
            os.killpg(os.getpgid(pid), signal.SIGKILL)
        except Exception:
            pass
        out.close()


if __name__ == "__main__":
    main()
