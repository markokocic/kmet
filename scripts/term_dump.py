#!/usr/bin/env python3
"""Replay raw pty capture through a minimal ANSI terminal emulator and dump
frames. Frames are dumped at each CSI 2026 sync-end boundary plus the final
state. Output format:

  == t=12.345 sync=N rows=24 buffer=123 ==
  text line 1
  text line 2
  ...

Each frame shows ALL lines in the buffer (scrollback + visible screen),
separated: scrollback lines, then a '--- screen ---' divider, then visible.

Also prints a color annotation line under each text line: per-cell color code.
Colors: K R G Y B M C W = fg 30-37; k r g y b m c w = fg 90-97; . = default.
Bold text prefixed with *.
"""
import argparse
import re
import sys
import time

# SGR color char map
FG = {
    "30": "K", "31": "R", "32": "G", "33": "Y", "34": "B",
    "35": "M", "36": "C", "37": "W",
    "90": "k", "91": "r", "92": "g", "93": "y", "94": "b",
    "95": "m", "96": "c", "97": "w",
}
BG = {
    "40": "K", "41": "R", "42": "G", "43": "Y", "44": "B",
    "45": "M", "46": "C", "47": "W",
    "100": "k", "101": "r", "102": "g", "103": "y", "104": "b",
    "105": "m", "106": "c", "107": "w",
}

def rgb_char(r, g, b):
    """Rough name for a 24-bit color."""
    if r > 200 and g > 200 and b < 100:
        return "Y"          # yellow
    if r < 120 and g > 140 and b > 140:
        return "C"          # cyan/teal
    if r > 200 and g < 140 and b < 140:
        return "R"
    if g > 150 and r < 150 and b < 150:
        return "G"
    if b > 150 and r < 150 and g < 150:
        return "B"
    if r > 200 and g > 150 and b < 150:
        return "O"          # orange
    if r > 150 and g < 120 and b > 150:
        return "M"
    if abs(r - g) < 40 and abs(g - b) < 40:
        return "k" if r < 120 else "w"   # gray
    return "?"

class Cell:
    __slots__ = ("ch", "fg", "bg", "bold")
    def __init__(self, ch=" ", fg="", bg="", bold=False):
        self.ch = ch
        self.fg = fg
        self.bg = bg
        self.bold = bold

class Term:
    def __init__(self, rows, cols):
        self.rows = rows
        self.cols = cols
        self.buffer = []          # scrollback + screen; screen = last `rows`
        self.row = 0
        self.col = 0
        self.fg = ""
        self.bg = ""
        self.bold = False
        self.frames = []          # (t, lines, screen_start)
        self.t0 = time.time()
        self.sync = False
        # seed with blank screen
        for _ in range(rows):
            self.buffer.append([Cell() for _ in range(cols)])

    # ---- helpers ----
    def _ensure(self, r):
        while len(self.buffer) <= r:
            self.buffer.append([Cell() for _ in range(self.cols)])

    MAX_SCROLLBACK = 5000
    def _scroll(self):
        # push top of screen into scrollback: append a blank at the bottom;
        # the line above the screen stays in the buffer (scrollback).
        if len(self.buffer) >= self.MAX_SCROLLBACK:
            del self.buffer[0]
        self.buffer.append([Cell() for _ in range(self.cols)])
        self.row = self.rows - 1

    def crlf(self):
        if self.row == self.rows - 1:
            self._scroll()
        else:
            self.row += 1
        self.col = 0

    def write_text(self, s):
        for ch in s:
            if ch == "\r":
                self.col = 0
            elif ch == "\n":
                self.crlf()
            elif ch == "\b":
                if self.col > 0:
                    self.col -= 1
            elif ch == "\x07":
                pass
            else:
                if self.col >= self.cols:
                    self.crlf()
                if self.col < self.cols:
                    idx = len(self.buffer) - self.rows + self.row
                    if idx >= len(self.buffer):
                        self._ensure(idx)
                        idx = len(self.buffer) - self.rows + self.row
                    rowbuf = self.buffer[idx]
                    rowbuf[self.col] = Cell(ch, self.fg, self.bg, self.bold)
                    self.col += 1

    def _rowbuf(self, r):
        idx = len(self.buffer) - self.rows + r
        if idx < 0:
            self._ensure(len(self.buffer) + (-idx))
            idx = len(self.buffer) - self.rows + r
        if idx >= len(self.buffer):
            self._ensure(idx)
        return self.buffer[idx]

    def clear_screen(self):
        for r in range(self.rows):
            rb = self._rowbuf(r)
            for c in range(self.cols):
                rb[c] = Cell()
        self.row = 0
        self.col = 0

    def clear_scrollback(self):
        # remove all lines above the screen
        self.buffer = self.buffer[-self.rows:] if len(self.buffer) >= self.rows else self.buffer

    def clear_line(self):
        rb = self._rowbuf(self.row)
        for c in range(self.cols):
            rb[c] = Cell()

    def clear_eol(self):
        rb = self._rowbuf(self.row)
        for c in range(self.col, self.cols):
            rb[c] = Cell()

    # ---- CSI ----
    def csi(self, params, final):
        if final == "A":
            n = int(params or "1")
            self.row = max(0, self.row - n)
        elif final == "B":
            n = int(params or "1")
            self.row = min(self.rows - 1, self.row + n)
        elif final == "C":
            n = int(params or "1")
            self.col = min(self.cols - 1, self.col + n)
        elif final == "D":
            n = int(params or "1")
            self.col = max(0, self.col - n)
        elif final == "G":
            self.col = min(self.cols - 1, max(0, int(params or "1") - 1))
        elif final == "H" or final == "f":
            parts = (params or ";").split(";")
            r = int(parts[0] or "1") - 1 if parts and parts[0] else 0
            c = int(parts[1] or "1") - 1 if len(parts) > 1 and parts[1] else 0
            self.row = min(self.rows - 1, max(0, r))
            self.col = min(self.cols - 1, max(0, c))
        elif final == "J":
            mode = int(params or "0")
            if mode == 2:
                self.clear_screen()
            elif mode == 3:
                self.clear_scrollback()
            elif mode == 0:
                self.clear_eol()
        elif final == "K":
            mode = int(params or "0")
            if mode == 2:
                self.clear_line()
            elif mode == 0:
                self.clear_eol()
            elif mode == 1:
                rb = self._rowbuf(self.row)
                for c in range(0, self.col + 1):
                    rb[c] = Cell()
        elif final == "m":
            self.sgr(params)
        elif final in ("h", "l"):
            # modes: 2026 sync, 25 cursor, 1049 alt screen — track sync only
            if "?2026" in (params or ""):
                self.sync = (final == "h")
                if final == "l":
                    self.snapshot()
        # else: ignore

    def sgr(self, params):
        if not params:
            self.fg = ""
            self.bg = ""
            self.bold = False
            return
        codes = params.split(";")
        i = 0
        while i < len(codes):
            c = codes[i]
            if c == "":
                c = "0"
            if c == "0":
                self.fg = ""; self.bg = ""; self.bold = False
            elif c == "1":
                self.bold = True
            elif c == "22":
                self.bold = False
            elif c in FG:
                self.fg = c
            elif c in BG:
                self.bg = c
            elif c == "38" and i + 2 < len(codes) and codes[i+1] == "5":
                self.fg = "38;" + codes[i+2]
                i += 2
            elif c == "38" and i + 3 < len(codes) and codes[i+1] == "2":
                self.fg = "38;2;" + codes[i+2] + ";" + codes[i+3] + ";" + codes[i+4]
                i += 4
            elif c == "48" and i + 2 < len(codes) and codes[i+1] == "5":
                self.bg = "48;" + codes[i+2]
                i += 2
            elif c == "48" and i + 3 < len(codes) and codes[i+1] == "2":
                self.bg = "48;2;" + codes[i+2] + ";" + codes[i+3] + ";" + codes[i+4]
                i += 4
            elif c == "39":
                self.fg = ""
            elif c == "49":
                self.bg = ""
            i += 1

    # ---- capture ----
    def snapshot(self):
        self.frames.append((time.time() - self.t0, self.render_lines()))

    def render_lines(self):
        lines = []
        for i, rowbuf in enumerate(self.buffer):
            text = []
            color = []
            for cell in rowbuf:
                text.append(cell.ch)
                if cell.fg.startswith("38;2;"):
                    parts = cell.fg.split(";")[2:]
                    ch = rgb_char(int(parts[0]), int(parts[1]), int(parts[2]))
                    color.append("*" + ch if cell.bold else ch)
                elif cell.bold:
                    color.append("*" + (FG.get(cell.fg, ".") if not cell.fg.startswith("38") else "?"))
                elif cell.fg.startswith("38"):
                    color.append("#")
                else:
                    color.append(FG.get(cell.fg, "."))
            lines.append(("".join(text), "".join(color)))
        return lines

    def process(self, data):
        i = 0
        n = len(data)
        while i < n:
            ch = data[i]
            if ch == "\x1b":
                if i + 1 < n and data[i+1] == "[":
                    j = i + 2
                    while j < n and not ("@" <= data[j] <= "~"):
                        j += 1
                    if j < n:
                        params = data[i+2:j]
                        final = data[j]
                        self.csi(params, final)
                        i = j + 1
                        continue
                    else:
                        # incomplete; stop
                        break
                elif i + 1 < n and data[i+1] == "]":
                    # OSC: skip to BEL or ST
                    j = i + 2
                    while j < n and data[j] not in ("\x07", "\x1b"):
                        j += 1
                    if j < n and data[j] == "\x1b" and j + 1 < n and data[j+1] == "\\":
                        i = j + 2
                        continue
                    elif j < n and data[j] == "\x07":
                        i = j + 1
                        continue
                    else:
                        break
                else:
                    # single-char escape
                    i += 2
                    continue
            else:
                # collect run of plain text up to next ESC
                j = i
                while j < n and data[j] != "\x1b":
                    j += 1
                self.write_text(data[i:j])
                i = j
        return True

def dump_frame(idx, frame, out, include_color=True, maxbuf=None):
    t, lines = frame
    print("== frame %d t=%.3f buffer_lines=%d ==" % (idx, t, len(lines)), file=out)
    screen_start = max(0, len(lines) - term.rows)
    for li, (text, color) in enumerate(lines):
        if maxbuf and li < len(lines) - maxbuf:
            continue
        if li == screen_start:
            print("--- screen (%d lines) ---" % term.rows, file=out)
        print(text.rstrip(), file=out)
        if include_color:
            print("    " + color.rstrip(), file=out)

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("raw", help="raw capture file")
    ap.add_argument("--rows", type=int, default=24)
    ap.add_argument("--cols", type=int, default=80)
    ap.add_argument("--frames", type=int, default=200,
                    help="max frames to dump (0 = all)")
    ap.add_argument("--every", type=int, default=1, help="dump every Nth frame")
    ap.add_argument("--maxbuf", type=int, default=0,
                    help="only show last N buffer lines in dumps")
    ap.add_argument("--out", default="")
    args = ap.parse_args()

    global term
    term = Term(args.rows, args.cols)
    data = open(args.raw, "rb").read().decode("utf-8", errors="replace")
    term.process(data)
    term.snapshot()  # final state

    out = open(args.out, "w") if args.out else sys.stdout
    total = len(term.frames)
    shown = 0
    for idx in range(0, total, args.every):
        if args.frames and shown >= args.frames:
            break
        dump_frame(idx, term.frames[idx], out, maxbuf=args.maxbuf)
        shown += 1
        if shown >= args.frames:
            break
    if args.frames and total > shown:
        print("... (%d more frames not shown)" % (total - shown), file=out)
    if out is not sys.stdout:
        out.close()

if __name__ == "__main__":
    main()
