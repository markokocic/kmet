#!/usr/bin/env bash
# Usage: tmux_capture.sh <session> <send-after> <text> <timeout> <outfile> <cmd...>
set -u
NAME="$1"; SEND_AFTER="$2"; TEXT="$3"; TIMEOUT="$4"; OUT="$5"; shift 5
tmux kill-session -t "$NAME" 2>/dev/null
tmux new-session -d -s "$NAME" -x 80 -y 24 "$*" 2>/dev/null
tmux pipe-pane -t "$NAME" -o "cat > '$OUT'"
sleep "$SEND_AFTER"
tmux send-keys -t "$NAME" "$TEXT" Enter
sleep "$TIMEOUT"
tmux pipe-pane -t "$NAME" >/dev/null 2>&1
sleep 0.5
tmux kill-session -t "$NAME" 2>/dev/null
echo "captured -> $OUT"
