#!/usr/bin/env bash
# Final verification: kmet in tmux, streaming + in-app scroll (mouse wheel).
# Measures the rewrite pattern while scrolled up during streaming — the
# flicker metric. With the ScrollView layout, the window freezes when
# scrolled up: frames while scrolled up must rewrite ~0-3 lines (spinner/
# status), not the whole window.
set -u
NAME="kmetverify"; OUT="$1"
PROMPT="Write a detailed essay about the history of computing, at least 1500 words. Keep going until finished."
tmux kill-session -t "$NAME" 2>/dev/null
tmux new-session -d -s "$NAME" -x 80 -y 14 "cd /data/data/com.termux/files/home/kmet && bb run" 2>/dev/null
tmux pipe-pane -t "$NAME" -o "cat > '$OUT'"
sleep 8
tmux send-keys -t "$NAME" "$PROMPT" Enter
# wait for streaming content to build up
sleep 25
# scroll up in-app with 4 wheel-up events
for i in 1 2 3 4; do
  tmux send-keys -t "$NAME" -l "$(printf '\033[<64;40;7M')"
  sleep 0.3
done
# keep streaming while scrolled up for 5s
sleep 5
# scroll back to bottom
for i in 1 2 3 4 5 6; do
  tmux send-keys -t "$NAME" -l "$(printf '\033[<65;40;7M')"
  sleep 0.3
done
sleep 5
tmux pipe-pane -t "$NAME" >/dev/null 2>&1
sleep 0.5
tmux kill-session -t "$NAME" 2>/dev/null
echo "captured -> $OUT"
