#!/usr/bin/env bash
# Usage: tmux_repro.sh <name> <cmd...>
# Sends: ls (t=3), ls (t=14), resize 80->100 (t=24), done t=30
set -u
NAME="$1"; OUT="$2"; shift 2
tmux kill-session -t "$NAME" 2>/dev/null
tmux new-session -d -s "$NAME" -x 80 -y 24 "$*" 2>/dev/null
tmux pipe-pane -t "$NAME" -o "cat > '$OUT'"
sleep 3
tmux send-keys -t "$NAME" "ls" Enter
sleep 11
tmux send-keys -t "$NAME" "ls" Enter
sleep 10
tmux resize-window -t "$NAME" -x 100 -y 24
sleep 6
tmux pipe-pane -t "$NAME" >/dev/null 2>&1
sleep 0.5
tmux kill-session -t "$NAME" 2>/dev/null
echo "captured -> $OUT"
