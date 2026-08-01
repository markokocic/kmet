#!/usr/bin/env python3
"""Run a command in a pty (fixed size), optionally send input, capture all
output bytes to a file. Kills the whole process group on timeout."""
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
    ap.add_argument("--text", default="")
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
    send_text = args.text.replace("\\n", "\n")
    out = open(args.out, "wb")
    start = time.time()
    sent = False
    try:
        while time.time() - start < args.timeout:
            r, _, _ = select.select([fd], [], [], 0.05)
            if r:
                try:
                    data = os.read(fd, 65536)
                except OSError:
                    break
                if not data:
                    break
                out.write(data)
                out.flush()
            if not sent and time.time() - start >= args.send_after:
                os.write(fd, send_text.encode())
                sent = True
    finally:
        try:
            os.killpg(os.getpgid(pid), signal.SIGTERM)
        except Exception:
            pass
        time.sleep(0.3)
        try:
            os.killpg(os.getpgid(pid), signal.SIGKILL)
        except Exception:
            pass
        try:
            while True:
                r, _, _ = select.select([fd], [], [], 0.3)
                if not r:
                    break
                data = os.read(fd, 65536)
                if not data:
                    break
                out.write(data)
                out.flush()
        except OSError:
            pass
        out.close()
        try:
            os.waitpid(pid, 0)
        except Exception:
            pass
        print("captured -> %s" % args.out)


if __name__ == "__main__":
    main()
