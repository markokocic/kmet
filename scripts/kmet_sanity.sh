#!/usr/bin/env bash
# Sanity: kmet starts, renders the ScrollView layout, responds to in-app
# wheel scroll (mouse mode), and exits cleanly.
set -u
NAME="kmetsanity"; OUT="$1"
tmux kill-session -t "$NAME" 2>/dev/null
tmux new-session -d -s "$NAME" -x 80 -y 10 "cd /data/data/com.termux/files/home/kmet && bb run" 2>/dev/null
tmux pipe-pane -t "$NAME" -o "cat > '$OUT'"
sleep 7
# 10-row terminal: the startup document overflows the scroll viewport.
# Wheel-up x5 should scroll the view up and show the scrollbar.
for i in 1 2 3 4 5; do
  tmux send-keys -t "$NAME" -l "$(printf '\033[<64;40;5M')"
  sleep 0.25
done
sleep 1
echo "=== after 5x wheel-up (10-row terminal) ==="
tmux capture-pane -t "$NAME" -p | sed 's/\x1b\[[0-9;?]*[a-zA-Z]//g'
# wheel-down back to bottom, then exit with ctrl+c twice
for i in 1 2 3 4 5 6; do
  tmux send-keys -t "$NAME" -l "$(printf '\033[<65;40;5M')"
  sleep 0.2
done
sleep 0.5
tmux send-keys -t "$NAME" C-c
sleep 0.5
tmux send-keys -t "$NAME" C-c
sleep 2
tmux pipe-pane -t "$NAME" >/dev/null 2>&1
sleep 0.5
tmux kill-session -t "$NAME" 2>/dev/null
echo "captured -> $OUT"
